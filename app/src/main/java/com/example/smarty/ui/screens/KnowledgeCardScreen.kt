package com.example.smarty.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
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
import com.example.smarty.data.model.getAttachments
import com.example.smarty.ui.components.CategoryChip
import com.example.smarty.ui.components.DecompressionPlaceholder
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
    onEditNote: (String, String, String) -> Unit = { _, _, _ -> },  // noteId, newTitle, newContent
    onPlayAudio: (AudioTrack) -> Unit = {},
    onMarkAsViewed: () -> Unit = {},
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditSheet by remember { mutableStateOf(false) }

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
                    IconButton(onClick = onArchiveClick) {
                        Icon(
                            imageVector = Icons.Default.Archive,
                            contentDescription = "Archive"
                        )
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete"
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
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp + bottomContentPadding),
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

            // Image Preview Section (for image notes or notes with image attachments)
            if (note.type == NoteType.IMAGE || note.imageUri != null) {
                val imageUri = note.imageUri ?: note.fileUri
                if (imageUri != null) {
                    ImagePreviewSection(
                        imageUri = imageUri,
                        fileName = note.fileName,
                        onOpenImage = {
                            imageViewerUri = imageUri
                            showImageViewer = true
                        }
                    )
                }
            }

            // Video Preview Section (for video notes)
            if (note.type == NoteType.VIDEO || note.fileMimeType?.startsWith("video/") == true) {
                val videoUri = note.fileUri ?: note.imageUri
                if (videoUri != null) {
                    VideoPreviewSection(
                        videoUri = videoUri,
                        fileName = note.fileName,
                        fileSize = note.fileSize,
                        onPlayVideo = {
                            videoPlayerUri = videoUri
                            showVideoPlayer = true
                        }
                    )
                }
            }

            // Document/File Preview Section (for documents, spreadsheets, etc.)
            if (note.fileUri != null && note.type !in listOf(NoteType.IMAGE, NoteType.VIDEO, NoteType.AUDIO)) {
                FilePreviewSection(
                    fileName = note.fileName ?: "Attached file",
                    fileSize = note.fileSize,
                    mimeType = note.fileMimeType,
                    onOpenFile = {
                        documentViewerUri = note.fileUri
                        documentViewerMimeType = note.fileMimeType
                        documentViewerFileName = note.fileName
                        showDocumentViewer = true
                    }
                )
            }

            // Multiple Attachments Section
            val attachments = note.getAttachments()
            if (attachments.size > 1) {
                MultipleAttachmentsSection(
                    attachments = attachments,
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

            // Audio Playback Section (for audio notes)
            if (note.type == NoteType.AUDIO && note.fileUri != null) {
                SectionCard(
                    title = "Audio",
                    icon = Icons.Default.MusicNote,
                    accentColor = AudioPink
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = note.fileName ?: "Audio file",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            note.fileSize?.let { size ->
                                Text(
                                    text = formatFileSize(size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                            FilledIconButton(
                            onClick = {
                                val mimeType = context.contentResolver.getType(Uri.parse(note.fileUri))
                                val track = AudioTrack(
                                    uri = note.fileUri!!,
                                    title = note.title,
                                    fileName = note.fileName,
                                    sourceNoteId = note.id,
                                    mimeType = mimeType
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
        EditNoteSheet(
            note = note,
            onDismiss = { showEditSheet = false },
            onSave = { newTitle, newContent ->
                onEditNote(note.id, newTitle, newContent)
                showEditSheet = false
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

/**
 * Image preview section showing full-width image with tap to open.
 * Supports decompression of WebP compressed images with shimmer effect.
 */
@Composable
private fun ImagePreviewSection(
    imageUri: String,
    fileName: String?,
    onOpenImage: () -> Unit
) {
    SectionCard(
        title = "Image",
        icon = Icons.Default.Image,
        accentColor = LocalAccentColor.current
    ) {
        CompactFileRow(
            fileName = fileName ?: "Image",
            fileSize = null, // Size might not be available here, or we can pass it if we have it
            mimeType = "image/*",
            onAction = onOpenImage
        )
    }
}

/**
 * Video preview section with thumbnail and play button
 */
@Composable
private fun VideoPreviewSection(
    videoUri: String,
    fileName: String?,
    fileSize: Long?,
    onPlayVideo: () -> Unit
) {
    SectionCard(
        title = "Video",
        icon = Icons.Default.VideoFile,
        accentColor = com.example.smarty.ui.theme.VideoRed
    ) {
        CompactFileRow(
            fileName = fileName ?: "Video",
            fileSize = fileSize,
            mimeType = "video/*",
            onAction = onPlayVideo
        )
    }
}

/**
 * File/Document preview section for non-media files
 */
@Composable
private fun FilePreviewSection(
    fileName: String,
    fileSize: Long?,
    mimeType: String?,
    onOpenFile: () -> Unit
) {
    // Determine icon color
    val iconColor = when {
        mimeType?.contains("pdf") == true -> com.example.smarty.ui.theme.DocumentBlue
        mimeType?.contains("sheet") == true || mimeType?.contains("excel") == true -> com.example.smarty.ui.theme.SpreadsheetGreen
        mimeType?.contains("presentation") == true -> com.example.smarty.ui.theme.PresentationOrange
        mimeType?.contains("zip") == true -> com.example.smarty.ui.theme.ArchiveYellow
        mimeType == "application/vnd.android.package-archive" -> com.example.smarty.ui.theme.ApkGreen
        else -> com.example.smarty.ui.theme.FileGray
    }

    SectionCard(
        title = "Attachment",
        icon = Icons.Default.AttachFile,
        accentColor = iconColor
    ) {
        CompactFileRow(
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType ?: "*/*",
            onAction = onOpenFile
        )
    }
}

/**
 * Section displaying multiple attachments as a list of clickable items
 * Each attachment shows: icon, file name, size, and open button
 */
@Composable
private fun MultipleAttachmentsSection(
    attachments: List<NoteAttachment>,
    onOpenAttachment: (NoteAttachment) -> Unit
) {
    // Determine header based on attachment types
    val allImages = attachments.all { it.mimeType.startsWith("image/") }
    val allVideos = attachments.all { it.mimeType.startsWith("video/") }
    val allAudio = attachments.all { it.mimeType.startsWith("audio/") }

    val (headerTitle, headerIcon, headerColor) = when {
        allImages -> Triple("${attachments.size} Images", Icons.Default.Photo, com.example.smarty.ui.theme.ImageTeal)
        allVideos -> Triple("${attachments.size} Videos", Icons.Default.Videocam, com.example.smarty.ui.theme.VideoRed)
        allAudio -> Triple("${attachments.size} Audio Files", Icons.Default.MusicNote, AudioPink)
        else -> Triple("${attachments.size} Attachments", Icons.Default.AttachFile, com.example.smarty.ui.theme.FileGray)
    }

    SectionCard(
        title = headerTitle,
        icon = headerIcon,
        accentColor = headerColor
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            attachments.forEachIndexed { index, attachment ->
                AttachmentItem(
                    attachment = attachment,
                    index = index + 1,
                    onOpen = { onOpenAttachment(attachment) }
                )

                // Add divider between items (but not after the last)
                if (index < attachments.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}

/**
 * Individual attachment item showing icon, name, size, and open button
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

        Spacer(modifier = Modifier.width(8.dp))

        // Action Button
        FilledIconButton(
            onClick = onAction,
            modifier = Modifier.size(36.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = iconColor,
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "Open",
                modifier = Modifier.size(16.dp)
            )
        }
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
    onDismiss: () -> Unit,
    onSave: (title: String, content: String) -> Unit
) {
    var editedTitle by remember { mutableStateOf(note.title) }
    var editedContent by remember { mutableStateOf(note.content) }
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
                        onClick = { onSave(editedTitle, editedContent) },
                        enabled = editedTitle.isNotBlank(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save")
                    }
                }
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
