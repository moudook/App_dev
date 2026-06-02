package com.example.smarty.data.cache

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Hash-based fallback cache for AI responses.
 *
 * Unlike SemanticCache which uses embeddings for similarity matching,
 * this cache uses exact query matching with normalized text.
 *
 * This is used as a fallback when no embedding connection keys are available.
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
    private val normalizeQueries: Boolean = true,
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
        val timestamp: Long,
        var accessCount: Int = 0,
        val containsNoteData: Boolean = false,
    ) {
        fun isExpired(
            currentTime: Long,
            ttlMs: Long,
        ): Boolean = currentTime - timestamp > ttlMs
    }

    // Cache storage using normalized query hash as key
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    // Statistics
    @Volatile private var hits = 0

    @Volatile private var misses = 0

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
            .replace(Regex("\\s+"), " ") // Normalize whitespace
            .replace(Regex("[.!?,;:]+$"), "") // Remove trailing punctuation
    }

    /**
     * Get cached response for a query.
     *
     * @param query The user's query
     * @return Cached response if found, null otherwise
     */
    fun get(query: String): String? {
        if (query.isBlank()) return null

        // CRITICAL FIX: Never return cached results for action-oriented queries
        // These depend on dynamic data (notes, audio, etc.) and must always execute fresh
        if (isActionQuery(query)) {
            return null
        }

        val currentTime = System.currentTimeMillis()

        // Clean expired entries
        evictExpired(currentTime)

        val normalizedKey = normalizeQuery(query)
        val entry = cache[normalizedKey]

        if (entry != null && !entry.isExpired(currentTime, ttlMs)) {
            entry.accessCount++
            hits++
            Log.d(TAG, "Cache HIT for: ${query.take(30)}...")
            return entry.response
        }

        misses++
        return null
    }

    // Action keywords that should NOT be cached (depend on dynamic data)
    // These queries involve operations on user data that can change at any time
    private val ACTION_KEYWORDS =
        setOf(
            "play",
            "pause",
            "stop",
            "resume",
            "search",
            "find",
            "look",
            "check",
            "show",
            "display",
            "tell",
            "give",
            "list",
            "get",
            "fetch",
            "read",
            "open",
            "archive",
            "unarchive",
            "delete",
            "update",
            "create",
            "add",
            "remove",
            "edit",
            "modify",
            "count",
            "how many",
            "total",
            "number of",
            "notes",
            "note",
            "audio",
            "document",
            "image",
            "file",
        )

    /**
     * Check if a query is action-oriented (should not be cached).
     * Action queries depend on dynamic note/audio data that can change.
     */
    private fun isActionQuery(query: String): Boolean {
        val normalizedQuery = query.lowercase().trim()

        // Check for any action keyword
        val hasActionKeyword =
            ACTION_KEYWORDS.any { keyword ->
                normalizedQuery.startsWith(keyword) ||
                    normalizedQuery.contains(" $keyword ") ||
                    normalizedQuery.contains("$keyword ") ||
                    normalizedQuery.contains(" $keyword") ||
                    normalizedQuery.endsWith(keyword)
            }

        // Also check for question patterns about user data
        val isDataQuestion =
            normalizedQuery.contains("my ") ||
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
    fun put(
        query: String,
        response: String,
        containsNoteData: Boolean = false,
    ) {
        if (query.isBlank() || response.isBlank()) return

        // CRITICAL FIX: Don't cache action-oriented queries that depend on dynamic data
        // These queries (play, search, find, etc.) should always execute fresh
        if (isActionQuery(query)) {
            Log.d(TAG, "Skipping cache for action query: ${query.take(30)}...")
            return
        }

        // Evict if at capacity
        if (cache.size >= maxEntries) {
            evictLeastUsed()
        }

        val normalizedKey = normalizeQuery(query)
        val entry =
            CacheEntry(
                originalQuery = query,
                response = response,
                timestamp = System.currentTimeMillis(),
                containsNoteData = containsNoteData,
            )
        cache[normalizedKey] = entry
        Log.d(TAG, "Cached response for: ${query.take(30)}...")
    }

    /**
     * Clear all cached entries.
     */
    fun clear() {
        cache.clear()
        hits = 0
        misses = 0
        Log.d(TAG, "Cache cleared")
    }

    /**
     * Invalidate all cache entries that contain note data.
     * Call this when note privacy changes.
     */
    fun invalidateNoteDataEntries() {
        val keysToRemove = cache.filterValues { it.containsNoteData }.keys
        keysToRemove.forEach { cache.remove(it) }
        Log.d(TAG, "Invalidated ${keysToRemove.size} entries containing note data")
    }

    /**
     * Get cache statistics.
     */
    fun getStats(): CacheStats =
        CacheStats(
            size = cache.size,
            hits = hits,
            misses = misses,
            hitRate = if (hits + misses > 0) hits.toFloat() / (hits + misses) else 0f,
        )

    /**
     * Evict expired entries from cache.
     */
    private fun evictExpired(currentTime: Long) {
        val keysToRemove = cache.filterValues { it.isExpired(currentTime, ttlMs) }.keys
        keysToRemove.forEach { cache.remove(it) }
        if (keysToRemove.isNotEmpty()) {
            Log.d(TAG, "Evicted ${keysToRemove.size} expired entries")
        }
    }

    /**
     * Evict least recently used entries to make room.
     */
    private fun evictLeastUsed() {
        // Remove bottom 10% by access count
        val toRemove = (maxEntries * 0.1).toInt().coerceAtLeast(1)

        val keysToRemove =
            cache.entries
                .sortedBy { it.value.accessCount }
                .take(toRemove)
                .map { it.key }

        keysToRemove.forEach { cache.remove(it) }
        Log.d(TAG, "Evicted $toRemove least used entries")
    }

    /**
     * Cache statistics data class.
     */
    data class CacheStats(
        val size: Int,
        val hits: Int,
        val misses: Int,
        val hitRate: Float,
    )
}
