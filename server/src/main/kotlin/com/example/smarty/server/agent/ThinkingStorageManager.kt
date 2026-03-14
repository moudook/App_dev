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
 * The accumulated blocks are serialised as a compact JSON string that the client can
 * deserialise into a proper object tree for rich UI rendering.
 *
 * Format emitted for the `thinking` field:
 * ```
 * SMARTY_TRACE_V2:[{"type":"reasoning","text":"..."},{"type":"tool","name":"search_web",
 * "status":"completed","input":"What is...","output":"Wikipedia says..."},...]
 * ```
 *
 * Backward compat: if the client can't parse the prefix/JSON it falls back to plain text.
 */
class ThinkingStorageManager {
    private val logger = LoggerFactory.getLogger(ThinkingStorageManager::class.java)

    private val states = ConcurrentHashMap<String, ThinkingState>()
    private val mutex = Mutex()

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** Append a chunk of reasoning text to the current session. */
    suspend fun addReasoning(sessionId: String, reasoning: String) {
        mutex.withLock {
            val state = states.getOrPut(sessionId) { ThinkingState() }
            // Append to the last reasoning block if one is already open; else create new
            val last = state.blocks.lastOrNull()
            if (last is ThinkingBlock.Reasoning) {
                state.blocks[state.blocks.lastIndex] =
                    last.copy(text = last.text + reasoning)
            } else {
                state.blocks.add(ThinkingBlock.Reasoning(reasoning))
            }
            state.lastUpdated = System.currentTimeMillis()
        }
    }

    /**
     * Record a tool call.
     *
     * @param sessionId  session key
     * @param toolName   machine name of the tool (e.g. "search_web")
     * @param status     "started" | "completed" | "failed"
     * @param inputSummary  human-readable description of what was sent to the tool
     *                      (e.g. the search query)
     * @param outputSummary abbreviated result returned by the tool (first ~800 chars)
     * @param searchQueries for parallel web searches: individual query→result pairs
     */
    suspend fun addToolCall(
        sessionId: String,
        toolName: String,
        status: String,
        inputSummary: String? = null,
        outputSummary: String? = null,
        searchQueries: List<Pair<String, String?>> = emptyList()
    ) {
        mutex.withLock {
            val state = states.getOrPut(sessionId) { ThinkingState() }
            state.blocks.add(
                ThinkingBlock.ToolCall(
                    toolName = toolName,
                    status = status,
                    inputSummary = inputSummary,
                    outputSummary = outputSummary,
                    searchQueries = searchQueries
                )
            )
            state.lastUpdated = System.currentTimeMillis()
        }
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
        logger.info("Finalised thinking for session $sessionId: blocks=${getBlockCount(sessionId)}, len=${thinking.length}")
        return thinking
    }

    /** Free memory for the given session. */
    suspend fun clear(sessionId: String) {
        mutex.withLock { states.remove(sessionId) }
    }

    /** Debugging helper. */
    suspend fun getStateInfo(sessionId: String): ThinkingStateInfo {
        return mutex.withLock {
            val state = states[sessionId]
                ?: return@withLock ThinkingStateInfo(0, 0, 0)
            ThinkingStateInfo(
                reasoningLength = state.blocks.filterIsInstance<ThinkingBlock.Reasoning>()
                    .sumOf { it.text.length },
                toolCallsCount = state.blocks.filterIsInstance<ThinkingBlock.ToolCall>().size,
                lastUpdated = state.lastUpdated
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun getBlockCount(sessionId: String): Int =
        mutex.withLock { states[sessionId]?.blocks?.size ?: 0 }

    /**
     * Serialise blocks into the SMARTY_TRACE_V2 format.
     *
     * Uses hand-built JSON to avoid a serialisation library dependency in the server
     * and keep the format 100% stable.
     */
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
                    sb.append(",\"name\":"); appendJsonString(sb, block.toolName)
                    sb.append(",\"status\":"); appendJsonString(sb, block.status)
                    if (block.inputSummary != null) {
                        sb.append(",\"input\":"); appendJsonString(sb, block.inputSummary)
                    }
                    if (block.outputSummary != null) {
                        sb.append(",\"output\":"); appendJsonString(sb, block.outputSummary)
                    }
                    if (block.searchQueries.isNotEmpty()) {
                        sb.append(",\"queries\":[")
                        block.searchQueries.forEachIndexed { qi, (q, r) ->
                            if (qi > 0) sb.append(',')
                            sb.append("{\"q\":"); appendJsonString(sb, q)
                            if (r != null) { sb.append(",\"r\":"); appendJsonString(sb, r) }
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

    /** Append a JSON-safe quoted string to the StringBuilder. */
    private fun appendJsonString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"'  -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u${c.code.toString(16).padStart(4, '0')}")
                        else sb.append(c)
            }
        }
        sb.append('"')
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal types
    // ─────────────────────────────────────────────────────────────────────────

    private data class ThinkingState(
        val blocks: MutableList<ThinkingBlock> = mutableListOf(),
        var lastUpdated: Long = 0L
    )

    private sealed class ThinkingBlock {
        data class Reasoning(val text: String) : ThinkingBlock()
        data class ToolCall(
            val toolName: String,
            val status: String,
            val inputSummary: String? = null,
            val outputSummary: String? = null,
            val searchQueries: List<Pair<String, String?>> = emptyList()
        ) : ThinkingBlock()
    }

    /** Public debugging snapshot */
    data class ThinkingStateInfo(
        val reasoningLength: Int,
        val toolCallsCount: Int,
        val lastUpdated: Long
    )
}

/** Singleton instance. */
object ThinkingStorageManagerSingleton {
    val instance = ThinkingStorageManager()
}
