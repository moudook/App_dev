package com.example.smarty.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data management section for settings.
 *
 * Provides controls for:
 * - Backup and restore functionality
 * - Cache management
 * - Data export
 * - Google Calendar sync
 * - Archive viewing
 *
 * @param cacheSizeBytes Current cache size in bytes
 * @param onClearCache Callback to clear the cache
 * @param isClearingCache Whether cache clearing is in progress
 * @param lastBackupTime Timestamp of last backup (0 if never backed up)
 * @param onBackupClick Callback when backup option is clicked
 * @param lastCalendarSyncTime Timestamp of last calendar sync
 * @param onCalendarSync Callback to sync calendar
 * @param onArchiveClick Callback when archive option is clicked
 * @param onExportClick Optional callback for data export
 * @param modifier Modifier for the section container
 */
@Composable
fun DataManagementSection(
    cacheSizeBytes: Long,
    onClearCache: () -> Unit,
    isClearingCache: Boolean = false,
    lastBackupTime: Long = 0L,
    onBackupClick: () -> Unit,
    lastCalendarSyncTime: Long = 0L,
    onCalendarSync: () -> Unit,
    onArchiveClick: () -> Unit,
    onExportClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val accentColor = LocalAccentColor.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Backup & Sync Row
        DataManagementRow(
            title = "backup_sync",
            subtitle = formatBackupTime(lastBackupTime),
            icon = Icons.Default.CloudSync, // Standard backup icon
            onClick = onBackupClick
        )

        // Google Calendar Sync Row
        DataManagementRow(
            title = "google_calendar_sync",
            subtitle = formatCalendarSyncTime(lastCalendarSyncTime),
            icon = Icons.Default.Sync, // Standard sync icon
            onClick = onCalendarSync
        )

        // Archive Row
        DataManagementRow(
            title = "archive",
            subtitle = "view_archived_notes",
            icon = Icons.Default.Archive, // Standard archive icon
            onClick = onArchiveClick
        )

        // Clear Cache Row
        ClearCacheRow(
            cacheSizeBytes = cacheSizeBytes,
            onClearCache = onClearCache,
            isClearing = isClearingCache
        )

        // Export Row (optional)
        if (onExportClick != null) {
            DataManagementRow(
                title = "export_data",
                subtitle = "export_all_notes_and_settings",
                icon = Icons.Default.Output, // Creative: Output
                onClick = onExportClick
            )
        }
    }
}

/**
 * Individual row for data management options.
 */
@Composable
private fun DataManagementRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isDestructive: Boolean = false
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        isDestructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        onClick = onClick,
        enabled = enabled
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (enabled) 0.7f else 0.4f
                        )
                    )
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.TrendingFlat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Cache clearing row with loading state.
 */
@Composable
private fun ClearCacheRow(
    cacheSizeBytes: Long,
    onClearCache: () -> Unit,
    isClearing: Boolean
) {
    val enabled = !isClearing && cacheSizeBytes > 0

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        onClick = onClearCache,
        enabled = enabled
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline, // Standard delete/clear icon
                    contentDescription = null,
                    tint = if (enabled) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = "clear_cache",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    Text(
                        text = formatCacheSize(cacheSizeBytes).lowercase(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (enabled) 0.7f else 0.4f
                        )
                    )
                }
            }

            if (isClearing) {
                com.example.smarty.ui.components.CalmThinkingDots(
                    dotSize = 3.dp
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.TrendingFlat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Format backup time for display.
 */
private fun formatBackupTime(timestamp: Long): String {
    return if (timestamp > 0) {
        val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
        "last:_\\${sdf.format(Date(timestamp))}"
    } else {
        "not_backed_up"
    }
}

/**
 * Format calendar sync time for display.
 */
private fun formatCalendarSyncTime(timestamp: Long): String {
    return if (timestamp > 0) {
        val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        "last_sync:_\\${sdf.format(Date(timestamp))}"
    } else {
        "not_synced"
    }
}

/**
 * Format cache size for display.
 */
fun formatCacheSize(bytes: Long): String {
    return when {
        bytes <= 0 -> "no_cache"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

/**
 * Standalone composable for cache clearing confirmation dialog.
 */
@Composable
fun ClearCacheConfirmDialog(
    cacheSizeBytes: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
        com.example.smarty.ui.components.common.JarvisDialog(
        title = "clear_cache",
        text = "this_will_clear_${formatCacheSize(cacheSizeBytes).lowercase()}_of_cached_data_downloaded_content_may_need_to_be_fetched_again",
        onConfirm = {
            onConfirm()
            onDismiss()
        },
        onDismiss = onDismiss,
        confirmText = "clear",
        dismissText = "cancel",
        isDestructive = true
    )
}

/**
 * Backup options sheet content for showing backup/restore options.
 */
@Composable
fun BackupOptionsContent(
    lastBackupTime: Long,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onClose: () -> Unit,
    isBackingUp: Boolean = false,
    isRestoring: Boolean = false
) {
    val accentColor = LocalAccentColor.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        // Header
        Text(
            text = "backup_sync",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "keep_your_notes_safe_with_automatic_backups",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Last backup info
        if (lastBackupTime > 0) {
            val sdf = SimpleDateFormat("MMMM dd, yyyy 'at' HH:mm", Locale.getDefault())
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = accentColor.copy(alpha = 0.1f)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "last_backup",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = sdf.format(Date(lastBackupTime)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Backup button
        Button(
            onClick = onBackup,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !isBackingUp && !isRestoring,
            colors = ButtonDefaults.buttonColors(
                containerColor = accentColor
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (isBackingUp) {
                com.example.smarty.ui.components.CalmThinkingDots(
                    color = MaterialTheme.colorScheme.onPrimary,
                    dotSize = 4.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("backing_up")
            } else {
                Icon(
                    imageVector = Icons.Default.CloudUpload,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "backup_now",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Restore button
        OutlinedButton(
            onClick = onRestore,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !isBackingUp && !isRestoring,
            shape = RoundedCornerShape(16.dp)
        ) {
            if (isRestoring) {
                com.example.smarty.ui.components.CalmThinkingDots(
                    dotSize = 4.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("restoring")
            } else {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "restore_from_backup",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Info text
        Text(
            text = "backups_include_notes_categories_settings_chat_history_ai_memories_calendar_events_and_attachments",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}
