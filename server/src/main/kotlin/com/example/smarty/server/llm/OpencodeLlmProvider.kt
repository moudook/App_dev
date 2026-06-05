package com.example.smarty.server.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.UUID

private val JsonElement?.safeStr: String?
    get() = (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.deepStr(): String? {
    if (this == null || this is JsonNull) return null
    if (this is JsonPrimitive) return this.contentOrNull
    if (this is JsonObject) {
        return this["delta"]?.deepStr()
            ?: this["text"]?.deepStr()
            ?: this["content"]?.deepStr()
            ?: this["result"]?.deepStr()
            ?: this["output"]?.deepStr()
            ?: this["data"]?.deepStr()
            ?: if (this.keys.size == 1 && this.values.first() is JsonPrimitive) {
                this.values
                    .first()
                    .jsonPrimitive.contentOrNull
            } else {
                this.toString()
            }
    }
    return this.toString()
}

private fun JsonElement?.rawJsonStr(): String? {
    if (this == null || this is JsonNull) return null
    if (this is JsonPrimitive) return this.contentOrNull
    return this.toString()
}

class OpencodeLlmProvider(
    private val client: HttpClient,
    override val providerName: String = "OpenCode CLI",
    private val defaultModel: String = OpencodeModelRegistry.defaultModel,
    private val daemonHost: String = "127.0.0.1",
) : LlmProvider {
    private val logger = LoggerFactory.getLogger(OpencodeLlmProvider::class.java)
    private val daemonBaseUrl get() = "http://$daemonHost:${com.example.smarty.server.agent.OpencodeDaemonManager.daemonPort}"
    private val agentName = System.getenv("OPENCODE_AGENT")?.takeIf { it.isNotBlank() } ?: "smarty-headless-agent"

    companion object {
        private val daemonSemaphore = kotlinx.coroutines.sync.Semaphore(5)
        private val TAG_STRIP_REGEX = Regex("</?(think|final)>", RegexOption.IGNORE_CASE)
        private const val MAX_SSE_LINE_LENGTH = 1_000_000 // 1 MB per line
        private const val MAX_BUSY_RETRIES = 3
        private const val BUSY_RETRY_DELAY_MS = 5_000L

        fun stripThinkFinalTags(text: String): String = text.replace(TAG_STRIP_REGEX, "").trim()
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
            val context = StreamContext(inferenceId)

            val selectedModel = OpencodeModelRegistry.requireAllowedFreeModel(model ?: defaultModel)
            val slashIndex = selectedModel.indexOf('/')
            val providerId = if (slashIndex > 0) selectedModel.substring(0, slashIndex) else "opencode"
            val modelId = if (slashIndex > 0) selectedModel.substring(slashIndex + 1) else selectedModel

            var activeSessionId = externalSessionId ?: createDaemonSession().also { onExternalSessionCreated(it) }
            val flowCollector = this

            var isNotFound = false

            suspend fun tryExecuteStream(
                sessId: String,
                isRetry: Boolean,
            ): Boolean {
                var localIsNotFound = false
                val systemPrompt =
                    messages
                        .filter { it.role == LlmMessage.Role.SYSTEM }
                        .joinToString("\n\n") { it.content }
                        .takeIf { it.isNotBlank() }
                val conversationMessages = messages.filter { it.role != LlmMessage.Role.SYSTEM }

                val parts =
                    conversationMessages.mapNotNull { msg ->
                        when (msg.role) {
                            LlmMessage.Role.USER ->
                                buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive(msg.content))
                                }
                            LlmMessage.Role.ASSISTANT ->
                                buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive(msg.content))
                                }
                            LlmMessage.Role.TOOL ->
                                buildJsonObject {
                                    put("type", JsonPrimitive("tool_return"))
                                    put("name", JsonPrimitive(msg.name ?: "tool"))
                                    put("output", buildJsonObject { put("result", JsonPrimitive(msg.content)) })
                                }
                            else -> null
                        }
                    }

                val activeVariant = variant?.takeIf { it.isNotBlank() }
                logger.info(
                    "[OpenCode.Request][inference=$inferenceId][session=$sessId] " +
                        "model=$providerId/$modelId, parts=${parts.size}, variant=$activeVariant, isRetry=$isRetry",
                )

                daemonSemaphore.acquire()
                try {
                    client
                        .preparePost("$daemonBaseUrl/session/$sessId/message") {
                            contentType(ContentType.Application.Json)
                            header("Accept", "text/event-stream")
                            setBody(
                                DaemonMessageRequest(
                                    parts = parts,
                                    model =
                                        buildJsonObject {
                                            put("providerID", JsonPrimitive(providerId))
                                            put("modelID", JsonPrimitive(modelId))
                                        },
                                    agent = agentName,
                                    system = systemPrompt,
                                    variant = activeVariant?.let { JsonPrimitive(it) },
                                ),
                            )
                        }.execute { response ->
                            if (response.status.value == 404) {
                                logger.warn("[OpenCode.LlmProvider][inference=$inferenceId] Session $sessId not found (404) on daemon.")
                                localIsNotFound = true
                                return@execute
                            }

                            val contentType = response.headers["Content-Type"] ?: "unknown"
                            logger.info("[OpenCode.Response][inference=$inferenceId] Status=${response.status}, Content-Type=$contentType")

                            // If daemon returns JSON instead of SSE, treat the whole body as one message event
                            if (contentType.contains("application/json", ignoreCase = true)) {
                                val body = response.bodyAsText()
                                logger.debug("[DAEMON_RAW_JSON][inference=$inferenceId] $body")
                                flowCollector.processSseEvent("message", body, context)
                                return@execute
                            }

                            val channel = response.bodyAsChannel()
                            var currentEvent: String? = null
                            val currentData = StringBuilder()
                            var busyRetries = 0

                            while (!channel.isClosedForRead) {
                                val rawLine = channel.readLine() ?: break
                                if (rawLine.length > MAX_SSE_LINE_LENGTH) {
                                    logger.warn(
                                        "[OpenCode.SSE][inference=$inferenceId] Line exceeds ${MAX_SSE_LINE_LENGTH} bytes, skipping",
                                    )
                                    continue
                                }
                                val line =
                                    rawLine.replace(
                                        Regex("\u001B\\][^\u0007]+\u0007|\u001B\\[[;\\d]*[ -/]*[@-~]|\u001B\\][^\u001B]+\u001B\\\\"),
                                        "",
                                    )

                                logger.debug("[DAEMON_RAW][inference=$inferenceId] $line")

                                if (line.isBlank()) {
                                    if (currentData.isNotEmpty()) {
                                        if (!flowCollector.processSseEvent(currentEvent ?: "message", currentData.toString(), context)) {
                                            // Error detected — escalate to caller
                                            return@execute
                                        }
                                        currentData.setLength(0)
                                        currentEvent = null
                                    }
                                    continue
                                }

                                if (line.startsWith("event:")) {
                                    currentEvent = line.substringAfter("event:").trim()
                                } else if (line.startsWith("data:")) {
                                    val data = line.substringAfter("data:").trim()
                                    if (currentData.isNotEmpty()) currentData.append("\n")
                                    if (currentData.length + data.length > MAX_SSE_LINE_LENGTH * 2) {
                                        logger.warn("[OpenCode.SSE][inference=$inferenceId] Multi-line data exceeds max, truncating")
                                        continue
                                    }
                                    currentData.append(data)
                                } else if (line.startsWith("{")) {
                                    if (!flowCollector.processSseEvent("message", line, context)) {
                                        return@execute
                                    }
                                }
                            }

                            if (currentData.isNotEmpty()) {
                                flowCollector.processSseEvent(currentEvent ?: "message", currentData.toString(), context)
                            }
                        }
                } finally {
                    daemonSemaphore.release()
                }
                isNotFound = localIsNotFound
                return !localIsNotFound
            }

            val success = tryExecuteStream(activeSessionId, isRetry = false)
            if (!success && isNotFound) {
                logger.info("[OpenCode.LlmProvider] Recreating session after 404 for inference=$inferenceId")
                activeSessionId = createDaemonSession().also { onExternalSessionCreated(it) }
                tryExecuteStream(activeSessionId, isRetry = true)
            }
        }.flowOn(Dispatchers.IO)

    suspend fun getSessionHistory(sessionId: String): JsonArray? =
        try {
            val response = client.get("$daemonBaseUrl/session/$sessionId/message") {}
            if (response.status.value == 200) {
                Json.parseToJsonElement(response.bodyAsText()).jsonObject["parts"]?.jsonArray
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch session history for $sessionId", e)
            null
        }

    /** Process a single SSE event. Returns false if the stream should stop (error/terminal). */
    private suspend fun FlowCollector<LlmChunk>.processSseEvent(
        eventType: String,
        data: String,
        context: StreamContext,
    ): Boolean {
        val inferenceId = context.inferenceId
        logger.debug("[OpenCode.SSE][inference=$inferenceId] eventType=$eventType data=${data.take(200)}")

        emit(LlmChunk(content = null, rawJson = data, sseEvent = eventType))

        if (data.startsWith("{")) {
            val outerJson = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return true

            // ── Unwrap OpenCode 1.16+ envelope: { type, properties } ─────────────
            // New daemon SSE format wraps the actual payload under "properties" with
            // the event name under "type". Older formats put fields at top level.
            val (effectiveType, json) = if (outerJson["properties"] is JsonObject && outerJson["type"] is JsonPrimitive) {
                outerJson["type"]?.safeStr to (outerJson["properties"]?.jsonObject ?: outerJson)
            } else {
                eventType to outerJson
            }

            val jsonKind = json["kind"]?.safeStr
            if (effectiveType == "session.status" || jsonKind == "session.status") {
                val statusObj = json["status"]?.jsonObject
                if (statusObj != null) {
                    val statusType = statusObj["type"]?.safeStr
                    if (statusType == "retry" || statusType == "error" || statusType == "failed") {
                        val errorMsg = statusObj["message"]?.safeStr ?: "AI service returned error status"
                        val fullMsg = "OpenCode free tier: $errorMsg"
                        logger.error("[OpenCode.Error][inference=$inferenceId] $fullMsg")
                        // Emit error chunk instead of throwing — lets agent loop retry upstream
                        emit(LlmChunk(content = null, finishReason = "error", sseEvent = effectiveType))
                        return false
                    }
                    if (statusType == "busy") {
                        logger.warn("[OpenCode.Busy][inference=$inferenceId] Daemon is busy, will retry upstream")
                        emit(LlmChunk(content = null, finishReason = "busy", sseEvent = effectiveType))
                        return false
                    }
                }
            }

            if ((json["name"].safeStr ?: "").endsWith("Error")) {
                val errorMsg = "Daemon error: ${json["message"]?.safeStr ?: data.take(200)}"
                logger.error("[OpenCode.Error][inference=$inferenceId] $errorMsg")
                // Emit error chunk for upstream retry instead of silent swallow
                emit(LlmChunk(content = null, finishReason = "error", sseEvent = effectiveType))
                return false
            }

            parseCanonicalResponse(json, effectiveType)?.parts?.forEachIndexed { i, part ->
                val chunk =
                    when (part.type) {
                        "text" -> {
                            val c = part.content
                            LlmChunk(
                                content =
                                    if (c != null) {
                                        stripThinkFinalTags(c)
                                    } else {
                                        null
                                    },
                                reasoning = null,
                                subagentId = part.subagentId,
                                sseEvent = effectiveType,
                            )
                        }
                        "reasoning" ->
                            LlmChunk(
                                content = null,
                                reasoning = part.content,
                                subagentId = part.subagentId,
                                sseEvent = effectiveType,
                            )
                        "tool_use", "tool", "call" -> {
                            val pendingQ =
                                if (part.toolName == "ask_user" || part.toolName == "askuser") {
                                    extractQuestionFromArgs(part.toolArgs)
                                } else {
                                    null
                                }

                            LlmChunk(
                                content = null,
                                toolCall =
                                    LlmToolCall(
                                        "tool-${System.currentTimeMillis()}-$i",
                                        part.toolName ?: "unknown",
                                        part.toolArgs ?: "",
                                        status = part.status,
                                    ),
                                question = pendingQ,
                                subagentId = part.subagentId,
                                sseEvent = effectiveType,
                            )
                        }
                        "tool_result", "result" ->
                            LlmChunk(
                                content = null,
                                toolResult = LlmToolResult(part.toolName ?: "unknown", part.content ?: ""),
                                subagentId = part.subagentId,
                                sseEvent = effectiveType,
                            )
                        else -> null
                    }
                if (chunk != null) emit(chunk)
            } ?: run {
                // PROBE: Log unparseable events at WARN so we can see the actual daemon format.
                // When running against OpenCode CLI provider we expect parseCanonicalResponse
                // to always return a result for assistant-content events. A null here means
                // the daemon is using an event shape we haven't accounted for yet.
                if (effectiveType != null && (effectiveType.contains("message", ignoreCase = true) || effectiveType.contains("part", ignoreCase = true))) {
                    logger.warn("[OpenCode.SSE][inference=$inferenceId] UNPARSEABLE eventType=$effectiveType dataPreview=${data.take(300)}")
                } else {
                    logger.debug("[OpenCode.SSE][inference=$inferenceId] No parser matched eventType=$effectiveType")
                }
            }
        }
        return true
    }

    private fun extractQuestionFromArgs(argsJson: String?): PendingQuestion? {
        if (argsJson.isNullOrBlank()) return null
        return try {
            val json = Json.parseToJsonElement(argsJson).jsonObject

            var questionStr = json["question"]?.safeStr ?: "What would you like?"
            var options: List<String> = emptyList()
            var allowCustom =
                json["allowCustom"]?.safeStr?.toBooleanStrictOrNull()
                    ?: json["allow_custom"]?.safeStr?.toBooleanStrictOrNull()
                    ?: false

            val questions = json["questions"]?.jsonArray
            if (questions != null && questions.isNotEmpty()) {
                val first = questions[0].jsonObject
                questionStr = first["question"]?.safeStr ?: questionStr
                allowCustom = first["allowCustom"]?.safeStr?.toBooleanStrictOrNull()
                    ?: first["allow_custom"]?.safeStr?.toBooleanStrictOrNull()
                    ?: allowCustom
                val optsEl = first["options"]
                if (optsEl is JsonArray) {
                    options = optsEl.mapNotNull { it.safeStr }
                }
            }

            val optsEl = json["options"]
            if (options.isEmpty() && optsEl is JsonArray) {
                options = optsEl.mapNotNull { it.safeStr }
            }

            PendingQuestion(question = questionStr, options = options, allowCustom = allowCustom)
        } catch (e: Exception) {
            logger.debug("Failed to parse ask_user args: ${e.message}")
            null
        }
    }

    private fun parseCanonicalResponse(
        json: JsonObject,
        eventType: String? = null,
    ): CanonicalResponse? {
        val topSubagentId = json["subagent_id"].safeStr

        // ── Handle SSE event types where the type is in the event: line, not the JSON ──
        // Format: event: message.part.delta  data: {"sessionID":"...","field":"text","delta":"Hello"}
        if (eventType == "message.part.delta") {
            val delta = json["delta"]?.deepStr()
            val field = json["field"]?.safeStr ?: "text"
            if (delta != null) {
                logger.trace("[OpenCode.SSE] message.part.delta field=$field delta=${delta.take(80)}")
                return CanonicalResponse(listOf(CanonicalPart(field, delta, subagentId = topSubagentId)))
            }
        }

        // Format: event: message.part.updated  data: {"sessionID":"...","part":{...},"delta":"Hello"}
        // New (1.16+) format: data: {"type":"message.part.updated","properties":{"part":{...},"delta":"Hello"}}
        // — when we get here, `json` is already the unwrapped `properties` object.
        if (eventType == "message.part.updated") {
            val part = json["part"]?.jsonObject
            val explicitDelta = json["delta"]?.deepStr()
            if (part != null) {
                val partType = part["type"].safeStr ?: "text"
                val content = part.deepStr()
                // Prefer the explicit delta when present — it is the incremental text.
                // Fall back to the full content only when no delta was supplied.
                val emitContent = explicitDelta ?: content
                if (emitContent != null) {
                    logger.trace("[OpenCode.SSE] message.part.updated partType=$partType delta=${explicitDelta?.take(80)} fullLen=${content?.length}")
                    return CanonicalResponse(listOf(CanonicalPart(partType, emitContent, subagentId = topSubagentId)))
                }
            }
            // Fallback: maybe the data itself has a type field
            val fallbackType = json["type"].safeStr
            if (fallbackType != null) {
                val content = json.deepStr()
                if (content != null) {
                    return CanonicalResponse(listOf(CanonicalPart(fallbackType, content, subagentId = topSubagentId)))
                }
            }
        }

        // ── Handle canonical format where type is in the JSON itself ──
        val partsArray = json["parts"]?.jsonArray
        if (partsArray != null) {
            val parts =
                partsArray.flatMap { el ->
                    val obj = el as? JsonObject ?: return@flatMap emptyList<CanonicalPart>()
                    val type = obj["type"].safeStr ?: "unknown"
                    val sid = obj["subagent_id"].safeStr ?: topSubagentId
                    when (type) {
                        "text", "reasoning", "content" ->
                            listOf(
                                CanonicalPart(
                                    type = if (type == "content") "text" else type,
                                    content = obj.deepStr(),
                                    subagentId = sid,
                                ),
                            )
                        "tool_use", "tool", "call", "web_search", "search", "subtask", "file", "patch", "retry", "compaction" -> {
                            val call = obj["call"]?.jsonObject ?: obj
                            val rawToolName = (call["name"] ?: call["tool"] ?: call["function"])?.deepStr() ?: type
                            val toolName = if (rawToolName == "askuser") "ask_user" else rawToolName

                            val stateObj = call["state"]?.jsonObject
                            val status = stateObj?.get("status")?.safeStr
                            val inputElement = stateObj?.get("input") ?: call["arguments"] ?: call["args"] ?: call["input"] ?: call["query"]
                            val outputElement = stateObj?.get("output")
                            val toolArgs = inputElement?.rawJsonStr()
                            val toolOutput = outputElement?.deepStr() ?: outputElement?.rawJsonStr()

                            val toolParts = mutableListOf<CanonicalPart>()
                            toolParts.add(CanonicalPart("tool_use", null, toolName, toolArgs, subagentId = sid, status = status))
                            if (toolOutput != null) {
                                toolParts.add(CanonicalPart("tool_result", toolOutput, toolName, subagentId = sid))
                            }
                            toolParts
                        }
                        "tool_result", "result",
                        "web_search_result", "search_result", "subtask_result",
                        "file_result", "patch_result", "retry_result", "compaction_result",
                        ->
                            listOf(
                                CanonicalPart(
                                    "tool_result",
                                    toolName = (obj["name"] ?: obj["tool"])?.safeStr ?: type.replace("_result", ""),
                                    content = obj.deepStr(),
                                    subagentId = sid,
                                ),
                            )
                        else -> listOf(CanonicalPart(type, subagentId = sid))
                    }
                }
            return CanonicalResponse(parts)
        }

        val type = json["type"].safeStr ?: eventType
        if (type != null) {
            val part = json["part"]?.jsonObject ?: json
            val sid = part["subagent_id"].safeStr ?: topSubagentId
            val content = part.deepStr()
            val toolName = (part["tool"] ?: part["name"] ?: part["function"] ?: json["name"]).deepStr()

            val stateObj = part["state"]?.jsonObject
            val status = stateObj?.get("status")?.safeStr
            val inputElement =
                stateObj?.get("input") ?: part["arguments"] ?: part["args"] ?: part["input"] ?: json["arguments"] ?: json["args"]
                    ?: json["input"]
            val outputElement = stateObj?.get("output")
            val toolArgs = inputElement?.rawJsonStr()
            val toolOutput = outputElement?.deepStr() ?: outputElement?.rawJsonStr()

            return when (type) {
                "text", "content" -> CanonicalResponse(listOf(CanonicalPart("text", content, subagentId = sid)))
                "reasoning", "thought" -> CanonicalResponse(listOf(CanonicalPart("reasoning", content, subagentId = sid)))
                "tool_use", "tool", "call", "web_search", "search", "subtask", "file", "patch", "retry", "compaction" -> {
                    val parts = mutableListOf<CanonicalPart>()
                    val rawToolName = if (toolName == "null" || toolName.isNullOrBlank()) type else toolName
                    val actualToolName = if (rawToolName == "askuser") "ask_user" else rawToolName
                    parts.add(CanonicalPart("tool_use", content, actualToolName, toolArgs, subagentId = sid, status = status))
                    if (toolOutput != null) {
                        parts.add(CanonicalPart("tool_result", toolOutput, actualToolName, subagentId = sid))
                    }
                    CanonicalResponse(parts)
                }
                "tool_result", "result",
                "web_search_result", "search_result", "subtask_result",
                "file_result", "patch_result", "retry_result", "compaction_result",
                -> {
                    val actualToolName = if (toolName == "null" || toolName.isNullOrBlank()) type.replace("_result", "") else toolName
                    CanonicalResponse(listOf(CanonicalPart("tool_result", content, actualToolName, subagentId = sid)))
                }
                "part-delta", "delta" -> {
                    val subType = json["part_type"].safeStr ?: "text"
                    CanonicalResponse(listOf(CanonicalPart(subType, content, subagentId = sid)))
                }
                // Handle message.updated — may contain assembled parts array
                "message.updated" -> {
                    var partsArray =
                        json["parts"]?.jsonArray
                            ?: json["info"]?.jsonObject?.get("parts")?.jsonArray
                            ?: json["message"]?.jsonObject?.get("parts")?.jsonArray

                    // Extra fallback: daemon may nest content under info.message.content
                    if (partsArray == null) {
                        partsArray =
                            json["info"]?.jsonObject?.get("message")?.jsonObject?.get("parts")?.jsonArray
                                ?: json["info"]?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonArray
                    }

                    if (partsArray != null) {
                        val parts =
                            partsArray.flatMap { el ->
                                val obj = el as? JsonObject ?: return@flatMap emptyList<CanonicalPart>()
                                val pType = obj["type"].safeStr ?: "unknown"
                                val pContent = obj.deepStr()
                                when (pType) {
                                    "text", "content" -> listOf(CanonicalPart("text", pContent, subagentId = sid))
                                    "reasoning" -> listOf(CanonicalPart("reasoning", pContent, subagentId = sid))
                                    "tool_result", "result" ->
                                        listOf(CanonicalPart("tool_result", pContent, subagentId = sid))

                                    else -> emptyList()
                                }
                            }
                        if (parts.isNotEmpty()) CanonicalResponse(parts) else null
                    } else {
                        val fallbackText =
                            json["text"]?.deepStr()
                                ?: json["content"]?.deepStr()
                                ?: json["delta"]?.deepStr()
                                // deeply nested: info.message.content
                                ?: json["info"]?.jsonObject?.get("message")?.jsonObject?.get("content")?.deepStr()
                                ?: json["info"]?.jsonObject?.get("content")?.deepStr()
                                ?: json["info"]?.jsonObject?.get("text")?.deepStr()
                                ?: json["message"]?.jsonObject?.get("content")?.deepStr()
                                ?: json["message"]?.jsonObject?.get("text")?.deepStr()
                        if (!fallbackText.isNullOrBlank()) {
                            CanonicalResponse(listOf(CanonicalPart("text", fallbackText, subagentId = sid)))
                        } else null
                    }
                }
                // Ignore status events — they carry no text content
                "session.status" -> null
                else -> null
            }
        }

        val content = json.deepStr()
        if (content != null && content != json.toString()) {
            return CanonicalResponse(listOf(CanonicalPart("text", content, subagentId = topSubagentId)))
        }

        return null
    }

    private suspend fun createDaemonSession(): String {
        val response =
            client.post("$daemonBaseUrl/session") {
                contentType(ContentType.Application.Json)
                setBody(DaemonSessionRequest())
            }
        return response.body<DaemonSessionResponse>().id
    }

    private class StreamContext(
        val inferenceId: String,
    )
}

@Serializable private data class DaemonSessionRequest(
    val parentID: String? = null,
)

@Serializable private data class DaemonSessionResponse(
    val id: String,
)

@Serializable private data class DaemonMessageRequest(
    val parts: List<JsonObject>,
    val model: JsonObject? = null,
    val agent: String? = null,
    val system: String? = null,
    val stream: Boolean = true,
    val variant: JsonPrimitive? = null,
)

private data class CanonicalPart(
    val type: String,
    val content: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val subagentId: String? = null,
    val status: String? = null,
)

private data class CanonicalResponse(
    val parts: List<CanonicalPart>,
)
