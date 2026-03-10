package com.example.smarty.features.chat.domain.mapper

import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.ChatRole
import com.example.smarty.features.chat.domain.state.ChatState

/**
 * ChatMessageMapper - Transforms chat messages for UI presentation.
 * 
 * Single Responsibility: Only handles message transformations.
 * DRY: Centralized message cleaning and formatting logic.
 */
object ChatMessageMapper {
    
    // Pre-compiled regex for THINK tag removal
    private val THINK_TAG_REGEX = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    private val THINK_OPEN_REGEX = Regex("<think>.*", RegexOption.DOT_MATCHES_ALL)
    private val PARTIAL_FINAL_REGEX = Regex("<fi?n?a?l?$")
    private val PARTIAL_THINK_REGEX = Regex("<th?i?n?k?$")
    
    /**
     * Clean message content by removing THINK tags.
     */
    fun cleanContent(content: String): String {
        return THINK_TAG_REGEX.replace(content, "").trim()
    }
    
    /**
     * Extract thinking content from message.
     */
    fun extractThinking(content: String): String? {
        val match = THINK_TAG_REGEX.find(content)
        return match?.value?.let {
            it.removePrefix("<think>").removeSuffix("</think>").trim()
        }
    }
    
    /**
     * Check if message has partial THINK tag (still streaming).
     */
    fun hasPartialThinkTag(content: String): Boolean {
        return PARTIAL_THINK_REGEX.containsMatchIn(content) || PARTIAL_FINAL_REGEX.containsMatchIn(content)
    }
    
    /**
     * Check if message is complete (no partial tags).
     */
    fun isMessageComplete(content: String): Boolean {
        return !hasPartialThinkTag(content) && !THINK_OPEN_REGEX.containsMatchIn(content)
    }
    
    /**
     * Format message for display based on role.
     */
    fun formatForDisplay(message: ChatMessage): String {
        return if (message.role == ChatRole.USER) {
            message.content
        } else {
            cleanContent(message.content)
        }
    }
    
    /**
     * Calculate display position for typewriter animation.
     */
    fun calculateDisplayPosition(
        messageId: String,
        fullText: String,
        isStreaming: Boolean,
        previousPosition: Int = 0
    ): Int {
        return if (isStreaming) {
            val charsPerFrame = 2
            val remaining = fullText.length - previousPosition
            minOf(previousPosition + charsPerFrame, fullText.length)
        } else {
            fullText.length
        }
    }
    
    /**
     * Get visible text for typewriter effect.
     */
    fun getVisibleText(
        fullText: String,
        displayPosition: Int
    ): String {
        return if (displayPosition >= fullText.length) {
            fullText
        } else {
            fullText.substring(0, displayPosition)
        }
    }
    
    /**
     * Update chat state with new message.
     */
    fun addMessageToState(
        currentState: ChatState,
        message: ChatMessage
    ): ChatState {
        return currentState.copy(
            messages = currentState.messages + message,
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    /**
     * Update existing message in state.
     */
    fun updateMessageInState(
        currentState: ChatState,
        messageId: String,
        content: String,
        thinking: String? = null
    ): ChatState {
        val updatedMessages = currentState.messages.map { msg ->
            if (msg.id == messageId) {
                msg.copy(
                    content = content,
                    thinking = thinking ?: msg.thinking,
                    isStreaming = msg.isStreaming
                )
            } else {
                msg
            }
        }
        
        return currentState.copy(
            messages = updatedMessages,
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    /**
     * Mark message as complete in state.
     */
    fun markMessageCompleteInState(
        currentState: ChatState,
        messageId: String
    ): ChatState {
        val updatedMessages = currentState.messages.map { msg ->
            if (msg.id == messageId) {
                msg.copy(isStreaming = false)
            } else {
                msg
            }
        }
        
        return currentState.copy(
            messages = updatedMessages,
            lastUpdated = System.currentTimeMillis()
        )
    }
}
