package com.example.smarty.server.llm

import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class LlmCacheKey(
    val messages: List<LlmMessage>,
    val tools: List<ToolDefinition>,
    val modelOverride: String?,
    val isActionQuery: Boolean,
)

object LlmCache {
    private val logger = LoggerFactory.getLogger(LlmCache::class.java)
    private val cache = ConcurrentHashMap<String, LlmCacheValue>()

    private const val MAX_ENTRIES = 200
    private const val TTL_MS = 3_600_000L // 1 hour
    private const val MAX_VALUE_BYTES = 512 * 1024 // 512KB per entry
    private const val CLEANUP_INTERVAL_MS = 300_000L // 5 minutes
    private const val EVICTION_BATCH_SIZE = 20 // Evict this many when over limit

    @Volatile
    private var lastCleanup = System.currentTimeMillis()

    data class LlmCacheValue(
        val response: String,
        val timestamp: Long = System.currentTimeMillis(),
        val hadToolCalls: Boolean = false,
        val sizeBytes: Int = response.toByteArray(Charsets.UTF_8).size,
    )

    private val ACTION_KEYWORDS =
        setOf(
            "play", "pause", "stop", "resume", "search", "find", "look",
            "check", "show", "display", "tell", "give", "list", "get",
            "fetch", "read", "open", "archive", "unarchive", "delete",
            "update", "create", "add", "remove", "edit", "modify", "count",
            "schedule", "calendar", "timer", "alarm", "reminder", "set",
            "launch", "notes", "note", "audio", "document", "image",
            "file", "backup", "settings", "screenshot", "share", "navigate",
            "go to", "move to",
        )

    fun isActionQuery(userMessage: String): Boolean {
        val normalized = userMessage.lowercase().trim()
        return ACTION_KEYWORDS.any { keyword ->
            normalized.startsWith(keyword) ||
                normalized.contains(" $keyword ") ||
                normalized.contains("$keyword ") ||
                normalized.contains(" $keyword")
        } ||
            normalized.contains("my ") ||
            normalized.contains("i have") ||
            normalized.contains("do i have") ||
            normalized.contains("what's in my") ||
            normalized.contains("what is in my")
    }

    /**
     * Hash the cache key to a fixed-size string. Avoids storing full conversation
     * histories as map keys which caused unbounded heap growth.
     */
    private fun hashKey(key: LlmCacheKey): String {
        val sb = StringBuilder()
        // Only use the last 6 messages for the hash — full history makes cache hits rare anyway
        val recentMessages = key.messages.takeLast(6)
        for (msg in recentMessages) {
            sb.append(msg.role.name).append(':').append(msg.content.take(500)).append('|')
        }
        sb.append("model=").append(key.modelOverride ?: "default")
        sb.append("|tools=").append(key.tools.size)
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(sb.toString().toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * Get cached response for a key.
     */
    fun get(key: LlmCacheKey): String? {
        maybeCleanup()
        val hashedKey = hashKey(key)
        val value = cache[hashedKey] ?: return null
        if (System.currentTimeMillis() - value.timestamp > TTL_MS) {
            cache.remove(hashedKey)
            return null
        }
        logger.info("LlmCache HIT (history size: ${key.messages.size})")
        return value.response
    }

    /**
     * Store response in cache.
     */
    fun put(
        key: LlmCacheKey,
        response: String,
        hadToolCalls: Boolean = false,
    ) {
        // Skip caching oversized responses
        val responseBytes = response.toByteArray(Charsets.UTF_8).size
        if (responseBytes > MAX_VALUE_BYTES) {
            logger.debug("Skipping cache - response too large (${responseBytes / 1024}KB)")
            return
        }

        val hashedKey = hashKey(key)

        // Batch evict when over limit
        if (cache.size >= MAX_ENTRIES) {
            evictOldest(EVICTION_BATCH_SIZE)
        }

        cache[hashedKey] = LlmCacheValue(response, hadToolCalls = hadToolCalls)
        logger.debug("Cached LLM response (hash: ${hashedKey.take(8)}...)")
    }

    /**
     * Proactively clean up expired entries. Called lazily on get/put.
     */
    private fun maybeCleanup() {
        val now = System.currentTimeMillis()
        if (now - lastCleanup < CLEANUP_INTERVAL_MS) return
        lastCleanup = now

        val expired = cache.entries.filter { now - it.value.timestamp > TTL_MS }
        expired.forEach { cache.remove(it.key) }
        if (expired.isNotEmpty()) {
            logger.debug("LlmCache cleanup: removed ${expired.size} expired entries")
        }
    }

    /**
     * Remove the oldest N entries by timestamp.
     */
    private fun evictOldest(count: Int) {
        val toRemove = cache.entries
            .sortedBy { it.value.timestamp }
            .take(count)
        toRemove.forEach { cache.remove(it.key) }
        if (toRemove.isNotEmpty()) {
            logger.debug("LlmCache eviction: removed ${toRemove.size} oldest entries")
        }
    }

    fun clear() {
        cache.clear()
    }

    fun size(): Int = cache.size
}
