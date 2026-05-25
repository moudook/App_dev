package com.example.smarty.server.models

import kotlinx.serialization.Serializable

@Serializable
data class AttachmentInfo(
    val fileName: String,
    val fileType: String,
)

@Serializable
data class ContentAnalysisResult(
    val title: String,
    val category: String,
    val summary: String,
    val whySaved: String,
    val todos: List<String> = emptyList(),
    val memories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val stackId: String? = null,
    val success: Boolean = true,
    val error: String? = null,
)

@Serializable
data class DocumentAnalysisResult(
    val title: String,
    val summary: String,
    val keyPoints: List<String> = emptyList(),
    val category: String,
    val actionItems: List<String> = emptyList(),
    val userRelevance: String = "",
    val references: DocumentReferences? = null,
    val success: Boolean = true,
    val error: String? = null,
)

@Serializable
data class DocumentReferences(
    val formulas: List<String> = emptyList(),
    val keyTerms: List<KeyTerm> = emptyList(),
    val recurringTopics: List<String> = emptyList(),
)

@Serializable
data class KeyTerm(
    val term: String,
    val definition: String,
)
