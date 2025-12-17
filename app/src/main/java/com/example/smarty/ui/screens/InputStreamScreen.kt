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
import androidx.compose.ui.unit.dp
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
import com.example.smarty.ui.components.ChatEmptyState
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

    // Scroll to bottom when new chat message arrives
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty() && isChatMode) {
            chatListState.animateScrollToItem(chatMessages.size - 1)
        }
    }

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
                        
                        Text(
                            text = "UNDO",
                            color = LocalAccentColor.current,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .clickable {
                                    lastArchivedNoteId?.let { noteId ->
                                        onUnarchiveNote(noteId)
                                        lastArchivedNoteId = null
                                    }
                                    data.dismiss()
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        },
        topBar = {
            AnimatedContent(
                targetState = isSelectionMode,
                transitionSpec = {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(150))
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
                    // Custom Premium Header
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .height(64.dp)
                            .padding(horizontal = 24.dp) // More breathing room
                    ) {
                        // Branding (Left)
                        CogniHeader(
                            modifier = Modifier.align(Alignment.CenterStart),
                            showShimmer = true
                        )

                        // Actions Pill (Right)
                        Surface(
                            modifier = Modifier.align(Alignment.CenterEnd),
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), // Glassy effect
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
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
                }
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                color = androidx.compose.ui.graphics.Color.Transparent,
                tonalElevation = 0.dp
            ) {
                // Golden ratio: padding 16dp, gaps 13dp/8dp (Fibonacci derived)
                Column(
                    modifier = Modifier.padding(ComponentSpacing.screenPadding)
                ) {
                    // Processing indicator - show for both note processing and chat processing
                    AnimatedVisibility(
                        visible = isProcessing || isChatProcessing,
                        enter = fadeIn(tween(200)) + expandVertically(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        ),
                        exit = fadeOut(tween(150)) + shrinkVertically()
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
                                } else {
                                    onAddNote(inputText, attachments)
                                }
                                inputText = ""
                                onInputTextChange("")
                                attachments = emptyList()
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
                        onRemoveAttachment = { id ->
                            attachments = attachments.filter { it.id != id }
                        },
                        isChatMode = isChatMode,
                        isProcessing = isChatProcessing,
                        onOpenChatHistory = { showChatHistorySheet = true },
                        isAiExcluded = isAiExcluded
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        // Clear focus when tapping outside the input field
        val interactionSource = remember { MutableInteractionSource() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
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
                    fadeIn(tween(300)) togetherWith fadeOut(tween(200))
                },
                label = "chatModeTransition"
            ) { showChat ->
                if (showChat) {
                    // Chat messages view
                    if (chatMessages.isEmpty()) {
                        ChatEmptyState(modifier = Modifier.fillMaxSize())
                    } else {
                        LazyColumn(
                            state = chatListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = ComponentSpacing.listContentPadding,
                                bottom = ComponentSpacing.listContentPadding
                            ),
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
                    if (notes.isEmpty()) {
                        com.example.smarty.ui.components.NotesEmptyState(modifier = Modifier.fillMaxSize())
                    } else {
                        // Golden ratio: list padding 16dp, item gap 13dp (Fibonacci)
                        LazyColumn(
                            state = listState,
                            modifier = modifier.fillMaxSize(),
                            contentPadding = PaddingValues(ComponentSpacing.listContentPadding),
                            verticalArrangement = Arrangement.spacedBy(ComponentSpacing.listItemGap)
                        ) {
                            itemsIndexed(
                                items = notes,
                                key = { _, note -> note.id }
                            ) { index, note ->
                                // Wrapper box for placement animation
                                Box(modifier = Modifier.animateItem()) {
                                    // Staggered entry animation for each item
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
                                            // Archive with snackbar undo
                                            lastArchivedNoteId = note.id
                                            onArchiveNote(note.id)
                                            scope.launch {
                                                val result = snackbarHostState.showSnackbar(
                                                    message = "Note archived",
                                                    duration = SnackbarDuration.Short
                                                )
                                                // Only clear the ID after snackbar is dismissed (not when undo is clicked)
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

            // Alphabet fast scroller overlay (only show when notes exist and not in chat mode)
            if (notes.isNotEmpty() && !isChatMode) {
                AlphabetFastScroller(
                    notes = notes,
                    lazyListState = listState,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
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

    // Scale animation with spring
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.85f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 300f
        ),
        label = "itemScale"
    )

    // Alpha animation
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(200, easing = CogniEasing.appleEaseOut),
        label = "itemAlpha"
    )

    // Slide up animation
    val offsetY by animateFloatAsState(
        targetValue = if (appeared) 0f else 20f,
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
        index = index,
        isArchiveView = false,  // Main view: swipe right = archive
        onArchive = onArchive,
        isSelected = isSelected,
        isSelectionMode = isSelectionMode,
        onLongPress = onLongPress,
        onPlayYouTube = onPlayYouTube,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                translationY = offsetY
            }
    )
}
