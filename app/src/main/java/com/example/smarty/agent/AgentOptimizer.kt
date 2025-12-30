package com.example.smarty.agent

import android.util.Log
import com.example.smarty.agent.prompts.ToolExampleStore
import com.example.smarty.data.cache.HashBasedCache
import com.example.smarty.data.cache.SemanticCache
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.remote.EmbeddingService
import com.example.smarty.data.remote.GeminiEmbeddingService
import com.example.smarty.util.HistoryCompressor
import com.example.smarty.util.PIIMasker
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Agent Optimizer - integrates all optimization components.
 *
 * Provides a unified interface for:
 * - PII masking/unmasking (privacy)
 * - History compression (token reduction)
 * - Semantic caching (API call reduction) - Multi-provider support
 * - Dynamic few-shot examples (tool selection accuracy)
 * - Parallel tool execution (2-3x speedup)
 *
 * Cache Provider Priority:
 * 1. OpenAI embeddings (text-embedding-3-small) - Best quality
 * 2. Gemini embeddings (text-embedding-004) - Good alternative
 * 3. Hash-based fallback - Exact match only, but still functional
 *
 * Usage:
 * ```kotlin
 * val optimizer = AgentOptimizer(
 *     openAiApiKey = openAiKey,
 *     geminiApiKey = geminiKey  // Falls back to Gemini if OpenAI unavailable
 * )
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
 *
 * @see CacheMode for available caching strategies
 */
class AgentOptimizer(
    openAiApiKey: String? = null,
    geminiApiKey: String? = null,
    enableSemanticCache: Boolean = true,
    enablePiiMasking: Boolean = true,
    enableHistoryCompression: Boolean = true,
    enableFewShotExamples: Boolean = true
) {
    companion object {
        private const val TAG = "AgentOptimizer"

        // Timeout constants (AGENT-001, AGENT-002 fixes)
        private const val CACHE_LOOKUP_TIMEOUT_MS = 2000L      // 2 seconds max for cache lookup
        private const val EMBEDDING_INIT_TIMEOUT_MS = 5000L   // 5 seconds max for embedding service init
    }

    // Feature flags
    private val piiEnabled = enablePiiMasking
    private val compressionEnabled = enableHistoryCompression
    private val fewShotEnabled = enableFewShotExamples

    // Cache mode tracking
    private val cacheMode: CacheMode

    // Semantic cache (optional - requires OpenAI or Gemini API key for embeddings)
    private val semanticCache: SemanticCache?

    // Hash-based fallback cache (used when no embedding service is available)
    private val hashBasedCache: HashBasedCache?

    init {
        // Try to initialize semantic cache with available embedding providers
        // Priority: OpenAI > Gemini > Hash-based fallback
        val (cache, mode) = initializeCache(openAiApiKey, geminiApiKey, enableSemanticCache)
        semanticCache = cache
        cacheMode = mode
        hashBasedCache = if (mode == CacheMode.HASH_BASED) HashBasedCache() else null

        when (mode) {
            CacheMode.OPENAI_SEMANTIC -> Log.i(TAG, "Semantic cache initialized with OpenAI embeddings")
            CacheMode.GEMINI_SEMANTIC -> Log.i(TAG, "Semantic cache initialized with Gemini embeddings")
            CacheMode.HASH_BASED -> Log.i(TAG, "Using hash-based fallback cache (no embedding API keys available)")
            CacheMode.DISABLED -> Log.i(TAG, "Caching disabled")
        }
    }

    /**
     * Initialize the semantic cache with embedding service.
     *
     * AGENT-001 NOTE: Embedding service constructors are lightweight and don't make network calls.
     * Network calls happen during get()/put() operations, which are protected by CACHE_LOOKUP_TIMEOUT_MS.
     * The embedding services have built-in timeouts (10s connect, 30s read) as additional protection.
     */
    private fun initializeCache(
        openAiApiKey: String?,
        geminiApiKey: String?,
        enableCache: Boolean
    ): Pair<SemanticCache?, CacheMode> {
        if (!enableCache) {
            return null to CacheMode.DISABLED
        }

        // Try OpenAI first (best quality embeddings)
        if (!openAiApiKey.isNullOrBlank()) {
            try {
                Log.d(TAG, "Initializing OpenAI embedding service...")
                val embeddingService = EmbeddingService(openAiApiKey)
                Log.d(TAG, "OpenAI embedding service initialized successfully")
                return SemanticCache(embeddingService) to CacheMode.OPENAI_SEMANTIC
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize OpenAI embedding service: ${e.message}")
            }
        }

        // Try Gemini as alternative
        if (!geminiApiKey.isNullOrBlank()) {
            try {
                Log.d(TAG, "Initializing Gemini embedding service...")
                val embeddingService = GeminiEmbeddingService(geminiApiKey)
                Log.d(TAG, "Gemini embedding service initialized successfully")
                return SemanticCache(embeddingService) to CacheMode.GEMINI_SEMANTIC
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize Gemini embedding service: ${e.message}")
            }
        }

        // Fall back to hash-based caching
        Log.i(TAG, "No embedding API keys available, using hash-based fallback cache")
        return null to CacheMode.HASH_BASED
    }

    /**
     * Cache operating mode.
     */
    enum class CacheMode {
        OPENAI_SEMANTIC,   // Full semantic cache with OpenAI embeddings
        GEMINI_SEMANTIC,   // Full semantic cache with Gemini embeddings
        HASH_BASED,        // Fallback hash-based exact-match cache
        DISABLED           // No caching
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

        // 1. Check cache first (semantic or hash-based fallback)
        // CRITICAL FIX (AGENT-002): Wrap semantic cache lookup in timeout to prevent blocking
        when (cacheMode) {
            CacheMode.OPENAI_SEMANTIC, CacheMode.GEMINI_SEMANTIC -> {
                try {
                    withTimeout(CACHE_LOOKUP_TIMEOUT_MS) {
                        semanticCache?.get(query)
                    }?.let { cachedResponse ->
                        cacheHits.incrementAndGet()
                        Log.d(TAG, "Semantic cache HIT - skipping API call")
                        return ProcessedQuery(
                            maskedQuery = query,
                            compressedHistory = history,
                            cacheHit = cachedResponse
                        )
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "Semantic cache lookup timed out after ${CACHE_LOOKUP_TIMEOUT_MS}ms, treating as cache miss")
                }
            }
            CacheMode.HASH_BASED -> {
                hashBasedCache?.get(query)?.let { cachedResponse ->
                    cacheHits.incrementAndGet()
                    Log.d(TAG, "Hash-based cache HIT - skipping API call")
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
            val masked = PIIMasker.mask(query)
            if (masked != query) {
                piiMasked.incrementAndGet()
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
                tokensSaved.addAndGet(saved.toLong())
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
        when (cacheMode) {
            CacheMode.OPENAI_SEMANTIC, CacheMode.GEMINI_SEMANTIC -> {
                semanticCache?.put(originalQuery, unmaskedResponse)
            }
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
        PIIMasker.clearSession()
    }

    /**
     * Clear all caches (semantic and hash-based).
     */
    suspend fun clearCache() {
        semanticCache?.clear()
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
            semanticCacheStats = semanticCache?.getStats(),
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
     * Check if semantic caching is available.
     */
    fun isSemanticCacheEnabled(): Boolean = semanticCache != null

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
        val semanticCacheStats: SemanticCache.CacheStats?,
        val hashBasedCacheStats: HashBasedCache.CacheStats?,
        val toolExampleStoreStats: ToolExampleStore.StoreStats?
        // AGENT-012: parallelExecutorStats removed - ParallelToolExecutor was unused
    ) {
        /**
         * Get a human-readable summary of optimization impact.
         */
        fun getSummary(): String = buildString {
            appendLine("=== Agent Optimization Stats ===")
            appendLine("Cache mode: ${cacheMode.name}")
            appendLine("Queries: $totalQueries (cache hit rate: ${(cacheHitRate * 100).toInt()}%)")
            appendLine("Tokens saved: ~$tokensSaved")
            appendLine("PII masked: $piiMaskedCount queries")
            appendLine("Few-shot examples used: $fewShotUsedCount times")
            semanticCacheStats?.let {
                appendLine("Semantic cache: ${it.size} entries, ${it.hits} hits, ${it.misses} misses")
            }
            hashBasedCacheStats?.let {
                appendLine("Hash-based cache: ${it.size} entries, ${it.hits} hits, ${it.misses} misses")
            }
            // AGENT-012: parallelExecutorStats removed
            toolExampleStoreStats?.let {
                appendLine("Tool examples: ${it.totalExamples} covering ${it.toolsCovered} tools")
            }
        }
    }
}
