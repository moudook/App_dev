package com.example.smarty.features.chat.domain

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.smarty.features.chat.agent.models.ScreenContext
import com.example.smarty.R
import com.example.smarty.features.chat.agent.AgentEventSink
import com.example.smarty.features.chat.agent.ClientCommandExecutor
import com.example.smarty.features.chat.agent.models.ImageDisplayItem
import com.example.smarty.features.chat.agent.models.WebCitation
import com.example.smarty.features.chat.agent.transport.CommandTransport
import com.example.smarty.features.chat.agent.transport.LocalCommandTransport
import com.example.smarty.protocol.AgentCommand
import com.example.smarty.features.audio.domain.AudioFeatureManager.AudioSearchResult
import com.example.smarty.core.domain.model.InlineChatImage
import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.AudioTrack
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.core.domain.model.Category
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.ChatRole
import com.example.smarty.core.domain.model.Citation
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.NoteType
import com.example.smarty.core.domain.model.SmartyTimer
import com.example.smarty.core.domain.model.TodoItem
import com.example.smarty.core.domain.model.getTodos
import com.example.smarty.data.remote.RemoteAgentService
import com.example.smarty.data.repository.ChatRepository
import com.example.smarty.data.repository.DeviceAudioRepository
import com.example.smarty.data.repository.SmartyRepository
import com.example.smarty.data.state.SharedAppState
import com.example.smarty.di.ServiceLocator
import com.example.smarty.core.domain.model.AttachmentMetadata
import com.example.smarty.data.remote.AIResponse
import com.example.smarty.service.AlarmScheduler
import com.example.smarty.service.LocalCommandProcessor
import com.example.smarty.service.CommandResult
import com.example.smarty.ui.components.ConnectionStatus
import com.example.smarty.core.common.util.AndroidLogger
import com.example.smarty.core.common.util.HistoryCompressor
import com.example.smarty.core.common.util.PrivacyGuard
import com.example.smarty.features.audio.domain.AudioPlaybackManager
import com.example.smarty.features.calendar.domain.CalendarManager
import com.example.smarty.core.domain.model.CategoryStatInfo
import com.example.smarty.features.chat.domain.ChatManager
import com.example.smarty.features.notes.domain.NoteOperationsManager
import com.example.smarty.core.domain.model.RecallResult
import com.example.smarty.features.search.domain.SearchFeatureManager
import com.example.smarty.core.domain.model.SearchQueryAnalysis
import com.example.smarty.core.domain.model.SearchResultItem
import com.example.smarty.features.system.domain.SystemFeatureManager
import com.example.smarty.features.chat.domain.StyleFeatureManager
import com.example.smarty.features.chat.domain.WorkflowManager
import com.example.smarty.features.audio.domain.AudioFeatureManager
import com.example.smarty.features.calendar.domain.CalendarFeatureManager
import com.example.smarty.viewmodel.managers.MemoryFeatureManager
import com.example.smarty.ui.components.AttachmentOption
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.header
import io.ktor.client.plugins.sse.SSE
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ViewModel for AssistActivity - Handles Smarty overlay functionality
 *
 * Responsibilities:
 * - Chat message state management
 * - AI agent communication
 * - Voice input state
 * - Saving conversations to main app's chat history
 */
class AssistViewModel(
    application: Application
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AssistViewModel"
    }

    private val sharedAppState: SharedAppState by lazy {
        ServiceLocator.provideSharedAppState()
    }

    // Repositories from ServiceLocator
    private val repository: SmartyRepository by lazy {
        ServiceLocator.provideRepository(application)
    }

    private val chatRepository: ChatRepository by lazy {
        ServiceLocator.provideChatRepository(application)
    }

    private val alarmScheduler: AlarmScheduler by lazy {
        AlarmScheduler.getInstance(application)
    }

    private val deviceAudioRepository: DeviceAudioRepository by lazy {
        ServiceLocator.provideDeviceAudioRepository(application)
    }

    // Hybridized Feature Managers via ServiceLocator
    private val audioPlaybackManager: AudioPlaybackManager by lazy {
        ServiceLocator.provideAudioPlaybackManager(application)
    }

    private val systemFeatureManager: SystemFeatureManager by lazy {
        ServiceLocator.provideSystemFeatureManager(application)
    }

    private val settingsFeatureManager: com.example.smarty.features.settings.domain.SettingsFeatureManager by lazy {
        ServiceLocator.provideSettingsFeatureManager(application)
    }

    private val searchFeatureManager: SearchFeatureManager by lazy {
        ServiceLocator.provideSearchFeatureManager(application)
    }

    private val calendarFeatureManager: CalendarFeatureManager by lazy {
        ServiceLocator.provideCalendarFeatureManager(application)
    }

    private val calendarManager: CalendarManager by lazy {
        calendarFeatureManager.getCalendarManager()
    }

    private val styleFeatureManager: StyleFeatureManager by lazy {
        ServiceLocator.provideStyleFeatureManager()
    }

    private val audioFeatureManager: AudioFeatureManager by lazy {
        ServiceLocator.provideAudioFeatureManager(application)
    }

    private val memoryFeatureManager: MemoryFeatureManager by lazy {
        ServiceLocator.provideMemoryFeatureManager(application)
    }

    // Chat Manager - handles chat state and session lifecycle
    private val chatManager: ChatManager by lazy {
        val logger = AndroidLogger()
        ChatManager(
            application,
            chatRepository,
            viewModelScope,
            HistoryCompressor(logger)
        )
    }

    private val noteOperationsManager: NoteOperationsManager by lazy {
        ServiceLocator.provideNoteOperationsManager(application)
    }

    private val workflowManager: WorkflowManager by lazy {
        WorkflowManager(
            repository = repository,
            scope = viewModelScope,
            onStatusUpdate = { _ -> /* UI status updates disabled */ }
        )
    }

    private val localCommandProcessor: LocalCommandProcessor by lazy {
        LocalCommandProcessor(
            context = application,
            getNotes = { _notes.value },
            getActiveNoteId = { null }, // Overlay mode doesn't have a single active note view
            systemFeatureManager = systemFeatureManager,
            getDeviceAudio = { deviceAudioRepository.getAllAudio() }
        )
    }

    // Task 7: Command transport for delivering validated commands to execution
    private val commandTransport: CommandTransport by lazy {
        LocalCommandTransport(clientCommandExecutor, viewModelScope)
    }

    // Notes state for agent callbacks (cached on first access)
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    private val _archivedNotes = MutableStateFlow<List<Note>>(emptyList())
    private val _categories = MutableStateFlow<List<Category>>(emptyList())

    // Temporary storage for citations during agent execution (thread-safe)
    private val pendingCitations = java.util.concurrent.CopyOnWriteArrayList<WebCitation>()

    // Temporary storage for inline images during agent execution (thread-safe)
    private val pendingInlineImages = java.util.concurrent.CopyOnWriteArrayList<InlineChatImage>()

    // Agent Event Sink for Koog tools notifications
    private val agentEventSink = object : AgentEventSink {
        override fun onToolExecutionStarted(toolName: String, toolDisplayName: String) {
            // UI indicators disabled
        }

        override fun onToolExecutionCompleted(toolName: String) {
            // UI indicators disabled
        }

        override fun onStatusUpdate(status: String) {
            // UI indicators disabled
        }

        override fun onCitationsFound(citations: List<WebCitation>) {
            pendingCitations.addAll(citations)
        }

        override fun onDisplayImages(images: List<ImageDisplayItem>) {
            pendingInlineImages.clear()
            pendingInlineImages.addAll(images.map {
                InlineChatImage(uri = it.uri, fileName = it.fileName, noteTitle = it.noteTitle)
            })
        }

        override fun onPlanStatusChanged(status: String?) {
            // Not displayed in assist mode
        }

        override fun onStateSync(syncType: String, data: String) {
            // Handle state synchronization - currently not displayed in assist mode
            Log.d(TAG, "State sync received: $syncType")
        }

        override fun emit(command: AgentCommand) {
            // Handle remote commands from agent
            Log.d(TAG, "Received agent command: $command")

            // Task 7: Route commands through transport abstraction
            when (command) {
                is AgentCommand.NotifyToolStarted -> {
                    // UI indicators disabled
                }
                is AgentCommand.NotifyToolCompleted -> {
                    // UI indicators disabled
                }
                is AgentCommand.NotifyStatus -> {
                    // UI indicators disabled
                }
                is AgentCommand.NotifyCitations -> {
                    val citations = command.citations.map { proto ->
                        WebCitation(proto.title, proto.url, proto.snippet)
                    }
                    onCitationsFound(citations)
                }
                else -> {
                    viewModelScope.launch {
                        val result = commandTransport.dispatch(command)
                        if (result != null) {
                            val sessionId = chatManager.currentSessionId.value ?: "unknown"
                            remoteAgentService.sendEvent(sessionId, result)

                            // Auto-trigger continuation to let Agent reason about the tool result
                            processRemoteQuery("", ChatMessage(
                                id = "continuation",
                                role = ChatRole.USER,
                                content = "",
                                timestamp = System.currentTimeMillis()
                            ))
                        }
                    }
                }
            }
        }
    }

    // Client Command Executor for Koog tools actions
    private val clientCommandExecutor = object : ClientCommandExecutor {
        override fun getActiveNotes(): List<Note> = PrivacyGuard.getAiVisibleNotes(_notes.value)
        override fun getArchivedNotes(): List<Note> = PrivacyGuard.getAiVisibleNotes(_archivedNotes.value)
        override fun getCategories(): List<Category> = _categories.value
        override fun getScreenContext(): ScreenContext? {
            val ctx = _assistContext.value
            return ScreenContext(
                selectedText = ctx?.selectedText,
                referringApp = ctx?.referringPackage ?: application.packageName,
                capturedAt = System.currentTimeMillis(),
                contextData = mapOf(
                    "current_screen" to getCurrentScreen(),
                    "referring_package" to (ctx?.referringPackage ?: "")
                )
            )
        }

        override suspend fun getDeviceAudio(): List<AudioTrack> {
            return deviceAudioRepository.getAllAudio()
        }

        override fun getCurrentScreen(): String = "assist_overlay"

        override fun getSystemStatus(): Map<String, String> {
            return mapOf(
                "mode" to "assist_overlay",
                "theme" to if (settingsFeatureManager.isDarkTheme.value) "dark" else "light",
                "battery" to "unknown", // Simplified
                "connection" to "connected"
            )
        }

        override fun addNote(content: String, category: String?) {
            viewModelScope.launch {
                noteOperationsManager.addNote(
                    content = content,
                    type = NoteType.BRAIN_DUMP,
                    excludeFromAiChat = false,
                    initialCategory = category
                )
            }
        }

        override fun updateNote(noteId: String, title: String?, content: String?) {
            viewModelScope.launch {
                noteOperationsManager.updateNote(noteId, title, content, _notes.value, _archivedNotes.value)
            }
        }

        override fun deleteNoteById(noteId: String) {
            viewModelScope.launch {
                noteOperationsManager.deleteNoteById(noteId, _notes.value, _archivedNotes.value)
            }
        }

        override fun archiveNote(noteId: String) {
            noteOperationsManager.archiveNote(noteId)
        }

        override fun unarchiveNote(noteId: String) {
            noteOperationsManager.unarchiveNote(noteId)
        }

        override fun summarizeNote(noteId: String) {
            noteOperationsManager.summarizeNote(noteId, _notes.value, _archivedNotes.value)
        }

        override suspend fun processNoteWithAi(note: Note) {
            noteOperationsManager.processNoteWithAi(note)
        }

        override fun addTodoToNote(noteId: String, text: String) {
            viewModelScope.launch {
                noteOperationsManager.addTodoToNote(noteId, text)
            }
        }

        override suspend fun onCreateCategory(name: String): Category {
            return noteOperationsManager.getOrCreateCategory(name)
        }

        override suspend fun getCategoryStats(): List<CategoryStatInfo> {
            return noteOperationsManager.getCategoryStats(_categories.value, _notes.value + _archivedNotes.value)
        }

        override fun launchApp(packageName: String) {
            systemFeatureManager.launchApp(packageName)
        }

        override fun findPackageName(appName: String): String? {
            return systemFeatureManager.findPackageName(appName)
        }

        override fun navigateTo(screen: String) {
            systemFeatureManager.navigateTo(screen)
        }

        override fun shareContent(text: String, title: String?) {
            systemFeatureManager.shareContent(text, title)
        }

        override fun toggleTheme(isDark: Boolean) {
            systemFeatureManager.toggleTheme(isDark)
        }

        override suspend fun toggleSetting(setting: String, enable: Boolean) {
            systemFeatureManager.toggleSetting(setting, enable)
        }

        override suspend fun takeScreenshot(save: Boolean) {
            systemFeatureManager.captureScreen()
        }

        override fun clearCache() {
            systemFeatureManager.clearCache()
        }

        override fun backupData() {
            systemFeatureManager.backupData()
        }

        override fun setPrivacyMode(mode: String) {
            systemFeatureManager.setPrivacyMode(mode)
        }

        override suspend fun storeContext(content: String, type: String) {
            memoryFeatureManager.storeMemory(content, type)
        }

        override suspend fun updateContext(id: String, content: String, type: String) {
            memoryFeatureManager.deleteMemory(id)
            memoryFeatureManager.storeMemory(content, type)
        }

        override suspend fun deleteContext(id: String) {
            memoryFeatureManager.deleteMemory(id)
        }

        override suspend fun searchNotes(
            query: String,
            category: String?,
            noteType: String?,
            timeRange: String,
            limit: Int
        ): List<SearchResultItem> {
            return searchFeatureManager.search(query, category, noteType, timeRange, emptySet(), limit)
        }

        override suspend fun advancedSearch(
            query: String,
            algorithm: String,
            limit: Int,
            minScore: Double
        ): List<SearchResultItem> {
            return searchFeatureManager.advancedSearch(query, algorithm, limit, minScore)
        }

        override fun analyzeQuery(query: String): SearchQueryAnalysis {
            return searchFeatureManager.analyzeQuery(query)
        }

        override suspend fun performRecall(query: String, minScore: Double): List<RecallResult> {
            return searchFeatureManager.performRecall(query, minScore)
        }

        override fun requestAudioPlayback(track: AudioTrack) {
            audioFeatureManager.play(track)
        }

        override fun playAudioList(tracks: List<AudioTrack>) {
            audioFeatureManager.playList(tracks)
        }

        override fun pauseAudioPlayback() {
            audioFeatureManager.pause()
        }

        override fun resumeAudioPlayback() {
            audioFeatureManager.resume()
        }

        override fun stopAudioPlayback() {
            audioFeatureManager.stop()
        }

        override fun seekAudioTo(positionMs: Long) {
            audioFeatureManager.seekTo(positionMs)
        }

        override fun nextTrack() {
            audioFeatureManager.next()
        }

        override fun previousTrack() {
            audioFeatureManager.previous()
        }

        override fun toggleAudioPlayback() {
            audioFeatureManager.togglePlayPause()
        }

        override fun getCurrentAudioTrack(): AudioTrack? {
            return audioFeatureManager.getCurrentTrack()
        }

        override fun getCurrentAudioPosition(): Long {
            return audioFeatureManager.getCurrentPosition()
        }

        override fun getAudioDuration(): Long {
            return audioFeatureManager.getDuration()
        }

        override fun isAudioPlaying(): Boolean {
            return audioFeatureManager.isPlaying()
        }

        override suspend fun findMatchingAudio(query: String): AudioSearchResult {
            return audioFeatureManager.findAudioTrack(query)
        }

        override suspend fun controlAudio(action: String) {
            when (action.lowercase()) {
                "play", "resume" -> audioFeatureManager.resume()
                "pause" -> audioFeatureManager.pause()
                "stop" -> audioFeatureManager.stop()
                "next" -> audioFeatureManager.next()
                "previous" -> audioFeatureManager.previous()
            }
        }

        override suspend fun seekAudio(positionMs: Long) {
            audioFeatureManager.seekTo(positionMs)
        }

        override fun addCalendarEvent(
            title: String,
            startTimeStr: String,
            endTimeStr: String?,
            description: String?,
            location: String?,
            isPrivate: Boolean
        ) {
            val startMillis = calendarFeatureManager.parseDateTime(startTimeStr) ?: return
            val endMillis = endTimeStr?.let { calendarFeatureManager.parseDateTime(it) } ?: (startMillis + 3600000L)
            calendarFeatureManager.addCalendarEvent(title, description, startMillis, endMillis, false, location, null, null, isPrivate)
        }

        override suspend fun scheduleEvent(title: String, startTime: Long, endTime: Long, description: String?) {
            calendarFeatureManager.addCalendarEvent(title, description, startTime, endTime)
        }

        override suspend fun listEvents(date: Long): List<CalendarEvent> {
            return calendarFeatureManager.getEventsForDay(date)
        }

        override suspend fun deleteEvent(eventId: String) {
            calendarFeatureManager.deleteCalendarEvent(eventId)
        }

        override fun deleteCalendarEvent(eventId: String) {
            calendarFeatureManager.deleteCalendarEvent(eventId)
        }

        override suspend fun queryCalendarEvents(query: String?): List<CalendarEvent> {
            return if (query.isNullOrBlank()) calendarFeatureManager.getTodayEvents() else calendarFeatureManager.searchEvents(query)
        }

        override fun bulkDeleteEvents(eventIds: List<String>) {
            eventIds.forEach { calendarFeatureManager.deleteCalendarEvent(it) }
        }

        override fun setTimer(name: String, timeStr: String, isAlarm: Boolean) {
            val triggerTime = calendarFeatureManager.parseDateTime(timeStr) ?: return
            calendarFeatureManager.setTimer(name, triggerTime, isAlarm)
        }

        override fun cancelTimer(timerId: String) {
            calendarFeatureManager.cancelTimer(timerId)
        }

        override fun bulkArchiveNotes(noteIds: List<String>) {
            noteOperationsManager.bulkArchiveNotes(noteIds)
        }

        override fun bulkDeleteNotes(noteIds: List<String>) {
            noteOperationsManager.bulkDeleteNotes(noteIds, _notes.value, _archivedNotes.value)
        }

        override fun bulkMoveToCategory(noteIds: List<String>, categoryName: String) {
            noteOperationsManager.bulkMoveToCategory(noteIds, categoryName)
        }
    }

    // Task 15: Remote Agent Service (Thin Client)
    private val remoteAgentService: RemoteAgentService by lazy {
        val client = HttpClient(OkHttp) {
            install(SSE) {
                reconnectionTime = 5.seconds
            }
            engine {
                config {
                    connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                    writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        }

        val securePrefs = com.example.smarty.data.local.SecurePreferences.getInstance(application)
        RemoteAgentService(
            client = client,
            eventSink = agentEventSink,
            serverUrlProvider = { settingsFeatureManager.getSmartyServerUrl() },
            deviceIdProvider = { securePrefs.getDeviceId() }
        )
    }

    // State observed from managers
    val messages: StateFlow<List<ChatMessage>> = chatManager.chatMessages
    val isChatMode: StateFlow<Boolean> = chatManager.isChatMode

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    // Assist context (selected text from triggering app)
    private val _assistContext = MutableStateFlow<AssistContext?>(null)
    val assistContext: StateFlow<AssistContext?> = _assistContext.asStateFlow()

    /**
     * Data class for assist context information
     */
    data class AssistContext(
        val selectedText: String?,
        val referringPackage: String
    )

    // Theme state (from SettingsFeatureManager as source of truth)
    val isDarkTheme: StateFlow<Boolean> = settingsFeatureManager.isDarkTheme

    // Connection Status (from SharedAppState)
    val connectionStatus: StateFlow<ConnectionStatus> = sharedAppState.connectionStatus

    init {
        // Enter chat mode for this Smarty interaction
        viewModelScope.launch {
            chatManager.enterChatMode()
            Log.d(TAG, "Entered Smarty chat mode")

            // Load notes for agent context
            loadNotesForContext()
        }
    }

    /**
     * Load notes and categories for agent context
     * Delegates to NoteOperationsManager for centralized data access.
     */
    private suspend fun loadNotesForContext() {
        try {
            _notes.value = noteOperationsManager.getAllNotes().first()
            _archivedNotes.value = noteOperationsManager.getArchivedNotes().first()
            _categories.value = noteOperationsManager.getAllCategories().first()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading notes: ${e.message}", e)
        }
    }

    /**
     * Universal Dispatcher for Smarty queries.
     * Routes intent between FAST-PATH (Local Commands) and REASONING-PATH (AI Agent).
     */
    fun sendMessage(content: String, attachments: List<Attachment> = emptyList()) {
        if (content.isBlank() && attachments.isEmpty()) return

        viewModelScope.launch {
            // Reset success flag for new request
            chatManager.resetApiCallFlag()

            var processingSet = false
            try {
                // Set processing state with error handling
                try {
                    _isProcessing.value = true
                    processingSet = true
                    pendingCitations.clear()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set processing state: ${e.message}")
                    processingSet = false
                }

                // 1. Add user message via manager
                val userMessage = chatManager.addUserMessage(content, attachments)

                // 2. FAST-PATH: Check Local Command Processor (offline, 0ms latency)
                val commandResult = localCommandProcessor.process(content)

                if (commandResult is CommandResult.SavePageRequest) {
                    // Screen capture not fully supported in overlay mode yet
                    val smartyMessage = ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        role = ChatRole.SMARTY,
                        content = getApplication<Application>().getString(R.string.limited_in_overlay_mode),
                        timestamp = System.currentTimeMillis()
                    )
                    addSmartyMessage(smartyMessage, userMessage)
                    return@launch
                }

                if (commandResult is CommandResult.Handled) {
                    Log.i(TAG, "Query handled by FAST-PATH: $content")
                    // Local commands are considered successful interactions
                    chatManager.markApiCallSuccessful()
                    val smartyMessage = ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        role = ChatRole.SMARTY,
                        content = commandResult.response,
                        timestamp = System.currentTimeMillis()
                    )
                    addSmartyMessage(smartyMessage, userMessage)
                    return@launch
                }

                if (commandResult is CommandResult.NavigateTo) {
                    Log.i(TAG, "Navigation requested by FAST-PATH: ${commandResult.route}")
                    chatManager.markApiCallSuccessful()
                    systemFeatureManager.navigateTo(commandResult.route)
                    val smartyMessage = ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        role = ChatRole.SMARTY,
                        content = getApplication<Application>().getString(R.string.navigating_success, commandResult.route),
                        timestamp = System.currentTimeMillis()
                    )
                    addSmartyMessage(smartyMessage, userMessage)
                    return@launch
                }

                if (commandResult is CommandResult.HandledAndPassToLLM) {
                    Log.i(TAG, "Query handled by FAST-PATH (HandledAndPassToLLM): $content")
                    chatManager.markApiCallSuccessful()
                    val smartyMessage = ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        role = ChatRole.SMARTY,
                        content = commandResult.response,
                        timestamp = System.currentTimeMillis()
                    )
                    addSmartyMessage(smartyMessage, userMessage)
                    // Continue to reasoning path...
                }

                // 3. REMOTE-PATH: AI Agent processing
                processRemoteQuery(content, userMessage)

            } catch (e: Exception) {
                Log.e(TAG, "Error in universal dispatcher: ${e.message}", e)
                val errorMessage = ChatMessage(
                    id = java.util.UUID.randomUUID().toString(),
                    role = ChatRole.SMARTY,
                    content = getApplication<Application>().getString(R.string.request_timed_out),
                    timestamp = System.currentTimeMillis(),
                    isError = true
                )
                chatManager.addSmartyMessage(errorMessage)
            } finally {
                // Safely reset processing state only if we successfully set it
                if (processingSet) {
                    try {
                        // Use NonCancellable to ensure processing state is always reset
                        // but wrap in try-catch to prevent crashes during cleanup
                        withContext(NonCancellable) {
                            _isProcessing.value = false
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to reset processing state: ${e.message}")
                        // Fallback: try direct assignment
                        try {
                            _isProcessing.value = false
                        } catch (fallbackE: Exception) {
                            Log.e(TAG, "Complete failure to reset processing state: ${fallbackE.message}")
                        }
                    }
                }
            }
        }
    }

    /**
     * REMOTE-PATH: AI Agent execution for complex intent.
     */
    private suspend fun processRemoteQuery(content: String, userMessage: ChatMessage) {
        // Clear previous state
        pendingCitations.clear()
        pendingInlineImages.clear()

        try {
            // Collect chunks from the remote stream
            val responseBuilder = StringBuilder()
            val thinkingBuilder = StringBuilder()
            // Use user-selected strategy (BALANCED, FASTEST, etc.)
            // The server's ProviderRouter will handle the actual provider selection
            val provider = settingsFeatureManager.providerStrategy.value
            val sessionId = chatManager.currentSessionId.value

            remoteAgentService.sendQuery(content, provider = provider, sessionId = sessionId)
                .collect { event ->
                    when (event) {
                        is com.example.smarty.protocol.AgentEvent.Processing -> {
                            if (!event.content.isNullOrEmpty()) {
                                responseBuilder.append(event.content)
                            }
                            if (!event.thinking.isNullOrEmpty()) {
                                thinkingBuilder.append(event.thinking)
                            }
                        }
                        is com.example.smarty.protocol.AgentEvent.Result -> {
                            if (event.content.isNotEmpty()) {
                                responseBuilder.append(event.content)
                            }
                            if (!event.thinking.isNullOrEmpty()) {
                                thinkingBuilder.append(event.thinking)
                            }
                        }
                        is com.example.smarty.protocol.AgentEvent.Error -> {
                            if (event.message.isNotEmpty()) {
                                responseBuilder.append("\n[Error: ${event.message}]")
                            }
                        }
                        else -> {
                           // Other events like ToolCall, StateSync, Command are handled by eventSink inside RemoteAgentService
                        }
                    }
                }

            val fullResponse = responseBuilder.toString()
            val fullThinking = thinkingBuilder.toString()

            // Handle success
            chatManager.markApiCallSuccessful()

            // Get inline images and clear pending
            val inlineImages = pendingInlineImages.toList()
            pendingInlineImages.clear()

            val smartyMessage = ChatMessage(
                id = java.util.UUID.randomUUID().toString(),
                role = ChatRole.SMARTY,
                content = fullResponse,
                thinking = fullThinking.takeIf { it.isNotBlank() },
                timestamp = System.currentTimeMillis(),
                citations = pendingCitations.map { citation ->
                    Citation(
                        title = citation.title,
                        url = citation.url,
                        snippet = citation.snippet
                    )
                },
                inlineImages = inlineImages
            )

            addSmartyMessage(smartyMessage, userMessage)

        } catch (e: Exception) {
            Log.e(TAG, "Remote agent execution failed", e)
            val errorMessage = ChatMessage(
                id = java.util.UUID.randomUUID().toString(),
                role = ChatRole.SMARTY,
                content = getApplication<Application>().getString(R.string.error_prefix, e.message ?: "Connection error"),
                timestamp = System.currentTimeMillis(),
                isError = true
            )
            addSmartyMessage(errorMessage, userMessage)
        }
    }

    /**
     * Add Smarty response and persist the pair via manager.
     */
    private suspend fun addSmartyMessage(smartyMessage: ChatMessage, userMessage: ChatMessage) {
        // Add Smarty response via manager
        chatManager.addSmartyMessage(smartyMessage)

        // Save message pair via manager
        withContext(NonCancellable) {
            chatManager.saveMessagePair(
                userMessage = userMessage,
                smartyMessage = smartyMessage
            )
        }
    }

    /**
     * Set listening state (for voice input)
     */
    fun setListening(listening: Boolean) {
        _isListening.value = listening
    }

    /**
     * Stop listening
     */
    fun stopListening() {
        _isListening.value = false
    }

    /**
     * Save conversation and prepare to close
     */
    fun saveAndClose() {
        viewModelScope.launch {
            try {
                // Use NonCancellable to ensure session is finalized even if scope is cancelled
                withContext(NonCancellable) {
                    chatManager.exitChatMode()
            Log.d(TAG, "Exited Smarty chat mode and finalized session")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving session: ${e.message}", e)
            }
        }
    }

    /**
     * Add a single message to the conversation (UI-005 fix)
     * Used by AssistActivity when processing input directly
     */
    fun addMessage(message: ChatMessage) {
        viewModelScope.launch {
            if (message.role == ChatRole.USER) {
                chatManager.addUserMessage(message.content, message.attachments)
            } else {
                val userMsg = chatManager.chatMessages.value.lastOrNull { it.role == ChatRole.USER }
                chatManager.addSmartyMessage(message)

                if (userMsg != null) {
                    withContext(NonCancellable) {
                        chatManager.saveMessagePair(
                            userMessage = userMsg,
                            smartyMessage = message
                        )
                        Log.d(TAG, "Message pair saved to current session")
                    }
                }
            }
        }
    }

    /**
     * Clear all messages (start fresh conversation)
     */
    fun clearMessages() {
        viewModelScope.launch {
            pendingCitations.clear()
            chatManager.createNewChatSession()
            Log.d(TAG, "Started fresh Smarty session")
        }
    }

    /**
     * Set the assist context from the triggering app
     */
    fun setAssistContext(selectedText: String?, referringPackage: String) {
        _assistContext.value = AssistContext(
            selectedText = selectedText,
            referringPackage = referringPackage
        )
        if (!selectedText.isNullOrBlank()) {
            Log.d(TAG, "Assist context set: text from $referringPackage")
        }
    }

    /**
     * Add citations from web search to pending list
     */
    fun addCitations(citations: List<WebCitation>) {
        pendingCitations.addAll(citations)
        Log.d(TAG, "Added ${citations.size} citations to pending list")
    }

    /**
     * Clear pending citations (call before new request)
     */
    fun clearPendingCitations() {
        pendingCitations.clear()
    }

    /**
     * Resolves a string that might be a resource key with parameters (e.g., "key|param1|param2")
     */
    private fun resolveResourceString(input: String?): String? {
        if (input == null) return null

        val parts = input.split("|")
        val key = parts[0]
        val resId = getApplication<Application>().resources.getIdentifier(key, "string", getApplication<Application>().packageName)

        return if (resId != 0) {
            if (parts.size > 1) {
                // Try to parse numeric arguments if possible
                val args = parts.subList(1, parts.size).map {
                    it.toIntOrNull() ?: it
                }.toTypedArray<Any>()

                try {
                    getApplication<Application>().getString(resId, *args)
                } catch (e: Exception) {
                    // Fallback to raw key if formatting fails
                    input
                }
            } else {
                getApplication<Application>().getString(resId)
            }
        } else {
            // Not a resource key, return as is
            input
        }
    }
}

/**
 * Factory for AssistViewModel
 */
class AssistViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AssistViewModel::class.java)) {
            return AssistViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}


