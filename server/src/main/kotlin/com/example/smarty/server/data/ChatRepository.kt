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
 * All operations are isolated by userId for multi-tenant security.
 */
class ChatRepository(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(ChatRepository::class.java)

    /**
     * Creates a new chat session for a specific user and returns its UUID.
     *
     * @param userId The authenticated user's ID
     * @param title Optional session title
     */
    suspend fun createSession(userId: String, title: String? = null): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            val sql = "INSERT INTO chat_sessions (id, user_id, title) VALUES (?, ?, ?)"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, id)
                stmt.setString(2, userId)
                stmt.setString(3, title)
                stmt.executeUpdate()
            }
        }
        logger.debug("Created session {} for user {}", id, userId)
        id.toString()
    }

    /**
     * Saves a message to a specific session.
     * Validates that the session belongs to the user before saving.
     * Updates the session's message count and last message preview.
     *
     * @param userId The authenticated user's ID
     * @param sessionId The session UUID
     * @param role The message role (USER, SMARTY, TOOL, etc.)
     * @param content The message content
     */
    suspend fun saveMessage(userId: String, sessionId: String, role: String, content: String) = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            // Verify session belongs to user before inserting message
            val verifySql = "SELECT 1 FROM chat_sessions WHERE id = ? AND user_id = ?"
            conn.prepareStatement(verifySql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                stmt.setString(2, userId)
                val exists = stmt.executeQuery().next()
                if (!exists) {
                    logger.warn("Session {} does not belong to user {}", sessionId, userId)
                    throw IllegalAccessException("Session does not belong to user")
                }
            }

            // Insert the message
            val sql = "INSERT INTO chat_messages (session_id, user_id, role, content) VALUES (?, ?, ?, ?)"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                stmt.setString(2, userId)
                stmt.setString(3, role)
                stmt.setString(4, content)
                stmt.executeUpdate()
            }

            // Update session stats (message count, last preview, updated_at)
            val updateSessionSql = """
                UPDATE chat_sessions 
                SET message_count = message_count + 1,
                    last_message_preview = ?,
                    updated_at = NOW()
                WHERE id = ?
            """.trimIndent()
            conn.prepareStatement(updateSessionSql).use { stmt ->
                stmt.setString(1, content.take(100)) // Preview limited to 100 chars
                stmt.setObject(2, UUID.fromString(sessionId))
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Retrieves the conversation history for a session, ordered by creation time.
     * Only returns messages for sessions owned by the specified user.
     *
     * @param userId The authenticated user's ID
     * @param sessionId The session UUID
     * @param limit Maximum number of messages to return
     */
    suspend fun getHistory(userId: String, sessionId: String, limit: Int = 50): List<LlmMessage> = withContext(Dispatchers.IO) {
        val history = mutableListOf<LlmMessage>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT cm.role, cm.content
                FROM chat_messages cm
                JOIN chat_sessions cs ON cm.session_id = cs.id
                WHERE cm.session_id = ? AND cs.user_id = ?
                ORDER BY cm.created_at ASC
                LIMIT ?
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                stmt.setString(2, userId)
                stmt.setInt(3, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        history.add(mapRowToMessage(rs))
                    }
                }
            }
        }
        history
    }

    /**
     * Lists all sessions for a user.
     *
     * @param userId The authenticated user's ID
     * @param limit Maximum number of sessions to return
     */
    suspend fun listSessions(userId: String, limit: Int = 20): List<SessionInfo> = withContext(Dispatchers.IO) {
        val sessions = mutableListOf<SessionInfo>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT id, title, created_at, updated_at, message_count, 
                       last_message_preview, is_active, summary, summary_generated_at
                FROM chat_sessions
                WHERE user_id = ?
                ORDER BY updated_at DESC
                LIMIT ?
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, userId)
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        sessions.add(
                            SessionInfo(
                                id = rs.getString("id"),
                                title = rs.getString("title"),
                                createdAt = rs.getTimestamp("created_at").time,
                                updatedAt = rs.getTimestamp("updated_at")?.time ?: System.currentTimeMillis(),
                                messageCount = rs.getInt("message_count"),
                                lastMessagePreview = rs.getString("last_message_preview") ?: "",
                                isActive = rs.getBoolean("is_active"),
                                summary = rs.getString("summary"),
                                summaryGeneratedAt = rs.getLong("summary_generated_at")
                            )
                        )
                    }
                }
            }
        }
        sessions
    }

    /**
     * Deletes a session and all its messages.
     * Only deletes if the session belongs to the user.
     *
     * @param userId The authenticated user's ID
     * @param sessionId The session UUID to delete
     * @return true if deleted, false if not found or not owned
     */
    suspend fun deleteSession(userId: String, sessionId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "DELETE FROM chat_sessions WHERE id = ? AND user_id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                stmt.setString(2, userId)
                stmt.executeUpdate() > 0
            }
        }
    }

    /**
     * Creates a session with a specific ID (for client-provided session IDs).
     * Uses ON CONFLICT DO NOTHING to handle race conditions.
     *
     * @param userId The authenticated user's ID
     * @param sessionId The session UUID to use
     * @param title Optional session title
     * @return true if created, false if already exists
     */
    suspend fun createSessionWithId(userId: String, sessionId: String, title: String? = null): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "INSERT INTO chat_sessions (id, user_id, title) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                stmt.setString(2, userId)
                stmt.setString(3, title)
                stmt.executeUpdate() > 0
            }
        }
    }

    /**
     * Gets a session if it exists and belongs to the user.
     *
     * @param userId The authenticated user's ID
     * @param sessionId The session UUID
     * @return SessionInfo if found and owned by user, null otherwise
     */
    suspend fun getSession(userId: String, sessionId: String): SessionInfo? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                SELECT id, title, created_at, updated_at, message_count, 
                       last_message_preview, is_active, summary, summary_generated_at
                FROM chat_sessions
                WHERE id = ? AND user_id = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                stmt.setString(2, userId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        SessionInfo(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            createdAt = rs.getTimestamp("created_at").time,
                            updatedAt = rs.getTimestamp("updated_at")?.time ?: System.currentTimeMillis(),
                            messageCount = rs.getInt("message_count"),
                            lastMessagePreview = rs.getString("last_message_preview") ?: "",
                            isActive = rs.getBoolean("is_active"),
                            summary = rs.getString("summary"),
                            summaryGeneratedAt = rs.getLong("summary_generated_at")
                        )
                    } else null
                }
            }
        }
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

/**
 * Info about a chat session.
 * Matches the client's ChatSession model for proper sync.
 */
data class SessionInfo(
    val id: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val lastMessagePreview: String = "",
    val isActive: Boolean = true,
    val summary: String? = null,
    val summaryGeneratedAt: Long? = null
)
