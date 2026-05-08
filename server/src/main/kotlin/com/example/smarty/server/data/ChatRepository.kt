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
class ChatRepository(
    private val dataSource: DataSource,
    private val chatMessageNotesRepo: ChatMessageNotesRepository,
) {
    private val logger = LoggerFactory.getLogger(ChatRepository::class.java)

    suspend fun createSession(
        userId: String,
        title: String? = null,
        sessionId: String? = null
    ): String =
        withContext(Dispatchers.IO) {
            val id = if (sessionId != null) UUID.fromString(sessionId) else UUID.randomUUID()
            dataSource.connection.use { conn ->
                val sql = """
                    INSERT INTO chat_sessions (
                        id, user_id, title, is_active, is_archived, is_pinned,
                        temperature, max_tokens, token_count, message_count,
                        metadata, created_at, updated_at
                    ) VALUES (?, ?, ?, true, false, false, 0.7, 4096, 0, 0, '{}', now(), now())
                    ON CONFLICT (id) DO NOTHING
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, id)
                    stmt.setObject(2, UUID.fromString(userId))
                    stmt.setString(3, title)
                    stmt.executeUpdate()
                }
            }
            id.toString()
        }

    suspend fun saveMessage(
        userId: String,
        sessionId: String,
        role: String,
        content: String,
        thinking: String? = null,
        toolCalls: String? = null,
        toolCallId: String? = null,
        tokenCount: Int = 0,
    ) = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO chat_messages (
                    session_id, user_id, role, content, thinking, tool_calls,
                    tool_call_id, token_count, is_edited, is_starred, metadata,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, false, false, '{}', now(), now())
            """.trimIndent()
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
            
            // Update session preview and counts
            val updateSessionSql = """
                UPDATE chat_sessions 
                SET last_message_preview = ?,
                    message_count = message_count + 1,
                    updated_at = now()
                WHERE id = ?
            """.trimIndent()
            conn.prepareStatement(updateSessionSql).use { stmt ->
                stmt.setString(1, content.take(200))
                stmt.setObject(2, UUID.fromString(sessionId))
                stmt.executeUpdate()
            }
        }
    }

    suspend fun updateSessionSummary(
        userId: String,
        sessionId: String,
        summary: String
    ) = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "UPDATE chat_sessions SET summary = ?, summary_generated_at = now(), updated_at = now() WHERE id = ? AND user_id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, summary)
                stmt.setObject(2, UUID.fromString(sessionId))
                stmt.setObject(3, UUID.fromString(userId))
                stmt.executeUpdate()
            }
        }
    }

    suspend fun listSessions(
        userId: String,
        limit: Int = 50,
    ): List<SessionInfo> =
        withContext(Dispatchers.IO) {
            val sessions = mutableListOf<SessionInfo>()
            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM chat_sessions WHERE user_id = ?::uuid ORDER BY updated_at DESC LIMIT ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.setInt(2, limit)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            sessions.add(mapRowToSessionInfo(rs))
                        }
                    }
                }
            }
            sessions
        }

    suspend fun listSessionsUpdatedAfter(
        userId: String,
        timestamp: Long,
        limit: Int = 100,
    ): List<SessionInfo> =
        withContext(Dispatchers.IO) {
            val sessions = mutableListOf<SessionInfo>()
            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM chat_sessions WHERE user_id = ?::uuid AND updated_at > ? ORDER BY updated_at ASC LIMIT ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.setTimestamp(2, java.sql.Timestamp(timestamp))
                    stmt.setInt(3, limit)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            sessions.add(mapRowToSessionInfo(rs))
                        }
                    }
                }
            }
            sessions
        }

    /**
     * Get all messages for a session as LlmMessage for agent consumption.
     */
    suspend fun getHistory(userId: String, sessionId: String): List<com.example.smarty.server.llm.LlmMessage> =
        getAllMessagesForSession(userId, sessionId).map { msg ->
            com.example.smarty.server.llm.LlmMessage(
                role = when (msg.role.uppercase()) {
                    "USER" -> com.example.smarty.server.llm.LlmMessage.Role.USER
                    "ASSISTANT" -> com.example.smarty.server.llm.LlmMessage.Role.ASSISTANT
                    "SYSTEM" -> com.example.smarty.server.llm.LlmMessage.Role.SYSTEM
                    "TOOL" -> com.example.smarty.server.llm.LlmMessage.Role.TOOL
                    else -> com.example.smarty.server.llm.LlmMessage.Role.USER
                },
                content = msg.content,
                thinking = msg.thinking,
            )
        }

    /**
     * Get all messages for a session (raw records).
     */
    suspend fun getAllMessagesForSession(
        userId: String,
        sessionId: String,
    ): List<MessageRecord> =
        withContext(Dispatchers.IO) {
            val messages = mutableListOf<MessageRecord>()
            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM chat_messages WHERE session_id = ?::uuid AND user_id = ?::uuid ORDER BY created_at ASC"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(sessionId))
                    stmt.setObject(2, UUID.fromString(userId))
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            messages.add(
                                MessageRecord(
                                    id = rs.getObject("id") as UUID,
                                    role = rs.getString("role"),
                                    content = rs.getString("content"),
                                    thinking = rs.getString("thinking"),
                                    createdAt = rs.getTimestamp("created_at").time,
                                )
                            )
                        }
                    }
                }
            }
            messages
        }

    private fun mapRowToSessionInfo(rs: ResultSet): SessionInfo {
        return SessionInfo(
            id = rs.getString("id"),
            title = rs.getString("title"),
            createdAt = rs.getTimestamp("created_at").time,
            updatedAt = rs.getTimestamp("updated_at")?.time ?: 0,
            messageCount = rs.getInt("message_count"),
            lastMessagePreview = rs.getString("last_message_preview") ?: "",
            isActive = rs.getBoolean("is_active"),
            summary = rs.getString("summary"),
            summaryGeneratedAt = rs.getTimestamp("summary_generated_at")?.time
        )
    }

    suspend fun deleteSession(userId: String, sessionId: String): Boolean =
        withContext(Dispatchers.IO) {
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
     * Get a single session by ID.
     */
    suspend fun getSession(userId: String, sessionId: String): SessionInfo? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM chat_sessions WHERE id = ? AND user_id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(sessionId))
                    stmt.setObject(2, UUID.fromString(userId))
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) mapRowToSessionInfo(rs) else null
                    }
                }
            }
        }

    /**
     * Create a session with a specific ID (for client-provided IDs).
     */
    suspend fun createSessionWithId(userId: String, sessionId: String, title: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = """
                    INSERT INTO chat_sessions (
                        id, user_id, title, is_active, is_archived, is_pinned,
                        temperature, max_tokens, token_count, message_count,
                        metadata, created_at, updated_at
                    ) VALUES (?, ?, ?, true, false, false, 0.7, 4096, 0, 0, '{}', now(), now())
                    ON CONFLICT (id) DO NOTHING
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(sessionId))
                    stmt.setObject(2, UUID.fromString(userId))
                    stmt.setString(3, title)
                    stmt.executeUpdate()
                }
            }
            true
        }

    /**
     * Update the thinking field of the most recent message in a session.
     */
    suspend fun updateMessageThinking(
        userId: String,
        sessionId: String,
        thinking: String,
        toolCalls: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            // Update the most recent message for this session that has content
            val sql = """
                UPDATE chat_messages 
                SET thinking = ?, tool_calls = ?::jsonb, updated_at = now()
                WHERE id = (
                    SELECT id FROM chat_messages 
                    WHERE session_id = ?::uuid AND user_id = ?::uuid 
                    ORDER BY created_at DESC LIMIT 1
                )
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, thinking)
                stmt.setString(2, toolCalls)
                stmt.setObject(3, UUID.fromString(sessionId))
                stmt.setObject(4, UUID.fromString(userId))
                stmt.executeUpdate() > 0
            }
        }
    }

    /**
     * List all sessions for a user (alias for listSessions).
     */
    suspend fun listAllSessions(userId: String, limit: Int = 50): List<SessionInfo> =
        listSessions(userId, limit)

    /**
     * Delete a message and all messages after it in the same session.
     */
    suspend fun deleteMessageAndAfter(userId: String, messageId: String): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            // First get the session and created_at of the message
            val getMsgSql = "SELECT session_id, created_at FROM chat_messages WHERE id = ?::uuid AND user_id = ?::uuid"
            val (sessionId, timestamp) = conn.prepareStatement(getMsgSql).use { stmt ->
                stmt.setObject(1, UUID.fromString(messageId))
                stmt.setObject(2, UUID.fromString(userId))
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        rs.getObject("session_id") as UUID to rs.getTimestamp("created_at").time
                    } else {
                        return@withContext 0
                    }
                }
            }
            
            // Delete all messages with created_at >= target message's created_at
            val deleteSql = """
                DELETE FROM chat_messages 
                WHERE session_id = ? AND user_id = ? 
                AND created_at >= (SELECT created_at FROM chat_messages WHERE id = ?::uuid)
            """.trimIndent()
            conn.prepareStatement(deleteSql).use { stmt ->
                stmt.setObject(1, sessionId)
                stmt.setObject(2, UUID.fromString(userId))
                stmt.setObject(3, UUID.fromString(messageId))
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Link a note to a chat message.
     */
    suspend fun linkNoteToMessage(userId: String, messageId: String, noteId: String) {
        val msgId = UUID.fromString(messageId)
        val noteUuid = UUID.fromString(noteId)
        // Verify ownership (message belongs to user)
        dataSource.connection.use { conn ->
            val checkSql = "SELECT 1 FROM chat_messages WHERE id = ? AND user_id = ?"
            conn.prepareStatement(checkSql).use { stmt ->
                stmt.setObject(1, msgId)
                stmt.setObject(2, UUID.fromString(userId))
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) throw IllegalAccessException("Message not found or not owned by user")
                }
            }
        }
        chatMessageNotesRepo.linkMessageToNote(msgId, noteUuid)
    }

    /**
     * Unlink a note from a chat message.
     */
    suspend fun unlinkNoteFromMessage(userId: String, messageId: String, noteId: String): Boolean {
        val msgId = UUID.fromString(messageId)
        val noteUuid = UUID.fromString(noteId)
        // Verify ownership
        dataSource.connection.use { conn ->
            val checkSql = "SELECT 1 FROM chat_messages WHERE id = ? AND user_id = ?"
            conn.prepareStatement(checkSql).use { stmt ->
                stmt.setObject(1, msgId)
                stmt.setObject(2, UUID.fromString(userId))
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) throw IllegalAccessException("Message not found or not owned by user")
                }
            }
        }
        return chatMessageNotesRepo.unlinkMessageFromNote(msgId, noteUuid)
    }

    /**
     * Get all note IDs linked to a chat message.
     */
    suspend fun getLinkedNotes(userId: String, messageId: String): List<String> = withContext(Dispatchers.IO) {
        // Verify ownership
        dataSource.connection.use { conn ->
            val checkSql = "SELECT 1 FROM chat_messages WHERE id = ? AND user_id = ?"
            conn.prepareStatement(checkSql).use { stmt ->
                stmt.setObject(1, UUID.fromString(messageId))
                stmt.setObject(2, UUID.fromString(userId))
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return@withContext emptyList()
                }
            }
        }
        chatMessageNotesRepo.getLinkedNotes(UUID.fromString(messageId))
            .map { it.toString() }
    }

    /**
     * Search chat history for a query.
     */
    suspend fun searchHistory(userId: String, query: String, limit: Int = 10): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<SearchResult>()
            dataSource.connection.use { conn ->
                val sql = """
                    SELECT 
                        cm.id as message_id,
                        cs.id as session_id,
                        cs.title as session_title,
                        cm.role,
                        cm.content,
                        cm.created_at,
                        ts_rank(to_tsvector('english', cm.content), plainto_tsquery('english', ?)) as relevance
                    FROM chat_messages cm
                    JOIN chat_sessions cs ON cm.session_id = cs.id
                    WHERE cm.user_id = ?::uuid 
                    AND cs.user_id = ?::uuid
                    AND cm.content ILIKE ?
                    ORDER BY relevance DESC, cm.created_at DESC
                    LIMIT ?
                """.trimIndent()
                val searchPattern = "%$query%"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, query)
                    stmt.setObject(2, UUID.fromString(userId))
                    stmt.setObject(3, UUID.fromString(userId))
                    stmt.setString(4, searchPattern)
                    stmt.setInt(5, limit)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(
                                SearchResult(
                                    messageId = rs.getObject("message_id").toString(),
                                    sessionId = rs.getObject("session_id").toString(),
                                    sessionTitle = rs.getString("session_title"),
                                    role = rs.getString("role"),
                                    content = rs.getString("content"),
                                    createdAt = rs.getTimestamp("created_at").time,
                                    relevance = rs.getDouble("relevance"),
                                )
                            )
                        }
                    }
                }
            }
            results
        }
}

@kotlinx.serialization.Serializable
data class SearchResult(
    val messageId: String,
    val sessionId: String,
    val sessionTitle: String?,
    val role: String,
    val content: String,
    val createdAt: Long,
    val relevance: Double,
)

data class SessionInfo(
    val id: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val lastMessagePreview: String = "",
    val isActive: Boolean = true,
    val summary: String? = null,
    val summaryGeneratedAt: Long? = null,
)

data class MessageRecord(
    val id: UUID,
    val role: String,
    val content: String,
    val thinking: String?,
    val createdAt: Long,
    val linkedNoteIds: List<String> = emptyList(),
)
