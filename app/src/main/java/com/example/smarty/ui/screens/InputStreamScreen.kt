package com.example.smarty.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.smarty.data.model.Attachment
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.TodoItem
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.CogniEasing
import com.example.smarty.ui.animation.StaggerCalculator
import com.example.smarty.ui.animation.rememberShakeGlowState
import com.example.smarty.ui.animation.shakeGlowEffect
import com.example.smarty.data.model.ChatSession
import com.example.smarty.ui.components.ChatHistorySheet
import com.example.smarty.ui.components.ChatMessageItem
import com.example.smarty.ui.components.CogniInputField
import com.example.smarty.ui.components.NoteCard
import com.example.smarty.ui.components.NoteTodoSheet
import com.example.smarty.ui.components.ChatEmptyState
import com.example.smarty.data.model.NoteType
import com.example.smarty.ui.components.AttachmentOption
import com.example.smarty.ui.components.SearchFilterTypeSelector
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.example.smarty.ui.components.PendingShareData
import com.example.smarty.ui.components.ProcessingDotsIndicator
import com.example.smarty.ui.components.ShareBottomSheet
import com.example.smarty.ui.components.getNoteTypeIcon
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.SafetyOrange
import com.example.smarty.ui.components.SearchEmptyState
import com.example.smarty.ui.components.ShakeCloudEffect
import com.example.smarty.ui.components.NotesLoadingState
import com.example.smarty.ui.components.ConnectionStatus
import com.example.smarty.ui.components.ConnectionStatusIndicator
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState

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
    onExitChatMode: () -> Unit = {},  // Back button handler for chat mode
    onSendChatMessage: (String, List<Attachment>) -> Unit = { _, _ -> },
    // Chat history parameters
    chatSessions: List<ChatSession> = emptyList(),
    currentSessionId: String? = null,
    onSwitchChatSession: (String) -> Unit = {},
    onNewChatSession: () -> Unit = {},
    onDeleteChatSession: (String) -> Unit = {},
    // AI exclusion parameters
    isAiExcluded: Boolean = false,
    onInputTextChange: (String) -> Unit = {},
    onInputAttachmentsChange: (List<Attachment>) -> Unit = {},
    onPlayYouTube: (String) -> Unit = {},
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    externalSpeechState: com.example.smarty.util.SpeechToTextState? = null,
    speechResults: kotlinx.coroutines.flow.Flow<String>? = null,
    
    // Search and Filter (Backend Integration)
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    selectedFilters: Set<AttachmentOption> = emptySet(),
    onFilterToggle: (AttachmentOption) -> Unit = {},
    onClearFilters: () -> Unit = {},

    wasShakeTriggered: Boolean = false,  // For border glow animation on mode switch
    connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTED,  // Phase 7
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

    val listState = rememberLazyListState()
    val chatListState = rememberLazyListState()

    // Snackbar state for undo archive
    val snackbarHostState = remember { SnackbarHostState() }
    var lastArchivedNoteId by remember { mutableStateOf<String?>(null) }

    // Shake glow animation state
    val shakeGlowState = rememberShakeGlowState()
    val accentColor = LocalAccentColor.current

    // Trigger glow when shake mode switch detected
    LaunchedEffect(wasShakeTriggered) {
        if (wasShakeTriggered) {
            shakeGlowState.triggerGlow()
        }
    }

    // Voice Input State (Speech-to-Text) - Use external from MainActivity
    val speechState = externalSpeechState ?: com.example.smarty.util.rememberSpeechToText(
        onResult = { /* Handled by global flow */ }
    )

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
                val baseText = currentText.take(partialTextStartIndex)
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
            val baseText = if (lastPartialText.isNotEmpty()) {
                currentText.take(partialTextStartIndex)
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
    val displayedNotes = notes

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
    
    // Select all visible notes
    fun selectAllNotes() {
        selectedNoteIds = displayedNotes.map { it.id }.toSet()
        isSelectionMode = true
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

    // Handle back button press - exit selection mode instead of closing app
    BackHandler(enabled = isSelectionMode) {
        clearSelection()
    }

    // Handle back button press - exit chat mode and return to main page
    // This only triggers when in chat mode and NOT in selection mode
    BackHandler(enabled = isChatMode && !isSelectionMode) {
        onExitChatMode()
    }

    // Todo sheet state
    var selectedNoteForTodo by remember { mutableStateOf<Note?>(null) }
    val todoSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Chat history sheet state
    var showChatHistorySheet by remember { mutableStateOf(false) }
    val chatHistorySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Delete confirmation state
    var noteToDelete by remember { mutableStateOf<Note?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Helper function to get file info from URI
    fun getFileInfo(uri: Uri): Attachment? {
        return try {
            var fileName: String? = null
            var fileSize: Long = 0
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

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

            // Safe fallback chain - never force unwrap
            val safeName = fileName
                ?: uri.lastPathSegment
                ?: "Unknown_${System.currentTimeMillis()}"

            Attachment(
                uri = uri,
                fileName = safeName,
                mimeType = mimeType,
                fileSize = fileSize
            )
        } catch (e: Exception) {
            null
        }
    }

    // File picker launchers
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.mapNotNull { getFileInfo(it) }.let { newAttachments ->
            attachments = attachments + newAttachments
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.mapNotNull { getFileInfo(it) }.let { newAttachments ->
            attachments = attachments + newAttachments
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.mapNotNull { getFileInfo(it) }.let { newAttachments ->
            attachments = attachments + newAttachments
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.mapNotNull { getFileInfo(it) }.let { newAttachments ->
            attachments = attachments + newAttachments
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.mapNotNull { getFileInfo(it) }.let { newAttachments ->
            attachments = attachments + newAttachments
        }
    }

    // Scroll to top when new note is added
    LaunchedEffect(notes.size) {
        if (notes.isNotEmpty() && !isChatMode) {
            listState.animateScrollToItem(0)
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
            .shakeGlowEffect(
                isActive = shakeGlowState.isGlowing,
                glowColor = if (isChatMode) accentColor else MaterialTheme.colorScheme.primary,
                onAnimationComplete = { shakeGlowState.onGlowComplete() }
            )
    ) {
        // Shake cloud effect overlay (0.4s expand/contract from edges)
        ShakeCloudEffect(
            isVisible = wasShakeTriggered,
            modifier = Modifier.fillMaxSize()
        )
        
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
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
                    // Selection mode top bar
                    TopAppBar(
                        title = {
                            Text(
                                text = "${selectedNoteIds.size} selected",
                                style = MaterialTheme.typography.titleLarge
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { clearSelection() }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel selection",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        actions = {
                            // Select All button
                            IconButton(
                                onClick = { selectAllNotes() }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Done,
                                    contentDescription = "Select all",
                                    tint = LocalAccentColor.current
                                )
                            }
                            
                            // Archive button
                            IconButton(
                                onClick = { archiveSelected() },
                                enabled = selectedNoteIds.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Archive,
                                    contentDescription = "Archive selected",
                                    tint = if (selectedNoteIds.isNotEmpty())
                                        LocalAccentColor.current
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            // Delete button
                            IconButton(
                                onClick = { deleteSelected() },
                                enabled = selectedNoteIds.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Delete selected",
                                    tint = if (selectedNoteIds.isNotEmpty())
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                } else {
                    // Custom Premium Header with Categories
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        // Top Navigation Row
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(top = 12.dp)
                                .height(64.dp)
                                .padding(horizontal = 24.dp)
                        ) {
                            // Calendar Button (Left)
                            val isDarkLeft = isSystemInDarkTheme()
                            Surface(
                                modifier = Modifier.align(Alignment.CenterStart),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isDarkLeft) {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                    }
                                )
                            ) {
                                IconButton(
                                    onClick = onNavigateToCalendar,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CalendarMonth,
                                        contentDescription = "Calendar",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            // Connection Status Indicator (Center) - Phase 7
                            ConnectionStatusIndicator(
                                status = connectionStatus,
                                modifier = Modifier.align(Alignment.Center)
                            )

                            // Actions Pill (Right)
                            val isDark = isSystemInDarkTheme()
                            Surface(
                                modifier = Modifier.align(Alignment.CenterEnd),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isDark) {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                    }
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    // Stacks Button
                                    IconButton(
                                        onClick = onNavigateToStacks,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.GridView,
                                            contentDescription = "View Stacks",
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    // Vertical Divider
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height(16.dp)
                                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                    )

                                    // Settings Button
                                    IconButton(
                                        onClick = onNavigateToSettings,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = "Settings",
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Note Type Filter Chips (category selection for filtering notes by type)
                        // Only show in main mode when NOT in search mode
                        // (when search mode is active, the bottom pill becomes the filter)
                        SearchFilterTypeSelector(
                            visible = !isChatMode && !isSearchMode,
                            selectedFilters = selectedFilters,
                            onFilterToggle = onFilterToggle,
                            onClearFilters = onClearFilters,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).padding(horizontal = 16.dp)
                        )
                    } // End Column
                }
            }
        },
        bottomBar = {
            // Deprecated: Input field moved to main Box for floating transparency
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        // Clear focus when tapping outside the input field
        val topPadding = paddingValues.calculateTopPadding()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding) // Only respect top padding
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            // Only clear focus on tap - do NOT stop voice input here
                            // Voice input should only be stopped via the mic button
                            // Stopping on tap causes issues when user scrolls/touches while speaking
                            focusManager.clearFocus()
                        }
                    )
                }
        ) {
            // Animated content switching between notes and chat
            AnimatedContent(
                targetState = isChatMode,
                transitionSpec = {
                    fadeIn(tween(240)) togetherWith fadeOut(tween(160))
                },
                label = "chatModeTransition",
                modifier = Modifier.fillMaxSize()
            ) { showChat ->
                val contentBottomPadding = PaddingValues(
                    top = ComponentSpacing.listContentPadding,
                    bottom = 140.dp + bottomContentPadding // Extra padding for floating input
                )

                if (showChat) {
                    // Chat messages view
                    if (chatMessages.isEmpty()) {
                        ChatEmptyState(modifier = Modifier.fillMaxSize())
                    } else {
                        LazyColumn(
                            state = chatListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = contentBottomPadding,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(
                                items = chatMessages,
                                key = { it.id },
                                contentType = { it.role }
                            ) { message ->
                                // Stabilize getNote lambda - only recreate when notes change
                                val stableGetNote = remember(notes) {
                                    { id: String -> notes.find { it.id == id } }
                                }
                                ChatMessageItem(
                                    message = message,
                                    getNote = stableGetNote,
                                    onNoteClick = onNoteClick,
                                    onSuggestionClick = { suggestion ->
                                        // Send the clicked suggestion as a new message
                                        onSendChatMessage(suggestion, emptyList())
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // Notes list view
                    if (isNotesLoading) {
                        // Show skeleton loaders while notes are loading (Phase 8)
                        NotesLoadingState(
                            count = 5,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = ComponentSpacing.listContentPadding)
                        )
                    } else if (displayedNotes.isEmpty()) {
                        if (isSearchMode) {
                             // Animated search empty state with query display
                             SearchEmptyState(
                                 searchQuery = textValue.text,
                                 modifier = Modifier.fillMaxSize()
                             )
                        } else {
                            com.example.smarty.ui.components.NotesEmptyState(modifier = Modifier.fillMaxSize())
                        }
                    } else {
                        // Custom fling behavior - caps max scroll speed, natural momentum continues
                        // Calculation: Card height ~120dp ≈ 360px (at 3x density)
                        // For card to be "visible" it needs ~150ms on screen
                        // Max velocity = screen_height / time = ~2400px / 0.15s ≈ 4000-5000 px/s
                        // Using 4500f ensures at least one card is always readable during scroll
                        val cappedFling = rememberCappedFlingBehavior(
                            maxVelocity = 50000f   // Ensures cards are visible even at max speed
                        )

                        // Pull-to-refresh wrapper
                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = onRefreshNotes,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                flingBehavior = cappedFling,  // Apply velocity cap
                                contentPadding = PaddingValues(
                                    top = ComponentSpacing.listContentPadding,
                                    bottom = 140.dp + bottomContentPadding,
                                    start = 16.dp,
                                    end = 16.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(ComponentSpacing.listItemGap)
                            ) {
                            itemsIndexed(
                                items = displayedNotes,
                                key = { _, note -> note.id },
                                contentType = { _, note -> note.processingStatus }
                            ) { index, note ->
                                // Stabilize lambdas to prevent recomposition
                                val stableOnClick = remember(note.id, isSelectionMode) {
                                    {
                                        if (isSelectionMode) toggleSelection(note.id)
                                        else onNoteClick(note)
                                    }
                                }
                                val stableOnDelete = remember(note) {
                                    {
                                        noteToDelete = note
                                        showDeleteDialog = true
                                    }
                                }
                                val stableOnOpenTodo = remember(note) {
                                    { selectedNoteForTodo = note }
                                }
                                val stableOnArchive: () -> Unit = remember(note.id) {
                                    {
                                        lastArchivedNoteId = note.id
                                        onArchiveNote(note.id)
                                        scope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                message = "Note archived",
                                                duration = SnackbarDuration.Short
                                            )
                                            if (result == SnackbarResult.Dismissed && lastArchivedNoteId == note.id) {
                                                lastArchivedNoteId = null
                                            }
                                        }
                                    }
                                }
                                val stableOnLongPress = remember(note.id) {
                                    {
                                        isSelectionMode = true
                                        selectedNoteIds = setOf(note.id)
                                    }
                                }
                                val isNoteSelected = remember(selectedNoteIds, note.id) {
                                    note.id in selectedNoteIds
                                }

                                Box(modifier = Modifier.animateItem()) {
                                    AnimatedNoteItem(
                                        note = note,
                                        index = index,
                                        onClick = stableOnClick,
                                        onDelete = stableOnDelete,
                                        onOpenTodo = stableOnOpenTodo,
                                        onArchive = stableOnArchive,
                                        isSelected = isNoteSelected,
                                        isSelectionMode = isSelectionMode,
                                        onLongPress = stableOnLongPress,
                                        onPlayYouTube = onPlayYouTube,
                                        searchQuery = if (isSearchMode) textValue.text else null
                                    )
                                }
                            }
                        }
                        } // End PullToRefreshBox
                    }
                }
            }



            // Floating Input Field Container (Overlaying content)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = bottomContentPadding)
                    .navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier.padding(
                        start = ComponentSpacing.screenPadding,
                        end = ComponentSpacing.screenPadding,
                        bottom = ComponentSpacing.screenPadding,
                        top = 0.dp
                    )
                ) {
                    // Processing indicator
                    AnimatedVisibility(
                        visible = isProcessing || isChatProcessing,
                        enter = fadeIn(tween(160)) + expandVertically(),
                        exit = fadeOut(tween(120)) + shrinkVertically()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = ComponentSpacing.cardHeaderGap),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProcessingDotsIndicator()
                            Spacer(modifier = Modifier.width(ComponentSpacing.iconGap))
                            Text(
                                text = if (isChatMode) "Thinking..." else "Processing...",
                                style = MaterialTheme.typography.labelMedium,
                                color = LocalAccentColor.current
                            )
                        }
                    }

                    // Floating Input Field with Blue Glow Pop
                    Box(contentAlignment = Alignment.Center) {
                        // 1. Blue Blur Glow (The "Pop")
                        // Visible mostly on newer Android versions that support RenderEffect well,
                        // creates a soft colored shadow/glow behind the pill.
                        if (android.os.Build.VERSION.SDK_INT >= 31) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp) // Approximate height of the input pill
                                    .graphicsLayer {
                                        scaleX = 0.98f
                                        scaleY = 0.85f
                                        alpha = 1f
                                        renderEffect = android.graphics.RenderEffect
                                            .createBlurEffect(
                                                50f,
                                                50f,
                                                android.graphics.Shader.TileMode.DECAL
                                            )
                                            .asComposeRenderEffect()
                                    }
                                    .background(LocalAccentColor.current, androidx.compose.foundation.shape.RoundedCornerShape(50))
                            )
                        }

                        // 2. The Actual Input Field
                        CogniInputField(
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
                            onPickImage = { imagePickerLauncher.launch("image/*") },
                            onPickVideo = { videoPickerLauncher.launch("video/*") },
                            onPickDocument = {
                                documentPickerLauncher.launch(
                                    arrayOf(
                                        "application/pdf",
                                        "application/msword",
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "text/plain"
                                    )
                                )
                            },
                            onPickAudio = { audioPickerLauncher.launch("audio/*") },
                            onPickFile = { filePickerLauncher.launch("*/*") },
                            onRemoveAttachment = { id -> attachments = attachments.filter { it.id != id } },
                            isChatMode = isChatMode,
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
                            onOpenChatHistory = { showChatHistorySheet = true },
                            isAiExcluded = isAiExcluded,
                            isSearchMode = isSearchMode,
                            onToggleSearch = {
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
                            onClearFilters = onClearFilters
                        )
                    }
                }
            }

        }
    }


    // Delete confirmation dialog
    if (showDeleteDialog && noteToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                noteToDelete = null
            },
            title = {
                Text(
                    text = "Delete Note?",
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    text = "This action cannot be undone. The note and all its todos will be permanently deleted.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        noteToDelete?.let { onDeleteNote(it.id) }
                        showDeleteDialog = false
                        noteToDelete = null
                    }
                ) {
                    Text("Delete", color = SafetyOrange)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        noteToDelete = null
                    }
                ) {
                    Text("Cancel")
                }
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(ComponentSpacing.cardCornerRadius)
        )
    }

    // Todo bottom sheet - auto-saves on every action
    selectedNoteForTodo?.let { note ->
        NoteTodoSheet(
            note = note,
            sheetState = todoSheetState,
            onDismiss = { selectedNoteForTodo = null },
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

    // Chat history bottom sheet
    if (showChatHistorySheet) {
        ChatHistorySheet(
            sessions = chatSessions,
            currentSessionId = currentSessionId,
            sheetState = chatHistorySheetState,
            onDismiss = { showChatHistorySheet = false },
            onSelectSession = { sessionId ->
                onSwitchChatSession(sessionId)
            },
            onNewChat = onNewChatSession,
            onDeleteSession = onDeleteChatSession
        )
    }
}
}


/**
 * Animated note item with staggered entry animation
 * Uses spring physics for natural feel
 */
@Composable
private fun AnimatedNoteItem(
    note: Note,
    index: Int,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onOpenTodo: () -> Unit,
    onArchive: () -> Unit,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onLongPress: () -> Unit = {},
    onPlayYouTube: (String) -> Unit = {},
    searchQuery: String? = null
) {
    // Track if item has appeared
    var appeared by remember { mutableStateOf(false) }

    // Staggered delay based on index - first 5 items animate, rest appear instantly
    val staggerDelay = if (index < 5) StaggerCalculator.logarithmic(index, 40) else 0

    LaunchedEffect(Unit) {
        delay(staggerDelay.toLong())
        appeared = true
    }

    // Scale animation with spring - modified for "Orb Pop" effect
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.4f, // Start smaller (orb size)
        animationSpec = spring(
            dampingRatio = 0.6f, // Bouncy
            stiffness = 300f
        ),
        label = "itemScale"
    )

    // Alpha animation
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(240, easing = CogniEasing.appleEaseOut),
        label = "itemAlpha"
    )

    // Slide up animation - increased offset for dramatic entry
    val offsetY by animateFloatAsState(
        targetValue = if (appeared) 0f else 100f,
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = 400f
        ),
        label = "itemOffset"
    )

    NoteCard(
        note = note,
        onClick = onClick,
        onDelete = onDelete,
        onOpenTodo = onOpenTodo,
        modifier = Modifier
            .scale(scale)
            .graphicsLayer {
                this.alpha = alpha
            }
            .offset(y = offsetY.dp),
        index = index,
        onArchive = onArchive,
        isSelected = isSelected,
        isSelectionMode = isSelectionMode,
        onLongPress = onLongPress,
        onPlayYouTube = onPlayYouTube,
        searchQuery = searchQuery
    )
}


@Composable
private fun NoteTypeChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    // Premium "Pill" Aesthetic
    // Selected: Solid Black (Light Mode) / White (Dark Mode)
    // Unselected: Transparent with subtle outline
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        // Stronger contrast for unselected state: visible light background
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }
    
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.surface
    } else {
        // Darker icon for better readability
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    }
    
    Surface(
        onClick = onClick,
        shape = if (label.isEmpty()) androidx.compose.foundation.shape.CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(20.dp), // Circle for icon-only
        color = backgroundColor,
        contentColor = contentColor,
        modifier = Modifier.height(32.dp).widthIn(min = 32.dp) // Slightly bigger for touch target
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (label.isEmpty()) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label.ifEmpty { null },
                    modifier = Modifier.size(16.dp)
                )
            }
            if (label.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.2.sp
                    )
                )
            }
        }
    }
}

/**
 * Custom FlingBehavior that caps maximum scroll velocity while preserving natural momentum.
 *
 * The scroll continues naturally with standard physics, but the initial velocity
 * is capped so users can't fling faster than a certain speed.
 *
 * @param maxVelocity Maximum allowed velocity in pixels per second
 * @param defaultFlingBehavior The underlying fling behavior to delegate to
 */
private class CappedVelocityFlingBehavior(
    private val maxVelocity: Float,
    private val defaultFlingBehavior: FlingBehavior
) : FlingBehavior {

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        // Cap the velocity but preserve direction and natural momentum
        val cappedVelocity = if (abs(initialVelocity) > maxVelocity) {
            maxVelocity * sign(initialVelocity)
        } else {
            initialVelocity
        }

        // Delegate to default fling behavior with capped velocity
        // This preserves natural Android scroll physics (deceleration curve)
        return with(defaultFlingBehavior) {
            performFling(cappedVelocity)
        }
    }
}

/**
 * Remember a capped velocity fling behavior for LazyColumn/LazyRow.
 * Caps max scroll speed while keeping natural momentum and deceleration.
 */
@Composable
private fun rememberCappedFlingBehavior(
    maxVelocity: Float = 50000f  // Max pixels/second - scrolling continues, just slower
): FlingBehavior {
    // Get the default scroll behavior from the platform
    val defaultFling = androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior()

    return remember(maxVelocity, defaultFling) {
        CappedVelocityFlingBehavior(maxVelocity, defaultFling)
    }
}
