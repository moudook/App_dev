package com.example.smarty.features.notes.ui

import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import com.example.smarty.R
import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.Category
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.MentionState
import com.example.smarty.core.domain.model.MentionSuggestion
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.TodoItem
import com.example.smarty.ui.LocalAccentColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import com.example.smarty.features.voice.RecordingState
import com.example.smarty.features.voice.formatDuration



import com.example.smarty.ui.animation.SmartyEasing
import com.example.smarty.ui.animation.StaggerCalculator
import com.example.smarty.core.domain.model.ChatSession
import com.example.smarty.ui.components.common.SmartyDialog
import com.example.smarty.ui.components.SmartyInputField
import com.example.smarty.ui.components.NoteTodoSheet
import com.example.smarty.core.domain.model.NoteType
import com.example.smarty.ui.components.AttachmentOption
import com.example.smarty.ui.components.SearchFilterTypeSelector
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.example.smarty.ui.components.PendingShareData
import com.example.smarty.ui.components.ShareBottomSheet
import com.example.smarty.ui.components.getNoteTypeIcon
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.SmartyShapes
import com.example.smarty.ui.theme.SmartyBrushes
import com.example.smarty.ui.theme.softCardShadow
import com.example.smarty.ui.components.ConnectionStatus
import com.example.smarty.ui.components.ConnectionStatusIndicator
import com.example.smarty.ui.components.CloudSyncIndicator
import com.example.smarty.ui.components.NotesEmptyState
import com.example.smarty.ui.components.SearchEmptyState
import com.example.smarty.ui.components.HorizontalActionBar
import com.example.smarty.core.domain.model.NavigationTab
import com.example.smarty.ui.components.PinterestAction
import com.example.smarty.ui.components.PinterestMenu
import androidx.compose.ui.geometry.Offset
import com.example.smarty.ui.components.SelectionFloatingBar
import com.example.smarty.ui.components.sheets.CalendarSheet
import com.example.smarty.ui.components.sheets.StacksSheet
import com.example.smarty.ui.components.sheets.ArchiveSheet
import com.example.smarty.ui.components.sheets.SettingsSheet
import com.example.smarty.ui.components.AnimatedCategoryFilterChip
import com.example.smarty.ui.components.AddEventDialog
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.features.notes.ui.inputstream.CalendarContent
import com.example.smarty.features.notes.ui.inputstream.ChatHistoryContent
import com.example.smarty.features.notes.ui.inputstream.ChatModeContent
import com.example.smarty.features.notes.ui.inputstream.NormalModeContent
import com.example.smarty.features.notes.ui.inputstream.SelectionModeToolbar
import com.example.smarty.features.notes.ui.inputstream.StacksContent
import com.example.smarty.features.notes.ui.inputstream.ArchiveContent
import com.example.smarty.features.notes.ui.inputstream.SettingsContent
import com.example.smarty.features.notes.ui.inputstream.GamesContent
import com.example.smarty.features.voice.SpeechToTextState
import com.example.smarty.features.voice.rememberSpeechToText
import com.example.smarty.core.domain.model.SmartyTimer
import com.example.smarty.features.calendar.domain.GoogleCalendarSyncManager.DeviceCalendar
import com.example.smarty.features.voice.VoiceNoteRecorder
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.History
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputStreamScreen(
    notes: List<Note>,
    categories: List<Category>,
    isProcessing: Boolean,
    onAddNote: (String, List<Attachment>) -> Unit,
    onNoteClick: (Note) -> Unit,
    onDeleteNote: (String) -> Unit,
    onArchiveNote: (String) -> Unit,
    onUnarchiveNote: (String) -> Unit,
    onBulkArchive: (List<String>) -> Unit = {},
    onUndoArchive: () -> Unit = {},
    onRefreshNotes: () -> Unit = {},
    isRefreshing: Boolean = false,
    isNotesLoading: Boolean = false,
    isStacksLoading: Boolean = false,
    isArchiveLoading: Boolean = false,
    isChatHistoryLoading: Boolean = false,
    onUpdateNoteTodos: (String, List<TodoItem>, onComplete: (() -> Unit)?) -> Unit,
    onUpdateNoteCategory: (String, String, String) -> Unit = { _, _, _ -> },
    onNavigateToStacks: () -> Unit,
    onNavigateToSettings: () -> Unit,
    selectedTab: NavigationTab = NavigationTab.NOTES,
    onSelectedTabChange: (NavigationTab) -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToTicTacToe: () -> Unit = {},
    onNavigateToCoinToss: () -> Unit = {},
    onNavigateToChess: () -> Unit = {},
    pendingShare: PendingShareData?,
    onConfirmShare: (String?, String) -> Unit,
    onCancelShare: () -> Unit,
    // Share privacy mode (shake to activate)
    isShareFullPrivacy: Boolean = false,
    // Chat mode parameters
    isChatMode: Boolean = false,
    chatMessages: List<ChatMessage> = emptyList(),
    isChatProcessing: Boolean = false,
    agentActivity: com.example.smarty.features.chat.domain.ChatFeatureManager.AgentActivity? = null,
    onEnterChatMode: () -> Unit = {},  // Enter chat mode when clicking AI tab
    onExitChatMode: () -> Unit = {},  // Back button handler for chat mode
    onSendChatMessage: (String, List<Attachment>) -> Unit = { _, _ -> },
    onClarificationSubmit: (String, String) -> Unit = { _, _ -> },
    pendingClarificationRequests: List<com.example.smarty.core.domain.model.ClarificationRequest> = emptyList(),
    pendingApprovalToolId: String? = null,
    onCallApproval: (String, Boolean, String?) -> Unit = { _, _, _ -> },
    onGenerateImageDirect: (String) -> Unit = {},  // Direct image generation via Krea API
    onStopGeneration: () -> Unit = {},
    // Chat history parameters
    chatSessions: List<ChatSession> = emptyList(),
    currentSessionId: String? = null,
    onSwitchChatSession: (String) -> Unit = {},
    onNewChatSession: () -> Unit = {},
    onDeleteChatSession: (String) -> Unit = {},
    onDeleteChatMessage: (String) -> Unit = {},
    onNoteClickById: (String) -> Unit = {},  // For NoteBlock cards in chat

    // @Mention parameters (Chat mode)
    mentionState: MentionState = MentionState(),
    onMentionSelected: (MentionSuggestion, String) -> String = { _, text -> text },  // Returns updated text
    // Pending chat text (for "Ask Smarty" from note card)
    pendingChatText: String? = null,
    onClearPendingChatText: () -> Unit = {},
    // Mention state update callback (called on text change in chat mode)
    onUpdateMentionState: (String, Int) -> Unit = { _, _ -> },
    // AI exclusion parameters
    isAiExcluded: Boolean = false,
    onInputTextChange: (String) -> Unit = {},
    // HOISTED STATE: Input attachments from ViewModel (Persistence Fix)
    currentInputAttachments: List<Attachment> = emptyList(),
    onInputAttachmentsChange: (List<Attachment>) -> Unit = {},

    // HOISTED STATE: Selected Category Filter (Performance Fix)
    selectedCategory: Category? = null,
    onSelectCategory: (Category?) -> Unit = {},
    onPlayYouTube: (String) -> Unit = {},
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    isMiniPlayerVisible: Boolean = false,
    bottomGradientVerticalOffset: androidx.compose.ui.unit.Dp = 60.dp,
    externalSpeechState: SpeechToTextState? = null,
    speechResults: kotlinx.coroutines.flow.Flow<String>? = null,

    // Search and Filter (Backend Integration)
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    selectedFilters: Set<AttachmentOption> = emptySet(),
    onFilterToggle: (AttachmentOption) -> Unit = {},
    onClearFilters: () -> Unit = {},

    // Search History (BATCH 5C)
    recentSearches: List<String> = emptyList(),
    onRecordSearch: (String) -> Unit = {},
    onClearSearchHistory: () -> Unit = {},

    wasShakeTriggered: Boolean = false,  // Deprecated: shake indicator removed, kept for API compatibility
    connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTED,  // Phase 7
    cloudSyncState: com.example.smarty.features.notes.domain.SmartyViewModel.CloudSyncState = com.example.smarty.features.notes.domain.SmartyViewModel.CloudSyncState.Idle,
    onSyncCloud: () -> Unit = {},
    onSyncCloudSilent: () -> Unit = {},
    syncSnackbarMessage: kotlinx.coroutines.flow.SharedFlow<String> = kotlinx.coroutines.flow.MutableSharedFlow(),
    // Camera trigger from widget
    cameraTriggered: Boolean = false,
    onClearCameraTrigger: () -> Unit = {},
    // Pin and Share callbacks
    onPinNote: (String) -> Unit = {},
    onUnpinNote: (String) -> Unit = {},
    onShareNotes: (List<Note>) -> Unit = {},

    // 
    // CENTRALIZED UI: All features accessible from main screen via bottom sheets
    // 

    // Theme toggle (always visible in header)
    isDarkTheme: Boolean = false,
    onToggleTheme: (Boolean) -> Unit = {},

    // Archived notes for Archive sheet
    archivedNotes: List<Note> = emptyList(),

    // Calendar events for Calendar sheet
    calendarEvents: List<CalendarEvent> = emptyList(),
    activeTimers: List<SmartyTimer> = emptyList(),
    onAddCalendarEvent: () -> Unit = {},
    onCreateCalendarEvent: ((title: String, description: String?, startTime: Long, endTime: Long, isAllDay: Boolean) -> Unit)? = null,
    onEventClick: (CalendarEvent) -> Unit = {},
    onEventClickById: (String) -> Unit = {},
    onDeleteCalendarEvent: (CalendarEvent) -> Unit = {},

    // Category management for Stacks sheet
    onCreateCategory: (String) -> Unit = {},
    onRenameCategory: (Category, String) -> Unit = { _, _ -> },
    onDeleteCategory: (Category) -> Unit = {},
    onCategoryClick: (Category) -> Unit = {},
    onSyncCategoryCounts: () -> Unit = {},  // Sync category counts when entering stacks view

    // Settings props
    cacheSizeBytes: Long = 0L,
    onClearCache: () -> Unit = {},
    isClearingCache: Boolean = false,
    shakeSensitivity: Float = 0.63f,
    onShakeSensitivityChange: (Float) -> Unit = {},
    onSignOut: () -> Unit = {},
    // Settings sub-sheet content
    backupContent: @Composable (onDismiss: () -> Unit) -> Unit = {},
    // Google Calendar Two-Way Sync
    isCalendarSyncEnabled: Boolean = false,
    onSetCalendarSyncEnabled: (Boolean) -> Unit = {},
    deviceCalendars: List<DeviceCalendar> = emptyList(),
    targetCalendarId: Long = -1L,
    onSetTargetCalendarId: (Long) -> Unit = {},
    onLoadDeviceCalendars: () -> Unit = {},

    // Shake Blocking
    onSetShakeBlocked: (Boolean) -> Unit = {},

    // Dynamic Model Selection
    selectedModel: String = "opencode/deepseek-v4-flash-free",
    availableModels: List<Pair<String, String>> = emptyList(),
    onModelSelected: (String) -> Unit = {},
    modelVariantMap: Map<String, List<String>> = emptyMap(),
    selectedVariant: String? = null,
    onVariantSelected: (String?) -> Unit = {},
    onRefreshModels: suspend () -> List<Pair<String, String>> = { emptyList() },

    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Separate text states for normal mode and chat mode
    // This preserves text independently when switching between modes via shake
    var normalModeTextValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var chatModeTextValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    // Derived current text based on mode
    val textValue = if (isChatMode) chatModeTextValue else normalModeTextValue

    // Auto-send state for chat mode - triggers 0.4s after speech stops
    var autoSendActive by remember { mutableStateOf(false) }
    var autoSendJob by remember { mutableStateOf<Job?>(null) }

    // Track if we received speech input (for auto-send triggering)
    var hadSpeechInput by remember { mutableStateOf(false) }

    // Partial text tracking for progressive speech append
    var lastPartialText by remember { mutableStateOf("") }
    var partialTextStartIndex by remember { mutableIntStateOf(0) }

    // Sync ViewModel's input text whenever the text value changes
    // This ensures shake detection always uses the current text state
    // Critical for shake-to-toggle: ViewModel must know if input is truly empty
    LaunchedEffect(textValue.text, isChatMode) {
        onInputTextChange(textValue.text)
    }

    // Sync attachments to ViewModel for shake detection
    // When attachments change, notify ViewModel so shake can trigger privacy mode
    // PERSISTENCE FIX: State is now hoisted to ViewModel, passed in via currentInputAttachments
    // Removed local 'attachments' state to prevent data loss on rotation

    // 
    // @MENTION: Consume pending chat text (from "Ask AI" button on note cards)
    // 
    LaunchedEffect(pendingChatText) {
        if (pendingChatText != null && pendingChatText.isNotBlank()) {
            // Set the pending text to chat input
            chatModeTextValue = TextFieldValue(
                text = pendingChatText,
                selection = androidx.compose.ui.text.TextRange(pendingChatText.length)
            )
            // Clear the pending text so it doesn't re-trigger
            onClearPendingChatText()
        }
    }

    val gridState = rememberLazyStaggeredGridState()
    val chatListState = rememberLazyListState()

    // Snackbar state for undo archive
    val snackbarHostState = remember { SnackbarHostState() }
    var lastArchivedNoteId by remember { mutableStateOf<String?>(null) }

    // Sync snackbar messages
    LaunchedEffect(Unit) {
        syncSnackbarMessage.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    // Accent color for theming
    val accentColor = LocalAccentColor.current

    // 
    // CENTRALIZED UI: Bottom Sheet States
    // 
    val activeTab = if (isChatMode) NavigationTab.CHAT else selectedTab

    var showCalendarSheet by remember { mutableStateOf(false) }
    val calendarSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Calendar inline view state (shows calendar in main area instead of sheet)
    var showCalendarInline by remember { mutableStateOf(false) }

    var showStacksSheet by remember { mutableStateOf(false) }
    val stacksSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Stacks inline view state
    var showStacksInline by remember { mutableStateOf(false) }

    var showArchiveSheet by remember { mutableStateOf(false) }
    val archiveSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Archive inline view state
    var showArchiveInline by remember { mutableStateOf(false) }

    var showSettingsSheet by remember { mutableStateOf(false) }
    val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Settings inline view state
    var showSettingsInline by remember { mutableStateOf(false) }

    // Games inline view state
    var showGamesInline by remember { mutableStateOf(false) }

    // Chat history inline view state (shows history in main area instead of bottom sheet)
    var showChatHistoryInline by remember { mutableStateOf(false) }

    // Block shake when any inline view is active (Settings, Calendar, Stacks, Archive, History, Games)
    // This prevents accidental navigation or data loss while editing in these views
    LaunchedEffect(showSettingsInline, showCalendarInline, showStacksInline, showArchiveInline, showChatHistoryInline, showGamesInline) {
        val isInlineViewActive = showSettingsInline || showCalendarInline || showStacksInline || showArchiveInline || showChatHistoryInline || showGamesInline
        onSetShakeBlocked(isInlineViewActive)
    }

    // Navigation Stack for proper linear back behavior across tabs
    var tabStackRoutes by rememberSaveable { mutableStateOf(listOf(NavigationTab.NOTES.name)) }

    // Sync external changes (deep links, back presses, or AI triggers) into the stack and UI state
    LaunchedEffect(selectedTab, isChatMode) {
        val currentActive = if (isChatMode) NavigationTab.CHAT.name else selectedTab.name
        
        if (tabStackRoutes.lastOrNull() != currentActive) {
            tabStackRoutes = tabStackRoutes.filter { it != currentActive } + currentActive
        }
        
        // Sync inline views based on currentActive
        showCalendarInline = currentActive == NavigationTab.CALENDAR.name
        showStacksInline = currentActive == NavigationTab.STACKS.name
        showArchiveInline = currentActive == NavigationTab.ARCHIVE.name
        showSettingsInline = currentActive == NavigationTab.SETTINGS.name
        showGamesInline = currentActive == NavigationTab.GAMES.name
        
        if (currentActive != NavigationTab.CHAT.name) {
            showChatHistoryInline = false
        }
    }

    // Auto-sync silently when user navigates to NOTES tab
    LaunchedEffect(selectedTab) {
        if (selectedTab == NavigationTab.NOTES && !isChatMode) {
            onSyncCloudSilent()
        }
    }

    // Handle tab selection - open sheets or switch modes with proper stack queuing
    fun handleTabSelection(tab: NavigationTab) {
        if (tab.name == tabStackRoutes.lastOrNull()) {
            // Toggle off behavior: drop current tab and return to previous, or default to NOTES
            if (tabStackRoutes.size > 1) {
                tabStackRoutes = tabStackRoutes.dropLast(1)
            } else if (tab != NavigationTab.NOTES) {
                tabStackRoutes = listOf(NavigationTab.NOTES.name)
            }
        } else {
            // Bring tab to the front of the queue
            tabStackRoutes = tabStackRoutes.filter { it != tab.name } + tab.name
        }
        
        val newTab = NavigationTab.valueOf(tabStackRoutes.last())
        
        if (newTab == NavigationTab.CHAT) {
            if (!isChatMode) onEnterChatMode()
        } else {
            if (isChatMode) onExitChatMode()
            onSelectedTabChange(newTab)
        }
    }

    // Voice Input State (Speech-to-Text) - Use external from MainActivity
    val speechState = externalSpeechState ?: rememberSpeechToText(
        onResult = { /* Handled by global flow */ }
    )

    // Voice Recording State (Long-press on mic to record audio note)
    val voiceRecorder = remember { VoiceNoteRecorder(context, scope) }
    val recordingState by voiceRecorder.state.collectAsState()
    val isRecording = recordingState is RecordingState.Recording


    // Handle recording completion - add as audio attachment
    LaunchedEffect(recordingState) {
        when (val state = recordingState) {
            is RecordingState.Completed -> {
                // Add the recorded audio as an attachment
                val file = java.io.File(state.filePath)
                val uri = android.net.Uri.fromFile(file)
                val newAttachment = Attachment(
                    id = java.util.UUID.randomUUID().toString(),
                    uri = uri.toString(),
                    mimeType = "audio/mp4",
                    fileName = file.name,
                    fileSize = file.length()
                )
                onInputAttachmentsChange(currentInputAttachments + newAttachment)
                voiceRecorder.reset()
            }
            else -> { /* Other states handled by UI */ }
        }
    }


    // Cancel recording on dispose if still active
    DisposableEffect(Unit) {
        onDispose {
            if (voiceRecorder.state.value is RecordingState.Recording) {
                voiceRecorder.cancelRecording()
            }
        }
    }


    // Handle partial results for progressive text append
    // Use the mode that INITIATED the speech, not the current mode
    LaunchedEffect(speechState) {
        speechState.onPartialResult = { partialText ->
            if (partialText.isNotBlank()) {
                hadSpeechInput = true  // Mark that we got speech input

                // Use the mode that initiated speech to prevent cross-mode text insertion
                val targetChatMode = speechState.speechInitiatedInChatMode ?: isChatMode

                val currentTextValue = if (targetChatMode) chatModeTextValue else normalModeTextValue
                val currentText = currentTextValue.text

                // If this is the first partial, record where we're inserting
                if (lastPartialText.isEmpty()) {
                    partialTextStartIndex = currentText.length
                    // Add space if needed before the partial text
                    if (currentText.isNotEmpty() && !currentText.endsWith(" ")) {
                        val spacedText = "$currentText "
                        partialTextStartIndex = spacedText.length
                        if (targetChatMode) {
                            chatModeTextValue = TextFieldValue(spacedText, TextRange(spacedText.length))
                        } else {
                            normalModeTextValue = TextFieldValue(spacedText, TextRange(spacedText.length))
                        }
                    }
                }

                // Replace the previous partial with the new one
                // Bounds check: ensure index doesn't exceed current text length
                val safeStartIndex = partialTextStartIndex.coerceAtMost(currentText.length)
                val baseText = currentText.take(safeStartIndex)
                val newText = baseText + partialText
                val newValue = TextFieldValue(newText, TextRange(newText.length))

                if (targetChatMode) {
                    chatModeTextValue = newValue
                } else {
                    normalModeTextValue = newValue
                }

                lastPartialText = partialText
                onInputTextChange(newText)
            }
        }
    }

    // Observe global speech results (final) and update local text fields
    // Use the mode that INITIATED the speech, not the current mode
    LaunchedEffect(speechResults) {
        speechResults?.collect { result ->
            // Final result replaces any partial text
            hadSpeechInput = true

            // Use the mode that initiated speech to prevent cross-mode text insertion
            val targetChatMode = speechState.speechInitiatedInChatMode ?: isChatMode

            val currentTextValue = if (targetChatMode) chatModeTextValue else normalModeTextValue
            val currentText = currentTextValue.text

            // Use the base text before any partial results
            // Bounds check: ensure index doesn't exceed current text length
            val baseText = if (lastPartialText.isNotEmpty()) {
                val safeIndex = partialTextStartIndex.coerceAtMost(currentText.length)
                currentText.take(safeIndex)
            } else {
                val spacer = if (currentText.isNotEmpty() && !currentText.endsWith(" ")) " " else ""
                currentText + spacer
            }

            val newText = baseText + result
            val newValue = TextFieldValue(newText, TextRange(newText.length))

            if (targetChatMode) {
                chatModeTextValue = newValue
            } else {
                normalModeTextValue = newValue
            }

            // Reset partial tracking
            lastPartialText = ""
            partialTextStartIndex = 0

            // Reset speech initiation mode after processing
            speechState.speechInitiatedInChatMode = null


            onInputTextChange(newText)
        }
    }

    // Auto-send in chat mode: 0.4s after speech recognition stops
    LaunchedEffect(speechState.isListening, isChatMode) {
        if (!speechState.isListening && isChatMode && hadSpeechInput) {
            autoSendActive = true
            autoSendJob?.cancel()
            autoSendJob = scope.launch {
                delay(100)  // Reduced to 0.1 seconds for faster auto-send
                if (autoSendActive) {
                    val finalChatText = chatModeTextValue.text
                    if (finalChatText.isNotBlank()) {
                        onSendChatMessage(finalChatText, currentInputAttachments)
                        chatModeTextValue = TextFieldValue("")
                        onInputAttachmentsChange(emptyList())
                        onInputTextChange("")
                        autoSendActive = false
                        hadSpeechInput = false
                        lastPartialText = ""
                        partialTextStartIndex = 0
                    }
                }
            }
        } else if (speechState.isListening) {
            // Cancel auto-send if user starts speaking again
            autoSendActive = false
            autoSendJob?.cancel()
        }
    }

    // Multi-select state
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedNoteIds by remember { mutableStateOf(setOf<String>()) }

    // Pinterest-style menu state
    var pinterestMenuPosition by remember { mutableStateOf<Offset?>(null) }
    var activePinterestNoteId by remember { mutableStateOf<String?>(null) }

    // Search Mode State
    var isSearchMode by remember { mutableStateOf(false) }
    
    // Image Generation Mode State
    var isImageGenMode by remember { mutableStateOf(false) }

    // Filtered Notes Logic
    // OPTIMIZATION: Filter logic moved to ViewModel (SmartyViewModel.notes)
    // The 'notes' list passed here is already filtered by the ViewModel based on:
    // 1. Search query
    // 2. Selected filters
    // 3. Selected category (via onSelectCategory)
    // We use the list directly to avoid main-thread filtering
    val displayedNotes = notes

    // Selection handlers
    fun toggleSelection(noteId: String) {
        selectedNoteIds = if (noteId in selectedNoteIds)
            selectedNoteIds - noteId
        else {
            isSelectionMode = true  // Enable selection mode when adding
            selectedNoteIds + noteId
        }
        if (selectedNoteIds.isEmpty()) isSelectionMode = false
    }

    fun clearSelection() {
        selectedNoteIds = emptySet()
        isSelectionMode = false
    }

    fun archiveSelected() {
        val ids = selectedNoteIds.toList()
        val count = ids.size
        onBulkArchive(ids)
        clearSelection()
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = context.getString(R.string.notes_archived, ids.size),
                actionLabel = context.getString(R.string.undo),
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                onUndoArchive()
            }
        }
    }

    // Select all visible notes (or deselect all if already all selected)
    fun selectAllNotes() {
        val allNoteIds = displayedNotes.map { it.id }.toSet()
        if (selectedNoteIds == allNoteIds) {
            // All are selected - deselect all
            selectedNoteIds = emptySet()
            isSelectionMode = false
        } else {
            // Not all selected - select all
            selectedNoteIds = allNoteIds
            isSelectionMode = true
        }
    }

    // Delete selected notes with undo capability
    fun deleteSelected() {
        val idsToDelete = selectedNoteIds.toList()
        idsToDelete.forEach { onDeleteNote(it) }
        val count = idsToDelete.size
        clearSelection()
        scope.launch {
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.notes_deleted, count),
                duration = SnackbarDuration.Short
            )
        }
    }

    // Pin selected notes
    fun pinSelected() {
        val ids = selectedNoteIds.toList()
        ids.forEach { onPinNote(it) }
        val count = ids.size
        clearSelection()
        scope.launch {
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.notes_pinned, count),
                duration = SnackbarDuration.Short
            )
        }
    }

    // Categorize selected notes
    fun categorizeSelected(categoryId: String, categoryName: String) {
        val ids = selectedNoteIds.toList()
        ids.forEach { onUpdateNoteCategory(it, categoryId, categoryName) }
        val count = ids.size
        clearSelection()
        scope.launch {
            snackbarHostState.showSnackbar(
                message = "Notes categorized ($count)",
                duration = SnackbarDuration.Short
            )
        }
    }

    // Share selected notes
    fun shareSelected() {
        val selectedNotes = displayedNotes.filter { it.id in selectedNoteIds }
        if (selectedNotes.isNotEmpty()) {
            onShareNotes(selectedNotes)
        }
        clearSelection()
    }

    // Handle back button press - exit selection mode instead of closing app
    BackHandler(enabled = isSelectionMode) {
        clearSelection()
    }

    // Handle back button press - exit history view first
    BackHandler(enabled = isChatMode && showChatHistoryInline && !isSelectionMode) {
        showChatHistoryInline = false
    }

    // Handle back button press - linear queue navigation across tabs
    BackHandler(enabled = tabStackRoutes.size > 1 && !isSelectionMode && !showChatHistoryInline) {
        tabStackRoutes = tabStackRoutes.dropLast(1)
        val target = NavigationTab.valueOf(tabStackRoutes.last())
        if (target == NavigationTab.CHAT) {
            if (!isChatMode) onEnterChatMode()
        } else {
            if (isChatMode) onExitChatMode()
            onSelectedTabChange(target)
        }
    }

    // Handle back button press - clear active category filter instead of closing app
    val isOnlyAtRoot = tabStackRoutes.size <= 1 && !isChatMode && !showChatHistoryInline
    BackHandler(enabled = selectedCategory != null && !isSelectionMode && isOnlyAtRoot) {
        onSelectCategory(null)
    }

    // Add event dialog state for inline calendar
    var showAddEventDialog by remember { mutableStateOf(false) }
    var selectedDateForNewEvent by remember { mutableStateOf<java.util.Calendar?>(null) }

    // Todo sheet state - store ID only and derive note from notes list to stay in sync
    var selectedNoteIdForTodo by remember { mutableStateOf<String?>(null) }
    val selectedNoteForTodo = selectedNoteIdForTodo?.let { id -> notes.find { it.id == id } }
    val todoSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Delete confirmation state - store ID only and derive note from list to stay in sync
    var noteToDeleteId by remember { mutableStateOf<String?>(null) }
    val noteToDelete = noteToDeleteId?.let { id -> notes.find { it.id == id } }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCategorizeDialog by remember { mutableStateOf(false) }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }

    // MIME type detection from file extension (fallback when ContentResolver fails)
    fun getMimeTypeFromExtension(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            // Images
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "heic", "heif" -> "image/heic"
            // Videos
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            // Audio
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "wma" -> "audio/x-ms-wma"
            // Documents
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt" -> "text/plain"
            "rtf" -> "application/rtf"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "md" -> "text/markdown"
            // Archives
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            // Other
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    // Helper function to get file info from URI with robust MIME type detection
    fun getFileInfo(uri: Uri): Attachment? {
        return try {
            var fileName: String? = null
            var fileSize: Long = 0

            // Query file metadata from ContentResolver
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) fileName = cursor.getString(nameIndex)

                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }
            }

            // Safe fallback chain for filename - never force unwrap
            val safeName = fileName
                ?: uri.lastPathSegment
                ?: "Unknown_${System.currentTimeMillis()}"

            // Robust MIME type detection:
            // 1. Try ContentResolver first
            // 2. Fall back to extension-based detection if null or generic
            val contentResolverMime = context.contentResolver.getType(uri)
            val mimeType = when {
                // ContentResolver returned a specific type (not generic)
                contentResolverMime != null &&
                contentResolverMime != "application/octet-stream" &&
                contentResolverMime != "binary/octet-stream" -> contentResolverMime
                // Fall back to extension-based detection
                else -> getMimeTypeFromExtension(safeName)
            }

            android.util.Log.d("AttachmentPicker", "File: $safeName, MIME: $mimeType (ContentResolver: $contentResolverMime)")

            Attachment(
                id = java.util.UUID.randomUUID().toString(),
                uri = uri.toString(),
                fileName = safeName,
                mimeType = mimeType,
                fileSize = fileSize
            )
        } catch (e: Exception) {
            android.util.Log.e("AttachmentPicker", "Failed to get file info: ${e.message}", e)
            null
        }
    }

    // File picker launchers - using modern contracts for better MIME type handling

    // Image picker - uses PickMultipleVisualMedia for reliable image selection
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        uris.mapNotNull { getFileInfo(it) }.let { newAttachments ->
            onInputAttachmentsChange(currentInputAttachments + newAttachments)
        }
    }

    // Video picker - uses PickMultipleVisualMedia with VideoOnly filter
    val videoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        uris.mapNotNull { getFileInfo(it) }.let { newAttachments ->
            onInputAttachmentsChange(currentInputAttachments + newAttachments)
        }
    }

    // Audio picker - uses OpenMultipleDocuments with audio MIME types
    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.mapNotNull { getFileInfo(it) }.let { newAttachments ->
            onInputAttachmentsChange(currentInputAttachments + newAttachments)
        }
    }

    // Document picker - uses OpenMultipleDocuments for documents
    val documentPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.mapNotNull { getFileInfo(it) }.let { newAttachments ->
            onInputAttachmentsChange(currentInputAttachments + newAttachments)
        }
    }

    // Generic file picker - uses OpenMultipleDocuments for any file type
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.mapNotNull { getFileInfo(it) }.let { newAttachments ->
            onInputAttachmentsChange(currentInputAttachments + newAttachments)
        }
    }

    // Camera capture - takes a photo and saves to file
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraImageUri != null) {
            getFileInfo(cameraImageUri!!)?.let { attachment ->
                onInputAttachmentsChange(currentInputAttachments + attachment)
            }
        }
    }

    // Helper function to create a temp file for camera capture
    fun createImageFile(): Uri? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val imageFileName = "SMARTY_${timeStamp}"
            val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val imageFile = File.createTempFile(imageFileName, ".jpg", storageDir)
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                imageFile
            )
        } catch (e: Exception) {
            null
        }
    }

    // Handle camera trigger from widget - auto-open image picker
    LaunchedEffect(cameraTriggered) {
        if (cameraTriggered) {
            // Launch the image picker when widget camera button is tapped
            imagePickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
            // Clear the trigger so it doesn't fire again
            onClearCameraTrigger()
        }
    }

    // Scroll to top when new note is added
    LaunchedEffect(notes.size) {
        if (notes.isNotEmpty() && !isChatMode) {
            gridState.animateScrollToItem(0)
        }
    }

    val coroutineScope = rememberCoroutineScope()

    // --- Chat Scrolling State Management ---
    var autoScrollEnabled by remember { mutableStateOf(true) }
    var userIsScrolling by remember { mutableStateOf(false) }
    
    // When the user interacts with the list (dragging or flinging), temporarily disable auto-scroll
    LaunchedEffect(chatListState.isScrollInProgress, gridState.isScrollInProgress) {
        val isScrolling = chatListState.isScrollInProgress || gridState.isScrollInProgress
        if (isScrolling) {
            kotlinx.coroutines.delay(150) // Delay before starting hide animation
            userIsScrolling = true
            autoScrollEnabled = false
        } else {
            kotlinx.coroutines.delay(600) // Delay before starting show animation
            userIsScrolling = false
        }
    }

    // Determine if we need to show the scroll-to-bottom FAB
    val showScrollToBottomFab by remember {
        androidx.compose.runtime.derivedStateOf {
            if (chatMessages.isEmpty()) return@derivedStateOf false
            
            val layoutInfo = chatListState.layoutInfo
            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            if (visibleItemsInfo.isEmpty()) return@derivedStateOf false
            
            val lastVisibleItem = visibleItemsInfo.last()
            
            // Check if the bottommost item is visible and fully scrolled
            if (lastVisibleItem.index == layoutInfo.totalItemsCount - 1) {
                val bottomOffset = lastVisibleItem.offset + lastVisibleItem.size
                // Allow a small padding buffer of 100px
                bottomOffset > layoutInfo.viewportEndOffset + 100
            } else {
                // There are still items entirely below the viewport
                true
            }
        }
    }

    // Perform auto-scrolling when new messages arrive or when streaming updates text length
    LaunchedEffect(chatMessages.size, chatMessages.lastOrNull()?.content?.length, isChatProcessing) {
        // ONLY scroll if the user hasn't explicitly scrolled up and disabled autoScroll
        if (chatMessages.isNotEmpty() && isChatMode && autoScrollEnabled && !userIsScrolling) {
            // VERY IMPORTANT: Give Compose time to re-measure so totalItemsCount gets updated 
            // after the new message was injected. Otherwise we scroll to the old bottom which moves UP!
            kotlinx.coroutines.delay(100)
            
            if (chatListState.layoutInfo.totalItemsCount > 0) {
                // Scroll to NEWEST at the TOP (index 0) with a large negative offset to hit the edge
                chatListState.animateScrollToItem(0, scrollOffset = -10000)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
    ) {
        // Root-level gradient that spans behind status bar (edge-to-edge)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = if (isDarkTheme) SmartyBrushes.topScrimDark else SmartyBrushes.topScrimLight)
        )

        // Content Box with transparent background to let gradient show through
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
        ) {
        Scaffold(
        modifier = Modifier.imePadding(), // This handles keyboard
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                // Compact snackbar positioned above floating input field
                Surface(
                    modifier = Modifier
                        .padding(bottom = 120.dp, start = 16.dp, end = 16.dp) // Above input field
                        .wrapContentWidth(),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = data.visuals.message.lowercase(),
                            style = MaterialTheme.typography.labelMedium
                        )
                        
                        if (lastArchivedNoteId != null) {
                            Surface(
                                onClick = {
                                    lastArchivedNoteId?.let { noteId ->
                                        onUnarchiveNote(noteId)
                                        lastArchivedNoteId = null
                                    }
                                    data.dismiss()
                                },
                                shape = LocalShapes.current.chipSmall,
                                color = LocalAccentColor.current.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = stringResource(R.string.undo),
                                    color = LocalAccentColor.current,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
},
        topBar = {
// 
            // CENTRALIZED UI: Minimal Header + Horizontal Action Bar
            // 
            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            // 1. Master Orchestrator for Scroll State
            val scrollTransition = updateTransition(
                targetState = userIsScrolling, 
                label = "TopBarScroll"
            )
            
            // 2. Opacity Fade
            val topBarAlpha by scrollTransition.animateFloat(
                transitionSpec = { spring(dampingRatio = 0.8f, stiffness = 150f) },
                label = "topBarAlpha"
            ) { isScrolling -> if (isScrolling) 0f else 1f }
            
            // 3. Y-Axis Slide (in Pixels, not Dp, for raw GPU translation)
            val topBarTranslationY by scrollTransition.animateFloat(
                transitionSpec = { spring(dampingRatio = 0.75f, stiffness = 100f) },
                label = "topBarTranslationY"
            ) { isScrolling -> if (isScrolling) -250f else 0f } // -250f pixels pushes it up off-screen

            val topGradientTranslationY by scrollTransition.animateFloat(
                transitionSpec = { spring(dampingRatio = 0.75f, stiffness = 100f) },
                label = "topGradientTranslationY"
            ) { isScrolling -> if (isScrolling) -120f else 0f } // Increased shift up

            val topGradientBrush = if (isDarkTheme) {
                SmartyBrushes.topScrimDark
            } else {
                SmartyBrushes.topScrimLight
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = topGradientTranslationY }
                    .background(brush = topGradientBrush)
                    .zIndex(2f)
            ) {
                // Minimal Header Row: Status Indicators
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(24.dp), // Reduced from 32dp
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ConnectionStatusIndicator(
                            status = connectionStatus
                        )
                        CloudSyncIndicator(
                            syncState = cloudSyncState,
                            onSyncClick = onSyncCloud
                        )
                    }
                }

                // Horizontal Action Bar - All features accessible here
                HorizontalActionBar(
                    modifier = Modifier.graphicsLayer {
                        // S-TIER FIX: GPU Hardware Acceleration. Zero Layout Recalculation on Scroll!
                        alpha = topBarAlpha
                        translationY = topBarTranslationY
                    },
                    selectedTab = when {
                        showStacksInline -> NavigationTab.STACKS
                        showArchiveInline -> NavigationTab.ARCHIVE
                        showSettingsInline -> NavigationTab.SETTINGS
                        showGamesInline -> NavigationTab.GAMES
                        showCalendarInline -> NavigationTab.CALENDAR
                        isChatMode -> NavigationTab.CHAT
                        else -> selectedTab
                    },
                    onTabSelected = { tab -> handleTabSelection(tab) },
                    isChatMode = isChatMode,
                    isHistoryMode = showChatHistoryInline,
                    isCalendarMode = showCalendarInline,
                    isStacksMode = showStacksInline,
                    isArchiveMode = showArchiveInline,
                    isSettingsMode = showSettingsInline,
                    archiveCount = archivedNotes.size
                )

                // FILTER CHIP REMOVED: User requested removal of "philtre option"
                // Filtering is now handled exclusively via search input text
            } // End Column
        },
        bottomBar = {
            // Deprecated: Input field moved to main Box for floating transparency
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        // Clear focus when tapping outside the input field
        val topPadding = paddingValues.calculateTopPadding()

        Box(
            modifier = Modifier
                .fillMaxSize()
                // .padding(top = topPadding) -- REMOVED to allow scrolling behind header
                .pointerInput(speechState.isListening, isChatMode) {
                    detectTapGestures(
                        onTap = {
                            // Clear focus and stop voice input on tap
                            // Scrolling does NOT trigger this - only deliberate taps
                            focusManager.clearFocus()
                            if (speechState.isListening) {
                                speechState.stopListening()
                            }
                        }
                    )
                }
        ) {
            // Determine which content to show
            // Priority: Stacks > Archive > Settings > Calendar > Chat > Notes
            val contentMode = when {
                showStacksInline -> "stacks"
                showArchiveInline -> "archive"
                showSettingsInline -> "settings"
                showGamesInline -> "games"
                showCalendarInline -> "calendar"
                isChatMode -> "chat"
                else -> "notes"
            }

            // Animated content switching between notes, chat, and calendar
            // Wrapped in Box for centered layout
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                AnimatedContent(
                    targetState = contentMode,
                    transitionSpec = {
                        // Determine movement direction based on mode index
                        val modes = listOf("chat", "notes", "calendar", "stacks", "archive", "settings", "games")
                        val targetIndex = modes.indexOf(targetState)
                        val initialIndex = modes.indexOf(initialState)

                        // User specified: Notes -> Chat is "clockwise"
                        // In our list: Chat (0), Notes (1)
                        // So if targetIndex < initialIndex, it's clockwise (Forward)
                        // Except for wrapping? No, let's keep it simple for now.

                        val isClockwise = targetIndex < initialIndex

                        // S-Tier Physics for Screen Push/Pop
                        val screenSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 350f)
                        val slideSpring = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.8f, stiffness = 350f)

                        if (isClockwise) {
                            (scaleIn(initialScale = 0.92f, animationSpec = screenSpring) +
                             fadeIn(tween(250)) +
                             slideInVertically(animationSpec = slideSpring) { it / 15 }) togetherWith
                            (scaleOut(targetScale = 1.08f, animationSpec = screenSpring) +
                             fadeOut(tween(200)))
                        } else {
                            (scaleIn(initialScale = 1.08f, animationSpec = screenSpring) +
                             fadeIn(tween(250)) +
                             slideInVertically(animationSpec = slideSpring) { -it / 15 }) togetherWith
                            (scaleOut(targetScale = 0.92f, animationSpec = screenSpring) +
                             fadeOut(tween(200)))
                        } using SizeTransform { _, _ -> 
                            // Ensures container boundaries morph fluidly between screens
                            spring(dampingRatio = 0.75f, stiffness = 400f) 
                        }
                    },
                    label = "contentModeTransition",
                    modifier = Modifier
                        .fillMaxSize()
                        .align(Alignment.TopCenter) // Explicitly center content area
                ) { mode ->
                    val contentPaddingWithTop = PaddingValues(
                        top = ComponentSpacing.listContentPadding + topPadding, // Start content below header
                        bottom = 140.dp + bottomContentPadding // Extra padding for floating input
                    )

                when (mode) {
                    "stacks" -> {
                        // Stacks inline view - categories grid
                        StacksContent(
                            categories = categories,
                            isLoading = isStacksLoading,
                            onCategoryClick = { category ->
                                onSelectCategory(category)
                                showStacksInline = false  // Close and filter notes
                            },
                            onCreateCategory = onCreateCategory,
                            onRenameCategory = onRenameCategory,
                            onDeleteCategory = onDeleteCategory,
                            contentPadding = contentPaddingWithTop,
                            modifier = Modifier.fillMaxSize(),
                            onSyncCategoryCounts = onSyncCategoryCounts
                        )
                    }
                    "archive" -> {
                        // Archive inline view - archived notes
                        ArchiveContent(
                            archivedNotes = archivedNotes,
                            isLoading = isArchiveLoading,
                            onDeleteNote = onDeleteNote,
                            onUnarchiveNote = onUnarchiveNote,
                            contentPadding = contentPaddingWithTop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    "settings" -> {
                        // Settings inline view
                        SettingsContent(
                            isDarkTheme = isDarkTheme,
                            onToggleTheme = onToggleTheme,
                            cacheSizeBytes = cacheSizeBytes,
                            onClearCache = onClearCache,
                            isClearingCache = isClearingCache,
                            shakeSensitivity = shakeSensitivity,
                            onShakeSensitivityChange = onShakeSensitivityChange,
                            onSignOut = onSignOut,
                            backupContent = backupContent,
                            contentPadding = contentPaddingWithTop,
                            modifier = Modifier.fillMaxSize(),
                            isCalendarSyncEnabled = isCalendarSyncEnabled,
                            onSetCalendarSyncEnabled = onSetCalendarSyncEnabled,
                            deviceCalendars = deviceCalendars,
                            targetCalendarId = targetCalendarId,
                            onSetTargetCalendarId = onSetTargetCalendarId,
                            onLoadDeviceCalendars = onLoadDeviceCalendars,

                            connectionStatus = connectionStatus,
                            onCloudSync = onSyncCloud
                        )
                    }
                    "games" -> {
                        GamesContent(
                            contentPadding = contentPaddingWithTop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    "calendar" -> {
                        // Calendar inline view - same layer as note cards
                        CalendarContent(
                            events = calendarEvents,
                            activeTimers = activeTimers,
                            onEventClick = onEventClick,
                            onAddEvent = { selectedDate ->
                                // Fallback if onCreateEvent not provided
                                selectedDateForNewEvent = selectedDate
                                showAddEventDialog = true
                            },
                            onDeleteEvent = onDeleteCalendarEvent,
                            contentPadding = contentPaddingWithTop,
                            modifier = Modifier.fillMaxSize(),
                            // Enable inline event creation (no popup)
                            onCreateEvent = onCreateCalendarEvent
                        )
                    }
                    "chat" -> {
                        // Chat mode - switches between messages and history view
                        AnimatedContent(
                            targetState = showChatHistoryInline,
                            transitionSpec = {
                                if (targetState) {
                                    // Entering history: Zoom out effect (starts large 1.1x and settles to 1.0x)
                                    scaleIn(
                                        initialScale = 1.1f, 
                                        animationSpec = tween(350, easing = LinearOutSlowInEasing)
                                    ) + fadeIn(tween(200)) togetherWith
                                    scaleOut(
                                        targetScale = 0.95f, 
                                        animationSpec = tween(350)
                                    ) + fadeOut(tween(200))
                                } else {
                                    // Returning to chat: Zoom in (history fades out scaling down)
                                    scaleIn(
                                        initialScale = 0.95f, 
                                        animationSpec = tween(350)
                                    ) + fadeIn(tween(200)) togetherWith 
                                    scaleOut(
                                        targetScale = 1.1f, 
                                        animationSpec = tween(350)
                                    ) + fadeOut(tween(200))
                                }
                            },
                            label = "chatHistoryTransition",
                            modifier = Modifier.fillMaxSize()
                        ) { showHistory ->
                            if (showHistory) {
                                // History view - same layer as note cards
                                ChatHistoryContent(
                                    sessions = chatSessions,
                                    currentSessionId = currentSessionId,
                                    isLoading = isChatHistoryLoading,
                                    onSelectSession = onSwitchChatSession,
                                    onNewChat = onNewChatSession,
                                    onDeleteSession = onDeleteChatSession,
                                    onBackToChat = { showChatHistoryInline = false },
                                    contentPadding = contentPaddingWithTop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                // Chat messages view with pinch-to-zoom-out gesture for history
                                // Track cumulative scale during pinch gesture
                                var cumulativeScale by remember { mutableFloatStateOf(1f) }
                                var pointerCount by remember { mutableIntStateOf(0) }

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(Unit) {
                                            awaitEachGesture {
                                                // Wait for first pointer down
                                                awaitFirstDown(requireUnconsumed = false)
                                                
                                                do {
                                                    val event = awaitPointerEvent()
                                                    
                                                    // Track the number of active pointers
                                                    pointerCount = event.changes.count { it.pressed }
                                                    
                                                    // Only process zoom if we have 2+ pointers (actual pinch gesture)
                                                    if (pointerCount >= 2) {
                                                        // Calculate zoom from the event
                                                        val zoom = event.calculateZoom()
                                                        
                                                        // Update cumulative scale
                                                        cumulativeScale *= zoom
                                                        
                                                        // Trigger history when pinch-out (zoom out) below threshold
                                                        // Using 0.70f for more deliberate gesture detection
                                                        if (cumulativeScale < 0.70f) {
                                                            showChatHistoryInline = true
                                                            cumulativeScale = 1f // Reset for next gesture
                                                        }
                                                        
                                                        // Reset scale when zooming back in
                                                        if (zoom > 1f && cumulativeScale > 1f) {
                                                            cumulativeScale = 1f
                                                        }
                                                        
                                                        // Consume the event to prevent scrolling during pinch
                                                        event.changes.forEach { it.consume() }
                                                    }
                                                    // If pointerCount == 1, allow normal scrolling
                                                } while (event.changes.any { it.pressed })
                                                
                                                // Reset when all fingers are lifted
                                                cumulativeScale = 1f
                                            }
                                        }
                                ) {
                                    ChatModeContent(
                                        chatMessages = chatMessages,
                                        chatListState = chatListState,
                                        notes = notes,
                                        onNoteClick = onNoteClick,
                                        onNoteClickById = onNoteClickById,
                                        onEventClickById = onEventClickById,
                                        onSendChatMessage = onSendChatMessage,
                                        onDeleteMessage = onDeleteChatMessage,
                                        contentPadding = contentPaddingWithTop,
                                        isChatProcessing = isChatProcessing,
                                        agentActivity = agentActivity,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        // Notes mode content - extracted to NormalModeContent component
                        NormalModeContent(
                            displayedNotes = displayedNotes,
                            isNotesLoading = isNotesLoading,
                            isSearchMode = isSearchMode,
                            searchQuery = if (isChatMode) chatModeTextValue.text else normalModeTextValue.text,
                            gridState = gridState,
                            isRefreshing = isRefreshing,
                            onRefreshNotes = onRefreshNotes,
                            bottomContentPadding = bottomContentPadding,
                            topContentPadding = topPadding,
                            isSelectionMode = isSelectionMode,
                            selectedNoteIds = selectedNoteIds,
                            onToggleSelection = { noteId -> toggleSelection(noteId) },
                            onEnterSelectionMode = { noteId, _ ->
                                // Normal selection mode - no Pinterest menu
                                toggleSelection(noteId)
                            },
                            onNoteClick = onNoteClick,
                            onArchiveNote = onArchiveNote,
                            onDeleteNoteRequest = { noteId ->
                                noteToDeleteId = noteId
                                showDeleteDialog = true
                            },
                            onOpenTodo = { noteId -> selectedNoteIdForTodo = noteId },
                            onPlayYouTube = onPlayYouTube,
                            snackbarHostState = snackbarHostState,
                            onSetLastArchivedNoteId = { lastArchivedNoteId = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
            } // Close Box wrapper for centered content

// Floating Input Field Container OR Selection Pill Bar (mutually exclusive)
            val density = LocalDensity.current
            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0

            // Selection mode replaces input field with action pill
            val showSelectionPill = isSelectionMode && selectedNoteIds.isNotEmpty()
            
            val miniPlayerHeight = ComponentSpacing.miniPlayerHeight
            val miniPlayerExtraMargin = 16.dp
            val miniPlayerPadding = if (isMiniPlayerVisible && !isKeyboardVisible) miniPlayerHeight + miniPlayerExtraMargin else 0.dp

            val attachmentCount = currentInputAttachments.size
            val attachmentRowHeight = if (attachmentCount > 0) 60.dp else 0.dp
            val multiLineExtraHeight = if (textValue.text.count { it == '\n' } > 0) 40.dp else 0.dp

            // Input field height (hidden when selection mode active)
            val inputFieldHeight = if (showSelectionPill) 0.dp else (72.dp + attachmentRowHeight + multiLineExtraHeight)
            val inputFieldPadding = ComponentSpacing.screenPadding
            val isSearchSuggestionsVisible = isSearchMode && textValue.text.isEmpty() && recentSearches.isNotEmpty()
            val searchSuggestionsHeight = if (isSearchSuggestionsVisible && !showSelectionPill) {
                when {
                    isKeyboardVisible -> 100.dp
                    isLandscape -> 120.dp
                    else -> 200.dp
                }
            } else {
                0.dp
            }
            val extraBottomCoverage = when {
                isKeyboardVisible -> 10.dp
                isLandscape -> 20.dp
                else -> 40.dp
            }
            val gradientOffset = bottomGradientVerticalOffset + when {
                isKeyboardVisible -> (-10).dp
                isLandscape -> (-10).dp
                else -> 0.dp
            }
            
            val baseGradientHeight = inputFieldHeight + inputFieldPadding + searchSuggestionsHeight + extraBottomCoverage
            val targetGradientHeight = baseGradientHeight + miniPlayerPadding
            
            val animatedGradientHeight by animateDpAsState(
                targetValue = targetGradientHeight,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "gradientHeightAnimation"
            )
            
            val animatedGradientOffset by animateDpAsState(
                targetValue = gradientOffset,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "gradientOffsetAnimation"
            )
            

            val bottomGradientBrush = if (isDarkTheme) {
                SmartyBrushes.bottomScrimDark
            } else {
                SmartyBrushes.bottomScrimLight
            }
            
            // Input block only visible on Notes and Chat pages
            val showInputBlock = !showCalendarInline && !showStacksInline && !showArchiveInline && !showSettingsInline && !showGamesInline

            androidx.compose.animation.AnimatedVisibility(
                visible = showInputBlock,
                enter = slideInVertically(
                    initialOffsetY = { it / 2 },
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f)
                ) + fadeIn(tween(250)),
                exit = slideOutVertically(
                    targetOffsetY = { it / 2 },
                    animationSpec = tween(200)
                ) + fadeOut(tween(200)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = bottomContentPadding + miniPlayerPadding)
                    .navigationBarsPadding()
            ) {
                // Bottom Bar Scroll Physics
                val bottomScrollTransition = updateTransition(
                    targetState = userIsScrolling, 
                    label = "BottomBarScroll"
                )
                
                val bottomBarAlpha by bottomScrollTransition.animateFloat(
                    transitionSpec = { spring(dampingRatio = 0.8f, stiffness = 150f) },
                    label = "bottomBarAlpha"
                ) { isScrolling -> if (isScrolling) 0f else 1f }
                
                val bottomBarTranslationY by bottomScrollTransition.animateFloat(
                    transitionSpec = { spring(dampingRatio = 0.75f, stiffness = 100f) },
                    label = "bottomBarTranslationY"
                ) { isScrolling -> if (isScrolling) 250f else 0f } // Push down off-screen

                val bottomGradientTranslationY by bottomScrollTransition.animateFloat(
                    transitionSpec = { spring(dampingRatio = 0.75f, stiffness = 100f) },
                    label = "bottomGradientTranslationY"
                ) { isScrolling -> if (isScrolling) 120f else 0f } // Increased shift down

            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(animatedGradientHeight)
                        .offset(y = animatedGradientOffset)
                        .align(Alignment.BottomCenter)
                        .graphicsLayer { translationY = bottomGradientTranslationY }
                        .background(brush = bottomGradientBrush)
                        .zIndex(1f)
)

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .graphicsLayer { 
                            alpha = bottomBarAlpha 
                            translationY = bottomBarTranslationY 
                        }
                        .padding(
                            start = 8.dp,
                            end = 8.dp,
                            bottom = ComponentSpacing.screenPadding,
                            top = 0.dp
                        )
                        .zIndex(2f)
                ) {
                    // Search suggestions dropdown (BATCH 5C)
                    // Shows when in search mode with empty query and recent searches available
                    AnimatedVisibility(
                        visible = isSearchMode && textValue.text.isEmpty() && recentSearches.isNotEmpty(),
                        enter = fadeIn(tween(160)) + expandVertically(),
                        exit = fadeOut(tween(120)) + shrinkVertically()
                    ) {
                        SearchSuggestionsDropdown(
                            suggestions = recentSearches.take(5),
                            onSuggestionClick = { suggestion ->
                                // Update the text field with the selected suggestion
                                normalModeTextValue = TextFieldValue(suggestion, TextRange(suggestion.length))
                                onSearchQueryChange(suggestion)
                                onInputTextChange(suggestion)
                                // Record the search (moves to top of history)
                                onRecordSearch(suggestion)
                            },
                            onClearHistory = onClearSearchHistory,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    // Processing indicator REMOVED as per user request (shimmer is sufficient)
                    // (AnimatedVisibility block was here)

                    // SELECTION PILL BAR - Replaces input field when notes selected
                    if (showSelectionPill) {
                        SelectionPillBar(
                            selectedCount = selectedNoteIds.size,
                            onPin = { pinSelected() },
                            onShare = { shareSelected() },
                            onArchive = { archiveSelected() },
                            onDelete = { deleteSelected() },
                            onCategorize = { showCategorizeDialog = true },
                            onClose = { clearSelection() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    // Check for clarification block!
                    val activeClarificationMessage = if (isChatMode) {
                        chatMessages.find { it.role == com.example.smarty.core.domain.model.ChatRole.SMARTY && it.clarificationRequest != null && !it.isStreaming }
                    } else null

                    val pendingQuestions = if (pendingClarificationRequests.isNotEmpty()) {
                        pendingClarificationRequests
                    } else {
                        activeClarificationMessage?.clarificationRequest?.let { listOf(it) } ?: emptyList()
                    }

                    // Floating Input Field
                    if (!showSelectionPill) {
                        Box(contentAlignment = Alignment.BottomCenter) {
                        // The Actual Input Field with halftone shimmer inside
                        SmartyInputField(
                            value = textValue,
                            onValueChange = { newTextValue ->
                                // Cancel auto-send if user manually types
                                if (autoSendActive) {
                                    autoSendActive = false
                                    autoSendJob?.cancel()
                                }
                                // Reset speech tracking on manual input
                                hadSpeechInput = false

                                // Update the correct state based on current mode
                                if (isChatMode) {
                                    chatModeTextValue = newTextValue
                                    // @Mention: Update mention state for autocomplete
                                    onUpdateMentionState(newTextValue.text, newTextValue.selection.end)
                                } else {
                                    normalModeTextValue = newTextValue
                                    // Search mode integration
                                    if (isSearchMode) {
                                        onSearchQueryChange(newTextValue.text)
                                    }
                                }
                                onInputTextChange(newTextValue.text)
                            },
                            onSubmit = {
                                // CRITICAL FIX: Use the actual state variable, not the derived textValue
                                // textValue is derived and may be stale in the closure
                                val actualText = if (isChatMode) chatModeTextValue.text else normalModeTextValue.text
                                if (actualText.isNotBlank() || currentInputAttachments.isNotEmpty()) {
                                    if (isChatMode && isImageGenMode) {
                                        // Direct image generation mode
                                        onGenerateImageDirect(actualText)
                                        chatModeTextValue = TextFieldValue("")
                                        isImageGenMode = false // Auto-exit image gen mode after submit
                                    } else if (isChatMode) {
                                        onSendChatMessage(actualText, currentInputAttachments)
                                        chatModeTextValue = TextFieldValue("")
                                    } else if (!isSearchMode) {
                                        onAddNote(actualText, currentInputAttachments)
                                        normalModeTextValue = TextFieldValue("")
                                    }

                                    if (!isSearchMode) {
                                        onInputTextChange("")
                                        onInputAttachmentsChange(emptyList())
                                    }
                                }
                            },
                            attachments = currentInputAttachments,
                            onPickImage = {
                                // Use PickVisualMedia with ImageOnly filter for reliable image selection
                                imagePickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onPickVideo = {
                                // Use PickVisualMedia with VideoOnly filter for reliable video selection
                                videoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            },
                            onPickDocument = {
                                // OpenMultipleDocuments for document types
                                documentPickerLauncher.launch(
                                    arrayOf(
                                        "application/pdf",
                                        "application/msword",
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "application/vnd.ms-excel",
                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                        "application/vnd.ms-powerpoint",
                                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                        "text/plain",
                                        "text/csv",
                                        "application/rtf"
                                    )
                                )
                            },
                            onPickAudio = {
                                // OpenMultipleDocuments for audio types
                                audioPickerLauncher.launch(
                                    arrayOf(
                                        "audio/*",
                                        "audio/mpeg",
                                        "audio/mp4",
                                        "audio/wav",
                                        "audio/ogg",
                                        "audio/flac"
                                    )
                                )
                            },
                            onPickFile = {
                                // OpenMultipleDocuments for any file type
                                filePickerLauncher.launch(arrayOf("*/*"))
                            },
                            onOpenCamera = {
                                // Launch actual camera to take a photo
                                createImageFile()?.let { uri ->
                                    cameraImageUri = uri
                                    cameraLauncher.launch(uri)
                                }
                            },
                            onRemoveAttachment = { id -> onInputAttachmentsChange(currentInputAttachments.filter { it.id != id }) },
                            pendingQuestions = pendingQuestions,
                            onQuestionAnswered = { response ->
                                if (pendingApprovalToolId != null) {
                                    onCallApproval(pendingApprovalToolId, true, response.ifEmpty { null })
                                } else if (activeClarificationMessage != null) {
                                    onClarificationSubmit(activeClarificationMessage.id, response)
                                }
                            },
                            isChatMode = isChatMode,
                            isHistoryMode = showChatHistoryInline, // Pass history state
                            isProcessing = isChatProcessing,
                            onClearInput = {
                                // Clear local state depending on mode
                                if (isChatMode) {
                                    chatModeTextValue = androidx.compose.ui.text.input.TextFieldValue("")
                                } else {
                                    normalModeTextValue = androidx.compose.ui.text.input.TextFieldValue("")
                                }
                                // Clear attachments and common state
                                onInputAttachmentsChange(emptyList())
                                onInputTextChange("")


                                // Reset search mode if active but keep it explicit
                                if (isSearchMode) {
                                    onSearchQueryChange("")
                                }
                            },
                            onOpenChatHistory = { showChatHistoryInline = true },
                            onNewChat = { 
                                onNewChatSession()
                                showChatHistoryInline = false
                            },
                            isAiExcluded = isAiExcluded,
                            isSearchMode = isSearchMode,
                            onToggleSearch = {
                                // Record search when exiting search mode with a query
                                if (isSearchMode && normalModeTextValue.text.isNotBlank()) {
                                    onRecordSearch(normalModeTextValue.text)
                                }
                                isSearchMode = !isSearchMode
                                normalModeTextValue = TextFieldValue("")  // Only clear normal mode text
                                onInputTextChange("")
                            },
                            isVoiceListening = speechState.isListening,
                            onStartVoiceInput = {
                                if (speechState.isListening) {
                                    speechState.stopListening()
                                } else {
                                    // Pass current mode so speech result goes to correct input field
                                    speechState.startListening(isChatMode = isChatMode)
                                }
                            },
                            onStopVoiceInput = {
                                speechState.stopListening()
                            },
                            isAgentWorking = isChatProcessing,
                            autoSendActive = autoSendActive,
                            // Search filter parameters
                            selectedFilters = selectedFilters,
                            onFilterToggle = onFilterToggle,
                            onClearFilters = onClearFilters,
                            // Voice recording (hold mic to record, release to stop)
                            onStartRecording = {
                                // Stop any active speech recognition before recording
                                if (speechState.isListening) {
                                    speechState.stopListening()
                                }
                                voiceRecorder.startRecording()
                            },
                            onStopRecording = {
                                voiceRecorder.stopRecording()
                            },
                            isRecording = isRecording,
                            // @Mention autocomplete (Chat mode only)
                            mentionState = mentionState,
                            onMentionSelected = { suggestion ->
                                // Get updated text from callback and set it
                                val updatedText = onMentionSelected(suggestion, chatModeTextValue.text)
                                chatModeTextValue = TextFieldValue(
                                    text = updatedText,
                                    selection = androidx.compose.ui.text.TextRange(updatedText.length)
                                )
                            },
                            // Image generation mode toggle
                            isImageGenMode = isImageGenMode,
                            onToggleImageGenMode = {
                                isImageGenMode = !isImageGenMode
                            },
                            showScrollButton = chatListState.canScrollForward || chatListState.canScrollBackward,
                            isAtLatest = !chatListState.canScrollBackward,
                            onScrollToBottom = {
                                coroutineScope.launch {
                                    val total = chatListState.layoutInfo.totalItemsCount
                                    if (total > 0) {
                                        chatListState.animateScrollToItem(total - 1, scrollOffset = 10000)
                                    }
                                }
                            },
                            onScrollToTop = {
                                coroutineScope.launch {
                                    chatListState.animateScrollToItem(0, scrollOffset = -10000)
                                }
                            },
                            selectedModel = selectedModel,
                            availableModels = availableModels,
                            onModelSelected = onModelSelected,
                            modelVariantMap = modelVariantMap,
                            selectedVariant = selectedVariant,
                            onVariantSelected = onVariantSelected,
                            onRefreshModels = onRefreshModels
                        )
                        }  // Close Box for input field
                    }  // Close if (!showSelectionPill)
                }
            }

        }
        } // AnimatedVisibility (input block)
    }


    // Pinterest-style menu implementation
    pinterestMenuPosition?.let { pos ->
        val noteId = activePinterestNoteId
        if (noteId != null) {
            PinterestMenu(
                anchorPosition = pos,
                onDismiss = {
                    pinterestMenuPosition = null
                    activePinterestNoteId = null
                },
                onAction = { action ->
                    when (action) {
                        PinterestAction.PIN -> onPinNote(noteId)
                        PinterestAction.SHARE -> {
                            val note = notes.find { it.id == noteId }
                            if (note != null) onShareNotes(listOf(note))
                        }
                        PinterestAction.ARCHIVE -> onArchiveNote(noteId)
                        PinterestAction.DELETE -> {
                            noteToDeleteId = noteId
                            showDeleteDialog = true
                        }
                    }
                    pinterestMenuPosition = null
                    activePinterestNoteId = null
                }
            )
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog && noteToDelete != null) {
        SmartyDialog(
            title = stringResource(R.string.delete_note),
            text = stringResource(R.string.delete_note_warning),
            onConfirm = {
                onDeleteNote(noteToDelete.id)
                showDeleteDialog = false
                noteToDeleteId = null
            },
            onDismiss = {
                showDeleteDialog = false
                noteToDeleteId = null
            },
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            isDestructive = true
        )
    }

    // Todo bottom sheet - auto-saves on every action
    selectedNoteForTodo?.let { note ->
        NoteTodoSheet(
            note = note,
            sheetState = todoSheetState,
            onDismiss = { selectedNoteIdForTodo = null },
            onSaveTodos = { todos ->
                // Auto-save without dismissing - user clicks Done to close
                onUpdateNoteTodos(note.id, todos, null)
            }
        )
    }

    // Share bottom sheet for incoming shared content
    val shareSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    pendingShare?.let { share ->
        ShareBottomSheet(
            pendingShare = share,
            categories = categories,
            sheetState = shareSheetState,
            isFullPrivacy = isShareFullPrivacy,
            onDismiss = onCancelShare,
            onSave = { selectedCategory, aiInstructions ->
                onConfirmShare(selectedCategory, aiInstructions)
            }
        )
    }

    // 
    // CENTRALIZED UI: Bottom Sheets for all features
    // 

    // Calendar Sheet
    if (showCalendarSheet) {
        CalendarSheet(
            events = calendarEvents,
            activeTimers = activeTimers,
            sheetState = calendarSheetState,
            onDismiss = { showCalendarSheet = false },
            onAddEvent = onAddCalendarEvent,
            onEventClick = onEventClick,
            onDeleteEvent = onDeleteCalendarEvent,
            onCreateEvent = onCreateCalendarEvent
        )
    }

    // Stacks Sheet
    if (showStacksSheet) {
        StacksSheet(
            categories = categories,
            sheetState = stacksSheetState,
            onDismiss = { showStacksSheet = false },
            onCategoryClick = { category ->
                onSelectCategory(category)
                showStacksSheet = false
            },
            onCreateCategory = onCreateCategory,
            onDeleteCategory = onDeleteCategory
        )
    }

    // Archive Sheet
    if (showArchiveSheet) {
        ArchiveSheet(
            archivedNotes = archivedNotes,
            sheetState = archiveSheetState,
            onDismiss = { showArchiveSheet = false },
            onDeleteNote = onDeleteNote,
            onUnarchiveNote = onUnarchiveNote
        )
    }

    // Settings Sheet
    if (showSettingsSheet) {
        SettingsSheet(
            sheetState = settingsSheetState,
            onDismiss = { showSettingsSheet = false },
            isDarkTheme = isDarkTheme,
            onToggleTheme = onToggleTheme,
            cacheSizeBytes = cacheSizeBytes,
            onClearCache = onClearCache,
            isClearingCache = isClearingCache,
            shakeSensitivity = shakeSensitivity,
            onShakeSensitivityChange = onShakeSensitivityChange,
            onSignOut = onSignOut,
            backupContent = backupContent,
            // Google Calendar Two-Way Sync
            isCalendarSyncEnabled = isCalendarSyncEnabled,
            onSetCalendarSyncEnabled = onSetCalendarSyncEnabled,
            deviceCalendars = deviceCalendars,
            targetCalendarId = targetCalendarId,
            onSetTargetCalendarId = onSetTargetCalendarId,
            onLoadDeviceCalendars = onLoadDeviceCalendars,
            onCloudSync = onSyncCloud
        )
    }

    // Add Event Dialog for inline calendar
    if (showAddEventDialog && onCreateCalendarEvent != null) {
        AddEventDialog(
            onDismiss = {
                showAddEventDialog = false
                selectedDateForNewEvent = null
            },
            onConfirm = { title, description, startTime, endTime, isAllDay ->
                onCreateCalendarEvent(title, description, startTime, endTime, isAllDay)
                showAddEventDialog = false
                selectedDateForNewEvent = null
            },
            initialDate = selectedDateForNewEvent ?: java.util.Calendar.getInstance()
        )
    }

    // Categorize selected notes dialog
    if (showCategorizeDialog) {
        CategorizeNotesDialog(
            categories = categories,
            onCategorySelected = { categoryId, categoryName ->
                categorizeSelected(categoryId, categoryName)
                showCategorizeDialog = false
            },
            onDismiss = {
                showCategorizeDialog = false
            }
        )
    }
}
}
}

/**
 * Dialog for categorizing selected notes.
 * Shows a list of categories to choose from.
 */
@Composable
fun CategorizeNotesDialog(
    categories: List<Category>,
    onCategorySelected: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Categorize Notes",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            LazyColumn {
                items(categories.size) { index ->
                    val category = categories[index]
                    ListItem(
                        headlineContent = { Text(category.name) },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        modifier = Modifier
                            .clickable {
                                onCategorySelected(category.id, category.name)
                            }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Displays a dropdown of recent search suggestions.
 * Shows when search field is focused and has suggestions available.
 */
@Composable
fun SearchSuggestionsDropdown(
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.recent_searches),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            TextButton(onClick = onClearHistory) {
                Text(
                    stringResource(R.string.clear),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalAccentColor.current.copy(alpha = 0.7f)
                )
            }
        }

        suggestions.forEach { suggestion ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSuggestionClick(suggestion) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null, // Decorative icon - indicates search history
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * Selection Pill Bar - Replaces input field when notes are selected.
 * Has same UI properties as input field (shape, elevation, styling).
 * Shows selection actions and count.
 */
@Composable
fun SelectionPillBar(
    selectedCount: Int,
    onPin: () -> Unit,
    onShare: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onCategorize: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = !MaterialTheme.colorScheme.surface.luminance().let { it > 0.5f }
    val backgroundColor = if (isDark) Color(0xFF2C2C35) else Color(0xFFFCFCFD)
    val borderColor = if (isDark) Color(0xFF3C3C45) else Color(0xFFE5E5EA)
    val accentColor = LocalAccentColor.current
    
    Surface(
        shape = RoundedCornerShape(28.dp),  // Same as input field
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 8.dp,  // Same as input field
        modifier = modifier
            .softCardShadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(28.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side: Close button + Count
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Close button (X)
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close selection",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                // Selected count pill
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = accentColor.copy(alpha = 0.15f),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "$selectedCount selected",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
            
            // Right side: Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Categorize
                IconButton(
                    onClick = onCategorize,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Categorize",
                        modifier = Modifier.size(22.dp)
                    )
                }
                
                // Pin
                IconButton(
                    onClick = onPin,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Pin",
                        modifier = Modifier.size(22.dp)
                    )
                }
                
                // Share
                IconButton(
                    onClick = onShare,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        modifier = Modifier.size(22.dp)
                    )
                }
                
                // Archive
                IconButton(
                    onClick = onArchive,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Archive,
                        contentDescription = "Archive",
                        modifier = Modifier.size(22.dp)
                    )
                }
                
                // Delete (red)
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

