package com.example.smarty.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.ui.components.CategoryChip
import com.example.smarty.ui.components.getNoteTypeColor
import com.example.smarty.ui.components.getNoteTypeIcon
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.AudioPink
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }

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
                .padding(24.dp),
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
                                val track = AudioTrack(
                                    uri = note.fileUri!!,
                                    title = note.title,
                                    fileName = note.fileName,
                                    sourceNoteId = note.id
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
