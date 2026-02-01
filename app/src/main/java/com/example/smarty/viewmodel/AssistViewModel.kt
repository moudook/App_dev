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
import com.example.smarty.agent.AgentResult
import com.example.smarty.agent.SmartyAgentOptimized
import com.example.smarty.agent.SmartyAgentProvider
import com.example.smarty.agent.ImageDisplayItem
import com.example.smarty.agent.WebCitation
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
import com.example.smarty.data.model.getTodos
import com.example.smarty.data.remote.providers.TavilySearchProvider
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
import com.example.smarty.util.api.GroqKeyManager
import com.google.gson.Gson
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
import kotlinx.coroutines.runBlocking

/**
 * ViewModel for AssistActivity - Handles assistant overlay functionality
 *
 * Responsibilities:
 * - Chat message state management
 * - AI agent communication
 * - Voice input state
 * - Saving conversations to main app's chat history
 */
class AssistViewModel(application: Application) : AndroidViewModel(application) {

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
    private val repository: SmartyRepository by lazy {
        SmartyRepository(
            database.noteDao(),
            database.categoryDao(),
            database.calendarDao(),
            database.noteVersionDao()
        )
    }
    private val chatRepository: ChatRepository by lazy {
        ChatRepository(database.chatDao())
    }
    private val groqKeyManager: GroqKeyManager by lazy {
        GroqKeyManager.getInstance(application)
    }
    // Use shared HttpClientProvider to avoid connection pool duplication
    private val httpClient: OkHttpClient by lazy {
        com.example.smarty.util.HttpClientProvider.default
    }
    private val tavilySearchProvider: TavilySearchProvider by lazy {
        TavilySearchProvider(httpClient, Gson())
    }
    private val alarmScheduler: AlarmScheduler by lazy {
        AlarmScheduler.getInstance(application)
    }
    // Device audio repository for MediaStore access
    private val deviceAudioRepository: DeviceAudioRepository by lazy {
        DeviceAudioRepository(application)
    }
    private val agentProvider: SmartyAgentProvider by lazy {
        SmartyAgentProvider(securePreferences, groqKeyManager)
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
            securePreferences = securePreferences,
            tavilySearchProvider = tavilySearchProvider
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
        com.example.smarty.viewmodel.managers.ChatManager(application, chatRepository, viewModelScope)
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
            tavilySearchProvider = tavilySearchProvider,
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
            systemFeatureManager = systemFeatureManager,
            getDeviceAudio = { deviceAudioRepository.getAllAudio() }
        )
    }

    // Notes state for agent callbacks (cached on first access)
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    private val _archivedNotes = MutableStateFlow<List<Note>>(emptyList())
    private val _categories = MutableStateFlow<List<Category>>(emptyList())

import com.example.smarty.agent.AgentEventSink
import com.example.smarty.agent.ClientCommandExecutor

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
    }

    // Client Command Executor for Koog tools actions
    private val clientCommandExecutor = object : ClientCommandExecutor {
        override fun getActiveNotes(): List<Note> = PrivacyGuard.getAiVisibleNotes(_notes.value)
        override fun getArchivedNotes(): List<Note> = PrivacyGuard.getAiVisibleNotes(_archivedNotes.value)
        override fun getCategories(): List<Category> = _categories.value
        override fun getTavilyApiKey(): String? = securePreferences.getTavilyApiKey()
        // BATCH-3C: OpenAI API key for AgentOptimizer semantic cache (embeddings)
        override fun getOpenAiApiKey(): String? = securePreferences.getProviderKeys(com.example.smarty.data.local.AIProvider.OPENAI).firstOrNull()
        // Gemini API key for AgentOptimizer semantic cache fallback
        override fun getGeminiApiKey(): String? = securePreferences.getProviderKeys(com.example.smarty.data.local.AIProvider.GEMINI).firstOrNull()

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

        override fun getDeviceAudio(): List<AudioTrack> {
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

        override fun findMatchingAudio(query: String): AudioSearchResult {
            return systemFeatureManager.findMatchingAudio(query)
        }

        override fun addNote(content: String, category: String?) {
            noteOperationsManager.addNote(
                content = content,
                initialCategory = category
            )
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

        override fun onDeepResearch(topic: String, apiKey: String, focusAreas: List<String>?, searchDepth: Int) {
            workflowManager.performDeepResearch(topic, apiKey, focusAreas, searchDepth)
        }

        override fun onAnalyzeStyle(limit: Int): com.example.smarty.viewmodel.managers.StyleAnalysisReport {
            return styleFeatureManager.analyzeStyle(_notes.value, limit)
        }

        override suspend fun onWebSearch(
            query: String,
            maxResults: Int,
            topic: String,
            onCitationsFound: (List<com.example.smarty.agent.WebCitation>) -> Unit
        ): com.example.smarty.agent.tools.base.WebSearchResult {
            val apiKey = securePreferences.getTavilyApiKey() ?: return com.example.smarty.agent.tools.base.WebSearchResult(
                success = false,
                query = query,
                reason = "Web search not configured"
            )
            return searchFeatureManager.performWebSearch(query, apiKey, maxResults, topic, onCitationsFound)
        }

        override suspend fun onParallelWebSearch(
            queries: List<String>,
            maxResults: Int,
            topic: String,
            onCitationsFound: (List<com.example.smarty.agent.WebCitation>) -> Unit
        ): com.example.smarty.agent.tools.base.WebSearchResult {
            return searchFeatureManager.performParallelWebSearch(queries, maxResults, topic, onCitationsFound)
        }
    }

    private val smartyAgent: SmartyAgentOptimized by lazy {
        SmartyAgentOptimized(
            context = application,
            agentProvider = agentProvider,
            repository = repository,
            tavilySearchProvider = tavilySearchProvider,
            alarmScheduler = alarmScheduler,
            eventSink = agentEventSink,
            commandExecutor = clientCommandExecutor,
            aiMemoryDao = database.aiMemoryDao(),
            executionPlanManager = executionPlanManager // HYBRID: Shared state machine
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
                        role = ChatRole.ASSISTANT,
                        content = getApplication<Application>().getString(R.string.limited_in_overlay_mode)
                    )
                    addAssistantMessage(assistantMessage, userMessage)
                    return@launch
                }

                if (commandResult is CommandResult.Handled) {
                    Log.i(TAG, "Query handled by FAST-PATH: $content")
                    // Local commands are considered successful interactions
                    chatManager.markApiCallSuccessful()
                    val assistantMessage = ChatMessage(
                        role = ChatRole.ASSISTANT,
                        content = commandResult.response
                    )
                    addAssistantMessage(assistantMessage, userMessage)
                    return@launch
                }

                if (commandResult is CommandResult.HandledAndPassToLLM) {
                    Log.i(TAG, "Query handled by FAST-PATH (PassToLLM): $content")
                    chatManager.markApiCallSuccessful()
                    val assistantMessage = ChatMessage(
                        role = ChatRole.ASSISTANT,
                        content = commandResult.response
                    )
                    addAssistantMessage(assistantMessage, userMessage)
                    // Continue to reasoning path...
                }

                // 3. REASONING-PATH: AI Agent processing
                processReasoningPath(content, userMessage)

            } catch (e: Exception) {
                Log.e(TAG, "Error in universal dispatcher: ${e.message}", e)
                val errorMessage = ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = getApplication<Application>().getString(R.string.request_timed_out),
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
        // Build conversation history for context using manager
        val conversationHistory = chatManager.getHistoryForAgent().takeLast(10)

        // Get AI response
        val result = smartyAgent.run(
            userMessage = content,
            conversationHistory = conversationHistory
        )

        // Mark success if agent execution succeeded
        if (result is AgentResult.Success) {
            chatManager.markApiCallSuccessful()
        }

        // Get inline images and clear pending
        val inlineImages = pendingInlineImages.toList()
        pendingInlineImages.clear()

        // Create assistant message from result
        val assistantMessage = when (result) {
            is AgentResult.Success -> ChatMessage(
                role = ChatRole.ASSISTANT,
                content = result.response,
                citations = pendingCitations.map { citation ->
                    Citation(
                        title = citation.title,
                        url = citation.url,
                        snippet = citation.snippet
                    )
                },
                inlineImages = inlineImages
            )
            is AgentResult.Error -> ChatMessage(
                role = ChatRole.ASSISTANT,
                content = result.message,
                isError = true
            )
            is AgentResult.NoProvider -> ChatMessage(
                role = ChatRole.ASSISTANT,
                content = result.message,
                isError = true
            )
        }

        addAssistantMessage(assistantMessage, userMessage)
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
                assistantMessage = assistantMessage,
                hasApiKeys = true
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
                            assistantMessage = message,
                            hasApiKeys = true
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
    private val agentProvider: SmartyAgentProvider,
    private val tavilySearchProvider: TavilySearchProvider,
    private val alarmScheduler: AlarmScheduler,
    private val aiMemoryDao: com.example.smarty.data.local.AIMemoryDao
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AssistViewModel::class.java)) {
            // We still just pass application because AssistViewModel injects its own dependencies lazily
            // But we need this factory signature to match what AssistActivity is trying to pass.
            // Ideally, we should refactor AssistViewModel to accept these in constructor for better testing,
            // but for now we'll just ignore them to fix the compilation error while keeping the lazy injection logic.
            return AssistViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
