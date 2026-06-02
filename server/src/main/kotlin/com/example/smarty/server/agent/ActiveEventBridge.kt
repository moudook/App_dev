package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentEvent
import org.slf4j.LoggerFactory

/**
 * No-op bridge — events flow through AgentRunManager only.
 */
object ActiveEventBridge {
    private val logger = LoggerFactory.getLogger(ActiveEventBridge::class.java)

    fun clear(userId: String) {
        logger.info("[ActiveEventBridge] Cleared for user: $userId")
    }

    suspend fun emit(
        sessionId: String?,
        event: AgentEvent,
    ) {
        // No-op — plugin bridge handles all events
    }
}
