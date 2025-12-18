package com.example.smarty.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.example.smarty.ui.components.AlphabetFastScroller
import com.example.smarty.ui.components.ChatHistorySheet
import com.example.smarty.ui.components.ChatMessageItem
import com.example.smarty.ui.components.CogniHeader
import com.example.smarty.ui.components.CogniInputField
import com.example.smarty.ui.components.NoteCard
import com.example.smarty.ui.components.NoteTodoSheet
import com.example.smarty.ui.components.NoteTodoSheet
import com.example.smarty.ui.components.ChatEmptyState
import com.example.smarty.ui.components.DynamicIsland
import com.example.smarty.data.model.NoteType
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.example.smarty.ui.components.PendingShareData
import com.example.smarty.ui.components.ProcessingDotsIndicator
import com.example.smarty.ui.components.ShareBottomSheet
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.SafetyOrange
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
    onUpdateNoteTodos: (String, List<TodoItem>) -> Unit,
    onNavigateToStacks: () -> Unit,
    onNavigateToSettings: () -> Unit,
    pendingShare: PendingShareData?,
    onConfirmShare: (String?, String) -> Unit,
    onCancelShare: () -> Unit,
    // Share privacy mode (shake to activate)
    isShareFullPrivacy: Boolean = false,
    // Chat mode parameters
    isChatMode: Boolean = false,
    chatMessages: List<ChatMessage> = emptyList(),
    isChatProcessing: Boolean = false,
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
    onPlayYouTube: (String) -> Unit = {},
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var inputText by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    val listState = rememberLazyListState()
    val chatListState = rememberLazyListState()

    // Snackbar state for undo archive
    val snackbarHostState = remember { SnackbarHostState() }
    var lastArchivedNoteId by remember { mutableStateOf<String?>(null) }

    // Multi-select state
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedNoteIds by remember { mutableStateOf(setOf<String>()) }

    // Search Mode State
    var isSearchMode by remember { mutableStateOf(false) }
    
    // File Type Categorization State
    var selectedTypeFilter by remember { mutableStateOf<NoteType?>(null) }
    val availableTypes by remember(notes) {
        derivedStateOf {
            notes.map { it.type }.toSet().sortedBy { it.name }
        }
    }

    // Filtered Notes Logic
    val displayedNotes by remember(notes, inputText, isSearchMode, selectedTypeFilter) {
        derivedStateOf {
            val typeFiltered = if (selectedTypeFilter != null) {
                notes.filter { it.type == selectedTypeFilter }
            } else {
                notes
            }

            if (isSearchMode && inputText.isNotBlank()) {
                typeFiltered.filter { note ->
                    note.title.contains(inputText, ignoreCase = true) ||
                    note.content.contains(inputText, ignoreCase = true) ||
                    (note.summary?.contains(inputText, ignoreCase = true) == true)
                }
            } else {
                typeFiltered
            }
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
        selectedNoteIds.forEach { onArchiveNote(it) }
        val count = selectedNoteIds.size
        clearSelection()
        scope.launch {

            snackbarHostState.showSnackbar(
                message = "$count note${if (count > 1) "s" else ""} archived",
                duration = SnackbarDuration.Short
            )
        }
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

            if (fileName == null) fileName = uri.lastPathSegment ?: "Unknown"

            Attachment(
                uri = uri,
                fileName = fileName!!,
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

    // Dynamic Island State Logic
    var showCategoryTransient by remember { mutableStateOf(false) }
    LaunchedEffect(selectedTypeFilter) {
        showCategoryTransient = true
        kotlinx.coroutines.delay(2000)
        showCategoryTransient = false
    }

    // Scroll to bottom when new chat message arrives
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty() && isChatMode) {
            chatListState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
        modifier = Modifier.imePadding(), // This handles keyboard
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                // Custom visually pleasing Snackbar
                Surface(
                    modifier = Modifier
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                        .widthIn(max = 400.dp)
                        .wrapContentWidth(),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = data.visuals.message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                        
                        Surface(
                            onClick = {
                                lastArchivedNoteId?.let { noteId ->
                                    onUnarchiveNote(noteId)
                                    lastArchivedNoteId = null
                                }
                                data.dismiss()
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = LocalAccentColor.current.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "UNDO",
                                color = LocalAccentColor.current,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
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
                        // Top Branding Row
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .height(64.dp)
                                .padding(horizontal = 24.dp)
                        ) {
                            // Branding (Left)
                            CogniHeader(
                                modifier = Modifier.align(Alignment.CenterStart),
                                showShimmer = true
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

                        // File Type Categorization Chips
                        AnimatedVisibility(
                            visible = availableTypes.isNotEmpty(),
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item {
                                    NoteTypeChip(
                                        label = "All",
                                        isSelected = selectedTypeFilter == null,
                                        onClick = { selectedTypeFilter = null }
                                    )
                                }
                                items(availableTypes) { type ->
                                    NoteTypeChip(
                                        label = formatNoteType(type),
                                        isSelected = selectedTypeFilter == type,
                                        onClick = { selectedTypeFilter = type }
                                    )
                                }
                            }
                        }
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
        val interactionSource = remember { MutableInteractionSource() }
        val topPadding = paddingValues.calculateTopPadding()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding) // Only respect top padding
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    focusManager.clearFocus()
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
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = chatMessages,
                                key = { it.id }
                            ) { message ->
                                ChatMessageItem(message = message)
                            }
                        }
                    }
                } else {
                    // Notes list view
                    if (displayedNotes.isEmpty()) {
                        if (isSearchMode) {
                             // Simple "No results" or keep empty (user didn't specify empty state)
                             // Reusing Empty State but maybe we want a "No Results" specific one later.
                             // For now, if searching and empty, show nothing or generic.
                             // Let's just show nothing to avoid "Hello Himmu" popping up during search.
                             Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No matches found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                             }
                        } else {
                            com.example.smarty.ui.components.NotesEmptyState(modifier = Modifier.fillMaxSize())
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = modifier.fillMaxSize(),
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
                                key = { _, note -> note.id }
                            ) { index, note ->
                                Box(modifier = Modifier.animateItem()) {
                                    AnimatedNoteItem(
                                        note = note,
                                        index = index,
                                        onClick = {
                                            if (isSelectionMode) toggleSelection(note.id)
                                            else onNoteClick(note)
                                        },
                                        onDelete = {
                                            noteToDelete = note
                                            showDeleteDialog = true
                                        },
                                        onOpenTodo = { selectedNoteForTodo = note },
                                        onArchive = {
                                            lastArchivedNoteId = note.id
                                            onArchiveNote(note.id)
                                            scope.launch {
                                                val result = snackbarHostState.showSnackbar(
                                                    message = "Note archived",
                                                    duration = SnackbarDuration.Short
                                                )
                                                if (result == SnackbarResult.Dismissed) {
                                                    lastArchivedNoteId = null
                                                }
                                            }
                                        },
                                        isSelected = note.id in selectedNoteIds,
                                        isSelectionMode = isSelectionMode,
                                        onLongPress = {
                                            isSelectionMode = true
                                            selectedNoteIds = setOf(note.id)
                                        },
                                        onPlayYouTube = onPlayYouTube
                                    )
                                }
                            }
                        }
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

                    CogniInputField(
                        value = inputText,
                        onValueChange = { newText ->
                            inputText = newText
                            onInputTextChange(newText)
                        },
                        onSubmit = {
                            if (inputText.isNotBlank() || attachments.isNotEmpty()) {
                                if (isChatMode) {
                                    onSendChatMessage(inputText, attachments)
                                } else if (!isSearchMode) {
                                    onAddNote(inputText, attachments)
                                }
                                
                                if (!isSearchMode) {
                                    inputText = ""
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
                        onOpenChatHistory = { showChatHistorySheet = true },
                        isAiExcluded = isAiExcluded,
                        isSearchMode = isSearchMode,
                        onToggleSearch = {
                            isSearchMode = !isSearchMode
                            inputText = "" 
                            onInputTextChange("")
                        }
                    )
                }
            }

            // Dynamic Island moved to top z-order
        }
    }

    // Dynamic Island (Floats on top, unclipped)
    if (!isSelectionMode && !isChatMode) {
        // MANUAL POSITION ADJUSTMENT: Tweak this to match punch hole
        val verticalOffset = (10).dp 

        // Compute Island State
        val islandState = when {
            isProcessing -> com.example.smarty.ui.components.DynamicIslandState.Processing
            showCategoryTransient -> {
                val categoryName = selectedTypeFilter?.let { formatNoteType(it) } ?: "All"
                val count = displayedNotes.size
                com.example.smarty.ui.components.DynamicIslandState.Info(
                    label = categoryName,
                    secondaryLabel = count.toString(),
                    icon = if (selectedTypeFilter == null) Icons.Default.GridView else null
                )
            }
            else -> com.example.smarty.ui.components.DynamicIslandState.Contracted
        }

        DynamicIsland(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = verticalOffset)
                .padding(top = 0.dp),
            state = islandState
        )
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

    // Todo bottom sheet
    selectedNoteForTodo?.let { note ->
        NoteTodoSheet(
            note = note,
            sheetState = todoSheetState,
            onDismiss = { selectedNoteForTodo = null },
            onSaveTodos = { todos ->
                onUpdateNoteTodos(note.id, todos)
                selectedNoteForTodo = null
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
    onPlayYouTube: (String) -> Unit = {}
) {
    // Track if item has appeared
    var appeared by remember { mutableStateOf(false) }

    // Staggered delay based on index - first 5 items animate, rest appear instantly
    val staggerDelay = if (index < 5) StaggerCalculator.logarithmic(index, 40) else 0

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(staggerDelay.toLong())
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
        onPlayYouTube = onPlayYouTube
    )
}


@Composable
private fun NoteTypeChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = if (isSelected) LocalAccentColor.current else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        contentColor = if (isSelected) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

private fun formatNoteType(type: NoteType): String {
    return when (type) {
        NoteType.BRAIN_DUMP -> "Notes"
        NoteType.IMAGE -> "Images"
        NoteType.VIDEO -> "Videos"
        NoteType.AUDIO -> "Audio"
        NoteType.DOCUMENT -> "Docs"
        NoteType.YOUTUBE -> "YouTube"
        NoteType.WEBSITE -> "Links"
        NoteType.CODE -> "Code"
        NoteType.TWITTER -> "X / Twitter"
        NoteType.INSTAGRAM -> "Instagram"
        NoteType.SPREADSHEET -> "Sheets"
        NoteType.PRESENTATION -> "Slides"
        NoteType.APK -> "APKs"
        NoteType.ARCHIVE -> "Archives"
        NoteType.FILE -> "Files"
    }
}
