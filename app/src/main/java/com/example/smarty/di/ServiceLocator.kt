package com.example.smarty.di

import android.app.Application
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.local.SmartyDatabase
import com.example.smarty.data.remote.AIService
import com.example.smarty.data.repository.SmartyRepository
import com.example.smarty.data.repository.SyncRepository
import com.example.smarty.data.repository.FirestoreSyncRepository
import com.example.smarty.data.repository.DeviceAudioRepository
import com.example.smarty.viewmodel.managers.AudioFeatureManager
import com.example.smarty.viewmodel.managers.AudioPlaybackManager
import com.example.smarty.viewmodel.managers.CalendarFeatureManager
import com.example.smarty.viewmodel.managers.ChatFeatureManager
import com.example.smarty.viewmodel.managers.MemoryFeatureManager
import com.example.smarty.viewmodel.managers.MemorySyncManager
import com.example.smarty.viewmodel.managers.NoteOperationsManager
import com.example.smarty.viewmodel.managers.SearchFeatureManager
import com.example.smarty.viewmodel.managers.SettingsFeatureManager
import com.example.smarty.viewmodel.managers.SystemFeatureManager
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
    private var memorySyncManager: MemorySyncManager? = null

    @Volatile
    private var memoryFeatureManager: MemoryFeatureManager? = null

    @Volatile
    private var calendarFeatureManager: CalendarFeatureManager? = null

    @Volatile
    private var noteOperationsManager: NoteOperationsManager? = null

    @Volatile
    private var repository: SmartyRepository? = null

    @Volatile
    private var syncRepository: SyncRepository? = null

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
            val database = SmartyDatabase.getDatabase(application)
            val securePreferences = SecurePreferences.getInstance(application)
            val aiService = AIService(application, securePreferences)
            val repository = provideRepository(application)

            NoteOperationsManager(
                repository = repository,
                aiService = aiService,
                context = application,
                scope = applicationScope, // Use app scope for singleton manager
                noteDao = database.noteDao()
            ).also { noteOperationsManager = it }
        }
    }

    fun provideMemorySyncManager(application: Application): MemorySyncManager {
        return memorySyncManager ?: synchronized(this) {
            val database = SmartyDatabase.getDatabase(application)
            val securePreferences = SecurePreferences.getInstance(application)
            val aiService = AIService(application, securePreferences)

            MemorySyncManager(
                context = application,
                database = database,
                aiMemoryDao = database.aiMemoryDao(),
                aiService = aiService
            ).also { memorySyncManager = it }
        }
    }

    fun provideMemoryFeatureManager(application: Application): MemoryFeatureManager {
        return memoryFeatureManager ?: synchronized(this) {
            val database = SmartyDatabase.getDatabase(application)
            val syncManager = provideMemorySyncManager(application)

            MemoryFeatureManager(
                aiMemoryDao = database.aiMemoryDao(),
                syncManager = syncManager,
                scope = applicationScope // Use app scope for shared manager
            ).also { memoryFeatureManager = it }
        }
    }

    fun provideCalendarFeatureManager(application: Application): CalendarFeatureManager {
        return calendarFeatureManager ?: synchronized(this) {
            val database = SmartyDatabase.getDatabase(application)
            val securePreferences = SecurePreferences.getInstance(application)
            val alarmScheduler = com.example.smarty.service.AlarmScheduler.getInstance(application)
            val repository = provideRepository(application)
            val googleCalendarSyncManager = com.example.smarty.calendar.GoogleCalendarSyncManager(application, repository)

            CalendarFeatureManager(
                calendarDao = database.calendarDao(),
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
                onNavigateRequest = { /* TODO: Hook up navigation */ }
            ).also { systemFeatureManager = it }
        }
    }

    // ChatFeatureManager requires a lot of dependencies and ViewModelScope usually
    // We might need to factory it per ViewModel or keep it shared if it holds state
    fun provideChatFeatureManager(application: Application, scope: CoroutineScope): ChatFeatureManager {
        return chatFeatureManager ?: synchronized(this) {
            val database = SmartyDatabase.getDatabase(application)
            val securePreferences = SecurePreferences.getInstance(application)
            val repo = provideRepository(application)
            val chatRepo = com.example.smarty.data.repository.ChatRepository(database.chatDao())

            val settingsFM = provideSettingsFeatureManager(application)
            val noteOps = provideNoteOperationsManager(application)
            val systemFM = provideSystemFeatureManager(application)
            val completionSound = com.example.smarty.util.CompletionSoundManager.getInstance(application)
            val alarmScheduler = com.example.smarty.service.AlarmScheduler.getInstance(application)
            val executionPlan = com.example.smarty.viewmodel.managers.ExecutionPlanManager()
            val memoryFM = provideMemoryFeatureManager(application)
            val searchFM = provideSearchFeatureManager(application)
            val audioFM = provideAudioFeatureManager(application)
            val calendarFM = provideCalendarFeatureManager(application)
            val styleFM = com.example.smarty.viewmodel.managers.StyleFeatureManager()
            val workflowManager = com.example.smarty.viewmodel.managers.WorkflowManager(
                repository = repo,
                scope = applicationScope,
                onStatusUpdate = { /* handled via callback later */ }
            )
            val savedStateHandle = androidx.lifecycle.SavedStateHandle() // Placeholder if not provided

            // Shared State
            val sharedState = com.example.smarty.data.state.SharedAppState() // Should be singleton too

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
                executionPlanManager = executionPlan,
                memoryFeatureManager = memoryFM,
                searchFeatureManager = searchFM,
                audioFeatureManager = audioFM,
                calendarFeatureManager = calendarFM,
                styleFeatureManager = styleFM,
                workflowManager = workflowManager,
                savedStateHandle = savedStateHandle,
                currentScreen = sharedState.currentScreen,
                activeNoteId = sharedState.activeNoteId,
                isDarkTheme = sharedState.isDarkTheme,
                connectionStatus = sharedState.connectionStatus,
                cacheSizeBytes = sharedState.cacheSizeBytes,
                unreadForMemoryCount = provideMemorySyncManager(application).unreadCount
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
        memorySyncManager = null
        memoryFeatureManager = null
        calendarFeatureManager = null
        noteOperationsManager = null
        repository = null
        audioFeatureManager = null
        settingsFeatureManager = null
        systemFeatureManager = null
        chatFeatureManager = null
        audioPlaybackManager = null
        deviceAudioRepository = null
        searchFeatureManager = null
    }
}
