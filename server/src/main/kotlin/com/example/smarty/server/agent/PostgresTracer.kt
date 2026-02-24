package com.example.smarty.server.agent

import com.example.smarty.server.data.DatabaseFactory
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Persists agent trace events to PostgreSQL for long-term observability.
 * Non-blocking with timeout to prevent hanging.
 */
class PostgresTracer(private val userId: String) : AgentTracer {
    private val logger = LoggerFactory.getLogger(PostgresTracer::class.java)
    private val dataSource = DatabaseFactory.getDataSource() as? HikariDataSource
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun trace(event: AgentTraceEvent) {
        if (dataSource == null) return

        try {
            withTimeout(3000) {
                withContext(Dispatchers.IO) {
                    try {
                        dataSource.connection.use { conn ->
                            val sql = """
                                INSERT INTO agent_traces (session_id, user_id, step_type, content, metadata)
                                VALUES (?, ?, ?, ?, ?::jsonb)
                            """.trimIndent()

                            conn.prepareStatement(sql).use { stmt ->
                                stmt.setObject(1, UUID.fromString(event.sessionId))
                                stmt.setString(2, userId)
                                stmt.setString(3, event.stepType.name)
                                stmt.setString(4, event.content)
                                stmt.setString(5, json.encodeToString(event.metadata))
                                stmt.executeUpdate()
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to persist agent trace: ${e.message}")
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn("Trace persistence timed out - continuing without trace")
        }
    }
}
