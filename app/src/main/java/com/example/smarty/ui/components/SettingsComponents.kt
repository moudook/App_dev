package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.utils.AnimationLifecycleState
import com.example.smarty.ui.utils.ThemeAwareColors
import com.example.smarty.ui.utils.rememberAnimationLifecycleState

/**
 * Connection status indicator that shows the current network/API status.
 * Displays as a small pill with animated status dot.
 */
@Composable
fun ConnectionStatusIndicator(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val accentColor = LocalAccentColor.current

    // Pulse animation for connecting state - LIFECYCLE AWARE
    val lifecycleState by rememberAnimationLifecycleState()
    val shouldAnimate = lifecycleState == AnimationLifecycleState.RUNNING

    val pulseAlpha =
        if (shouldAnimate) {
            val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")
            val animatedAlpha by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "pulse",
            )
            animatedAlpha
        } else {
            0.75f // Static mid-point value
        }

    val statusColor =
        when (status) {
            ConnectionStatus.CONNECTED -> ThemeAwareColors.successColor()
            ConnectionStatus.CONNECTING -> ThemeAwareColors.accentColor()
            ConnectionStatus.DISCONNECTED -> ThemeAwareColors.errorColor()
            ConnectionStatus.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        }

    val dotAlpha = if (status == ConnectionStatus.CONNECTING) pulseAlpha else 1f

    AnimatedVisibility(
        visible = status != ConnectionStatus.CONNECTED,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp), // Pill shape
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
            tonalElevation = 0.dp,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Status dot
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = dotAlpha)),
                )

                if (showLabel) {
                    Text(
                        text =
                            when (status) {
                                ConnectionStatus.CONNECTED -> stringResource(R.string.connected)
                                ConnectionStatus.CONNECTING -> stringResource(R.string.connecting)
                                ConnectionStatus.DISCONNECTED -> stringResource(R.string.reconnecting)
                                ConnectionStatus.OFFLINE -> stringResource(R.string.offline)
                            },
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Medium,
                            ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Cloud sync status indicator.
 * Shows sync state: idle (hidden), syncing (spinner), success (checkmark), error (error icon).
 */
@Composable
fun CloudSyncIndicator(
    syncState: com.example.smarty.features.notes.domain.SmartyViewModel.CloudSyncState,
    onSyncClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val lifecycleState by rememberAnimationLifecycleState()
    val shouldAnimate = lifecycleState == AnimationLifecycleState.RUNNING

    when (syncState) {
        is com.example.smarty.features.notes.domain.SmartyViewModel.CloudSyncState.Idle -> {
            // Don't show anything when idle
        }
        is com.example.smarty.features.notes.domain.SmartyViewModel.CloudSyncState.Syncing -> {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                tonalElevation = 0.dp,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                modifier = modifier.clickable(onClick = onSyncClick),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (showLabel) {
                        Text(
                            text = "Syncing…",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        is com.example.smarty.features.notes.domain.SmartyViewModel.CloudSyncState.Success -> {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = ThemeAwareColors.successColor().copy(alpha = 0.15f),
                tonalElevation = 0.dp,
                border = BorderStroke(0.5.dp, ThemeAwareColors.successColor().copy(alpha = 0.3f)),
                modifier = modifier.clickable(onClick = onSyncClick),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = "Synced",
                        modifier = Modifier.size(12.dp),
                        tint = ThemeAwareColors.successColor(),
                    )
                    if (showLabel) {
                        Text(
                            text = "Synced.",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = ThemeAwareColors.successColor(),
                        )
                    }
                }
            }
        }
        is com.example.smarty.features.notes.domain.SmartyViewModel.CloudSyncState.Error -> {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = ThemeAwareColors.errorColor().copy(alpha = 0.15f),
                tonalElevation = 0.dp,
                border = BorderStroke(0.5.dp, ThemeAwareColors.errorColor().copy(alpha = 0.3f)),
                modifier = modifier.clickable(onClick = onSyncClick),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Sync error",
                        modifier = Modifier.size(12.dp),
                        tint = ThemeAwareColors.errorColor(),
                    )
                    if (showLabel) {
                        Text(
                            text = "Sync failed.",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = ThemeAwareColors.errorColor(),
                        )
                    }
                }
            }
        }
    }
}

enum class ConnectionStatus {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    OFFLINE,
}

/**
 * Long press action menu for categories.
 * Shows View, Edit, Delete options in a dropdown.
 */
@Composable
fun CategoryActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onView: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentColor = LocalAccentColor.current

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.open)) },
            onClick = {
                onView()
                onDismiss()
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Visibility,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            },
        )

        DropdownMenuItem(
            text = { Text(stringResource(R.string.edit)) },
            onClick = {
                onEdit()
                onDismiss()
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.remove),
                    color = ThemeAwareColors.errorColor(),
                )
            },
            onClick = {
                onDelete()
                onDismiss()
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = ThemeAwareColors.errorColor(),
                )
            },
        )
    }
}

/**
 * Clean input row for settings with label and text field.
 * Uses OutlinedTextField for better usability and aesthetics.
 */
@Composable
fun SettingsInputRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val focusColor = if (isDark) Color.White else Color.Black

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
            )
        },
        textStyle = MaterialTheme.typography.titleMedium,
        placeholder = {
            if (placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        },
        keyboardOptions = keyboardOptions,
        singleLine = true,
        shape = RoundedCornerShape(26.dp), // "History Pill" shape
        colors =
            OutlinedTextFieldDefaults.colors(
                // Legend on Border colors
                focusedLabelColor = focusColor,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                // Border colors (Pure White focus on Dark, Black focus on Light)
                focusedBorderColor = focusColor,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                // Container colors
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
            ),
    )
}
