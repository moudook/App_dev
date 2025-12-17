package com.example.smarty.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smarty.data.model.ChatSession
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.MonoFont
import com.example.smarty.ui.theme.SafetyOrange
import java.text.SimpleDateFormat
import java.util.*

/**
 * Bottom sheet showing chat history with all previous sessions.
 * Allows user to switch between sessions, create new ones, or delete old ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistorySheet(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onSelectSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onDeleteSession: (String) -> Unit
) {
    var sessionToDelete by remember { mutableStateOf<ChatSession?>(null) }
    val accentColor = LocalAccentColor.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface, // Standard Dark Surface
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                shape = RoundedCornerShape(2.dp)
            ) {
                Box(modifier = Modifier.size(width = 32.dp, height = 4.dp))
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "History",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "•  ${sessions.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }

                // New Chat (Text Button Style for "Apple-like" cleanness)
                TextButton(
                    onClick = {
                        onNewChat()
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = accentColor)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "New Chat")
                }
            }

            // Sessions list
            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No conversations yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp) // Tighter list
                ) {
                    items(sessions, key = { it.id }) { session ->
                        ChatSessionItem(
                            session = session,
                            isSelected = session.id == currentSessionId,
                            onClick = {
                                onSelectSession(session.id)
                                onDismiss()
                            },
                            onDelete = { sessionToDelete = session }
                        )
                    }
                }
            }
        }
    }

    // Delete Dialog (Minimalist)
    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Delete chat?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSession(session.id)
                        sessionToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChatSessionItem(
    session: ChatSession,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val accentColor = LocalAccentColor.current
    
    // Minimalist Selection
    val backgroundColor = if (isSelected) accentColor.copy(alpha = 0.1f) else androidx.compose.ui.graphics.Color.Transparent
    val contentColor = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Simple Icon
        Icon(
            imageVector = if (isSelected) Icons.Default.ChatBubble else Icons.Default.ChatBubbleOutline,
            contentDescription = null,
            tint = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                // Optional: Date if space permits, or put in next line
                Spacer(modifier = Modifier.weight(1f))
                
                Text(
                    text = formatRelativeTime(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            
            if (session.lastMessagePreview.isNotBlank()) {
                Text(
                    text = session.lastMessagePreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        // Delete Action (Only show if necessary or on swipe? 
        // For simplicity, we keep the button but make it very subtle)
        /* 
         * DESIGN CHOICE: To make it truly Apple-like, we might hide the delete button 
         * until swipe, but swipe is complex. We'll keep a very faint X or Trash.
         */
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close, // Close is cleaner than Trash for lists
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
    
    // Separator (Apple Style)
    if (!isSelected) {
        Divider(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(start = 48.dp) // Inset separator
        )
    }
}

/**
 * Format timestamp as relative time (e.g., "2 hours ago", "Yesterday", "Dec 15")
 */
private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        diff < 172800_000 -> "Yesterday"
        diff < 604800_000 -> "${diff / 86400_000}d ago"
        else -> {
            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}
