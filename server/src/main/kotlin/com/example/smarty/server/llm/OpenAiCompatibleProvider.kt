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
        explicitNulls = false
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
                timeout {
                    requestTimeoutMillis = 120_000
                    connectTimeoutMillis = 30_000
                }
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
            val errorMsg = when {
                e.message?.contains("rate", ignoreCase = true) == true -> "Rate limit exceeded. Please try again in a moment."
                e.message?.contains("401") == true || e.message?.contains("unauthorized", ignoreCase = true) == true -> "API authentication failed."
                e.message?.contains("500") == true || e.message?.contains("502") == true -> "LLM provider is experiencing issues."
                e.message?.contains("503") == true -> "LLM provider is temporarily unavailable."
                e.message?.contains("timeout", ignoreCase = true) == true -> "Request timed out. Please try again."
                else -> "$providerName API error: ${e.message?.take(100)}"
            }
            throw IllegalStateException(errorMsg, e)
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
                header("Accept-Encoding", "identity")
                header("Cache-Control", "no-cache")
                timeout {
                    requestTimeoutMillis = 300_000
                    connectTimeoutMillis = 30_000
                    socketTimeoutMillis = 300_000
                }
                setBody(requestBody)
            }.execute { httpResponse ->
                if (!httpResponse.status.isSuccess()) {
                    val errorBody = httpResponse.bodyAsText()
                    logger.error("LLM API error for $providerName: ${httpResponse.status} - $errorBody")
                    val errorMsg = when {
                        errorBody.contains("rate", ignoreCase = true) -> "Rate limit exceeded. Please try again in a moment."
                        errorBody.contains("invalid", ignoreCase = true) || errorBody.contains("unauthorized", ignoreCase = true) -> "API authentication failed."
                        httpResponse.status.value == 500 -> "LLM provider is experiencing issues. Please try again."
                        httpResponse.status.value == 502 || httpResponse.status.value == 503 -> "LLM provider is temporarily unavailable."
                        else -> "$providerName API returned ${httpResponse.status}: ${errorBody.take(200)}"
                    }
                    throw IllegalStateException(errorMsg)
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
                                val reasoning = delta.effectiveReasoning
                                val content = delta.content
                                val toolCall = delta.effectiveToolCalls?.firstOrNull()?.let { tc ->
                                    LlmToolCall(
                                        id = tc.id ?: "",
                                        functionName = tc.function?.name ?: "",
                                        arguments = tc.function?.arguments ?: ""
                                    )
                                }

                                val combinedContent = buildString {
                                    if (!reasoning.isNullOrBlank()) {
                                        append(reasoning)
                                    }
                                    if (!content.isNullOrBlank()) {
                                        append(content)
                                    }
                                }

                                if (combinedContent.isNotEmpty() || toolCall != null) {
                                    emit(LlmChunk(combinedContent.ifEmpty { null }, toolCall))
                                }
                            }
                        } catch (e: Exception) {
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
    ): OpenAiChatRequest {
        val toolsList = if (tools.isNotEmpty() && supportsTools()) {
            tools.map { it.toOpenAiTool() }
        } else null

        return OpenAiChatRequest(
            model = model ?: defaultModel,
            messages = messages.map { it.toOpenAiMessage() },
            tools = toolsList,
            stream = stream
        )
    }

    private fun LlmMessage.toOpenAiMessage(): OpenAiMessage {
        val roleStr = when (role) {
            LlmMessage.Role.TOOL -> "tool"
            LlmMessage.Role.SMARTY -> "assistant"
            LlmMessage.Role.SYSTEM -> "system"
            LlmMessage.Role.USER -> "user"
        }
        return OpenAiMessage(
            role = roleStr,
            content = content,
            name = name,
            toolCallId = if (role == LlmMessage.Role.TOOL) name else null
        )
    }

    private fun ToolDefinition.toOpenAiTool(): OpenAiTool {
        val propertiesMap = mutableMapOf<String, ToolPropertySchema>()
        parameters.properties.forEach { (name, prop) ->
            propertiesMap[name] = ToolPropertySchema(
                type = prop.type,
                description = prop.description,
                enum = prop.enum?.takeIf { it.isNotEmpty() }
            )
        }

        return OpenAiTool(
            type = "function",
            function = OpenAiFunctionDefinition(
                name = name,
                description = description,
                parameters = ToolParametersSchema(
                    type = parameters.type,
                    properties = propertiesMap,
                    required = parameters.required
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
    val toolCallId: String? = null,
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
    val parameters: ToolParametersSchema
)

@Serializable
private data class ToolParametersSchema(
    val type: String = "object",
    val properties: Map<String, ToolPropertySchema>,
    val required: List<String> = emptyList()
)

@Serializable
private data class ToolPropertySchema(
    val type: String,
    val description: String? = null,
    val enum: List<String>? = null
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
    val reasoningContent: String? = null,
    @SerialName("reasoning_content") val reasoning_content: String? = null,
    val toolCalls: List<OpenAiToolCall>? = null,
    @SerialName("tool_calls") val tool_calls: List<OpenAiToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
) {
    val effectiveToolCalls: List<OpenAiToolCall>? get() = toolCalls ?: tool_calls
    val effectiveReasoning: String? get() = reasoningContent ?: reasoning_content
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
