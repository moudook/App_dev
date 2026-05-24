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
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Resilient JSON content extraction extension properties.
 */
private val JsonElement?.safeStr: String?
    get() = (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.deepStr(): String? {
    if (this == null || this is JsonNull) return null
    if (this is JsonPrimitive) return this.contentOrNull
    if (this is JsonObject) {
        // Search common fields recursively for any value that might be a string
        return this["delta"]?.deepStr() 
            ?: this["text"]?.deepStr() 
            ?: this["content"]?.deepStr() 
            ?: this["result"]?.deepStr() 
            ?: this["output"]?.deepStr() 
            ?: this["data"]?.deepStr()
            ?: if (this.keys.size == 1 && this.values.first() is JsonPrimitive) this.values.first().jsonPrimitive.contentOrNull else this.toString()
    }
    return this.toString()
}

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

    private val stateFile = java.io.File(System.getProperty("java.io.tmpdir"), "opencode_session_state.json")
    private val sessionMessageCount = loadSessionMessageCount()

    private fun loadSessionMessageCount(): java.util.concurrent.ConcurrentHashMap<String, Int> {
        val map = java.util.concurrent.ConcurrentHashMap<String, Int>()
        try {
            if (stateFile.exists()) {
                val jsonObj = Json.parseToJsonElement(stateFile.readText()).jsonObject
                jsonObj.forEach { (k, v) -> map[k] = v.jsonPrimitive.content.toInt() }
            }
        } catch (e: Exception) { logger.warn("Failed to load OpenCode session state: ${e.message}") }
        return map
    }

    private fun saveSessionMessageCount() {
        try {
            val jsonObj = buildJsonObject { sessionMessageCount.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
            stateFile.writeText(jsonObj.toString())
        } catch (e: Exception) { logger.warn("Failed to save OpenCode session state: ${e.message}") }
    }

    override suspend fun generate(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?,
        externalSessionId: String?,
        onExternalSessionCreated: suspend (String) -> Unit,
    ): LlmResponse {
        val content = StringBuilder()
        val toolCalls = mutableListOf<LlmToolCall>()
        stream(messages, tools, model, externalSessionId, onExternalSessionCreated).collect { chunk ->
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
    ): Flow<LlmChunk> = flow {
        val inferenceId = UUID.randomUUID().toString().take(8)
        val context = StreamContext(inferenceId)

        val selectedModel = OpencodeModelRegistry.requireAllowedFreeModel(model ?: defaultModel)
        val slashIndex = selectedModel.indexOf('/')
        val providerId = if (slashIndex > 0) selectedModel.substring(0, slashIndex) else "opencode"
        val modelId = if (slashIndex > 0) selectedModel.substring(slashIndex + 1) else selectedModel

        val daemonSessionId = externalSessionId ?: createDaemonSession().also { onExternalSessionCreated(it) }
        val previouslySentCount = sessionMessageCount.getOrPut(daemonSessionId) { 0 }

        val systemPrompt = messages.filter { it.role == LlmMessage.Role.SYSTEM }.joinToString("\n\n") { it.content }.takeIf { it.isNotBlank() }
        val newMessages = messages.drop(previouslySentCount)

        val parts = newMessages.mapNotNull { msg ->
            when (msg.role) {
                LlmMessage.Role.USER -> buildJsonObject { put("type", "text"); put("text", msg.content) }
                LlmMessage.Role.ASSISTANT -> buildJsonObject { 
                    put("type", "text")
                    val thinking = msg.thinking?.takeIf { it.isNotBlank() }?.let { "<think>\n$it\n</think>\n" } ?: ""
                    put("text", "$thinking${msg.content}")
                }
                LlmMessage.Role.TOOL -> buildJsonObject { 
                    put("type", "tool_return")
                    put("name", msg.name ?: "tool")
                    put("output", buildJsonObject { put("result", msg.content) })
                }
                LlmMessage.Role.SYSTEM -> null
            }
        }

        sessionMessageCount[daemonSessionId] = messages.size
        saveSessionMessageCount()

        logger.info("[OpenCode.Request][inference=$inferenceId] model=$providerId/$modelId, parts=${parts.size}")

        val flowCollector = this
        client.preparePost("$daemonBaseUrl/session/$daemonSessionId/message") {
            contentType(ContentType.Application.Json)
            header("Accept", "text/event-stream")
            setBody(DaemonMessageRequest(
                parts = parts,
                model = buildJsonObject { put("providerID", providerId); put("modelID", modelId) },
                agent = agentName,
                system = systemPrompt
            ))
        }.execute { response ->
            val contentType = response.headers["Content-Type"] ?: "unknown"
            logger.info("[OpenCode.Response][inference=$inferenceId] Status=${response.status}, Content-Type=$contentType")

            val channel = response.bodyAsChannel()
            var currentEvent: String? = null
            val currentData = StringBuilder()

            // ADAPTIVE PARSER: Handle both SSE stream and single large JSON batch
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                
                // UNFILTERED DEBUG LOGGING: Print exactly what the daemon spits out
                logger.info("[DAEMON_RAW][inference=$inferenceId] $line")
                
                if (line.isBlank()) {
                    if (currentData.isNotEmpty()) {
                        flowCollector.processSseEvent(currentEvent ?: "message", currentData.toString(), context)
                        currentData.setLength(0)
                        currentEvent = null
                    }
                    continue
                }

                if (line.startsWith("event:")) {
                    currentEvent = line.substringAfter("event:").trim()
                } else if (line.startsWith("data:")) {
                    val data = line.substringAfter("data:").trim()
                    // Detect NDJSON (line-delimited JSON) inside SSE data
                    if (data.startsWith("{") && data.endsWith("}")) {
                        flowCollector.processSseEvent(currentEvent ?: "message", data, context)
                    } else {
                        if (currentData.isNotEmpty()) currentData.append("\n")
                        currentData.append(data)
                    }
                } else if (line.startsWith("{")) {
                    // This is a direct batch JSON response, not SSE
                    flowCollector.processSseEvent("message", line, context)
                }
            }
            
            // Final flush for remaining data
            if (currentData.isNotEmpty()) {
                flowCollector.processSseEvent(currentEvent ?: "message", currentData.toString(), context)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<LlmChunk>.processSseEvent(
        eventType: String,
        data: String,
        context: StreamContext,
    ) {
        val inferenceId = context.inferenceId
        logger.trace("[OpenCode.SSE][inference=$inferenceId] Raw data: $data")

        // 1. Guaranteed Raw Emission (Immediate)
        emit(LlmChunk(content = null, rawJson = data, sseEvent = eventType))

        // 2. Resilient Semantic Parsing
        if (data.startsWith("{")) {
            val json = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
            
            // Check for Daemon errors
            if ((json["name"].safeStr ?: "").endsWith("Error")) {
                logger.error("[OpenCode.Error][inference=$inferenceId] Daemon error: $data")
                return
            }

            parseCanonicalResponse(json)?.parts?.forEachIndexed { i, part ->
                val chunk = when (part.type) {
                    "text" -> LlmChunk(content = part.content, reasoning = null, subagentId = part.subagentId, sseEvent = eventType)
                    "reasoning" -> LlmChunk(content = null, reasoning = part.content, subagentId = part.subagentId, sseEvent = eventType)
                    "tool_use", "tool", "call" -> LlmChunk(
                        content = null,
                        toolCall = LlmToolCall("tool-${System.currentTimeMillis()}-$i", part.toolName ?: "unknown", part.toolArgs ?: ""),
                        subagentId = part.subagentId,
                        sseEvent = eventType
                    )
                    "tool_result", "result" -> LlmChunk(
                        content = null,
                        toolResult = LlmToolResult(part.toolName ?: "unknown", part.content ?: ""),
                        subagentId = part.subagentId,
                        sseEvent = eventType
                    )
                    else -> null
                }
                if (chunk != null) emit(chunk)
            }
        }
    }

    private fun parseCanonicalResponse(json: JsonObject): CanonicalResponse? {
        val topSubagentId = json["subagent_id"].safeStr
        
        // V1: Parts Array (Batch/NDJSON responses)
        val partsArray = json["parts"]?.jsonArray
        if (partsArray != null) {
            val parts = partsArray.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val type = obj["type"].safeStr ?: "unknown"
                val sid = obj["subagent_id"].safeStr ?: topSubagentId
                when (type) {
                    "text", "reasoning", "content" -> CanonicalPart(type = if(type=="content") "text" else type, content = obj.deepStr(), subagentId = sid)
                    "tool_use", "tool", "call" -> {
                        val call = obj["call"]?.jsonObject ?: obj
                        CanonicalPart("tool_use", toolName = (call["name"] ?: call["tool"] ?: call["function"]).deepStr(), toolArgs = (call["arguments"] ?: call["input"])?.toString(), subagentId = sid)
                    }
                    "tool_result", "result" -> CanonicalPart("tool_result", toolName = (obj["name"] ?: obj["tool"]).safeStr, content = obj.deepStr(), subagentId = sid)
                    else -> CanonicalPart(type, subagentId = sid)
                }
            }
            return CanonicalResponse(parts)
        }

        // V2: Individual SSE Part (Daemon incremental)
        val type = json["type"].safeStr
        if (type != null) {
            val part = json["part"]?.jsonObject ?: json
            val sid = part["subagent_id"].safeStr ?: topSubagentId
            val content = part.deepStr()
            val toolName = (part["tool"] ?: part["name"] ?: part["function"] ?: json["name"]).deepStr()
            val toolArgs = (part["input"] ?: part["arguments"] ?: json["input"] ?: json["arguments"])?.toString()

            return when (type) {
                "text", "content" -> CanonicalResponse(listOf(CanonicalPart("text", content, subagentId = sid)))
                "reasoning", "thought" -> CanonicalResponse(listOf(CanonicalPart("reasoning", content, subagentId = sid)))
                "tool_use", "tool", "call" -> CanonicalResponse(listOf(CanonicalPart("tool_use", content, toolName, toolArgs, subagentId = sid)))
                "tool_result", "result" -> CanonicalResponse(listOf(CanonicalPart("tool_result", content, toolName, subagentId = sid)))
                "part-delta", "delta" -> {
                    val subType = json["part_type"].safeStr ?: "text"
                    CanonicalResponse(listOf(CanonicalPart(subType, content, subagentId = sid)))
                }
                else -> null
            }
        }
        
        // V3: Top-level fallback
        val content = json.deepStr()
        if (content != null && content != json.toString()) {
            return CanonicalResponse(listOf(CanonicalPart("text", content, subagentId = topSubagentId)))
        }
        
        return null
    }

    private suspend fun createDaemonSession(): String {
        val response = client.post("$daemonBaseUrl/session") {
            contentType(ContentType.Application.Json)
            setBody(DaemonSessionRequest())
        }
        return response.body<DaemonSessionResponse>().id
    }

    private class StreamContext(val inferenceId: String)
}

@Serializable private data class DaemonSessionRequest(val parentID: String? = null)
@Serializable private data class DaemonSessionResponse(val id: String)
@Serializable private data class DaemonMessageRequest(val parts: List<JsonObject>, val model: JsonObject? = null, val agent: String? = null, val system: String? = null)
private data class CanonicalPart(val type: String, val content: String? = null, val toolName: String? = null, val toolArgs: String? = null, val subagentId: String? = null)
private data class CanonicalResponse(val parts: List<CanonicalPart>)
