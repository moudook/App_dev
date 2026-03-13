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
 * 
 * SINGLE RESPONSIBILITY: Only manages chat_sessions and chat_messages tables.
 * Delegates note relationship management to ChatMessageNotesRepository.
 * GLOBAL STATE: All tables reference users(firebase_uid) with cascade deletes.
 */
class ChatRepository(
    private val dataSource: DataSource,
    private val chatMessageNotesRepo: ChatMessageNotesRepository
) {
    private val logger = LoggerFactory.getLogger(ChatRepository::class.java)

    /**
     * Creates a new chat session for a specific user and returns its UUID.
     *
     * @param userId The authenticated user's UUID (users.id)
     * @param title Optional session title
     */
    suspend fun createSession(userId: String, title: String? = null): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            val sql = "INSERT INTO chat_sessions (id, user_id, title, is_active, is_archived, is_pinned, temperature, max_tokens, token_count, message_count, metadata, created_at, updated_at) VALUES (?, ?, ?, true, false, false, 0.7, 4096, 0, 0, '{}', now(), now())"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, id)
                stmt.setObject(2, UUID.fromString(userId))
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
     * @param userId The authenticated user's UUID (users.id)
     * @param sessionId The session UUID
     * @param role The message role (user, assistant, system, tool)
     * @param content The message content
     * @param thinking Optional thinking/reasoning content
     * @param toolCalls Optional JSON-serialized tool calls
     * @param toolCallId Optional tool call ID
     * @param tokenCount Optional token count
     */
    suspend fun saveMessage(
        userId: String,
        sessionId: String,
        role: String,
        content: String,
        thinking: String? = null,
        toolCalls: String? = null,
        toolCallId: String? = null,
        tokenCount: Int = 0
    ) = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            // Verify session belongs to user before inserting message
            val verifySql = "SELECT 1 FROM chat_sessions WHERE id = ? AND user_id = ?"
            conn.prepareStatement(verifySql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                stmt.setObject(2, UUID.fromString(userId))
                val exists = stmt.executeQuery().next()
                if (!exists) {
                    logger.warn("Session {} does not belong to user {}", sessionId, userId)
                    throw IllegalAccessException("Session does not belong to user")
                }
            }

            // Insert the message
            val sql = "INSERT INTO chat_messages (session_id, user_id, role, content, thinking, tool_calls, tool_call_id, token_count, is_edited, is_starred, metadata, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, false, false, '{}', now(), now())"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                stmt.setObject(2, UUID.fromString(userId))
                stmt.setString(3, role.lowercase())
                stmt.setString(4, content)
                stmt.setString(5, thinking)
                stmt.setString(6, toolCalls ?: "null")
                stmt.setString(7, toolCallId)
                stmt.setInt(8, tokenCount)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Retrieves the conversation history for a session, ordered by creation time.
     * Only returns messages for sessions owned by the specified user.
     *
     * @param userId The authenticated user's UUID (users.id)
     * @param sessionId The session UUID
     * @param limit Maximum number of messages to return
     */
    suspend fun getHistory(userId: String, sessionId: String, limit: Int = 50): List<LlmMessage> = withContext(Dispatchers.IO) {
        val history = mutableListOf<LlmMessage>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT cm.role, cm.content, cm.thinking, cm.tool_calls, cm.tool_call_id
                FROM chat_messages cm
                JOIN chat_sessions cs ON cm.session_id = cs.id
                WHERE cm.session_id = ? AND cs.user_id = ?
                ORDER BY cm.created_at ASC
                LIMIT ?
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                stmt.setObject(2, UUID.fromString(userId))
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
     * @param userId The authenticated user's UUID (users.id)
     * @param limit Maximum number of sessions to return
     */
    suspend fun listSessions(userId: String, limit: Int = 20): List<SessionInfo> = withContext(Dispatchers.IO) {
        val sessions = mutableListOf<SessionInfo>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT id, title, created_at, updated_at, message_count,
                       is_active, is_archived, is_pinned, model_used, temperature, max_tokens
                FROM chat_sessions
                WHERE user_id = ?
                ORDER BY updated_at DESC
                LIMIT ?
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(userId))
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        sessions.add(
                            SessionInfo(
                                id = rs.getString("id"),
                                title = rs.getString("title"),
                                createdAt = rs.getTimestamp("created_at")?.time ?: 0,
                                updatedAt = rs.getTimestamp("updated_at")?.time ?: 0,
                                messageCount = rs.getInt("message_count"),
                                lastMessagePreview = "",  // Not available in new schema
                                isActive = rs.getBoolean("is_active"),
                                summary = null,  // Not available in new schema
                                summaryGeneratedAt = null  // Not available in new schema
                            )
                        )
                    }
                }
            }
        }
        sessions
    }

    /**
     * Lists ALL sessions for a user (for sync).
     */
    suspend fun listAllSessions(userId: String, limit: Int = 100): List<SessionInfo> = listSessions(userId, limit)

    /**
     * Gets ALL messages for a session (for sync).
     */
    suspend fun getAllMessagesForSession(userId: String, sessionId: String): List<MessageRecord> = withContext(Dispatchers.IO) {
        val messages = mutableListOf<MessageRecord>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT cm.id, cm.role, cm.content, cm.thinking, cm.tool_calls, cm.tool_call_id, cm.created_at
                FROM chat_messages cm
                JOIN chat_sessions cs ON cm.session_id = cs.id
                WHERE cm.session_id = ? AND cs.user_id = ?
                ORDER BY cm.created_at ASC
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                stmt.setObject(2, UUID.fromString(userId))
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        messages.add(MessageRecord(
                            id = rs.getObject("id") as UUID,
                            role = rs.getString("role"),
                            content = rs.getString("content"),
                            thinking = rs.getString("thinking"),
                            createdAt = rs.getTimestamp("created_at").time
                        ))
                    }
                }
            }
        }
        messages
    }

    /**
     * Deletes a session and all its messages.
     * Only deletes if the session belongs to the user.
     *
     * @param userId The authenticated user's UUID (users.id)
     * @param sessionId The session UUID to delete
     * @return true if deleted, false if not found or not owned
     */
    suspend fun deleteSession(userId: String, sessionId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "DELETE FROM chat_sessions WHERE id = ? AND user_id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                stmt.setObject(2, UUID.fromString(userId))
                stmt.executeUpdate() > 0
            }
        }
    }

    /**
     * Creates a session with a specific ID (for client-provided session IDs).
     * Uses ON CONFLICT DO NOTHING to handle race conditions.
     *
     * @param userId The authenticated user's UUID (users.id)
     * @param sessionId The session UUID to use
     * @param title Optional session title
     * @return true if created, false if already exists
     */
    suspend fun createSessionWithId(userId: String, sessionId: String, title: String? = null): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "INSERT INTO chat_sessions (id, user_id, title, is_active, is_archived, is_pinned, temperature, max_tokens, token_count, message_count, metadata, created_at, updated_at) VALUES (?, ?, ?, true, false, false, 0.7, 4096, 0, 0, '{}', now(), now()) ON CONFLICT (id) DO NOTHING"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                stmt.setObject(2, UUID.fromString(userId))
                stmt.setString(3, title)
                stmt.executeUpdate() > 0
            }
        }
    }

    /**
     * Gets a session if it exists and belongs to the user.
     *
     * @param userId The authenticated user's UUID (users.id)
     * @param sessionId The session UUID
     * @return SessionInfo if found and owned by user, null otherwise
     */
    suspend fun getSession(userId: String, sessionId: String): SessionInfo? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                SELECT id, title, created_at, updated_at, message_count,
                       is_active, is_archived, is_pinned, model_used
                FROM chat_sessions
                WHERE id = ? AND user_id = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                stmt.setObject(2, UUID.fromString(userId))
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        SessionInfo(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            createdAt = rs.getTimestamp("created_at").time,
                            updatedAt = rs.getTimestamp("updated_at")?.time ?: System.currentTimeMillis(),
                            messageCount = rs.getInt("message_count"),
                            lastMessagePreview = "",  // Not in new schema
                            isActive = rs.getBoolean("is_active"),
                            summary = null,  // Not in new schema
                            summaryGeneratedAt = null  // Not in new schema
                        )
                    } else null
                }
            }
        }
    }

    // =============================================================================
    // NOTE RELATIONSHIP METHODS (Delegated to ChatMessageNotesRepository)
    // =============================================================================

    /**
     * Link a note to a chat message.
     * Validates that the message belongs to the user before linking.
     */
    suspend fun linkNoteToMessage(userId: String, messageId: String, noteId: String): Unit = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            // Verify message belongs to user
            val verifySql = """
                SELECT 1 FROM chat_messages cm
                JOIN chat_sessions cs ON cm.session_id = cs.id
                WHERE cm.id = ? AND cs.user_id = ?
            """.trimIndent()
            conn.prepareStatement(verifySql).use { stmt ->
                stmt.setObject(1, UUID.fromString(messageId))
                stmt.setObject(2, UUID.fromString(userId))
                val exists = stmt.executeQuery().next()
                if (!exists) {
                    logger.warn("Message {} does not belong to user {}", messageId, userId)
                    throw IllegalAccessException("Message does not belong to user")
                }
            }
        }
        // Delegate to junction repository
        chatMessageNotesRepo.linkMessageToNote(UUID.fromString(messageId), UUID.fromString(noteId))
        logger.info("Linked note {} to message {} for user {}", noteId, messageId, userId)
    }

    /**
     * Unlink a note from a chat message.
     */
    suspend fun unlinkNoteFromMessage(userId: String, messageId: String, noteId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            // Verify message belongs to user
            val verifySql = """
                SELECT 1 FROM chat_messages cm
                JOIN chat_sessions cs ON cm.session_id = cs.id
                WHERE cm.id = ? AND cs.user_id = ?
            """.trimIndent()
            conn.prepareStatement(verifySql).use { stmt ->
                stmt.setObject(1, UUID.fromString(messageId))
                stmt.setObject(2, UUID.fromString(userId))
                val exists = stmt.executeQuery().next()
                if (!exists) {
                    logger.warn("Message {} does not belong to user {}", messageId, userId)
                    throw IllegalAccessException("Message does not belong to user")
                }
            }
        }
        // Delegate to junction repository
        chatMessageNotesRepo.unlinkMessageFromNote(UUID.fromString(messageId), UUID.fromString(noteId))
    }

    /**
     * Get all notes linked to a chat message.
     */
    suspend fun getLinkedNotes(userId: String, messageId: String): List<String> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            // Verify message belongs to user
            val verifySql = """
                SELECT 1 FROM chat_messages cm
                JOIN chat_sessions cs ON cm.session_id = cs.id
                WHERE cm.id = ? AND cs.user_id = ?
            """.trimIndent()
            conn.prepareStatement(verifySql).use { stmt ->
                stmt.setObject(1, UUID.fromString(messageId))
                stmt.setObject(2, UUID.fromString(userId))
                val exists = stmt.executeQuery().next()
                if (!exists) {
                    logger.warn("Message {} does not belong to user {}", messageId, userId)
                    throw IllegalAccessException("Message does not belong to user")
                }
            }
        }
        // Delegate to junction repository
        chatMessageNotesRepo.getLinkedNotes(UUID.fromString(messageId))
            .map { it.toString() }
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
            content = rs.getString("content"),
            thinking = rs.getString("thinking")
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

/**
 * Record for a chat message (for sync).
 */
data class MessageRecord(
    val id: UUID,
    val role: String,
    val content: String,
    val thinking: String?,
    val createdAt: Long,
    val linkedNoteIds: List<String> = emptyList()
)
