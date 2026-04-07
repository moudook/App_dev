package com.example.smarty.server.llm

import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class LlmCacheKey(
    val messages: List<LlmMessage>,
    val tools: List<ToolDefinition>,
    val model: String?,
    val isActionQuery: Boolean = false,
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

    fun get(key: LlmCacheKey): String? {
        if (!key.isActionQuery) {
            logger.debug("Skipping cache for non-action query")
            return null
        }
        val value = cache[key] ?: return null
        if (System.currentTimeMillis() - value.timestamp > TTL_MS) {
            cache.remove(key)
            return null
        }
        if (!value.hadToolCalls) {
            logger.debug("Cache entry had no tool calls, skipping")
            return null
        }
        logger.info("LlmCache HIT for action query (history size: ${key.messages.size})")
        return value.response
    }

    fun put(
        key: LlmCacheKey,
        response: String,
        hadToolCalls: Boolean = false,
    ) {
        if (!key.isActionQuery) {
            logger.debug("Not caching non-action query response")
            return
        }
        if (!hadToolCalls) {
            logger.debug("Not caching response without tool calls")
            return
        }
        if (cache.size > MAX_SIZE) {
            val oldest = cache.entries.minByOrNull { it.value.timestamp }
            oldest?.let { cache.remove(it.key) }
        }
        cache[key] = LlmCacheValue(response, hadToolCalls = hadToolCalls)
        logger.debug("Cached action query response")
    }

    fun clear() {
        cache.clear()
    }
}
