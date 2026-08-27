package com.gomoku.android.game

const val BOARD_SIZE = 15
const val BOARD_CELLS = BOARD_SIZE * BOARD_SIZE
const val EMPTY = 0
const val BLACK = 1
const val WHITE = 2

data class Move(
    val row: Int,
    val col: Int,
    val player: Int,
)

object GomokuRules {
    private val directions = arrayOf(
        1 to 0,
        0 to 1,
        1 to 1,
        1 to -1,
    )

    fun opponent(player: Int): Int = if (player == BLACK) WHITE else BLACK

    fun index(row: Int, col: Int): Int = row * BOARD_SIZE + col

    fun isInside(row: Int, col: Int): Boolean =
        row in 0 until BOARD_SIZE && col in 0 until BOARD_SIZE

    fun isEmpty(board: IntArray, row: Int, col: Int): Boolean =
        isInside(row, col) && board[index(row, col)] == EMPTY

    fun isWinningMove(board: IntArray, move: Move): Boolean {
        if (!isInside(move.row, move.col) || board[index(move.row, move.col)] != move.player) {
            return false
        }
        return directions.any { (dr, dc) ->
            1 + count(board, move.row, move.col, dr, dc, move.player) +
                count(board, move.row, move.col, -dr, -dc, move.player) >= 5
        }
    }

    fun isBoardFull(board: IntArray): Boolean = board.none { it == EMPTY }

    fun findWinner(board: IntArray): Int? {
        for (row in 0 until BOARD_SIZE) {
            for (col in 0 until BOARD_SIZE) {
                val player = board[index(row, col)]
                if (player == EMPTY) continue
                if (isWinningMove(board, Move(row, col, player))) return player
            }
        }
        return null
    }

    fun createBoard(): IntArray = IntArray(BOARD_CELLS) { EMPTY }

    private fun count(
        board: IntArray,
        startRow: Int,
        startCol: Int,
        dr: Int,
        dc: Int,
        player: Int,
    ): Int {
        var row = startRow + dr
        var col = startCol + dc
        var total = 0
        while (isInside(row, col) && board[index(row, col)] == player) {
            total++
            row += dr
            col += dc
        }
        return total
    }
}

enum class AiDifficulty(
    val label: String,
    val timeBudgetMs: Long,
    val maxDepth: Int,
    val candidateLimit: Int,
) {
    EASY("新手", 60L, 1, 6),
    NORMAL("普通", 220L, 2, 9),
    HARD("困难", 700L, 4, 12),
    MASTER("大师", 1_500L, 5, 14),
}

data class AiResult(
    val move: Move,
    val completedDepth: Int,
    val searchedNodes: Int,
)
