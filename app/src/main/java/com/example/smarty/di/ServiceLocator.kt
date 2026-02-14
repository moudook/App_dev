package com.example.smarty.di

import android.app.Application
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.local.SmartyDatabase
import com.example.smarty.data.cache.AIResponseCache
import com.example.smarty.data.remote.AIService
import com.example.smarty.data.repository.SmartyRepository
import com.example.smarty.data.repository.SyncRepository
import com.example.smarty.data.repository.FirestoreSyncRepository
import com.example.smarty.data.repository.DeviceAudioRepository
import com.example.smarty.features.audio.domain.AudioFeatureManager
import com.example.smarty.features.audio.domain.AudioPlaybackManager
import com.example.smarty.features.calendar.domain.CalendarFeatureManager
import com.example.smarty.features.chat.domain.ChatFeatureManager
import com.example.smarty.features.notes.domain.NoteOperationsManager
import com.example.smarty.features.search.domain.SearchFeatureManager
import com.example.smarty.features.settings.domain.SettingsFeatureManager
import com.example.smarty.features.system.domain.SystemFeatureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/**
 * Simple Service Locator to manage singletons and dependencies.
 * Helps decouple ViewModels by providing shared instances of managers.
 */
object ServiceLocator {

    @Volatile
    private var repository: SmartyRepository? = null

    @Volatile
    private var syncRepository: SyncRepository? = null

    @Volatile
    private var noteOperationsManager: NoteOperationsManager? = null

    @Volatile
    private var calendarFeatureManager: CalendarFeatureManager? = null

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun provideSyncRepository(): SyncRepository {
        return syncRepository ?: synchronized(this) {
            FirestoreSyncRepository().also { syncRepository = it }
        }
    }

    fun provideRepository(application: Application): SmartyRepository {
        return repository ?: synchronized(this) {
            val database = SmartyDatabase.getDatabase(application)
            val syncRepo = provideSyncRepository()
            SmartyRepository(
                noteDao = database.noteDao(),
                categoryDao = database.categoryDao(),
                calendarDao = database.calendarDao(),
                noteVersionDao = database.noteVersionDao(),
                context = application,
                syncRepository = syncRepo
            ).also { repository = it }
        }
    }

    fun provideNoteOperationsManager(application: Application): NoteOperationsManager {
        return noteOperationsManager ?: synchronized(this) {
            val securePreferences = SecurePreferences.getInstance(application)
            val aiService = provideAIService(application)
            val repository = provideRepository(application)

            NoteOperationsManager(
                repository = repository,
                aiService = aiService,
                context = application,
                scope = applicationScope // Use app scope for singleton manager
            ).also { noteOperationsManager = it }
        }
    }

    fun provideCalendarFeatureManager(application: Application): CalendarFeatureManager {
        return calendarFeatureManager ?: synchronized(this) {
            val securePreferences = SecurePreferences.getInstance(application)
            val alarmScheduler = com.example.smarty.service.AlarmScheduler.getInstance(application)
            val repository = provideRepository(application)
            val googleCalendarSyncManager = com.example.smarty.features.calendar.domain.GoogleCalendarSyncManager(application, repository)

            CalendarFeatureManager(
                repository = repository,
                googleCalendarSyncManager = googleCalendarSyncManager,
                securePreferences = securePreferences,
                alarmScheduler = alarmScheduler,
                scope = applicationScope
            ).also { calendarFeatureManager = it }
        }
    }

    @Volatile
    private var audioFeatureManager: AudioFeatureManager? = null

    @Volatile
    private var settingsFeatureManager: SettingsFeatureManager? = null

    @Volatile
    private var searchFeatureManager: SearchFeatureManager? = null

    @Volatile
    private var systemFeatureManager: SystemFeatureManager? = null

    @Volatile
    private var chatFeatureManager: ChatFeatureManager? = null

    @Volatile
    private var audioPlaybackManager: AudioPlaybackManager? = null

    @Volatile
    private var deviceAudioRepository: DeviceAudioRepository? = null

    fun provideDeviceAudioRepository(application: Application): DeviceAudioRepository {
        return deviceAudioRepository ?: synchronized(this) {
            DeviceAudioRepository(application).also { deviceAudioRepository = it }
        }
    }

    fun provideAudioPlaybackManager(application: Application): AudioPlaybackManager {
        return audioPlaybackManager ?: synchronized(this) {
            AudioPlaybackManager(
                context = application,
                scope = applicationScope
            ).also { audioPlaybackManager = it }
        }
    }

    fun provideAudioFeatureManager(application: Application): AudioFeatureManager {
        return audioFeatureManager ?: synchronized(this) {
            val playbackManager = provideAudioPlaybackManager(application)
            val repo = provideDeviceAudioRepository(application)

            AudioFeatureManager(
                audioPlaybackManager = playbackManager,
                deviceAudioRepository = repo,
                scope = applicationScope
            ).also { audioFeatureManager = it }
        }
    }

    fun provideSettingsFeatureManager(application: Application): SettingsFeatureManager {
        return settingsFeatureManager ?: synchronized(this) {
            val securePreferences = SecurePreferences.getInstance(application)

            SettingsFeatureManager(
                securePreferences = securePreferences,
                scope = applicationScope
            ).also { settingsFeatureManager = it }
        }
    }

    fun provideSystemFeatureManager(application: Application): SystemFeatureManager {
        return systemFeatureManager ?: synchronized(this) {
            val securePreferences = SecurePreferences.getInstance(application)
            val audioManager = provideAudioPlaybackManager(application)
            val repo = provideDeviceAudioRepository(application)
            // Note: Navigation callback needs to be handled carefully or injected later
            // For now passing a no-op or we need a way to set it

            val calendarFM = provideCalendarFeatureManager(application)

            SystemFeatureManager(
                context = application,
                scope = applicationScope,
                audioManager = audioManager,
                calendarManager = calendarFM.getCalendarManager(),
                securePreferences = securePreferences,
                deviceAudioRepository = repo,
                onNavigateRequest = { screen ->
                    provideSharedAppState().setNavigationRequest(screen)
                }
            ).also { systemFeatureManager = it }
        }
    }

    @Volatile
    private var chatRepository: com.example.smarty.data.repository.ChatRepository? = null

    fun provideChatRepository(application: Application): com.example.smarty.data.repository.ChatRepository {
        return chatRepository ?: synchronized(this) {
            val database = SmartyDatabase.getDatabase(application)
            com.example.smarty.data.repository.ChatRepository(database.chatDao()).also { chatRepository = it }
        }
    }

    @Volatile
    private var aiService: AIService? = null

    fun provideAIService(application: Application): AIService {
        return aiService ?: synchronized(this) {
            val securePreferences = SecurePreferences.getInstance(application)
            val remoteAgentService = provideRemoteAgentService(application)
            val aiResponseCache = provideAIResponseCache(application)
            AIService(application, securePreferences, remoteAgentService, aiResponseCache).also { aiService = it }
        }
    }

    fun provideRemoteAgentService(application: Application): com.example.smarty.data.remote.RemoteAgentService {
        val securePreferences = SecurePreferences.getInstance(application)

        // Create Ktor HttpClient using shared OkHttp engine
        val ktorClient = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
            engine {
                preconfigured = com.example.smarty.core.common.util.HttpClientProvider.default
            }
        }

        return com.example.smarty.data.remote.RemoteAgentService(
            client = ktorClient,
            eventSink = com.example.smarty.core.common.worker.BackgroundAgentEventSink(),
            serverUrlProvider = { securePreferences.getSmartyServerUrl() },
            deviceIdProvider = { securePreferences.getDeviceId() }
        )
    }

    @Volatile
    private var alarmScheduler: com.example.smarty.service.AlarmScheduler? = null

    fun provideAlarmScheduler(application: Application): com.example.smarty.service.AlarmScheduler {
        return alarmScheduler ?: synchronized(this) {
            com.example.smarty.service.AlarmScheduler.getInstance(application).also { alarmScheduler = it }
        }
    }

    @Volatile
    private var completionSoundManager: com.example.smarty.core.common.util.CompletionSoundManager? = null

    fun provideCompletionSoundManager(application: Application): com.example.smarty.core.common.util.CompletionSoundManager {
        return completionSoundManager ?: synchronized(this) {
            com.example.smarty.core.common.util.CompletionSoundManager.getInstance(application).also { completionSoundManager = it }
        }
    }

    @Volatile
    private var cacheManager: com.example.smarty.data.cache.CacheManager? = null

    @Volatile
    private var aiResponseCache: AIResponseCache? = null

    fun provideAIResponseCache(application: Application): AIResponseCache {
        return aiResponseCache ?: synchronized(this) {
            val database = SmartyDatabase.getDatabase(application)
            AIResponseCache(
                cacheDao = database.aiCacheDao(),
                scope = applicationScope
            ).also { aiResponseCache = it }
        }
    }

    fun provideCacheManager(application: Application): com.example.smarty.data.cache.CacheManager {
        return cacheManager ?: synchronized(this) {
            com.example.smarty.data.cache.CacheManager.getInstance(application).also { cacheManager = it }
        }
    }

    @Volatile
    private var styleFeatureManager: com.example.smarty.features.chat.domain.StyleFeatureManager? = null

    fun provideStyleFeatureManager(): com.example.smarty.features.chat.domain.StyleFeatureManager {
        return styleFeatureManager ?: synchronized(this) {
            com.example.smarty.features.chat.domain.StyleFeatureManager().also { styleFeatureManager = it }
        }
    }

    @Volatile
    private var workflowManager: com.example.smarty.features.chat.domain.WorkflowManager? = null

    fun provideWorkflowManager(application: Application): com.example.smarty.features.chat.domain.WorkflowManager {
        return workflowManager ?: synchronized(this) {
            val repo = provideRepository(application)
            com.example.smarty.features.chat.domain.WorkflowManager(
                repository = repo,
                scope = applicationScope,
                onStatusUpdate = { /* Callback handling to be improved */ }
            ).also { workflowManager = it }
        }
    }

    @Volatile
    private var noteProcessingQueueManager: com.example.smarty.features.notes.domain.NoteProcessingQueueManager? = null

    fun provideNoteProcessingQueueManager(application: Application): com.example.smarty.features.notes.domain.NoteProcessingQueueManager {
        return noteProcessingQueueManager ?: synchronized(this) {
            val repo = provideRepository(application)
            val aiService = provideAIService(application)
            com.example.smarty.features.notes.domain.NoteProcessingQueueManager(
                repository = repo,
                aiService = aiService,
                scope = applicationScope
            ).also { noteProcessingQueueManager = it }
        }
    }

    @Volatile
    private var sharedAppState: com.example.smarty.data.state.SharedAppState? = null

    fun provideSharedAppState(): com.example.smarty.data.state.SharedAppState {
        return sharedAppState ?: synchronized(this) {
            com.example.smarty.data.state.SharedAppState().also { sharedAppState = it }
        }
    }

    // ChatFeatureManager requires a lot of dependencies and ViewModelScope usually
    // We might need to factory it per ViewModel or keep it shared if it holds state
    fun provideChatFeatureManager(application: Application, scope: CoroutineScope): ChatFeatureManager {
        return chatFeatureManager ?: synchronized(this) {
            val database = SmartyDatabase.getDatabase(application)
            val securePreferences = SecurePreferences.getInstance(application)
            val repo = provideRepository(application)
            val chatRepo = provideChatRepository(application) // Use provider

            val settingsFM = provideSettingsFeatureManager(application)
            val noteOps = provideNoteOperationsManager(application)
            val systemFM = provideSystemFeatureManager(application)
            val completionSound = provideCompletionSoundManager(application)
            val alarmScheduler = provideAlarmScheduler(application)
            val searchFM = provideSearchFeatureManager(application)
            val audioFM = provideAudioFeatureManager(application)
            val calendarFM = provideCalendarFeatureManager(application)
            val styleFM = provideStyleFeatureManager()
            val workflowManager = provideWorkflowManager(application)
            val savedStateHandle = androidx.lifecycle.SavedStateHandle() // Placeholder if not provided

            // Shared State
            val sharedState = provideSharedAppState()

            ChatFeatureManager(
                application = application,
                scope = applicationScope, // Use app scope for persistence
                chatRepository = chatRepo,
                repository = repo,
                database = database,
                securePreferences = securePreferences,
                settingsFeatureManager = settingsFM,
                noteOperationsManager = noteOps,
                systemFeatureManager = systemFM,
                completionSoundManager = completionSound,
                alarmScheduler = alarmScheduler,
                searchFeatureManager = searchFM,
                audioFeatureManager = audioFM,
                calendarFeatureManager = calendarFM,
                styleFeatureManager = styleFM,
                workflowManager = workflowManager,
                savedStateHandle = savedStateHandle,
                currentScreen = sharedState.currentScreen,
                activeNoteId = sharedState.activeNoteId,
                isDarkTheme = settingsFM.isDarkTheme,
                connectionStatus = sharedState.connectionStatus,
                cacheSizeBytes = settingsFM.cacheSizeBytes,
                onNavigate = { screen -> sharedState.setNavigationRequest(screen) }
            ).also { chatFeatureManager = it }
        }
    }

    fun provideSearchFeatureManager(application: Application): SearchFeatureManager {
        return searchFeatureManager ?: synchronized(this) {
            val repo = provideRepository(application)
            val noteOps = provideNoteOperationsManager(application)
            // Get notes flow from NoteOperationsManager (which gets it from Repo)
            // We need a StateFlow for SearchFeatureManager
            val allNotesFlow = noteOps.getAllNotes()
                .stateIn(applicationScope, SharingStarted.WhileSubscribed(5000), emptyList())

            val searchHistory = com.example.smarty.data.local.SearchHistoryManager(application)
            val securePreferences = SecurePreferences.getInstance(application)

            SearchFeatureManager(
                repository = repo,
                allNotes = allNotesFlow,
                searchHistoryManager = searchHistory,
                securePreferences = securePreferences
            ).also { searchFeatureManager = it }
        }
    }

    // Reset for testing or cleanup
    fun reset() {
        calendarFeatureManager = null
        noteOperationsManager = null
        repository = null
        chatRepository = null
        audioFeatureManager = null
        settingsFeatureManager = null
        systemFeatureManager = null
        chatFeatureManager = null
        audioPlaybackManager = null
        deviceAudioRepository = null
        searchFeatureManager = null
        aiResponseCache = null
    }
    @Volatile
    private var memoryFeatureManager: com.example.smarty.viewmodel.managers.MemoryFeatureManager? = null

    fun provideMemoryFeatureManager(application: Application): com.example.smarty.viewmodel.managers.MemoryFeatureManager {
        return memoryFeatureManager ?: synchronized(this) {
            val database = SmartyDatabase.getDatabase(application)
            com.example.smarty.viewmodel.managers.MemoryFeatureManager(database.aiMemoryDao()).also { memoryFeatureManager = it }
        }
    }

    @Volatile
    private var memorySyncManager: com.example.smarty.features.chat.domain.memory.MemorySyncManager? = null

    fun provideMemorySyncManager(application: Application): com.example.smarty.features.chat.domain.memory.MemorySyncManager {
        return memorySyncManager ?: synchronized(this) {
            val featureManager = provideMemoryFeatureManager(application)
            val noteOps = provideNoteOperationsManager(application)
            com.example.smarty.features.chat.domain.memory.MemorySyncManager(featureManager, noteOps).also { memorySyncManager = it }
        }
    }
}

