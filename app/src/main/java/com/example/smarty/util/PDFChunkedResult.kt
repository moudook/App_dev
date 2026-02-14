package com.example.smarty.util

import com.example.smarty.data.model.DocumentChunk

sealed class PDFChunkedResult {
    data class Success(
        val chunks: List<DocumentChunk>,
        val fullText: String,
        val totalPages: Int,
        val totalCharacters: Int
    ) : PDFChunkedResult() {
        fun getCombinedText(): String = fullText
    }

    data class Empty(val message: String) : PDFChunkedResult()
    data class Error(val message: String) : PDFChunkedResult()
}
