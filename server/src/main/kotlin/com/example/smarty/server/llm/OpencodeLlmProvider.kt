package com.example.smarty.server.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import org.slf4j.LoggerFactory

class OpencodeLlmProvider(
    private val client: HttpClient,
    override val providerName: String = "OpenCode CLI",
    private val defaultModel: String = OpencodeModelRegistry.defaultModel,
    private val daemonPort: Int = 4096,
    private val daemonHost: String = "127.0.0.1",
) : LlmProvider {
    private val logger = LoggerFactory.getLogger(OpencodeLlmProvider::class.java)
    private val daemonBaseUrl = "http://$daemonHost:$daemonPort"
    private val agentName = System.getenv("OPENCODE_AGENT")?.takeIf { it.isNotBlank() } ?: "smarty-headless-agent"

    private val daemonJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    override suspend fun generate(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?,
    ): LlmResponse {
        logger.info("[OpenCode] generate() called — model={}, messages={}, tools={}", model ?: "default", messages.size, tools.size)
        val startTime = System.currentTimeMillis()
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<LlmToolCall>()
        stream(messages, tools, model).collect { chunk ->
            chunk.content?.let { content.append(it) }
            chunk.reasoning?.let { reasoning.append(it) }
            chunk.toolCall?.let { toolCalls.add(it) }
        }
        val duration = System.currentTimeMillis() - startTime
        logger.info("[OpenCode] generate() completed in {}ms — content={} chars, toolCalls={}, reasoning={} chars",
            duration, content.length, toolCalls.size, reasoning.length)
        return LlmResponse(
            content = content.toString().ifBlank { null },
            toolCalls = toolCalls,
        )
    }

    override suspend fun stream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?,
    ): Flow<LlmChunk> = flow {
        val streamStartTime = System.currentTimeMillis()
        logger.info("[OpenCode] === PHASE 2: LLM Inference (Daemon HTTP API) ===")
        logger.info("[OpenCode] stream() starting — model={}, messages={}, tools={}", model ?: "default", messages.size, tools.size)

        val selectedModel = OpencodeModelRegistry.requireAllowedFreeModel(model ?: defaultModel)
        logger.info("[OpenCode] Model selected: {} (requested: {})", selectedModel, model ?: "default")

        val daemonSessionId = createDaemonSession()
        logger.info("[OpenCode] Daemon session created: {}", daemonSessionId)

        val systemPrompt = extractSystemPrompt(messages)
        logger.info("[OpenCode] Friday system prompt: {} chars", systemPrompt?.length ?: 0)

        val userMessage = buildUserMessage(messages)
        logger.info("[OpenCode] User message: {} chars, {} tools", userMessage.length, tools.size)

        // Daemon API: tools is Record<string, boolean> — tool name -> enabled
        // NOT an array of OpenAI-style definitions. Daemon reads tool defs from opencode.json/plugins.
        val toolsMap = if (tools.isNotEmpty()) {
            buildJsonObject {
                tools.forEach { tool ->
                    put(tool.name, true)
                }
            }
        } else null

        // Model object: { modelID: "...", providerID: "..." }
        val modelObj = buildJsonObject {
            put("modelID", selectedModel)
            put("providerID", "opencode")
        }

        logger.info("[OpenCode] POST /session/{}/message — model={}, agent={}, toolsMap={}", daemonSessionId, selectedModel, agentName, toolsMap != null)

        val httpRequest = client.post("$daemonBaseUrl/session/$daemonSessionId/message") {
            contentType(ContentType.Application.Json)
            header("Accept", "text/event-stream")
            setBody(DaemonMessageRequest(
                message = userMessage,
                model = modelObj,
                agent = agentName,
                system = systemPrompt,
                tools = toolsMap,
            ))
        }

        val channel = httpRequest.bodyAsChannel()
        var currentEvent: String? = null
        var currentData = StringBuilder()
        var totalChars = 0
        var lineCount = 0
        var eventCount = 0

        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            lineCount++

            if (lineCount <= 10) {
                logger.debug("[OpenCode] SSE raw line #{}: '{}'", lineCount, line.take(200))
            }

            if (line.isEmpty()) {
                if (currentData.isNotEmpty()) {
                    val eventType = currentEvent ?: "message"
                    eventCount++
                    processSseEvent(eventType, currentData.toString(), totalChars)?.let { chars ->
                        totalChars = chars
                    }
                }
                currentEvent = null
                currentData = StringBuilder()
                continue
            }

            if (line.startsWith("event:")) {
                currentEvent = line.substringAfter("event:").trim()
            } else if (line.startsWith("data:")) {
                val data = line.substringAfter("data:").trim()
                if (data.isNotEmpty()) {
                    if (currentData.isNotEmpty()) currentData.append("\n")
                    currentData.append(data)
                }
            } else if (line.startsWith("id:") || line.startsWith("retry:") || line.startsWith(":")) {
                // Skip SSE metadata and comments
            } else if (line.startsWith("{")) {
                // Daemon may return plain JSON error (not wrapped in SSE) — process directly
                processSseEvent("message", line, totalChars)?.let { chars ->
                    totalChars = chars
                }
            }
        }

        if (currentData.isNotEmpty()) {
            val eventType = currentEvent ?: "message"
            eventCount++
            processSseEvent(eventType, currentData.toString(), totalChars)?.let { chars ->
                totalChars = chars
            }
        }

        logger.info("[OpenCode] SSE stream read — {} lines, {} events, {} chars, {}ms elapsed", lineCount, eventCount, totalChars, System.currentTimeMillis() - streamStartTime)
        val streamDuration = System.currentTimeMillis() - streamStartTime
        logger.info("[OpenCode] stream() completed — {} chars, {}ms elapsed", totalChars, streamDuration)
        logger.info("[OpenCode] === PHASE 2 COMPLETE ===")
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<LlmChunk>.processSseEvent(
        eventType: String,
        data: String,
        totalChars: Int,
    ): Int? {
        var runningTotal = totalChars

        if (data.isBlank()) return runningTotal

        logger.debug("[OpenCode] SSE event: '{}' — data: {}", eventType, data.take(300))

        // Check for daemon error response (plain JSON, not SSE-wrapped)
        if (data.startsWith("{\"name\":\"BadRequest\"") || data.startsWith("{\"name\":\"Error\"")) {
            logger.error("[OpenCode] Daemon error: {}", data.take(500))
            return runningTotal
        }

        if (data.startsWith("{")) {
            val part = runCatching { daemonJson.decodeFromString<DaemonPart>(data) }.getOrNull()
            if (part != null) {
                return when (part.type) {
                    "text" -> {
                        val text = part.text ?: return runningTotal
                        runningTotal += text.length
                        emit(LlmChunk(content = text, reasoning = null))
                        runningTotal
                    }
                    "reasoning" -> {
                        val reasoning = part.text ?: return runningTotal
                        emit(LlmChunk(content = null, reasoning = reasoning))
                        runningTotal
                    }
                    "tool_use", "tool" -> {
                        val toolName = part.name ?: "unknown"
                        val toolInput = part.input?.toString() ?: ""
                        emit(LlmChunk(content = null, toolCall = LlmToolCall(
                            id = "tool-${System.currentTimeMillis()}",
                            functionName = toolName,
                            arguments = toolInput,
                        )))
                        runningTotal
                    }
                    else -> {
                        logger.debug("[OpenCode] Part type '{}' not handled", part.type)
                        runningTotal
                    }
                }
            }

            val toolCall = runCatching { daemonJson.decodeFromString<DaemonToolCall>(data) }.getOrNull()
            if (toolCall != null && toolCall.name != null) {
                emit(LlmChunk(content = null, toolCall = LlmToolCall(
                    id = toolCall.id ?: "tool-${System.currentTimeMillis()}",
                    functionName = toolCall.name,
                    arguments = toolCall.input?.toString() ?: "",
                )))
                return runningTotal
            }
        }

        return when (eventType) {
            "error" -> {
                logger.error("[OpenCode] SSE error event: {}", data)
                runningTotal
            }
            "end", "message_end", "done", "session-update", "part-start", "part-end" -> {
                logger.debug("[OpenCode] SSE control event: {}", eventType)
                runningTotal
            }
            else -> {
                logger.debug("[OpenCode] Unknown SSE event type: '{}' data: {}", eventType, data.take(100))
                runningTotal
            }
        }
    }

    private suspend fun createDaemonSession(): String {
        val response = client.post("$daemonBaseUrl/session") {
            contentType(ContentType.Application.Json)
            setBody(DaemonSessionRequest())
        }
        val result: DaemonSessionResponse = response.body()
        return result.id
    }

    private fun extractSystemPrompt(messages: List<LlmMessage>): String? {
        return messages
            .filter { it.role == LlmMessage.Role.SYSTEM }
            .joinToString("\n\n") { it.content }
            .takeIf { it.isNotBlank() }
    }

    private fun buildUserMessage(messages: List<LlmMessage>): String {
        val nonSystem = messages.filter { it.role != LlmMessage.Role.SYSTEM }
        return nonSystem.joinToString("\n\n") { msg ->
            when (msg.role) {
                LlmMessage.Role.USER -> "<user>\n${msg.content}\n</user>"
                LlmMessage.Role.ASSISTANT -> {
                    val thinking = msg.thinking?.takeIf { it.isNotBlank() }?.let { "<think>\n$it\n</think>\n" } ?: ""
                    "<assistant>\n$thinking${msg.content}\n</assistant>"
                }
                LlmMessage.Role.TOOL -> "<tool_result name=\"${msg.name ?: "tool"}\">\n${msg.content}\n</tool_result>"
                else -> msg.content
            }
        }
    }
}

@Serializable
private data class DaemonSessionRequest(
    val parentID: String? = null,
    val title: String? = null,
)

@Serializable
private data class DaemonSessionResponse(
    val id: String,
)

@Serializable
private data class DaemonMessageRequest(
    val message: String,
    val model: JsonObject? = null,
    val agent: String? = null,
    val noReply: Boolean? = null,
    val system: String? = null,
    val tools: JsonObject? = null,
    val parts: List<JsonObject>? = null,
)

@Serializable
private data class DaemonPart(
    val type: String,
    val text: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
    val output: JsonObject? = null,
)

@Serializable
private data class DaemonToolCall(
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
)
