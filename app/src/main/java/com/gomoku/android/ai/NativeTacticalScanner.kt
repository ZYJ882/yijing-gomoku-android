package com.gomoku.android.ai

import com.gomoku.android.game.BLACK
import com.gomoku.android.game.BOARD_CELLS
import com.gomoku.android.game.BOARD_SIZE
import com.gomoku.android.game.EMPTY
import com.gomoku.android.game.GomokuRules
import com.gomoku.android.game.WHITE

/**
 * NDK 战术扫描桥接。
 *
 * 原生库仅承担热点且确定性的“一手成五”扫描，Kotlin 搜索、规则和 UI 保持不变。
 * 若设备 ABI、加载或 JNI 调用异常，调用方会收到 null 并无缝回退至 Kotlin 扫描。
 */
internal object NativeTacticalScanner {
    private val nativeAvailable: Boolean = runCatching {
        System.loadLibrary("gomoku_tactics")
        true
    }.getOrDefault(false)

    fun winningMoveIndices(board: IntArray, player: Int): IntArray? {
        if (!nativeAvailable || board.size != BOARD_CELLS || (player != BLACK && player != WHITE)) return null
        return runCatching { nativeWinningMoveIndices(board, player) }
            .getOrNull()
            ?.filter { index ->
                index in 0 until BOARD_CELLS && board[index] == EMPTY
            }
            ?.toIntArray()
    }

    private external fun nativeWinningMoveIndices(board: IntArray, player: Int): IntArray

    /** 用于 JVM 单元测试和极少数 native 库无法加载设备上的正确性回退。 */
    fun kotlinWinningMoveIndices(board: IntArray, player: Int): IntArray {
        val result = ArrayList<Int>(2)
        for (row in 0 until BOARD_SIZE) {
            for (col in 0 until BOARD_SIZE) {
                if (!GomokuRules.isEmpty(board, row, col)) continue
                val index = GomokuRules.index(row, col)
                board[index] = player
                val wins = isFiveAt(board, row, col, player)
                board[index] = EMPTY
                if (wins) result += index
            }
        }
        return result.toIntArray()
    }

    private fun isFiveAt(board: IntArray, row: Int, col: Int, player: Int): Boolean {
        val directions = arrayOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
        for ((dr, dc) in directions) {
            var count = 1
            var r = row + dr
            var c = col + dc
            while (GomokuRules.isInside(r, c) && board[GomokuRules.index(r, c)] == player) {
                count++
                r += dr
                c += dc
            }
            r = row - dr
            c = col - dc
            while (GomokuRules.isInside(r, c) && board[GomokuRules.index(r, c)] == player) {
                count++
                r -= dr
                c -= dc
            }
            if (count >= 5) return true
        }
        return false
    }
}
