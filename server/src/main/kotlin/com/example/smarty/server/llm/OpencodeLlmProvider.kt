package com.example.smarty.server.llm

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.UUID

class OpencodeLlmProvider(
    private val client: HttpClient,
    override val providerName: String = "OpenCode Zen API",
    private val defaultModel: String = OpencodeModelRegistry.defaultModel,
) : LlmProvider {
    private val logger = LoggerFactory.getLogger(OpencodeLlmProvider::class.java)

    companion object {
        private const val ZEN_BASE_URL = "https://opencode.ai/zen/v1"
        private val ZEN_PUBLIC_KEY = System.getenv("OPENCODE_API_KEY") ?: ""
    }

    override suspend fun generate(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?,
        externalSessionId: String?,
        onExternalSessionCreated: suspend (String) -> Unit,
        variant: String?,
    ): LlmResponse {
        val content = StringBuilder()
        val toolCalls = mutableListOf<LlmToolCall>()
        stream(messages, tools, model, externalSessionId, onExternalSessionCreated, variant).collect { chunk ->
            chunk.content?.let { content.append(it) }
            chunk.toolCall?.let { toolCalls.add(it) }
        }
        return LlmResponse(content = content.toString().ifBlank { null }, toolCalls = toolCalls)
    }

    override suspend fun stream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?,
        externalSessionId: String?,
        onExternalSessionCreated: suspend (String) -> Unit,
        variant: String?,
    ): Flow<LlmChunk> =
        flow {
            val inferenceId = UUID.randomUUID().toString().take(8)

            // Notify of session ID if it's new (using externalSessionId or just generating a random one)
            val activeSessionId = externalSessionId ?: UUID.randomUUID().toString().also { onExternalSessionCreated(it) }

            val rawModelId = (model ?: defaultModel).substringAfter('/').takeIf { it.isNotBlank() } ?: "deepseek-v4-flash"
            val cleanModelId = rawModelId.removeSuffix("-free").removeSuffix("-Free")
            val modelId = if (cleanModelId == "auto") "deepseek-v4-flash" else cleanModelId
            val requestStartMs = System.currentTimeMillis()

            val toolsJson =
                if (tools.isNotEmpty()) {
                    buildJsonArray {
                        tools.forEach { td ->
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("function"))
                                    put(
                                        "function",
                                        buildJsonObject {
                                            put("name", JsonPrimitive(td.name))
                                            put("description", JsonPrimitive(td.description))
                                            put(
                                                "parameters",
                                                buildJsonObject {
                                                    put("type", JsonPrimitive(td.parameters.type))
                                                    put(
                                                        "properties",
                                                        kotlinx.serialization.json.JsonObject(
                                                            td.parameters.properties.mapValues { (_, v) ->
                                                                buildJsonObject {
                                                                    put("type", JsonPrimitive(v.type))
                                                                    v.description?.let { put("description", JsonPrimitive(it)) }
                                                                }
                                                            },
                                                        ),
                                                    )
                                                    put(
                                                        "required",
                                                        kotlinx.serialization.json.JsonArray(
                                                            td.parameters.required.map { JsonPrimitive(it) },
                                                        ),
                                                    )
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    }
                } else {
                    kotlinx.serialization.json.JsonArray(emptyList())
                }

            val systemPrompt =
                messages
                    .filter { it.role == LlmMessage.Role.SYSTEM }
                    .joinToString("\n\n") { it.content }
                    .takeIf { it.isNotBlank() }

            val chatMessages =
                buildList {
                    if (systemPrompt != null) {
                        add(
                            buildJsonObject {
                                put("role", JsonPrimitive("system"))
                                put("content", JsonPrimitive(systemPrompt))
                            },
                        )
                    }
                    messages.filter { it.role != LlmMessage.Role.SYSTEM }.forEach { msg ->
                        val role =
                            when (msg.role) {
                                LlmMessage.Role.USER -> "user"
                                LlmMessage.Role.ASSISTANT -> "assistant"
                                LlmMessage.Role.TOOL -> "tool"
                                LlmMessage.Role.SYSTEM -> "system"
                            }
                        add(
                            buildJsonObject {
                                put("role", JsonPrimitive(role))
                                put("content", JsonPrimitive(msg.content))
                                if (msg.role == LlmMessage.Role.TOOL && !msg.toolCallId.isNullOrBlank()) {
                                    put("tool_call_id", JsonPrimitive(msg.toolCallId))
                                }
                                if (msg.role == LlmMessage.Role.ASSISTANT && msg.toolCalls.isNotEmpty()) {
                                    put(
                                        "tool_calls",
                                        kotlinx.serialization.json.buildJsonArray {
                                            msg.toolCalls.forEach { tc ->
                                                add(
                                                    buildJsonObject {
                                                        put("id", JsonPrimitive(tc.id))
                                                        put("type", JsonPrimitive("function"))
                                                        put(
                                                            "function",
                                                            buildJsonObject {
                                                                put("name", JsonPrimitive(tc.functionName))
                                                                put("arguments", JsonPrimitive(tc.arguments))
                                                            },
                                                        )
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                        )
                    }
                }

            val body =
                buildJsonObject {
                    put("model", JsonPrimitive(modelId))
                    put("stream", JsonPrimitive(true))
                    put("messages", kotlinx.serialization.json.JsonArray(chatMessages))
                    if (toolsJson.isNotEmpty()) {
                        put("tools", toolsJson)
                    }
                }

            logger.info(
                "[OpenCode.DirectZen][inference=$inferenceId] POST $ZEN_BASE_URL/chat/completions model=$modelId tools=${tools.size}",
            )

            try {
                client
                    .preparePost("$ZEN_BASE_URL/chat/completions") {
                        contentType(ContentType.Application.Json)
                        header("Authorization", "Bearer $ZEN_PUBLIC_KEY")
                        header("Accept", "text/event-stream")
                        setBody(body.toString())
                    }.execute { response ->
                        val statusMs = System.currentTimeMillis() - requestStartMs
                        logger.info("[OpenCode.DirectZen][inference=$inferenceId] status=${response.status} headersAfterMs=$statusMs")
                        if (response.status.value !in 200..299) {
                            val errBody = runCatching { response.bodyAsText() }.getOrElse { "<no body>" }
                            logger.error("[OpenCode.DirectZen][inference=$inferenceId] HTTP ${response.status} body=$errBody")
                            emit(LlmChunk(content = null, finishReason = "error", rawJson = errBody.take(500), sseEvent = "error"))
                            return@execute
                        }
                        val channel = response.bodyAsChannel()
                        var currentData = StringBuilder()
                        var directChunkCount = 0
                        var firstChunkLogged = false
                        var activeToolCall: com.example.smarty.server.llm.LlmToolCall? = null

                        while (!channel.isClosedForRead) {
                            val line = channel.readLine() ?: break
                            if (line.isBlank()) {
                                if (currentData.isNotEmpty()) {
                                    val data = currentData.toString().trim()
                                    currentData.setLength(0)
                                    if (data == "[DONE]") {
                                        activeToolCall = null
                                        emit(LlmChunk(content = null, finishReason = "stop", sseEvent = "done"))
                                        break
                                    }
                                    val json = runCatching { Json.parseToJsonElement(data) }.getOrNull() as? JsonObject
                                    if (json != null) {
                                        val nowMs = System.currentTimeMillis()
                                        val choice = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                                        val delta = choice?.get("delta")?.jsonObject
                                        val finishReason = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull

                                        val reasoningContent = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                                        val reasoningField = delta?.get("reasoning")?.jsonPrimitive?.contentOrNull
                                        val reasoning = reasoningContent ?: reasoningField

                                        val rawContent = delta?.get("content")?.jsonPrimitive?.contentOrNull
                                        val content =
                                            if (rawContent != null && rawContent.contains("<think>")) {
                                                rawContent.replace(Regex("<think>[\\s\\S]*?</think>\\n?"), "")
                                            } else {
                                                rawContent
                                            }

                                        val toolCallDelta =
                                            delta
                                                ?.get("tool_calls")
                                                ?.jsonArray
                                                ?.firstOrNull()
                                                ?.jsonObject
                                        val rawToolCall =
                                            if (toolCallDelta != null) {
                                                val fn = toolCallDelta["function"]?.jsonObject
                                                val args =
                                                    fn?.get("arguments")?.jsonPrimitive?.contentOrNull
                                                        ?: fn?.get("arguments")?.toString()
                                                        ?: ""
                                                com.example.smarty.server.llm.LlmToolCall(
                                                    id = toolCallDelta["id"]?.jsonPrimitive?.contentOrNull ?: "",
                                                    functionName = fn?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
                                                    arguments = args,
                                                )
                                            } else {
                                                null
                                            }

                                        val toolCall =
                                            if (rawToolCall != null) {
                                                val prev = activeToolCall
                                                val merged =
                                                    if (prev != null && rawToolCall.id.isBlank() && rawToolCall.functionName.isBlank()) {
                                                        com.example.smarty.server.llm.LlmToolCall(
                                                            id = prev.id,
                                                            functionName = prev.functionName,
                                                            arguments = prev.arguments + rawToolCall.arguments,
                                                        )
                                                    } else {
                                                        rawToolCall
                                                    }
                                                activeToolCall = merged
                                                merged
                                            } else {
                                                null
                                            }

                                        if (finishReason != null) {
                                            activeToolCall = null
                                        }

                                        if (!content.isNullOrEmpty() || !reasoning.isNullOrEmpty() || toolCall != null) {
                                            if (!firstChunkLogged) {
                                                logger.info(
                                                    "[OpenCode.DirectZen.StreamDiag][inference=$inferenceId] FIRST_SSE_CHUNK after ${nowMs - requestStartMs}ms",
                                                )
                                                firstChunkLogged = true
                                            }
                                            directChunkCount++
                                            emit(
                                                LlmChunk(
                                                    content = content,
                                                    reasoning = reasoning,
                                                    toolCall = toolCall,
                                                    finishReason = finishReason,
                                                    rawJson = data,
                                                    sseEvent = "message",
                                                ),
                                            )
                                        } else {
                                            emit(
                                                LlmChunk(content = null, rawJson = data, sseEvent = "message", finishReason = finishReason),
                                            )
                                        }
                                    }
                                }
                                continue
                            }
                            if (line.startsWith("data:")) {
                                val d = line.substringAfter("data:").trim()
                                if (currentData.isNotEmpty()) currentData.append("\n")
                                currentData.append(d)
                            } else if (line.startsWith("{")) {
                                val json = runCatching { Json.parseToJsonElement(line) }.getOrNull() as? JsonObject
                                if (json != null) {
                                    val choice = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                                    val delta = choice?.get("delta")?.jsonObject
                                    var activeToolCall2: com.example.smarty.server.llm.LlmToolCall? = activeToolCall
                                    val finishReason = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
                                    val reasoning =
                                        delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                                            ?: delta?.get("reasoning")?.jsonPrimitive?.contentOrNull
                                    val rawContent = delta?.get("content")?.jsonPrimitive?.contentOrNull
                                    val content =
                                        if (rawContent != null && rawContent.contains("<think>")) {
                                            rawContent.replace(Regex("<think>[\\s\\S]*?</think>\\n?"), "")
                                        } else {
                                            rawContent
                                        }
                                    val toolCallDelta =
                                        delta
                                            ?.get("tool_calls")
                                            ?.jsonArray
                                            ?.firstOrNull()
                                            ?.jsonObject
                                    val rawToolCall =
                                        if (toolCallDelta != null) {
                                            val fn = toolCallDelta["function"]?.jsonObject
                                            com.example.smarty.server.llm.LlmToolCall(
                                                id = toolCallDelta["id"]?.jsonPrimitive?.contentOrNull ?: "",
                                                functionName = fn?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
                                                arguments = fn?.get("arguments")?.toString() ?: "",
                                            )
                                        } else {
                                            null
                                        }

                                    val toolCall =
                                        if (rawToolCall != null) {
                                            val prev = activeToolCall2
                                            val merged =
                                                if (prev != null && rawToolCall.id.isBlank() && rawToolCall.functionName.isBlank()) {
                                                    com.example.smarty.server.llm.LlmToolCall(
                                                        id = prev.id,
                                                        functionName = prev.functionName,
                                                        arguments = prev.arguments + rawToolCall.arguments,
                                                    )
                                                } else {
                                                    rawToolCall
                                                }
                                            activeToolCall2 = merged
                                            activeToolCall = merged
                                            merged
                                        } else {
                                            null
                                        }

                                    if (finishReason != null) {
                                        activeToolCall = null
                                    }
                                    if (!content.isNullOrEmpty() || !reasoning.isNullOrEmpty() || toolCall != null) {
                                        directChunkCount++
                                        val emitText = content ?: reasoning ?: ""
                                        emit(
                                            LlmChunk(
                                                content = emitText,
                                                reasoning = reasoning,
                                                toolCall = toolCall,
                                                finishReason = finishReason,
                                                rawJson = line,
                                                sseEvent = "message",
                                            ),
                                        )
                                    } else {
                                        emit(LlmChunk(content = null, rawJson = line, sseEvent = "message", finishReason = finishReason))
                                    }
                                }
                            }
                        }
                        val totalMs = System.currentTimeMillis() - requestStartMs
                        logger.info(
                            "[OpenCode.DirectZen.StreamDiag][inference=$inferenceId] STREAM_COMPLETE totalMs=$totalMs chunks=$directChunkCount",
                        )
                    }
            } catch (e: Exception) {
                logger.error("[OpenCode.DirectZen][inference=$inferenceId] failed class={} msg={}", e.javaClass.name, e.message)
                emit(LlmChunk(content = null, finishReason = "error", sseEvent = "error"))
            }
        }.flowOn(Dispatchers.IO)
}
