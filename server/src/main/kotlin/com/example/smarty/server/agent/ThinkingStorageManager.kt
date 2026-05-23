package com.example.smarty.server.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Thinking Storage Manager — accumulates and manages the complete agent trace for one session.
 *
 * Stores a time-ordered list of "blocks":
 *  - ReasoningBlock  : a chunk of LLM reasoning text
 *  - ToolCallBlock   : a tool call (name, input summary, output summary, status)
 *
 * REVISION V3: Supports block updates via unique IDs to prevent "combining thinking"
 * and "hidden tool calls" during streaming.
 */
class ThinkingStorageManager {
    private val logger = LoggerFactory.getLogger(ThinkingStorageManager::class.java)

    private val states = ConcurrentHashMap<String, ThinkingState>()
    private val mutex = Mutex()

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** Append a chunk of reasoning text to the current session. */
    suspend fun addReasoning(
        sessionId: String,
        reasoning: String,
        forceNewBlock: Boolean = false,
    ) {
        mutex.withLock {
            val state = states.getOrPut(sessionId) { ThinkingState() }
            val last = state.blocks.lastOrNull()
            
            if (!forceNewBlock && last is ThinkingBlock.Reasoning) {
                state.blocks[state.blocks.lastIndex] =
                    last.copy(text = last.text + reasoning)
            } else {
                state.blocks.add(ThinkingBlock.Reasoning(reasoning))
            }
            state.lastUpdated = System.currentTimeMillis()
        }
    }

    /**
     * Start or Update a tool call in the trace.
     * Uses [toolCallId] to ensure we update the correct block instead of creating duplicates.
     */
    suspend fun updateToolCall(
        sessionId: String,
        toolCallId: String,
        toolName: String,
        status: String,
        inputSummary: String? = null,
        outputSummary: String? = null,
        searchQueries: List<Pair<String, String?>> = emptyList(),
    ) {
        mutex.withLock {
            val state = states.getOrPut(sessionId) { ThinkingState() }
            
            val existingIndex = state.blocks.indexOfFirst { 
                it is ThinkingBlock.ToolCall && it.id == toolCallId 
            }

            if (existingIndex >= 0) {
                val existing = state.blocks[existingIndex] as ThinkingBlock.ToolCall
                state.blocks[existingIndex] = existing.copy(
                    status = status,
                    inputSummary = inputSummary ?: existing.inputSummary,
                    outputSummary = outputSummary ?: existing.outputSummary,
                    searchQueries = if (searchQueries.isNotEmpty()) searchQueries else existing.searchQueries
                )
            } else {
                state.blocks.add(
                    ThinkingBlock.ToolCall(
                        id = toolCallId,
                        toolName = toolName,
                        status = status,
                        inputSummary = inputSummary,
                        outputSummary = outputSummary,
                        searchQueries = searchQueries,
                    )
                )
            }
            state.lastUpdated = System.currentTimeMillis()
        }
    }

    /** Backward compatibility wrapper for old addToolCall calls */
    suspend fun addToolCall(
        sessionId: String,
        toolName: String,
        status: String,
        inputSummary: String? = null,
        outputSummary: String? = null,
        searchQueries: List<Pair<String, String?>> = emptyList(),
    ) {
        updateToolCall(
            sessionId = sessionId,
            toolCallId = "legacy-${System.currentTimeMillis()}",
            toolName = toolName,
            status = status,
            inputSummary = inputSummary,
            outputSummary = outputSummary,
            searchQueries = searchQueries
        )
    }

    /** Build and return the complete thinking trace as a serialised JSON string. */
    suspend fun getCompleteThinking(sessionId: String): String {
        return mutex.withLock {
            val state = states[sessionId] ?: return@withLock ""
            serialiseBlocks(state.blocks)
        }
    }

    /** Alias for getCompleteThinking — call once before emitting the final Result event. */
    suspend fun finalizeAndGetThinking(sessionId: String): String {
        val thinking = getCompleteThinking(sessionId)
        return thinking
    }

    /** Get current thinking without finalizing (for progressive save during streaming). */
    suspend fun getCurrentThinking(sessionId: String): String {
        return getCompleteThinking(sessionId)
    }

    /** Free memory for the given session. */
    suspend fun clear(sessionId: String) {
        mutex.withLock { states.remove(sessionId) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

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
                    sb.append("{\"type\":\"tool\"")
                    sb.append(",\"id\":")
                    appendJsonString(sb, block.id)
                    sb.append(",\"name\":")
                    appendJsonString(sb, block.toolName)
                    sb.append(",\"status\":")
                    appendJsonString(sb, block.status)
                    if (block.inputSummary != null) {
                        sb.append(",\"input\":")
                        appendJsonString(sb, block.inputSummary)
                    }
                    if (block.outputSummary != null) {
                        sb.append(",\"output\":")
                        appendJsonString(sb, block.outputSummary)
                    }
                    if (block.searchQueries.isNotEmpty()) {
                        sb.append(",\"queries\":[")
                        block.searchQueries.forEachIndexed { qi, (q, r) ->
                            if (qi > 0) sb.append(',')
                            sb.append("{\"q\":")
                            appendJsonString(sb, q)
                            if (r != null) {
                                sb.append(",\"r\":")
                                appendJsonString(sb, r)
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

    private fun appendJsonString(sb: StringBuilder, value: String) {
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

    private data class ThinkingState(
        val blocks: MutableList<ThinkingBlock> = mutableListOf(),
        var lastUpdated: Long = 0L,
    )

    private sealed class ThinkingBlock {
        data class Reasoning(val text: String) : ThinkingBlock()
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
