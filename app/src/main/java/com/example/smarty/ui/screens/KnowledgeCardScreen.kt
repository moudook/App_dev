package com.example.smarty.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.example.smarty.ui.components.viewers.FullScreenDocumentViewer
import com.example.smarty.ui.components.viewers.FullScreenImageViewer
import com.example.smarty.ui.components.viewers.FullScreenVideoPlayer
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
    onPlayAudio: (AudioTrack) -> Unit = {},
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Viewer states
    var showImageViewer by remember { mutableStateOf(false) }
    var imageViewerUri by remember { mutableStateOf<String?>(null) }

    var showVideoPlayer by remember { mutableStateOf(false) }
    var videoPlayerUri by remember { mutableStateOf<String?>(null) }

    var showDocumentViewer by remember { mutableStateOf(false) }
    var documentViewerUri by remember { mutableStateOf<String?>(null) }
    var documentViewerMimeType by remember { mutableStateOf<String?>(null) }
    var documentViewerFileName by remember { mutableStateOf<String?>(null) }

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

            // Title
            Text(
                text = note.title,
                style = MaterialTheme.typography.headlineMedium,
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
}

@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = accentColor
                )
            }
            content()
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

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Decompression state
    var isDecompressing by remember { mutableStateOf(false) }
    var decompressedUri by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf(false) }

    // Determine if file needs decompression
    val compressionType = remember(imageUri) {
        FileCompressor.getCompressionType(imageUri, "image/*")
    }

    // WebP images don't need decompression - they're directly viewable
    // GZIP images would need decompression but we don't GZIP images
    val displayUri = decompressedUri ?: imageUri

    // Start decompression for GZIP files when entering screen
    LaunchedEffect(imageUri) {
        if (compressionType == CompressionType.GZIP) {
            isDecompressing = true
            scope.launch {
                try {
                    val file = File(imageUri.removePrefix("file://"))
                    if (file.exists()) {
                        val cacheDir = FileStorageHelper.getDecompressionCacheDir(context)
                        val decompressed = FileCompressor.decompressForViewing(
                            fileId = imageUri,
                            compressedFile = file,
                            compressionType = compressionType,
                            cacheDir = cacheDir
                        )
                        decompressedUri = decompressed?.let { "file://${it.absolutePath}" }
                    }
                } catch (e: Exception) {
                    loadError = true
                } finally {
                    isDecompressing = false
                }
            }
        }
    }

    SectionCard(
        title = "Image",
        icon = Icons.Default.Image,
        accentColor = LocalAccentColor.current
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Image preview with shimmer during decompression
            if (isDecompressing) {
                DecompressionPlaceholder(
                    modifier = Modifier.fillMaxWidth(),
                    aspectRatio = 4f / 3f
                )
            } else {
                ShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp, max = 300.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenImage() },
                    isLoading = false
                ) {
                    Surface(
                        modifier = Modifier.matchParentSize(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(displayUri)
                                .crossfade(true)
                                .build(),
                            contentDescription = fileName ?: "Image",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Tap to open hint
            if (!isDecompressing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.TouchApp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Tap to open full image",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
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
    val context = LocalContext.current

    SectionCard(
        title = "Video",
        icon = Icons.Default.VideoFile,
        accentColor = com.example.smarty.ui.theme.VideoRed
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Video thumbnail with play overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPlayVideo() }
            ) {
                // Thumbnail
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(videoUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = fileName ?: "Video",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Dark overlay
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.3f)
                ) {}

                // Play button
                Surface(
                    modifier = Modifier
                        .size(64.dp)
                        .align(Alignment.Center),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.95f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play video",
                            tint = com.example.smarty.ui.theme.VideoRed,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }

            // File info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (fileName != null) {
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (fileSize != null) {
                        Text(
                            text = formatFileSize(fileSize),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                FilledTonalButton(
                    onClick = onPlayVideo,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = com.example.smarty.ui.theme.VideoRed.copy(alpha = 0.15f),
                        contentColor = com.example.smarty.ui.theme.VideoRed
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play")
                }
            }
        }
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
    // Determine icon and color based on mime type
    val (icon, iconColor) = when {
        mimeType?.contains("pdf") == true -> Icons.AutoMirrored.Filled.Article to com.example.smarty.ui.theme.DocumentBlue
        mimeType?.contains("document") == true || mimeType?.contains("word") == true ->
            Icons.AutoMirrored.Filled.Article to com.example.smarty.ui.theme.DocumentBlue
        mimeType?.contains("sheet") == true || mimeType?.contains("excel") == true ->
            Icons.Default.TableChart to com.example.smarty.ui.theme.SpreadsheetGreen
        mimeType?.contains("presentation") == true || mimeType?.contains("powerpoint") == true ->
            Icons.Default.Slideshow to com.example.smarty.ui.theme.PresentationOrange
        mimeType?.contains("zip") == true || mimeType?.contains("rar") == true || mimeType?.contains("tar") == true ->
            Icons.Default.FolderZip to com.example.smarty.ui.theme.ArchiveYellow
        mimeType == "application/vnd.android.package-archive" ->
            Icons.Default.Android to com.example.smarty.ui.theme.ApkGreen
        mimeType?.contains("text") == true ->
            Icons.AutoMirrored.Filled.TextSnippet to com.example.smarty.ui.theme.FileGray
        else -> Icons.Default.AttachFile to com.example.smarty.ui.theme.FileGray
    }

    SectionCard(
        title = "Attachment",
        icon = Icons.Default.AttachFile,
        accentColor = iconColor
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // File icon
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(12.dp),
                color = iconColor.copy(alpha = 0.15f)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // File info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (fileSize != null) {
                    Text(
                        text = formatFileSize(fileSize),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Open button
            FilledIconButton(
                onClick = onOpenFile,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = iconColor,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open file"
                )
            }
        }
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
    // Determine icon and color based on mime type
    val (icon, iconColor) = when {
        attachment.mimeType.startsWith("image/") -> Icons.Default.Photo to com.example.smarty.ui.theme.ImageTeal
        attachment.mimeType.startsWith("video/") -> Icons.Default.Videocam to com.example.smarty.ui.theme.VideoRed
        attachment.mimeType.startsWith("audio/") -> Icons.Default.MusicNote to AudioPink
        attachment.mimeType.contains("pdf") -> Icons.AutoMirrored.Filled.Article to com.example.smarty.ui.theme.DocumentBlue
        attachment.mimeType.contains("document") || attachment.mimeType.contains("word") ->
            Icons.AutoMirrored.Filled.Article to com.example.smarty.ui.theme.DocumentBlue
        attachment.mimeType.contains("sheet") || attachment.mimeType.contains("excel") ->
            Icons.Default.TableChart to com.example.smarty.ui.theme.SpreadsheetGreen
        attachment.mimeType.contains("presentation") || attachment.mimeType.contains("powerpoint") ->
            Icons.Default.Slideshow to com.example.smarty.ui.theme.PresentationOrange
        attachment.mimeType.contains("zip") || attachment.mimeType.contains("rar") ->
            Icons.Default.FolderZip to com.example.smarty.ui.theme.ArchiveYellow
        else -> Icons.Default.AttachFile to com.example.smarty.ui.theme.FileGray
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onOpen() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Index badge with icon
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(10.dp),
            color = iconColor.copy(alpha = 0.12f)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // File info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = attachment.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (attachment.fileSize > 0) {
                Text(
                    text = formatFileSize(attachment.fileSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Open button
        FilledTonalIconButton(
            onClick = onOpen,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = iconColor.copy(alpha = 0.15f),
                contentColor = iconColor
            ),
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "Open file",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
