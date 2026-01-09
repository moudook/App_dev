package com.example.smarty.agent.tools.base

import com.example.smarty.util.toon.ToonManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Common result types for Jarvis AI Agent tools.
 * These provide structured responses in TOON format for reduced token usage.
 *
 * TOON (Token-Oriented Object Notation) provides 30-60% token savings vs JSON.
 * Format: {key:value|key2:value2|nested:{a:1}}
 */

private val json = Json { encodeDefaults = false }

/**
 * Result for note CRUD operations (create, update, delete, archive).
 */
@Serializable
data class NoteOperationResult(
    val success: Boolean,
    val noteId: String? = null,
    val noteTitle: String? = null,
    val message: String,
    val error: String? = null
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

/**
 * Result for note search operations.
 */
@Serializable
data class NoteSearchResult(
    val success: Boolean,
    val notes: List<NoteInfo>,
    val totalCount: Int,
    val message: String
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

/**
 * Simplified note info for search results (privacy-safe).
 * Includes content so AI can read and understand note details.
 * Private notes are already filtered out before this is created.
 *
 * IMPORTANT: The 'id' is for INTERNAL tool use only (update/delete operations).
 * AI should NEVER expose IDs to users - present notes by title/content instead.
 */
@Serializable
data class NoteInfo(
    val id: String,  // Internal use only - never show to user
    val title: String,
    val content: String,  // Full note content for AI access
    val summary: String?,
    val category: String?,
    val type: String,
    val createdAt: Long
)

/**
 * Result for web search operations.
 */
@Serializable
data class WebSearchResult(
    val success: Boolean,
    val query: String,
    val reason: String,
    val aiSummary: String? = null,
    val results: List<WebResult> = emptyList(),
    val totalResults: Int = 0,
    val error: String? = null
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

/**
 * Individual web search result.
 */
@Serializable
data class WebResult(
    val title: String,
    val url: String,
    val snippet: String
)

/**
 * Result for audio playback operations.
 *
 * FALLBACK BEHAVIOR: When `shouldFallbackToAI` is true and `success` is false,
 * the AI agent should generate a graceful, conversational response instead of
 * showing the raw error message. This maintains decorum when audio isn't available.
 *
 * Example fallback responses:
 * - "I couldn't find that audio, but I'd be happy to help you save some music!"
 * - "No matching audio found. Would you like me to search your notes instead?"
 */
@Serializable
data class AudioPlaybackResult(
    val success: Boolean,
    val action: String,
    val trackTitle: String? = null,
    val message: String,
    val error: String? = null,
    val availableAudio: List<String>? = null,  // List of available audio filenames when search fails
    val shouldFallbackToAI: Boolean = false  // When true, AI should respond gracefully instead of showing error
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

/**
 * Result for todo operations.
 */
@Serializable
data class TodoOperationResult(
    val success: Boolean,
    val noteId: String? = null,
    val todoId: String? = null,
    val message: String,
    val error: String? = null
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

/**
 * Result for category operations.
 */
@Serializable
data class CategoryResult(
    val success: Boolean,
    val categories: List<CategoryInfo> = emptyList(),
    val message: String
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

/**
 * Category info for listing.
 */
@Serializable
data class CategoryInfo(
    val name: String,
    val noteCount: Int
)

/**
 * Result for summarize operations.
 */
@Serializable
data class SummarizeResult(
    val success: Boolean,
    val noteId: String,
    val title: String,
    val summary: String?,
    val content: String,
    val error: String? = null
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

/**
 * Information about a single image attachment.
 * Used INTERNALLY by ViewImageTool - NOT serialized to AI response.
 * Contains sensitive URIs that should not be exposed.
 */
data class ImageInfo(
    val uri: String,
    val fileName: String,
    val mimeType: String,
    val index: Int,
    val attachmentId: String? = null
)

/**
 * Result for image display operations.
 * Used by ViewImageTool to report found images.
 *
 * SECURITY: Does NOT include URIs or file paths - these are internal.
 * The AI only sees success status, note title, and image count.
 * Actual images are displayed via the onDisplayImages callback.
 */
@Serializable
data class ImageDisplayResult(
    val success: Boolean,
    val noteTitle: String? = null,
    val imageCount: Int = 0,
    val message: String,
    val error: String? = null
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}
