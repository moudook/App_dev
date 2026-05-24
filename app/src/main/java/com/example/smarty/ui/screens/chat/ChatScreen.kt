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
import com.example.smarty.core.domain.model.ChatRole
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
    onEventClickById: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Collect global state - single source of truth
    val chatState by viewModel.chatState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val pendingApproval by viewModel.pendingApprovalState.collectAsState()
    val messages = chatState.messages
    val isProcessing = chatState.isProcessing
    
    // List state for scroll management
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    // Input text state - initialized once from ViewModel, then local-first
    val initialText = remember { uiState.inputText }
    var inputText by remember { mutableStateOf(initialText) }
    
    // Auto-scroll to bottom when new messages arrive or streaming updates
    val streamingMessage = chatState.streamingMessage
    val hasStreaming = streamingMessage != null
    var prevMessageCount by remember { mutableStateOf(messages.size) }
    LaunchedEffect(messages.size) {
        if (messages.size > prevMessageCount && uiState.isAtLatestMessage) {
            listState.animateScrollToItem(messages.size - 1)
        }
        prevMessageCount = messages.size
    }
    // Keep scrolling during streaming — no animation to avoid jitter on every token
    LaunchedEffect(hasStreaming, streamingMessage?.content?.length) {
        if (hasStreaming && uiState.isAtLatestMessage && messages.size > 0) {
            listState.scrollToItem(messages.size)
        }
    }
    
    // Pre-compute group positions once per list change — avoids O(n²) per recomposition
    val groupPositions by remember {
        derivedStateOf {
            val map = mutableMapOf<String, MessageGroupPosition>()
            messages.forEachIndexed { index, msg ->
                val isUser = msg.role == ChatRole.USER
                val prevSameRole = index > 0 && messages[index - 1].role == msg.role
                val nextSameRole = index < messages.size - 1 && messages[index + 1].role == msg.role
                map[msg.id] = when {
                    !prevSameRole && !nextSameRole -> MessageGroupPosition.SINGLE
                    !prevSameRole && nextSameRole -> MessageGroupPosition.TOP
                    prevSameRole && !nextSameRole -> MessageGroupPosition.BOTTOM
                    else -> MessageGroupPosition.MIDDLE
                }
            }
            map
        }
    }
    
    // Memoize clarification message lookup — avoids full list scan on every recomposition
    val msgWithClarification by remember {
        derivedStateOf {
            messages.find { it.clarificationRequest != null }
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
                    ChatMessageItem(
                        message = message,
                        groupPosition = groupPositions[message.id] ?: MessageGroupPosition.SINGLE,
                        onNoteClick = { onNoteClick(it.id) },
                        onNoteClickById = onNoteClickById,
                        onEventClickById = onEventClickById,
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
                            val clarificationMsg = msgWithClarification
                            if (clarificationMsg != null) {
                                viewModel.onEvent(ChatEvent.ClarificationSubmitted(clarificationMsg.id, response))
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
            // Streaming message rendered as last item — stable list means
            // only this item recomposes on each token
            val sm = chatState.streamingMessage
            if (sm != null) {
                item(key = "streaming") {
                    ChatMessageItem(
                        message = sm,
                        groupPosition = MessageGroupPosition.SINGLE,
                        onNoteClick = { onNoteClick(it.id) },
                        onNoteClickById = onNoteClickById,
                        onEventClickById = onEventClickById,
                        onCopyMessage = {},
                        onDeleteMessage = {},
                        onEditMessage = {},
                        onClarificationSubmit = {},
                        onRegenerateMessage = {},
                        onSuggestionClick = {}
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
        val clarificationMsg = msgWithClarification
        if (clarificationMsg?.clarificationRequest != null) {
            com.example.smarty.ui.components.chat.InteractiveQuestionBlock(
                requests = listOf(clarificationMsg.clarificationRequest!!),
                onSubmit = { response ->
                    viewModel.onEvent(ChatEvent.ClarificationSubmitted(clarificationMsg.id, response))
                },
                onSkip = {
                    viewModel.onEvent(ChatEvent.ClarificationSubmitted(clarificationMsg.id, ""))
                },
                modifier = Modifier.padding(16.dp)
            )
        } else if (pendingApproval != null && pendingApproval!!.toolName == "ask_user") {
            val approval = pendingApproval!!
            val parsedRequests = mutableListOf<com.example.smarty.core.domain.model.ClarificationRequest>()
            
            try {
                val json = org.json.JSONObject(approval.toolArgs)
                val questionsArray = json.optJSONArray("questions")
                
                if (questionsArray != null && questionsArray.length() > 0) {
                    for (i in 0 until questionsArray.length()) {
                        val qObj = questionsArray.getJSONObject(i)
                        val qText = qObj.optString("question", "Please provide input:")
                        val qAllowCustom = qObj.optBoolean("allow_custom", true)
                        val qOptions = mutableListOf<String>()
                        
                        val optArr = qObj.optJSONArray("options")
                        if (optArr != null) {
                            for (j in 0 until optArr.length()) {
                                qOptions.add(optArr.getString(j))
                            }
                        }
                        
                        parsedRequests.add(com.example.smarty.core.domain.model.ClarificationRequest(
                            question = qText,
                            options = qOptions,
                            allowCustomInput = qAllowCustom
                        ))
                    }
                } else {
                    // Fallback to old format if 'questions' array is missing
                    val qText = json.optString("question", "Please provide input:")
                    val qAllowCustom = json.optBoolean("allow_custom", true)
                    val qOptions = mutableListOf<String>()
                    val optArr = json.optJSONArray("options")
                    if (optArr != null) {
                        for (j in 0 until optArr.length()) {
                            qOptions.add(optArr.getString(j))
                        }
                    }
                    parsedRequests.add(com.example.smarty.core.domain.model.ClarificationRequest(
                        question = qText,
                        options = qOptions,
                        allowCustomInput = qAllowCustom
                    ))
                }
            } catch (e: Exception) {
                // Ignore parsing errors and use defaults
                if (parsedRequests.isEmpty()) {
                    parsedRequests.add(com.example.smarty.core.domain.model.ClarificationRequest(
                        question = "Please provide input:",
                        options = emptyList(),
                        allowCustomInput = true
                    ))
                }
            }
            
            com.example.smarty.ui.components.chat.InteractiveQuestionBlock(
                requests = parsedRequests,
                onSubmit = { response ->
                    viewModel.callApproval(approval.toolId, true, response)
                },
                onSkip = {
                    viewModel.callApproval(approval.toolId, false, null)
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
                selectedModel = uiState.selectedModel,
                availableModels = uiState.availableModels,
                onModelSelected = { viewModel.onEvent(ChatEvent.ModelSelected(it)) },
                onRefreshModels = { viewModel.refreshModelsNow() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
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
