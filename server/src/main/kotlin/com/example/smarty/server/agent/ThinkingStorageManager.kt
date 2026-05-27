package com.example.smarty.server.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thinking Storage Manager — accumulates and manages the complete agent trace for one session.
 * REVISION V4: Thread-safe non-blocking reads to prevent deadlocks during SSE emission.
 */
class ThinkingStorageManager {
    private val logger = LoggerFactory.getLogger(ThinkingStorageManager::class.java)

    // State container with CopyOnWriteArrayList for thread-safe lock-free reads
    private data class ThinkingState(
        val blocks: CopyOnWriteArrayList<ThinkingBlock> = CopyOnWriteArrayList(),
        var lastUpdated: Long = 0L,
        val writeMutex: Mutex = Mutex(), // Per-session lock for atomic updates
    )

    private val states = ConcurrentHashMap<String, ThinkingState>()

    /** Append a chunk of reasoning text to the current session. */
    suspend fun addReasoning(
        sessionId: String,
        reasoning: String,
        forceNewBlock: Boolean = false,
    ) {
        val state = states.getOrPut(sessionId) { ThinkingState() }
        state.writeMutex.withLock {
            val last = state.blocks.lastOrNull()
            if (!forceNewBlock && last is ThinkingBlock.Reasoning) {
                // In CopyOnWriteArrayList, we replace the element to ensure visibility
                val updated = last.copy(text = last.text + reasoning)
                state.blocks[state.blocks.lastIndex] = updated
            } else {
                state.blocks.add(ThinkingBlock.Reasoning(reasoning))
            }
            state.lastUpdated = System.currentTimeMillis()
        }
    }

    /** Start or Update a tool call in the trace using unique [toolCallId]. */
    suspend fun updateToolCall(
        sessionId: String,
        toolCallId: String,
        toolName: String,
        status: String,
        inputSummary: String? = null,
        outputSummary: String? = null,
        searchQueries: List<Pair<String, String?>> = emptyList(),
    ) {
        val state = states.getOrPut(sessionId) { ThinkingState() }
        state.writeMutex.withLock {
            val existingIndex = state.blocks.indexOfFirst { it is ThinkingBlock.ToolCall && it.id == toolCallId }
            if (existingIndex >= 0) {
                val existing = state.blocks[existingIndex] as ThinkingBlock.ToolCall
                state.blocks[existingIndex] =
                    existing.copy(
                        status = status,
                        inputSummary = inputSummary ?: existing.inputSummary,
                        outputSummary = outputSummary ?: existing.outputSummary,
                        searchQueries = if (searchQueries.isNotEmpty()) searchQueries else existing.searchQueries,
                    )
            } else {
                state.blocks.add(ThinkingBlock.ToolCall(toolCallId, toolName, status, inputSummary, outputSummary, searchQueries))
            }
            state.lastUpdated = System.currentTimeMillis()
        }
    }

    /** Lock-free read of the complete thinking trace. Safe to call during SSE emission. */
    fun getCompleteThinking(sessionId: String): String {
        val state = states[sessionId] ?: return ""
        // No lock needed for reading CopyOnWriteArrayList
        return serialiseBlocks(state.blocks)
    }

    suspend fun finalizeAndGetThinking(sessionId: String): String = getCompleteThinking(sessionId)

    fun getCurrentThinking(sessionId: String): String = getCompleteThinking(sessionId)

    fun clear(sessionId: String) {
        states.remove(sessionId)
    }

    private fun serialiseBlocks(blocks: List<ThinkingBlock>): String {
        if (blocks.isEmpty()) return ""
        val sb = StringBuilder("SMARTY_TRACE_V2:[")
        blocks.forEachIndexed { index, block ->
            if (index > 0) sb.append(',')
            when (block) {
                is ThinkingBlock.Reasoning -> {
                    sb.append("{\"type\":\"reasoning\",\"text\":")
                    appendJsonString(sb, block.text)
                    sb.append('}')
                }
                is ThinkingBlock.ToolCall -> {
                    sb.append("{\"type\":\"tool\",\"id\":")
                    appendJsonString(sb, block.id)
                    sb.append(",\"name\":")
                    appendJsonString(sb, block.toolName)
                    sb.append(",\"status\":")
                    appendJsonString(sb, block.status)
                    block.inputSummary?.let {
                        sb.append(",\"input\":")
                        appendJsonString(sb, it)
                    }
                    block.outputSummary?.let {
                        sb.append(",\"output\":")
                        appendJsonString(sb, it)
                    }
                    if (block.searchQueries.isNotEmpty()) {
                        sb.append(",\"queries\":[")
                        block.searchQueries.forEachIndexed { qi, (q, r) ->
                            if (qi > 0) sb.append(',')
                            sb.append("{\"q\":")
                            appendJsonString(sb, q)
                            r?.let {
                                sb.append(",\"r\":")
                                appendJsonString(sb, it)
                            }
                            sb.append('}')
                        }
                        sb.append(']')
                    }
                    sb.append('}')
                }
            }
        }
        sb.append(']')
        return sb.toString()
    }

    private fun appendJsonString(
        sb: StringBuilder,
        value: String,
    ) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u${c.code.toString(16).padStart(4, '0')}") else sb.append(c)
            }
        }
        sb.append('"')
    }

    private sealed class ThinkingBlock {
        data class Reasoning(
            val text: String,
        ) : ThinkingBlock()

        data class ToolCall(
            val id: String,
            val toolName: String,
            val status: String,
            val inputSummary: String? = null,
            val outputSummary: String? = null,
            val searchQueries: List<Pair<String, String?>> = emptyList(),
        ) : ThinkingBlock()
    }
}

object ThinkingStorageManagerSingleton {
    val instance = ThinkingStorageManager()
}
