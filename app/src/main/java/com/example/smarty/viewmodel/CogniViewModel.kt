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
import com.example.smarty.data.local.CogniDatabase
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.model.Attachment
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatRole
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteAttachment
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.model.TodoItem
import com.example.smarty.data.model.getAllAttachmentUris
import com.example.smarty.data.model.getAttachments
import com.example.smarty.data.model.getTodos
import com.example.smarty.data.model.withAttachments
import com.example.smarty.data.model.withTodos
import com.example.smarty.data.remote.AIService
import com.example.smarty.agent.AgentCallbacks
import com.example.smarty.agent.AgentResult
import com.example.smarty.agent.CogniAgent
import com.example.smarty.agent.CogniAgentProvider
import com.example.smarty.data.remote.providers.TavilySearchProvider

import com.example.smarty.ui.components.PendingShareData
import com.google.gson.Gson
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.example.smarty.data.repository.ChatRepository
import com.example.smarty.data.repository.CogniRepository
import com.example.smarty.data.model.ChatSession
import com.example.smarty.util.ContentTypeDetector
import com.example.smarty.util.FileStorageHelper
import com.example.smarty.util.PDFTextExtractor
import com.example.smarty.util.PDFExtractionResult
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.ShakeDetector
import com.example.smarty.util.NetworkMonitor
import com.example.smarty.ui.components.ConnectionStatus
import com.example.smarty.voice.VoskWakeWordManager
import com.example.smarty.voice.ResponseTTSManager
import com.example.smarty.util.api.RateLimiter
import android.media.AudioManager
import android.telephony.TelephonyManager
import android.telephony.PhoneStateListener
import android.os.Build
import com.example.smarty.util.api.GroqKeyManager
import com.example.smarty.util.api.KeyUsageStats
import com.example.smarty.service.AlarmScheduler
import com.example.smarty.service.AudioPlayerService
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

class CogniViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    // SavedStateHandle keys for state preservation across process death (BUG-053)
    companion object {
        private const val TAG = "CogniViewModel"
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
    private val database: CogniDatabase by lazy { CogniDatabase.getDatabase(application) }
    private val repository: CogniRepository by lazy {
        CogniRepository(
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

    // Rate limiter for API call management (30 calls/min, 14.4k/day) - lazy
    private val rateLimiter: RateLimiter by lazy {
        RateLimiter.getInstance(application)
    }

    // GROQ Key Manager for per-key usage tracking - lazy
    private val groqKeyManager: GroqKeyManager by lazy {
        GroqKeyManager.getInstance(application)
    }

    // TTS Manager for speaking AI responses - lazy
    private val responseTTSManager: ResponseTTSManager by lazy {
        ResponseTTSManager(application).also { tts ->
            // Set up callbacks to pause/resume wake word detection during TTS
            // Note: These callbacks are called from TTS background thread,
            // so we post to viewModelScope for thread safety
            tts.setSpeechLifecycleCallbacks(
                onStart = {
                    // Post to main thread for thread safety
                    viewModelScope.launch(Dispatchers.Main) {
                        // Pause wake word detection while AI is speaking
                        voskWakeWordManager?.stopListening()
                        Log.d(TAG, "TTS started - paused wake word detection")
                    }
                },
                onEnd = {
                    // Post to main thread for thread safety
                    viewModelScope.launch(Dispatchers.Main) {
                        // Resume wake word detection after AI finishes speaking
                        voskWakeWordManager?.restartListening()
                        Log.d(TAG, "TTS ended - resumed wake word detection")
                    }
                }
            )
        }
    }

    // Koog-based AI Agent (GROQ-only with multi-key rotation) - lazy
    private val agentProvider: CogniAgentProvider by lazy {
        CogniAgentProvider(securePreferences, groqKeyManager)
    }
    private val cogniAgent: CogniAgent by lazy {
        CogniAgent(
            agentProvider = agentProvider,
            repository = repository,
            tavilySearchProvider = tavilySearchProvider,
            alarmScheduler = alarmScheduler,
            callbacks = agentCallbacks,
            rateLimiter = rateLimiter  // API budget management
        )
    }

    // GROQ key usage stats exposed for UI - lazy
    val groqKeyUsageStats: StateFlow<List<KeyUsageStats>> by lazy { groqKeyManager.usageStats }

    // TTS state exposed for UI
    val isTTSSpeaking: StateFlow<Boolean> by lazy { responseTTSManager.isSpeaking }
    val isTTSEnabled: StateFlow<Boolean> by lazy { responseTTSManager.isEnabled }

    // Agent callbacks for Koog tools that need ViewModel state
    // SECURITY: Pre-filter notes at callback level for defense-in-depth
    private val agentCallbacks = object : AgentCallbacks {
        override fun getActiveNotes(): List<Note> = PrivacyGuard.getAiVisibleNotes(notes.value)
        override fun getArchivedNotes(): List<Note> = PrivacyGuard.getAiVisibleNotes(archivedNotes.value)
        override fun getCategories(): List<Category> = categories.value
        override fun getTavilyApiKey(): String? = securePreferences.getTavilyApiKey()

        override suspend fun processNoteWithAi(note: Note) {
            simulateAiProcessing(note)
        }

        override suspend fun findNoteByDescription(description: String, notes: List<Note>): Note? {
            // Simple fuzzy matching
            return notes.find { note ->
                note.title.contains(description, ignoreCase = true) ||
                note.content.contains(description, ignoreCase = true)
            }
        }

        override fun requestAudioPlayback(track: AudioTrack) {
            // BUG FIX (ISSUE 3): Add logging to verify tool callback execution
            Log.i(TAG, "▶ requestAudioPlayback CALLBACK INVOKED: track='${track.title}', uri=${track.uri}")

            // Stop TTS before playing audio - user wants to hear the audio, not TTS
            responseTTSManager.stop()
            Log.d(TAG, "TTS stopped for audio playback")

            // Directly set the track for playback - it now has a valid URI from the note attachment
            _pendingAudioPlayback.value = track
            Log.d(TAG, "✓ pendingAudioPlayback set to: ${track.title}")
        }

        override fun onToolExecutionStarted(toolName: String, toolDisplayName: String) {
            // No-op: Dynamic Island removed
        }

        override fun onToolExecutionCompleted(toolName: String) {
            // No-op: Dynamic Island removed
        }
    }

    // Chat repository for persistence - lazy to avoid blocking
    private val chatRepository: ChatRepository by lazy {
        ChatRepository(database.chatDao())
    }

    // Calendar DAO for event management - lazy
    private val calendarDao by lazy { database.calendarDao() }

    // Shake detector for toggling chat mode
    private var shakeDetector: ShakeDetector? = null

    // Vosk wake word manager for offline "Terminator" detection
    private var voskWakeWordManager: VoskWakeWordManager? = null

    // Wake word detection state
    private val _isWakeWordActive = MutableStateFlow(false)
    val isWakeWordActive: StateFlow<Boolean> = _isWakeWordActive.asStateFlow()

    private val _wakeWordTriggered = MutableStateFlow(false)
    val wakeWordTriggered: StateFlow<Boolean> = _wakeWordTriggered.asStateFlow()

    // Mutex for thread-safe note operations (BUG-016 fix)
    private val noteOperationMutex = Mutex()

    // ==================== Delegated Managers ====================

    // Chat Manager - handles chat state and session lifecycle
    private val chatManager = ChatManager(chatRepository, viewModelScope)

    // Share Flow Manager - handles share interception and processing
    private val shareFlowManager = ShareFlowManager(
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
            try {
                // Force reload from database
                repository.refreshNotes()
                // Brief delay for visual feedback
                kotlinx.coroutines.delay(500)
            } finally {
                _isRefreshing.value = false
            }
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



    // Expose secure preferences state for UI
    val geminiKeys: StateFlow<List<String>> = securePreferences.geminiKeys
    val huggingFaceKeys: StateFlow<List<String>> = securePreferences.huggingFaceKeys
    val providerConfigs: StateFlow<Map<AIProvider, AIProviderConfig>> = securePreferences.providerConfigs
    val providerPriorityOrder: StateFlow<List<AIProvider>> = securePreferences.providerPriorityOrder
    val isPinSet: StateFlow<Boolean> = securePreferences.isPinSet

    fun setProviderPriority(priority: List<AIProvider>) {
        securePreferences.setProviderPriority(priority)
    }

    // Cache management
    private val cacheManager = CacheManager.getInstance(application)
    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()
    private val _isClearingCache = MutableStateFlow(false)
    val isClearingCache: StateFlow<Boolean> = _isClearingCache.asStateFlow()

    // Audio playback request from AI agent (observed by MainActivity to trigger playback)
    private val _pendingAudioPlayback = MutableStateFlow<AudioTrack?>(null)
    val pendingAudioPlayback: StateFlow<AudioTrack?> = _pendingAudioPlayback.asStateFlow()

    fun clearPendingAudioPlayback() {
        _pendingAudioPlayback.value = null
    }



    // Selected category (must be declared before notes flow that uses it)
    private val _selectedCategory = MutableStateFlow<Category?>(null)
    val selectedCategory: StateFlow<Category?> = _selectedCategory.asStateFlow()

    // Search and Filter State
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFilters = MutableStateFlow<Set<AttachmentOption>>(emptySet())
    val selectedFilters: StateFlow<Set<AttachmentOption>> = _selectedFilters.asStateFlow()

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
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
        
        // Step 1: Fetch candidates from DB
        // We do NOT filter by type in DB anymore because we need to check ALL attachments
        // and support "AND" logic (intersection), which SQL "IN" clause doesn't support easily.
        val candidatesFlow = if (effectiveQuery.isEmpty()) {
            if (category != null) repository.getNotesByCategory(category.id)
            else repository.getAllNotes()
        } else {
            // Pass empty list to searchNotes so it ignores type filtering
            repository.searchNotes(effectiveQuery, emptyList())
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
                    filters.all { filter -> noteMatchesFilter(note, filter) }
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Check if a note contains content matching the specific filter.
     * Checks both primary note type and all attachments.
     */
    private fun noteMatchesFilter(note: Note, filter: AttachmentOption): Boolean {
        // 1. Check primary type
        if (typeMatchesFilter(note.type, filter)) return true
        
        // 2. Check source URL for Link/Website
        if (filter == AttachmentOption.LINK && (note.sourceUrl != null || note.type == NoteType.WEBSITE || note.type == NoteType.YOUTUBE)) return true

        // 3. Check all attachments
        val attachments = note.getAttachments()
        return attachments.any { attachment ->
             mimeTypeMatchesFilter(attachment.mimeType, filter)
        }
    }

    private fun typeMatchesFilter(type: NoteType, filter: AttachmentOption): Boolean {
        return when (filter) {
            AttachmentOption.IMAGE -> type == NoteType.IMAGE || type == NoteType.INSTAGRAM
            AttachmentOption.VIDEO -> type == NoteType.VIDEO || type == NoteType.YOUTUBE
            AttachmentOption.AUDIO -> type == NoteType.AUDIO
            AttachmentOption.DOCUMENT -> type == NoteType.DOCUMENT || type == NoteType.SPREADSHEET || type == NoteType.PRESENTATION
            AttachmentOption.FILE -> type == NoteType.FILE || type == NoteType.ARCHIVE || type == NoteType.APK || type == NoteType.CODE
            AttachmentOption.LINK -> type == NoteType.WEBSITE || type == NoteType.TWITTER
        }
    }

    private fun mimeTypeMatchesFilter(mimeType: String, filter: AttachmentOption): Boolean {
        return when (filter) {
            AttachmentOption.IMAGE -> mimeType.startsWith("image/")
            AttachmentOption.VIDEO -> mimeType.startsWith("video/")
            AttachmentOption.AUDIO -> mimeType.startsWith("audio/")
            AttachmentOption.DOCUMENT -> mimeType.contains("pdf") || mimeType.contains("word") || mimeType.contains("excel") || mimeType.contains("powerpoint") || mimeType.contains("text/")
            AttachmentOption.FILE -> true // Broad catch-all for files if explicitly tagged, but usually specific mimes
            AttachmentOption.LINK -> false // Links don't have mime types in attachments usually
        }
    }


    val categories = repository.getAllCategories()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Calendar events
    val calendarEvents = calendarDao.getAllEvents()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedNote = MutableStateFlow<Note?>(null)
    val selectedNote: StateFlow<Note?> = _selectedNote.asStateFlow()

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

        // Restore state from SavedStateHandle after process death (BUG-053)
        // Made non-blocking - failures won't affect startup
        restoreState()

        // Initialize Neural TTS (uses bundled model, no download needed)
        initializeNeuralTTS()
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
                repository.syncAllCategoryCounts()
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
            // Restore selected note by ID with retry
            savedStateHandle.get<String>(KEY_SELECTED_NOTE_ID)?.let { noteId ->
                restoreWithRetry("note") {
                    val note = repository.getNoteById(noteId)
                    if (note != null) {
                        _selectedNote.value = note
                        Log.d(TAG, "Restored selectedNote: ${note.id}")
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
                    val category = repository.getCategoryById(categoryId)
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
        viewModelScope.launch {
            repository.syncAllCategoryCounts()
        }
    }

    fun selectNote(note: Note?) {
        _selectedNote.value = note
        // Persist to SavedStateHandle for process death recovery (BUG-053)
        savedStateHandle[KEY_SELECTED_NOTE_ID] = note?.id
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
        viewModelScope.launch {
            try {
                val detectedType = if (type == NoteType.BRAIN_DUMP) detectContentType(content) else type
                val shouldProcess = shouldAnalyze(detectedType)

                val note = Note(
                    title = extractTitle(content, detectedType),
                    content = content,
                    type = detectedType,
                    sourceUrl = sourceUrl ?: if (detectedType != NoteType.BRAIN_DUMP && content.startsWith("http")) content else null,
                    processingStatus = if (shouldProcess) ProcessingStatus.PROCESSING else ProcessingStatus.COMPLETED,
                    excludeFromAiChat = excludeFromAiChat
                )
                repository.insertNote(note)
                Log.d(TAG, "Text note inserted: ${note.id}")

                try {
                    if (shouldProcess) {
                        simulateAiProcessing(note)
                    } else {
                        // Just categorize without AI analysis
                        storeWithoutAnalysis(note)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AI processing failed for text note: ${e.message}", e)
                    storeWithoutAnalysis(note)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Text note creation failed: ${e.message}", e)
            }
        }
    }

    fun addNoteFromShare(sharedContent: SharedContent) {
        viewModelScope.launch {
            val note = when {
                // File shared
                sharedContent.fileUri != null -> {
                    val type = detectTypeFromMime(sharedContent.mimeType)
                    val shouldProcess = shouldAnalyze(type)

                    Note(
                        title = sharedContent.fileName ?: getDefaultTitle(type),
                        content = buildFileDescription(sharedContent),
                        fileUri = sharedContent.fileUri,
                        fileName = sharedContent.fileName,
                        fileMimeType = sharedContent.mimeType,
                        fileSize = sharedContent.fileSize,
                        imageUri = if (type == NoteType.IMAGE) sharedContent.fileUri else null,
                        type = type,
                        processingStatus = if (shouldProcess) ProcessingStatus.PROCESSING else ProcessingStatus.COMPLETED
                    )
                }
                // Text/link shared
                sharedContent.text != null -> {
                    val type = detectContentType(sharedContent.text)
                    Note(
                        title = extractTitle(sharedContent.text, type),
                        content = sharedContent.text,
                        sourceUrl = if (type != NoteType.BRAIN_DUMP && sharedContent.text.contains("://"))
                            extractUrl(sharedContent.text) else null,
                        type = type,
                        processingStatus = ProcessingStatus.PROCESSING // Text/links always get analyzed
                    )
                }
                else -> return@launch
            }

            repository.insertNote(note)

            if (shouldAnalyze(note.type)) {
                simulateAiProcessing(note)
            } else {
                storeWithoutAnalysis(note)
            }
        }
    }

    /**
     * Add note with attachments from the input field
     * Handles both text content and file attachments
     * Groups multiple attachments into a SINGLE note with attachmentsJson
     *
     * @param content Text content of the note
     * @param attachments List of file attachments
     * @param excludeFromAiChat Whether to exclude this note from AI chat context
     */
    fun addNoteWithAttachments(
        content: String,
        attachments: List<Attachment>,
        excludeFromAiChat: Boolean = _pendingNoteAiExcluded.value
    ) {
        viewModelScope.launch {
            try {
                // Capture the AI exclusion state
                val aiExcluded = excludeFromAiChat

                when {
                    // Attachments present (with or without text) - create SINGLE grouped note
                    attachments.isNotEmpty() -> {
                        // 1. OPTIMISTIC UPDATE: Insert PENDING note immediately with original attachments
                        // This triggers the UI shimmer instantly while we do heavy compression in background
                        val primaryOriginal = attachments[0]
                        val type = detectTypeFromMime(primaryOriginal.mimeType)

                        val tempAttachments = attachments.map {
                            NoteAttachment(
                                uri = it.uri.toString(),
                                fileName = it.fileName,
                                mimeType = it.mimeType,
                                fileSize = it.fileSize
                            )
                        }

                        val title = when {
                            content.isNotBlank() -> extractTitle(content, type)
                            attachments.size > 1 -> "${attachments.size} ${getTypePluralName(type)}"
                            else -> primaryOriginal.fileName
                        }

                        val initialContent = if (content.isNotBlank()) {
                            content
                        } else {
                            buildMultipleAttachmentsDescription(tempAttachments)
                        }

                        // Create and INSERT PENDING note instantly
                        val initialNote = Note(
                            title = title,
                            content = initialContent,
                            fileUri = primaryOriginal.uri.toString(),
                            fileName = primaryOriginal.fileName,
                            fileMimeType = primaryOriginal.mimeType,
                            fileSize = primaryOriginal.fileSize,
                            imageUri = if (type == NoteType.IMAGE) primaryOriginal.uri.toString() else null,
                            type = type,
                            processingStatus = ProcessingStatus.PENDING, // Triggers shimmer
                            excludeFromAiChat = aiExcluded
                        ).withAttachments(tempAttachments)

                        // Insert immediately to update UI
                        repository.insertNote(initialNote)
                        Log.d(TAG, "Note inserted with PENDING status: ${initialNote.id}")

                        // 2. BACKGROUND WORK: Copy and compress all attachments
                        // Wrapped in try-catch to ensure note is completed even if attachment processing fails
                        var processedAttachments = tempAttachments
                        var primary = primaryOriginal

                        try {
                            // OPTIMIZATION: Parallel processing with async - 60-70% faster for 3+ files
                            val processedResults = kotlinx.coroutines.coroutineScope {
                                attachments.mapIndexed { index, attachment ->
                                    async(Dispatchers.IO) {
                                        val copied = copyAttachmentToStorage(attachment)
                                        index to NoteAttachment(
                                            uri = copied.uri.toString(),
                                            fileName = copied.fileName,
                                            mimeType = copied.mimeType,
                                            fileSize = copied.fileSize
                                        )
                                    }
                                }.awaitAll()
                            }.sortedBy { it.first }

                            processedAttachments = processedResults.map { it.second }
                            val primaryProcessed = processedResults.firstOrNull()?.second
                            if (primaryProcessed != null) {
                                primary = Attachment(
                                    uri = android.net.Uri.parse(primaryProcessed.uri),
                                    fileName = primaryProcessed.fileName,
                                    mimeType = primaryProcessed.mimeType,
                                    fileSize = primaryProcessed.fileSize
                                )
                            }
                        } catch (e: Exception) {
                            // Attachment processing failed - continue with original attachments
                            Log.e(TAG, "Attachment processing failed, using originals: ${e.message}", e)
                        }

                        // 3. UPDATE: Update note with optimized files and correct status
                        val shouldProcess = shouldAnalyze(type)

                        val finalContent = if (content.isNotBlank()) {
                            content
                        } else {
                            buildMultipleAttachmentsDescription(processedAttachments)
                        }

                        val updatedNote = initialNote.copy(
                            content = finalContent,
                            fileUri = primary.uri.toString(),
                            fileName = primary.fileName,
                            fileSize = primary.fileSize,
                            fileMimeType = primary.mimeType,
                            imageUri = if (type == NoteType.IMAGE) primary.uri.toString() else null,
                            processingStatus = if (shouldProcess) ProcessingStatus.PROCESSING else ProcessingStatus.COMPLETED
                        ).withAttachments(processedAttachments)

                        repository.updateNote(updatedNote)
                        Log.d(TAG, "Note updated to ${updatedNote.processingStatus}: ${updatedNote.id}")

                        // AI processing wrapped in try-catch to ensure note is completed
                        try {
                            if (shouldProcess) {
                                simulateAiProcessing(updatedNote)
                            } else {
                                storeWithoutAnalysis(updatedNote)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "AI processing failed, completing note without AI: ${e.message}", e)
                            // Mark note as completed without AI processing
                            storeWithoutAnalysis(updatedNote)
                        }
                    }

                    // Just text, no attachments
                    content.isNotBlank() -> {
                        addNote(content, excludeFromAiChat = aiExcluded)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Note creation failed: ${e.message}", e)
            } finally {
                // Always reset pending note state after submission
                resetPendingNoteState()
            }
        }
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
     * Build description for multiple attachments
     */
    private fun buildMultipleAttachmentsDescription(attachments: List<NoteAttachment>): String {
        val sb = StringBuilder()
        sb.append("${attachments.size} files attached:\n\n")
        attachments.forEachIndexed { index, attachment ->
            sb.append("${index + 1}. ${attachment.fileName}")
            if (attachment.fileSize > 0) {
                sb.append(" (${formatFileSize(attachment.fileSize)})")
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
                    Log.i(TAG, "Attachment compressed: ${attachment.fileName} saved ${formatSize(compressed.savedBytes)} " +
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
            sb.append("Size: ").append(formatFileSize(attachment.fileSize))
        }
        return sb.toString()
    }

    /**
     * Store file without AI analysis - just put in appropriate category
     */
    private suspend fun storeWithoutAnalysis(note: Note) {
        val categoryName = getStorageCategoryName(note.type)
        val category = repository.getOrCreateCategory(categoryName)

        val updatedNote = note.copy(
            categoryId = category.id,
            categoryName = category.name,
            summary = null, // No AI summary
            whySaved = null, // No AI insight
            processingStatus = ProcessingStatus.COMPLETED,
            updatedAt = System.currentTimeMillis()
        )
        repository.updateNote(updatedNote)
    }

    // Delegate to ContentTypeDetector for O(1) storage category lookup
    private fun getStorageCategoryName(type: NoteType): String =
        ContentTypeDetector.getStorageCategoryName(type)

    fun archiveNote(noteId: String) {
        viewModelScope.launch {
            try {
                noteOperationMutex.withLock {
                    repository.archiveNote(noteId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error archiving note: ${e.message}", e)
            }
        }
    }

    fun unarchiveNote(noteId: String) {
        viewModelScope.launch {
            try {
                noteOperationMutex.withLock {
                    repository.unarchiveNote(noteId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error unarchiving note: ${e.message}", e)
            }
        }
    }

    // Bulk operations with undo support (Phase 4)
    fun archiveNotes(noteIds: List<String>) {
        if (noteIds.isEmpty()) return
        viewModelScope.launch {
            try {
                noteOperationMutex.withLock {
                    repository.archiveNotes(noteIds)
                }
                // Store for undo
                _lastArchivedNoteIds.value = noteIds
            } catch (e: Exception) {
                Log.e(TAG, "Error bulk archiving notes: ${e.message}", e)
            }
        }
    }

    fun undoArchive() {
        val ids = _lastArchivedNoteIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                noteOperationMutex.withLock {
                    repository.unarchiveNotes(ids)
                }
                // Clear undo state
                _lastArchivedNoteIds.value = emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Error undoing archive: ${e.message}", e)
            }
        }
    }

    fun clearUndoState() {
        _lastArchivedNoteIds.value = emptyList()
    }

    // Archived notes for archive screen
    val archivedNotes = repository.getArchivedNotes()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteNote(note: Note) {
        viewModelScope.launch {
            try {
                noteOperationMutex.withLock {
                    // Clean up attachment files (only deletes app's copies, not original files)
                    val context = getApplication<Application>()
                    note.getAllAttachmentUris().forEach { uri ->
                        FileStorageHelper.deleteFile(context, uri)
                    }
                    // Then delete database record
                    repository.deleteNote(note)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting note: ${e.message}", e)
            }
        }
    }

    fun deleteNoteById(noteId: String) {
        viewModelScope.launch {
            try {
                // Search in both active notes and archived notes
                val note = notes.value.find { it.id == noteId }
                    ?: archivedNotes.value.find { it.id == noteId }
                note?.let { deleteNote(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting note by ID: ${e.message}", e)
            }
        }
    }

    /**
     * Update todos for a note - with callback for completion.
     * BUG FIX: Use fresh note from database, not stale StateFlow cache.
     */
    fun updateNoteTodos(noteId: String, todos: List<TodoItem>, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            try {
                noteOperationMutex.withLock {
                    // BUG FIX: Get fresh note from database, not stale StateFlow
                    val note = repository.getNoteById(noteId)
                    note?.let {
                        val updatedNote = it.withTodos(todos)
                        repository.updateNote(updatedNote)
                        Log.d(TAG, "Todos saved for note $noteId: ${todos.size} items")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating note todos: ${e.message}", e)
            } finally {
                // Always call completion callback on main thread
                onComplete?.invoke()
            }
        }
    }

    fun updateNoteCategory(noteId: String, categoryId: String, categoryName: String) {
        viewModelScope.launch {
            repository.updateNoteCategory(noteId, categoryId, categoryName)
        }
    }

    fun markNoteAsViewed(noteId: String) {
        viewModelScope.launch {
            repository.updateNoteViewedStatus(noteId, true)
        }
    }

    // =========================================================================
    // PIN OPERATIONS
    // =========================================================================

    fun pinNote(noteId: String) {
        viewModelScope.launch {
            repository.pinNote(noteId)
            Log.d(TAG, "Note pinned: $noteId")
        }
    }

    fun unpinNote(noteId: String) {
        viewModelScope.launch {
            repository.unpinNote(noteId)
            Log.d(TAG, "Note unpinned: $noteId")
        }
    }

    fun toggleNotePin(noteId: String) {
        viewModelScope.launch {
            repository.toggleNotePin(noteId)
            Log.d(TAG, "Note pin toggled: $noteId")
        }
    }

    // =========================================================================
    // REMINDER OPERATIONS
    // =========================================================================

    fun setNoteReminder(noteId: String, reminderText: String, durationMs: Long? = null) {
        viewModelScope.launch {
            val expiresAt = durationMs?.let { System.currentTimeMillis() + it }
            repository.setNoteReminder(noteId, reminderText, expiresAt)
            Log.d(TAG, "Reminder set for note: $noteId, expires: $expiresAt")
        }
    }

    fun clearNoteReminder(noteId: String) {
        viewModelScope.launch {
            repository.clearNoteReminder(noteId)
            Log.d(TAG, "Reminder cleared for note: $noteId")
        }
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
            val versions = repository.getNoteVersionsOnce(noteId)
            _selectedNoteVersions.value = versions
        }
    }

    /**
     * Get version history for a note
     */
    fun getNoteVersions(noteId: String) = repository.getNoteVersions(noteId)

    /**
     * Get version history as one-shot query
     */
    suspend fun getNoteVersionsOnce(noteId: String) = repository.getNoteVersionsOnce(noteId)

    /**
     * Restore a note to a previous version
     */
    fun restoreNoteVersion(noteId: String, versionId: String, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val success = repository.restoreNoteVersion(noteId, versionId)
            if (success) {
                // Refresh selected note if viewing this note
                if (_selectedNote.value?.id == noteId) {
                    _selectedNote.value = repository.getNoteById(noteId)
                }
                // Reload versions to show the new version created by restoration
                loadNoteVersions(noteId)
                Log.d(TAG, "Note restored to version: $versionId")
            } else {
                Log.e(TAG, "Failed to restore note to version: $versionId")
            }
            onComplete?.invoke(success)
        }
    }

    /**
     * Get version count for a note
     */
    suspend fun getNoteVersionCount(noteId: String) = repository.getNoteVersionCount(noteId)

    /**
     * Edit a note's title, content, and optionally attachments.
     * Called when user edits a note from the detail view.
     * Automatically saves a version snapshot before updating.
     */
    fun editNote(noteId: String, newTitle: String, newContent: String, newAttachments: List<NoteAttachment>? = null) {
        viewModelScope.launch {
            try {
                noteOperationMutex.withLock {
                    val note = repository.getNoteById(noteId)
                    note?.let {
                        var updatedNote = it.copy(
                            title = newTitle,
                            content = newContent,
                            updatedAt = System.currentTimeMillis()
                        )

                        // Update attachments if provided
                        if (newAttachments != null) {
                            // Update the full JSON list
                            updatedNote = updatedNote.withAttachments(newAttachments)

                            // Sync legacy primary file fields with the first attachment
                            val primary = newAttachments.firstOrNull()
                            if (primary != null) {
                                updatedNote = updatedNote.copy(
                                    fileUri = primary.uri,
                                    fileName = primary.fileName,
                                    fileMimeType = primary.mimeType,
                                    fileSize = primary.fileSize,
                                    // Only update imageUri if it matches the primary and is an image
                                    imageUri = if (primary.mimeType.startsWith("image/")) primary.uri else null
                                )
                            } else {
                                // No attachments left - clear legacy fields
                                updatedNote = updatedNote.copy(
                                    fileUri = null,
                                    fileName = null,
                                    fileMimeType = null,
                                    fileSize = null,
                                    imageUri = null
                                )
                            }
                        }

                        // Use updateNoteWithVersion to save version history
                        repository.updateNoteWithVersion(updatedNote, "User edit")
                        // Update selected note if this is the currently viewed note
                        if (_selectedNote.value?.id == noteId) {
                            _selectedNote.value = updatedNote
                        }
                        Log.d(TAG, "Note edited with version: $noteId")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error editing note: ${e.message}", e)
            }
        }
    }

    // Share interception for bottom sheet (delegated to ShareFlowManager)
    fun interceptShareForPreview(sharedContent: SharedContent) {
        shareFlowManager.interceptShareForPreview(sharedContent)
    }

    fun confirmShare(selectedCategory: String?, aiInstructions: String) {
        viewModelScope.launch {
            shareFlowManager.confirmShare(
                selectedCategory = selectedCategory,
                aiInstructions = aiInstructions,
                callback = object : ShareFlowManager.ShareConfirmCallback {
                    override suspend fun processNoteWithAi(note: Note) {
                        simulateAiProcessing(note)
                    }
                }
            )
        }
    }

    /**
     * Save note in full privacy mode - no AI processing at all
     */
    private suspend fun saveNoteWithoutAiProcessing(note: Note) {
        val category = repository.getOrCreateCategory("Private Notes")
        val savedNote = note.copy(
            isFullPrivacy = true,
            excludeFromAiChat = true,
            categoryId = category.id,
            categoryName = category.name,
            processingStatus = ProcessingStatus.COMPLETED,
            summary = null,  // No AI summary
            whySaved = null,  // No AI insight
            updatedAt = System.currentTimeMillis()
        )
        repository.updateNote(savedNote)
    }

    fun cancelShare() {
        shareFlowManager.cancelShare()
    }

    // Delegate file size formatting to ContentTypeDetector
    private fun formatSize(bytes: Long): String = ContentTypeDetector.formatSize(bytes)

    /**
     * Generate a title for a note based on content and type.
     * Uses content preview for short content, or truncates long content.
     */
    private fun generateTitle(content: String, type: NoteType): String {
        return when {
            content.isNotBlank() && content.length > 30 -> content.take(30) + "..."
            content.isNotBlank() -> content
            else -> ContentTypeDetector.getDefaultTitle(type)
        }
    }

    // Delegate URL extraction to ContentTypeDetector
    private fun extractUrl(text: String): String? = ContentTypeDetector.extractUrl(text)

    // Delegate MIME type detection to ContentTypeDetector
    private fun detectTypeFromMime(mimeType: String?): NoteType =
        ContentTypeDetector.detectTypeFromMime(mimeType)

    // Delegate default title lookup to ContentTypeDetector
    private fun getDefaultTitle(type: NoteType): String = ContentTypeDetector.getDefaultTitle(type)

    /**
     * Build file description from shared content metadata.
     * Includes filename, MIME type, and formatted file size.
     */
    private fun buildFileDescription(content: SharedContent): String {
        val sb = StringBuilder()
        content.fileName?.let { sb.append("File: ").append(it) }
        content.mimeType?.let {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("Type: ").append(it)
        }
        content.fileSize?.let {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("Size: ").append(formatFileSize(it))
        }
        return if (sb.isEmpty()) "Shared file" else sb.toString()
    }

    // Delegate file size formatting to ContentTypeDetector
    private fun formatFileSize(bytes: Long): String = ContentTypeDetector.formatFileSize(bytes)

    // Delegate content type detection to ContentTypeDetector
    private fun detectContentType(text: String): NoteType = ContentTypeDetector.detectContentType(text)

    // Delegate title extraction to ContentTypeDetector
    private fun extractTitle(content: String, type: NoteType): String =
        ContentTypeDetector.extractTitle(content, type)

    private suspend fun simulateAiProcessing(note: Note) {
        // ============================================================================
        // ABSOLUTE SECURITY BARRIER - PrivacyGuard
        // ============================================================================
        // Private notes can NEVER be processed by AI. There is NO function that
        // can grant AI access to private notes. This is an unbreakable rule.
        // ============================================================================
        if (!PrivacyGuard.canAiProcess(note)) {
            PrivacyGuard.logSecurityEvent(note.id, "AI processing")
            saveNoteWithoutAiProcessing(note)
            return
        }
        // ============================================================================

        _isProcessing.value = true

        try {
            // Check if this is a PDF that needs special processing
            if (note.fileMimeType == "application/pdf" && note.fileUri != null) {
                processPdfWithAi(note)
                return
            }

            // Build attachment metadata for AI (file names and types only, no content)
            val attachmentMetadata = note.getAttachments().map { attachment ->
                com.example.smarty.data.model.AttachmentMetadata.fromNoteAttachment(attachment)
            }.takeIf { it.isNotEmpty() }

            // Use real AI service with fallback for regular content
            val aiResponse = aiService.analyzeContent(note.content, attachmentMetadata)

            val categoryName = aiResponse.category
            val summary = aiResponse.summary
            val whySaved = aiResponse.whySaved
            val newTitle = aiResponse.title
            val tags = aiResponse.tags

            val category = repository.getOrCreateCategory(categoryName)

            // Convert tags list to JSON for storage
            val tagsJson = if (tags.isNotEmpty()) {
                com.google.gson.Gson().toJson(tags)
            } else null

            val updatedNote = note.copy(
                title = if (newTitle.isNotBlank()) newTitle else note.title,
                summary = summary,
                whySaved = whySaved,
                categoryId = category.id,
                categoryName = category.name,
                tagsJson = tagsJson,
                processingStatus = ProcessingStatus.COMPLETED,
                updatedAt = System.currentTimeMillis()
            )
            repository.updateNote(updatedNote)
        } catch (e: Exception) {
            Log.e(TAG, "AI processing error for note ${note.id}: ${e.message}", e)
            // Fallback: complete note without AI processing
            storeWithoutAnalysis(note)
        } finally {
            _isProcessing.value = false
        }
    }

    /**
     * Process PDF documents with AI analysis
     * Extracts text from PDF and sends to AI for comprehensive summarization
     *
     * SECURITY: Private PDFs are NEVER processed - uses PrivacyGuard
     */
    private suspend fun processPdfWithAi(note: Note) {
        // ============================================================================
        // ABSOLUTE SECURITY BARRIER - Private PDFs are NEVER processed by AI
        // ============================================================================
        if (!PrivacyGuard.canAiProcess(note)) {
            PrivacyGuard.logSecurityEvent(note.id, "PDF AI processing")
            saveNoteWithoutAiProcessing(note)
            _isProcessing.value = false
            return
        }
        // ============================================================================

        Log.i(TAG, "Processing PDF document: ${note.fileName}")

        try {
            val uri = Uri.parse(note.fileUri)
            val extractionResult = pdfExtractor.extractText(uri)

            when (extractionResult) {
                is PDFExtractionResult.Success -> {
                    Log.i(TAG, "PDF text extracted: ${extractionResult.characterCount} chars from ${extractionResult.pageCount} pages")

                    // Use document analysis for comprehensive summarization
                    val documentResponse = aiService.analyzeDocument(
                        documentText = extractionResult.text,
                        fileName = note.fileName,
                        userContext = null // Could pass user instructions if available
                    )

                    val category = repository.getOrCreateCategory(documentResponse.category)

                    // Build comprehensive summary with key points
                    val fullSummary = buildString {
                        append(documentResponse.summary)
                        if (documentResponse.keyPoints.isNotEmpty()) {
                            append("\n\nKey Points:")
                            documentResponse.keyPoints.forEach { point ->
                                append("\n• $point")
                            }
                        }
                        if (documentResponse.actionItems.isNotEmpty()) {
                            append("\n\nAction Items:")
                            documentResponse.actionItems.forEach { item ->
                                append("\n☐ $item")
                            }
                        }
                    }

                    val updatedNote = note.copy(
                        title = documentResponse.title,
                        summary = fullSummary,
                        whySaved = documentResponse.userRelevance,
                        categoryId = category.id,
                        categoryName = category.name,
                        processingStatus = ProcessingStatus.COMPLETED,
                        updatedAt = System.currentTimeMillis()
                    )
                    repository.updateNote(updatedNote)
                    Log.i(TAG, "PDF processed successfully: ${documentResponse.title}")
                }

                is PDFExtractionResult.Empty -> {
                    Log.w(TAG, "PDF has no extractable text: ${extractionResult.message}")

                    // Still categorize the PDF even without text content
                    val category = repository.getOrCreateCategory("Documents")
                    val updatedNote = note.copy(
                        summary = "Image-based PDF (${extractionResult.pageCount} pages). Text extraction not available for scanned documents.",
                        whySaved = "Document saved for reference",
                        categoryId = category.id,
                        categoryName = category.name,
                        processingStatus = ProcessingStatus.COMPLETED,
                        updatedAt = System.currentTimeMillis()
                    )
                    repository.updateNote(updatedNote)
                }

                is PDFExtractionResult.Error -> {
                    Log.e(TAG, "PDF extraction failed: ${extractionResult.message}")

                    // Mark as failed but still save
                    val category = repository.getOrCreateCategory("Documents")
                    val updatedNote = note.copy(
                        summary = "PDF could not be analyzed: ${extractionResult.message}",
                        whySaved = "Document saved",
                        categoryId = category.id,
                        categoryName = category.name,
                        processingStatus = ProcessingStatus.FAILED,
                        updatedAt = System.currentTimeMillis()
                    )
                    repository.updateNote(updatedNote)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing PDF: ${e.message}", e)

            val category = repository.getOrCreateCategory("Documents")
            val updatedNote = note.copy(
                summary = "Error processing PDF",
                whySaved = "Document saved",
                categoryId = category.id,
                categoryName = category.name,
                processingStatus = ProcessingStatus.FAILED,
                updatedAt = System.currentTimeMillis()
            )
            repository.updateNote(updatedNote)
        } finally {
            _isProcessing.value = false
        }
    }

    /**
     * Generate a mock AI response for offline fallback.
     * Delegates to ContentTypeDetector for efficient pattern matching.
     *
     * @param note The note to generate a response for
     * @return Triple of (category tag, summary, intent)
     */
    private fun generateMockAiResponse(note: Note): Triple<String, String, String> =
        ContentTypeDetector.generateMockAiResponse(note.type, note.content)

    // API Key Management
    fun addApiKey(provider: AIProvider, apiKey: String) {
        securePreferences.addProviderKey(provider, apiKey)
        // Sync GROQ keys with manager for usage tracking
        if (provider == AIProvider.GROQ) {
            viewModelScope.launch { agentProvider.syncGroqKeys() }
        }
    }

    fun removeApiKey(provider: AIProvider, apiKey: String) {
        securePreferences.removeProviderKey(provider, apiKey)
        // Sync GROQ keys with manager for usage tracking
        if (provider == AIProvider.GROQ) {
            viewModelScope.launch { agentProvider.syncGroqKeys() }
        }
    }

    fun updateApiKey(provider: AIProvider, oldKey: String, newKey: String) {
        securePreferences.updateProviderKey(provider, oldKey, newKey)
        // Sync GROQ keys with manager for usage tracking
        if (provider == AIProvider.GROQ) {
            viewModelScope.launch { agentProvider.syncGroqKeys() }
        }
    }

    fun setProviderEnabled(provider: AIProvider, enabled: Boolean) {
        securePreferences.setProviderEnabled(provider, enabled)
    }

    fun setSelectedModel(provider: AIProvider, model: String) {
        securePreferences.setSelectedModel(provider, model)
    }

    fun testApiKey(provider: AIProvider, apiKey: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val isValid = aiService.testApiKey(provider, apiKey)
            onResult(isValid)
        }
    }

    // Theme Management
    val isDarkTheme: StateFlow<Boolean> = securePreferences.isDarkTheme

    fun setDarkTheme(isDark: Boolean) {
        securePreferences.setDarkTheme(isDark)
    }

    // Rate Limit Stats (exposed for UI monitoring)
    fun getRateLimitStats() = rateLimiter.getUsageStats()

    // Tavily Web Search API Management
    private val _tavilyApiKey = MutableStateFlow(securePreferences.getTavilyApiKey())
    val tavilyApiKey: StateFlow<String?> = _tavilyApiKey.asStateFlow()

    fun setTavilyApiKey(key: String?) {
        securePreferences.setTavilyApiKey(key)
        _tavilyApiKey.value = key
    }

    // Shake Sensitivity Management
    private val _shakeSensitivity = MutableStateFlow(securePreferences.getShakeSensitivity())
    val shakeSensitivity: StateFlow<Float> = _shakeSensitivity.asStateFlow()

    fun setShakeSensitivity(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        securePreferences.setShakeSensitivity(clamped)
        _shakeSensitivity.value = clamped
    }

    // Cache Management
    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            _cacheSizeBytes.value = cacheManager.getCacheSize()
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            _isClearingCache.value = true
            try {
                cacheManager.clearCache()
                _cacheSizeBytes.value = 0L
                Log.d(TAG, "Cache cleared successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear cache: ${e.message}")
            } finally {
                _isClearingCache.value = false
            }
        }
    }

    // User Category Creation
    fun createUserCategory(name: String) {
        viewModelScope.launch {
            val category = Category(
                name = name,
                isAiGenerated = false,  // User-created category
                noteCount = 0
            )
            repository.insertCategory(category)
        }
    }

    // Delete Category (BUG-028: Proper cascade cleanup)
    fun deleteCategory(category: Category) {
        viewModelScope.launch {
            try {
                // Use atomic cleanup method that handles all notes via SQL UPDATE
                // This is more reliable than filtering from StateFlow which may be stale
                repository.deleteCategoryWithCleanup(category)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete category: ${category.name}", e)
            }
        }
    }

    // PIN Management
    fun setPin(pin: String) {
        securePreferences.setPin(pin)
    }

    fun verifyPin(pin: String): Boolean {
        return securePreferences.verifyPin(pin)
    }

    fun changePin(oldPin: String, newPin: String): Boolean {
        return securePreferences.changePin(oldPin, newPin)
    }

    fun clearPin() {
        securePreferences.clearPin()
    }

    fun isPinConfigured(): Boolean {
        return securePreferences.isPinConfigured()
    }

    fun isFirstLaunch(): Boolean {
        return securePreferences.isFirstLaunch()
    }

    fun setFirstLaunchComplete() {
        securePreferences.setFirstLaunchComplete()
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
            getThreshold = { securePreferences.getShakeThreshold() }
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
     * - MainActivity should launch Google Speech Recognizer
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

                if (isMusicPlaying && !isAudioFocusLost) {
                    // Music just started - pause Vosk
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

        Log.d(TAG, "All conditions met - resuming Vosk")
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
     * Restart wake word detection after Google STT completes.
     * Call this from onActivityResult after speech recognition finishes.
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
    fun clearSpeakerVerificationCache() {
        voskWakeWordManager?.clearSpeakerCache()
    }

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

    // ==================== TTS Control Methods ====================

    /**
     * Enable or disable TTS for AI responses.
     */
    fun setTTSEnabled(enabled: Boolean) {
        responseTTSManager.setEnabled(enabled)
    }

    /**
     * Stop current TTS speech immediately.
     */
    fun stopTTS() {
        responseTTSManager.stop()
    }

    /**
     * Speak text using TTS (for AI responses).
     */
    private fun speakResponse(text: String) {
        responseTTSManager.speak(text)
    }

    // Neural TTS Voice Management (bundled model - no download needed)
    val currentTtsVoice: StateFlow<String> by lazy { responseTTSManager.currentVoice }
    val isTtsReady: StateFlow<Boolean> by lazy { responseTTSManager.isInitialized }

    /**
     * Initialize neural TTS - uses bundled model (no download needed).
     * Called during ViewModel initialization.
     */
    fun initializeNeuralTTS() {
        // Neural TTS is auto-initialized in ResponseTTSManager
        // with bundled voice model (en_US-lessac-medium)
        Log.d(TAG, "Neural TTS initialization triggered (auto-initialized in ResponseTTSManager)")
    }

    // ==================== End TTS Control Methods ====================

    /**
     * Send a message in chat mode using the Koog-based AI agent.
     *
     * ARCHITECTURE: Uses JetBrains Koog framework for agent orchestration:
     * - Koog handles the agent loop, tool execution, and multi-step reasoning
     * - PrivacyGuard is enforced at the tool level via CogniToolBase
     */
    fun sendChatMessage(content: String, attachments: List<Attachment> = emptyList()) {
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
                // Run agent

                // Build conversation history for agent memory
                val conversationHistory = chatManager.chatMessages.value
                    .filter { it.role != ChatRole.SYSTEM } // Exclude system messages
                    .map { msg ->
                        val role = when (msg.role) {
                            ChatRole.USER -> "User"
                            ChatRole.ASSISTANT -> "Assistant"
                            else -> "System"
                        }
                        Pair(role, msg.content)
                    }

                // Run the Koog agent with conversation history
                val result = cogniAgent.run(content, conversationHistory)

                when (result) {
                    is AgentResult.Success -> {
                        Log.d(TAG, "Agent completed successfully via ${result.provider}")

                        // Detect if this was an audio-related query
                        val isAudioQuery = content.lowercase().let {
                            it.contains("play") || it.contains("music") || it.contains("audio") ||
                            it.contains("song") || it.contains("podcast") || it.contains("listen")
                        }

                        // Extract suggestions from TOON format: {suggestions:["a","b"]}
                        val (cleanedResponse, suggestions) = extractSuggestionsFromResponse(result.response)

                        // Create assistant message from agent response
                        val assistantMessage = ChatMessage(
                            role = ChatRole.ASSISTANT,
                            content = cleanedResponse,
                            isAudioRelated = isAudioQuery,
                            suggestions = suggestions,
                            isError = false
                        )

                        chatManager.addAssistantMessage(assistantMessage)

                        // Speak AI response - but NOT if audio playback was just triggered
                        // (user wants to hear the music, not TTS talking over it)
                        if (_pendingAudioPlayback.value == null) {
                            speakResponse(cleanedResponse)
                        } else {
                            Log.d(TAG, "Skipping TTS - audio playback pending")
                        }

                        chatManager.markApiCallSuccessful()
                        chatManager.saveMessagePair(
                            userMessage = userMessage,
                            assistantMessage = assistantMessage,
                            hasApiKeys = securePreferences.hasAnyApiKeys()
                        )
                    }

                    is AgentResult.Error -> {
                        Log.e(TAG, "Agent error: ${result.message}")

                        val errorMessage = ChatMessage(
                            role = ChatRole.ASSISTANT,
                            content = result.message,
                            isError = true,  // Mark as error - no suggestions will show
                            suggestions = emptyList()
                        )
                        chatManager.addAssistantMessage(errorMessage)
                    }

                    is AgentResult.NoProvider -> {
                        Log.w(TAG, "No provider configured: ${result.message}")

                        val noKeyMessage = ChatMessage(
                            role = ChatRole.ASSISTANT,
                            content = "Please configure an AI provider API key in Settings to use chat."
                        )
                        chatManager.addAssistantMessage(noKeyMessage)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing chat message: ${e.message}", e)



                val errorMessage = ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "I encountered an error processing your request. Please try again."
                )
                chatManager.addAssistantMessage(errorMessage)
            } finally {
                chatManager.setProcessing(false)
            }
        }
    }

    // ==================== Calendar Operations ====================

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
    ) {
        viewModelScope.launch {
            val event = CalendarEvent(
                title = title,
                description = description,
                startTime = startTime,
                endTime = endTime,
                isAllDay = isAllDay,
                location = location,
                color = color,
                reminderMinutes = reminderMinutes,
                isEventPrivate = isPrivate
            )
            calendarDao.insertEvent(event)
            Log.d(TAG, "Added calendar event: $title")
        }
    }

    /**
     * Update an existing calendar event
     */
    fun updateCalendarEvent(event: CalendarEvent) {
        viewModelScope.launch {
            try {
                calendarDao.updateEvent(event)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating calendar event", e)
            }
        }
    }

    fun deleteCalendarEvent(eventId: String) {
        viewModelScope.launch {
            try {
                calendarDao.deleteEventById(eventId)
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting calendar event", e)
            }
        }
    }

    // ==================== Dynamic Model Management ====================

    fun getAvailableModels(provider: AIProvider): List<Pair<String, String>> {
        return securePreferences.getAvailableModels(provider)
    }

    fun refreshGroqModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val apiKey = securePreferences.getProviderKeys(AIProvider.GROQ).firstOrNull()
            if (apiKey.isNullOrBlank()) return@launch

            try {
                val request = okhttp3.Request.Builder()
                    .url("https://api.groq.com/openai/v1/models")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()

                OkHttpClient().newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = org.json.JSONObject(body)
                        val data = json.getJSONArray("data")
                        val models = mutableListOf<Pair<String, String>>()

                        for (i in 0 until data.length()) {
                            val item = data.getJSONObject(i)
                            val id = item.getString("id")
                            // Basic formatting for display name
                            val name = when {
                                id.contains("llama-4-scout") -> "Llama 4 Scout 17B (Dynamic)"
                                id.contains("llama-4") -> "Llama 4 (Dynamic)"
                                id.contains("llama-3.3") -> "Llama 3.3 (Dynamic)"
                                id.contains("llama-3.1") -> "Llama 3.1 (Dynamic)"
                                id.contains("mixtral") -> "Mixtral (Dynamic)"
                                id.contains("gemma") -> "Gemma (Dynamic)"
                                else -> id
                            }
                            models.add(id to name)
                        }
                        
                        // Sort by name for better UX
                        models.sortBy { it.second }

                        if (models.isNotEmpty()) {
                            securePreferences.setDynamicModels(AIProvider.GROQ, models)
                            // Force refresh of provider configs flow
                            securePreferences.setProviderEnabled(AIProvider.GROQ, securePreferences.isProviderEnabled(AIProvider.GROQ))
                        }
                    } else {
                        Log.e(TAG, "Groq models fetch failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching Groq models", e)
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
        cacheManager.clearTemporaryData()

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

        // Clean up TTS resources
        responseTTSManager.shutdown()

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
 * Factory for CogniViewModel that provides SavedStateHandle for state preservation
 * across process death (BUG-053 fix).
 *
 * Usage in Activity:
 * ```
 * private val viewModel: CogniViewModel by viewModels {
 *     CogniViewModelFactory(application, this)
 * }
 * ```
 */
class CogniViewModelFactory(
    private val application: Application,
    owner: SavedStateRegistryOwner
) : AbstractSavedStateViewModelFactory(owner, null) {

    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(
        key: String,
        modelClass: Class<T>,
        handle: SavedStateHandle
    ): T {
        if (modelClass.isAssignableFrom(CogniViewModel::class.java)) {
            return CogniViewModel(application, handle) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
