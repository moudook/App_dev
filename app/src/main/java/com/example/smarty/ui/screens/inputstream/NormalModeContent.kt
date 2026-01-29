package com.example.smarty.ui.screens.inputstream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.data.model.Note
import com.example.smarty.ui.components.NoteCard
import com.example.smarty.ui.components.NotesEmptyState
import com.example.smarty.ui.components.NotesLoadingState
import com.example.smarty.ui.components.SearchEmptyState
import com.example.smarty.ui.theme.ComponentSpacing
import kotlinx.coroutines.launch

/**
 * Normal mode content displaying notes in a staggered grid.
 *
 * Extracted from InputStreamScreen to improve code organization.
 * Handles notes display, empty states, loading skeleton, and pull-to-refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NormalModeContent(
    displayedNotes: List<Note>,
    isNotesLoading: Boolean,
    isSearchMode: Boolean,
    searchQuery: String,
    gridState: LazyStaggeredGridState,
    isRefreshing: Boolean,
    onRefreshNotes: () -> Unit,
    bottomContentPadding: Dp,
    topContentPadding: Dp = 0.dp,
    // Selection mode
    isSelectionMode: Boolean,
    selectedNoteIds: Set<String>,
    onToggleSelection: (String) -> Unit,
    onEnterSelectionMode: (String) -> Unit,
    // Note actions
    onNoteClick: (Note) -> Unit,
    onArchiveNote: (String) -> Unit,
    onDeleteNoteRequest: (String) -> Unit,
    onOpenTodo: (String) -> Unit,
    onPlayYouTube: (String) -> Unit,
    // Snackbar for undo
    snackbarHostState: SnackbarHostState,
    onSetLastArchivedNoteId: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    if (isNotesLoading) {
        // Show skeleton loaders while notes are loading
        NotesLoadingState(
            count = 5,
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = ComponentSpacing.listContentPadding)
        )
    } else if (displayedNotes.isEmpty()) {
        if (isSearchMode) {
            // Animated search empty state with query display
            com.example.smarty.ui.components.SearchEmptyState(
                searchQuery = searchQuery,
                modifier = modifier.fillMaxSize()
            )
        } else {
            NotesEmptyState(modifier = modifier.fillMaxSize())
        }
    } else {
        // Pull-to-refresh wrapper
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefreshNotes,
            modifier = modifier.fillMaxSize()
        ) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = ComponentSpacing.listContentPadding + topContentPadding,
                    bottom = 140.dp + bottomContentPadding,
                    start = 8.dp,
                    end = 8.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalItemSpacing = 8.dp
            ) {
                items(
                    items = displayedNotes,
                    key = { note -> note.id },
                    contentType = { note -> note.processingStatus }
                ) { note ->
                    val index = displayedNotes.indexOf(note)

                    // Stabilize lambdas to prevent recomposition
                    val stableOnClick = remember(note.id, isSelectionMode) {
                        {
                            if (isSelectionMode) onToggleSelection(note.id)
                            else onNoteClick(note)
                        }
                    }
                    val stableOnDelete = remember(note.id) {
                        { onDeleteNoteRequest(note.id) }
                    }
                    val stableOnOpenTodo = remember(note.id) {
                        { onOpenTodo(note.id) }
                    }
                    val stableOnArchive: () -> Unit = remember(note.id) {
                        {
                            onSetLastArchivedNoteId(note.id)
                            onArchiveNote(note.id)
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.note_archived),
                                    duration = SnackbarDuration.Short
                                )
                                if (result == SnackbarResult.Dismissed) {
                                    onSetLastArchivedNoteId(null)
                                }
                            }
                        }
                    }
                    val stableOnLongPress = remember(note.id) {
                        { onEnterSelectionMode(note.id) }
                    }
                    val isNoteSelected = note.id in selectedNoteIds

                    NoteCard(
                        note = note,
                        onClick = stableOnClick,
                        onDelete = stableOnDelete,
                        onOpenTodo = stableOnOpenTodo,
                        modifier = Modifier,
                        index = index,
                        onArchive = stableOnArchive,
                        isSelected = isNoteSelected,
                        isSelectionMode = isSelectionMode,
                        onLongPress = stableOnLongPress,
                        onPlayYouTube = onPlayYouTube,
                        searchQuery = if (isSearchMode) searchQuery else null
                    )
                }
            }
        }
    }
}
