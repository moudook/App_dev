package com.example.smarty.server.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID

private val kotlinx.serialization.json.JsonElement?.safeContent: String?
    get() = if (this == null || this is kotlinx.serialization.json.JsonNull) null else this.jsonPrimitive.content

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

    private val daemonJson =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

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
            val jsonObj =
                kotlinx.serialization.json.buildJsonObject {
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
        onExternalSessionCreated: suspend (String) -> Unit,
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
        logger.info(
            "[OpenCode] generate() completed in {}ms — content={} chars, toolCalls={}, reasoning={} chars",
            duration,
            content.length,
            toolCalls.size,
            reasoning.length,
        )
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
        onExternalSessionCreated: suspend (String) -> Unit,
    ): Flow<LlmChunk> =
        flow {
            val inferenceId = java.util.UUID.randomUUID().toString().take(8)
            val context = StreamContext(inferenceId)

            logger.info(
                "[OpenCode.Session][inference=$inferenceId] stream() starting — requestedModel=${model ?: "default"}, messages=${messages.size}, tools=${tools.size}",
            )

            val selectedModel = OpencodeModelRegistry.requireAllowedFreeModel(model ?: defaultModel)
            val slashIndex = selectedModel.indexOf('/')
            val providerId = if (slashIndex > 0) selectedModel.substring(0, slashIndex) else "opencode"
            val modelId = if (slashIndex > 0) selectedModel.substring(slashIndex + 1) else selectedModel

            logger.info(
                "[OpenCode.Session][inference=$inferenceId] Model resolution: requestedModel=${model ?: "default"}, resolvedProvider=$providerId, resolvedModel=$modelId",
            )

            val sessionStart = System.currentTimeMillis()
            val daemonSessionId =
                externalSessionId ?: createDaemonSession().also {
                    onExternalSessionCreated(it)
                }

            if (!sessionMessageCount.containsKey(daemonSessionId)) {
                sessionMessageCount[daemonSessionId] = 0
                saveSessionMessageCount()
            }
            val previouslySentCount = sessionMessageCount[daemonSessionId] ?: 0

            context.sessionCreateMs = System.currentTimeMillis() - sessionStart
            logger.info("[OpenCode.Session][inference=$inferenceId] Daemon session: $daemonSessionId in ${context.sessionCreateMs}ms")

            var systemPrompt = extractSystemPrompt(messages)
            val newMessages = messages.drop(previouslySentCount)

            val parts =
                newMessages.mapNotNull { msg ->
                    when (msg.role) {
                        LlmMessage.Role.USER ->
                            buildJsonObject {
                                put("type", "text")
                                put("text", msg.content)
                            }
                        LlmMessage.Role.ASSISTANT ->
                            buildJsonObject {
                                put("type", "text")
                                val thinking = msg.thinking?.takeIf { it.isNotBlank() }?.let { "<think>\n$it\n</think>\n" } ?: ""
                                put("text", "$thinking${msg.content}")
                            }
                        LlmMessage.Role.TOOL ->
                            buildJsonObject {
                                put("type", "tool_return")
                                put("name", msg.name ?: "tool")
                                put(
                                    "output",
                                    buildJsonObject {
                                        put("result", msg.content)
                                    },
                                )
                            }
                        LlmMessage.Role.SYSTEM -> null
                    }
                }

            sessionMessageCount[daemonSessionId] = messages.size
            saveSessionMessageCount()

            logger.info("[OpenCode.Session][inference=$inferenceId] Sending ${parts.size} parts (delta: ${newMessages.size} msgs)")

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
                                model =
                                    buildJsonObject {
                                        put("providerID", providerId)
                                        put("modelID", modelId)
                                    },
                                agent = agentName,
                                system = systemPrompt,
                            ),
                        )
                    }.execute { response ->
                        context.requestSendMs = System.currentTimeMillis() - context.startTime
                        val channel = response.bodyAsChannel()
                        var currentEvent: String? = null
                        val currentData = StringBuilder()
                        var lineCount = 0
                        var eventCount = 0

                        while (!channel.isClosedForRead) {
                            val line = channel.readUTF8Line() ?: break
                            lineCount++

                            if (context.firstByteMs == 0L) {
                                context.firstByteMs = System.currentTimeMillis() - context.startTime
                            }

                            if (line.isEmpty()) {
                                if (currentData.isNotEmpty()) {
                                    val eventType = currentEvent ?: "message"
                                    eventCount++
                                    val parseStart = System.currentTimeMillis()
                                    flowCollector.processSseEvent(eventType, currentData.toString(), context)
                                    context.totalParseTime += System.currentTimeMillis() - parseStart
                                }
                                currentEvent = null
                                currentData.setLength(0)
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
                            } else if (line.startsWith("{")) {
                                val parseStart = System.currentTimeMillis()
                                flowCollector.processSseEvent("message", line, context)       
                                context.totalParseTime += System.currentTimeMillis() - parseStart
                            }
                        }

                        if (currentData.isNotEmpty()) {
                            val eventType = currentEvent ?: "message"
                            val parseStart = System.currentTimeMillis()
                            flowCollector.processSseEvent(eventType, currentData.toString(), context)
                            context.totalParseTime += System.currentTimeMillis() - parseStart 
                        }
                    }
                    break
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (attempt >= maxAttempts) throw e
                    kotlinx.coroutines.delay(backoff)
                    backoff *= 2
                }
            }
        }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<LlmChunk>.processSseEvent(
        eventType: String,
        data: String,
        context: StreamContext,
    ) {
        val inferenceId = context.inferenceId
        if (data.isBlank()) return

        logger.trace("[OpenCode.Replay][inference=$inferenceId] RAW PAYLOAD: $data")

        val isDaemonError =
            runCatching {
                val el = Json.parseToJsonElement(data)
                if (el !is JsonObject) return@runCatching false
                val name = (el["name"] as? JsonPrimitive)?.content ?: ""
                name.endsWith("Error") || name.endsWith("Request")
            }.getOrDefault(false)
            
        if (isDaemonError) {
            emit(LlmChunk(content = null, rawJson = data, sseEvent = "error"))
            return
        }

        if (data.startsWith("{")) {
            val jsonElement = runCatching { Json.parseToJsonElement(data) }.getOrNull()

            if (jsonElement is JsonObject) {
                val canonicalResponse = parseCanonicalResponse(jsonElement, inferenceId)

                if (canonicalResponse != null && canonicalResponse.parts.isNotEmpty()) {
                    context.partsExisted = true
                    for (i in 0 until canonicalResponse.parts.size) {
                        val part = canonicalResponse.parts[i]
                        context.partCount++

                        // Only attach rawJson to the first emitted chunk of a multi-part frame 
                        // to prevent duplicate raw events in the client.
                        val rawDataForChunk = if (i == 0) data else null

                        val chunk = when (part.type) {
                            "text" -> {
                                if (!part.content.isNullOrEmpty()) {
                                    context.textChars += part.content.length
                                    if (context.firstTokenMs == 0L) context.firstTokenMs = System.currentTimeMillis() - context.startTime
                                    LlmChunk(content = part.content, reasoning = null, subagentId = part.subagentId, rawJson = rawDataForChunk, sseEvent = eventType)
                                } else null
                            }
                            "reasoning" -> {
                                if (!part.content.isNullOrEmpty()) {
                                    LlmChunk(content = null, reasoning = part.content, subagentId = part.subagentId, rawJson = rawDataForChunk, sseEvent = eventType)
                                } else null
                            }
                            "tool_use", "tool" -> {
                                context.toolCalls++
                                LlmChunk(
                                    content = null,
                                    toolCall = LlmToolCall(
                                        id = "tool-${System.currentTimeMillis()}-$i",
                                        functionName = part.toolName ?: "unknown",
                                        arguments = part.toolArgs ?: "",
                                    ),
                                    subagentId = part.subagentId,
                                    rawJson = rawDataForChunk,
                                    sseEvent = eventType,
                                )
                            }
                            "tool_result", "tool_result_delta", "tool_return" -> {
                                if (!part.content.isNullOrEmpty()) {
                                    LlmChunk(
                                        content = null,
                                        toolResult = LlmToolResult(
                                            functionName = part.toolName ?: "unknown",
                                            result = part.content,
                                        ),
                                        subagentId = part.subagentId,
                                        rawJson = rawDataForChunk,
                                        sseEvent = eventType,
                                    )
                                } else null
                            }
                            else -> null
                        }

                        if (chunk != null) {
                            val emitStart = System.currentTimeMillis()
                            emit(chunk)
                            context.totalEmitTime += System.currentTimeMillis() - emitStart
                        }
                    }
                    return
                }
            }
        }

        val rawEmitStart = System.currentTimeMillis()
        emit(LlmChunk(content = null, rawJson = data, sseEvent = eventType))
        context.totalEmitTime += System.currentTimeMillis() - rawEmitStart
    }

    private suspend fun createDaemonSession(): String {
        val response =
            client.post("$daemonBaseUrl/session") {
                contentType(ContentType.Application.Json)
                setBody(DaemonSessionRequest())
            }
        if (response.status.value !in 200..299) throw IllegalStateException("OpenCode daemon failed to start session")
        val result = response.body<DaemonSessionResponse>()
        return result.id
    }

    private fun extractSystemPrompt(messages: List<LlmMessage>): String? {
        return messages.filter { it.role == LlmMessage.Role.SYSTEM }.joinToString("\n\n") { it.content }.takeIf { it.isNotBlank() }
    }

    private class StreamContext(val inferenceId: String, val startTime: Long = System.currentTimeMillis()) {
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
private data class DaemonSessionRequest(val parentID: String? = null, val title: String? = null)

@Serializable
private data class DaemonSessionResponse(val id: String)

@Serializable
private data class DaemonMessageRequest(val parts: List<JsonObject>, val model: JsonObject? = null, val agent: String? = null, val noReply: Boolean? = null, val system: String? = null)

private data class CanonicalPart(val type: String, val content: String? = null, val toolName: String? = null, val toolArgs: String? = null, val subagentId: String? = null)

private data class CanonicalResponse(val parts: List<CanonicalPart>)

private fun parseCanonicalResponse(json: JsonObject, inferenceId: String): CanonicalResponse? {
    val topSubagentId = json["subagent_id"]?.jsonPrimitive?.content
    val partsArray = json["parts"]?.jsonArray
    if (partsArray != null) {
        val canonicalParts = partsArray.mapNotNull { el ->
            if (el !is JsonObject) return@mapNotNull null
            val type = el["type"]?.jsonPrimitive?.content ?: "unknown"
            val partSubagentId = el["subagent_id"]?.jsonPrimitive?.content ?: topSubagentId
            when (type) {
                "text", "reasoning", "content" -> CanonicalPart(type = if (type == "content") "text" else type, content = el["text"].safeContent ?: el["delta"].safeContent ?: el["content"].safeContent, subagentId = partSubagentId)
                "tool_use", "tool", "call" -> {
                    val callObj = el["call"]?.jsonObject
                    CanonicalPart(type = "tool_use", toolName = callObj?.get("name").safeContent ?: el["name"].safeContent ?: el["tool"].safeContent, toolArgs = callObj?.get("arguments")?.toString() ?: el["input"]?.toString() ?: el["arguments"]?.toString(), subagentId = partSubagentId)
                }
                "tool_result", "tool_return", "result" -> CanonicalPart(type = "tool_result", toolName = el["name"].safeContent ?: el["tool"].safeContent, content = el["output"]?.toString() ?: el["result"]?.toString() ?: el["text"].safeContent, subagentId = partSubagentId)
                else -> CanonicalPart(type = type, subagentId = partSubagentId)
            }
        }
        return CanonicalResponse(canonicalParts)
    }
    val partType = json["type"].safeContent
    if (partType != null) {
        val innerPart = json["part"]?.jsonObject ?: json
        val partSubagentId = innerPart["subagent_id"].safeContent ?: json["subagent_id"].safeContent ?: topSubagentId
        val content = innerPart["delta"].safeContent ?: innerPart["text"].safeContent ?: innerPart["content"].safeContent ?: json["delta"].safeContent ?: json["text"].safeContent
        val toolName = innerPart["tool"].safeContent ?: innerPart["name"].safeContent ?: json["name"].safeContent
        val rawInput = innerPart["input"] ?: innerPart["arguments"] ?: json["input"] ?: json["arguments"]
        val toolArgs = if (rawInput != null && rawInput !is kotlinx.serialization.json.JsonNull) rawInput.toString() else null
        val rawOutput = innerPart["output"] ?: innerPart["result"] ?: json["output"] ?: json["result"]
        val toolOutput = if (rawOutput != null && rawOutput !is kotlinx.serialization.json.JsonNull) { if (rawOutput is kotlinx.serialization.json.JsonPrimitive && rawOutput.isString) rawOutput.content else rawOutput.toString() } else { null }
        val partsToReturn = mutableListOf<CanonicalPart>()
        when (partType) {
            "text", "content" -> partsToReturn.add(CanonicalPart(type = "text", content = content, subagentId = partSubagentId))
            "reasoning", "thought" -> partsToReturn.add(CanonicalPart(type = "reasoning", content = content, subagentId = partSubagentId))
            "tool_use", "tool", "call" -> {
                partsToReturn.add(CanonicalPart(type = "tool_use", toolName = toolName, toolArgs = toolArgs, subagentId = partSubagentId))
                if (toolOutput != null) partsToReturn.add(CanonicalPart(type = "tool_result", toolName = toolName, content = toolOutput, subagentId = partSubagentId))
            }
            "tool_result", "tool_return", "result" -> partsToReturn.add(CanonicalPart(type = "tool_result", toolName = toolName, content = toolOutput ?: content, subagentId = partSubagentId))
            "part-delta", "part_delta" -> { val subType = json["part_type"].safeContent ?: "text"; partsToReturn.add(CanonicalPart(type = subType, content = content, subagentId = partSubagentId)) }
        }
        if (partsToReturn.isNotEmpty()) return CanonicalResponse(partsToReturn)
    }
    val contentStr = json["content"].safeContent ?: json["text"].safeContent
    if (contentStr != null) return CanonicalResponse(listOf(CanonicalPart(type = "text", content = contentStr, subagentId = topSubagentId)))
    return null
}
