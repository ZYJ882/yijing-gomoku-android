package com.gomoku.android

import com.gomoku.android.ai.GomokuAi
import com.gomoku.android.game.AiDifficulty
import com.gomoku.android.game.BLACK
import com.gomoku.android.game.BoardTapResolver
import com.gomoku.android.game.FirstMovePreference
import com.gomoku.android.game.GomokuRules
import com.gomoku.android.game.Move
import com.gomoku.android.game.WHITE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GomokuCoreTest {
    @Test
    fun `five consecutive black stones wins`() {
        val board = GomokuRules.createBoard()
        repeat(5) { col -> board[GomokuRules.index(7, col + 3)] = BLACK }

        assertTrue(GomokuRules.isWinningMove(board, Move(7, 7, BLACK)))
        assertEquals(BLACK, GomokuRules.findWinner(board))
    }

    @Test
    fun `six consecutive stones also wins under freestyle rule`() {
        val board = GomokuRules.createBoard()
        repeat(6) { col -> board[GomokuRules.index(5, col + 2)] = WHITE }

        assertTrue(GomokuRules.isWinningMove(board, Move(5, 7, WHITE)))
        assertEquals(WHITE, GomokuRules.findWinner(board))
    }

    @Test
    fun `opening book takes the center on an empty board`() {
        val result = GomokuAi().chooseMove(GomokuRules.createBoard(), BLACK, AiDifficulty.MASTER)

        assertEquals(Move(7, 7, BLACK), result.move)
    }

    @Test
    fun `opening book answers a centered opening with a balanced reply`() {
        val board = GomokuRules.createBoard()
        board[GomokuRules.index(7, 7)] = BLACK

        val result = GomokuAi().chooseMove(board, WHITE, AiDifficulty.MASTER)

        assertEquals(Move(5, 7, WHITE), result.move)
    }

    @Test
    fun `immediate win takes priority over opening book`() {
        val board = GomokuRules.createBoard()
        board[GomokuRules.index(7, 7)] = BLACK
        repeat(4) { col -> board[GomokuRules.index(6, col + 3)] = WHITE }

        val result = GomokuAi().chooseMove(board, WHITE, AiDifficulty.MASTER)

        assertTrue("开局库不得覆盖一手成五", result.move.row == 6 && (result.move.col == 2 || result.move.col == 7))
    }

    @Test
    fun `immediate block takes priority over opening book`() {
        val board = GomokuRules.createBoard()
        board[GomokuRules.index(7, 7)] = BLACK
        repeat(4) { col -> board[GomokuRules.index(6, col + 3)] = BLACK }

        val result = GomokuAi().chooseMove(board, WHITE, AiDifficulty.MASTER)

        assertTrue("开局库不得覆盖对手一手胜负", result.move.row == 6 && (result.move.col == 2 || result.move.col == 7))
    }

    @Test
    fun `easy ai returns a legal move with a lighter budget`() {
        val board = GomokuRules.createBoard()
        board[GomokuRules.index(7, 7)] = BLACK

        val result = GomokuAi().chooseMove(board, WHITE, AiDifficulty.EASY)

        assertTrue(GomokuRules.isInside(result.move.row, result.move.col))
        assertEquals(0, board[GomokuRules.index(result.move.row, result.move.col)])
        assertTrue("新手档应使用轻量思考预算", AiDifficulty.EASY.timeBudgetMs <= 30L)
    }

    @Test
    fun `ai blocks an opponent immediate five`() {
        val board = GomokuRules.createBoard()
        repeat(4) { col -> board[GomokuRules.index(7, col + 3)] = BLACK }
        board[GomokuRules.index(6, 5)] = WHITE

        val result = GomokuAi().chooseMove(board, WHITE, AiDifficulty.NORMAL)

        assertEquals(WHITE, result.move.player)
        assertTrue(
            "AI 应封堵黑棋四连的一端",
            (result.move.row == 7 && result.move.col == 2) || (result.move.row == 7 && result.move.col == 7),
        )
    }

    @Test
    fun `tap near the right side of a grid cell resolves to the nearest intersection`() {
        val edge = 1_000f
        val padding = edge * 0.065f
        val cell = (edge - 2 * padding) / 14f
        val resolved = BoardTapResolver.resolve(
            x = padding + 2.70f * cell,
            y = padding + 7f * cell,
            width = edge,
            height = edge,
        )

        assertEquals(7, resolved?.row)
        assertEquals(3, resolved?.col)
    }

    @Test
    fun `first move preferences assign the expected local chess color`() {
        assertEquals(BLACK, FirstMovePreference.PLAYER_FIRST.localPlayer(randomFirstIsBlack = false))
        assertEquals(WHITE, FirstMovePreference.AI_FIRST.localPlayer(randomFirstIsBlack = true))
        assertEquals(BLACK, FirstMovePreference.RANDOM.localPlayer(randomFirstIsBlack = true))
        assertEquals(WHITE, FirstMovePreference.RANDOM.localPlayer(randomFirstIsBlack = false))
    }

    @Test
    fun `ai takes an immediate winning move`() {
        val board = GomokuRules.createBoard()
        repeat(4) { col -> board[GomokuRules.index(8, col + 3)] = WHITE }
        board[GomokuRules.index(6, 5)] = BLACK

        val result = GomokuAi().chooseMove(board, WHITE, AiDifficulty.NORMAL)

        assertTrue(
            "AI 应立即完成五连",
            (result.move.row == 8 && result.move.col == 2) || (result.move.row == 8 && result.move.col == 7),
        )
    }

    @Test
    fun `ai fills the gap of a broken four to win immediately`() {
        val board = GomokuRules.createBoard()
        listOf(3, 4, 6, 7).forEach { col -> board[GomokuRules.index(7, col)] = WHITE }
        board[GomokuRules.index(6, 6)] = BLACK

        val result = GomokuAi().chooseMove(board, WHITE, AiDifficulty.HARD)

        assertEquals(Move(7, 5, WHITE), result.move)
    }

    @Test
    fun `ai blocks the gap of an opponent broken four`() {
        val board = GomokuRules.createBoard()
        listOf(3, 4, 6, 7).forEach { col -> board[GomokuRules.index(7, col)] = BLACK }
        board[GomokuRules.index(6, 6)] = WHITE

        val result = GomokuAi().chooseMove(board, WHITE, AiDifficulty.HARD)

        assertEquals(Move(7, 5, WHITE), result.move)
    }

    @Test
    fun `hard ai starts an open four double threat`() {
        val board = GomokuRules.createBoard()
        listOf(4, 5, 6).forEach { col -> board[GomokuRules.index(7, col)] = WHITE }
        board[GomokuRules.index(6, 5)] = BLACK

        val result = GomokuAi().chooseMove(board, WHITE, AiDifficulty.HARD)

        assertTrue(
            "AI 应走出开放四，制造两端均可成五的双威胁",
            result.move.row == 7 && (result.move.col == 3 || result.move.col == 7),
        )
    }

    @Test
    fun `hard ai blocks an opponent open four double threat`() {
        val board = GomokuRules.createBoard()
        listOf(4, 5, 6).forEach { col -> board[GomokuRules.index(7, col)] = BLACK }
        board[GomokuRules.index(6, 5)] = WHITE

        val result = GomokuAi().chooseMove(board, WHITE, AiDifficulty.HARD)

        assertTrue(
            "AI 应抢占黑棋形成开放四的任一关键点",
            result.move.row == 7 && (result.move.col == 3 || result.move.col == 7),
        )
    }

    @Test
    fun `master ai returns a legal move within a guarded midgame budget`() {
        val board = GomokuRules.createBoard()
        val black = listOf(7 to 7, 6 to 6, 8 to 8, 7 to 5, 5 to 8, 9 to 6)
        val white = listOf(7 to 6, 6 to 7, 8 to 7, 7 to 8, 5 to 7, 9 to 7)
        black.forEach { (row, col) -> board[GomokuRules.index(row, col)] = BLACK }
        white.forEach { (row, col) -> board[GomokuRules.index(row, col)] = WHITE }

        val startedAt = System.nanoTime()
        val result = GomokuAi().chooseMove(board, BLACK, AiDifficulty.MASTER)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(GomokuRules.isInside(result.move.row, result.move.col))
        assertEquals(0, board[GomokuRules.index(result.move.row, result.move.col)])
        assertTrue("受控威胁搜索应在移动端保护时间内返回", elapsedMs < 3_000)
    }
}
