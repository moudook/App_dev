package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentEvent
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges [AgentEvent]s from the MCP server layer to the active chat SSE stream.
 *
 * When the MCP daemon calls ask_user and no Android client is connected yet,
 * events are queued and flushed when the next emit() call finds an emitter.
 */
object ActiveEventBridge {
    private val logger = LoggerFactory.getLogger(ActiveEventBridge::class.java)

    private val emitters = ConcurrentHashMap<String, suspend (AgentEvent) -> Unit>()
    private val pendingEvents = ConcurrentHashMap<String, MutableList<AgentEvent>>()

    fun register(
        userId: String,
        emitter: suspend (AgentEvent) -> Unit,
    ) {
        emitters[userId] = emitter
        logger.info("[ActiveEventBridge] Registered active SSE emitter for user: $userId")
    }

    fun clear(userId: String) {
        emitters.remove(userId)
        pendingEvents.remove(userId)
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
            // Flush any pending events first
            val queued = pendingEvents.remove(sid)
            if (queued != null && queued.isNotEmpty()) {
                logger.info("[ActiveEventBridge] Flushing ${queued.size} pending events for user: $sid")
                for (pendingEvent in queued) {
                    try {
                        emitter(pendingEvent)
                    } catch (e: Exception) {
                        logger.warn("[ActiveEventBridge] Failed to flush pending event for user $sid: ${e.message}")
                    }
                }
            }
            try {
                emitter(event)
                logger.debug("[ActiveEventBridge] Forwarded event: ${event::class.simpleName} for user: $sid")
            } catch (e: Exception) {
                logger.warn("[ActiveEventBridge] Failed to forward event for user $sid: ${e.message}")
            }
        } else {
            // Queue the event so it's delivered when the Android client connects
            logger.info("[ActiveEventBridge] No active emitter for user $sid — queuing event: ${event::class.simpleName}")
            pendingEvents.getOrPut(sid) { mutableListOf() }.add(event)
        }
    }
}
