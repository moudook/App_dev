package com.example.smarty.features.chat.domain

import android.app.Application
import android.util.Log
import com.example.smarty.R
import com.example.smarty.core.domain.model.AgentActionResult
import com.example.smarty.core.domain.model.AgentToolCallEntry
import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.ChatRole
import com.example.smarty.core.domain.model.Citation
import com.example.smarty.core.domain.model.ClarificationRequest
import com.example.smarty.service.CommandResult
import com.example.smarty.features.chat.agent.models.ImageDisplayItem
import com.example.smarty.features.chat.agent.models.WebCitation
import com.example.smarty.data.repository.ChatRepository
import com.example.smarty.data.repository.SmartyRepository
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.core.domain.model.MentionState
import com.example.smarty.features.chat.domain.state.PendingApproval
import com.example.smarty.features.chat.domain.thinking.ThinkingParser
import com.example.smarty.features.settings.domain.SettingsFeatureManager
import com.example.smarty.features.system.domain.SystemFeatureManager
import com.example.smarty.protocol.AgentEvent
import com.example.smarty.data.remote.RemoteAgentService
import com.example.smarty.service.LocalCommandProcessor
import com.example.smarty.core.common.util.CompletionSoundManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class ChatQueryDispatcher(
    private val scope: CoroutineScope,
    private val application: Application,
    private val chatManager: ChatManager,
    private val remoteAgentService: RemoteAgentService,
    private val localCommandProcessor: LocalCommandProcessor,
    private val systemFeatureManager: SystemFeatureManager,
    private val repository: SmartyRepository,
    private val chatRepository: ChatRepository,
    private val securePreferences: SecurePreferences,
    private val settingsFeatureManager: SettingsFeatureManager,
    private val completionSoundManager: CompletionSoundManager,
    private val currentSessionId: StateFlow<String?>,
    private val _agentActivity: MutableStateFlow<ChatFeatureManager.AgentActivity?>,
    private val _pendingChatText: MutableStateFlow<String?>,
    private val _mentionState: MutableStateFlow<MentionState>,
    private val _pendingApprovalState: MutableStateFlow<PendingApproval?>,
    private val _pendingClarificationRequests: MutableStateFlow<List<ClarificationRequest>>,
    private val pendingActions: CopyOnWriteArrayList<AgentActionResult>,
    private val pendingCitations: MutableList<WebCitation>,
    private val pendingInlineImages: MutableList<ImageDisplayItem>,
    private val navigateTo: (String) -> Unit
) {
    companion object {
        private const val TAG = "ChatQueryDispatcher"
    }

    private var currentStreamingJob: Job? = null
    private val noteTagRegex = "<note_([a-zA-Z0-9-]+)>".toRegex()
    private val eventTagRegex = "<event_([a-zA-Z0-9-]+)>".toRegex()

    fun callApproval(
        toolId: String,
        approved: Boolean,
        feedback: String? = null,
    ) {
        val current = _pendingApprovalState.value
        if (current == null) {
            Log.w(TAG, "callApproval: no pending approval")
            return
        }
        if (current.toolId != toolId) {
            Log.w(TAG, "callApproval: toolId mismatch — $toolId vs ${current.toolId}")
            return
        }
        Log.i(TAG, ">>> CALL_APPROVAL: toolId=$toolId, approved=$approved")
        scope.launch {
            try {
                remoteAgentService.sendApproval(toolId, approved, feedback)
                Log.i(TAG, ">>> CALL_APPROVAL: sent successfully")
                _pendingApprovalState.value = null
            } catch (e: Exception) {
                Log.e(TAG, "callApproval failed: ${e.message}", e)
            }
        }
    }

    fun generateImageDirect(
        prompt: String,
        aspectRatio: String = "1:1",
    ) {
        if (prompt.isBlank()) return

        scope.launch {
            try {
                chatManager.setProcessing(true)
                chatManager.ensureSession()

                val userMessage = chatManager.addUserMessage("Generate image: $prompt")

                val streamingMessageId = java.util.UUID.randomUUID().toString()
                chatManager.addSmartyMessage(
                    ChatMessage(
                        id = streamingMessageId,
                        role = ChatRole.SMARTY,
                        content = "",
                        timestamp = System.currentTimeMillis(),
                        isStreaming = true,
                        toolCalls = listOf(
                            AgentToolCallEntry(
                                toolName = "generate_image",
                                status = "started",
                                displayName = "Direct Request",
                                inputSummary = prompt,
                            ),
                        ),
                    ),
                )

                _agentActivity.value = ChatFeatureManager.AgentActivity(
                    type = ChatFeatureManager.AgentActivity.Type.TOOL_RUNNING,
                    displayText = "Generating image...",
                    toolName = "generate_image",
                )

                val result = remoteAgentService.generateImageDirect(prompt, aspectRatio)

                _agentActivity.value = null

                if (result != null && result.success) {
                    val smartyMessage = ChatMessage(
                        id = streamingMessageId,
                        role = ChatRole.SMARTY,
                        content = "",
                        timestamp = System.currentTimeMillis(),
                        toolCalls = listOf(
                            AgentToolCallEntry(
                                toolName = "generate_image",
                                status = "completed",
                                displayName = "Direct Request",
                                inputSummary = prompt,
                                outputSummary = result.url,
                            ),
                        ),
                    )
                    chatManager.replaceMessage(streamingMessageId, smartyMessage)
                    chatManager.markApiCallSuccessful()
                    chatManager.saveMessagePair(
                        userMessage = userMessage,
                        smartyMessage = smartyMessage.copy(content = "Generated image for: $prompt"),
                    )
                } else {
                    val errorMsg = result?.error ?: result?.message ?: "Please try again."
                    val smartyMessage = ChatMessage(
                        id = streamingMessageId,
                        role = ChatRole.SMARTY,
                        content = "Image generation failed: $errorMsg",
                        timestamp = System.currentTimeMillis(),
                        isError = true,
                    )
                    chatManager.replaceMessage(streamingMessageId, smartyMessage)
                    chatManager.markApiCallSuccessful()
                    chatManager.saveMessagePair(
                        userMessage = userMessage,
                        smartyMessage = smartyMessage,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Image generation error: ${e.message}", e)
                _agentActivity.value = null

                val errorMessage = ChatMessage(
                    id = java.util.UUID.randomUUID().toString(),
                    role = ChatRole.SMARTY,
                    content = "Image generation failed: ${e.message}",
                    timestamp = System.currentTimeMillis(),
                )
                chatManager.addSmartyMessage(errorMessage)
                chatManager.markApiCallSuccessful()
                chatManager.saveMessagePair(
                    userMessage = chatManager.chatMessages.value.lastOrNull { it.isUser } ?: return@launch,
                    smartyMessage = errorMessage,
                )
            } finally {
                chatManager.setProcessing(false)
            }
        }
    }

    fun stopGeneration() {
        Log.d(TAG, "Stopping generation...")
        currentStreamingJob?.cancel()
        currentStreamingJob = null
        chatManager.setProcessing(false)
        _agentActivity.value = null
    }

    fun dispatchQuery(
        content: String,
        attachments: List<Attachment> = emptyList(),
    ) {
        if (content.isBlank() && attachments.isEmpty()) return

        _pendingChatText.value = ""
        currentStreamingJob?.cancel()

        currentStreamingJob = scope.launch {
            var processingSet = false
            try {
                try {
                    chatManager.setProcessing(true)
                    processingSet = true
                    chatManager.resetApiCallFlag()
                    chatManager.ensureSession()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to initialize chat processing: ${e.message}")
                    processingSet = false
                }

                val userMessage = chatManager.addUserMessage(content, attachments)

                val commandResult = localCommandProcessor.process(content)
                when (commandResult) {
                    is CommandResult.Handled -> {
                        chatManager.markApiCallSuccessful()
                        val smartyMessage = ChatMessage(
                            id = java.util.UUID.randomUUID().toString(),
                            role = ChatRole.SMARTY,
                            content = commandResult.response,
                            timestamp = System.currentTimeMillis(),
                        )
                        chatManager.addSmartyMessage(smartyMessage)
                        chatManager.saveMessagePair(
                            userMessage = userMessage,
                            smartyMessage = smartyMessage,
                        )
                        return@launch
                    }
                    is CommandResult.NavigateTo -> {
                        chatManager.markApiCallSuccessful()
                        navigateTo(commandResult.route)
                        val response = application.getString(R.string.navigating_success, commandResult.route)
                        val smartyMessage = ChatMessage(
                            id = java.util.UUID.randomUUID().toString(),
                            role = ChatRole.SMARTY,
                            content = response,
                            timestamp = System.currentTimeMillis(),
                        )
                        chatManager.addSmartyMessage(smartyMessage)
                        chatManager.saveMessagePair(
                            userMessage = userMessage,
                            smartyMessage = smartyMessage,
                        )
                        return@launch
                    }
                    is CommandResult.HandledAndPassToLLM -> {
                        val localMessage = ChatMessage(
                            id = java.util.UUID.randomUUID().toString(),
                            role = ChatRole.SMARTY,
                            content = commandResult.response,
                            timestamp = System.currentTimeMillis(),
                        )
                        chatManager.addSmartyMessage(localMessage)
                    }
                    is CommandResult.SavePageRequest -> {
                        systemFeatureManager.captureScreen()
                        chatManager.markApiCallSuccessful()
                        val response = application.getString(R.string.capturing_screenshot)
                        val smartyMessage = ChatMessage(
                            id = java.util.UUID.randomUUID().toString(),
                            role = ChatRole.SMARTY,
                            content = response,
                            timestamp = System.currentTimeMillis(),
                        )
                        chatManager.addSmartyMessage(smartyMessage)
                        chatManager.saveMessagePair(
                            userMessage = userMessage,
                            smartyMessage = smartyMessage,
                        )
                        return@launch
                    }
                    else -> Log.d(TAG, "Falling back to REASONING-PATH")
                }

                processRemoteQuery(content, userMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Error in dispatcher: ${e.message}", e)
                chatManager.addSmartyMessage(
                    ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        role = ChatRole.SMARTY,
                        content = application.getString(
                            R.string.error_prefix,
                            e.message ?: application.getString(R.string.unknown_error),
                        ),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } finally {
                _agentActivity.value = null
                if (processingSet) {
                    try {
                        withContext(NonCancellable) {
                            chatManager.setProcessing(false)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to reset processing state: ${e.message}")
                        try {
                            chatManager.setProcessing(false)
                        } catch (fallbackE: Exception) {
                            Log.e(TAG, "Complete failure to reset processing state: ${fallbackE.message}")
                        }
                    }
                }
            }
        }
    }

    private suspend fun extractAndStripInlineTags(
        builder: StringBuilder,
        messageId: String,
    ) {
        val content = builder.toString()
        var newContent = content
        var hasChanges = false

        noteTagRegex.findAll(content).forEach { matchResult ->
            val noteId = matchResult.groupValues[1]
            newContent = newContent.replace(matchResult.value, "")
            hasChanges = true
            val dbNote = repository.getNoteById(noteId)
            if (dbNote != null) {
                val noteRef = com.example.smarty.core.domain.model.NoteReference(
                    noteId = dbNote.id,
                    title = dbNote.title,
                    snippet = dbNote.summary ?: dbNote.content.take(100),
                    category = dbNote.categoryName,
                )
                chatManager.updateMessageNoteReferences(messageId, noteRef)
            }
        }

        eventTagRegex.findAll(content).forEach { matchResult ->
            val eventId = matchResult.groupValues[1]
            newContent = newContent.replace(matchResult.value, "")
            hasChanges = true
            val dbEvent = repository.getCalendarEventById(eventId)
            if (dbEvent != null) {
                val eventRef = com.example.smarty.core.domain.model.EventReference(
                    eventId = dbEvent.id,
                    title = dbEvent.title,
                    timeSnippet = "Planned Event",
                    description = dbEvent.description,
                )
                chatManager.updateMessageEventReferences(messageId, eventRef)
            }
        }

        if (hasChanges) {
            builder.clear()
            builder.append(newContent)
        }
    }

    private suspend fun processRemoteQuery(
        content: String,
        userMessage: ChatMessage,
    ) {
        pendingCitations.clear()
        pendingInlineImages.clear()
        pendingActions.clear()
        _mentionState.value = MentionState()
        chatManager.setProcessing(true)

        val streamingMessageId = java.util.UUID.randomUUID().toString()
        try {
            val streamingMessage = ChatMessage(
                id = streamingMessageId,
                role = ChatRole.SMARTY,
                content = "",
                timestamp = System.currentTimeMillis(),
                isStreaming = true,
            )
            chatManager.addSmartyMessage(streamingMessage)

            var isThinkingActive = false
            var isStreamingActive = false
            var finalReasoningText = ""
            var finalReasoningDurationMs: Long? = null
            var finalResponseText = ""
            val pendingToolCallsMap = LinkedHashMap<String, AgentToolCallEntry>()
            val orderedToolCallIds = mutableListOf<String>()
            val collectedAgentSteps = LinkedHashMap<Int, com.example.smarty.core.domain.model.AgentStepEntry>()
            val agentEventsBuilder = mutableListOf<com.example.smarty.protocol.AgentEvent>()

            val fallbackTextBuilder = StringBuilder()
            val fallbackThinkingBuilder = StringBuilder()

            fun currentToolCalls(): List<AgentToolCallEntry> =
                orderedToolCallIds.mapNotNull { pendingToolCallsMap[it] }

            fun currentSteps(): List<com.example.smarty.core.domain.model.AgentStepEntry> =
                collectedAgentSteps.values.sortedBy { it.stepIndex }

            // ── 50ms debounce: one Room write per 50ms window instead of per-token ──
            // hasPendingFlush is set by every event that mutates skeleton state.
            // The flushJob polls every 50ms and fires updateMessageSkeleton only when dirty.
            val hasPendingFlush = AtomicBoolean(false)
            val flushJob = scope.launch {
                while (isActive) {
                    delay(50L)
                    if (hasPendingFlush.getAndSet(false)) {
                        val currentText = fallbackTextBuilder.toString()
                        val currentThinking = fallbackThinkingBuilder.toString()
                        chatManager.updateMessageSkeleton(
                            streamingMessageId,
                            isThinking = isThinkingActive,
                            isStreaming = isStreamingActive,
                            content = currentText,
                            thinking = currentThinking.ifEmpty { null },
                            toolCalls = currentToolCalls(),
                            steps = currentSteps(),
                            agentEvents = agentEventsBuilder.toList(),
                        )
                    }
                }
            }

            // Mark dirty — the flush job picks it up on next 50ms tick.
            // Terminal events (Done, Error) must call flushNow() to guarantee
            // the final state is written before the job is cancelled.
            fun pushSkeleton() {
                hasPendingFlush.set(true)
            }

            suspend fun flushNow() {
                hasPendingFlush.set(false)
                val currentText = fallbackTextBuilder.toString()
                val currentThinking = fallbackThinkingBuilder.toString()
                chatManager.updateMessageSkeleton(
                    streamingMessageId,
                    isThinking = isThinkingActive,
                    isStreaming = isStreamingActive,
                    content = currentText,
                    thinking = currentThinking.ifEmpty { null },
                    toolCalls = currentToolCalls(),
                    steps = currentSteps(),
                    agentEvents = agentEventsBuilder.toList(),
                )
            }

            fun pushBlocks() {
                val rawFallback = fallbackTextBuilder.toString()
                val hasCleanResponse = finalResponseText.isNotBlank()
                val hasCleanReasoning = finalReasoningText.isNotBlank()

                val contentFinal = when {
                    hasCleanResponse -> finalResponseText
                    ThinkingParser.hasThinking(rawFallback) -> ThinkingParser.extractAnswer(rawFallback)
                    else -> rawFallback
                }

                val thinking = when {
                    hasCleanReasoning -> finalReasoningText
                    else -> fallbackThinkingBuilder.toString().ifEmpty {
                        if (ThinkingParser.hasThinking(rawFallback)) ThinkingParser.extractThinking(rawFallback) else null
                    }
                }

                if (contentFinal.isBlank() && thinking.isNullOrBlank()) return

                scope.launch {
                    chatManager.updateMessageContent(
                        streamingMessageId,
                        content = contentFinal.trim(),
                        thinking = thinking?.trim(),
                    )
                }
            }

            val sessionId = currentSessionId.value
            val selectedModel = securePreferences.getSelectedModel(com.example.smarty.data.local.AIConnection.LOCAL_PC)
            val selectedVariant = securePreferences.getSelectedVariant()
            val sectionName = if (chatManager.isChatMode.value) "chat" else "notes"
            remoteAgentService
                .sendQuery(
                    query = content,
                    sessionId = sessionId,
                    model = selectedModel,
                    variant = selectedVariant,
                    messageId = streamingMessageId,
                    section = sectionName,
                ).collect { event ->
                    agentEventsBuilder.add(event)
                    Log.d(TAG, ">>> EVENT: ${event::class.simpleName}")
                    when (event) {
                        is AgentEvent.ThinkingActive -> {
                            isThinkingActive = true
                            pushSkeleton()
                        }
                        is AgentEvent.StreamingActive -> {
                            isStreamingActive = true
                            pushSkeleton()
                        }
                        is AgentEvent.ReasoningBlock -> {
                            finalReasoningText = event.content
                            finalReasoningDurationMs = event.thinkingDurationMs
                            val lastThinkingKey = collectedAgentSteps.entries
                                .filter { it.value.stepType == "thinking" }
                                .maxByOrNull { it.key }
                                ?.key
                            if (lastThinkingKey != null) {
                                collectedAgentSteps[lastThinkingKey] = collectedAgentSteps[lastThinkingKey]!!
                                    .copy(stepStatus = "completed", stepContent = event.content)
                            }
                            pushBlocks()
                        }
                        is AgentEvent.ResponseBlock -> {
                            finalResponseText = event.content
                            pushBlocks()
                        }
                        is AgentEvent.TextDelta -> {
                            fallbackTextBuilder.append(event.text)
                            if (!isStreamingActive && finalResponseText.isEmpty()) {
                                isStreamingActive = true
                            }
                            pushSkeleton()
                        }
                        is AgentEvent.ReasoningDelta -> {
                            val thinkingKey = collectedAgentSteps.entries
                                .filter { it.value.stepType == "thinking" && it.value.stepStatus == "started" }
                                .maxByOrNull { it.key }
                                ?.key
                        
                            if (thinkingKey != null) {
                                val existing = collectedAgentSteps[thinkingKey]!!
                                collectedAgentSteps[thinkingKey] = existing.copy(
                                    stepContent = existing.stepContent + event.text
                                )
                            } else {
                                val stepIdx = collectedAgentSteps.size
                                collectedAgentSteps[stepIdx] = com.example.smarty.core.domain.model.AgentStepEntry(
                                    stepType = "thinking",
                                    stepTitle = "Thinking",
                                    stepContent = event.text,
                                    stepStatus = "started",
                                    stepIndex = stepIdx,
                                )
                            }
                        
                            if (!isThinkingActive && finalReasoningText.isEmpty()) {
                                isThinkingActive = true
                            }
                            pushSkeleton()
                        }
                        is AgentEvent.ToolStart -> {
                            val openThinkingKey = collectedAgentSteps.entries
                                .filter { it.value.stepType == "thinking" && it.value.stepStatus == "started" }
                                .maxByOrNull { it.key }
                                ?.key
                            if (openThinkingKey != null) {
                                val thinkingStep = collectedAgentSteps[openThinkingKey]!!
                                if (thinkingStep.stepContent.isBlank()) {
                                    collectedAgentSteps.remove(openThinkingKey)
                                } else {
                                    collectedAgentSteps[openThinkingKey] = thinkingStep.copy(stepStatus = "completed")
                                }
                            }

                            val existing = pendingToolCallsMap[event.toolId]
                            val finalStatus = if (existing?.status == "waiting_user") "waiting_user" else "started"

                            val entry = AgentToolCallEntry(
                                toolName = event.name,
                                displayName = event.name.replace('_', ' ').replaceFirstChar { it.uppercase() },
                                status = finalStatus,
                                inputSummary = event.args ?: event.inputSummary.ifEmpty { null },
                                outputSummary = null,
                                toolCallId = event.toolId,
                                isMcpTool = event.isMcpTool,
                                isInteractive = event.isInteractive,
                                startedAt = event.timestamp,
                            )
                            val isNewTool = !pendingToolCallsMap.containsKey(event.toolId)
                            if (isNewTool) {
                                orderedToolCallIds.add(event.toolId)
                                val stepIdx = collectedAgentSteps.size
                                collectedAgentSteps[stepIdx] = com.example.smarty.core.domain.model.AgentStepEntry(
                                    stepType = "tool_call",
                                    stepTitle = entry.displayName,
                                    stepContent = "",
                                    stepStatus = "started",
                                    stepIndex = stepIdx,
                                )
                            }
                            pendingToolCallsMap[event.toolId] = entry
                            
                            val actionResult = AgentActionResult(
                                action = event.name,
                                success = true,
                                resultSummary = "Running ${event.name}…",
                            )
                            pendingActions.removeAll { it.action == event.name }
                            pendingActions.add(actionResult)
                            pushSkeleton()
                        }
                        is AgentEvent.ToolEnd -> {
                            pendingToolCallsMap[event.toolId]?.let { existing ->
                                val summary = event.outputSummary.ifEmpty {
                                    event.result ?: event.error ?: ""
                                }
                                pendingToolCallsMap[event.toolId] = existing.copy(
                                    status = if (event.success) "completed" else "failed",
                                    outputSummary = summary,
                                    durationMs = existing.startedAt?.let { event.timestamp - it },
                                )
                                pendingActions.removeAll { it.action == existing.displayName }
                                pendingActions.add(
                                    AgentActionResult(
                                        action = existing.displayName,
                                        success = event.success,
                                        resultSummary = summary,
                                    )
                                )
                            }
                            pushSkeleton()
                        }
                        is AgentEvent.StepStart -> {
                            val isThinking = event.title.equals("Thinking", ignoreCase = true)
                        
                            if (isThinking) {
                                val alreadyOpen = collectedAgentSteps.values.any {
                                    it.stepType == "thinking" && it.stepStatus == "started"
                                }
                                if (!alreadyOpen) {
                                    val stepIdx = collectedAgentSteps.size
                                    collectedAgentSteps[stepIdx] = com.example.smarty.core.domain.model.AgentStepEntry(
                                        stepType = "thinking",
                                        stepTitle = "Thinking",
                                        stepContent = "",
                                        stepStatus = "started",
                                        stepIndex = stepIdx,
                                    )
                                }
                            } else {
                                val openThinkingKey = collectedAgentSteps.entries
                                    .filter { it.value.stepType == "thinking" && it.value.stepStatus == "started" }
                                    .maxByOrNull { it.key }
                                    ?.key
                                if (openThinkingKey != null) {
                                    val thinkingStep = collectedAgentSteps[openThinkingKey]!!
                                    if (thinkingStep.stepContent.isBlank()) {
                                        collectedAgentSteps.remove(openThinkingKey)
                                    } else {
                                        collectedAgentSteps[openThinkingKey] = thinkingStep.copy(stepStatus = "completed")
                                    }
                                }
                        
                                val existing = collectedAgentSteps.values.find {
                                    it.stepTitle == event.title && it.stepStatus == "started"
                                }
                                if (existing == null) {
                                    val stepIdx = collectedAgentSteps.size
                                    collectedAgentSteps[stepIdx] = com.example.smarty.core.domain.model.AgentStepEntry(
                                        stepType = "tool_call",
                                        stepTitle = event.title,
                                        stepContent = "",
                                        stepStatus = "started",
                                        stepIndex = stepIdx,
                                    )
                                }
                            }
                            pushSkeleton()
                        }
                        is AgentEvent.StepEnd -> {
                            val targetKey = if (collectedAgentSteps.isNotEmpty()) collectedAgentSteps.keys.maxOrNull() ?: -1 else -1
                            if (targetKey >= 0) {
                                val existing = collectedAgentSteps[targetKey]
                                if (existing != null) {
                                    collectedAgentSteps[targetKey] = existing.copy(
                                        stepStatus = if (event.success) "completed" else "failed",
                                    )
                                }
                            }
                            pushSkeleton()
                        }
                        is AgentEvent.ApprovalRequested -> {
                            Log.i(TAG, ">>> APPROVAL_REQUESTED: toolName=${event.toolName}, toolId=${event.toolId}")
                            val openThinkingKey = collectedAgentSteps.entries
                                .filter { it.value.stepType == "thinking" && it.value.stepStatus == "started" }
                                .maxByOrNull { it.key }
                                ?.key
                            if (openThinkingKey != null) {
                                val thinkingStep = collectedAgentSteps[openThinkingKey]!!
                                if (thinkingStep.stepContent.isBlank()) {
                                    collectedAgentSteps.remove(openThinkingKey)
                                } else {
                                    collectedAgentSteps[openThinkingKey] = thinkingStep.copy(stepStatus = "completed")
                                }
                            }

                            val entry = AgentToolCallEntry(
                                toolName = event.toolName,
                                displayName = event.toolName.replace('_', ' ').replaceFirstChar { it.uppercase() },
                                status = "waiting_user",
                                inputSummary = event.question,
                                outputSummary = null,
                                toolCallId = event.toolId,
                                isMcpTool = true,
                                isInteractive = true,
                                startedAt = event.timestamp,
                            )
                            val isNewTool = !pendingToolCallsMap.containsKey(event.toolId)
                            if (isNewTool) {
                                orderedToolCallIds.add(event.toolId)
                                val stepIdx = collectedAgentSteps.size
                                collectedAgentSteps[stepIdx] = com.example.smarty.core.domain.model.AgentStepEntry(
                                    stepType = "tool_call",
                                    stepTitle = entry.displayName,
                                    stepContent = "",
                                    stepStatus = "started",
                                    stepIndex = stepIdx,
                                )
                            }
                            pendingToolCallsMap[event.toolId] = entry
                            _pendingApprovalState.value = PendingApproval(
                                messageId = streamingMessageId,
                                sessionId = chatManager.currentSessionId.value,
                                eventId = event.eventId,
                                toolId = event.toolId,
                                toolName = event.toolName,
                                toolTitle = event.toolName.replace('_', ' ').replaceFirstChar { it.uppercase() },
                                toolArgs = org.json.JSONObject().apply {
                                    put("questions", org.json.JSONArray().apply {
                                        put(org.json.JSONObject().apply {
                                            put("question", event.question)
                                            put("options", org.json.JSONArray(event.options))
                                            put("allow_custom", true)
                                        })
                                    })
                                }.toString(),
                            )
                            if (event.interactive) {
                                val parsed = listOf(ClarificationRequest(
                                    question = event.question,
                                    options = event.options,
                                    allowCustomInput = true,
                                ))
                                _pendingClarificationRequests.value = parsed
                            }
                            pushSkeleton()
                        }
                        is AgentEvent.ApprovalResult -> {
                            Log.i(TAG, ">>> APPROVAL_RESULT: toolId=${event.toolId}, granted=${event.granted}")
                            _pendingApprovalState.value = null
                            _pendingClarificationRequests.value = emptyList()
                            pendingToolCallsMap[event.toolId]?.let { existing ->
                                pendingToolCallsMap[event.toolId] = existing.copy(
                                    status = if (event.granted) "started" else "declined",
                                    outputSummary = if (event.granted) "User: ${event.feedback}" else "Declined",
                                )
                            }
                            pushSkeleton()
                        }
                        is AgentEvent.Done -> {
                            Log.d(TAG, ">>> DONE: stream completed")
                            isThinkingActive = false
                            isStreamingActive = false
                            flushJob.cancel()
                            pushBlocks()
                            flushNow()
                        }
                        is AgentEvent.Error -> {
                            isThinkingActive = false
                            isStreamingActive = false
                            val cleanError = event.message
                                .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
                                .trim()
                            fallbackTextBuilder.append("\n[$cleanError]")
                            _pendingApprovalState.value = null
                            _pendingClarificationRequests.value = emptyList()
                            for (id in orderedToolCallIds) {
                                pendingToolCallsMap[id]?.let { entry ->
                                    if (entry.status == "started" || entry.status == "waiting_user") {
                                        pendingToolCallsMap[id] = entry.copy(status = "failed", outputSummary = "Session error")
                                    }
                                }
                            }
                            flushJob.cancel()
                            pushBlocks()
                            flushNow()
                        }
                        is AgentEvent.StateSync -> {}
                        is AgentEvent.SubAgentEvent -> {
                            // Render sub-agent task as a timeline step (agent/description/state fields)
                            val existingKey = collectedAgentSteps.entries
                                .find { it.value.stepTitle == event.agent && it.value.stepType == "sub_agent" }
                                ?.key
                            if (existingKey != null) {
                                collectedAgentSteps[existingKey] = collectedAgentSteps[existingKey]!!.copy(
                                    stepStatus = if (event.state == "running") "started" else event.state,
                                    stepContent = event.description,
                                )
                            } else {
                                val stepIdx = collectedAgentSteps.size
                                collectedAgentSteps[stepIdx] = com.example.smarty.core.domain.model.AgentStepEntry(
                                    stepType = "sub_agent",
                                    stepTitle = event.agent.ifBlank { "Sub-Agent" },
                                    stepContent = event.description,
                                    stepStatus = if (event.state == "running") "started" else event.state,
                                    stepIndex = stepIdx,
                                )
                            }
                            pushSkeleton()
                        }
                        is AgentEvent.CompactionMarker -> {}
                        is AgentEvent.DeviceCommand -> {
                            Log.d(TAG, ">>> DEVICE_COMMAND: action=${event.action}, app=${event.app}, setting=${event.setting}, on=${event.on}, info=${event.info}")
                            scope.launch {
                                var success = true
                                var resultMsg = "Success"
                                try {
                                    withContext(Dispatchers.IO) {
                                        withTimeout(10_000) {
                                            when (event.action) {
                                                "navigate" -> {
                                                    val info = event.info
                                                    if (info != null) {
                                                        withContext(Dispatchers.Main) { navigateTo(info) }
                                                        resultMsg = "Navigated to $info"
                                                    } else {
                                                        success = false
                                                        resultMsg = "Missing info for navigation"
                                                    }
                                                }
                                                "guided_breathing" -> {
                                                    withContext(Dispatchers.Main) { navigateTo("guided_breathing") }
                                                    resultMsg = "Started guided breathing"
                                                }
                                                "toggle" -> {
                                                    val setting = event.setting ?: "unknown"
                                                    val on = event.on ?: false
                                                    val toggledResult = systemFeatureManager.toggleSetting(setting, on)
                                                    if (toggledResult.isSuccess) {
                                                        resultMsg = toggledResult.getOrDefault("Toggled $setting to $on")
                                                    } else {
                                                        success = false
                                                        resultMsg = toggledResult.exceptionOrNull()?.message ?: "Failed to toggle $setting"
                                                    }
                                                }
                                                "launch" -> {
                                                    val app = event.app ?: ""
                                                    val launched = systemFeatureManager.launchApp(app)
                                                    if (launched) {
                                                        resultMsg = "Launched $app"
                                                    } else {
                                                        success = false
                                                        resultMsg = "Failed to launch $app"
                                                    }
                                                }
                                                "status" -> {
                                                    val battery = systemFeatureManager.getBatteryLevel()
                                                    resultMsg = "Battery: $battery"
                                                }
                                                "capture" -> {
                                                    systemFeatureManager.captureScreen()
                                                    resultMsg = "Captured screen"
                                                }
                                                else -> {
                                                    success = false
                                                    resultMsg = "Unknown action: ${event.action}"
                                                }
                                            }
                                        }
                                    }
                                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                    success = false
                                    resultMsg = "Hardware command hung on device (10s timeout)"
                                    Log.e(TAG, "Device command timed out", e)
                                } catch (e: Exception) {
                                    success = false
                                    resultMsg = e.message ?: "Unknown error"
                                    Log.e(TAG, "Error executing device command", e)
                                }
                                
                                remoteAgentService.sendEvent(
                                    sessionId = event.sessionId,
                                    event = com.example.smarty.protocol.ClientEvent.ToolResult(
                                        commandId = event.commandId,
                                        result = resultMsg,
                                        isError = !success,
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                        is AgentEvent.AskUserRequest -> {
                            // §2.2 DB-backed ask_user interactive session.
                            // Render question UI (options or open text), activate mic,
                            // then POST answers to /webhook/ask_user_response.
                            Log.i(TAG, ">>> ASK_USER_REQUEST: toolCallId=${event.toolCallId}, questions=${event.questions.size}")
                            // Update the streaming message to show the question
                            val firstQuestion = event.questions.firstOrNull()?.question ?: "Please answer:"
                            val options = event.questions.firstOrNull()?.options ?: emptyList()
                            val parsed = event.questions.map { q ->
                                ClarificationRequest(
                                    question = q.question,
                                    options = q.options,
                                    allowCustomInput = q.allowCustom,
                                )
                            }
                            _pendingClarificationRequests.value = parsed
                            _pendingApprovalState.value = PendingApproval(
                                messageId = streamingMessageId,
                                sessionId = chatManager.currentSessionId.value,
                                eventId = event.eventId,
                                toolId = event.toolCallId,
                                toolName = "ask_user",
                                toolTitle = "Question",
                                toolArgs = org.json.JSONObject().apply {
                                    put("questions", org.json.JSONArray().apply {
                                        event.questions.forEach { q ->
                                            put(org.json.JSONObject().apply {
                                                put("question", q.question)
                                                put("options", org.json.JSONArray(q.options))
                                                put("allow_custom", q.allowCustom)
                                            })
                                        }
                                    })
                                }.toString(),
                            )
                            // Flush so the question UI appears in the message
                            pushSkeleton()
                        }
                        is AgentEvent.LaunchUiRequest -> {
                            // §3.2 launch_ui: navigate to the requested screen and ACK back.
                            Log.i(TAG, ">>> LAUNCH_UI_REQUEST: commandId=${event.commandId}, intent=${event.intent}")
                            scope.launch {
                                var success = true
                                var resultMsg = "Launched ${event.intent}"
                                try {
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        navigateTo(event.intent)
                                    }
                                } catch (e: Exception) {
                                    success = false
                                    resultMsg = "Failed to launch ${event.intent}: ${e.message}"
                                    Log.e(TAG, "LaunchUiRequest failed", e)
                                }
                                try {
                                    remoteAgentService.submitLaunchResult(event.commandId, success, resultMsg)
                                } catch (e: Exception) {
                                    Log.e(TAG, "submitLaunchResult failed: ${e.message}", e)
                                }
                            }
                        }
                        is AgentEvent.ShareContentRequest -> {
                            // §3.1 share_content: trigger Android Share Sheet.
                            Log.i(TAG, ">>> SHARE_CONTENT_REQUEST: commandId=${event.commandId}, mimeType=${event.mimeType}")
                            scope.launch {
                                try {
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = event.mimeType
                                        putExtra(android.content.Intent.EXTRA_TEXT, event.content)
                                        event.title?.let { putExtra(android.content.Intent.EXTRA_SUBJECT, it) }
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    application.startActivity(
                                        android.content.Intent.createChooser(shareIntent, event.title ?: "Share")
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "ShareContentRequest failed: ${e.message}", e)
                                }
                            }
                        }
                        is AgentEvent.ImageReady -> {
                            // §3.1 image_ready: inject image into chat bubble.
                            Log.i(TAG, ">>> IMAGE_READY: imageId=${event.imageId}, messageId=${event.messageId}")
                            pendingInlineImages.removeAll { it.fileName == event.imageId }
                            pendingInlineImages.add(
                                com.example.smarty.features.chat.agent.models.ImageDisplayItem(
                                    uri = event.url,
                                    fileName = event.imageId,
                                    noteTitle = event.prompt ?: "",
                                )
                            )
                            pushSkeleton()
                        }
                        is AgentEvent.NoteProcessed -> {
                            // §5.2 background note processing complete — no visible UI change needed.
                            Log.d(TAG, ">>> NOTE_PROCESSED: noteId=${event.noteId}, success=${event.success}")
                        }
                        is AgentEvent.MemoryUpdated -> {
                            // §5.3 user profile updated — silent ACK.
                            Log.d(TAG, ">>> MEMORY_UPDATED: profileField=${event.profileField}")
                        }

                    }
                }

            chatManager.markApiCallSuccessful()

            val rawFallback = fallbackTextBuilder.toString()
            val hasCleanBlocks = finalResponseText.isNotEmpty()
            val finalThinking = if (finalReasoningText.isNotEmpty()) {
                finalReasoningText
            } else {
                fallbackThinkingBuilder.toString().ifEmpty {
                    if (ThinkingParser.hasThinking(rawFallback)) ThinkingParser.extractThinking(rawFallback) else null
                }
            }

            val finalContent = when {
                hasCleanBlocks -> finalResponseText
                ThinkingParser.hasThinking(rawFallback) -> ThinkingParser.extractAnswer(rawFallback)
                else -> rawFallback
            }.let { text ->
                if (text.isEmpty() && !finalThinking.isNullOrBlank()) {
                    ""
                } else {
                    text.ifEmpty { "[No response received. Please try again.]" }
                }
            }

            val smartyMessage = ChatMessage(
                id = streamingMessageId,
                role = ChatRole.SMARTY,
                content = finalContent,
                thinking = finalThinking,
                timestamp = System.currentTimeMillis(),
                executedActions = pendingActions.toList(),
                toolCalls = currentToolCalls(),
                agentSteps = collectedAgentSteps.values.toList(),
                citations = pendingCitations.toList().map { Citation(it.title, it.url, it.snippet) },
                inlineImages = pendingInlineImages.map { com.example.smarty.core.domain.model.InlineChatImage(uri = it.uri, fileName = it.fileName, noteTitle = it.noteTitle) },
                isStreaming = false,
                agentEvents = agentEventsBuilder.toList(),
                clarificationRequest = null,
                noteReferences = emptyList(),
            )

            chatManager.replaceMessage(streamingMessageId, smartyMessage)
            chatManager.saveMessagePair(userMessage, smartyMessage)

            if (collectedAgentSteps.isNotEmpty()) {
                try {
                    chatRepository.saveAgentSteps(streamingMessageId, collectedAgentSteps.values.toList())
                } catch (dbEx: Exception) {
                    Log.w(TAG, "Failed to persist agent steps: ${dbEx.message}")
                }
            }

            if (settingsFeatureManager.isSoundEnabled()) {
                completionSoundManager.playAgentCompletionSound(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Remote query execution failed", e)
            _pendingApprovalState.value = null
            _pendingClarificationRequests.value = emptyList()
            chatManager.updateMessageById(
                streamingMessageId,
                application.getString(R.string.error_prefix, e.message ?: "Connection error"),
            )
        }
    }
}
