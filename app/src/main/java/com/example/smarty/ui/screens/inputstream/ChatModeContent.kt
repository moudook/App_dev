package com.example.smarty.ui.screens.inputstream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.Note
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
    modifier: Modifier = Modifier
) {
    if (chatMessages.isEmpty()) {
        ChatEmptyState(modifier = modifier.fillMaxSize())
    } else {
        LazyColumn(
            state = chatListState,
            modifier = modifier.fillMaxSize(),
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
        }
    }
}
