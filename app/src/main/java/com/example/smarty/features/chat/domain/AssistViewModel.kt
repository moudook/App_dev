package com.example.smarty.features.chat.domain

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.smarty.R
import com.example.smarty.core.common.util.AndroidLogger
import com.example.smarty.core.common.util.HistoryCompressor
import com.example.smarty.core.common.util.PrivacyGuard
import com.example.smarty.core.domain.model.*
import com.example.smarty.data.remote.RemoteAgentService
import com.example.smarty.data.repository.ChatRepository
import com.example.smarty.data.repository.DeviceAudioRepository
import com.example.smarty.data.repository.SmartyRepository
import com.example.smarty.data.state.SharedAppState
import com.example.smarty.di.ServiceLocator
import com.example.smarty.features.audio.domain.AudioFeatureManager
import com.example.smarty.features.audio.domain.AudioPlaybackManager
import com.example.smarty.features.calendar.domain.CalendarFeatureManager
import com.example.smarty.features.chat.agent.AgentEventSink
import com.example.smarty.features.chat.agent.ClientCommandExecutor
import com.example.smarty.features.chat.agent.models.*
import com.example.smarty.features.chat.agent.transport.CommandTransport
import com.example.smarty.features.chat.agent.transport.LocalCommandTransport
import com.example.smarty.features.notes.domain.NoteOperationsManager
import com.example.smarty.features.search.domain.SearchFeatureManager
import com.example.smarty.features.system.domain.SystemFeatureManager
import com.example.smarty.protocol.AgentCommand
import com.example.smarty.service.CommandResult
import com.example.smarty.service.LocalCommandProcessor
import com.example.smarty.ui.components.ConnectionStatus
import com.example.smarty.viewmodel.managers.MemoryFeatureManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import com.example.smarty.data.local.AIConnection
import com.example.smarty.data.local.SecurePreferences


/**
 * ViewModel for AssistActivity - Handles Smarty overlay functionality
 */
class AssistViewModel(
    application: Application,
) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "AssistViewModel"
    }

    private val sharedAppState: SharedAppState by lazy { ServiceLocator.provideSharedAppState() }
    private val repository: SmartyRepository by lazy { ServiceLocator.provideRepository(application) }
    private val chatRepository: ChatRepository by lazy { ServiceLocator.provideChatRepository(application) }
    private val deviceAudioRepository: DeviceAudioRepository by lazy { ServiceLocator.provideDeviceAudioRepository(application) }
    private val systemFeatureManager: SystemFeatureManager by lazy { ServiceLocator.provideSystemFeatureManager(application) }
    private val settingsFeatureManager: com.example.smarty.features.settings.domain.SettingsFeatureManager by lazy { ServiceLocator.provideSettingsFeatureManager(application) }
    private val searchFeatureManager: SearchFeatureManager by lazy { ServiceLocator.provideSearchFeatureManager(application) }
    private val calendarFeatureManager: CalendarFeatureManager by lazy { ServiceLocator.provideCalendarFeatureManager(application) }
    private val audioFeatureManager: AudioFeatureManager by lazy { ServiceLocator.provideAudioFeatureManager(application) }
    private val memoryFeatureManager: MemoryFeatureManager by lazy { ServiceLocator.provideMemoryFeatureManager(application) }
    private val noteOperationsManager: NoteOperationsManager by lazy { ServiceLocator.provideNoteOperationsManager(application) }
    private val styleFeatureManager: StyleFeatureManager by lazy { ServiceLocator.provideStyleFeatureManager() }
    private val workflowManager: WorkflowManager by lazy { ServiceLocator.provideWorkflowManager(application) }

    private val chatManager: ChatManager by lazy {
        ChatManager(application, chatRepository, viewModelScope, HistoryCompressor(AndroidLogger()))
    }

    private val localCommandProcessor: LocalCommandProcessor by lazy {
        LocalCommandProcessor(
            context = application,
            getNotes = { _notes.value },
            getActiveNoteId = { null },
            systemFeatureManager = systemFeatureManager,
            getDeviceAudio = { deviceAudioRepository.getAllAudio() },
        )
    }

    private val commandTransport: CommandTransport by lazy {
        LocalCommandTransport(clientCommandExecutor, viewModelScope)
    }

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    private val _archivedNotes = MutableStateFlow<List<Note>>(emptyList())
    private val _categories = MutableStateFlow<List<Category>>(emptyList())

    private val pendingCitations = java.util.concurrent.CopyOnWriteArrayList<Citation>()
    private val pendingInlineImages = java.util.concurrent.CopyOnWriteArrayList<InlineChatImage>()
    private val pendingToolCalls = java.util.concurrent.CopyOnWriteArrayList<AgentToolCallEntry>()

    private var currentStreamingJob: Job? = null

    private val agentEventSink = object : AgentEventSink {
        override fun onToolExecutionStarted(toolName: String, toolDisplayName: String) {}
        override fun onToolExecutionCompleted(toolName: String) {}
        override fun onStatusUpdate(status: String) {}
        override fun onCitationsFound(citations: List<WebCitation>) {
            pendingCitations.addAll(citations.map { Citation(it.title, it.url, it.snippet) })
        }
        override fun onDisplayImages(images: List<ImageDisplayItem>) {
            pendingInlineImages.clear()
            pendingInlineImages.addAll(images.map { InlineChatImage(uri = it.uri, fileName = it.fileName, noteTitle = it.noteTitle) })
        }
        override fun onPlanStatusChanged(status: String?) {}
        override fun onStateSync(syncType: String, data: String) {}
        override fun emit(command: AgentCommand) {
            when (command) {
                is AgentCommand.NotifyCitations -> {
                    onCitationsFound(command.citations.map { WebCitation(it.title, it.url, it.snippet) })
                }
                else -> {
                    viewModelScope.launch {
                        val result = commandTransport.dispatch(command)
                        if (result != null) {
                            val sessionId = chatManager.currentSessionId.value ?: "unknown"
                            remoteAgentService.sendEvent(sessionId, result)
                            processRemoteQuery("", ChatMessage(id = "continuation", role = ChatRole.USER, content = "", timestamp = System.currentTimeMillis()))
                        }
                    }
                }
            }
        }
    }

    private val clientCommandExecutor = object : ClientCommandExecutor {
        override fun getActiveNotes(): List<Note> = PrivacyGuard.getAiVisibleNotes(_notes.value)
        override fun getArchivedNotes(): List<Note> = PrivacyGuard.getAiVisibleNotes(_archivedNotes.value)
        override fun getCategories(): List<Category> = _categories.value
        override fun getScreenContext(): ScreenContext? = null
        override suspend fun getDeviceAudio(): List<AudioTrack> = deviceAudioRepository.getAllAudio()
        override fun getCurrentScreen(): String = "assist_overlay"
        override fun getSystemStatus(): Map<String, String> = emptyMap()
        override fun addNote(content: String, category: String?) {
            noteOperationsManager.addNote(content = content, initialCategory = category)
        }
        override fun updateNote(noteId: String, title: String?, content: String?) {
            viewModelScope.launch { noteOperationsManager.updateNote(noteId, title, content, _notes.value, _archivedNotes.value) }
        }
        override fun deleteNoteById(noteId: String) {
            viewModelScope.launch { noteOperationsManager.deleteNoteById(noteId, _notes.value, _archivedNotes.value) }
        }
        override fun archiveNote(noteId: String) { noteOperationsManager.archiveNote(noteId) }
        override fun unarchiveNote(noteId: String) { noteOperationsManager.unarchiveNote(noteId) }
        override fun summarizeNote(noteId: String) { noteOperationsManager.summarizeNote(noteId, _notes.value, _archivedNotes.value) }
        override suspend fun processNoteWithAi(note: Note) { noteOperationsManager.processNoteWithAi(note) }
        override fun addTodoToNote(noteId: String, text: String) { viewModelScope.launch { noteOperationsManager.addTodoToNote(noteId, text) } }
        override suspend fun onCreateCategory(name: String): Category = noteOperationsManager.getOrCreateCategory(name)
        override suspend fun getCategoryStats(): List<CategoryStatInfo> = noteOperationsManager.getCategoryStats(_categories.value, _notes.value + _archivedNotes.value)
        override fun launchApp(packageName: String) { systemFeatureManager.launchApp(packageName) }
        override fun findPackageName(appName: String): String? = systemFeatureManager.findPackageName(appName)
        override fun navigateTo(screen: String) { systemFeatureManager.navigateTo(screen) }
        override fun shareContent(text: String, title: String?) { systemFeatureManager.shareContent(text, title) }
        override fun toggleTheme(isDark: Boolean) { systemFeatureManager.toggleTheme(isDark) }
        override suspend fun toggleSetting(setting: String, enable: Boolean) { systemFeatureManager.toggleSetting(setting, enable) }
        override suspend fun takeScreenshot(save: Boolean) { systemFeatureManager.captureScreen() }
        override fun clearCache() { systemFeatureManager.clearCache() }
        override fun backupData() { systemFeatureManager.backupData() }
        override fun setPrivacyMode(mode: String) { systemFeatureManager.setPrivacyMode(mode) }
        override suspend fun storeContext(content: String, type: String) { memoryFeatureManager.storeMemory(content, type) }
        override suspend fun updateContext(id: String, content: String, type: String) { memoryFeatureManager.deleteMemory(id); memoryFeatureManager.storeMemory(content, type) }
        override suspend fun deleteContext(id: String) { memoryFeatureManager.deleteMemory(id) }
        override suspend fun searchNotes(query: String, category: String?, noteType: String?, timeRange: String, limit: Int): List<SearchResultItem> = searchFeatureManager.search(query = query, category = category, noteType = noteType, timeRange = timeRange, limit = limit)
        override suspend fun advancedSearch(query: String, algorithm: String, limit: Int, minScore: Double): List<SearchResultItem> = searchFeatureManager.advancedSearch(query, algorithm, limit, minScore)
        override fun analyzeQuery(query: String): SearchQueryAnalysis = searchFeatureManager.analyzeQuery(query)
        override suspend fun performRecall(query: String, minScore: Double): List<RecallResult> = searchFeatureManager.performRecall(query, minScore)
        override fun requestAudioPlayback(track: AudioTrack) { audioFeatureManager.play(track) }
        override fun playAudioList(tracks: List<AudioTrack>) { audioFeatureManager.playList(tracks) }
        override fun pauseAudioPlayback() { audioFeatureManager.pause() }
        override fun resumeAudioPlayback() { audioFeatureManager.resume() }
        override fun stopAudioPlayback() { audioFeatureManager.stop() }
        override fun seekAudioTo(positionMs: Long) { audioFeatureManager.seekTo(positionMs) }
        override fun nextTrack() { audioFeatureManager.next() }
        override fun previousTrack() { audioFeatureManager.previous() }
        override fun toggleAudioPlayback() { audioFeatureManager.togglePlayPause() }
        override fun getCurrentAudioTrack(): AudioTrack? = audioFeatureManager.getCurrentTrack()
        override fun getCurrentAudioPosition(): Long = audioFeatureManager.getCurrentPosition()
        override fun getAudioDuration(): Long = audioFeatureManager.getDuration()
        override fun isAudioPlaying(): Boolean = audioFeatureManager.isPlaying()
        override suspend fun findMatchingAudio(query: String): AudioFeatureManager.AudioSearchResult = audioFeatureManager.findAudioTrack(query)
        override suspend fun controlAudio(action: String) {
            when (action.lowercase()) {
                "play", "resume" -> audioFeatureManager.resume()
                "pause" -> audioFeatureManager.pause()
                "stop" -> audioFeatureManager.stop()
                "next" -> audioFeatureManager.next()
                "previous" -> audioFeatureManager.previous()
            }
        }
        override suspend fun seekAudio(positionMs: Long) { audioFeatureManager.seekTo(positionMs) }
        override fun addCalendarEvent(title: String, startTimeStr: String, endTimeStr: String?, description: String?, location: String?, isPrivate: Boolean) {
            val startMillis = calendarFeatureManager.parseDateTime(startTimeStr) ?: return
            val endMillis = endTimeStr?.let { calendarFeatureManager.parseDateTime(it) } ?: (startMillis + 3600000L)
            calendarFeatureManager.addCalendarEvent(title, description, startMillis, endMillis, false, location, null, null, isPrivate)
        }
        override suspend fun scheduleEvent(title: String, startTime: Long, endTime: Long, description: String?) { calendarFeatureManager.addCalendarEvent(title, description, startTime, endTime) }
        override suspend fun listEvents(date: Long): List<CalendarEvent> = calendarFeatureManager.getEventsForDay(date)
        override suspend fun deleteEvent(eventId: String) { calendarFeatureManager.deleteCalendarEvent(eventId) }
        override fun deleteCalendarEvent(eventId: String) { calendarFeatureManager.deleteCalendarEvent(eventId) }
        override suspend fun queryCalendarEvents(query: String?): List<CalendarEvent> = if (query.isNullOrBlank()) calendarFeatureManager.getTodayEvents() else calendarFeatureManager.searchEvents(query)
        override fun bulkDeleteEvents(eventIds: List<String>) { eventIds.forEach { calendarFeatureManager.deleteCalendarEvent(it) } }
        override fun setTimer(name: String, timeStr: String, isAlarm: Boolean, repeat: String?, triggerTime: Long?) {
            val finalTriggerTime = triggerTime ?: calendarFeatureManager.parseDateTime(timeStr) ?: return
            calendarFeatureManager.setTimer(name, finalTriggerTime, isAlarm, repeat)
        }
        override fun listTimers() { /* No-op on thin client */ }
        override fun cancelTimer(timerId: String) { calendarFeatureManager.cancelTimer(timerId) }
        override fun bulkArchiveNotes(noteIds: List<String>) { noteOperationsManager.bulkArchiveNotes(noteIds) }
        override fun bulkDeleteNotes(noteIds: List<String>) { noteOperationsManager.bulkDeleteNotes(noteIds, _notes.value, _archivedNotes.value) }
        override fun bulkMoveToCategory(noteIds: List<String>, categoryName: String) { noteOperationsManager.bulkMoveToCategory(noteIds, categoryName) }
    }

    private val remoteAgentService: RemoteAgentService by lazy {
        val client = HttpClient(OkHttp) {
            install(SSE) { reconnectionTime = 5.seconds }
            engine {
                config {
                    connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                    writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        }
        val securePrefs = SecurePreferences.getInstance(application)
        RemoteAgentService(
            client = client,
            eventSink = agentEventSink,
            serverUrlProvider = { settingsFeatureManager.getSmartyServerUrl() },
            deviceIdProvider = { securePrefs.getDeviceId() },
        )
    }

    val messages: StateFlow<List<ChatMessage>> = chatManager.chatMessages
    val isChatMode: StateFlow<Boolean> = chatManager.isChatMode

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _assistContext = MutableStateFlow<AssistContext?>(null)
    val assistContext: StateFlow<AssistContext?> = _assistContext.asStateFlow()

    data class AssistContext(val selectedText: String?, val referringPackage: String)

    val isDarkTheme: StateFlow<Boolean> = settingsFeatureManager.isDarkTheme
    val connectionStatus: StateFlow<ConnectionStatus> = sharedAppState.connectionStatus



    private val _isImageGenMode = MutableStateFlow(false)
    val isImageGenMode: StateFlow<Boolean> = _isImageGenMode.asStateFlow()

    private val securePreferences: SecurePreferences by lazy {
        SecurePreferences.getInstance(getApplication())
    }

    val selectedModel: StateFlow<String> = securePreferences.selectedModelFlow
    val availableModels: StateFlow<List<Pair<String, String>>> = securePreferences.availableModelsFlow

    fun toggleImageGenMode() {
        _isImageGenMode.value = !_isImageGenMode.value
    }

    fun selectModel(modelId: String) {
        Log.d(TAG, "Model selected in Assist: $modelId")
        securePreferences.setSelectedModel(AIConnection.LOCAL_PC, modelId)
    }

    suspend fun refreshModelsNow(): List<Pair<String, String>> {
        return try {
            val refreshed = remoteAgentService.getOpencodeModels(refresh = true)
            if (refreshed.isNotEmpty()) {
                securePreferences.setCachedModels(refreshed)
                refreshed
            } else {
                securePreferences.getAvailableModels(AIConnection.LOCAL_PC)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh models in Assist: ${e.message}")
            securePreferences.getAvailableModels(AIConnection.LOCAL_PC)
        }
    }

    init {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Fetching dynamic models from server in Assist...")
                val dynamicModels = remoteAgentService.getOpencodeModels(refresh = true)
                Log.d(TAG, "Server returned ${dynamicModels.size} models in Assist: $dynamicModels")
                
                if (dynamicModels.isNotEmpty()) {
                    securePreferences.setCachedModels(dynamicModels)
                    
                    val currentModel = securePreferences.getSelectedModel(AIConnection.LOCAL_PC)
                    val activeModel = if (dynamicModels.any { it.first == currentModel }) {
                        currentModel
                    } else {
                        val defaultModel = dynamicModels.first().first
                        securePreferences.setSelectedModel(AIConnection.LOCAL_PC, defaultModel)
                        defaultModel
                    }
                    Log.d(TAG, "Models updated in Assist: selected=$activeModel, available=${dynamicModels.size}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize opencode models in Assist: ${e.message}", e)
            }
        }

        viewModelScope.launch {
            chatManager.enterChatMode()
            _notes.value = noteOperationsManager.getAllNotes().first()
            _archivedNotes.value = noteOperationsManager.getArchivedNotes().first()
            _categories.value = noteOperationsManager.getAllCategories().first()
        }
    }

    fun sendMessage(content: String, attachments: List<Attachment> = emptyList()) {
        if (content.isBlank() && attachments.isEmpty()) return
        currentStreamingJob?.cancel()
        currentStreamingJob = viewModelScope.launch {
            chatManager.resetApiCallFlag()
            _isProcessing.value = true
            pendingCitations.clear()
            chatManager.ensureSession()
            val finalContent = content
            val userMessage = chatManager.addUserMessage(finalContent, attachments)
            
            val commandResult = localCommandProcessor.process(content)
            if (commandResult is CommandResult.Handled) {
                chatManager.markApiCallSuccessful()
                addSmartyMessage(ChatMessage(id = java.util.UUID.randomUUID().toString(), role = ChatRole.SMARTY, content = commandResult.response, timestamp = System.currentTimeMillis()), userMessage)
            } else {
                processRemoteQuery(content, userMessage)
            }
            _isProcessing.value = false
        }
    }

    fun generateImageDirect(prompt: String, aspectRatio: String = "1:1") {
        if (prompt.isBlank()) return
        currentStreamingJob?.cancel()
        currentStreamingJob = viewModelScope.launch {
            chatManager.resetApiCallFlag()
            val userMessage = chatManager.addUserMessage(prompt)
            _isProcessing.value = true
            val streamingMessageId = java.util.UUID.randomUUID().toString()
            chatManager.addSmartyMessage(ChatMessage(id = streamingMessageId, role = ChatRole.SMARTY, content = "", timestamp = System.currentTimeMillis(), isStreaming = true, toolCalls = listOf(AgentToolCallEntry(toolName = "generate_image", status = "started", displayName = "Direct Request", inputSummary = prompt))))
            val result = remoteAgentService.generateImageDirect(prompt, aspectRatio)
            if (result != null && result.success) {
                chatManager.replaceMessage(streamingMessageId, ChatMessage(id = streamingMessageId, role = ChatRole.SMARTY, content = "", timestamp = System.currentTimeMillis(), toolCalls = listOf(AgentToolCallEntry(toolName = "generate_image", status = "completed", displayName = "Direct Request", inputSummary = prompt, outputSummary = result.url))))
                chatManager.markApiCallSuccessful()
                chatManager.saveMessagePair(userMessage, ChatMessage(id = streamingMessageId, role = ChatRole.SMARTY, content = "Generated image for: $prompt", timestamp = System.currentTimeMillis(), toolCalls = listOf(AgentToolCallEntry(toolName = "generate_image", status = "completed", displayName = "Direct Request", inputSummary = prompt, outputSummary = result.url))))
            } else {
                val errorMsg = result?.error ?: result?.message ?: "Please try again."
                chatManager.replaceMessage(streamingMessageId, ChatMessage(id = streamingMessageId, role = ChatRole.SMARTY, content = "Failed to generate image: $errorMsg", timestamp = System.currentTimeMillis(), isError = true))
                chatManager.markApiCallSuccessful()
                chatManager.saveMessagePair(userMessage, ChatMessage(id = streamingMessageId, role = ChatRole.SMARTY, content = "Failed to generate image: $errorMsg", timestamp = System.currentTimeMillis(), isError = true))
            }
            _isProcessing.value = false
        }
    }

    private suspend fun processRemoteQuery(content: String, userMessage: ChatMessage) {
        pendingCitations.clear()
        pendingInlineImages.clear()
        pendingToolCalls.clear()
        val responseBuilder = StringBuilder()
        var finalThinking: String? = null
        val agentEventsBuilder = mutableListOf<com.example.smarty.protocol.AgentEvent>()
        val streamingMessageId = java.util.UUID.randomUUID().toString()
        chatManager.addSmartyMessage(ChatMessage(id = streamingMessageId, role = ChatRole.SMARTY, content = "", timestamp = System.currentTimeMillis(), isStreaming = true))

        try {
            val selectedModel = selectedModel.value
            remoteAgentService.sendQuery(content, sessionId = chatManager.currentSessionId.value, model = selectedModel).collect { event ->
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
                            sessionId = chatManager.currentSessionId.value ?: "unknown",
                            eventType = eventType,
                            payloadJson = payloadJson
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save timeline event: ${e.message}")
                }

                // 2) Accumulate in-memory for UI timeline
                agentEventsBuilder.add(event)

                when (event) {
                    is com.example.smarty.protocol.AgentEvent.Processing -> {
                        event.content?.let { responseBuilder.append(it) }
                        event.thinking?.let { finalThinking = it }
                        chatManager.updateMessageWithThinking(streamingMessageId, responseBuilder.toString(), event.thinking)
                    }
                    is com.example.smarty.protocol.AgentEvent.Result -> {
                        // Server sends empty content in Result to avoid duplication.
                        // The accumulated content from Processing events is the final answer.
                        finalThinking = event.thinking ?: finalThinking
                        chatManager.updateMessageWithThinking(streamingMessageId, responseBuilder.toString(), finalThinking)
                        if (event.citations.isNotEmpty()) pendingCitations.addAll(event.citations)
                    }
                    is com.example.smarty.protocol.AgentEvent.ToolCall -> {
                        pendingToolCalls.add(AgentToolCallEntry(toolName = event.toolName, status = event.status, displayName = event.displayName, inputSummary = event.inputSummary, outputSummary = event.outputSummary, timestamp = event.timestamp))
                    }
                    else -> {}
                }
            }
            val contentToSave = responseBuilder.toString()
            val smartyMessage = ChatMessage(
                id = streamingMessageId,
                role = ChatRole.SMARTY,
                content = contentToSave,
                toolCalls = pendingToolCalls.toList(),
                timestamp = System.currentTimeMillis(),
                citations = pendingCitations.toList(),
                isStreaming = false,
                agentEvents = agentEventsBuilder.toList()
            )
            chatManager.replaceMessage(streamingMessageId, smartyMessage)
            chatManager.markApiCallSuccessful()
            chatManager.saveMessagePair(userMessage, smartyMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Remote execution failed", e)
        }
    }

    private suspend fun addSmartyMessage(smartyMessage: ChatMessage, userMessage: ChatMessage) {
        chatManager.addSmartyMessage(smartyMessage)
        withContext(NonCancellable) { chatManager.saveMessagePair(userMessage, smartyMessage) }
    }

    fun setListening(listening: Boolean) { _isListening.value = listening }
    fun stopListening() { _isListening.value = false }
    fun stopGeneration() { currentStreamingJob?.cancel(); currentStreamingJob = null; _isProcessing.value = false }
    fun saveAndClose() { viewModelScope.launch { chatManager.exitChatMode() } }
    fun addMessage(message: ChatMessage) {
        viewModelScope.launch {
            if (message.role == ChatRole.USER) chatManager.addUserMessage(message.content, message.attachments)
            else chatManager.addSmartyMessage(message)
        }
    }
    fun clearMessages() { viewModelScope.launch { chatManager.createNewChatSession() } }
    fun setAssistContext(selectedText: String?, referringPackage: String) { _assistContext.value = AssistContext(selectedText, referringPackage) }
    fun addCitations(citations: List<WebCitation>) { pendingCitations.addAll(citations.map { Citation(it.title, it.url, it.snippet) }) }
    fun clearPendingCitations() { pendingCitations.clear() }
}

class AssistViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AssistViewModel(application) as T
    }
}
