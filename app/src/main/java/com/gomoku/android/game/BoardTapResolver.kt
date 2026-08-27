package com.gomoku.android.game

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

data class BoardCell(val row: Int, val col: Int)

/**
 * 将画布中的触控坐标映射到最近的棋盘交叉点。
 *
 * 不能直接把坐标差 `toInt()` 截断：点击落在两个交叉点之间的右半部分时，
 * 截断会错误地归属到左侧点，随后又会因距离校验失败而被忽略。此处使用四舍五入
 * 选择最近交叉点，并以半个格距加少量容差判断是否属于有效点击区。
 */
object BoardTapResolver {
    private const val BOARD_PADDING_RATIO = 0.065f
    private const val HIT_RADIUS_RATIO = 0.54f

    fun resolve(x: Float, y: Float, width: Float, height: Float): BoardCell? {
        val edge = min(width, height)
        if (edge <= 0f) return null

        val padding = edge * BOARD_PADDING_RATIO
        val cell = (edge - 2f * padding) / (BOARD_SIZE - 1)
        if (cell <= 0f) return null

        val col = ((x - padding) / cell).roundToInt()
        val row = ((y - padding) / cell).roundToInt()
        if (!GomokuRules.isInside(row, col)) return null

        val targetX = padding + col * cell
        val targetY = padding + row * cell
        return if (abs(x - targetX) <= cell * HIT_RADIUS_RATIO && abs(y - targetY) <= cell * HIT_RADIUS_RATIO) {
            BoardCell(row, col)
        } else {
            null
        }
    }
}
