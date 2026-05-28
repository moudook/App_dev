package com.example.smarty.server.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

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
                this.values.first().jsonPrimitive.contentOrNull
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
        fun stripThinkFinalTags(text: String): String = text.replace(TAG_STRIP_REGEX, "").trim()
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
    ): Flow<LlmChunk> =
        flow {
            val inferenceId = UUID.randomUUID().toString().take(8)
            val selectedModel = OpencodeModelRegistry.requireAllowedFreeModel(model ?: defaultModel)
            val modelId = selectedModel.removePrefix("opencode/")

            // Create or reuse session
            var activeSessionId = externalSessionId
            if (activeSessionId == null) {
                activeSessionId = createDaemonSession().also { onExternalSessionCreated(it) }
            }

            // Extract user message (last USER role message)
            val userMessage = messages.lastOrNull { it.role == LlmMessage.Role.USER }?.content?.trim() ?: ""
            if (userMessage.isEmpty()) {
                logger.warn("[OpenCode][inference=$inferenceId] Empty user message, skipping")
                return@flow
            }

            // Extract system prompt
            val systemPrompt = messages
                .filter { it.role == LlmMessage.Role.SYSTEM }
                .joinToString("\n\n") { it.content }
                .takeIf { it.isNotBlank() }

            logger.info(
                "[OpenCode.Subprocess][inference=$inferenceId][session=$activeSessionId] " +
                    "Starting subprocess streaming: model=$modelId, agent=$agentName, userMsg=${userMessage.take(80)}..."
            )

            daemonSemaphore.acquire()
            try {
                streamViaSubprocess(
                    inferenceId = inferenceId,
                    sessionId = activeSessionId,
                    modelId = modelId,
                    agentName = agentName,
                    systemPrompt = systemPrompt,
                    userMessage = userMessage,
                    flowCollector = this,
                )
            } finally {
                daemonSemaphore.release()
            }
        }.flowOn(Dispatchers.IO)

    /**
     * Stream inference via CLI subprocess — gets real-time JSON events from the daemon.
     *
     * Command:
     *   opencode run --format json --pure --attach {daemonUrl} -s {sessionId}
     *                -m {modelId} --agent {agentName} "{userMessage}"
     *
     * --pure  prevents loading external plugins (ripgrep, LSP, etc.)
     *         so the CLI acts as a thin event-streaming client.
     */
    private suspend fun streamViaSubprocess(
        inferenceId: String,
        sessionId: String,
        modelId: String,
        agentName: String,
        @Suppress("UNUSED_PARAMETER") systemPrompt: String?,
        userMessage: String,
        flowCollector: FlowCollector<LlmChunk>,
    ) {
        val commands = mutableListOf<String>()
        commands.add("opencode")
        commands.add("run")
        commands.add("--format")
        commands.add("json")
        commands.add("--pure")
        commands.add("--attach")
        commands.add(daemonBaseUrl)
        commands.add("-s")
        commands.add(sessionId)
        commands.add("-m")
        commands.add(modelId)
        commands.add("--agent")
        commands.add(agentName)

        commands.add(userMessage)

        logger.info("[OpenCode.Subprocess][inference=$inferenceId] Command: ${commands.joinToString(" ").take(300)}")

        val process = ProcessBuilder(commands)
            .redirectErrorStream(true)
            .start()

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String?
        var lineCount = 0

        try {
            while (reader.readLine().also { line = it } != null) {
                val rawLine = line!!.trim()
                if (rawLine.isEmpty()) continue

                lineCount++
                logger.info("[SUBPROCESS_JSON][inference=$inferenceId][line=$lineCount] $rawLine")

                // Try to parse as JSON event
                if (rawLine.startsWith("{")) {
                    try {
                        val json = Json.parseToJsonElement(rawLine).jsonObject
                        val eventType = json["type"]?.safeStr

                        when (eventType) {
                            // Text/streaming deltas
                            "text_delta", "content_delta", "part_delta" -> {
                                val text = json["text"]?.safeStr
                                    ?: json["delta"]?.safeStr
                                    ?: json["content"]?.safeStr
                                if (text != null) {
                                    flowCollector.emit(
                                        LlmChunk(
                                            content = stripThinkFinalTags(text),
                                            sseEvent = eventType,
                                        ),
                                    )
                                }
                            }

                            // Complete text part
                            "text", "content" -> {
                                val text = json["text"]?.safeStr
                                    ?: json["content"]?.safeStr
                                    ?: json.deepStr()
                                if (text != null) {
                                    flowCollector.emit(
                                        LlmChunk(
                                            content = stripThinkFinalTags(text),
                                            sseEvent = eventType,
                                        ),
                                    )
                                }
                            }

                            // Reasoning/thinking
                            "reasoning", "thought", "thinking" -> {
                                val text = json["text"]?.safeStr
                                    ?: json["content"]?.safeStr
                                    ?: json.deepStr()
                                if (text != null) {
                                    flowCollector.emit(
                                        LlmChunk(
                                            content = null,
                                            reasoning = text,
                                            sseEvent = "reasoning",
                                        ),
                                    )
                                }
                            }

                            // Tool use / tool call
                            "tool_use", "tool", "call" -> {
                                val toolName = (json["name"] ?: json["tool"] ?: json["function"])?.safeStr ?: "unknown"
                                val rawName = if (toolName == "askuser") "ask_user" else toolName

                                val stateObj = json["state"]?.jsonObject
                                val status = stateObj?.get("status")?.safeStr
                                val inputElement = stateObj?.get("input")
                                    ?: json["arguments"]
                                    ?: json["args"]
                                    ?: json["input"]
                                    ?: json["query"]
                                val outputElement = stateObj?.get("output")
                                val toolArgs = inputElement?.rawJsonStr()
                                val toolOutput = outputElement?.deepStr()

                                flowCollector.emit(
                                    LlmChunk(
                                        content = null,
                                        toolCall = LlmToolCall(
                                            "tool-${System.currentTimeMillis()}",
                                            rawName,
                                            toolArgs ?: "",
                                            status = status,
                                        ),
                                        sseEvent = "tool_call",
                                    ),
                                )

                                if (toolOutput != null) {
                                    flowCollector.emit(
                                        LlmChunk(
                                            content = null,
                                            toolResult = LlmToolResult(rawName, toolOutput),
                                            sseEvent = "tool_result",
                                        ),
                                    )
                                }
                            }

                            // Web search
                            "web_search", "search" -> {
                                val query = (json["query"] ?: json["q"])?.safeStr ?: ""
                                val results = json["results"]?.toString()
                                val resultText = json["result"]?.safeStr

                                flowCollector.emit(
                                    LlmChunk(
                                        content = null,
                                        toolCall = LlmToolCall(
                                            "websearch-${System.currentTimeMillis()}",
                                            "websearch",
                                            query,
                                            status = if (results != null || resultText != null) "completed" else "running",
                                        ),
                                        sseEvent = "web_search",
                                    ),
                                )

                                if (resultText != null) {
                                    flowCollector.emit(
                                        LlmChunk(
                                            content = null,
                                            toolResult = LlmToolResult("websearch", resultText),
                                            sseEvent = "web_search_result",
                                        ),
                                    )
                                }
                            }

                            // Sub-agent events
                            "subagent_spawn", "subagent_create" -> {
                                val agentId = (json["agentId"] ?: json["subagent_id"])?.safeStr ?: "unknown"
                                val task = (json["task"] ?: json["prompt"])?.safeStr ?: ""
                                flowCollector.emit(
                                    LlmChunk(
                                        content = null,
                                        toolCall = LlmToolCall(
                                            "subagent-${System.currentTimeMillis()}",
                                            "subagent",
                                            task,
                                            status = "spawned",
                                        ),
                                        subagentId = agentId,
                                        sseEvent = "subagent",
                                    ),
                                )
                            }

                            "subagent_message", "subagent_update" -> {
                                val agentId = (json["agentId"] ?: json["subagent_id"])?.safeStr
                                val txt = json.deepStr()
                                if (txt != null) {
                                    flowCollector.emit(
                                        LlmChunk(
                                            content = txt,
                                            subagentId = agentId,
                                            sseEvent = "subagent_message",
                                        ),
                                    )
                                }
                            }

                            "subagent_complete" -> {
                                val agentId = (json["agentId"] ?: json["subagent_id"])?.safeStr
                                val result = json["result"]?.deepStr()
                                flowCollector.emit(
                                    LlmChunk(
                                        content = null,
                                        toolResult = LlmToolResult("subagent", result ?: ""),
                                        subagentId = agentId,
                                        sseEvent = "subagent_complete",
                                    ),
                                )
                            }

                            // Error events
                            "error" -> {
                                val message = (json["message"] ?: json["error"])?.safeStr ?: rawLine
                                logger.error("[OpenCode.Subprocess][inference=$inferenceId] Error: $message")
                                flowCollector.emit(
                                    LlmChunk(
                                        content = null,
                                        rawJson = rawLine,
                                        sseEvent = "error",
                                    ),
                                )
                            }

                            // Progress / status
                            "status", "progress" -> {
                                val msg = json["message"]?.safeStr ?: json["status"]?.safeStr
                                if (msg != null) {
                                    flowCollector.emit(
                                        LlmChunk(
                                            content = msg,
                                            sseEvent = eventType,
                                        ),
                                    )
                                }
                            }

                            // Complete response batch
                            "response", "complete", "finish" -> break

                            // Unknown JSON — pass through as raw
                            else -> {
                                flowCollector.emit(
                                    LlmChunk(
                                        content = null,
                                        rawJson = rawLine,
                                        sseEvent = eventType ?: "unknown",
                                    ),
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // Not valid JSON, or parsing failed — log and continue
                        logger.debug("[OpenCode.Subprocess][inference=$inferenceId] Non-JSON line: $rawLine")
                        flowCollector.emit(
                            LlmChunk(
                                content = rawLine,
                                rawJson = rawLine,
                                sseEvent = "raw",
                            ),
                        )
                    }
                } else {
                    // Plain text line from subprocess
                    flowCollector.emit(
                        LlmChunk(
                            content = rawLine,
                            rawJson = rawLine,
                            sseEvent = "raw",
                        ),
                    )
                }
            }
        } finally {
            // Wait for process to finish with timeout
            val exited = process.waitFor(15, TimeUnit.MINUTES)
            if (!exited) {
                logger.warn("[OpenCode.Subprocess][inference=$inferenceId] Process timed out, killing")
                process.destroyForcibly()
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                logger.warn("[OpenCode.Subprocess][inference=$inferenceId] Process exited with code $exitCode")
                val remaining = reader.readText()
                if (remaining.isNotBlank()) {
                    logger.warn("[OpenCode.Subprocess][inference=$inferenceId] Remaining output: ${remaining.take(500)}")
                }
            }

            reader.close()
            logger.info("[OpenCode.Subprocess][inference=$inferenceId] Completed. Lines processed: $lineCount, exitCode: $exitCode")
        }
    }

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
