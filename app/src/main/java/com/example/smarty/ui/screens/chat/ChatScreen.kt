package com.example.smarty.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.features.chat.domain.ChatViewModel
import com.example.smarty.features.chat.domain.event.ChatEvent
import com.example.smarty.features.chat.domain.state.ChatState
import com.example.smarty.features.chat.domain.state.ChatUiState
import com.example.smarty.ui.components.ChatMessageItem
import com.example.smarty.ui.components.MessageGroupPosition
import com.example.smarty.ui.components.SmartyInputField
import com.example.smarty.ui.components.chat.ChatEmptyState
import kotlinx.coroutines.launch

/**
 * Main Chat Screen - Demonstrates complete SDE architecture.
 * 
 * Principles applied:
 * - **Global State**: Observes ChatState and ChatUiState flows
 * - **Event-Driven**: All user interactions go through ChatEvent
 * - **Single Responsibility**: UI only handles presentation
 * - **DRY**: Uses extracted components (ChatMessageItem, SmartyInputField)
 * 
 * @param viewModel The ChatViewModel providing state and handling events
 * @param onNoteClick Callback when a referenced note is clicked
 * @param onAttachmentClick Callback when an attachment is clicked
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNoteClick: (String) -> Unit = {},
    onAttachmentClick: (String) -> Unit = {},
    onNoteClickById: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Collect global state - single source of truth
    val chatState by viewModel.chatState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val messages = chatState.messages
    val isProcessing = chatState.isProcessing
    
    // List state for scroll management
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    // Input text state - synced with UI state
    var inputText by remember { mutableStateOf(uiState.inputText) }
    
    // Sync input text with UI state changes
    LaunchedEffect(uiState.inputText) {
        inputText = uiState.inputText
    }
    
    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && uiState.isAtLatestMessage) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    // Handle scroll position changes
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            val isAtLatest = lastVisibleItem?.index == messages.size - 1
            viewModel.onEvent(
                ChatEvent.ScrollPositionChanged(
                    position = listState.firstVisibleItemIndex,
                    isAtLatest = isAtLatest
                )
            )
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // Messages list
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    ChatEmptyState(
                        modifier = Modifier.fillParentMaxSize()
                    )
                }
            } else {
                items(
                    items = messages,
                    key = { message -> message.id }
                ) { message ->
                    val groupPosition = calculateGroupPosition(message, messages)
                    
                    ChatMessageItem(
                        message = message,
                        groupPosition = groupPosition,
                        onNoteClick = { onNoteClick(it.id) },
                        onNoteClickById = { noteId -> onNoteClickById(noteId) },
                        onCopyMessage = { content ->
                            viewModel.onEvent(ChatEvent.MessageCopied(message.id, content))
                        },
                        onDeleteMessage = {
                            viewModel.onEvent(ChatEvent.MessageDeleted(message.id))
                        },
                        onEditMessage = { editedMessage ->
                            viewModel.onEvent(ChatEvent.MessageEdited(editedMessage))
                        },
                        onClarificationSubmit = { response ->
                            // Find the message with clarification request and submit response
                            val msgWithClarification = messages.find { it.clarificationRequest != null }
                            if (msgWithClarification != null) {
                                viewModel.onEvent(ChatEvent.ClarificationSubmitted(msgWithClarification.id, response))
                            }
                        },
                        onRegenerateMessage = {
                            // Handle regeneration
                        },
                        onSuggestionClick = { suggestion ->
                            viewModel.onEvent(ChatEvent.SuggestionClicked(suggestion))
                        }
                        // modifier = Modifier.animateItemPlacement() // Removed experimental API
                    )
                }
            }
        }
        
        // Scroll to bottom button (shown when not at latest)
        if (!uiState.isAtLatestMessage && messages.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                FilledTonalIconButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Text("↓")
                }
            }
        }
        
        // Input area: If there's an active clarification request, show the interactive question block
        val msgWithClarification = messages.find { it.clarificationRequest != null }
        if (msgWithClarification?.clarificationRequest != null) {
            com.example.smarty.ui.components.chat.InteractiveQuestionBlock(
                request = msgWithClarification.clarificationRequest!!,
                onSubmit = { response ->
                    viewModel.onEvent(ChatEvent.ClarificationSubmitted(msgWithClarification.id, response))
                },
                onSkip = {
                    viewModel.onEvent(ChatEvent.ClarificationSubmitted(msgWithClarification.id, ""))
                },
                modifier = Modifier.padding(16.dp)
            )
        } else {
            // Standard Input field
            SmartyInputField(
            value = inputText,
            onValueChange = { 
                inputText = it
                viewModel.onEvent(ChatEvent.InputTextChanged(it))
            },
            onSubmit = {
                if (inputText.text.isNotBlank()) {
                    viewModel.onEvent(ChatEvent.MessageSent(inputText.text))
                    inputText = androidx.compose.ui.text.input.TextFieldValue("")
                }
            },
            isChatMode = true,
            isProcessing = isProcessing,
            isAgentWorking = isProcessing,
            onStopGeneration = { viewModel.onEvent(ChatEvent.GenerationStopped) },
            onStartVoiceInput = { viewModel.onEvent(ChatEvent.VoiceInputStarted) },
            onStopVoiceInput = { viewModel.onEvent(ChatEvent.VoiceInputStopped) },
            chatPlaceholder = "Ask anything...",
            showHistoryOption = true,
            onOpenChatHistory = { viewModel.onEvent(ChatEvent.ChatHistoryRequested) },
            onNewChat = { viewModel.onEvent(ChatEvent.NewChatRequested) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        }
    }
}

/**
 * Calculate message group position for bubble styling.
 * Groups consecutive messages from the same role.
 */
private fun calculateGroupPosition(
    message: ChatMessage,
    messages: List<ChatMessage>
): MessageGroupPosition {
    val index = messages.indexOfFirst { it.id == message.id }
    
    if (index == -1) return MessageGroupPosition.SINGLE
    
    val isUser = message.role == com.example.smarty.core.domain.model.ChatRole.USER
    val prevSameRole = if (index > 0) messages[index - 1].role == message.role else false
    val nextSameRole = if (index < messages.size - 1) messages[index + 1].role == message.role else false
    
    return when {
        !prevSameRole && !nextSameRole -> MessageGroupPosition.SINGLE
        !prevSameRole && nextSameRole -> MessageGroupPosition.TOP
        prevSameRole && !nextSameRole -> MessageGroupPosition.BOTTOM
        else -> MessageGroupPosition.MIDDLE
    }
}

/**
 * Chat Screen Preview
 */
@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ChatScreenPreview() {
    MaterialTheme {
        // In production, provide actual ViewModel
        // ChatScreen(viewModel = viewModel())
        Box(modifier = Modifier.fillMaxSize()) {
            Text("Preview requires ViewModel")
        }
    }
}
