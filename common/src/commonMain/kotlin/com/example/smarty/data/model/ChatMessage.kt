package com.example.smarty.core.domain.model

// import java.util.UUID

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Role in a chat conversation
 */
@Serializable
enum class ChatRole {
    @SerialName("user")
    USER,
    @SerialName("assistant")
    SMARTY,
    @SerialName("system")
    SYSTEM
}

/**
 * Result of an agent action execution
 */
data class AgentActionResult(
    val action: String,
    val success: Boolean,
    val resultSummary: String,
    val affectedNoteIds: List<String> = emptyList()
)

/**
 * Citation/source from web research
 */
data class Citation(
    val title: String,
    val url: String,
    val snippet: String
)

/**
 * Image to display inline in chat message.
 * Used by ViewImageTool to show images from notes in AI responses.
 */
data class InlineChatImage(
    val uri: String,
    val fileName: String,
    val noteTitle: String
)

/**
 * A single message in the chat conversation
 */
data class ChatMessage(
    val id: String, // = UUID.randomUUID().toString(),
    val role: ChatRole,
    val content: String,
    val attachments: List<Attachment> = emptyList(),
    val timestamp: Long = 0L, // System.currentTimeMillis(),
    val executedActions: List<AgentActionResult> = emptyList(),
    val referencedNoteIds: List<String> = emptyList(),
    val isAudioRelated: Boolean = false,  // True when user asked about audio/music playback
    val suggestions: List<String> = emptyList(),  // AI-provided suggestions (max 2)
    val isError: Boolean = false,  // True when this message represents an API error
    val isStreaming: Boolean = false,  // True when AI response is still being received
    val citations: List<Citation> = emptyList(),  // Sources from web research
    val inlineImages: List<InlineChatImage> = emptyList(),  // Images from ViewImageTool to display inline
    val clarificationRequest: ClarificationRequest? = null  // Interactive clarification request
) {
    /**
     * Check if this is a user message
     */
    val isUser: Boolean get() = role == ChatRole.USER

    /**
     * Check if this is a Smarty response
     */
    val isSmarty: Boolean get() = role == ChatRole.SMARTY

    /**
     * Check if this is a system message
     */
    val isSystem: Boolean get() = role == ChatRole.SYSTEM

    /**
     * Check if any actions were executed
     */
    val hasActions: Boolean get() = executedActions.isNotEmpty()

    /**
     * Check if all actions succeeded
     */
    val allActionsSucceeded: Boolean get() = executedActions.all { it.success }

    /**
     * Check if suggestions should be shown (not an error and has suggestions)
     */
    val hasSuggestions: Boolean get() = !isError && suggestions.isNotEmpty()

    /**
     * Check if citations/sources are available
     */
    val hasCitations: Boolean get() = citations.isNotEmpty()

    /**
     * Check if inline images are available to display
     */
    val hasInlineImages: Boolean get() = inlineImages.isNotEmpty()
}
