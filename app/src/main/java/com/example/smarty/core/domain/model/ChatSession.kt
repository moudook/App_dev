package com.example.smarty.core.domain.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Represents a chat session/conversation.
 * Each session contains multiple messages and can be switched between.
 *
 * CONVERSATION SUMMARIES:
 * Sessions can have AI-generated summaries that capture:
 * - Main topics discussed
 * - Actions taken (notes created, todos added, etc.)
 * - Key user preferences revealed
 *
 * Summaries are used to provide context in future conversations
 * without including full message history.
 */
@Entity(
    tableName = "chat_sessions",
    indices = [
        Index(value = ["updatedAt"]),
        Index(value = ["isActive"])
    ]
)
data class ChatSession(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val lastMessagePreview: String = "",
    val isActive: Boolean = true,  // Currently selected session

    /**
     * AI-generated summary of the conversation.
     * Contains abstract description of topics discussed and actions taken.
     * Used for providing context in future conversations.
     *
     * PRIVACY: Never contains raw private note content.
     */
    val summary: String? = null,

    /**
     * When the summary was generated.
     * Used to determine if summary needs updating.
     */
    val summaryGeneratedAt: Long? = null
)

/**
 * Entity for storing chat messages in the database.
 * Linked to a ChatSession via sessionId.
 */
@Entity(
    tableName = "chat_messages",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["timestamp"]),
        Index(value = ["sessionId", "timestamp"])
    ]
)
data class ChatMessageEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val role: String,  // USER, ASSISTANT, SYSTEM
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachmentsJson: String = "[]",  // JSON serialized attachments
    val executedActionsJson: String = "[]",  // JSON serialized actions
    val referencedNoteIds: String = "",  // Comma-separated note IDs
    val citationsJson: String = "[]",  // JSON serialized citations from web search
    val inlineImagesJson: String = "[]"  // JSON serialized inline images from ViewImageTool
) {
    /**
     * Convert to domain model ChatMessage
     */
    fun toChatMessage(attachments: List<Attachment> = emptyList(), actions: List<AgentActionResult> = emptyList()): ChatMessage {
        // Parse citations from JSON
        val citations = try {
            if (citationsJson.isNotBlank() && citationsJson != "[]") {
                parseCitationsJson(citationsJson)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }

        // Parse inline images from JSON
        val inlineImages: List<InlineChatImage> = try {
            if (inlineImagesJson.isNotBlank() && inlineImagesJson != "[]") {
                Companion.parseInlineImagesJson(inlineImagesJson)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }

        return ChatMessage(
            id = id,
            role = ChatRole.valueOf(role),
            content = content,
            attachments = attachments,
            timestamp = timestamp,
            executedActions = actions,
            referencedNoteIds = referencedNoteIds.split(",").filter { it.isNotBlank() },
            citations = citations,
            inlineImages = inlineImages
        )
    }

    companion object {
        /**
         * Create entity from domain model
         */
        fun fromChatMessage(message: ChatMessage, sessionId: String): ChatMessageEntity {
            // Serialize citations to JSON
            val citationsJson = if (message.citations.isNotEmpty()) {
                serializeCitationsToJson(message.citations)
            } else {
                "[]"
            }

            // Serialize inline images to JSON
            val inlineImagesJson = if (message.inlineImages.isNotEmpty()) {
                Companion.serializeInlineImagesToJson(message.inlineImages)
            } else {
                "[]"
            }

            return ChatMessageEntity(
                id = message.id,
                sessionId = sessionId,
                role = message.role.name,
                content = message.content,
                timestamp = message.timestamp,
                referencedNoteIds = message.referencedNoteIds.joinToString(","),
                citationsJson = citationsJson,
                inlineImagesJson = inlineImagesJson
            )
        }

        /**
         * Parse citations from JSON string
         */
        private fun parseCitationsJson(json: String): List<Citation> {
            // Simple JSON parsing without external library
            val citations = mutableListOf<Citation>()
            try {
                // Remove outer brackets and split by "},{"
                val trimmed = json.trim().removePrefix("[").removeSuffix("]")
                if (trimmed.isBlank()) return emptyList()

                // Split by },{ but keep track of nested braces
                val items = mutableListOf<String>()
                var depth = 0
                var start = 0
                for (i in trimmed.indices) {
                    when (trimmed[i]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                items.add(trimmed.substring(start, i + 1).trim().removePrefix(",").trim())
                                start = i + 1
                            }
                        }
                    }
                }

                for (item in items) {
                    val obj = item.removePrefix("{").removeSuffix("}")
                    var title = ""
                    var url = ""
                    var snippet = ""

                    // Parse each field
                    val regex = """"(\w+)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""".toRegex()
                    regex.findAll(obj).forEach { match ->
                        val (key, value) = match.destructured
                        val unescaped = value.replace("\\\"", "\"").replace("\\\\", "\\")
                        when (key) {
                            "title" -> title = unescaped
                            "url" -> url = unescaped
                            "snippet" -> snippet = unescaped
                        }
                    }

                    if (title.isNotBlank() || url.isNotBlank()) {
                        citations.add(Citation(title = title, url = url, snippet = snippet))
                    }
                }
            } catch (e: Exception) {
                // Return empty on parse error
            }
            return citations
        }

        /**
         * Serialize citations to JSON string
         */
        private fun serializeCitationsToJson(citations: List<Citation>): String {
            if (citations.isEmpty()) return "[]"

            val items = citations.map { citation ->
                val title = citation.title.replace("\\", "\\\\").replace("\"", "\\\"")
                val url = citation.url.replace("\\", "\\\\").replace("\"", "\\\"")
                val snippet = citation.snippet.replace("\\", "\\\\").replace("\"", "\\\"")
                """{"title":"$title","url":"$url","snippet":"$snippet"}"""
            }
            return "[${items.joinToString(",")}]"
        }

        /**
         * Parse inline images from JSON string
         */
        private fun parseInlineImagesJson(json: String): List<InlineChatImage> {
            val images = mutableListOf<InlineChatImage>()
            try {
                val trimmed = json.trim().removePrefix("[").removeSuffix("]")
                if (trimmed.isBlank()) return emptyList()

                // Split by },{ but keep track of nested braces
                val items = mutableListOf<String>()
                var depth = 0
                var start = 0
                for (i in trimmed.indices) {
                    when (trimmed[i]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                items.add(trimmed.substring(start, i + 1).trim().removePrefix(",").trim())
                                start = i + 1
                            }
                        }
                    }
                }

                for (item in items) {
                    val obj = item.removePrefix("{").removeSuffix("}")
                    var uri = ""
                    var fileName = ""
                    var noteTitle = ""

                    val regex = """"(\w+)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""".toRegex()
                    regex.findAll(obj).forEach { match ->
                        val (key, value) = match.destructured
                        val unescaped = value.replace("\\\"", "\"").replace("\\\\", "\\")
                        when (key) {
                            "uri" -> uri = unescaped
                            "fileName" -> fileName = unescaped
                            "noteTitle" -> noteTitle = unescaped
                        }
                    }

                    if (uri.isNotBlank()) {
                        images.add(InlineChatImage(uri = uri, fileName = fileName, noteTitle = noteTitle))
                    }
                }
            } catch (e: Exception) {
                // Return empty on parse error
            }
            return images
        }

        /**
         * Serialize inline images to JSON string
         */
        private fun serializeInlineImagesToJson(images: List<InlineChatImage>): String {
            if (images.isEmpty()) return "[]"

            val items = images.map { image ->
                val uri = image.uri.replace("\\", "\\\\").replace("\"", "\\\"")
                val fileName = image.fileName.replace("\\", "\\\\").replace("\"", "\\\"")
                val noteTitle = image.noteTitle.replace("\\", "\\\\").replace("\"", "\\\"")
                """{"uri":"$uri","fileName":"$fileName","noteTitle":"$noteTitle"}"""
            }
            return "[${items.joinToString(",")}]"
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
