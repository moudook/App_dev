package com.example.smarty.agent

import android.util.Log
import com.example.smarty.agent.execution.ParallelToolExecutor
import com.example.smarty.agent.prompts.ToolExampleStore
import com.example.smarty.data.cache.SemanticCache
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.remote.EmbeddingService
import com.example.smarty.util.HistoryCompressor
import com.example.smarty.util.PIIMasker

/**
 * Agent Optimizer - integrates all optimization components.
 *
 * Provides a unified interface for:
 * - PII masking/unmasking (privacy)
 * - History compression (token reduction)
 * - Semantic caching (API call reduction)
 * - Dynamic few-shot examples (tool selection accuracy)
 * - Parallel tool execution (2-3x speedup)
 *
 * Usage:
 * ```kotlin
 * val optimizer = AgentOptimizer(openAiApiKey)
 *
 * // Before sending to LLM
 * val processed = optimizer.preProcess(userQuery, history)
 * if (processed.cacheHit != null) return processed.cacheHit  // Skip API call!
 *
 * // Get relevant tool examples for prompt
 * val examples = optimizer.getToolExamples(userQuery)
 *
 * // After receiving response
 * val finalResponse = optimizer.postProcess(query, maskedQuery, llmResponse)
 * ```
 */
class AgentOptimizer(
    openAiApiKey: String? = null,
    enableSemanticCache: Boolean = true,
    enablePiiMasking: Boolean = true,
    enableHistoryCompression: Boolean = true,
    enableFewShotExamples: Boolean = true
) {
    companion object {
        private const val TAG = "AgentOptimizer"
    }

    // Feature flags
    private val piiEnabled = enablePiiMasking
    private val compressionEnabled = enableHistoryCompression
    private val cacheEnabled = enableSemanticCache && !openAiApiKey.isNullOrBlank()
    private val fewShotEnabled = enableFewShotExamples

    // Semantic cache (optional - requires OpenAI API key for embeddings)
    private val semanticCache: SemanticCache? = if (cacheEnabled && !openAiApiKey.isNullOrBlank()) {
        try {
            val embeddingService = EmbeddingService(openAiApiKey)
            SemanticCache(embeddingService)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize semantic cache: ${e.message}")
            null
        }
    } else {
        null
    }

    // Dynamic few-shot example store
    private val toolExampleStore: ToolExampleStore? = if (fewShotEnabled) {
        try {
            ToolExampleStore()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize tool example store: ${e.message}")
            null
        }
    } else {
        null
    }

    // Parallel tool executor
    val parallelToolExecutor = ParallelToolExecutor()

    // Statistics
    private var totalQueries = 0
    private var cacheHits = 0
    private var tokensSaved = 0L
    private var piiMasked = 0
    private var fewShotUsed = 0

    /**
     * Pre-process user query before sending to LLM.
     *
     * @param query The original user query
     * @param history Conversation history (optional)
     * @return ProcessedQuery containing masked query, compressed history, and optional cache hit
     */
    suspend fun preProcess(
        query: String,
        history: List<ChatMessage> = emptyList()
    ): ProcessedQuery {
        totalQueries++

        // 1. Check semantic cache first
        if (cacheEnabled) {
            semanticCache?.get(query)?.let { cachedResponse ->
                cacheHits++
                Log.d(TAG, "Semantic cache HIT - skipping API call")
                return ProcessedQuery(
                    maskedQuery = query,
                    compressedHistory = history,
                    cacheHit = cachedResponse
                )
            }
        }

        // 2. Mask PII in query
        val maskedQuery = if (piiEnabled) {
            val masked = PIIMasker.mask(query)
            if (masked != query) {
                piiMasked++
                Log.d(TAG, "PII masked in query")
            }
            masked
        } else {
            query
        }

        // 3. Compress history
        val compressedHistory = if (compressionEnabled && history.isNotEmpty()) {
            val compressed = HistoryCompressor.compress(history)
            val originalTokens = HistoryCompressor.estimateTokens(history)
            val compressedTokens = HistoryCompressor.estimateTokens(compressed)
            val saved = originalTokens - compressedTokens
            if (saved > 0) {
                tokensSaved += saved
                Log.d(TAG, "History compressed: saved ~$saved tokens")
            }
            compressed
        } else {
            history
        }

        return ProcessedQuery(
            maskedQuery = maskedQuery,
            compressedHistory = compressedHistory,
            cacheHit = null
        )
    }

    /**
     * Post-process LLM response.
     *
     * @param originalQuery The original (unmasked) user query (for caching)
     * @param maskedQuery The masked query that was sent to LLM
     * @param response The LLM response
     * @return Unmasked response
     */
    suspend fun postProcess(
        originalQuery: String,
        maskedQuery: String,
        response: String
    ): String {
        // 1. Unmask PII in response
        val unmaskedResponse = if (piiEnabled) {
            PIIMasker.unmask(response)
        } else {
            response
        }

        // 2. Cache the response for future similar queries
        if (cacheEnabled) {
            semanticCache?.put(originalQuery, unmaskedResponse)
        }

        return unmaskedResponse
    }

    /**
     * Get relevant tool examples for the query (dynamic few-shot prompting).
     *
     * @param query User's query
     * @param toolNames Optional filter to specific tools
     * @param count Number of examples to retrieve (default: 3)
     * @return Formatted examples string for prompt, or empty string if disabled
     */
    suspend fun getToolExamples(
        query: String,
        toolNames: List<String>? = null,
        count: Int = 3
    ): String {
        if (!fewShotEnabled || toolExampleStore == null) return ""

        val examples = toolExampleStore.getRelevantExamples(query, toolNames, count)
        if (examples.isNotEmpty()) {
            fewShotUsed++
        }
        return toolExampleStore.formatExamplesForPrompt(examples)
    }

    /**
     * Record a successful tool usage for future few-shot examples.
     */
    suspend fun recordToolUsage(
        toolName: String,
        userQuery: String,
        arguments: Map<String, String>,
        description: String
    ) {
        toolExampleStore?.addExample(
            ToolExampleStore.ToolExample(
                toolName = toolName,
                userQuery = userQuery,
                arguments = arguments,
                description = description,
                keywords = userQuery.lowercase()
                    .split(Regex("[\\s,.!?]+"))
                    .filter { it.length > 2 }
                    .toSet()
            )
        )
    }

    /**
     * Convert conversation history to legacy format (for compatibility).
     *
     * @param messages List of ChatMessage objects
     * @return List of (role, content) pairs
     */
    fun historyToLegacyFormat(messages: List<ChatMessage>): List<Pair<String, String>> {
        return messages.map { msg ->
            val role = when {
                msg.isUser -> "USER"
                msg.isAssistant -> "ASSISTANT"
                else -> "SYSTEM"
            }
            role to msg.content
        }
    }

    /**
     * Build optimized history section for prompt.
     *
     * @param history Compressed conversation history
     * @return Formatted history string for prompt
     */
    fun buildHistorySection(history: List<ChatMessage>): String {
        if (history.isEmpty()) return ""

        return "\nHISTORY:\n" + history.joinToString("\n") { msg ->
            val role = when {
                msg.isUser -> "USER"
                msg.isAssistant -> "ASSISTANT"
                msg.isSystem -> "CONTEXT"
                else -> "UNKNOWN"
            }
            "$role: ${msg.content}"
        } + "\n"
    }

    /**
     * Clear PII masking session (call when starting new conversation).
     */
    fun clearSession() {
        PIIMasker.clearSession()
    }

    /**
     * Clear semantic cache.
     */
    suspend fun clearCache() {
        semanticCache?.clear()
    }

    /**
     * Get optimization statistics.
     */
    fun getStats(): OptimizerStats = OptimizerStats(
        totalQueries = totalQueries,
        cacheHits = cacheHits,
        cacheHitRate = if (totalQueries > 0) cacheHits.toFloat() / totalQueries else 0f,
        tokensSaved = tokensSaved,
        piiMaskedCount = piiMasked,
        fewShotUsedCount = fewShotUsed,
        semanticCacheStats = semanticCache?.getStats(),
        parallelExecutorStats = parallelToolExecutor.getStats(),
        toolExampleStoreStats = toolExampleStore?.getStats()
    )

    /**
     * Reset statistics.
     */
    fun resetStats() {
        totalQueries = 0
        cacheHits = 0
        tokensSaved = 0
        piiMasked = 0
        fewShotUsed = 0
        parallelToolExecutor.resetStats()
    }

    /**
     * Check if semantic caching is available.
     */
    fun isSemanticCacheEnabled(): Boolean = semanticCache != null

    /**
     * Result of query pre-processing.
     */
    data class ProcessedQuery(
        val maskedQuery: String,
        val compressedHistory: List<ChatMessage>,
        val cacheHit: String?  // Non-null if cache hit, skip API call
    )

    /**
     * Optimizer statistics.
     */
    data class OptimizerStats(
        val totalQueries: Int,
        val cacheHits: Int,
        val cacheHitRate: Float,
        val tokensSaved: Long,
        val piiMaskedCount: Int,
        val fewShotUsedCount: Int,
        val semanticCacheStats: SemanticCache.CacheStats?,
        val parallelExecutorStats: ParallelToolExecutor.ExecutionStats?,
        val toolExampleStoreStats: ToolExampleStore.StoreStats?
    ) {
        /**
         * Get a human-readable summary of optimization impact.
         */
        fun getSummary(): String = buildString {
            appendLine("=== Agent Optimization Stats ===")
            appendLine("Queries: $totalQueries (cache hit rate: ${(cacheHitRate * 100).toInt()}%)")
            appendLine("Tokens saved: ~$tokensSaved")
            appendLine("PII masked: $piiMaskedCount queries")
            appendLine("Few-shot examples used: $fewShotUsedCount times")
            parallelExecutorStats?.let {
                appendLine("Parallel executions: ${it.parallelBatches} batches, ~${it.totalTimeSavedMs}ms saved")
            }
            toolExampleStoreStats?.let {
                appendLine("Tool examples: ${it.totalExamples} covering ${it.toolsCovered} tools")
            }
        }
    }
}
