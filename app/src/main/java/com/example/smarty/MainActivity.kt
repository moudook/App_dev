package com.example.smarty

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.example.smarty.navigation.CogniNavHost
import com.example.smarty.ui.components.audio.AnimatedMiniPlayer
import com.example.smarty.ui.components.audio.FullAudioPlayer

import com.example.smarty.ui.theme.CogniTheme
import com.example.smarty.data.worker.CacheCleanupWorker
import com.example.smarty.viewmodel.AudioPlayerViewModel
import com.example.smarty.viewmodel.CogniViewModel
import com.example.smarty.viewmodel.CogniViewModelFactory
import com.example.smarty.viewmodel.SharedContent
import com.example.smarty.viewmodel.SharedFileInfo
import com.example.smarty.ui.screens.StartupScreen
import com.example.smarty.ui.components.AttachmentOption

class MainActivity : ComponentActivity() {
    // Use factory for SavedStateHandle support (BUG-053: state preservation across process death)
    private val viewModel: CogniViewModel by viewModels {
        CogniViewModelFactory(application, this)
    }
    private val audioPlayerViewModel: AudioPlayerViewModel by viewModels()

    // Permission launcher for multiple permissions
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Log permission results for debugging
        permissions.entries.forEach { entry ->
            android.util.Log.d("Permissions", "${entry.key}: ${if (entry.value) "GRANTED" else "DENIED"}")
        }
        // Initialize Vosk after permissions granted
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            viewModel.initVoskWakeWord(this)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        


        // Request all necessary permissions
        requestRequiredPermissions()

        // Initialize shake detector for chat mode toggle
        viewModel.initShakeDetector(this)

        // Initialize Vosk wake word detection if permission already granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            viewModel.initVoskWakeWord(this)
        }

        // Schedule periodic cache cleanup
        CacheCleanupWorker.schedule(this)

        // Detect if launched via share intent - skip splash animation
        val isShareIntent = intent?.action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)

        // Handle share intent on launch
        handleIntent(intent)

        setContent {
            // Collect theme state at the top level for CogniTheme
            val isDarkThemeTop by viewModel.isDarkTheme.collectAsState()

            CogniTheme(darkTheme = isDarkThemeTop) {
                // Splash Screen State - Skip for share intents
                var showSplash by remember { mutableStateOf(!isShareIntent) }

                val navController = rememberNavController()
                val notes by viewModel.notes.collectAsState()
                val categories by viewModel.categories.collectAsState()
                val selectedNote by viewModel.selectedNote.collectAsState()
                val selectedCategory by viewModel.selectedCategory.collectAsState()
                val isProcessing by viewModel.isProcessing.collectAsState()
                val isNotesLoading by viewModel.isNotesLoading.collectAsState()

                // API Key states
                val providerConfigs by viewModel.providerConfigs.collectAsState()
                val providerPriorityOrder by viewModel.providerPriorityOrder.collectAsState()

                // PIN state
                val isPinConfigured by viewModel.isPinSet.collectAsState()

                // Pending share state
                val pendingShare by viewModel.pendingShare.collectAsState()
                val isShareFullPrivacy by viewModel.pendingShareFullPrivacy.collectAsState()

                // Archived notes
                val archivedNotes by viewModel.archivedNotes.collectAsState()

                // Calendar events
                val calendarEvents by viewModel.calendarEvents.collectAsState()

                // Theme state
                val isDarkTheme by viewModel.isDarkTheme.collectAsState()

                // Chat mode state
                val isChatMode by viewModel.isChatMode.collectAsState()
                val chatMessages by viewModel.chatMessages.collectAsState()
                val isChatProcessing by viewModel.isChatProcessing.collectAsState()

                // Chat history state
                val chatSessions by viewModel.chatSessions.collectAsState()
                val currentSessionId by viewModel.currentSessionId.collectAsState()

                // AI exclusion state
                val isAiExcluded by viewModel.pendingNoteAiExcluded.collectAsState()

                // Audio player state
                val audioUiState by audioPlayerViewModel.uiState.collectAsState()
                val isMiniPlayerVisible by audioPlayerViewModel.isMiniPlayerVisible.collectAsState()
                val isFullPlayerVisible by audioPlayerViewModel.isFullPlayerVisible.collectAsState()
                val fullPlayerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                // Cache state
                val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsState()
                val isClearingCache by viewModel.isClearingCache.collectAsState()

                // Search and Filter State (Backend Integration)
                val searchQuery by viewModel.searchQuery.collectAsState()
                val selectedFilters by viewModel.selectedFilters.collectAsState()


                // Tavily Web Search API state
                val tavilyApiKey by viewModel.tavilyApiKey.collectAsState()

                // Shake sensitivity state
                val shakeSensitivity by viewModel.shakeSensitivity.collectAsState()

                // GROQ key usage stats for UI display
                val groqKeyUsageStats by viewModel.groqKeyUsageStats.collectAsState()

                // Shake mode switch animation trigger
                val wasShakeTriggered by viewModel.wasShakeTriggered.collectAsState()
                val connectionStatus by viewModel.connectionStatus.collectAsState()

                // Wake word detection state
                val wakeWordTriggered by viewModel.wakeWordTriggered.collectAsState()

                // Audio playback request from AI agent
                val pendingAudioPlayback by viewModel.pendingAudioPlayback.collectAsState()

                // Trigger audio playback when AI agent requests it
                LaunchedEffect(pendingAudioPlayback) {
                    pendingAudioPlayback?.let { track ->
                        audioPlayerViewModel.playAudio(track)
                        viewModel.clearPendingAudioPlayback()
                    }
                }

                // Refresh cache size when composable enters composition
                LaunchedEffect(Unit) {
                    viewModel.refreshCacheSize()
                }

                // GLOBAL SPEECH STATE - Track voice input across all screens
                val globalSpeechState = com.example.smarty.util.rememberSpeechToText(
                    onResult = { text ->
                        // Handle speech result globally via ViewModel
                        viewModel.onSpeechResult(text)
                    },
                    onError = { error ->
                        // Handle error globally if needed
                    }
                )

                // Trigger existing speech input when wake word is detected
                // CONTEXT-AWARE: Routes speech to main page or chat based on current mode
                // Uses the shimmer effect on input block instead of Google's popup
                LaunchedEffect(wakeWordTriggered, isChatMode) {
                    if (wakeWordTriggered) {
                        android.util.Log.i("WakeWord", "Triggering speech input in ${if (isChatMode) "CHAT" else "MAIN"} mode")
                        viewModel.clearWakeWordTrigger()
                        // Pass current mode so speech result goes to correct input field
                        globalSpeechState.startListening(isChatMode = isChatMode)
                    }
                }

                // Sync mic state to ViewModel for contextual shake detection
                // Also coordinate audio playback with speech recognition
                // Pause/resume wake word detection based on mic usage
                LaunchedEffect(globalSpeechState.isListening) {
                    viewModel.updateMicListening(globalSpeechState.isListening)
                    audioPlayerViewModel.onSpeechListeningChanged(globalSpeechState.isListening)

                    if (globalSpeechState.isListening) {
                        // Mic is in use (user tapped mic button) - pause wake word detection
                        android.util.Log.d("WakeWord", "Mic active - pausing wake word detection")
                        viewModel.stopWakeWordDetection()
                    } else {
                        // Mic released - restart wake word detection
                        android.util.Log.d("WakeWord", "Mic released - resuming wake word detection")
                        viewModel.restartWakeWordDetection()
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    Crossfade(
                        targetState = showSplash,
                        animationSpec = tween(600),
                        label = "SplashScreenTransition"
                    ) { isSplash ->
                        if (isSplash) {
                            StartupScreen(
                                onComplete = { showSplash = false }
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize()) {
                                CogniNavHost(
                                    navController = navController,
                                    notes = notes,
                                    archivedNotes = archivedNotes,
                                    categories = categories,
                                    selectedNote = selectedNote,
                                    selectedCategory = selectedCategory,
                                    isProcessing = isProcessing,
                                    providerConfigs = providerConfigs,
                                    providerPriorityOrder = providerPriorityOrder,
                                    onAddApiKey = { provider, key ->
                                        viewModel.addApiKey(provider, key)
                                    },
                                    onRemoveApiKey = { provider, key ->
                                        viewModel.removeApiKey(provider, key)
                                    },
                                    onUpdateApiKey = { provider, oldKey, newKey ->
                                        viewModel.updateApiKey(provider, oldKey, newKey)
                                    },
                                    onSetProviderEnabled = { provider, enabled ->
                                        viewModel.setProviderEnabled(provider, enabled)
                                    },
                                    onSetSelectedModel = { provider, model ->
                                        viewModel.setSelectedModel(provider, model)
                                    },
                                    onSetProviderPriority = { priority ->
                                        viewModel.setProviderPriority(priority)
                                    },
                                    onTestApiKey = { provider, key, callback ->
                                        viewModel.testApiKey(provider, key, callback)
                                    },
                                    // PIN management
                                    isPinConfigured = isPinConfigured,
                                    onSetPin = { pin ->
                                        viewModel.setPin(pin)
                                    },
                                    onVerifyPin = { pin ->
                                        viewModel.verifyPin(pin)
                                    },
                                    onChangePin = { oldPin, newPin ->
                                        viewModel.changePin(oldPin, newPin)
                                    },
                                    onClearPin = {
                                        viewModel.clearPin()
                                    },
                                    // Notes management
                                    onAddNote = { content, attachments ->
                                        viewModel.addNoteWithAttachments(content, attachments)
                                    },
                                    onSelectNote = { note ->
                                        viewModel.selectNote(note)
                                    },
                                    onSelectCategory = { category ->
                                        viewModel.selectCategory(category)
                                    },
                                    onCreateCategory = { name ->
                                        viewModel.createUserCategory(name)
                                    },
                                    onDeleteCategory = { category ->
                                        viewModel.deleteCategory(category)
                                    },
                                    onArchiveNote = { noteId ->
                                        viewModel.archiveNote(noteId)
                                    },
                                    onUnarchiveNote = { noteId ->
                                        viewModel.unarchiveNote(noteId)
                                    },
                                    onBulkArchive = { noteIds ->
                                        viewModel.archiveNotes(noteIds)
                                    },
                                    onUndoArchive = {
                                        viewModel.undoArchive()
                                    },
                                    onRefreshNotes = {
                                        viewModel.refreshNotes()
                                    },
                                    isRefreshing = viewModel.isRefreshing.collectAsState().value,
                                    isNotesLoading = isNotesLoading,
                                    onDeleteNote = { note ->
                                        viewModel.deleteNote(note)
                                    },
                                    onDeleteNoteById = { noteId ->
                                        viewModel.deleteNoteById(noteId)
                                    },
                                    onUpdateNoteTodos = { noteId, todos, onComplete ->
                                        viewModel.updateNoteTodos(noteId, todos, onComplete)
                                    },
                                    onEditNote = { noteId, newTitle, newContent ->
                                        viewModel.editNote(noteId, newTitle, newContent)
                                    },
                                    onMarkAsViewed = { noteId ->
                                        viewModel.markNoteAsViewed(noteId)
                                    },
                                    // Pending share management
                                    pendingShare = pendingShare,
                                    onConfirmShare = { category, instructions ->
                                        viewModel.confirmShare(category, instructions)
                                    },
                                    onCancelShare = {
                                        viewModel.cancelShare()
                                    },
                                    isShareFullPrivacy = isShareFullPrivacy,
                                    // Chat mode management
                                    isChatMode = isChatMode,
                                    chatMessages = chatMessages,
                                    isChatProcessing = isChatProcessing,
                                    onSendChatMessage = { content, attachments ->
                                        viewModel.sendChatMessage(content, attachments)
                                    },
                                    onExitChatMode = {
                                        viewModel.exitChatMode()
                                    },
                                    // Chat history management
                                    chatSessions = chatSessions,
                                    currentSessionId = currentSessionId,
                                    onSwitchChatSession = { sessionId ->
                                        viewModel.switchToChatSession(sessionId)
                                    },
                                    onNewChatSession = {
                                        viewModel.createNewChatSession()
                                    },
                                    onDeleteChatSession = { sessionId ->
                                        viewModel.deleteChatSession(sessionId)
                                    },
                                    // AI exclusion state
                                    isAiExcluded = isAiExcluded,
                                    onInputTextChange = { text ->
                                        viewModel.updateInputText(text)
                                    },
                                    onInputAttachmentsChange = { attachments ->
                                        viewModel.updateInputAttachments(attachments)
                                    },
                                    // Search and Filter Params
                                    searchQuery = searchQuery,
                                    onSearchQueryChange = { query ->
                                        viewModel.onSearchQueryChange(query)
                                    },
                                    selectedFilters = selectedFilters,
                                    onFilterToggle = { option ->
                                        viewModel.onFilterToggle(option)
                                    },
                                    onClearFilters = {
                                        viewModel.clearFilters()
                                    },
                                    // Audio player
                                    onPlayAudio = { track ->
                                        audioPlayerViewModel.playAudio(track)
                                    },
                                    // Theme management
                                    isDarkTheme = isDarkTheme,
                                    onToggleTheme = { isDark ->
                                        viewModel.setDarkTheme(isDark)
                                    },
                                    // Cache management
                                    cacheSizeBytes = cacheSizeBytes,
                                    onClearCache = {
                                        viewModel.clearCache()
                                    },
                                    isClearingCache = isClearingCache,
                                    // Tavily Web Search API
                                    tavilyApiKey = tavilyApiKey,
                                    onSetTavilyApiKey = { key ->
                                        viewModel.setTavilyApiKey(key)
                                    },
                                    // Shake sensitivity
                                    shakeSensitivity = shakeSensitivity,
                                    onShakeSensitivityChange = { value ->
                                        viewModel.setShakeSensitivity(value)
                                    },
                                    // GROQ key usage stats
                                    groqKeyUsageStats = groqKeyUsageStats,
                                    // Shake mode switch animation
                                    wasShakeTriggered = wasShakeTriggered,
                                    connectionStatus = connectionStatus,
                                    // Calendar management
                                    calendarEvents = calendarEvents,
                                    onAddCalendarEvent = { title, description, startTime, endTime, isAllDay, location, color, reminderMinutes, isPrivate ->
                                        viewModel.addCalendarEvent(
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
                                    },
                                    onUpdateCalendarEvent = { event ->
                                        viewModel.updateCalendarEvent(event)
                                    },
                                    onDeleteCalendarEvent = { eventId ->
                                        viewModel.deleteCalendarEvent(eventId)
                                    },
                                    bottomContentPadding = androidx.compose.animation.core.animateDpAsState(
                                        targetValue = if (isMiniPlayerVisible && !isFullPlayerVisible && WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) == 0) 84.dp else 0.dp,
                                        label = "PlayerPadding"
                                    ).value,
                                    externalSpeechState = globalSpeechState,
                                    speechResults = viewModel.speechResults,

                                    // Dynamic Models - Only Groq supported for refresh now
                                    onRefreshModels = { provider ->
                                        if (provider == com.example.smarty.data.local.AIProvider.GROQ) {
                                            viewModel.refreshGroqModels()
                                        }
                                    },
                                    getAvailableModels = { provider ->
                                        viewModel.getAvailableModels(provider)
                                    },
                                    // Track screen changes for shake gesture (only works on main screen)
                                    onScreenChange = { route ->
                                        viewModel.setCurrentScreen(route)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )



                                // Mini Audio Player overlay at bottom (Hide when keyboard is open)
                                val isKeyboardOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
                                AnimatedMiniPlayer(
                                    visible = isMiniPlayerVisible && !isFullPlayerVisible && !isKeyboardOpen,
                                    state = audioUiState,
                                    onPlayPauseClick = { audioPlayerViewModel.togglePlayPause() },
                                    onExpandClick = { audioPlayerViewModel.expandToFullPlayer() },
                                    onCloseClick = { audioPlayerViewModel.stop() },
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .navigationBarsPadding()
                                )

                                // Full Audio Player modal
                                if (isFullPlayerVisible) {
                                    FullAudioPlayer(
                                        state = audioUiState,
                                        sheetState = fullPlayerSheetState,
                                        onPlayPauseClick = { audioPlayerViewModel.togglePlayPause() },
                                        onSeek = { progress -> audioPlayerViewModel.seekTo(progress) },
                                        onDismiss = { audioPlayerViewModel.collapseToMiniPlayer() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Start shake detection when app is in foreground
        viewModel.startShakeDetection()
        // Start wake word detection (Vosk)
        viewModel.startWakeWordDetection()
        // Resume resource-intensive operations
        viewModel.resumeResourceIntensiveOperations()
    }

    override fun onPause() {
        super.onPause()
        // Stop shake detection to save battery when app is in background
        viewModel.stopShakeDetection()
        // Stop wake word detection to free mic and save battery
        viewModel.stopWakeWordDetection()
        // Pause resource-intensive operations
        viewModel.pauseResourceIntensiveOperations()
    }

    override fun onStop() {
        super.onStop()
        // Minimize background resources when app goes to background
        viewModel.minimizeBackgroundResources()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Handle incoming share intents safely (BUG-054 fix).
     * Wraps intent processing in try-catch to prevent crashes during edge cases
     * like rapid configuration changes or early lifecycle states.
     */
    private fun handleIntent(intent: Intent?) {
        try {
            when (intent?.action) {
                Intent.ACTION_SEND -> {
                    val mimeType = intent.type

                    // Always check for EXTRA_TEXT first - YouTube and many apps share URLs this way
                    // regardless of the mimeType they declare
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)

                    when {
                        // Text-based shares (URLs from YouTube, links, etc.)
                        sharedText != null -> {
                            viewModel.interceptShareForPreview(SharedContent(text = sharedText))
                        }
                        // File-based shares (images, documents, audio, etc.)
                        else -> {
                            val fileUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(Intent.EXTRA_STREAM)
                            }

                            fileUri?.let { uri ->
                                // Get file details
                                val fileName = getFileName(uri)
                                val fileSize = getFileSize(uri)

                                viewModel.interceptShareForPreview(
                                    SharedContent(
                                        fileUri = uri.toString(),
                                        fileName = fileName,
                                        mimeType = mimeType,
                                        fileSize = fileSize
                                    )
                                )
                            }
                        }
                    }
                }
                // Handle multiple files shared at once
                Intent.ACTION_SEND_MULTIPLE -> {
                    val mimeType = intent.type
                    val uriList: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                    }

                    uriList?.let { uris ->
                        if (uris.isNotEmpty()) {
                            val files = uris.map { uri ->
                                SharedFileInfo(
                                    fileUri = uri.toString(),
                                    fileName = getFileName(uri),
                                    mimeType = contentResolver.getType(uri) ?: mimeType,
                                    fileSize = getFileSize(uri)
                                )
                            }
                            viewModel.interceptShareForPreview(SharedContent(files = files))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error handling intent: ${e.message}", e)
            // Intent handling failed, but don't crash the app - user can still use it normally
        }
    }

    private fun getFileName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                } else null
            } ?: uri.lastPathSegment
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }

    private fun getFileSize(uri: Uri): Long? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Request all required permissions based on Android version.
     * - Android 13+ (API 33): Media permissions (READ_MEDIA_IMAGES, etc.) and POST_NOTIFICATIONS
     * - Android 12 and below: READ_EXTERNAL_STORAGE
     */
    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // RECORD_AUDIO - Required for wake word detection and speech recognition
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ - Need granular media permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            // Notification permission for Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-12 - Need READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        // Request all needed permissions at once
        if (permissionsToRequest.isNotEmpty()) {
            android.util.Log.d("Permissions", "Requesting: $permissionsToRequest")
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            android.util.Log.d("Permissions", "All permissions already granted")
        }
    }
}
