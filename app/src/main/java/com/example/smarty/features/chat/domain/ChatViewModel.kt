package com.example.smarty.features.chat.domain

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.ChatRole
import com.example.smarty.data.repository.ChatRepository
import com.example.smarty.data.state.SharedAppState
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.local.AIConnection
import com.example.smarty.di.ServiceLocator
import com.example.smarty.features.chat.domain.mapper.ChatMessageMapper
import com.example.smarty.features.chat.domain.state.ChatState
import com.example.smarty.features.chat.domain.state.ChatUiState
import com.example.smarty.features.chat.domain.event.ChatEvent
import com.example.smarty.features.chat.domain.usecase.*
import com.example.smarty.ui.components.ConnectionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Refactored ViewModel for chat using SDE best practices.
 * 
 * Principles applied:
 * - **Single Responsibility**: Use cases handle specific business logic
 * - **Global State Management**: ChatState and ChatUiState for separated concerns
 * - **DRY**: ChatMessageMapper centralizes transformations
 * - **Event-driven**: ChatEvent sealed class for UI events
 * - **Immutable State**: State updates via copy-on-write
 */
class ChatViewModel(
    application: Application
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    // Use cases - each with single responsibility
    private val sendMessageUseCase: SendMessageUseCase by lazy {
        SendMessageUseCase(chatRepository)
    }
    
    private val updateMessageUseCase: UpdateMessageUseCase by lazy {
        UpdateMessageUseCase(chatRepository)
    }
    
    private val getMessagesUseCase: GetMessagesUseCase by lazy {
        GetMessagesUseCase(chatRepository)
    }
    
    private val clearMessagesUseCase: ClearMessagesUseCase by lazy {
        ClearMessagesUseCase(chatRepository)
    }
    
    private val deleteMessageUseCase: DeleteMessageUseCase by lazy {
        DeleteMessageUseCase(chatRepository)
    }

    // Repositories
    private val chatRepository: ChatRepository by lazy {
        ServiceLocator.provideChatRepository(application)
    }

    private val sharedAppState: SharedAppState by lazy {
        ServiceLocator.provideSharedAppState()
    }
    
    // Remote Agent Service for AI processing
    private val remoteAgentService: com.example.smarty.data.remote.RemoteAgentService by lazy {
        ServiceLocator.provideRemoteAgentService(application)
    }

    private val securePreferences: SecurePreferences by lazy {
        SecurePreferences.getInstance(application)
    }

    // Global state - immutable, updated via copy
    private val _chatState = MutableStateFlow(ChatState.initial())
    val chatState: StateFlow<ChatState> = _chatState.asStateFlow()

    // UI state - separated from domain state
    private val _uiState = MutableStateFlow(ChatUiState.initial())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        // Set initial state from cached preferences
        val initialModel = securePreferences.getSelectedModel(AIConnection.LOCAL_PC)
        val initialCachedModels = securePreferences.getAvailableModels(AIConnection.LOCAL_PC)
        _uiState.update { 
            it.copy(
                selectedModel = initialModel,
                availableModels = initialCachedModels
            )
        }

        // Fetch latest dynamic list of models from backend
        viewModelScope.launch {
            try {
                val dynamicModels = remoteAgentService.getOpencodeModels(refresh = false)
                if (dynamicModels.isNotEmpty()) {
                    securePreferences.setCachedModels(dynamicModels)
                    val activeModel = securePreferences.getSelectedModel(AIConnection.LOCAL_PC)
                    _uiState.update { 
                        it.copy(
                            selectedModel = activeModel,
                            availableModels = dynamicModels
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize opencode models: ${e.message}")
            }
        }
    }

    val connectionStatus: StateFlow<ConnectionStatus> = sharedAppState.connectionStatus

    // Current streaming job
    private var currentStreamingJob: Job? = null

    /**
     * Process UI events - single entry point for all user interactions.
     */
    fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.MessageSent -> handleSendMessage(event.content, event.attachments)
            is ChatEvent.InputTextChanged -> handleInputTextChange(event.newText)
            is ChatEvent.MessageCopied -> handleCopyMessage(event.messageId, event.content)
            is ChatEvent.MessageDeleted -> handleDeleteMessage(event.messageId)
            is ChatEvent.MessageEdited -> handleEditMessage(event.message)
            is ChatEvent.ClarificationSubmitted -> handleClarificationSubmit(event.messageId, event.response)
            is ChatEvent.InputFocusChanged -> handleFocusChange(event.isFocused)
            is ChatEvent.AttachmentPanelToggled -> handleAttachmentPanelToggle(event.isVisible)
            is ChatEvent.ScrollPositionChanged -> handleScrollPositionChange(event.position, event.isAtLatest)
            is ChatEvent.NewChatRequested -> handleNewChat()
            is ChatEvent.GenerationStopped -> handleStopGeneration()
            is ChatEvent.ErrorOccurred -> handleError(event.message, event.error)
            is ChatEvent.ErrorDismissed -> handleDismissError()
            is ChatEvent.ModelSelected -> handleModelSelected(event.modelId)
            is ChatEvent.ModelsRefreshRequested -> handleModelsRefresh()
            // Add more event handlers as needed
            else -> Log.d(TAG, "Event not handled: $event")
        }
    }

    /**
     * Send a message - delegates to use case for business logic.
     */
    private fun handleSendMessage(content: String, attachments: List<Attachment>) {
        if (content.isBlank() && attachments.isEmpty()) return

        currentStreamingJob?.cancel()

        currentStreamingJob = viewModelScope.launch {
            // Update state: set processing
            _chatState.update { it.copy(isProcessing = true, errorMessage = null) }

            try {
                // Ensure session exists
                ensureSession()

                // Create user message via use case
                val sessionId = _chatState.value.currentSessionId ?: return@launch
                val userMessage = sendMessageUseCase.execute(sessionId, content, attachments)

                // Update state: add user message
                _chatState.update { state ->
                    state.copy(
                        messages = state.messages + userMessage,
                        lastUpdated = System.currentTimeMillis()
                    )
                }

                // Process with AI (simplified - would integrate with agent)
                processWithAI(content, userMessage)

            } catch (e: Exception) {
                Log.e(TAG, "Error sending message: ${e.message}", e)
                _chatState.update { 
                    it.copy(
                        errorMessage = "Failed to send message: ${e.message}",
                        isProcessing = false
                    )
                }
            }
        }
    }

    /**
     * Process message with AI agent using RemoteAgentService.
     */
    private suspend fun processWithAI(content: String, userMessage: ChatMessage) {
        val sessionId = _chatState.value.currentSessionId ?: return

        try {
            // Create streaming message
            val streamingMessageId = java.util.UUID.randomUUID().toString()
            val streamingMessage = ChatMessage(
                id = streamingMessageId,
                role = ChatRole.SMARTY,
                content = "",
                timestamp = System.currentTimeMillis(),
                isStreaming = true
            )

            // Add to state
            _chatState.update { state ->
                state.copy(
                    messages = state.messages + streamingMessage,
                    lastUpdated = System.currentTimeMillis()
                )
            }

            // Actually call the AI service
            val responseBuilder = StringBuilder()
            var finalContent: String? = null
            var finalThinking: String? = null
            remoteAgentService.sendQuery(
                query = content,
                sessionId = sessionId,
                model = _uiState.value.selectedModel
            ).collect { event ->
                when (event) {
                    is com.example.smarty.protocol.AgentEvent.Processing -> {
                        event.content?.let { responseBuilder.append(it) }
                        // Update message as content streams in
                        _chatState.update { state ->
                            state.copy(
                                messages = state.messages.map { msg ->
                                    if (msg.id == streamingMessageId) {
                                        msg.copy(content = responseBuilder.toString())
                                    } else msg
                                },
                                lastUpdated = System.currentTimeMillis()
                            )
                        }
                    }
                    is com.example.smarty.protocol.AgentEvent.Result -> {
                        finalContent = if (event.content.isNotEmpty()) event.content else responseBuilder.toString()
                        finalThinking = event.thinking
                        // Mark complete
                        _chatState.update { state ->
                            state.copy(
                                messages = state.messages.map { msg ->
                                    if (msg.id == streamingMessageId) {
                                        msg.copy(
                                            isStreaming = false,
                                            content = finalContent!!,
                                            thinking = finalThinking
                                        )
                                    } else msg
                                },
                                isProcessing = false,
                                lastUpdated = System.currentTimeMillis()
                            )
                        }
                    }
                    else -> { /* Handle other events */ }
                }
            }

            // Save message pair with the correct final content
            val savedMessage = streamingMessage.copy(
                content = finalContent ?: responseBuilder.toString(),
                thinking = finalThinking,
                isStreaming = false
            )
            saveMessagePair(userMessage, savedMessage)

        } catch (e: Exception) {
            Log.e(TAG, "AI processing failed: ${e.message}", e)
            _chatState.update { 
                it.copy(
                    isProcessing = false,
                    errorMessage = "AI processing failed: ${e.message}"
                )
            }
        }
    }

    /**
     * Delete a message - delegates to use case.
     */
    private fun handleDeleteMessage(messageId: String) {
        viewModelScope.launch {
            try {
                deleteMessageUseCase.execute(messageId)

                // Update state
                _chatState.update { state ->
                    state.copy(
                        messages = state.messages.filter { it.id != messageId },
                        lastUpdated = System.currentTimeMillis()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting message: ${e.message}", e)
                _chatState.update {
                    it.copy(errorMessage = "Failed to delete message: ${e.message}")
                }
            }
        }
    }

    /**
     * Handle message edit - Feature 4: Edit & Resend.
     * Removes the original message and all messages after it, then re-sends as a new message.
     */
    private fun handleEditMessage(message: ChatMessage) {
        Log.d(TAG, "Edit & Resend for message: ${message.id}")
        viewModelScope.launch {
            try {
                // Remove the edited message and everything after it from state
                val currentMessages = _chatState.value.messages
                val editIndex = currentMessages.indexOfFirst { it.id == message.id }
                if (editIndex >= 0) {
                    val messagesToRemove = currentMessages.drop(editIndex)
                    val trimmedMessages = currentMessages.take(editIndex)
                    messagesToRemove.forEach { msg ->
                        try { deleteMessageUseCase.execute(msg.id) } catch (_: Exception) {}
                    }
                    _chatState.update { it.copy(messages = trimmedMessages, lastUpdated = System.currentTimeMillis()) }
                }
                // Re-send the edited content
                handleSendMessage(message.content, message.attachments)
            } catch (e: Exception) {
                Log.e(TAG, "Error in Edit & Resend: ${e.message}", e)
                _chatState.update { it.copy(errorMessage = "Edit failed: ${e.message}") }
            }
        }
    }

    /**
     * Handle clarification submission - Feature 2: Interactive Question Mode.
     * Sends the user's chosen clarification answer as a new message to the AI.
     */
    private fun handleClarificationSubmit(messageId: String, response: String) {
        Log.d(TAG, "Clarification submitted for message: $messageId -> $response")
        if (response.isBlank()) return
        // Clear the clarification UI from the message
        _chatState.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (msg.id == messageId) msg.copy(clarificationRequest = null) else msg
                },
                lastUpdated = System.currentTimeMillis()
            )
        }
        // Send the clarification answer as a new user message
        handleSendMessage(response, emptyList())
    }

    /**
     * Handle input text change - updates UI state only.
     */
    private fun handleInputTextChange(newText: androidx.compose.ui.text.input.TextFieldValue) {
        _uiState.update { it.copy(inputText = newText) }
    }

    /**
     * Handle focus change - updates UI state only.
     */
    private fun handleFocusChange(isFocused: Boolean) {
        _uiState.update { it.copy(isInputFocused = isFocused) }
    }

    /**
     * Handle attachment panel toggle - updates UI state only.
     */
    private fun handleAttachmentPanelToggle(isVisible: Boolean) {
        _uiState.update { it.copy(showAttachmentPanel = isVisible) }
    }

    /**
     * Handle scroll position change - updates state.
     */
    private fun handleScrollPositionChange(position: Int, isAtLatest: Boolean) {
        _chatState.update { 
            it.copy(
                scrollPosition = position,
                lastUpdated = System.currentTimeMillis()
            )
        }
        _uiState.update { it.copy(isAtLatestMessage = isAtLatest) }
    }

    /**
     * Start a new chat session.
     */
    private fun handleNewChat() {
        viewModelScope.launch {
            clearMessagesUseCase.execute(_chatState.value.currentSessionId ?: return@launch)
            
            _chatState.update { 
                ChatState.initial().copy(
                    currentSessionId = it.currentSessionId // Keep session ID
                )
            }
            
            _uiState.update { ChatUiState.initial() }
            
            Log.d(TAG, "New chat session started")
        }
    }

    /**
     * Stop current generation.
     */
    private fun handleStopGeneration() {
        currentStreamingJob?.cancel()
        currentStreamingJob = null
        _chatState.update { it.copy(isProcessing = false) }
        Log.d(TAG, "Generation stopped")
    }

    /**
     * Handle error.
     */
    private fun handleError(message: String, error: Throwable?) {
        Log.e(TAG, "Error: $message", error)
        _chatState.update { it.copy(errorMessage = message) }
    }

    /**
     * Dismiss error.
     */
    private fun handleDismissError() {
        _chatState.update { it.copy(errorMessage = null) }
    }

    /**
     * Ensure chat session exists.
     */
    private suspend fun ensureSession() {
        if (_chatState.value.currentSessionId == null) {
            val newSessionId = java.util.UUID.randomUUID().toString()
            _chatState.update { it.copy(currentSessionId = newSessionId) }
            Log.d(TAG, "New session created: $newSessionId")
        }
    }

    /**
     * Save message pair to database.
     */
    private suspend fun saveMessagePair(
        userMessage: ChatMessage,
        smartyMessage: ChatMessage
    ) {
        withContext(NonCancellable) {
            try {
                val sessionId = _chatState.value.currentSessionId ?: return@withContext
                chatRepository.saveMessage(sessionId, userMessage)
                chatRepository.saveMessage(sessionId, smartyMessage)
                Log.d(TAG, "Message pair saved to session $sessionId")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving messages: ${e.message}", e)
            }
        }
    }

    /**
     * Copy message to clipboard.
     */
    private fun handleCopyMessage(messageId: String, content: String) {
        val cleanedContent = ChatMessageMapper.cleanContent(content)
        
        // Use Android clipboard
        val clipboard = android.content.ClipData.newPlainText("chat_message", cleanedContent)
        val clipboardManager = getApplication<Application>()
            .getSystemService(android.content.ClipboardManager::class.java)
        clipboardManager.setPrimaryClip(clipboard)
        
        Log.d(TAG, "Message copied to clipboard: $messageId")
    }

    /**
     * Clear all messages.
     */
    fun clearMessages() {
        viewModelScope.launch {
            clearMessagesUseCase.execute(_chatState.value.currentSessionId ?: return@launch)
            _chatState.update { ChatState.initial() }
            _uiState.update { ChatUiState.initial() }
            Log.d(TAG, "All messages cleared")
        }
    }

    /**
     * Update UI state directly (for simple UI-only changes).
     */
    fun updateUiState(update: ChatUiState.() -> ChatUiState) {
        _uiState.update(update)
    }

    /**
     * Set listening state.
     */
    fun setListening(listening: Boolean) {
        _uiState.update { it.copy(isVoiceListening = listening) }
        _chatState.update { it.copy(isListening = listening) }
    }

    private fun handleModelSelected(modelId: String) {
        Log.d(TAG, "Model selected: $modelId")
        securePreferences.setSelectedModel(AIConnection.LOCAL_PC, modelId)
        _uiState.update { it.copy(selectedModel = modelId) }
    }

    private fun handleModelsRefresh() {
        viewModelScope.launch {
            try {
                val refreshed = remoteAgentService.getOpencodeModels(refresh = true)
                if (refreshed.isNotEmpty()) {
                    securePreferences.setCachedModels(refreshed)
                    _uiState.update { it.copy(availableModels = refreshed) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh models: ${e.message}")
            }
        }
    }

    /**
     * Suspend function for UI to call directly for live model refresh.
     * Returns the refreshed model list, or empty if failed.
     */
    suspend fun refreshModelsNow(): List<Pair<String, String>> {
        return try {
            val refreshed = remoteAgentService.getOpencodeModels(refresh = true)
            if (refreshed.isNotEmpty()) {
                securePreferences.setCachedModels(refreshed)
                _uiState.update { it.copy(availableModels = refreshed) }
            }
            refreshed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh models: ${e.message}")
            emptyList()
        }
    }
}

// Helper extension for StateFlow
fun <T, R> StateFlow<T>.asStateFlow(transform: (T) -> R): StateFlow<R> {
    val mutableStateFlow = MutableStateFlow(transform(value))
    val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
        collect { mutableStateFlow.value = transform(it) }
    }
    // Note: In production, manage job lifecycle properly
    return mutableStateFlow.asStateFlow()
}
