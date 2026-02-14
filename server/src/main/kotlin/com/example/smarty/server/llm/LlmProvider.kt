package com.example.smarty.server.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Abstraction for Large Language Model providers.
 * Unifies access to OpenAI, Claude, Gemini, etc.
 */
interface LlmProvider {
    val providerName: String

    /**
     * Generate a complete response (non-streaming).
     */
    suspend fun generate(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition> = emptyList(),
        model: String? = null
    ): LlmResponse

    /**
     * Stream the response token by token (or chunk by chunk).
     */
    suspend fun stream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition> = emptyList(),
        model: String? = null
    ): Flow<LlmChunk>
}

@Serializable
data class LlmMessage(
    val role: Role,
    val content: String,
    val name: String? = null,
    val images: List<ByteArray>? = null // Optional image attachments
) {
    enum class Role {
        SYSTEM, USER, SMARTY, TOOL
    }
}

@Serializable
data class LlmResponse(
    val content: String?,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val usage: LlmUsage? = null
)

@Serializable
data class LlmChunk(
    val content: String?, // Partial text
    val toolCall: LlmToolCall? = null, // Partial or complete tool call
    val usage: LlmUsage? = null // Optional usage info (usually in the last chunk)
)

@Serializable
data class LlmToolCall(
    val id: String,
    val functionName: String,
    val arguments: String // JSON string
)

@Serializable
data class LlmUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

/**
 * Definition of a tool that can be passed to the LLM.
 * Matches OpenAI's function calling schema.
 */
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: ToolParameters // JSON Schema definition
)

@Serializable
data class ToolParameters(
    val type: String = "object",
    val properties: Map<String, ToolProperty>,
    val required: List<String> = emptyList()
)

@Serializable
data class ToolProperty(
    val type: String,
    val description: String? = null,
    val enum: List<String>? = null
)
