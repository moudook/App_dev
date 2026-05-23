package com.example.smarty.features.chat.domain.state

import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.ui.components.ConnectionStatus

/**
 * Global state holder for chat feature.
 * Encapsulates all chat-related state in a single immutable data class.
 *
 * Principles:
 * - Immutable state (copy-on-write)
 * - Single source of truth
 * - Separated UI state from domain state
 */
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val streamingMessage: ChatMessage? = null,
    val currentSessionId: String? = null,
    val isChatMode: Boolean = false,
    val isProcessing: Boolean = false,
    val isListening: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTED,
    val activeMessageId: String? = null,
    val scrollPosition: Int = 0,
    val pendingAttachments: List<Attachment> = emptyList(),
    val errorMessage: String? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
) {
    val hasMessages: Boolean = messages.isNotEmpty()
    val canScroll: Boolean = messages.size > 5
    val isEmpty: Boolean = messages.isEmpty()

    companion object {
        fun initial() = ChatState()
    }
}
