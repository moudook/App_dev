package com.example.smarty.viewmodel.managers

import android.util.Log
import com.example.smarty.data.model.Attachment
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatRole
import com.example.smarty.data.model.ChatSession
import com.example.smarty.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import com.example.smarty.util.HistoryCompressor
import com.example.smarty.util.PIIMasker

/**
 * Manages chat mode state and session lifecycle.
 * Extracted from SmartyViewModel for better separation of concerns.
 *
 * Responsibilities:
 * - Chat mode on/off state
 * - Chat message history (in-memory)
 * - Chat session management (create, switch, delete)
 * - Session persistence coordination
 */
class ChatManager(
    private val context: android.content.Context,
    private val chatRepository: ChatRepository,
    private val scope: CoroutineScope,
    private val historyCompressor: HistoryCompressor,
    private val piiMasker: PIIMasker
) {
    companion object {
        private const val TAG = "ChatManager"
    }

    // Chat mode state
    private val _isChatMode = MutableStateFlow(true)
    val isChatMode: StateFlow<Boolean> = _isChatMode.asStateFlow()

    // Chat messages (in-memory for current session)
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    // Processing state
    private val _isChatProcessing = MutableStateFlow(false)
    val isChatProcessing: StateFlow<Boolean> = _isChatProcessing.asStateFlow()

    // State preservation during mode switching
    private var preservedChatMessages: List<ChatMessage> = emptyList()
    private var preservedProcessingState: Boolean = false

    // Current session
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    // All sessions
    private val _chatSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val chatSessions: StateFlow<List<ChatSession>> = _chatSessions.asStateFlow()

    // Track if last API call was successful (for smart saving)
    private var lastApiCallSuccessful = false

    // Mutex for thread-safe chat operations (BUG-038 fix)
    private val chatMutex = Mutex()

    /**
     * BUG FIX (TECH-004): Error state for UI to observe.
     * Previously, errors were silently logged without UI notification.
     */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * BUG FIX (TECH-004): Clear the last error.
     * Call this after displaying the error to the user.
     */
    fun clearError() {
        _lastError.value = null
    }

    /**
     * Initialize by loading sessions and cleaning up empty ones
     */
    fun initialize() {
        scope.launch {
            chatRepository.getAllSessions()
                .distinctUntilChanged()
                .collect { sessions ->
                    // Use mutex to prevent race conditions with session switches
                    chatMutex.withLock {
                        _chatSessions.value = sessions
                    }
                }
        }
        scope.launch {
            chatRepository.cleanupEmptySessions()
        }
    }

    /**
     * Toggle between note input mode and chat mode
     */
    fun toggleChatMode() {
        scope.launch {
            try {
                if (!_isChatMode.value) {
                    enterChatMode()
                } else {
                    exitChatMode()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling chat mode: ${e.message}", e)
            }
        }
    }

    /**
     * Enter chat mode - creates a new session or restores the last active one
     * Also restores preserved state from quick switching
     */
    suspend fun enterChatMode() {
        _isChatMode.value = true

        val activeSession = chatRepository.getActiveSession()
        if (activeSession != null) {
            _currentSessionId.value = activeSession.id
            // Check if we have preserved messages from a recent switch
            if (preservedChatMessages.isNotEmpty()) {
                // Restore preserved state
                chatMutex.withLock {
                    _chatMessages.value = preservedChatMessages
                    _isChatProcessing.value = preservedProcessingState
                }
                Log.d(TAG, "Restored preserved chat state: ${preservedChatMessages.size} messages, processing: $preservedProcessingState")
                // Clear preserved state
                preservedChatMessages = emptyList()
                preservedProcessingState = false
            } else {
                // Load from repository
                val messages = chatRepository.getMessagesForSessionOnce(activeSession.id)
                chatMutex.withLock {
                    _chatMessages.value = messages
                    // Restore processing state if needed
                    _isChatProcessing.value = preservedProcessingState
                    preservedProcessingState = false
                }
                Log.d(TAG, "Restored chat session: ${activeSession.id} with ${messages.size} messages")
            }
        } else {
            val newSession = chatRepository.createNewSession(context)
            _currentSessionId.value = newSession.id
            chatMutex.withLock {
                _chatMessages.value = emptyList()
                _isChatProcessing.value = false
            }
            Log.d(TAG, "Created new chat session: ${newSession.id}")
        }

        Log.d(TAG, "Entered chat mode")
    }

    /**
     * Exit chat mode and return to note input mode
     * Preserves chat state for quick switching back
     */
    fun exitChatMode() {
        // Preserve current state before switching
        preservedChatMessages = _chatMessages.value
        preservedProcessingState = _isChatProcessing.value

        scope.launch {
            _currentSessionId.value?.let { sessionId ->
                // BUG FIX: If session is empty, delete it immediately to prevent blank history
                if (_chatMessages.value.isEmpty()) {
                    chatRepository.deleteSession(sessionId)
                    Log.d(TAG, "Deleted empty session on exit: $sessionId")
                } else {
                    // Don't finalize session immediately for quick switching
                    // Just mark it as inactive but keep the data
                    chatRepository.markSessionInactive(sessionId)
                }
            }
        }
        _isChatMode.value = false
        Log.d(TAG, "Exited chat mode (preserved ${preservedChatMessages.size} messages, processing: $preservedProcessingState)")
    }

    /**
     * Create a new chat session (starts fresh conversation)
     *
     * BUG FIX (Issue #38): Clear PIIMasker session to prevent memory leak
     * from accumulated placeholder mappings across sessions.
     */
    fun createNewChatSession() {
        scope.launch {
            _currentSessionId.value?.let { sessionId ->
                chatRepository.finalizeSession(sessionId)
            }

            val newSession = chatRepository.createNewSession(context)
            _currentSessionId.value = newSession.id
            chatMutex.withLock {
                _chatMessages.value = emptyList()
            }
            lastApiCallSuccessful = false

            // BUG FIX (Issue #38): Clear PII masker session to prevent memory leak
            piiMasker.clearSession()

            Log.d(TAG, "Created new chat session: ${newSession.id}")
        }
    }

    /**
     * Switch to a different chat session
     */
    fun switchToChatSession(sessionId: String) {
        scope.launch {
            chatRepository.switchToSession(sessionId)
            _currentSessionId.value = sessionId
            val messages = chatRepository.getMessagesForSessionOnce(sessionId)
            chatMutex.withLock {
                _chatMessages.value = messages
            }
            Log.d(TAG, "Switched to chat session: $sessionId with ${messages.size} messages")
        }
    }

    /**
     * Delete a chat session
     */
    fun deleteChatSession(sessionId: String) {
        scope.launch {
            val isCurrentSession = sessionId == _currentSessionId.value
            chatRepository.deleteSession(sessionId)

            if (isCurrentSession) {
                val newSession = chatRepository.createNewSession(context)
                _currentSessionId.value = newSession.id
                chatMutex.withLock {
                    _chatMessages.value = emptyList()
                }
            }
            Log.d(TAG, "Deleted chat session: $sessionId")
        }
    }

    /**
     * Clear current chat history (keeps session, just clears messages in memory)
     */
    fun clearChatHistory() {
        scope.launch {
            chatMutex.withLock {
                _chatMessages.value = emptyList()
            }
        }
        Log.d(TAG, "Chat history cleared")
    }

    /**
     * Add a user message to the chat (thread-safe using Mutex)
     */
    suspend fun addUserMessage(content: String, attachments: List<Attachment> = emptyList()): ChatMessage {
        val userMessage = ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            role = ChatRole.USER,
            content = content,
            attachments = attachments,
            timestamp = System.currentTimeMillis()
        )
        chatMutex.withLock {
            _chatMessages.value = _chatMessages.value + userMessage
        }
        return userMessage
    }

    /**
     * Add an assistant response to the chat (thread-safe using Mutex)
     */
    suspend fun addAssistantMessage(message: ChatMessage) {
        chatMutex.withLock {
            _chatMessages.value = _chatMessages.value + message
        }
    }

    /**
     * Set chat processing state
     */
    fun setProcessing(isProcessing: Boolean) {
        _isChatProcessing.value = isProcessing
    }

    /**
     * Mark API call as successful
     */
    fun markApiCallSuccessful() {
        lastApiCallSuccessful = true
    }

    /**
     * Reset API call success flag
     */
    fun resetApiCallFlag() {
        lastApiCallSuccessful = false
    }

    /**
     * Determine if chat should be saved based on conditions
     */
    fun shouldSaveChat(): Boolean {
        if (_currentSessionId.value == null) return false
        if (!lastApiCallSuccessful) return false
        return true
    }

    /**
     * Ensure we have a valid session, creating one if needed
     */
    suspend fun ensureSession(): String {
        val existingId = _currentSessionId.value
        if (existingId != null) {
            return existingId
        }
        val newSession = chatRepository.createNewSession(context)
        _currentSessionId.value = newSession.id
        return newSession.id
    }

    /**
     * Update the actions of a specific assistant message (thread-safe using Mutex)
     */
    suspend fun updateAssistantMessageActions(messageId: String, updatedActions: List<com.example.smarty.data.model.AgentActionResult>) {
        chatMutex.withLock {
            val currentMessages = _chatMessages.value
            val updatedMessages = currentMessages.map { message ->
                if (message.id == messageId) {
                    message.copy(executedActions = updatedActions)
                } else {
                    message
                }
            }
            _chatMessages.value = updatedMessages
        }
    }

    /**
     * Save a message pair to persistent storage (thread-safe)
     *
     * BUG FIX (TECH-004): Now returns Result to indicate success/failure
     * and sets lastError state for UI observation.
     */
    suspend fun saveMessagePair(
        userMessage: ChatMessage,
        assistantMessage: ChatMessage
    ): Result<Unit> {
        return chatMutex.withLock {
            try {
                _currentSessionId.value?.let { sessionId ->
                    chatRepository.saveMessagePair(
                        sessionId = sessionId,
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                        shouldSave = shouldSaveChat()
                    )
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving message pair: ${e.message}", e)
                // BUG FIX (TECH-004): Set error state for UI to observe
                _lastError.value = context.getString(com.example.smarty.R.string.error_save_message)
                Result.failure(e)
            }
        }
    }

    /**
     * Get compressed conversation history for AI agent.
     *
     * Uses HistoryCompressor to reduce token usage:
     * - Keeps last 3 exchanges verbatim
     * - Summarizes older exchanges
     * - Expected 50-70% token reduction for long conversations
     *
     * @return Compressed chat messages suitable for agent context
     */
    fun getCompressedHistory(): List<ChatMessage> {
        return historyCompressor.compress(_chatMessages.value)
    }

    /**
     * Get conversation history in legacy format (role, content pairs).
     * Uses compression for token efficiency.
     *
     * @return List of (role, content) pairs for agent
     */
    fun getHistoryForAgent(): List<Pair<String, String>> {
        val compressed = getCompressedHistory()
        return compressed.map { msg ->
            val role = when {
                msg.isUser -> "USER"
                msg.isAssistant -> "ASSISTANT"
                else -> "SYSTEM"
            }
            role to msg.content
        }
    }
}
