package com.example.smarty.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.smarty.data.model.Attachment
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.MentionState
import com.example.smarty.data.model.MentionSuggestion
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.TodoItem
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.JarvisEasing
import com.example.smarty.ui.animation.StaggerCalculator
import com.example.smarty.data.model.ChatSession
import com.example.smarty.ui.components.JarvisInputField
import com.example.smarty.ui.components.NoteTodoSheet
import com.example.smarty.data.model.NoteType
import com.example.smarty.ui.components.AttachmentOption
import com.example.smarty.ui.components.SearchFilterTypeSelector
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.example.smarty.ui.components.PendingShareData
import com.example.smarty.data.model.AIMemory
import com.example.smarty.ui.components.ProcessingDotsIndicator
import com.example.smarty.ui.components.ShareBottomSheet
import com.example.smarty.ui.components.getNoteTypeIcon
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.SafetyOrange

import com.example.smarty.ui.components.ConnectionStatus
import com.example.smarty.ui.components.ConnectionStatusIndicator
import com.example.smarty.ui.components.HorizontalActionBar
import com.example.smarty.ui.components.NavigationTab
import com.example.smarty.ui.components.sheets.CalendarSheet
import com.example.smarty.ui.components.sheets.StacksSheet
import com.example.smarty.ui.components.sheets.ArchiveSheet
import com.example.smarty.ui.components.sheets.SettingsSheet
import com.example.smarty.ui.components.AnimatedCategoryFilterChip
import com.example.smarty.ui.components.AddEventDialog
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.data.local.AIProvider
import com.example.smarty.ui.screens.inputstream.CalendarContent
import com.example.smarty.ui.screens.inputstream.ChatHistoryContent
import com.example.smarty.ui.screens.inputstream.ChatModeContent
import com.example.smarty.ui.screens.inputstream.NormalModeContent
import com.example.smarty.ui.screens.inputstream.SelectionModeToolbar
import com.example.smarty.ui.screens.inputstream.StacksContent
import com.example.smarty.ui.screens.inputstream.ArchiveContent
import com.example.smarty.ui.screens.inputstream.SettingsContent
import com.example.smarty.data.local.AIProviderConfig
import com.example.smarty.util.api.KeyUsageStats
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
    onUpdateNoteTodos: (String, List<TodoItem>, onComplete: (() -> Unit)?) -> Unit,
    onNavigateToStacks: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToCalendar: () -> Unit = {},
    pendingShare: PendingShareData?,
    onConfirmShare: (String?, String) -> Unit,
    onCancelShare: () -> Unit,
    // Share privacy mode (shake to activate)
    isShareFullPrivacy: Boolean = false,
    // Chat mode parameters
    isChatMode: Boolean = false,
    chatMessages: List<ChatMessage> = emptyList(),
    isChatProcessing: Boolean = false,
    onEnterChatMode: () -> Unit = {},  // Enter chat mode when clicking AI tab
    onExitChatMode: () -> Unit = {},  // Back button handler for chat mode
    onSendChatMessage: (String, List<Attachment>) -> Unit = { _, _ -> },
    // Chat history parameters
    chatSessions: List<ChatSession> = emptyList(),
    currentSessionId: String? = null,
    onSwitchChatSession: (String) -> Unit = {},
    onNewChatSession: () -> Unit = {},
    onDeleteChatSession: (String) -> Unit = {},
    // @Mention parameters (Chat mode)
    mentionState: MentionState = MentionState(),
    onMentionSelected: (MentionSuggestion, String) -> String = { _, text -> text },  // Returns updated text
    // AI Planning Status (dynamic placeholder)
    aiPlanStatus: String? = null,
    currentToolName: String? = null,
    // Pending chat text (for "Ask AI" from note card)
    pendingChatText: String? = null,
    onClearPendingChatText: () -> Unit = {},
    // Mention state update callback (called on text change in chat mode)
    onUpdateMentionState: (String, Int) -> Unit = { _, _ -> },
    // AI exclusion parameters
    isAiExcluded: Boolean = false,
    onInputTextChange: (String) -> Unit = {},
    onInputAttachmentsChange: (List<Attachment>) -> Unit = {},
    onPlayYouTube: (String) -> Unit = {},
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    isMiniPlayerVisible: Boolean = false,  // Audio player visibility for gradient adjustment
    externalSpeechState: com.example.smarty.util.SpeechToTextState? = null,
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
    // Camera trigger from widget
    cameraTriggered: Boolean = false,
    onClearCameraTrigger: () -> Unit = {},
    // Pin and Share callbacks
    onPinNote: (String) -> Unit = {},
    onUnpinNote: (String) -> Unit = {},
    onShareNotes: (List<Note>) -> Unit = {},

    // ═══════════════════════════════════════════════════════════════════════════
    // CENTRALIZED UI: All features accessible from main screen via bottom sheets
    // ═══════════════════════════════════════════════════════════════════════════

    // Theme toggle (always visible in header)
    isDarkTheme: Boolean = false,
    onToggleTheme: (Boolean) -> Unit = {},

    // Archived notes for Archive sheet
    archivedNotes: List<Note> = emptyList(),

    // Calendar events for Calendar sheet
    calendarEvents: List<CalendarEvent> = emptyList(),
    onAddCalendarEvent: () -> Unit = {},
    onCreateCalendarEvent: ((title: String, description: String?, startTime: Long, endTime: Long, isAllDay: Boolean) -> Unit)? = null,
    onEventClick: (CalendarEvent) -> Unit = {},
    onDeleteCalendarEvent: (CalendarEvent) -> Unit = {},

    // Category management for Stacks sheet
    onCreateCategory: (String) -> Unit = {},
    onDeleteCategory: (Category) -> Unit = {},
    onCategoryClick: (Category) -> Unit = {},
    onSyncCategoryCounts: () -> Unit = {},  // Sync category counts when entering stacks view

    // Settings props for Settings sheet
    providerConfigs: Map<AIProvider, AIProviderConfig> = emptyMap(),
    providerPriorityOrder: List<AIProvider> = emptyList(),
    onAddApiKey: (AIProvider, String) -> Unit = { _, _ -> },
    onRemoveApiKey: (AIProvider, String) -> Unit = { _, _ -> },
    onUpdateApiKey: (AIProvider, String, String) -> Unit = { _, _, _ -> },
    onSetProviderEnabled: (AIProvider, Boolean) -> Unit = { _, _ -> },
    onSetSelectedModel: (AIProvider, String) -> Unit = { _, _ -> },
    onSetProviderPriority: (List<AIProvider>) -> Unit = {},
    onTestApiKey: (AIProvider, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    tavilyApiKeys: List<String> = emptyList(),
    onAddTavilyApiKey: (String) -> Unit = {},
    onRemoveTavilyApiKey: (String) -> Unit = {},
    cacheSizeBytes: Long = 0L,
    onClearCache: () -> Unit = {},
    isClearingCache: Boolean = false,
    shakeSensitivity: Float = 0.63f,
    onShakeSensitivityChange: (Float) -> Unit = {},
    groqKeyUsageStats: List<KeyUsageStats> = emptyList(),
    onRefreshModels: (AIProvider) -> Unit = {},
    getAvailableModels: (AIProvider) -> List<Pair<String, String>> = { emptyList() },
    onSignOut: () -> Unit = {},
    // Settings sub-sheet content
    aiConfigContent: @Composable (onDismiss: () -> Unit) -> Unit = {},
    archiveContentForSettings: @Composable (onDismiss: () -> Unit) -> Unit = {},

    backupContent: @Composable (onDismiss: () -> Unit) -> Unit = {},
    // AI Memory
    aiMemories: List<AIMemory> = emptyList(),
    onDeleteAIMemory: (AIMemory) -> Unit = {},
    onClearAllAIMemories: () -> Unit = {},
    // Memory sync
    onSyncAIMemories: () -> Unit = {},
    isMemorySyncInProgress: Boolean = false,
    memorySyncResult: String? = null,
    unreadForMemoryCount: Int = 0,
    onClearMemorySyncResult: () -> Unit = {},
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

    // RESTORED VARIABLES that were accidentally deleted or need to be present
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }

    // Sync attachments to ViewModel for shake detection
    // When attachments change, notify ViewModel so shake can trigger privacy mode
    LaunchedEffect(attachments) {
        onInputAttachmentsChange(attachments)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // @MENTION: Consume pending chat text (from "Ask AI" button on note cards)
    // ═══════════════════════════════════════════════════════════════════════════
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

    // Accent color for theming
    val accentColor = LocalAccentColor.current

    // ═══════════════════════════════════════════════════════════════════════════
    // CENTRALIZED UI: Bottom Sheet States
    // ═══════════════════════════════════════════════════════════════════════════
    var selectedTab by remember { mutableStateOf(NavigationTab.NOTES) }

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

    // Category filter state
    var activeCategoryFilter by remember { mutableStateOf<Category?>(null) }

    // Chat history inline view state (shows history in main area instead of bottom sheet)
    var showChatHistoryInline by remember { mutableStateOf(false) }

    // Reset history view when entering chat mode (e.g., via shake gesture)
    // This ensures shake always opens chat messages, not history
    LaunchedEffect(isChatMode) {
        if (isChatMode) {
            // Entering chat mode - always show chat messages, not history
            showChatHistoryInline = false
            // Also close calendar inline view when entering chat mode
            showCalendarInline = false
        }
    }

    // Reset inline views when exiting chat mode
    LaunchedEffect(isChatMode) {
        if (!isChatMode) {
            showChatHistoryInline = false
        }
    }

    // Handle tab selection - open sheets or switch modes
    fun handleTabSelection(tab: NavigationTab) {
        selectedTab = tab
        
        // Helper to close all inline views
        fun closeAllInlineViews() {
            showCalendarInline = false
            showStacksInline = false
            showArchiveInline = false
            showSettingsInline = false
            showChatHistoryInline = false
        }
        
        when (tab) {
            NavigationTab.NOTES -> {
                closeAllInlineViews()
                if (isChatMode) {
                    onExitChatMode()
                }
            }
            NavigationTab.CHAT -> {
                closeAllInlineViews()
                if (!isChatMode) {
                    onEnterChatMode()
                } else if (showChatHistoryInline) {
                    showChatHistoryInline = false
                }
            }
            NavigationTab.CALENDAR -> {
                if (showCalendarInline) {
                    showCalendarInline = false
                } else {
                    if (isChatMode) onExitChatMode()
                    closeAllInlineViews()
                    showCalendarInline = true
                }
            }
            NavigationTab.STACKS -> {
                if (showStacksInline) {
                    showStacksInline = false
                } else {
                    if (isChatMode) onExitChatMode()
                    closeAllInlineViews()
                    showStacksInline = true
                }
            }
            NavigationTab.ARCHIVE -> {
                if (showArchiveInline) {
                    showArchiveInline = false
                } else {
                    if (isChatMode) onExitChatMode()
                    closeAllInlineViews()
                    showArchiveInline = true
                }
            }
            NavigationTab.SETTINGS -> {
                if (showSettingsInline) {
                    showSettingsInline = false
                } else {
                    if (isChatMode) onExitChatMode()
                    closeAllInlineViews()
                    showSettingsInline = true
                }
            }
        }
    }

    // Voice Input State (Speech-to-Text) - Use external from MainActivity
    val speechState = externalSpeechState ?: com.example.smarty.util.rememberSpeechToText(
        onResult = { /* Handled by global flow */ }
    )

    // Voice Recording State (Long-press on mic to record audio note)
    val voiceRecorder = remember { com.example.smarty.voice.VoiceNoteRecorder(context, scope) }
    val recordingState by voiceRecorder.state.collectAsState()
    val isRecording = recordingState is com.example.smarty.voice.VoiceNoteRecorder.RecordingState.Recording

    // Handle recording completion - add as audio attachment
    LaunchedEffect(recordingState) {
        when (val state = recordingState) {
            is com.example.smarty.voice.VoiceNoteRecorder.RecordingState.Completed -> {
                // Add the recorded audio as an attachment
                val file = java.io.File(state.filePath)
                val uri = android.net.Uri.fromFile(file)
                val newAttachment = Attachment(
                    id = java.util.UUID.randomUUID().toString(),
                    uri = uri,
                    mimeType = "audio/mp4",
                    fileName = file.name,
                    fileSize = file.length()
                )
                attachments = attachments + newAttachment
                voiceRecorder.reset()
            }
            else -> { /* Other states handled by UI */ }
        }
    }

    // Cancel recording on dispose if still active
    DisposableEffect(Unit) {
        onDispose {
            if (voiceRecorder.state.value is com.example.smarty.voice.VoiceNoteRecorder.RecordingState.Recording) {
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

    // Auto-send in chat mode: 0.4s after speech reJarvistion stops
    LaunchedEffect(speechState.isListening, isChatMode) {
        if (!speechState.isListening && isChatMode && hadSpeechInput) {
            val currentText = chatModeTextValue.text
            if (currentText.isNotBlank()) {
                autoSendActive = true
                autoSendJob?.cancel()
                autoSendJob = scope.launch {
                    delay(400)  // 0.4 seconds
                    if (autoSendActive) {
                        onSendChatMessage(currentText, attachments)
                        chatModeTextValue = TextFieldValue("")
                        attachments = emptyList()
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

    // Search Mode State
    var isSearchMode by remember { mutableStateOf(false) }

    // Filtered Notes Logic (Backend Driven for Phase 2)
    // We now use the 'notes' list directly as it is already filtered by the ViewModel
    // based on searchQuery and selectedFilters.
    // Filter by category if active, then by search/filters
    val displayedNotes = remember(notes, activeCategoryFilter) {
        if (activeCategoryFilter != null) {
            notes.filter { it.categoryId == activeCategoryFilter?.id }
        } else {
            notes
        }
    }

    // Selection handlers
    fun toggleSelection(noteId: String) {
        selectedNoteIds = if (noteId in selectedNoteIds)
            selectedNoteIds - noteId
        else
            selectedNoteIds + noteId
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
                message = "$count note${if (count > 1) "s" else ""} archived",
                actionLabel = "UNDO",
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
                message = "$count note${if (count > 1) "s" else ""} deleted",
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
                message = "$count note${if (count > 1) "s" else ""} pinned",
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

    // Handle back button press - exit history view first, then chat mode
    // Priority: History view → Chat mode → Normal navigation
    BackHandler(enabled = isChatMode && showChatHistoryInline && !isSelectionMode) {
        showChatHistoryInline = false
    }

    // Handle back button press - exit chat mode and return to main page
    // This only triggers when in chat mode, NOT showing history, and NOT in selection mode
    BackHandler(enabled = isChatMode && !showChatHistoryInline && !isSelectionMode) {
        onExitChatMode()
    }

    // Handle back button press - exit calendar inline view
    BackHandler(enabled = showCalendarInline && !isSelectionMode) {
        showCalendarInline = false
    }

    // Handle back button press - exit stacks inline view
    BackHandler(enabled = showStacksInline && !isSelectionMode) {
        showStacksInline = false
    }

    // Handle back button press - exit archive inline view
    BackHandler(enabled = showArchiveInline && !isSelectionMode) {
        showArchiveInline = false
    }

    // Handle back button press - exit settings inline view
    BackHandler(enabled = showSettingsInline && !isSelectionMode) {
        showSettingsInline = false
    }

    // Handle back button press - clear active category filter instead of closing app
    BackHandler(enabled = activeCategoryFilter != null && !isSelectionMode && !showStacksInline && !showCalendarInline && !showArchiveInline && !showSettingsInline && !isChatMode) {
        activeCategoryFilter = null
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
                uri = uri,
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
            attachments = attachments + newAttachments
        }
    }

    // Video picker - uses PickMultipleVisualMedia with VideoOnly filter
    val videoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        uris.mapNotNull { getFileInfo(it) }.let { newAttachments ->
            attachments = attachments + newAttachments
        }
    }

    // Audio picker - uses OpenMultipleDocuments with audio MIME types
    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.mapNotNull { getFileInfo(it) }.let { newAttachments ->
            attachments = attachments + newAttachments
        }
    }

    // Document picker - uses OpenMultipleDocuments for documents
    val documentPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.mapNotNull { getFileInfo(it) }.let { newAttachments ->
            attachments = attachments + newAttachments
        }
    }

    // Generic file picker - uses OpenMultipleDocuments for any file type
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.mapNotNull { getFileInfo(it) }.let { newAttachments ->
            attachments = attachments + newAttachments
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



    // Scroll to bottom when new chat message arrives
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty() && isChatMode) {
            chatListState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background) // Base background
    ) {
        // Dynamic Mesh Spill Background (header gradient)
        MeshSpillEffect()


        
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
                            text = data.visuals.message,
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
                                shape = RoundedCornerShape(6.dp),
                                color = LocalAccentColor.current.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "UNDO",
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
            AnimatedContent(
                targetState = isSelectionMode,
                transitionSpec = {
                    fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                },
                label = "topBarTransition"
            ) { inSelectionMode ->
                if (inSelectionMode) {
                    // Selection mode top bar - extracted to separate component
                    SelectionModeToolbar(
                        selectedCount = selectedNoteIds.size,
                        onClearSelection = { clearSelection() },
                        onSelectAll = { selectAllNotes() },
                        onPinSelected = { pinSelected() },
                        onShareSelected = { shareSelected() },
                        onArchiveSelected = { archiveSelected() },
                        onDeleteSelected = { deleteSelected() }
                    )
                } else {
                    // ═══════════════════════════════════════════════════════════════════
                    // CENTRALIZED UI: Minimal Header + Horizontal Action Bar
                    // ═══════════════════════════════════════════════════════════════════
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to MaterialTheme.colorScheme.background,
                                        0.3f to MaterialTheme.colorScheme.background,
                                        0.5f to MaterialTheme.colorScheme.background.copy(alpha = 0.9f),
                                        0.7f to MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                                        0.85f to MaterialTheme.colorScheme.background.copy(alpha = 0.3f),
                                        1.0f to Color.Transparent
                                    )
                                )
                            )
                    ) {
                        // Minimal Header Row: Logo | Status | Theme Toggle
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(top = 2.dp)
                                .height(40.dp)
                                .padding(horizontal = 20.dp)
                        ) {
                            // Logo/Brand (Left)
                            Text(
                                text = "Jarvis",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.5).sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.align(Alignment.CenterStart)
                            )

                            // Connection Status (Center)
                            ConnectionStatusIndicator(
                                status = connectionStatus,
                                modifier = Modifier.align(Alignment.Center)
                            )

                            // Theme Toggle - Always Visible (AI is in the action bar below)
                            IconButton(
                                onClick = { onToggleTheme(!isDarkTheme) },
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .size(40.dp)
                            ) {
                                Icon(
                                    imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                    contentDescription = if (isDarkTheme) "Switch to Light Mode" else "Switch to Dark Mode",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // Horizontal Action Bar - All features accessible here
                        HorizontalActionBar(
                            selectedTab = when {
                                showStacksInline -> NavigationTab.STACKS
                                showArchiveInline -> NavigationTab.ARCHIVE
                                showSettingsInline -> NavigationTab.SETTINGS
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

                        // Category filter chip
                        AnimatedCategoryFilterChip(
                            categoryName = activeCategoryFilter?.name,
                            noteCount = displayedNotes.size,
                            onClear = { activeCategoryFilter = null },
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    } // End Column
                }
            }
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
                showCalendarInline -> "calendar"
                isChatMode -> "chat"
                else -> "notes"
            }

            // Animated content switching between notes, chat, and calendar
            AnimatedContent(
                targetState = contentMode,
                transitionSpec = {
                    fadeIn(tween(240)) togetherWith fadeOut(tween(160))
                },
                label = "contentModeTransition",
                modifier = Modifier.fillMaxSize()
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
                            onCategoryClick = { category ->
                                activeCategoryFilter = category
                                showStacksInline = false  // Close and filter notes
                            },
                            onCreateCategory = onCreateCategory,
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
                            onDeleteNote = onDeleteNote,
                            onUnarchiveNote = onUnarchiveNote,
                            contentPadding = contentPaddingWithTop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    "settings" -> {
                        // Settings inline view
                        SettingsContent(
                            providerConfigs = providerConfigs,
                            providerPriorityOrder = providerPriorityOrder,
                            onAddApiKey = onAddApiKey,
                            onRemoveApiKey = onRemoveApiKey,
                            onUpdateApiKey = onUpdateApiKey,
                            onSetProviderEnabled = onSetProviderEnabled,
                            onSetSelectedModel = onSetSelectedModel,
                            onSetProviderPriority = onSetProviderPriority,
                            onTestApiKey = onTestApiKey,
                            isDarkTheme = isDarkTheme,
                            onToggleTheme = onToggleTheme,
                            tavilyApiKeys = tavilyApiKeys,
                            onAddTavilyApiKey = onAddTavilyApiKey,
                            onRemoveTavilyApiKey = onRemoveTavilyApiKey,
                            cacheSizeBytes = cacheSizeBytes,
                            onClearCache = onClearCache,
                            isClearingCache = isClearingCache,
                            shakeSensitivity = shakeSensitivity,
                            onShakeSensitivityChange = onShakeSensitivityChange,
                            groqKeyUsageStats = groqKeyUsageStats,
                            onRefreshModels = onRefreshModels,
                            getAvailableModels = getAvailableModels,
                            onSignOut = onSignOut,
                            aiConfigContent = aiConfigContent,
                            archiveContent = archiveContentForSettings,

                            backupContent = backupContent,
                            // AI Memory
                            aiMemories = aiMemories,
                            onDeleteAIMemory = onDeleteAIMemory,
                            onClearAllAIMemories = onClearAllAIMemories,
                            // Memory sync
                            onSyncAIMemories = onSyncAIMemories,
                            isMemorySyncInProgress = isMemorySyncInProgress,
                            memorySyncResult = memorySyncResult,
                            unreadForMemoryCount = unreadForMemoryCount,
                            onClearMemorySyncResult = onClearMemorySyncResult,
                            contentPadding = contentPaddingWithTop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    "calendar" -> {
                        // Calendar inline view - same layer as note cards
                        CalendarContent(
                            events = calendarEvents,
                            onEventClick = onEventClick,
                            onAddEvent = { selectedDate ->
                                // Show inline add event dialog with selected date
                                selectedDateForNewEvent = selectedDate
                                showAddEventDialog = true
                            },
                            onDeleteEvent = onDeleteCalendarEvent,
                            contentPadding = contentPaddingWithTop,
                            modifier = Modifier.fillMaxSize()
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
                                        onSendChatMessage = onSendChatMessage,
                                        contentPadding = contentPaddingWithTop,
                                        currentToolName = currentToolName,
                                        isChatProcessing = isChatProcessing,
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
                            searchQuery = textValue.text,
                            gridState = gridState,
                            isRefreshing = isRefreshing,
                            onRefreshNotes = onRefreshNotes,
                            bottomContentPadding = bottomContentPadding,
                            topContentPadding = topPadding,
                            isSelectionMode = isSelectionMode,
                            selectedNoteIds = selectedNoteIds,
                            onToggleSelection = { noteId -> toggleSelection(noteId) },
                            onEnterSelectionMode = { noteId ->
                                isSelectionMode = true
                                selectedNoteIds = setOf(noteId)
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



            // Floating Input Field Container (Overlaying content)
            // Additional bottom padding when mini player is visible to prevent overlap
            val miniPlayerPadding = if (isMiniPlayerVisible) 72.dp else 0.dp
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = bottomContentPadding + miniPlayerPadding)
                    .navigationBarsPadding()
            ) {
                // Bottom Gradient Scrim (Hides scrolling text behind input)
                // Extended height when mini player is visible
                val gradientHeight = if (isMiniPlayerVisible) 280.dp else 200.dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(gradientHeight) // Height to cover input area + fade + mini player
                        .align(Alignment.BottomCenter)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                                    MaterialTheme.colorScheme.background
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            start = ComponentSpacing.screenPadding,
                            end = ComponentSpacing.screenPadding,
                            bottom = ComponentSpacing.screenPadding,
                            top = 0.dp
                        )
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

                    // Processing indicator
                    // Processing indicator REMOVED as per user request (shimmer is sufficient)
                    // (AnimatedVisibility block was here)

                    // Floating Input Field (Blue blur glow removed - only halftone particles visible now)
                    Box(contentAlignment = Alignment.Center) {
                        // The Actual Input Field with halftone shimmer inside
                        JarvisInputField(
                            value = textValue,
                            aiPlanStatus = aiPlanStatus,
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
                                val text = textValue.text
                                if (text.isNotBlank() || attachments.isNotEmpty()) {
                                    if (isChatMode) {
                                        onSendChatMessage(text, attachments)
                                        chatModeTextValue = TextFieldValue("")  // Only clear chat mode text
                                    } else if (!isSearchMode) {
                                        onAddNote(text, attachments)
                                        normalModeTextValue = TextFieldValue("")  // Only clear normal mode text
                                    }

                                    if (!isSearchMode) {
                                        onInputTextChange("")
                                        attachments = emptyList()
                                    }
                                }
                            },
                            attachments = attachments,
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
                            onRemoveAttachment = { id -> attachments = attachments.filter { it.id != id } },
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
                                attachments = emptyList()
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
                            currentTool = currentToolName,
                            isAgentWorking = isChatProcessing,
                            autoSendActive = autoSendActive,
                            // Search filter parameters
                            selectedFilters = selectedFilters,
                            onFilterToggle = onFilterToggle,
                            onClearFilters = onClearFilters,
                            // Voice recording (hold mic to record, release to stop)
                            onStartRecording = {
                                // Stop any active speech reJarvistion before recording
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
                            }
                        )
                    }
                }
            }

        }
    }


    // Delete confirmation dialog
    if (showDeleteDialog && noteToDelete != null) {
        com.example.smarty.ui.components.common.JarvisDialog(
            title = "Delete Note?",
            text = "This action cannot be undone. The note and all its todos will be permanently deleted.",
            onConfirm = {
                noteToDelete?.let { onDeleteNote(it.id) }
                showDeleteDialog = false
                noteToDeleteId = null
            },
            onDismiss = {
                showDeleteDialog = false
                noteToDeleteId = null
            },
            confirmText = "Delete",
            dismissText = "Cancel",
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

    // ═══════════════════════════════════════════════════════════════════════════
    // CENTRALIZED UI: Bottom Sheets for all features
    // ═══════════════════════════════════════════════════════════════════════════

    // Calendar Sheet
    if (showCalendarSheet) {
        CalendarSheet(
            events = calendarEvents,
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
                activeCategoryFilter = category
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
            providerConfigs = providerConfigs,
            providerPriorityOrder = providerPriorityOrder,
            isDarkTheme = isDarkTheme,
            onAddApiKey = onAddApiKey,
            onRemoveApiKey = onRemoveApiKey,
            onUpdateApiKey = onUpdateApiKey,
            onSetProviderEnabled = onSetProviderEnabled,
            onSetSelectedModel = onSetSelectedModel,
            onSetProviderPriority = onSetProviderPriority,
            onTestApiKey = onTestApiKey,
            onToggleTheme = onToggleTheme,
            tavilyApiKeys = tavilyApiKeys,
            onAddTavilyApiKey = onAddTavilyApiKey,
            onRemoveTavilyApiKey = onRemoveTavilyApiKey,
            cacheSizeBytes = cacheSizeBytes,
            onClearCache = onClearCache,
            isClearingCache = isClearingCache,
            shakeSensitivity = shakeSensitivity,
            onShakeSensitivityChange = onShakeSensitivityChange,
            groqKeyUsageStats = groqKeyUsageStats,
            onRefreshModels = onRefreshModels,
            getAvailableModels = getAvailableModels,
            onSignOut = onSignOut,
            archiveContent = archiveContentForSettings,
            backupContent = backupContent,
            // AI Memory
            aiMemories = aiMemories,
            onDeleteAIMemory = onDeleteAIMemory,
            onClearAllAIMemories = onClearAllAIMemories,
            onSyncAIMemories = onSyncAIMemories,
            isMemorySyncInProgress = isMemorySyncInProgress,
            memorySyncResult = memorySyncResult,
            unreadForMemoryCount = unreadForMemoryCount,
            onClearMemorySyncResult = onClearMemorySyncResult
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
}
}


/**
 * Displays a dropdown of recent search suggestions.
 * Shows when search field is focused and has suggestions available.
 */
@Composable
private fun SearchSuggestionsDropdown(
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
                text = "Recent searches",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onClearHistory) {
                Text("Clear")
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
                    contentDescription = null,
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
 * Mesh Spill Effect: A subtle, cloud-like gradient background for both top and bottom.
 * Replicates the "ChatGPT" look with overlapping radial gradients.
 */
@Composable
internal fun MeshSpillEffect(modifier: Modifier = Modifier) {
    val accentColor = LocalAccentColor.current
    // Use Canvas to draw blurred circles at the top and bottom
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 0.85f } // Slight transparency adjustment
    ) {
        // ══════════════════════════════════════════════════════════════
        // TOP CIRCLES
        // ══════════════════════════════════════════════════════════════
        // Circle 1: Top Left - Larger, more diffuse with smooth multi-stop gradient
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to accentColor.copy(alpha = 0.12f),
                    0.2f to accentColor.copy(alpha = 0.10f),
                    0.4f to accentColor.copy(alpha = 0.07f),
                    0.6f to accentColor.copy(alpha = 0.04f),
                    0.8f to accentColor.copy(alpha = 0.01f),
                    1.0f to Color.Transparent
                ),
                center = androidx.compose.ui.geometry.Offset(x = size.width * 0.2f, y = 0f),
                radius = size.width * 1.0f
            )
        )
        // Circle 2: Top Right - Softer highlight with smooth fade
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to accentColor.copy(alpha = 0.10f),
                    0.25f to accentColor.copy(alpha = 0.07f),
                    0.5f to accentColor.copy(alpha = 0.04f),
                    0.75f to accentColor.copy(alpha = 0.015f),
                    1.0f to Color.Transparent
                ),
                center = androidx.compose.ui.geometry.Offset(x = size.width * 0.8f, y = size.height * 0.1f),
                radius = size.width * 0.9f
            )
        )

        // ══════════════════════════════════════════════════════════════
        // BOTTOM CIRCLES (Mirrored)
        // ══════════════════════════════════════════════════════════════
        // Circle 3: Bottom Right - Larger, more diffuse
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to accentColor.copy(alpha = 0.12f),
                    0.2f to accentColor.copy(alpha = 0.10f),
                    0.4f to accentColor.copy(alpha = 0.07f),
                    0.6f to accentColor.copy(alpha = 0.04f),
                    0.8f to accentColor.copy(alpha = 0.01f),
                    1.0f to Color.Transparent
                ),
                center = androidx.compose.ui.geometry.Offset(x = size.width * 0.8f, y = size.height),
                radius = size.width * 1.0f
            )
        )
        // Circle 4: Bottom Left - Softer highlight with smooth fade
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to accentColor.copy(alpha = 0.10f),
                    0.25f to accentColor.copy(alpha = 0.07f),
                    0.5f to accentColor.copy(alpha = 0.04f),
                    0.75f to accentColor.copy(alpha = 0.015f),
                    1.0f to Color.Transparent
                ),
                center = androidx.compose.ui.geometry.Offset(x = size.width * 0.2f, y = size.height * 0.9f),
                radius = size.width * 0.9f
            )
        )
    }
}
