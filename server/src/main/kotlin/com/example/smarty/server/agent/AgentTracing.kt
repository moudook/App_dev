package com.example.smarty.server.agent

import com.example.smarty.server.data.DatabaseFactory
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Extracted tracing and monitoring logic from ServerAgent.kt
 * Handles agent execution tracing, metrics, and persistence
 */
interface AgentTracer {
    suspend fun trace(event: AgentTraceEvent)
}

data class AgentTraceEvent(
    val sessionId: String,
    val stepType: AgentStepType,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val eventId: String = UUID.randomUUID().toString(),
)

enum class AgentStepType {
    THOUGHT,
    TOOL_CALL,
    TOOL_RESULT,
    FINAL,
    ERROR,
    CHECKPOINT,
}

/**
 * Composite tracer that delegates to multiple tracers
 */
class CompositeTracer(private val tracers: List<AgentTracer>) : AgentTracer {
    override suspend fun trace(event: AgentTraceEvent) {
        tracers.forEach { tracer ->
            try {
                tracer.trace(event)
            } catch (e: Exception) {
                // Ignore tracer failures - they shouldn't break agent execution
            }
        }
    }
}

/**
 * Logger tracer - logs trace events to SLF4J
 */
class LoggerTracer(private val userId: String) : AgentTracer {
    private val logger = LoggerFactory.getLogger(LoggerTracer::class.java)

    override suspend fun trace(event: AgentTraceEvent) {
        logger.debug(
            "[AgentTrace] user=$userId session=${event.sessionId} type=${event.stepType} content=${event.content.take(100)}",
        )
    }
}

/**
 * Metrics tracer - records metrics using Micrometer
 */
class MonitoringTracer(private val userId: String) : AgentTracer {
    override suspend fun trace(event: AgentTraceEvent) {
        try {
            io.micrometer.core.instrument.Metrics.counter(
                "agent.trace.events",
                "stepType",
                event.stepType.name,
                "userId",
                userId.take(8),
            ).increment()
        } catch (e: Exception) {
            // Ignore metrics failures
        }
    }
}

/**
 * PostgreSQL tracer - stores trace events in database for later analysis
 */
class PostgresTracer(private val userId: String) : AgentTracer {
    private val logger = LoggerFactory.getLogger(PostgresTracer::class.java)

    override suspend fun trace(event: AgentTraceEvent) {
        val dataSource = DatabaseFactory.getDataSource() ?: return

        try {
            dataSource.connection.use { conn ->
                val stmt =
                    conn.prepareStatement(
                        """
                    INSERT INTO agent_traces (
                        id, session_id, user_id, step_name, step_type, content,
                        input_data, output_data, error_message, metadata, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                    )

                stmt.use {
                    stmt.setString(1, event.eventId)
                    stmt.setString(2, event.sessionId)
                    stmt.setObject(3, java.util.UUID.fromString(userId))
                    stmt.setString(4, event.stepType.name.lowercase())
                    stmt.setString(5, event.stepType.name)
                    stmt.setString(6, event.content.take(10000))
                    stmt.setString(7, null)
                    stmt.setString(8, null)
                    stmt.setString(9, null)
                    stmt.setString(10, "{}")
                    stmt.setTimestamp(11, java.sql.Timestamp.from(Instant.ofEpochMilli(event.timestamp)))

                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to store agent trace: ${e.message}")
        }
    }
}

/**
 * Agent checkpoint persistence manager
 * Handles saving and loading agent execution state for recovery
 */
class AgentPersistenceManager(private val userId: String) {
    private val logger = LoggerFactory.getLogger(AgentPersistenceManager::class.java)

    fun saveCheckpoint(
        sessionId: String,
        messages: List<com.example.smarty.server.llm.LlmMessage>,
        context: String,
    ) {
        val dataSource = DatabaseFactory.getDataSource() ?: return

        try {
            dataSource.connection.use { conn ->
                val stmt =
                    conn.prepareStatement(
                        """
                    INSERT INTO agent_checkpoints (
                        id, session_id, user_id, state_json, version, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (session_id) DO UPDATE SET
                        state_json = EXCLUDED.state_json,
                        version = agent_checkpoints.version + 1,
                        updated_at = EXCLUDED.updated_at
                """,
                    )

                val stateJson =
                    buildString {
                        append("{")
                        append("\"messages\": [],")
                        append("\"context\": \"${context.replace("\"", "\\\"")}\"")
                        append("}")
                    }

                stmt.use {
                    stmt.setString(1, UUID.randomUUID().toString())
                    stmt.setString(2, sessionId)
                    stmt.setObject(3, java.util.UUID.fromString(userId))
                    stmt.setString(4, stateJson)
                    stmt.setInt(5, 1)
                    stmt.setTimestamp(6, java.sql.Timestamp.from(Instant.now()))
                    stmt.setTimestamp(7, java.sql.Timestamp.from(Instant.now()))

                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to save checkpoint: ${e.message}")
        }
    }

    fun loadCheckpoint(sessionId: String): CheckpointResult? {
        val dataSource = DatabaseFactory.getDataSource() ?: return null

        return try {
            dataSource.connection.use { conn ->
                val stmt =
                    conn.prepareStatement(
                        """
                    SELECT state_json FROM agent_checkpoints 
                    WHERE session_id = ? AND user_id = ?
                    ORDER BY updated_at DESC LIMIT 1
                """,
                    )

                stmt.setString(1, sessionId)
                stmt.setObject(2, java.util.UUID.fromString(userId))

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val stateJson = rs.getString("state_json")
                        // For now, return null - full checkpoint restoration needs proper JSON parsing
                        // This is a placeholder that can be extended later
                        null
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to load checkpoint: ${e.message}")
            null
        }
    }

    fun clearCheckpoint(sessionId: String) {
        val dataSource = DatabaseFactory.getDataSource() ?: return

        try {
            dataSource.connection.use { conn ->
                val stmt =
                    conn.prepareStatement(
                        """
                    DELETE FROM agent_checkpoints WHERE session_id = ? AND user_id = ?
                """,
                    )

                stmt.setString(1, sessionId)
                stmt.setObject(2, java.util.UUID.fromString(userId))
                stmt.executeUpdate()
            }
        } catch (e: Exception) {
            logger.warn("Failed to clear checkpoint: ${e.message}")
        }
    }

    data class CheckpointResult(
        val messages: List<com.example.smarty.server.llm.LlmMessage>,
        val version: Int,
    )
}
