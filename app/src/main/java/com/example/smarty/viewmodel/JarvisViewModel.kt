@file:Suppress("DEPRECATION")
package com.example.smarty.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarty.data.cache.AIResponseCache
import com.example.smarty.data.cache.CacheManager
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.AIProviderConfig
import com.example.smarty.data.local.JarvisDatabase
import com.example.smarty.data.local.SearchHistoryManager
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.model.Attachment
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatRole
import com.example.smarty.data.model.MentionState
import com.example.smarty.data.model.MentionSuggestion
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteAttachment
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.model.TodoItem
import com.example.smarty.data.model.ChunkAnalysis
import com.example.smarty.data.model.Citation
import com.example.smarty.data.model.getAllAttachmentUris
import com.example.smarty.data.model.getAttachments
import com.example.smarty.data.model.getTodos
import com.example.smarty.data.model.withAttachments
import com.example.smarty.data.model.withTodos
import com.example.smarty.data.remote.AIService
import com.example.smarty.agent.models.ScreenContext
import com.example.smarty.agent.AgentCallbacks
import com.example.smarty.agent.AgentResult
import com.example.smarty.agent.JarvisAgentOptimized
import com.example.smarty.agent.JarvisAgentProvider
import com.example.smarty.agent.ImageDisplayItem
import com.example.smarty.data.model.InlineChatImage
import com.example.smarty.data.remote.providers.TavilySearchProvider
import com.example.smarty.data.model.ClarificationRequest

import com.example.smarty.ui.components.PendingShareData
import com.google.gson.Gson
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import com.example.smarty.data.repository.ChatRepository
import com.example.smarty.data.repository.DeviceAudioRepository
import com.example.smarty.data.repository.JarvisRepository
import com.example.smarty.data.model.ChatSession
import com.example.smarty.util.CompletionSoundManager
import com.example.smarty.util.ContentTypeDetector
import com.example.smarty.viewmodel.managers.NoteProcessingQueueManager
import com.example.smarty.viewmodel.managers.MemorySyncManager
import com.example.smarty.util.FileStorageHelper
import com.example.smarty.util.PDFTextExtractor
import com.example.smarty.util.PDFExtractionResult
import com.example.smarty.util.ThinkingParser
import com.example.smarty.util.PDFChunkedResult
import com.example.smarty.util.PDFChunk
import com.example.smarty.util.ProcessingStrategy
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.mention.MentionParser
import com.example.smarty.util.mention.NoteContextBuilder
import com.example.smarty.util.mention.ThinkingModeProcessor
import com.example.smarty.viewmodel.managers.*
import com.example.smarty.data.remote.DocumentAnalysisResponse
import com.example.smarty.util.ShakeDetector
import com.example.smarty.util.NetworkMonitor
import com.example.smarty.ui.components.ConnectionStatus
import com.example.smarty.voice.VoskWakeWordManager
// TTS removed - was: import com.example.smarty.voice.ResponseTTSManager
import com.example.smarty.util.api.RateLimiter
import android.media.AudioManager
import android.telephony.TelephonyManager
import android.telephony.PhoneStateListener
import android.os.Build
import com.example.smarty.util.api.GroqKeyManager
import com.example.smarty.util.api.KeyUsageStats
import com.example.smarty.service.AlarmScheduler
import com.example.smarty.service.AudioPlayerService
import com.example.smarty.service.CommandResult
import com.example.smarty.service.LocalCommandProcessor
import com.example.smarty.data.model.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import com.example.smarty.ui.components.AttachmentOption
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.ViewModelProvider
import androidx.savedstate.SavedStateRegistryOwner
import kotlinx.coroutines.withTimeout
import com.example.smarty.widget.QuickNoteWidgetProvider
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single file info for sharing
 */
data class SharedFileInfo(
    val fileUri: String,
    val fileName: String?,
    val mimeType: String?,
    val fileSize: Long?
)

/**
 * Shared content that can include text or multiple files
 */
data class SharedContent(
    val text: String? = null,
    val fileUri: String? = null,  // Legacy single file
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSize: Long? = null,
    val files: List<SharedFileInfo> = emptyList()  // Multiple files
) {
    /** Get all files (combines legacy single + multiple) */
    fun getAllFiles(): List<SharedFileInfo> {
        if (files.isNotEmpty()) return files
        if (fileUri != null) {
            return listOf(SharedFileInfo(fileUri, fileName, mimeType, fileSize))
        }
        return emptyList()
    }
}

class JarvisViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    // SavedStateHandle keys for state preservation across process death (BUG-053)
    companion object {
        private const val TAG = "JarvisViewModel"
        private const val KEY_SELECTED_NOTE_ID = "selectedNoteId"
        private const val KEY_SELECTED_CATEGORY_ID = "selectedCategoryId"
        private const val KEY_IS_CHAT_MODE = "isChatMode"

        /**
         * BUG FIX (L-002): Maximum notes to load into memory at once.
         * Prevents OOM crashes on devices with many notes.
         * Full pagination should be implemented in P9 (Performance).
         * 500 notes × ~5KB average = ~2.5MB, safe for most devices.
         */
        private const val MAX_NOTES_IN_MEMORY = 500
    }

    // Lazy initialization to avoid blocking main thread during permission requests
    // These are initialized on first access, not during ViewModel construction
    private val securePreferences: SecurePreferences by lazy {
        SecurePreferences.getInstance(application)
    }
    private val aiService: AIService by lazy { AIService(securePreferences) }
    private val pdfExtractor: PDFTextExtractor by lazy { PDFTextExtractor(application) }

    // Repository needs to be initialized before agent - lazy to avoid blocking
    private val database: JarvisDatabase by lazy { JarvisDatabase.getDatabase(application) }
    private val repository: JarvisRepository by lazy {
        JarvisRepository(
            database.noteDao(),
            database.categoryDao(),
            database.calendarDao(),
            database.noteVersionDao()
        )
    }

    // Web search provider for agent actions
    private val tavilySearchProvider: TavilySearchProvider by lazy {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        TavilySearchProvider(httpClient, Gson())
    }

    // Alarm scheduler for timer/alarm tools - lazy to avoid blocking
    private val alarmScheduler: AlarmScheduler by lazy {
        AlarmScheduler.getInstance(application)
    }

    // Device audio repository for MediaStore access - lazy to avoid blocking
    private val deviceAudioRepository: DeviceAudioRepository by lazy {
        DeviceAudioRepository(application)
    }

    // Rate limiter for API call management (30 calls/min, 14.4k/day) - lazy
    private val rateLimiter: RateLimiter by lazy {
        RateLimiter.getInstance(application)
    }

    // GROQ Key Manager for per-key usage tracking - lazy
    private val groqKeyManager: GroqKeyManager by lazy {
        GroqKeyManager.getInstance(application)
    }

    // Completion sound manager for AI agent and notecard processing
    private val completionSoundManager: CompletionSoundManager by lazy {
        CompletionSoundManager.getInstance(application)
    }

    // Cache manager for memory management
    private val cacheManager: com.example.smarty.data.cache.CacheManager by lazy {
        com.example.smarty.data.cache.CacheManager.getInstance(application)
    }

    // System Feature Manager - Hybridized action layer for UI, Local Commands, and AI
    private val systemFeatureManager: SystemFeatureManager by lazy {
        SystemFeatureManager(
            context = application,
            scope = viewModelScope,
            audioManager = audioPlaybackManager,
            securePreferences = securePreferences,
            deviceAudioRepository = deviceAudioRepository,
            onNavigateRequest = { screen -> navigateTo(screen) }
        )
    }

    // Settings Feature Manager - Centralized preferences and keys
    private val settingsFeatureManager: SettingsFeatureManager by lazy {
        SettingsFeatureManager(securePreferences, aiService, rateLimiter, viewModelScope)
    }

    // Search Feature Manager - Centralized retrieval for UI and AI
    private val searchFeatureManager: com.example.smarty.viewmodel.managers.SearchFeatureManager by lazy {
        com.example.smarty.viewmodel.managers.SearchFeatureManager(
            repository = repository,
            allNotes = _allNotesForAgent,
            searchHistoryManager = SearchHistoryManager(application),
            tavilySearchProvider = tavilySearchProvider
        )
    }

    // Style Feature Manager - Analyzes user writing patterns
    private val styleFeatureManager: com.example.smarty.viewmodel.managers.StyleFeatureManager by lazy {
        com.example.smarty.viewmodel.managers.StyleFeatureManager()
    }

    // Workflow Manager - Handles multi-step agentic tasks
    private val workflowManager: com.example.smarty.viewmodel.managers.WorkflowManager by lazy {
        com.example.smarty.viewmodel.managers.WorkflowManager(
            repository = repository,
            tavilySearchProvider = tavilySearchProvider,
            scope = viewModelScope,
            onStatusUpdate = { status -> _currentToolName.value = status }
        )
    }

    // Memory Sync Manager - handles behavior extraction from notes
    private val memorySyncManager by lazy {
        MemorySyncManager(
            database = database,
            aiMemoryDao = database.aiMemoryDao(),
            aiService = aiService
        )
    }

    // Memory Feature Manager - Centralized long-term memory
    private val memoryFeatureManager: com.example.smarty.viewmodel.managers.MemoryFeatureManager by lazy {
        com.example.smarty.viewmodel.managers.MemoryFeatureManager(
            aiMemoryDao = database.aiMemoryDao(),
            syncManager = memorySyncManager,
            scope = viewModelScope
        )
    }

    // Execution Plan Manager - Shared between UI and AI Agent
    private val executionPlanManager: com.example.smarty.viewmodel.managers.ExecutionPlanManager by lazy {
        com.example.smarty.viewmodel.managers.ExecutionPlanManager()
    }

    /** Expose reactive plan state to UI */
    val activeExecutionPlan: StateFlow<com.example.smarty.viewmodel.managers.ExecutionPlan?> = executionPlanManager.activePlan

    // Koog-based AI Agent (GROQ-only with multi-key rotation) - lazy
    private val agentProvider: JarvisAgentProvider by lazy {
        JarvisAgentProvider(securePreferences, groqKeyManager)
    }
    private val JarvisAgent: JarvisAgentOptimized by lazy {
        JarvisAgentOptimized(
            context = application,
            agentProvider = agentProvider,
            repository = repository,
            tavilySearchProvider = tavilySearchProvider,
            alarmScheduler = alarmScheduler,
            callbacks = agentCallbacks,
            aiMemoryDao = database.aiMemoryDao(),
            executionPlanManager = executionPlanManager, // HYBRID: Shared state machine
            rateLimiter = rateLimiter
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOCAL COMMAND PROCESSOR - Handle commands without AI (open app, play music)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * LocalCommandProcessor handles hardcoded commands like "open youtube", "play music"
     * These commands don't need AI processing and execute immediately.
     */
    private val localCommandProcessor: LocalCommandProcessor by lazy {
        LocalCommandProcessor(
            context = getApplication(),
            getNotes = { notes.value },
            systemFeatureManager = systemFeatureManager,
            getDeviceAudio = { systemFeatureManager.getDeviceAudio() }
        )
    }

    /**
     * Launch an app by package name.
     * Used by LocalCommandProcessor for "open [app]" commands.
     */
    private fun launchApp(packageName: String) {
        try {
            val context = getApplication<Application>()
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d(TAG, "Successfully launched app: $packageName")
            } else {
                Log.w(TAG, "No launch intent found for package: $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app $packageName: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // @MENTION SYSTEM - Note tagging/reference in chat
    // ═══════════════════════════════════════════════════════════════════════════

    /** MentionFeatureManager for resolving @mentions to notes */
    private val mentionManager: MentionFeatureManager by lazy {
        MentionFeatureManager(repository)
    }

    /** ThinkingModeProcessor for @thinking deep document analysis */
    private val thinkingModeProcessor: ThinkingModeProcessor by lazy {
        ThinkingModeProcessor(application)
    }

    /** NoteContextBuilder for building AI context from mentions */
    private val noteContextBuilder: NoteContextBuilder by lazy {
        NoteContextBuilder(mentionManager)
    }

    /** Current mention state for autocomplete dropdown */
    private val _mentionState = MutableStateFlow(MentionState())
    val mentionState: StateFlow<MentionState> = _mentionState.asStateFlow()

    /** Current cursor position in chat input (for mention detection) */
    private var chatInputCursorPosition: Int = 0

    // ═══════════════════════════════════════════════════════════════════════════
    // PLAN PROGRESS UI STATE
    // ═══════════════════════════════════════════════════════════════════════════

    /** Current AI plan status (e.g., "Step 2/5: Searching for recipes...") */
    private val _aiPlanStatus = MutableStateFlow<String?>(null)
    val aiPlanStatus: StateFlow<String?> = _aiPlanStatus.asStateFlow()

    /** Current tool being executed by the AI agent */
    private val _currentToolName = MutableStateFlow<String?>(null)
    val currentToolName: StateFlow<String?> = _currentToolName.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // AI NAVIGATION CONTROL
    // ═══════════════════════════════════════════════════════════════════════════

    /** AI-triggered navigation request. Observed by UI to perform navigation. */
    private val _navigationRequest = MutableStateFlow<String?>(null)
    val navigationRequest: StateFlow<String?> = _navigationRequest.asStateFlow()

    /**
     * Request navigation to a specific screen.
     * Called by AI tools or internal logic.
     */
    fun navigateTo(screen: String) {
        viewModelScope.launch {
            _navigationRequest.value = screen
            Log.d(TAG, "AI requested navigation to: $screen")
        }
    }

    /**
     * Clear the current navigation request after it has been handled by the UI.
     */
    fun clearNavigationRequest() {
        _navigationRequest.value = null
    }

    // GROQ key usage stats exposed for UI - lazy
    val groqKeyUsageStats: StateFlow<List<KeyUsageStats>> by lazy { groqKeyManager.usageStats }

    // Local LLM Server IP/Port/HTTPS state (USB/WiFi connectivity)
    val localServerIP: StateFlow<String> = settingsFeatureManager.localServerIP
    val localServerPort: StateFlow<String> = settingsFeatureManager.localServerPort
    val localServerUseHttps: StateFlow<Boolean> = settingsFeatureManager.localServerUseHttps

    // ═══════════════════════════════════════════════════════════════════════════
    // THINKING MODE - Control reasoning display for Falcon-H1R-7B model
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Thinking mode toggle state for reasoning models (Falcon-H1R-7B).
     * When enabled, the model will show its reasoning process in <think> tags.
     * When disabled, the model skips explicit thinking output for faster responses.
     */
    private val _isThinkingModeEnabled = MutableStateFlow(true)
    val isThinkingModeEnabled: StateFlow<Boolean> = _isThinkingModeEnabled.asStateFlow()

    /**
     * Toggle thinking mode on/off.
     * Used by UI toggle button to control reasoning display.
     */
    fun toggleThinkingMode() {
        _isThinkingModeEnabled.value = !_isThinkingModeEnabled.value
        Log.d(TAG, "Thinking mode ${if (_isThinkingModeEnabled.value) "enabled" else "disabled"}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PROACTIVE INTELLIGENCE - Monitor system state and suggest AI actions
    // ═══════════════════════════════════════════════════════════════════════════

    private val _proactiveSuggestion = MutableStateFlow<String?>(null)
    val proactiveSuggestion: StateFlow<String?> = _proactiveSuggestion.asStateFlow()

    /**
     * Monitor system state for proactive AI engagement opportunities.
     */
    private fun startProactiveMonitoring() {
        viewModelScope.launch {
            while (isActive) {
                val unreadCount = unreadForMemoryCount.value
                val cacheSize = _cacheSizeBytes.value
                val memoryCount = aiMemories.value.size

                val suggestion = when {
                    unreadCount > 15 -> "You have $unreadCount unread notes. Should I analyze them to update your AI memory?"
                    cacheSize > 500 * 1024 * 1024 -> "Your app cache is getting large (${ContentTypeDetector.formatFileSize(cacheSize)}). Want me to clear it?"
                    memoryCount > 100 -> "Your AI memory is quite detailed. Should I consolidate it to keep things organized?"
                    else -> null
                }

                if (_proactiveSuggestion.value != suggestion) {
                    _proactiveSuggestion.value = suggestion
                    if (suggestion != null) Log.i(TAG, "Proactive suggestion ready: $suggestion")
                }

                delay(300_000) // Check every 5 minutes
            }
        }
    }

    /**
     * Accept a proactive suggestion and run it through the AI agent.
     */
    fun acceptSuggestion() {
        val suggestion = _proactiveSuggestion.value ?: return
        _proactiveSuggestion.value = null
        dispatchQuery(suggestion)
    }

    fun dismissSuggestion() {
        _proactiveSuggestion.value = null
    }

    fun setLocalServerIP(ip: String) {
        settingsFeatureManager.setLocalServerIP(ip)
        Log.d(TAG, "Local server IP set to: $ip")
    }

    fun setLocalServerPort(port: String) {
        settingsFeatureManager.setLocalServerPort(port)
        Log.d(TAG, "Local server port set to: $port")
    }

    fun setLocalServerUseHttps(useHttps: Boolean) {
        settingsFeatureManager.setLocalServerUseHttps(useHttps)
        Log.d(TAG, "Local server HTTPS set to: $useHttps")
    }

    /**
     * UNFILTERED notes source for AI agent.
     * Delegated to NoteOperationsManager for centralized data flow.
     */
    private val _allNotesForAgent: StateFlow<List<Note>> by lazy {
        noteOperationsManager.getAllNotes()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCREEN CONTEXT - Track active item being viewed (e.g., a specific note)
    // ═══════════════════════════════════════════════════════════════════════════

    private val _activeNoteId = MutableStateFlow<String?>(null)
    val activeNoteId: StateFlow<String?> = _activeNoteId.asStateFlow()

    fun setActiveNote(noteId: String?) {
        _activeNoteId.value = noteId
    }

    // Agent callbacks for Koog tools that need ViewModel state
    // SECURITY: Pre-filter notes at callback level for defense-in-depth
    // BUG FIX: Use _allNotesForAgent instead of notes.value to avoid UI filter interference
    private val agentCallbacks = object : AgentCallbacks {
        override fun getActiveNotes(): List<Note> {
            val rawNotes = _allNotesForAgent.value
            val visibleNotes = PrivacyGuard.getAiVisibleNotes(rawNotes)

            // DIAGNOSTIC: Log note counts to help debug agent issues
            Log.d(TAG, " getActiveNotes callback: raw=${rawNotes.size}, visible=${visibleNotes.size}")

            // Warn if notes appear empty (potential StateFlow race condition)
            if (rawNotes.isEmpty()) {
                Log.w(TAG, "️ getActiveNotes: StateFlow returned EMPTY - may be cold start race condition")
            }

            return visibleNotes
        }
        override fun getArchivedNotes(): List<Note> = PrivacyGuard.getAiVisibleNotes(archivedNotes.value)
        override fun getCategories(): List<Category> = categories.value
        override fun getTavilyApiKey(): String? = settingsFeatureManager.getTavilyApiKeySync()
        // BATCH-3C: OpenAI API key for AgentOptimizer semantic cache (embeddings)
        override fun getOpenAiApiKey(): String? = settingsFeatureManager.getProviderKeys(AIProvider.OPENAI).firstOrNull()
        // Gemini API key for AgentOptimizer semantic cache fallback
        override fun getGeminiApiKey(): String? = settingsFeatureManager.getProviderKeys(AIProvider.GEMINI).firstOrNull()

        override suspend fun processNoteWithAi(note: Note) {
            noteOperationsManager.processNoteWithAi(note)
        }

        override suspend fun findNoteByDescription(description: String, notes: List<Note>): Note? {
            return noteOperationsManager.findNoteByDescription(description, notes)
        }

        override fun requestAudioPlayback(track: AudioTrack) {
            // BUG FIX (ISSUE 3): Add logging to verify tool callback execution
            Log.i(TAG, "▶ requestAudioPlayback CALLBACK INVOKED: track='${track.title}', uri=${track.uri}")

            // Use hybridized system feature manager
            systemFeatureManager.playAudio(track)
            Log.d(TAG, " audio playback triggered via systemFeatureManager")
        }

        override fun onToolExecutionStarted(toolName: String, toolDisplayName: String) {
            _currentToolName.value = toolDisplayName
        }

        override fun onToolExecutionCompleted(toolName: String) {
            _currentToolName.value = null
        }

        override fun onStatusUpdate(status: String) {
            _currentToolName.value = status
        }

        override fun onCitationsFound(citations: List<com.example.smarty.agent.WebCitation>) {
            // Store citations for the current chat response
            pendingCitations.addAll(citations)
            Log.d(TAG, "Citations found: ${citations.size} sources")
        }

        override fun launchApp(packageName: String) {
            // Use hybridized system feature manager
            systemFeatureManager.launchApp(packageName)
        }

        override fun getScreenContext(): ScreenContext? {
            val activeId = _activeNoteId.value ?: return null
            val note = _allNotesForAgent.value.find { it.id == activeId } ?: return null

            return ScreenContext(
                selectedText = null, // Could be populated if we track UI selection
                referringApp = application.packageName,
                capturedAt = System.currentTimeMillis(),
                contextData = mapOf(
                    "active_note_id" to note.id,
                    "active_note_title" to note.title,
                    "active_note_content" to (note.content ?: ""),
                    "active_note_type" to note.type.name,
                    "current_screen" to _currentScreen.value
                )
            )
        }

        override fun onDisplayImages(images: List<ImageDisplayItem>) {
            // Store images for the current chat response
            pendingInlineImages.clear()
            pendingInlineImages.addAll(images.map {
                InlineChatImage(uri = it.uri, fileName = it.fileName, noteTitle = it.noteTitle)
            })
            Log.d(TAG, "Images found: ${images.size} images to display inline")
        }
        
        override fun onPlanStatusChanged(status: String?) {
            _aiPlanStatus.value = status
        }

        override suspend fun markNoteAsAnalyzedForMemory(noteId: String) {
            noteOperationsManager.markNoteAsAnalyzedForMemory(noteId)
            Log.d(TAG, "Marked note $noteId as analyzed for AI memory via manager")
        }

        // NEW: Get audio files from device storage (MediaStore)
        override fun getDeviceAudio(): List<AudioTrack> {
            return systemFeatureManager.getDeviceAudio()
        }

        // HYBRID-CONTROL: Internal app navigation
        override fun navigateTo(screen: String) {
            this@JarvisViewModel.navigateTo(screen)
        }

        override fun getCurrentScreen(): String {
            return _currentScreen.value
        }

        override fun getSystemStatus(): Map<String, String> {
            return systemFeatureManager.getSystemStatus(
                isDarkTheme = isDarkTheme.value,
                connectionStatus = connectionStatus.value.name,
                cacheSize = ContentTypeDetector.formatFileSize(_cacheSizeBytes.value),
                unreadMemoryCount = unreadForMemoryCount.value
            )
        }

        override fun addNote(content: String, category: String?) {
            viewModelScope.launch {
                // Use NoteOperationsManager for consistent processing (OCR, AI analysis)
                noteOperationsManager.addNote(
                    content = content,
                    type = com.example.smarty.data.model.NoteType.BRAIN_DUMP,
                    excludeFromAiChat = false,
                    initialCategory = category
                )
            }
        }

        override fun updateNote(noteId: String, title: String?, content: String?) {
            viewModelScope.launch {
                noteOperationsManager.updateNote(
                    noteId = noteId,
                    newTitle = title,
                    newContent = content,
                    activeNotes = notes.value,
                    archivedNotes = archivedNotes.value
                )
            }
        }

        override fun deleteNoteById(noteId: String) {
            viewModelScope.launch {
                noteOperationsManager.deleteNoteById(
                    noteId = noteId,
                    activeNotes = notes.value,
                    archivedNotes = archivedNotes.value
                )
            }
        }

        override fun archiveNote(noteId: String) {
            noteOperationsManager.archiveNote(noteId)
        }

        override fun unarchiveNote(noteId: String) {
            noteOperationsManager.unarchiveNote(noteId)
        }

        override fun summarizeNote(noteId: String) {
            noteOperationsManager.summarizeNote(noteId, notes.value, archivedNotes.value)
        }

        override suspend fun onCreateCategory(name: String): Category {
            return noteOperationsManager.getOrCreateCategory(name)
        }

        override suspend fun getCategoryStats(): List<com.example.smarty.viewmodel.managers.CategoryStatInfo> {
            return noteOperationsManager.getCategoryStats(categories.value, notes.value)
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
            return memoryFeatureManager.analyzePatterns(notes.value, noteOperationsManager.getAllCategoriesSync())
        }

        override suspend fun learnFromNotes(maxNotes: Int): com.example.smarty.viewmodel.managers.LearningReport {
            return memoryFeatureManager.learnFromNotes(notes.value, maxNotes)
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

        override fun getMemoryStats(): Map<String, Any> {
            // Delegate to manager but run blocking for simplicity in the callback if needed
            // However, getMemoryStats is not suspend in the interface, but it IS in the manager
            // Let's check the interface definition in JarvisAgentOptimized.kt again
            return runBlocking { memoryFeatureManager.getMemoryStats() }
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

        override fun findMatchingAudio(query: String): AudioTrack? {
            return audioFeatureManager.findAudioTrack(query)
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
            calendarManager.setTimer(name, triggerTime, isAlarm)
        }

        override fun cancelTimer(timerId: String) {
            calendarManager.cancelTimer(timerId)
        }

        override fun addTodoToNote(noteId: String, text: String) {
            viewModelScope.launch {
                noteOperationsManager.addTodoToNote(noteId, text)
            }
        }

        override fun bulkArchiveNotes(noteIds: List<String>) {
            noteOperationsManager.bulkArchiveNotes(noteIds)
        }

        override fun bulkDeleteNotes(noteIds: List<String>) {
            noteOperationsManager.bulkDeleteNotes(noteIds, notes.value, archivedNotes.value)
        }

        override fun bulkMoveToCategory(noteIds: List<String>, categoryName: String) {
            noteOperationsManager.bulkMoveToCategory(noteIds, categoryName)
        }

        override fun onDeepResearch(topic: String, apiKey: String, focusAreas: List<String>?, searchDepth: Int) {
            workflowManager.performDeepResearch(topic, apiKey, focusAreas, searchDepth)
        }

        override fun onAnalyzeStyle(limit: Int): com.example.smarty.viewmodel.managers.StyleAnalysisReport {
            return styleFeatureManager.analyzeStyle(notes.value, limit)
        }

        override suspend fun onWebSearch(
            query: String,
            maxResults: Int,
            topic: String,
            onCitationsFound: (List<com.example.smarty.agent.WebCitation>) -> Unit
        ): com.example.smarty.agent.tools.base.WebSearchResult {
            val apiKey = settingsFeatureManager.getTavilyApiKeySync() ?: return com.example.smarty.agent.tools.base.WebSearchResult(
                success = false,
                query = query,
                reason = "Web search not configured"
            )
            return searchFeatureManager.performWebSearch(query, apiKey, maxResults, topic, onCitationsFound)
        }
    }

    // Temporary storage for citations during agent execution
    private val pendingCitations = CopyOnWriteArrayList<com.example.smarty.agent.WebCitation>()

    // Temporary storage for inline images during agent execution
    private val pendingInlineImages = CopyOnWriteArrayList<InlineChatImage>()

    // Chat repository for persistence - lazy to avoid blocking
    private val chatRepository: ChatRepository by lazy {
        ChatRepository(database.chatDao())
    }

    // Calendar DAO for event management - lazy
    private val calendarDao by lazy { database.calendarDao() }

    // ═══════════════════════════════════════════════════════════════════════════
    // AI MEMORY - Stores learned user preferences and patterns
    // ═══════════════════════════════════════════════════════════════════════════

    // Memory managers initialized above in lazy properties (lines 273-289)

    // AI Memories StateFlow for UI observation - Observed through manager
    val aiMemories: StateFlow<List<com.example.smarty.data.model.AIMemory>> by lazy {
        memoryFeatureManager.allMemories
    }

    /**
     * Delete a specific AI memory
     */
    fun deleteAIMemory(memory: com.example.smarty.data.model.AIMemory) {
        viewModelScope.launch {
            memoryFeatureManager.deleteMemory(memory.id)
        }
    }

    /**
     * Clear all AI memories
     */
    fun clearAllAIMemories() {
        viewModelScope.launch {
            memoryFeatureManager.clearAllMemories()
        }
    }

    // State for sync operation progress - delegated to MemorySyncManager
    val isMemorySyncInProgress: StateFlow<Boolean> by lazy { memorySyncManager.isSyncing }
    val memorySyncResult: StateFlow<String?> by lazy { memorySyncManager.syncResult }

    // Notes pending analysis count - now a StateFlow for real-time UI updates
    val unreadForMemoryCount: StateFlow<Int> by lazy { memorySyncManager.unreadCount }

    /**
     * Refresh the unread count manually
     */
    fun refreshUnreadForMemoryCount() {
        memorySyncManager.refreshUnreadCount()
    }

    /**
     * Sync AI memories by analyzing notes that haven't been read for memory.
     * Delegated to MemorySyncManager - uses AIService.simpleChat() for AI calls.
     */
    fun syncAIMemoriesFromNotes() {
        Log.d(TAG, "UI triggered syncAIMemoriesFromNotes")
        viewModelScope.launch(Dispatchers.IO) {
            memorySyncManager.syncMemoriesFromNotes()
        }
    }

    /**
     * Clear the sync result message
     */
    fun clearMemorySyncResult() {
        memorySyncManager.clearSyncResult()
    }

    // Shake detector for toggling chat mode
    private var shakeDetector: ShakeDetector? = null

    // Vosk wake word manager for offline "Terminator" detection
    private var voskWakeWordManager: VoskWakeWordManager? = null

    // Wake word detection state
    private val _isWakeWordActive = MutableStateFlow(false)
    val isWakeWordActive: StateFlow<Boolean> = _isWakeWordActive.asStateFlow()

    private val _wakeWordTriggered = MutableStateFlow(false)
    val wakeWordTriggered: StateFlow<Boolean> = _wakeWordTriggered.asStateFlow()

    // Camera trigger state (for widget camera button)
    private val _cameraTriggered = MutableStateFlow(false)
    val cameraTriggered: StateFlow<Boolean> = _cameraTriggered.asStateFlow()

    // Mutex for thread-safe note operations (BUG-016 fix)
    private val noteOperationMutex = Mutex()










    // ==================== Delegated Managers ====================

    // Chat Manager - handles chat state and session lifecycle
    private val chatManager = ChatManager(chatRepository, viewModelScope)

    // Share Flow Manager - handles share interception and processing
    private val shareFlowManager = com.example.smarty.viewmodel.managers.ShareFlowManager(
        repository = repository,
        context = application,
        scope = viewModelScope,
        getNotesSnapshot = { notes.value }
    )



    // Note Operations Manager - handles note CRUD operations
    // Pass noteDao for batch write support (50-300% performance improvement)
    private val noteOperationsManager = com.example.smarty.viewmodel.managers.NoteOperationsManager(
        repository = repository,
        aiService = aiService,
        context = application,
        scope = viewModelScope,
        noteDao = database.noteDao()
    )

    // Note Processing Queue Manager - handles background processing with timeout and recovery
    private val noteProcessingQueueManager by lazy {
        NoteProcessingQueueManager(
            noteDao = database.noteDao(),
            repository = repository,
            aiService = aiService,
            scope = viewModelScope
        )
    }

    // Queue state exposed for UI (optional - shows pending count)
    val pendingNoteProcessingCount: StateFlow<Int> by lazy { noteProcessingQueueManager.pendingCount }

    // Calendar Manager - handles calendar event CRUD operations and reminders
    private val calendarManager by lazy {
        com.example.smarty.viewmodel.managers.CalendarManager(
            calendarDao = calendarDao,
            alarmScheduler = alarmScheduler,
            scope = viewModelScope
        )
    }

    // Audio Playback Manager - handles audio playback coordination with AudioPlayerService
    private val audioPlaybackManager by lazy {
        com.example.smarty.viewmodel.managers.AudioPlaybackManager(
            context = getApplication(),
            scope = viewModelScope
        )
    }

    // Audio Feature Manager - hybridized audio control for UI and AI agent
    private val audioFeatureManager by lazy {
        com.example.smarty.viewmodel.managers.AudioFeatureManager(
            audioPlaybackManager = audioPlaybackManager,
            deviceAudioRepository = deviceAudioRepository,
            scope = viewModelScope
        )
    }


    // ==================== Chat State (delegated to ChatManager) ====================
    val isChatMode: StateFlow<Boolean> = chatManager.isChatMode
    val chatMessages: StateFlow<List<ChatMessage>> = chatManager.chatMessages
    val isChatProcessing: StateFlow<Boolean> = chatManager.isChatProcessing
    val currentSessionId: StateFlow<String?> = chatManager.currentSessionId
    val chatSessions: StateFlow<List<ChatSession>> = chatManager.chatSessions

    // ==================== Share Flow State (delegated to ShareFlowManager) ====================
    val pendingShare: StateFlow<PendingShareData?> = shareFlowManager.pendingShare
    val pendingShareFullPrivacy: StateFlow<Boolean> = shareFlowManager.pendingShareFullPrivacy
    val isActiveShareMode: StateFlow<Boolean> = shareFlowManager.isActiveShareMode

    // AI exclusion state for pending notes (while writing)
    private val _pendingNoteAiExcluded = MutableStateFlow(false)
    val pendingNoteAiExcluded: StateFlow<Boolean> = _pendingNoteAiExcluded.asStateFlow()

    // Current input text (hoisted from UI for shake detection)
    private val _currentInputText = MutableStateFlow("")
    val currentInputText: StateFlow<String> = _currentInputText.asStateFlow()

    // Current input attachments (hoisted from UI for shake detection)
    private val _currentInputAttachments = MutableStateFlow<List<Attachment>>(emptyList())
    val currentInputAttachments: StateFlow<List<Attachment>> = _currentInputAttachments.asStateFlow()

    // Microphone listening state (hoisted from UI for shake detection)
    private val _isMicListening = MutableStateFlow(false)
    val isMicListening: StateFlow<Boolean> = _isMicListening.asStateFlow()

    // App foreground state - CRITICAL for microphone privacy
    // When false, all microphone access should be stopped
    private val _isAppInForeground = MutableStateFlow(true)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    // Track if mic is in use by voice enrollment or other features
    // When true, wake word detection should not restart
    private var isMicInUseByOther = false

    // Track if phone call is active - wake word should not work during calls
    private var isPhoneCallActive = false

    // Track if another app has audio focus
    private var isAudioFocusLost = false

    // Track if in-app audio is playing
    private var isInAppAudioPlaying = false

    // Job for collecting audio player state
    private var audioPlayerCollectorJob: Job? = null

    // Phone state listener for call detection
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyManager: TelephonyManager? = null

    // Audio manager for checking if music is active
    private var audioManager: AudioManager? = null

    // Job for periodic music check when music was detected
    private var musicCheckJob: Job? = null

    // Shake-triggered mode switch (for glow animation feedback)
    private val _wasShakeTriggered = MutableStateFlow(false)
    val wasShakeTriggered: StateFlow<Boolean> = _wasShakeTriggered.asStateFlow()

    // Current screen route - shake only works on main screen (input_stream)
    // Default to "startup" so shake is disabled until navigation explicitly sets the screen
    private val _currentScreen = MutableStateFlow("startup")
    val currentScreen: StateFlow<String> = _currentScreen.asStateFlow()

    // Shared flow for speech results to be consumed by screens
    // BUG FIX (RX-04): Added extraBufferCapacity to prevent dropped events
    // when collector is suspended (e.g., during screen transition)
    private val _speechResults = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        extraBufferCapacity = 8  // Buffer up to 8 speech results to prevent drops
    )
    val speechResults = _speechResults.asSharedFlow()

    // Pull-to-refresh state
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun refreshNotes() {
        viewModelScope.launch {
            _isRefreshing.value = true
            noteOperationsManager.refreshNotes()
            // Brief delay for visual feedback
            kotlinx.coroutines.delay(500)
            _isRefreshing.value = false
        }
    }
    fun clearInput() {
        _currentInputText.value = ""
        _currentInputAttachments.value = emptyList()
    }

    fun onSpeechResult(text: String) {
        viewModelScope.launch {
            _speechResults.emit(text)
        }
    }



    // Expose settings state for UI
    val geminiKeys: StateFlow<List<String>> = settingsFeatureManager.geminiKeys
    val huggingFaceKeys: StateFlow<List<String>> = settingsFeatureManager.huggingFaceKeys
    val providerConfigs: StateFlow<Map<AIProvider, AIProviderConfig>> = settingsFeatureManager.providerConfigs
    val providerPriorityOrder: StateFlow<List<AIProvider>> = settingsFeatureManager.providerPriorityOrder

    fun setProviderPriority(priority: List<AIProvider>) {
        settingsFeatureManager.setProviderPriority(priority)
    }

    // Cache management
    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()
    private val _isClearingCache = MutableStateFlow(false)
    val isClearingCache: StateFlow<Boolean> = _isClearingCache.asStateFlow()

    // Audio playback request from AI agent (observed by MainActivity to trigger playback)
    // Delegated to AudioPlaybackManager for centralized control - single source of truth
    val pendingAudioPlayback: StateFlow<AudioTrack?>
        get() = audioPlaybackManager.pendingAudioPlayback

    fun clearPendingAudioPlayback() {
        audioPlaybackManager.clearPendingAudioPlayback()
    }

    // ==================== Audio Playback Control (delegated to AudioFeatureManager) ====================

    /** Start playing an audio track directly */
    fun playAudioTrack(track: AudioTrack) = audioFeatureManager.play(track)

    /** Pause the current playback */
    fun pauseAudioPlayback() = audioFeatureManager.pause()

    /** Resume the paused playback */
    fun resumeAudioPlayback() = audioFeatureManager.resume()

    /** Stop playback completely */
    fun stopAudioPlayback() = audioFeatureManager.stop()

    /** Seek to a specific position */
    fun seekAudioTo(position: Long) = audioFeatureManager.seekTo(position)

    /** Toggle between play and pause */
    fun toggleAudioPlayback() = audioFeatureManager.togglePlayPause()

    /** Get the currently playing track */
    fun getCurrentAudioTrack(): AudioTrack? = audioFeatureManager.getCurrentTrack()

    /** Get current playback position in milliseconds */
    fun getCurrentAudioPosition(): Long = audioFeatureManager.getCurrentPosition()

    /** Get total duration of current track in milliseconds */
    fun getAudioDuration(): Long = audioFeatureManager.getDuration()

    /** Check if audio is currently playing */
    fun isAudioPlaying(): Boolean = audioFeatureManager.isPlaying()


    /** Toggle play/pause state */
    fun toggleAudioPlayPause() = audioPlaybackManager.togglePlayPause()

    /** Audio player state for UI */
    val audioPlayerState: StateFlow<com.example.smarty.data.model.AudioPlayerState>
        get() = audioPlaybackManager.playerState

    /** Notify audio service of foreground state */
    fun onAudioAppEnterForeground() = audioPlaybackManager.onAppEnterForeground()

    /** Notify audio service of background state */
    fun onAudioAppEnterBackground() = audioPlaybackManager.onAppEnterBackground()



    // Selected category (must be declared before notes flow that uses it)
    private val _selectedCategory = MutableStateFlow<Category?>(null)
    val selectedCategory: StateFlow<Category?> = _selectedCategory.asStateFlow()

    // Search and Filter State
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFilters = MutableStateFlow<Set<AttachmentOption>>(emptySet())
    val selectedFilters: StateFlow<Set<AttachmentOption>> = _selectedFilters.asStateFlow()

    // Expose recent searches state for UI
    val recentSearches: StateFlow<List<String>> = searchFeatureManager.recentSearches

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    /**
     * RECORD SEARCH - Delegated to SearchFeatureManager.
     */
    fun recordSearch(query: String) {
        if (query.length >= 2) {
            searchFeatureManager.addSearchHistory(query)
        }
    }

    /**
     * CLEAR SEARCH HISTORY - Delegated to SearchFeatureManager.
     */
    fun clearSearchHistory() {
        searchFeatureManager.clearSearchHistory()
    }

    /**
     * GET SEARCH SUGGESTIONS - Delegated to SearchFeatureManager.
     */
    fun getSearchSuggestions(query: String): List<String> {
        return searchFeatureManager.getHistorySuggestions(query)
    }

    fun onFilterToggle(option: AttachmentOption) {
        val current = _selectedFilters.value
        _selectedFilters.value = if (option in current) current - option else current + option
    }

    fun clearFilters() {
        _selectedFilters.value = emptySet()
    }

    /**
     * OPTIMIZATION: Debounced search flow
     * - debounce(300ms): Reduces DB queries by ~90% during typing
     * - flatMapLatest: Cancels previous queries when new input arrives
     * Note: StateFlow is already distinct, so distinctUntilChanged not needed on filters/category
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val notes: StateFlow<List<Note>> = combine(
        _searchQuery.debounce(300),  // PERF: 90% fewer DB queries (StateFlow already distinct)
        _selectedFilters,
        _selectedCategory
    ) { query, filters, category ->
        Triple(query, filters, category)
    }.flatMapLatest { (query, filters, category) ->
        val effectiveQuery = query.trim()
        
        // Step 1: Fetch candidates from DB via SearchFeatureManager
        val candidatesFlow = if (effectiveQuery.isEmpty()) {
            if (category != null) searchFeatureManager.getNotesByCategory(category.id)
            else searchFeatureManager.getAllNotesFlow()
        } else {
            searchFeatureManager.searchNotesFlow(effectiveQuery)
        }
        
        // Step 2: Apply Intersection Filter (AND Logic) in Memory
        // BUG FIX (L-002): Apply defensive limit to prevent OOM on large collections
        candidatesFlow.map { notesList ->
            val limitedList = if (notesList.size > MAX_NOTES_IN_MEMORY) {
                Log.w(TAG, "Large note collection detected (${notesList.size}), limiting to $MAX_NOTES_IN_MEMORY for memory safety")
                notesList.take(MAX_NOTES_IN_MEMORY)
            } else {
                notesList
            }

            if (filters.isEmpty()) {
                limitedList
            } else {
                limitedList.filter { note ->
                    // Note must satisfy ALL selected filters
                    // Delegate to SearchFeatureManager for logic parity with AI Agent
                    filters.all { filter -> searchFeatureManager.noteMatchesFilter(note, filter) }
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories = noteOperationsManager.getAllCategories()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Calendar events (delegated to CalendarManager)
    val calendarEvents: StateFlow<List<CalendarEvent>>
        get() = calendarManager.calendarEvents

    /**
     * REACTIVE SELECTED NOTE - Auto-updates when note changes in database.
     *
     * FIX: Previously _selectedNote was a snapshot that didn't update when AI processing completed.
     * Now we store only the note ID and observe the actual note from the database.
     * This ensures the detail view always shows fresh data (summary, whySaved, category).
     */
    private val _selectedNoteId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedNote: StateFlow<Note?> = _selectedNoteId
        .flatMapLatest { noteId ->
            if (noteId != null) {
                noteOperationsManager.getNoteByIdFlow(noteId)
            } else {
                flowOf(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    // Loading state for notes list (Phase 8)
    private val _isNotesLoading = MutableStateFlow(true)
    val isNotesLoading: StateFlow<Boolean> = _isNotesLoading.asStateFlow()

    // Undo state for bulk archive operations (Phase 4)
    private val _lastArchivedNoteIds = MutableStateFlow<List<String>>(emptyList())
    val lastArchivedNoteIds: StateFlow<List<String>> = _lastArchivedNoteIds.asStateFlow()

    // Network monitoring (Phase 7)
    private val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(application) }
    val connectionStatus: StateFlow<ConnectionStatus> = networkMonitor.connectionStatus
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionStatus.CONNECTED)

    init {
        // OPTIMIZATION: Track notes loading state - only first emission needed
        // Using take(1) instead of collect to avoid permanent subscription
        viewModelScope.launch {
            notes.take(1).collect {
                _isNotesLoading.value = false
            }
        }

        // DEFERRED: Category count sync moved to lazy initialization
        // This saves 500-2000ms on startup. Will sync when categories are first accessed.

        // DEFERRED: GROQ key sync moved to first AI request

        // DEFERRED: Chat manager initialization moved to when chat mode is entered

        // Set up NoteOperationsManager callback for AI processing
        noteOperationsManager.setAiProcessingCallback(object : com.example.smarty.viewmodel.managers.NoteOperationsManager.AiProcessingCallback {
            override suspend fun onProcessingComplete(note: Note) {
                // SECURITY: Don't log note titles to prevent data leakage via logcat
                Log.d(TAG, "Note processing complete: id=${note.id.take(8)}...")
            }
            override suspend fun onProcessingError(note: Note, error: String) {
                // SECURITY: Don't log note titles to prevent data leakage via logcat
                Log.e(TAG, "Note processing error for id=${note.id.take(8)}...: $error")
            }
        })

        // Initialize Note Processing Queue Manager
        // Recovers stuck notes and starts background queue processor
        viewModelScope.launch(Dispatchers.IO) {
            try {
                noteProcessingQueueManager.initialize()
                Log.d(TAG, "Note processing queue initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize note processing queue: ${e.message}", e)
            }
        }

        // Collect note processing events for completion sound
        viewModelScope.launch {
            noteProcessingQueueManager.processingEvents.collect { event ->
                when (event) {
                    is NoteProcessingQueueManager.NoteProcessingEvent.Completed -> {
                        // Play completion sound when notecard processing finishes
                        completionSoundManager.playNotecardCompletionSound(
                            isAppInForeground = _isAppInForeground.value,
                            noteTitle = event.noteTitle
                        )
                        // Note: selectedNote is reactive and auto-updates from database
                    }
                    // Retry and Failed events can be handled here if needed for UI notifications
                    is NoteProcessingQueueManager.NoteProcessingEvent.Retry -> {
                        Log.d(TAG, "Note ${event.noteId} retry attempt ${event.attempt}")
                    }
                    is NoteProcessingQueueManager.NoteProcessingEvent.Failed -> {
                        Log.w(TAG, "Note ${event.noteId} processing failed: ${event.reason}")
                    }
                }
            }
        }

        // Restore state from SavedStateHandle after process death (BUG-053)
        // Made non-blocking - failures won't affect startup
        restoreState()

        // Schedule FTS maintenance (weekly optimization)
        scheduleFtsMaintenance()

        // Start proactive system monitoring for AI engagement
        startProactiveMonitoring()
    }

    // Track if deferred initialization has been done
    private var categorySyncDone = false
    private var chatManagerInitialized = false
    private var groqKeysSynced = false

    /**
     * Perform deferred initialization for categories.
     * Called when categories are first accessed.
     */
    private fun ensureCategorySyncDone() {
        if (categorySyncDone) return
        categorySyncDone = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                noteOperationsManager.syncCategoryCounts()
                Log.d(TAG, "Deferred category sync complete")
            } catch (e: Exception) {
                Log.w(TAG, "Category sync failed: ${e.message}")
            }
        }
    }

    /**
     * Perform deferred initialization for chat manager.
     * Called when chat mode is first entered.
     */
    private fun ensureChatManagerInitialized() {
        if (chatManagerInitialized) return
        chatManagerInitialized = true
        chatManager.initialize()
        Log.d(TAG, "Deferred chat manager initialization complete")
    }

    /**
     * Perform deferred initialization for GROQ keys.
     * Called before first AI request.
     */
    private fun ensureGroqKeysSynced() {
        if (groqKeysSynced) return
        groqKeysSynced = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                agentProvider.syncGroqKeys()
                Log.d(TAG, "Deferred GROQ key sync complete")
            } catch (e: Exception) {
                Log.w(TAG, "GROQ key sync failed: ${e.message}")
            }
        }
    }

    /**
     * Schedule FTS index maintenance.
     * Delegates to NoteOperationsManager for centralized maintenance logic.
     */
    private fun scheduleFtsMaintenance() {
        noteOperationsManager.optimizeSearchIndex()
    }

    /**
     * Restore navigation state from SavedStateHandle after process death.
     * This preserves selectedNote, selectedCategory, and chatMode across restarts.
     *
     * CRITICAL: Wrapped in try-catch to prevent crashes during process death recovery.
     * All database access is guarded to handle cases where lazy init isn't complete.
     *
     * BUG FIX (L-003): Replaced arbitrary delay(500) with retry-based approach.
     * - Retries with exponential backoff instead of fixed delay
     * - Works on slow devices (more retries) and fast devices (less waiting)
     * - Maximum 3 retries with 100ms, 200ms, 400ms delays
     */
    private fun restoreState() {
        viewModelScope.launch {
            // Restore selected note by ID - just set the ID, the reactive Flow will fetch the note
            savedStateHandle.get<String>(KEY_SELECTED_NOTE_ID)?.let { noteId ->
                restoreWithRetry("note") {
                    val noteExists = noteOperationsManager.getNoteById(noteId) != null
                    if (noteExists) {
                        _selectedNoteId.value = noteId
                        Log.d(TAG, "Restored selectedNoteId: $noteId")
                        true
                    } else {
                        savedStateHandle.remove<String>(KEY_SELECTED_NOTE_ID)
                        true // Note doesn't exist anymore, that's OK
                    }
                }
            }

            // Restore selected category by ID with retry
            savedStateHandle.get<String>(KEY_SELECTED_CATEGORY_ID)?.let { categoryId ->
                restoreWithRetry("category") {
                    val category = noteOperationsManager.getCategoryById(categoryId)
                    if (category != null) {
                        _selectedCategory.value = category
                        Log.d(TAG, "Restored selectedCategory: ${category.id}")
                        true
                    } else {
                        savedStateHandle.remove<String>(KEY_SELECTED_CATEGORY_ID)
                        true // Category doesn't exist anymore, that's OK
                    }
                }
            }

            // Restore chat mode state with retry
            savedStateHandle.get<Boolean>(KEY_IS_CHAT_MODE)?.let { wasChatMode ->
                if (wasChatMode) {
                    restoreWithRetry("chat mode") {
                        chatManager.enterChatMode()
                        Log.d(TAG, "Restored chat mode")
                        true
                    }
                }
            }
        }
    }

    /**
     * Helper function to restore state with exponential backoff retry.
     * BUG FIX (L-003): Replaces arbitrary delays with proper retry mechanism.
     *
     * @param itemName Name of the item being restored (for logging)
     * @param maxRetries Maximum number of retry attempts (default: 3)
     * @param initialDelayMs Initial delay in milliseconds (default: 100ms)
     * @param block The restoration logic to execute
     * @return true if restoration succeeded, false otherwise
     */
    private suspend fun restoreWithRetry(
        itemName: String,
        maxRetries: Int = 3,
        initialDelayMs: Long = 100,
        block: suspend () -> Boolean
    ): Boolean {
        var delayMs = initialDelayMs
        repeat(maxRetries) { attempt ->
            try {
                if (block()) return true
            } catch (e: Exception) {
                if (attempt == maxRetries - 1) {
                    Log.w(TAG, "Failed to restore $itemName after $maxRetries attempts: ${e.message}")
                    // Clear saved state to prevent repeated failures
                    when (itemName) {
                        "note" -> savedStateHandle.remove<String>(KEY_SELECTED_NOTE_ID)
                        "category" -> savedStateHandle.remove<String>(KEY_SELECTED_CATEGORY_ID)
                        "chat mode" -> savedStateHandle[KEY_IS_CHAT_MODE] = false
                    }
                    return false
                }
                Log.d(TAG, "Retry $itemName restoration (attempt ${attempt + 1}/$maxRetries)")
                kotlinx.coroutines.delay(delayMs)
                delayMs *= 2 // Exponential backoff
            }
        }
        return false
    }

    // Public sync function for manual recalculation
    fun syncCategoryCounts() {
        noteOperationsManager.syncCategoryCounts()
    }

    fun selectNote(note: Note?) {
        // Only store the ID - the actual note is observed reactively from the database
        _selectedNoteId.value = note?.id
        // Persist to SavedStateHandle for process death recovery (BUG-053)
        savedStateHandle[KEY_SELECTED_NOTE_ID] = note?.id
    }

    /**
     * DEPRECATED: No longer needed since selectedNote is now reactive.
     * The Flow automatically emits updates when the database changes.
     * Keeping this as a no-op to avoid breaking existing call sites.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun syncSelectedNoteIfNeeded(updatedNote: Note) {
        // No-op: selectedNote is now a reactive Flow that auto-updates from database
    }

    fun selectCategory(category: Category?) {
        // Ensure category counts are synced when user first interacts with categories
        ensureCategorySyncDone()

        _selectedCategory.value = category
        // Persist to SavedStateHandle for process death recovery (BUG-053)
        savedStateHandle[KEY_SELECTED_CATEGORY_ID] = category?.id
    }

    // O(1) lookup using enum ordinal comparison
    private fun shouldAnalyze(type: NoteType): Boolean = NoteType.isAnalyzable(type)

    fun addNote(
        content: String,
        type: NoteType = NoteType.BRAIN_DUMP,
        sourceUrl: String? = null,
        excludeFromAiChat: Boolean = false
    ) {
        noteOperationsManager.addNote(content, type, sourceUrl, excludeFromAiChat)
    }

    fun addNoteFromShare(sharedContent: SharedContent) {
        // Convert to manager's SharedContent format
        val managerContent = com.example.smarty.viewmodel.managers.SharedContent(
            text = sharedContent.text,
            fileUri = sharedContent.fileUri,
            fileName = sharedContent.fileName,
            mimeType = sharedContent.mimeType,
            fileSize = sharedContent.fileSize
        )
        noteOperationsManager.addNoteFromShare(managerContent)
    }

    /**
     * Add note with attachments from the input field
     */
    fun addNoteWithAttachments(
        content: String,
        attachments: List<Attachment>,
        excludeFromAiChat: Boolean = _pendingNoteAiExcluded.value
    ) {
        noteOperationsManager.addNoteWithAttachments(content, attachments, excludeFromAiChat)
        resetPendingNoteState()
    }

    /**
     * Get plural name for a note type (for titles like "3 Images")
     */
    private fun getTypePluralName(type: NoteType): String {
        return when (type) {
            NoteType.IMAGE -> "Images"
            NoteType.VIDEO -> "Videos"
            NoteType.AUDIO -> "Audio Files"
            NoteType.DOCUMENT -> "Documents"
            NoteType.SPREADSHEET -> "Spreadsheets"
            NoteType.PRESENTATION -> "Presentations"
            NoteType.CODE -> "Code Files"
            NoteType.ARCHIVE -> "Archives"
            NoteType.APK -> "APK Files"
            else -> "Files"
        }
    }

    /**
     * Extract suggestions from agent response.
     * Parses TOON format: {suggestions:["suggestion1","suggestion2"]}
     * Returns cleaned response (without suggestions block) and list of suggestions.
     *
     * BUG FIX (Issue #18): Made extraction more robust with multiple fallback patterns
     * and proper error logging instead of silent failure.
     */
    private fun extractSuggestionsFromResponse(response: String): Pair<String, List<String>> {
        try {
            // Multiple patterns to handle various LLM output formats (including local LLMs)
            val patterns = listOf(
                // Standard format: {suggestions:["a","b"]}
                Regex("""\{suggestions:\s*\[([^\]]*)\]\}""", RegexOption.IGNORE_CASE),
                // With extra spaces: { suggestions : [ "a" , "b" ] }
                Regex("""\{\s*suggestions\s*:\s*\[([^\]]*)\]\s*\}""", RegexOption.IGNORE_CASE),
                // JSON-style with quotes: {"suggestions":["a","b"]}
                Regex("""\{"suggestions"\s*:\s*\[([^\]]*)\]\}""", RegexOption.IGNORE_CASE),
                // Markdown code block: ```{suggestions:...}```
                Regex("""```\s*\{suggestions:\s*\[([^\]]*)\]\}\s*```""", RegexOption.IGNORE_CASE),
                // Suggestions on new line: \nsuggestions: [...]
                Regex("""\n\s*suggestions\s*:\s*\[([^\]]*)\]""", RegexOption.IGNORE_CASE),
                // With "Suggestions:" label (capital S)
                Regex("""Suggestions\s*:\s*\[([^\]]*)\]"""),
                // Fallback: just the array after suggestions:
                Regex("""suggestions\s*:\s*\[([^\]]*)\]""", RegexOption.IGNORE_CASE)
            )

            var match: MatchResult? = null
            var matchedPattern = ""
            for ((index, pattern) in patterns.withIndex()) {
                match = pattern.find(response)
                if (match != null) {
                    matchedPattern = "pattern$index"
                    break
                }
            }

            if (match == null) {
                // No suggestions found - this is normal, not an error
                Log.d(TAG, "No suggestions block found in response")
                return Pair(response.trim(), emptyList())
            }

            // Extract the suggestions array content
            val suggestionsContent = match.groupValues[1]

            // Parse individual suggestions with multiple quote styles
            // Handles: "text", 'text', and unquoted text separated by commas
            val suggestions = mutableListOf<String>()

            // Try quoted strings first
            val quotedPattern = Regex(""""([^"\\]*(?:\\.[^"\\]*)*)"|'([^'\\]*(?:\\.[^'\\]*)*)'""")
            quotedPattern.findAll(suggestionsContent).forEach { quotedMatch ->
                val suggestion = quotedMatch.groupValues[1].ifEmpty { quotedMatch.groupValues[2] }
                if (suggestion.isNotBlank()) {
                    // Unescape any escaped characters
                    suggestions.add(suggestion.replace("\\\"", "\"").replace("\\'", "'"))
                }
            }

            // If no quoted strings found, try comma-separated values
            if (suggestions.isEmpty() && suggestionsContent.isNotBlank()) {
                suggestionsContent.split(",")
                    .map { it.trim().trim('"', '\'', ' ') }
                    .filter { it.isNotBlank() && it.length >= 2 }
                    .take(2)
                    .forEach { suggestions.add(it) }
            }

            // Limit to 2 suggestions
            val finalSuggestions = suggestions.take(2)

            // Remove the suggestions block from the response
            val cleanedResponse = response.replace(match.value, "").trim()

            if (finalSuggestions.isNotEmpty()) {
                Log.d(TAG, "Extracted ${finalSuggestions.size} suggestions via $matchedPattern: $finalSuggestions")
            }

            return Pair(cleanedResponse, finalSuggestions)

        } catch (e: Exception) {
            // BUG FIX (Issue #18): Log errors instead of silent failure
            Log.e(TAG, "Failed to extract suggestions from response: ${e.message}", e)
            return Pair(response.trim(), emptyList())
        }
    }

    /**
     * Parsing of Clarification Request from AI response.
     * Expected format: {clarification:{question:"...",options:["A","B"],custom:true}}
     */
    private fun extractClarificationFromResponse(response: String): Pair<String, com.example.smarty.data.model.ClarificationRequest?> {
        try {
            // Pattern: {clarification:{...}}
            val pattern = Regex("""\{clarification:\s*\{(.*?)\}\}""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val match: kotlin.text.MatchResult = pattern.find(response) ?: return Pair(response, null)
            
            val content = match.groupValues.get(1)
            
            // Extract question
            val questionMatch = Regex("""question:\s*["'](.*?)["']""").find(content)
            val question = questionMatch?.groupValues?.get(1) ?: return Pair(response, null)
            
            // Extract options
            val optionsMatch = Regex("""options:\s*\[(.*?)\]""").find(content)
            val optionsStr = optionsMatch?.groupValues?.get(1) ?: ""
            val options = optionsStr.split(",")
                .map { it.trim().trim('"', '\'') }
                .filter { it.isNotBlank() }
            
            // Extract custom input flag
            val customMatch = Regex("""custom:\s*(true|false)""").find(content)
            val allowCustomString = customMatch?.groupValues?.get(1)
            val allowCustom = allowCustomString?.toBoolean() ?: true
            
            val request = ClarificationRequest(
                question = question,
                options = options,
                allowCustomInput = allowCustom
            )
            
            // Clean response
            val matchValue = match.value
            val cleanedResponse = response.replace(matchValue, "").trim()
            
            Log.d(TAG, "Extracted Clarification Request: $question")
            return Pair(cleanedResponse, request)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing clarification request", e)
            return Pair(response, null)
        }
    }

    /**
     * Filter out internal planning text from AI responses.
     * The planning system is for internal AI task management - users should only see final results.
     * 
     * @param response The raw AI response
     * @return Cleaned response with planning text removed, or null if this is purely a planning message
     */
    private fun filterPlanningText(response: String): String? {
        val trimmed = response.trim()
        
        // Patterns that indicate this is internal planning text (should be hidden)
        val planningPatterns = listOf(
            "=== CURRENT EXECUTION PLAN ===",
            "IMMEDIATE ACTION:",
            "NEXT ACTION:",
            "Step 1/",
            "Step 2/",
            "Step 3/",
            "Step 4/",
            "Step 5/",
            "Step 6/",
            "Step 7/",
            "Execute this now",
            "Executing step",
            "I'm starting by",
            "Now executing",
            "I'll start by",
            "First, I'll",
            "I'm now going to",
            "Proceeding with step"
        )
        
        // Check if the response is primarily planning text
        val isPlanningMessage = planningPatterns.any { pattern ->
            trimmed.contains(pattern, ignoreCase = true)
        }
        
        if (isPlanningMessage) {
            Log.d(TAG, "Filtering out planning text: ${trimmed.take(50)}...")
            
            // If the response is ONLY planning text, return null to skip this message entirely
            // This happens during intermediate plan steps
            val linesWithoutPlanning = trimmed.lines().filter { line ->
                !planningPatterns.any { pattern -> line.contains(pattern, ignoreCase = true) }
            }.joinToString("\n").trim()
            
            return if (linesWithoutPlanning.isBlank() || linesWithoutPlanning.length < 20) {
                null // Skip this message entirely - it's just planning
            } else {
                linesWithoutPlanning // Return the non-planning part
            }
        }
        
        return response // No planning text detected, return as-is
    }
    
    /**
     * Submit user response to a clarification request.
     * Treats the response as a user message.
     */
    fun submitClarification(response: String) {
        if (response.isBlank()) return
        
        viewModelScope.launch {
            // Add as user message and trigger agent
            sendChatMessage(response)
        }
    }

    /**
     * Build description for multiple attachments
     */
    private fun buildMultipleAttachmentsDescription(attachments: List<NoteAttachment>): String {
        val sb = StringBuilder()
        sb.append("${attachments.size} files attached:\n\n")
        attachments.forEachIndexed { index, attachment ->
            sb.append("${index + 1}. ${attachment.fileName}")
            if (attachment.fileSize > 0) {
                sb.append(" (${ContentTypeDetector.formatFileSize(attachment.fileSize)})")
            }
            if (index < attachments.lastIndex) sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Compresses and stores an attachment to internal storage for persistence.
     * Uses optimal compression based on file type.
     * Returns the original attachment if compression fails.
     */
    private suspend fun copyAttachmentToStorage(attachment: Attachment): Attachment {
        return try {
            val compressed = FileStorageHelper.compressAndStore(
                context = getApplication(),
                sourceUri = attachment.uri,
                mimeType = attachment.mimeType,
                originalFileName = attachment.fileName
            )
            if (compressed != null) {
                // Log compression savings
                if (compressed.isCompressed) {
                    Log.i(TAG, "Attachment compressed: ${attachment.fileName} saved ${ContentTypeDetector.formatFileSize(compressed.savedBytes)} " +
                            "(${String.format("%.1f", compressed.compressionRatio)}% reduction)")
                }
                attachment.copy(
                    uri = Uri.parse(compressed.uri),
                    fileName = compressed.fileName,
                    fileSize = compressed.compressedSize,
                    mimeType = compressed.mimeType
                )
            } else {
                Log.w(TAG, "File compression returned null, using original URI: ${attachment.uri}")
                attachment
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress attachment: ${e.message}")
            attachment // Return original on failure
        }
    }

    /**
     * Build description for an attachment
     */
    private fun buildAttachmentDescription(attachment: Attachment): String {
        val sb = StringBuilder()
        sb.append("File: ").append(attachment.fileName)
        sb.append('\n')
        sb.append("Type: ").append(attachment.mimeType)
        if (attachment.fileSize > 0) {
            sb.append('\n')
            sb.append("Size: ").append(ContentTypeDetector.formatFileSize(attachment.fileSize))
        }
        return sb.toString()
    }

    /**
     * Store file without AI analysis - just put in appropriate category.
     * Delegates 100% of logic to NoteOperationsManager.
     */
    private suspend fun storeWithoutAnalysis(note: Note) {
        noteOperationsManager.storeWithoutAnalysis(note)
    }

    // Bulk operations with undo support
    fun archiveNotes(noteIds: List<String>) {
        if (noteIds.isEmpty()) return
        noteOperationsManager.bulkArchiveNotes(noteIds)
        _lastArchivedNoteIds.value = noteIds
    }

    fun undoArchive() {
        val ids = _lastArchivedNoteIds.value
        if (ids.isEmpty()) return
        noteOperationsManager.bulkUnarchiveNotes(ids)
        _lastArchivedNoteIds.value = emptyList()
    }

    fun clearUndoState() {
        _lastArchivedNoteIds.value = emptyList()
    }

    // Archived notes for archive screen
    val archivedNotes = noteOperationsManager.getArchivedNotes()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteNote(note: Note) {
        noteOperationsManager.deleteNote(note)
    }

    fun deleteNoteById(noteId: String) {
        viewModelScope.launch {
            noteOperationsManager.deleteNoteById(noteId, notes.value, archivedNotes.value)
        }
    }

    fun archiveNote(noteId: String) {
        noteOperationsManager.archiveNote(noteId)
    }

    fun unarchiveNote(noteId: String) {
        noteOperationsManager.unarchiveNote(noteId)
    }

    /**
     * Update todos for a note.
     */
    fun updateNoteTodos(noteId: String, todos: List<TodoItem>, onComplete: (() -> Unit)? = null) {
        noteOperationsManager.updateNoteTodos(noteId, todos, notes.value, archivedNotes.value)
        onComplete?.invoke()
    }

    fun updateNoteCategory(noteId: String, categoryId: String, categoryName: String) {
        noteOperationsManager.updateNoteCategory(noteId, categoryId, categoryName)
    }

    fun markNoteAsViewed(noteId: String) {
        noteOperationsManager.markNoteAsViewed(noteId)
    }

    // =========================================================================
    // PIN OPERATIONS
    // =========================================================================

    fun pinNote(noteId: String) {
        noteOperationsManager.pinNote(noteId)
    }

    fun unpinNote(noteId: String) {
        noteOperationsManager.unpinNote(noteId)
    }

    fun toggleNotePin(noteId: String) {
        noteOperationsManager.toggleNotePin(noteId)
    }

    // =========================================================================
    // REMINDER OPERATIONS
    // =========================================================================

    fun setNoteReminder(noteId: String, reminderText: String, durationMs: Long? = null) {
        noteOperationsManager.setNoteReminder(noteId, reminderText, durationMs)
    }

    fun clearNoteReminder(noteId: String) {
        noteOperationsManager.clearNoteReminder(noteId)
    }

    // =========================================================================
    // VERSION OPERATIONS (Git-like history)
    // =========================================================================

    // Currently loaded versions for selected note (for UI display)
    private val _selectedNoteVersions = MutableStateFlow<List<com.example.smarty.data.model.NoteVersion>>(emptyList())
    val selectedNoteVersions: StateFlow<List<com.example.smarty.data.model.NoteVersion>> = _selectedNoteVersions.asStateFlow()

    /**
     * Load version history for display in UI
     */
    fun loadNoteVersions(noteId: String) {
        viewModelScope.launch {
            val versions = noteOperationsManager.getNoteVersions(noteId)
            _selectedNoteVersions.value = versions
        }
    }

    /**
     * Get version history for a note
     */
    fun getNoteVersions(noteId: String) = noteOperationsManager.getNoteVersionsFlow(noteId)

    /**
     * Get version history as one-shot query
     */
    suspend fun getNoteVersionsOnce(noteId: String) = noteOperationsManager.getNoteVersions(noteId)

    /**
     * Restore a note to a previous version
     */
    fun restoreNoteVersion(noteId: String, versionId: String, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val success = noteOperationsManager.restoreNoteVersion(noteId, versionId)
            if (success) {
                loadNoteVersions(noteId)
            }
            onComplete?.invoke(success)
        }
    }

    /**
     * Edit a note's title, content, and optionally attachments.
     */
    fun editNote(noteId: String, newTitle: String, newContent: String, newSummary: String?, newWhySaved: String?, newAttachments: List<NoteAttachment>? = null) {
        noteOperationsManager.editNote(noteId, newTitle, newContent, newSummary, newWhySaved, newAttachments)
    }

    // Share interception for bottom sheet (delegated to ShareFlowManager)
    fun interceptShareForPreview(sharedContent: SharedContent) {
        // Convert from viewmodel.SharedContent to managers.SharedContent
        val managerSharedContent = com.example.smarty.viewmodel.managers.SharedContent(
            text = sharedContent.text,
            fileUri = sharedContent.fileUri,
            fileName = sharedContent.fileName,
            mimeType = sharedContent.mimeType,
            fileSize = sharedContent.fileSize,
            files = sharedContent.files.map { file ->
                com.example.smarty.viewmodel.managers.SharedFileInfo(
                    fileUri = file.fileUri,
                    fileName = file.fileName,
                    mimeType = file.mimeType,
                    fileSize = file.fileSize
                )
            }
        )
        shareFlowManager.interceptShareForPreview(managerSharedContent)
    }

    fun confirmShare(selectedCategory: String?, aiInstructions: String) {
        viewModelScope.launch {
            shareFlowManager.confirmShare(
                selectedCategory = selectedCategory,
                aiInstructions = aiInstructions,
                callback = object : ShareFlowManager.ShareConfirmCallback {
                    override suspend fun processNoteWithAi(note: Note) {
                        noteOperationsManager.processNoteWithAi(note)
                    }
                }
            )
        }
    }

    fun cancelShare() {
        shareFlowManager.cancelShare()
    }

    // Helper methods delegated to NoteOperationsManager or ContentTypeDetector

    // API Key Management
    fun addApiKey(provider: AIProvider, apiKey: String) {
        settingsFeatureManager.addProviderKey(provider, apiKey)
        // Sync GROQ keys with manager for usage tracking
        if (provider == AIProvider.GROQ) {
            viewModelScope.launch { agentProvider.syncGroqKeys() }
        }
        // Trigger queue processing - provider just became available
        noteProcessingQueueManager.onProviderAvailable()
    }

    fun removeApiKey(provider: AIProvider, apiKey: String) {
        settingsFeatureManager.removeProviderKey(provider, apiKey)
        // Sync GROQ keys with manager for usage tracking
        if (provider == AIProvider.GROQ) {
            viewModelScope.launch { agentProvider.syncGroqKeys() }
        }
    }

    fun updateApiKey(provider: AIProvider, oldKey: String, newKey: String) {
        settingsFeatureManager.updateProviderKey(provider, oldKey, newKey)
        // Sync GROQ keys with manager for usage tracking
        if (provider == AIProvider.GROQ) {
            viewModelScope.launch { agentProvider.syncGroqKeys() }
        }
        // Trigger queue processing - provider config changed
        noteProcessingQueueManager.onProviderAvailable()
    }

    fun setProviderEnabled(provider: AIProvider, enabled: Boolean) {
        settingsFeatureManager.setProviderEnabled(provider, enabled)
        // If provider was enabled, trigger queue processing
        if (enabled) {
            noteProcessingQueueManager.onProviderAvailable()
        }
    }

    fun setSelectedModel(provider: AIProvider, model: String) {
        settingsFeatureManager.setSelectedModel(provider, model)
    }

    fun testApiKey(provider: AIProvider, apiKey: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val isValid = settingsFeatureManager.testApiKey(provider, apiKey)
            onResult(isValid)
        }
    }

    // Theme Management
    val isDarkTheme: StateFlow<Boolean> = settingsFeatureManager.isDarkTheme

    fun setDarkTheme(isDark: Boolean) {
        settingsFeatureManager.setDarkTheme(isDark)
    }

    // Rate Limit Stats (exposed for UI monitoring)
    fun getRateLimitStats() = settingsFeatureManager.getRateLimitStats()

    // Tavily Web Search API Management (supports multiple keys)
    val tavilyApiKey: StateFlow<String?> = settingsFeatureManager.tavilyApiKey
    val tavilyApiKeys: StateFlow<List<String>> = settingsFeatureManager.tavilyApiKeys

    fun setTavilyApiKey(key: String?) {
        settingsFeatureManager.setTavilyApiKey(key)
    }

    fun addTavilyApiKey(key: String) {
        settingsFeatureManager.addTavilyApiKey(key)
    }

    fun removeTavilyApiKey(key: String) {
        settingsFeatureManager.removeTavilyApiKey(key)
    }

    // Shake Sensitivity Management
    val shakeSensitivity: StateFlow<Float> = settingsFeatureManager.shakeSensitivity

    fun setShakeSensitivity(value: Float) {
        settingsFeatureManager.setShakeSensitivity(value)
    }

    // Cache Management
    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            _cacheSizeBytes.value = systemFeatureManager.getCacheSize()
        }
    }

    fun clearCache() {
        _isClearingCache.value = true
        systemFeatureManager.clearCache { newSize ->
            _cacheSizeBytes.value = newSize
            _isClearingCache.value = false
        }
    }

    // User Category Creation
    fun createUserCategory(name: String) {
        noteOperationsManager.createUserCategory(name)
    }

    // Delete Category (BUG-028: Proper cascade cleanup)
    fun deleteCategory(category: Category) {
        noteOperationsManager.deleteCategory(category)
    }


    fun isFirstLaunch(): Boolean {
        return settingsFeatureManager.isFirstLaunch()
    }

    fun setFirstLaunchComplete() {
        settingsFeatureManager.setFirstLaunchComplete()
    }

    // ==================== Chat Mode & Agent Functionality ====================

    /**
     * Update the current input text from UI
     * Used for contextual shake detection
     */
    fun updateInputText(text: String) {
        _currentInputText.value = text
    }

    /**
     * Update the current input attachments from UI
     * Used for contextual shake detection (attachments should trigger privacy mode)
     */
    fun updateInputAttachments(attachments: List<Attachment>) {
        _currentInputAttachments.value = attachments
    }

    /**
     * Update microphone listening state from UI
     * Used for shake detection - mic active should trigger privacy mode
     */
    fun updateMicListening(isListening: Boolean) {
        _isMicListening.value = isListening
    }

    /**
     * Toggle AI exclusion for the pending note
     * Called when shaking while typing
     */
    fun togglePendingNoteAiExclusion() {
        _pendingNoteAiExcluded.value = !_pendingNoteAiExcluded.value
        Log.d(TAG, "AI exclusion toggled: ${_pendingNoteAiExcluded.value}")
    }

    /**
     * Reset pending note state after submission
     */
    fun resetPendingNoteState() {
        _pendingNoteAiExcluded.value = false
        _currentInputText.value = ""
        _currentInputAttachments.value = emptyList()
    }

    /**
     * Initialize the shake detector with contextual behavior
     * Call this from MainActivity.onCreate()
     *
     * Shake behavior:
     * - If input has text → toggle AI exclusion for current note
     * - If input is empty → toggle chat mode
     */
    fun initShakeDetector(context: Context) {
        shakeDetector = ShakeDetector(
            context = context,
            onShakeDetected = { handleShake() },
            getThreshold = { settingsFeatureManager.getShakeThreshold() }
        )
        Log.d(TAG, "Shake detector initialized with contextual handler")
    }

    // Job for wake word state collection - cancelled on re-init
    private var wakeWordCollectorJob: Job? = null

    /**
     * Initialize Vosk wake word detection (fully offline).
     * Call this from MainActivity.onCreate() after permissions are granted.
     * Idempotent - safe to call multiple times.
     *
     * When "hello reddit" is detected:
     * - Vosk stops listening (frees mic)
     * - wakeWordTriggered becomes true
     * - MainActivity should launch Google Speech ReJarviszer
     * - After STT completes, call restartWakeWordDetection()
     */
    fun initVoskWakeWord(context: Context) {
        // Prevent double initialization
        if (voskWakeWordManager != null) {
            Log.d(TAG, "Vosk wake word manager already initialized, skipping")
            return
        }

        voskWakeWordManager = VoskWakeWordManager(
            context = context.applicationContext,
            scope = viewModelScope,
            onWakeWordDetected = {
                Log.i(TAG, "Wake word detected - triggering STT")
                _wakeWordTriggered.value = true
            }
        )
        voskWakeWordManager?.initialize()

        // Cancel any existing collector before starting new one
        wakeWordCollectorJob?.cancel()

        // Observe listening state
        wakeWordCollectorJob = viewModelScope.launch {
            voskWakeWordManager?.isListening?.collect { isListening ->
                _isWakeWordActive.value = isListening
            }
        }

        // Initialize phone call detection
        initPhoneCallListener(context)

        // Initialize audio focus detection
        initAudioFocusListener(context)

        Log.i(TAG, "Vosk wake word manager initialized with call and audio focus detection")
    }

    /**
     * Initialize phone call state listener.
     * Stops wake word when user is on a call.
     */
    @Suppress("DEPRECATION")
    private fun initPhoneCallListener(context: Context) {
        try {
            telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            phoneStateListener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    when (state) {
                        TelephonyManager.CALL_STATE_RINGING,
                        TelephonyManager.CALL_STATE_OFFHOOK -> {
                            // Call active - stop wake word
                            if (!isPhoneCallActive) {
                                isPhoneCallActive = true
                                stopWakeWordDetection()
                                Log.d(TAG, "Phone call active - stopped wake word detection")
                            }
                        }
                        TelephonyManager.CALL_STATE_IDLE -> {
                            // Call ended - can restart wake word
                            if (isPhoneCallActive) {
                                isPhoneCallActive = false
                                restartWakeWordDetection()
                                Log.d(TAG, "Phone call ended - restarting wake word detection")
                            }
                        }
                    }
                }
            }
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            Log.d(TAG, "Phone call listener initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize phone call listener: ${e.message}")
        }
    }

    /**
     * Initialize audio focus listener.
     * Stops wake word when another app takes audio focus or in-app audio plays.
     *
     * NOTE: We do NOT request audio focus ourselves - that would pause other apps' music.
     * Instead we check isMusicActive and observe in-app audio state.
     */
    private fun initAudioFocusListener(context: Context) {
        try {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

            // Check if music is already playing (initial state)
            val isMusicPlaying = audioManager?.isMusicActive == true
            if (isMusicPlaying) {
                Log.d(TAG, "Music already playing on init - Vosk will not start")
                isAudioFocusLost = true
            }

            // NOTE: We intentionally do NOT request audio focus here.
            // Requesting audio focus would cause other apps (Spotify, YouTube, etc.)
            // to pause their music when our app starts.
            // Instead, we check isMusicActive at key points (startWakeWordDetection, maybeResumeVosk)

            // Observe in-app audio player state
            startInAppAudioObserver()

            Log.d(TAG, "Audio detection initialized (passive mode - won't interrupt other apps)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio manager: ${e.message}")
        }
    }

    /**
     * Start observing in-app audio player state.
     * Pauses Vosk when in-app audio is playing.
     */
    private fun startInAppAudioObserver() {
        audioPlayerCollectorJob?.cancel()
        audioPlayerCollectorJob = viewModelScope.launch {
            AudioPlayerService.playerState.collect { state ->
                val wasPlaying = isInAppAudioPlaying
                isInAppAudioPlaying = state.playbackState == PlaybackState.PLAYING

                if (isInAppAudioPlaying && !wasPlaying) {
                    // Audio started playing - pause Vosk
                    Log.d(TAG, "In-app audio started - pausing Vosk")
                    voskWakeWordManager?.stopListening()
                } else if (!isInAppAudioPlaying && wasPlaying) {
                    // Audio stopped - try to resume Vosk
                    Log.d(TAG, "In-app audio stopped - checking if Vosk can resume")
                    maybeResumeVosk()
                }
            }
        }
    }

    /**
     * Start periodic check for system music.
     * Monitors both:
     * - Music stopping (so we can resume Vosk)
     * - Music starting (so we can pause Vosk)
     * Only runs when app is in foreground.
     */
    private fun startMusicCheck() {
        musicCheckJob?.cancel()
        musicCheckJob = viewModelScope.launch {
            Log.d(TAG, "Starting periodic music check (every 2s)")
            // BUG-001 FIX: Use isActive for proper cancellation instead of while(true)
            while (isActive) {
                delay(2000L) // Check every 2 seconds

                // Only check when app is in foreground
                if (!_isAppInForeground.value) {
                    continue
                }

                val isMusicPlaying = audioManager?.isMusicActive == true

                // BUG FIX: Don't treat in-app audio as lost audio focus
                // Only set isAudioFocusLost if music is playing AND it's not us
                if (isMusicPlaying && !isInAppAudioPlaying && !isAudioFocusLost) {
                    // System music (not us) started - pause Vosk
                    Log.d(TAG, "System music started mid-session - pausing Vosk")
                    isAudioFocusLost = true
                    voskWakeWordManager?.stopListening()
                } else if (!isMusicPlaying && isAudioFocusLost) {
                    // Music stopped - try to resume Vosk
                    Log.d(TAG, "System music stopped - resuming Vosk")
                    isAudioFocusLost = false
                    maybeResumeVosk()
                }
            }
        }
    }

    /**
     * Stop the periodic music check.
     */
    private fun stopMusicCheck() {
        musicCheckJob?.cancel()
        musicCheckJob = null
    }

    /**
     * Try to resume Vosk if all conditions allow.
     */
    private fun maybeResumeVosk() {
        if (!_isAppInForeground.value) {
            Log.d(TAG, "Cannot resume Vosk - app in background")
            return
        }
        if (isPhoneCallActive) {
            Log.d(TAG, "Cannot resume Vosk - phone call active")
            return
        }
        if (isAudioFocusLost) {
            Log.d(TAG, "Cannot resume Vosk - audio focus lost")
            return
        }
        if (isInAppAudioPlaying) {
            Log.d(TAG, "Cannot resume Vosk - in-app audio playing")
            return
        }
        if (isMicInUseByOther) {
            Log.d(TAG, "Cannot resume Vosk - mic in use by other")
            return
        }
        if (pendingShareFullPrivacy.value) {
            Log.d(TAG, "Cannot resume Vosk - privacy mode active")
            return
        }
        // Check if any system audio is playing
        if (audioManager?.isMusicActive == true) {
            Log.d(TAG, "Cannot resume Vosk - system music active")
            return
        }
        // Check if Vosk is globally paused (e.g., by AssistActivity)
        if (VoskWakeWordManager.isGloballyPaused) {
            Log.d(TAG, "Cannot resume Vosk - globally paused by AssistActivity")
            return
        }

        Log.d(TAG, "All conditions met - resuming Vosk wake word detection")
        voskWakeWordManager?.restartListening()
    }

    /**
     * Check if audio is available (no call active, no audio focus lost, no audio playing).
     */
    private fun isAudioAvailable(): Boolean {
        return !isPhoneCallActive && !isAudioFocusLost && !isInAppAudioPlaying
    }

    /**
     * Start wake word detection.
     * Call this from Activity.onResume().
     *
     * MED-007: Respects privacy mode - Vosk won't listen during share privacy mode.
     * Also checks audio playback state - won't start if audio is playing.
     */
    fun startWakeWordDetection() {
        // MED-007: Don't start Vosk if in privacy mode (during share flow with full privacy)
        if (pendingShareFullPrivacy.value) {
            Log.d(TAG, "Skipping wake word start - privacy mode active")
            return
        }
        // Don't start if audio is playing (in-app or system)
        if (isInAppAudioPlaying) {
            Log.d(TAG, "Skipping wake word start - in-app audio playing")
            return
        }

        // Always start the music check to monitor for music starting/stopping
        startMusicCheck()

        if (audioManager?.isMusicActive == true) {
            Log.d(TAG, "Skipping wake word start - system music active")
            isAudioFocusLost = true
            return
        }

        isAudioFocusLost = false
        voskWakeWordManager?.startListening()
    }

    /**
     * Stop wake word detection.
     * Call this from Activity.onPause().
     */
    fun stopWakeWordDetection() {
        voskWakeWordManager?.stopListening()
        // Stop music check when going to background (will restart on resume if needed)
        stopMusicCheck()
    }

    /**
     * MED-007: Pause Vosk when entering privacy-sensitive mode.
     * Called when pendingShareFullPrivacy changes to true.
     */
    private fun pauseVoskForPrivacy() {
        if (pendingShareFullPrivacy.value) {
            Log.d(TAG, "Pausing Vosk for privacy mode")
            voskWakeWordManager?.stopListening()
        }
    }

    /**
     * Manually trigger voice input (e.g. from widget)
     */
    fun triggerVoiceInput() {
        _wakeWordTriggered.value = true
    }

    /**
     * Manually trigger camera input (e.g. from widget)
     * Opens the image picker when the app launches
     */
    fun triggerCameraInput() {
        _cameraTriggered.value = true
    }

    /**
     * Clear the camera trigger flag after it has been handled
     */
    fun clearCameraTrigger() {
        _cameraTriggered.value = false
    }

    /**
     * Restart wake word detection after Google STT completes.
     * Call this from onActivityResult after speech reJarvistion finishes.
     *
     * PRIVACY: Only restarts if app is in foreground to prevent background mic access.
     * MED-007: Also respects privacy mode.
     */
    fun restartWakeWordDetection() {
        _wakeWordTriggered.value = false
        // CRITICAL: Only restart if all conditions are met
        if (!_isAppInForeground.value) {
            Log.d(TAG, "Skipping wake word restart - app is in background")
            return
        }
        if (isMicInUseByOther) {
            Log.d(TAG, "Skipping wake word restart - mic in use by voice enrollment or other")
            return
        }
        if (isPhoneCallActive) {
            Log.d(TAG, "Skipping wake word restart - phone call is active")
            return
        }
        if (isAudioFocusLost) {
            Log.d(TAG, "Skipping wake word restart - audio focus lost to another app")
            return
        }
        if (isInAppAudioPlaying) {
            Log.d(TAG, "Skipping wake word restart - in-app audio playing")
            return
        }
        if (audioManager?.isMusicActive == true) {
            Log.d(TAG, "Skipping wake word restart - system music active")
            isAudioFocusLost = true
            startMusicCheck()
            return
        }
        // MED-007: Don't restart if in privacy mode
        if (pendingShareFullPrivacy.value) {
            Log.d(TAG, "Skipping wake word restart - privacy mode active")
            return
        }
        voskWakeWordManager?.restartListening()
    }

    /**
     * Clear the wake word triggered flag.
     * Call this if STT is cancelled.
     */
    fun clearWakeWordTrigger() {
        _wakeWordTriggered.value = false
    }

    /**
     * Clear the speaker verification cache.
     * Call this after voice fingerprint is deleted or retrained.
     * Forces the wake word detector to reload the embedding from disk.
     */

    /**
     * Mark mic as in use by voice enrollment or other features.
     * Prevents wake word from auto-restarting when app resumes.
     */
    fun setMicInUseByOther(inUse: Boolean) {
        isMicInUseByOther = inUse
        Log.d(TAG, "Mic in use by other: $inUse")
    }

    /**
     * Update the current screen route - call when navigation changes
     * Shake gesture only works on the main inputStream screen
     * 
     * OPTIMIZATION: Automatically starts/stops shake detection based on screen.
     * This saves significant battery by not running the accelerometer sensor
     * when user is on Settings, Calendar, Stacks, or other screens.
     */
    fun setCurrentScreen(screen: String) {
        val previousScreen = _currentScreen.value
        _currentScreen.value = screen
        
        // BATTERY OPTIMIZATION: Only run shake sensor on main screen
        when {
            screen == "input_stream" && previousScreen != "input_stream" -> {
                // Entering main screen - start shake detection
                shakeDetector?.start()
                Log.d(TAG, "Screen -> $screen: Started shake detection (battery optimization)")
            }
            screen != "input_stream" && previousScreen == "input_stream" -> {
                // Leaving main screen - stop shake detection to save battery
                shakeDetector?.stop()
                Log.d(TAG, "Screen -> $screen: Stopped shake detection (battery optimization)")
            }
            else -> {
                Log.d(TAG, "Screen -> $screen (shake detection unchanged)")
            }
        }
    }

    /**
     * Handle shake gesture contextually
     * ONLY works on the main input_stream screen - ignored on other screens
     * Priority: Share mode > Chat mode > Input content (text OR attachments) > Empty input
     */
    private fun handleShake() {
        // Only process shake on main screen (input_stream)
        if (_currentScreen.value != "input_stream") {
            Log.d(TAG, "Shake ignored - not on main screen (current: ${_currentScreen.value})")
            return
        }

        // Provide haptic feedback ONLY when shake is actually processed
        // This prevents vibration on Calendar, Settings, Stacks, etc.
        shakeDetector?.triggerHapticFeedback()

        when {
            // Priority 1: During share flow -> toggle full privacy mode
            shareFlowManager.isInShareMode() -> {
                toggleShareFullPrivacy()
                Log.d(TAG, "Shake: Toggled full privacy mode during share")
            }
            // Priority 2: Mic is listening OR has text OR has attachments -> toggle AI exclusion/privacy
            _isMicListening.value || _currentInputText.value.isNotBlank() || _currentInputAttachments.value.isNotEmpty() -> {
                togglePendingNoteAiExclusion()
                Log.d(TAG, "Shake: Toggled AI exclusion (active content)")
            }
            // Priority 3: Completely empty (no mic, no text, no attachments) -> toggle chat mode
            else -> {
                toggleChatMode(fromShake = true)
                Log.d(TAG, "Shake: Toggled chat mode (empty state)")
            }
        }
    }

    /**
     * Toggle full privacy mode for share flow (delegated to ShareFlowManager)
     */
    fun toggleShareFullPrivacy() {
        shareFlowManager.toggleFullPrivacy()
    }

    /**
     * Start shake detection - call from Activity.onResume()
     */
    fun startShakeDetection() {
        shakeDetector?.start()
    }

    /**
     * Stop shake detection - call from Activity.onPause()
     */
    fun stopShakeDetection() {
        shakeDetector?.stop()
    }

    /**
     * Toggle between note input mode and chat mode (delegated to ChatManager)
     * @param fromShake Whether this toggle was triggered by a shake gesture
     */
    fun toggleChatMode(fromShake: Boolean = false) {
        // Ensure chat manager is initialized before toggling
        ensureChatManagerInitialized()

        // Track if this was shake-triggered for glow animation
        if (fromShake) {
            _wasShakeTriggered.value = true
            viewModelScope.launch {
                kotlinx.coroutines.delay(500) // Reset after animation
                _wasShakeTriggered.value = false
            }
        }

        chatManager.toggleChatMode()
        // Persist chat mode state for process death recovery (BUG-053)
        savedStateHandle[KEY_IS_CHAT_MODE] = !isChatMode.value  // Will be opposite after toggle
    }

    /**
     * Enter chat mode (delegated to ChatManager)
     * Called when user taps the AI/Chat tab
     */
    fun enterChatMode() {
        ensureChatManagerInitialized()
        viewModelScope.launch {
            chatManager.enterChatMode()
            // Persist chat mode state for process death recovery (BUG-053)
            savedStateHandle[KEY_IS_CHAT_MODE] = true
        }
    }

    /**
     * Initial text to pre-populate in chat input (for @mention quick reference).
     * Set when user clicks "Ask AI" from KnowledgeCard.
     */
    private val _pendingChatText = MutableStateFlow<String?>(null)
    val pendingChatText: StateFlow<String?> = _pendingChatText.asStateFlow()

    /**
     * Enter chat mode with a note pre-referenced.
     * Called when user clicks "Ask AI" button on a note card.
     *
     * @param noteTitle Title of the note to reference
     */
    fun enterChatWithNoteReference(noteTitle: String) {
        // Build the @mention text
        val mentionText = if (noteTitle.contains(' ')) {
            "@\"$noteTitle\" "
        } else {
            "@${noteTitle.replace(' ', '_')} "
        }

        _pendingChatText.value = mentionText
        enterChatMode()
    }

    /**
     * Clear pending chat text after it's been consumed by the UI.
     */
    fun clearPendingChatText() {
        _pendingChatText.value = null
    }

    /**
     * Exit chat mode and return to note input mode (delegated to ChatManager)
     */
    fun exitChatMode() {
        chatManager.exitChatMode()
        // Persist chat mode state for process death recovery (BUG-053)
        savedStateHandle[KEY_IS_CHAT_MODE] = false
    }

    /**
     * Create a new chat session (delegated to ChatManager)
     */
    fun createNewChatSession() {
        chatManager.createNewChatSession()
    }

    /**
     * Switch to a different chat session (delegated to ChatManager)
     */
    fun switchToChatSession(sessionId: String) {
        chatManager.switchToChatSession(sessionId)
    }

    /**
     * Delete a chat session (delegated to ChatManager)
     */
    fun deleteChatSession(sessionId: String) {
        chatManager.deleteChatSession(sessionId)
    }

    /**
     * Clear current chat history (delegated to ChatManager)
     */
    fun clearChatHistory() {
        chatManager.clearChatHistory()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // @MENTION HANDLING - Real-time autocomplete for note references
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Update mention state when chat input text changes.
     * Detects active @mention typing and fetches suggestions.
     *
     * @param text Current text field content
     * @param cursorPosition Current cursor position in text
     */
    fun updateMentionState(text: String, cursorPosition: Int) {
        chatInputCursorPosition = cursorPosition

        viewModelScope.launch {
            val detection = MentionParser.detectActiveMention(text, cursorPosition)

            if (detection.isTypingMention && !detection.isEmailPattern) {
                // User is typing a mention - get suggestions
                val suggestions = mentionManager.getSuggestions(detection.query)
                _mentionState.value = MentionState(
                    isActive = true,
                    query = detection.query,
                    triggerIndex = detection.triggerIndex,
                    suggestions = suggestions,
                    highlightedIndex = 0
                )
            } else {
                // Not typing a mention - dismiss dropdown
                if (_mentionState.value.isActive) {
                    _mentionState.value = MentionState()
                }
            }
        }
    }

    /**
     * Handle mention selection from autocomplete dropdown.
     * Returns the text to insert (replacing @query with proper mention).
     *
     * @param suggestion Selected mention suggestion
     * @param currentText Current text field content
     * @return Updated text with mention inserted
     */
    fun onMentionSelected(suggestion: MentionSuggestion, currentText: String): String {
        val mentionState = _mentionState.value
        if (!mentionState.isActive || mentionState.triggerIndex < 0) {
            return currentText
        }

        // Build the replacement text based on suggestion type
        val replacement = when (suggestion) {
            is MentionSuggestion.NoteSuggestion -> {
                val title = suggestion.note.title
                // Use quotes if title has spaces
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

        // Calculate what to replace: from @triggerIndex to cursor position
        val beforeMention = currentText.substring(0, mentionState.triggerIndex)
        val afterCursor = if (chatInputCursorPosition < currentText.length) {
            currentText.substring(chatInputCursorPosition)
        } else ""

        // Dismiss the dropdown
        _mentionState.value = MentionState()

        // Return updated text with mention and trailing space
        return "$beforeMention$replacement $afterCursor"
    }

    /**
     * Dismiss mention dropdown without selection.
     */
    fun dismissMention() {
        _mentionState.value = MentionState()
    }

    /**
     * UNIVERSAL ACTION DISPATCHER
     * The primary entry point for all user intent.
     * Hybridizes fast-path rule execution with deep-path agentic reasoning.
     */
    fun dispatchQuery(content: String, attachments: List<Attachment> = emptyList()) {
        if (content.isBlank() && attachments.isEmpty()) return

        // Ensure GROQ keys are synced before first AI request
        ensureGroqKeysSynced()
        // Ensure chat manager is initialized
        ensureChatManagerInitialized()

        viewModelScope.launch {
            chatManager.setProcessing(true)
            chatManager.resetApiCallFlag()

            // Ensure we have a session
            chatManager.ensureSession()

            // Add user message to chat history
            val userMessage = chatManager.addUserMessage(content, attachments)

            try {
                // 1. FAST-PATH: Check Local Command Processor (0ms latency, offline)
                val commandResult = localCommandProcessor.process(content)
                when (commandResult) {
                    is com.example.smarty.service.CommandResult.Handled -> {
                        Log.i(TAG, "Query handled by FAST-PATH: $content")
                        val assistantMessage = ChatMessage(
                            role = ChatRole.ASSISTANT,
                            content = commandResult.response
                        )
                        chatManager.addAssistantMessage(assistantMessage)
                        chatManager.saveMessagePair(
                            userMessage = userMessage,
                            assistantMessage = assistantMessage,
                            hasApiKeys = settingsFeatureManager.hasAnyApiKeys()
                        )
                        return@launch // Handled locally
                    }
                    is com.example.smarty.service.CommandResult.HandledAndPassToLLM -> {
                        Log.i(TAG, "FAST-PATH executed action, but passing to REASONING-PATH for additional intent")
                        val localMessage = ChatMessage(
                            role = ChatRole.ASSISTANT,
                            content = commandResult.response
                        )
                        chatManager.addAssistantMessage(localMessage)
                    }
                    else -> Log.d(TAG, "Query falling back to REASONING-PATH: $content")
                }

                // 2. REASONING-PATH: AI Agent processing for complex intent
                processReasoningPath(content, userMessage)

            } catch (e: Exception) {
                Log.e(TAG, "Error in universal dispatcher: ${e.message}", e)
                chatManager.addAssistantMessage(ChatMessage(role = ChatRole.ASSISTANT, content = "I encountered an error: ${e.message}"))
            } finally {
                chatManager.setProcessing(false)
            }
        }
    }

    /**
     * Internal implementation of the agentic reasoning path.
     */
    private suspend fun processReasoningPath(content: String, userMessage: ChatMessage) {
        // Build conversation history for agent memory
        val conversationHistory = chatManager.chatMessages.value
            .filter { it.role != ChatRole.SYSTEM }
            .map { msg ->
                val role = when (msg.role) {
                    ChatRole.USER -> "User"
                    ChatRole.ASSISTANT -> "Assistant"
                    else -> "System"
                }
                Pair(role, msg.content)
            }

        // Clear pending citations before running agent
        pendingCitations.clear()

        // Reset mention state
        _mentionState.value = MentionState()

        // Parse @mentions
        val parsedMentions = MentionParser.parseAllMentions(content)
        val taggedNoteContext = if (parsedMentions.isNotEmpty()) {
            val resolvedMentions = mentionManager.resolveMentions(parsedMentions)
            noteContextBuilder.buildContext(resolvedMentions)
        } else null

        // @THINKING deep analysis
        val thinkingModeContext = if (thinkingModeProcessor.hasThinkingCommand(content) && taggedNoteContext != null) {
            thinkingModeProcessor.processThinkingMode(content, taggedNoteContext.resolvedMentions.flatMap { it.notes })
        } else null

        // Prepare final prompt
        val cleanedContent = if (parsedMentions.isNotEmpty()) MentionParser.cleanMessage(content, parsedMentions) else content
        val finalUserMessage = if (!_isThinkingModeEnabled.value) "/no_think $cleanedContent" else cleanedContent

        // Execute Agent
        val result = JarvisAgent.run(
            userMessage = finalUserMessage,
            conversationHistory = conversationHistory,
            taggedNoteContext = taggedNoteContext,
            thinkingModeContext = thinkingModeContext,
            isThinkingModeEnabled = _isThinkingModeEnabled.value
        )

        handleAgentResult(result, userMessage)
    }

    private suspend fun handleAgentResult(result: AgentResult, userMessage: ChatMessage) {
        when (result) {
            is AgentResult.Success -> {
                Log.d(TAG, "Agent completed successfully via ${result.provider}")

                val filteredResponse = filterPlanningText(result.response) ?: return
                val (responseWithoutSuggestions, suggestions) = extractSuggestionsFromResponse(filteredResponse)
                val (cleanedResponse, clarificationRequest) = extractClarificationFromResponse(responseWithoutSuggestions)

                // Citations and Images
                val citations = pendingCitations.map { wc -> Citation(title = wc.title, url = wc.url, snippet = wc.snippet) }
                pendingCitations.clear()
                val inlineImages = pendingInlineImages.toList()
                pendingInlineImages.clear()

                // Thinking Content
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
                chatManager.saveMessagePair(
                    userMessage = userMessage,
                    assistantMessage = assistantMessage,
                    hasApiKeys = true
                )

                if (settingsFeatureManager.isSoundEnabled() && !_wakeWordTriggered.value) {
                    completionSoundManager.playAgentCompletionSound(isAppInForeground = _isAppInForeground.value)
                }
            }
            is AgentResult.Error -> {
                chatManager.addAssistantMessage(ChatMessage(role = ChatRole.ASSISTANT, content = "Error: ${result.message}", isError = true))
            }
            is AgentResult.NoProvider -> {
                chatManager.addAssistantMessage(ChatMessage(role = ChatRole.ASSISTANT, content = "Please configure an AI provider API key in Settings."))
            }
        }
    }

    /**
     * Send a message in chat mode using the Koog-based AI agent.
     */
    fun sendChatMessage(content: String, attachments: List<Attachment> = emptyList()) {
        dispatchQuery(content, attachments)
    }

    // ==================== Calendar Operations (delegated to CalendarManager) ====================

    /**
     * Add a new calendar event
     */
    fun addCalendarEvent(
        title: String,
        description: String? = null,
        startTime: Long,
        endTime: Long,
        isAllDay: Boolean = false,
        location: String? = null,
        color: Int? = null,
        reminderMinutes: Int? = null,
        isPrivate: Boolean = false
    ) = calendarManager.addCalendarEvent(
        title = title,
        description = description,
        startTime = startTime,
        endTime = endTime,
        isAllDay = isAllDay,
        location = location,
        color = color,
        reminderMinutes = reminderMinutes,
        isPrivate = isPrivate
    )

    /**
     * Update an existing calendar event
     */
    fun updateCalendarEvent(event: CalendarEvent) = calendarManager.updateCalendarEvent(event)

    /**
     * Delete a calendar event by ID
     */
    fun deleteCalendarEvent(eventId: String) = calendarManager.deleteCalendarEvent(eventId)

    /**
     * Get events for a specific day
     */
    suspend fun getEventsForDay(dayMillis: Long): List<CalendarEvent> =
        calendarManager.getEventsForDay(dayMillis)

    /**
     * Get today's events
     */
    suspend fun getTodayEvents(): List<CalendarEvent> = calendarManager.getTodayEvents()

    /**
     * Get AI-visible upcoming events (for agent context)
     */
    suspend fun getAiVisibleUpcomingEvents(limit: Int = 10): List<CalendarEvent> =
        calendarManager.getAiVisibleUpcomingEvents(limit)

    // ==================== Dynamic Model Management ====================

    fun getAvailableModels(provider: AIProvider): List<Pair<String, String>> {
        return settingsFeatureManager.getAvailableModels(provider)
    }

    fun refreshGroqModels() {
        settingsFeatureManager.refreshGroqModels { success ->
            if (success) {
                // Trigger queue processing - provider config changed
                noteProcessingQueueManager.onProviderAvailable()
            }
        }
    }
    // ==================== Resource Optimization ====================

    // Track if resource-intensive operations are paused
    private var isResourceOptimized = false

    /**
     * Pause resource-intensive operations when app goes to background (onPause).
     * This reduces battery consumption while preserving essential functionality.
     */
    fun pauseResourceIntensiveOperations() {
        if (isResourceOptimized) return
        isResourceOptimized = true
        Log.d(TAG, "Pausing resource-intensive operations")

        // CRITICAL: Mark app as backgrounded to stop all microphone access
        _isAppInForeground.value = false

        // CRITICAL: Stop wake word detection to release microphone
        stopWakeWordDetection()
        Log.d(TAG, "Stopped wake word detection for background")

        // Flush any pending batched database writes before going to background
        viewModelScope.launch {
            noteOperationsManager.flushPendingWrites()
        }

        // Clear in-memory caches to reduce memory footprint
        AIResponseCache.clear()
        systemFeatureManager.clearTemporaryData()

        // Image loading is handled by Coil which auto-pauses when lifecycle not active
        // Database access remains active for scheduled backups
        // Audio service continues if playing (handled by AudioPlayerService)

        Log.d(TAG, "Background optimization complete - cleared temporary caches")
    }

    /**
     * Resume resource-intensive operations when app returns to foreground (onResume).
     */
    fun resumeResourceIntensiveOperations() {
        if (!isResourceOptimized) return
        isResourceOptimized = false
        Log.d(TAG, "Resuming resource-intensive operations")

        // CRITICAL: Mark app as foregrounded to allow microphone access
        _isAppInForeground.value = true

        // RACE CONDITION FIX: Do NOT restart wake word here!
        // MainActivity.onResume() handles wake word restart with a 300ms delay
        // to allow proper initialization after process death.
        // Having two paths (immediate + delayed) causes race conditions
        // in VoskWakeWordManager's native resources.
        // See: APP_CRASH_ON_RESUME.md Bug #5

        // Refresh cache size on resume
        refreshCacheSize()
    }

    /**
     * Minimize background resources when app goes to background (onStop).
     * Trims memory caches while preserving database and scheduled operations.
     */
    fun minimizeBackgroundResources() {
        Log.d(TAG, "Minimizing background resources")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Trim image caches to reduce memory footprint
                cacheManager.trimToSize(cacheManager.getCacheSize() / 2)
                Log.d(TAG, "Trimmed cache to 50% of current size")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to trim cache: ${e.message}")
            }
        }
    }

    /**
     * Clean up resources when ViewModel is cleared
     *
     * LEAK FIX: Replaced GlobalScope with ProcessLifecycleOwner scope.
     * GlobalScope creates orphaned coroutines that hold ViewModel references,
     * causing memory leaks. ProcessLifecycleOwner scope is tied to app lifecycle.
     */
    override fun onCleared() {
        super.onCleared()

        // LEAK FIX: Use viewModelScope for cleanup instead of GlobalScope
        // viewModelScope is still active during onCleared and completes pending work
        // GlobalScope creates orphaned coroutines that hold ViewModel references
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withTimeout(3000L) {
                    noteOperationsManager.cleanup()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup timeout or error: ${e.message}")
            }
        }

        // Cancel wake word collector job to prevent coroutine leak
        wakeWordCollectorJob?.cancel()
        wakeWordCollectorJob = null
        shakeDetector?.stop()
        shakeDetector = null
        voskWakeWordManager?.destroy()
        voskWakeWordManager = null

        // Clean up completion sound manager
        completionSoundManager.shutdown()

        // Clean up phone state listener to prevent memory leak
        @Suppress("DEPRECATION")
        try {
            phoneStateListener?.let { listener ->
                telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up phone state listener: ${e.message}")
        }
        phoneStateListener = null
        telephonyManager = null

        // Clean up audio player observer
        audioPlayerCollectorJob?.cancel()
        audioPlayerCollectorJob = null

        // Clean up music check job
        musicCheckJob?.cancel()
        musicCheckJob = null

        audioManager = null
    }
}

/**
 * Factory for JarvisViewModel that provides SavedStateHandle for state preservation
 * across process death (BUG-053 fix).
 *
 * Usage in Activity:
 * ```
 * private val viewModel: JarvisViewModel by viewModels {
 *     JarvisViewModelFactory(application, this)
 * }
 * ```
 */
class JarvisViewModelFactory(
    private val application: Application,
    owner: SavedStateRegistryOwner
) : AbstractSavedStateViewModelFactory(owner, null) {

    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(
        key: String,
        modelClass: Class<T>,
        handle: SavedStateHandle
    ): T {
        if (modelClass.isAssignableFrom(JarvisViewModel::class.java)) {
            return JarvisViewModel(application, handle) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
