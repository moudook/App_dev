package com.example.smarty.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Represents a chat session/conversation.
 * Each session contains multiple messages and can be switched between.
 */
@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val lastMessagePreview: String = "",
    val isActive: Boolean = true  // Currently selected session
)

/**
 * Entity for storing chat messages in the database.
 * Linked to a ChatSession via sessionId.
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val role: String,  // USER, ASSISTANT, SYSTEM
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachmentsJson: String = "[]",  // JSON serialized attachments
    val executedActionsJson: String = "[]",  // JSON serialized actions
    val referencedNoteIds: String = ""  // Comma-separated note IDs
) {
    /**
     * Convert to domain model ChatMessage
     */
    fun toChatMessage(attachments: List<Attachment> = emptyList(), actions: List<AgentActionResult> = emptyList()): ChatMessage {
        return ChatMessage(
            id = id,
            role = ChatRole.valueOf(role),
            content = content,
            attachments = attachments,
            timestamp = timestamp,
            executedActions = actions,
            referencedNoteIds = referencedNoteIds.split(",").filter { it.isNotBlank() }
        )
    }

    companion object {
        /**
         * Create entity from domain model
         */
        fun fromChatMessage(message: ChatMessage, sessionId: String): ChatMessageEntity {
            return ChatMessageEntity(
                id = message.id,
                sessionId = sessionId,
                role = message.role.name,
                content = message.content,
                timestamp = message.timestamp,
                referencedNoteIds = message.referencedNoteIds.joinToString(",")
            )
        }
    }
}

/**
 * Combined data class for UI display
 */
data class ChatSessionWithPreview(
    val session: ChatSession,
    val lastUserMessage: String?,
    val lastAssistantMessage: String?
)
