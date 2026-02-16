package com.example.smarty.server.agent

import com.example.smarty.server.data.DatabaseFactory
import com.example.smarty.server.llm.LlmMessage
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
     */
    suspend fun saveCheckpoint(sessionId: String, messages: List<LlmMessage>, lastNode: String? = null) {
        if (dataSource == null) return

        withContext(Dispatchers.IO) {
            try {
                dataSource.connection.use { conn ->
                    val checkpoint = AgentCheckpoint(messages)
                    val stateJson = json.encodeToString(checkpoint)

                    val sql = """
                        INSERT INTO agent_checkpoints (session_id, user_id, state_json, last_node, version)
                        VALUES (?, ?, ?::jsonb, ?, 1)
                        ON CONFLICT (session_id) DO UPDATE SET
                            state_json = EXCLUDED.state_json,
                            last_node = EXCLUDED.last_node,
                            version = agent_checkpoints.version + 1,
                            created_at = NOW()
                    """.trimIndent()

                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setObject(1, UUID.fromString(sessionId))
                        stmt.setString(2, userId)
                        stmt.setString(3, stateJson)
                        stmt.setString(4, lastNode)
                        stmt.executeUpdate()
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to save agent checkpoint: ${e.message}")
            }
        }
    }

    /**
     * Loads the latest checkpoint for a session.
     */
    suspend fun loadCheckpoint(sessionId: String): AgentCheckpoint? {
        if (dataSource == null) return null

        return withContext(Dispatchers.IO) {
            try {
                dataSource.connection.use { conn ->
                    val sql = "SELECT state_json FROM agent_checkpoints WHERE session_id = ? AND user_id = ?"
                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setObject(1, UUID.fromString(sessionId))
                        stmt.setString(2, userId)
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

    /**
     * Deletes a checkpoint (Tombstone).
     */
    suspend fun clearCheckpoint(sessionId: String) {
        if (dataSource == null) return
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "DELETE FROM agent_checkpoints WHERE session_id = ? AND user_id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(sessionId))
                    stmt.setString(2, userId)
                    stmt.executeUpdate()
                }
            }
        }
    }
}
