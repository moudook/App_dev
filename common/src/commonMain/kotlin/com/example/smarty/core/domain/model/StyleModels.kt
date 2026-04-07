package com.example.smarty.core.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class WritingStylePattern(
    val patternType: String, // e.g., "structure", "tone", "length", "format"
    val description: String,
    val frequency: Float = 1.0f, // 0.0 to 1.0 scale
)

@Serializable
data class StyleAnalysisReport(
    val totalNotesAnalyzed: Int,
    val writingPatterns: List<WritingStylePattern>,
    val summary: String,
)
