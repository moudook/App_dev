package com.example.smarty.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smarty.data.backup.AutoBackupConfig
import com.example.smarty.data.backup.BackupMetadata
import com.example.smarty.data.backup.BackupOperationState
import com.example.smarty.data.backup.LocalBackupMetadata
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.SafetyOrange
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    isSignedIn: Boolean,
    signedInEmail: String?,
    signedInDisplayName: String?,
    signedInPhotoUrl: String?,
    backupState: BackupOperationState,
    restoreState: BackupOperationState,
    availableBackups: List<BackupMetadata>,
    lastBackupTime: Long,
    autoBackupEnabled: Boolean,
    autoBackupIntervalDays: Int,
    // Local backup parameters
    localBackupState: BackupOperationState,
    localBackups: List<LocalBackupMetadata>,
    onBackClick: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: (BackupMetadata) -> Unit,
    onDeleteBackup: (BackupMetadata) -> Unit,
    onSetAutoBackupEnabled: (Boolean) -> Unit,
    onSetAutoBackupInterval: (Int) -> Unit,
    onResetBackupState: () -> Unit,
    onResetRestoreState: () -> Unit,
    // Local backup callbacks
    onCreateLocalBackup: () -> Unit,
    onDeleteLocalBackup: (LocalBackupMetadata) -> Unit,
    onShareLocalBackup: (LocalBackupMetadata) -> Intent,
    onResetLocalBackupState: () -> Unit,
    isEmbedded: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showRestoreConfirmDialog by remember { mutableStateOf<BackupMetadata?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<BackupMetadata?>(null) }
    var showDeleteLocalBackupDialog by remember { mutableStateOf<LocalBackupMetadata?>(null) }

    // Reset states when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            onResetBackupState()
            onResetRestoreState()
            onResetLocalBackupState()
        }
    }

    if (isEmbedded) {
        Box(modifier = modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(ComponentSpacing.screenPadding),
                verticalArrangement = Arrangement.spacedBy(ComponentSpacing.listItemGap)
            ) {
                // Google Account Section
                item {
                    SectionHeader(title = "Google Account")
                }

                item {
                    GoogleAccountCard(
                        isSignedIn = isSignedIn,
                        email = signedInEmail,
                        displayName = signedInDisplayName,
                        photoUrl = signedInPhotoUrl,
                        onSignIn = onSignIn,
                        onSignOut = onSignOut
                    )
                }

                // Backup Section (only show when signed in)
                if (isSignedIn) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionHeader(title = "Backup")
                    }

                    item {
                        BackupStatusCard(
                            lastBackupTime = lastBackupTime,
                            backupState = backupState,
                            onCreateBackup = onCreateBackup
                        )
                    }

                    // Auto-backup settings
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionHeader(title = "Auto-Backup")
                    }

                    item {
                        AutoBackupCard(
                            enabled = autoBackupEnabled,
                            intervalDays = autoBackupIntervalDays,
                            onEnabledChange = onSetAutoBackupEnabled,
                            onIntervalClick = { showIntervalDialog = true }
                        )
                    }

                    // Available backups for restore
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionHeader(title = "Available Backups")
                    }

                    if (availableBackups.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Text(
                                    text = "No backups found in Google Drive",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    } else {
                        items(availableBackups) { backup ->
                            BackupListItem(
                                backup = backup,
                                restoreState = restoreState,
                                onRestore = { showRestoreConfirmDialog = backup },
                                onDelete = { showDeleteConfirmDialog = backup }
                            )
                        }
                    }

                    // Info footer for cloud backup
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Cloud backups include all notes, categories, and attachments. API keys are not backed up for security.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }

                // Local Backup Section (always visible, independent of sign-in)
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SectionHeader(title = "Local Backup")
                }

                item {
                    LocalBackupCard(
                        localBackupState = localBackupState,
                        onCreateLocalBackup = onCreateLocalBackup
                    )
                }

                // Local backups list
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    SectionHeader(title = "Saved Backups")
                }

                if (localBackups.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Text(
                                text = "No local backups yet. Create one to share or save.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else {
                    items(localBackups) { backup ->
                        LocalBackupListItem(
                            backup = backup,
                            onShare = {
                                val intent = onShareLocalBackup(backup)
                                context.startActivity(Intent.createChooser(intent, "Share Backup"))
                            },
                            onDelete = { showDeleteLocalBackupDialog = backup }
                        )
                    }
                }

                // Info footer for local backup
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Local backups are stored on your device and can be shared via any app. Use these to transfer data between devices or create offline archives.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Backup & Sync") },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
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
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(ComponentSpacing.screenPadding),
                verticalArrangement = Arrangement.spacedBy(ComponentSpacing.listItemGap)
            ) {
                // Google Account Section
                item {
                    SectionHeader(title = "Google Account")
                }

                item {
                    GoogleAccountCard(
                        isSignedIn = isSignedIn,
                        email = signedInEmail,
                        displayName = signedInDisplayName,
                        photoUrl = signedInPhotoUrl,
                        onSignIn = onSignIn,
                        onSignOut = onSignOut
                    )
                }

                // Backup Section (only show when signed in)
                if (isSignedIn) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionHeader(title = "Backup")
                    }

                    item {
                        BackupStatusCard(
                            lastBackupTime = lastBackupTime,
                            backupState = backupState,
                            onCreateBackup = onCreateBackup
                        )
                    }

                    // Auto-backup settings
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionHeader(title = "Auto-Backup")
                    }

                    item {
                        AutoBackupCard(
                            enabled = autoBackupEnabled,
                            intervalDays = autoBackupIntervalDays,
                            onEnabledChange = onSetAutoBackupEnabled,
                            onIntervalClick = { showIntervalDialog = true }
                        )
                    }

                    // Available backups for restore
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionHeader(title = "Available Backups")
                    }

                    if (availableBackups.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Text(
                                    text = "No backups found in Google Drive",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    } else {
                        items(availableBackups) { backup ->
                            BackupListItem(
                                backup = backup,
                                restoreState = restoreState,
                                onRestore = { showRestoreConfirmDialog = backup },
                                onDelete = { showDeleteConfirmDialog = backup }
                            )
                        }
                    }

                    // Info footer for cloud backup
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Cloud backups include all notes, categories, and attachments. API keys are not backed up for security.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }

                // Local Backup Section (always visible, independent of sign-in)
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SectionHeader(title = "Local Backup")
                }

                item {
                    LocalBackupCard(
                        localBackupState = localBackupState,
                        onCreateLocalBackup = onCreateLocalBackup
                    )
                }

                // Local backups list
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    SectionHeader(title = "Saved Backups")
                }

                if (localBackups.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Text(
                                text = "No local backups yet. Create one to share or save.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else {
                    items(localBackups) { backup ->
                        LocalBackupListItem(
                            backup = backup,
                            onShare = {
                                val intent = onShareLocalBackup(backup)
                                context.startActivity(Intent.createChooser(intent, "Share Backup"))
                            },
                            onDelete = { showDeleteLocalBackupDialog = backup }
                        )
                    }
                }

                // Info footer for local backup
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Local backups are stored on your device and can be shared via any app. Use these to transfer data between devices or create offline archives.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }

    // Interval picker dialog
    if (showIntervalDialog) {
        IntervalPickerDialog(
            currentInterval = autoBackupIntervalDays,
            onDismiss = { showIntervalDialog = false },
            onSelectInterval = { days ->
                onSetAutoBackupInterval(days)
                showIntervalDialog = false
            }
        )
    }

    // Restore confirmation dialog
    showRestoreConfirmDialog?.let { backup ->
        AlertDialog(
            onDismissRequest = { showRestoreConfirmDialog = null },
            title = { Text("Restore Backup?") },
            text = {
                Text(
                    "This will replace all current data with the backup from ${backup.displayDate}.\n\n" +
                    "Notes: ${backup.noteCount}\n" +
                    "Categories: ${backup.categoryCount}\n\n" +
                    "This action cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRestoreBackup(backup)
                        showRestoreConfirmDialog = null
                    }
                ) {
                    Text("Restore", color = LocalAccentColor.current)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete cloud backup confirmation dialog
    showDeleteConfirmDialog?.let { backup ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text("Delete Backup?") },
            text = {
                Text(
                    "Are you sure you want to delete this backup from ${backup.displayDate}?\n\n" +
                    "This action cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteBackup(backup)
                        showDeleteConfirmDialog = null
                    }
                ) {
                    Text("Delete", color = SafetyOrange)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete local backup confirmation dialog
    showDeleteLocalBackupDialog?.let { backup ->
        AlertDialog(
            onDismissRequest = { showDeleteLocalBackupDialog = null },
            title = { Text("Delete Local Backup?") },
            text = {
                Text(
                    "Are you sure you want to delete this backup from ${backup.displayDate}?\n\n" +
                    "Size: ${backup.displaySize}\n" +
                    "Notes: ${backup.noteCount}\n" +
                    "Categories: ${backup.categoryCount}\n\n" +
                    "This action cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteLocalBackup(backup)
                        showDeleteLocalBackupDialog = null
                    }
                ) {
                    Text("Delete", color = SafetyOrange)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteLocalBackupDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun GoogleAccountCard(
    isSignedIn: Boolean,
    email: String?,
    displayName: String?,
    photoUrl: String?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSignedIn) {
                // Profile icon (no remote image loading for security)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(LocalAccentColor.current.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = LocalAccentColor.current
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName ?: "Google Account",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (email != null) {
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                TextButton(onClick = onSignOut) {
                    Text("Sign Out", color = SafetyOrange)
                }
            } else {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Not signed in",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Sign in to enable backup",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                Button(
                    onClick = onSignIn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalAccentColor.current
                    )
                ) {
                    Text("Sign In")
                }
            }
        }
    }
}

@Composable
private fun BackupStatusCard(
    lastBackupTime: Long,
    backupState: BackupOperationState,
    onCreateBackup: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Backup,
                    contentDescription = null,
                    tint = LocalAccentColor.current,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Last Backup",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (lastBackupTime > 0) {
                            val sdf = SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", Locale.getDefault())
                            sdf.format(Date(lastBackupTime))
                        } else {
                            "Never backed up"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Backup progress or button
            when (backupState) {
                is BackupOperationState.Idle -> {
                    Button(
                        onClick = onCreateBackup,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LocalAccentColor.current
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Backup Now")
                    }
                }

                is BackupOperationState.InProgress -> {
                    Column {
                        LinearProgressIndicator(
                            progress = { backupState.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = LocalAccentColor.current
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = backupState.stage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is BackupOperationState.Success -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = LocalAccentColor.current
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = backupState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalAccentColor.current
                        )
                    }
                }

                is BackupOperationState.Error -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = SafetyOrange
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = backupState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SafetyOrange
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoBackupCard(
    enabled: Boolean,
    intervalDays: Int,
    onEnabledChange: (Boolean) -> Unit,
    onIntervalClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-Backup",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Automatically backup when due",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.background,
                        checkedTrackColor = LocalAccentColor.current
                    )
                )
            }

            AnimatedVisibility(visible = enabled) {
                Column {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onIntervalClick() }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Backup Interval",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Every $intervalDays days",
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalAccentColor.current
                            )
                        }

                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupListItem(
    backup: BackupMetadata,
    restoreState: BackupOperationState,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val isRestoring = restoreState is BackupOperationState.InProgress

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = backup.displayDate,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${backup.noteCount} notes, ${backup.categoryCount} categories • ${backup.displaySize}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = backup.deviceName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Restore progress or buttons
            if (isRestoring) {
                val state = restoreState as BackupOperationState.InProgress
                Column {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = LocalAccentColor.current
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.stage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = SafetyOrange)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onRestore,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LocalAccentColor.current
                        )
                    ) {
                        Text("Restore")
                    }
                }
            }
        }
    }
}

@Composable
private fun IntervalPickerDialog(
    currentInterval: Int,
    onDismiss: () -> Unit,
    onSelectInterval: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup Interval") },
        text = {
            Column {
                AutoBackupConfig.INTERVAL_OPTIONS.forEach { days ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectInterval(days) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentInterval == days,
                            onClick = { onSelectInterval(days) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = LocalAccentColor.current
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (days) {
                                30 -> "Every 30 days (monthly)"
                                60 -> "Every 60 days"
                                100 -> "Every 100 days (recommended)"
                                180 -> "Every 180 days (6 months)"
                                365 -> "Every 365 days (yearly)"
                                else -> "Every $days days"
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun LocalBackupCard(
    localBackupState: BackupOperationState,
    onCreateLocalBackup: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.FolderZip,
                    contentDescription = null,
                    tint = LocalAccentColor.current,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Create Local Backup",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Save a ZIP file to share or store",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Backup progress or button
            when (localBackupState) {
                is BackupOperationState.Idle -> {
                    Button(
                        onClick = onCreateLocalBackup,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LocalAccentColor.current
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Archive,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create Backup")
                    }
                }

                is BackupOperationState.InProgress -> {
                    Column {
                        LinearProgressIndicator(
                            progress = { localBackupState.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = LocalAccentColor.current
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = localBackupState.stage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is BackupOperationState.Success -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = LocalAccentColor.current
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = localBackupState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalAccentColor.current
                        )
                    }
                }

                is BackupOperationState.Error -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = SafetyOrange
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = localBackupState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SafetyOrange
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalBackupListItem(
    backup: LocalBackupMetadata,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.FolderZip,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = backup.displayDate,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${backup.noteCount} notes, ${backup.categoryCount} categories",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${backup.displaySize} • ${backup.deviceName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = SafetyOrange
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete", color = SafetyOrange)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onShare,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalAccentColor.current
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share")
                }
            }
        }
    }
}
