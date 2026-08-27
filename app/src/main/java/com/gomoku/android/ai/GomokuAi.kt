package com.gomoku.android.ai

import com.gomoku.android.game.AiDifficulty
import com.gomoku.android.game.AiResult
import com.gomoku.android.game.BLACK
import com.gomoku.android.game.BOARD_CELLS
import com.gomoku.android.game.BOARD_SIZE
import com.gomoku.android.game.EMPTY
import com.gomoku.android.game.GomokuRules
import com.gomoku.android.game.Move
import com.gomoku.android.game.WHITE
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 离线搜索型五子棋 AI。
 *
 * 采用“强制战术优先 + 迭代加深 Alpha-Beta”的混合路线：
 * 1. 必胜、必防和双威胁优先；
 * 2. 只搜索棋子附近的候选落点；
 * 3. 使用 Zobrist 哈希的置换表复用完整子树结果；
 * 4. 以时间预算而非固定层数停止，适应不同手机性能。
 */
class GomokuAi {
    private data class ScoredMove(val row: Int, val col: Int, val score: Int)
    private data class TranspositionEntry(val depth: Int, val score: Int, val bestIndex: Int)

    private class SearchAborted : RuntimeException()

    private val directions = arrayOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
    private val zobrist = LongArray(BOARD_CELLS * 2) { index ->
        val mixed = index.toLong() * -7046029254386353131L + -4658895280553007687L
        mixed xor (mixed ushr 27) xor (mixed shl 17)
    }
    private val sideToMoveKey = -7723592293110705685L
    private val transposition = HashMap<Long, TranspositionEntry>(32_768)

    private var aiPlayer = WHITE
    private var deadlineNanos = 0L
    private var searchedNodes = 0
    private var cancelled: () -> Boolean = { false }

    fun chooseMove(
        board: IntArray,
        player: Int,
        difficulty: AiDifficulty,
        shouldCancel: () -> Boolean = { false },
    ): AiResult {
        require(board.size == BOARD_CELLS) { "棋盘尺寸不正确" }
        aiPlayer = player
        cancelled = shouldCancel
        searchedNodes = 0
        transposition.clear()
        deadlineNanos = System.nanoTime() + difficulty.timeBudgetMs * 1_000_000L

        val workingBoard = board.copyOf()
        val allCandidates = orderedCandidates(workingBoard, player, BOARD_CELLS)
        val fallback = allCandidates.firstOrNull() ?: ScoredMove(BOARD_SIZE / 2, BOARD_SIZE / 2, 0)

        immediateWinningMove(workingBoard, player, allCandidates)?.let {
            return AiResult(it, 0, searchedNodes)
        }

        val enemy = GomokuRules.opponent(player)
        immediateWinningMove(workingBoard, enemy, allCandidates)?.let { block ->
            return AiResult(Move(block.row, block.col, player), 0, searchedNodes)
        }

        // 落子后制造两个下一手必胜点时，对手通常无法同时封住；它是轻量的强制威胁检测。
        doubleThreatMove(workingBoard, player, allCandidates)?.let {
            return AiResult(it, 0, searchedNodes)
        }

        val limited = allCandidates.take(difficulty.candidateLimit)
        if (difficulty == AiDifficulty.EASY) {
            val variety = min(3, limited.size)
            val picked = limited[Random.nextInt(variety)]
            return AiResult(Move(picked.row, picked.col, player), 0, searchedNodes)
        }

        var bestMove = Move(fallback.row, fallback.col, player)
        var completedDepth = 0
        val initialHash = computeHash(workingBoard)

        for (depth in 1..difficulty.maxDepth) {
            try {
                val result = searchRoot(workingBoard, initialHash, depth, player, difficulty.candidateLimit)
                bestMove = result
                completedDepth = depth
            } catch (_: SearchAborted) {
                break
            }
        }
        return AiResult(bestMove, completedDepth, searchedNodes)
    }

    private fun searchRoot(
        board: IntArray,
        hash: Long,
        depth: Int,
        player: Int,
        candidateLimit: Int,
    ): Move {
        ensureSearchActive()
        var alpha = -WIN_SCORE
        val beta = WIN_SCORE
        var bestScore = -WIN_SCORE
        var best: ScoredMove? = null
        val hint = transposition[hash xor sideKey(player)]?.bestIndex
        val candidates = orderedCandidates(board, player, candidateLimit, hint)

        for (candidate in candidates) {
            ensureSearchActive()
            val boardIndex = GomokuRules.index(candidate.row, candidate.col)
            board[boardIndex] = player
            val nextHash = hash xor zobristKey(boardIndex, player)
            val score = if (GomokuRules.isWinningMove(board, Move(candidate.row, candidate.col, player))) {
                WIN_SCORE - depth
            } else {
                search(
                    board = board,
                    hash = nextHash,
                    toMove = GomokuRules.opponent(player),
                    depth = depth - 1,
                    alpha = alpha,
                    beta = beta,
                    lastMove = Move(candidate.row, candidate.col, player),
                    candidateLimit = candidateLimit,
                )
            }
            board[boardIndex] = EMPTY

            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
            alpha = max(alpha, bestScore)
        }
        val selected = best ?: candidates.first()
        transposition[hash xor sideKey(player)] = TranspositionEntry(depth, bestScore, GomokuRules.index(selected.row, selected.col))
        return Move(selected.row, selected.col, player)
    }

    private fun search(
        board: IntArray,
        hash: Long,
        toMove: Int,
        depth: Int,
        alpha: Int,
        beta: Int,
        lastMove: Move,
        candidateLimit: Int,
    ): Int {
        ensureSearchActive()
        searchedNodes++

        if (GomokuRules.isWinningMove(board, lastMove)) {
            return if (lastMove.player == aiPlayer) WIN_SCORE - depth else -WIN_SCORE + depth
        }
        if (GomokuRules.isBoardFull(board)) return 0
        if (depth <= 0) return evaluateBoard(board)

        val ttKey = hash xor sideKey(toMove)
        val cached = transposition[ttKey]
        if (cached != null && cached.depth >= depth) return cached.score

        val maximizing = toMove == aiPlayer
        var localAlpha = alpha
        var localBeta = beta
        var bestScore = if (maximizing) -WIN_SCORE else WIN_SCORE
        var bestIndex = -1
        val candidates = orderedCandidates(board, toMove, candidateLimit, cached?.bestIndex)

        for (candidate in candidates) {
            ensureSearchActive()
            val boardIndex = GomokuRules.index(candidate.row, candidate.col)
            board[boardIndex] = toMove
            val score = search(
                board = board,
                hash = hash xor zobristKey(boardIndex, toMove),
                toMove = GomokuRules.opponent(toMove),
                depth = depth - 1,
                alpha = localAlpha,
                beta = localBeta,
                lastMove = Move(candidate.row, candidate.col, toMove),
                candidateLimit = candidateLimit,
            )
            board[boardIndex] = EMPTY

            if (maximizing) {
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = boardIndex
                }
                localAlpha = max(localAlpha, bestScore)
            } else {
                if (score < bestScore) {
                    bestScore = score
                    bestIndex = boardIndex
                }
                localBeta = min(localBeta, bestScore)
            }
            if (localAlpha >= localBeta) break
        }

        transposition[ttKey] = TranspositionEntry(depth, bestScore, bestIndex)
        return bestScore
    }

    private fun immediateWinningMove(
        board: IntArray,
        player: Int,
        candidates: List<ScoredMove>,
    ): Move? {
        for (candidate in candidates) {
            val boardIndex = GomokuRules.index(candidate.row, candidate.col)
            board[boardIndex] = player
            val wins = GomokuRules.isWinningMove(board, Move(candidate.row, candidate.col, player))
            board[boardIndex] = EMPTY
            if (wins) return Move(candidate.row, candidate.col, player)
        }
        return null
    }

    private fun doubleThreatMove(
        board: IntArray,
        player: Int,
        candidates: List<ScoredMove>,
    ): Move? {
        for (candidate in candidates.take(16)) {
            val boardIndex = GomokuRules.index(candidate.row, candidate.col)
            board[boardIndex] = player
            val nextMoves = orderedCandidates(board, player, 12)
            var winningReplies = 0
            for (next in nextMoves) {
                val nextIndex = GomokuRules.index(next.row, next.col)
                board[nextIndex] = player
                if (GomokuRules.isWinningMove(board, Move(next.row, next.col, player))) winningReplies++
                board[nextIndex] = EMPTY
                if (winningReplies >= 2) break
            }
            board[boardIndex] = EMPTY
            if (winningReplies >= 2) return Move(candidate.row, candidate.col, player)
        }
        return null
    }

    private fun orderedCandidates(
        board: IntArray,
        player: Int,
        limit: Int,
        preferredIndex: Int? = null,
    ): List<ScoredMove> {
        if (board.all { it == EMPTY }) {
            return listOf(ScoredMove(BOARD_SIZE / 2, BOARD_SIZE / 2, 0))
        }
        val opponent = GomokuRules.opponent(player)
        val candidates = ArrayList<ScoredMove>()
        for (row in 0 until BOARD_SIZE) {
            for (col in 0 until BOARD_SIZE) {
                if (!GomokuRules.isEmpty(board, row, col) || !hasNeighbor(board, row, col)) continue
                val own = evaluatePoint(board, row, col, player)
                val defend = evaluatePoint(board, row, col, opponent)
                val center = 14 - (kotlin.math.abs(row - 7) + kotlin.math.abs(col - 7))
                var score = own * 11 / 10 + defend + center
                if (GomokuRules.index(row, col) == preferredIndex) score += 3_000_000
                candidates += ScoredMove(row, col, score)
            }
        }
        return candidates.sortedByDescending { it.score }.take(limit)
    }

    private fun hasNeighbor(board: IntArray, row: Int, col: Int): Boolean {
        for (dr in -2..2) {
            for (dc in -2..2) {
                if (dr == 0 && dc == 0) continue
                val nr = row + dr
                val nc = col + dc
                if (GomokuRules.isInside(nr, nc) && board[GomokuRules.index(nr, nc)] != EMPTY) return true
            }
        }
        return false
    }

    private fun evaluateBoard(board: IntArray): Int {
        val ownMoves = orderedCandidates(board, aiPlayer, 10)
        val opponentMoves = orderedCandidates(board, GomokuRules.opponent(aiPlayer), 10)
        val own = ownMoves.take(4).sumOf { it.score }
        val opponent = opponentMoves.take(4).sumOf { it.score }
        return own - opponent
    }

    private fun evaluatePoint(board: IntArray, row: Int, col: Int, player: Int): Int {
        var total = 0
        for ((dr, dc) in directions) {
            val forward = countAndOpen(board, row, col, dr, dc, player)
            val backward = countAndOpen(board, row, col, -dr, -dc, player)
            val stones = forward.first + backward.first
            val openEnds = forward.second + backward.second
            total += patternValue(stones, openEnds)
        }
        return total
    }

    private fun countAndOpen(
        board: IntArray,
        row: Int,
        col: Int,
        dr: Int,
        dc: Int,
        player: Int,
    ): Pair<Int, Int> {
        var r = row + dr
        var c = col + dc
        var stones = 0
        while (GomokuRules.isInside(r, c) && board[GomokuRules.index(r, c)] == player) {
            stones++
            r += dr
            c += dc
        }
        val open = if (GomokuRules.isInside(r, c) && board[GomokuRules.index(r, c)] == EMPTY) 1 else 0
        return stones to open
    }

    private fun patternValue(stonesAroundPoint: Int, openEnds: Int): Int = when {
        stonesAroundPoint >= 4 -> 10_000_000
        stonesAroundPoint == 3 && openEnds == 2 -> 300_000
        stonesAroundPoint == 3 && openEnds == 1 -> 40_000
        stonesAroundPoint == 2 && openEnds == 2 -> 9_000
        stonesAroundPoint == 2 && openEnds == 1 -> 1_500
        stonesAroundPoint == 1 && openEnds == 2 -> 500
        stonesAroundPoint == 1 && openEnds == 1 -> 80
        else -> 5
    }

    private fun computeHash(board: IntArray): Long {
        var hash = 0L
        board.forEachIndexed { index, cell ->
            if (cell != EMPTY) hash = hash xor zobristKey(index, cell)
        }
        return hash
    }

    private fun zobristKey(index: Int, player: Int): Long = zobrist[index * 2 + if (player == BLACK) 0 else 1]

    private fun sideKey(player: Int): Long = if (player == BLACK) sideToMoveKey else 0L

    private fun ensureSearchActive() {
        if (System.nanoTime() >= deadlineNanos || Thread.currentThread().isInterrupted || cancelled()) {
            throw SearchAborted()
        }
    }

    private companion object {
        const val WIN_SCORE = 100_000_000
    }
}
