package com.example.smarty.viewmodel.managers

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.example.smarty.agent.*
import com.example.smarty.agent.models.ScreenContext
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.SmartyDatabase
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.model.*
import com.example.smarty.data.remote.AIService
import com.example.smarty.data.remote.providers.TavilySearchProvider
import com.example.smarty.data.repository.ChatRepository
import com.example.smarty.service.AlarmScheduler
import com.example.smarty.service.CommandResult
import com.example.smarty.service.LocalCommandProcessor
import com.example.smarty.ui.components.ConnectionStatus
import com.example.smarty.util.CompletionSoundManager
import com.example.smarty.util.ContentTypeDetector
import com.example.smarty.util.FileStorageHelper
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.ThinkingParser
import com.example.smarty.util.api.GroqKeyManager
import com.example.smarty.util.api.RateLimiter
import com.example.smarty.util.mention.MentionParser
import com.example.smarty.util.mention.NoteContextBuilder
import com.example.smarty.util.mention.ThinkingModeProcessor
import com.example.smarty.viewmodel.managers.AudioFeatureManager.AudioSearchResult
import com.example.smarty.R
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Orchestrates the Chat feature, including AI agent interaction,
 * session management, and mention resolution.
 */
import com.example.smarty.data.repository.SmartyRepository

class ChatFeatureManager(
    private val application: Application,
    private val scope: CoroutineScope,
    private val chatRepository: ChatRepository,
    private val repository: SmartyRepository,
    private val database: SmartyDatabase,
    private val securePreferences: SecurePreferences,
    private val groqKeyManager: GroqKeyManager,
    private val tavilySearchProvider: TavilySearchProvider,
    private val settingsFeatureManager: SettingsFeatureManager,
    private val noteOperationsManager: NoteOperationsManager,
    private val systemFeatureManager: SystemFeatureManager,
    private val completionSoundManager: CompletionSoundManager,
    private val alarmScheduler: AlarmScheduler,
    private val executionPlanManager: ExecutionPlanManager,
    private val rateLimiter: RateLimiter,
    private val memoryFeatureManager: MemoryFeatureManager,
    private val searchFeatureManager: SearchFeatureManager,
    private val audioFeatureManager: AudioFeatureManager,
    private val calendarFeatureManager: CalendarFeatureManager,
    private val styleFeatureManager: StyleFeatureManager,
    private val workflowManager: WorkflowManager,
    private val savedStateHandle: SavedStateHandle,
    // External states needed for callbacks/logic
    private val currentScreen: StateFlow<String>,
    private val activeNoteId: StateFlow<String?>,
    private val isDarkTheme: StateFlow<Boolean>,
    private val connectionStatus: StateFlow<ConnectionStatus>,
    private val cacheSizeBytes: StateFlow<Long>,
    private val unreadForMemoryCount: StateFlow<Int>
) {
    companion object {
        private const val TAG = "ChatFeatureManager"
        private const val KEY_IS_CHAT_MODE = "isChatMode"
        private const val KEY_CURRENT_SESSION_ID = "currentSessionId"
    }

    // Reuse existing ChatManager for basic state and session management
    private val chatManager = ChatManager(application, chatRepository, scope)

    // --- Internal Managers ---

    private val mentionManager: MentionFeatureManager by lazy {
        MentionFeatureManager(repository)
    }

    private val allNotes = noteOperationsManager.getAllNotes()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val archivedNotes = noteOperationsManager.getArchivedNotes()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val allCategories = noteOperationsManager.getAllCategories()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val thinkingModeProcessor: ThinkingModeProcessor by lazy {
        ThinkingModeProcessor(application)
    }

    private val noteContextBuilder: NoteContextBuilder by lazy {
        NoteContextBuilder(mentionManager)
    }

    private val agentProvider: SmartyAgentProvider by lazy {
        SmartyAgentProvider(securePreferences, groqKeyManager)
    }

    private val smartyAgent: SmartyAgentOptimized by lazy {
        SmartyAgentOptimized(
            context = application,
            agentProvider = agentProvider,
            repository = repository,
            tavilySearchProvider = tavilySearchProvider,
            alarmScheduler = alarmScheduler,
            callbacks = agentCallbacks,
            aiMemoryDao = database.aiMemoryDao(),
            executionPlanManager = executionPlanManager,
            rateLimiter = rateLimiter
        )
    }

    private val localCommandProcessor: LocalCommandProcessor by lazy {
        LocalCommandProcessor(
            context = application,
            getNotes = { allNotes.value }, // This might need adjustment if notes are handled elsewhere
            systemFeatureManager = systemFeatureManager,
            getDeviceAudio = { systemFeatureManager.getDeviceAudio() }
        )
    }

    // Agent callbacks for Smarty tools
    private val agentCallbacks = object : AgentCallbacks {
        override fun getActiveNotes(): List<Note> {
            val rawNotes = allNotes.value
            return PrivacyGuard.getAiVisibleNotes(rawNotes)
        }
        override fun getArchivedNotes(): List<Note> = PrivacyGuard.getAiVisibleNotes(archivedNotes.value)
        override fun getCategories(): List<Category> = allCategories.value
        override fun getTavilyApiKey(): String? = settingsFeatureManager.getTavilyApiKeySync()
        override fun getOpenAiApiKey(): String? = settingsFeatureManager.getProviderKeys(AIProvider.OPENAI).firstOrNull()
        override fun getGeminiApiKey(): String? = settingsFeatureManager.getProviderKeys(AIProvider.GEMINI).firstOrNull()

        override suspend fun processNoteWithAi(note: Note) {
            noteOperationsManager.processNoteWithAi(note)
        }

        override suspend fun findNoteByDescription(description: String, notes: List<Note>): Note? {
            return noteOperationsManager.findNoteByDescription(description, notes)
        }

        override fun requestAudioPlayback(track: AudioTrack) {
            systemFeatureManager.playAudio(track)
        }

        override fun onToolExecutionStarted(toolName: String, toolDisplayName: String) {
            val finalName = resolveResourceString(toolDisplayName) ?: toolDisplayName
            this@ChatFeatureManager.onToolExecutionStarted(finalName)
        }

        override fun onToolExecutionCompleted(toolName: String) {
            this@ChatFeatureManager.onToolExecutionCompleted()
        }

        override fun onStatusUpdate(status: String) {
            val finalStatus = resolveResourceString(status) ?: status
            this@ChatFeatureManager.onToolExecutionStarted(finalStatus)
        }

        override fun onCitationsFound(citations: List<WebCitation>) {
            this@ChatFeatureManager.onCitationsFound(citations)
        }

        override fun launchApp(packageName: String) {
            systemFeatureManager.launchApp(packageName)
        }

        override fun getScreenContext(): ScreenContext? {
            val activeId = activeNoteId.value ?: return null
            val note = allNotes.value.find { it.id == activeId } ?: return null

            return ScreenContext(
                selectedText = null,
                referringApp = application.packageName,
                capturedAt = System.currentTimeMillis(),
                contextData = mapOf(
                    "active_note_id" to note.id,
                    "active_note_title" to note.title,
                    "active_note_content" to (note.content ?: ""),
                    "active_note_type" to note.type.name,
                    "current_screen" to currentScreen.value
                )
            )
        }

        override fun onDisplayImages(images: List<ImageDisplayItem>) {
            this@ChatFeatureManager.onDisplayImages(images)
        }

        override fun onPlanStatusChanged(status: String?) {
            this@ChatFeatureManager.onPlanStatusChanged(status)
        }

        override suspend fun markNoteAsAnalyzedForMemory(noteId: String) {
            noteOperationsManager.markNoteAsAnalyzedForMemory(noteId)
        }

        override fun getDeviceAudio(): List<AudioTrack> = systemFeatureManager.getDeviceAudio()

        override fun navigateTo(screen: String) {
            this@ChatFeatureManager.navigateTo(screen)
        }

        override fun getCurrentScreen(): String = currentScreen.value

        override fun getSystemStatus(): Map<String, String> {
            return systemFeatureManager.getSystemStatus(
                isDarkTheme = isDarkTheme.value,
                connectionStatus = connectionStatus.value.name,
                cacheSize = ContentTypeDetector.formatFileSize(application, cacheSizeBytes.value),
                unreadMemoryCount = unreadForMemoryCount.value
            )
        }

        override fun addNote(content: String, category: String?) {
            scope.launch {
                noteOperationsManager.addNote(
                    content = content,
                    type = NoteType.BRAIN_DUMP,
                    excludeFromAiChat = false,
                    initialCategory = category
                )
            }
        }

        override fun updateNote(noteId: String, title: String?, content: String?) {
            scope.launch {
                noteOperationsManager.updateNote(noteId, title, content, allNotes.value, archivedNotes.value)
            }
        }

        override fun deleteNoteById(noteId: String) {
            scope.launch {
                noteOperationsManager.deleteNoteById(noteId, allNotes.value, archivedNotes.value)
            }
        }

        override fun archiveNote(noteId: String) {
            noteOperationsManager.archiveNote(noteId)
        }

        override fun unarchiveNote(noteId: String) {
            noteOperationsManager.unarchiveNote(noteId)
        }

        override fun summarizeNote(noteId: String) {
            noteOperationsManager.summarizeNote(noteId, allNotes.value, archivedNotes.value)
        }

        override suspend fun onCreateCategory(name: String): Category {
            return noteOperationsManager.getOrCreateCategory(name)
        }

        override suspend fun getCategoryStats(): List<CategoryStatInfo> {
            return noteOperationsManager.getCategoryStats(allCategories.value, allNotes.value)
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

        override suspend fun retrieveMemories(query: String?, limit: Int): List<AIMemory> {
            return memoryFeatureManager.retrieveMemories(query, limit)
        }

        override suspend fun analyzePatterns(): UserPatternsReport {
            return memoryFeatureManager.analyzePatterns(allNotes.value, allCategories.value)
        }

        override suspend fun learnFromNotes(maxNotes: Int): LearningReport {
            return memoryFeatureManager.learnFromNotes(allNotes.value, maxNotes)
        }

        override fun backupData() {
            systemFeatureManager.backupData()
        }

        override fun setPrivacyMode(mode: String) {
            systemFeatureManager.setPrivacyMode(mode)
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

        override fun shareContent(text: String, title: String?) {
            systemFeatureManager.shareContent(text, title)
        }

        override fun findPackageName(appName: String): String? {
            return systemFeatureManager.findPackageName(appName)
        }

        override fun findMatchingAudio(query: String): AudioSearchResult {
            return audioFeatureManager.findAudioTrack(query)
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

        override fun addCalendarEvent(
            title: String,
            startTimeStr: String,
            endTimeStr: String?,
            description: String?,
            location: String?,
            isPrivate: Boolean
        ) {
            val startMillis = calendarFeatureManager.parseDateTime(startTimeStr) ?: return
            val endMillis = endTimeStr?.let { calendarFeatureManager.parseDateTime(it) }
                ?: (startMillis + 3600000L)

            calendarFeatureManager.addCalendarEvent(
                title = title,
                description = description,
                startTime = startMillis,
                endTime = endMillis,
                location = location,
                isPrivate = isPrivate
            )
        }

        override fun deleteCalendarEvent(eventId: String) {
            calendarFeatureManager.deleteCalendarEvent(eventId)
        }

        override suspend fun queryCalendarEvents(query: String?): List<CalendarEvent> {
            return if (query.isNullOrBlank()) {
                calendarFeatureManager.getTodayEvents()
            } else {
                calendarFeatureManager.searchEvents(query)
            }
        }

        override fun bulkDeleteEvents(eventIds: List<String>) {
            eventIds.forEach { id ->
                calendarFeatureManager.deleteCalendarEvent(id)
            }
        }

        override fun setTimer(name: String, timeStr: String, isAlarm: Boolean) {
            val triggerTime = calendarFeatureManager.parseDateTime(timeStr) ?: return
            calendarFeatureManager.setTimer(name, triggerTime, isAlarm)
        }

        override fun cancelTimer(timerId: String) {
            calendarFeatureManager.cancelTimer(timerId)
        }

        override fun addTodoToNote(noteId: String, text: String) {
            scope.launch {
                noteOperationsManager.addTodoToNote(noteId, text)
            }
        }

        override fun bulkArchiveNotes(noteIds: List<String>) {
            noteOperationsManager.bulkArchiveNotes(noteIds)
        }

        override fun bulkDeleteNotes(noteIds: List<String>) {
            noteOperationsManager.bulkDeleteNotes(noteIds, allNotes.value, archivedNotes.value)
        }

        override fun bulkMoveToCategory(noteIds: List<String>, categoryName: String) {
            noteOperationsManager.bulkMoveToCategory(noteIds, categoryName)
        }

        override fun onDeepResearch(topic: String, apiKey: String, focusAreas: List<String>?, searchDepth: Int) {
            workflowManager.performDeepResearch(topic, apiKey, focusAreas, searchDepth)
        }

        override fun onAnalyzeStyle(limit: Int): StyleAnalysisReport {
            return styleFeatureManager.analyzeStyle(allNotes.value, limit)
        }

        override suspend fun onWebSearch(
            query: String,
            maxResults: Int,
            topic: String,
            onCitationsFound: (List<WebCitation>) -> Unit
        ): com.example.smarty.agent.tools.base.WebSearchResult {
            val apiKey = settingsFeatureManager.getTavilyApiKeySync() ?: return com.example.smarty.agent.tools.base.WebSearchResult(
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
            onCitationsFound: (List<WebCitation>) -> Unit
        ): com.example.smarty.agent.tools.base.WebSearchResult {
            return searchFeatureManager.performParallelWebSearch(queries, maxResults, topic, onCitationsFound)
        }
    }

    // Exposed flows from ChatManager
    val isChatMode: StateFlow<Boolean> = chatManager.isChatMode
    val chatMessages: StateFlow<List<ChatMessage>> = chatManager.chatMessages
    val isChatProcessing: StateFlow<Boolean> = chatManager.isChatProcessing
    val currentSessionId: StateFlow<String?> = chatManager.currentSessionId
    val chatSessions: StateFlow<List<ChatSession>> = chatManager.chatSessions

    // Mention State
    private val _mentionState = MutableStateFlow(MentionState())
    val mentionState: StateFlow<MentionState> = _mentionState.asStateFlow()
    private var chatInputCursorPosition: Int = 0

    // Pending Text
    private val _pendingChatText = MutableStateFlow<String?>(null)
    val pendingChatText: StateFlow<String?> = _pendingChatText.asStateFlow()

    // Thinking Mode (default: false = Flash mode)
    private val _isThinkingModeEnabled = MutableStateFlow(false)
    val isThinkingModeEnabled: StateFlow<Boolean> = _isThinkingModeEnabled.asStateFlow()

    // UI Status
    private val _aiPlanStatus = MutableStateFlow<String?>(null)
    val aiPlanStatus: StateFlow<String?> = _aiPlanStatus.asStateFlow()

    private val _currentToolName = MutableStateFlow<String?>(null)
    val currentToolName: StateFlow<String?> = _currentToolName.asStateFlow()

    // Navigation
    private val _navigationRequest = MutableStateFlow<String?>(null)
    val navigationRequest: StateFlow<String?> = _navigationRequest.asStateFlow()

    // Proactive Suggestions
    private val _proactiveSuggestion = MutableStateFlow<String?>(null)
    val proactiveSuggestion: StateFlow<String?> = _proactiveSuggestion.asStateFlow()

    // Internal state for current response
    private val pendingCitations = CopyOnWriteArrayList<WebCitation>()
    private val pendingInlineImages = CopyOnWriteArrayList<InlineChatImage>()

    init {
        chatManager.initialize()
    }

    fun toggleChatMode(fromShake: Boolean = false) {
        chatManager.toggleChatMode()
        savedStateHandle[KEY_IS_CHAT_MODE] = !isChatMode.value
    }

    /** Update the current tool name display */
    fun updateCurrentToolName(name: String?) {
        _currentToolName.value = name
    }

    fun enterChatMode() {
        scope.launch {
            chatManager.enterChatMode()
            savedStateHandle[KEY_IS_CHAT_MODE] = true
        }
    }

    fun exitChatMode() {
        chatManager.exitChatMode()
        savedStateHandle[KEY_IS_CHAT_MODE] = false
    }

    fun createNewChatSession() {
        chatManager.createNewChatSession()
        scope.launch {
            chatManager.currentSessionId.collect { id ->
                if (id != null) {
                    savedStateHandle[KEY_CURRENT_SESSION_ID] = id
                }
            }
        }
    }

    fun switchToChatSession(sessionId: String) {
        chatManager.switchToChatSession(sessionId)
        savedStateHandle[KEY_CURRENT_SESSION_ID] = sessionId
    }

    fun deleteChatSession(sessionId: String) {
        chatManager.deleteChatSession(sessionId)
    }

    fun clearChatHistory() {
        chatManager.clearChatHistory()
    }

    fun enterChatWithNoteReference(noteTitle: String) {
        val mentionText = if (noteTitle.contains(' ')) {
            "@\"$noteTitle\" "
        } else {
            "@${noteTitle.replace(' ', '_')} "
        }
        _pendingChatText.value = mentionText
        enterChatMode()
    }

    fun clearPendingChatText() {
        _pendingChatText.value = null
    }

    fun toggleThinkingMode() {
        _isThinkingModeEnabled.value = !_isThinkingModeEnabled.value
    }

    fun syncGroqKeys() {
        scope.launch {
            agentProvider.syncGroqKeys()
        }
    }

    fun updateMentionState(text: String, cursorPosition: Int) {
        chatInputCursorPosition = cursorPosition
        scope.launch {
            val detection = MentionParser.detectActiveMention(text, cursorPosition)
            if (detection.isTypingMention && !detection.isEmailPattern) {
                val suggestions = mentionManager.getSuggestions(detection.query)
                _mentionState.value = MentionState(
                    isActive = true,
                    query = detection.query,
                    triggerIndex = detection.triggerIndex,
                    suggestions = suggestions,
                    highlightedIndex = 0
                )
            } else {
                if (_mentionState.value.isActive) {
                    _mentionState.value = MentionState()
                }
            }
        }
    }

    fun onMentionSelected(suggestion: MentionSuggestion, currentText: String): String {
        val state = _mentionState.value
        if (!state.isActive || state.triggerIndex < 0) return currentText

        val replacement = when (suggestion) {
            is MentionSuggestion.NoteSuggestion -> {
                val title = suggestion.note.title
                if (title.contains(' ')) "@\"$title\"" else "@${title.replace(' ', '_')}"
            }
            is MentionSuggestion.TypeFilter -> "@${suggestion.keyword}"
            is MentionSuggestion.CategorySuggestion -> {
                val name = suggestion.category.name
                if (name.contains(' ')) "@\"$name\"" else "@${name.replace(' ', '_')}"
            }
            is MentionSuggestion.SpecialFilter -> "@${suggestion.filterName}"
            is MentionSuggestion.CommandSuggestion -> "@${suggestion.commandName}"
        }

        val beforeMention = currentText.substring(0, state.triggerIndex)
        val afterCursor = if (chatInputCursorPosition < currentText.length) {
            currentText.substring(chatInputCursorPosition)
        } else ""

        _mentionState.value = MentionState()
        return "$beforeMention$replacement $afterCursor"
    }

    fun dismissMention() {
        _mentionState.value = MentionState()
    }

    fun sendChatMessage(content: String, attachments: List<Attachment> = emptyList()) {
        dispatchQuery(content, attachments)
    }

    fun dispatchQuery(content: String, attachments: List<Attachment> = emptyList()) {
        if (content.isBlank() && attachments.isEmpty()) return

        scope.launch {
            var processingSet = false
            try {
                // Set processing state with error handling
                try {
                    chatManager.setProcessing(true)
                    processingSet = true
                    chatManager.resetApiCallFlag()
                    chatManager.ensureSession()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to initialize chat processing: ${e.message}")
                    // Continue anyway, but mark that we couldn't set processing state
                    processingSet = false
                }

                val userMessage = chatManager.addUserMessage(content, attachments)

                // 1. FAST-PATH: Local Command Processor
                val commandResult = localCommandProcessor.process(content)
                when (commandResult) {
                    is CommandResult.Handled -> {
                        chatManager.markApiCallSuccessful()
                        val assistantMessage = ChatMessage(role = ChatRole.ASSISTANT, content = commandResult.response)
                        chatManager.addAssistantMessage(assistantMessage)
                        chatManager.saveMessagePair(
                            userMessage = userMessage,
                            assistantMessage = assistantMessage,
                            hasApiKeys = settingsFeatureManager.hasAnyApiKeys()
                        )
                        return@launch
                    }
                    is CommandResult.HandledAndPassToLLM -> {
                        val localMessage = ChatMessage(role = ChatRole.ASSISTANT, content = commandResult.response)
                        chatManager.addAssistantMessage(localMessage)
                    }
                    else -> Log.d(TAG, "Falling back to REASONING-PATH")
                }

                // 2. REASONING-PATH
                processReasoningPath(content, userMessage)

            } catch (e: Exception) {
                Log.e(TAG, "Error in dispatcher: ${e.message}", e)
                chatManager.addAssistantMessage(ChatMessage(role = ChatRole.ASSISTANT, content = application.getString(R.string.error_prefix, e.message ?: application.getString(R.string.unknown_error))))
            } finally {
                // Safely reset processing state only if we successfully set it
                if (processingSet) {
                    try {
                        // Use NonCancellable to ensure processing state is always reset
                        // but wrap in try-catch to prevent crashes during cleanup
                        withContext(NonCancellable) {
                            chatManager.setProcessing(false)
                            _currentToolName.value = null
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to reset processing state: ${e.message}")
                        // Fallback: try direct assignment
                        try {
                            chatManager.setProcessing(false)
                            _currentToolName.value = null
                        } catch (fallbackE: Exception) {
                            Log.e(TAG, "Complete failure to reset processing state: ${fallbackE.message}")
                        }
                    }
                }
            }
        }
    }

    private suspend fun processReasoningPath(content: String, userMessage: ChatMessage) {
        val history = chatManager.chatMessages.value
            .filter { it.role != ChatRole.SYSTEM }
            .map { msg ->
                val role = when (msg.role) {
                    ChatRole.USER -> "User"
                    ChatRole.ASSISTANT -> "Assistant"
                    else -> "System"
                }

                // ENHANCEMENT: Include citations in history so agent can "remember" sources
                val contentWithContext = buildString {
                    append(msg.content)
                    if (msg.citations.isNotEmpty()) {
                        append("\n\n[Context: Referenced Sources]")
                        msg.citations.take(5).forEach { citation ->
                            append("\n- ${citation.title}: ${citation.url}")
                        }
                    }
                }

                Pair(role, contentWithContext)
            }

        pendingCitations.clear()
        _mentionState.value = MentionState()

        val parsedMentions = MentionParser.parseAllMentions(content)
        val taggedNoteContext = if (parsedMentions.isNotEmpty()) {
            val resolvedMentions = mentionManager.resolveMentions(parsedMentions)
            noteContextBuilder.buildContext(resolvedMentions)
        } else null

        val thinkingModeContext = if (thinkingModeProcessor.hasThinkingCommand(content) && taggedNoteContext != null) {
            thinkingModeProcessor.processThinkingMode(content, taggedNoteContext.resolvedMentions.flatMap { it.notes })
        } else null

        val cleanedContent = if (parsedMentions.isNotEmpty()) MentionParser.cleanMessage(content, parsedMentions) else content
        val finalUserMessage = if (!_isThinkingModeEnabled.value) "/no_think $cleanedContent" else cleanedContent

        val result = smartyAgent.run(
            userMessage = finalUserMessage,
            conversationHistory = history,
            taggedNoteContext = taggedNoteContext,
            thinkingModeContext = thinkingModeContext,
            isThinkingModeEnabled = _isThinkingModeEnabled.value
        )

        handleAgentResult(result, userMessage)
    }

    private suspend fun handleAgentResult(result: AgentResult, userMessage: ChatMessage) {
        when (result) {
            is AgentResult.Success -> {
                chatManager.markApiCallSuccessful()
                val filteredResponse = filterPlanningText(result.response) ?: return
                val (responseWithoutSuggestions, suggestions) = extractSuggestionsFromResponse(filteredResponse)
                val (cleanedResponse, clarificationRequest) = extractClarificationFromResponse(responseWithoutSuggestions)

                val citations = pendingCitations.map { Citation(title = it.title, url = it.url, snippet = it.snippet) }
                pendingCitations.clear()
                val inlineImages = pendingInlineImages.toList()
                pendingInlineImages.clear()

                val parsedThinking = ThinkingParser.parse(cleanedResponse)

                val assistantMessage = ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = parsedThinking.answer,
                    thinkingContent = parsedThinking.thinking,
                    suggestions = suggestions,
                    citations = citations,
                    inlineImages = inlineImages,
                    clarificationRequest = clarificationRequest
                )

                chatManager.addAssistantMessage(assistantMessage)
                chatManager.saveMessagePair(userMessage, assistantMessage, true)

                if (settingsFeatureManager.isSoundEnabled()) {
                    completionSoundManager.playAgentCompletionSound(true)
                }
            }
            is AgentResult.Error -> {
                chatManager.addAssistantMessage(ChatMessage(role = ChatRole.ASSISTANT, content = application.getString(R.string.error_prefix, result.message), isError = true))
            }
            is AgentResult.NoProvider -> {
                chatManager.addAssistantMessage(ChatMessage(role = ChatRole.ASSISTANT, content = application.getString(com.example.smarty.R.string.api_key_required)))
            }
        }
    }

    private fun filterPlanningText(text: String): String? {
        if (text.isBlank()) return null
        return text.replace(Regex("<plan>.*?</plan>", RegexOption.DOT_MATCHES_ALL), "").trim()
    }

    private fun extractSuggestionsFromResponse(text: String): Pair<String, List<String>> {
        val suggestions = mutableListOf<String>()
        val suggestionRegex = Regex("\\[suggestion:(.*?)\\]")
        val cleanedText = suggestionRegex.replace(text) { matchResult ->
            suggestions.add(matchResult.groupValues[1].trim())
            ""
        }.trim()
        return Pair(cleanedText, suggestions)
    }

    private fun extractClarificationFromResponse(text: String): Pair<String, ClarificationRequest?> {
        val clarificationRegex = Regex("\\[clarification:(.*?)\\]")
        val match = clarificationRegex.find(text)
        return if (match != null) {
            val question = match.groupValues[1].trim()
            val cleanedText = text.replace(match.value, "").trim()
            Pair(cleanedText, ClarificationRequest(question = question, options = emptyList()))
        } else {
            Pair(text, null)
        }
    }

    fun navigateTo(screen: String) {
        scope.launch {
            _navigationRequest.value = screen
        }
    }

    fun clearNavigationRequest() {
        _navigationRequest.value = null
    }

    fun startProactiveMonitoring(unreadCountFlow: StateFlow<Int>, cacheSizeFlow: StateFlow<Long>, memoryCountFlow: StateFlow<Int>) {
        scope.launch {
            while (true) {
                val unreadCount = unreadCountFlow.value
                val cacheSize = cacheSizeFlow.value
                val memoryCount = memoryCountFlow.value

                val suggestion = when {
                    unreadCount > 15 -> application.getString(R.string.unread_notes_suggestion, unreadCount)
                    cacheSize > 500 * 1024 * 1024 -> application.getString(R.string.large_cache_suggestion)
                    memoryCount > 100 -> application.getString(R.string.detailed_memory_suggestion)
                    else -> null
                }

                if (_proactiveSuggestion.value != suggestion) {
                    _proactiveSuggestion.value = suggestion
                }
                delay(300_000)
            }
        }
    }

    fun acceptSuggestion() {
        val suggestion = _proactiveSuggestion.value ?: return
        _proactiveSuggestion.value = null
        dispatchQuery(suggestion)
    }

    fun dismissSuggestion() {
        _proactiveSuggestion.value = null
    }

    // Callbacks for SmartyAgent
    fun onCitationsFound(citations: List<WebCitation>) {
        pendingCitations.addAll(citations)
    }

    fun onDisplayImages(images: List<ImageDisplayItem>) {
        pendingInlineImages.clear()
        pendingInlineImages.addAll(images.map {
            InlineChatImage(uri = it.uri, fileName = it.fileName, noteTitle = it.noteTitle)
        })
    }

    fun onPlanStatusChanged(status: String?) {
        _aiPlanStatus.value = status
    }

    fun onToolExecutionStarted(toolDisplayName: String) {
        _currentToolName.value = toolDisplayName
    }

    fun onToolExecutionCompleted() {
        _currentToolName.value = null
    }

    /**
     * Resolves a string that might be a resource key with parameters (e.g., "key|param1|param2")
     */
    private fun resolveResourceString(input: String?): String? {
        if (input == null) return null

        val parts = input.split("|")
        val key = parts[0]
        val resId = application.resources.getIdentifier(key, "string", application.packageName)

        return if (resId != 0) {
            if (parts.size > 1) {
                // Try to parse numeric arguments if possible
                val args = parts.subList(1, parts.size).map {
                    it.toIntOrNull() ?: it
                }.toTypedArray<Any>()

                try {
                    application.getString(resId, *args)
                } catch (e: Exception) {
                    // Fallback to raw key if formatting fails
                    input
                }
            } else {
                application.getString(resId)
            }
        } else {
            // Not a resource key, return as is
            input
        }
    }
}
