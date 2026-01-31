package com.example.smarty.data.remote.providers

import android.util.Log
import com.example.smarty.data.remote.AIResponse
import com.example.smarty.data.remote.AIResponseParser
import com.example.smarty.data.remote.DocumentAnalysisResponse
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AI provider implementation for Anthropic (Claude) API.
 * Uses the /v1/messages endpoint.
 */
class AnthropicProvider(
    private val client: OkHttpClient,
    private val gson: Gson
) : AIProviderContract {

    override val providerName: String = "Anthropic"

    companion object {
        private const val TAG = "AnthropicProvider"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }

    data class AnthropicMessage(
        val role: String,
        val content: String
    )

    data class AnthropicRequest(
        val model: String,
        val messages: List<AnthropicMessage>,
        val system: String? = null,
        @SerializedName("max_tokens") val maxTokens: Int,
        val temperature: Float
    )

    override suspend fun analyzeContent(
        context: android.content.Context,
        content: String,
        apiKey: String,
        model: String,
        systemPrompt: String
    ): AIResponse? {
        val requestBody = AnthropicRequest(
            model = model,
            messages = listOf(
                AnthropicMessage(role = "user", content = content)
            ),
            system = systemPrompt,
            maxTokens = AIRequestConfig.ANALYSIS.maxTokens,
            temperature = AIRequestConfig.ANALYSIS.temperature
        )

        return executeRequest(requestBody, apiKey) { text ->
            AIResponseParser.parseOpenAIResponse(context, text, providerName)
        }
    }

    override suspend fun analyzeDocument(
        context: android.content.Context,
        content: String,
        apiKey: String,
        model: String,
        systemPrompt: String
    ): DocumentAnalysisResponse? {
        val requestBody = AnthropicRequest(
            model = model,
            messages = listOf(
                AnthropicMessage(role = "user", content = content)
            ),
            system = systemPrompt,
            maxTokens = AIRequestConfig.DOCUMENT.maxTokens,
            temperature = AIRequestConfig.DOCUMENT.temperature
        )

        return executeRequest(requestBody, apiKey) { text ->
            val cleanText = extractText(text)
            AIResponseParser.parseDocumentAnalysisFromText(context, cleanText)
        }
    }

    override suspend fun chat(
        context: android.content.Context,
        systemPrompt: String,
        userPrompt: String,
        apiKey: String,
        model: String
    ): String? {
        val requestBody = AnthropicRequest(
            model = model,
            messages = listOf(
                AnthropicMessage(role = "user", content = userPrompt)
            ),
            system = systemPrompt,
            maxTokens = AIRequestConfig.CHAT.maxTokens,
            temperature = AIRequestConfig.CHAT.temperature
        )

        return executeRequest(requestBody, apiKey) { text -> extractText(text) }
    }

    // BUG-005 FIX: Made suspend and use withContext instead of runBlocking
    private suspend fun <T> executeRequest(
        requestBody: AnthropicRequest,
        apiKey: String,
        parser: (String) -> T?
    ): T? {
        val jsonBody = gson.toJson(requestBody)

        val request = Request.Builder()
            .url(API_URL)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("content-type", "application/json")
            .build()

        return try {
            // BUG-005 FIX: Use withContext instead of runBlocking to avoid ANR
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            val responseBody = response.body?.string()

            Log.d(TAG, "HTTP ${response.code}")

            if (!response.isSuccessful) {
                Log.e(TAG, "Error: $responseBody")
                return null
            }

            if (responseBody == null) return null
            parser(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Network error: ${e.message}", e)
            null
        }
    }

    /**
     * Extract text content from Anthropic response.
     *
     * Response structure:
     * {
     *   "content": [
     *     { "type": "text", "text": "..." }
     *   ]
     * }
     */
    private fun extractText(responseBody: String?): String? {
        if (responseBody.isNullOrBlank()) return null

        return try {
            val json = JsonParser.parseString(responseBody).asJsonObject

            if (json.has("error")) {
                Log.e(TAG, "API error: ${json.getAsJsonObject("error")}")
                return null
            }

            val contentArray = json.getAsJsonArray("content")
            if (contentArray == null || contentArray.size() == 0) return null

            val firstBlock = contentArray[0].asJsonObject
            if (firstBlock.get("type").asString == "text") {
                firstBlock.get("text").asString
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract text: ${e.message}")
            null
        }
    }
}
