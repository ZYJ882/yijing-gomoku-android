package com.gomoku.android.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.gomoku.android.game.BOARD_CELLS
import com.gomoku.android.game.Move
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 同一局域网双人对战连接层。
 *
 * 房主以 NSD/DNS-SD 发布一个 TCP 服务；加入方扫描、解析服务后建立一条 TCP 长连接。
 * 传输协议为按行分隔的 JSON。此类对战仅适用于可信的同一局域网，不能代替互联网账号
 * 与服务器校验机制。
 */
class LanMultiplayerManager(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val writeLock = Any()
    private val roomsByServiceName = linkedMapOf<String, LanRoom>()
    private val resolvingServices = mutableSetOf<String>()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var peerSocket: Socket? = null
    @Volatile private var isDiscovering = false
    @Volatile private var isRegistered = false
    @Volatile private var closedByUser = false
    private var advertisedServiceName: String? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    var eventListener: (LanEvent) -> Unit = {}

    fun host(roomName: String) {
        leave(announce = false)
        closedByUser = false
        executor.execute {
            try {
                val server = ServerSocket(0)
                server.reuseAddress = true
                serverSocket = server
                postState(LanConnectionState.HOSTING, LanRole.HOST, "房间已打开，正在发布到局域网…")
                registerRoom(roomName, server.localPort)

                val accepted = server.accept()
                if (closedByUser) {
                    accepted.close()
                    return@execute
                }
                peerSocket = accepted
                startReader(accepted)
                postState(LanConnectionState.CONNECTED, LanRole.HOST, "玩家已加入，你执黑棋先手")
                post(LanEvent.PeerJoined)
            } catch (error: Exception) {
                if (!closedByUser) post(LanEvent.Error("开房失败：${error.userMessage()}"))
            }
        }
    }

    fun startDiscovery() {
        stopDiscovery()
        closedByUser = false
        roomsByServiceName.clear()
        emitRooms()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                isDiscovering = true
                postState(LanConnectionState.DISCOVERING, LanRole.NONE, "正在扫描同一 Wi‑Fi 下的房间…")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE || serviceInfo.serviceName == advertisedServiceName) return
                resolveRoom(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                roomsByServiceName.remove(serviceInfo.serviceName)
                emitRooms()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
                tryStopDiscovery(this)
                post(LanEvent.Error("无法扫描房间（错误码 $errorCode）"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
            }
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            post(LanEvent.Error("无法启动房间扫描：${error.userMessage()}"))
        }
    }

    fun join(room: LanRoom) {
        stopDiscovery()
        closePeerOnly()
        closedByUser = false
        postState(LanConnectionState.CONNECTING, LanRole.GUEST, "正在加入 ${room.serviceName}…")
        executor.execute {
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(room.hostAddress, room.port), CONNECT_TIMEOUT_MS)
                if (closedByUser) {
                    socket.close()
                    return@execute
                }
                peerSocket = socket
                startReader(socket)
                postState(LanConnectionState.CONNECTED, LanRole.GUEST, "已连接，等待房主开始对局…")
                send(JSONObject().put("type", TYPE_JOIN))
            } catch (error: Exception) {
                if (!closedByUser) post(LanEvent.Error("加入失败：${error.userMessage()}"))
            }
        }
    }

    /** 房主点击开始后，将随机分配的房主棋色发送给加入方。 */
    fun sendStart(hostPlayer: Int) {
        send(JSONObject().put("type", TYPE_START).put("hostPlayer", hostPlayer))
    }

    fun sendPause(paused: Boolean) {
        send(JSONObject().put("type", TYPE_PAUSE).put("paused", paused))
    }

    fun sendMove(move: Move) {
        send(
            JSONObject()
                .put("type", TYPE_MOVE)
                .put("row", move.row)
                .put("col", move.col)
                .put("player", move.player),
        )
    }

    fun leave(announce: Boolean = true) {
        closedByUser = true
        if (announce) send(JSONObject().put("type", TYPE_LEAVE))
        stopDiscovery()
        unregisterRoom()
        closePeerOnly()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        } finally {
            serverSocket = null
        }
        advertisedServiceName = null
        postState(LanConnectionState.IDLE, LanRole.NONE, "在同一 Wi‑Fi 下创建或扫描房间")
    }

    fun release() {
        leave()
        executor.shutdownNow()
    }

    private fun registerRoom(roomName: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = roomName.trim().ifBlank { "五子棋房间" }.take(48)
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("app", "gomoku")
            setAttribute("version", "1")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                isRegistered = true
                advertisedServiceName = serviceInfo.serviceName
                postState(LanConnectionState.HOSTING, LanRole.HOST, "房间“${serviceInfo.serviceName}”已开放，等待玩家加入")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isRegistered = false
                post(LanEvent.Error("房间发布失败（错误码 $errorCode）"))
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                isRegistered = false
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isRegistered = false
            }
        }
        registrationListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun unregisterRoom() {
        val listener = registrationListener ?: return
        if (isRegistered) {
            try {
                nsdManager.unregisterService(listener)
            } catch (_: Exception) {
            }
        }
        isRegistered = false
        registrationListener = null
    }

    private fun resolveRoom(serviceInfo: NsdServiceInfo) {
        val name = serviceInfo.serviceName
        synchronized(resolvingServices) {
            if (!resolvingServices.add(name)) return
        }
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(resolvingServices) { resolvingServices.remove(name) }
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                synchronized(resolvingServices) { resolvingServices.remove(name) }
                val address = resolvedInfo.host?.hostAddress ?: return
                if (resolvedInfo.port <= 0 || resolvedInfo.serviceName == advertisedServiceName) return
                roomsByServiceName[resolvedInfo.serviceName] = LanRoom(
                    serviceName = resolvedInfo.serviceName,
                    hostAddress = address,
                    port = resolvedInfo.port,
                )
                emitRooms()
            }
        })
    }

    private fun startReader(socket: Socket) {
        executor.execute {
            try {
                BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).use { reader ->
                    while (!closedByUser) {
                        val line = reader.readLine() ?: break
                        handleIncoming(line)
                    }
                }
                if (!closedByUser) post(LanEvent.PeerLeft)
            } catch (error: SocketException) {
                if (!closedByUser) post(LanEvent.PeerLeft)
            } catch (error: Exception) {
                if (!closedByUser) post(LanEvent.Error("连接中断：${error.userMessage()}"))
            } finally {
                closePeerOnly()
            }
        }
    }

    private fun handleIncoming(raw: String) {
        try {
            val message = JSONObject(raw)
            when (message.optString("type")) {
                TYPE_START -> post(LanEvent.MatchStarted(message.getInt("hostPlayer")))
                TYPE_MOVE -> post(
                    LanEvent.RemoteMoveReceived(
                        Move(message.getInt("row"), message.getInt("col"), message.getInt("player")),
                    ),
                )
                TYPE_PAUSE -> post(LanEvent.PauseChanged(message.getBoolean("paused")))
                TYPE_LEAVE -> post(LanEvent.PeerLeft)
            }
        } catch (error: Exception) {
            post(LanEvent.Error("收到无效对局消息：${error.userMessage()}"))
        }
    }

    private fun send(message: JSONObject) {
        val socket = peerSocket ?: return
        executor.execute {
            try {
                synchronized(writeLock) {
                    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                    writer.write(message.toString())
                    writer.newLine()
                    writer.flush()
                }
            } catch (error: Exception) {
                if (!closedByUser) post(LanEvent.Error("消息发送失败：${error.userMessage()}"))
            }
        }
    }

    private fun stopDiscovery() {
        val listener = discoveryListener ?: return
        if (isDiscovering) tryStopDiscovery(listener)
        discoveryListener = null
        isDiscovering = false
    }

    private fun tryStopDiscovery(listener: NsdManager.DiscoveryListener) {
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (_: Exception) {
        }
    }

    private fun closePeerOnly() {
        try {
            peerSocket?.close()
        } catch (_: Exception) {
        } finally {
            peerSocket = null
        }
    }

    private fun emitRooms() = post(LanEvent.RoomsChanged(roomsByServiceName.values.sortedBy { it.serviceName }))

    private fun postState(connection: LanConnectionState, role: LanRole, message: String) =
        post(LanEvent.StateChanged(connection, role, message))

    private fun post(event: LanEvent) {
        mainHandler.post { eventListener(event) }
    }

    private fun Exception.userMessage(): String = message?.take(80) ?: javaClass.simpleName

    private companion object {
        const val SERVICE_TYPE = "_gomoku-android._tcp."
        const val CONNECT_TIMEOUT_MS = 5_000
        const val TYPE_JOIN = "join"
        const val TYPE_START = "start"
        const val TYPE_MOVE = "move"
        const val TYPE_PAUSE = "pause"
        const val TYPE_LEAVE = "leave"
    }
}
