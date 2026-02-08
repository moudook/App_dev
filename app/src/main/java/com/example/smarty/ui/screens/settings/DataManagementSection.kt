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
import com.example.smarty.ui.components.OrganicThinkingIndicator
import com.example.smarty.ui.LocalAccentColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.example.smarty.R
import com.example.smarty.ui.components.common.SmartyDialog

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
            title = stringResource(R.string.backup_sync),
            subtitle = formatBackupTime(lastBackupTime),
            icon = Icons.Default.CloudSync, // Standard backup icon
            onClick = onBackupClick
        )

        // Google Calendar Sync Row
        DataManagementRow(
            title = stringResource(R.string.google_calendar_sync),
            subtitle = formatCalendarSyncTime(lastCalendarSyncTime),
            icon = Icons.Default.Sync, // Standard sync icon
            onClick = onCalendarSync
        )

        // Archive Row
        DataManagementRow(
            title = stringResource(R.string.archive),
            subtitle = stringResource(R.string.view_archived_notes),
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
                title = stringResource(R.string.export_data),
                subtitle = stringResource(R.string.export_all_notes_and_settings),
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
                // Leading Icon Container
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isDestructive) Color(0xFFFF3B30).copy(alpha = 0.1f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isDestructive) Color(0xFFFF3B30) else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
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
                // Leading Icon Container
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFFF3B30).copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = Color(0xFFFF3B30), // Semantic Red
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.clear_cache),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = if (enabled) Color(0xFFFF3B30) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
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
                OrganicThinkingIndicator(
                    size = 20.dp,
                    baseColor = Color(0xFFFF3B30)
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
@Composable
private fun formatBackupTime(timestamp: Long): String {
    return if (timestamp > 0) {
        val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
        stringResource(R.string.last_synced, sdf.format(Date(timestamp)))
    } else {
        stringResource(R.string.not_backed_up)
    }
}

/**
 * Format calendar sync time for display.
 */
@Composable
private fun formatCalendarSyncTime(timestamp: Long): String {
    return if (timestamp > 0) {
        val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        stringResource(R.string.last_synced, sdf.format(Date(timestamp)))
    } else {
        stringResource(R.string.not_synced)
    }
}

/**
 * Format cache size for display.
 */
@Composable
fun formatCacheSize(bytes: Long): String {
    return when {
        bytes <= 0 -> stringResource(R.string.no_cache)
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
        SmartyDialog(
        title = stringResource(R.string.clear_cache),
        text = stringResource(R.string.clear_cache_info, formatCacheSize(cacheSizeBytes).lowercase()),
        onConfirm = {
            onConfirm()
            onDismiss()
        },
        onDismiss = onDismiss,
        confirmText = stringResource(R.string.clear),
        dismissText = stringResource(R.string.cancel),
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
            text = stringResource(R.string.backup_sync),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.backup_sync_info),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Last backup info
        if (lastBackupTime > 0) {
            val dateStr = SimpleDateFormat(stringResource(R.string.date_format_long), Locale.getDefault()).format(Date(lastBackupTime))
            val timeStr = SimpleDateFormat(stringResource(R.string.time_format_24h), Locale.getDefault()).format(Date(lastBackupTime))
            val formattedDateTime = stringResource(R.string.date_at_time_format, dateStr, timeStr)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF34C759).copy(alpha = 0.08f),
                border = BorderStroke(0.5.dp, Color(0xFF34C759).copy(alpha = 0.15f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF34C759), // Semantic Green
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.last_backup),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formattedDateTime,
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
                containerColor = Color(0xFF007AFF) // Semantic Blue
            ),
            shape = RoundedCornerShape(26.dp)
        ) {
            if (isBackingUp) {
                OrganicThinkingIndicator(
                    size = 20.dp,
                    baseColor = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.backing_up))
            } else {
                Icon(
                    imageVector = Icons.Default.CloudUpload,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.backup_now),
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
            shape = RoundedCornerShape(26.dp)
        ) {
            if (isRestoring) {
                OrganicThinkingIndicator(
                    size = 20.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.restoring))
            } else {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.restore_from_backup),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Info text
        Text(
            text = stringResource(R.string.backups_include_info),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}
