package com.example.smarty.data.repository

import android.util.Log
import androidx.room.Transaction
import com.example.smarty.core.common.util.PrivacyGuard
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.ChatMessageEntity
import com.example.smarty.core.domain.model.ChatRole
import com.example.smarty.core.domain.model.ChatSession
import com.example.smarty.core.domain.model.Note
import com.example.smarty.data.local.ChatDao
import com.example.smarty.data.local.ChatMessageNotesDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Repository for managing chat sessions and messages.
 * Implements smart saving logic to avoid storing redundant data.
 *
 * SINGLE RESPONSIBILITY: Only manages chat_sessions and chat_messages tables.
 * Delegates note relationship management to ChatMessageNotesDao.
 * GLOBAL STATE: All operations respect user isolation and cascade deletes.
 */
class ChatRepository(
    private val chatDao: ChatDao,
    private val chatMessageNotesDao: ChatMessageNotesDao,
) {
    companion object {
        private const val TAG = "ChatRepository"
        private const val MAX_SESSIONS = 100 // Maximum number of sessions to keep
        private const val MIN_MESSAGES_TO_SAVE = 2 // Need at least user + smarty response
    }

    // ==================== Session Operations ====================

    /**
     * Get all chat sessions ordered by most recent
     */
    fun getAllSessions(): Flow<List<ChatSession>> =
        chatDao.getAllSessions()
            .distinctUntilChanged()

    /**
     * Get the currently active session
     */
    suspend fun getActiveSession(): ChatSession? = chatDao.getActiveSession()

    /**
     * Get active session as Flow for reactive updates
     */
    fun getActiveSessionFlow(): Flow<ChatSession?> =
        chatDao.getActiveSessionFlow()
            .distinctUntilChanged()

    /**
     * Create a new chat session and make it active
     */
    @Transaction
    suspend fun createNewSession(
        context: android.content.Context,
        title: String? = null,
    ): ChatSession {
        // Deactivate all existing sessions
        chatDao.deactivateAllSessions()

        val session =
            ChatSession(
                title = title ?: context.getString(com.example.smarty.R.string.title_new_chat),
                isActive = true,
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
        Log.d(TAG, "Entering deleteSession. Attempting to delete session and its messages. sessionId: $sessionId")
        try {
            chatDao.deleteMessagesForSession(sessionId)
            Log.d(TAG, "deleteSession: Successfully executed chatDao.deleteMessagesForSession for $sessionId")

            chatDao.deleteSessionById(sessionId)
            Log.d(TAG, "deleteSession: Successfully executed chatDao.deleteSessionById for $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Error during deleteSession for sessionId $sessionId: ${e.message}", e)
            throw e
        }
    }

    /**
     * Update session title (auto-generated from first message or user-set)
     */
    suspend fun updateSessionTitle(
        sessionId: String,
        title: String,
    ) {
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

    suspend fun getMessagesForSessionOnce(sessionId: String): List<ChatMessage> {
        return chatDao.getMessagesForSessionOnce(sessionId).map { it.toChatMessage() }
    }

    suspend fun deleteMessage(messageId: String): Boolean {
        Log.d(TAG, "Entering ChatRepository.deleteMessage for messageId: $messageId")
        return try {
            chatDao.deleteMessageById(messageId)
            Log.d(TAG, "Successfully executed chatDao.deleteMessageById for messageId: $messageId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute chatDao.deleteMessageById for message: $messageId", e)
            false
        }
    }

    suspend fun messageExists(messageId: String): Boolean {
        return chatDao.messageExists(messageId)
    }

    suspend fun getMessageById(messageId: String): ChatMessage? {
        return chatDao.getMessageById(messageId)?.toChatMessage()
    }

    /**
     * Save a message to a session.
     * Returns true if the message was saved, false if skipped.
     *
     * Smart saving logic:
     * - Always save user messages
     * - Only save Smarty responses if they have content
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
        allNotes: List<Note>? = null,
    ): Boolean {
        Log.d(TAG, "Entering saveMessage for session: $sessionId, messageId: ${message.id}, role: ${message.role}")
        // Skip system messages - they're for internal use
        if (message.role == ChatRole.SYSTEM) {
            Log.d(TAG, "saveMessage: Skipping System message.")
            return false
        }

        // Skip empty messages (unless they carry tool calls, inline images, or citations — e.g. citation-only responses)
        if (message.content.isBlank() && message.toolCalls.isEmpty() && message.inlineImages.isEmpty() && message.citations.isEmpty()) {
            Log.d(TAG, "saveMessage: Skipping blank message with no tool calls/images/citations.")
            return false
        }

        // SECURITY: Final sanitization check if notes are provided
        val sanitizedMessage =
            if (allNotes != null && message.referencedNoteIds.isNotEmpty()) {
                val sanitizedIds =
                    PrivacyGuard.sanitizeForChatPersistence(
                        message.referencedNoteIds,
                        allNotes,
                    )
                if (sanitizedIds.size != message.referencedNoteIds.size) {
                    Log.w(
                        TAG,
                        "SECURITY: Final sanitization removed ${message.referencedNoteIds.size - sanitizedIds.size} private note IDs",
                    )
                }
                message.copy(referencedNoteIds = sanitizedIds)
            } else {
                message
            }

        val entity = ChatMessageEntity.fromChatMessage(sanitizedMessage, sessionId)

        try {
            // Debug logging for thinking section storage
            if (message.role == ChatRole.SMARTY) {
                Log.d(TAG, "saveMessage: SMARTY message thinking length=${entity.thinking?.length}")
                if (entity.thinking != null) {
                    Log.d(TAG, "saveMessage: thinking preview=${entity.thinking.take(300)}")
                    Log.d(TAG, "saveMessage: thinking hasToolCalls=${entity.thinking.contains("[Action:")}")
                }
            }

            chatDao.insertMessage(entity)
            Log.d(TAG, "saveMessage: Successfully inserted ChatMessageEntity for message ID: ${message.id}")

            // Update session metadata
            val preview =
                if (message.content.length > 50) {
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
                Log.d(TAG, "saveMessage: Auto-generated and updated session title to: $autoTitle")
            }

            Log.d(TAG, "saveMessage: Finished saving message: ${message.id} to session: $sessionId")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "saveMessage: Error inserting message ID ${message.id} to session $sessionId - ${e.message}", e)
            return false
        }
    }

    /**
     * Save a pair of messages (user + smarty) atomically.
     * This is the primary method to use after a successful API response.
     *
     * @param sessionId The session to save to
     * @param userMessage The user's message
     * @param smartyMessage The Smarty response
     * @param shouldSave Whether saving is allowed (based on API success, demo mode, etc.)
     * @return true if messages were saved
     */
    @Transaction
    suspend fun saveMessagePair(
        sessionId: String,
        userMessage: ChatMessage,
        smartyMessage: ChatMessage,
        shouldSave: Boolean,
    ): Boolean {
        if (!shouldSave) {
            Log.d(TAG, "Skipping save - saving disabled")
            return false
        }

        // Smarty response must have content or tool calls/images/citations (e.g. image generation has no text, citation-only responses have no content)
        if (smartyMessage.content.isBlank() && smartyMessage.toolCalls.isEmpty() && smartyMessage.inlineImages.isEmpty() && smartyMessage.citations.isEmpty()) {
            Log.d(TAG, "Skipping save - smarty content is empty and no tool calls/images/citations")
            return false
        }

        // Save user message if it has content (continuation turns might have blank user messages)
        if (userMessage.content.isNotBlank()) {
            saveMessage(sessionId, userMessage)
        }

        // Save Smarty response
        saveMessage(sessionId, smartyMessage)

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
        val smartyCount = chatDao.getSmartyMessageCount(sessionId)

        // Need at least one user message and one Smarty response
        return messageCount >= MIN_MESSAGES_TO_SAVE && smartyCount >= 1
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

    // =============================================================================
    // NOTE RELATIONSHIP METHODS (Delegated to ChatMessageNotesDao)
    // =============================================================================

    /**
     * Link a note to a chat message.
     */
    @Transaction
    suspend fun linkNoteToMessage(
        messageId: String,
        noteId: String,
    ) {
        chatMessageNotesDao.linkMessageToNote(messageId, noteId)
        Log.d(TAG, "Linked note $noteId to message $messageId")
    }

    /**
     * Unlink a note from a chat message.
     */
    suspend fun unlinkNoteFromMessage(
        messageId: String,
        noteId: String,
    ) {
        chatMessageNotesDao.unlinkMessageFromNote(messageId, noteId)
        Log.d(TAG, "Unlinked note $noteId from message $messageId")
    }

    /**
     * Get all notes linked to a chat message.
     */
    suspend fun getLinkedNoteIds(messageId: String): List<String> {
        return chatMessageNotesDao.getLinkedNoteIds(messageId)
    }

    /**
     * Get all notes linked to a chat message as Flow.
     */
    fun getLinkedNoteIdsFlow(messageId: String): Flow<List<String>> {
        return chatMessageNotesDao.getLinkedNoteIdsFlow(messageId)
    }

    /**
     * Link multiple notes to a message.
     */
    @Transaction
    suspend fun linkMultipleNotesToMessage(
        messageId: String,
        noteIds: List<String>,
    ) {
        chatMessageNotesDao.linkMultipleNotesToMessage(messageId, noteIds)
        Log.d(TAG, "Linked ${noteIds.size} notes to message $messageId")
    }

    /**
     * Check if a message is linked to a specific note.
     */
    suspend fun isMessageLinkedToNote(
        messageId: String,
        noteId: String,
    ): Boolean {
        return chatMessageNotesDao.isLinked(messageId, noteId)
    }

    /**
     * Get count of notes linked to a message.
     */
    suspend fun getLinkedNoteCount(messageId: String): Int {
        return chatMessageNotesDao.getLinkCountForMessage(messageId)
    }

    // ==================== Helper Functions ====================

    /**
     * Generate a title from message content
     */
    private fun generateTitleFromContent(content: String): String {
        // Clean up the content
        val cleaned =
            content
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
