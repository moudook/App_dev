package com.example.smarty.data.model

import java.util.UUID

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
 * A single message in the chat conversation
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val content: String,
    val attachments: List<Attachment> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val executedActions: List<AgentActionResult> = emptyList(),
    val referencedNoteIds: List<String> = emptyList(),
    val isAudioRelated: Boolean = false  // True when user asked about audio/music playback
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
}
