package com.example.smarty.ui.screens.inputstream

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.smarty.ui.LocalAccentColor

/**
 * Selection mode toolbar for bulk note operations.
 *
 * Extracted from InputStreamScreen to improve code organization.
 * Provides actions for selecting, archiving, deleting, pinning, and sharing notes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionModeToolbar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onPinSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onArchiveSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasSelection = selectedCount > 0

    TopAppBar(
        modifier = modifier,
        title = { /* Empty - no text displayed */ },
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel selection",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        actions = {
            // Select All button
            IconButton(onClick = onSelectAll) {
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = "Select all",
                    tint = LocalAccentColor.current
                )
            }

            // Pin button
            IconButton(
                onClick = onPinSelected,
                enabled = hasSelection
            ) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = "Pin selected",
                    tint = if (hasSelection)
                        LocalAccentColor.current
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Share button
            IconButton(
                onClick = onShareSelected,
                enabled = hasSelection
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share selected",
                    tint = if (hasSelection)
                        LocalAccentColor.current
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Archive button
            IconButton(
                onClick = onArchiveSelected,
                enabled = hasSelection
            ) {
                Icon(
                    imageVector = Icons.Default.Archive,
                    contentDescription = "Archive selected",
                    tint = if (hasSelection)
                        LocalAccentColor.current
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Delete button
            IconButton(
                onClick = onDeleteSelected,
                enabled = hasSelection
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete selected",
                    tint = if (hasSelection)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}
