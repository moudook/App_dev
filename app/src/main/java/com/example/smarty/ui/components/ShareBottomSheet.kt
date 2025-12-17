package com.example.smarty.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.MonoFont

/**
 * Data class for pending share content
 */
data class PendingShareData(
    val text: String? = null,
    val fileUri: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSize: Long? = null,
    val detectedType: NoteType = NoteType.FILE,
    val suggestedCategory: String? = null,
    val relatedNotes: List<Note> = emptyList()
)

/**
 * Bottom sheet for configuring shared content before saving.
 * Shows file preview, category selection, related notes, and AI instructions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareBottomSheet(
    pendingShare: PendingShareData,
    categories: List<Category>,
    sheetState: SheetState,
    isFullPrivacy: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (selectedCategory: String?, aiInstructions: String) -> Unit
) {
    var selectedCategory by remember { mutableStateOf<String?>(pendingShare.suggestedCategory) }
    // In full privacy mode, AI cannot decide - always manual selection
    var letAIDecide by remember { mutableStateOf(!isFullPrivacy) }
    var aiInstructions by remember { mutableStateOf("") }

    // Force letAIDecide to false when privacy mode changes
    LaunchedEffect(isFullPrivacy) {
        if (isFullPrivacy) {
            letAIDecide = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Full Privacy Mode Banner - shows when shake activated
            PrivacyModeBanner(
                isActive = isFullPrivacy,
                modifier = Modifier.fillMaxWidth()
            )

            if (isFullPrivacy) {
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "save_to_cogni",
                    style = MaterialTheme.typography.headlineSmall,
                    color = LocalAccentColor.current
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // File/Content Preview Section
            SharePreviewCard(pendingShare)

            Spacer(modifier = Modifier.height(20.dp))

            // Category Selection Section
            Text(
                text = "Category",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Let AI decide toggle (hidden in full privacy mode)
            AnimatedVisibility(
                visible = !isFullPrivacy,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { letAIDecide = !letAIDecide }
                        .border(
                            1.dp,
                            if (letAIDecide) LocalAccentColor.current else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = if (letAIDecide) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Let AI decide",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (letAIDecide) LocalAccentColor.current else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Switch(
                        checked = letAIDecide,
                        onCheckedChange = { letAIDecide = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = LocalAccentColor.current,
                            checkedTrackColor = LocalAccentColor.current.copy(alpha = 0.3f)
                        )
                    )
                }
            }

            // Category chips (always visible in privacy mode, otherwise depends on letAIDecide)
            AnimatedVisibility(
                visible = !letAIDecide || isFullPrivacy,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(categories) { category ->
                            FilterChip(
                                selected = selectedCategory == category.name,
                                onClick = {
                                    selectedCategory = if (selectedCategory == category.name) null else category.name
                                },
                                label = { Text(category.name) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LocalAccentColor.current.copy(alpha = 0.2f),
                                    selectedLabelColor = LocalAccentColor.current
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Related Notes Section
            if (pendingShare.relatedNotes.isNotEmpty()) {
                Text(
                    text = "Related Notes",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(pendingShare.relatedNotes.take(5)) { note ->
                        RelatedNoteChip(note)
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // AI Instructions Section
            Text(
                text = "Instructions for AI (optional)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (aiInstructions.isNotEmpty()) LocalAccentColor.current else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(12.dp)
                    ),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .heightIn(min = 80.dp)
                ) {
                    BasicTextField(
                        value = aiInstructions,
                        onValueChange = { aiInstructions = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = MonoFont,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(LocalAccentColor.current)
                    )
                    if (aiInstructions.isEmpty()) {
                        Text(
                            text = "e.g., \"This is for my project X\" or \"Summarize key points\"",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFont),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // Privacy hint (only show when not in privacy mode)
            if (!isFullPrivacy) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Vibration,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Shake phone for Full Privacy Mode",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(ComponentSpacing.buttonCornerRadius)
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = {
                        val category = if (letAIDecide) null else selectedCategory
                        onSave(category, aiInstructions)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(ComponentSpacing.buttonCornerRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalAccentColor.current,
                        contentColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save")
                }
            }
        }
    }
}

/**
 * Preview card showing the shared content
 */
@Composable
private fun SharePreviewCard(pendingShare: PendingShareData) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline,
                RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Preview thumbnail or icon
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    // Image preview
                    pendingShare.fileUri != null && pendingShare.mimeType?.startsWith("image/") == true -> {
                        AsyncImage(
                            model = pendingShare.fileUri,
                            contentDescription = "Image preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    // Type icon for files
                    pendingShare.fileUri != null -> {
                        Surface(
                            color = getTypeColor(pendingShare.detectedType).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = getTypeIcon(pendingShare.detectedType),
                                contentDescription = null,
                                tint = getTypeColor(pendingShare.detectedType),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                            )
                        }
                    }
                    // Text content icon
                    else -> {
                        Surface(
                            color = LocalAccentColor.current.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.TextSnippet,
                                contentDescription = null,
                                tint = LocalAccentColor.current,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                            )
                        }
                    }
                }
            }

            // Content info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = pendingShare.fileName ?: getTypeName(pendingShare.detectedType),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (pendingShare.text != null) {
                    Text(
                        text = pendingShare.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Type badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = getTypeColor(pendingShare.detectedType).copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = pendingShare.detectedType.name.lowercase().replace("_", " "),
                            style = MaterialTheme.typography.labelSmall,
                            color = getTypeColor(pendingShare.detectedType),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // File size
                    if (pendingShare.fileSize != null && pendingShare.fileSize > 0) {
                        Text(
                            text = formatFileSize(pendingShare.fileSize),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Chip showing a related note
 */
@Composable
private fun RelatedNoteChip(note: Note) {
    Surface(
        modifier = Modifier.width(150.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            Text(
                text = note.title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = note.summary ?: note.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun getTypeIcon(type: NoteType): ImageVector {
    return when (type) {
        NoteType.IMAGE -> Icons.Default.Photo
        NoteType.VIDEO -> Icons.Default.Videocam
        NoteType.AUDIO -> Icons.Default.MusicNote
        NoteType.DOCUMENT -> Icons.AutoMirrored.Filled.Article
        NoteType.YOUTUBE -> Icons.Default.PlayCircle
        NoteType.WEBSITE -> Icons.Default.Link
        NoteType.CODE -> Icons.Default.Code
        NoteType.ARCHIVE -> Icons.Default.FolderZip
        else -> Icons.Default.AttachFile
    }
}

private fun getTypeColor(type: NoteType): androidx.compose.ui.graphics.Color {
    return when (type) {
        NoteType.IMAGE -> com.example.smarty.ui.theme.ImageTeal
        NoteType.VIDEO -> com.example.smarty.ui.theme.VideoRed
        NoteType.AUDIO -> com.example.smarty.ui.theme.AudioPink
        NoteType.DOCUMENT -> com.example.smarty.ui.theme.DocumentBlue
        NoteType.YOUTUBE -> com.example.smarty.ui.theme.YoutubeRed
        NoteType.CODE -> com.example.smarty.ui.theme.CodeCyan
        else -> com.example.smarty.ui.theme.FileGray
    }
}

private fun getTypeName(type: NoteType): String {
    return when (type) {
        NoteType.BRAIN_DUMP -> "Text Note"
        NoteType.YOUTUBE -> "YouTube Video"
        NoteType.WEBSITE -> "Web Link"
        NoteType.IMAGE -> "Image"
        NoteType.VIDEO -> "Video"
        NoteType.AUDIO -> "Audio File"
        NoteType.DOCUMENT -> "Document"
        else -> "File"
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
