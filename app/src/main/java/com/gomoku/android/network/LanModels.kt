package com.gomoku.android.network

import com.gomoku.android.game.Move

enum class LanConnectionState {
    IDLE,
    DISCOVERING,
    HOSTING,
    CONNECTING,
    CONNECTED,
    ERROR,
}

enum class LanRole {
    NONE,
    HOST,
    GUEST,
}

data class LanRoom(
    val serviceName: String,
    val hostAddress: String,
    val port: Int,
)

data class LanUiState(
    val connection: LanConnectionState = LanConnectionState.IDLE,
    val role: LanRole = LanRole.NONE,
    val rooms: List<LanRoom> = emptyList(),
    val roomName: String = "我的五子棋房间",
    val message: String = "在同一 Wi‑Fi 下创建或扫描房间",
) {
    val isLobby: Boolean get() = connection != LanConnectionState.CONNECTED
    val isConnected: Boolean get() = connection == LanConnectionState.CONNECTED
}

sealed interface LanEvent {
    data class StateChanged(
        val connection: LanConnectionState,
        val role: LanRole,
        val message: String,
    ) : LanEvent

    data class RoomsChanged(val rooms: List<LanRoom>) : LanEvent
    data object PeerJoined : LanEvent
    data class MatchStarted(val hostPlayer: Int) : LanEvent
    data class RemoteMoveReceived(val move: Move) : LanEvent
    data class PauseChanged(val paused: Boolean) : LanEvent
    data object PeerLeft : LanEvent
    data class Error(val reason: String) : LanEvent
}
