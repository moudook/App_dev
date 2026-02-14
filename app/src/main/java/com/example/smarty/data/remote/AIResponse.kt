package com.example.smarty.data.remote

import kotlinx.serialization.Serializable

/**
 * Standard AI Response model used throughout the app.
 * Represents the analysis of a note or content.
 */
@Serializable
data class AIResponse(
    val title: String,
    val category: String,
    val summary: String,
    val whySaved: String,
    val todos: List<String> = emptyList(),
    val success: Boolean = true,
    val error: String? = null
)

/**
 * Response model for document analysis (PDFs, long texts).
 */
@Serializable
data class DocumentAnalysisResponse(
    val title: String,
    val summary: String,
    val keyPoints: List<String> = emptyList(),
    val category: String,
    val actionItems: List<String> = emptyList(),
    val userRelevance: String = "",
    val success: Boolean = true,
    val error: String? = null
)
