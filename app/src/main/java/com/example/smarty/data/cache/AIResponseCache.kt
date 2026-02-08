package com.example.smarty.data.cache

import android.util.Log
import com.example.smarty.data.remote.AIResponse
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * LRU cache for AI analysis responses to minimize redundant API calls.
 *
 * Features:
 * - Thread-safe via ConcurrentHashMap
 * - TTL-based expiration (30 minutes default)
 * - LRU eviction when max size exceeded
 * - Content-based hashing for cache keys
 */
object AIResponseCache {
    private const val TAG = "AIResponseCache"
    private const val MAX_SIZE = 50
    private const val DEFAULT_TTL_MS = 30 * 60 * 1000L  // 30 minutes

    private data class CacheEntry(
        val response: AIResponse,
        val timestamp: Long = System.currentTimeMillis(),
        var lastAccess: Long = System.currentTimeMillis()
    ) {
        fun isExpired(ttlMs: Long = DEFAULT_TTL_MS): Boolean {
            return System.currentTimeMillis() - timestamp > ttlMs
        }
    }

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Generate a cache key from content by hashing it.
     * Normalizes whitespace and trims content to create consistent keys.
     */
    fun generateKey(content: String): String {
        // Normalize content: trim, collapse whitespace
        val normalized = content.trim()
            .replace(Regex("\\s+"), " ")
            .take(2000)  // Only hash first 2000 chars for performance

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(normalized.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Hash failed, using content prefix", e)
            normalized.take(100).hashCode().toString()
        }
    }

    /**
     * Get cached response if available and not expired.
     */
    fun get(key: String): AIResponse? {
        val entry = cache[key] ?: return null

        // Check expiration
        if (entry.isExpired()) {
            cache.remove(key)
            Log.d(TAG, "Cache entry expired: ${key.take(16)}...")
            return null
        }

        // Update last access time for LRU
        entry.lastAccess = System.currentTimeMillis()
        Log.d(TAG, "Cache hit: ${key.take(16)}...")
        return entry.response
    }

    /**
     * Cache a response.
     * Evicts oldest entries if cache is full.
     */
    fun put(key: String, response: AIResponse) {
        // Evict if needed
        while (cache.size >= MAX_SIZE) {
            evictOldest()
        }

        cache[key] = CacheEntry(response)
        Log.d(TAG, "Cached response: ${key.take(16)}... (cache size: ${cache.size})")
    }

    /**
     * Evict the least recently accessed entry.
     */
    private fun evictOldest() {
        val oldest = cache.entries.minByOrNull { it.value.lastAccess }
        oldest?.let {
            cache.remove(it.key)
            Log.d(TAG, "Evicted oldest entry: ${it.key.take(16)}...")
        }
    }

    /**
     * Clear all cached entries.
     */
    fun clear() {
        cache.clear()
        Log.i(TAG, "Cache cleared")
    }

    /**
     * Get current cache statistics.
     */
    fun getStats(): CacheStats {
        val validEntries = cache.values.count { !it.isExpired() }
        val expiredEntries = cache.size - validEntries
        return CacheStats(
            size = cache.size,
            validEntries = validEntries,
            expiredEntries = expiredEntries
        )
    }

    data class CacheStats(
        val size: Int,
        val validEntries: Int,
        val expiredEntries: Int
    )
}
