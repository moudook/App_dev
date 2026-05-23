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
import com.example.smarty.features.chat.domain.state.PendingApproval
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

    // ── Permission Engine ──────────────────────────────────────────────────────
    // Tracks the currently-pending approval gate so the UI can show
    // a PermissionCard while the agent stream is paused.
    private val _pendingApprovalState = MutableStateFlow<PendingApproval?>(null)
    val pendingApprovalState: StateFlow<PendingApproval?> = _pendingApprovalState.asStateFlow()

    /**
     * Call when user taps Approve or Deny on the PermissionCard.
     * Sends the decision back to the server so the agent stream can resume.
     */
    fun callApproval(toolId: String, approved: Boolean, feedback: String? = null) {
        // Read pending atomically — if a newer ApprovalRequested arrived between
        // the user tap and this execution, the toolId won't match and we bail.
        val current = _pendingApprovalState.value ?: return
        if (current.toolId != toolId) {
            Log.w(TAG, "callApproval: toolId mismatch — UI sent $toolId but pending is ${current.toolId}. Stale tap discarded.")
            return
        }
        val sessionId = current.sessionId ?: return

        viewModelScope.launch {
            try {
                remoteAgentService.sendApproval(
                    toolId = toolId,
                    approved = approved,
                    feedback = feedback,
                )
                // Clear pending state
                _pendingApprovalState.update { null }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send approval response: ${e.message}", e)
            }
        }
    }

    init {
        // Collect model preference updates dynamically for perfect real-time sync across viewports
        viewModelScope.launch {
            securePreferences.selectedModelFlow.collect { model ->
                _uiState.update { it.copy(selectedModel = model) }
            }
        }
        viewModelScope.launch {
            securePreferences.availableModelsFlow.collect { models ->
                _uiState.update { it.copy(availableModels = models) }
            }
        }

        // Set initial state from cached preferences
        val initialModel = securePreferences.getSelectedModel(AIConnection.LOCAL_PC)
        val initialCachedModels = securePreferences.getAvailableModels(AIConnection.LOCAL_PC)
        
        Log.d(TAG, "ViewModel init: initialModel=$initialModel, cachedModels=${initialCachedModels.size}")
        
        // Always start with correct fallback models (synced with server discoveries)
        val fallbackModels = com.example.smarty.features.chat.domain.state.DEFAULT_FREE_MODELS
        
        _uiState.update { 
            it.copy(
                selectedModel = initialModel,
                availableModels = fallbackModels
            )
        }

        // Fetch latest dynamic list of models from backend
        viewModelScope.launch {
            try {
                Log.d(TAG, "Fetching dynamic models from server...")
                val dynamicModels = remoteAgentService.getOpencodeModels(refresh = true)
                Log.d(TAG, "Server returned ${dynamicModels.size} models: $dynamicModels")
                
                if (dynamicModels.isNotEmpty()) {
                    securePreferences.setCachedModels(dynamicModels)
                    
                    // Validate current selected model is still in the list
                    val currentModel = securePreferences.getSelectedModel(AIConnection.LOCAL_PC)
                    val activeModel = if (dynamicModels.any { it.first == currentModel }) {
                        currentModel
                    } else {
                        val defaultModel = dynamicModels.first().first
                        securePreferences.setSelectedModel(AIConnection.LOCAL_PC, defaultModel)
                        defaultModel
                    }
                    
                    _uiState.update { 
                        it.copy(
                            selectedModel = activeModel,
                            availableModels = dynamicModels
                        )
                    }
                    Log.d(TAG, "Models updated: selected=$activeModel, available=${dynamicModels.size}")
                } else {
                    Log.w(TAG, "Server returned empty model list, keeping fallback models")
                    // Don't update UI - keep the correct fallback models
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize opencode models: ${e.message}", e)
                // Keep using correct fallback models - don't fall back to potentially stale cache
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
            // Create streaming message — kept separate from messages list
            // to avoid full LazyColumn recomposition on every token
            val streamingMessageId = java.util.UUID.randomUUID().toString()
            var currentStreamingMessage = ChatMessage(
                id = streamingMessageId,
                role = ChatRole.SMARTY,
                content = "",
                timestamp = System.currentTimeMillis(),
                isStreaming = true
            )

            // Set streaming message in state
            _chatState.update { it.copy(streamingMessage = currentStreamingMessage) }

            // Actually call the AI service
            val responseBuilder = StringBuilder()
            val thinkingBuilder = StringBuilder()
            val agentStepsBuilder = mutableListOf<com.example.smarty.core.domain.model.AgentStepEntry>()
            val agentEventsBuilder = mutableListOf<com.example.smarty.protocol.AgentEvent>()
            remoteAgentService.sendQuery(
                query = content,
                sessionId = sessionId,
                model = _uiState.value.selectedModel,
                messageId = streamingMessageId,
            ).collect { event ->
                // 1) Save to unified event log
                try {
                    val eventType = event::class.simpleName ?: "Unknown"
                    val payloadJson = kotlinx.serialization.json.Json.encodeToString(
                        com.example.smarty.protocol.AgentEvent.serializer(), event
                    )
                    chatRepository.saveTimelineEvent(
                        com.example.smarty.data.local.entity.TimelineEventEntity(
                            eventId = event.eventId,
                            traceId = streamingMessageId,
                            timestamp = event.timestamp,
                            sessionId = sessionId,
                            eventType = eventType,
                            payloadJson = payloadJson
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save timeline event: ${e.message}")
                }

                // 2) Accumulate in-memory for UI timeline
                agentEventsBuilder.add(event)
                currentStreamingMessage = currentStreamingMessage.copy(
                    agentEvents = agentEventsBuilder.toList()
                )
                _chatState.update { it.copy(streamingMessage = currentStreamingMessage) }

                when (event) {
                    // ── Content streaming (per-chunk deltas) ──
                    is com.example.smarty.protocol.AgentEvent.FinalAnswerDelta -> {
                        responseBuilder.append(event.text)
                        currentStreamingMessage = currentStreamingMessage.copy(
                            content = responseBuilder.toString(),
                            agentSteps = agentStepsBuilder.toList(),
                            agentEvents = agentEventsBuilder.toList()
                        )
                        _chatState.update { it.copy(streamingMessage = currentStreamingMessage) }
                    }

                    // ── Reasoning/thinking streaming (per-chunk deltas) ──
                    is com.example.smarty.protocol.AgentEvent.ReasoningDelta -> {
                        thinkingBuilder.append(event.text)
                        currentStreamingMessage = currentStreamingMessage.copy(
                            thinking = thinkingBuilder.toString(),
                            agentEvents = agentEventsBuilder.toList()
                        )
                        _chatState.update { it.copy(streamingMessage = currentStreamingMessage) }
                    }

                    is com.example.smarty.protocol.AgentEvent.ReasoningStarted -> {
                        thinkingBuilder.clear()
                        currentStreamingMessage = currentStreamingMessage.copy(
                            thinking = "",
                            agentEvents = agentEventsBuilder.toList()
                        )
                        _chatState.update { it.copy(streamingMessage = currentStreamingMessage) }
                    }

                    is com.example.smarty.protocol.AgentEvent.ReasoningFinished -> {
                        currentStreamingMessage = currentStreamingMessage.copy(
                            agentEvents = agentEventsBuilder.toList()
                        )
                        _chatState.update { it.copy(streamingMessage = currentStreamingMessage) }
                    }

                    // ── Legacy Processing events — Server sends FULL accumulated content here.
                    //     Use for content sync + thinking extraction, not for primary accumulation.
                    is com.example.smarty.protocol.AgentEvent.Processing -> {
                        if (event.content.isNotEmpty()) {
                            responseBuilder.clear()
                            responseBuilder.append(event.content)
                        }
                        if (!event.thinking.isNullOrEmpty()) {
                            thinkingBuilder.clear()
                            thinkingBuilder.append(event.thinking)
                        }
                        currentStreamingMessage = currentStreamingMessage.copy(
                            content = responseBuilder.toString(),
                            thinking = thinkingBuilder.toString(),
                            agentSteps = agentStepsBuilder.toList(),
                            agentEvents = agentEventsBuilder.toList()
                        )
                        _chatState.update { it.copy(streamingMessage = currentStreamingMessage) }
                    }

                    // ── Final answer lifecycle ──
                    is com.example.smarty.protocol.AgentEvent.FinalAnswerStarted -> {
                        currentStreamingMessage = currentStreamingMessage.copy(
                            agentEvents = agentEventsBuilder.toList()
                        )
                        _chatState.update { it.copy(streamingMessage = currentStreamingMessage) }
                    }

                    is com.example.smarty.protocol.AgentEvent.FinalAnswerFinished -> {
                        currentStreamingMessage = currentStreamingMessage.copy(
                            agentEvents = agentEventsBuilder.toList()
                        )
                        _chatState.update { it.copy(streamingMessage = currentStreamingMessage) }
                    }

                    // ── Final result (stream complete) ──
                    is com.example.smarty.protocol.AgentEvent.Result -> {
                        currentStreamingMessage = currentStreamingMessage.copy(
                            isStreaming = false,
                            content = responseBuilder.toString(),
                            thinking = thinkingBuilder.toString(),
                            agentSteps = agentStepsBuilder.toList(),
                            agentEvents = agentEventsBuilder.toList()
                        )
                        _chatState.update { state ->
                            state.copy(
                                messages = state.messages + currentStreamingMessage,
                                streamingMessage = null,
                                isProcessing = false
                            )
                        }
                        
                        // Trigger a background sync so notes created by the agent show up live
                        try {
                            com.example.smarty.di.ServiceLocator.provideEventSink().onStateSync("chat_finished", "")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to trigger sync", e)
                        }
                    }

                    // ── Agent step timeline ──
                    is com.example.smarty.protocol.AgentEvent.AgentStep -> {
                        val uiStep = com.example.smarty.core.domain.model.AgentStepEntry(
                            stepType = event.stepType,
                            stepTitle = event.stepTitle,
                            stepContent = event.stepContent,
                            stepStatus = event.stepStatus,
                            stepIndex = event.stepIndex,
                            toolName = event.toolName,
                            durationMs = event.durationMs
                        )
                        val existingIndex = agentStepsBuilder.indexOfFirst { it.stepIndex == event.stepIndex }
                        if (existingIndex >= 0) {
                            agentStepsBuilder[existingIndex] = uiStep
                        } else {
                            agentStepsBuilder.add(uiStep)
                        }
                        currentStreamingMessage = currentStreamingMessage.copy(
                            agentSteps = agentStepsBuilder.toList(),
                            agentEvents = agentEventsBuilder.toList()
                        )
                        _chatState.update { it.copy(streamingMessage = currentStreamingMessage) }
                    }

                    // ── Tool Call (legacy) ──
                    is com.example.smarty.protocol.AgentEvent.ToolCall -> {
                        Log.d(TAG, "ToolCall: ${event.toolName} (${event.status})")
                        currentStreamingMessage = currentStreamingMessage.copy(
                            agentEvents = agentEventsBuilder.toList()
                        )
                        _chatState.update { it.copy(streamingMessage = currentStreamingMessage) }
                    }

                    // ── Question flow ──
                    is com.example.smarty.protocol.AgentEvent.Question -> {
                        val clarificationRequest = com.example.smarty.core.domain.model.ClarificationRequest(
                            question = event.question,
                            options = event.options,
                            allowCustomInput = event.allowCustom
                        )
                        currentStreamingMessage = currentStreamingMessage.copy(
                            clarificationRequest = clarificationRequest,
                            agentEvents = agentEventsBuilder.toList()
                        )
                        _chatState.update { it.copy(streamingMessage = currentStreamingMessage) }
                    }

                    // ── Errors ──
                    is com.example.smarty.protocol.AgentEvent.Error -> {
                        Log.e(TAG, "Agent error: ${event.message}")
                        responseBuilder.append("\n[Error: ${event.message}]")
                    }

                    // ── Approval flow ──
                    is com.example.smarty.protocol.AgentEvent.ApprovalRequested -> {
                        Log.i(TAG, "Approval requested: ${event.toolName} — pausing agent stream for user decision")
                        _pendingApprovalState.update {
                            PendingApproval(
                                messageId = streamingMessageId,
                                sessionId = sessionId,
                                eventId = event.eventId,
                                toolId = event.toolId,
                                toolName = event.toolName,
                                toolTitle = event.toolTitle,
                                toolArgs = event.toolArgs,
                            )
                        }
                        _chatState.update { state ->
                            state.copy(isProcessing = true, lastUpdated = System.currentTimeMillis())
                        }
                    }
                    is com.example.smarty.protocol.AgentEvent.ApprovalGranted -> {
                        Log.i(TAG, "Tool approved: ${event.toolId} — resuming stream")
                        _pendingApprovalState.update { null }
                        _chatState.update { state ->
                            state.copy(isProcessing = true, lastUpdated = System.currentTimeMillis())
                        }
                    }
                    is com.example.smarty.protocol.AgentEvent.ApprovalDenied -> {
                        Log.i(TAG, "Tool denied: ${event.toolId} — stream will continue without executing the tool")
                        _pendingApprovalState.update { null }
                    }
                    else -> {
                        Log.d(TAG, "Unhandled event: ${event::class.simpleName}")
                    }
                }
            }

            // Save message pair — use final accumulated message
            saveMessagePair(userMessage, currentStreamingMessage)

        } catch (e: Exception) {
            Log.e(TAG, "AI processing failed: ${e.message}", e)
            _chatState.update { 
                it.copy(
                    streamingMessage = null,
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
        
        // Notify server to interrupt LLM inference and tool execution (C3 fix)
        val sessionId = _chatState.value.currentSessionId
        if (sessionId != null) {
            viewModelScope.launch {
                try {
                    remoteAgentService.interruptSession(sessionId)
                    Log.d(TAG, "Sent interrupt signal to remote server for session: $sessionId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send interrupt signal", e)
                }
            }
        }
        
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
                val models = if (refreshed.isNotEmpty()) {
                    securePreferences.setCachedModels(refreshed)
                    refreshed
                } else {
                    com.example.smarty.features.chat.domain.state.DEFAULT_FREE_MODELS
                }
                _uiState.update { it.copy(availableModels = models) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh models: ${e.message}")
            }
        }
    }

    /**
     * Suspend function for UI to call directly for live model refresh.
     * Returns the refreshed model list, or fallback defaults if failed.
     */
    suspend fun refreshModelsNow(): List<Pair<String, String>> {
        return try {
            val refreshed = remoteAgentService.getOpencodeModels(refresh = true)
            val models = if (refreshed.isNotEmpty()) {
                securePreferences.setCachedModels(refreshed)
                refreshed
            } else {
                com.example.smarty.features.chat.domain.state.DEFAULT_FREE_MODELS
            }
            _uiState.update { it.copy(availableModels = models) }
            models
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh models: ${e.message}")
            com.example.smarty.features.chat.domain.state.DEFAULT_FREE_MODELS
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
