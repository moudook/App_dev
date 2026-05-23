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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    
    private val stateFile = java.io.File(System.getProperty("java.io.tmpdir"), "opencode_session_state.json")
    
    // Tracks how many messages from the history have already been sent to the daemon session
    private val sessionMessageCount = loadSessionMessageCount()

    private fun loadSessionMessageCount(): java.util.concurrent.ConcurrentHashMap<String, Int> {
        val map = java.util.concurrent.ConcurrentHashMap<String, Int>()
        try {
            if (stateFile.exists()) {
                val jsonStr = stateFile.readText()
                val jsonObj = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr).jsonObject
                jsonObj.forEach { (k, v) ->
                    map[k] = v.jsonPrimitive.content.toInt()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to load OpenCode session state: ${e.message}")
        }
        return map
    }

    private fun saveSessionMessageCount() {
        try {
            val jsonObj = kotlinx.serialization.json.buildJsonObject {
                sessionMessageCount.forEach { (k, v) ->
                    put(k, kotlinx.serialization.json.JsonPrimitive(v))
                }
            }
            stateFile.writeText(jsonObj.toString())
        } catch (e: Exception) {
            logger.warn("Failed to save OpenCode session state: ${e.message}")
        }
    }

    override suspend fun generate(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?,
        externalSessionId: String?,
        onExternalSessionCreated: suspend (String) -> Unit
    ): LlmResponse {
        logger.info("[OpenCode] generate() called — model={}, messages={}, tools={}", model ?: "default", messages.size, tools.size)
        val startTime = System.currentTimeMillis()
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<LlmToolCall>()
        stream(messages, tools, model, externalSessionId, onExternalSessionCreated).collect { chunk ->
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
        externalSessionId: String?,
        onExternalSessionCreated: suspend (String) -> Unit
    ): Flow<LlmChunk> = flow {
        val inferenceId = java.util.UUID.randomUUID().toString().take(8)
        val context = StreamContext(inferenceId)
        
        logger.info("[OpenCode.Session][inference=$inferenceId] stream() starting — requestedModel=${model ?: "default"}, messages=${messages.size}, tools=${tools.size}")

        val selectedModel = OpencodeModelRegistry.requireAllowedFreeModel(model ?: defaultModel)
        val slashIndex = selectedModel.indexOf('/')
        val providerId = if (slashIndex > 0) selectedModel.substring(0, slashIndex) else "opencode"
        val modelId = if (slashIndex > 0) selectedModel.substring(slashIndex + 1) else selectedModel

        logger.info("[OpenCode.Session][inference=$inferenceId] Model resolution: " +
            "requestedModel=${model ?: "default"}, " +
            "resolvedProvider=$providerId, " +
            "resolvedModel=$modelId")

        val sessionStart = System.currentTimeMillis()
        val daemonSessionId = externalSessionId ?: createDaemonSession().also {
            onExternalSessionCreated(it)
        }
        
        // Ensure we track this session if we haven't seen it in this memory lifecycle
        if (!sessionMessageCount.containsKey(daemonSessionId)) {
            sessionMessageCount[daemonSessionId] = 0
            saveSessionMessageCount()
        }
        val previouslySentCount = sessionMessageCount[daemonSessionId] ?: 0
        
        context.sessionCreateMs = System.currentTimeMillis() - sessionStart
        logger.info("[OpenCode.Session][inference=$inferenceId] Daemon session: $daemonSessionId in ${context.sessionCreateMs}ms")

        var systemPrompt = extractSystemPrompt(messages)
        
        // Tool definitions are now handled natively via the MCP server integration.
        // We no longer inject custom XML tool schemas into the system prompt.
        logger.info("[OpenCode.Session][inference=$inferenceId] Friday system prompt: ${systemPrompt?.length ?: 0} chars")

        val newMessages = messages.drop(previouslySentCount)
        
        val parts = newMessages.mapNotNull { msg ->
            when (msg.role) {
                LlmMessage.Role.USER -> buildJsonObject {
                    put("type", "text")
                    put("text", msg.content)
                }
                LlmMessage.Role.ASSISTANT -> buildJsonObject {
                    put("type", "text")
                    val thinking = msg.thinking?.takeIf { it.isNotBlank() }?.let { "<think>\n$it\n</think>\n" } ?: ""
                    put("text", "$thinking${msg.content}")
                }
                LlmMessage.Role.TOOL -> buildJsonObject {
                    put("type", "tool_return")
                    put("name", msg.name ?: "tool")
                    put("output", buildJsonObject {
                        put("result", msg.content)
                    })
                }
                LlmMessage.Role.SYSTEM -> null
            }
        }
        
        // Update the tracker so next iteration only sends newly appended tool results/messages
        sessionMessageCount[daemonSessionId] = messages.size
        saveSessionMessageCount()

        logger.info("[OpenCode.Session][inference=$inferenceId] Sending ${parts.size} parts (delta: ${newMessages.size} msgs)")

        logger.info("[OpenCode.Session][inference=$inferenceId] POST /session/$daemonSessionId/message — agent=$agentName, tools=${tools.size}, system=${systemPrompt?.length ?: 0}chars")

        val flowCollector = this

        var attempt = 0
        val maxAttempts = 3
        var backoff = 1000L

        while (true) {
            attempt++
            try {
                client.preparePost("$daemonBaseUrl/session/$daemonSessionId/message") {
                    contentType(ContentType.Application.Json)
                    header("Accept", "text/event-stream")
                    setBody(
                        DaemonMessageRequest(
                            parts = parts,
                            model = buildJsonObject {
                                put("providerID", providerId)
                                put("modelID", modelId)
                            },
                            agent = agentName,
                            system = systemPrompt,
                        )
                    )
                }.execute { response ->
            context.requestSendMs = System.currentTimeMillis() - context.startTime

            val contentType = response.contentType()?.toString() ?: "unknown"
            val streamFraming = when {
                contentType.contains("event-stream", ignoreCase = true) -> "SSE_EVENT_STREAM"
                contentType.contains("json", ignoreCase = true) -> "SINGLE_JSON"
                else -> "UNKNOWN"
            }

            logger.info("[OpenCode.Transport][inference=$inferenceId] HTTP response: status=${response.status.value}, contentType=$contentType, contentLength=${response.headers["Content-Length"]}")
            logger.info("[OpenCode.Transport][inference=$inferenceId] Stream framing detected: $streamFraming")

            val channel = response.bodyAsChannel()
            var currentEvent: String? = null
            val currentData = StringBuilder()
            var lineCount = 0
            var eventCount = 0

            logger.info("[OpenCode.Transport][inference=$inferenceId] === SSE STREAM STARTED ===")

            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                lineCount++

                if (context.firstByteMs == 0L) {
                    context.firstByteMs = System.currentTimeMillis() - context.startTime
                }

                if (context.sampleRawResponse != null) {
                    context.sampleRawResponse?.append(line)?.append("\n")
                }

                // Phase A - Transport Read
                logger.trace("[OpenCode.Parser][inference=$inferenceId] Transport line #$lineCount (${line.length} chars)")

                if (line.isEmpty()) {
                    if (currentData.isNotEmpty()) {
                        val eventType = currentEvent ?: "message"
                        eventCount++
                        logger.info("[OpenCode.Parser][inference=$inferenceId] SSE EVENT #$eventCount: type='$eventType', data=${currentData.length} chars")
                        
                        val parseStart = System.currentTimeMillis()
                        flowCollector.processSseEvent(eventType, currentData.toString(), context)
                        context.totalParseTime += System.currentTimeMillis() - parseStart
                    } else {
                        logger.debug("[OpenCode.Parser][inference=$inferenceId] SSE: empty line (no data to process)")
                    }
                    currentEvent = null
                    currentData.setLength(0)
                    continue
                }

                if (line.startsWith("event:")) {
                    currentEvent = line.substringAfter("event:").trim()
                    logger.debug("[OpenCode.Parser][inference=$inferenceId] SSE: event type set to '$currentEvent'")
                } else if (line.startsWith("data:")) {
                    val data = line.substringAfter("data:").trim()
                    if (data.isNotEmpty()) {
                        if (currentData.isNotEmpty()) currentData.append("\n")
                        currentData.append(data)
                        logger.debug("[OpenCode.Parser][inference=$inferenceId] SSE: data appended (${currentData.length} chars total)")
                    }
                } else if (line.startsWith("id:") || line.startsWith("retry:") || line.startsWith(":")) {
                    logger.debug("[OpenCode.Parser][inference=$inferenceId] SSE: skipping metadata/comment line")
                } else if (line.startsWith("{")) {
                    logger.info("[OpenCode.Parser][inference=$inferenceId] SSE: raw JSON line detected (not SSE-wrapped)")
                    val parseStart = System.currentTimeMillis()
                    flowCollector.processSseEvent("message", line, context)
                    context.totalParseTime += System.currentTimeMillis() - parseStart
                } else {
                    logger.debug("[OpenCode.Parser][inference=$inferenceId] SSE: unrecognized line format: '${line.take(100)}'")
                }
            }

            if (currentData.isNotEmpty()) {
                val eventType = currentEvent ?: "message"
                eventCount++
                logger.info("[OpenCode.Parser][inference=$inferenceId] SSE FINAL EVENT: type='$eventType', data=${currentData.length} chars")
                val parseStart = System.currentTimeMillis()
                flowCollector.processSseEvent(eventType, currentData.toString(), context)
                context.totalParseTime += System.currentTimeMillis() - parseStart
            }

            logger.info("[OpenCode.Transport][inference=$inferenceId] === SSE STREAM COMPLETE ===")
            
            // Assertion warning
            if (context.partsExisted && context.textChars == 0) {
                logger.error("[OpenCode.Semantics][inference=$inferenceId] Parts existed but no text emitted — parser mismatch likely")
            }

            // Save raw response sample if enabled
            if (context.sampleRawResponse != null) {
                runCatching {
                    val sampleDir = java.io.File("C:\\Users\\gbust\\.gemini\\antigravity\\brain\\e2e50583-2f36-4d7b-b40d-a5a1189e8acf\\scratch")
                    if (!sampleDir.exists()) sampleDir.mkdirs()
                    val sampleFile = java.io.File(sampleDir, "raw_response_$inferenceId.json")
                    sampleFile.writeText(context.sampleRawResponse.toString())
                    logger.info("[OpenCode.Session][inference=$inferenceId] Raw response sample saved to: ${sampleFile.absolutePath}")
                }.onFailure { e ->
                    logger.warn("[OpenCode.Session][inference=$inferenceId] Failed to save raw response sample: ${e.message}")
                }
            }

            logger.info("[OpenCode.Session][inference=$inferenceId] Stream ended: reason=completed, parts=${context.partCount}, textChars=${context.textChars}, toolCalls=${context.toolCalls}")
        }
        break // Break out of retry loop on success
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) {
            logger.info("[OpenCode.Transport][inference=$inferenceId] Request was cancelled by client")
            // Try to abort on daemon if possible
            runCatching {
                val url = java.net.URL("$daemonBaseUrl/session/$daemonSessionId/abort")
                val con = url.openConnection() as java.net.HttpURLConnection
                con.requestMethod = "POST"
                con.connectTimeout = 2000
                con.readTimeout = 2000
                con.responseCode // Trigger request
            }.onFailure { err ->
                logger.debug("[OpenCode.Transport][inference=$inferenceId] Failed to call abort endpoint: ${err.message}")
            }
            throw e
        }
        if (context.firstByteMs > 0L) {
            logger.error("[OpenCode.Transport][inference=$inferenceId] Connection lost mid-stream. Aborting to prevent duplicate message submission: ${e.message}", e)
            throw e
        }
        if (attempt >= maxAttempts) {
            logger.error("[OpenCode.Transport][inference=$inferenceId] Request failed after $maxAttempts attempts: ${e.message}", e)
            throw e
        }
        logger.warn("[OpenCode.Transport][inference=$inferenceId] Network error (attempt $attempt/$maxAttempts): ${e.message}. Retrying in ${backoff}ms...")
        kotlinx.coroutines.delay(backoff)
        backoff *= 2
    }
}

        val totalMs = System.currentTimeMillis() - context.startTime
        logger.info("[OpenCode.Session][inference=$inferenceId] Latency breakdown: " +
            "sessionCreateMs=${context.sessionCreateMs}, " +
            "requestSendMs=${context.requestSendMs}, " +
            "firstByteMs=${context.firstByteMs}, " +
            "firstTokenMs=${context.firstTokenMs}, " +
            "parseMs=${context.totalParseTime}, " +
            "emitMs=${context.totalEmitTime}, " +
            "totalMs=$totalMs")
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<LlmChunk>.processSseEvent(
        eventType: String,
        data: String,
        context: StreamContext,
    ) {
        val inferenceId = context.inferenceId
        if (data.isBlank()) {
            logger.debug("[OpenCode.Parser][inference=$inferenceId] processSseEvent: blank data, skipping")
            return
        }

        logger.info("[OpenCode.Parser][inference=$inferenceId] processSseEvent: type='$eventType', data=${data.length} chars, preview='${data.take(150).replace("\n", "\\n")}'")

        // === 1. EVENT REPLAY LOGGING ===
        // We log the raw payload immediately so it's never lost if parsing fails
        logger.trace("[OpenCode.Replay][inference=$inferenceId] RAW PAYLOAD: $data")

        // Check for daemon error response (plain JSON, not SSE-wrapped)
        // Daemon errors come as {"name":"XxxError","data":{"message":"..."}}
        val isDaemonError = runCatching {
            val el = Json.parseToJsonElement(data)
            if (el !is JsonObject) return@runCatching false
            val name = (el["name"] as? JsonPrimitive)?.content ?: ""
            name.endsWith("Error") || name.endsWith("Request")
        }.getOrDefault(false)
        if (isDaemonError) {
            logger.error("[OpenCode.Parser][inference=$inferenceId] Daemon error detected: ${data.take(500)}")
            return
        }

        if (data.startsWith("{")) {
            // Parse the JSON to determine event type
            val jsonElement = runCatching {
                Json.parseToJsonElement(data)
            }.onFailure { e ->
                logger.error("[OpenCode.Parser][inference=$inferenceId] JSON parse failed on SSE line", e)
            }.getOrNull()
            
            if (jsonElement is JsonObject) {
                // === 2. CANONICAL INTERNAL SCHEMAS & PARSER VERSIONING ===
                // This shields the orchestration layer from underlying API format changes
                val canonicalResponse = parseCanonicalResponse(jsonElement, inferenceId)
                
                if (canonicalResponse != null && canonicalResponse.parts.isNotEmpty()) {
                    context.partsExisted = true
                    logger.debug("[OpenCode.Semantics][inference=$inferenceId] Processing ${canonicalResponse.parts.size} canonical parts")
                    
                    for (i in 0 until canonicalResponse.parts.size) {
                        val part = canonicalResponse.parts[i]
                        context.partCount++
                        
                        logger.debug("[OpenCode.Semantics][inference=$inferenceId] Part[$i] type=${part.type}")
                        when (part.type) {
                            "text" -> {
                                val text = part.content
                                if (!text.isNullOrEmpty()) {
                                    val preview = text.take(80).replace("\n", "\\n")
                                    logger.info("[OpenCode.Semantics][inference=$inferenceId] Extracted text chunk: ${text.length} chars (preview='$preview')")
                                    
                                    context.textChars += text.length
                                    if (context.firstTokenMs == 0L) {
                                        context.firstTokenMs = System.currentTimeMillis() - context.startTime
                                    }
                                    
                                    val emitStart = System.currentTimeMillis()
                                    logger.debug("[OpenCode.Emit][inference=$inferenceId] Emitting delta to flow collector (${text.length} chars)")
                                    emit(LlmChunk(content = text, reasoning = null, subagentId = part.subagentId))
                                    logger.trace("[OpenCode.Emit][inference=$inferenceId] Collector accepted chunk")
                                    context.totalEmitTime += System.currentTimeMillis() - emitStart
                                } else {
                                    logger.debug("[OpenCode.Semantics][inference=$inferenceId] Skipping metadata object because no assistant content fields present")
                                }
                            }
                            "reasoning" -> {
                                val reasoning = part.content
                                if (!reasoning.isNullOrEmpty()) {
                                    val preview = reasoning.take(80).replace("\n", "\\n")
                                    logger.info("[OpenCode.Semantics][inference=$inferenceId] Extracted reasoning chunk: ${reasoning.length} chars (preview='$preview')")
                                    
                                    val emitStart = System.currentTimeMillis()
                                    logger.debug("[OpenCode.Emit][inference=$inferenceId] Emitting delta to flow collector (${reasoning.length} chars)")
                                    emit(LlmChunk(content = null, reasoning = reasoning, subagentId = part.subagentId))
                                    logger.trace("[OpenCode.Emit][inference=$inferenceId] Collector accepted chunk")
                                    context.totalEmitTime += System.currentTimeMillis() - emitStart
                                }
                            }
                            "tool_use", "tool" -> {
                                val toolName = part.toolName ?: "unknown"
                                val toolInput = part.toolArgs ?: ""
                                context.toolCalls++
                                logger.info("[OpenCode.Tools][inference=$inferenceId] Emitting tool_use: name='$toolName'")
                                
                                val emitStart = System.currentTimeMillis()
                                emit(LlmChunk(content = null, toolCall = LlmToolCall(
                                    id = "tool-${System.currentTimeMillis()}",
                                    functionName = toolName,
                                    arguments = toolInput
                                ), subagentId = part.subagentId))
                                context.totalEmitTime += System.currentTimeMillis() - emitStart
                            }
                            "tool_result", "tool_return" -> {
                                val content = part.content
                                if (!content.isNullOrEmpty()) {
                                    val toolName = part.toolName ?: "unknown"
                                    logger.info("[OpenCode.Tools][inference=$inferenceId] Emitting tool_result: name='$toolName', length=${content.length}")
                                    
                                    val emitStart = System.currentTimeMillis()
                                    emit(LlmChunk(content = null, toolResult = LlmToolResult(
                                        functionName = toolName,
                                        result = content
                                    ), subagentId = part.subagentId))
                                    context.totalEmitTime += System.currentTimeMillis() - emitStart
                                }
                            }
                            "step-start", "step-finish" -> {
                                logger.debug("[OpenCode.Semantics][inference=$inferenceId] Skipping structural boundary: ${part.type}")
                            }
                            else -> {
                                logger.warn("[OpenCode.Semantics][inference=$inferenceId] Unknown part type='${part.type}'")
                            }
                        }
                    }
                    logger.debug("[OpenCode.Semantics][inference=$inferenceId] Accumulator size now=${context.textChars}")
                    return
                }
                
                logger.warn("[OpenCode.Semantics][inference=$inferenceId] Unknown part or JSON object not recognized: keys=${jsonElement.keys}")
            }
        }

        when (eventType) {
            "error" -> {
                logger.error("[OpenCode.Parser][inference=$inferenceId] SSE error event: $data")
            }
            "end", "message_end", "done", "session-update", "part-start", "part-end" -> {
                logger.debug("[OpenCode.Parser][inference=$inferenceId] SSE control event: $eventType")
            }
            else -> {
                logger.warn("[OpenCode.Parser][inference=$inferenceId] Unknown SSE event type: '$eventType' (data=${data.length} chars)")
            }
        }
    }

    private suspend fun createDaemonSession(): String {
        val response = client.post("$daemonBaseUrl/session") {
            contentType(ContentType.Application.Json)
            setBody(DaemonSessionRequest())
        }

        if (response.status.value !in 200..299) {
            val errorText =
                runCatching { response.body<String>() }.getOrDefault("Unknown error")
            logger.error("[OpenCode] Daemon session create failed: ${response.status} - $errorText")
            throw IllegalStateException(
                "OpenCode daemon failed to start session. Status: ${response.status}"
            )
        }

        val result =
            runCatching { response.body<DaemonSessionResponse>() }.getOrNull()
                ?: throw IllegalStateException(
                    "Daemon /session returned invalid JSON (missing 'id' field)."
                )

        return result.id
    }

    private fun extractSystemPrompt(messages: List<LlmMessage>): String? {
        return messages
            .filter { it.role == LlmMessage.Role.SYSTEM }
            .joinToString("\n\n") { it.content }
            .takeIf { it.isNotBlank() }
    }

    // Removed buildUserMessage as we now map directly to parts

    private class StreamContext(
        val inferenceId: String,
        val startTime: Long = System.currentTimeMillis()
    ) {
        var sessionCreateMs: Long = 0L
        var requestSendMs: Long = 0L
        var firstByteMs: Long = 0L
        var firstTokenMs: Long = 0L
        var totalParseTime: Long = 0L
        var totalEmitTime: Long = 0L
        var partCount: Int = 0
        var textChars: Int = 0
        var toolCalls: Int = 0
        var partsExisted: Boolean = false
        var sampleRawResponse: StringBuilder? = StringBuilder()
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
    val model: JsonObject? = null,
    val agent: String? = null,
    val noReply: Boolean? = null,
    val system: String? = null,
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
    val arguments: JsonObject? = null,
)

// === Canonical Internal Schemas ===
private data class CanonicalPart(
    val type: String, 
    val content: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val subagentId: String? = null
)

private data class CanonicalResponse(
    val parts: List<CanonicalPart>
)

/**
 * Parser Versioning: Attempt multiple schema extractions and normalize to CanonicalResponse
 */
private fun parseCanonicalResponse(json: JsonObject, inferenceId: String): CanonicalResponse? {
    val topSubagentId = json["subagent_id"]?.jsonPrimitive?.content

    val partsArray = json["parts"]?.jsonArray
    if (partsArray != null) {
        // Version 1: OpenCode native parts array
        val canonicalParts = partsArray.mapNotNull { el ->
            if (el !is JsonObject) return@mapNotNull null
            val type = el["type"]?.jsonPrimitive?.content ?: "unknown"
            val partSubagentId = el["subagent_id"]?.jsonPrimitive?.content ?: topSubagentId
            when (type) {
                "text", "reasoning" -> CanonicalPart(type = type, content = el["text"]?.jsonPrimitive?.content, subagentId = partSubagentId)
                "tool_use", "tool" -> CanonicalPart(type = type, toolName = el["name"]?.jsonPrimitive?.content, toolArgs = el["input"]?.toString(), subagentId = partSubagentId)
                "tool_result", "tool_return" -> CanonicalPart(type = "tool_result", toolName = el["name"]?.jsonPrimitive?.content, content = el["output"]?.toString() ?: el["text"]?.jsonPrimitive?.content, subagentId = partSubagentId)
                else -> CanonicalPart(type = type, subagentId = partSubagentId)
            }
        }
        return CanonicalResponse(canonicalParts)
    }

    val contentStr = json["content"]?.jsonPrimitive?.content
    if (contentStr != null) {
        // Version 2: Simple content string fallback
        return CanonicalResponse(listOf(CanonicalPart(type = "text", content = contentStr, subagentId = topSubagentId)))
    }
    
    val messageObj = json["message"]?.jsonObject
    if (messageObj != null) {
        // Version 3: OpenAI style choices/message
        val text = messageObj["content"]?.jsonPrimitive?.content
        if (text != null) {
            return CanonicalResponse(listOf(CanonicalPart(type = "text", content = text, subagentId = topSubagentId)))
        }
    }

    // Version 4: Individual part object (daemon incremental SSE)
    // e.g. {"type": "reasoning", "text": "...", "subagent_id": "sub1"}
    val partType = json["type"]?.jsonPrimitive?.content
    if (partType != null) {
        val partSubagentId = json["subagent_id"]?.jsonPrimitive?.content ?: topSubagentId
        val part = when (partType) {
            "text" -> CanonicalPart(type = "text", content = json["text"]?.jsonPrimitive?.content, subagentId = partSubagentId)
            "reasoning" -> CanonicalPart(type = "reasoning", content = json["text"]?.jsonPrimitive?.content, subagentId = partSubagentId)
            "tool_use", "tool" -> CanonicalPart(type = partType, toolName = json["name"]?.jsonPrimitive?.content, toolArgs = json["input"]?.toString(), subagentId = partSubagentId)
            "tool_result", "tool_return" -> CanonicalPart(type = "tool_result", toolName = json["name"]?.jsonPrimitive?.content, content = json["output"]?.toString() ?: json["text"]?.jsonPrimitive?.content, subagentId = partSubagentId)
            else -> {
                org.slf4j.LoggerFactory.getLogger("OpencodeLlmProvider").warn("[OpenCode.Schemas][inference=$inferenceId] Unrecognized individual part type: '$partType'")
                null
            }
        }
        if (part != null) {
            return CanonicalResponse(listOf(part))
        }
    }

    return null
}
