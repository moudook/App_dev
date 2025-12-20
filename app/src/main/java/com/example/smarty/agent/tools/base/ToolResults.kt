package com.example.smarty.agent.tools.base

import kotlinx.serialization.Serializable

/**
 * Common result types for Cogni AI Agent tools.
 * These provide structured responses that the LLM can understand and process.
 *
 * Note: The results override toString() to provide LLM-friendly text output
 * since Koog's ToolResult.TextSerializable interface may not be available.
 */

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
    override fun toString(): String = buildString {
        if (success) {
            append("SUCCESS: $message")
            noteTitle?.let { append(" (Note: '$it')") }
        } else {
            append("FAILED: $message")
            error?.let { append(" - $it") }
        }
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
    override fun toString(): String = buildString {
        if (!success || notes.isEmpty()) {
            append(message)
        } else {
            appendLine("Found $totalCount notes:")
            notes.forEach { note ->
                appendLine("- [${note.id}] ${note.title} (${note.category ?: "Uncategorized"})")
                note.summary?.let { appendLine("  Summary: ${it.take(100)}...") }
            }
        }
    }
}

/**
 * Simplified note info for search results (privacy-safe).
 */
@Serializable
data class NoteInfo(
    val id: String,
    val title: String,
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
    override fun toString(): String = buildString {
        if (!success) {
            append("Search failed: ${error ?: "Unknown error"}")
            return@buildString
        }
        appendLine("Web search for '$query' ($reason):")
        aiSummary?.let { appendLine("AI Summary: $it") }
        if (results.isNotEmpty()) {
            appendLine("Results:")
            results.forEach { result ->
                appendLine("- ${result.title}: ${result.snippet}")
                appendLine("  URL: ${result.url}")
            }
        }
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
 */
@Serializable
data class AudioPlaybackResult(
    val success: Boolean,
    val action: String,
    val trackTitle: String? = null,
    val message: String,
    val error: String? = null
) {
    override fun toString(): String = buildString {
        if (success) {
            append("SUCCESS: $message")
            trackTitle?.let { append(" - Now playing: $it") }
        } else {
            append("FAILED: $message")
            error?.let { append(" - $it") }
        }
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
    override fun toString(): String = buildString {
        if (success) {
            append("SUCCESS: $message")
        } else {
            append("FAILED: $message")
            error?.let { append(" - $it") }
        }
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
    override fun toString(): String = buildString {
        if (!success || categories.isEmpty()) {
            append(message)
        } else {
            appendLine("Categories:")
            categories.forEach { cat ->
                appendLine("- ${cat.name}: ${cat.noteCount} notes")
            }
        }
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
    override fun toString(): String = buildString {
        if (!success) {
            append("Failed to get note: ${error ?: "Unknown error"}")
            return@buildString
        }
        appendLine("Note: $title")
        summary?.let { appendLine("Summary: $it") }
        appendLine("Content:")
        append(content.take(2000))
        if (content.length > 2000) append("... (truncated)")
    }
}
