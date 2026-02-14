package com.example.smarty.core.domain.model

import kotlinx.serialization.Serializable
import com.example.smarty.core.domain.model.Note

@Serializable
data class SearchResultItem(
    val note: Note,
    val score: Float,
    val highlight: String?
)

@Serializable
data class SearchQueryAnalysis(
    val originalQuery: String,
    val parsedKeywords: List<String>,
    val detectedIntent: String,
    val complexity: Int,
    val suggestedStrategy: String
)

@Serializable
data class RecallResult(
    val id: String,
    val title: String,
    val content: String,
    val score: Double,
    val reason: String
)
