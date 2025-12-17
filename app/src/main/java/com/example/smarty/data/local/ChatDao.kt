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
     * Delete sessions with no messages (empty chats)
     */
    @Query("DELETE FROM chat_sessions WHERE messageCount = 0 AND createdAt < :olderThan")
    suspend fun deleteEmptySessions(olderThan: Long = System.currentTimeMillis() - 60000) // 1 minute grace period

    /**
     * Delete old sessions beyond a limit (keep most recent N sessions)
     */
    @Query("""
        DELETE FROM chat_sessions
        WHERE id NOT IN (
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
