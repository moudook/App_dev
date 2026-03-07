package com.example.smarty.server.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * COMPLETELY REWRITTEN: Thinking Section Storage Manager
 * 
 * Purpose: Accumulate and manage the complete thinking content including:
 * - LLM reasoning content (from reasoning_content field or <think> tags)
 * - Tool call information (action name + status)
 * - Complete audit trail of AI decision-making
 * 
 * Architecture:
 * - Session-scoped accumulation (one ThinkingState per conversation)
 * - Thread-safe mutex protection
 * - Explicit finalization before storage
 * - Clear separation between accumulation and emission
 */
class ThinkingStorageManager {
    private val logger = LoggerFactory.getLogger(ThinkingStorageManager::class.java)
    
    /**
     * Thread-safe map of session states
     * Key: sessionId (from user message hash)
     * Value: ThinkingState with accumulated content
     */
    private val states = ConcurrentHashMap<String, ThinkingState>()
    private val mutex = Mutex()
    
    /**
     * Get or create thinking state for a session (internal use)
     */
    private suspend fun getState(sessionId: String): ThinkingState {
        return mutex.withLock {
            states.getOrPut(sessionId) { ThinkingState() }
        }
    }
    
    /**
     * Add reasoning content to thinking
     */
    suspend fun addReasoning(sessionId: String, reasoning: String) {
        mutex.withLock {
            val state = states.getOrPut(sessionId) { ThinkingState() }
            state.reasoningBuilder.append(reasoning)
            state.lastUpdated = System.currentTimeMillis()
        }
    }
    
    /**
     * Add tool call to thinking
     */
    suspend fun addToolCall(sessionId: String, toolName: String, status: String) {
        mutex.withLock {
            val state = states.getOrPut(sessionId) { ThinkingState() }
            state.toolCalls.add(ToolCallInfo(toolName, status, System.currentTimeMillis()))
            state.lastUpdated = System.currentTimeMillis()
        }
    }
    
    /**
     * Get complete thinking content (reasoning + tool calls)
     */
    suspend fun getCompleteThinking(sessionId: String): String {
        return mutex.withLock {
            val state = states[sessionId] ?: return@withLock ""
            
            val builder = StringBuilder()
            
            // Add reasoning if present
            if (state.reasoningBuilder.isNotEmpty()) {
                builder.appendLine(state.reasoningBuilder.toString().trim())
            }
            
            // Add tool calls if present
            if (state.toolCalls.isNotEmpty()) {
                if (builder.isNotEmpty()) builder.appendLine()
                state.toolCalls.forEach { toolCall ->
                    builder.appendLine("[Action: ${toolCall.name} (${toolCall.status})]")
                }
            }
            
            builder.toString().trim()
        }
    }
    
    /**
     * Finalize and get thinking for storage
     * Call this ONCE before saving to database
     */
    suspend fun finalizeAndGetThinking(sessionId: String): String {
        val thinking = getCompleteThinking(sessionId)
        logger.info("Finalized thinking for session $sessionId: length=${thinking.length}, hasToolCalls=${thinking.contains("[Action:")}")
        return thinking
    }
    
    /**
     * Clear thinking state after storage
     * Call this after saving to database to free memory
     */
    suspend fun clear(sessionId: String) {
        mutex.withLock {
            states.remove(sessionId)
        }
    }
    
    /**
     * Get thinking state info for debugging
     */
    suspend fun getStateInfo(sessionId: String): ThinkingStateInfo {
        return mutex.withLock {
            val state = states[sessionId] ?: return@withLock ThinkingStateInfo(0, 0, 0)
            ThinkingStateInfo(
                reasoningLength = state.reasoningBuilder.length,
                toolCallsCount = state.toolCalls.size,
                lastUpdated = state.lastUpdated
            )
        }
    }
    
    /**
     * Thinking state holder (internal)
     */
    private data class ThinkingState(
        val reasoningBuilder: StringBuilder = StringBuilder(),
        val toolCalls: MutableList<ToolCallInfo> = mutableListOf(),
        var lastUpdated: Long = 0L
    )
    
    /**
     * Tool call information
     */
    private data class ToolCallInfo(
        val name: String,
        val status: String, // "completed", "failed", "started"
        val timestamp: Long
    )
    
    /**
     * State info for debugging
     */
    data class ThinkingStateInfo(
        val reasoningLength: Int,
        val toolCallsCount: Int,
        val lastUpdated: Long
    )
}

/**
 * Singleton instance for global access
 */
object ThinkingStorageManagerSingleton {
    val instance = ThinkingStorageManager()
}
