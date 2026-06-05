package com.example.smarty.server.agent

import com.example.smarty.server.data.DatabaseFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Agent tracer interface for tracing agent execution steps
 */
interface AgentTracer {
    suspend fun trace(event: AgentTraceEvent)
}

/**
 * Agent trace event - represents a single step in agent execution
 */
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
class CompositeTracer(
    private val tracers: List<AgentTracer>,
) : AgentTracer {
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
class LoggerTracer(
    private val userId: String,
) : AgentTracer {
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
class MonitoringTracer(
    private val userId: String,
) : AgentTracer {
    override suspend fun trace(event: AgentTraceEvent) {
        try {
            io.micrometer.core.instrument.Metrics
                .counter(
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
 * PostgreSQL tracer - stores trace events in reasoning_traces via ReasoningService.
 * Falls back to direct JDBC insert into agent_traces if ReasoningService is unavailable.
 */
class PostgresTracer(
    private val userId: String,
) : AgentTracer {
    private val logger = LoggerFactory.getLogger(PostgresTracer::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)

    // Lazily initialized — only when a DB connection is available
    private val reasoningService: com.example.smarty.server.services.ReasoningService? by lazy {
        val ds = DatabaseFactory.getDataSource() ?: return@lazy null
        com.example.smarty.server.services
            .ReasoningService(
                com.example.smarty.server.data
                    .ReasoningTraceRepository(ds),
            )
    }

    private fun AgentStepType.toReasoningStepType(): com.example.smarty.server.data.ReasoningStepType =
        when (this) {
            AgentStepType.THOUGHT -> com.example.smarty.server.data.ReasoningStepType.ANALYSIS
            AgentStepType.TOOL_CALL -> com.example.smarty.server.data.ReasoningStepType.VERIFICATION
            AgentStepType.TOOL_RESULT -> com.example.smarty.server.data.ReasoningStepType.VERIFICATION
            AgentStepType.FINAL -> com.example.smarty.server.data.ReasoningStepType.SYNTHESIS
            AgentStepType.ERROR -> com.example.smarty.server.data.ReasoningStepType.REFLECTION
            AgentStepType.CHECKPOINT -> com.example.smarty.server.data.ReasoningStepType.PLANNING
        }

    override suspend fun trace(event: AgentTraceEvent) {
        val service = reasoningService
        if (service != null) {
            // Primary path: structured reasoning_traces row
            try {
                service.logReasoningStep(
                    sessionId = event.sessionId,
                    messageId = null,
                    userId = userId,
                    stepType = event.stepType.toReasoningStepType(),
                    title =
                        event.stepType.name
                            .lowercase()
                            .replace('_', ' '),
                    content = event.content.take(10_000),
                    confidenceScore = if (event.stepType == AgentStepType.FINAL) 0.9 else 0.6,
                    durationMs = System.currentTimeMillis() - event.timestamp,
                    isFinal = event.stepType == AgentStepType.FINAL,
                )
            } catch (e: Exception) {
                logger.warn("ReasoningService trace failed, falling back to agent_traces: ${e.message}")
                insertRawTrace(event)
            }
        } else {
            // Fallback path: raw agent_traces insert (no DB or missing reasoningTraceRepository)
            insertRawTrace(event)
        }
    }

    private fun insertRawTrace(event: AgentTraceEvent) {
        val dataSource = DatabaseFactory.getDataSource() ?: return
        scope.launch {
            try {
                dataSource.connection.use { conn ->
                    conn
                        .prepareStatement(
                            """
                        INSERT INTO agent_traces (
                            id, session_id, user_id, step_name, step_type, content,
                            input_data, output_data, error_message, metadata, created_at
                        ) VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                        """,
                        ).use { stmt ->
                            stmt.setString(1, event.eventId)
                            stmt.setString(2, event.sessionId)
                            stmt.setObject(3, java.util.UUID.fromString(userId))
                            stmt.setString(4, event.stepType.name.lowercase())
                            stmt.setString(5, event.stepType.name)
                            stmt.setString(6, event.content.take(10_000))
                            stmt.setString(7, null)
                            stmt.setString(8, null)
                            stmt.setString(9, null)
                            stmt.setString(10, "{}")
                            stmt.setTimestamp(11, java.sql.Timestamp.from(Instant.ofEpochMilli(event.timestamp)))
                            stmt.executeUpdate()
                        }
                }
            } catch (e: Exception) {
                logger.warn("Failed to store raw agent trace: ${e.message}")
            }
        }
    }
}

/**
 * Agent checkpoint persistence manager
 * Handles saving and loading agent execution state for recovery
 */
class AgentPersistenceManager(
    private val userId: String,
) {
    private val logger = LoggerFactory.getLogger(AgentPersistenceManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun saveCheckpoint(
        sessionId: String,
        messages: List<com.example.smarty.server.llm.LlmMessage>,
        context: String,
    ) {
        val dataSource = DatabaseFactory.getDataSource() ?: return

        try {
            dataSource.connection.use { conn ->
                val stateJson =
                    buildJsonObject {
                        put("messages", buildJsonArray { })
                        put("context", kotlinx.serialization.json.JsonPrimitive(context))
                    }.toString()

                val stmt =
                    conn.prepareStatement(
                        """
                    INSERT INTO agent_checkpoints (
                        id, session_id, user_id, state_json, version, created_at, updated_at
                    ) VALUES (?::uuid, ?::uuid, ?::uuid, ?::jsonb, ?, ?, ?)
                    ON CONFLICT (session_id) DO UPDATE SET
                        state_json = EXCLUDED.state_json,
                        version = agent_checkpoints.version + 1,
                        updated_at = EXCLUDED.updated_at
                """,
                    )

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
                    WHERE session_id = ?::uuid AND user_id = ?::uuid
                    ORDER BY updated_at DESC LIMIT 1
                """,
                    )

                stmt.setString(1, sessionId)
                stmt.setObject(2, java.util.UUID.fromString(userId))

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val stateJson = rs.getString("state_json")
                        try {
                            val parsed = json.parseToJsonElement(stateJson).jsonObject
                            val context = parsed["context"]?.jsonPrimitive?.content ?: ""
                            CheckpointResult(context = context)
                        } catch (e: Exception) {
                            logger.warn("Failed to parse checkpoint JSON: ${e.message}")
                            CheckpointResult(context = stateJson)
                        }
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
                    DELETE FROM agent_checkpoints WHERE session_id = ?::uuid AND user_id = ?::uuid
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
        val messages: List<com.example.smarty.server.llm.LlmMessage> = emptyList(),
        val context: String = "",
        val version: Int = 0,
    )
}
