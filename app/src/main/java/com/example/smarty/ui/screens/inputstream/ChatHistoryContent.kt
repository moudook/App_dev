package com.example.smarty.ui.screens.inputstream

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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.HighlightOff
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
import com.example.smarty.data.model.ChatSession
import com.example.smarty.ui.LocalAccentColor
import java.text.SimpleDateFormat
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
            if (sessions.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                         Text(
                            text = "No past conversations",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
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
        com.example.smarty.ui.components.common.JarvisDialog(
            title = "Delete chat?",
            text = "This action cannot be undone.",
            onConfirm = {
                onDeleteSession(session.id)
                sessionToDeleteId = null
            },
            onDismiss = { sessionToDeleteId = null },
            confirmText = "Delete",
            dismissText = "Cancel",
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
    // Local color helpers
    val AppLightBlue = Color(0xFFD0E7FE)
    val AppDarkBlue = Color(0xFF003258)

    // "Ultrathink" / Note Card Aesthetic Constants
    val containerShape = RoundedCornerShape(percent = 50) // Pill shape
    val iconShape = CircleShape // Fully round for pill aesthetic

    // Colors
    val containerColor by animateColorAsState(
        targetValue = if (isSelected)
            AppLightBlue
        else
            MaterialTheme.colorScheme.surface, // Clean surface look for unselected
        label = "containerColor"
    )

    val contentColor by animateColorAsState(
        targetValue = if (isSelected)
            AppDarkBlue
        else
            MaterialTheme.colorScheme.onSurface,
        label = "contentColor"
    )
    
    val borderColor by animateColorAsState(
        targetValue = if (isSelected)
            AppDarkBlue.copy(alpha = 0.1f)
        else
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), // Match NoteCard border style
        label = "borderColor"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp) // Consistent margins with note cards
            .clip(containerShape)
            .clickable { onClick() },
        shape = containerShape,
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp), // Balanced pill padding
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isNewChat = session.title.isBlank() ||
                session.title.equals("New Chat", ignoreCase = true) ||
                session.title.equals("New Conversation", ignoreCase = true)

            // Icon - Clean, borderless, outlined style
            Icon(
                imageVector = if (isNewChat) Icons.Outlined.AutoAwesome else Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = if (isSelected) AppDarkBlue else MaterialTheme.colorScheme.onSurfaceVariant,
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
                    text = if (isSelected && isNewChat) "Current Chat" else session.title.ifBlank { "New Conversation" },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Preview
                Text(
                    text = session.lastMessagePreview.ifBlank { "Start a new conversation" },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        letterSpacing = 0.2.sp
                    ),
                    color = if (isSelected) contentColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
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

                // Delete Action - Minimal, borderless
                Icon(
                    imageVector = Icons.Outlined.HighlightOff,
                    contentDescription = "Delete",
                    tint = if (isSelected) contentColor.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onDelete)
                )
            }
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Now"
        diff < 3600_000 -> "${diff / 60_000}m"
        diff < 86400_000 -> "${diff / 3600_000}h"
        diff < 172800_000 -> "Yesterday"
        diff < 604800_000 -> "${diff / 86400_000}d"
        else -> {
            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}
