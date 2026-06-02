package com.example.smarty.features.chat.domain.mapper

import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.ChatRole
import com.example.smarty.features.chat.domain.state.ChatState

object ChatMessageMapper {
    fun cleanContent(content: String): String = content

    fun extractThinking(content: String): String? = null

    fun hasPartialThinkTag(content: String): Boolean = false

    fun isMessageComplete(content: String): Boolean = true

    fun formatForDisplay(message: ChatMessage): String =
        if (message.role == ChatRole.USER) {
            message.content
        } else {
            cleanContent(message.content)
        }

    fun calculateDisplayPosition(
        messageId: String,
        fullText: String,
        isStreaming: Boolean,
        previousPosition: Int = 0,
    ): Int =
        if (isStreaming) {
            val charsPerFrame = 2
            val remaining = fullText.length - previousPosition
            minOf(previousPosition + charsPerFrame, fullText.length)
        } else {
            fullText.length
        }

    fun getVisibleText(
        fullText: String,
        displayPosition: Int,
    ): String =
        if (displayPosition >= fullText.length) {
            fullText
        } else {
            fullText.substring(0, displayPosition)
        }

    fun addMessageToState(
        currentState: ChatState,
        message: ChatMessage,
    ): ChatState =
        currentState.copy(
            messages = currentState.messages + message,
            lastUpdated = System.currentTimeMillis(),
        )

    fun updateMessageInState(
        currentState: ChatState,
        messageId: String,
        content: String,
        thinking: String? = null,
    ): ChatState {
        val updatedMessages =
            currentState.messages.map { msg ->
                if (msg.id == messageId) {
                    msg.copy(
                        content = content,
                        thinking = null,
                        isStreaming = msg.isStreaming,
                    )
                } else {
                    msg
                }
            }

        return currentState.copy(
            messages = updatedMessages,
            lastUpdated = System.currentTimeMillis(),
        )
    }

    fun markMessageCompleteInState(
        currentState: ChatState,
        messageId: String,
    ): ChatState {
        val updatedMessages =
            currentState.messages.map { msg ->
                if (msg.id == messageId) {
                    msg.copy(isStreaming = false)
                } else {
                    msg
                }
            }

        return currentState.copy(
            messages = updatedMessages,
            lastUpdated = System.currentTimeMillis(),
        )
    }
}
