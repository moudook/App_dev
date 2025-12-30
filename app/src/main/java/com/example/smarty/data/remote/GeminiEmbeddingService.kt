package com.example.smarty.data.remote

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Service for generating text embeddings using Google's Gemini Embedding API.
 *
 * Uses text-embedding-004 model which is:
 * - Free tier available
 * - 768 dimensions (smaller than OpenAI's 1536)
 * - Good quality for semantic similarity
 *
 * This implements the same interface as EmbeddingService to allow
 * SemanticCache to work with either provider.
 *
 * API Documentation: https://ai.google.dev/gemini-api/docs/embeddings
 *
 * @see EmbeddingServiceContract
 * @see EmbeddingService for OpenAI implementation
 */
class GeminiEmbeddingService(
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient()
) : EmbeddingServiceContract {
    companion object {
        private const val TAG = "GeminiEmbeddingService"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MODEL = "text-embedding-004"
        private const val EMBEDDING_DIMENSIONS = 768

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()

    /**
     * Generate embedding vector for text.
     *
     * @param text The text to embed
     * @return FloatArray of 768 dimensions, or null on error
     */
    override suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        if (apiKey.isBlank()) {
            Log.w(TAG, "No API key configured for Gemini embeddings")
            return@withContext null
        }

        try {
            val request = GeminiEmbeddingRequest(
                content = GeminiEmbeddingContent(
                    parts = listOf(GeminiTextPart(text = text.take(8000)))
                )
            )

            val requestBody = gson.toJson(request)
                .toRequestBody("application/json".toMediaType())

            val url = "$BASE_URL/$MODEL:embedContent?key=$apiKey"

            val httpRequest = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "Gemini Embedding API error: ${response.code} - $errorBody")
                    return@withContext null
                }

                val responseBody = response.body?.string()
                val embeddingResponse = gson.fromJson(responseBody, GeminiEmbeddingResponse::class.java)

                embeddingResponse.embedding?.values?.map { it.toFloat() }?.toFloatArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating Gemini embedding: ${e.message}", e)
            null
        }
    }

    /**
     * Calculate cosine similarity between two embedding vectors.
     *
     * @param a First embedding vector
     * @param b Second embedding vector
     * @return Similarity score between 0 and 1 (1 = identical)
     */
    override fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator > 0) dotProduct / denominator else 0f
    }

    /**
     * Batch embed multiple texts.
     *
     * Note: Gemini's batch embedding API has different format,
     * so we implement this as sequential calls for simplicity.
     *
     * @param texts List of texts to embed
     * @return Map of text to embedding (only successful embeddings included)
     */
    override suspend fun embedBatch(texts: List<String>): Map<String, FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyMap()
        if (apiKey.isBlank()) return@withContext emptyMap()

        val results = mutableMapOf<String, FloatArray>()

        // Gemini doesn't have a batch API like OpenAI, so we call sequentially
        // Consider using batchEmbedContents endpoint for better performance
        for (text in texts) {
            embed(text)?.let { embedding ->
                results[text] = embedding
            }
        }

        results
    }

    // ==================== API Models ====================

    private data class GeminiEmbeddingRequest(
        val content: GeminiEmbeddingContent
    )

    private data class GeminiEmbeddingContent(
        val parts: List<GeminiTextPart>
    )

    private data class GeminiTextPart(
        val text: String
    )

    private data class GeminiEmbeddingResponse(
        val embedding: GeminiEmbeddingValues?
    )

    private data class GeminiEmbeddingValues(
        val values: List<Double>?
    )
}
