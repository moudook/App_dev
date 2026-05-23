package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentEvent
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges [AgentEvent]s from the MCP server layer to the active chat SSE stream.
 */
object ActiveEventBridge {
    private val logger = LoggerFactory.getLogger(ActiveEventBridge::class.java)

    private val emitters = ConcurrentHashMap<String, suspend (AgentEvent) -> Unit>()

    fun register(
        userId: String,
        emitter: suspend (AgentEvent) -> Unit,
    ) {
        emitters[userId] = emitter
        logger.info("[ActiveEventBridge] Registered active SSE emitter for user: $userId")
    }

    fun clear(userId: String) {
        emitters.remove(userId)
        logger.info("[ActiveEventBridge] Cleared active SSE emitter for user: $userId")
    }

    suspend fun emit(
        sessionId: String?,
        event: AgentEvent,
    ) {
        val sid = sessionId
        if (sid == null) {
            logger.warn("[ActiveEventBridge] emit() called with null sessionId — refusing to guess target session")
            return
        }
        val emitter = emitters[sid]
        if (emitter != null) {
            try {
                emitter(event)
                logger.debug("[ActiveEventBridge] Forwarded event: ${event::class.simpleName} for user: $sid")
            } catch (e: Exception) {
                logger.warn("[ActiveEventBridge] Failed to forward event for user $sid: ${e.message}")
            }
        } else {
            logger.warn("[ActiveEventBridge] No active emitter found for user: $sid")
        }
    }
}
