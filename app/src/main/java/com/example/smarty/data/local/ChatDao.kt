package com.example.smarty.data.local

import androidx.room.*
import com.example.smarty.data.model.ChatMessageEntity
import com.example.smarty.data.model.ChatSession
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    // ==================== Session Operations ====================

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun getAllSessions(): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    suspend fun getAllSessionsOnce(): List<ChatSession>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): ChatSession?

    @Query("SELECT * FROM chat_sessions WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveSession(): ChatSession?

    @Query("SELECT * FROM chat_sessions WHERE isActive = 1 LIMIT 1")
    fun getActiveSessionFlow(): Flow<ChatSession?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSession)

    @Update
    suspend fun updateSession(session: ChatSession)

    @Delete
    suspend fun deleteSession(session: ChatSession)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    @Query("UPDATE chat_sessions SET isActive = 0")
    suspend fun deactivateAllSessions()

    @Query("UPDATE chat_sessions SET isActive = 1 WHERE id = :sessionId")
    suspend fun activateSession(sessionId: String)

    @Transaction
    suspend fun switchToSession(sessionId: String) {
        deactivateAllSessions()
        activateSession(sessionId)
    }

    @Query("""
        UPDATE chat_sessions
        SET messageCount = messageCount + 1,
            updatedAt = :timestamp,
            lastMessagePreview = :preview
        WHERE id = :sessionId
    """)
    suspend fun incrementMessageCount(sessionId: String, preview: String, timestamp: Long = System.currentTimeMillis())

    @Query("""
        UPDATE chat_sessions
        SET title = :title, updatedAt = :timestamp
        WHERE id = :sessionId
    """)
    suspend fun updateSessionTitle(sessionId: String, title: String, timestamp: Long = System.currentTimeMillis())

    // ==================== Summary Operations ====================

    /**
     * Update session summary.
     * Called after ConversationSummarizer generates a summary.
     */
    @Query("""
        UPDATE chat_sessions
        SET summary = :summary,
            summaryGeneratedAt = :generatedAt
        WHERE id = :sessionId
    """)
    suspend fun updateSessionSummary(
        sessionId: String,
        summary: String,
        generatedAt: Long = System.currentTimeMillis()
    )

    /**
     * Get recent session summaries for AI context.
     * Returns non-null summaries from recent sessions, excluding the current active one.
     * Used to provide conversation history context to the AI agent.
     *
     * @param limit Maximum number of summaries to return
     * @param excludeSessionId Session ID to exclude (typically the active session)
     */
    @Query("""
        SELECT * FROM chat_sessions
        WHERE summary IS NOT NULL
        AND summary != ''
        AND id != :excludeSessionId
        ORDER BY updatedAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentSessionSummaries(limit: Int, excludeSessionId: String = ""): List<ChatSession>

    /**
     * Get sessions that need summary generation.
     * Returns sessions with enough messages but no summary, or outdated summaries.
     */
    @Query("""
        SELECT * FROM chat_sessions
        WHERE messageCount >= :minMessages
        AND (summary IS NULL OR summaryGeneratedAt < :olderThan)
        ORDER BY updatedAt DESC
        LIMIT :limit
    """)
    suspend fun getSessionsNeedingSummary(
        minMessages: Int = 3,
        olderThan: Long = System.currentTimeMillis() - 30 * 60 * 1000,  // 30 minutes
        limit: Int = 5
    ): List<ChatSession>

    // ==================== Message Operations ====================

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSessionOnce(sessionId: String): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): ChatMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    @Delete
    suspend fun deleteMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun getMessageCountForSession(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId AND role = 'ASSISTANT'")
    suspend fun getAssistantMessageCount(sessionId: String): Int

    // ==================== Cleanup Operations ====================

    /**
     * Delete sessions with no messages (empty chats).
     * BUG-043 fix: Never delete the active session even if it's empty.
     */
    @Query("DELETE FROM chat_sessions WHERE isActive = 0 AND messageCount = 0 AND createdAt < :olderThan")
    suspend fun deleteEmptySessions(olderThan: Long = System.currentTimeMillis() - 60000) // 1 minute grace period

    /**
     * Delete old sessions beyond a limit (keep most recent N sessions).
     * BUG-043 fix: Never delete the active session even if it's old.
     */
    @Query("""
        DELETE FROM chat_sessions
        WHERE isActive = 0
        AND id NOT IN (
            SELECT id FROM chat_sessions
            ORDER BY updatedAt DESC
            LIMIT :keepCount
        )
    """)
    suspend fun pruneOldSessions(keepCount: Int = 20)

    /**
     * Delete all chat data
     */
    @Query("DELETE FROM chat_messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAllSessions()

    @Transaction
    suspend fun deleteAllChatData() {
        deleteAllMessages()
        deleteAllSessions()
    }
}
