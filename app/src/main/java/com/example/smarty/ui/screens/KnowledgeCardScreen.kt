package com.example.smarty.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteAttachment
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.NoteVersion
import com.example.smarty.data.model.getAttachments
import com.example.smarty.data.model.getChunkAnalyses
import com.example.smarty.data.model.hasChunkAnalyses
import com.example.smarty.ui.components.CategoryChip
import com.example.smarty.ui.components.DecompressionPlaceholder
import com.example.smarty.ui.components.FloatingActionBar
import com.example.smarty.ui.components.ShimmerBox
import com.example.smarty.ui.components.getNoteTypeColor
import com.example.smarty.ui.components.getNoteTypeIcon
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
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    isMiniPlayerVisible: Boolean = false,
    // @Mention: Ask AI about this note (opens chat with note pre-referenced)
    onAskAI: (() -> Unit)? = null,
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
    var selectedTab by remember { mutableStateOf(KnowledgeTab.SUMMARY) }

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
        MeshSpillEffect()
        
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { },
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
                    // Only apply bottom padding from scaffold (navigation bar), ignore top to scroll behind header
                    // Add extra padding to account for floating action bar and potential audio player overlap
                    .padding(bottom = paddingValues.calculateBottomPadding() + 100.dp + bottomContentPadding),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                 // Spacer to push content down initially, clearing the transparent header
                 Spacer(modifier = Modifier.height(paddingValues.calculateTopPadding() + 8.dp))
                 
                // Top spacing to show gradient effect
                Spacer(modifier = Modifier.height(8.dp))

                // 1. Unified Header Card
                KnowledgeHeaderCard(
                    note = note,
                    isEditing = isEditing,
                    editedTitle = editedTitle,
                    onTitleChange = { editedTitle = it }
                )

                // 2. Custom Tab Row
                KnowledgeTabRow(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it }
                )

                // 3. Tab Content
                when (selectedTab) {
                    KnowledgeTab.SUMMARY -> {
                        // Logic to split header/content for better layout (e.g. "115 pages analysed" vs body)
                        val fullSummary = note.summary ?: "No summary available."
                        val (summaryTitle, summaryContent) = remember(fullSummary) {
                            if (fullSummary.contains("\n\n")) {
                                val parts = fullSummary.split("\n\n", limit = 2)
                                // Heuristic: Header should be short (< 100 chars) to be treated as a kicker
                                if (parts[0].length < 100) parts[0] to parts[1] else "" to fullSummary
                            } else {
                                "" to fullSummary
                            }
                        }

                        val isInlineMode = !isEditing && summaryTitle.isEmpty() && !showChunkAnalyses

                        // AI Summary Section
                        if (isEditing || note.summary != null) {
                            SectionCard(
                                title = if (isEditing) "" else if (showChunkAnalyses) "Per-Page Analysis" else summaryTitle,
                                icon = Icons.Outlined.Psychology,
                                inlineLayout = isInlineMode && !hasChunks,
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
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = LocalAccentColor.current,
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                        )
                                    )
                                } else if (showChunkAnalyses && chunkAnalyses.isNotEmpty()) {
                                    // Show per-page/chunk analyses
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        chunkAnalyses.forEach { chunk ->
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                                        RoundedCornerShape(12.dp)
                                                    )
                                                    .padding(12.dp)
                                            ) {
                                                // Page range header
                                                Text(
                                                    text = "Pages ${chunk.pageRange}",
                                                    style = MaterialTheme.typography.labelMedium.copy(
                                                        fontWeight = FontWeight.Bold
                                                    ),
                                                    color = LocalAccentColor.current
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                // Chunk summary
                                                Text(
                                                    text = chunk.summary,
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        lineHeight = 22.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    if (isInlineMode) {
                                        // Story Chapter Style (Inline Icon)
                                        val text = androidx.compose.ui.text.buildAnnotatedString {
                                            appendInlineContent("icon", "[icon]")
                                            append(" ")
                                            append(summaryContent)
                                        }
                                        val inlineContent = mapOf(
                                            "icon" to androidx.compose.foundation.text.InlineTextContent(
                                                androidx.compose.ui.text.Placeholder(
                                                    width = 40.sp,
                                                    height = 28.sp,
                                                    placeholderVerticalAlign = androidx.compose.ui.text.PlaceholderVerticalAlign.Center
                                                )
                                            ) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier.fillMaxSize().padding(end = 8.dp)
                                                ) {
                                                    Surface(
                                                        shape = CircleShape,
                                                        color = LocalAccentColor.current.copy(alpha = 0.1f),
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Box(contentAlignment = Alignment.Center) {
                                                            Icon(
                                                                imageVector = Icons.Outlined.Psychology,
                                                                contentDescription = null,
                                                                tint = LocalAccentColor.current,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        )

                                        Text(
                                            text = text,
                                            inlineContent = inlineContent,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontSize = 17.sp,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                                lineHeight = 28.sp,
                                                letterSpacing = 0.15.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                                        )
                                    } else {
                                        // Dynamic text rendering to support shimmer on processing status
                                        val contentParts = remember(summaryContent) { summaryContent.split("\n\n") }

                                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                            contentParts.forEach { part ->
                                                // Check for processing keywords
                                                val isAnalyzing = part.startsWith("Analyzing section") || part.startsWith("Generating final summary")

                                                val baseColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)

                                                if (isAnalyzing) {
                                                    // Shimmer effect for active processing
                                                    val shimmerBrush = rememberShimmerBrush(
                                                        baseColor = baseColor,
                                                        highlightColor = LocalAccentColor.current.copy(alpha = 0.8f),
                                                        durationMillis = 1500
                                                    )

                                                    Text(
                                                        text = part,
                                                        style = MaterialTheme.typography.bodyLarge.copy(
                                                            fontSize = 17.sp,
                                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                                            lineHeight = 28.sp,
                                                            letterSpacing = 0.15.sp,
                                                            brush = shimmerBrush
                                                        )
                                                        // Note: 'color' param is ignored when 'brush' is provided in style
                                                    )
                                                } else {
                                                    // Standard text
                                                    Text(
                                                        text = part,
                                                        style = MaterialTheme.typography.bodyLarge.copy(
                                                            fontSize = 17.sp,
                                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                                            lineHeight = 28.sp,
                                                            letterSpacing = 0.15.sp
                                                        ),
                                                        color = baseColor
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                             // Fallback only if not editing and no summary
                             SectionCard(
                                title = "",
                                icon = Icons.Outlined.Psychology
                            ) {
                                Text(
                                    text = "No summary available for this item.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Why You Saved This
                        // Why You Saved This
                        if (isEditing || note.whySaved != null) {
                            val isWhySavedInline = !isEditing
                            SectionCard(
                                title = "",
                                icon = Icons.Outlined.BookmarkBorder,
                                accentColor = LocalAccentColor.current,
                                inlineLayout = isWhySavedInline
                            ) {
                                if (isEditing) {
                                    OutlinedTextField(
                                        value = editedWhySaved,
                                        onValueChange = { editedWhySaved = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = LocalAccentColor.current,
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                        )
                                    )
                                } else {
                                    // Inline Text Wrapping (Napkin Sketch Style)
                                    val whySavedText = note.whySaved ?: ""
                                    val text = androidx.compose.ui.text.buildAnnotatedString {
                                        appendInlineContent("icon", "[icon]")
                                        append(" ")
                                        append(whySavedText)
                                    }
                                    
                                    val inlineContent = mapOf(
                                        "icon" to androidx.compose.foundation.text.InlineTextContent(
                                            androidx.compose.ui.text.Placeholder(
                                                width = 40.sp, 
                                                height = 24.sp, // Slightly shorter for alignment
                                                placeholderVerticalAlign = androidx.compose.ui.text.PlaceholderVerticalAlign.Center
                                            )
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.fillMaxSize().padding(end = 8.dp)
                                            ) {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = LocalAccentColor.current.copy(alpha = 0.1f),
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = Icons.Outlined.BookmarkBorder,
                                                            contentDescription = null,
                                                            tint = LocalAccentColor.current,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    )

                                    Text(
                                        text = text,
                                        inlineContent = inlineContent,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 17.sp,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                            lineHeight = 26.sp
                                        ),
                                        color = LocalAccentColor.current
                                    )
                                }
                            }
                        }
                        
                        // Related Knowledge Section (Semantic Note Linking)
                        if (!isEditing && allNotes.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            RelatedNotesSection(
                                currentNote = note,
                                allNotes = allNotes,
                                onNoteClick = onNavigateToNote
                            )
                        }
                    }

                    KnowledgeTab.FILES -> {
                        // Unified File/Attachment Section
                        val attachments = note.getAttachments()
                        
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
                                    fileName = note.fileName ?: "File",
                                    mimeType = note.fileMimeType ?: fallbackMime,
                                    fileSize = note.fileSize ?: 0L
                                )
                            } else null
                        } else null

                        val otherAttachments = attachments.filter { !it.mimeType.startsWith("audio/") } + listOfNotNull(legacyMainAttachment)
                        val audioAttachments = attachments.filter { it.mimeType.startsWith("audio/") }
                        
                        val legacyAudioItem = if (note.type == NoteType.AUDIO && note.fileUri != null && attachments.isEmpty()) {
                            NoteAttachment(
                                uri = note.fileUri,
                                fileName = note.fileName ?: "Audio file",
                                mimeType = "audio/*",
                                fileSize = note.fileSize ?: 0L
                            )
                        } else null
                        
                        val allAudioItems = audioAttachments + listOfNotNull(legacyAudioItem)
                        val finalAttachments = (otherAttachments + allAudioItems).distinctBy { it.uri }

                        if (finalAttachments.isEmpty()) {
                            // Empty State
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(
                                        imageVector = Icons.Outlined.AttachFile,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Text(
                                        text = "No files attached",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                finalAttachments.forEach { attachment ->
                                    FileAttachmentItem(
                                        attachment = attachment,
                                        onOpen = {
                                            val mimeType = attachment.mimeType.ifEmpty { "*/*" }
                                            if (mimeType.startsWith("audio/")) {
                                                val track = AudioTrack(
                                                    uri = attachment.uri,
                                                    title = note.title,
                                                    fileName = attachment.fileName,
                                                    sourceNoteId = note.id,
                                                    mimeType = attachment.mimeType
                                                )
                                                onPlayAudio(track)
                                            } else {
                                                when {
                                                    mimeType.startsWith("image/") -> {
                                                        imageViewerUri = attachment.uri
                                                        showImageViewer = true
                                                    }
                                                    mimeType.startsWith("video/") -> {
                                                        videoPlayerUri = attachment.uri
                                                        showVideoPlayer = true
                                                    }
                                                    else -> {
                                                        val uri = attachment.uri
                                                        documentViewerUri = uri
                                                        documentViewerMimeType = mimeType
                                                        documentViewerFileName = attachment.fileName
                                                        showDocumentViewer = true
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    KnowledgeTab.ORIGINAL -> {
                        // Original Content
                        SectionCard(
                            title = "",
                            icon = Icons.AutoMirrored.Outlined.Article,
                            forceVertical = true
                        ) {
                            if (isEditing) {
                                OutlinedTextField(
                                    value = editedContent,
                                    onValueChange = { editedContent = it },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = LocalAccentColor.current,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    )
                                )
                            } else {
                                Text(
                                    text = note.content.ifBlank { "No content text available." },
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 17.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        lineHeight = 26.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        // Source Link Button
                        note.sourceUrl?.let { url ->
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Open Source")
                            }
                        }
                    }
                }
                
                // Common Footer Elements
                // Metadata Footer
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Created ${formatDate(note.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                
                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "ID: ${note.id.take(8)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                // History Icon (Minimalist)
                IconButton(
                    onClick = {
                        onLoadVersions()
                        showVersionHistory = true
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "View History",
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
                onAskAI = onAskAI
            )
        }
    }

    // Delete Confirmation Dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    text = "Delete Note?",
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    text = "This action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDeleteClick()
                    }
                ) {
                    Text(
                        text = "Delete",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(18.dp)
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

    // Decompression Loading Dialog
    if (isDecompressing) {
        AlertDialog(
            onDismissRequest = { /* Can't dismiss while decompressing */ },
            confirmButton = { },
            title = {
                Text(
                    text = "Opening Document",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Decompressing file...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Version History Sheet
    if (showVersionHistory) {
        VersionHistorySheet(
            versions = noteVersions,
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

enum class KnowledgeTab(val title: String) {
    SUMMARY("Summary"),
    FILES("Files"),
    ORIGINAL("Original Content")
}

@Composable
fun KnowledgeHeaderCard(
    note: Note,
    isEditing: Boolean = false,
    editedTitle: String = "",
    onTitleChange: (String) -> Unit = {}
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .softCardShadow(shape = RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface, 
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon Container
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = getNoteTypeColor(note.type).copy(alpha = 0.1f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = getNoteTypeIcon(note.type),
                        contentDescription = null,
                        tint = getNoteTypeColor(note.type),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Info Column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = getNoteTypeName(note.type).uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                
                if (isEditing) {
                    OutlinedTextField(
                        value = editedTitle,
                        onValueChange = onTitleChange,
                        textStyle = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            lineHeight = 28.sp
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = LocalAccentColor.current,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                } else {
                    Text(
                        text = note.title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            lineHeight = 28.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            // Status Tag (TODO / DONE / PROCESSING)
            val statusColor = when(note.processingStatus) {
                com.example.smarty.data.model.ProcessingStatus.COMPLETED -> Color(0xFF66C2A5) // Greenish
                com.example.smarty.data.model.ProcessingStatus.FAILED -> MaterialTheme.colorScheme.error
                else -> Color(0xFFFC8D62) // Orangeish for pending/processing
            }
            
            Surface(
                color = statusColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.align(Alignment.Top)
            ) {
                Text(
                    text = if (note.processingStatus == com.example.smarty.data.model.ProcessingStatus.COMPLETED) "DONE" else "TODO",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

private val AppLightBlue = Color(0xFFD0E7FE)
private val AppDarkBlue = Color(0xFF003258)

@Composable
fun KnowledgeTabRow(
    selectedTab: KnowledgeTab,
    onTabSelected: (KnowledgeTab) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        KnowledgeTab.entries.forEach { tab ->
            val isSelected = selectedTab == tab
            val animColor by animateColorAsState(
                targetValue = if (isSelected) AppLightBlue else Color.Transparent, 
                label = "tabBg"
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) AppDarkBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "tabText"
            )
            val elevation by animateDpAsState(
                targetValue = if (isSelected) 4.dp else 0.dp,
                label = "tabElevation"
            )

            Surface(
                shape = RoundedCornerShape(50),
                color = if (isSelected) animColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), // Light gray for unselected
                shadowElevation = 0.dp, // Flat for cleaner look
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clickable { onTabSelected(tab) }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = tab.title,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium
                        ),
                        color = textColor
                    )
                }
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .softCardShadow(shape = RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp
    ) {
        if (inlineLayout) {
             // Custom/Inline Mode: No forced Row/Column structure, just padding
             // Caller handles icon integration (e.g. inline text)
             Box(modifier = Modifier.padding(20.dp)) {
                 content()
             }
        } else if (title.isNotBlank() || forceVertical || showToggleIndicator) {
            // Mode 1: Header + Full Width Body (For Summary with metadata OR forced vertical layout)
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                // Header Row: Icon + Title (if present)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Icon Container (clickable if onIconClick provided)
                    Surface(
                        shape = CircleShape,
                        color = if (isToggled) accentColor.copy(alpha = 0.2f) else accentColor.copy(alpha = 0.1f),
                        modifier = Modifier
                            .size(32.dp)
                            .then(
                                if (onIconClick != null) {
                                    Modifier.clickable { onIconClick() }
                                } else Modifier
                            )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = if (showToggleIndicator) "Toggle view" else null,
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Title Text (Metadata like "115 pages analyzed")
                    if (title.isNotBlank()) {
                        Text(
                            text = title, // Keep original case
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    // Toggle indicator (shown when chunk analyses available)
                    if (showToggleIndicator) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isToggled) accentColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.clickable { onIconClick?.invoke() }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                @Suppress("DEPRECATION")
                                Icon(
                                    imageVector = if (isToggled) Icons.Filled.ViewList else Icons.Default.Summarize,
                                    contentDescription = null,
                                    tint = if (isToggled) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isToggled) "Pages" else "Summary",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isToggled) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Main Content (Full Width)
                Box(modifier = Modifier.fillMaxWidth()) {
                    content()
                }
            }
        } else {
            // Mode 2: Icon | Content Side-by-Side (For simple sections)
            Row(
                modifier = Modifier.padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Icon Container
                Surface(
                    shape = CircleShape,
                    color = accentColor.copy(alpha = 0.1f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                
                // Content Column
                Box(modifier = Modifier.weight(1f)) {
                    content()
                }
            }
        }
    }
}

private fun getNoteTypeName(type: NoteType): String {
    return when (type) {
        NoteType.YOUTUBE -> "YouTube"
        NoteType.TWITTER -> "Twitter/X"
        NoteType.INSTAGRAM -> "Instagram"
        NoteType.WEBSITE -> "Web Link"
        NoteType.IMAGE -> "Image"
        NoteType.BRAIN_DUMP -> "Brain Dump"
        NoteType.DOCUMENT -> "Document"
        NoteType.SPREADSHEET -> "Spreadsheet"
        NoteType.PRESENTATION -> "Presentation"
        NoteType.VIDEO -> "Video"
        NoteType.AUDIO -> "Audio"
        NoteType.CODE -> "Code"
        NoteType.ARCHIVE -> "Archive"
        NoteType.APK -> "APK"
        NoteType.FILE -> "File"
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatFileSize(bytes: Long): String = com.example.smarty.util.ContentTypeDetector.formatFileSize(bytes)



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
                    .background(if (isImage) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f) else AppLightBlue),
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
                        tint = AppDarkBlue,
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
                    text = attachment.fileName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${attachment.mimeType.substringAfterLast('/')} • ${formatFileSize(attachment.fileSize)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Open/Play Icon
            val actionIcon = if (attachment.mimeType.startsWith("audio/")) Icons.Default.PlayArrow else Icons.Default.ChevronRight
            Icon(
                imageVector = actionIcon,
                contentDescription = "Open",
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
                Text(
                    text = formatFileSize(fileSize),
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
                    text = "Private Note",
                    style = MaterialTheme.typography.titleSmall,
                    color = privacyColor
                )
                Text(
                    text = "AI cannot access this note",
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
                    text = "Edit Note",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { 
                            // filtering duplicates before saving
                            onSave(editedTitle, editedContent, currentAttachments.distinctBy { it.uri }) 
                        },
                        enabled = editedTitle.isNotBlank(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save")
                    }
                }
            }

            // Attachments Management Section
            if (currentAttachments.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Attachments",
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
                                        contentDescription = "Remove attachment",
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
                label = { Text("Title") },
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
                label = { Text("Content") },
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
                        text = "Version History",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${versions.size} version${if (versions.size != 1) "s" else ""} saved",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (versions.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "No versions yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Edit this note to create a version",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
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
        AlertDialog(
            onDismissRequest = {
                showRestoreDialog = false
                selectedVersion = null
            },
            title = {
                Text(
                    text = "Restore Version?",
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "This will restore the note to version ${selectedVersion!!.versionNumber}.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "A new version will be created with the current content before restoring.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedVersion?.let { onRestoreVersion(it.id) }
                        showRestoreDialog = false
                        selectedVersion = null
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRestoreDialog = false
                        selectedVersion = null
                    }
                ) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(18.dp)
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
                        text = "v${version.versionNumber}",
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
                        text = version.title,
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
                                text = "Latest",
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalAccentColor.current,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Text(
                    text = formatDate(version.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                version.changeDescription?.let { desc ->
                    Text(
                        text = desc,
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
                contentDescription = "Restore this version",
                tint = if (isFirst) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
