package com.example.smarty.data.cache

import android.util.Log
import com.example.smarty.data.local.AICacheDao
import com.example.smarty.data.local.CachedAIResponse
import com.example.smarty.data.remote.AIResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent AI response cache using Room database.
 *
 * Features:
 * - Thread-safe with in-memory fast lookup + Room persistence
 * - TTL-based expiration (24 hours default for persistence, 30 min for memory)
 * - LRU eviction when max size exceeded
 * - Content-based hashing for cache keys
 * - Survives app restarts
 */
class AIResponseCache(
    private val cacheDao: AICacheDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val TAG = "AIResponseCache"
    private val MAX_SIZE = 100
    private val DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours for persistent cache

    // In-memory cache for fast lookups
    private data class MemoryEntry(
        val response: AIResponse,
        val timestamp: Long = System.currentTimeMillis(),
        var lastAccess: Long = System.currentTimeMillis(),
    ) {
        fun isExpired(ttlMs: Long = 30 * 60 * 1000L): Boolean = System.currentTimeMillis() - timestamp > ttlMs
    }

    private val memoryCache = ConcurrentHashMap<String, MemoryEntry>()

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    init {
        // Prune expired entries on startup
        scope.launch {
            try {
                val pruned = pruneExpired()
                if (pruned > 0) {
                    Log.i(TAG, "Pruned $pruned expired cache entries on startup")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error pruning cache on startup", e)
            }
        }
    }

    /**
     * Generate a cache key from content by hashing it.
     * Normalizes whitespace and trims content to create consistent keys.
     */
    fun generateKey(content: String): String {
        // Normalize content: trim, collapse whitespace
        val normalized =
            content
                .trim()
                .replace(Regex("\\s+"), " ")
                .take(2000) // Only hash first 2000 chars for performance

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
     * Checks memory cache first, then falls back to Room.
     */
    suspend fun get(key: String): AIResponse? {
        // Check memory cache first
        val memoryEntry = memoryCache[key]
        if (memoryEntry != null) {
            if (memoryEntry.isExpired()) {
                memoryCache.remove(key)
                Log.d(TAG, "Memory cache entry expired: ${key.take(16)}...")
            } else {
                memoryEntry.lastAccess = System.currentTimeMillis()
                Log.d(TAG, "Memory cache hit: ${key.take(16)}...")
                return memoryEntry.response
            }
        }

        // Fall back to Room
        return try {
            val cached = cacheDao.get(key, System.currentTimeMillis())
            if (cached != null) {
                val response = json.decodeFromString<AIResponse>(cached.jsonResponse)
                // Add to memory cache for faster subsequent lookups
                memoryCache[key] = MemoryEntry(response)
                // Update last access in Room
                cacheDao.updateLastAccess(key)
                Log.d(TAG, "Room cache hit: ${key.take(16)}...")
                response
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading from Room cache", e)
            null
        }
    }

    /**
     * Cache a response in both memory and Room.
     * Evicts oldest entries if cache is full.
     */
    suspend fun put(
        key: String,
        response: AIResponse,
    ) {
        // Evict memory cache if needed
        while (memoryCache.size >= MAX_SIZE / 2) {
            evictOldestFromMemory()
        }

        // Add to memory cache
        memoryCache[key] = MemoryEntry(response)
        Log.d(TAG, "Added to memory cache: ${key.take(16)}... (memory size: ${memoryCache.size})")

        // Persist to Room
        try {
            // Check Room size and evict if needed
            val count = cacheDao.getCount()
            if (count >= MAX_SIZE) {
                val toEvict = count - MAX_SIZE + 10 // Evict extra to avoid frequent evictions
                cacheDao.evictOldest(toEvict)
                Log.d(TAG, "Evicted $toEvict entries from Room cache")
            }

            val entry =
                CachedAIResponse(
                    contentHash = key,
                    jsonResponse = json.encodeToString(response),
                    createdAt = System.currentTimeMillis(),
                    expiresAt = System.currentTimeMillis() + DEFAULT_TTL_MS,
                    lastAccessedAt = System.currentTimeMillis(),
                )
            cacheDao.put(entry)
            Log.d(TAG, "Persisted to Room cache: ${key.take(16)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to Room cache", e)
        }
    }

    /**
     * Evict the least recently accessed entry from memory.
     */
    private fun evictOldestFromMemory() {
        val oldest = memoryCache.entries.minByOrNull { it.value.lastAccess }
        oldest?.let {
            memoryCache.remove(it.key)
            Log.d(TAG, "Evicted oldest from memory: ${it.key.take(16)}...")
        }
    }

    /**
     * Prune expired entries from Room cache.
     * @return number of entries pruned
     */
    suspend fun pruneExpired(): Int =
        try {
            val before = cacheDao.getCount()
            cacheDao.pruneExpired(System.currentTimeMillis())
            val after = cacheDao.getCount()
            before - after
        } catch (e: Exception) {
            Log.e(TAG, "Error pruning cache", e)
            0
        }

    /**
     * Clear all cached entries (both memory and Room).
     */
    suspend fun clear() {
        memoryCache.clear()
        try {
            cacheDao.clearAll()
            Log.i(TAG, "Cache cleared (memory and Room)")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing Room cache", e)
        }
    }

    /**
     * Get current cache statistics.
     */
    suspend fun getStats(): CacheStats {
        val memorySize = memoryCache.size
        val memoryValid = memoryCache.values.count { !it.isExpired() }

        return try {
            val roomSize = cacheDao.getCount()
            val roomValid = cacheDao.getValidCount(System.currentTimeMillis())
            CacheStats(
                memorySize = memorySize,
                memoryValid = memoryValid,
                roomSize = roomSize,
                roomValid = roomValid,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting cache stats", e)
            CacheStats(
                memorySize = memorySize,
                memoryValid = memoryValid,
                roomSize = 0,
                roomValid = 0,
            )
        }
    }

    data class CacheStats(
        val memorySize: Int,
        val memoryValid: Int,
        val roomSize: Int,
        val roomValid: Int,
    ) {
        val totalSize: Int get() = memorySize + roomSize
        val totalValid: Int get() = memoryValid + roomValid
    }

    companion object {
        /**
         * Legacy singleton for backwards compatibility during migration.
         * New code should inject AIResponseCache instance.
         */
        @Deprecated("Use injected instance instead", ReplaceWith("AIResponseCache(cacheDao)"))
        object Legacy {
            private const val TAG = "AIResponseCache.Legacy"
            private const val MAX_SIZE = 50
            private const val DEFAULT_TTL_MS = 30 * 60 * 1000L // 30 minutes

            private data class CacheEntry(
                val response: AIResponse,
                val timestamp: Long = System.currentTimeMillis(),
                var lastAccess: Long = System.currentTimeMillis(),
            ) {
                fun isExpired(ttlMs: Long = DEFAULT_TTL_MS): Boolean = System.currentTimeMillis() - timestamp > ttlMs
            }

            private val cache = ConcurrentHashMap<String, CacheEntry>()

            fun generateKey(content: String): String {
                val normalized =
                    content
                        .trim()
                        .replace(Regex("\\s+"), " ")
                        .take(2000)

                return try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val hash = digest.digest(normalized.toByteArray(Charsets.UTF_8))
                    hash.joinToString("") { "%02x".format(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "Hash failed, using content prefix", e)
                    normalized.take(100).hashCode().toString()
                }
            }

            fun get(key: String): AIResponse? {
                val entry = cache[key] ?: return null

                if (entry.isExpired()) {
                    cache.remove(key)
                    return null
                }

                entry.lastAccess = System.currentTimeMillis()
                return entry.response
            }

            fun put(
                key: String,
                response: AIResponse,
            ) {
                while (cache.size >= MAX_SIZE) {
                    evictOldest()
                }
                cache[key] = CacheEntry(response)
            }

            private fun evictOldest() {
                val oldest = cache.entries.minByOrNull { it.value.lastAccess }
                oldest?.let { cache.remove(it.key) }
            }

            fun clear() {
                cache.clear()
            }
        }
    }
}
