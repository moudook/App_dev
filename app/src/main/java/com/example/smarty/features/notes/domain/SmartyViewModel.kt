@file:Suppress("DEPRECATION")
package com.example.smarty.features.notes.domain

import android.app.Application
import android.util.Log
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.savedstate.SavedStateRegistryOwner
import com.example.smarty.data.local.SearchHistoryManager
import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.AudioTrack
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.core.domain.model.Category
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.ChatSession
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.NoteType
import com.example.smarty.core.domain.model.SmartyTimer
import com.example.smarty.data.remote.AIService
import com.example.smarty.data.repository.ChatRepository
import com.example.smarty.data.repository.DeviceAudioRepository
import com.example.smarty.data.repository.SmartyRepository
import com.example.smarty.service.AlarmScheduler
import com.example.smarty.ui.components.ConnectionStatus
import com.example.smarty.ui.components.PendingShareData
import com.example.smarty.core.common.util.CompletionSoundManager
import com.example.smarty.core.common.util.NetworkMonitor
import com.example.smarty.core.common.util.ShakeDetector
import com.example.smarty.core.domain.model.MentionState
import com.example.smarty.ui.components.AttachmentOption
import android.content.Context
import android.net.Uri
import com.example.smarty.core.domain.model.PlaybackState
import com.example.smarty.data.cache.AIResponseCache
import com.example.smarty.core.domain.model.TodoItem
import com.example.smarty.core.domain.model.MentionSuggestion
import com.example.smarty.service.AudioPlayerService
import com.example.smarty.core.common.util.FileStorageHelper
import com.example.smarty.R
import com.example.smarty.features.notes.domain.ShareFlowManager
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import com.example.smarty.core.domain.model.NoteAttachment
import com.example.smarty.core.common.util.ContentTypeDetector
import com.example.smarty.features.calendar.domain.CalendarFeatureManager
import com.example.smarty.features.chat.domain.ChatFeatureManager
import com.example.smarty.features.notes.domain.NoteProcessingQueueManager
import com.example.smarty.features.settings.domain.SettingsFeatureManager
import com.example.smarty.features.system.domain.SystemFeatureManager
import com.example.smarty.features.audio.domain.AudioFeatureManager
import com.example.smarty.features.search.domain.SearchFeatureManager
import com.example.smarty.features.chat.domain.StyleFeatureManager
import com.example.smarty.features.chat.domain.WorkflowManager

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

import com.example.smarty.core.domain.model.SharedContent
import com.example.smarty.core.domain.model.SharedFileInfo

class SmartyViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    // SavedStateHandle keys for state preservation across process death (BUG-053)
    companion object {
        private const val TAG = "SmartyViewModel"
        private const val KEY_SELECTED_NOTE_ID = "selectedNoteId"
        private const val KEY_SELECTED_CATEGORY_ID = "selectedCategoryId"
        private const val KEY_IS_CHAT_MODE = "isChatMode"
        private const val KEY_CURRENT_SCREEN = "currentScreen"
        private const val KEY_CURRENT_SESSION_ID = "currentSessionId"
        private const val KEY_SEARCH_QUERY = "searchQuery"
        private const val KEY_SELECTED_FILTERS = "selectedFilters"

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
    // ServiceLocator ensures singletons are used where appropriate
    private val aiService: AIService by lazy {
        com.example.smarty.di.ServiceLocator.provideAIService(application)
    }

    // Repository needs to be initialized before agent - lazy to avoid blocking
    private val repository: SmartyRepository by lazy {
        com.example.smarty.di.ServiceLocator.provideRepository(application)
    }

    private val chatRepository: ChatRepository by lazy {
        com.example.smarty.di.ServiceLocator.provideChatRepository(application)
    }

    // Alarm scheduler for timer/alarm tools - lazy to avoid blocking
    private val alarmScheduler: AlarmScheduler by lazy {
        com.example.smarty.di.ServiceLocator.provideAlarmScheduler(application)
    }

    // Device audio repository for MediaStore access - lazy to avoid blocking
    private val deviceAudioRepository: DeviceAudioRepository by lazy {
        com.example.smarty.di.ServiceLocator.provideDeviceAudioRepository(application)
    }

    // Completion sound manager for AI agent and notecard processing
    private val completionSoundManager: CompletionSoundManager by lazy {
        com.example.smarty.di.ServiceLocator.provideCompletionSoundManager(application)
    }

    // Cache manager for memory management
    private val cacheManager: com.example.smarty.data.cache.CacheManager by lazy {
        com.example.smarty.di.ServiceLocator.provideCacheManager(application)
    }

    // Calendar Feature Manager - handles calendar logic and sync
    private val calendarFeatureManager by lazy {
        com.example.smarty.di.ServiceLocator.provideCalendarFeatureManager(getApplication())
    }

    // Settings Feature Manager - Centralized preferences and keys
    private val settingsFeatureManager: SettingsFeatureManager by lazy {
        com.example.smarty.di.ServiceLocator.provideSettingsFeatureManager(application)
    }

    // System Feature Manager - Hybridized action layer for UI, Local Commands, and AI
    private val systemFeatureManager: SystemFeatureManager by lazy {
        com.example.smarty.di.ServiceLocator.provideSystemFeatureManager(application)
    }

    // Search Feature Manager - Centralized retrieval for UI and AI
    private val searchFeatureManager: com.example.smarty.features.search.domain.SearchFeatureManager by lazy {
        com.example.smarty.di.ServiceLocator.provideSearchFeatureManager(application)
    }

    // Style Feature Manager - Analyzes user writing patterns
    private val styleFeatureManager: com.example.smarty.features.chat.domain.StyleFeatureManager by lazy {
        com.example.smarty.di.ServiceLocator.provideStyleFeatureManager()
    }

    // Workflow Manager - Handles multi-step agentic tasks
    private val workflowManager: com.example.smarty.features.chat.domain.WorkflowManager by lazy {
        com.example.smarty.di.ServiceLocator.provideWorkflowManager(application)
    }

    // AI Response Cache
    private val aiResponseCache: AIResponseCache by lazy {
        com.example.smarty.di.ServiceLocator.provideAIResponseCache(application)
    }

    // AI Provider Strategy
    val providerStrategy: StateFlow<String> = settingsFeatureManager.providerStrategy

    fun setProviderStrategy(strategy: String) {
        settingsFeatureManager.setProviderStrategy(strategy)
        Log.d(TAG, "Provider strategy set to: $strategy")
    }

    // AI Personality
    val personality: StateFlow<String> = settingsFeatureManager.personality

    fun setPersonality(personality: String) {
        settingsFeatureManager.setPersonality(personality)
        Log.d(TAG, "Personality set to: $personality")
    }

    /**
     * UNFILTERED notes source for AI agent.
     * Delegated to NoteOperationsManager for centralized data flow.
     */
    private val _allNotesForAgent: StateFlow<List<Note>> by lazy {
        noteOperationsManager.getAllNotes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    // Shake detector for toggling chat mode
    private var shakeDetector: ShakeDetector? = null



    // Voice input trigger (for widget voice input button - NOT Vosk wake word)
    // This is triggered by the widget to launch Google Speech Recognizer
    private val _wakeWordTriggered = MutableStateFlow(false)
    val wakeWordTriggered: StateFlow<Boolean> = _wakeWordTriggered.asStateFlow()

    // Camera trigger state (for widget camera button)
    private val _cameraTriggered = MutableStateFlow(false)
    val cameraTriggered: StateFlow<Boolean> = _cameraTriggered.asStateFlow()

    // Mutex for thread-safe note operations (BUG-016 fix)
    private val noteOperationMutex = Mutex()










    // ==================== Delegated Managers ====================

    // Shared App State - Global state holder for cross-component synchronization
    private val sharedAppState by lazy {
        com.example.smarty.di.ServiceLocator.provideSharedAppState()
    }

    // Current screen route - delegated to SharedAppState
    val currentScreen: StateFlow<String> = sharedAppState.currentScreen

    // 
    // SCREEN CONTEXT - Track active item being viewed (e.g., a specific note)
    // 

    val activeNoteId: StateFlow<String?> = sharedAppState.activeNoteId
    val selectedTab: StateFlow<com.example.smarty.core.domain.model.NavigationTab> = sharedAppState.selectedTab

    fun setActiveNote(noteId: String?) {
        sharedAppState.setActiveNoteId(noteId)
    }

    fun setSelectedTab(tab: com.example.smarty.core.domain.model.NavigationTab) {
        sharedAppState.setSelectedTab(tab)
    }

    // Network monitoring (Phase 7)
    private val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(application) }
    val connectionStatus: StateFlow<ConnectionStatus> = networkMonitor.connectionStatus
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionStatus.CONNECTED)

    // Chat Feature Manager - handles chat logic, sessions, and AI interaction
    private val chatFeatureManager: ChatFeatureManager by lazy {
        com.example.smarty.di.ServiceLocator.provideChatFeatureManager(getApplication(), viewModelScope)
    }

    // Share Flow Manager - handles share interception and processing
    private val shareFlowManager = com.example.smarty.features.notes.domain.ShareFlowManager(
        repository = repository,
        context = application,
        scope = viewModelScope,
        getNotesSnapshot = { notes.value }
    )



    // Note Operations Manager - handles note CRUD operations
    private val noteOperationsManager: com.example.smarty.features.notes.domain.NoteOperationsManager by lazy {
        com.example.smarty.di.ServiceLocator.provideNoteOperationsManager(application)
    }

    // Note Processing Queue Manager - handles background processing with timeout and recovery
    private val noteProcessingQueueManager by lazy {
        com.example.smarty.di.ServiceLocator.provideNoteProcessingQueueManager(application)
    }

    // Queue state exposed for UI (optional - shows pending count)
    val pendingNoteProcessingCount: StateFlow<Int> by lazy { noteProcessingQueueManager.pendingCount }

    // Audio Playback Manager - handles audio playback coordination with AudioPlayerService
    private val audioPlaybackManager by lazy {
        com.example.smarty.di.ServiceLocator.provideAudioPlaybackManager(application)
    }

    // Audio Feature Manager - hybridized audio control for UI and AI agent
    private val audioFeatureManager by lazy {
        com.example.smarty.di.ServiceLocator.provideAudioFeatureManager(application)
    }


    // ==================== Chat State (delegated to ChatFeatureManager) ====================
    val isChatMode: StateFlow<Boolean> get() = chatFeatureManager.isChatMode
    val chatMessages: StateFlow<List<ChatMessage>> get() = chatFeatureManager.chatMessages
    val isChatProcessing: StateFlow<Boolean> get() = chatFeatureManager.isChatProcessing
    val agentActivity: StateFlow<ChatFeatureManager.AgentActivity?> get() = chatFeatureManager.agentActivity
    val currentSessionId: StateFlow<String?> get() = chatFeatureManager.currentSessionId
    val chatSessions: StateFlow<List<ChatSession>> get() = chatFeatureManager.chatSessions
    val mentionState: StateFlow<MentionState> get() = chatFeatureManager.mentionState
    val pendingChatText: StateFlow<String?> get() = chatFeatureManager.pendingChatText
    val navigationRequest: StateFlow<String?> get() = sharedAppState.navigationRequest
    val proactiveSuggestion: StateFlow<String?> get() = chatFeatureManager.proactiveSuggestion

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

    // Shake-triggered mode switch (for glow animation feedback)
    private val _wasShakeTriggered = MutableStateFlow(false)
    val wasShakeTriggered: StateFlow<Boolean> = _wasShakeTriggered.asStateFlow()

    // Current screen route - shake only works on main screen (input_stream)
    // Default to "startup" so shake is disabled until navigation explicitly sets the screen
    // Shared flow for speech results to be consumed by screens
    // BUG FIX (RX-04): Added extraBufferCapacity to prevent dropped events
    // when collector is suspended (e.g., during screen transition)
    private val _speechResults = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        extraBufferCapacity = 8  // Buffer up to 8 speech results to prevent drops
    )
    val speechResults = _speechResults.asSharedFlow()

    // Pull-to-refresh state (local DB refresh)
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

    // Cloud sync state and operations
    sealed class CloudSyncState {
        object Idle : CloudSyncState()
        object Syncing : CloudSyncState()
        data class Success(val notesUpdated: Int, val sessionsUpdated: Int, val eventsUpdated: Int) : CloudSyncState()
        data class Error(val message: String) : CloudSyncState()
    }

    private val _cloudSyncState = MutableStateFlow<CloudSyncState>(CloudSyncState.Idle)
    val cloudSyncState: StateFlow<CloudSyncState> = _cloudSyncState.asStateFlow()

    // Sync snackbar messages
    private val _syncSnackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val syncSnackbarMessage = _syncSnackbarMessage.asSharedFlow()

    fun syncCloudNow() {
        viewModelScope.launch {
            _cloudSyncState.value = CloudSyncState.Syncing
            try {
                val app = getApplication<Application>()
                val syncCoordinator = com.example.smarty.di.ServiceLocator.provideSyncCoordinator(app)

                // Push local changes first, then pull from server
                val pushResult = syncCoordinator.pushPendingChanges()
                val pullResult = syncCoordinator.pullFromServer()

                when {
                    pullResult is com.example.smarty.data.sync.PullResult.Success -> {
                        val total = pullResult.notes + pullResult.sessions + pullResult.events
                        _cloudSyncState.value = CloudSyncState.Success(
                            notesUpdated = pullResult.notes,
                            sessionsUpdated = pullResult.sessions,
                            eventsUpdated = pullResult.events
                        )
                        _syncSnackbarMessage.emit("Synced: ${pullResult.notes} notes, ${pullResult.sessions} chats, ${pullResult.events} events")
                        kotlinx.coroutines.delay(3000)
                        _cloudSyncState.value = CloudSyncState.Idle
                    }
                    pullResult is com.example.smarty.data.sync.PullResult.Offline -> {
                        _cloudSyncState.value = CloudSyncState.Error("No internet connection")
                        _syncSnackbarMessage.emit("No internet connection")
                        kotlinx.coroutines.delay(3000)
                        _cloudSyncState.value = CloudSyncState.Idle
                    }
                    else -> {
                        val errorMsg = (pullResult as? com.example.smarty.data.sync.PullResult.Error)?.message ?: "Sync failed"
                        _cloudSyncState.value = CloudSyncState.Error(errorMsg)
                        _syncSnackbarMessage.emit("Sync failed: $errorMsg")
                        kotlinx.coroutines.delay(3000)
                        _cloudSyncState.value = CloudSyncState.Idle
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cloud sync failed", e)
                _cloudSyncState.value = CloudSyncState.Error(e.message ?: "Unknown error")
                _syncSnackbarMessage.emit("Sync error: ${e.message}")
                kotlinx.coroutines.delay(3000)
                _cloudSyncState.value = CloudSyncState.Idle
            }
        }
    }

    fun resetCloudSyncState() {
        _cloudSyncState.value = CloudSyncState.Idle
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



    // --- Remote Server Configuration ---
    val serverUrl: StateFlow<String> = settingsFeatureManager.serverUrl
    fun setServerUrl(url: String) = settingsFeatureManager.setServerUrl(url)

    fun testServerConnection(url: String, callback: (com.example.smarty.features.settings.domain.SettingsFeatureManager.LocalServerTestResult) -> Unit) {
        viewModelScope.launch {
            val result = settingsFeatureManager.testServerConnection(url)
            withContext(Dispatchers.Main) {
                callback(result)
            }
        }
    }

    // --- Server Settings (Remote Only) ---
    // Declared above at line 431


    // --- Calendar & Timer State ---
    val isCalendarSyncEnabled: StateFlow<Boolean> = calendarFeatureManager.isCalendarSyncEnabled
    val deviceCalendars: StateFlow<List<com.example.smarty.features.calendar.domain.GoogleCalendarSyncManager.DeviceCalendar>> = calendarFeatureManager.deviceCalendars
    val targetCalendarId: StateFlow<Long> = calendarFeatureManager.targetCalendarId

    fun setCalendarSyncEnabled(enabled: Boolean) = calendarFeatureManager.setCalendarSyncEnabled(enabled)
    fun setTargetCalendarId(id: Long) = calendarFeatureManager.setTargetCalendarId(id)
    fun loadDeviceCalendars() = calendarFeatureManager.loadDeviceCalendars()
    fun cancelTimer(timer: SmartyTimer) = calendarFeatureManager.cancelTimer(timer.id)

    // Cache management
    val cacheSizeBytes: StateFlow<Long> = settingsFeatureManager.cacheSizeBytes
    val isClearingCache: StateFlow<Boolean> = settingsFeatureManager.isClearingCache

    fun refreshCacheSize() {
        settingsFeatureManager.updateCacheSize()
    }

    fun clearCache() {
        settingsFeatureManager.clearCache()
    }

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
    val audioPlayerState: StateFlow<com.example.smarty.core.domain.model.AudioPlayerState>
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
        savedStateHandle[KEY_SEARCH_QUERY] = query
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
        val next = if (option in current) current - option else current + option
        _selectedFilters.value = next
        // Store as List of strings for SavedStateHandle compatibility
        savedStateHandle[KEY_SELECTED_FILTERS] = next.map { it.name }
    }

    fun clearFilters() {
        _selectedFilters.value = emptySet()
        savedStateHandle[KEY_SELECTED_FILTERS] = emptyList<String>()
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
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories: StateFlow<List<Category>> = noteOperationsManager.getAllCategories()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Calendar events (delegated to CalendarFeatureManager)
    val calendarEvents: StateFlow<List<CalendarEvent>>
        get() = calendarFeatureManager.calendarEvents

    val activeTimers: StateFlow<List<SmartyTimer>>
        get() = calendarFeatureManager.activeTimers

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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    // Loading state for notes list (Phase 8)
    private val _isNotesLoading = MutableStateFlow(true)
    val isNotesLoading: StateFlow<Boolean> = _isNotesLoading.asStateFlow()

    private val _isStacksLoading = MutableStateFlow(true)
    val isStacksLoading: StateFlow<Boolean> = _isStacksLoading.asStateFlow()

    private val _isArchiveLoading = MutableStateFlow(true)
    val isArchiveLoading: StateFlow<Boolean> = _isArchiveLoading.asStateFlow()

    private val _isChatHistoryLoading = MutableStateFlow(true)
    val isChatHistoryLoading: StateFlow<Boolean> = _isChatHistoryLoading.asStateFlow()

    private val _isCalendarLoading = MutableStateFlow(true)
    val isCalendarLoading: StateFlow<Boolean> = _isCalendarLoading.asStateFlow()

    private val _isSettingsLoading = MutableStateFlow(true)
    val isSettingsLoading: StateFlow<Boolean> = _isSettingsLoading.asStateFlow()

    private val _isNoteVersionsLoading = MutableStateFlow(false)
    val isNoteVersionsLoading: StateFlow<Boolean> = _isNoteVersionsLoading.asStateFlow()

    // Undo state for bulk archive operations (Phase 4)
    private val _lastArchivedNoteIds = MutableStateFlow<List<String>>(emptyList())
    val lastArchivedNoteIds: StateFlow<List<String>> = _lastArchivedNoteIds.asStateFlow()

    // Network monitoring (Phase 7)
    // MOVED ABOVE CHAT FEATURE MANAGER TO FIX INIT ORDER
    // private val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(application) }
    // val connectionStatus: StateFlow<ConnectionStatus> = networkMonitor.connectionStatus
    //    .distinctUntilChanged()
    //    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionStatus.CONNECTED)

    init {
        // OPTIMIZATION: Track notes loading state - only first emission needed
        // Using take(1) instead of collect to avoid permanent subscription
        viewModelScope.launch {
            try {
                // Wait for initial DB fetch and enforce minimum skeleton duration
                kotlinx.coroutines.delay(600)
                _isNotesLoading.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize notes from database", e)
                _isNotesLoading.value = false
            }
        }

        viewModelScope.launch {
            try {
                kotlinx.coroutines.delay(600)
                _isStacksLoading.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize categories", e)
                _isStacksLoading.value = false
            }
        }

        viewModelScope.launch {
            try {
                kotlinx.coroutines.delay(600)
                _isArchiveLoading.value = false
            } catch (e: Exception) {
                 Log.e(TAG, "Failed to initialize archived notes", e)
                 _isArchiveLoading.value = false
            }
        }

        viewModelScope.launch {
            kotlinx.coroutines.delay(600)
            _isChatHistoryLoading.value = false
        }

        viewModelScope.launch {
            kotlinx.coroutines.delay(600)
            _isCalendarLoading.value = false
            _isSettingsLoading.value = false
        }

        // Initialize feature managers and settings logic here if needed
        // DEFERRED: Chat manager initialization moved to when chat mode is entered

        // Set up NoteOperationsManager callback for AI processing
        noteOperationsManager.setAiProcessingCallback(object : com.example.smarty.features.notes.domain.NoteOperationsManager.AiProcessingCallback {
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
    }

    // Track if deferred initialization has been done
    private var categorySyncDone = false

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
                        chatFeatureManager.enterChatMode()

                        // Restore specific session if saved
                        savedStateHandle.get<String>(KEY_CURRENT_SESSION_ID)?.let { sessionId ->
                            chatFeatureManager.switchToChatSession(sessionId)
                            Log.d(TAG, "Restored chat session: $sessionId")
                        }

                        Log.d(TAG, "Restored chat mode")
                        true
                    }
                }
            }

            // Restore current screen - UI will observe this and navigate if needed
            savedStateHandle.get<String>(KEY_CURRENT_SCREEN)?.let { screen ->
                if (screen != "startup" && screen != "input_stream") {
                    sharedAppState.setCurrentScreen(screen)
                    Log.d(TAG, "Restored currentScreen: $screen")
                }
            }

            // Restore search and filters
            savedStateHandle.get<String>(KEY_SEARCH_QUERY)?.let { query ->
                _searchQuery.value = query
                Log.d(TAG, "Restored searchQuery: $query")
            }

            savedStateHandle.get<List<String>>(KEY_SELECTED_FILTERS)?.let { filterNames ->
                val filters = filterNames.mapNotNull { name ->
                    try { AttachmentOption.valueOf(name) } catch (e: Exception) { null }
                }.toSet()
                _selectedFilters.value = filters
                Log.d(TAG, "Restored selectedFilters: $filters")
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
        
        // Mark note as viewed to clear the unread indicator dot
        note?.id?.let { noteId ->
            noteOperationsManager.markNoteAsViewed(noteId)
        }
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
        Log.d(TAG, "addNote: Generating new note. type=$type, excludeAI=$excludeFromAiChat, contentLen=${content.length}")
        noteOperationsManager.addNote(content, type, sourceUrl, excludeFromAiChat)
    }

    fun addNoteFromShare(sharedContent: SharedContent) {
        // Convert to manager's SharedContent format
        val managerContent = com.example.smarty.core.domain.model.SharedContent(
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
            NoteType.IMAGE -> getApplication<Application>().getString(R.string.note_type_images)
            NoteType.VIDEO -> getApplication<Application>().getString(R.string.note_type_videos)
            NoteType.AUDIO -> getApplication<Application>().getString(R.string.note_type_audio_files)
            NoteType.DOCUMENT -> getApplication<Application>().getString(R.string.note_type_documents)
            NoteType.SPREADSHEET -> getApplication<Application>().getString(R.string.note_type_spreadsheets)
            NoteType.PRESENTATION -> getApplication<Application>().getString(R.string.note_type_presentations)
            NoteType.CODE -> getApplication<Application>().getString(R.string.note_type_code_files)
            NoteType.ARCHIVE -> getApplication<Application>().getString(R.string.note_type_archives)
            NoteType.APK -> getApplication<Application>().getString(R.string.note_type_apk_files)
            else -> getApplication<Application>().getString(R.string.note_type_files)
        }
    }

    /**
     * Submit user response to a clarification request.
     * Treats the response as a user message.
     */
    fun submitClarification(response: String) {
        chatFeatureManager.dispatchQuery(response)
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
                sourceUri = android.net.Uri.parse(attachment.uri),
                mimeType = attachment.mimeType,
                originalFileName = attachment.fileName
            )
            if (compressed != null) {
                // Log compression savings
                if (compressed.isCompressed) {
                    Log.i(TAG, "Attachment compressed: ${attachment.fileName} saved ${ContentTypeDetector.formatFileSize(getApplication(), compressed.savedBytes)} " +
                            "(${String.format("%.1f", compressed.compressionRatio)}% reduction)")
                }
                attachment.copy(
                    uri = compressed.uri,
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
        sb.append(getApplication<Application>().getString(R.string.file_label, attachment.fileName))
        sb.append('\n')
        sb.append(getApplication<Application>().getString(R.string.type_label, attachment.mimeType))
        if (attachment.fileSize > 0) {
            sb.append('\n')
            sb.append(getApplication<Application>().getString(R.string.size_label, ContentTypeDetector.formatFileSize(getApplication(), attachment.fileSize)))
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

    // Archived notes for archive screen - lazy to ensure noteOperationsManager is initialized
    val archivedNotes: StateFlow<List<Note>> by lazy {
        noteOperationsManager.getArchivedNotes()
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    fun deleteNote(note: Note) {
        Log.d(TAG, "deleteNote: Deleting note with object reference. Note ID: ${note.id}")
        noteOperationsManager.deleteNote(note)
    }

    fun deleteNoteById(noteId: String) {
        Log.d(TAG, "deleteNoteById: Deleting note explicitly by ID. Note ID: $noteId")
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
    private val _selectedNoteVersions = MutableStateFlow<List<com.example.smarty.core.domain.model.NoteVersion>>(emptyList())
    val selectedNoteVersions: StateFlow<List<com.example.smarty.core.domain.model.NoteVersion>> = _selectedNoteVersions.asStateFlow()

    /**
     * Load version history for display in UI
     */
    fun loadNoteVersions(noteId: String) {
        viewModelScope.launch {
            _isNoteVersionsLoading.value = true
            val versions = noteOperationsManager.getNoteVersions(noteId)
            _selectedNoteVersions.value = versions
            _isNoteVersionsLoading.value = false
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
        Log.d(TAG, "editNote: Updating note. Note ID: $noteId")
        noteOperationsManager.editNote(noteId, newTitle, newContent, newSummary, newWhySaved, newAttachments)
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

    // Theme Management
    val isDarkTheme: StateFlow<Boolean> = settingsFeatureManager.isDarkTheme

    fun setDarkTheme(isDark: Boolean) {
        settingsFeatureManager.setDarkTheme(isDark)
    }

    // Shake Sensitivity Management
    val shakeSensitivity: StateFlow<Float> = settingsFeatureManager.shakeSensitivity

    fun setShakeSensitivity(value: Float) {
        settingsFeatureManager.setShakeSensitivity(value)
    }

    // User Category Creation
    fun createUserCategory(name: String) {
        noteOperationsManager.createUserCategory(name)
    }

    // Rename Category
    fun renameCategory(category: Category, newName: String) {
        noteOperationsManager.renameCategory(category, newName)
    }

    // Delete Category (BUG-028: Proper cascade cleanup)
    fun deleteCategory(category: Category) {
        Log.d(TAG, "deleteCategory: Removing category. ID: ${category.id}, Name: ${category.name}")
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









    /**
     * Manually trigger text input focus (e.g. from widget)
     */
    fun triggerTextInput() {
        // This could set a flag or just ensure we are on the right screen
        // For now, we'll just ensure we're in normal mode
        setCurrentScreen("inputStream")
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
     * Clear the voice input trigger flag.
     * Call this after Google Speech Recognizer is launched from widget.
     */
    fun clearWakeWordTrigger() {
        _wakeWordTriggered.value = false
    }

    /**
     * Clear the speaker verification cache.
     * Call this after voice fingerprint is deleted or retrained.
     * Forces the wake word detector to reload the embedding from disk.
     */


    // Shake blocking state for inline views and critical sections
    private val _isShakeBlocked = MutableStateFlow(false)

    /**
     * explicit control to block shake gesture (e.g. in Settings, Login, Games)
     */
    fun setShakeBlocked(blocked: Boolean) {
        _isShakeBlocked.value = blocked
        // If blocked, ensure we stop the detector to save battery
        if (blocked) {
            shakeDetector?.stop()
        } else if (currentScreen.value == "input_stream") {
            // Only restart if we are on the main screen
            shakeDetector?.start()
        }
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
        val previousScreen = currentScreen.value
        sharedAppState.setCurrentScreen(screen)
        savedStateHandle[KEY_CURRENT_SCREEN] = screen

        // BATTERY OPTIMIZATION: Only run shake sensor on main screen
        // AND only if not explicitly blocked
        when {
            screen == "input_stream" && previousScreen != "input_stream" -> {
                // Entering main screen - start shake detection if not blocked
                if (!_isShakeBlocked.value) {
                    shakeDetector?.start()
                    Log.d(TAG, "Screen -> $screen: Started shake detection (battery optimization)")
                }
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
        // 1. Global Block (Login, Games, Settings Editing)
        if (_isShakeBlocked.value) {
            Log.d(TAG, "Shake ignored - explicitly blocked (Settings/Login/Game)")
            return
        }

        // 2. Screen Block (Only allowed on input_stream)
        if (currentScreen.value != "input_stream") {
            Log.d(TAG, "Shake ignored - not on main screen (current: ${currentScreen.value})")
            return
        }

        when {
            // Priority 1: During share flow -> toggle full privacy mode
            shareFlowManager.isInShareMode() -> {
                shakeDetector?.triggerHapticFeedback()
                toggleShareFullPrivacy()
                Log.d(TAG, "Shake: Toggled full privacy mode during share")
            }
            // Priority 2: Mic is listening OR has text OR has attachments -> toggle AI exclusion/privacy
            // This preserves the "private option" feature user requested
            _isMicListening.value || _currentInputText.value.isNotBlank() || _currentInputAttachments.value.isNotEmpty() -> {
                shakeDetector?.triggerHapticFeedback()
                togglePendingNoteAiExclusion()
                Log.d(TAG, "Shake: Toggled AI exclusion (active content)")
            }
            // Priority 3: Completely empty (no mic, no text, no attachments)
            // Screen toggle removed per user request.
            else -> {
                Log.d(TAG, "Shake ignored - screen toggle disabled (empty state)")
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
     * Stop agent generation - cancels current streaming job
     * Called when user presses stop button during agent response
     */
    fun stopGeneration() {
        Log.d(TAG, "ViewModel: stopGeneration() called")
        chatFeatureManager.stopGeneration()
    }

    /**
     * Toggle between note input mode and chat mode (delegated to ChatFeatureManager)
     * @param fromShake Whether this toggle was triggered by a shake gesture
     */
    fun toggleChatMode(fromShake: Boolean = false) {
        // Track if this was shake-triggered for glow animation
        if (fromShake) {
            _wasShakeTriggered.value = true
            viewModelScope.launch {
                kotlinx.coroutines.delay(500) // Reset after animation
                _wasShakeTriggered.value = false
            }
        }
        chatFeatureManager.toggleChatMode(fromShake)
    }

    // ==================== Chat Mode ====================

    fun deleteChatMessage(messageId: String) {
        Log.d(TAG, "deleteChatMessage: Passing deletion request from UI down to Feature Manager for messageId: $messageId")
        viewModelScope.launch {
            chatFeatureManager.deleteMessage(messageId)
        }
    }

    /**
     * Enter chat mode (delegated to ChatFeatureManager)
     */
    fun enterChatMode() {
        chatFeatureManager.enterChatMode()
    }

    /**
     * Enter chat mode with a note pre-referenced.
     * Called when user clicks "Ask AI" button on a note card.
     *
     * @param noteTitle Title of the note to reference
     */
    fun enterChatWithNoteReference(noteTitle: String) {
        chatFeatureManager.enterChatWithNoteReference(noteTitle)
    }

    /**
     * Clear pending chat text after it's been consumed by the UI.
     */
    fun clearPendingChatText() {
        chatFeatureManager.clearPendingChatText()
    }

    /**
     * Clear navigation request after handling (delegated to ChatFeatureManager)
     */
    fun clearNavigationRequest() {
        chatFeatureManager.clearNavigationRequest()
    }

    /**
     * Exit chat mode and return to note input mode (delegated to ChatFeatureManager)
     */
    fun exitChatMode() {
        chatFeatureManager.exitChatMode()
    }

    /**
     * Create a new chat session (delegated to ChatFeatureManager)
     */
    fun createNewChatSession() {
        chatFeatureManager.createNewChatSession()
    }

    /**
     * Switch to a different chat session (delegated to ChatFeatureManager)
     */
    fun switchToChatSession(sessionId: String) {
        chatFeatureManager.switchToChatSession(sessionId)
    }

    /**
     * Delete a chat session (delegated to ChatFeatureManager)
     */
    fun deleteChatSession(sessionId: String) {
        chatFeatureManager.deleteChatSession(sessionId)
    }

    /**
     * Clear current chat history (delegated to ChatFeatureManager)
     */
    fun clearChatHistory() {
        chatFeatureManager.clearChatHistory()
    }

    // 
    // @MENTION HANDLING - Real-time autocomplete for note references
    // 

    /**
     * Update mention state when chat input text changes.
     * Detects active @mention typing and fetches suggestions.
     *
     * @param text Current text field content
     * @param cursorPosition Current cursor position in text
     */
    fun updateMentionState(text: String, cursorPosition: Int) {
        chatFeatureManager.updateMentionState(text, cursorPosition)
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
        return chatFeatureManager.onMentionSelected(suggestion, currentText)
    }

    /**
     * Dismiss mention dropdown without selection.
     */
    fun dismissMention() {
        chatFeatureManager.dismissMention()
    }

    /**
     * UNIVERSAL ACTION DISPATCHER
     * The primary entry point for all user intent.
     * Hybridizes fast-path rule execution with deep-path agentic reasoning.
     */
    fun dispatchQuery(content: String, attachments: List<Attachment> = emptyList()) {
        chatFeatureManager.dispatchQuery(content, attachments)
    }

    /**
     * Send a message in chat mode using the Koog-based AI agent.
     */
    fun sendChatMessage(content: String, attachments: List<Attachment> = emptyList()) {
        chatFeatureManager.sendChatMessage(content, attachments)
    }

    /**
     * Submit user's answer to an interactive question.
     */
    fun submitClarification(messageId: String, response: String) {
        chatFeatureManager.submitClarification(messageId, response)
    }

    /**
     * Direct image generation via Krea API (delegated to ChatFeatureManager).
     */
    fun generateImageDirect(prompt: String, aspectRatio: String = "1:1") {
        chatFeatureManager.generateImageDirect(prompt, aspectRatio)
    }

    // ==================== Calendar Operations (delegated to CalendarFeatureManager) ====================

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
        calendarFeatureManager.addCalendarEvent(
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
    }

    /**
     * Update an existing calendar event
     */
    fun updateCalendarEvent(event: CalendarEvent) {
        calendarFeatureManager.updateCalendarEvent(event)
    }

    /**
     * Delete a calendar event by ID
     */
    fun deleteCalendarEvent(eventId: String) {
        calendarFeatureManager.deleteCalendarEvent(eventId)
    }

    /**
     * Get events for a specific day
     */
    suspend fun getEventsForDay(dayMillis: Long): List<CalendarEvent> =
        calendarFeatureManager.getEventsForDay(dayMillis)

    /**
     * Get today's events
     */
    suspend fun getTodayEvents(): List<CalendarEvent> =
        calendarFeatureManager.getTodayEvents()

    /**
     * Get AI-visible upcoming events (for agent context)
     */
    suspend fun getAiVisibleUpcomingEvents(limit: Int = 10): List<CalendarEvent> =
        calendarFeatureManager.getAiVisibleUpcomingEvents(limit)

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

        // Wake word detection removed - using Google Speech Recognizer instead

        // Flush any pending batched database writes before going to background
        viewModelScope.launch {
            noteOperationsManager.flushPendingWrites()
            // Clear in-memory caches to reduce memory footprint
            aiResponseCache.clear()
        }

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

        // LEAK FIX: Use viewModelScope with NonCancellable to ensure cleanup completes
        // even if the ViewModel is destroyed.
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            try {
                withTimeout(3000L) {
                    noteOperationsManager.cleanup()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup timeout or error: ${e.message}")
            }
        }

        shakeDetector?.stop()
        shakeDetector = null

        // Clean up completion sound manager
        completionSoundManager.shutdown()


    }
}

// SmartyViewModelFactory is defined in SmartyViewModelFactory.kt
