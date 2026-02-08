package com.example.smarty.ui.screens.inputstream


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import com.example.smarty.ui.components.ChatEmptyState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.Note
import com.example.smarty.ui.components.OrganicThinkingIndicator
import com.example.smarty.ui.components.ChatMessageItem

import com.example.smarty.ui.components.MessageGroupPosition

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
    isChatProcessing: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Chat messages content
        if (chatMessages.isEmpty()) {
            ChatEmptyState(modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
                state = chatListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                // We handle spacing manually for dynamic grouping
                verticalArrangement = Arrangement.Top
            ) {
                items(
                    count = chatMessages.size,
                    key = { index -> chatMessages[index].id },
                    contentType = { index -> chatMessages[index].role }
                ) { index ->
                    val message = chatMessages[index]
                    val prevMessage = chatMessages.getOrNull(index - 1)
                    val nextMessage = chatMessages.getOrNull(index + 1)

                    // Grouping Logic: Check roles
                    val isSameAsPrev = prevMessage?.role == message.role
                    val isSameAsNext = nextMessage?.role == message.role

                    // Calculate position in group
                    val groupPosition = when {
                        !isSameAsPrev && !isSameAsNext -> MessageGroupPosition.SINGLE
                        !isSameAsPrev && isSameAsNext -> MessageGroupPosition.TOP
                        isSameAsPrev && isSameAsNext -> MessageGroupPosition.MIDDLE
                        isSameAsPrev && !isSameAsNext -> MessageGroupPosition.BOTTOM
                        else -> MessageGroupPosition.SINGLE
                    }

                    // Dynamic Spacing Logic
                    // Different sender (or first item) = Large gap (24dp)
                    // Same sender = Small gap (2.dp) - handled by item padding logic effectively
                    // We add top padding here
                    val topSpacing = if (index == 0) 0.dp else if (isSameAsPrev) 2.dp else 24.dp

                    // Stabilize getNote lambda - only recreate when notes change
                    val stableGetNote = remember(notes) {
                        { id: String -> notes.find { it.id == id } }
                    }

                    ChatMessageItem(
                        message = message,
                        groupPosition = groupPosition,
                        getNote = stableGetNote,
                        onNoteClick = onNoteClick,
                        onSuggestionClick = { suggestion ->
                            // Send the clicked suggestion as a new message
                            onSendChatMessage(suggestion, emptyList())
                        },
                        modifier = Modifier.padding(top = topSpacing)
                    )
                }

                // ═══════════════════════════════════════════════════════════════════════════
                // INLINE TOOL INDICATOR
                // Appears as the last item in the chat while the agent is working
                // Logic: Show if (Tool Active) OR (Processing AND Last message is NOT from Assistant)
                // This prevents the indicator from persisting after the assistant has replied.
                // ═══════════════════════════════════════════════════════════════════════════
                val lastMessageIsAssistant = chatMessages.lastOrNull()?.role == com.example.smarty.data.model.ChatRole.ASSISTANT
                val showIndicator = !currentToolName.isNullOrBlank() || (isChatProcessing && !lastMessageIsAssistant)

                if (showIndicator) {
                    item(key = "tool_indicator") {


                        val statusText = if (!currentToolName.isNullOrBlank()) {
                            currentToolName
                        } else {
                            stringResource(R.string.thinking)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)
                                .animateItem(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start
                        ) {
                            // Organic Thinking indicator
                            OrganicThinkingIndicator(
                                size = 28.dp,
                                baseColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )

                            Spacer(Modifier.width(12.dp))

                            Text(
                                text = statusText.lowercase(), // Systematic lowercase
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 0.4.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }

                        // Auto-scroll when tool indicator appears
                        LaunchedEffect(Unit) {
                            chatListState.animateScrollToItem(chatMessages.size)
                        }
                    }
                }
            }
        }
    }
}
