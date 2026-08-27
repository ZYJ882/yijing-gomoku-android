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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 面向移动端的专项五子棋引擎。
 *
 * 搜索顺序：立即胜负 -> VCF 强迫威胁 -> 迭代加深主变化搜索（PVS）。
 * 评估使用方向模式码表缓存；置换表保存精确值或 Alpha/Beta 上下界，避免将剪枝值误作精确值复用。
 */
class GomokuAi {
    private data class ScoredMove(val row: Int, val col: Int, val score: Int)

    private enum class Bound {
        EXACT,
        LOWER,
        UPPER,
    }

    private data class TranspositionEntry(
        val depth: Int,
        val score: Int,
        val bound: Bound,
        val bestIndex: Int,
    )

    private data class LineThreat(
        val score: Int,
        val rank: Int,
    )

    private class SearchAborted : RuntimeException()

    private val directions = arrayOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
    private val zobrist = LongArray(BOARD_CELLS * 2) { index ->
        val mixed = index.toLong() * -7046029254386353131L + -4658895280553007687L
        mixed xor (mixed ushr 27) xor (mixed shl 17)
    }
    private val sideToMoveKey = -7723592293110705685L
    private val candidateKey = 5443911996627931537L
    private val transposition = HashMap<Long, TranspositionEntry>(MAX_TRANSPOSITION_ENTRIES)
    private val candidateCache = HashMap<Long, List<ScoredMove>>(MAX_CANDIDATE_CACHE_ENTRIES)
    private val patternCodeCache = HashMap<Int, LineThreat>(MAX_PATTERN_CACHE_ENTRIES)
    private val killerMoves = IntArray(MAX_SEARCH_DEPTH) { -1 }
    private val history = IntArray(BOARD_CELLS * 2)

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
        candidateCache.clear()
        killerMoves.fill(-1)
        history.fill(0)
        deadlineNanos = System.nanoTime() + difficulty.timeBudgetMs * 1_000_000L

        val workingBoard = board.copyOf()
        val initialHash = computeHash(workingBoard)
        val allCandidates = orderedCandidates(
            board = workingBoard,
            player = player,
            limit = BOARD_CELLS,
            hash = initialHash,
        )
        val fallback = allCandidates.firstOrNull() ?: ScoredMove(BOARD_SIZE / 2, BOARD_SIZE / 2, 0)

        immediateWinningMove(workingBoard, player, allCandidates)?.let {
            return AiResult(it, 0, searchedNodes)
        }

        val enemy = GomokuRules.opponent(player)
        immediateWinningMove(workingBoard, enemy, allCandidates)?.let { block ->
            return AiResult(Move(block.row, block.col, player), 0, searchedNodes)
        }

        // 仅在高难度中运行 VCF。它搜索“制造唯一必防四 -> 对手被迫封堵 -> 再制造必防四”的链。
        if (difficulty != AiDifficulty.EASY && difficulty != AiDifficulty.NORMAL) {
            findVcfWinningMove(
                board = workingBoard,
                attacker = player,
                attackPlies = if (difficulty == AiDifficulty.MASTER) 4 else 3,
                candidateLimit = max(16, difficulty.candidateLimit + 4),
            )?.let { return AiResult(it, 0, searchedNodes) }

            // 对手若存在 VCF 起手，优先占据该关键点；常规 Alpha-Beta 会继续比较其他防守手。
            findVcfWinningMove(
                board = workingBoard,
                attacker = enemy,
                attackPlies = if (difficulty == AiDifficulty.MASTER) 4 else 3,
                candidateLimit = max(16, difficulty.candidateLimit + 4),
            )?.let { threat ->
                if (GomokuRules.isEmpty(workingBoard, threat.row, threat.col)) {
                    return AiResult(Move(threat.row, threat.col, player), 0, searchedNodes)
                }
            }
        }

        val limited = allCandidates.take(difficulty.candidateLimit)
        if (difficulty == AiDifficulty.EASY) {
            val variety = min(3, limited.size)
            val picked = if (variety > 0) limited[Random.nextInt(variety)] else fallback
            return AiResult(Move(picked.row, picked.col, player), 0, searchedNodes)
        }

        var bestMove = Move(fallback.row, fallback.col, player)
        var completedDepth = 0
        for (depth in 1..difficulty.maxDepth) {
            try {
                bestMove = searchRoot(
                    board = workingBoard,
                    hash = initialHash,
                    depth = depth,
                    player = player,
                    candidateLimit = difficulty.candidateLimit,
                )
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
        val key = positionKey(hash, player)
        val hint = transposition[key]?.bestIndex
        val candidates = orderForSearch(
            orderedCandidates(board, player, candidateLimit, hint, hash),
            player = player,
            depth = depth,
        )
        var isFirstMove = true

        for (candidate in candidates) {
            ensureSearchActive()
            val boardIndex = GomokuRules.index(candidate.row, candidate.col)
            board[boardIndex] = player
            val childHash = hash xor zobristKey(boardIndex, player)
            val score = if (GomokuRules.isWinningMove(board, Move(candidate.row, candidate.col, player))) {
                WIN_SCORE - depth
            } else if (isFirstMove) {
                search(
                    board = board,
                    hash = childHash,
                    toMove = GomokuRules.opponent(player),
                    depth = depth - 1,
                    alpha = alpha,
                    beta = beta,
                    lastMove = Move(candidate.row, candidate.col, player),
                    candidateLimit = candidateLimit,
                )
            } else {
                var probe = search(
                    board = board,
                    hash = childHash,
                    toMove = GomokuRules.opponent(player),
                    depth = depth - 1,
                    alpha = alpha,
                    beta = min(beta, alpha + 1),
                    lastMove = Move(candidate.row, candidate.col, player),
                    candidateLimit = candidateLimit,
                )
                if (probe > alpha && probe < beta) {
                    probe = search(
                        board = board,
                        hash = childHash,
                        toMove = GomokuRules.opponent(player),
                        depth = depth - 1,
                        alpha = alpha,
                        beta = beta,
                        lastMove = Move(candidate.row, candidate.col, player),
                        candidateLimit = candidateLimit,
                    )
                }
                probe
            }
            board[boardIndex] = EMPTY
            isFirstMove = false

            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
            alpha = max(alpha, bestScore)
        }

        val selected = best ?: candidates.firstOrNull() ?: ScoredMove(BOARD_SIZE / 2, BOARD_SIZE / 2, 0)
        storeTransposition(
            key = key,
            entry = TranspositionEntry(
                depth = depth,
                score = bestScore,
                bound = Bound.EXACT,
                bestIndex = GomokuRules.index(selected.row, selected.col),
            ),
        )
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
        if (depth <= 0) return evaluateBoard(board, hash)

        val key = positionKey(hash, toMove)
        val cached = transposition[key]
        val originalAlpha = alpha
        val originalBeta = beta
        var localAlpha = alpha
        var localBeta = beta
        if (cached != null && cached.depth >= depth) {
            when (cached.bound) {
                Bound.EXACT -> return cached.score
                Bound.LOWER -> localAlpha = max(localAlpha, cached.score)
                Bound.UPPER -> localBeta = min(localBeta, cached.score)
            }
            if (localAlpha >= localBeta) return cached.score
        }

        val maximizing = toMove == aiPlayer
        var bestScore = if (maximizing) -WIN_SCORE else WIN_SCORE
        var bestIndex = -1
        val candidates = orderForSearch(
            orderedCandidates(board, toMove, candidateLimit, cached?.bestIndex, hash),
            player = toMove,
            depth = depth,
        )
        var isFirstMove = true

        for (candidate in candidates) {
            ensureSearchActive()
            val boardIndex = GomokuRules.index(candidate.row, candidate.col)
            board[boardIndex] = toMove
            val childHash = hash xor zobristKey(boardIndex, toMove)
            val score = if (isFirstMove) {
                search(
                    board = board,
                    hash = childHash,
                    toMove = GomokuRules.opponent(toMove),
                    depth = depth - 1,
                    alpha = localAlpha,
                    beta = localBeta,
                    lastMove = Move(candidate.row, candidate.col, toMove),
                    candidateLimit = candidateLimit,
                )
            } else if (maximizing) {
                var probe = search(
                    board = board,
                    hash = childHash,
                    toMove = GomokuRules.opponent(toMove),
                    depth = depth - 1,
                    alpha = localAlpha,
                    beta = min(localBeta, localAlpha + 1),
                    lastMove = Move(candidate.row, candidate.col, toMove),
                    candidateLimit = candidateLimit,
                )
                if (probe > localAlpha && probe < localBeta) {
                    probe = search(
                        board = board,
                        hash = childHash,
                        toMove = GomokuRules.opponent(toMove),
                        depth = depth - 1,
                        alpha = localAlpha,
                        beta = localBeta,
                        lastMove = Move(candidate.row, candidate.col, toMove),
                        candidateLimit = candidateLimit,
                    )
                }
                probe
            } else {
                var probe = search(
                    board = board,
                    hash = childHash,
                    toMove = GomokuRules.opponent(toMove),
                    depth = depth - 1,
                    alpha = max(localAlpha, localBeta - 1),
                    beta = localBeta,
                    lastMove = Move(candidate.row, candidate.col, toMove),
                    candidateLimit = candidateLimit,
                )
                if (probe < localBeta && probe > localAlpha) {
                    probe = search(
                        board = board,
                        hash = childHash,
                        toMove = GomokuRules.opponent(toMove),
                        depth = depth - 1,
                        alpha = localAlpha,
                        beta = localBeta,
                        lastMove = Move(candidate.row, candidate.col, toMove),
                        candidateLimit = candidateLimit,
                    )
                }
                probe
            }
            board[boardIndex] = EMPTY
            isFirstMove = false

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
            if (localAlpha >= localBeta) {
                registerCutoff(toMove, boardIndex, depth)
                break
            }
        }

        val bound = when {
            bestScore <= originalAlpha -> Bound.UPPER
            bestScore >= originalBeta -> Bound.LOWER
            else -> Bound.EXACT
        }
        storeTransposition(key, TranspositionEntry(depth, bestScore, bound, bestIndex))
        return bestScore
    }

    /**
     * VCF（连续冲四）子集搜索。每一步攻击后只接受：直接成五、产生两个成五点，
     * 或产生唯一成五点且对手没有立即反杀；后一种情况下防守方被迫占据该唯一点。
     */
    private fun findVcfWinningMove(
        board: IntArray,
        attacker: Int,
        attackPlies: Int,
        candidateLimit: Int,
    ): Move? {
        val candidates = orderedCandidates(board, attacker, candidateLimit).take(candidateLimit)
        for (candidate in candidates) {
            ensureSearchActive()
            if (tryForcingAttack(board, attacker, candidate, attackPlies - 1, candidateLimit)) {
                return Move(candidate.row, candidate.col, attacker)
            }
        }
        return null
    }

    private fun canForceVcf(
        board: IntArray,
        attacker: Int,
        remainingAttackPlies: Int,
        candidateLimit: Int,
    ): Boolean {
        if (remainingAttackPlies <= 0) return false
        val candidates = orderedCandidates(board, attacker, candidateLimit).take(candidateLimit)
        return candidates.any { candidate ->
            ensureSearchActive()
            tryForcingAttack(board, attacker, candidate, remainingAttackPlies - 1, candidateLimit)
        }
    }

    /**
     * 走出一手进攻后，防守方若不能立即取胜，就只能在唯一的成五点上封堵；
     * 如果进攻方有两个成五点则已经形成必胜。该顺序确保不会错误模拟连续两手进攻。
     */
    private fun tryForcingAttack(
        board: IntArray,
        attacker: Int,
        candidate: ScoredMove,
        remainingAttackPlies: Int,
        candidateLimit: Int,
    ): Boolean {
        val defender = GomokuRules.opponent(attacker)
        val attackIndex = GomokuRules.index(candidate.row, candidate.col)
        board[attackIndex] = attacker

        val forced = when {
            GomokuRules.isWinningMove(board, Move(candidate.row, candidate.col, attacker)) -> true
            immediateWinningMoves(board, defender, candidateLimit).isNotEmpty() -> false
            else -> {
                val followUps = immediateWinningMoves(board, attacker, candidateLimit)
                when {
                    followUps.size >= 2 -> true
                    followUps.size == 1 && remainingAttackPlies > 0 -> {
                        val block = followUps.first()
                        val blockIndex = GomokuRules.index(block.row, block.col)
                        board[blockIndex] = defender
                        val continuation = canForceVcf(
                            board = board,
                            attacker = attacker,
                            remainingAttackPlies = remainingAttackPlies,
                            candidateLimit = candidateLimit,
                        )
                        board[blockIndex] = EMPTY
                        continuation
                    }
                    else -> false
                }
            }
        }
        board[attackIndex] = EMPTY
        return forced
    }

    private fun immediateWinningMove(
        board: IntArray,
        player: Int,
        candidates: List<ScoredMove>,
    ): Move? = immediateWinningMoves(board, player, candidates).firstOrNull()

    private fun immediateWinningMoves(
        board: IntArray,
        player: Int,
        candidateLimit: Int,
    ): List<Move> = immediateWinningMoves(
        board = board,
        player = player,
        candidates = orderedCandidates(board, player, candidateLimit).take(candidateLimit),
    )

    private fun immediateWinningMoves(
        board: IntArray,
        player: Int,
        candidates: List<ScoredMove>,
    ): List<Move> {
        val wins = ArrayList<Move>(2)
        for (candidate in candidates) {
            ensureSearchActive()
            val index = GomokuRules.index(candidate.row, candidate.col)
            board[index] = player
            if (GomokuRules.isWinningMove(board, Move(candidate.row, candidate.col, player))) {
                wins += Move(candidate.row, candidate.col, player)
            }
            board[index] = EMPTY
        }
        return wins
    }

    private fun orderedCandidates(
        board: IntArray,
        player: Int,
        limit: Int,
        preferredIndex: Int? = null,
        hash: Long? = null,
    ): List<ScoredMove> {
        if (board.all { it == EMPTY }) {
            return listOf(ScoredMove(BOARD_SIZE / 2, BOARD_SIZE / 2, CENTER_BONUS))
        }

        val cacheKey = hash?.let { positionKey(it xor candidateKey, player) }
        val generated = cacheKey?.let { candidateCache[it] } ?: generateCandidates(board, player).also { candidates ->
            if (cacheKey != null) {
                if (candidateCache.size >= MAX_CANDIDATE_CACHE_ENTRIES) candidateCache.clear()
                candidateCache[cacheKey] = candidates
            }
        }

        val reordered = if (preferredIndex == null) {
            generated
        } else {
            val preferred = generated.firstOrNull { GomokuRules.index(it.row, it.col) == preferredIndex }
            if (preferred == null) generated else listOf(preferred) + generated.filterNot { it === preferred }
        }
        return reordered.take(limit)
    }

    private fun generateCandidates(board: IntArray, player: Int): List<ScoredMove> {
        val opponent = GomokuRules.opponent(player)
        val candidates = ArrayList<ScoredMove>()
        for (row in 0 until BOARD_SIZE) {
            for (col in 0 until BOARD_SIZE) {
                if (!GomokuRules.isEmpty(board, row, col) || !hasNeighbor(board, row, col)) continue
                val own = scoreCandidate(board, row, col, player)
                val defend = scoreCandidate(board, row, col, opponent)
                val center = CENTER_BONUS - (abs(row - BOARD_SIZE / 2) + abs(col - BOARD_SIZE / 2))
                candidates += ScoredMove(row, col, own * 11 / 10 + defend + center)
            }
        }
        return candidates.sortedByDescending { it.score }
    }

    private fun orderForSearch(
        candidates: List<ScoredMove>,
        player: Int,
        depth: Int,
    ): List<ScoredMove> {
        val killer = killerMoves.getOrNull(depth) ?: -1
        return candidates.sortedByDescending { candidate ->
            val index = GomokuRules.index(candidate.row, candidate.col)
            val killerBonus = if (index == killer) KILLER_BONUS else 0
            val historyBonus = history[index * 2 + playerSlot(player)].coerceAtMost(MAX_HISTORY_BONUS)
            candidate.score + killerBonus + historyBonus
        }
    }

    private fun registerCutoff(player: Int, moveIndex: Int, depth: Int) {
        if (depth in killerMoves.indices && killerMoves[depth] != moveIndex) {
            killerMoves[depth] = moveIndex
        }
        val historyIndex = moveIndex * 2 + playerSlot(player)
        history[historyIndex] = (history[historyIndex] + depth * depth * HISTORY_UNIT)
            .coerceAtMost(MAX_HISTORY_BONUS)
    }

    private fun playerSlot(player: Int): Int = if (player == BLACK) 0 else 1

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

    private fun evaluateBoard(board: IntArray, hash: Long): Int {
        val ownMoves = orderedCandidates(board, aiPlayer, 6, hash = hash)
        val opponent = GomokuRules.opponent(aiPlayer)
        val enemyMoves = orderedCandidates(board, opponent, 6, hash = hash)
        val own = ownMoves.take(4).sumOf { it.score }
        val enemy = enemyMoves.take(4).sumOf { it.score }
        return own - enemy
    }

    private fun scoreCandidate(board: IntArray, row: Int, col: Int, player: Int): Int {
        val index = GomokuRules.index(row, col)
        board[index] = player
        val score = scorePlacedMove(board, row, col, player)
        board[index] = EMPTY
        return score
    }

    /** 对刚刚落下的一子进行四方向模式识别。 */
    private fun scorePlacedMove(board: IntArray, row: Int, col: Int, player: Int): Int {
        var total = 0
        var fours = 0
        var openThrees = 0
        for ((dr, dc) in directions) {
            val threat = classifyLine(makeLine(board, row, col, dr, dc, player))
            total += threat.score
            if (threat.rank >= 4) fours++
            if (threat.rank == 3) openThrees++
        }
        if (fours >= 2) total += DOUBLE_FOUR_BONUS
        if (openThrees >= 2) total += DOUBLE_THREE_BONUS
        return total
    }

    private fun makeLine(
        board: IntArray,
        row: Int,
        col: Int,
        dr: Int,
        dc: Int,
        player: Int,
    ): String = buildString(11) {
        for (offset in -5..5) {
            val r = row + offset * dr
            val c = col + offset * dc
            append(
                when {
                    !GomokuRules.isInside(r, c) -> '#'
                    board[GomokuRules.index(r, c)] == player -> 'X'
                    board[GomokuRules.index(r, c)] == EMPTY -> '.'
                    else -> 'O'
                },
            )
        }
    }

    /**
     * 四方向局部模式表。X 表示当前方，. 表示空点，O/# 表示阻断。
     * 模式按威胁强度返回单方向最高等级，避免同一线段被低阶模式重复放大。
     */
    private fun classifyLine(line: String): LineThreat {
        val code = encodeLine(line)
        patternCodeCache[code]?.let { return it }
        val threat = when {
            "XXXXX" in line -> LineThreat(FIVE_SCORE, 5)
            ".XXXX." in line -> LineThreat(OPEN_FOUR_SCORE, 4)
            hasAny(line, "XXX.X", "XX.XX", "X.XXX") -> LineThreat(BROKEN_FOUR_SCORE, 4)
            "XXXX." in line || ".XXXX" in line -> LineThreat(CLOSED_FOUR_SCORE, 4)
            ".XXX." in line -> LineThreat(OPEN_THREE_SCORE, 3)
            hasAny(line, ".XX.X.", ".X.XX.", ".X.X.X.") -> LineThreat(BROKEN_THREE_SCORE, 3)
            "XXX." in line || ".XXX" in line -> LineThreat(CLOSED_THREE_SCORE, 2)
            ".XX." in line -> LineThreat(OPEN_TWO_SCORE, 2)
            hasAny(line, ".X.X.", ".X..X.") -> LineThreat(BROKEN_TWO_SCORE, 1)
            "XX." in line || ".XX" in line -> LineThreat(CLOSED_TWO_SCORE, 1)
            else -> LineThreat(SINGLE_SCORE, 0)
        }
        if (patternCodeCache.size >= MAX_PATTERN_CACHE_ENTRIES) patternCodeCache.clear()
        patternCodeCache[code] = threat
        return threat
    }

    /** 将局部线段编码为三进制：空点 0、己方 1、阻断点 2。 */
    private fun encodeLine(line: String): Int {
        var code = 0
        for (cell in line) {
            code = code * 3 + when (cell) {
                '.' -> 0
                'X' -> 1
                else -> 2
            }
        }
        return code
    }

    private fun hasAny(line: String, vararg patterns: String): Boolean = patterns.any { it in line }

    private fun computeHash(board: IntArray): Long {
        var hash = 0L
        board.forEachIndexed { index, cell ->
            if (cell != EMPTY) hash = hash xor zobristKey(index, cell)
        }
        return hash
    }

    private fun zobristKey(index: Int, player: Int): Long =
        zobrist[index * 2 + if (player == BLACK) 0 else 1]

    private fun sideKey(player: Int): Long = if (player == BLACK) sideToMoveKey else 0L

    private fun positionKey(hash: Long, player: Int): Long = hash xor sideKey(player)

    private fun storeTransposition(key: Long, entry: TranspositionEntry) {
        val old = transposition[key]
        if (old != null && old.depth > entry.depth) return
        if (transposition.size >= MAX_TRANSPOSITION_ENTRIES) transposition.clear()
        transposition[key] = entry
    }

    private fun ensureSearchActive() {
        if (System.nanoTime() >= deadlineNanos || Thread.currentThread().isInterrupted || cancelled()) {
            throw SearchAborted()
        }
    }

    private companion object {
        const val WIN_SCORE = 100_000_000
        const val FIVE_SCORE = 20_000_000
        const val OPEN_FOUR_SCORE = 2_500_000
        const val BROKEN_FOUR_SCORE = 800_000
        const val CLOSED_FOUR_SCORE = 250_000
        const val OPEN_THREE_SCORE = 75_000
        const val BROKEN_THREE_SCORE = 38_000
        const val CLOSED_THREE_SCORE = 8_000
        const val OPEN_TWO_SCORE = 1_800
        const val BROKEN_TWO_SCORE = 900
        const val CLOSED_TWO_SCORE = 250
        const val SINGLE_SCORE = 12
        const val DOUBLE_FOUR_BONUS = 5_000_000
        const val DOUBLE_THREE_BONUS = 240_000
        const val CENTER_BONUS = 30
        const val MAX_TRANSPOSITION_ENTRIES = 120_000
        const val MAX_CANDIDATE_CACHE_ENTRIES = 24_000
        const val MAX_PATTERN_CACHE_ENTRIES = 120_000
        const val MAX_SEARCH_DEPTH = 16
        const val KILLER_BONUS = 1_400_000
        const val HISTORY_UNIT = 1_600
        const val MAX_HISTORY_BONUS = 1_000_000
    }
}
