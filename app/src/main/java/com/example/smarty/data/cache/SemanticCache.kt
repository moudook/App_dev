package com.example.smarty.data.cache

import android.util.Log
import com.example.smarty.data.remote.EmbeddingService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Semantic cache for AI responses.
 *
 * Unlike hash-based caching (exact match), semantic caching uses vector embeddings
 * to find similar queries. This enables cache hits for paraphrased questions.
 *
 * Example:
 * - Query 1: "What's the weather like today?"
 * - Query 2: "How's the weather?"
 * - Similarity: ~0.97 (cache hit!)
 *
 * Expected impact: 40-60% reduction in API calls
 *
 * @property embeddingService Service for generating text embeddings
 * @property similarityThreshold Minimum similarity for cache hit (default: 0.90)
 * @property maxEntries Maximum cache size before eviction (default: 100)
 * @property ttlMs Time-to-live in milliseconds (default: 2 hours)
 *
 * L9 FIX: Adjusted thresholds for better cache hit rate:
 * - Reduced similarity from 0.95 to 0.90 (more permissive)
 * - Increased TTL from 30min to 2 hours for longer-lived cache entries
 */
class SemanticCache(
    private val embeddingService: EmbeddingService,
    private val similarityThreshold: Float = 0.90f,  // Reduced from 0.95 for more cache hits
    private val maxEntries: Int = 100,
    private val ttlMs: Long = 2 * 60 * 60 * 1000 // 2 hours (increased from 30 min)
) {
    companion object {
        private const val TAG = "SemanticCache"
    }

    /**
     * Cache entry storing query embedding, response, and metadata.
     * PRIVACY FIX (CRIT-007): Added containsNoteData flag to track entries
     * that may need invalidation when note privacy changes.
     */
    data class CacheEntry(
        val query: String,
        val embedding: FloatArray,
        val response: String,
        val timestamp: Long = System.currentTimeMillis(),
        var accessCount: Int = 0,
        val containsNoteData: Boolean = false  // True if response references user notes
    ) {
        fun isExpired(ttlMs: Long): Boolean =
            System.currentTimeMillis() - timestamp > ttlMs

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CacheEntry) return false
            return query == other.query
        }

        override fun hashCode(): Int = query.hashCode()
    }

    // Thread-safe cache storage
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    // Mutex for thread-safe operations that read multiple entries
    private val mutex = Mutex()

    // Statistics
    private var hits = 0
    private var misses = 0

    /**
     * Get cached response for a semantically similar query.
     *
     * @param query The user's query
     * @return Cached response if found with similarity >= threshold, null otherwise
     */
    suspend fun get(query: String): String? = mutex.withLock {
        if (query.isBlank()) return@withLock null

        // Clean expired entries
        evictExpired()

        // Generate embedding for query
        val queryEmbedding = embeddingService.embed(query) ?: run {
            Log.w(TAG, "Failed to generate embedding for query")
            misses++
            return@withLock null
        }

        // Find most similar cached entry
        var bestMatch: CacheEntry? = null
        var bestSimilarity = 0f

        cache.values.forEach { entry ->
            if (!entry.isExpired(ttlMs)) {
                val similarity = embeddingService.cosineSimilarity(queryEmbedding, entry.embedding)
                if (similarity > bestSimilarity && similarity >= similarityThreshold) {
                    bestSimilarity = similarity
                    bestMatch = entry
                }
            }
        }

        bestMatch?.let { match ->
            match.accessCount++
            hits++
            Log.d(TAG, "Cache HIT: similarity=${"%.3f".format(bestSimilarity)}, query='${query.take(50)}'")
            return@withLock match.response
        }

        misses++
        Log.d(TAG, "Cache MISS: query='${query.take(50)}'")
        return@withLock null
    }

    /**
     * Store a query-response pair in the cache.
     *
     * @param query The original query
     * @param response The AI response to cache
     */
    /**
     * @param containsNoteData PRIVACY FIX (CRIT-007): Set to true if response references user notes.
     *                         These entries will be invalidated when note privacy changes.
     */
    suspend fun put(query: String, response: String, containsNoteData: Boolean = false) = mutex.withLock {
        if (query.isBlank() || response.isBlank()) return@withLock

        // Generate embedding for query
        val embedding = embeddingService.embed(query) ?: run {
            Log.w(TAG, "Failed to generate embedding for caching")
            return@withLock
        }

        // Evict if at capacity
        if (cache.size >= maxEntries) {
            evictLeastUsed()
        }

        // Store entry
        val entry = CacheEntry(
            query = query,
            embedding = embedding,
            response = response,
            containsNoteData = containsNoteData
        )
        cache[query] = entry

        Log.d(TAG, "Cached response for query='${query.take(50)}' (noteData=$containsNoteData)")
    }

    /**
     * Clear all cached entries.
     */
    suspend fun clear() = mutex.withLock {
        cache.clear()
        hits = 0
        misses = 0
        Log.d(TAG, "Cache cleared")
    }

    /**
     * PRIVACY FIX (CRIT-007): Invalidate all cache entries that contain note data.
     * Call this when note privacy changes to prevent stale private data from being returned.
     */
    suspend fun invalidateNoteDataEntries() = mutex.withLock {
        val sizeBefore = cache.size
        cache.entries.removeIf { it.value.containsNoteData }
        val removed = sizeBefore - cache.size
        if (removed > 0) {
            Log.d(TAG, "Privacy change: invalidated $removed cache entries containing note data")
        }
    }

    /**
     * Get cache statistics.
     */
    fun getStats(): CacheStats = CacheStats(
        size = cache.size,
        hits = hits,
        misses = misses,
        hitRate = if (hits + misses > 0) hits.toFloat() / (hits + misses) else 0f
    )

    /**
     * Evict expired entries from cache.
     */
    private fun evictExpired() {
        val now = System.currentTimeMillis()
        cache.entries.removeIf { it.value.isExpired(ttlMs) }
    }

    /**
     * Evict least recently used entries to make room.
     */
    private fun evictLeastUsed() {
        // Remove bottom 10% by access count
        val toRemove = (maxEntries * 0.1).toInt().coerceAtLeast(1)

        // FIX: Collect keys to remove FIRST to avoid ConcurrentModificationException
        val keysToRemove = cache.entries
            .sortedBy { it.value.accessCount }
            .take(toRemove)
            .map { it.key }

        keysToRemove.forEach { cache.remove(it) }
    }

    /**
     * Check if a similar query exists in cache (without returning response).
     * Useful for deciding whether to make an API call.
     */
    suspend fun hasSimilar(query: String): Boolean = mutex.withLock {
        val queryEmbedding = embeddingService.embed(query) ?: return@withLock false

        cache.values.any { entry ->
            !entry.isExpired(ttlMs) &&
                embeddingService.cosineSimilarity(queryEmbedding, entry.embedding) >= similarityThreshold
        }
    }

    /**
     * Cache statistics data class.
     */
    data class CacheStats(
        val size: Int,
        val hits: Int,
        val misses: Int,
        val hitRate: Float
    )
}
