package com.example.smarty.features.notes.ui.inputstream

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.core.domain.model.ChatSession
import com.example.smarty.ui.components.common.SmartyDialog
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.ChatHistoryEmptyState
import java.util.*

/**
 * Inline chat history content that displays in the main content area.
 *
 * This replaces the bottom sheet approach - history is shown in the same
 * layer as note cards, behind the gradient input field.
 */
@Composable
fun ChatHistoryContent(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    onSelectSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onBackToChat: () -> Unit,
    contentPadding: PaddingValues,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    var sessionToDeleteId by remember { mutableStateOf<String?>(null) }
    val sessionToDelete = sessionToDeleteId?.let { id -> sessions.find { it.id == id } }
    val accentColor = LocalAccentColor.current
    
    // Track cumulative scale for zoom-in gesture (opposite of zoom-out in chat)
    var cumulativeScale by remember { mutableFloatStateOf(1f) }
    var pointerCount by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Wait for first pointer down
                    awaitFirstDown(requireUnconsumed = false)
                    
                    do {
                        val event = awaitPointerEvent()
                        
                        // Track the number of active pointers
                        pointerCount = event.changes.count { it.pressed }
                        
                        // Only process zoom if we have 2+ pointers (actual pinch gesture)
                        if (pointerCount >= 2) {
                            // Calculate zoom from the event
                            val zoom = event.calculateZoom()
                            
                            // Update cumulative scale
                            cumulativeScale *= zoom
                            
                            // Trigger return to chat when pinch-in (zoom in) above threshold
                            // Using 1.3f for zoom IN detection (opposite of zoom out)
                            if (cumulativeScale > 1.3f) {
                                onBackToChat()
                                cumulativeScale = 1f // Reset for next gesture
                            }
                            
                            // Reset scale when zooming back out
                            if (zoom < 1f && cumulativeScale < 1f) {
                                cumulativeScale = 1f
                            }
                            
                            // Consume the event to prevent scrolling during pinch
                            event.changes.forEach { it.consume() }
                        }
                        // If pointerCount == 1, allow normal scrolling
                    } while (event.changes.any { it.pressed })
                    
                    // Reset when all fingers are lifted
                    cumulativeScale = 1f
                }
            }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding, // Includes necessary top padding
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isLoading) {
                item {
                    com.example.smarty.ui.components.ChatHistoryLoadingState(
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else if (sessions.isEmpty()) {
                item {
                    ChatHistoryEmptyState(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp)
                    )
                }
            } else {
                items(sessions, key = { it.id }) { session ->
                    InlineSessionItem(
                        session = session,
                        isSelected = session.id == currentSessionId,
                        accentColor = accentColor,
                        onClick = {
                            onSelectSession(session.id)
                            onBackToChat()
                        },
                        onDelete = { sessionToDeleteId = session.id }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }

    // Delete Dialog
    sessionToDelete?.let { session ->
        SmartyDialog(
            title = stringResource(R.string.delete_chat),
            text = stringResource(R.string.chat_delete_warning),
            onConfirm = {
                onDeleteSession(session.id)
                sessionToDeleteId = null
            },
            onDismiss = { sessionToDeleteId = null },
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            isDestructive = true
        )
    }
}

@Composable
private fun InlineSessionItem(
    session: ChatSession,
    isSelected: Boolean,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    // Theme-aware colors
    val containerColor by animateColorAsState(
        targetValue = if (isSelected)
            accentColor.copy(alpha = 0.12f)
        else
            MaterialTheme.colorScheme.surface,
        label = "containerColor"
    )

    val contentColor by animateColorAsState(
        targetValue = if (isSelected)
            accentColor
        else
            MaterialTheme.colorScheme.onSurface,
        label = "contentColor"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isSelected)
            accentColor.copy(alpha = 0.2f)
        else
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        label = "borderColor"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(percent = 50))
            .clickable { onClick() },
        shape = RoundedCornerShape(percent = 50),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isNewChat = session.title.isBlank() ||
                session.title.equals("new_chat", ignoreCase = true) ||
                session.title.equals("new_conversation", ignoreCase = true)

            // Icon - Unified to AutoAwesome for AI features
            Icon(
                imageVector = if (isNewChat) Icons.Default.AutoAwesome else Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Text Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // Title
                Text(
                    text = if (isSelected && isNewChat) stringResource(R.string.current_chat) else session.title.ifBlank { stringResource(R.string.new_conversation) }.lowercase(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = (-0.2).sp
                    ),
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Preview
                Text(
                    text = session.lastMessagePreview.ifBlank { stringResource(R.string.start_a_new_conversation) }.lowercase(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        letterSpacing = 0.3.sp
                    ),
                    color = if (isSelected) contentColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Right Actions
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center
            ) {
                // Time
                Text(
                    text = formatRelativeTime(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = if (isSelected) contentColor.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Delete Action - Standard Delete icon
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = stringResource(R.string.delete),
                        tint = if (isSelected) contentColor.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> stringResource(R.string.now)
        diff < 3600_000 -> stringResource(R.string.minutes_ago, diff / 60_000)
        diff < 86400_000 -> stringResource(R.string.hours_ago, diff / 3600_000)
        diff < 172800_000 -> stringResource(R.string.yesterday)
        diff < 604800_000 -> stringResource(R.string.days_ago, diff / 86400_000)
        else -> {
            val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestamp)).lowercase()
        }
    }
}
