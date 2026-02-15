package com.example.smarty.server.data

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Client for generating vector embeddings from text.
 * Defaults to OpenAI's text-embedding-3-small (1536 dimensions).
 * Gracefully degrades to zero vectors if embedding API is unavailable.
 */
class EmbeddingClient {
    private val logger = LoggerFactory.getLogger(EmbeddingClient::class.java)
    private val apiKey = System.getenv("OPENAI_API_KEY")
    private val baseUrl = System.getenv("OPENAI_BASE_URL") ?: "https://api.openai.com/v1"

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    suspend fun embed(text: String): List<Float> {
        if (apiKey.isNullOrBlank()) {
            logger.warn("OPENAI_API_KEY is not set. Returning zero vector. Embeddings will not work.")
            return List(1536) { 0f }
        }

        try {
            val response: HttpResponse = client.post("$baseUrl/embeddings") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(EmbeddingRequest(
                    model = "text-embedding-3-small",
                    input = text
                ))
            }

            // Check if response is successful
            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logger.warn("Embedding API returned ${response.status}. Response: $errorBody. Returning zero vector.")
                return List(1536) { 0f }
            }

            // Try to parse as success response
            return try {
                val embeddingResponse = response.body<EmbeddingResponse>()
                embeddingResponse.data.firstOrNull()?.embedding ?: List(1536) { 0f }
            } catch (e: Exception) {
                logger.warn("Failed to parse embedding response: ${e.message}. Returning zero vector.")
                List(1536) { 0f }
            }
        } catch (e: Exception) {
            logger.error("Failed to generate embedding", e)
            // Return zero vector on failure to prevent crash, but log error
            return List(1536) { 0f }
        }
    }
}

@Serializable
data class EmbeddingRequest(
    val model: String,
    val input: String
)

@Serializable
data class EmbeddingResponse(
    val data: List<EmbeddingData>? = null,
    val error: EmbeddingError? = null
)

@Serializable
data class EmbeddingError(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null
)

@Serializable
data class EmbeddingData(
    val embedding: List<Float>,
    val index: Int
)
