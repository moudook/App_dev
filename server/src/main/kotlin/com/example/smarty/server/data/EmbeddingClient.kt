package com.example.smarty.server.data

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Client for generating vector embeddings from text.
 * Defaults to OpenAI's text-embedding-3-small (1536 dimensions).
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
        if (apiKey.isNullOrBlank() || apiKey == "dummy") {
            logger.info("Embeddings disabled (Key: ${if (apiKey.isNullOrBlank()) "Missing" else "Dummy"}). Returning zero vector.")
            return List(1536) { 0f }
        }

        try {
            val response: EmbeddingResponse = client.post("$baseUrl/embeddings") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(EmbeddingRequest(
                    model = "text-embedding-3-small",
                    input = text
                ))
            }.body()

            return response.data.firstOrNull()?.embedding ?: List(1536) { 0f }
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
    val data: List<EmbeddingData>
)

@Serializable
data class EmbeddingData(
    val embedding: List<Float>,
    val index: Int
)
