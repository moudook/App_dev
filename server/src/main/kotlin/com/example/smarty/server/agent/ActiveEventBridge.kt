package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentEvent
import org.slf4j.LoggerFactory

/**
 * Bridges [AgentEvent]s from the MCP server layer to the active chat SSE stream.
 *
 * When the OpenCode daemon calls MCP tools (e.g. bash, ask_user), [McpServer]
 * emits [AgentEvent.ApprovalRequested]. That event must reach the Android app's
 * SSE stream which lives in [ChatRoutes]. This bridge connects those two layers.
 *
 * Usage:
 * - [ChatRoutes] calls [register] when an SSE stream starts (passing the same
 *   emitter it gave to [ServerAgent]).
 * - [ChatRoutes] calls [clear] when the SSE stream ends.
 * - [McpServer] calls [emit] to forward approval events to the active stream.
 */
object ActiveEventBridge {
    private val logger = LoggerFactory.getLogger(ActiveEventBridge::class.java)

    @Volatile
    private var activeEmitter: (suspend (AgentEvent) -> Unit)? = null

    fun register(emitter: suspend (AgentEvent) -> Unit) {
        activeEmitter = emitter
        logger.info("[ActiveEventBridge] Registered active SSE emitter")
    }

    fun clear() {
        activeEmitter = null
        logger.info("[ActiveEventBridge] Cleared active SSE emitter")
    }

    suspend fun emit(event: AgentEvent) {
        activeEmitter?.let { emitter ->
            try {
                emitter(event)
                logger.debug("[ActiveEventBridge] Forwarded event: ${event::class.simpleName}")
            } catch (e: Exception) {
                logger.warn("[ActiveEventBridge] Failed to forward event: ${e.message}")
            }
        }
    }
}
