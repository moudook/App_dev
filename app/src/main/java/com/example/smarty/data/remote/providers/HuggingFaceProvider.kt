package com.example.smarty.data.remote.providers

import android.util.Log
import com.example.smarty.data.remote.AIResponse
import com.example.smarty.data.remote.AIResponseParser
import com.example.smarty.data.remote.DocumentAnalysisResponse
import com.example.smarty.data.remote.HuggingFaceParams
import com.example.smarty.data.remote.HuggingFaceRequest
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AI provider implementation for HuggingFace Inference API.
 *
 * HuggingFace uses a different API format than OpenAI:
 * - Requests use "inputs" instead of "messages"
 * - Responses return an array with "generated_text"
 * - Uses instruction-tuned models with [INST] tags
 *
 * API Documentation: https://huggingface.co/docs/api-inference
 *
 * @property client OkHttp client for making requests
 * @property gson Gson instance for JSON serialization
 */
class HuggingFaceProvider(
    private val client: OkHttpClient,
    private val gson: Gson
) : AIProviderContract {

    override val providerName: String = "HuggingFace"

    companion object {
        private const val TAG = "HuggingFaceProvider"
        private const val BASE_URL = "https://router.huggingface.co/models"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Analyze content using HuggingFace Inference API.
     *
     * Request format:
     * ```json
     * {
     *   "inputs": "<s>[INST] {prompt} [/INST]",
     *   "parameters": {
     *     "max_new_tokens": 300,
     *     "temperature": 0.4,
     *     "return_full_text": false
     *   }
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
        val prompt = formatPrompt(systemPrompt, content)

        val requestBody = HuggingFaceRequest(
            inputs = prompt,
            parameters = HuggingFaceParams(
                maxNewTokens = AIRequestConfig.ANALYSIS.maxTokens,
                temperature = AIRequestConfig.ANALYSIS.temperature,
                returnFullText = false
            )
        )

        val jsonBody = gson.toJson(requestBody)
        val url = "$BASE_URL/$model"

        Log.d(TAG, "HuggingFace URL: $url")

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        return try {
            // BUG-005 FIX: Use withContext instead of runBlocking to avoid ANR
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            val responseBody = response.body?.string()

            Log.d(TAG, "HuggingFace HTTP ${response.code}")

            if (!response.isSuccessful) {
                val errorMsg = parseHuggingFaceError(response.code, responseBody)
                Log.e(TAG, "HuggingFace error: $errorMsg")
                return null
            }

            AIResponseParser.parseHuggingFaceResponse(context, responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "HuggingFace network error: ${e.message}", e)
            null
        }
    }

    /**
     * Analyze document using HuggingFace Inference API.
     */
    override suspend fun analyzeDocument(
        context: android.content.Context,
        content: String,
        apiKey: String,
        model: String,
        systemPrompt: String
    ): DocumentAnalysisResponse? {
        val prompt = formatPrompt(systemPrompt, content)

        val requestBody = HuggingFaceRequest(
            inputs = prompt,
            parameters = HuggingFaceParams(
                maxNewTokens = AIRequestConfig.DOCUMENT.maxTokens,
                temperature = AIRequestConfig.DOCUMENT.temperature,
                returnFullText = false
            )
        )

        val jsonBody = gson.toJson(requestBody)
        val url = "$BASE_URL/$model"

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        return try {
            // BUG-005 FIX: Use withContext instead of runBlocking to avoid ANR
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                Log.e(TAG, "HuggingFace document analysis error: ${response.code}")
                return null
            }

            // Parse HuggingFace response format
            val text = extractText(responseBody)
            AIResponseParser.parseDocumentAnalysisFromText(context, text)
        } catch (e: Exception) {
            Log.e(TAG, "HuggingFace document analysis network error: ${e.message}")
            null
        }
    }

    /**
     * Chat using HuggingFace Inference API.
     */
    override suspend fun chat(
        context: android.content.Context,
        systemPrompt: String,
        userPrompt: String,
        apiKey: String,
        model: String
    ): String? {
        val prompt = formatChatPrompt(systemPrompt, userPrompt)

        val requestBody = HuggingFaceRequest(
            inputs = prompt,
            parameters = HuggingFaceParams(
                maxNewTokens = AIRequestConfig.CHAT.maxTokens,
                temperature = AIRequestConfig.CHAT.temperature,
                returnFullText = false
            )
        )

        val jsonBody = gson.toJson(requestBody)
        val url = "$BASE_URL/$model"

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        return try {
            // BUG-005 FIX: Use withContext instead of runBlocking to avoid ANR
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                Log.e(TAG, "HuggingFace chat error: ${response.code} - $responseBody")
                return null
            }

            extractText(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "HuggingFace chat network error: ${e.message}")
            null
        }
    }

    /**
     * Format prompt for instruction-tuned models.
     * Uses Llama/Mistral instruction format with [INST] tags.
     *
     * @param systemPrompt The system instructions
     * @param userContent The user's content to analyze
     * @return Formatted prompt string
     */
    private fun formatPrompt(systemPrompt: String, userContent: String): String {
        return """<s>[INST] $systemPrompt
$userContent [/INST]"""
    }

    /**
     * Format chat prompt for conversational responses.
     *
     * @param systemPrompt The system instructions
     * @param userPrompt The user's message
     * @return Formatted prompt string
     */
    private fun formatChatPrompt(systemPrompt: String, userPrompt: String): String {
        return """<s>[INST] $systemPrompt

$userPrompt [/INST]"""
    }

    /**
     * Parse HuggingFace error response.
     *
     * Common error codes:
     * - 401: Invalid API key
     * - 403: Model requires Pro subscription or license acceptance
     * - 404: Model not found
     * - 503: Model is loading
     */
    private fun parseHuggingFaceError(code: Int, responseBody: String?): String {
        val baseMessage = when (code) {
            401 -> "Invalid API key. Please check your HuggingFace token."
            403 -> "Access denied. This model may require a Pro subscription or license acceptance on huggingface.co"
            404 -> "Model not found. Please verify the model ID."
            503 -> "Model is loading. Please wait a moment and try again."
            else -> "HTTP error $code"
        }

        // Try to extract more details from response body
        if (!responseBody.isNullOrBlank()) {
            try {
                val json = JsonParser.parseString(responseBody).asJsonObject
                val error = json.get("error")?.asString
                if (!error.isNullOrBlank()) {
                    return "$baseMessage - $error"
                }
            } catch (_: Exception) { }
        }

        return baseMessage
    }

    /**
     * Extract text from HuggingFace response.
     *
     * Response format:
     * ```json
     * [{ "generated_text": "..." }]
     * ```
     */
    private fun extractText(responseBody: String?): String? {
        if (responseBody.isNullOrBlank()) return null

        return try {
            val jsonArray = JsonParser.parseString(responseBody).asJsonArray
            if (jsonArray.size() == 0) return null

            jsonArray[0].asJsonObject.get("generated_text")?.asString
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract HuggingFace text: ${e.message}")
            null
        }
    }
}
