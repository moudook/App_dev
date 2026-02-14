package com.example.smarty.features.settings.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.smarty.ui.theme.rememberMonochromeAccent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.ui.theme.softCardShadow
import com.example.smarty.data.backup.AutoBackupConfig
import com.example.smarty.data.backup.BackupMetadata
import com.example.smarty.data.backup.BackupOperationState
import com.example.smarty.data.backup.LocalBackupMetadata
import com.example.smarty.ui.components.common.SmartyDialog
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ComponentSpacing
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
    isLoadingCloudBackups: Boolean = false,
    lastBackupTime: Long,
    autoBackupEnabled: Boolean,
    autoBackupIntervalDays: Int,
    // Local backup parameters
    localBackupState: BackupOperationState,
    localBackups: List<LocalBackupMetadata>,
    isLoadingLocalBackups: Boolean = false,
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

    // Monochrome Logic
    val monochromeColor = rememberMonochromeAccent()

    CompositionLocalProvider(LocalAccentColor provides monochromeColor) {
        if (isEmbedded) {
            Box(modifier = modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(ComponentSpacing.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(16.dp) // Calmer spacing
                ) {
                    // Google Account Section
                    item {
                        SectionHeader(title = stringResource(R.string.google_account))
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
                            SectionHeader(title = stringResource(R.string.backup))
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
                            SectionHeader(title = stringResource(R.string.auto_backup))
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
                            SectionHeader(title = stringResource(R.string.available_backups))
                        }

                        if (isLoadingCloudBackups) {
                            item {
                                com.example.smarty.ui.components.BackupsLoadingState(
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        } else if (availableBackups.isEmpty()) {
                            item {
                                com.example.smarty.ui.components.BackupEmptyState(
                                    isLocal = false,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp)
                                )
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
                                text = stringResource(R.string.cloud_backup_info),
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
                        SectionHeader(title = stringResource(R.string.local_backup))
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
                        SectionHeader(title = stringResource(R.string.saved_backups))
                    }

                    if (isLoadingLocalBackups) {
                        item {
                            com.example.smarty.ui.components.BackupsLoadingState(
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    } else if (localBackups.isEmpty()) {
                        item {
                            com.example.smarty.ui.components.BackupEmptyState(
                                isLocal = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp)
                            )
                        }
                    } else {
                        items(localBackups) { backup ->
                            LocalBackupListItem(
                                backup = backup,
                                onShare = {
                                    val intent = onShareLocalBackup(backup)
                                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_backup)))
                                },
                                onDelete = { showDeleteLocalBackupDialog = backup }
                            )
                        }
                    }

                    // Info footer for local backup
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.local_backup_info),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
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
                            Text(
                                stringResource(R.string.backup_and_sync),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.5).sp
                                )
                            )
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
                        SectionHeader(title = stringResource(R.string.google_account))
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
                            SectionHeader(title = stringResource(R.string.backup))
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
                            SectionHeader(title = stringResource(R.string.auto_backup))
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
                            SectionHeader(title = stringResource(R.string.available_backups))
                        }

                        if (isLoadingCloudBackups) {
                            item {
                                com.example.smarty.ui.components.BackupsLoadingState(
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        } else if (availableBackups.isEmpty()) {
                            item {
                                com.example.smarty.ui.components.BackupEmptyState(
                                    isLocal = false,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp)
                                )
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
                                text = stringResource(R.string.cloud_backup_info),
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
                        SectionHeader(title = stringResource(R.string.local_backup))
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
                        SectionHeader(title = stringResource(R.string.saved_backups))
                    }

                    if (isLoadingLocalBackups) {
                        item {
                            com.example.smarty.ui.components.BackupsLoadingState(
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    } else if (localBackups.isEmpty()) {
                        item {
                            com.example.smarty.ui.components.BackupEmptyState(
                                isLocal = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp)
                            )
                        }
                    } else {
                        items(localBackups) { backup ->
                            LocalBackupListItem(
                                backup = backup,
                                onShare = {
                                    val intent = onShareLocalBackup(backup)
                                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_backup)))
                                },
                                onDelete = { showDeleteLocalBackupDialog = backup }
                            )
                        }
                    }

                    // Info footer for local backup
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.local_backup_info),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
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

    showRestoreConfirmDialog?.let { backup ->
        SmartyDialog(
            title = stringResource(R.string.restore_backup),
            text = stringResource(
                R.string.restore_warning,
                backup.displayDate.lowercase(),
                backup.noteCount,
                backup.categoryCount
            ),
            onConfirm = {
                onRestoreBackup(backup)
                showRestoreConfirmDialog = null
            },
            onDismiss = { showRestoreConfirmDialog = null },
            confirmText = stringResource(R.string.restore),
            dismissText = stringResource(R.string.cancel)
        )
    }

    // Delete cloud backup confirmation dialog
    showDeleteConfirmDialog?.let { backup ->
        SmartyDialog(
            title = stringResource(R.string.delete_cloud_backup),
            text = stringResource(
                R.string.delete_confirm_cloud,
                backup.displayDate.lowercase()
            ),
            onConfirm = {
                onDeleteBackup(backup)
                showDeleteConfirmDialog = null
            },
            onDismiss = { showDeleteConfirmDialog = null },
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            isDestructive = true
        )
    }

    // Delete local backup confirmation dialog
    showDeleteLocalBackupDialog?.let { backup ->
        SmartyDialog(
            title = stringResource(R.string.delete_local_archive),
            text = stringResource(
                R.string.delete_confirm_local,
                backup.displayDate.lowercase(),
                backup.displaySize.lowercase(),
                backup.noteCount,
                backup.categoryCount
            ),
            onConfirm = {
                onDeleteLocalBackup(backup)
                showDeleteLocalBackupDialog = null
            },
            onDismiss = { showDeleteLocalBackupDialog = null },
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            isDestructive = true
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        ),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), // Dimmed monochrome
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 12.dp)
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .softCardShadow(elevation = 4.dp, shape = RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSignedIn) {
                // Profile icon
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(LocalAccentColor.current.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Face,
                        contentDescription = null,
                        tint = LocalAccentColor.current,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName?.lowercase() ?: stringResource(R.string.google_account),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (email != null) {
                        Text(
                            text = email.lowercase(),
                            style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.2.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                TextButton(onClick = onSignOut) {
                    Text(
                        stringResource(R.string.sign_out),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFF3B30).copy(alpha = 0.8f) // Semantic Red
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(32.dp)
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.not_signed_in),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.sign_in_to_enable_cloud_backup),
                        style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.2.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                Button(
                    onClick = onSignIn,
                    shape = RoundedCornerShape(26.dp), // Pill button
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface // Monochrome Action
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Text(
                        stringResource(R.string.sign_in), 
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.surface
                    )
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .softCardShadow(elevation = 4.dp, shape = RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = null,
                    tint = Color(0xFF007AFF), // Semantic Blue (Processing/Sync)
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.last_backup),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        text = if (lastBackupTime > 0) {
                            val dateStr = SimpleDateFormat(stringResource(R.string.date_format_short), Locale.getDefault()).format(Date(lastBackupTime))
                            val timeStr = SimpleDateFormat(stringResource(R.string.time_format_24h), Locale.getDefault()).format(Date(lastBackupTime))
                            stringResource(R.string.date_at_time_format, dateStr, timeStr).lowercase()
                        } else {
                            stringResource(R.string.never_backed_up)
                        },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.2).sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Backup progress or button
            when (backupState) {
                is BackupOperationState.Idle -> {
                    Button(
                        onClick = onCreateBackup,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface
                        ),
                        elevation = ButtonDefaults.buttonElevation(0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.surface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.backup_now), 
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.surface
                        )
                    }
                }

                is BackupOperationState.InProgress -> {
                    Column {
                        com.example.smarty.ui.components.CalmLinearProgress(
                            progress = { backupState.progress },
                            modifier = Modifier.fillMaxWidth()
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
                            tint = Color(0xFF34C759) // Semantic Green
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when(backupState.message) {
                                "connection_lost" -> stringResource(R.string.connection_lost)
                                "backup_failed" -> stringResource(R.string.backup_failed)
                                else -> backupState.message
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF34C759) // Semantic Green
                        )
                    }
                }

                is BackupOperationState.Error -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.GppBad,
                                contentDescription = null,
                                tint = Color(0xFFFF3B30) // Semantic Red
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when(backupState.message) {
                                    "connection_lost" -> stringResource(R.string.connection_lost)
                                    "backup_failed" -> stringResource(R.string.backup_failed)
                                    "permission_revoked" -> stringResource(R.string.permission_revoked)
                                    else -> backupState.message
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFFF3B30), // Semantic Red
                                modifier = Modifier.weight(1f)
                            )
                        }

                        backupState.recoveryIntent?.let { intent ->
                            val launcher = rememberLauncherForActivityResult(
                                ActivityResultContracts.StartActivityForResult()
                            ) { _ -> onCreateBackup() }

                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { launcher.launch(intent) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30)) // Semantic Red
                            ) {
                                Icon(Icons.Default.VpnKey, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.fix_permissions_token))
                            }
                        }
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .softCardShadow(elevation = 4.dp, shape = RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.auto_backup),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.automatically_backup_when_due),
                        style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.2.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.background,
                        checkedTrackColor = Color(0xFF007AFF), // Semantic Blue for active state
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
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
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onIntervalClick() }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.backup_interval),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.every_x_days, intervalDays),
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp),
                                color = Color(0xFF007AFF) // Semantic Blue for interactive value
                            )
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.TrendingFlat,
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .softCardShadow(elevation = 2.dp, shape = RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CloudQueue,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), // Monochrome
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = backup.displayDate.lowercase(),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.backup_stats, backup.noteCount, backup.categoryCount, backup.displaySize.lowercase()),
                        style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.2.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Text(
                        text = backup.deviceName.lowercase(),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.3.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
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
                    com.example.smarty.ui.components.CalmLinearProgress(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth()
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
                        Text(
                            stringResource(R.string.delete),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onRestore,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface
                        ),
                        elevation = ButtonDefaults.buttonElevation(0.dp)
                    ) {
                        Text(
                            stringResource(R.string.restore), 
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.surface
                        )
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
    SmartyDialog(
        title = stringResource(R.string.backup_interval),
        onDismiss = onDismiss,
        confirmText = "",
        dismissText = stringResource(R.string.cancel),
        confirmEnabled = false,
        onConfirm = {},
        customContent = {
            Column {
                AutoBackupConfig.INTERVAL_OPTIONS.forEach { days ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelectInterval(days) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentInterval == days,
                            onClick = { onSelectInterval(days) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Color(0xFF007AFF) // Semantic Blue
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (days) {
                                30 -> stringResource(R.string.every_30_days)
                                60 -> stringResource(R.string.every_60_days)
                                100 -> stringResource(R.string.every_100_days)
                                180 -> stringResource(R.string.every_180_days)
                                365 -> stringResource(R.string.every_365_days)
                                else -> stringResource(R.string.every_x_days, days)
                            }.lowercase(),
                            style = MaterialTheme.typography.bodyLarge.copy(letterSpacing = 0.2.sp)
                        )
                    }
                }
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .softCardShadow(elevation = 4.dp, shape = RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
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
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), // Monochrome
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.create_local_backup),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.save_a_zip_file_to_share_or_store),
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
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface
                        ),
                        elevation = ButtonDefaults.buttonElevation(0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.surface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.create_backup),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.surface
                        )
                    }
                }

                is BackupOperationState.InProgress -> {
                    Column {
                        com.example.smarty.ui.components.CalmLinearProgress(
                            progress = { localBackupState.progress },
                            modifier = Modifier.fillMaxWidth()
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
                            tint = Color(0xFF34C759) // Semantic Green
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = localBackupState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF34C759) // Semantic Green
                        )
                    }
                }

                is BackupOperationState.Error -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.GppBad,
                            contentDescription = null,
                            tint = Color(0xFFFF3B30) // Semantic Red
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = localBackupState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFFF3B30) // Semantic Red
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .softCardShadow(elevation = 2.dp, shape = RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
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
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), // Monochrome
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = backup.displayDate.lowercase(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.local_backup_stats, backup.noteCount, backup.categoryCount, backup.displaySize.lowercase()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.local_backup_device_info, backup.displaySize.lowercase(), backup.deviceName.lowercase()),
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
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color(0xFFFF3B30).copy(alpha = 0.8f) // Semantic Red
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.delete), 
                        color = Color(0xFFFF3B30).copy(alpha = 0.8f) // Semantic Red
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onShare,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share, 
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.surface
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.share),
                        color = MaterialTheme.colorScheme.surface
                    )
                }
            }
        }
    }
}
