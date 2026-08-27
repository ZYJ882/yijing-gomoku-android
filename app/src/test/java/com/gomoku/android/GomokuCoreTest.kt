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
}
