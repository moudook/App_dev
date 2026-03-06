package com.example.smarty.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.core.domain.model.ChatSession
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.MonoFont
import com.example.smarty.ui.theme.rememberMonochromeAccent
import java.text.SimpleDateFormat
import java.util.*

// Cached date formatter for chat history items
private val shortDateFormat by lazy { SimpleDateFormat("MMM d", Locale.getDefault()) }

/**
 * Bottom sheet showing chat history with all previous sessions.
 * Allows user to switch between sessions, create new ones, or delete old ones.
 * Now updated with Monochrome Aesthetics (Black/White).
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
    onDeleteSession: (String) -> Unit,
    isLoading: Boolean = false
) {
    // Store ID only and derive session from list to stay in sync
    var sessionToDeleteId by remember { mutableStateOf<String?>(null) }
    val sessionToDelete = sessionToDeleteId?.let { id -> sessions.find { it.id == id } }
    
    // Monochrome Logic
    val monochromeColor = rememberMonochromeAccent()

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
        CompositionLocalProvider(LocalAccentColor provides monochromeColor) {
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
                            text = stringResource(R.string.history),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${stringResource(R.string.bullet_separator)}  ${stringResource(R.string.sessions_count, sessions.size)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }

                    // New Chat (Text Button Style for "Apple-like" cleanness)
                    // New Chat button removed as per user request (redundant with bottom action bar)
                    Box(modifier = Modifier.size(1.dp)) // Spacer placeholder or just empty
                }

                // Sessions list
                if (isLoading) {
                    com.example.smarty.ui.components.ChatHistoryLoadingState(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                    )
                } else if (sessions.isEmpty()) {
                    com.example.smarty.ui.components.ChatHistoryEmptyState(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                    )
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
                                onDelete = { sessionToDeleteId = session.id }
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete Dialog (Minimalist)
    sessionToDelete?.let { session ->
        com.example.smarty.ui.components.common.SmartyDialog(
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
private fun ChatSessionItem(
    session: ChatSession,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val accentColor = LocalAccentColor.current

    // UI Constants
    // Apple Design: "Continuous" curves. For a 48dp Icon, ~10-12dp radius is the "Squircle" sweet spot.
    // For the list item container, a subtle 14dp radius is cleaner than 16dp.
    // Modern "Pill" shape for history items - 28dp for a perfect 56dp height pill
    val containerShape = RoundedCornerShape(28.dp)
    val iconShape = RoundedCornerShape(18.dp) // Softer squircle for the icon

    val containerColor = if (isSelected)
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    else
        Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(containerShape)
            .background(containerColor)
            .then(
                if (isSelected) Modifier.border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    shape = containerShape
                ) else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isNewChat = session.title.isBlank() || session.title.equals("new_chat", ignoreCase = true) || session.title.equals("new_conversation", ignoreCase = true)

        // 1. Icon (Proper Squircle)
        Surface(
            modifier = Modifier.size(44.dp), // Slightly smaller, tighter
            shape = iconShape,
            color = if (isSelected) accentColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            contentColor = if (isSelected) (if (accentColor.luminance() < 0.5f) Color.White else Color.Black) else MaterialTheme.colorScheme.onSurfaceVariant // Contrast for monochrome
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Smart Icon Logic
                val icon = when {
                    isNewChat -> Icons.Default.AutoAwesome // Standard AI
                    isSelected -> Icons.Default.AutoAwesome // Active AI
                    else -> Icons.AutoMirrored.Filled.Chat // History
                }

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // 2. Center Text Column
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isSelected && isNewChat) stringResource(R.string.current_chat) else session.title.ifBlank { stringResource(R.string.new_conversation) }.lowercase(),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium, // lighter than SemiBold for Apple look
                    fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp)
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = session.lastMessagePreview.ifBlank { stringResource(R.string.start_a_new_conversation) }.lowercase(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 3. Right Side: Date + Close visual group
        // Configured to be perfectly aligned vertically
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = formatRelativeTime(session.updatedAt).lowercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Delete Button (Small, subtle)
            // Using a Box to expand click area without affecting visuals
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)) // Subtle backing
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                 Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/**
 * Format timestamp as relative time (e.g., "2 hours ago", "Yesterday", "Dec 15")
 */
@Composable
private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> stringResource(R.string.just_now)
        diff < 3600_000 -> stringResource(R.string.minutes_ago, diff / 60_000)
        diff < 86400_000 -> stringResource(R.string.hours_ago, diff / 3600_000)
        diff < 172800_000 -> stringResource(R.string.yesterday)
        diff < 604800_000 -> stringResource(R.string.days_ago, diff / 86400_000)
        else -> {
            shortDateFormat.format(Date(timestamp)).lowercase()
        }
    }
}
