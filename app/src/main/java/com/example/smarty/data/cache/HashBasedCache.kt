package com.example.smarty.data.cache

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Hash-based fallback cache for AI responses.
 *
 * Unlike SemanticCache which uses embeddings for similarity matching,
 * this cache uses exact query matching with normalized text.
 *
 * This is used as a fallback when no embedding API keys are available.
 * While it won't match semantically similar queries, it still provides
 * caching benefits for exact or near-exact repeated queries.
 *
 * Normalization includes:
 * - Lowercase conversion
 * - Whitespace normalization
 * - Punctuation removal (optional)
 *
 * @property maxEntries Maximum cache size before eviction (default: 100)
 * @property ttlMs Time-to-live in milliseconds (default: 2 hours)
 * @property normalizeQueries Whether to normalize queries for better matching (default: true)
 */
class HashBasedCache(
    private val maxEntries: Int = 100,
    private val ttlMs: Long = 2 * 60 * 60 * 1000, // 2 hours
    private val normalizeQueries: Boolean = true
) {
    companion object {
        private const val TAG = "HashBasedCache"
    }

    /**
     * Cache entry storing response and metadata.
     */
    data class CacheEntry(
        val originalQuery: String,
        val response: String,
        val timestamp: Long = System.currentTimeMillis(),
        var accessCount: Int = 0,
        val containsNoteData: Boolean = false
    ) {
        fun isExpired(ttlMs: Long): Boolean =
            System.currentTimeMillis() - timestamp > ttlMs
    }

    // Thread-safe cache storage using normalized query hash as key
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    // Mutex for thread-safe operations
    private val mutex = Mutex()

    // Statistics
    private var hits = 0
    private var misses = 0

    /**
     * Normalize a query for consistent matching.
     *
     * @param query The original query
     * @return Normalized query string
     */
    private fun normalizeQuery(query: String): String {
        if (!normalizeQueries) return query

        return query
            .lowercase()
            .trim()
            .replace(Regex("\\s+"), " ")  // Normalize whitespace
            .replace(Regex("[.!?,;:]+$"), "")  // Remove trailing punctuation
    }

    /**
     * Get cached response for a query.
     *
     * @param query The user's query
     * @return Cached response if found, null otherwise
     */
    suspend fun get(query: String): String? = mutex.withLock {
        if (query.isBlank()) return@withLock null

        // CRITICAL FIX: Never return cached results for action-oriented queries
        // These depend on dynamic data (notes, audio, etc.) and must always execute fresh
        if (isActionQuery(query)) {
            Log.d(TAG, "Cache BYPASS: Action query detected - '$query' will execute fresh")
            return@withLock null
        }

        // Clean expired entries
        evictExpired()

        val normalizedKey = normalizeQuery(query)
        val entry = cache[normalizedKey]

        if (entry != null && !entry.isExpired(ttlMs)) {
            entry.accessCount++
            hits++
            Log.d(TAG, "Cache HIT: query='${query.take(50)}'")
            return@withLock entry.response
        }

        misses++
        Log.d(TAG, "Cache MISS: query='${query.take(50)}'")
        return@withLock null
    }

    // Action keywords that should NOT be cached (depend on dynamic data)
    // These queries involve operations on user data that can change at any time
    private val ACTION_KEYWORDS = setOf(
        // Playback/media actions
        "play", "pause", "stop", "resume",
        // Search/query actions
        "search", "find", "look", "check",
        // Display actions
        "show", "display", "tell", "give", "list",
        // Data retrieval
        "get", "fetch", "read", "open",
        // Data modification
        "archive", "unarchive", "delete", "update", "create", "add", "remove", "edit", "modify",
        // Counting/analytics (depend on current note count)
        "count", "how many", "total", "number of",
        // Note-specific queries (always depend on dynamic data)
        "notes", "note", "audio", "document", "image", "file"
    )

    /**
     * Check if a query is action-oriented (should not be cached).
     * Action queries depend on dynamic note/audio data that can change.
     *
     * EXPANDED CRITERIA:
     * - Commands that perform operations (play, delete, archive, etc.)
     * - Queries about user data (notes, audio, documents)
     * - Count/quantity queries (how many notes, etc.)
     */
    private fun isActionQuery(query: String): Boolean {
        val normalizedQuery = query.lowercase().trim()

        // Check for any action keyword
        val hasActionKeyword = ACTION_KEYWORDS.any { keyword ->
            normalizedQuery.startsWith(keyword) ||
            normalizedQuery.contains(" $keyword ") ||
            normalizedQuery.contains("$keyword ") ||
            normalizedQuery.contains(" $keyword") ||
            normalizedQuery.endsWith(keyword)
        }

        // Also check for question patterns about user data
        val isDataQuestion = normalizedQuery.contains("my ") ||
            normalizedQuery.contains("i have") ||
            normalizedQuery.contains("do i have") ||
            normalizedQuery.contains("what's in my") ||
            normalizedQuery.contains("what is in my")

        return hasActionKeyword || isDataQuestion
    }

    /**
     * Store a query-response pair in the cache.
     *
     * @param query The original query
     * @param response The AI response to cache
     * @param containsNoteData Whether the response contains user note data
     */
    suspend fun put(query: String, response: String, containsNoteData: Boolean = false) = mutex.withLock {
        if (query.isBlank() || response.isBlank()) return@withLock

        // CRITICAL FIX: Don't cache action-oriented queries that depend on dynamic data
        // These queries (play, search, find, etc.) should always execute fresh
        if (isActionQuery(query)) {
            Log.d(TAG, "SKIP CACHE: Action query detected - '$query' will not be cached")
            return@withLock
        }

        // Evict if at capacity
        if (cache.size >= maxEntries) {
            evictLeastUsed()
        }

        val normalizedKey = normalizeQuery(query)
        val entry = CacheEntry(
            originalQuery = query,
            response = response,
            containsNoteData = containsNoteData
        )
        cache[normalizedKey] = entry

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
     * Invalidate all cache entries that contain note data.
     * Call this when note privacy changes.
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
        cache.entries.removeIf { it.value.isExpired(ttlMs) }
    }

    /**
     * Evict least recently used entries to make room.
     */
    private fun evictLeastUsed() {
        // Remove bottom 10% by access count
        val toRemove = (maxEntries * 0.1).toInt().coerceAtLeast(1)

        val keysToRemove = cache.entries
            .sortedBy { it.value.accessCount }
            .take(toRemove)
            .map { it.key }

        keysToRemove.forEach { cache.remove(it) }
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
