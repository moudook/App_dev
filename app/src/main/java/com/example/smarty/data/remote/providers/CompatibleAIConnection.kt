package com.example.smarty.data.remote.providers

import android.util.Log
import com.example.smarty.data.remote.AIResponse
import com.example.smarty.data.remote.AIResponseParser
import com.example.smarty.data.remote.CompatibleAIMessage
import com.example.smarty.data.remote.CompatibleAIRequest
import com.example.smarty.data.remote.DocumentAnalysisResponse
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AI connection implementation for standardized compatible APIs.
 *
 * This implementation works with any API that follows the chat completions protocol format,
 * primarily used for Local LLM servers and server-side managed endpoints.
 *
 * @property client OkHttp client for making requests
 * @property gson Gson instance for JSON serialization
 * @property baseUrl The API endpoint URL
 * @property name Display name for logging
 */
class CompatibleAIConnection(
    private val client: OkHttpClient,
    private val gson: Gson,
    private val baseUrl: String,
    private val name: String,
) : AIConnectionContract {
    override val connectionName: String = name

    companion object {
        private const val TAG = "CompatibleAIConnection"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Mask sensitive connection token for safe logging.
         */
        private fun maskConnectionToken(token: String?): String {
            if (token == null || token.length < 8) return "****"
            return token.take(4) + "****" + token.takeLast(4)
        }

        /**
         * Sanitize response body for logging by removing potential sensitive data.
         */
        private fun sanitizeForLogging(responseBody: String?): String {
            if (responseBody.isNullOrBlank()) return "[empty response]"
            return responseBody
                .replace(
                    Regex(""""(api[_-]?key|token|authorization|secret|password|bearer)"\\s*:\\s*"[^"]+"""", RegexOption.IGNORE_CASE),
                ) { match ->
                    val keyName = match.groupValues.getOrNull(1) ?: "key"
                    """"$keyName": "****""""
                }
                .replace(Regex("""Bearer\s+[A-Za-z0-9\-_.]+""", RegexOption.IGNORE_CASE), "Bearer ****")
                .replace(Regex("""sk-[A-Za-z0-9]{20,}"""), "sk-****")
                .take(500)
        }

        /**
         * Create a server connection instance for the Smarty Server.
         * @param url Dynamic URL from SecurePreferences.getServerUrl()
         */
        fun localPC(
            client: OkHttpClient,
            gson: Gson,
            url: String,
        ) = CompatibleAIConnection(client, gson, url, "Smarty Server")
    }

    /**
     * Analyze content using compatible API.
     *
     * Request format:
     * ```json
     * {
     *   "model": "model-name",
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
        connectionToken: String,
        model: String,
        systemPrompt: String,
    ): AIResponse? {
        val requestBody =
            CompatibleAIRequest(
                model = model,
                messages =
                    listOf(
                        CompatibleAIMessage(role = "system", content = systemPrompt),
                        CompatibleAIMessage(role = "user", content = content),
                    ),
                temperature = AIRequestConfig.ANALYSIS.temperature,
                maxTokens = AIRequestConfig.ANALYSIS.maxTokens,
            )

        val jsonBody = gson.toJson(requestBody)

        Log.d(TAG, "$name URL: $baseUrl")

        val request =
            Request.Builder()
                .url(baseUrl)
                .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Authorization", "Bearer $connectionToken")
                .addHeader("Content-Type", "application/json")
                .build()

        return try {
            // BUG-005 FIX: Use withContext instead of runBlocking to avoid ANR
            val response =
                withContext(Dispatchers.IO) {
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
                    error = "HTTP ${response.code}",
                )
            }

            AIResponseParser.parseCompatibleResponse(context, responseBody, name)
        } catch (e: Exception) {
            Log.e(TAG, "$name network error: ${e.message}", e)
            null
        }
    }

    /**
     * Analyze document using compatible API with extended limits.
     */
    override suspend fun analyzeDocument(
        context: android.content.Context,
        content: String,
        connectionToken: String,
        model: String,
        systemPrompt: String,
    ): DocumentAnalysisResponse? {
        val requestBody =
            CompatibleAIRequest(
                model = model,
                messages =
                    listOf(
                        CompatibleAIMessage(role = "system", content = systemPrompt),
                        CompatibleAIMessage(role = "user", content = content),
                    ),
                temperature = AIRequestConfig.DOCUMENT.temperature,
                maxTokens = AIRequestConfig.DOCUMENT.maxTokens,
            )

        val jsonBody = gson.toJson(requestBody)

        val request =
            Request.Builder()
                .url(baseUrl)
                .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Authorization", "Bearer $connectionToken")
                .addHeader("Content-Type", "application/json")
                .build()

        return try {
            // BUG-005 FIX: Use withContext instead of runBlocking to avoid ANR
            val response =
                withContext(Dispatchers.IO) {
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
     * Chat using compatible API with conversational settings.
     */
    override suspend fun chat(
        context: android.content.Context,
        systemPrompt: String,
        userPrompt: String,
        connectionToken: String,
        model: String,
    ): String? {
        val requestBody =
            CompatibleAIRequest(
                model = model,
                messages =
                    listOf(
                        CompatibleAIMessage(role = "system", content = systemPrompt),
                        CompatibleAIMessage(role = "user", content = userPrompt),
                    ),
                temperature = AIRequestConfig.CHAT.temperature,
                maxTokens = AIRequestConfig.CHAT.maxTokens,
            )

        val jsonBody = gson.toJson(requestBody)

        Log.d(TAG, "$name chat URL: $baseUrl")

        val request =
            Request.Builder()
                .url(baseUrl)
                .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Authorization", "Bearer $connectionToken")
                .addHeader("Content-Type", "application/json")
                .build()

        return try {
            // BUG-005 FIX: Use withContext instead of runBlocking to avoid ANR
            val response =
                withContext(Dispatchers.IO) {
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
     * Extract text content from compatible response.
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
