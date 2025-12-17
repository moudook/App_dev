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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.example.smarty.navigation.CogniNavHost
import com.example.smarty.ui.components.audio.AnimatedMiniPlayer
import com.example.smarty.ui.components.audio.FullAudioPlayer
import com.example.smarty.ui.theme.CogniTheme
import com.example.smarty.data.worker.CacheCleanupWorker
import com.example.smarty.viewmodel.AudioPlayerViewModel
import com.example.smarty.viewmodel.CogniViewModel
import com.example.smarty.viewmodel.SharedContent

class MainActivity : ComponentActivity() {
    private val viewModel: CogniViewModel by viewModels()
    private val audioPlayerViewModel: AudioPlayerViewModel by viewModels()

    // Permission launcher for multiple permissions
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Log permission results for debugging
        permissions.entries.forEach { entry ->
            android.util.Log.d("Permissions", "${entry.key}: ${if (entry.value) "GRANTED" else "DENIED"}")
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

        // Schedule periodic cache cleanup
        CacheCleanupWorker.schedule(this)

        // Handle share intent on launch
        handleIntent(intent)

        setContent {
            // Collect theme state at the top level for CogniTheme
            val isDarkThemeTop by viewModel.isDarkTheme.collectAsState()

            CogniTheme(darkTheme = isDarkThemeTop) {
                val navController = rememberNavController()
                val notes by viewModel.notes.collectAsState()
                val categories by viewModel.categories.collectAsState()
                val selectedNote by viewModel.selectedNote.collectAsState()
                val selectedCategory by viewModel.selectedCategory.collectAsState()
                val isProcessing by viewModel.isProcessing.collectAsState()

                // API Key states
                val providerConfigs by viewModel.providerConfigs.collectAsState()

                // PIN state
                val isPinConfigured by viewModel.isPinSet.collectAsState()

                // Pending share state
                val pendingShare by viewModel.pendingShare.collectAsState()
                val isShareFullPrivacy by viewModel.pendingShareFullPrivacy.collectAsState()

                // Archived notes
                val archivedNotes by viewModel.archivedNotes.collectAsState()

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

                // Refresh cache size when composable enters composition
                LaunchedEffect(Unit) {
                    viewModel.refreshCacheSize()
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    CogniNavHost(
                    navController = navController,
                    notes = notes,
                    archivedNotes = archivedNotes,
                    categories = categories,
                    selectedNote = selectedNote,
                    selectedCategory = selectedCategory,
                    isProcessing = isProcessing,
                    // API Key management
                    providerConfigs = providerConfigs,
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
                    onDeleteNote = { note ->
                        viewModel.deleteNote(note)
                    },
                    onDeleteNoteById = { noteId ->
                        viewModel.deleteNoteById(noteId)
                    },
                    onUpdateNoteTodos = { noteId, todos ->
                        viewModel.updateNoteTodos(noteId, todos)
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
                    modifier = Modifier.fillMaxSize()
                    )

                    // Mini Audio Player overlay at bottom
                    AnimatedMiniPlayer(
                        visible = isMiniPlayerVisible && !isFullPlayerVisible,
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

    override fun onResume() {
        super.onResume()
        // Start shake detection when app is in foreground
        viewModel.startShakeDetection()
    }

    override fun onPause() {
        super.onPause()
        // Stop shake detection to save battery when app is in background
        viewModel.stopShakeDetection()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
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
