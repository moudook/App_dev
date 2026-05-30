package com.example.smarty.server.agent

/**
 * Thinking Storage Manager — accumulates and manages the complete agent trace for one session.
 * REVISION V5: Gutted — thinking traces are no longer saved or transmitted.
 */
class ThinkingStorageManager {

    suspend fun addReasoning(
        sessionId: String,
        reasoning: String,
        forceNewBlock: Boolean = false,
    ) {
    }

    suspend fun updateToolCall(
        sessionId: String,
        toolCallId: String,
        toolName: String,
        status: String,
        inputSummary: String? = null,
        outputSummary: String? = null,
        searchQueries: List<Pair<String, String?>> = emptyList(),
    ) {
    }

    fun getCompleteThinking(sessionId: String): String = ""

    suspend fun finalizeAndGetThinking(sessionId: String): String = ""

    fun getCurrentThinking(sessionId: String): String = ""

    fun clear(sessionId: String) {
    }
}

object ThinkingStorageManagerSingleton {
    val instance = ThinkingStorageManager()
}
