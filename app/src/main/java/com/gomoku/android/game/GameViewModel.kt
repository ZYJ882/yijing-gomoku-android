package com.gomoku.android.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gomoku.android.ai.GomokuAi
import com.gomoku.android.network.LanConnectionState
import com.gomoku.android.network.LanEvent
import com.gomoku.android.network.LanMultiplayerManager
import com.gomoku.android.network.LanRole
import com.gomoku.android.network.LanRoom
import com.gomoku.android.network.LanUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

enum class GameStatus {
    PLAYING,
    BLACK_WON,
    WHITE_WON,
    DRAW,
}

enum class MatchPhase {
    SETUP,
    PLAYING,
    PAUSED,
    FINISHED,
}

enum class GameMode {
    AI,
    LAN,
}

enum class FirstMovePreference(val label: String) {
    RANDOM("随机先手"),
    PLAYER_FIRST("我先手"),
    AI_FIRST("AI 先手");

    fun localPlayer(randomFirstIsBlack: Boolean = Random.nextBoolean()): Int = when (this) {
        RANDOM -> if (randomFirstIsBlack) BLACK else WHITE
        PLAYER_FIRST -> BLACK
        AI_FIRST -> WHITE
    }
}

data class GameUiState(
    val board: IntArray = GomokuRules.createBoard(),
    val history: List<Move> = emptyList(),
    val currentPlayer: Int = BLACK,
    val localPlayer: Int = BLACK,
    val status: GameStatus = GameStatus.PLAYING,
    val phase: MatchPhase = MatchPhase.SETUP,
    val mode: GameMode = GameMode.AI,
    val difficulty: AiDifficulty = AiDifficulty.HARD,
    val firstMovePreference: FirstMovePreference = FirstMovePreference.RANDOM,
    val isAiThinking: Boolean = false,
    val lastMove: Move? = null,
    val aiDepth: Int = 0,
    val aiNodes: Int = 0,
    val lan: LanUiState = LanUiState(),
) {
    val isMyTurn: Boolean get() = currentPlayer == localPlayer
    val isMatchPlaying: Boolean get() = phase == MatchPhase.PLAYING
}

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val ai = GomokuAi()
    private val lanManager = LanMultiplayerManager(application.applicationContext)
    private var aiJob: Job? = null

    var uiState = androidx.compose.runtime.mutableStateOf(GameUiState())
        private set

    init {
        lanManager.eventListener = ::onLanEvent
    }

    fun onCellTapped(row: Int, col: Int) {
        val state = uiState.value
        if (!state.isMatchPlaying || state.status != GameStatus.PLAYING || state.isAiThinking || !state.isMyTurn) return
        if (state.mode == GameMode.LAN && !state.lan.isConnected) return
        if (!GomokuRules.isEmpty(state.board, row, col)) return

        applyMove(Move(row, col, state.localPlayer), sendToPeer = state.mode == GameMode.LAN)
    }

    fun setDifficulty(difficulty: AiDifficulty) {
        val state = uiState.value
        if (state.mode == GameMode.AI && state.phase == MatchPhase.SETUP) {
            uiState.value = state.copy(difficulty = difficulty)
        }
    }

    fun setFirstMovePreference(preference: FirstMovePreference) {
        val state = uiState.value
        if (state.mode == GameMode.AI && state.phase == MatchPhase.SETUP) {
            uiState.value = state.copy(firstMovePreference = preference)
        }
    }

    /** 仅在用户明确点击“开始对局”后进入人机棋局，先手规则由开始页选择。 */
    fun startMatch() {
        val state = uiState.value
        if (state.mode == GameMode.LAN) {
            startLanMatch(state)
            return
        }
        if (state.phase != MatchPhase.SETUP && state.phase != MatchPhase.FINISHED) return

        val localPlayer = state.firstMovePreference.localPlayer()
        val fresh = state.copy(
            board = GomokuRules.createBoard(),
            history = emptyList(),
            currentPlayer = BLACK,
            localPlayer = localPlayer,
            status = GameStatus.PLAYING,
            phase = MatchPhase.PLAYING,
            isAiThinking = false,
            lastMove = null,
            aiDepth = 0,
            aiNodes = 0,
        )
        uiState.value = fresh
        if (fresh.currentPlayer != fresh.localPlayer) requestAiMove(fresh.board, GomokuRules.opponent(fresh.localPlayer), fresh.difficulty)
    }

    fun togglePause() {
        val state = uiState.value
        when (state.phase) {
            MatchPhase.PLAYING -> {
                aiJob?.cancel()
                uiState.value = state.copy(phase = MatchPhase.PAUSED, isAiThinking = false)
                if (state.mode == GameMode.LAN) lanManager.sendPause(true)
            }
            MatchPhase.PAUSED -> {
                val resumed = state.copy(phase = MatchPhase.PLAYING)
                uiState.value = resumed
                if (resumed.mode == GameMode.LAN) {
                    lanManager.sendPause(false)
                } else if (resumed.currentPlayer != resumed.localPlayer) {
                    uiState.value = resumed.copy(isAiThinking = true, aiDepth = 0, aiNodes = 0)
                    requestAiMove(resumed.board, GomokuRules.opponent(resumed.localPlayer), resumed.difficulty)
                }
            }
            else -> Unit
        }
    }

    /** 结束对局并回到对应模式的开始页；局域网对局会同时断开房间。 */
    fun endMatch() {
        val state = uiState.value
        aiJob?.cancel()
        if (state.mode == GameMode.LAN) {
            lanManager.leave()
            uiState.value = GameUiState(mode = GameMode.LAN, difficulty = state.difficulty)
        } else {
            uiState.value = GameUiState(
                difficulty = state.difficulty,
                firstMovePreference = state.firstMovePreference,
            )
        }
    }

    fun openAiMode() {
        aiJob?.cancel()
        lanManager.leave()
        uiState.value = GameUiState(
            difficulty = uiState.value.difficulty,
            firstMovePreference = uiState.value.firstMovePreference,
        )
    }

    fun openLanLobby() {
        aiJob?.cancel()
        lanManager.leave()
        uiState.value = GameUiState(
            mode = GameMode.LAN,
            lan = LanUiState(),
            difficulty = uiState.value.difficulty,
            firstMovePreference = uiState.value.firstMovePreference,
        )
    }

    fun updateRoomName(roomName: String) {
        uiState.value = uiState.value.copy(lan = uiState.value.lan.copy(roomName = roomName.take(48)))
    }

    fun hostRoom() {
        val state = uiState.value
        if (state.mode != GameMode.LAN) return
        val reset = state.copy(
            board = GomokuRules.createBoard(),
            history = emptyList(),
            currentPlayer = BLACK,
            localPlayer = BLACK,
            status = GameStatus.PLAYING,
            phase = MatchPhase.SETUP,
            lastMove = null,
            isAiThinking = false,
            lan = state.lan.copy(
                connection = LanConnectionState.HOSTING,
                role = LanRole.HOST,
                message = "正在创建房间…",
            ),
        )
        uiState.value = reset
        lanManager.host(reset.lan.roomName)
    }

    fun scanRooms() {
        if (uiState.value.mode == GameMode.LAN) lanManager.startDiscovery()
    }

    fun joinRoom(room: LanRoom) {
        val state = uiState.value
        if (state.mode != GameMode.LAN) return
        uiState.value = state.copy(
            board = GomokuRules.createBoard(),
            history = emptyList(),
            currentPlayer = BLACK,
            localPlayer = WHITE,
            status = GameStatus.PLAYING,
            phase = MatchPhase.SETUP,
            lastMove = null,
            isAiThinking = false,
            lan = state.lan.copy(
                connection = LanConnectionState.CONNECTING,
                role = LanRole.GUEST,
                message = "正在加入 ${room.serviceName}…",
            ),
        )
        lanManager.join(room)
    }

    fun leaveLanRoom() = endMatch()

    fun undo() {
        val state = uiState.value
        if (state.mode == GameMode.LAN || !state.isMatchPlaying || state.history.isEmpty()) return
        aiJob?.cancel()

        var keptHistory = state.history
        do {
            keptHistory = keptHistory.dropLast(1)
        } while (keptHistory.isNotEmpty() && playerToMove(keptHistory) != state.localPlayer)

        val board = GomokuRules.createBoard()
        keptHistory.forEach { move -> board[GomokuRules.index(move.row, move.col)] = move.player }
        val current = playerToMove(keptHistory)
        val updated = state.copy(
            board = board,
            history = keptHistory,
            currentPlayer = current,
            status = GameStatus.PLAYING,
            phase = MatchPhase.PLAYING,
            isAiThinking = false,
            lastMove = keptHistory.lastOrNull(),
            aiDepth = 0,
            aiNodes = 0,
        )
        uiState.value = updated
        if (current != updated.localPlayer) {
            uiState.value = updated.copy(isAiThinking = true)
            requestAiMove(board, GomokuRules.opponent(updated.localPlayer), updated.difficulty)
        }
    }

    private fun startLanMatch(state: GameUiState) {
        if (!state.lan.isConnected || state.lan.role != LanRole.HOST) return
        val hostPlayer = randomPlayer()
        val fresh = state.copy(
            board = GomokuRules.createBoard(),
            history = emptyList(),
            currentPlayer = BLACK,
            localPlayer = hostPlayer,
            status = GameStatus.PLAYING,
            phase = MatchPhase.PLAYING,
            isAiThinking = false,
            lastMove = null,
            aiDepth = 0,
            aiNodes = 0,
            lan = state.lan.copy(message = "对局开始：你执${pieceName(hostPlayer)}棋"),
        )
        uiState.value = fresh
        lanManager.sendStart(hostPlayer)
    }

    private fun applyMove(move: Move, sendToPeer: Boolean) {
        val state = uiState.value
        if (!state.isMatchPlaying || move.player != state.currentPlayer || !GomokuRules.isEmpty(state.board, move.row, move.col)) return
        val nextBoard = state.board.copyOf()
        nextBoard[GomokuRules.index(move.row, move.col)] = move.player
        val status = calculateStatus(nextBoard, move)
        val nextPlayer = if (status == GameStatus.PLAYING) GomokuRules.opponent(move.player) else move.player
        val nextPhase = if (status == GameStatus.PLAYING) MatchPhase.PLAYING else MatchPhase.FINISHED
        uiState.value = state.copy(
            board = nextBoard,
            history = state.history + move,
            currentPlayer = nextPlayer,
            status = status,
            phase = nextPhase,
            lastMove = move,
        )
        if (sendToPeer) lanManager.sendMove(move)
        val updated = uiState.value
        if (updated.mode == GameMode.AI && updated.status == GameStatus.PLAYING && updated.currentPlayer != updated.localPlayer) {
            uiState.value = updated.copy(isAiThinking = true, aiDepth = 0, aiNodes = 0)
            requestAiMove(nextBoard, GomokuRules.opponent(updated.localPlayer), updated.difficulty)
        }
    }

    private fun requestAiMove(boardSnapshot: IntArray, aiPlayer: Int, difficulty: AiDifficulty) {
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.Default) {
                    ai.chooseMove(
                        board = boardSnapshot,
                        player = aiPlayer,
                        difficulty = difficulty,
                        shouldCancel = { Thread.currentThread().isInterrupted },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            ensureActive()

            val state = uiState.value
            if (state.mode != GameMode.AI || !state.isMatchPlaying || state.status != GameStatus.PLAYING || state.currentPlayer != aiPlayer) return@launch
            val selected = result?.move?.takeIf { GomokuRules.isEmpty(state.board, it.row, it.col) }
                ?: firstLegalMove(state.board, aiPlayer)
            if (selected == null) {
                uiState.value = state.copy(isAiThinking = false)
                return@launch
            }

            val nextBoard = state.board.copyOf()
            nextBoard[GomokuRules.index(selected.row, selected.col)] = aiPlayer
            val status = calculateStatus(nextBoard, selected)
            uiState.value = state.copy(
                board = nextBoard,
                history = state.history + selected,
                currentPlayer = if (status == GameStatus.PLAYING) GomokuRules.opponent(aiPlayer) else aiPlayer,
                status = status,
                phase = if (status == GameStatus.PLAYING) MatchPhase.PLAYING else MatchPhase.FINISHED,
                isAiThinking = false,
                lastMove = selected,
                aiDepth = result?.completedDepth ?: 0,
                aiNodes = result?.searchedNodes ?: 0,
            )
        }
    }

    private fun firstLegalMove(board: IntArray, player: Int): Move? {
        val center = BOARD_SIZE / 2
        if (GomokuRules.isEmpty(board, center, center)) return Move(center, center, player)
        for (index in board.indices) {
            if (board[index] == EMPTY) return Move(index / BOARD_SIZE, index % BOARD_SIZE, player)
        }
        return null
    }

    private fun onLanEvent(event: LanEvent) {
        val state = uiState.value
        when (event) {
            is LanEvent.StateChanged -> uiState.value = state.copy(
                lan = state.lan.copy(connection = event.connection, role = event.role, message = event.message),
            )
            is LanEvent.RoomsChanged -> uiState.value = state.copy(lan = state.lan.copy(rooms = event.rooms))
            LanEvent.PeerJoined -> uiState.value = state.copy(
                board = GomokuRules.createBoard(),
                history = emptyList(),
                currentPlayer = BLACK,
                status = GameStatus.PLAYING,
                phase = MatchPhase.SETUP,
                lastMove = null,
                lan = state.lan.copy(
                    connection = LanConnectionState.CONNECTED,
                    role = LanRole.HOST,
                    message = "对手已加入。点击“开始对局”后随机决定先手。",
                ),
            )
            is LanEvent.MatchStarted -> {
                if (state.lan.role != LanRole.GUEST) return
                val guestPlayer = GomokuRules.opponent(event.hostPlayer)
                uiState.value = state.copy(
                    board = GomokuRules.createBoard(),
                    history = emptyList(),
                    currentPlayer = BLACK,
                    localPlayer = guestPlayer,
                    status = GameStatus.PLAYING,
                    phase = MatchPhase.PLAYING,
                    isAiThinking = false,
                    lastMove = null,
                    lan = state.lan.copy(message = "对局开始：你执${pieceName(guestPlayer)}棋"),
                )
            }
            is LanEvent.RemoteMoveReceived -> {
                if (state.mode != GameMode.LAN || !state.lan.isConnected || !state.isMatchPlaying) return
                val remotePlayer = GomokuRules.opponent(state.localPlayer)
                if (event.move.player != remotePlayer || event.move.player != state.currentPlayer ||
                    !GomokuRules.isEmpty(state.board, event.move.row, event.move.col)
                ) {
                    uiState.value = state.copy(lan = state.lan.copy(message = "收到异常落子，已忽略"))
                    return
                }
                applyMove(event.move, sendToPeer = false)
            }
            is LanEvent.PauseChanged -> {
                if (state.mode != GameMode.LAN || state.phase == MatchPhase.FINISHED) return
                uiState.value = state.copy(
                    phase = if (event.paused) MatchPhase.PAUSED else MatchPhase.PLAYING,
                    lan = state.lan.copy(message = if (event.paused) "对手暂停了对局" else "对手恢复了对局"),
                )
            }
            LanEvent.PeerLeft -> uiState.value = state.copy(
                phase = MatchPhase.SETUP,
                lan = state.lan.copy(connection = LanConnectionState.IDLE, message = "对手已离开房间", rooms = emptyList()),
            )
            is LanEvent.Error -> uiState.value = state.copy(
                phase = MatchPhase.SETUP,
                lan = state.lan.copy(connection = LanConnectionState.ERROR, message = event.reason),
            )
        }
    }

    private fun calculateStatus(board: IntArray, move: Move): GameStatus = when {
        GomokuRules.isWinningMove(board, move) -> if (move.player == BLACK) GameStatus.BLACK_WON else GameStatus.WHITE_WON
        GomokuRules.isBoardFull(board) -> GameStatus.DRAW
        else -> GameStatus.PLAYING
    }

    private fun randomPlayer(): Int = if (Random.nextBoolean()) BLACK else WHITE

    private fun playerToMove(history: List<Move>): Int = if (history.size % 2 == 0) BLACK else WHITE

    private fun pieceName(player: Int): String = if (player == BLACK) "黑" else "白"

    override fun onCleared() {
        aiJob?.cancel()
        lanManager.release()
        super.onCleared()
    }
}
