package com.example.smarty.ui.screens.inputstream

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.Note
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.ChatEmptyState
import com.example.smarty.ui.components.ChatMessageItem

/**
 * Chat mode content displaying AI conversation messages.
 *
 * Extracted from InputStreamScreen to improve code organization.
 * Handles the display of chat messages, empty state, and message interactions.
 */
@Composable
fun ChatModeContent(
    chatMessages: List<ChatMessage>,
    chatListState: LazyListState,
    notes: List<Note>,
    onNoteClick: (Note) -> Unit,
    onSendChatMessage: (String, List<com.example.smarty.data.model.Attachment>) -> Unit,
    contentPadding: PaddingValues,
    currentToolName: String? = null,
    modifier: Modifier = Modifier
) {
    val accentColor = LocalAccentColor.current
    
    Box(modifier = modifier.fillMaxSize()) {
        // Chat messages content
        if (chatMessages.isEmpty()) {
            ChatEmptyState(modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
                state = chatListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(
                    items = chatMessages,
                    key = { it.id },
                    contentType = { it.role }
                ) { message ->
                    // Stabilize getNote lambda - only recreate when notes change
                    val stableGetNote = remember(notes) {
                        { id: String -> notes.find { it.id == id } }
                    }
                    ChatMessageItem(
                        message = message,
                        getNote = stableGetNote,
                        onNoteClick = onNoteClick,
                        onSuggestionClick = { suggestion ->
                            // Send the clicked suggestion as a new message
                            onSendChatMessage(suggestion, emptyList())
                        }
                    )
                }

                // ═══════════════════════════════════════════════════════════════════════════
                // INLINE TOOL INDICATOR (ChatGPT-style)
                // Appears as the last item in the chat while the agent is working
                // ═══════════════════════════════════════════════════════════════════════════
                if (!currentToolName.isNullOrBlank()) {
                    item(key = "tool_indicator") {
                        val infiniteTransition = rememberInfiniteTransition(label = "tool_pulse")
                        val pulseAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.6f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(600, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulse_alpha"
                        )

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .animateItem(), // Smoothly animate entrance/exit
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 1.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Pulsing dot indicator
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .graphicsLayer { alpha = pulseAlpha }
                                        .clip(CircleShape)
                                        .background(accentColor)
                                )
                                
                                // Tool name with icon prefix
                                val toolIcon = when {
                                    currentToolName.contains("search", ignoreCase = true) -> "🔍"
                                    currentToolName.contains("note", ignoreCase = true) -> "📝"
                                    currentToolName.contains("event", ignoreCase = true) ||
                                    currentToolName.contains("calendar", ignoreCase = true) -> "📅"
                                    currentToolName.contains("timer", ignoreCase = true) -> "⏱️"
                                    currentToolName.contains("app", ignoreCase = true) -> "📱"
                                    currentToolName.contains("audio", ignoreCase = true) ||
                                    currentToolName.contains("play", ignoreCase = true) -> "🎵"
                                    currentToolName.contains("image", ignoreCase = true) -> "🖼️"
                                    currentToolName.contains("memory", ignoreCase = true) -> "🧠"
                                    else -> "⚙️"
                                }
                                
                                Text(
                                    text = "$toolIcon ${currentToolName}",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
