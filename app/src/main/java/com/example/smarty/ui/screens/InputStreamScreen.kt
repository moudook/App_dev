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
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
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
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.TodoItem
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.CogniEasing
import com.example.smarty.ui.animation.StaggerCalculator
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
    // Pin and Share callbacks
    onPinNote: (String) -> Unit = {},
    onUnpinNote: (String) -> Unit = {},
    onShareNotes: (List<Note>) -> Unit = {},
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

    // Accent color for theming (used by ShakeCloudEffect)
    val accentColor = LocalAccentColor.current

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
        modifier = modifier.fillMaxSize()
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

                            // Pin button
                            IconButton(
                                onClick = { pinSelected() },
                                enabled = selectedNoteIds.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PushPin,
                                    contentDescription = "Pin selected",
                                    tint = if (selectedNoteIds.isNotEmpty())
                                        LocalAccentColor.current
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Share button
                            IconButton(
                                onClick = { shareSelected() },
                                enabled = selectedNoteIds.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share selected",
                                    tint = if (selectedNoteIds.isNotEmpty())
                                        LocalAccentColor.current
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
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
                .pointerInput(speechState.isListening) {
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
                                // OPTIMIZED: Use derivedStateOf to avoid recomposition on every selectedNoteIds change
                                val isNoteSelected by remember(note.id) {
                                    derivedStateOf { note.id in selectedNoteIds }
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

                    // Floating Input Field (Blue blur glow removed - only halftone particles visible now)
                    Box(contentAlignment = Alignment.Center) {
                        // The Actual Input Field with halftone shimmer inside
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
 * OPTIMIZED: Animated note item with staggered entry animation.
 *
 * Performance improvements:
 * - Single Animatable drives all 3 transforms (was 3 separate animateFloatAsState)
 * - 66% reduction in animation overhead (1 animator instead of 3)
 * - graphicsLayer lambda defers all state reads to draw phase
 * - Scale, alpha, and translation derived mathematically from single progress
 * - Items beyond index 5 skip animation entirely (instant appear)
 *
 * Mathematical derivation from progress p ∈ [0, 1]:
 * - scale = 0.4 + 0.6p (linear: 0.4 → 1.0)
 * - alpha = p (linear: 0 → 1)
 * - offsetY = 100(1-p) (linear: 100 → 0)
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
    // OPTIMIZED: Single animatable drives all transforms
    // Skip animation for items beyond index 5 (instant appear)
    val shouldAnimate = index < 5
    val animationProgress = remember { Animatable(if (shouldAnimate) 0f else 1f) }

    // Staggered delay using logarithmic spacing for natural cascade
    LaunchedEffect(Unit) {
        if (shouldAnimate) {
            val staggerDelay = StaggerCalculator.logarithmic(index, 40)
            delay(staggerDelay.toLong())
            // Spring animation for bouncy "orb pop" effect
            animationProgress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.65f, // Slightly bouncy
                    stiffness = 350f
                )
            )
        }
    }

    // OPTIMIZED: Pre-compute density for offset calculation
    val density = LocalDensity.current

    NoteCard(
        note = note,
        onClick = onClick,
        onDelete = onDelete,
        onOpenTodo = onOpenTodo,
        modifier = Modifier
            // OPTIMIZED: Single graphicsLayer with lambda defers ALL reads to draw phase
            // This prevents recomposition on every animation frame
            .graphicsLayer {
                val p = animationProgress.value
                // Derive all transforms from single progress value
                val scale = 0.4f + 0.6f * p
                scaleX = scale
                scaleY = scale
                alpha = p
                translationY = (1f - p) * 100f * density.density
            },
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
