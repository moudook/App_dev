package com.example.smarty.features.notes.ui.inputstream


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
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.Note
import com.example.smarty.ui.components.ChatMessageItem
import com.example.smarty.ui.components.AgentActivityIndicator
import com.example.smarty.features.chat.domain.ChatFeatureManager.AgentActivity

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
    onSendChatMessage: (String, List<com.example.smarty.core.domain.model.Attachment>) -> Unit,
    contentPadding: PaddingValues,
    isChatProcessing: Boolean = false,
    isHistoryLoading: Boolean = false,
    agentActivity: AgentActivity? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Loading State
        if (isHistoryLoading) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                items(5) { index ->
                    // Alternate between user (right) and assistant (left) for realistic feel
                    com.example.smarty.ui.components.ChatMessageSkeleton(isFromUser = index % 2 == 0)
                }
            }
        }
        // Chat messages content
        else if (chatMessages.isEmpty()) {
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

                // Inline indicator removed for cleaner UI

                // Agent Activity Indicator (Thinking/Tool Execution)
                if (agentActivity != null) {
                    item(key = "agent_activity") {
                        AgentActivityIndicator(
                            activity = agentActivity,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

