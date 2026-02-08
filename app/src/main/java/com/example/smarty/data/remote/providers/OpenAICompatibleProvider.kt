package com.example.smarty.data.remote.providers

import android.util.Log
import com.example.smarty.data.remote.AIResponse
import com.example.smarty.data.remote.AIResponseParser
import com.example.smarty.data.remote.DocumentAnalysisResponse
import com.example.smarty.data.remote.OpenAIMessage
import com.example.smarty.data.remote.OpenAIRequest
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

        /**
         * Mask sensitive API key for safe logging.
         * Shows first 4 and last 4 characters with **** in between.
         */
        private fun maskApiKey(key: String?): String {
            if (key == null || key.length < 8) return "****"
            return key.take(4) + "****" + key.takeLast(4)
        }

        /**
         * Sanitize response body for logging by removing potential sensitive data.
         * Masks any API keys, tokens, or authorization headers that might be echoed in error responses.
         */
        private fun sanitizeForLogging(responseBody: String?): String {
            if (responseBody.isNullOrBlank()) return "[empty response]"
            // Mask common patterns for API keys/tokens in responses
            // Patterns: "api_key": "...", "token": "...", "authorization": "...", "Bearer ..."
            return responseBody
                .replace(Regex(""""(api[_-]?key|token|authorization|secret|password|bearer)"\\s*:\\s*"[^"]+"""", RegexOption.IGNORE_CASE)) { match ->
                    val keyName = match.groupValues.getOrNull(1) ?: "key"
                    """"$keyName": "****""""
                }
                .replace(Regex("""Bearer\s+[A-Za-z0-9\-_.]+""", RegexOption.IGNORE_CASE), "Bearer ****")
                .replace(Regex("""sk-[A-Za-z0-9]{20,}"""), "sk-****")
                .replace(Regex("""gsk_[A-Za-z0-9]{20,}"""), "gsk_****")
                .replace(Regex("""xai-[A-Za-z0-9]{20,}"""), "xai-****")
                .take(500) // Limit log length to prevent large dumps
        }

        // API Base URLs for different providers
        const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
        const val DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions"
        const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
        const val CEREBRAS_URL = "https://api.cerebras.ai/v1/chat/completions"
        const val COHERE_URL = "https://api.cohere.ai/compatibility/v1/chat/completions"
        const val GITHUB_URL = "https://models.github.ai/inference/chat/completions"
        // FOR TESTING ONLY - Remove before publishing!
        // Note: USB tethering IP can change - run ipconfig on PC to find it
        // The PC's IP (not the phone's gateway IP) should be used
        // LOCAL_PC_URL is now dynamically read from SecurePreferences.getLocalPCUrl()

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

        /**
         * Create a GitHub Models provider instance.
         * Free with GitHub account (requires PAT with models scope).
         * Access to GPT-4o, Llama, DeepSeek, Phi, Mistral models.
         */
        fun github(client: OkHttpClient, gson: Gson) =
            OpenAICompatibleProvider(client, gson, GITHUB_URL, "GitHub")

        /**
         * Create a Local PC provider instance (USB tethering).
         * FOR TESTING ONLY - Remove before publishing!
         * @param url Dynamic URL from SecurePreferences.getLocalPCUrl() - IP can change with each USB tethering session
         */
        fun localPC(client: OkHttpClient, gson: Gson, url: String) =
            OpenAICompatibleProvider(client, gson, url, "Local PC")
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
    override suspend fun analyzeContent(
        context: android.content.Context,
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
            // BUG-005 FIX: Use withContext instead of runBlocking to avoid ANR
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            val responseBody = response.body?.string()

            Log.d(TAG, "$name HTTP ${response.code}")

            if (!response.isSuccessful) {
                Log.e(TAG, "$name error: ${sanitizeForLogging(responseBody)}")
                return AIResponse(
                    title = "Error",
                    category = "Note",
                    summary = "Processing failed",
                    whySaved = "Error",
                    success = false,
                    error = "HTTP ${response.code}"
                )
            }

            AIResponseParser.parseOpenAIResponse(context, responseBody, name)
        } catch (e: Exception) {
            Log.e(TAG, "$name network error: ${e.message}", e)
            null
        }
    }

    /**
     * Analyze document using OpenAI-compatible API with extended limits.
     */
    override suspend fun analyzeDocument(
        context: android.content.Context,
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
            // BUG-005 FIX: Use withContext instead of runBlocking to avoid ANR
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                Log.e(TAG, "$name document analysis error: ${response.code}")
                return null
            }

            // Extract text from response and parse as document analysis
            val text = extractText(responseBody)
            AIResponseParser.parseDocumentAnalysisFromText(context, text)
        } catch (e: Exception) {
            Log.e(TAG, "$name document analysis network error: ${e.message}")
            null
        }
    }

    /**
     * Chat using OpenAI-compatible API with conversational settings.
     */
    override suspend fun chat(
        context: android.content.Context,
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
            // BUG-005 FIX: Use withContext instead of runBlocking to avoid ANR
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            val responseBody = response.body?.string()

            Log.d(TAG, "$name chat HTTP ${response.code}")

            if (!response.isSuccessful) {
                Log.e(TAG, "$name chat error: ${sanitizeForLogging(responseBody)}")
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
                val errorObj = json.getAsJsonObject("error")
                Log.e(TAG, "$name API error: ${sanitizeForLogging(errorObj.toString())}")
                return null
            }

            val choices = json.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) {
                Log.w(TAG, "$name: Empty or null choices array")
                return null
            }

            val firstChoice = choices.firstOrNull()?.asJsonObject
            if (firstChoice == null) {
                Log.w(TAG, "$name: First choice is null")
                return null
            }

            val message = firstChoice.getAsJsonObject("message")
            if (message == null) {
                Log.w(TAG, "$name: Message object is null")
                return null
            }

            message.get("content")?.asString
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract $name text: ${e.message}")
            null
        }
    }
}
