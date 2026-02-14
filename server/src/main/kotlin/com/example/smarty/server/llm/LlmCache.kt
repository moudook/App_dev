package com.example.smarty.server.llm

import java.util.*
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Key for LLM caching, combining all relevant input parameters.
 */
data class LlmCacheKey(
    val messages: List<LlmMessage>,
    val tools: List<ToolDefinition>,
    val model: String?
)

/**
 * Thread-safe LRU cache for LLM responses (KOOG-inspired).
 * This prevents redundant calls to expensive LLM providers for identical prompts.
 */
object LlmCache {
    private val logger = LoggerFactory.getLogger(LlmCache::class.java)
    private val cache = ConcurrentHashMap<LlmCacheKey, LlmCacheValue>()
    
    private const val MAX_SIZE = 500
    private const val TTL_MS = 3600_000 // 1 hour

    data class LlmCacheValue(
        val response: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun get(key: LlmCacheKey): String? {
        val value = cache[key] ?: return null
        if (System.currentTimeMillis() - value.timestamp > TTL_MS) {
            cache.remove(key)
            return null
        }
        logger.info("LlmCache HIT for prompt (history size: ${key.messages.size})")
        return value.response
    }

    fun put(key: LlmCacheKey, response: String) {
        if (cache.size > MAX_SIZE) {
            // Primitive cleanup: remove oldest entry if full
            val oldest = cache.entries.minByOrNull { it.value.timestamp }
            oldest?.let { cache.remove(it.key) }
        }
        cache[key] = LlmCacheValue(response)
    }

    fun clear() {
        cache.clear()
    }
}
