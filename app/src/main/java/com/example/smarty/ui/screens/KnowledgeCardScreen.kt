package com.example.smarty.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.smarty.util.CompressionType
import com.example.smarty.util.FileCompressor
import com.example.smarty.util.FileStorageHelper
import com.example.smarty.util.FileViewerHelper
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.runtime.rememberCoroutineScope
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeCardScreen(
    note: Note,
    onBackClick: () -> Unit,
    onArchiveClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onEditNote: (String, String, String, List<NoteAttachment>) -> Unit = { _, _, _, _ -> },  // noteId, newTitle, newContent, attachments
    onPlayAudio: (AudioTrack) -> Unit = {},
    onMarkAsViewed: () -> Unit = {},
    // Version history callbacks
    noteVersions: List<NoteVersion> = emptyList(),
    onLoadVersions: () -> Unit = {},
    onRestoreVersion: (String) -> Unit = {},  // versionId
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditSheet by remember { mutableStateOf(false) }
    var showVersionHistory by remember { mutableStateOf(false) }

    // Viewer states
    var showImageViewer by remember { mutableStateOf(false) }
    var imageViewerUri by remember { mutableStateOf<String?>(null) }

    var showVideoPlayer by remember { mutableStateOf(false) }
    var videoPlayerUri by remember { mutableStateOf<String?>(null) }

    var showDocumentViewer by remember { mutableStateOf(false) }
    var documentViewerUri by remember { mutableStateOf<String?>(null) }
    var documentViewerMimeType by remember { mutableStateOf<String?>(null) }
    var documentViewerFileName by remember { mutableStateOf<String?>(null) }

    // Mark as viewed when screen is opened
    LaunchedEffect(note.id) {
        if (!note.isViewed) {
             onMarkAsViewed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showEditSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 100.dp + bottomContentPadding),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
            // Type and Category Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = getNoteTypeIcon(note.type),
                        contentDescription = null,
                        tint = getNoteTypeColor(note.type),
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = getNoteTypeName(note.type),
                        style = MaterialTheme.typography.labelLarge,
                        color = getNoteTypeColor(note.type)
                    )
                }

                note.categoryName?.let { category ->
                    CategoryChip(name = category)
                }
            }

            // Privacy Banner (shown when note is private)
            if (note.isFullPrivacy || note.excludeFromAiChat) {
                PrivacyBanner()
            }

            // Title
            Text(
                text = note.title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                    letterSpacing = (-1).sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            // AI Summary Section
            note.summary?.let { summary ->
                SectionCard(
                    title = "AI Summary",
                    icon = Icons.Default.AutoAwesome
                ) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Why You Saved This
            note.whySaved?.let { reason ->
                SectionCard(
                    title = "Why You Saved This",
                    icon = Icons.Default.Lightbulb,
                    accentColor = LocalAccentColor.current
                ) {
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalAccentColor.current
                    )
                }
            }

            // Original Content
            SectionCard(
                title = "Original Content",
                icon = Icons.Default.Description
            ) {
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Unified File/Attachment Section
            // We combine legacy main file (if no attachments exist) with the attachments list
            // to avoid duplicates and ensure a consistent list UI.
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

            // 1. Render Non-Audio Attachments
            if (otherAttachments.isNotEmpty()) {
                AttachmentMosaic(
                    attachments = otherAttachments,
                    onOpenAttachment = { attachment ->
                        val mimeType = attachment.mimeType.ifEmpty { "*/*" }
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
                                documentViewerUri = attachment.uri
                                documentViewerMimeType = mimeType
                                documentViewerFileName = attachment.fileName
                                showDocumentViewer = true
                            }
                        }
                    }
                )
            }

            // 2. Audio Gallery Section (Unified for all audio files)
            // Combine strict attachments with legacy fileUri if applicable (for backward compatibility)
            val legacyAudioItem = if (note.type == NoteType.AUDIO && note.fileUri != null && attachments.isEmpty()) {
                NoteAttachment(
                    uri = note.fileUri,
                    fileName = note.fileName ?: "Audio file",
                    mimeType = "audio/*",
                    fileSize = note.fileSize ?: 0L
                )
            } else null

            // Combine both sources
            val allAudioItems = audioAttachments + listOfNotNull(legacyAudioItem)

            if (allAudioItems.isNotEmpty()) {
                SectionCard(
                    title = if (allAudioItems.size > 1) "${allAudioItems.size} Audio Files" else "Audio",
                    icon = Icons.Default.MusicNote,
                    accentColor = AudioPink
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        allAudioItems.forEachIndexed { index, audioItem ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = audioItem.fileName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sizeText = if (audioItem.fileSize > 0) formatFileSize(audioItem.fileSize) else null
                                    if (sizeText != null) {
                                        Text(
                                            text = sizeText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                FilledIconButton(
                                    onClick = {
                                        val track = AudioTrack(
                                            uri = audioItem.uri,
                                            title = note.title, // Use note title as context
                                            fileName = audioItem.fileName,
                                            sourceNoteId = note.id,
                                            mimeType = audioItem.mimeType
                                        )
                                        onPlayAudio(track)
                                    },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = AudioPink,
                                        contentColor = MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play audio"
                                    )
                                }
                            }
                            
                            // Divider between items
                            if (index < allAudioItems.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                )
                            }
                        }
                    }
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
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Open Source",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Metadata Footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Created ${formatDate(note.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Text(
                    text = "ID: ${note.id.take(8)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }

            // Version History Button
            OutlinedButton(
                onClick = {
                    onLoadVersions()
                    showVersionHistory = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline
                )
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "View History",
                    style = MaterialTheme.typography.labelLarge
                )
            }
            }

            // Floating Action Bar at bottom
            FloatingActionBar(
                onEdit = { showEditSheet = true },
                onArchive = onArchiveClick,
                onDelete = { showDeleteDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
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

    // Edit Note Sheet
    if (showEditSheet) {
        val initialAttachments = remember(note) {
            val list = note.getAttachments().toMutableList()
            if (list.isEmpty() && note.fileUri != null) {
                list.add(
                    NoteAttachment(
                        uri = note.fileUri,
                        fileName = note.fileName ?: "File",
                        mimeType = note.fileMimeType ?: "*/*",
                        fileSize = note.fileSize ?: 0L
                    )
                )
            }
            list
        }

        EditNoteSheet(
            note = note,
            initialAttachments = initialAttachments,
            onDismiss = { showEditSheet = false },
            onSave = { newTitle, newContent, newAttachments ->
                onEditNote(note.id, newTitle, newContent, newAttachments)
                showEditSheet = false
            }
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

@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: androidx.compose.ui.graphics.Color = LocalAccentColor.current,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .softCardShadow(shape = RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp // Handled by custom shadow
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Icon Container
                Surface(
                    shape = CircleShape,
                    color = accentColor.copy(alpha = 0.12f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        fontSize = 17.sp,
                        letterSpacing = (-0.5).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            // Content with slight padding
            Box(modifier = Modifier.padding(start = 4.dp)) {
                content()
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
fun AttachmentMosaic(
    attachments: List<NoteAttachment>,
    onOpenAttachment: (NoteAttachment) -> Unit
) {
    if (attachments.isEmpty()) return

    val count = attachments.size
    val spacing = 2.dp
    // Height constraint for the mosaic
    val height = 280.dp 

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
    ) {
        // LEFT COLUMN (Main Item)
        // If count == 1, take full width. Else take 66% (or weight 1.8f).
        Box(
            modifier = Modifier
                .weight(if (count > 1) 1.8f else 1f)
                .fillMaxHeight()
        ) {
           MosaicTile(attachments[0], onOpen = { onOpenAttachment(attachments[0]) })
        }

        if (count > 1) {
            Spacer(modifier = Modifier.width(spacing))
            
            // RIGHT COLUMN
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                // Top Right Item
                // If count == 2, take full height. Else take 50% (weight 1f).
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    MosaicTile(attachments[1], onOpen = { onOpenAttachment(attachments[1]) })
                }
                
                if (count > 2) {
                    Spacer(modifier = Modifier.height(spacing))
                    
                    // Bottom Right Item (Counter or 3rd Image)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        // Display 3rd item as background
                        MosaicTile(
                            attachment = attachments[2],
                            onOpen = { onOpenAttachment(attachments[2]) },
                            isDimmed = count > 3
                        )
                         
                         // Dedicated Overlay for overflow (Count > 3)
                         // e.g. If 4 items, we show 1, 2, 3(dimmed) with "+1" meaning "3 and 1 more" -> Total 4.
                         // Or usually "+N" on the last tile means "N more items hidden".
                         // So if Total=4, Valid Indices: 0,1,2,3.
                         // We show 0,1,2. Hidden=3. Count=1.
                         // Display "+1".
                         // Text should be "+${count - 3}" if we consider 3 shown.
                         // Wait, user sketch says "+4" and "img 2".
                         // Implying "img 2" is one item, "+4" is the rest.
                         // Total = 1(Main) + 1(Img2) + 4(Rest) = 6.
                         // My layout: Main, TopRight(Img2), BottomRight(Rest).
                         // BottomRight acts as the container for the rest.
                         // So BottomRight should display "+4".
                         // Does it show Img3? Probably as background.
                         // So Count - 2 is the number represented in that box?
                         // If Total=6. Main(1) + TR(1) = 2 shown fully.
                         // Remaining = 4.
                         // So "+4".
                         // Yes, `count - 2` logic works for the text.
                         
                         if (count > 3) {
                             Box(
                                 modifier = Modifier
                                    .matchParentSize()
                                    .background(Color.Black.copy(alpha = 0.5f)),
                                 contentAlignment = Alignment.Center
                             ) {
                                  Text(
                                      text = "+${count - 2}", 
                                      style = MaterialTheme.typography.displaySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                                      color = Color.White
                                  )
                             }
                         }
                    }
                }
            }
        }
    }
}

@Composable
private fun MosaicTile(
    attachment: NoteAttachment,
    onOpen: () -> Unit,
    isDimmed: Boolean = false
) {
    val mimeType = attachment.mimeType
    val isImage = mimeType.startsWith("image/")
    val isVideo = mimeType.startsWith("video/")
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpen() }
    ) {
        if (isImage) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(attachment.uri)
                    .crossfade(true)
                    .build(),
                contentDescription = attachment.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Fallback for non-images
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // Icon Logic
                val icon = when {
                    isVideo -> Icons.Default.Videocam
                    mimeType.startsWith("audio/") -> Icons.Default.MusicNote
                    else -> Icons.AutoMirrored.Filled.Article
                }
                Icon(
                    imageVector = icon, 
                    contentDescription = null, 
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
                if (isVideo) {
                     Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(48.dp)
                     )
                }
            }
        }
        
        // Gradient scrim for text legibility if needed, or simple dim
        if (isDimmed) {
             Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.1f)))
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
        mimeType.startsWith("image/") -> Icons.Default.Photo to com.example.smarty.ui.theme.ImageTeal
        mimeType.startsWith("video/") -> Icons.Default.Videocam to com.example.smarty.ui.theme.VideoRed
        mimeType.startsWith("audio/") -> Icons.Default.MusicNote to AudioPink
        mimeType.contains("pdf") -> Icons.AutoMirrored.Filled.Article to com.example.smarty.ui.theme.DocumentBlue
        mimeType.contains("document") || mimeType.contains("word") ->
            Icons.AutoMirrored.Filled.Article to com.example.smarty.ui.theme.DocumentBlue
        mimeType.contains("sheet") || mimeType.contains("excel") ->
            Icons.Default.TableChart to com.example.smarty.ui.theme.SpreadsheetGreen
        mimeType.contains("presentation") || mimeType.contains("powerpoint") ->
            Icons.Default.Slideshow to com.example.smarty.ui.theme.PresentationOrange
        mimeType.contains("zip") || mimeType.contains("rar") ->
            Icons.Default.FolderZip to com.example.smarty.ui.theme.ArchiveYellow
        mimeType == "application/vnd.android.package-archive" ->
            Icons.Default.Android to com.example.smarty.ui.theme.ApkGreen
        else -> Icons.Default.AttachFile to com.example.smarty.ui.theme.FileGray
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
                                            attachment.mimeType.startsWith("image/") -> Icons.Default.Photo
                                            attachment.mimeType.startsWith("audio/") -> Icons.Default.MusicNote
                                            attachment.mimeType.startsWith("video/") -> Icons.Default.Videocam
                                            else -> Icons.Default.AttachFile
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
