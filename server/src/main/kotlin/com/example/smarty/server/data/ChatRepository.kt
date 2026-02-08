package com.example.smarty.server.data

import com.example.smarty.server.llm.LlmMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

/**
 * Repository for managing chat sessions and persistent history in the database.
 */
class ChatRepository(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(ChatRepository::class.java)

    /**
     * Creates a new chat session and returns its UUID.
     */
    suspend fun createSession(title: String? = null): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            val sql = "INSERT INTO chat_sessions (id, title) VALUES (?, ?)"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, id)
                stmt.setString(2, title)
                stmt.executeUpdate()
            }
        }
        id.toString()
    }

    /**
     * Saves a message to a specific session.
     */
    suspend fun saveMessage(sessionId: String, role: String, content: String) = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "INSERT INTO chat_messages (session_id, role, content) VALUES (?, ?, ?)"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                stmt.setString(2, role)
                stmt.setString(3, content)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Retrieves the conversation history for a session, ordered by creation time.
     */
    suspend fun getHistory(sessionId: String, limit: Int = 50): List<LlmMessage> = withContext(Dispatchers.IO) {
        val history = mutableListOf<LlmMessage>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT role, content
                FROM chat_messages
                WHERE session_id = ?
                ORDER BY created_at ASC
                LIMIT ?
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        history.add(mapRowToMessage(rs))
                    }
                }
            }
        }
        history
    }

    private fun mapRowToMessage(rs: ResultSet): LlmMessage {
        val roleStr = rs.getString("role")
        val role = try {
            LlmMessage.Role.valueOf(roleStr.uppercase())
        } catch (e: Exception) {
            LlmMessage.Role.USER // Default fallback
        }

        return LlmMessage(
            role = role,
            content = rs.getString("content")
        )
    }
}
