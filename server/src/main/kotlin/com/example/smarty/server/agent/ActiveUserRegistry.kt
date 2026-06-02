package com.example.smarty.server.agent

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the currently active user for each chat SSE stream.
 * McpServer uses this to route daemon-originated ask_user events
 * to the correct WebSocket client instead of using a stale fallback userId.
 */
object ActiveUserRegistry {
    private val logger = LoggerFactory.getLogger(ActiveUserRegistry::class.java)
    private val activeUsers = ConcurrentHashMap<String, Long>()

    fun setActive(userId: String) {
        activeUsers[userId] = System.currentTimeMillis()
        logger.debug("[ActiveUserRegistry] User marked active: $userId")
    }

    fun clearActive(userId: String) {
        activeUsers.remove(userId)
        logger.debug("[ActiveUserRegistry] User cleared: $userId")
    }

    /**
     * Returns the most recently active userId, or null if no users are active.
     * Used by McpServer when the daemon calls ask_user from localhost
     * and there's no authenticated principal.
     */
    fun getMostRecentActiveUser(): String? =
        activeUsers.entries
            .maxByOrNull { it.value }
            ?.key
}
