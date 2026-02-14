package com.example.smarty.core.domain.model

data class SearchQueryAnalysis(
    val originalQuery: String,
    val parsedKeywords: List<String>,
    val detectedIntent: String,
    val complexity: Int,
    val suggestedStrategy: String
)
