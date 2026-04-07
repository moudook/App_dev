package com.example.smarty.data.remote

import android.content.Context
import android.util.Log
import com.example.smarty.core.domain.model.AttachmentMetadata
import com.example.smarty.data.local.SecurePreferences

/**
 * AI Service facade that coordinates AI operations.
 * Thin Client Version: All operations are offloaded to the server via RemoteAgentService.
 */
class AIService(
    private val context: Context,
    private val securePreferences: SecurePreferences,
    private val remoteAgentService: RemoteAgentService,
    private val aiResponseCache: com.example.smarty.data.cache.AIResponseCache,
) {
    companion object {
        private const val TAG = "AIService"
    }

    /**
     * Analyzes content using the remote server with local caching.
     */
    suspend fun analyzeContent(
        content: String,
        attachmentMetadata: List<AttachmentMetadata>? = null,
    ): AIResponse {
        // 1. Check Cache
        val cacheKey = aiResponseCache.generateKey(content)
        val cachedResponse = aiResponseCache.get(cacheKey)
        if (cachedResponse != null) {
            Log.d(TAG, "Cache hit for content analysis")
            return cachedResponse
        }

        // 2. Call Remote Service
        val response =
            remoteAgentService.analyzeContent(
                content,
                attachmentMetadata?.map {
                    AttachmentInfo(it.fileName, it.fileType)
                },
            )

        // 3. Cache and Return
        return if (response != null && response.success) {
            aiResponseCache.put(cacheKey, response)
            response
        } else {
            response ?: AIResponse(
                title = "Analysis Failed",
                category = "general",
                summary = "Could not connect to server.",
                whySaved = "Error",
                success = false,
                error = "Server unavailable",
            )
        }
    }

    /**
     * Analyzes document content on the server.
     */
    suspend fun analyzeDocument(
        documentText: String,
        fileName: String? = null,
        userContext: String? = null,
    ): DocumentAnalysisResponse {
        return remoteAgentService.analyzeDocument(documentText, fileName, userContext) ?: DocumentAnalysisResponse(
            title = fileName ?: "Document",
            summary = "Analysis failed",
            keyPoints = emptyList(),
            category = "document",
            actionItems = emptyList(),
            userRelevance = "Error",
            success = false,
            error = "Server unavailable",
        )
    }

    /**
     * Simple chat for non-agent AI interactions.
     */
    suspend fun simpleChat(
        systemPrompt: String,
        userPrompt: String,
    ): String {
        // For thin client, we can treat this as a briefing or single-turn chat
        // using the remote service.
        // Or we can use sendQuery and collect the first result.
        // For now, let's use a specific briefing endpoint if available, or just chat.

        // Using generateBriefing as a proxy for simple Q&A if appropriate,
        // or falling back to a simplified chat query.
        return remoteAgentService.generateBriefing("$systemPrompt\n\nUser: $userPrompt")
            ?: "Server unavailable"
    }

    /**
     * Process an image on the server (OCR/Description).
     */
    suspend fun processImage(
        imageBytes: ByteArray,
        mimeType: String,
    ): String {
        return remoteAgentService.processImage(imageBytes, mimeType)?.text
            ?: "Image processing failed"
    }

    /**
     * Process a PDF on the server.
     */
    suspend fun processPdf(
        pdfBytes: ByteArray,
        fileName: String?,
    ): String {
        return remoteAgentService.processPdf(pdfBytes, fileName)?.text
            ?: "PDF processing failed"
    }

    /**
     * Check if AI connection is available.
     */
    fun isAiAvailable(): Boolean {
        // Always true for thin client (assumes network might work)
        // Real check happens on request
        return true
    }
}
