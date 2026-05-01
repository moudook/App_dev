package com.example.smarty.server.llm

import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.ToolDefinition
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

data class LlmCacheKey(
    val messages: List<LlmMessage>,
    val tools: List<ToolDefinition>,
    val modelOverride: String?,
    val isActionQuery: Boolean,
)

object LlmCache {
    private val logger = LoggerFactory.getLogger(LlmCache::class.java)
    private val cache = ConcurrentHashMap<LlmCacheKey, LlmCacheValue>()

    private const val MAX_SIZE = 500
    private const val TTL_MS = 3600_000 // 1 hour

    data class LlmCacheValue(
        val response: String,
        val timestamp: Long = System.currentTimeMillis(),
        val hadToolCalls: Boolean = false,
    )

    private val ACTION_KEYWORDS =
        setOf(
            "play", "pause", "stop", "resume", "search", "find", "look", "check",
            "show", "display", "tell", "give", "list", "get", "fetch", "read", "open",
            "archive", "unarchive", "delete", "update", "create", "add", "remove", "edit", "modify",
            "count", "schedule", "calendar", "timer", "alarm", "reminder", "set", "launch",
            "notes", "note", "audio", "document", "image", "file", "backup", "settings",
            "screenshot", "share", "navigate", "go to", "move to",
        )

    fun isActionQuery(userMessage: String): Boolean {
        val normalized = userMessage.lowercase().trim()
        return ACTION_KEYWORDS.any { keyword ->
            normalized.startsWith(keyword) ||
                normalized.contains(" $keyword ") ||
                normalized.contains("$keyword ") ||
                normalized.contains(" $keyword")
        } || normalized.contains("my ") ||
            normalized.contains("i have") ||
            normalized.contains("do i have") ||
            normalized.contains("what's in my") ||
            normalized.contains("what is in my")
    }

    /**
     * Get cached response for a key.
     * Returns cached response if valid, null otherwise.
     * 
     * FIXED: Removed incorrect filtering that prevented caching of non-action queries.
     * Cache should work for all query types to improve performance.
     */
    fun get(key: LlmCacheKey): String? {
        val value = cache[key] ?: return null
        if (System.currentTimeMillis() - value.timestamp > TTL_MS) {
            cache.remove(key)
            return null
        }
        // FIXED: Removed check for hadToolCalls - cache all valid responses
        logger.info("LlmCache HIT for query (history size: ${key.messages.size})")
        return value.response
    }

    /**
     * Store response in cache.
     * 
     * FIXED: Removed incorrect filtering that prevented caching of non-action queries.
     * Cache should work for all query types to improve performance.
     */
    fun put(
        key: LlmCacheKey,
        response: String,
        hadToolCalls: Boolean = false,
    ) {
        if (cache.size > MAX_SIZE) {
            val oldest = cache.entries.minByOrNull { it.value.timestamp }
            oldest?.let { cache.remove(it.key) }
        }
        cache[key] = LlmCacheValue(response, hadToolCalls = hadToolCalls)
        logger.debug("Cached query response (action query: ${key.isActionQuery})")
    }

    fun clear() {
        cache.clear()
    }
}