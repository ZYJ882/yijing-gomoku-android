package com.gomoku.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gomoku.android.game.AiDifficulty
import com.gomoku.android.game.BLACK
import com.gomoku.android.game.BoardTapResolver
import com.gomoku.android.game.BOARD_SIZE
import com.gomoku.android.game.EMPTY
import com.gomoku.android.game.GameMode
import com.gomoku.android.game.FirstMovePreference
import com.gomoku.android.game.MatchPhase
import com.gomoku.android.game.GameStatus
import com.gomoku.android.game.GameUiState
import com.gomoku.android.game.GameViewModel
import com.gomoku.android.game.GomokuRules
import com.gomoku.android.game.WHITE
import com.gomoku.android.network.LanConnectionState
import com.gomoku.android.network.LanRoom
import kotlin.math.abs
import java.util.Locale
import kotlin.math.min

private val Ink = Color(0xFF12213A)
private val Night = Color(0xFF0E172A)
private val Muted = Color(0xFF8190A8)
private val BoardWood = Color(0xFFE7B96A)
private val BoardLine = Color(0xFF694D29)
private val Accent = Color(0xFF4CC9A3)

@Composable
fun GomokuApp(viewModel: GameViewModel = viewModel()) {
    val state by viewModel.uiState
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionDenied = false
            viewModel.openLanLobby()
        } else {
            permissionDenied = true
        }
    }

    GomokuTheme {
        Surface(color = Night, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF0E172A), Color(0xFF182A47))))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 18.dp),
            ) {
                AppHeader(state.mode)
                Spacer(Modifier.height(15.dp))
                ModeSelector(
                    mode = state.mode,
                    onAiMode = viewModel::openAiMode,
                    onLanMode = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                        } else {
                            viewModel.openLanLobby()
                        }
                    },
                )
                if (permissionDenied) {
                    Spacer(Modifier.height(10.dp))
                    PermissionHint()
                }
                Spacer(Modifier.height(16.dp))

                when {
                    state.mode == GameMode.AI && state.phase == MatchPhase.SETUP -> AiStartScreen(state, viewModel)
                    state.mode == GameMode.LAN && state.lan.isLobby -> LanLobby(state, viewModel)
                    state.mode == GameMode.LAN && state.phase == MatchPhase.SETUP -> LanStartScreen(state, viewModel)
                    else -> MatchScreen(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun AppHeader(mode: GameMode) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("弈", color = Ink, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("弈境", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(if (mode == GameMode.AI) "五子棋 · 本地强 AI" else "五子棋 · 同一 Wi‑Fi 实时对弈", color = Color(0xFFAEBBD0), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.weight(1f))
        AssistChip(
            onClick = {},
            label = { Text(if (mode == GameMode.AI) "AI" else "LAN") },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = Color(0xFF203553),
                labelColor = Accent,
            ),
            border = null,
        )
    }
}

@Composable
private fun ModeSelector(mode: GameMode, onAiMode: () -> Unit, onLanMode: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(Color(0xFF142541))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ModeTab("人机对战", selected = mode == GameMode.AI, modifier = Modifier.weight(1f), onClick = onAiMode)
        ModeTab("局域网对战", selected = mode == GameMode.LAN, modifier = Modifier.weight(1f), onClick = onLanMode)
    }
}

@Composable
private fun ModeTab(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Accent else Color.Transparent,
            contentColor = if (selected) Ink else Color(0xFFB9C5D7),
        ),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun PermissionHint() {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF4A2D32)), shape = RoundedCornerShape(15.dp)) {
        Text(
            text = "局域网扫描需要“附近 Wi‑Fi 设备”权限。请在系统设置中允许后重试；人机对战不受影响。",
            color = Color(0xFFFFD7DA),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun AiStartScreen(state: GameUiState, viewModel: GameViewModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF162843)),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF214460)),
                contentAlignment = Alignment.Center,
            ) {
                Text("弈", color = Accent, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(16.dp))
            Text("静候落子", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text("选择难度和先手规则后，点击开始进入对局。", color = Color(0xFFB9C7D9), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            DifficultySection(selected = state.difficulty, enabled = true, onSelected = viewModel::setDifficulty)
            Spacer(Modifier.height(20.dp))
            FirstMoveSection(selected = state.firstMovePreference, onSelected = viewModel::setFirstMovePreference)
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = viewModel::startMatch,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
            ) {
                Text("开始对局", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun LanStartScreen(state: GameUiState, viewModel: GameViewModel) {
    val isHost = state.lan.role == com.gomoku.android.network.LanRole.HOST
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF162843)),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("双方已就绪", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                if (isHost) "点击开始对局后，将随机决定双方的先手棋色。" else "等待房主点击“开始对局”。先手棋色将随机决定。",
                color = Color(0xFFB9C7D9),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = viewModel::startMatch,
                enabled = isHost,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink, disabledContainerColor = Color(0xFF2A3E59)),
            ) {
                Text(if (isHost) "开始对局" else "等待房主开始", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = viewModel::endMatch) { Text("离开房间", color = Accent) }
        }
    }
}

@Composable
private fun LanLobby(state: GameUiState, viewModel: GameViewModel) {
    val lan = state.lan
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF162843)),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("局域网房间", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(lan.message, color = Color(0xFFB7C4D7), style = MaterialTheme.typography.bodyMedium)

            if (lan.connection == LanConnectionState.HOSTING) {
                Spacer(Modifier.height(18.dp))
                WaitingForPeer(onLeave = viewModel::leaveLanRoom)
                return@Column
            }

            Spacer(Modifier.height(16.dp))
            Text("创建房间", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = lan.roomName,
                onValueChange = viewModel::updateRoomName,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("房间名称") },
                singleLine = true,
                colors = GomokuTextFieldColors(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = viewModel::hostRoom,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
            ) {
                Text("开房并等待玩家加入", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("可加入的房间", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("只显示当前同一 Wi‑Fi 下的房间", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = viewModel::scanRooms,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.65f)),
                ) {
                    Text(if (lan.connection == LanConnectionState.DISCOVERING) "扫描中" else "扫描房间")
                }
            }
            Spacer(Modifier.height(9.dp))
            if (lan.rooms.isEmpty()) {
                EmptyRoomList(isScanning = lan.connection == LanConnectionState.DISCOVERING)
            } else {
                lan.rooms.forEach { room ->
                    RoomRow(room = room, onJoin = { viewModel.joinRoom(room) })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    LanSecurityNote()
}

@Composable
private fun WaitingForPeer(onLeave: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1C3B50)), shape = RoundedCornerShape(16.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Accent))
            Spacer(Modifier.height(10.dp))
            Text("房间已开放", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("请让对方点击“扫描房间”后加入。", color = Color(0xFFC0D4E3), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onLeave) { Text("关闭房间", color = Accent) }
        }
    }
}

@Composable
private fun EmptyRoomList(isScanning: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(105.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF10213B)),
    ) {
        Text(
            if (isScanning) "正在发现同一 Wi‑Fi 下的房间…" else "暂无房间，点击“扫描房间”刷新。",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun RoomRow(room: LanRoom, onJoin: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(Color(0xFF1B304E))
            .padding(start = 13.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(Accent))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(room.serviceName, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${room.hostAddress}:${room.port}", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        Button(
            onClick = onJoin,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
            contentPadding = ButtonDefaults.ContentPadding,
        ) { Text("加入") }
    }
}

@Composable
private fun LanSecurityNote() {
    Text(
        "提示：仅在可信的同一局域网中开房和加入。若路由器开启了客户端隔离，设备可能互相无法发现。",
        color = Color(0xFF9AA9BE),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun MatchScreen(state: GameUiState, viewModel: GameViewModel) {
    GameStatusCard(state)
    Spacer(Modifier.height(16.dp))
    GomokuBoard(
        board = state.board,
        lastMoveRow = state.lastMove?.row,
        lastMoveCol = state.lastMove?.col,
        enabled = state.isMatchPlaying && state.status == GameStatus.PLAYING && !state.isAiThinking && state.isMyTurn &&
            (state.mode == GameMode.AI || state.lan.isConnected),
        onCellTapped = viewModel::onCellTapped,
    )
    Spacer(Modifier.height(16.dp))
    AnimatedVisibility(visible = state.phase == MatchPhase.PAUSED) {
        PauseNotice(onResume = viewModel::togglePause)
    }
    if (state.phase == MatchPhase.PAUSED) Spacer(Modifier.height(12.dp))
    AnimatedVisibility(visible = state.phase == MatchPhase.FINISHED) {
        ResultCard(status = state.status, localPlayer = state.localPlayer, onFinish = viewModel::endMatch)
    }
    if (state.phase == MatchPhase.FINISHED) Spacer(Modifier.height(12.dp))

    if (state.phase == MatchPhase.PLAYING || state.phase == MatchPhase.PAUSED) {
        InMatchActions(
            isPaused = state.phase == MatchPhase.PAUSED,
            canUndo = state.mode == GameMode.AI && state.history.isNotEmpty() && !state.isAiThinking,
            onPauseToggle = viewModel::togglePause,
            onUndo = viewModel::undo,
            onEnd = viewModel::endMatch,
        )
    }
    Spacer(Modifier.height(9.dp))
    Text(
        text = if (state.mode == GameMode.AI) "自由规则 · 15 × 15 · ${state.firstMovePreference.label}" else "局域网直连 · 每局随机先手 · 双方实时同步落子",
        color = Color(0xFF9BA9BD),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun PauseNotice(onResume: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF25415F)), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("对局已暂停", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onResume) { Text("继续对局", color = Accent) }
        }
    }
}

@Composable
private fun InMatchActions(
    isPaused: Boolean,
    canUndo: Boolean,
    onPauseToggle: () -> Unit,
    onUndo: () -> Unit,
    onEnd: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onPauseToggle, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD7E0ED))) {
            Text(if (isPaused) "继续对局" else "暂停")
        }
        Button(onClick = onEnd, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)) {
            Text("结束对局", fontWeight = FontWeight.Bold)
        }
    }
    if (canUndo) {
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onUndo, modifier = Modifier.fillMaxWidth()) { Text("悔棋", color = Color(0xFFB8C8DE)) }
    }
}

@Composable
private fun GameStatusCard(state: GameUiState) {
    val (title, subtitle, dotColor) = when {
        state.phase == MatchPhase.PAUSED -> Triple("对局已暂停", "点击“继续对局”恢复落子", Color(0xFFAEBBD0))
        state.phase == MatchPhase.FINISHED && state.status == GameStatus.BLACK_WON -> Triple("本局结束", "黑棋获胜", Color(0xFFF2C56E))
        state.phase == MatchPhase.FINISHED && state.status == GameStatus.WHITE_WON -> Triple("本局结束", "白棋获胜", Accent)
        state.phase == MatchPhase.FINISHED && state.status == GameStatus.DRAW -> Triple("本局结束", "棋盘已满，平局", Color(0xFFAEBBD0))
        state.mode == GameMode.LAN && state.lan.isConnected && state.isMyTurn ->
            Triple("轮到你落子", "你执${pieceName(state.localPlayer)}棋", if (state.localPlayer == BLACK) Color(0xFFF2C56E) else Accent)
        state.mode == GameMode.LAN && state.lan.isConnected ->
            Triple("等待对手落子", "对手执${pieceName(GomokuRules.opponent(state.localPlayer))}棋", Accent)
        state.isAiThinking -> Triple("AI 正在计算", "正在检查威胁与最佳应手", Accent)
        else -> Triple("轮到你落子", "你执${pieceName(state.localPlayer)}棋", if (state.localPlayer == BLACK) Color(0xFFF2C56E) else Accent)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF162843)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color(0xFFB8C5D7), style = MaterialTheme.typography.bodySmall)
            }
            if (state.isAiThinking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Accent,
                    strokeWidth = 2.5.dp,
                )
            } else if (state.mode == GameMode.AI && state.aiNodes > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("深度 ${state.aiDepth}", color = Accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text("${formatNodes(state.aiNodes)} 节点", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun GomokuBoard(
    board: IntArray,
    lastMoveRow: Int?,
    lastMoveCol: Int?,
    enabled: Boolean,
    onCellTapped: (Int, Int) -> Unit,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val corner = RoundedCornerShape(24.dp)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(corner)
            .background(BoardWood)
            .border(2.dp, Color(0xFFFADE9D), corner)
            .semantics {
                val stones = board.count { it != EMPTY }
                contentDescription = "五子棋棋盘，15乘15，已有${stones}步，${if (enabled) "当前可以落子" else "当前不可落子"}"
            }
            .onSizeChanged { canvasSize = it }
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures { point ->
                        BoardTapResolver.resolve(
                            x = point.x,
                            y = point.y,
                            width = canvasSize.width.toFloat(),
                            height = canvasSize.height.toFloat(),
                        )?.let { cell -> onCellTapped(cell.row, cell.col) }
                    }
                }
            },
    ) {
        val edge = min(size.width, size.height)
        val padding = edge * 0.065f
        val cell = (edge - 2 * padding) / (BOARD_SIZE - 1)
        val lineEnd = padding + cell * (BOARD_SIZE - 1)
        for (i in 0 until BOARD_SIZE) {
            val coordinate = padding + cell * i
            drawLine(BoardLine.copy(alpha = 0.70f), Offset(padding, coordinate), Offset(lineEnd, coordinate), 1.35.dp.toPx())
            drawLine(BoardLine.copy(alpha = 0.70f), Offset(coordinate, padding), Offset(coordinate, lineEnd), 1.35.dp.toPx())
        }
        listOf(3 to 3, 3 to 11, 7 to 7, 11 to 3, 11 to 11).forEach { (row, col) ->
            drawCircle(BoardLine, radius = cell * 0.105f, center = Offset(padding + col * cell, padding + row * cell))
        }
        board.forEachIndexed { index, player ->
            if (player == EMPTY) return@forEachIndexed
            val row = index / BOARD_SIZE
            val col = index % BOARD_SIZE
            val center = Offset(padding + col * cell, padding + row * cell)
            val radius = cell * 0.405f
            if (player == BLACK) {
                drawCircle(Color(0xFF0A1220), radius, center)
                drawCircle(Brush.radialGradient(listOf(Color(0xFF4B5D78), Color(0xFF121B2B)), center, radius), radius * 0.87f, center)
            } else {
                drawCircle(Color(0xFFAAB5C4), radius, center)
                drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFD6DFE9)), center, radius), radius * 0.87f, center)
            }
            if (row == lastMoveRow && col == lastMoveCol) drawCircle(Accent, radius * 0.20f, center, style = Stroke(width = maxOf(1.5.dp.toPx(), radius * 0.10f)))
        }
    }
}

@Composable
private fun DifficultySection(selected: AiDifficulty, enabled: Boolean, onSelected: (AiDifficulty) -> Unit) {
    Text("AI 难度", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AiDifficulty.entries.forEach { difficulty ->
            FilterChip(
                selected = selected == difficulty,
                onClick = { onSelected(difficulty) },
                enabled = enabled,
                label = { Text(difficulty.label) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Ink, containerColor = Color(0xFF1A2C49), labelColor = Color(0xFFCBD6E6)),
                border = FilterChipDefaults.filterChipBorder(borderColor = Color(0xFF38506F), selectedBorderColor = Accent, enabled = enabled, selected = selected == difficulty),
            )
        }
    }
}

@Composable
private fun FirstMoveSection(selected: FirstMovePreference, onSelected: (FirstMovePreference) -> Unit) {
    Text("先手设置", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FirstMovePreference.entries.forEach { preference ->
            FilterChip(
                selected = selected == preference,
                onClick = { onSelected(preference) },
                label = { Text(preference.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Accent,
                    selectedLabelColor = Ink,
                    containerColor = Color(0xFF1A2C49),
                    labelColor = Color(0xFFCBD6E6),
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = Color(0xFF38506F),
                    selectedBorderColor = Accent,
                    enabled = true,
                    selected = selected == preference,
                ),
            )
        }
    }
}

@Composable
private fun ActionRow(primaryText: String, canUndo: Boolean, onUndo: () -> Unit, onPrimary: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD7E0ED))) { Text("悔棋") }
        Button(onClick = onPrimary, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)) { Text(primaryText, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun LanGameActions(onRestart: () -> Unit, onLeave: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onLeave, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD7E0ED))) { Text("离开房间") }
        Button(onClick = onRestart, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)) { Text("双方重新开始", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ResultCard(status: GameStatus, localPlayer: Int, onFinish: () -> Unit) {
    val message = when (status) {
        GameStatus.BLACK_WON -> if (localPlayer == BLACK) "你赢了，漂亮的五连！" else "黑棋获胜，本局结束。"
        GameStatus.WHITE_WON -> if (localPlayer == WHITE) "你赢了，漂亮的五连！" else "白棋获胜，本局结束。"
        GameStatus.DRAW -> "平局。棋盘已没有空位。"
        GameStatus.PLAYING -> return
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF214460)), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 11.dp, bottom = 11.dp)) {
            Text(message, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onFinish) { Text("返回开始页", color = Accent) }
        }
    }
}

@Composable
private fun GomokuTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Accent,
    unfocusedBorderColor = Color(0xFF49627E),
    focusedLabelColor = Accent,
    unfocusedLabelColor = Color(0xFFAAB8CC),
    cursorColor = Accent,
)

private fun pieceName(player: Int): String = if (player == BLACK) "黑" else "白"

private fun formatNodes(nodes: Int): String = when {
    nodes >= 10_000 -> String.format(Locale.US, "%.1f万", nodes / 10_000f)
    nodes >= 1_000 -> String.format(Locale.US, "%.1fk", nodes / 1_000f)
    else -> nodes.toString()
}

@Composable
private fun GomokuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Ink,
            background = Night,
            surface = Color(0xFF162843),
            onSurface = Color.White,
        ),
        content = content,
    )
}
