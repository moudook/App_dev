package com.example.smarty.features.notes.ui.inputstream

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.smarty.R
import com.example.smarty.core.domain.model.Note
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.animation.SmartyEasing
import com.example.smarty.ui.components.ArchiveEmptyState
import com.example.smarty.ui.components.NoteCard
import com.example.smarty.ui.components.common.SmartyDialog
import com.example.smarty.ui.theme.ComponentSpacing

/**
 * Inline archive content that displays in the main content area.
 *
 * This replaces the full-page overlay approach - archive is shown in the same
 * layer as note cards, behind the gradient input field.
 *
 * Fully functional: Delete notes permanently, unarchive notes (restore to main).
 * Uses swipe gestures on NoteCard: swipe right = delete, swipe left = unarchive.
 */
@Composable
fun ArchiveContent(
    archivedNotes: List<Note>,
    onDeleteNote: (String) -> Unit,
    onUnarchiveNote: (String) -> Unit,
    contentPadding: PaddingValues,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val accentColor = LocalAccentColor.current

    // Delete confirmation state
    var noteToDelete by remember { mutableStateOf<Note?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(ComponentSpacing.listItemGap),
    ) {
        if (isLoading) {
            item {
                com.example.smarty.ui.components.NotesLoadingState(
                    count = 4,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        } else if (archivedNotes.isEmpty()) {
            // Empty state
            item {
                ArchiveEmptyState(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 60.dp),
                )
            }
        } else {
            // Archive items with full functionality
            items(
                items = archivedNotes,
                key = { it.id },
            ) { note ->
                InlineArchiveNoteItem(
                    note = note,
                    onDelete = {
                        noteToDelete = note
                        showDeleteDialog = true
                    },
                    onUnarchive = { onUnarchiveNote(note.id) },
                )
            }
        }

        // Bottom spacer for input field
        item {
            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog && noteToDelete != null) {
        SmartyDialog(
            title = stringResource(R.string.delete_permanently),
            text = stringResource(R.string.archive_delete_warning),
            onConfirm = {
                noteToDelete?.let { onDeleteNote(it.id) }
                showDeleteDialog = false
                noteToDelete = null
            },
            onDismiss = {
                showDeleteDialog = false
                noteToDelete = null
            },
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            isDestructive = true,
        )
    }
}

/**
 * Note item for inline archive view with animation
 */
@Composable
private fun InlineArchiveNoteItem(
    note: Note,
    onDelete: () -> Unit,
    onUnarchive: () -> Unit,
) {
    var appeared by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        appeared = true
    }

    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.85f,
        animationSpec =
            spring(
                dampingRatio = 0.7f,
                stiffness = 300f,
            ),
        label = "itemScale",
    )

    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(200, easing = SmartyEasing.appleEaseOut),
        label = "itemAlpha",
    )

    NoteCard(
        note = note,
        onClick = { /* No click action in archive - read-only view */ },
        onDelete = onDelete,
        onOpenTodo = { /* No todo in archive */ },
        isArchiveView = true, // Swipe right = delete, swipe left = unarchive
        onUnarchive = onUnarchive,
        modifier =
            Modifier
                .padding(horizontal = 16.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                },
    )
}
