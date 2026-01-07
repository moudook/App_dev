package com.example.smarty.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.example.smarty.data.model.Note
import com.example.smarty.ui.animation.CogniEasing
import com.example.smarty.ui.animation.StaggerCalculator
import com.example.smarty.ui.components.NoteCard
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.SafetyOrange
import kotlinx.coroutines.launch

/**
 * Archive screen showing all archived notes.
 * - Swipe right: Permanent delete (with confirmation)
 * - Swipe left: Unarchive (restore to main view)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    archivedNotes: List<Note>,
    onBackClick: () -> Unit,
    onDeleteNote: (String) -> Unit,
    onUnarchiveNote: (String) -> Unit,
    isEmbedded: Boolean = false,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Delete confirmation state
    var noteToDelete by remember { mutableStateOf<Note?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (isEmbedded) {
        // Embedded content (no Scaffold)
        Box(modifier = modifier.fillMaxSize()) {
            if (archivedNotes.isEmpty()) {
                com.example.smarty.ui.components.ArchiveEmptyState(
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(ComponentSpacing.listContentPadding),
                    verticalArrangement = Arrangement.spacedBy(ComponentSpacing.listItemGap)
                ) {
                    items(
                        items = archivedNotes,
                        key = { it.id }
                    ) { note ->
                        ArchiveNoteItem(
                            note = note,
                            onDelete = {
                                noteToDelete = note
                                showDeleteDialog = true
                            },
                            onUnarchive = { onUnarchiveNote(note.id) }
                        )
                    }
                }
            }
        }
    } else {
        // Intercept system back button
        androidx.activity.compose.BackHandler(onBack = onBackClick)

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Archive",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Text(
                                text = "${archivedNotes.size} item${if (archivedNotes.size != 1) "s" else ""}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
            if (archivedNotes.isEmpty()) {
                // Empty state
                com.example.smarty.ui.components.ArchiveEmptyState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(ComponentSpacing.listContentPadding),
                    verticalArrangement = Arrangement.spacedBy(ComponentSpacing.listItemGap)
                ) {
                    items(
                        items = archivedNotes,
                        key = { it.id }
                    ) { note ->
                        ArchiveNoteItem(
                            note = note,
                            onDelete = {
                                noteToDelete = note
                                showDeleteDialog = true
                            },
                            onUnarchive = { onUnarchiveNote(note.id) }
                        )
                    }
                }
            }
        }
    }


    // Delete confirmation dialog
    if (showDeleteDialog && noteToDelete != null) {
        com.example.smarty.ui.components.common.LoumDialog(
            title = "Delete Permanently?",
            text = "This note will be permanently deleted. This action cannot be undone.",
            onConfirm = {
                noteToDelete?.let { onDeleteNote(it.id) }
                showDeleteDialog = false
                noteToDelete = null
            },
            onDismiss = {
                showDeleteDialog = false
                noteToDelete = null
            },
            confirmText = "Delete",
            dismissText = "Cancel",
            isDestructive = true
        )
    }
}

/**
 * Note item for archive view with staggered animation
 */
@Composable
private fun ArchiveNoteItem(
    note: Note,
    onDelete: () -> Unit,
    onUnarchive: () -> Unit
) {
    // Track if item has appeared
    var appeared by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
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

    NoteCard(
        note = note,
        onClick = { /* No click action in archive */ },
        onDelete = onDelete,  // Permanent delete
        onOpenTodo = { /* No todo in archive */ },
        isArchiveView = true,  // Archive view: swipe right = delete, swipe left = unarchive
        onUnarchive = onUnarchive,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
    )
}
