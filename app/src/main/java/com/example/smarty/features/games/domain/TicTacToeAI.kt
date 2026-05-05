package com.example.smarty.features.games.domain

import android.content.Context
import java.io.File

/**
 * ABSOLUTE PINNACLE: Nano-level Binary Optimization (Black Hat Level)
 * - 0 Object overhead: Uses contiguous primitive arrays instead of HashMaps.
 * - 13 Bytes per state: Int (4 bytes) for bitboard state + 9 Bytes for Quantized Q-Values.
 * - Total RAM Footprint: < 70 KB (Max possible states = 5478).
 * - In-place binary search for O(log N) lookup.
 * - Quantizes Q-values from Float [-1.0, 1.0] to Byte [-127, 127] mapping.
 */
class TicTacToeAI(private val context: Context) {
    // Flattened primitive arrays. No Objects.
    private var states = IntArray(0)
    private var qValues = ByteArray(0) // 9 bytes per state
    
    private val alpha = 0.15f
    private val gamma = 0.95f
    private val modelFileName = "tictactoe_nano_v3.bin"

    init {
        loadModel()
    }

    /**
     * 18-bit Bitboard representation. Fits beautifully into a 32-bit Int.
     * xBits (9 bits) | oBits (9 bits)
     */
    private fun getBitboard(board: List<String?>): Int {
        var xBits = 0
        var oBits = 0
        for (i in 0 until 9) {
            if (board[i] == "X") xBits = xBits or (1 shl i)
            if (board[i] == "O") oBits = oBits or (1 shl i)
        }
        return (xBits shl 9) or oBits
    }

    private fun loadModel() {
        val file = File(context.filesDir, modelFileName)
        if (!file.exists()) return

        try {
            val bytes = file.readBytes()
            if (bytes.size < 4) return
            // Read Little Endian Int
            val numStates = (bytes[0].toInt() and 0xFF) or 
                            ((bytes[1].toInt() and 0xFF) shl 8) or 
                            ((bytes[2].toInt() and 0xFF) shl 16) or 
                            ((bytes[3].toInt() and 0xFF) shl 24)
            
            val expectedSize = 4 + numStates * 13
            if (bytes.size != expectedSize) return

            states = IntArray(numStates)
            qValues = ByteArray(numStates * 9)

            var offset = 4
            for (i in 0 until numStates) {
                states[i] = (bytes[offset].toInt() and 0xFF) or 
                            ((bytes[offset+1].toInt() and 0xFF) shl 8) or 
                            ((bytes[offset+2].toInt() and 0xFF) shl 16) or 
                            ((bytes[offset+3].toInt() and 0xFF) shl 24)
                offset += 4
                System.arraycopy(bytes, offset, qValues, i * 9, 9)
                offset += 9
            }
        } catch (e: Exception) {
            // Failsafe: Start fresh if binary is corrupted
            states = IntArray(0)
            qValues = ByteArray(0)
        }
    }

    private fun saveModel() {
        val file = File(context.filesDir, modelFileName)
        try {
            val numStates = states.size
            val bytes = ByteArray(4 + numStates * 13)
            
            // Write Little Endian Int
            bytes[0] = (numStates and 0xFF).toByte()
            bytes[1] = ((numStates ushr 8) and 0xFF).toByte()
            bytes[2] = ((numStates ushr 16) and 0xFF).toByte()
            bytes[3] = ((numStates ushr 24) and 0xFF).toByte()
            
            var offset = 4
            for (i in 0 until numStates) {
                val state = states[i]
                bytes[offset] = (state and 0xFF).toByte()
                bytes[offset+1] = ((state ushr 8) and 0xFF).toByte()
                bytes[offset+2] = ((state ushr 16) and 0xFF).toByte()
                bytes[offset+3] = ((state ushr 24) and 0xFF).toByte()
                offset += 4
                
                System.arraycopy(qValues, i * 9, bytes, offset, 9)
                offset += 9
            }
            file.writeBytes(bytes)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Convert Byte [-127, 127] back to Float [-1.0, 1.0]
    private fun getQ(stateIndex: Int, action: Int): Float {
        if (stateIndex < 0) return 0f
        return qValues[stateIndex * 9 + action].toFloat() / 127f
    }

    // Quantize Float to Byte [-127, 127]
    private fun setQ(stateIndex: Int, action: Int, value: Float) {
        val quantized = (value.coerceIn(-1f, 1f) * 127).toInt().toByte()
        qValues[stateIndex * 9 + action] = quantized
    }

    /**
     * O(log N) lookup. If state doesn't exist, it allocates and arraycopies.
     * Since N is guaranteed <= 5478, shifting 70KB in contiguous memory is nanosecond-fast,
     * far outperforming the JVM Garbage Collection overhead of a standard HashMap.
     */
    private fun ensureState(state: Int): Int {
        val idx = states.binarySearch(state)
        if (idx >= 0) return idx // Found
        
        // Not found, binarySearch returns -(insertion_point) - 1
        val insertPoint = -(idx + 1)
        val newStates = IntArray(states.size + 1)
        val newQValues = ByteArray(qValues.size + 9)
        
        // Shift states
        System.arraycopy(states, 0, newStates, 0, insertPoint)
        newStates[insertPoint] = state
        System.arraycopy(states, insertPoint, newStates, insertPoint + 1, states.size - insertPoint)
        
        // Shift Q-Values (The 9 bytes at the insert point implicitly default to 0)
        System.arraycopy(qValues, 0, newQValues, 0, insertPoint * 9)
        System.arraycopy(qValues, insertPoint * 9, newQValues, (insertPoint + 1) * 9, (states.size - insertPoint) * 9)
        
        states = newStates
        qValues = newQValues
        
        return insertPoint
    }

    fun getBestMove(board: List<String?>): Int {
        val state = getBitboard(board)
        val idx = states.binarySearch(state)
        
        // Heaven Layer: Immediate Heuristics (0 lookup cost)
        val winningMove = findImmediateWin(board, "O")
        if (winningMove != -1) return winningMove
        val blockingMove = findImmediateWin(board, "X")
        if (blockingMove != -1) return blockingMove

        var bestMove = -1
        var maxQ = -Float.MAX_VALUE

        for (i in 0 until 9) {
            if (board[i] == null) {
                val q = getQ(idx, i)
                if (q > maxQ) {
                    maxQ = q
                    bestMove = i
                }
            }
        }

        return if (bestMove != -1) bestMove else board.indices.filter { board[it] == null }.random()
    }

    private fun findImmediateWin(board: List<String?>, player: String): Int {
        // Flattened 1D representation of winning lines
        val lines = intArrayOf(
            0,1,2, 3,4,5, 6,7,8, // rows
            0,3,6, 1,4,7, 2,5,8, // cols
            0,4,8, 2,4,6         // diags
        )
        for (i in lines.indices step 3) {
            val a = lines[i]; val b = lines[i+1]; val c = lines[i+2]
            val sA = board[a]; val sB = board[b]; val sC = board[c]
            
            var pCount = 0
            var nullIdx = -1
            
            if (sA == player) pCount++ else if (sA == null) nullIdx = a
            if (sB == player) pCount++ else if (sB == null) nullIdx = b
            if (sC == player) pCount++ else if (sC == null) nullIdx = c
            
            if (pCount == 2 && nullIdx != -1) return nullIdx
        }
        return -1
    }

    fun updateModel(history: List<Pair<List<String?>, Int>>, reward: Float) {
        var nextMaxQ = 0f
        
        for (i in history.indices.reversed()) {
            val (boardState, action) = history[i]
            val state = getBitboard(boardState)
            val idx = ensureState(state) // Ultra-fast contiguous array expansion
            
            val currentReward = if (i == history.size - 1) reward else 0f
            val currentQ = getQ(idx, action)
            val newQ = currentQ + alpha * (currentReward + gamma * nextMaxQ - currentQ)
            
            setQ(idx, action, newQ)
            
            nextMaxQ = -Float.MAX_VALUE
            for (a in 0 until 9) {
                if (boardState[a] == null) {
                    val q = getQ(idx, a)
                    if (q > nextMaxQ) nextMaxQ = q
                }
            }
        }
        saveModel()
    }
}
