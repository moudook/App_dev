package com.example.smarty.features.notes.ui.inputstream


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.ui.components.ChatEmptyState
import com.example.smarty.ui.components.ChatMessageItem
import com.example.smarty.ui.components.AgentActivityIndicator
import com.example.smarty.features.chat.domain.ChatFeatureManager.AgentActivity
import com.example.smarty.features.chat.domain.FailedMessage
import com.example.smarty.ui.components.MessageGroupPosition
import com.example.smarty.ui.LocalAccentColor
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

data class MessageGroup(
    val label: String,
    val messages: List<ChatMessage>
)

fun groupMessagesByTime(messages: List<ChatMessage>): List<MessageGroup> {
    val now = System.currentTimeMillis()
    val groups = mutableMapOf<String, MutableList<ChatMessage>>()
    val order = mutableListOf<String>()
    
    messages.forEach { message ->
        val label = when {
            message.timestamp > now - 86_400_000 -> "Today"
            message.timestamp > now - 172_800_000 -> "Yesterday"
            message.timestamp > now - 604_800_000 -> "This Week"
            else -> "Earlier"
        }
        if (!groups.containsKey(label)) {
            order.add(label)
        }
        groups.getOrPut(label) { mutableListOf() }.add(message)
    }
    
    return order.map { label -> MessageGroup(label, groups[label] ?: emptyList()) }
}

@Composable
fun ChatModeContent(
    chatMessages: List<ChatMessage>,
    chatListState: LazyListState,
    notes: List<Note>,
    onNoteClick: (Note) -> Unit,
    onSendChatMessage: (String, List<Attachment>) -> Unit,
    contentPadding: PaddingValues,
    isChatProcessing: Boolean = false,
    isHistoryLoading: Boolean = false,
    agentActivity: AgentActivity? = null,
    failedMessages: List<FailedMessage> = emptyList(),
    onCopyMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    onRetryFailed: (FailedMessage) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val accentColor = LocalAccentColor.current
    
    val isAtLatestMessage by remember {
        derivedStateOf {
            !chatListState.canScrollForward
        }
    }

    // Scroll to latest when new messages arrive (if user was at bottom)
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty() && !chatListState.canScrollBackward) {
            chatListState.animateScrollToItem(0, scrollOffset = -10000)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (isHistoryLoading) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                items(5) { index ->
                    com.example.smarty.ui.components.ChatMessageSkeleton(isFromUser = index % 2 == 0)
                }
            }
        }
        else if (chatMessages.isEmpty() && failedMessages.isEmpty()) {
            ChatEmptyState(modifier = Modifier.fillMaxSize())
        } else {
            val groupedMessages = remember(chatMessages) { groupMessagesByTime(chatMessages) }
            
            LazyColumn(
                state = chatListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.Top
            ) {
                groupedMessages.forEach { group ->
                    if (groupedMessages.size > 1) {
                        item(key = "header_${group.label}") {
                            Text(
                                text = group.label,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 0.5.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 16.dp)
                            )
                        }
                    }
                    
                    items(
                        count = group.messages.size,
                        key = { index -> group.messages[index].id },
                        contentType = { index -> group.messages[index].role }
                    ) { index ->
                        val message = group.messages[index]
                        val prevMessage = group.messages.getOrNull(index - 1)
                        val nextMessage = group.messages.getOrNull(index + 1)

                        val isSameAsPrev = prevMessage?.role == message.role
                        val isSameAsNext = nextMessage?.role == message.role

                        val groupPosition = when {
                            !isSameAsPrev && !isSameAsNext -> MessageGroupPosition.SINGLE
                            !isSameAsPrev && isSameAsNext -> MessageGroupPosition.TOP
                            isSameAsPrev && isSameAsNext -> MessageGroupPosition.MIDDLE
                            isSameAsPrev && !isSameAsNext -> MessageGroupPosition.BOTTOM
                            else -> MessageGroupPosition.SINGLE
                        }

                        val topSpacing = if (index == 0) 0.dp else if (isSameAsPrev) 2.dp else 24.dp

                        val stableGetNote = remember(notes) {
                            { id: String -> notes.find { it.id == id } }
                        }

                        ChatMessageItem(
                            message = message,
                            groupPosition = groupPosition,
                            getNote = stableGetNote,
                            onNoteClick = onNoteClick,
                            onSuggestionClick = { suggestion ->
                                onSendChatMessage(suggestion, emptyList())
                            },
                            onCopyMessage = onCopyMessage,
                            onDeleteMessage = onDeleteMessage,
                            onRegenerateMessage = onRegenerateMessage,
                            modifier = Modifier.padding(top = topSpacing)
                        )
                    }
                }

                if (failedMessages.isNotEmpty()) {
                    item(key = "failed_messages_header") {
                        Text(
                            text = stringResource(R.string.failed_messages),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 16.dp)
                        )
                    }
                    
                    items(
                        items = failedMessages,
                        key = { it.timestamp }
                    ) { failedMessage ->
                        FailedMessageItem(
                            failedMessage = failedMessage,
                            onRetry = { onRetryFailed(failedMessage) },
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp)
                        )
                    }
                }

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

@Composable
private fun FailedMessageItem(
    failedMessage: FailedMessage,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = failedMessage.originalContent.take(50) + if (failedMessage.originalContent.length > 50) "..." else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.retry),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

