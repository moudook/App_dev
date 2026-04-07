package com.example.smarty.data.model

data class DocumentChunk(
    val index: Int,
    val totalChunks: Int,
    val content: String,
    val charCount: Int,
    val startPosition: Int,
    val endPosition: Int,
)
