package com.example.smarty.ui.components.sheets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smarty.data.model.Note
import com.example.smarty.ui.screens.ArchiveScreen

/**
 * Archive as a full-page overlay.
 * Completely covers the main screen for focused archive access.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveSheet(
    archivedNotes: List<Note>,
    sheetState: SheetState, // Keep for API compatibility, but not used
    onDismiss: () -> Unit,
    onDeleteNote: (String) -> Unit,
    onUnarchiveNote: (String) -> Unit
) {
    // Handle back button
    BackHandler(enabled = true) {
        onDismiss()
    }

    // Full-page overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        ArchiveScreen(
            archivedNotes = archivedNotes,
            onBackClick = onDismiss,
            onDeleteNote = onDeleteNote,
            onUnarchiveNote = onUnarchiveNote,
            isEmbedded = false, // Use full screen mode
            modifier = Modifier.fillMaxSize()
        )
    }
}
