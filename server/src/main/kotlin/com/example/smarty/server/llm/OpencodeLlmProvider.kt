package com.example.smarty.server.llm

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

class OpencodeLlmProvider(
    private val client: HttpClient,
    override val providerName: String = "OpenCode CLI",
    private val defaultModel: String = OpencodeModelRegistry.defaultModel,
    private val daemonPort: Int = 4096,
    private val daemonHost: String = "127.0.0.1",
) : LlmProvider {
    private val logger = LoggerFactory.getLogger(OpencodeLlmProvider::class.java)
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    private val daemonBaseUrl = "http://$daemonHost:$daemonPort"
    private val agentName = System.getenv("OPENCODE_AGENT")?.takeIf { it.isNotBlank() } ?: "smarty-headless-agent"

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
    ): Flow<LlmChunk> =
        flow {
            val streamStartTime = System.currentTimeMillis()
            logger.info("[OpenCode] stream() starting — model={}, messages={}, tools={}", model ?: "default", messages.size, tools.size)

            val selectedModel = OpencodeModelRegistry.requireAllowedFreeModel(model ?: defaultModel)
            logger.info("[OpenCode] Model selected: {} (requested: {})", selectedModel, model ?: "default")

            val prompt = buildCliPrompt(messages, tools)
            logger.info("[OpenCode] Prompt built — {} chars, {} messages, {} tools", prompt.length, messages.size, tools.size)

            val sessionId = deriveSessionId(messages)
            logger.info("[OpenCode] Session ID: {}", sessionId)

            val daemonAvailable = isDaemonRunning()
            logger.info("[OpenCode] Daemon status: {} ({}:{})", if (daemonAvailable) "RUNNING" else "NOT RUNNING", daemonHost, daemonPort)

            val process = startCliProcess(prompt, selectedModel, sessionId)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            logger.info("[OpenCode] CLI process started (PID: {}) — model: {}, session: {}", process.pid(), selectedModel, sessionId)

            var lineCount = 0
            var eventCount = 0
            var nonJsonLines = 0
            var accumulatedUsage: LlmUsage? = null
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                lineCount++
                val current = line?.trim().orEmpty()

                if (current.isEmpty() || !current.startsWith('{')) {
                    if (current.isNotEmpty()) {
                        nonJsonLines++
                        logger.debug("CLI non-JSON line [{}]: {}", lineCount, current.take(200))
                    }
                    continue
                }

                runCatching {
                    val obj = json.parseToJsonElement(current).jsonObject
                    val eventType = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    eventCount++

                    when (eventType) {
                        "text" -> {
                            val text = extractPartTextField(obj)
                            if (!text.isNullOrEmpty()) {
                                logger.debug("OpenCode text event: {} chars", text.length)
                                emit(LlmChunk(content = text, reasoning = null))
                            }
                        }

                        "tool_use" -> {
                            val part = obj["part"]?.jsonObject
                            val toolName = part?.get("tool")?.jsonPrimitive?.contentOrNull
                            val state = part?.get("state")?.jsonObject
                            val status = state?.get("status")?.jsonPrimitive?.contentOrNull

                            if (toolName != null && status == "completed") {
                                logger.debug("[OpenCode] tool_use completed: {} (status: {})", toolName, status)
                                when {
                                    isSmartyTool(toolName) -> {
                                        val toolCall = buildSmartyToolCall(part, toolName, state)
                                        if (toolCall != null) {
                                            logger.info("[OpenCode] SMARTY TOOL: {} — callId: {}", toolName, toolCall.id)
                                            logger.debug("[OpenCode] Tool args: {}", toolCall.arguments.take(200))
                                            emit(LlmChunk(content = null, reasoning = null, toolCall = toolCall))
                                        }
                                    }
                                    toolName == "websearch" -> {
                                        val output = extractToolOutput(state)
                                        if (!output.isNullOrBlank()) {
                                            logger.info("[OpenCode] websearch completed: {} chars", output.length)
                                            emit(LlmChunk(content = output, reasoning = null))
                                        }
                                    }
                                    else -> {
                                        logger.debug("[OpenCode] Internal tool '{}' completed (not a Smarty tool — ignored)", toolName)
                                    }
                                }
                            } else if (toolName != null) {
                                logger.debug("[OpenCode] tool_use event: {} (status: {} — not completed yet)", toolName, status)
                            }
                        }

                        "step_start" -> {
                            logger.debug("OpenCode step_start")
                        }

                        "step_finish" -> {
                            val tokens = obj["part"]?.jsonObject?.get("tokens")?.jsonObject
                            if (tokens != null) {
                                val inputTokens = tokens["input"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                                val outputTokens = tokens["output"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                                val reasoningTokens = tokens["reasoning"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                                val usage = LlmUsage(
                                    promptTokens = inputTokens,
                                    completionTokens = outputTokens + reasoningTokens,
                                    totalTokens = inputTokens + outputTokens + reasoningTokens,
                                )
                                accumulatedUsage = usage
                                emit(LlmChunk(content = null, reasoning = null, toolCall = null, usage = usage))
                                logger.info("OpenCode step_finish: tokens={}/{}/{} (input/output/reasoning)", inputTokens, outputTokens, reasoningTokens)
                            } else {
                                logger.info("OpenCode step_finish (no token data)")
                            }
                        }

                        "error" -> {
                            val errorObj = obj["error"]?.jsonObject
                            val errorMsg = errorObj?.get("data")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                                ?: errorObj?.get("message")?.jsonPrimitive?.contentOrNull
                                ?: "Unknown OpenCode error"
                            logger.error("[OpenCode] ERROR event: {}", errorMsg)
                            // Surface error to the client as content so it's visible in chat
                            emit(LlmChunk(content = "\n[OpenCode Error: $errorMsg]\n", reasoning = null))
                        }

                        else -> {
                            logger.debug("OpenCode unknown event type: {}", eventType)
                        }
                    }
                }.onFailure { e ->
                    logger.warn("OpenCode parse error on line {}: {}", lineCount, e.message)
                }
            }

            val streamDuration = System.currentTimeMillis() - streamStartTime
            logger.info("[OpenCode] stream() completed — {} lines, {} events, {} non-JSON, {}ms elapsed", lineCount, eventCount, nonJsonLines, streamDuration)

            val exitCode = withContext(Dispatchers.IO) {
                val completed = process.waitFor(60, TimeUnit.SECONDS)
                if (!completed) {
                    logger.warn("OpenCode process did not exit within 60s — destroying")
                    process.destroyForcibly()
                }
                process.exitValue()
            }

            if (exitCode != 0) {
                logger.error("[OpenCode] CLI exited with code {} after {} lines (session: {})", exitCode, lineCount, sessionId)
                // Surface non-zero exit as error content if no other content was produced
                if (lineCount == 0 || eventCount == 0) {
                    emit(LlmChunk(content = "\n[OpenCode CLI exited with code $exitCode — no output produced]\n", reasoning = null))
                }
            } else {
                logger.info("[OpenCode] CLI exited cleanly (code 0) — session: {}", sessionId)
            }

            if (accumulatedUsage != null) {
                emit(LlmChunk(content = null, reasoning = null, toolCall = null, usage = accumulatedUsage))
            }
        }.flowOn(Dispatchers.IO)

    private suspend fun startCliProcess(
        prompt: String,
        model: String,
        sessionId: String,
    ) = withContext(Dispatchers.IO) {
        val workDir = File(System.getProperty("user.dir"), "_temp/opencode").apply { mkdirs() }
        logger.info("[OpenCode] Working directory: {}", workDir.absolutePath)

        val command = mutableListOf("opencode", "run")

        val daemonRunning = isDaemonRunning()
        if (daemonRunning) {
            command += listOf("--attach", daemonBaseUrl)
            logger.info("[OpenCode] Attaching to running daemon at {}", daemonBaseUrl)
        } else {
            logger.info("[OpenCode] No daemon detected — using one-shot CLI mode")
        }

        command += listOf(
            "--agent", agentName,
            "--model", model,
            "--format", "json",
            "--session", sessionId,
            "--dangerously-skip-permissions",
            prompt,
        )

        logger.info("[OpenCode] CLI command: {} [prompt: {} chars, agent: {}, daemon: {}]",
            command.take(8).joinToString(" "), prompt.length, agentName, daemonRunning)

        val processStart = System.currentTimeMillis()
        val process = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        logger.info("[OpenCode] Process started in {}ms — PID: {}", System.currentTimeMillis() - processStart, process.pid())
        process
    }

    private suspend fun isDaemonRunning(): Boolean =
        try {
            val checkStart = System.currentTimeMillis()
            val response =
                client.get(daemonBaseUrl) {
                    timeout {
                        requestTimeoutMillis = 1_500
                        connectTimeoutMillis = 1_000
                    }
                }
            val checkDuration = System.currentTimeMillis() - checkStart
            val isRunning = response.status.isSuccess() || response.bodyAsText().isNotBlank()
            logger.debug("[OpenCode] Daemon health check: {} ({}ms, status: {})", if (isRunning) "UP" else "DOWN", checkDuration, response.status.value)
            isRunning
        } catch (e: Exception) {
            logger.debug("[OpenCode] Daemon health check failed: {} — daemon not running", e.message)
            false
        }

    private fun deriveSessionId(messages: List<LlmMessage>): String {
        val anchor =
            messages
                .firstOrNull { it.role == LlmMessage.Role.USER }
                ?.content
                ?: messages.joinToString("|") { it.content.take(80) }
        return "smarty-${anchor.hashCode().toUInt()}"
    }

    private fun buildCliPrompt(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
    ): String {
        val toolContract =
            tools.joinToString("\n\n") { tool ->
                val props =
                    tool.parameters.properties.entries.joinToString("\n") { (name, prop) ->
                        val enumText = prop.enum?.joinToString(prefix = " enum=[", postfix = "]") ?: ""
                        "- $name: ${prop.type}${if (prop.description != null) " - ${prop.description}" else ""}$enumText"
                    }
                """
                Tool: ${tool.name}
                Description: ${tool.description}
                Parameters:
                $props
                Required: ${tool.parameters.required.joinToString()}
                """.trimIndent()
            }

        val bridgePrompt =
            """
            <opencode_cli_bridge>
            You are running inside the OpenCode CLI, but Smarty owns all application tools, database writes, Android device commands, chat history, and user-visible streaming.

            Preserve and obey the full Friday system prompt below. It is the primary behavior contract and represents months of careful tuning.

            Tool protocol:
            - Do not run local shell, file, edit, or write tools for Smarty app actions.
            - You may use OpenCode web search only when necessary for current information.
            - For every Smarty tool action, output exactly one block and then stop that turn:
              <tool_call>{"name":"tool_name","arguments":{"key":"value"}}</tool_call>
            - Use only tools listed in <smarty_tools>.
            - After the server returns a tool result in the next turn, continue autonomously.
            - If a tool result says permanent error, schema error, auth failure, missing field, or blocked, do not repeat the same call. Choose a different valid tool, ask the user with ask_user, or produce a useful final answer with what failed.
            - If a tool result says transient error, retry at most once with changed/safer arguments.
            - Autonomous Fallback on Tool Failures: If any Smarty tool call returns a failure, error, or exception, do NOT get stuck or repeatedly retry the same failed tool. Immediately fall back to alternate strategies, use your own intelligence and OpenCode websearch to gather information, and solve the user's request autonomously. Explain the limitation in <final> but ensure the user's goal is met.
            - Never output premium or non-free model names. The active model is a verified OpenCode free model.

            Response protocol:
            - For normal replies, follow the existing <think> and <final> format from the Friday prompt.
            - For tool calls, include brief reasoning in <think> if useful, then exactly one <tool_call> block and no <final>.
            </opencode_cli_bridge>

            <smarty_tools>
            $toolContract
            </smarty_tools>
            """.trimIndent()

        val formattedMessages =
            messages.joinToString("\n\n") { msg ->
                when (msg.role) {
                    LlmMessage.Role.SYSTEM -> "<system>\n${msg.content}\n</system>"
                    LlmMessage.Role.USER -> "<user>\n${msg.content}\n</user>"
                    LlmMessage.Role.ASSISTANT -> {
                        val thinking = msg.thinking?.takeIf { it.isNotBlank() }?.let { "<think>\n$it\n</think>\n" } ?: ""
                        "<assistant>\n$thinking${msg.content}\n</assistant>"
                    }
                    LlmMessage.Role.TOOL -> "<tool_result name=\"${msg.name ?: "tool"}\">\n${msg.content}\n</tool_result>"
                }
            }

        return "$bridgePrompt\n\n<conversation>\n$formattedMessages\n</conversation>"
    }

    private fun extractPartTextField(obj: JsonObject): String? {
        val part = obj["part"]?.jsonObject ?: return null
        val text = part["text"]?.jsonPrimitive?.contentOrNull
        return text?.takeIf { it.isNotBlank() }
    }

    private fun isSmartyTool(toolName: String): Boolean = toolName in smartyToolNames

    private fun extractToolOutput(state: JsonObject?): String? {
        if (state == null) return null
        val output = state["output"]?.jsonPrimitive?.contentOrNull
        val input = state["input"]?.jsonObject?.get("query")?.jsonPrimitive?.contentOrNull
        val metadata = state["metadata"]?.jsonObject
        val exitCode = metadata?.get("exit")?.jsonPrimitive?.contentOrNull?.toIntOrNull()

        return when {
            output != null && exitCode == 0 -> output
            output != null -> "[Tool completed with exit code $exitCode]: $output"
            else -> "[Tool completed, no output]"
        }
    }

    private fun buildSmartyToolCall(
        part: JsonObject?,
        toolName: String,
        state: JsonObject?,
    ): LlmToolCall? {
        val input = state?.get("input")?.jsonObject
        val callId = part?.get("callID")?.jsonPrimitive?.contentOrNull
            ?: UUID.randomUUID().toString()
        return LlmToolCall(
            id = callId,
            functionName = toolName,
            arguments = input?.toString() ?: "{}",
        )
    }

    companion object {
        private val smartyToolNames = setOf(
            "create_note", "search_notes", "update_note", "delete_note",
            "create_reminder", "search_reminders", "update_reminder", "delete_reminder",
            "create_event", "search_events", "update_event", "delete_event",
            "create_task", "search_tasks", "update_task", "delete_task",
            "ask_user", "web_search", "deep_research",
            "create_image", "edit_image",
            "get_weather", "send_email",
            "create_contact", "search_contacts",
            "get_location", "read_file", "write_file", "run_code",
            "summarize", "translate",
        )
    }
}
