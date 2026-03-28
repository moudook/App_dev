package com.example.smarty.features.chat.domain

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.ChatRole
import com.example.smarty.core.domain.model.ChatSession
import com.example.smarty.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.smarty.core.common.util.HistoryCompressor
import java.util.UUID

data class FailedMessage(
    val originalContent: String,
    val attachments: List<Attachment>,
    val error: String,
    val timestamp: Long
)

data class QueuedMessage(
    val id: String,
    val content: String,
    val attachments: List<Attachment>,
    val queuedAt: Long
)

class ChatManager(
    private val context: android.content.Context,
    private val chatRepository: ChatRepository,
    private val scope: CoroutineScope,
    private val historyCompressor: HistoryCompressor,
    private val savedStateHandle: SavedStateHandle? = null
) {
    companion object {
        private const val TAG = "ChatManager"
        private const val KEY_DRAFT_TEXT = "draftText"
    }

    private val _isChatMode = MutableStateFlow(true)
    val isChatMode: StateFlow<Boolean> = _isChatMode.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isChatProcessing = MutableStateFlow(false)
    val isChatProcessing: StateFlow<Boolean> = _isChatProcessing.asStateFlow()

    private var preservedChatMessages: List<ChatMessage> = emptyList()
    private var preservedProcessingState: Boolean = false

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _chatSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val chatSessions: StateFlow<List<ChatSession>> = _chatSessions.asStateFlow()

    private var lastApiCallSuccessful = false

    private val chatMutex = Mutex()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _failedMessages = MutableStateFlow<List<FailedMessage>>(emptyList())
    val failedMessages: StateFlow<List<FailedMessage>> = _failedMessages.asStateFlow()

    private val _pendingQueue = MutableStateFlow<List<QueuedMessage>>(emptyList())
    val pendingQueue: StateFlow<List<QueuedMessage>> = _pendingQueue.asStateFlow()

    fun clearError() {
        _lastError.value = null
    }

    fun initialize() {
        scope.launch {
            chatRepository.getAllSessions()
                .distinctUntilChanged()
                .collect { sessions ->
                    chatMutex.withLock {
                        _chatSessions.value = sessions
                    }
                }
        }
        scope.launch {
            chatRepository.cleanupEmptySessions()
        }
    }

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

    suspend fun enterChatMode() {
        _isChatMode.value = true

        val activeSession = chatRepository.getActiveSession()
        if (activeSession != null) {
            _currentSessionId.value = activeSession.id
            if (preservedChatMessages.isNotEmpty()) {
                val deduped = preservedChatMessages.distinctBy { it.id }
                chatMutex.withLock {
                    _chatMessages.value = deduped
                    _isChatProcessing.value = preservedProcessingState
                }
                preservedChatMessages = emptyList()
                preservedProcessingState = false
            } else {
                val messages = chatRepository.getMessagesForSessionOnce(activeSession.id)
                    .distinctBy { it.id }
                chatMutex.withLock {
                    _chatMessages.value = messages
                    _isChatProcessing.value = preservedProcessingState
                    preservedProcessingState = false
                }
            }
        } else {
            _currentSessionId.value = null
            chatMutex.withLock {
                _chatMessages.value = emptyList()
                _isChatProcessing.value = false
            }
            Log.d(TAG, "No active session found - session will be created when user sends a message")
        }
    }

    fun exitChatMode() {
        preservedChatMessages = _chatMessages.value
        preservedProcessingState = _isChatProcessing.value

        scope.launch {
            _currentSessionId.value?.let { sessionId ->
                if (_chatMessages.value.isEmpty()) {
                    chatRepository.deleteSession(sessionId)
                    Log.d(TAG, "Deleted empty session on exit: $sessionId")
                } else {
                    chatRepository.markSessionInactive(sessionId)
                }
            }
        }
        _isChatMode.value = false
        Log.d(TAG, "Exited chat mode (preserved ${preservedChatMessages.size} messages, processing: $preservedProcessingState)")
    }

    fun createNewChatSession() {
        scope.launch {
            _currentSessionId.value?.let { sessionId ->
                chatRepository.finalizeSession(sessionId)
            }

            _currentSessionId.value = null
            chatMutex.withLock {
                _chatMessages.value = emptyList()
            }
            lastApiCallSuccessful = false
            preservedChatMessages = emptyList()

            Log.d(TAG, "Reset to new chat state - session will be created on first message")
        }
    }

    fun switchToChatSession(sessionId: String) {
        scope.launch {
            preservedChatMessages = emptyList()
            chatRepository.switchToSession(sessionId)
            _currentSessionId.value = sessionId
            val messages = chatRepository.getMessagesForSessionOnce(sessionId)
                .distinctBy { it.id }
            chatMutex.withLock {
                _chatMessages.value = messages
            }
            Log.d(TAG, "Switched to chat session: $sessionId with ${messages.size} messages")
        }
    }

    fun deleteChatSession(sessionId: String) {
        scope.launch {
            Log.d(TAG, "Entering deleteChatSession for sessionId: $sessionId")
            val isCurrentSession = sessionId == _currentSessionId.value
            try {
                chatRepository.deleteSession(sessionId)
                if (isCurrentSession) {
                    Log.d(TAG, "deleteChatSession: Session $sessionId is the current session, clearing state.")
                    _currentSessionId.value = null
                    chatMutex.withLock {
                        _chatMessages.value = emptyList()
                    }
                }
                Log.d(TAG, "Successfully deleted chat session: $sessionId. Current Session ID is now: ${_currentSessionId.value}")
            } catch (e: Exception) {
                Log.e(TAG, "Error in deleteChatSession for sessionId: $sessionId", e)
            }
        }
    }

    fun clearChatHistory() {
        scope.launch {
            chatRepository.deleteAllChatData()
            
            _currentSessionId.value = null
            chatMutex.withLock {
                _chatMessages.value = emptyList()
            }
            Log.d(TAG, "Chat history cleared from database and memory")
        }
    }

    suspend fun addUserMessage(content: String, attachments: List<Attachment> = emptyList()): ChatMessage {
        Log.d(TAG, "Entering addUserMessage. Content length: ${content.length}, Attachments: ${attachments.size}")
        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.USER,
            content = content,
            attachments = attachments,
            timestamp = System.currentTimeMillis()
        )
        chatMutex.withLock {
            if (_chatMessages.value.any { it.id == userMessage.id }) {
                Log.w(TAG, "addUserMessage: Duplicate message ID detected, skipping: ${userMessage.id}")
                return@withLock
            }
            _chatMessages.value = _chatMessages.value + userMessage
            Log.d(TAG, "addUserMessage: Successfully added user message ${userMessage.id} to StateFlow.")
        }
        return userMessage
    }

    suspend fun addSmartyMessage(message: ChatMessage) {
        Log.d(TAG, "Entering addSmartyMessage. Message ID: ${message.id}, Role: ${message.role}")
        chatMutex.withLock {
            if (_chatMessages.value.any { it.id == message.id }) {
                Log.w(TAG, "addSmartyMessage: Duplicate message ID detected, skipping: ${message.id}")
                return@withLock
            }
            _chatMessages.value = _chatMessages.value + message
            Log.d(TAG, "addSmartyMessage: Successfully added Smarty message ${message.id} to StateFlow.")
        }
    }

    suspend fun updateMessageById(messageId: String, newContent: String) {
        chatMutex.withLock {
            _chatMessages.value = _chatMessages.value.map { msg ->
                if (msg.id == messageId) msg.copy(content = newContent) else msg
            }
        }
    }

    suspend fun updateMessageWithThinking(messageId: String, newContent: String, newThinking: String?, confidence: String? = null, sourceType: String? = null) {
        chatMutex.withLock {
            _chatMessages.value = _chatMessages.value.map { msg ->
                if (msg.id == messageId) msg.copy(
                    content = newContent, 
                    thinking = newThinking.takeIf { !it.isNullOrBlank() },
                    confidence = confidence ?: msg.confidence,
                    sourceType = sourceType ?: msg.sourceType
                ) else msg
            }
        }
    }

    suspend fun updateMessageClarification(messageId: String, clarification: com.example.smarty.core.domain.model.ClarificationRequest?) {
        chatMutex.withLock {
            _chatMessages.value = _chatMessages.value.map { msg ->
                if (msg.id == messageId) msg.copy(clarificationRequest = clarification) else msg
            }
        }
    }

    suspend fun updateMessageNoteReferences(messageId: String, noteReference: com.example.smarty.core.domain.model.NoteReference) {
        chatMutex.withLock {
            _chatMessages.value = _chatMessages.value.map { msg ->
                if (msg.id == messageId) {
                    val updatedRefs = msg.noteReferences.toMutableList()
                    updatedRefs.add(noteReference)
                    msg.copy(noteReferences = updatedRefs)
                } else msg
            }
        }
    }

    suspend fun replaceMessage(messageId: String, newMessage: ChatMessage) {
        Log.d(TAG, "Entering replaceMessage. Target messageId: $messageId, New message ID: ${newMessage.id}")
        chatMutex.withLock {
            val exists = _chatMessages.value.any { it.id == messageId }
            if (!exists) {
                Log.w(TAG, "replaceMessage: Target messageId $messageId not found in StateFlow memory.")
            }
            _chatMessages.value = _chatMessages.value.map { msg ->
                if (msg.id == messageId) newMessage else msg
            }
            Log.d(TAG, "replaceMessage: Completed replace operation for $messageId.")
        }
    }

    suspend fun deleteMessage(messageId: String): Boolean {
        Log.d(TAG, "Entering deleteMessage for messageId: $messageId")
        return chatMutex.withLock {
            val messageToDelete = _chatMessages.value.find { it.id == messageId }
            if (messageToDelete != null) {
                Log.d(TAG, "deleteMessage: Message $messageId found in StateFlow memory. Proceeding to delete from memory and repository.")
                _chatMessages.value = _chatMessages.value.filter { it.id != messageId }
                scope.launch {
                    try {
                        val success = chatRepository.deleteMessage(messageId)
                        Log.d(TAG, "Successfully deleted message from repository: $messageId (repository returned: $success)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting message from repository: $messageId", e)
                    }
                }
                true
            } else {
                Log.w(TAG, "deleteMessage: Message $messageId NOT found in StateFlow memory. Deletion aborted at ChatManager level.")
                false
            }
        }
    }

    fun setProcessing(isProcessing: Boolean) {
        _isChatProcessing.value = isProcessing
    }

    fun markApiCallSuccessful() {
        lastApiCallSuccessful = true
    }

    fun resetApiCallFlag() {
        lastApiCallSuccessful = false
    }

    fun shouldSaveChat(): Boolean {
        if (_currentSessionId.value == null) return false
        if (!lastApiCallSuccessful) return false
        return true
    }

    suspend fun ensureSession(): String {
        val existingId = _currentSessionId.value
        if (existingId != null) {
            return existingId
        }
        val newSession = chatRepository.createNewSession(context)
        _currentSessionId.value = newSession.id
        return newSession.id
    }

    suspend fun updateSmartyMessageActions(messageId: String, updatedActions: List<com.example.smarty.core.domain.model.AgentActionResult>) {
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

    suspend fun saveMessagePair(
        userMessage: ChatMessage,
        smartyMessage: ChatMessage
    ): Result<Unit> {
        Log.d(TAG, "Entering saveMessagePair. UserMsgId: ${userMessage.id}, SmartyMsgId: ${smartyMessage.id}. CurrentSessionId: ${_currentSessionId.value}")
        return chatMutex.withLock {
            try {
                if (_currentSessionId.value == null) {
                    Log.w(TAG, "saveMessagePair: currentSessionId is null. Cannot save message pair.")
                }
                _currentSessionId.value?.let { sessionId ->
                    val saveAllowed = shouldSaveChat()
                    Log.d(TAG, "saveMessagePair: Invoking chatRepository.saveMessagePair. sessionId: $sessionId, shouldSave: $saveAllowed")
                    chatRepository.saveMessagePair(
                        sessionId = sessionId,
                        userMessage = userMessage,
                        smartyMessage = smartyMessage,
                        shouldSave = saveAllowed
                    )
                }
                Log.d(TAG, "saveMessagePair: Completed successfully.")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error in saveMessagePair: ${e.message}", e)
                _lastError.value = context.getString(com.example.smarty.R.string.error_save_message)
                Result.failure(e)
            }
        }
    }

    fun getCompressedHistory(): List<ChatMessage> {
        return historyCompressor.compress(_chatMessages.value)
    }

    fun getHistoryForAgent(): List<Pair<String, String>> {
        val compressed = getCompressedHistory()
        return compressed.map { msg ->
            val role = when {
                msg.isUser -> "USER"
                msg.isSmarty -> "SMARTY"
                else -> "SYSTEM"
            }
            role to msg.content
        }
    }

    fun saveDraft(text: String) {
        savedStateHandle?.set(KEY_DRAFT_TEXT, text)
    }

    fun getDraft(): String? = savedStateHandle?.get<String>(KEY_DRAFT_TEXT)

    fun clearDraft() {
        savedStateHandle?.remove<String>(KEY_DRAFT_TEXT)
    }

    fun addFailedMessage(content: String, attachments: List<Attachment>, error: String) {
        val failed = FailedMessage(
            originalContent = content,
            attachments = attachments,
            error = error,
            timestamp = System.currentTimeMillis()
        )
        _failedMessages.update { it + failed }
    }

    fun removeFailedMessage(failedMessage: FailedMessage) {
        _failedMessages.update { it.filter { m -> m != failedMessage } }
    }

    fun clearFailedMessages() {
        _failedMessages.value = emptyList()
    }

    fun queueMessage(content: String, attachments: List<Attachment>): QueuedMessage {
        val queued = QueuedMessage(
            id = UUID.randomUUID().toString(),
            content = content,
            attachments = attachments,
            queuedAt = System.currentTimeMillis()
        )
        _pendingQueue.update { it + queued }
        Log.d(TAG, "Queued message for later delivery: ${queued.id}")
        return queued
    }

    fun clearQueuedMessage(queuedId: String) {
        _pendingQueue.update { it.filter { m -> m.id != queuedId } }
    }

fun clearPendingQueue() {
        _pendingQueue.value = emptyList()
    }
}
