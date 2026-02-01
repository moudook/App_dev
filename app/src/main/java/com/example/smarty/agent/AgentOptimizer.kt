package com.example.smarty.agent

import com.example.smarty.agent.prompts.ToolExampleStore
import com.example.smarty.data.cache.HashBasedCache
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.util.HistoryCompressor
import com.example.smarty.util.Logger
import com.example.smarty.util.PIIMasker
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Agent Optimizer - integrates all optimization components.
 *
 * Provides a unified interface for:
 * - PII masking/unmasking (privacy)
 * - History compression (token reduction)
 * - Hash-based caching (API call reduction for exact matches)
 * - Dynamic few-shot examples (tool selection accuracy)
 *
 * Uses on-device hash-based caching only (no cloud embedding APIs).
 *
 * Usage:
 * ```kotlin
 * val optimizer = AgentOptimizer()
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
    private val logger: Logger,
    private val historyCompressor: HistoryCompressor,
    private val piiMasker: PIIMasker,
    enableCache: Boolean = true,
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
    private val fewShotEnabled = enableFewShotExamples

    // Cache mode tracking
    private val cacheMode: CacheMode

    // Hash-based cache (on-device, no API calls)
    private val hashBasedCache: HashBasedCache?

    init {
        cacheMode = if (enableCache) CacheMode.HASH_BASED else CacheMode.DISABLED
        hashBasedCache = if (enableCache) HashBasedCache() else null

        when (cacheMode) {
            CacheMode.HASH_BASED -> logger.i(TAG, "Using on-device hash-based cache")
            CacheMode.DISABLED -> logger.i(TAG, "Caching disabled")
        }
    }

    /**
     * Cache operating mode.
     */
    enum class CacheMode {
        HASH_BASED,        // On-device hash-based exact-match cache
        DISABLED           // No caching
    }

    // Dynamic few-shot example store
    private val toolExampleStore: ToolExampleStore? = if (fewShotEnabled) {
        try {
            ToolExampleStore(logger)
        } catch (e: Exception) {
            logger.w(TAG, "Failed to initialize tool example store: ${e.message}")
            null
        }
    } else {
        null
    }

    // AGENT-012: ParallelToolExecutor removed - was unused (Koog handles tool execution internally)

    // Statistics - AGENT-013: Using atomic types for thread safety
    private val totalQueries = AtomicInteger(0)
    private val cacheHits = AtomicInteger(0)
    private val tokensSaved = AtomicLong(0L)
    private val piiMasked = AtomicInteger(0)
    private val fewShotUsed = AtomicInteger(0)

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
        totalQueries.incrementAndGet()

        // 1. Check hash-based cache first
        when (cacheMode) {
            CacheMode.HASH_BASED -> {
                hashBasedCache?.get(query)?.let { cachedResponse ->
                    cacheHits.incrementAndGet()
                    logger.d(TAG, "Hash-based cache HIT - skipping API call")
                    return ProcessedQuery(
                        maskedQuery = query,
                        compressedHistory = history,
                        cacheHit = cachedResponse
                    )
                }
            }
            CacheMode.DISABLED -> { /* No caching */ }
        }

        // 2. Mask PII in query
        val maskedQuery = if (piiEnabled) {
            val masked = piiMasker.mask(query)
            if (masked != query) {
                piiMasked.incrementAndGet()
                logger.d(TAG, "PII masked in query")
            }
            masked
        } else {
            query
        }

        // 3. Compress history
        val compressedHistory = if (compressionEnabled && history.isNotEmpty()) {
            val compressed = historyCompressor.compress(history)
            val originalTokens = historyCompressor.estimateTokens(history)
            val compressedTokens = historyCompressor.estimateTokens(compressed)
            val saved = originalTokens - compressedTokens
            if (saved > 0) {
                tokensSaved.addAndGet(saved.toLong())
                logger.d(TAG, "History compressed: saved ~$saved tokens")
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
            piiMasker.unmask(response)
        } else {
            response
        }

        // 2. Cache the response for future exact-match queries
        when (cacheMode) {
            CacheMode.HASH_BASED -> {
                hashBasedCache?.put(originalQuery, unmaskedResponse)
            }
            CacheMode.DISABLED -> { /* No caching */ }
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
            fewShotUsed.incrementAndGet()
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
        piiMasker.clearSession()
    }

    /**
     * Clear hash-based cache.
     */
    suspend fun clearCache() {
        hashBasedCache?.clear()
    }

    /**
     * Get optimization statistics.
     */
    fun getStats(): OptimizerStats {
        val total = totalQueries.get()
        val hits = cacheHits.get()
        return OptimizerStats(
            totalQueries = total,
            cacheHits = hits,
            cacheHitRate = if (total > 0) hits.toFloat() / total else 0f,
            tokensSaved = tokensSaved.get(),
            piiMaskedCount = piiMasked.get(),
            fewShotUsedCount = fewShotUsed.get(),
            cacheMode = cacheMode,
            hashBasedCacheStats = hashBasedCache?.getStats(),
            toolExampleStoreStats = toolExampleStore?.getStats()
        )
    }

    /**
     * Reset statistics.
     */
    fun resetStats() {
        totalQueries.set(0)
        cacheHits.set(0)
        tokensSaved.set(0L)
        piiMasked.set(0)
        fewShotUsed.set(0)
        // AGENT-012: parallelToolExecutor.resetStats() removed
    }

    /**
     * Check if caching is available.
     */
    fun isCacheAvailable(): Boolean = hashBasedCache != null

    /**
     * Check if any caching (semantic or hash-based) is enabled.
     */
    fun isCacheEnabled(): Boolean = cacheMode != CacheMode.DISABLED

    /**
     * Get the current cache mode.
     */
    fun getCacheMode(): CacheMode = cacheMode

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
        val cacheMode: CacheMode,
        val hashBasedCacheStats: HashBasedCache.CacheStats?,
        val toolExampleStoreStats: ToolExampleStore.StoreStats?
    ) {
        /**
         * Get a human-readable summary of optimization impact.
         */
        fun getSummary(): String = buildString {
            appendLine("=== agent optimization stats ===")
            appendLine("cache mode: ${cacheMode.name.lowercase()}")
            appendLine("queries: $totalQueries (cache hit rate: ${(cacheHitRate * 100).toInt()}%)")
            appendLine("tokens saved: ~$tokensSaved")
            appendLine("pii masked: $piiMaskedCount queries")
            appendLine("few-shot examples used: $fewShotUsedCount times")
            hashBasedCacheStats?.let {
                appendLine("hash-based cache: ${it.size} entries, ${it.hits} hits, ${it.misses} misses")
            }
            toolExampleStoreStats?.let {
                appendLine("tool examples: ${it.totalExamples} covering ${it.toolsCovered} tools")
            }
        }
    }
}
