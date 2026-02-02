package com.example.smarty.server.llm

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory

/**
 * Provider implementation for Anthropic Claude.
 */
class AnthropicProvider(
    private val client: HttpClient,
    override val providerName: String = "Claude",
    private val apiKey: String,
    private val defaultModel: String = "claude-3-5-sonnet-20240620"
) : LlmProvider {

    private val logger = LoggerFactory.getLogger(AnthropicProvider::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val baseUrl = "https://api.anthropic.com/v1"

    override suspend fun generate(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?
    ): LlmResponse {
        // Non-streaming implementation reused from stream for simplicity in this task scope
        // Ideally would use a separate call
        throw NotImplementedError("Use stream() for AnthropicProvider")
    }

    override suspend fun stream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?
    ): Flow<LlmChunk> = flow {
        val modelName = model ?: defaultModel
        val requestBody = messages.toAnthropicRequest(modelName, tools)

        try {
            client.preparePost("$baseUrl/messages") {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.execute { httpResponse ->
                val channel: ByteReadChannel = httpResponse.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: continue

                    if (line.startsWith("event: ")) {
                        val eventType = line.removePrefix("event: ").trim()
                        // Next line is data
                        val dataLine = channel.readUTF8Line() ?: continue
                        if (!dataLine.startsWith("data: ")) continue
                        val data = dataLine.removePrefix("data: ").trim()

                        when (eventType) {
                            "content_block_delta" -> {
                                try {
                                    val delta = json.decodeFromString<AnthropicStreamEvent>(data)
                                    val text = delta.delta?.text
                                    if (text != null) {
                                        emit(LlmChunk(content = text))
                                    }
                                } catch (e: Exception) {
                                    // Ignore parse errors
                                }
                            }
                            // Handle tool_use delta if implementing tools
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Anthropic stream failed", e)
            throw e
        }
    }

    private fun List<LlmMessage>.toAnthropicRequest(model: String, tools: List<ToolDefinition>): AnthropicRequest {
        val systemMessage = this.find { it.role == LlmMessage.Role.SYSTEM }?.content
        val conversation = this.filter { it.role != LlmMessage.Role.SYSTEM }.map {
            AnthropicMessage(
                role = if (it.role == LlmMessage.Role.USER) "user" else "assistant",
                content = it.content
            )
        }

        return AnthropicRequest(
            model = model,
            system = systemMessage,
            messages = conversation,
            stream = true,
            max_tokens = 4096
        )
    }
}

// --- Anthropic DTOs ---

@Serializable
data class AnthropicRequest(
    val model: String,
    val system: String? = null,
    val messages: List<AnthropicMessage>,
    val stream: Boolean = false,
    val max_tokens: Int
)

@Serializable
data class AnthropicMessage(
    val role: String,
    val content: String
)

@Serializable
data class AnthropicStreamEvent(
    val delta: AnthropicDelta? = null
)

@Serializable
data class AnthropicDelta(
    val type: String,
    val text: String? = null
)
