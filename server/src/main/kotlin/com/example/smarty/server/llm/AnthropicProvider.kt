package com.example.smarty.server.llm

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import com.example.smarty.server.plugins.ServerMonitor

/**
 * Provider implementation for Anthropic Claude.
 */
class AnthropicProvider(
    private val client: HttpClient,
    override val providerName: String = "Claude",
    private val apiKey: String,
    private val defaultModel: String = "claude-3-5-sonnet-20240620",
    private val baseUrl: String = "https://api.anthropic.com/v1"
) : LlmProvider {

    private val logger = LoggerFactory.getLogger(AnthropicProvider::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun generate(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?
    ): LlmResponse {
        throw NotImplementedError("Use stream() for AnthropicProvider")
    }

    override suspend fun stream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?
    ): Flow<LlmChunk> = flow {
        val modelName = model ?: defaultModel
        val requestBody = messages.toAnthropicRequest(modelName, tools)

        logger.info("Streaming request to $baseUrl/messages (model=$modelName, messages=${messages.size})")

        val requestJson = json.encodeToString(requestBody)
        logger.debug("Request body: ${requestJson.take(500)}")

        val llmStartTime = System.currentTimeMillis()
        try {
            client.preparePost("$baseUrl/messages") {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                // Use TextContent to bypass ContentNegotiation double-encoding
                setBody(TextContent(requestJson, ContentType.Application.Json))
            }.execute { httpResponse ->
                val statusCode = httpResponse.status.value
                logger.info("LLM response status: $statusCode")

                if (statusCode !in 200..299) {
                    val errorBody = httpResponse.bodyAsText()
                    logger.error("LLM request failed with status $statusCode: $errorBody")
                    throw RuntimeException("LLM returned HTTP $statusCode: ${errorBody.take(200)}")
                }

                var chunkCount = 0
                // Tool call accumulation state
                var activeToolId = ""
                var activeToolName = ""
                var activeToolArgs = StringBuilder()
                var inToolBlock = false

                val channel: ByteReadChannel = httpResponse.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: continue

                    if (line.isBlank()) continue

                    if (line.startsWith("event: ")) {
                        val eventType = line.removePrefix("event: ").trim()
                        // Next line is data
                        val dataLine = channel.readUTF8Line() ?: continue
                        if (!dataLine.startsWith("data: ")) {
                             logger.warn("Expected data: line after event: but got: $dataLine")
                             continue
                        }
                        val data = dataLine.removePrefix("data: ").trim()

                        when (eventType) {
                            "message_start" -> {
                                try {
                                    val event = json.decodeFromString<AnthropicStreamEvent>(data)
                                    val responseModel = event.message?.model
                                    logger.info("Stream started (response model: $responseModel)")
                                    event.message?.usage?.let { usage ->
                                        emit(LlmChunk(content = null, usage = LlmUsage(
                                            promptTokens = usage.input_tokens ?: 0,
                                            completionTokens = usage.output_tokens ?: 0,
                                            totalTokens = (usage.input_tokens ?: 0) + (usage.output_tokens ?: 0)
                                        )))
                                    }
                                } catch (e: Exception) {
                                    logger.error("Failed to parse message_start: $data", e)
                                }
                            }
                            "content_block_start" -> {
                                // Detect tool_use blocks — this is where tool calls begin
                                try {
                                    val jsonObj = json.parseToJsonElement(data).jsonObject
                                    val contentBlock = jsonObj["content_block"]?.jsonObject
                                    val blockType = contentBlock?.get("type")?.jsonPrimitive?.contentOrNull

                                    if (blockType == "tool_use") {
                                        inToolBlock = true
                                        activeToolId = contentBlock["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                        activeToolName = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                        activeToolArgs = StringBuilder()
                                        logger.info("Tool call started: $activeToolName (id: $activeToolId)")

                                        // Emit initial tool call chunk so ServerAgent knows a tool is coming
                                        emit(LlmChunk(
                                            content = null,
                                            toolCall = LlmToolCall(
                                                id = activeToolId,
                                                functionName = activeToolName,
                                                arguments = ""
                                            )
                                        ))
                                    } else {
                                        inToolBlock = false
                                        logger.debug("Content block started: type=$blockType")
                                    }
                                } catch (e: Exception) {
                                    logger.warn("Failed to parse content_block_start: $data", e)
                                }
                            }
                            "content_block_delta" -> {
                                try {
                                    val jsonObj = json.parseToJsonElement(data).jsonObject
                                    val delta = jsonObj["delta"]?.jsonObject
                                    val deltaType = delta?.get("type")?.jsonPrimitive?.contentOrNull

                                    when (deltaType) {
                                        "text_delta" -> {
                                            // Regular text content
                                            val text = delta["text"]?.jsonPrimitive?.contentOrNull
                                            if (text != null) {
                                                chunkCount++
                                                emit(LlmChunk(content = text))
                                            }
                                        }
                                        "input_json_delta" -> {
                                            // Tool call argument streaming
                                            val partialJson = delta["partial_json"]?.jsonPrimitive?.contentOrNull
                                            if (partialJson != null && inToolBlock) {
                                                activeToolArgs.append(partialJson)
                                                // Emit incremental tool call chunk
                                                emit(LlmChunk(
                                                    content = null,
                                                    toolCall = LlmToolCall(
                                                        id = activeToolId,
                                                        functionName = activeToolName,
                                                        arguments = partialJson
                                                    )
                                                ))
                                            }
                                        }
                                        else -> {
                                            // Fallback: try to extract text directly (some proxies simplify the format)
                                            val text = delta?.get("text")?.jsonPrimitive?.contentOrNull
                                            if (text != null) {
                                                chunkCount++
                                                emit(LlmChunk(content = text))
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    logger.warn("Failed to parse content_block_delta: $data", e)
                                }
                            }
                            "content_block_stop" -> {
                                if (inToolBlock) {
                                    logger.info("Tool call completed: $activeToolName with args: ${activeToolArgs.toString().take(200)}")
                                    inToolBlock = false
                                }
                            }
                            "message_delta" -> {
                                try {
                                    val event = json.decodeFromString<AnthropicStreamEvent>(data)
                                    event.usage?.let { usage ->
                                        emit(LlmChunk(content = null, usage = LlmUsage(
                                            promptTokens = 0,
                                            completionTokens = usage.output_tokens ?: 0,
                                            totalTokens = usage.output_tokens ?: 0
                                        )))
                                    }
                                } catch (e: Exception) {
                                    logger.error("Failed to parse message_delta: $data", e)
                                }
                            }
                            "message_stop" -> {
                                logger.info("Received message_stop event")
                            }
                            "error" -> {
                                logger.error("LLM stream error event: $data")
                                throw RuntimeException("LLM error: $data")
                            }
                            "ping" -> {
                                // Ignore pings
                            }
                            else -> {
                                logger.debug("Unknown event type: $eventType")
                            }
                        }
                    }
                }
                logger.info("Stream completed: $chunkCount content chunks emitted")
                ServerMonitor.trackLlmRequest(
                    model = modelName,
                    latency = System.currentTimeMillis() - llmStartTime,
                    success = true
                )
            }
        } catch (e: Exception) {
            ServerMonitor.trackLlmRequest(
                model = modelName,
                latency = System.currentTimeMillis() - llmStartTime,
                success = false,
                errorMsg = e.message
            )
            logger.error("Anthropic stream failed: ${e.message}", e)
            throw e
        }
    }

    private fun List<LlmMessage>.toAnthropicRequest(model: String, tools: List<ToolDefinition>): AnthropicRequest {
        val systemMessage = this.find { it.role == LlmMessage.Role.SYSTEM }?.content
        val conversation = this.filter { it.role != LlmMessage.Role.SYSTEM }.map {
            val role = when (it.role) {
                LlmMessage.Role.USER, LlmMessage.Role.TOOL -> "user"
                else -> "assistant"
            }
            val contentRaw = if (it.role == LlmMessage.Role.TOOL) {
                "Tool Result: ${it.content}"
            } else {
                it.content
            }

            // Parse content for embedded images
            val contentElement = parseContent(contentRaw)

            AnthropicMessage(role = role, content = contentElement)
        }

        return AnthropicRequest(
            model = model,
            system = systemMessage,
            messages = conversation,
            stream = true,
            max_tokens = 4096
        )
    }

    private fun parseContent(content: String): JsonElement {
        // Regex to match [Image: data:mime;base64,data]
        // Pattern: \[Image: data:([^;]+);base64,([^\\]]+)\\]
        val imageRegex = "\\[Image: data:([^;]+);base64,([^\\]]+)\\]".toRegex()

        if (!imageRegex.containsMatchIn(content)) {
            return JsonPrimitive(content)
        }

        val blocks = mutableListOf<JsonObject>()
        var lastIndex = 0

        imageRegex.findAll(content).forEach { matchResult ->
            // Add text before image
            if (matchResult.range.first > lastIndex) {
                val text = content.substring(lastIndex, matchResult.range.first).trim()
                if (text.isNotEmpty()) {
                    blocks.add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                }
            }

            // Add image
            val mimeType = matchResult.groupValues[1]
            val base64Data = matchResult.groupValues[2]

            blocks.add(buildJsonObject {
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", mimeType)
                    put("data", base64Data)
                })
            })

            lastIndex = matchResult.range.last + 1
        }

        // Add remaining text
        if (lastIndex < content.length) {
            val text = content.substring(lastIndex).trim()
            if (text.isNotEmpty()) {
                blocks.add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
        }

        return JsonArray(blocks)
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
    val content: JsonElement
)

@Serializable
data class AnthropicStreamEvent(
    val type: String? = null,
    val message: AnthropicMessageResponse? = null,
    val delta: AnthropicDelta? = null,
    val usage: AnthropicUsage? = null
)

@Serializable
data class AnthropicMessageResponse(
    val id: String,
    val model: String? = null,
    val usage: AnthropicUsage? = null
)

@Serializable
data class AnthropicUsage(
    val input_tokens: Int? = null,
    val output_tokens: Int? = null
)

@Serializable
data class AnthropicDelta(
    val type: String? = null,
    val text: String? = null
)
