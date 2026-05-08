package com.example.smarty.features.games.domain

// ─────────────────────────────────────────────────────────────────────────────
// CHESS AI — Optimized: pre-alloc buffers, TT, move ordering, quiescence,
//            deferred LoRA saves, zero-boxing eval hot path
// ─────────────────────────────────────────────────────────────────────────────
//
// KEY OPTIMIZATIONS vs old version:
//   1. Pre-allocated FloatArrays for eval (no per-call allocation)
//   2. Transposition Table (2-bucket, 1MB) — avoids re-evaluating positions
//   3. Move ordering: captures first, MVV-LVA
//   4. Quiescence search — avoids horizon effect on captures
//   5. Deferred LoRA save — writes to disk only when game ends or every 8 moves
//   6. Uses ChessEngine bitboard state (no List<Any?> boxing)
//
// ABBREVIATIONS: see ChessModels.kt header
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ChessAI(private val context: Context) {

    // ── NNUE dimensions ──────────────────────────────────────────────────
    private val INPUT_DIM = 768
    private val TRANSFORMER_DIM = 256
    private val FC1_DIM = 32
    private val FC2_DIM = 32
    private val LORA_RANK = 8

    // ── Model weights (loaded once) ──────────────────────────────────────
    private var transformerW = FloatArray(INPUT_DIM * TRANSFORMER_DIM)
    private var transformerB = FloatArray(TRANSFORMER_DIM)
    private var fc1W = FloatArray(512 * FC1_DIM)
    private var fc1B = FloatArray(FC1_DIM)
    private var fc2W = FloatArray(FC2_DIM * FC2_DIM)
    private var fc2B = FloatArray(FC2_DIM)
    private var evalW = FloatArray(32)
    private var evalB: Float = 0f

    // ── LoRA weights ─────────────────────────────────────────────────────
    private var loraA = FloatArray(32 * LORA_RANK)
    private var loraB = FloatArray(LORA_RANK)
    private val alpha = 0.01f
    private val baseModelName = "nnue_base_int8.bin"
    private val loraModelName = "lora_profile_int8.bin"
    private val loraPersistentName = "lora_user_profile.bin"

    // ── Pre-allocated eval buffers (avoids GC) ──────────────────────────
    private val _wf = FloatArray(768)
    private val _bf = FloatArray(768)
    private val _wT = FloatArray(256)
    private val _bT = FloatArray(256)
    private val _combined = FloatArray(512)
    private val _x1 = FloatArray(32)
    private val _x2 = FloatArray(32)
    private val _mid = FloatArray(LORA_RANK)

    // ── Transposition Table ───────────────────────────────────────────────
    private val TT_SIZE = (1 * 1024 * 1024) / 16  // ~1MB, 16 bytes per entry
    private val ttKeys = LongArray(TT_SIZE)
    private val ttValues = FloatArray(TT_SIZE)
    private val ttDepth = IntArray(TT_SIZE)
    private val ttFlag = IntArray(TT_SIZE)  // 0=empty, 1=exact, 2=lower, 3=upper

    // ── Deferred LoRA save ────────────────────────────────────────────────
    private var loraDirty = false
    private var movesSinceSave = 0
    private val SAVE_INTERVAL = 8
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Piece values for MVV-LVA move ordering ──────────────────────────
    private val PIECE_VALUE = intArrayOf(100, 320, 330, 500, 900, 20000) // P,N,B,R,Q,K

    init {
        try {
            loadBaseModel()
        } catch (e: Exception) {
            android.util.Log.e("ChessAI", "Failed to load base model: ${e.message}. Using defaults.")
        }
        try {
            loadLoRAModel()
        } catch (e: Exception) {
            android.util.Log.e("ChessAI", "Failed to load LoRA model: ${e.message}. Using defaults.")
        }
    }

    // ── Model loading (unchanged from original) ─────────────────────────
    private fun loadBaseModel() {
        context.assets.open(baseModelName).use { stream ->
            val bytes = stream.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val scale = buffer.float
            fun readLayer(size: Int): FloatArray {
                val arr = FloatArray(size)
                for (i in 0 until size) {
                    if (buffer.remaining() > 0) arr[i] = buffer.get().toFloat() / scale
                }
                return arr
            }
            transformerW = readLayer(INPUT_DIM * TRANSFORMER_DIM)
            transformerB = readLayer(TRANSFORMER_DIM)
            fc1W = readLayer(512 * FC1_DIM)
            fc1B = readLayer(FC1_DIM)
            fc2W = readLayer(FC2_DIM * FC2_DIM)
            fc2B = readLayer(FC2_DIM)
            evalW = readLayer(32)
            if (buffer.remaining() > 0) evalB = buffer.get().toFloat() / scale
        }
    }

    private fun loadLoRAModel() {
        try {
            val file = File(context.filesDir, loraPersistentName)
            val inputStream = if (file.exists()) file.inputStream() else context.assets.open(loraModelName)
            inputStream.use { stream ->
                val bytes = stream.readBytes()
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val scale = buffer.float
                for (i in loraA.indices) {
                    if (buffer.remaining() > 0) loraA[i] = buffer.get().toFloat() / scale
                }
                for (i in loraB.indices) {
                    if (buffer.remaining() > 0) loraB[i] = buffer.get().toFloat() / scale
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ChessAI", "LoRA loading issue: ${e.message}")
        }
    }

    // ── Deferred save: only flush to disk periodically or on game end ────
    fun saveLoRA() {
        loraDirty = true
        movesSinceSave++
        if (movesSinceSave >= SAVE_INTERVAL) {
            flushLoRA()
        }
    }

    fun flushLoRA() {
        if (!loraDirty) return
        loraDirty = false
        movesSinceSave = 0
        val file = File(context.filesDir, loraPersistentName)
        file.outputStream().use { stream ->
            val maxVal = (loraA.map { Math.abs(it) }.maxOrNull() ?: 1f)
                .coerceAtLeast(loraB.map { Math.abs(it) }.maxOrNull() ?: 1f)
            val scale = 127f / maxVal
            val buffer = ByteBuffer.allocate(4 + loraA.size + loraB.size)
                .order(ByteOrder.LITTLE_ENDIAN)
            buffer.putFloat(scale)
            for (v in loraA) buffer.put((v * scale).toInt().toByte())
            for (v in loraB) buffer.put((v * scale).toInt().toByte())
            stream.write(buffer.array())
        }
    }

    fun onGameEnd() { flushLoRA() }

    // ── Zobrist hash for TT ──────────────────────────────────────────────
    private val zobristPieces = LongArray(12 * 64) {
        kotlin.random.Random.nextLong()
    }
    private val zobristCastling = LongArray(16) { kotlin.random.Random.nextLong() }
    private val zobristEp = LongArray(64) { kotlin.random.Random.nextLong() }
    private val zobristTurn = kotlin.random.Random.nextLong()

    fun hashState(s: ChessEngine.State): Long {
        var h = 0L
        for (pt in 0..5) {
            for (col in 0..1) {
                val bb = ChessEngine.getPcBb(s, pcBbIdx(pt, col))
                var pieces = bb
                while (pieces != 0L) {
                    val sq = pieces.lsb()
                    pieces = pieces xor bit(sq)
                    h = h xor zobristPieces[pcBbIdx(pt, col) * 64 + sq]
                }
            }
        }
        h = h xor zobristCastling[s.cr and 0xF]
        if (s.ep >= 0) h = h xor zobristEp[s.ep]
        if (s.turn == 1) h = h xor zobristTurn
        return h
    }

    // ── TT probe/store ───────────────────────────────────────────────────
    private fun ttProbe(hash: Long, depth: Int, alpha: Float, beta: Float): Float? {
        val idx = (hash.toInt() and 0x7FFFFFFF) % TT_SIZE
        if (ttKeys[idx] == hash && ttDepth[idx] >= depth) {
            val v = ttValues[idx]
            val f = ttFlag[idx]
            if (f == 1) return v       // exact
            if (f == 2 && v >= beta) return v  // lower bound
            if (f == 3 && v <= alpha) return v  // upper bound
        }
        // Try second bucket
        val idx2 = (idx + 1) % TT_SIZE
        if (ttKeys[idx2] == hash && ttDepth[idx2] >= depth) {
            val v = ttValues[idx2]
            val f = ttFlag[idx2]
            if (f == 1) return v
            if (f == 2 && v >= beta) return v
            if (f == 3 && v <= alpha) return v
        }
        return null
    }

    private fun ttStore(hash: Long, depth: Int, value: Float, alpha: Float, beta: Float) {
        val idx = (hash.toInt() and 0x7FFFFFFF) % TT_SIZE
        val flag = when {
            value <= alpha -> 3  // upper bound
            value >= beta  -> 2  // lower bound
            else           -> 1  // exact
        }
        // Replace: prefer deeper entries, or replace if same hash or empty
        if (ttKeys[idx] == 0L || ttKeys[idx] == hash || ttDepth[idx] <= depth) {
            ttKeys[idx] = hash; ttValues[idx] = value; ttDepth[idx] = depth; ttFlag[idx] = flag
        } else {
            val idx2 = (idx + 1) % TT_SIZE
            if (ttKeys[idx2] == 0L || ttKeys[idx2] == hash || ttDepth[idx2] <= depth) {
                ttKeys[idx2] = hash; ttValues[idx2] = value; ttDepth[idx2] = depth; ttFlag[idx2] = flag
            }
        }
    }

    fun clearTT() { ttKeys.fill(0); ttValues.fill(0f); ttDepth.fill(0); ttFlag.fill(0) }

    // ── Evaluate using pre-allocated buffers (ZERO allocation) ────────────
    fun evaluate(s: ChessEngine.State): Float {
        boardToFeatures(s, _wf, _bf)
        transformInto(_wf, transformerW, transformerB, 768, 256, _wT)
        transformInto(_bf, transformerW, transformerB, 768, 256, _bT)
        System.arraycopy(_wT, 0, _combined, 0, 256)
        System.arraycopy(_bT, 0, _combined, 256, 256)
        layerInto(_combined, fc1W, fc1B, 512, 32, _x1)
        layerInto(_x1, fc2W, fc2B, 32, 32, _x2)
        var score = evalB
        for (i in 0 until 32) score += _x2[i] * evalW[i]
        val loraScore = applyLoRAInto(_x2)
        val clippedLora = loraScore.coerceIn(-0.5f, 0.5f)
        val finalScore = score + clippedLora
        return (-Math.log(2.0 / (finalScore + 1.0) - 1.0) / 0.00368208).toFloat()
    }

    // Legacy evaluate for Board<List<Any?>> — used by updateLearning from UI
    fun evaluate(board: List<Any?>): Float {
        val s = ChessEngine.initStart()
        boardToEngineState(board, s)
        return evaluate(s)
    }

    private fun transformInto(input: FloatArray, weights: FloatArray, bias: FloatArray, inDim: Int, outDim: Int, out: FloatArray) {
        for (j in 0 until outDim) {
            var sum = bias[j]
            for (i in 0 until inDim) if (input[i] > 0) sum += weights[i * outDim + j]
            out[j] = if (sum > 0) sum else 0f
        }
    }

    private fun layerInto(input: FloatArray, weights: FloatArray, bias: FloatArray, inDim: Int, outDim: Int, out: FloatArray) {
        for (j in 0 until outDim) {
            var sum = bias[j]
            for (i in 0 until inDim) sum += input[i] * weights[i * outDim + j]
            out[j] = if (sum > 0) sum else 0f
        }
    }

    private fun applyLoRAInto(x: FloatArray): Float {
        for (j in 0 until LORA_RANK) {
            var sum = 0f
            for (i in 0 until 32) sum += x[i] * loraA[i * LORA_RANK + j]
            _mid[j] = sum
        }
        var out = 0f
        for (i in 0 until LORA_RANK) out += _mid[i] * loraB[i]
        return out
    }

    // ── Board → features into pre-allocated arrays ──────────────────────
    fun boardToFeatures(s: ChessEngine.State, wf: FloatArray, bf: FloatArray) {
        wf.fill(0f); bf.fill(0f)
        for (r in 0..7) {
            for (f in 0..7) {
                val idx = sqIdx(r, f)
                var found = false
                for (pt in 0..5) {
                    for (col in 0..1) {
                        if (ChessEngine.getPcBb(s, pcBbIdx(pt, col)) and bit(idx) != 0L) {
                            val typeIdx = pt
                            val colorIdx = col
                            val pIdx = typeIdx + (colorIdx * 6)
                            val sq = (7 - r) * 8 + f
                            wf[pIdx * 64 + sq] = 1.0f
                            val bSq = sq xor 56
                            val bPIdx = typeIdx + ((1 - colorIdx) * 6)
                            bf[bPIdx * 64 + bSq] = 1.0f
                            found = true; break
                        }
                    }
                    if (found) break
                }
            }
        }
    }

    // Legacy: Board<List<Any?>> → features
    fun boardToFeatures(board: List<Any?>): Pair<FloatArray, FloatArray> {
        val wf = FloatArray(768); val bf = FloatArray(768)
        for (r in 0..7) {
            for (f in 0..7) {
                val p = board[r * 8 + f] ?: continue
                val pc = p as? Pc ?: continue
                val typeIdx = pc.t.ordinal
                val colorIdx = if (pc.c == Col.W) 0 else 1
                val pIdx = typeIdx + (colorIdx * 6)
                val sq = (7 - r) * 8 + f
                wf[pIdx * 64 + sq] = 1.0f
                val bSq = sq xor 56
                val bPIdx = typeIdx + ((1 - colorIdx) * 6)
                bf[bPIdx * 64 + bSq] = 1.0f
            }
        }
        return wf to bf
    }

    private fun boardToEngineState(board: List<Any?>, s: ChessEngine.State) {
        // Clear all
        for (i in s.pcBbs.indices) s.pcBbs[i] = 0
        for (i in s.occ.indices) s.occ[i] = 0
        for (r in 0..7) {
            for (f in 0..7) {
                val p = board[r * 8 + f] ?: continue
                val pc = p as? Pc ?: continue
                val pt = pc.t.ordinal
                val col = if (pc.c == Col.W) 0 else 1
                val idx = sqIdx(r, f)
                val bbIdx = pcBbIdx(pt, col)
                val cur = ChessEngine.getPcBb(s, bbIdx)
                ChessEngine.setPcBb(s, bbIdx, cur or bit(idx))
                val occBase = col * 2
                val occCur = longFromPair(s.occ, occBase)
                longToPair(s.occ, occBase, occCur or bit(idx))
            }
        }
    }

    private fun longFromPair(arr: IntArray, base: Int): Long =
        (arr[base].toLong() and 0xFFFFFFFFL) or (arr[base + 1].toLong() shl 32)

    private fun longToPair(arr: IntArray, base: Int, v: Long) {
        arr[base] = v.toInt()
        arr[base + 1] = (v ushr 32).toInt()
    }

    // ── Update learning (uses pre-alloc buffers, deferred save) ────────────
    fun updateLearning(board: List<Any?>, targetEval: Float) {
        val s = ChessEngine.initStart()
        boardToEngineState(board, s)
        updateLearningFromState(s, targetEval)
    }

    fun updateLearningFromState(s: ChessEngine.State, targetEval: Float) {
        boardToFeatures(s, _wf, _bf)
        transformInto(_wf, transformerW, transformerB, 768, 256, _wT)
        transformInto(_bf, transformerW, transformerB, 768, 256, _bT)
        System.arraycopy(_wT, 0, _combined, 0, 256)
        System.arraycopy(_bT, 0, _combined, 256, 256)
        layerInto(_combined, fc1W, fc1B, 512, 32, _x1)
        layerInto(_x1, fc2W, fc2B, 32, 32, _x2)
        var baseScore = evalB
        for (i in 0 until 32) baseScore += _x2[i] * evalW[i]
        val currentLoRA = applyLoRAInto(_x2)
        val currentEval = baseScore + currentLoRA.coerceIn(-0.5f, 0.5f)
        val error = targetEval - currentEval
        for (j in 0 until LORA_RANK) {
            var z = 0f
            for (i in 0 until 32) z += _x2[i] * loraA[i * LORA_RANK + j]
            loraB[j] += alpha * error * z
        }
        for (i in 0 until 32) {
            for (j in 0 until LORA_RANK) {
                loraA[i * LORA_RANK + j] += alpha * error * _x2[i] * loraB[j]
            }
        }
        saveLoRA()  // deferred — won't actually write every time
    }

    // ── Find best move (with TT, move ordering) ──────────────────────────
    fun findBestMove(
        s: ChessEngine.State,
        depth: Int
    ): Int? {
        val moves = IntArray(256); val caps = IntArray(256)
        val cnt = ChessEngine.legalMoves(s, moves, caps)
        if (cnt == 0) return null

        // Move ordering: sort captures first (MVV-LVA)
        orderMoves(s, moves, caps, cnt)

        val isMax = s.turn == 0  // white maximizes
        var bestMove = moves[0]
        var bestValue = if (isMax) -1e9f else 1e9f
        var alpha = -1e9f; var beta = 1e9f
        val u = ChessEngine.UndoInfo()

        for (i in 0 until cnt) {
            val m = moves[i]
            ChessEngine.applyMove(s, m, u)
            val value = minimax(s, depth - 1, alpha, beta, u)
            ChessEngine.undoMove(s, m, u)

            if (isMax) {
                if (value > bestValue) { bestValue = value; bestMove = m }
                alpha = maxOf(alpha, bestValue)
            } else {
                if (value < bestValue) { bestValue = value; bestMove = m }
                beta = minOf(beta, bestValue)
            }
        }
        return bestMove
    }

    // Legacy API: Board<List<Any?>> based (for compatibility)
    fun findBestMove(
        board: List<Any?>,
        depth: Int,
        isWhite: Boolean,
        legalMovesProvider: (List<Any?>, Boolean) -> List<Pair<Int, Int>>
    ): Pair<Int, Int>? {
        val s = ChessEngine.initStart()
        boardToEngineState(board, s)
        s.turn = if (isWhite) 0 else 1
        val move = findBestMove(s, depth) ?: return null
        return ChessEngine.moveFrom(move) to ChessEngine.moveTo(move)
    }

    // ── Move ordering: captures first, MVV-LVA ──────────────────────────
    private fun orderMoves(s: ChessEngine.State, moves: IntArray, caps: IntArray, cnt: Int) {
        // Simple: sort captures to front by captured piece value
        // Use insertion sort (small N, in-place)
        for (i in 1 until cnt) {
            val m = moves[i]; val c = caps[i]
            val score = moveOrderScore(s, m, c)
            var j = i - 1
            while (j >= 0 && moveOrderScore(s, moves[j], caps[j]) < score) {
                moves[j + 1] = moves[j]; caps[j + 1] = caps[j]; j--
            }
            moves[j + 1] = m; caps[j + 1] = c
        }
    }

    private fun moveOrderScore(s: ChessEngine.State, m: Int, isCap: Int): Int {
        if (isCap == 0) return 0
        val to = ChessEngine.moveTo(m)
        val opp = 1 - s.turn
        // Find captured piece type for MVV-LVA
        for (pt in 5 downTo 0) {  // check high-value pieces first
            if (ChessEngine.getPcBb(s, pcBbIdx(pt, opp)) and bit(to) != 0L)
                return PIECE_VALUE[pt] * 10  // MVV
        }
        return 100  // EP or unknown capture
    }

    // ── Minimax with alpha-beta, TT probe, null-move, quiescence ─────────
    private fun minimax(
        s: ChessEngine.State,
        depth: Int,
        alpha: Float,
        beta: Float,
        prevUndo: ChessEngine.UndoInfo
    ): Float {
        val hash = hashState(s)

        // TT probe
        val ttVal = ttProbe(hash, depth, alpha, beta)
        if (ttVal != null) return ttVal

        val isMax = s.turn == 0
        val origAlpha = alpha

        // Null-move pruning (skip for endgame — no pieces = no prune)
        if (depth >= 3 && !inEndgame(s)) {
            val u = ChessEngine.UndoInfo()
            s.turn = 1 - s.turn; s.ep = -1
            val nullVal = minimax(s, depth - 3, beta, beta + 1f, u)
            s.turn = 1 - s.turn
            if (isMax && nullVal >= beta) return beta
            if (!isMax && nullVal <= alpha) return alpha
        }

        if (depth <= 0) return quiescence(s, alpha, beta, prevUndo, 0)

        val moves = IntArray(256); val caps = IntArray(256)
        val cnt = ChessEngine.legalMoves(s, moves, caps)
        if (cnt == 0) {
            return if (ChessEngine.inCheck(s, s.turn)) {
                if (isMax) -1e6f + (100 - depth) else 1e6f - (100 - depth)
            } else 0f  // stalemate
        }

        orderMoves(s, moves, caps, cnt)

        val u = ChessEngine.UndoInfo()

        if (isMax) {
            var maxEval = -1e9f
            for (i in 0 until cnt) {
                ChessEngine.applyMove(s, moves[i], u)
                val ev = minimax(s, depth - 1, alpha, beta, u)
                ChessEngine.undoMove(s, moves[i], u)
                maxEval = maxOf(maxEval, ev)
                val a = maxOf(alpha, maxEval)
                if (beta <= a) break
            }
            ttStore(hash, depth, maxEval, origAlpha, beta)
            return maxEval
        } else {
            var minEval = 1e9f
            for (i in 0 until cnt) {
                ChessEngine.applyMove(s, moves[i], u)
                val ev = minimax(s, depth - 1, alpha, beta, u)
                ChessEngine.undoMove(s, moves[i], u)
                minEval = minOf(minEval, ev)
                val b = minOf(beta, minEval)
                if (b <= alpha) break
            }
            ttStore(hash, depth, minEval, origAlpha, beta)
            return minEval
        }
    }

    // ── Quiescence search: only look at captures to avoid horizon effect ──
    private fun quiescence(
        s: ChessEngine.State,
        alpha: Float,
        beta: Float,
        u: ChessEngine.UndoInfo,
        ply: Int
    ): Float {
        val standPat = evaluate(s)
        val isMax = s.turn == 0

        if (isMax) {
            if (standPat >= beta) return beta
            if (standPat > alpha) alpha.also { /* update below */ }
        } else {
            if (standPat <= alpha) return alpha
            if (standPat < beta) beta.also { /* update below */ }
        }

        var a = alpha; var b = beta

        // Generate only capture moves
        val moves = IntArray(256); val caps = IntArray(256)
        val cnt = ChessEngine.legalMoves(s, moves, caps)

        // Filter to captures only
        var capCnt = 0
        for (i in 0 until cnt) {
            if (caps[i] == 1) {
                moves[capCnt] = moves[i]; caps[capCnt] = 1; capCnt++
            }
        }
        if (capCnt == 0) return standPat

        // Max 4 ply of quiescence to avoid explosion
        if (ply >= 4) return standPat

        orderMoves(s, moves, caps, capCnt)

        if (isMax) {
            var maxEval = standPat
            for (i in 0 until capCnt) {
                ChessEngine.applyMove(s, moves[i], u)
                val ev = quiescence(s, a, b, u, ply + 1)
                ChessEngine.undoMove(s, moves[i], u)
                maxEval = maxOf(maxEval, ev)
                a = maxOf(a, maxEval)
                if (b <= a) break
            }
            return maxEval
        } else {
            var minEval = standPat
            for (i in 0 until capCnt) {
                ChessEngine.applyMove(s, moves[i], u)
                val ev = quiescence(s, a, b, u, ply + 1)
                ChessEngine.undoMove(s, moves[i], u)
                minEval = minOf(minEval, ev)
                b = minOf(b, minEval)
                if (b <= a) break
            }
            return minEval
        }
    }

    // ── Endgame detection for null-move pruning ──────────────────────────
    private fun inEndgame(s: ChessEngine.State): Boolean {
        // If both sides have <= 1 minor/major piece beyond king, it's endgame
        for (col in 0..1) {
            var pieces = 0
            for (pt in 0..4) { // P,N,B,R,Q (not K)
                pieces += (ChessEngine.getPcBb(s, pcBbIdx(pt, col))).popCount()
            }
            if (pieces > 3) return false
        }
        return true
    }
}
