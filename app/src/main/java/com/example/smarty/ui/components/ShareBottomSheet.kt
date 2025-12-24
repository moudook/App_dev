package com.example.smarty.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
 * Single file info for pending share
 */
data class PendingFileInfo(
    val fileUri: String,
    val fileName: String?,
    val mimeType: String?,
    val fileSize: Long?
)

/**
 * Data class for pending share content - supports multiple files
 */
data class PendingShareData(
    val text: String? = null,
    val fileUri: String? = null,  // Legacy single file
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSize: Long? = null,
    val detectedType: NoteType = NoteType.FILE,
    val suggestedCategory: String? = null,
    val relatedNotes: List<Note> = emptyList(),
    val files: List<PendingFileInfo> = emptyList()  // Multiple files
) {
    /** Get all files (combines legacy single + multiple) */
    fun getAllFiles(): List<PendingFileInfo> {
        if (files.isNotEmpty()) return files
        if (fileUri != null) {
            return listOf(PendingFileInfo(fileUri, fileName, mimeType, fileSize))
        }
        return emptyList()
    }

    /** Get the total file count */
    fun getFileCount(): Int = getAllFiles().size
}

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
    // Add pendingShare as dependency to reset state when share data changes
    val shareKey = pendingShare.text ?: pendingShare.fileUri ?: pendingShare.fileName
    var selectedCategory by remember(shareKey) { mutableStateOf<String?>(pendingShare.suggestedCategory) }
    var letAIDecide by remember(shareKey, isFullPrivacy) { mutableStateOf(!isFullPrivacy) }
    var aiInstructions by remember(shareKey) { mutableStateOf("") }

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
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
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
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .imePadding() // Handle keyboard
        ) {
            if (isFullPrivacy) {
                PrivacyModeBanner(
                    isActive = isFullPrivacy,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )
            }

            // Compact Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Save to Cogni",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    ),
                    color = LocalAccentColor.current
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Compact content preview
            SharePreviewCard(pendingShare)

            Spacer(modifier = Modifier.height(16.dp))

            // Efficient Category Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Category",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Inline AI Toggle
                if (!isFullPrivacy) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { letAIDecide = !letAIDecide }
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = if (letAIDecide) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Auto-sort",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (letAIDecide) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f)
                        )
                        Switch(
                            checked = letAIDecide,
                            onCheckedChange = { letAIDecide = it },
                            modifier = Modifier.scale(0.7f).height(32.dp),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = LocalAccentColor.current,
                                checkedTrackColor = LocalAccentColor.current.copy(alpha = 0.2f),
                                checkedBorderColor = Color.Transparent,
                                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        )
                    }
                }
            }

            // Chips show when AI is OFF or forced ON by privacy
            AnimatedVisibility(
                visible = !letAIDecide || isFullPrivacy,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                LazyRow(
                    modifier = Modifier.padding(top = 8.dp),
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
                                selectedContainerColor = LocalAccentColor.current.copy(alpha = 0.15f),
                                selectedLabelColor = LocalAccentColor.current
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selectedCategory == category.name,
                                borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Compact AI Instructions
            Text(
                text = "Add Context (Optional)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow, // Distinct background
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (aiInstructions.isNotEmpty()) LocalAccentColor.current.copy(0.5f) else Color.Transparent
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .heightIn(min = 60.dp) // Reduced height
                ) {
                    if (aiInstructions.isEmpty()) {
                        Text(
                            text = "e.g. \"Summarize key points\"...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    BasicTextField(
                        value = aiInstructions,
                        onValueChange = { aiInstructions = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(LocalAccentColor.current)
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
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(0.3f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = {
                        val category = if (letAIDecide) null else selectedCategory
                        onSave(category, aiInstructions)
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalAccentColor.current,
                        contentColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Note")
                }
            }
        }
    }
}

@Composable
private fun SharePreviewCard(pendingShare: PendingShareData) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh) // Higher contrast container
            .padding(vertical = 8.dp, horizontal = 12.dp), // Tighter vertical padding
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Compact Thumbnail
        Box(
            modifier = Modifier
                .size(40.dp) // Smaller thumbnail (was 48dp)
                .clip(RoundedCornerShape(10.dp))
                .background(getTypeColor(pendingShare.detectedType).copy(alpha = 0.2f)), // Stronger background
            contentAlignment = Alignment.Center
        ) {
            if (pendingShare.fileUri != null && pendingShare.mimeType?.startsWith("image/") == true) {
                AsyncImage(
                    model = pendingShare.fileUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = getTypeIcon(pendingShare.detectedType),
                    contentDescription = null,
                    tint = getTypeColor(pendingShare.detectedType),
                    modifier = Modifier.size(20.dp) // Smaller icon (was 24dp)
                )
            }
        }

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pendingShare.fileName ?: getTypeName(pendingShare.detectedType),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = pendingShare.detectedType.name.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (pendingShare.fileSize != null && pendingShare.fileSize > 0) {
                    Text(
                        text = "• ${formatFileSize(pendingShare.fileSize)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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

private fun formatFileSize(bytes: Long): String = com.example.smarty.util.ContentTypeDetector.formatFileSize(bytes)
