package com.example.smarty.data.repository

import android.util.Log
import androidx.room.Transaction
import com.example.smarty.data.local.ChatDao
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatMessageEntity
import com.example.smarty.data.model.ChatRole
import com.example.smarty.data.model.ChatSession
import com.example.smarty.data.model.Note
import com.example.smarty.util.PrivacyGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Repository for managing chat sessions and messages.
 * Implements smart saving logic to avoid storing redundant data.
 */
class ChatRepository(private val chatDao: ChatDao) {

    companion object {
        private const val TAG = "ChatRepository"
        private const val MAX_SESSIONS = 20  // Maximum number of sessions to keep
        private const val MIN_MESSAGES_TO_SAVE = 2  // Need at least user + assistant message
    }

    // ==================== Session Operations ====================

    /**
     * Get all chat sessions ordered by most recent
     */
    fun getAllSessions(): Flow<List<ChatSession>> = chatDao.getAllSessions()
        .distinctUntilChanged()

    /**
     * Get the currently active session
     */
    suspend fun getActiveSession(): ChatSession? = chatDao.getActiveSession()

    /**
     * Get active session as Flow for reactive updates
     */
    fun getActiveSessionFlow(): Flow<ChatSession?> = chatDao.getActiveSessionFlow()
        .distinctUntilChanged()

    /**
     * Create a new chat session and make it active
     */
    @Transaction
    suspend fun createNewSession(context: android.content.Context, title: String? = null): ChatSession {
        // Deactivate all existing sessions
        chatDao.deactivateAllSessions()

        val session = ChatSession(
            title = title ?: context.getString(com.example.smarty.R.string.title_new_chat),
            isActive = true
        )
        chatDao.insertSession(session)
        Log.d(TAG, "Created new session: ${session.id}")

        // Prune old sessions to keep storage bounded
        chatDao.pruneOldSessions(MAX_SESSIONS)

        return session
    }

    /**
     * Switch to an existing session
     */
    suspend fun switchToSession(sessionId: String) {
        chatDao.switchToSession(sessionId)
        Log.d(TAG, "Switched to session: $sessionId")
    }

    /**
     * Delete a session and all its messages
     */
    @Transaction
    suspend fun deleteSession(sessionId: String) {
        chatDao.deleteMessagesForSession(sessionId)
        chatDao.deleteSessionById(sessionId)
        Log.d(TAG, "Deleted session: $sessionId")
    }

    /**
     * Update session title (auto-generated from first message or user-set)
     */
    suspend fun updateSessionTitle(sessionId: String, title: String) {
        chatDao.updateSessionTitle(sessionId, title)
    }

    // ==================== Message Operations ====================

    /**
     * Get messages for a session
     */
    fun getMessagesForSession(sessionId: String): Flow<List<ChatMessage>> {
        return chatDao.getMessagesForSession(sessionId)
            .map { entities -> entities.map { it.toChatMessage() } }
            .distinctUntilChanged()
    }

    /**
     * Get messages for a session (one-shot)
     */
    suspend fun getMessagesForSessionOnce(sessionId: String): List<ChatMessage> {
        return chatDao.getMessagesForSessionOnce(sessionId).map { it.toChatMessage() }
    }

    /**
     * Save a message to a session.
     * Returns true if the message was saved, false if skipped.
     *
     * Smart saving logic:
     * - Always save user messages
     * - Only save assistant messages if they have content
     * - Skip system messages (internal use only)
     *
     * SECURITY: Referenced note IDs should be pre-sanitized before calling.
     * This adds a final validation check as defense in depth.
     *
     * @param sessionId The session to save to
     * @param message The message to save
     * @param allNotes Optional: All notes for final sanitization (if provided)
     */
    @Transaction
    suspend fun saveMessage(
        sessionId: String,
        message: ChatMessage,
        allNotes: List<Note>? = null
    ): Boolean {
        // Skip system messages - they're for internal use
        if (message.role == ChatRole.SYSTEM) {
            Log.d(TAG, "Skipping system message")
            return false
        }

        // Skip empty messages
        if (message.content.isBlank()) {
            Log.d(TAG, "Skipping empty message")
            return false
        }

        // SECURITY: Final sanitization check if notes are provided
        val sanitizedMessage = if (allNotes != null && message.referencedNoteIds.isNotEmpty()) {
            val sanitizedIds = PrivacyGuard.sanitizeForChatPersistence(
                message.referencedNoteIds,
                allNotes
            )
            if (sanitizedIds.size != message.referencedNoteIds.size) {
                Log.w(TAG, "SECURITY: Final sanitization removed ${message.referencedNoteIds.size - sanitizedIds.size} private note IDs")
            }
            message.copy(referencedNoteIds = sanitizedIds)
        } else {
            message
        }

        val entity = ChatMessageEntity.fromChatMessage(sanitizedMessage, sessionId)
        chatDao.insertMessage(entity)

        // Update session metadata
        val preview = if (message.content.length > 50) {
            message.content.take(50) + "..."
        } else {
            message.content
        }
        chatDao.incrementMessageCount(sessionId, preview)

        // Auto-generate title from first user message
        val session = chatDao.getSessionById(sessionId)
        if (session?.title == "New Chat" && message.role == ChatRole.USER) {
            val autoTitle = generateTitleFromContent(message.content)
            chatDao.updateSessionTitle(sessionId, autoTitle)
        }

        Log.d(TAG, "Saved message: ${message.id} to session: $sessionId")
        return true
    }

    /**
     * Save a pair of messages (user + assistant) atomically.
     * This is the primary method to use after a successful API response.
     *
     * @param sessionId The session to save to
     * @param userMessage The user's message
     * @param assistantMessage The assistant's response
     * @param shouldSave Whether saving is allowed (based on API success, demo mode, etc.)
     * @return true if messages were saved
     */
    @Transaction
    suspend fun saveMessagePair(
        sessionId: String,
        userMessage: ChatMessage,
        assistantMessage: ChatMessage,
        shouldSave: Boolean
    ): Boolean {
        if (!shouldSave) {
            Log.d(TAG, "Skipping save - saving disabled")
            return false
        }

        // Both messages must have content
        if (userMessage.content.isBlank() || assistantMessage.content.isBlank()) {
            Log.d(TAG, "Skipping save - empty content")
            return false
        }

        // Save both messages
        saveMessage(sessionId, userMessage)
        saveMessage(sessionId, assistantMessage)

        return true
    }

    // ==================== Cleanup Operations ====================

    /**
     * Clean up empty sessions (sessions with no messages)
     */
    suspend fun cleanupEmptySessions() {
        chatDao.deleteEmptySessions()
        Log.d(TAG, "Cleaned up empty sessions")
    }

    /**
     * Check if a session should be persisted.
     * A session is worth keeping if it has at least one complete exchange.
     */
    suspend fun shouldPersistSession(sessionId: String): Boolean {
        val messageCount = chatDao.getMessageCountForSession(sessionId)
        val assistantCount = chatDao.getAssistantMessageCount(sessionId)

        // Need at least one user message and one assistant response
        return messageCount >= MIN_MESSAGES_TO_SAVE && assistantCount >= 1
    }

    /**
     * Mark session as inactive without finalizing it
     * Used for quick switching between modes
     */
    suspend fun markSessionInactive(sessionId: String) {
        chatDao.deactivateSession(sessionId)
        Log.d(TAG, "Marked session as inactive: $sessionId (for quick switching)")
    }

    /**
     * Finalize a session - clean up if not worth keeping
     */
    suspend fun finalizeSession(sessionId: String) {
        if (!shouldPersistSession(sessionId)) {
            deleteSession(sessionId)
            Log.d(TAG, "Deleted empty/incomplete session: $sessionId")
        }
    }

    /**
     * Delete all chat data
     */
    suspend fun deleteAllChatData() {
        chatDao.deleteAllChatData()
        Log.d(TAG, "Deleted all chat data")
    }

    // ==================== Helper Functions ====================

    /**
     * Generate a title from message content
     */
    private fun generateTitleFromContent(content: String): String {
        // Clean up the content
        val cleaned = content
            .replace(Regex("[\\n\\r]+"), " ")
            .trim()

        // Take first meaningful words (up to 30 chars)
        return if (cleaned.length <= 30) {
            cleaned
        } else {
            // Try to break at word boundary
            val truncated = cleaned.take(30)
            val lastSpace = truncated.lastIndexOf(' ')
            if (lastSpace > 15) {
                truncated.substring(0, lastSpace) + "..."
            } else {
                truncated + "..."
            }
        }
    }

    /**
     * Get or create active session
     */
    suspend fun getOrCreateActiveSession(context: android.content.Context): ChatSession {
        return getActiveSession() ?: createNewSession(context)
    }
}
