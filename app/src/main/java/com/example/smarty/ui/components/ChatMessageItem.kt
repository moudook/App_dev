package com.example.smarty.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatRole
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.MonoFont
import kotlinx.coroutines.delay

/**
 * Chat message bubble component
 * - User messages aligned right with accent color background
 * - Assistant messages aligned left with surface color
 * - Shows executed action results
 * - Animated entrance
 */
@Composable
fun ChatMessageItem(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == ChatRole.USER
    var isVisible by remember { mutableStateOf(false) }

    // Animate entrance
    LaunchedEffect(message.id) {
        delay(50)
        isVisible = true
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(200)) + slideInHorizontally(
            initialOffsetX = { if (isUser) it else -it },
            animationSpec = tween(300)
        )
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // Message bubble
            val shapes = LocalShapes.current
            Surface(
                modifier = Modifier
                    .widthIn(max = 300.dp),
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = if (isUser) 20.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 20.dp
                ),
                color = if (isUser) {
                    LocalAccentColor.current
                } else {
                    MaterialTheme.colorScheme.surface
                }
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    // Message content
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = MonoFont
                        ),
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )

                    // Show attachments count if any
                    if (message.attachments.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${message.attachments.size} attachment(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUser) {
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            }
                        )
                    }
                }
            }

            // Action results (for assistant messages)
            if (!isUser && message.hasActions) {
                Spacer(modifier = Modifier.height(4.dp))
                message.executedActions.forEach { actionResult ->
                    ActionResultChip(
                        actionName = actionResult.action,
                        success = actionResult.success,
                        summary = actionResult.resultSummary
                    )
                }
            }

            // Timestamp
            Text(
                text = formatTimestamp(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)
            )
        }
    }
}

/**
 * Chip showing action execution result
 */
@Composable
private fun ActionResultChip(
    actionName: String,
    success: Boolean,
    summary: String
) {
    val shapes = LocalShapes.current
    Row(
        modifier = Modifier
            .clip(shapes.chipSmall)
            .background(
                if (success) {
                    LocalAccentColor.current.copy(alpha = 0.1f)
                } else {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                }
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (success) Icons.Default.Check else Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (success) LocalAccentColor.current else MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = formatActionName(actionName),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium
            ),
            color = if (success) LocalAccentColor.current else MaterialTheme.colorScheme.error
        )
    }
}

/**
 * Format action name for display
 */
private fun formatActionName(actionName: String): String {
    return actionName
        .replace("Action", "")
        .replace(Regex("([A-Z])"), " $1")
        .trim()
}

/**
 * Format timestamp for display
 */
private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        else -> {
            val date = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            date.format(java.util.Date(timestamp))
        }
    }
}
