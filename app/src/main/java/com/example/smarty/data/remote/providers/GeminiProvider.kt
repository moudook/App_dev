package com.example.smarty.data.remote.providers

import android.util.Log
import com.example.smarty.data.remote.AIResponse
import com.example.smarty.data.remote.AIResponseParser
import com.example.smarty.data.remote.DocumentAnalysisResponse
import com.example.smarty.data.remote.GeminiContent
import com.example.smarty.data.remote.GeminiGenerationConfig
import com.example.smarty.data.remote.GeminiPart
import com.example.smarty.data.remote.GeminiRequest
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AI provider implementation for Google's Gemini API.
 *
 * Gemini uses a unique API format with content/parts structure.
 * Supports both content analysis and document analysis.
 *
 * API Documentation: https://ai.google.dev/docs
 *
 * @property client OkHttp client for making requests
 * @property gson Gson instance for JSON serialization
 */
class GeminiProvider(
    private val client: OkHttpClient,
    private val gson: Gson
) : AIProviderContract {

    override val providerName: String = "Gemini"

    companion object {
        private const val TAG = "GeminiProvider"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Analyze content using Gemini API.
     *
     * Gemini request format:
     * ```json
     * {
     *   "contents": [{ "parts": [{ "text": "..." }] }],
     *   "generationConfig": { "temperature": 0.4, ... }
     * }
     * ```
     */
    override suspend fun analyzeContent(
        context: android.content.Context,
        content: String,
        apiKey: String,
        model: String,
        systemPrompt: String
    ): AIResponse? {
        val fullPrompt = "$systemPrompt\n$content"

        val requestBody = GeminiRequest(
            contents = listOf(
                GeminiContent(parts = listOf(GeminiPart(text = fullPrompt)))
            ),
            generationConfig = GeminiGenerationConfig(
                temperature = AIRequestConfig.ANALYSIS.temperature,
                maxOutputTokens = AIRequestConfig.ANALYSIS.maxTokens,
                topP = AIRequestConfig.ANALYSIS.topP,
                topK = AIRequestConfig.ANALYSIS.topK
            )
        )

        val jsonBody = gson.toJson(requestBody)
        val url = "$BASE_URL/$model:generateContent?key=$apiKey"

        Log.d(TAG, "Gemini URL: ${url.replace(apiKey, "API_KEY_HIDDEN")}")

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
            .build()

        return try {
            // BUG-005 FIX: Use withContext instead of runBlocking to avoid ANR
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            val responseBody = response.body?.string()

            Log.d(TAG, "Gemini HTTP ${response.code}")

            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini error response: $responseBody")
                return AIResponse(
                    title = "Error",
                    category = "Note",
                    summary = "Processing failed",
                    whySaved = "Error",
                    success = false,
                    error = "HTTP ${response.code}: ${parseError(responseBody)}"
                )
            }

            AIResponseParser.parseGeminiResponse(context, responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini network error: ${e.message}", e)
            null
        }
    }

    /**
     * Analyze document using Gemini API with extended token limits.
     */
    override suspend fun analyzeDocument(
        context: android.content.Context,
        content: String,
        apiKey: String,
        model: String,
        systemPrompt: String
    ): DocumentAnalysisResponse? {
        val fullPrompt = "$systemPrompt\n$content"

        val requestBody = GeminiRequest(
            contents = listOf(
                GeminiContent(parts = listOf(GeminiPart(text = fullPrompt)))
            ),
            generationConfig = GeminiGenerationConfig(
                temperature = AIRequestConfig.DOCUMENT.temperature,
                maxOutputTokens = AIRequestConfig.DOCUMENT.maxTokens,
                topP = AIRequestConfig.DOCUMENT.topP,
                topK = AIRequestConfig.DOCUMENT.topK
            )
        )

        val jsonBody = gson.toJson(requestBody)
        val url = "$BASE_URL/$model:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
            .build()

        return try {
            // BUG-005 FIX: Use withContext instead of runBlocking to avoid ANR
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini document analysis error: ${response.code}")
                return null
            }

            AIResponseParser.parseDocumentAnalysisResponse(context, responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini document analysis network error: ${e.message}")
            null
        }
    }

    /**
     * Chat using Gemini API with higher temperature for conversational responses.
     */
    override suspend fun chat(
        context: android.content.Context,
        systemPrompt: String,
        userPrompt: String,
        apiKey: String,
        model: String
    ): String? {
        val fullPrompt = "$systemPrompt\n\n$userPrompt"

        val requestBody = GeminiRequest(
            contents = listOf(
                GeminiContent(parts = listOf(GeminiPart(text = fullPrompt)))
            ),
            generationConfig = GeminiGenerationConfig(
                temperature = AIRequestConfig.CHAT.temperature,
                maxOutputTokens = AIRequestConfig.CHAT.maxTokens,
                topP = AIRequestConfig.CHAT.topP,
                topK = AIRequestConfig.CHAT.topK
            )
        )

        val jsonBody = gson.toJson(requestBody)
        val url = "$BASE_URL/$model:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
            .build()

        return try {
            // BUG-005 FIX: Use withContext instead of runBlocking to avoid ANR
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini chat error: ${response.code} - $responseBody")
                return null
            }

            extractText(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini chat network error: ${e.message}")
            null
        }
    }

    /**
     * Parse error message from Gemini error response.
     */
    private fun parseError(responseBody: String?): String {
        return AIResponseParser.parseGeminiError(responseBody)
    }

    /**
     * Extract text content from Gemini response.
     *
     * Response structure:
     * ```json
     * {
     *   "candidates": [{
     *     "content": {
     *       "parts": [{ "text": "..." }]
     *     }
     *   }]
     * }
     * ```
     */
    private fun extractText(responseBody: String?): String? {
        if (responseBody.isNullOrBlank()) return null

        return try {
            val json = JsonParser.parseString(responseBody).asJsonObject
            val candidates = json.getAsJsonArray("candidates")
            if (candidates == null || candidates.size() == 0) return null

            candidates[0].asJsonObject
                .getAsJsonObject("content")
                .getAsJsonArray("parts")[0].asJsonObject
                .get("text").asString
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract Gemini text: ${e.message}")
            null
        }
    }
}
