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
 * Service for generating text embeddings using OpenAI's embedding API.
 *
 * Uses text-embedding-3-small model which is:
 * - Cost effective: $0.00002 per 1K tokens
 * - Fast: ~100ms latency
 * - Compact: 1536 dimensions
 * - High quality for semantic similarity
 *
 * Used by SemanticCache to enable similarity-based cache lookups.
 *
 * @see EmbeddingServiceContract
 * @see GeminiEmbeddingService for alternative implementation
 */
class EmbeddingService(
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient()
) : EmbeddingServiceContract {
    companion object {
        private const val TAG = "EmbeddingService"
        private const val EMBEDDING_URL = "https://api.openai.com/v1/embeddings"
        private const val MODEL = "text-embedding-3-small"
        private const val EMBEDDING_DIMENSIONS = 1536

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()

    /**
     * Generate embedding vector for text.
     *
     * @param text The text to embed (max ~8000 tokens)
     * @return FloatArray of 1536 dimensions, or null on error
     */
    override suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        if (apiKey.isBlank()) {
            Log.w(TAG, "No API key configured for embeddings")
            return@withContext null
        }

        try {
            val request = EmbeddingRequest(
                input = text.take(8000), // Limit input length (~2000 tokens approx)
                model = MODEL
            )

            val requestBody = gson.toJson(request)
                .toRequestBody("application/json".toMediaType())

            val httpRequest = Request.Builder()
                .url(EMBEDDING_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            // FIX: Use .use {} to ensure response body is properly closed
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Embedding API error: ${response.code}")
                    return@withContext null
                }

                val responseBody = response.body?.string()
                val embeddingResponse = gson.fromJson(responseBody, EmbeddingResponse::class.java)

                embeddingResponse.data?.firstOrNull()?.embedding?.toFloatArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating embedding: ${e.message}", e)
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
     * Batch embed multiple texts (more efficient for multiple embeddings).
     *
     * @param texts List of texts to embed
     * @return Map of text to embedding (only successful embeddings included)
     */
    override suspend fun embedBatch(texts: List<String>): Map<String, FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyMap()
        if (apiKey.isBlank()) return@withContext emptyMap()

        try {
            val request = BatchEmbeddingRequest(
                input = texts.map { it.take(8000) },
                model = MODEL
            )

            val requestBody = gson.toJson(request)
                .toRequestBody("application/json".toMediaType())

            val httpRequest = Request.Builder()
                .url(EMBEDDING_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            // FIX: Use .use {} to ensure response body is properly closed
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Batch embedding API error: ${response.code}")
                    return@withContext emptyMap()
                }

                val responseBody = response.body?.string()
                val embeddingResponse = gson.fromJson(responseBody, EmbeddingResponse::class.java)

                embeddingResponse.data?.mapNotNull { data ->
                    val index = data.index ?: return@mapNotNull null
                    val embedding = data.embedding?.toFloatArray() ?: return@mapNotNull null
                    texts.getOrNull(index)?.let { it to embedding }
                }?.toMap() ?: emptyMap()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in batch embedding: ${e.message}", e)
            emptyMap()
        }
    }

    // ==================== API Models ====================

    private data class EmbeddingRequest(
        val input: String,
        val model: String
    )

    private data class BatchEmbeddingRequest(
        val input: List<String>,
        val model: String
    )

    private data class EmbeddingResponse(
        val data: List<EmbeddingData>?,
        val model: String?,
        val usage: EmbeddingUsage?
    )

    private data class EmbeddingData(
        val embedding: List<Float>?,
        val index: Int?
    )

    private data class EmbeddingUsage(
        @SerializedName("prompt_tokens")
        val promptTokens: Int?,
        @SerializedName("total_tokens")
        val totalTokens: Int?
    )
}
