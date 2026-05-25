package com.example.smarty.features.games.domain

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * ULTRA-LIGHTWEIGHT CHESS AI (NNUE + LoRA)
 */
class ChessAI(private val context: Context) {
    private val INPUT_DIM = 768
    private val TRANSFORMER_DIM = 256
    private val FC1_DIM = 32
    private val FC2_DIM = 32
    private val LORA_RANK = 8

    private lateinit var transformerW: FloatArray
    private lateinit var transformerB: FloatArray
    private lateinit var fc1W: FloatArray
    private lateinit var fc1B: FloatArray
    private lateinit var fc2W: FloatArray
    private lateinit var fc2B: FloatArray
    private lateinit var evalW: FloatArray
    private var evalB: Float = 0f

    private var loraA = FloatArray(32 * LORA_RANK)
    private var loraB = FloatArray(LORA_RANK * 1)
    private val alpha = 0.01f
    private val baseModelName = "nnue_base_int8.bin"
    private val loraModelName = "lora_profile_int8.bin"
    private val loraPersistentName = "lora_user_profile.bin"

    private val _wf = FloatArray(768)
    private val _bf = FloatArray(768)
    private val _wT = FloatArray(256)
    private val _bT = FloatArray(256)
    private val _combined = FloatArray(512)
    private val _x1 = FloatArray(32)
    private val _x2 = FloatArray(32)

    init {
        loadBaseModel()
        loadLoRAModel()
    }

    private fun loadBaseModel() {
        try {
            context.assets.open(baseModelName).use { stream ->
                val bytes = stream.readBytes()
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val scale = buffer.float
                fun readLayer(size: Int): FloatArray {
                    val arr = FloatArray(size)
                    for (i in 0 until size) arr[i] = buffer.get().toFloat() / scale
                    return arr
                }
                transformerW = readLayer(INPUT_DIM * TRANSFORMER_DIM)
                transformerB = readLayer(TRANSFORMER_DIM)
                fc1W = readLayer(512 * FC1_DIM)
                fc1B = readLayer(FC1_DIM)
                fc2W = readLayer(FC2_DIM * FC2_DIM)
                fc2B = readLayer(FC2_DIM)
                evalW = readLayer(32)
                evalB = buffer.get().toFloat() / scale
            }
        } catch (e: Exception) {}
    }

    private fun loadLoRAModel() {
        try {
            val file = File(context.filesDir, loraPersistentName)
            val inputStream = if (file.exists()) file.inputStream() else context.assets.open(loraModelName)
            inputStream.use { stream ->
                val bytes = stream.readBytes()
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val scale = buffer.float
                for (i in 0 until 32 * LORA_RANK) loraA[i] = buffer.get().toFloat() / scale
                for (i in 0 until LORA_RANK) loraB[i] = buffer.get().toFloat() / scale
            }
        } catch (e: Exception) {}
    }

    fun saveLoRA() {
        try {
            val file = File(context.filesDir, loraPersistentName)
            file.outputStream().use { stream ->
                val maxVal = (loraA.map { Math.abs(it) }.maxOrNull() ?: 1f).coerceAtLeast(loraB.map { Math.abs(it) }.maxOrNull() ?: 1f)
                val scale = 127f / maxVal
                val buffer = ByteBuffer.allocate(4 + loraA.size + loraB.size).order(ByteOrder.LITTLE_ENDIAN)
                buffer.putFloat(scale)
                for (v in loraA) buffer.put((v * scale).toInt().toByte())
                for (v in loraB) buffer.put((v * scale).toInt().toByte())
                stream.write(buffer.array())
            }
        } catch (e: Exception) {}
    }

    suspend fun evaluate(board: List<Any?>): Float {
        val (wf, bf) = boardToFeatures(board)
        transformInto(wf, transformerW, transformerB, 768, 256, _wT)
        transformInto(bf, transformerW, transformerB, 768, 256, _bT)
        System.arraycopy(_wT, 0, _combined, 0, 256)
        System.arraycopy(_bT, 0, _combined, 256, 256)
        layerInto(_combined, fc1W, fc1B, 512, 32, _x1)
        layerInto(_x1, fc2W, fc2B, 32, 32, _x2)
        var score = evalB
        for (i in 0 until 32) score += _x2[i] * evalW[i]
        val loraScore = applyLoRA(_x2)
        val finalScore = (score + loraScore.coerceIn(-0.5f, 0.5f)).coerceIn(-0.99f, 0.99f)
        return (-Math.log(2.0 / (finalScore + 1.0) - 1.0) / 0.00368208).toFloat()
    }

    private suspend fun transformInto(input: FloatArray, weights: FloatArray, bias: FloatArray, inDim: Int, outDim: Int, out: FloatArray) {
        for (j in 0 until outDim) {
            if (j % 64 == 0) kotlinx.coroutines.yield()
            var sum = bias[j]
            for (i in 0 until inDim) if (input[i] > 0) sum += weights[i * outDim + j]
            out[j] = if (sum > 0) sum else 0f
        }
    }

    private suspend fun layerInto(input: FloatArray, weights: FloatArray, bias: FloatArray, inDim: Int, outDim: Int, out: FloatArray) {
        for (j in 0 until outDim) {
            if (j % 8 == 0) kotlinx.coroutines.yield()
            var sum = bias[j]
            for (i in 0 until inDim) sum += input[i] * weights[i * outDim + j]
            out[j] = if (sum > 0) sum else 0f
        }
    }

    private fun applyLoRA(x: FloatArray): Float {
        val mid = FloatArray(LORA_RANK)
        for (j in 0 until LORA_RANK) {
            var sum = 0f
            for (i in 0 until 32) sum += x[i] * loraA[i * LORA_RANK + j]
            mid[j] = sum
        }
        var out = 0f
        for (i in 0 until LORA_RANK) out += mid[i] * loraB[i]
        return out
    }

    fun boardToFeatures(board: List<Any?>): Pair<FloatArray, FloatArray> {
        val wf = FloatArray(768); val bf = FloatArray(768)
        for (r in 0..7) for (f in 0..7) {
            val p = board[r * 8 + f] ?: continue
            val name = p.toString()
            val typeIdx = when { "P" in name -> 0; "N" in name -> 1; "B" in name -> 2; "R" in name -> 3; "Q" in name -> 4; "K" in name -> 5; else -> 0 }
            val colorIdx = if ("W" in name) 0 else 1
            val pIdx = typeIdx + (colorIdx * 6)
            val sq = (7 - r) * 8 + f
            wf[pIdx * 64 + sq] = 1.0f
            val bSq = sq xor 56
            val bPIdx = typeIdx + ((1 - colorIdx) * 6)
            bf[bPIdx * 64 + bSq] = 1.0f
        }
        return wf to bf
    }

    suspend fun updateLearning(board: List<Any?>, targetEval: Float) {
        val (wf, bf) = boardToFeatures(board)
        transformInto(wf, transformerW, transformerB, 768, 256, _wT)
        transformInto(bf, transformerW, transformerB, 768, 256, _bT)
        System.arraycopy(_wT, 0, _combined, 0, 256)
        System.arraycopy(_bT, 0, _combined, 256, 256)
        layerInto(_combined, fc1W, fc1B, 512, 32, _x1)
        layerInto(_x1, fc2W, fc2B, 32, 32, _x2)
        
        val baseScore = evalB + _x2.indices.sumOf { (_x2[it] * evalW[it]).toDouble() }.toFloat()
        val currentLoRA = applyLoRA(_x2)
        val currentEval = baseScore + currentLoRA.coerceIn(-0.5f, 0.5f)
        val error = targetEval - currentEval
        
        val z = FloatArray(LORA_RANK)
        for (j in 0 until LORA_RANK) {
            for (i in 0 until 32) z[j] += _x2[i] * loraA[i * LORA_RANK + j]
        }
        for (j in 0 until LORA_RANK) loraB[j] += alpha * error * z[j]
        for (i in 0 until 32) {
            for (j in 0 until LORA_RANK) {
                loraA[i * LORA_RANK + j] += alpha * error * _x2[i] * loraB[j]
            }
        }
        saveLoRA()
    }

    suspend fun findBestMove(
        board: List<Any?>,
        depth: Int,
        isWhite: Boolean,
        timeLimitMs: Long = 3000L,
        legalMovesProvider: (List<Any?>, Boolean) -> List<Pair<Int, Int>>
    ): Pair<Int, Int>? {
        var overallBestMove: Pair<Int, Int>? = null
        
        try {
            kotlinx.coroutines.withTimeout(timeLimitMs) {
                // Iterative deepening to ensure we always have a move ready if time runs out
                for (currentDepth in 1..depth) {
                    var bestValue = if (isWhite) -1e9f else 1e9f
                    var currentBestMove: Pair<Int, Int>? = null
                    
                    val moves = legalMovesProvider(board, isWhite)
                    if (moves.isEmpty()) break
                    
                    val workBoard = board.toTypedArray()
                    for (move in moves) {
                        kotlinx.coroutines.yield() // Prevent blocking the thread
                        val captured = workBoard[move.second]
                        workBoard[move.second] = workBoard[move.first]
                        workBoard[move.first] = null
                        val value = minimax(workBoard, currentDepth - 1, -1e9f, 1e9f, !isWhite, legalMovesProvider)
                        workBoard[move.first] = workBoard[move.second]
                        workBoard[move.second] = captured
                        
                        if (isWhite) { 
                            if (value > bestValue) { bestValue = value; currentBestMove = move } 
                        } else { 
                            if (value < bestValue) { bestValue = value; currentBestMove = move } 
                        }
                    }
                    if (currentBestMove != null) {
                        overallBestMove = currentBestMove
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Timed out! But we have overallBestMove from the previous depth iteration
        }
        
        // Fallback: if we didn't complete even depth 1, pick the first legal move
        if (overallBestMove == null) {
            val moves = legalMovesProvider(board, isWhite)
            if (moves.isNotEmpty()) return moves.first()
        }
        
        return overallBestMove
    }

    private suspend fun minimax(
        board: Array<Any?>,
        depth: Int,
        alpha: Float,
        beta: Float,
        isWhite: Boolean,
        legalMovesProvider: (List<Any?>, Boolean) -> List<Pair<Int, Int>>
    ): Float {
        kotlinx.coroutines.yield() // Ensure cancellation and thread-sharing is responsive
        if (depth <= 0) return evaluate(board.toList())
        val moves = legalMovesProvider(board.toList(), isWhite)
        if (moves.isEmpty()) return if (isWhite) -1e6f else 1e6f
        var a = alpha; var b = beta
        if (isWhite) {
            var maxEval = -1e9f
            for (move in moves) {
                val captured = board[move.second]; board[move.second] = board[move.first]; board[move.first] = null
                val ev = minimax(board, depth - 1, a, b, false, legalMovesProvider)
                board[move.first] = board[move.second]; board[move.second] = captured
                maxEval = maxOf(maxEval, ev); a = maxOf(a, ev)
                if (b <= a) break
            }
            return maxEval
        } else {
            var minEval = 1e9f
            for (move in moves) {
                val captured = board[move.second]; board[move.second] = board[move.first]; board[move.first] = null
                val ev = minimax(board, depth - 1, a, b, true, legalMovesProvider)
                board[move.first] = board[move.second]; board[move.second] = captured
                minEval = minOf(minEval, ev); b = minOf(b, ev)
                if (b <= a) break
            }
            return minEval
        }
    }
}
