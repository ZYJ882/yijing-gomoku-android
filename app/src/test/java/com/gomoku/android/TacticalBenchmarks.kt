package com.gomoku.android

import com.gomoku.android.game.BLACK
import com.gomoku.android.game.GomokuRules
import com.gomoku.android.game.WHITE

/**
 * 本项目自维护的可复现战术回归基准。
 *
 * 坐标使用 0 起始的 (row, col)。每题明确执子方和所有等价最佳着，
 * 以便不同引擎版本比较时不会把等价解误判为失败。
 */
internal data class TacticalBenchmark(
    val id: String,
    val description: String,
    val blackStones: List<Pair<Int, Int>>,
    val whiteStones: List<Pair<Int, Int>>,
    val toMove: Int,
    val acceptedMoves: Set<Pair<Int, Int>>,
)

internal object TacticalBenchmarks {
    val cases: List<TacticalBenchmark> = listOf(
        TacticalBenchmark(
            id = "win-horizontal-four",
            description = "横向四连的一手成五",
            blackStones = listOf(6 to 5),
            whiteStones = listOf(7 to 3, 7 to 4, 7 to 5, 7 to 6),
            toMove = WHITE,
            acceptedMoves = setOf(7 to 2, 7 to 7),
        ),
        TacticalBenchmark(
            id = "win-vertical-four",
            description = "纵向四连的一手成五",
            blackStones = listOf(6 to 6),
            whiteStones = listOf(3 to 7, 4 to 7, 5 to 7, 6 to 7),
            toMove = WHITE,
            acceptedMoves = setOf(2 to 7, 7 to 7),
        ),
        TacticalBenchmark(
            id = "win-diagonal-four",
            description = "主对角线四连的一手成五",
            blackStones = listOf(6 to 7),
            whiteStones = listOf(3 to 3, 4 to 4, 5 to 5, 6 to 6),
            toMove = WHITE,
            acceptedMoves = setOf(2 to 2, 7 to 7),
        ),
        TacticalBenchmark(
            id = "win-broken-four",
            description = "断四补点的一手成五",
            blackStones = listOf(6 to 6),
            whiteStones = listOf(7 to 3, 7 to 4, 7 to 6, 7 to 7),
            toMove = WHITE,
            acceptedMoves = setOf(7 to 5),
        ),
        TacticalBenchmark(
            id = "block-horizontal-four",
            description = "横向四连的必防",
            blackStones = listOf(7 to 3, 7 to 4, 7 to 5, 7 to 6),
            whiteStones = listOf(6 to 5),
            toMove = WHITE,
            acceptedMoves = setOf(7 to 2, 7 to 7),
        ),
        TacticalBenchmark(
            id = "block-vertical-four",
            description = "纵向四连的必防",
            blackStones = listOf(3 to 7, 4 to 7, 5 to 7, 6 to 7),
            whiteStones = listOf(6 to 6),
            toMove = WHITE,
            acceptedMoves = setOf(2 to 7, 7 to 7),
        ),
        TacticalBenchmark(
            id = "block-broken-four",
            description = "断四中间补点的必防",
            blackStones = listOf(7 to 3, 7 to 4, 7 to 6, 7 to 7),
            whiteStones = listOf(6 to 6),
            toMove = WHITE,
            acceptedMoves = setOf(7 to 5),
        ),
        TacticalBenchmark(
            id = "attack-open-four",
            description = "活三扩展为开放四双威胁",
            blackStones = listOf(6 to 5),
            whiteStones = listOf(7 to 4, 7 to 5, 7 to 6),
            toMove = WHITE,
            acceptedMoves = setOf(7 to 3, 7 to 7),
        ),
        TacticalBenchmark(
            id = "defend-open-four",
            description = "阻止对手把活三扩展为开放四",
            blackStones = listOf(7 to 4, 7 to 5, 7 to 6),
            whiteStones = listOf(6 to 5),
            toMove = WHITE,
            acceptedMoves = setOf(7 to 3, 7 to 7),
        ),
    )

    fun boardOf(case: TacticalBenchmark): IntArray = GomokuRules.createBoard().also { board ->
        case.blackStones.forEach { (row, col) -> board[GomokuRules.index(row, col)] = BLACK }
        case.whiteStones.forEach { (row, col) -> board[GomokuRules.index(row, col)] = WHITE }
    }
}
