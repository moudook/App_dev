package com.example.smarty.ui.components

import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.utils.AnimationLifecycleState
import com.example.smarty.ui.utils.rememberAnimationLifecycleState

/**
 * Connection status indicator that shows the current network/API status.
 * Displays as a small pill with animated status dot.
 */
@Composable
fun ConnectionStatusIndicator(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true
) {
    val accentColor = LocalAccentColor.current

    // Pulse animation for connecting state - LIFECYCLE AWARE
    val lifecycleState by rememberAnimationLifecycleState()
    val shouldAnimate = lifecycleState == AnimationLifecycleState.RUNNING

    val pulseAlpha = if (shouldAnimate) {
        val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")
        val animatedAlpha by infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )
        animatedAlpha
    } else {
        0.75f // Static mid-point value
    }
    
    val statusColor = when (status) {
        ConnectionStatus.CONNECTED -> Color(0xFFA5D6A7) // Soft Green (Calm Palette)
        ConnectionStatus.CONNECTING -> Color(0xFFFFCC80) // Soft Amber (Calm Palette)
        ConnectionStatus.DISCONNECTED -> Color(0xFFEF9A9A) // Soft Red (Calm Palette)
        ConnectionStatus.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    
    val dotAlpha = if (status == ConnectionStatus.CONNECTING) pulseAlpha else 1f
    
    AnimatedVisibility(
        visible = status != ConnectionStatus.CONNECTED,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Status dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = dotAlpha))
                )
                
                if (showLabel) {
                    Text(
                        text = when (status) {
                            ConnectionStatus.CONNECTED -> stringResource(R.string.connected)
                            ConnectionStatus.CONNECTING -> stringResource(R.string.connecting)
                            ConnectionStatus.DISCONNECTED -> stringResource(R.string.reconnecting)
                            ConnectionStatus.OFFLINE -> stringResource(R.string.offline)
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

enum class ConnectionStatus {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    OFFLINE
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
    modifier: Modifier = Modifier
) {
    val accentColor = LocalAccentColor.current
    
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier
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
                    tint = accentColor
                )
            }
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.remove),
                    color = MaterialTheme.colorScheme.error
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
                    tint = MaterialTheme.colorScheme.error
                )
            }
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
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
        )

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = {
                if (placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            },
            keyboardOptions = keyboardOptions,
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = LocalAccentColor.current,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        )
    }
}
