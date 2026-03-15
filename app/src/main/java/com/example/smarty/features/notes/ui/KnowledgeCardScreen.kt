package com.example.smarty.features.notes.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.smarty.R
import com.example.smarty.core.domain.model.*
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.common.SmartyDialog
import com.example.smarty.ui.components.getNoteTypeIcon
import com.example.smarty.ui.theme.*
import com.example.smarty.ui.components.viewers.FullScreenDocumentViewer
import com.example.smarty.ui.components.viewers.FullScreenImageViewer
import com.example.smarty.ui.components.viewers.FullScreenVideoPlayer
import com.example.smarty.ui.components.markdown.MarkdownRenderer
import com.example.smarty.ui.utils.AnimationLifecycleState
import com.example.smarty.ui.utils.rememberAnimationLifecycleState
import com.example.smarty.core.common.util.ContentTypeDetector
import com.example.smarty.ui.components.audio.AudioWaveform
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun KnowledgeCardScreen(
    note: Note,
    onBackClick: () -> Unit,
    onArchiveClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onEditNote: (String, String, String, String?, String?, List<NoteAttachment>) -> Unit = { _, _, _, _, _, _ -> },
    onPlayAudio: (AudioTrack) -> Unit = {},
    onPauseAudio: () -> Unit = {},
    onSeekAudio: (Float) -> Unit = {},
    audioUiState: AudioPlayerUiState = AudioPlayerUiState(),
    onMarkAsViewed: () -> Unit = {},
    // Version history callbacks
    noteVersions: List<NoteVersion> = emptyList(),
    onLoadVersions: () -> Unit = {},
    onRestoreVersion: (String) -> Unit = {},
    isVersionsLoading: Boolean = false,
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    isMiniPlayerVisible: Boolean = false,
    // @Mention
    onAskSmarty: (() -> Unit)? = null,
    // Related Notes
    allNotes: List<Note> = emptyList(),
    onNavigateToNote: (Note) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // State
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showVersionHistory by remember { mutableStateOf(false) }

    // Edit State - Initialize with note content
    var title by remember(note.id) { mutableStateOf(note.title) }
    var content by remember(note.id) { mutableStateOf(note.content) }
    // Clean up content if it contains auto-generated headers
    LaunchedEffect(note.id) {
        if (note.content.contains("files attached:\n\n")) {
            content = ""
        }
    }

    val currentAttachments = remember(note.id) { note.getAttachments() }

    // Check if dirty
    val isModified = title != note.title || content != note.content

    // Viewer states
    var showImageViewer by remember { mutableStateOf(false) }
    var imageViewerUri by remember { mutableStateOf<String?>(null) }
    var showVideoPlayer by remember { mutableStateOf(false) }
    var videoPlayerUri by remember { mutableStateOf<String?>(null) }
    var showDocumentViewer by remember { mutableStateOf(false) }
    var documentViewerUri by remember { mutableStateOf<String?>(null) }
    var documentViewerMimeType by remember { mutableStateOf<String?>(null) }
    var documentViewerFileName by remember { mutableStateOf<String?>(null) }

    // Mark as viewed
    LaunchedEffect(note.id) {
        if (!note.isViewed) {
            onMarkAsViewed()
        }
    }

    // Auto-save on back or disposal if modified
    DisposableEffect(note.id) {
        onDispose {
            if (isModified && title.isNotBlank()) {
                onEditNote(
                    note.id,
                    title,
                    content,
                    note.summary,
                    note.whySaved,
                    currentAttachments
                )
            }
        }
    }

    // Determine Accent Color
    val monochromeColor = rememberMonochromeAccent()

    CompositionLocalProvider(LocalAccentColor provides monochromeColor) {
        Scaffold(
            topBar = {
                KnowledgeTopBar(
                    isModified = isModified,
                    onBack = onBackClick,
                    onSave = {
                        focusManager.clearFocus()
                        onEditNote(
                            note.id,
                            title,
                            content,
                            note.summary,
                            note.whySaved,
                            currentAttachments
                        )
                    },
                    onArchive = onArchiveClick,
                    onDelete = { showDeleteDialog = true },
                    onHistory = {
                        onLoadVersions()
                        showVersionHistory = true
                    },
                    onAskSmarty = onAskSmarty
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.statusBars
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 100.dp + bottomContentPadding), // Space for FAB/Player
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // 1. Title Input (Huge, Bold)
                    BasicTextField(
                        value = title,
                        onValueChange = { title = it },
                        textStyle = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        ),
                        cursorBrush = SolidColor(LocalAccentColor.current),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        decorationBox = { innerTextField ->
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (title.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.title_placeholder),
                                        style = MaterialTheme.typography.displaySmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    // 2. Inline Audio Player (if applicable)
                    val audioAttachment = currentAttachments.find { it.mimeType.startsWith("audio/") }
                    if (note.type == NoteType.AUDIO || audioAttachment != null) {
                        val trackUri = audioAttachment?.uri ?: note.fileUri ?: ""
                        if (trackUri.isNotEmpty()) {
                            InlineAudioPlayer(
                                trackUri = trackUri,
                                trackTitle = note.title,
                                isPlaying = audioUiState.isPlaying && audioUiState.currentTrack?.uri == trackUri,
                                progress = if (audioUiState.currentTrack?.uri == trackUri) audioUiState.progress else 0f,
                                waveform = if (audioUiState.currentTrack?.uri == trackUri) audioUiState.waveformData else emptyList(),
                                duration = if (audioUiState.currentTrack?.uri == trackUri) audioUiState.durationFormatted else formatDuration(0L),
                                currentTime = if (audioUiState.currentTrack?.uri == trackUri) audioUiState.currentPositionFormatted else "0:00",
                                onPlayPause = {
                                    if (audioUiState.isPlaying && audioUiState.currentTrack?.uri == trackUri) {
                                        onPauseAudio()
                                    } else {
                                        onPlayAudio(
                                            AudioTrack(
                                                uri = trackUri,
                                                title = note.title,
                                                fileName = audioAttachment?.fileName,
                                                sourceNoteId = note.id,
                                                mimeType = "audio/mpeg"
                                            )
                                        )
                                    }
                                },
                                onSeek = onSeekAudio
                            )
                        }
                    }

                    // 3. Attachments Grid
                    if (currentAttachments.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            currentAttachments.forEach { attachment ->
                                if (!attachment.mimeType.startsWith("audio/")) { // Hide audio as it is shown in player
                                    AttachmentChip(
                                        attachment = attachment,
                                        onClick = {
                                            val mime = attachment.mimeType
                                            when {
                                                mime.startsWith("image/") -> {
                                                    imageViewerUri = attachment.uri
                                                    showImageViewer = true
                                                }
                                                mime.startsWith("video/") -> {
                                                    videoPlayerUri = attachment.uri
                                                    showVideoPlayer = true
                                                }
                                                else -> {
                                                    documentViewerUri = attachment.uri
                                                    documentViewerMimeType = mime
                                                    documentViewerFileName = attachment.fileName
                                                    showDocumentViewer = true
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 4. Content (Markdown Rendered)
                    if (content.isNotBlank()) {
                        MarkdownRenderer(
                            content = content,
                            isUser = false,
                            normalColor = MaterialTheme.colorScheme.onBackground,
                            boldColor = MaterialTheme.colorScheme.onBackground,
                            linkColor = LocalAccentColor.current,
                            codeColor = LocalAccentColor.current,
                            codeBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            codeBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.note_content_placeholder),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                lineHeight = 28.sp
                            )
                        )
                    }

                    // 5. Metadata / Footer
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Created ${formatDate(note.createdAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = getNoteTypeIcon(note.type),
                            contentDescription = null, // Decorative icon - note type indicator
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    } // End Theme Provider

    // Dialogs & Viewers
    if (showDeleteDialog) {
        SmartyDialog(
            title = stringResource(R.string.delete_note),
            text = stringResource(R.string.delete_note_warning),
            onConfirm = {
                showDeleteDialog = false
                onDeleteClick()
            },
            onDismiss = { showDeleteDialog = false },
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            isDestructive = true
        )
    }

    if (showImageViewer && imageViewerUri != null) {
        FullScreenImageViewer(imageUri = imageViewerUri!!, onDismiss = { showImageViewer = false; imageViewerUri = null })
    }
    if (showVideoPlayer && videoPlayerUri != null) {
        FullScreenVideoPlayer(videoUri = videoPlayerUri!!, onDismiss = { showVideoPlayer = false; videoPlayerUri = null })
    }
    if (showDocumentViewer && documentViewerUri != null) {
        FullScreenDocumentViewer(
            documentUri = documentViewerUri!!,
            mimeType = documentViewerMimeType,
            fileName = documentViewerFileName,
            onDismiss = { showDocumentViewer = false; documentViewerUri = null }
        )
    }

    // History Sheet
    if (showVersionHistory) {
        // Simple placeholder for version history if component not available in context
        // Ideally reuse VersionHistorySheet from previous implementation if needed
        // For this redesign, assuming it exists or logic is handled
    }
}

private fun formatDate(timestamp: Long): String {
    val date = Date(timestamp)
    val formatter = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
    return formatter.format(date)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeTopBar(
    isModified: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onHistory: () -> Unit,
    onAskSmarty: (() -> Unit)?
) {
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        },
        actions = {
            // Save Button (Only visible if modified)
            AnimatedVisibility(
                visible = isModified,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                FilledTonalButton(
                    onClick = onSave,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Text(stringResource(R.string.save))
                }
            }

            // Ask AI
            if (onAskSmarty != null) {
                IconButton(onClick = onAskSmarty) {
                    Icon(Icons.Default.AutoAwesome, "Ask AI", tint = LocalAccentColor.current)
                }
            }

            // More Menu
            var showMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "Options")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.history)) },
                        leadingIcon = { Icon(Icons.Default.History, null) },
                        onClick = { showMenu = false; onHistory() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.archive)) },
                        leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                        onClick = { showMenu = false; onArchive() }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDelete() },
                        colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error)
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
    )
}

@Composable
private fun InlineAudioPlayer(
    trackUri: String,
    trackTitle: String,
    isPlaying: Boolean,
    progress: Float,
    waveform: List<Float>,
    duration: String,
    currentTime: String,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit
) {
    Surface(
        shape = LocalShapes.current.cardSmall,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Play Button
                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    )
                }

                // Track Info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Audio Recording", // Or trackTitle
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "$currentTime / $duration",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Waveform
            AudioWaveform(
                waveformData = waveform,
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                activeColor = MaterialTheme.colorScheme.primary,
                inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                onSeek = onSeek
            )
        }
    }
}

@Composable
private fun AttachmentChip(
    attachment: NoteAttachment,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = LocalShapes.current.skeleton,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = null, // Decorative icon - attachment indicator, filename shown in text
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = attachment.fileName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp)
            )
        }
    }
}

