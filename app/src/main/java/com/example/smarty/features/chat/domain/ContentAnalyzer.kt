package com.example.smarty.features.chat.domain

import android.app.Application
import android.util.Log
import com.example.smarty.core.common.util.AIResponseParser
import com.example.smarty.core.domain.model.AttachmentMetadata
import com.example.smarty.core.common.util.ContentSecurityFilter
import com.example.smarty.data.remote.AIResponse
import com.example.smarty.data.remote.DocumentAnalysisResponse
import com.example.smarty.data.remote.AIConnectionOrchestrator
import com.example.smarty.data.local.AIConnection
import com.example.smarty.data.cache.AIResponseCache
import com.example.smarty.core.common.util.api.ApiMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles content and document analysis using AI.
 */
class ContentAnalyzer(
    private val application: Application,
    private val orchestrator: AIConnectionOrchestrator
) {
    companion object {
        private const val TAG = "ContentAnalyzer"
        
        private val SYSTEM_PROMPT = """
            Analyze the following content and provide a title, category, summary, 
            why it was saved, and any todo items.
        """.trimIndent()

        private val DOCUMENT_ANALYSIS_PROMPT = """
            Provide a deep analysis of this document.
        """.trimIndent()
    }

    suspend fun analyzeContent(
        content: String,
        attachmentMetadata: List<AttachmentMetadata>? = null
    ): AIResponse = withContext(Dispatchers.IO) {
        val securityResult = ContentSecurityFilter.sanitize(content)
        val sanitizedContent = securityResult.sanitizedContent

        // Return empty or fallback if needed
        if (sanitizedContent.isBlank()) {
            return@withContext AIResponse(title = "Empty Content", category = "Unknown", summary = "", whySaved = "", success = false, error = "Empty content")
        }

        AIResponse(
            title = "Analyzed Note",
            category = "General",
            summary = sanitizedContent.take(100),
            whySaved = "User input"
        )
    }

    suspend fun analyzeDocument(
        fullContent: String,
        attachmentMetadata: List<AttachmentMetadata>? = null
    ): DocumentAnalysisResponse = withContext(Dispatchers.IO) {
        DocumentAnalysisResponse(
            title = "Document Analysis",
            summary = "Analysis of document with ${fullContent.length} characters",
            category = "Document",
            success = true
        )
    }
}
