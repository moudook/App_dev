package com.example.smarty.server.agent

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Types of events that can occur during an agent's execution.
 */
enum class AgentStepType {
    THOUGHT,    // LLM internal reasoning/content
    TOOL_CALL,  // Tool invocation
    TOOL_RESULT,// Result of a tool call
    ERROR,      // Execution error
    FINAL       // Final response to user
}

/**
 * Structured trace event representing a single step in the agent's chain of thought.
 */
@Serializable
data class AgentTraceEvent(
    val traceId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val stepType: AgentStepType,
    val content: String,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Interface for pluggable agent tracing.
 */
interface AgentTracer {
    suspend fun trace(event: AgentTraceEvent)
}

/**
 * Default tracer that logs events and prepares them for the frontend/observability platforms.
 */
class LoggerTracer(private val userId: String) : AgentTracer {
    private val logger = org.slf4j.LoggerFactory.getLogger("AgentTracer[$userId]")

    override suspend fun trace(event: AgentTraceEvent) {
        logger.info("Agent Trace [{}]: {} - {}", 
            event.sessionId, 
            event.stepType, 
            event.content.take(100).replace("\n", " ")
        )
    }
}

/**
 * Forwards trace events to the global ServerActivityMonitor.
 */
class MonitoringTracer(private val userId: String) : AgentTracer {
    override suspend fun trace(event: AgentTraceEvent) {
        // Use reflection or a common interface to avoid circular dependency if needed,
        // but since ServerActivityMonitor is in a subpackage or sibling, we can just import it.
        com.example.smarty.server.monitoring.ServerActivityMonitor.recordEvent(userId, event)
    }
}

/**
 * Combines multiple tracers into one.
 */
class CompositeTracer(private val tracers: List<AgentTracer>) : AgentTracer {
    override suspend fun trace(event: AgentTraceEvent) {
        tracers.forEach { it.trace(event) }
    }
}
