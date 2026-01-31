package com.example.smarty.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material3.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.smarty.ui.theme.ComponentSpacing
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteAttachment
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.model.NoteVersion
import com.example.smarty.data.model.getAttachments
import com.example.smarty.data.model.getChunkAnalyses
import com.example.smarty.data.model.hasChunkAnalyses
import com.example.smarty.ui.components.CalmThinkingDots
import com.example.smarty.ui.components.CategoryChip
import com.example.smarty.ui.components.DecompressionPlaceholder
import com.example.smarty.ui.components.FloatingActionBar
import com.example.smarty.ui.components.ShimmerBox
import com.example.smarty.ui.components.getNoteTypeColor
import com.example.smarty.ui.components.getNoteTypeIcon
import com.example.smarty.ui.components.common.SmartyDialog
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.AudioPink
import com.example.smarty.ui.theme.softCardShadow
import com.example.smarty.ui.components.viewers.FullScreenDocumentViewer
import com.example.smarty.ui.components.viewers.FullScreenImageViewer
import com.example.smarty.ui.components.viewers.FullScreenVideoPlayer
import com.example.smarty.ui.utils.AnimationLifecycleState
import com.example.smarty.ui.utils.rememberAnimationLifecycleState
import androidx.compose.ui.text.font.FontWeight
import com.example.smarty.util.CompressionType
import com.example.smarty.util.FileCompressor
import com.example.smarty.util.FileStorageHelper
import com.example.smarty.util.FileViewerHelper
import com.example.smarty.util.LazyDecompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.runtime.rememberCoroutineScope
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.text.appendInlineContent
import com.example.smarty.ui.components.rememberShimmerBrush
import com.example.smarty.ui.components.RelatedNotesSection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeCardScreen(
    note: Note,
    onBackClick: () -> Unit,
    onArchiveClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onEditNote: (String, String, String, String?, String?, List<NoteAttachment>) -> Unit = { _, _, _, _, _, _ -> },  // noteId, newTitle, newContent, newSummary, newWhySaved, newAttachments
    onPlayAudio: (AudioTrack) -> Unit = {},
    onMarkAsViewed: () -> Unit = {},
    // Version history callbacks
    noteVersions: List<NoteVersion> = emptyList(),
    onLoadVersions: () -> Unit = {},
    onRestoreVersion: (String) -> Unit = {},  // versionId
    isVersionsLoading: Boolean = false,
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    isMiniPlayerVisible: Boolean = false,
    // @Mention: Ask Smarty about this note (opens chat with note pre-referenced)
    onAskSmarty: (() -> Unit)? = null,
    // Related Notes: All notes for semantic linking
    allNotes: List<Note> = emptyList(),
    onNavigateToNote: (Note) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    // var showEditSheet by remember { mutableStateOf(false) } // Removed in favor of in-place editing
    var showVersionHistory by remember { mutableStateOf(false) }
    
    // In-Place Editing State
    var isEditing by remember { mutableStateOf(false) }
    var editedTitle by remember(note.title) { mutableStateOf(note.title) }
    var editedContent by remember(note.content) { mutableStateOf(note.content) }
    var editedSummary by remember(note.summary) { mutableStateOf(note.summary ?: "") }
    var editedWhySaved by remember(note.whySaved) { mutableStateOf(note.whySaved ?: "") }
    // We retain currentAttachments behavior from existing code if possible, or just pass note.attachments
    // For now, we don't fully support attachment editing in-place, preserving current list
    // For now, we don't fully support attachment editing in-place, preserving current list
    val currentAttachments = note.getAttachments()

    // Viewer states
    var showImageViewer by remember { mutableStateOf(false) }
    var imageViewerUri by remember { mutableStateOf<String?>(null) }

    var showVideoPlayer by remember { mutableStateOf(false) }
    var videoPlayerUri by remember { mutableStateOf<String?>(null) }

    var showDocumentViewer by remember { mutableStateOf(false) }
    var documentViewerUri by remember { mutableStateOf<String?>(null) }
    var documentViewerMimeType by remember { mutableStateOf<String?>(null) }
    var documentViewerFileName by remember { mutableStateOf<String?>(null) }

    // Decompression state
    var isDecompressing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Tab State
    var selectedTab by remember { mutableStateOf(KnowledgeTab.INSIGHT) } // Default to Insight

    // Chunk analyses toggle state (for documents with per-page analyses)
    var showChunkAnalyses by remember { mutableStateOf(false) }
    val hasChunks = note.hasChunkAnalyses()
    val chunkAnalyses = remember(note.chunkAnalysesJson) { note.getChunkAnalyses() }

    // Mark as viewed when screen is opened
    LaunchedEffect(note.id) {
        if (!note.isViewed) {
             onMarkAsViewed()
        }
    }

    // Intercept system back button
    androidx.activity.compose.BackHandler(onBack = {
        if (isEditing || showVersionHistory || showImageViewer || showVideoPlayer || showDocumentViewer) {
             isEditing = false
             showVersionHistory = false
             showImageViewer = false
             showVideoPlayer = false
             showDocumentViewer = false
        } else {
            onBackClick()
        }
    })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            },
            containerColor = Color.Transparent
        ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = paddingValues.calculateBottomPadding() + 100.dp + bottomContentPadding),
                verticalArrangement = Arrangement.spacedBy(24.dp) // Increased spacing for cleaner layout
            ) {
                 // Spacer to push content down initially
                 Spacer(modifier = Modifier.height(paddingValues.calculateTopPadding() + 8.dp))

                // 1. Cleaner Header
                KnowledgeHeaderCard(
                    note = note,
                    isEditing = isEditing,
                    editedTitle = editedTitle,
                    onTitleChange = { editedTitle = it }
                )

                // 2. Tabs
                KnowledgeTabRow(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it }
                )

                // 3. Tab Content
                when (selectedTab) {
                    KnowledgeTab.INSIGHT -> {
                        // --- INSIGHT TAB: Summary + Why Saved + Actionable items ---

                        // "Why Saved" - High Priority Ideation Element
                        if (isEditing || note.whySaved != null) {
                            SectionCard(
                                title = stringResource(R.string.why_this_matters), // Clearer label for ideation
                                icon = Icons.Outlined.Lightbulb, // Lightbulb for ideas
                                accentColor = LocalAccentColor.current,
                                showToggleIndicator = false
                            ) {
                                if (isEditing) {
                                    OutlinedTextField(
                                        value = editedWhySaved,
                                        onValueChange = { editedWhySaved = it },
                                        placeholder = { Text(stringResource(R.string.why_saved_placeholder)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = LocalAccentColor.current,
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                        )
                                    )
                                } else {
                                    Text(
                                        text = note.whySaved?.ifBlank { stringResource(R.string.no_reason_recorded) } ?: stringResource(R.string.no_reason_recorded),
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Medium,
                                            lineHeight = 28.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        // Summary Section
                        val fullSummary = note.summary ?: stringResource(R.string.no_summary_available)
                        val (summaryTitle, summaryContent) = remember(fullSummary) {
                            if (fullSummary.contains("\n\n")) {
                                val parts = fullSummary.split("\n\n", limit = 2)
                                if (parts[0].length < 100) parts[0] to parts[1] else "" to fullSummary
                            } else {
                                "" to fullSummary
                            }
                        }

                        SectionCard(
                            title = if (isEditing) stringResource(R.string.summary) else if (showChunkAnalyses) stringResource(R.string.per_page_analysis) else summaryTitle.ifEmpty { stringResource(R.string.summary) },
                            icon = Icons.AutoMirrored.Filled.TextSnippet,
                            onIconClick = if (hasChunks && !isEditing) {
                                { showChunkAnalyses = !showChunkAnalyses }
                            } else null,
                            showToggleIndicator = hasChunks && !isEditing,
                            isToggled = showChunkAnalyses
                        ) {
                             if (isEditing) {
                                OutlinedTextField(
                                    value = editedSummary,
                                    onValueChange = { editedSummary = it },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = LocalAccentColor.current,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                    )
                                )
                            } else if (showChunkAnalyses && chunkAnalyses.isNotEmpty()) {
                                // Chunk analyses content (same as before)
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    chunkAnalyses.forEach { chunk ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                                    RoundedCornerShape(16.dp)
                                                )
                                                .padding(16.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.pages_range, chunk.pageRange),
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = LocalAccentColor.current
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = chunk.summary,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Standard Summary Text
                                Text(
                                    text = summaryContent.ifBlank { stringResource(R.string.no_summary_generated) },
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = 16.sp,
                                        lineHeight = 26.sp,
                                        letterSpacing = 0.2.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                )
                            }
                        }

                        // Related Knowledge (Bottom of Insight)
                        if (!isEditing && allNotes.isNotEmpty()) {
                            RelatedNotesSection(
                                currentNote = note,
                                allNotes = allNotes,
                                onNoteClick = onNavigateToNote
                            )
                        }
                    }

                    KnowledgeTab.SOURCE -> {
                        // --- SOURCE TAB: Files + Original Content ---

                        // 1. Files / Attachments
                        val attachments = note.getAttachments()
                        // Legacy handling logic (same as before)...
                        val legacyMainAttachment = if (attachments.isEmpty() && note.type != NoteType.AUDIO && (note.imageUri != null || note.fileUri != null)) {
                            val uri = note.imageUri ?: note.fileUri
                            if (uri != null) {
                                val fallbackMime = when (note.type) {
                                    NoteType.IMAGE -> "image/*"
                                    NoteType.VIDEO -> "video/*"
                                    else -> "*/*"
                                }
                                NoteAttachment(
                                    uri = uri,
                                    fileName = note.fileName ?: stringResource(R.string.file),
                                    mimeType = note.fileMimeType ?: fallbackMime,
                                    fileSize = note.fileSize ?: 0L
                                )
                            } else null
                        } else null

                        val allAttachments = (attachments + listOfNotNull(legacyMainAttachment)).distinctBy { it.uri }

                        if (allAttachments.isNotEmpty()) {
                            SectionCard(
                                title = stringResource(R.string.attachments),
                                icon = Icons.Outlined.Attachment
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    allAttachments.forEach { attachment ->
                                        FileAttachmentItem(
                                            attachment = attachment,
                                            onOpen = {
                                                // Simplified open logic call
                                                val mimeType = attachment.mimeType.ifEmpty { "*/*" }
                                                if (mimeType.startsWith("audio/")) {
                                                     onPlayAudio(
                                                        AudioTrack(
                                                            uri = attachment.uri,
                                                            title = note.title,
                                                            fileName = attachment.fileName,
                                                            sourceNoteId = note.id,
                                                            mimeType = attachment.mimeType
                                                        )
                                                     )
                                                } else if (mimeType.startsWith("image/")) {
                                                    imageViewerUri = attachment.uri; showImageViewer = true
                                                } else if (mimeType.startsWith("video/")) {
                                                    videoPlayerUri = attachment.uri; showVideoPlayer = true
                                                } else {
                                                    documentViewerUri = attachment.uri; documentViewerMimeType = mimeType; documentViewerFileName = attachment.fileName; showDocumentViewer = true
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            // Empty state for files if none
                             SectionCard(title = stringResource(R.string.attachments), icon = Icons.Outlined.Attachment) {
                                Text(stringResource(R.string.no_files_attached), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        // 2. Original Content Text
                        SectionCard(
                            title = stringResource(R.string.original_text),
                            icon = Icons.AutoMirrored.Outlined.Article,
                            forceVertical = true
                        ) {
                             if (isEditing) {
                                OutlinedTextField(
                                    value = editedContent,
                                    onValueChange = { editedContent = it },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = LocalAccentColor.current,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                    )
                                )
                            } else {
                                Text(
                                    text = note.content.ifBlank { stringResource(R.string.no_text_content) },
                                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                )
                            }
                        }

                        // Source Link
                        note.sourceUrl?.let { url ->
                            Button(
                                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = LocalAccentColor.current.copy(alpha = 0.1f), contentColor = LocalAccentColor.current),
                                elevation = ButtonDefaults.buttonElevation(0.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.visit_original_source))
                            }
                        }
                    }
                }

                // Common Footer Elements (Metadata)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatDate(note.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.weight(1f))

                // History Icon
                IconButton(
                    onClick = { onLoadVersions(); showVersionHistory = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = stringResource(R.string.history),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }


            // ══════════════════════════════════════════════════════════════
            // GRADIENT SCRIMS
            // ══════════════════════════════════════════════════════════════
            
            // 1. Top Header Scrim (White -> Transparent)
            // Hides scrolling text behind the transparent TopAppBar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.9f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // 2. Bottom Footer Scrim (Transparent -> White)
            // Hides scrolling text behind the FAB area and accounts for audio player when visible
            val bottomScrimHeight = if (isMiniPlayerVisible) 260.dp else 180.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bottomScrimHeight)
                    .align(Alignment.BottomCenter)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.9f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
            )

            // Floating Action Bar at bottom
            FloatingActionBar(
                onEdit = {
                    if (isEditing) {
                        onEditNote(
                            note.id,
                            editedTitle,
                            editedContent,
                            editedSummary.ifBlank { null },
                            editedWhySaved.ifBlank { null },
                            currentAttachments.distinctBy { it.uri }
                        )
                        isEditing = false
                    } else {
                        // Reset fields
                        editedTitle = note.title
                        editedContent = note.content
                        editedSummary = note.summary ?: ""
                        editedWhySaved = note.whySaved ?: ""
                        isEditing = true
                    }
                },
                onArchive = onArchiveClick,
                onDelete = { showDeleteDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = ComponentSpacing.screenPadding)
                    .navigationBarsPadding(),
                isVisible = !showImageViewer && !showVideoPlayer,
                isEditing = isEditing,
                onAskSmarty = onAskSmarty
            )
        }
    }

    // Delete Confirmation Dialog
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

    // Built-in Image Viewer
    if (showImageViewer && imageViewerUri != null) {
        FullScreenImageViewer(
            imageUri = imageViewerUri!!,
            onDismiss = {
                showImageViewer = false
                imageViewerUri = null
            },
            contentDescription = note.fileName
        )
    }

    // Built-in Video Player
    if (showVideoPlayer && videoPlayerUri != null) {
        FullScreenVideoPlayer(
            videoUri = videoPlayerUri!!,
            onDismiss = {
                showVideoPlayer = false
                videoPlayerUri = null
            }
        )
    }

    // Built-in Document Viewer
    if (showDocumentViewer && documentViewerUri != null) {
        FullScreenDocumentViewer(
            documentUri = documentViewerUri!!,
            mimeType = documentViewerMimeType,
            fileName = documentViewerFileName,
            onDismiss = {
                showDocumentViewer = false
                documentViewerUri = null
                documentViewerMimeType = null
                documentViewerFileName = null
            }
        )
    }

    if (isDecompressing) {
        SmartyDialog(
            title = stringResource(R.string.opening_document),
            text = "",
            customContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CalmThinkingDots(
                        dotSize = 4.dp
                    )
                    Text(
                        text = stringResource(R.string.decompressing_file),
                        style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.2.sp)
                    )
                }
            },
            onConfirm = {},
            onDismiss = {},
            confirmText = "",
            dismissText = ""
        )
    }

    // Version History Sheet
    if (showVersionHistory) {
        VersionHistorySheet(
            versions = noteVersions,
            isLoading = isVersionsLoading,
            onDismiss = { showVersionHistory = false },
            onRestoreVersion = { versionId ->
                onRestoreVersion(versionId)
                showVersionHistory = false
            }
        )
    }
    }
}

// --- NEW COMPONENTS FOR REDESIGN ---

enum class KnowledgeTab {
    INSIGHT,
    SOURCE
}

@Composable
fun getKnowledgeTabTitle(tab: KnowledgeTab): String {
    return when (tab) {
        KnowledgeTab.INSIGHT -> stringResource(R.string.insight)
        KnowledgeTab.SOURCE -> stringResource(R.string.source)
    }
}

@Composable
fun KnowledgeHeaderCard(
    note: Note,
    isEditing: Boolean = false,
    editedTitle: String = "",
    onTitleChange: (String) -> Unit = {}
) {
    val accentColor = LocalAccentColor.current
    val typeColor = getNoteTypeColor(note.type)

    // Removed the Surface wrapper for a cleaner, non-card look
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 16.dp), // Added vertical padding
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Row: Icon + Type + Status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = typeColor.copy(alpha = 0.1f),
                modifier = Modifier.size(48.dp) // Slightly smaller, more refined
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = getNoteTypeIcon(note.type),
                        contentDescription = null,
                        tint = typeColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = getNoteTypeName(note.type).uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontSize = 10.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                // Optional: Add "processed" indicator text if needed, or keep minimal
                if (note.processingStatus == ProcessingStatus.COMPLETED) {
                     Text(
                        text = stringResource(R.string.processed),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }

        // Title: Big, Bold, Editable
        if (isEditing) {
            BasicTextField(
                value = editedTitle,
                onValueChange = onTitleChange,
                textStyle = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 40.sp
                ),
                cursorBrush = SolidColor(accentColor),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                text = note.title, // Removed .lowercase() for title to respect user casing
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    lineHeight = 40.sp,
                    letterSpacing = (-0.5).sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// AppBlue constants removed for Calm Aesthetic dynamic theming

@Composable
fun KnowledgeTabRow(
    selectedTab: KnowledgeTab,
    onTabSelected: (KnowledgeTab) -> Unit
) {
    val accentColor = LocalAccentColor.current
    val isDark = isSystemInDarkTheme()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        KnowledgeTab.entries.forEach { tab ->
            val isSelected = selectedTab == tab
            // Animate background color
            val animColor by animateColorAsState(
                targetValue = if (isSelected) accentColor.copy(alpha = 0.1f) else Color.Transparent,
                label = "tabBg"
            )
            // Animate text color
            val textColor by animateColorAsState(
                targetValue = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                label = "tabText"
            )

            // Minimalist Tab Pill
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(animColor)
                    .clickable { onTabSelected(tab) }
                    .border(
                        width = 1.dp,
                        color = if (isSelected) accentColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = getKnowledgeTabTitle(tab).uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = 1.sp
                    ),
                    color = textColor
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: androidx.compose.ui.graphics.Color = LocalAccentColor.current,
    inlineLayout: Boolean = false,
    forceVertical: Boolean = false,
    onIconClick: (() -> Unit)? = null,
    showToggleIndicator: Boolean = false,
    isToggled: Boolean = false,
    content: @Composable () -> Unit
) {
    // Redesigned SectionCard: Cleaner, less "card-like", more "document-section-like"
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        if (title.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                if (showToggleIndicator) {
                     Spacer(modifier = Modifier.weight(1f))
                     Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isToggled) accentColor.copy(alpha = 0.1f) else Color.Transparent,
                        modifier = Modifier.clickable { onIconClick?.invoke() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isToggled) stringResource(R.string.view_history) else stringResource(R.string.summary),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isToggled) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Content Area
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            content()
        }
    }
}

@Composable
private fun getNoteTypeName(type: NoteType): String {
    return when (type) {
        NoteType.YOUTUBE -> stringResource(R.string.type_youtube)
        NoteType.TWITTER -> stringResource(R.string.type_twitter)
        NoteType.INSTAGRAM -> stringResource(R.string.type_instagram)
        NoteType.WEBSITE -> stringResource(R.string.type_web_link)
        NoteType.IMAGE -> stringResource(R.string.type_image)
        NoteType.BRAIN_DUMP -> stringResource(R.string.type_brain_dump)
        NoteType.DOCUMENT -> stringResource(R.string.type_document)
        NoteType.SPREADSHEET -> stringResource(R.string.type_spreadsheet)
        NoteType.PRESENTATION -> stringResource(R.string.type_presentation)
        NoteType.VIDEO -> stringResource(R.string.type_video)
        NoteType.AUDIO -> stringResource(R.string.type_audio)
        NoteType.CODE -> stringResource(R.string.type_code)
        NoteType.ARCHIVE -> stringResource(R.string.type_archive)
        NoteType.APK -> stringResource(R.string.type_apk)
        NoteType.FILE -> stringResource(R.string.type_file)
    }
}

@Composable
private fun formatDate(timestamp: Long): String {
    val dateStr = SimpleDateFormat(stringResource(R.string.date_format_short), Locale.getDefault()).format(Date(timestamp))
    val timeStr = SimpleDateFormat(stringResource(R.string.time_format_12h), Locale.getDefault()).format(Date(timestamp))
    return stringResource(R.string.date_at_time_format, dateStr, timeStr)
}




@Composable
fun FileAttachmentItem(
    attachment: NoteAttachment,
    onOpen: () -> Unit
) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)),
        modifier = Modifier.fillMaxWidth().height(64.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Thumbnail / Icon
            val mimeType = attachment.mimeType
            val isImage = mimeType.startsWith("image/")
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isImage) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isImage) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(attachment.uri)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val icon = when {
                        mimeType.startsWith("video/") -> Icons.Outlined.Videocam
                        mimeType.startsWith("audio/") -> Icons.Outlined.AudioFile
                        mimeType.contains("pdf") -> Icons.Outlined.PictureAsPdf
                        mimeType.contains("sheet") || mimeType.contains("excel") -> Icons.Outlined.TableChart
                        mimeType.contains("presentation") || mimeType.contains("powerpoint") -> Icons.Outlined.Slideshow
                        mimeType.contains("zip") || mimeType.contains("rar") -> Icons.Outlined.FolderZip
                        mimeType == "application/vnd.android.package-archive" -> Icons.Outlined.Android
                        else -> Icons.AutoMirrored.Outlined.Article
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // Text Info
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = attachment.fileName.lowercase(),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        letterSpacing = 0.1.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(
                        R.string.file_metadata_format,
                        attachment.mimeType.substringAfterLast('/').lowercase(),
                        com.example.smarty.ui.screens.settings.formatCacheSize(attachment.fileSize).lowercase()
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.3.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Open/Play Icon
            val actionIcon = if (attachment.mimeType.startsWith("audio/")) Icons.Default.PlayArrow else Icons.Default.ChevronRight
            Icon(
                imageVector = actionIcon,
                contentDescription = stringResource(R.string.open),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Legacy Components (Kept for Audio or Fallback)
 */
@Composable
private fun AttachmentItem(
    attachment: NoteAttachment,
    index: Int,
    onOpen: () -> Unit
) {
    CompactFileRow(
        fileName = attachment.fileName,
        fileSize = attachment.fileSize,
        mimeType = attachment.mimeType,
        onAction = onOpen,
        index = index
    )
}

/**
 * Unified Compact File Row Component
 * Used for all file types (single or multiple) to provide a consistent list-like UI.
 */
@Composable
private fun CompactFileRow(
    fileName: String,
    fileSize: Long?,
    mimeType: String,
    onAction: () -> Unit,
    index: Int? = null
) {
    // Determine icon and color based on mime type
    val (icon, iconColor) = when {
        mimeType.startsWith("image/") -> Icons.Outlined.Image to com.example.smarty.ui.theme.ImageTeal
        mimeType.startsWith("video/") -> Icons.Outlined.Videocam to com.example.smarty.ui.theme.VideoRed
        mimeType.startsWith("audio/") -> Icons.Outlined.AudioFile to AudioPink
        mimeType.contains("pdf") -> Icons.Outlined.PictureAsPdf to com.example.smarty.ui.theme.DocumentBlue
        mimeType.contains("document") || mimeType.contains("word") ->
            Icons.AutoMirrored.Outlined.Article to com.example.smarty.ui.theme.DocumentBlue
        mimeType.contains("sheet") || mimeType.contains("excel") ->
            Icons.Outlined.TableChart to com.example.smarty.ui.theme.SpreadsheetGreen
        mimeType.contains("presentation") || mimeType.contains("powerpoint") ->
            Icons.Outlined.Slideshow to com.example.smarty.ui.theme.PresentationOrange
        mimeType.contains("zip") || mimeType.contains("rar") ->
            Icons.Outlined.FolderZip to com.example.smarty.ui.theme.ArchiveYellow
        mimeType == "application/vnd.android.package-archive" ->
            Icons.Outlined.Android to com.example.smarty.ui.theme.ApkGreen
        else -> Icons.Outlined.AttachFile to com.example.smarty.ui.theme.FileGray
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onAction() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon Box
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(12.dp),
            color = iconColor.copy(alpha = 0.15f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
                // Optional Overlay Badge for index
                if (index != null) {
                    // We could overlay index here, but sticking to clean icon is cleaner
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Text Content
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (fileSize != null) {
                val context = androidx.compose.ui.platform.LocalContext.current
                Text(
                    text = com.example.smarty.util.ContentTypeDetector.formatFileSize(context, fileSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        // Removed separate open button as per request - whole row is clickable
    }
}

/**
 * Privacy banner displayed at the top of private notes.
 * Shows a prominent indicator that AI cannot access this note.
 * Features a subtle shimmer animation for visibility.
 */
@Composable
private fun PrivacyBanner() {
    val privacyColor = MaterialTheme.colorScheme.tertiary

    // Shimmer animation - LIFECYCLE AWARE
    val lifecycleState by rememberAnimationLifecycleState()
    val shouldAnimate = lifecycleState == AnimationLifecycleState.RUNNING

    val shimmerAlpha = if (shouldAnimate) {
        val infiniteTransition = rememberInfiniteTransition(label = "privacyBannerShimmer")
        val animatedAlpha by infiniteTransition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "privacyBannerShimmerAlpha"
        )
        animatedAlpha
    } else {
        0.925f // Static mid-point value
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = shimmerAlpha },
        shape = RoundedCornerShape(16.dp),
        color = privacyColor.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = privacyColor.copy(alpha = 0.25f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Shield Icon
            Surface(
                shape = CircleShape,
                color = privacyColor.copy(alpha = 0.15f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = privacyColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Privacy Text
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.private_note),
                    style = MaterialTheme.typography.titleSmall,
                    color = privacyColor
                )
                Text(
                    text = stringResource(R.string.ai_cannot_access_this_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = privacyColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}

/**
 * Bottom sheet for editing a note's title and content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditNoteSheet(
    note: Note,
    initialAttachments: List<NoteAttachment>,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String, attachments: List<NoteAttachment>) -> Unit
) {
    // Add note.id as dependency to reset state when note changes
    var editedTitle by remember(note.id) { mutableStateOf(note.title) }

    // Clean up content if it contains the auto-generated attachment header
    var editedContent by remember(note.id) {
        mutableStateOf(
            if (note.content.contains("files attached:\n\n")) {
                // If it looks like a generated list, start with empty content
                ""
            } else {
                note.content
            }
        )
    }

    var currentAttachments by remember(note.id, initialAttachments) { mutableStateOf(initialAttachments) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(2.dp)
            ) {
                Box(modifier = Modifier.size(width = 32.dp, height = 4.dp))
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.edit_note),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = {
                            // filtering duplicates before saving
                            onSave(editedTitle, editedContent, currentAttachments.distinctBy { it.uri })
                        },
                        enabled = editedTitle.isNotBlank(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }

            // Attachments Management Section
            if (currentAttachments.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.attachments),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        currentAttachments.forEachIndexed { index, attachment ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = when {
                                            attachment.mimeType.startsWith("image/") -> Icons.Outlined.Image
                                            attachment.mimeType.startsWith("audio/") -> Icons.Outlined.AudioFile
                                            attachment.mimeType.startsWith("video/") -> Icons.Outlined.Videocam
                                            else -> Icons.Outlined.AttachFile
                                        },
                                        contentDescription = null,
                                        tint = LocalAccentColor.current,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = attachment.fileName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                
                                // Remove Button
                                IconButton(
                                    onClick = {
                                        currentAttachments = currentAttachments.toMutableList().apply { removeAt(index) }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.remove_attachment),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            
                            if (index < currentAttachments.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Title Field
            OutlinedTextField(
                value = editedTitle,
                onValueChange = { editedTitle = it },
                label = { Text(stringResource(R.string.title)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LocalAccentColor.current,
                    focusedLabelColor = LocalAccentColor.current
                )
            )

            // Content Field
            OutlinedTextField(
                value = editedContent,
                onValueChange = { editedContent = it },
                label = { Text(stringResource(R.string.content)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 400.dp),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LocalAccentColor.current,
                    focusedLabelColor = LocalAccentColor.current
                )
            )
        }
    }
}

/**
 * Version History Sheet - displays past versions of a note with ability to restore
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VersionHistorySheet(
    versions: List<NoteVersion>,
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onRestoreVersion: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedVersion by remember { mutableStateOf<NoteVersion?>(null) }
    var showRestoreDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(2.dp)
            ) {
                Box(modifier = Modifier.size(width = 32.dp, height = 4.dp))
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = LocalAccentColor.current.copy(alpha = 0.12f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = LocalAccentColor.current,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = stringResource(R.string.version_history),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.versions_preserved, versions.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CalmThinkingDots()
                }
            } else if (versions.isEmpty()) {
                com.example.smarty.ui.components.VersionHistoryEmptyState(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp)
                )
            } else {
                // Version list
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    versions.forEach { version ->
                        VersionItem(
                            version = version,
                            isFirst = version == versions.firstOrNull(),
                            onClick = {
                                selectedVersion = version
                                showRestoreDialog = true
                            }
                        )
                    }
                }
            }
        }
    }

    // Restore Confirmation Dialog
    if (showRestoreDialog && selectedVersion != null) {
        SmartyDialog(
            title = stringResource(R.string.restore_version),
            text = "",
            customContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.restore_version_warning_detail, selectedVersion!!.versionNumber),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.restore_version_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            onConfirm = {
                selectedVersion?.let { onRestoreVersion(it.id) }
                showRestoreDialog = false
                selectedVersion = null
            },
            onDismiss = {
                showRestoreDialog = false
                selectedVersion = null
            },
            confirmText = stringResource(R.string.restore),
            dismissText = stringResource(R.string.cancel)
        )
    }
}

/**
 * Individual version item in the history list
 */
@Composable
private fun VersionItem(
    version: NoteVersion,
    isFirst: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = if (isFirst) {
            LocalAccentColor.current.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        border = if (isFirst) {
            androidx.compose.foundation.BorderStroke(1.dp, LocalAccentColor.current.copy(alpha = 0.3f))
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Version number badge
            Surface(
                shape = CircleShape,
                color = if (isFirst) LocalAccentColor.current else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.version_number, version.versionNumber),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        ),
                        color = if (isFirst) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Version details
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = version.title.lowercase(),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isFirst) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = LocalAccentColor.current.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = stringResource(R.string.latest),
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalAccentColor.current,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Text(
                    text = formatDate(version.createdAt).lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                version.changeDescription?.let { desc ->
                    Text(
                        text = desc.lowercase(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Restore arrow
            Icon(
                imageVector = Icons.Default.Restore,
                contentDescription = stringResource(R.string.restore_this_version),
                tint = if (isFirst) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
