package com.example.smarty.features.games.domain

import android.content.Context
import java.io.File
import kotlin.random.Random

/**
 * HYPER-AGGRESSIVE NANO-ENGINE (Black Hat / Amortal Edition)
 * - Symmetry Expansion: Learning one move learns all 8 equivalent board positions.
 * - Monte Carlo Propagation: Final rewards hit all moves instantly for rapid adaptation.
 * - Dynamic Alpha: Spikes learning rate on loss to pivot strategy immediately.
 */
class TicTacToeAI(
    private val context: Context,
) {
    private var states = IntArray(0)
    private var qValues = ByteArray(0)

    private var alpha = 0.35f
    private val gamma = 0.95f
    private val epsilon = 0.10f // Lowered to trust learned weights more
    private val modelFileName = "tictactoe_nano_v4.bin"

    // Symmetry Mapping Tables
    private val rot90 = intArrayOf(6, 3, 0, 7, 4, 1, 8, 5, 2)
    private val flipH = intArrayOf(2, 1, 0, 5, 4, 3, 8, 7, 6)

    init {
        loadModel()
    }

    fun activate() {}

    fun deactivate() {
        saveModel()
    }

    /**
     * Perspective-Correct Bitboard
     * bits 0-8: SELF positions
     * bits 9-17: OPPONENT positions
     */
    private fun getBitboard(
        board: List<String?>,
        perspectiveO: Boolean,
    ): Int {
        var selfBits = 0
        var oppBits = 0
        val selfSymbol = if (perspectiveO) "O" else "X"
        val oppSymbol = if (perspectiveO) "X" else "O"

        for (i in 0 until 9) {
            when (board[i]) {
                selfSymbol -> selfBits = selfBits or (1 shl i)
                oppSymbol -> oppBits = oppBits or (1 shl i)
            }
        }
        return (oppBits shl 9) or selfBits
    }

    private fun transformBitboard(
        bb: Int,
        map: IntArray,
    ): Int {
        val opp = (bb shr 9) and 0x1FF
        val self = bb and 0x1FF
        var newOpp = 0
        var newSelf = 0
        for (i in 0 until 9) {
            if ((opp shr i) and 1 == 1) newOpp = newOpp or (1 shl map[i])
            if ((self shr i) and 1 == 1) newSelf = newSelf or (1 shl map[i])
        }
        return (newOpp shl 9) or newSelf
    }

    private fun getAllSymmetries(
        bb: Int,
        action: Int,
    ): List<Pair<Int, Int>> {
        val syms = mutableListOf<Pair<Int, Int>>()
        var curBB = bb
        var curAct = action

        repeat(4) {
            syms.add(curBB to curAct)
            // Rotate
            curBB = transformBitboard(curBB, rot90)
            curAct = rot90[curAct]
        }

        // Flip and repeat
        curBB = transformBitboard(bb, flipH)
        curAct = flipH[action]
        repeat(4) {
            syms.add(curBB to curAct)
            curBB = transformBitboard(curBB, rot90)
            curAct = rot90[curAct]
        }
        return syms
    }

    private fun loadModel() {
        val file = File(context.filesDir, modelFileName)
        if (!file.exists()) return
        try {
            val bytes = file.readBytes()
            if (bytes.size < 4) return
            val numStates =
                (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8) or
                    ((bytes[2].toInt() and 0xFF) shl 16) or ((bytes[3].toInt() and 0xFF) shl 24)
            states = IntArray(numStates)
            qValues = ByteArray(numStates * 9)
            var offset = 4
            for (i in 0 until numStates) {
                states[i] = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 16) or ((bytes[offset + 3].toInt() and 0xFF) shl 24)
                offset += 4
                System.arraycopy(bytes, offset, qValues, i * 9, 9)
                offset += 9
            }
        } catch (e: Exception) {
        }
    }

    private fun saveModel() {
        val file = File(context.filesDir, modelFileName)
        try {
            val numStates = states.size
            val bytes = ByteArray(4 + numStates * 13)
            bytes[0] = (numStates and 0xFF).toByte()
            bytes[1] = ((numStates ushr 8) and 0xFF).toByte()
            bytes[2] = ((numStates ushr 16) and 0xFF).toByte()
            bytes[3] = ((numStates ushr 24) and 0xFF).toByte()
            var offset = 4
            for (i in 0 until numStates) {
                val s = states[i]
                bytes[offset] = (s and 0xFF).toByte()
                bytes[offset + 1] = ((s ushr 8) and 0xFF).toByte()
                bytes[offset + 2] = ((s ushr 16) and 0xFF).toByte()
                bytes[offset + 3] = ((s ushr 24) and 0xFF).toByte()
                offset += 4
                System.arraycopy(qValues, i * 9, bytes, offset, 9)
                offset += 9
            }
            file.writeBytes(bytes)
        } catch (e: Exception) {
        }
    }

    private fun getQ(
        stateIdx: Int,
        action: Int,
    ): Float = if (stateIdx < 0) 0f else qValues[stateIdx * 9 + action].toFloat() / 127f

    private fun setQ(
        stateIdx: Int,
        action: Int,
        value: Float,
    ) {
        qValues[stateIdx * 9 + action] = (value.coerceIn(-1.5f, 1.5f) * 80).toInt().toByte()
    }

    private fun ensureState(state: Int): Int {
        val idx = states.binarySearch(state)
        if (idx >= 0) return idx
        val insert = -(idx + 1)
        val nS = IntArray(states.size + 1)
        val nQ = ByteArray(qValues.size + 9)
        System.arraycopy(states, 0, nS, 0, insert)
        nS[insert] = state
        System.arraycopy(states, insert, nS, insert + 1, states.size - insert)
        System.arraycopy(qValues, 0, nQ, 0, insert * 9)
        System.arraycopy(qValues, insert * 9, nQ, (insert + 1) * 9, (states.size - insert) * 9)
        states = nS
        qValues = nQ
        return insert
    }

    fun getBestMove(board: List<String?>): Int {
        // Stochastic "Chaos" factor
        if (Random.nextFloat() < epsilon) {
            val available = board.indices.filter { board[it] == null }
            if (available.isNotEmpty()) return available.random()
        }

        // Tactical Heaven Layer
        val win = findImmediateWin(board, "O")
        if (win != -1) return win
        val block = findImmediateWin(board, "X")
        if (block != -1) return block

        val bb = getBitboard(board, perspectiveO = true)
        val idx = states.binarySearch(bb)

        var best = -1
        var maxQ = -Float.MAX_VALUE
        for (i in 0 until 9) {
            if (board[i] == null) {
                val q = getQ(idx, i)
                if (q > maxQ) {
                    maxQ = q
                    best = i
                }
            }
        }
        return if (best != -1) best else board.indices.filter { board[it] == null }.random()
    }

    private fun findImmediateWin(
        board: List<String?>,
        p: String,
    ): Int {
        val lines = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 3, 6, 1, 4, 7, 2, 5, 8, 0, 4, 8, 2, 4, 6)
        for (i in lines.indices step 3) {
            val a = lines[i]
            val b = lines[i + 1]
            val c = lines[i + 2]
            var cP = 0
            var nI = -1
            if (board[a] == p) {
                cP++
            } else if (board[a] == null) {
                nI = a
            }
            if (board[b] == p) {
                cP++
            } else if (board[b] == null) {
                nI = b
            }
            if (board[c] == p) {
                cP++
            } else if (board[c] == null) {
                nI = c
            }
            if (cP == 2 && nI != -1) return nI
        }
        return -1
    }

    fun updateModel(
        aiHistory: List<Pair<List<String?>, Int>>,
        userHistory: List<Pair<List<String?>, Int>>,
        reward: Float,
    ) {
        // Spike alpha on loss to pivot strategy aggressively
        val effectiveAlpha = if (reward < -1.0f) 0.5f else 0.25f

        // 1. AI Learning
        applyAggressiveUpdate(aiHistory, reward, perspectiveO = true, alpha = effectiveAlpha)

        // 2. Mirror Learning (Steal User's strategy)
        if (reward < -1.0f) {
            applyAggressiveUpdate(userHistory, 1.0f, perspectiveO = false, alpha = effectiveAlpha)
        }

        saveModel()
    }

    private fun applyAggressiveUpdate(
        seq: List<Pair<List<String?>, Int>>,
        finalR: Float,
        perspectiveO: Boolean,
        alpha: Float,
    ) {
        // Monte Carlo: Propagate final result backwards with discount
        var discountReward = finalR
        for (i in seq.indices.reversed()) {
            val (board, action) = seq[i]
            val bb = getBitboard(board, perspectiveO)

            // SYMMETRY EXPANSION: Update all 8 versions of this move
            val syms = getAllSymmetries(bb, action)
            for ((sBB, sAct) in syms) {
                val idx = ensureState(sBB)
                val currentQ = getQ(idx, sAct)
                val newQ = currentQ + alpha * (discountReward - currentQ)
                setQ(idx, sAct, newQ)
            }

            discountReward *= gamma // Move back in time
        }
    }
}
