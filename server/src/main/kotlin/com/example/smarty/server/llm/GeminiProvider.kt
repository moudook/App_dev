package com.example.smarty.server.llm

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Provider implementation for Google Gemini (Vertex AI / AI Studio).
 */
class GeminiProvider(
    private val client: HttpClient,
    override val providerName: String = "Gemini",
    private val apiKey: String,
    private val defaultModel: String = "gemini-1.5-flash"
) : LlmProvider {

    private val logger = LoggerFactory.getLogger(GeminiProvider::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models"

    override suspend fun generate(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?
    ): LlmResponse {
        val modelName = model ?: defaultModel
        val requestBody = messages.toGeminiRequest(tools)

        try {
            val response: GeminiResponse = client.post("$baseUrl/$modelName:generateContent?key=$apiKey") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.body()

            val content = response.candidates?.firstOrNull()?.content
            val text = content?.parts?.firstOrNull()?.text
            // Tool calls handling would go here (Gemini uses functionCall part)

            return LlmResponse(content = text)
        } catch (e: Exception) {
            logger.error("Gemini generate failed", e)
            throw e
        }
    }

    override suspend fun stream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?
    ): Flow<LlmChunk> = flow {
        val modelName = model ?: defaultModel
        val requestBody = messages.toGeminiRequest(tools)

        try {
            client.preparePost("$baseUrl/$modelName:streamGenerateContent?key=$apiKey") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.execute { httpResponse ->
                val channel: ByteReadChannel = httpResponse.bodyAsChannel()
                var buffer = ""

                // Gemini returns a JSON array stream, but sometimes chunks are sent as individual JSON objects
                // in the response stream. We need to handle the stream carefully.
                // Simplified approach: Read line by line and try to parse objects.

                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: continue

                    // Gemini stream format is complex (comma separated JSON objects).
                    // For robust streaming, we often rely on specific client handling.
                    // Here we filter for payload lines.

                    if (line.trim() == "[" || line.trim() == "]" || line.trim() == ",") continue

                    try {
                        val cleanLine = line.trim().removeSuffix(",")
                        val chunk = json.decodeFromString<GeminiResponse>(cleanLine)
                        val text = chunk.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text

                        if (text != null) {
                            emit(LlmChunk(content = text))
                        }

                        chunk.usageMetadata?.let { usage ->
                            emit(LlmChunk(content = null, usage = LlmUsage(
                                promptTokens = usage.promptTokenCount ?: 0,
                                completionTokens = usage.candidatesTokenCount ?: 0,
                                totalTokens = usage.totalTokenCount ?: 0
                            )))
                        }
                    } catch (e: Exception) {
                        // Buffer logic might be needed for split JSONs
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Gemini stream failed", e)
            throw e
        }
    }

    private fun List<LlmMessage>.toGeminiRequest(tools: List<ToolDefinition>): GeminiRequest {
        val contents = this.filter { it.role != LlmMessage.Role.SYSTEM }.map { msg ->
            val role = when (msg.role) {
                LlmMessage.Role.USER, LlmMessage.Role.TOOL -> "user"
                else -> "model"
            }

            val parts = mutableListOf<GeminiPart>()

            // Add text content
            val contentText = if (msg.role == LlmMessage.Role.TOOL) {
                "Tool Result: ${msg.content}"
            } else {
                msg.content
            }
            if (contentText.isNotEmpty()) {
                parts.add(GeminiPart(text = contentText))
            }

            // Add images
            msg.images?.forEach { imageData ->
                // Defaulting to image/jpeg if not specified.
                // In a production system, we should inspect bytes or pass mimeType in LlmMessage.
                val base64 = java.util.Base64.getEncoder().encodeToString(imageData)
                parts.add(GeminiPart(inlineData = GeminiBlob(mimeType = "image/jpeg", data = base64)))
            }

            GeminiContent(
                role = role,
                parts = parts
            )
        }

        // Gemini handles System Instructions separately
        val systemInstruction = this.find { it.role == LlmMessage.Role.SYSTEM }?.let {
            GeminiContent(role = "user", parts = listOf(GeminiPart(text = it.content)))
        }

        return GeminiRequest(
            contents = contents,
            systemInstruction = systemInstruction
            // tools logic would be mapped here
        )
    }
}

// --- Gemini DTOs ---

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null
)

@Serializable
data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiBlob? = null
)

@Serializable
data class GeminiBlob(
    val mimeType: String,
    val data: String // Base64
)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val usageMetadata: GeminiUsageMetadata? = null
)

@Serializable
data class GeminiUsageMetadata(
    val promptTokenCount: Int? = null,
    val candidatesTokenCount: Int? = null,
    val totalTokenCount: Int? = null
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null
)
