package com.example.smarty.data.remote.providers

import android.util.Log
import com.example.smarty.data.remote.AIResponse
import com.example.smarty.data.remote.AIResponseParser
import com.example.smarty.data.remote.DocumentAnalysisResponse
import com.example.smarty.data.remote.OpenAIMessage
import com.example.smarty.data.remote.OpenAIRequest
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AI provider implementation for OpenAI-compatible APIs.
 *
 * This provider works with any API that follows the OpenAI chat completions format:
 * - OpenAI (api.openai.com)
 * - DeepSeek (api.deepseek.com)
 * - Groq (api.groq.com)
 *
 * All these APIs use the same request/response format with messages array.
 *
 * @property client OkHttp client for making requests
 * @property gson Gson instance for JSON serialization
 * @property baseUrl The API endpoint URL
 * @property name Display name for logging
 */
class OpenAICompatibleProvider(
    private val client: OkHttpClient,
    private val gson: Gson,
    private val baseUrl: String,
    private val name: String
) : AIProviderContract {

    override val providerName: String = name

    companion object {
        private const val TAG = "OpenAIProvider"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // API Base URLs for different providers
        const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
        const val DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
        const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
        const val CEREBRAS_URL = "https://api.cerebras.ai/v1/chat/completions"
        const val COHERE_URL = "https://api.cohere.ai/compatibility/v1/chat/completions"

        /**
         * Create an OpenAI provider instance.
         */
        fun openAI(client: OkHttpClient, gson: Gson) =
            OpenAICompatibleProvider(client, gson, OPENAI_URL, "OpenAI")

        /**
         * Create a DeepSeek provider instance.
         */
        fun deepSeek(client: OkHttpClient, gson: Gson) =
            OpenAICompatibleProvider(client, gson, DEEPSEEK_URL, "DeepSeek")

        /**
         * Create a Groq provider instance.
         */
        fun groq(client: OkHttpClient, gson: Gson) =
            OpenAICompatibleProvider(client, gson, GROQ_URL, "Groq")

        /**
         * Create a Cerebras provider instance.
         * Ultra-fast inference with 2000+ tokens/second.
         */
        fun cerebras(client: OkHttpClient, gson: Gson) =
            OpenAICompatibleProvider(client, gson, CEREBRAS_URL, "Cerebras")

        /**
         * Create a Cohere provider instance.
         * OpenAI-compatible API with Command models.
         */
        fun cohere(client: OkHttpClient, gson: Gson) =
            OpenAICompatibleProvider(client, gson, COHERE_URL, "Cohere")
    }

    /**
     * Analyze content using OpenAI-compatible API.
     *
     * Request format:
     * ```json
     * {
     *   "model": "gpt-4",
     *   "messages": [
     *     { "role": "system", "content": "..." },
     *     { "role": "user", "content": "..." }
     *   ],
     *   "temperature": 0.4,
     *   "max_tokens": 300
     * }
     * ```
     */
    override fun analyzeContent(
        content: String,
        apiKey: String,
        model: String,
        systemPrompt: String
    ): AIResponse? {
        val requestBody = OpenAIRequest(
            model = model,
            messages = listOf(
                OpenAIMessage(role = "system", content = systemPrompt),
                OpenAIMessage(role = "user", content = content)
            ),
            temperature = AIRequestConfig.ANALYSIS.temperature,
            maxTokens = AIRequestConfig.ANALYSIS.maxTokens
        )

        val jsonBody = gson.toJson(requestBody)

        Log.d(TAG, "$name URL: $baseUrl")

        val request = Request.Builder()
            .url(baseUrl)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            Log.d(TAG, "$name HTTP ${response.code}")

            if (!response.isSuccessful) {
                Log.e(TAG, "$name error: $responseBody")
                return AIResponse(
                    title = "Error",
                    category = "Note",
                    summary = "Processing failed",
                    whySaved = "Error",
                    success = false,
                    error = "HTTP ${response.code}"
                )
            }

            AIResponseParser.parseOpenAIResponse(responseBody, name)
        } catch (e: Exception) {
            Log.e(TAG, "$name network error: ${e.message}", e)
            null
        }
    }

    /**
     * Analyze document using OpenAI-compatible API with extended limits.
     */
    override fun analyzeDocument(
        content: String,
        apiKey: String,
        model: String,
        systemPrompt: String
    ): DocumentAnalysisResponse? {
        val requestBody = OpenAIRequest(
            model = model,
            messages = listOf(
                OpenAIMessage(role = "system", content = systemPrompt),
                OpenAIMessage(role = "user", content = content)
            ),
            temperature = AIRequestConfig.DOCUMENT.temperature,
            maxTokens = AIRequestConfig.DOCUMENT.maxTokens
        )

        val jsonBody = gson.toJson(requestBody)

        val request = Request.Builder()
            .url(baseUrl)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                Log.e(TAG, "$name document analysis error: ${response.code}")
                return null
            }

            // Extract text from response and parse as document analysis
            val text = extractText(responseBody)
            AIResponseParser.parseDocumentAnalysisFromText(text)
        } catch (e: Exception) {
            Log.e(TAG, "$name document analysis network error: ${e.message}")
            null
        }
    }

    /**
     * Chat using OpenAI-compatible API with conversational settings.
     */
    override fun chat(
        systemPrompt: String,
        userPrompt: String,
        apiKey: String,
        model: String
    ): String? {
        val requestBody = OpenAIRequest(
            model = model,
            messages = listOf(
                OpenAIMessage(role = "system", content = systemPrompt),
                OpenAIMessage(role = "user", content = userPrompt)
            ),
            temperature = AIRequestConfig.CHAT.temperature,
            maxTokens = AIRequestConfig.CHAT.maxTokens
        )

        val jsonBody = gson.toJson(requestBody)

        Log.d(TAG, "$name chat URL: $baseUrl")

        val request = Request.Builder()
            .url(baseUrl)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            Log.d(TAG, "$name chat HTTP ${response.code}")

            if (!response.isSuccessful) {
                Log.e(TAG, "$name chat error: $responseBody")
                return null
            }

            extractText(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "$name chat network error: ${e.message}")
            null
        }
    }

    /**
     * Extract text content from OpenAI-compatible response.
     *
     * Response structure:
     * ```json
     * {
     *   "choices": [{
     *     "message": { "content": "..." }
     *   }]
     * }
     * ```
     */
    private fun extractText(responseBody: String?): String? {
        if (responseBody.isNullOrBlank()) return null

        return try {
            val json = JsonParser.parseString(responseBody).asJsonObject

            if (json.has("error")) {
                Log.e(TAG, "$name API error: ${json.getAsJsonObject("error")}")
                return null
            }

            val choices = json.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) return null

            choices[0].asJsonObject
                .getAsJsonObject("message")
                ?.get("content")?.asString
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract $name text: ${e.message}")
            null
        }
    }
}
