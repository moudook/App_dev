package com.example.smarty.features.chat.domain.usecase

import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.ChatRole
import com.example.smarty.data.repository.ChatRepository
import kotlinx.coroutines.flow.Flow

/**
 * Use Case: Send a chat message.
 *
 * Single Responsibility: Only handles sending user messages.
 * Encapsulates business logic for message creation and persistence.
 */
class SendMessageUseCase(
    private val chatRepository: ChatRepository,
) {
    /**
     * Execute: Create and save a user message.
     *
     * @param sessionId The chat session ID
     * @param content The message content
     * @param attachments Optional attachments
     * @return The created ChatMessage
     */
    suspend fun execute(
        sessionId: String,
        content: String,
        attachments: List<com.example.smarty.core.domain.model.Attachment> = emptyList(),
    ): ChatMessage {
        require(content.isNotBlank() || attachments.isNotEmpty()) {
            "Message must have content or attachments"
        }

        val message =
            ChatMessage(
                id =
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                role = ChatRole.USER,
                content = content,
                timestamp = System.currentTimeMillis(),
                attachments = attachments,
            )

        chatRepository.saveMessage(sessionId, message)
        return message
    }
}

/**
 * Use Case: Update a chat message.
 *
 * Single Responsibility: Only handles message updates.
 */
class UpdateMessageUseCase(
    private val chatRepository: ChatRepository,
) {
    /**
     * Execute: Update message content by ID.
     */
    suspend fun execute(
        sessionId: String,
        messageId: String,
        content: String,
        thinking: String? = null,
        isStreaming: Boolean = true,
    ) {
        // Get existing message and update it
        val existingMessage = chatRepository.getMessageById(messageId)
        if (existingMessage != null) {
            val updatedMessage =
                existingMessage.copy(
                    content = content,
                    thinking = null,
                    isStreaming = isStreaming,
                )
            chatRepository.saveMessage(sessionId, updatedMessage)
        }
    }
}

/**
 * Use Case: Get chat messages flow.
 *
 * Single Responsibility: Only provides message stream.
 */
class GetMessagesUseCase(
    private val chatRepository: ChatRepository,
) {
    /**
     * Execute: Get flow of messages for a session.
     */
    fun execute(sessionId: String): kotlinx.coroutines.flow.Flow<List<ChatMessage>> = chatRepository.getMessagesForSession(sessionId)

    /**
     * Execute: Get all messages for a session (one-time).
     */
    suspend fun getAllMessages(sessionId: String): List<ChatMessage> = chatRepository.getMessagesForSessionOnce(sessionId)
}

/**
 * Use Case: Clear chat messages.
 *
 * Single Responsibility: Only handles message cleanup.
 */
class ClearMessagesUseCase(
    private val chatRepository: ChatRepository,
) {
    /**
     * Execute: Clear all messages (not directly supported, delete individually).
     */
    suspend fun execute(sessionId: String) {
        // Clear by getting all messages and deleting them
        val messages = chatRepository.getMessagesForSessionOnce(sessionId)
        messages.forEach { message ->
            chatRepository.deleteMessage(message.id)
        }
    }
}

/**
 * Use Case: Delete a chat message.
 *
 * Single Responsibility: Only handles message deletion.
 */
class DeleteMessageUseCase(
    private val chatRepository: ChatRepository,
) {
    /**
     * Execute: Delete a specific message.
     */
    suspend fun execute(messageId: String): Boolean = chatRepository.deleteMessage(messageId)
}

/**
 * Use Case: Delete a chat message by session.
 *
 * Single Responsibility: Only handles message deletion by session.
 */
class DeleteMessageBySessionUseCase(
    private val chatRepository: ChatRepository,
) {
    /**
     * Execute: Delete a specific message from a session.
     */
    suspend fun execute(
        sessionId: String,
        messageId: String,
    ): Boolean = chatRepository.deleteMessage(messageId)
}
