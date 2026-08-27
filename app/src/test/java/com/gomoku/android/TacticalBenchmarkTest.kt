package com.gomoku.android

import com.gomoku.android.ai.GomokuAi
import com.gomoku.android.game.AiDifficulty
import com.gomoku.android.game.GomokuRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TacticalBenchmarkTest {
    @Test
    fun `master ai solves every fixed tactical benchmark`() {
        val failures = ArrayList<String>()
        TacticalBenchmarks.cases.forEach { case ->
            val board = TacticalBenchmarks.boardOf(case)
            val result = GomokuAi().chooseMove(board, case.toMove, AiDifficulty.MASTER)
            val move = result.move.row to result.move.col
            if (move !in case.acceptedMoves) {
                failures += "${case.id}: got $move, expected one of ${case.acceptedMoves}"
            }
        }
        assertTrue("残局题库评测失败：${failures.joinToString()}", failures.isEmpty())
    }

    @Test
    fun `normal ai retains immediate win and block benchmark coverage`() {
        val immediateCases = TacticalBenchmarks.cases.filter {
            it.id.startsWith("win-") || it.id.startsWith("block-")
        }
        immediateCases.forEach { case ->
            val board = TacticalBenchmarks.boardOf(case)
            val result = GomokuAi().chooseMove(board, case.toMove, AiDifficulty.NORMAL)
            assertTrue(
                "普通难度应保留 ${case.description} 的基础战术",
                (result.move.row to result.move.col) in case.acceptedMoves,
            )
        }
    }

    @Test
    fun `benchmark positions are legal and non-terminal`() {
        TacticalBenchmarks.cases.forEach { case ->
            val board = TacticalBenchmarks.boardOf(case)
            assertTrue("题库局面不应预先分出胜负：${case.id}", GomokuRules.findWinner(board) == null)
            case.blackStones.forEach { (row, col) ->
                assertEquals("黑棋坐标应被正确写入：${case.id}", 1, board[GomokuRules.index(row, col)])
            }
            case.whiteStones.forEach { (row, col) ->
                assertEquals("白棋坐标应被正确写入：${case.id}", 2, board[GomokuRules.index(row, col)])
            }
        }
    }
}
