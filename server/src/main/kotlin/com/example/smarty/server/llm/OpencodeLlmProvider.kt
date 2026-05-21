package com.example.smarty.server.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

        var systemPrompt = extractSystemPrompt(messages)
        
        // Inject tools into the system prompt to ensure the model knows about them
        if (tools.isNotEmpty()) {
            val toolsDesc = tools.joinToString("\n") { tool ->
                val schemaStr = runCatching { 
                    Json.encodeToString(ToolParameters.serializer(), tool.parameters) 
                }.getOrDefault("{}")
                "- ${tool.name}: ${tool.description}\n  Parameters JSON schema: $schemaStr" 
            }
            val toolInstruction = """
                
                You have access to the following tools. Use them when necessary by outputting a tool call in the exact XML format below. 
                Do NOT use any other format for tool calls.
                
                <tool_call_json>
                ```json
                {
                  "name": "tool_name",
                  "arguments": {
                    "param_name": "value"
                  }
                }
                ```
                </tool_call_json>
                
                Available tools:
                $toolsDesc
            """.trimIndent()
            systemPrompt = (systemPrompt ?: "") + "\n" + toolInstruction
        }
        
        logger.info("[OpenCode] Friday system prompt: {} chars", systemPrompt?.length ?: 0)

        val userMessage = buildUserMessage(messages)
        logger.info("[OpenCode] User message: {} chars, {} tools", userMessage.length, tools.size)

        val parts = listOf(buildJsonObject {
            put("type", "text")
            put("text", userMessage)
        })

        // We MUST NOT pass custom tools via the tools parameter to the Daemon API, 
        // as it strictly expects a Map<String, Boolean> of built-in tools, causing a BadRequest.
        val mappedTools = null

        logger.info("[OpenCode] POST /session/{}/message — agent={}, system={}chars, tools={}", daemonSessionId, agentName, systemPrompt?.length ?: 0, tools.size)

        var totalChars = 0
        val flowCollector = this

        client.preparePost("$daemonBaseUrl/session/$daemonSessionId/message") {
            contentType(ContentType.Application.Json)
            header("Accept", "text/event-stream")
            setBody(DaemonMessageRequest(
                parts = parts,
                model = selectedModel,
                agent = agentName,
                system = systemPrompt,
                tools = mappedTools
            ))
        }.execute { response ->
            val channel = response.bodyAsChannel()
            var currentEvent: String? = null
            var currentData = StringBuilder()
            var lineCount = 0
            var eventCount = 0

            logger.info("[OpenCode] === SSE STREAM STARTED ===")

            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                lineCount++

                // Log EVERY line for debugging
                logger.debug("[OpenCode] SSE LINE #{}: '{}'", lineCount, line.take(500))

                if (line.isEmpty()) {
                    if (currentData.isNotEmpty()) {
                        val eventType = currentEvent ?: "message"
                        eventCount++
                        logger.info("[OpenCode] SSE EVENT #{}: type='{}', data={} chars", eventCount, eventType, currentData.length)
                        flowCollector.processSseEvent(eventType, currentData.toString(), totalChars)?.let { chars ->
                            totalChars = chars
                        }
                    } else {
                        logger.debug("[OpenCode] SSE: empty line (no data to process)")
                    }
                    currentEvent = null
                    currentData = StringBuilder()
                    continue
                }

                if (line.startsWith("event:")) {
                    currentEvent = line.substringAfter("event:").trim()
                    logger.debug("[OpenCode] SSE: event type set to '{}'", currentEvent)
                } else if (line.startsWith("data:")) {
                    val data = line.substringAfter("data:").trim()
                    if (data.isNotEmpty()) {
                        if (currentData.isNotEmpty()) currentData.append("\n")
                        currentData.append(data)
                        logger.debug("[OpenCode] SSE: data appended ({} chars total)", currentData.length)
                    }
                } else if (line.startsWith("id:") || line.startsWith("retry:") || line.startsWith(":")) {
                    logger.debug("[OpenCode] SSE: skipping metadata/comment line")
                } else if (line.startsWith("{")) {
                    logger.info("[OpenCode] SSE: raw JSON line detected (not SSE-wrapped)")
                    flowCollector.processSseEvent("message", line, totalChars)?.let { chars ->
                        totalChars = chars
                    }
                } else {
                    logger.debug("[OpenCode] SSE: unrecognized line format: '{}'", line.take(100))
                }
            }

            if (currentData.isNotEmpty()) {
                val eventType = currentEvent ?: "message"
                eventCount++
                logger.info("[OpenCode] SSE FINAL EVENT: type='{}', data={} chars", eventType, currentData.length)
                flowCollector.processSseEvent(eventType, currentData.toString(), totalChars)?.let { chars ->
                    totalChars = chars
                }
            }

            logger.info("[OpenCode] === SSE STREAM COMPLETE ===")
            logger.info("[OpenCode] SSE stats: {} lines, {} events, {} chars, {}ms elapsed", lineCount, eventCount, totalChars, System.currentTimeMillis() - streamStartTime)
        }

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

        if (data.isBlank()) {
            logger.debug("[OpenCode] processSseEvent: blank data, skipping")
            return runningTotal
        }

        logger.info("[OpenCode] processSseEvent: type='{}', data={} chars, preview='{}'", eventType, data.length, data.take(150))

        // Check for daemon error response (plain JSON, not SSE-wrapped)
        // Daemon errors come as {"name":"XxxError","data":{"message":"..."}}
        val isDaemonError = runCatching {
            val el = Json.parseToJsonElement(data)
            if (el !is JsonObject) return@runCatching false
            val name = (el["name"] as? JsonPrimitive)?.content ?: ""
            name.endsWith("Error") || name.endsWith("Request")
        }.getOrDefault(false)
        if (isDaemonError) {
            logger.error("[OpenCode] Daemon error detected: {}", data.take(500))
            return runningTotal
        }

        if (data.startsWith("{")) {
            // Parse the JSON to determine event type
            val jsonElement = runCatching { 
                Json.parseToJsonElement(data) 
            }.onFailure { e ->
                logger.warn("[OpenCode] Failed to parse JSON: {}", e.message)
            }.getOrNull()
            
            if (jsonElement is JsonObject) {
                logger.debug("[OpenCode] JSON keys: {}", jsonElement.keys)
                
                // Check for "info" event (metadata) - skip it
                if (jsonElement.containsKey("info")) {
                    logger.info("[OpenCode] Skipping 'info' event (metadata)")
                    return runningTotal
                }

                // Check for "type" field (DaemonPart format)
                val part = runCatching { 
                    daemonJson.decodeFromString<DaemonPart>(data) 
                }.onFailure { e ->
                    logger.debug("[OpenCode] Failed to parse as DaemonPart: {}", e.message)
                }.getOrNull()
                
                if (part != null) {
                    logger.info("[OpenCode] Parsed as DaemonPart: type='{}', text={} chars", part.type, part.text?.length ?: 0)
                    return when (part.type) {
                        "text" -> {
                            val text = part.text ?: run {
                                logger.warn("[OpenCode] DaemonPart type='text' but no text field")
                                return runningTotal
                            }
                            runningTotal += text.length
                            logger.info("[OpenCode] Emitting text chunk: {} chars", text.length)
                            emit(LlmChunk(content = text, reasoning = null))
                            runningTotal
                        }
                        "reasoning" -> {
                            val reasoning = part.text ?: run {
                                logger.warn("[OpenCode] DaemonPart type='reasoning' but no text field")
                                return runningTotal
                            }
                            logger.info("[OpenCode] Emitting reasoning chunk: {} chars", reasoning.length)
                            emit(LlmChunk(content = null, reasoning = reasoning))
                            runningTotal
                        }
                        "tool_use", "tool" -> {
                            val toolName = part.name ?: "unknown"
                            val toolInput = part.input?.toString() ?: ""
                            logger.info("[OpenCode] Emitting tool_use: name='{}'", toolName)
                            emit(LlmChunk(content = null, toolCall = LlmToolCall(
                                id = "tool-${System.currentTimeMillis()}",
                                functionName = toolName,
                                arguments = toolInput,
                            )))
                            runningTotal
                        }
                        else -> {
                            logger.warn("[OpenCode] DaemonPart type '{}' not handled", part.type)
                            runningTotal
                        }
                    }
                } else {
                    logger.debug("[OpenCode] Not a DaemonPart (no 'type' field or parse failed)")
                }

                // Check for tool call format (name field)
                val toolCall = runCatching { 
                    daemonJson.decodeFromString<DaemonToolCall>(data) 
                }.onFailure { e ->
                    logger.debug("[OpenCode] Failed to parse as DaemonToolCall: {}", e.message)
                }.getOrNull()
                
                if (toolCall != null && toolCall.name != null) {
                    logger.info("[OpenCode] Emitting tool call: name='{}'", toolCall.name)
                    emit(LlmChunk(content = null, toolCall = LlmToolCall(
                        id = toolCall.id ?: "tool-${System.currentTimeMillis()}",
                        functionName = toolCall.name,
                        arguments = toolCall.input?.toString() ?: "",
                    )))
                    return runningTotal
                } else {
                    logger.debug("[OpenCode] Not a DaemonToolCall (no 'name' field or parse failed)")
                }
                
                logger.warn("[OpenCode] JSON object not recognized as any known format: keys={}", jsonElement.keys)
            } else {
                logger.debug("[OpenCode] Data starts with '{' but is not a JSON object")
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
                logger.warn("[OpenCode] Unknown SSE event type: '{}' (data={} chars)", eventType, data.length)
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
    val parts: List<JsonObject>,
    val model: String? = null,
    val agent: String? = null,
    val noReply: Boolean? = null,
    val system: String? = null,
    val tools: JsonObject? = null,
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
