package com.example.smarty.data.model

// import java.util.UUID

/**
 * Role in a chat conversation
 */
enum class ChatRole {
    USER,
    ASSISTANT,
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
    val suggestions: List<String> = emptyList(),  // AI-provided suggestions (max 2, from TOON response)
    val isError: Boolean = false,  // True when this message represents an API error
    val citations: List<Citation> = emptyList(),  // Sources from web research
    val inlineImages: List<InlineChatImage> = emptyList(),  // Images from ViewImageTool to display inline
    val clarificationRequest: ClarificationRequest? = null,  // Interactive clarification request
    val thinkingContent: String? = null  // Reasoning process from thinking-enabled models (Falcon-H1R-7B)
) {
    /**
     * Check if this is a user message
     */
    val isUser: Boolean get() = role == ChatRole.USER

    /**
     * Check if this is an assistant message
     */
    val isAssistant: Boolean get() = role == ChatRole.ASSISTANT

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

    /**
     * Check if thinking/reasoning content is available
     */
    val hasThinking: Boolean get() = !thinkingContent.isNullOrBlank()
}
