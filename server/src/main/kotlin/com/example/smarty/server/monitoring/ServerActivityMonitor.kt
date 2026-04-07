package com.example.smarty.server.monitoring

import com.example.smarty.server.agent.AgentTraceEvent
import kotlinx.serialization.Serializable
import java.util.Collections

/**
 * Data model for a server activity event.
 */
@Serializable
data class ActivityEvent(
    val timestamp: Long,
    val userId: String,
    val sessionId: String,
    val type: String,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Global monitor that keeps track of recent server activities and agent actions.
 * This provides the data for the "Advanced Health" dashboard.
 */
object ServerActivityMonitor {
    private const val MAX_EVENTS = 200
    private val events = Collections.synchronizedList(mutableListOf<ActivityEvent>())

    /**
     * Record a new activity event.
     */
    fun recordEvent(
        userId: String,
        event: AgentTraceEvent,
    ) {
        val activity =
            ActivityEvent(
                timestamp = event.timestamp,
                userId = userId,
                sessionId = event.sessionId,
                type = event.stepType.name,
                content = event.content,
                metadata = event.metadata,
            )

        synchronized(events) {
            events.add(0, activity)
            if (events.size > MAX_EVENTS) {
                events.subList(MAX_EVENTS, events.size).clear()
            }
        }
    }

    /**
     * Get the list of most recent events.
     */
    fun getRecentEvents(): List<ActivityEvent> {
        return synchronized(events) {
            events.toList()
        }
    }

    /**
     * Clear all recorded events.
     */
    fun clear() {
        events.clear()
    }
}
