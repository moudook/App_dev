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
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AI provider implementation for OpenRouter API.
 *
 * OpenRouter provides access to multiple AI models through a unified API.
 * It uses the OpenAI-compatible format but requires additional headers
 * for attribution and tracking.
 *
 * Required headers:
 * - HTTP-Referer: Your app's URL
 * - X-Title: Your app's name
 *
 * API Documentation: https://openrouter.ai/docs
 *
 * @property client OkHttp client for making requests
 * @property gson Gson instance for JSON serialization
 * @property appReferer The app's URL for attribution (e.g., "https://cogni.app")
 * @property appTitle The app's name for attribution (e.g., "Cogni")
 */
class OpenRouterProvider(
    private val client: OkHttpClient,
    private val gson: Gson,
    private val appReferer: String = "https://cogni.app",
    private val appTitle: String = "Cogni"
) : AIProviderContract {

    override val providerName: String = "OpenRouter"

    companion object {
        private const val TAG = "OpenRouterProvider"
        private const val BASE_URL = "https://openrouter.ai/api/v1/chat/completions"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Analyze content using OpenRouter API.
     *
     * Uses OpenAI-compatible format with additional attribution headers.
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

        Log.d(TAG, "OpenRouter URL: $BASE_URL")

        val request = Request.Builder()
            .url(BASE_URL)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", appReferer)
            .addHeader("X-Title", appTitle)
            .build()

        return try {
            // Execute blocking HTTP call on IO dispatcher
            val response = runBlocking(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            val responseBody = response.body?.string()

            Log.d(TAG, "OpenRouter HTTP ${response.code}")

            if (!response.isSuccessful) {
                Log.e(TAG, "OpenRouter error: $responseBody")
                return null
            }

            AIResponseParser.parseOpenAIResponse(responseBody, providerName)
        } catch (e: Exception) {
            Log.e(TAG, "OpenRouter network error: ${e.message}", e)
            null
        }
    }

    /**
     * Analyze document using OpenRouter API with extended limits.
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
            .url(BASE_URL)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", appReferer)
            .addHeader("X-Title", appTitle)
            .build()

        return try {
            // Execute blocking HTTP call on IO dispatcher
            val response = runBlocking(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                Log.e(TAG, "OpenRouter document analysis error: ${response.code}")
                return null
            }

            val text = extractText(responseBody)
            AIResponseParser.parseDocumentAnalysisFromText(text)
        } catch (e: Exception) {
            Log.e(TAG, "OpenRouter document analysis network error: ${e.message}")
            null
        }
    }

    /**
     * Chat using OpenRouter API with conversational settings.
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

        Log.d(TAG, "OpenRouter chat URL: $BASE_URL")

        val request = Request.Builder()
            .url(BASE_URL)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", appReferer)
            .addHeader("X-Title", appTitle)
            .build()

        return try {
            // Execute blocking HTTP call on IO dispatcher
            val response = runBlocking(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            val responseBody = response.body?.string()

            Log.d(TAG, "OpenRouter chat HTTP ${response.code}")

            if (!response.isSuccessful) {
                Log.e(TAG, "OpenRouter chat error: $responseBody")
                return null
            }

            extractText(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "OpenRouter chat network error: ${e.message}")
            null
        }
    }

    /**
     * Extract text content from OpenRouter response.
     * Uses same format as OpenAI-compatible APIs.
     */
    private fun extractText(responseBody: String?): String? {
        if (responseBody.isNullOrBlank()) return null

        return try {
            val json = JsonParser.parseString(responseBody).asJsonObject

            if (json.has("error")) {
                Log.e(TAG, "OpenRouter API error: ${json.getAsJsonObject("error")}")
                return null
            }

            val choices = json.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) return null

            choices[0].asJsonObject
                .getAsJsonObject("message")
                ?.get("content")?.asString
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract OpenRouter text: ${e.message}")
            null
        }
    }
}
