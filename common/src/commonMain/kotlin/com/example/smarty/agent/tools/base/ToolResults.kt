package com.example.smarty.agent.tools.base

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Common result types for Smarty AI Agent tools.
 */

/**
 * Result for note CRUD operations.
 */
@Serializable
data class NoteOperationResult(
    val success: Boolean,
    val noteId: String? = null,
    val noteTitle: String? = null,
    val message: String,
    val error: String? = null
)

/**
 * Result for note search operations.
 */
@Serializable
data class NoteSearchResult(
    val success: Boolean,
    val notes: List<NoteInfo>,
    val totalCount: Int,
    val message: String
)

/**
 * Simplified note info for search results.
 */
@Serializable
data class NoteInfo(
    val id: String,
    val title: String,
    val content: String,
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
)

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
    val error: String? = null,
    val availableAudio: List<String>? = null,
    val shouldFallbackToAI: Boolean = false
)

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
)

/**
 * Result for category operations.
 */
@Serializable
data class CategoryResult(
    val success: Boolean,
    val categories: List<CategoryInfo> = emptyList(),
    val message: String
)

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
)

/**
 * Result for image display operations.
 */
@Serializable
data class ImageDisplayResult(
    val success: Boolean,
    val noteTitle: String? = null,
    val imageCount: Int = 0,
    val message: String,
    val error: String? = null
)
