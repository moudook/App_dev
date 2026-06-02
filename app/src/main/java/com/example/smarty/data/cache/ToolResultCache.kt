package com.example.smarty.data.cache

import android.util.Log
import android.util.LruCache

/**
 * LRU cache for tool execution results to avoid duplicate DB/API calls.
 * Short TTL (30 seconds) since tool results can change quickly.
 *
 * SECURITY: Cache must be invalidated when:
 * - Any note is updated (privacy state could change)
 * - Notes are archived/unarchived
 * - Notes are deleted
 */
object ToolResultCache {
    private const val TAG = "ToolResultCache"
    private const val MAX_CACHE_SIZE = 50
    private const val TTL_MS = 30_000L // 30 seconds

    private data class CacheEntry(
        val result: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val cache = LruCache<String, CacheEntry>(MAX_CACHE_SIZE)

    fun generateKey(
        toolName: String,
        args: String,
    ): String = "$toolName:${args.hashCode()}"

    fun get(key: String): String? {
        val entry = cache.get(key) ?: return null
        if (System.currentTimeMillis() - entry.timestamp > TTL_MS) {
            cache.remove(key)
            return null
        }
        return entry.result
    }

    fun put(
        key: String,
        result: String,
    ) {
        cache.put(key, CacheEntry(result))
    }

    fun clear() {
        cache.evictAll()
    }

    /**
     * SECURITY FIX: Invalidate cache when notes are modified.
     * Called from repository when any note update occurs.
     * This prevents cached results from leaking private note data
     * when a user marks a note as private between searches.
     */
    fun invalidateNoteCache() {
        val cacheSize = cache.size()
        if (cacheSize > 0) {
            Log.d(TAG, "Invalidating $cacheSize cached entries due to note modification")
            cache.evictAll()
        }
    }
}
