package com.example.smarty.server.agent

import com.example.smarty.server.data.DatabaseFactory
import com.example.smarty.server.llm.LlmMessage
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Snapshot of the agent session state.
 */
@Serializable
data class AgentCheckpoint(
    val messages: List<LlmMessage>,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Manages saving and restoring agent session checkpoints (KOOG Persistence).
 */
class AgentPersistenceManager(private val userId: String) {
    private val logger = LoggerFactory.getLogger(AgentPersistenceManager::class.java)
    private val dataSource = DatabaseFactory.getDataSource() as? HikariDataSource
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Saves the current agent state for a specific session.
     * Uses a DELETE + INSERT approach to overwrite the latest version (version=1 slot),
     * since the unique constraint is (session_id, version).
     */
    suspend fun saveCheckpoint(sessionId: String, messages: List<LlmMessage>, lastNode: String? = null) {
        if (dataSource == null) return

        try {
            withTimeout(5000) {
                withContext(Dispatchers.IO) {
                    try {
                        dataSource.connection.use { conn ->
                            val checkpoint = AgentCheckpoint(messages)
                            val stateJson = json.encodeToString(checkpoint)

                            // Upsert: delete existing version=1 slot then insert fresh
                            val deleteSql = """
                                DELETE FROM agent_checkpoints
                                WHERE session_id = ? AND user_id = ? AND version = 1
                            """.trimIndent()
                            conn.prepareStatement(deleteSql).use { stmt ->
                                stmt.setObject(1, UUID.fromString(sessionId))
                                stmt.setObject(2, UUID.fromString(userId))
                                stmt.executeUpdate()
                            }

                            val insertSql = """
                                INSERT INTO agent_checkpoints (session_id, user_id, state_json, version)
                                VALUES (?, ?, ?::jsonb, 1)
                            """.trimIndent()
                            conn.prepareStatement(insertSql).use { stmt ->
                                stmt.setObject(1, UUID.fromString(sessionId))
                                stmt.setObject(2, UUID.fromString(userId))
                                stmt.setString(3, stateJson)
                                stmt.executeUpdate()
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to save agent checkpoint: ${e.message}")
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn("Checkpoint save timed out for session $sessionId - continuing without persistence")
        }
    }

    /**
     * Loads the latest checkpoint for a session.
     * Non-blocking with timeout.
     */
    suspend fun loadCheckpoint(sessionId: String): AgentCheckpoint? {
        if (dataSource == null) return null

        return try {
            withTimeout(3000) {
                withContext(Dispatchers.IO) {
                    try {
                        dataSource.connection.use { conn ->
                            val sql = """
                                SELECT state_json FROM agent_checkpoints
                                WHERE session_id = ? AND user_id = ?
                                ORDER BY version DESC
                                LIMIT 1
                            """.trimIndent()
                            conn.prepareStatement(sql).use { stmt ->
                                stmt.setObject(1, UUID.fromString(sessionId))
                                stmt.setObject(2, UUID.fromString(userId))
                                stmt.executeQuery().use { rs ->
                                    if (rs.next()) {
                                        val stateJson = rs.getString("state_json")
                                        json.decodeFromString<AgentCheckpoint>(stateJson)
                                    } else null
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to load agent checkpoint: ${e.message}")
                        null
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn("Checkpoint load timed out for session $sessionId")
            null
        }
    }

    /**
     * Deletes a checkpoint (Tombstone).
     */
    suspend fun clearCheckpoint(sessionId: String) {
        if (dataSource == null) return
        withContext(Dispatchers.IO) {
            try {
                dataSource.connection.use { conn ->
                    val sql = "DELETE FROM agent_checkpoints WHERE session_id = ? AND user_id = ?"
                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setObject(1, UUID.fromString(sessionId))
                        stmt.setObject(2, UUID.fromString(userId))
                        stmt.executeUpdate()
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to clear checkpoint: ${e.message}")
            }
        }
    }
}
