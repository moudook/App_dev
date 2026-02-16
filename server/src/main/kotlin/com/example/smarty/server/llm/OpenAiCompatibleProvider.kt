package com.example.smarty.server.llm

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
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
import kotlinx.serialization.encodeToJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * Universal implementation for OpenAI-compatible APIs.
 * Works with OpenAI, Groq, DeepSeek, OpenRouter, Cerebras, GitHub Models, etc.
 */
class OpenAiCompatibleProvider(
    private val client: HttpClient,
    override val providerName: String,
    private val baseUrl: String,
    private val apiKey: String,
    private val defaultModel: String
) : LlmProvider {

    private val logger = LoggerFactory.getLogger(OpenAiCompatibleProvider::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun generate(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?
    ): LlmResponse {
        val requestBody = buildRequestBody(messages, tools, model, stream = false)

        val endpoint = resolveEndpoint(baseUrl)

        try {
            val response: OpenAiChatResponse = client.post(endpoint) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.body()

            val choice = response.choices.firstOrNull() ?: return LlmResponse(content = null)

            return LlmResponse(
                content = choice.message.content,
                toolCalls = choice.message.toolCalls?.map { it.toLlmToolCall() } ?: emptyList(),
                usage = response.usage?.let { LlmUsage(it.promptTokens, it.completionTokens, it.totalTokens) }
            )
        } catch (e: Exception) {
            logger.error("Generate call failed for $providerName", e)
            throw e
        }
    }

    override suspend fun stream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?
    ): Flow<LlmChunk> = flow {
        val requestBody = buildRequestBody(messages, tools, model, stream = true)

        val endpoint = resolveEndpoint(baseUrl)

        try {
            client.preparePost(endpoint) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.execute { httpResponse ->
                // Check for error status before processing stream
                if (!httpResponse.status.isSuccess()) {
                    val errorBody = httpResponse.bodyAsText()
                    logger.error("LLM API error for $providerName: ${httpResponse.status} - $errorBody")
                    throw IllegalStateException("$providerName API returned ${httpResponse.status}: $errorBody")
                }

                val channel: ByteReadChannel = httpResponse.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: continue

                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        if (data == "[DONE]") break

                        try {
                            val chunk = json.decodeFromString<OpenAiStreamChunk>(data)
                            val delta = chunk.choices.firstOrNull()?.delta

                            if (delta != null) {
                                val content = delta.content
                                val toolCall = delta.toolCalls?.firstOrNull()?.let { tc ->
                                    // Streaming tool calls often come in fragments, but we verify basic structure
                                    LlmToolCall(
                                        id = tc.id ?: "",
                                        functionName = tc.function?.name ?: "",
                                        arguments = tc.function?.arguments ?: ""
                                    )
                                }

                                if (content != null || toolCall != null) {
                                    emit(LlmChunk(content, toolCall))
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore parsing errors for empty/keep-alive chunks
                            logger.debug("Failed to parse SSE chunk: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Stream call failed for $providerName", e)
            throw e
        }
    }

    /**
     * Check if the provider supports function calling / tools.
     * Some local or specialized providers don't support this feature.
     */
    private fun supportsTools(): Boolean {
        return when (providerName) {
            "Local LLM", "Mock" -> false
            else -> true
        }
    }

    private fun resolveEndpoint(baseUrl: String): String {
        return if (baseUrl.endsWith("/chat/completions") || baseUrl.endsWith("/messages")) {
            baseUrl
        } else {
            // Ensure no double slash if baseUrl ends with /
            val cleanBase = baseUrl.removeSuffix("/")
            "$cleanBase/chat/completions"
        }
    }

    private fun buildRequestBody(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?,
        stream: Boolean
    ): Map<String, Any?> {
        val toolsList = if (tools.isNotEmpty() && supportsTools()) {
            tools.map { it.toOpenAiTool() }
        } else null

        return mapOf(
            "model" to (model ?: defaultModel),
            "messages" to messages.map { it.toOpenAiMessageMap() },
            "tools" to toolsList,
            "stream" to stream
        )
    }

    private fun LlmMessage.toOpenAiMessageMap(): Map<String, Any?> {
        val roleStr = when (role) {
            LlmMessage.Role.TOOL -> "user"
            else -> role.name.lowercase()
        }
        val contentStr = if (role == LlmMessage.Role.TOOL) {
            "Tool Result: $content"
        } else {
            content
        }
        return mapOf(
            "role" to roleStr,
            "content" to contentStr,
            "name" to name
        )
    }

    private fun ToolDefinition.toOpenAiTool(): Map<String, Any> {
        val propertiesJson = buildJsonObject {
            parameters.properties.forEach { (name, prop) ->
                putJsonObject(name) {
                    put("type", prop.type)
                    prop.description?.let { put("description", it) }
                    prop.enum?.takeIf { it.isNotEmpty() }?.let { enumValues ->
                        putJsonArray("enum") {
                            enumValues.forEach { add(it) }
                        }
                    }
                }
            }
        }

        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to name,
                "description" to description,
                "parameters" to mapOf(
                    "type" to parameters.type,
                    "properties" to propertiesJson,
                    "required" to parameters.required
                )
            )
        )
    }

    private fun OpenAiToolCall.toLlmToolCall(): LlmToolCall = LlmToolCall(
        id = id ?: "",
        functionName = function?.name ?: "",
        arguments = function?.arguments ?: ""
    )
}

// --- OpenAI DTOs ---

@Serializable
private data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val tools: List<OpenAiTool>? = null,
    val stream: Boolean = false
)

@Serializable
private data class OpenAiMessage(
    val role: String,
    val content: String?,
    val name: String? = null,
    val toolCalls: List<OpenAiToolCall>? = null
)

@Serializable
private data class OpenAiTool(
    val type: String,
    val function: OpenAiFunctionDefinition
)

@Serializable
private data class OpenAiFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: ToolParameters
)

@Serializable
private data class OpenAiChatResponse(
    val choices: List<OpenAiChoice>,
    val usage: OpenAiUsage? = null
)

@Serializable
private data class OpenAiChoice(
    val message: OpenAiMessageDelta, // Non-streaming response has full message structure
    val finish_reason: String?
)

@Serializable
private data class OpenAiStreamChunk(
    val choices: List<OpenAiStreamChoice>
)

@Serializable
private data class OpenAiStreamChoice(
    val delta: OpenAiMessageDelta
)

@Serializable
private data class OpenAiMessageDelta(
    val content: String? = null,
    val toolCalls: List<OpenAiToolCall>? = null,
    @SerialName("tool_calls") val tool_calls: List<OpenAiToolCall>? = null // Handle snake_case variant
) {
    // Helper to consolidate toolCalls variants
    val effectiveToolCalls: List<OpenAiToolCall>? get() = toolCalls ?: tool_calls
}

@Serializable
private data class OpenAiToolCall(
    val id: String? = null,
    val type: String? = null,
    val function: OpenAiFunctionCall? = null
)

@Serializable
private data class OpenAiFunctionCall(
    val name: String? = null,
    val arguments: String? = null
)

@Serializable
private data class OpenAiUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)
