package com.example.smarty.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.smarty.agent.models.ScreenContext
import com.example.smarty.R
import com.example.smarty.agent.AgentEventSink
import com.example.smarty.agent.ClientCommandExecutor
import com.example.smarty.agent.models.ImageDisplayItem
import com.example.smarty.agent.models.WebCitation
import com.example.smarty.agent.WebSearchResult
import com.example.smarty.viewmodel.managers.AudioFeatureManager.AudioSearchResult
import com.example.smarty.data.model.InlineChatImage
import com.example.smarty.data.local.SmartyDatabase
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.model.Attachment
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatRole
import com.example.smarty.data.model.Citation
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.getTodos
import com.example.smarty.data.remote.RemoteAgentService
import com.example.smarty.data.repository.ChatRepository
import com.example.smarty.data.repository.DeviceAudioRepository
import com.example.smarty.data.repository.SmartyRepository
import com.example.smarty.service.AlarmScheduler
import com.example.smarty.service.LocalCommandProcessor
import com.example.smarty.service.CommandResult
import com.example.smarty.viewmodel.managers.AudioPlaybackManager
import com.example.smarty.viewmodel.managers.NoteOperationsManager
import com.example.smarty.viewmodel.managers.SearchFeatureManager
import com.example.smarty.viewmodel.managers.SystemFeatureManager
import com.example.smarty.util.PrivacyGuard
import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.header
// import io.ktor.client.plugins.contentnegotiation.ContentNegotiation // Removed - not available in minimal Ktor setup
import io.ktor.client.plugins.sse.SSE
// import io.ktor.serialization.kotlinx.json.json // Removed - not available in minimal Ktor setup
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json

/**
 * ViewModel for AssistActivity - Handles assistant overlay functionality
 *
 * Responsibilities:
 * - Chat message state management
 * - AI agent communication
 * - Voice input state
 * - Saving conversations to main app's chat history
 */
class AssistViewModel(
    application: Application,
    private val repository: SmartyRepository,
    private val chatRepository: ChatRepository,
    private val alarmScheduler: AlarmScheduler
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AssistViewModel"
    }

    // Lazy initialization
    private val securePreferences: SecurePreferences by lazy {
        SecurePreferences.getInstance(application)
    }
    private val database: SmartyDatabase by lazy {
        SmartyDatabase.getDatabase(application)
    }
    // Repository injected via constructor

    // ChatRepository injected via constructor

    // Use shared HttpClientProvider to avoid connection pool duplication
    private val httpClient: OkHttpClient by lazy {
        com.example.smarty.util.HttpClientProvider.default
    }
    // AlarmScheduler injected via constructor
    // Device audio repository for MediaStore access
    private val deviceAudioRepository: DeviceAudioRepository by lazy {
        DeviceAudioRepository(application)
    }

    // Hybridized Feature Managers
    private val audioPlaybackManager: AudioPlaybackManager by lazy {
        AudioPlaybackManager(application, viewModelScope)
    }

    private val systemFeatureManager: SystemFeatureManager by lazy {
        SystemFeatureManager(
            context = application,
            scope = viewModelScope,
            audioManager = audioPlaybackManager,
            calendarManager = calendarManager,
            securePreferences = securePreferences,
            deviceAudioRepository = deviceAudioRepository,
            onNavigateRequest = { /* Navigation not supported in overlay */ }
        )
    }

    private val searchFeatureManager: com.example.smarty.viewmodel.managers.SearchFeatureManager by lazy {
        com.example.smarty.viewmodel.managers.SearchFeatureManager(
            repository = repository,
            allNotes = _notes,
            searchHistoryManager = com.example.smarty.data.local.SearchHistoryManager(application),
            securePreferences = securePreferences
        )
    }

    private val calendarManager: com.example.smarty.viewmodel.managers.CalendarManager by lazy {
        com.example.smarty.viewmodel.managers.CalendarManager(
            calendarDao = database.calendarDao(),
            alarmScheduler = alarmScheduler,
            scope = viewModelScope
        )
    }

    private val styleFeatureManager: com.example.smarty.viewmodel.managers.StyleFeatureManager by lazy {
        com.example.smarty.viewmodel.managers.StyleFeatureManager()
    }

    // Chat Manager - handles chat state and session lifecycle
    private val chatManager: com.example.smarty.viewmodel.managers.ChatManager by lazy {
        val logger = com.example.smarty.util.AndroidLogger()
        com.example.smarty.viewmodel.managers.ChatManager(
            application,
            chatRepository,
            viewModelScope,
            com.example.smarty.util.HistoryCompressor(logger),
            com.example.smarty.util.PIIMasker(logger)
        )
    }

    private val noteOperationsManager: NoteOperationsManager by lazy {
        NoteOperationsManager(
            repository = repository,
            aiService = com.example.smarty.data.remote.AIService(getApplication(), securePreferences),
            context = application,
            scope = viewModelScope
        )
    }

    private val workflowManager: com.example.smarty.viewmodel.managers.WorkflowManager by lazy {
        com.example.smarty.viewmodel.managers.WorkflowManager(
            repository = repository,
            scope = viewModelScope,
            onStatusUpdate = { status -> _toolStatus.value = status }
        )
    }

    private val executionPlanManager: com.example.smarty.viewmodel.managers.ExecutionPlanManager by lazy {
        com.example.smarty.viewmodel.managers.ExecutionPlanManager()
    }

    /** Expose reactive plan state to Assist UI (if needed in future) */
    val activeExecutionPlan: StateFlow<com.example.smarty.viewmodel.managers.ExecutionPlan?> = executionPlanManager.activePlan

    private val memoryFeatureManager: com.example.smarty.viewmodel.managers.MemoryFeatureManager by lazy {
        val memorySyncManager = com.example.smarty.viewmodel.managers.MemorySyncManager(
            context = application,
            database = database,
            aiMemoryDao = database.aiMemoryDao(),
            aiService = com.example.smarty.data.remote.AIService(getApplication(), securePreferences)
        )
        com.example.smarty.viewmodel.managers.MemoryFeatureManager(
            aiMemoryDao = database.aiMemoryDao(),
            syncManager = memorySyncManager,
            scope = viewModelScope
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
    private val commandTransport: com.example.smarty.agent.transport.CommandTransport by lazy {
        com.example.smarty.agent.transport.LocalCommandTransport(clientCommandExecutor, viewModelScope)
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
            _toolStatus.value = resolveResourceString(toolDisplayName)
        }

        override fun onToolExecutionCompleted(toolName: String) {
            _toolStatus.value = null
        }

        override fun onStatusUpdate(status: String) {
            _toolStatus.value = resolveResourceString(status)
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

        override fun emit(command: com.example.smarty.protocol.AgentCommand) {
            // Handle remote commands from agent
            Log.d(TAG, "Received agent command: $command")

            // Task 7: Route commands through transport abstraction
            when (command) {
                is com.example.smarty.protocol.AgentCommand.NotifyToolStarted -> {
                    _toolStatus.value = resolveResourceString(command.displayName)
                }
                is com.example.smarty.protocol.AgentCommand.NotifyToolCompleted -> {
                    _toolStatus.value = null
                }
                is com.example.smarty.protocol.AgentCommand.NotifyStatus -> {
                    _toolStatus.value = resolveResourceString(command.status)
                }
                is com.example.smarty.protocol.AgentCommand.NotifyCitations -> {
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
                            processReasoningPath("", ChatMessage(
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

        override suspend fun processNoteWithAi(note: Note) {
            noteOperationsManager.processNoteWithAi(note)
        }

        override suspend fun markNoteAsAnalyzedForMemory(noteId: String) {
            noteOperationsManager.markNoteAsAnalyzedForMemory(noteId)
        }

        override suspend fun findNoteByDescription(description: String, notes: List<Note>): Note? {
            return notes.find { note ->
                note.title.contains(description, ignoreCase = true) ||
                        note.content.contains(description, ignoreCase = true)
            }
        }

        override fun requestAudioPlayback(track: AudioTrack) {
            Log.i(TAG, "Playback requested via agent: ${track.title}")
            systemFeatureManager.playAudio(track)
        }

        override fun launchApp(packageName: String) {
            systemFeatureManager.launchApp(packageName)
        }

        override fun getScreenContext(): ScreenContext? {
            val ctx = _assistContext.value
            return ScreenContext(
                selectedText = ctx?.selectedText,
                referringApp = ctx?.referringPackage ?: application.packageName,
                capturedAt = System.currentTimeMillis()
            )
        }

        override suspend fun getDeviceAudio(): List<AudioTrack> {
            return deviceAudioRepository.getAllAudio()
        }

        // HYBRID-CONTROL: Internal app navigation
        override fun navigateTo(screen: String) {
            systemFeatureManager.navigateTo(screen)
        }

        override fun getCurrentScreen(): String = "AssistOverlay"

        override fun getSystemStatus(): Map<String, String> {
            val batteryStatus = application.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else "unknown"

            return mapOf(
                "battery" to "$batteryPct%",
                "theme" to if (securePreferences.isDarkTheme.value) "dark" else "light",
                "mode" to "AssistOverlay",
                "referring_app" to (_assistContext.value?.referringPackage ?: "unknown")
            )
        }

        // HYBRID-CONTROL: Audio Playback Control
        override fun pauseAudioPlayback() {
            audioPlaybackManager.pause()
        }

        override fun resumeAudioPlayback() {
            audioPlaybackManager.resume()
        }

        override fun stopAudioPlayback() {
            audioPlaybackManager.stop()
        }

        override fun seekAudioTo(positionMs: Long) {
            audioPlaybackManager.seekTo(positionMs)
        }

        override fun nextTrack() {
            audioPlaybackManager.next()
        }

        override fun previousTrack() {
            audioPlaybackManager.previous()
        }

        override fun toggleAudioPlayback() {
            audioPlaybackManager.togglePlayPause()
        }

        override fun getCurrentAudioTrack(): AudioTrack? {
            return audioPlaybackManager.currentTrack
        }

        override fun getCurrentAudioPosition(): Long {
            return audioPlaybackManager.currentPosition
        }

        override fun getAudioDuration(): Long {
            return audioPlaybackManager.duration
        }

        override fun isAudioPlaying(): Boolean {
            return audioPlaybackManager.isPlaying
        }

        override suspend fun findMatchingAudio(query: String): AudioSearchResult {
            return systemFeatureManager.findMatchingAudio(query)
        }

        override suspend fun controlAudio(action: String) {
            when (action.lowercase()) {
                "pause" -> audioPlaybackManager.pause()
                "resume" -> audioPlaybackManager.resume()
                "stop" -> audioPlaybackManager.stop()
                "toggle" -> audioPlaybackManager.togglePlayPause()
                "next" -> audioPlaybackManager.next()
                "previous", "prev" -> audioPlaybackManager.previous()
            }
        }

        override suspend fun seekAudio(positionMs: Long) {
            audioPlaybackManager.seekTo(positionMs)
        }

        override fun addNote(content: String, category: String?) {
            noteOperationsManager.addNote(
                content = content,
                initialCategory = category
            )
        }

        override suspend fun captureKnowledge(title: String, content: String, source: String, category: String?) {
            noteOperationsManager.addNote(
                content = "[$title]\n\n$content\n\nSource: $source",
                type = NoteType.WEB_CLIPPING,
                sourceUrl = source,
                initialCategory = category
            )
        }

        override suspend fun searchKnowledge(query: String, filter: String?): List<com.example.smarty.viewmodel.managers.RecallResult> {
            // Knowledge search is typically handled by semantic search in SearchFeatureManager
            return searchFeatureManager.performRecall(query)
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

        override suspend fun onCreateCategory(name: String): Category {
            return noteOperationsManager.getOrCreateCategory(name)
        }

        override suspend fun getCategoryStats(): List<com.example.smarty.viewmodel.managers.CategoryStatInfo> {
            return noteOperationsManager.getCategoryStats(_categories.value, _notes.value + _archivedNotes.value)
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

        override fun syncMemory() {
            memoryFeatureManager.syncFromNotes()
        }

        override suspend fun storeMemory(content: String, scope: String?) {
            memoryFeatureManager.storeMemory(content, scope)
        }

        override suspend fun updateMemory(id: String, content: String?, type: String?, confidence: Float?): Boolean {
            return memoryFeatureManager.updateMemory(id, content, type, confidence)
        }

        override suspend fun deleteMemory(id: String): Boolean {
            return memoryFeatureManager.deleteMemory(id)
        }

        override suspend fun retrieveMemories(query: String?, limit: Int): List<com.example.smarty.data.model.AIMemory> {
            return memoryFeatureManager.retrieveMemories(query, limit)
        }

        override suspend fun analyzePatterns(): com.example.smarty.viewmodel.managers.UserPatternsReport {
            return memoryFeatureManager.analyzePatterns(_notes.value, noteOperationsManager.getAllCategoriesSync())
        }

        override suspend fun learnFromNotes(maxNotes: Int): com.example.smarty.viewmodel.managers.LearningReport {
            return memoryFeatureManager.learnFromNotes(_notes.value, maxNotes)
        }

        override fun backupData() {
            Log.i(TAG, "AI triggered data backup from overlay")
        }

        override fun setPrivacyMode(mode: String) {
            // mode = "standard", "strict", "private"
        }

        override fun consolidateMemories() {
            memoryFeatureManager.consolidateMemories()
        }

        override suspend fun getMemoryStats(): Map<String, Any> {
            return memoryFeatureManager.getMemoryStats()
        }

        override suspend fun searchNotes(
            query: String,
            category: String?,
            noteType: String?,
            timeRange: String,
            limit: Int
        ): List<com.example.smarty.viewmodel.managers.SearchResultItem> {
            return searchFeatureManager.search(query, category, noteType, timeRange, emptySet(), limit)
        }

        override suspend fun advancedSearch(
            query: String,
            algorithm: String,
            limit: Int,
            minScore: Double
        ): List<com.example.smarty.viewmodel.managers.SearchResultItem> {
            return searchFeatureManager.advancedSearch(query, algorithm, limit, minScore)
        }

        override fun analyzeQuery(query: String): com.example.smarty.viewmodel.managers.SearchQueryAnalysis {
            return searchFeatureManager.analyzeQuery(query)
        }

        override suspend fun performRecall(query: String, minScore: Double): List<com.example.smarty.viewmodel.managers.RecallResult> {
            return searchFeatureManager.performRecall(query, minScore)
        }

        override fun shareContent(text: String, title: String?) {
            systemFeatureManager.shareContent(text, title)
        }

        override fun findPackageName(appName: String): String? {
            return systemFeatureManager.findPackageName(appName)
        }

        override fun playAudioList(tracks: List<AudioTrack>) {
            audioPlaybackManager.playList(tracks)
        }

        // HYBRID-CONTROL: Time Operations (Delegated to Managers)
        override fun addCalendarEvent(
            title: String,
            startTimeStr: String,
            endTimeStr: String?,
            description: String?,
            location: String?,
            isPrivate: Boolean
        ) {
            val startMillis = calendarManager.parseDateTime(startTimeStr) ?: return
            val endMillis = endTimeStr?.let { calendarManager.parseDateTime(it) }
                ?: (startMillis + 3600000L) // 1 hour default

            calendarManager.addCalendarEvent(
                title = title,
                description = description,
                startTime = startMillis,
                endTime = endMillis,
                location = location,
                isPrivate = isPrivate
            )
        }

        override fun deleteCalendarEvent(eventId: String) {
            calendarManager.deleteCalendarEvent(eventId)
        }

        override suspend fun scheduleEvent(title: String, startTime: Long, endTime: Long, description: String?) {
            calendarManager.addCalendarEvent(
                title = title,
                description = description,
                startTime = startTime,
                endTime = endTime,
                location = null,
                isPrivate = false
            )
        }

        override suspend fun listEvents(date: Long): List<com.example.smarty.data.model.CalendarEvent> {
            return calendarManager.getEventsForDay(date)
        }

        override suspend fun deleteEvent(eventId: String) {
            calendarManager.deleteCalendarEvent(eventId)
        }

        override suspend fun queryCalendarEvents(query: String?): List<com.example.smarty.data.model.CalendarEvent> {
            return if (query.isNullOrBlank()) {
                calendarManager.getTodayEvents()
            } else {
                calendarManager.searchEvents(query)
            }
        }

        override fun bulkDeleteEvents(eventIds: List<String>) {
            calendarManager.bulkDeleteEvents(eventIds)
        }

        override fun setTimer(name: String, timeStr: String, isAlarm: Boolean) {
            val triggerTime = calendarManager.parseDateTime(timeStr) ?: return
            val timer = com.example.smarty.data.model.SmartyTimer(
                name = name,
                triggerTime = triggerTime,
                isAlarm = isAlarm,
                isActive = true
            )
            alarmScheduler.scheduleTimer(timer)
        }

        override fun cancelTimer(timerId: String) {
            alarmScheduler.cancelTimer(timerId)
        }

        override fun addTodoToNote(noteId: String, text: String) {
            viewModelScope.launch {
                val note = _notes.value.find { it.id == noteId } ?: return@launch
                val currentTodos = note.getTodos()
                val newTodo = com.example.smarty.data.model.TodoItem(
                    id = java.util.UUID.randomUUID().toString(),
                    text = text,
                    createdAt = System.currentTimeMillis()
                )
                noteOperationsManager.updateNoteTodos(noteId, currentTodos + newTodo, _notes.value, emptyList())
            }
        }

        // HYBRID-CONTROL: Orchestration (Bulk actions)
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
        // Initialize Ktor client - SSE only (JSON parsing done manually in RemoteAgentService)
        val client = HttpClient(OkHttp) {
            install(SSE)
            // Add header to bypass ngrok browser warning for public internet access
            install(DefaultRequest) {
                header("ngrok-skip-browser-warning", "true")
            }
        }

        // Connect to local server (reverse forwarded port)
        // Ensure you run: adb reverse tcp:7860 tcp:7860
        RemoteAgentService(
            client = client,
            eventSink = agentEventSink,
            serverUrlProvider = { securePreferences.getSmartyServerUrl() }
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

    // Tool execution status for UI feedback
    private val _toolStatus = MutableStateFlow<String?>(null)
    val toolStatus: StateFlow<String?> = _toolStatus.asStateFlow()

    /**
     * Data class for assist context information
     */
    data class AssistContext(
        val selectedText: String?,
        val referringPackage: String
    )

    // Theme state
    val isDarkTheme: StateFlow<Boolean> = securePreferences.isDarkTheme

    init {
        // Enter chat mode for this assistant interaction
        viewModelScope.launch {
            chatManager.enterChatMode()
            Log.d(TAG, "Entered assistant chat mode")

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
     * Universal Dispatcher for assistant queries.
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
                    val assistantMessage = ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        role = ChatRole.ASSISTANT,
                        content = getApplication<Application>().getString(R.string.limited_in_overlay_mode),
                        timestamp = System.currentTimeMillis()
                    )
                    addAssistantMessage(assistantMessage, userMessage)
                    return@launch
                }

                if (commandResult is CommandResult.Handled) {
                    Log.i(TAG, "Query handled by FAST-PATH: $content")
                    // Local commands are considered successful interactions
                    chatManager.markApiCallSuccessful()
                    val assistantMessage = ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        role = ChatRole.ASSISTANT,
                        content = commandResult.response,
                        timestamp = System.currentTimeMillis()
                    )
                    addAssistantMessage(assistantMessage, userMessage)
                    return@launch
                }

                if (commandResult is CommandResult.NavigateTo) {
                    Log.i(TAG, "Navigation requested by FAST-PATH: ${commandResult.route}")
                    chatManager.markApiCallSuccessful()
                    systemFeatureManager.navigateTo(commandResult.route)
                    val assistantMessage = ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        role = ChatRole.ASSISTANT,
                        content = getApplication<Application>().getString(R.string.navigating_success, commandResult.route),
                        timestamp = System.currentTimeMillis()
                    )
                    addAssistantMessage(assistantMessage, userMessage)
                    return@launch
                }

                if (commandResult is CommandResult.HandledAndPassToLLM) {
                    Log.i(TAG, "Query handled by FAST-PATH (HandledAndPassToLLM): $content")
                    chatManager.markApiCallSuccessful()
                    val assistantMessage = ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        role = ChatRole.ASSISTANT,
                        content = commandResult.response,
                        timestamp = System.currentTimeMillis()
                    )
                    addAssistantMessage(assistantMessage, userMessage)
                    // Continue to reasoning path...
                }

                // 3. REASONING-PATH: AI Agent processing
                processReasoningPath(content, userMessage)

            } catch (e: Exception) {
                Log.e(TAG, "Error in universal dispatcher: ${e.message}", e)
                val errorMessage = ChatMessage(
                    id = java.util.UUID.randomUUID().toString(),
                    role = ChatRole.ASSISTANT,
                    content = getApplication<Application>().getString(R.string.request_timed_out),
                    timestamp = System.currentTimeMillis(),
                    isError = true
                )
                chatManager.addAssistantMessage(errorMessage)
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
     * REASONING-PATH: AI Agent execution for complex intent.
     */
    private suspend fun processReasoningPath(content: String, userMessage: ChatMessage) {
        // Clear previous state
        pendingCitations.clear()
        pendingInlineImages.clear()
        _toolStatus.value = null

        try {
            // Collect chunks from the remote stream
            val responseBuilder = StringBuilder()
            val provider = "LOCAL_PC" // Thin client defaults to local or server-managed
            val sessionId = chatManager.currentSessionId.value

            remoteAgentService.sendQuery(content, provider, sessionId)
                .collect { chunk ->
                    responseBuilder.append(chunk)
                    // Optional: Stream partial response if needed
                }

            val fullResponse = responseBuilder.toString()

            // Handle success
            chatManager.markApiCallSuccessful()

            // Get inline images and clear pending
            val inlineImages = pendingInlineImages.toList()
            pendingInlineImages.clear()

            val assistantMessage = ChatMessage(
                id = java.util.UUID.randomUUID().toString(),
                role = ChatRole.ASSISTANT,
                content = fullResponse,
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

            addAssistantMessage(assistantMessage, userMessage)

        } catch (e: Exception) {
            Log.e(TAG, "Remote agent execution failed", e)
            val errorMessage = ChatMessage(
                id = java.util.UUID.randomUUID().toString(),
                role = ChatRole.ASSISTANT,
                content = getApplication<Application>().getString(R.string.error_prefix, e.message ?: "Connection error"),
                timestamp = System.currentTimeMillis(),
                isError = true
            )
            addAssistantMessage(errorMessage, userMessage)
        }
    }

    /**
     * Add assistant message and persist the pair via manager.
     */
    private suspend fun addAssistantMessage(assistantMessage: ChatMessage, userMessage: ChatMessage) {
        // Add assistant message via manager
        chatManager.addAssistantMessage(assistantMessage)

        // Save message pair via manager
        withContext(NonCancellable) {
            chatManager.saveMessagePair(
                userMessage = userMessage,
                assistantMessage = assistantMessage
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
                    Log.d(TAG, "Exited assistant chat mode and finalized session")
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
                chatManager.addAssistantMessage(message)

                if (userMsg != null) {
                    withContext(NonCancellable) {
                        chatManager.saveMessagePair(
                            userMessage = userMsg,
                            assistantMessage = message
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
            Log.d(TAG, "Started fresh assistant session")
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
     * Set the current tool execution status for UI feedback
     */
    fun setToolStatus(status: String?) {
        _toolStatus.value = status
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
    private val application: Application,
    private val repository: SmartyRepository,
    private val chatRepository: ChatRepository,
    private val alarmScheduler: AlarmScheduler,
    private val aiMemoryDao: com.example.smarty.data.local.AIMemoryDao
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AssistViewModel::class.java)) {
            return AssistViewModel(application, repository, chatRepository, alarmScheduler) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
