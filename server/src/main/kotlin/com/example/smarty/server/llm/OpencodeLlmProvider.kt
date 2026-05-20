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

/**
 * OpenCode CLI bridge.
 *
 * This intentionally treats OpenCode as an agentic CLI runtime, not as a normal
 * OpenAI-compatible API provider. The Ktor agent remains the owner of Smarty
 * tools, Supabase persistence, SSE, and session state; OpenCode supplies the
 * free model inference and returns text/reasoning/tool-call intents.
 */
class OpencodeLlmProvider(
    private val client: HttpClient,
    override val providerName: String = "OpenCode CLI",
    private val defaultModel: String = OpencodeModelRegistry.DEFAULT_MODEL,
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

    // Smarty tool names â€” filter out OpenCode internal tools (websearch, bash, etc.)
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

    private val daemonBaseUrl = "http://$daemonHost:$daemonPort"
    private val agentName = System.getenv("OPENCODE_AGENT")?.takeIf { it.isNotBlank() } ?: "smarty-headless-agent"

    override suspend fun generate(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?,
    ): LlmResponse {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<LlmToolCall>()
        stream(messages, tools, model).collect { chunk ->
            chunk.content?.let { content.append(it) }
            chunk.reasoning?.let { reasoning.append(it) }
            chunk.toolCall?.let { toolCalls.add(it) }
        }
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
            val selectedModel = OpencodeModelRegistry.requireAllowedFreeModel(model ?: defaultModel)
            val prompt = buildCliPrompt(messages, tools)
            val sessionId = deriveSessionId(messages)
            val process = startCliProcess(prompt, selectedModel, sessionId)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            logger.info("OpenCode stream started, reading output...")

            var lineCount = 0
            var eventCount = 0
            var nonJsonLines = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                lineCount++
                val current = line?.trim().orEmpty()

                // Log raw JSON for debugging (first 200 chars)
                if (current.startsWith('{')) {
                    logger.info("RAW JSON [{}]: {}", lineCount, current.take(200))
                } else if (current.isNotEmpty()) {
                    nonJsonLines++
                    logger.warn("CLI non-JSON output [{}]: {}", lineCount, current.take(300))
                }

                if (current.isEmpty() || !current.startsWith('{')) continue

                runCatching {
                    val obj = json.parseToJsonElement(current).jsonObject
                    val eventType = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    eventCount++

                    when (eventType) {
                        "reasoning" -> {
                            val text = extractPartText(obj)
                            if (!text.isNullOrEmpty()) {
                                logger.info("OpenCode reasoning event: {} chars", text.length)
                                emit(LlmChunk(content = null, reasoning = text))
                            }
                        }
                        "text" -> {
                            val text = extractPartText(obj)
                            if (!text.isNullOrEmpty()) {
                                logger.info("OpenCode text event: {} chars", text.length)
                                emit(LlmChunk(content = text, reasoning = null, toolCall = null))
                            }
                        }
                        "tool" -> {
                            val toolCall = extractSmartyToolCall(obj)
                            if (toolCall != null) {
                                logger.info("OpenCode Smarty tool call: {}", toolCall.functionName)
                                emit(LlmChunk(content = null, reasoning = null, toolCall = toolCall))
                            } else {
                                logger.debug("OpenCode internal tool (ignored)")
                            }
                        }
                        "step_finish" -> {
                            val reason = obj["part"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
                            logger.info("OpenCode step_finish: reason={}", reason)
                        }
                        "step_start" -> {
                            logger.debug("OpenCode step_start")
                        }
                        else -> {
                            logger.info("OpenCode unknown event type: {}", eventType)
                        }
                    }
                }.onFailure { e ->
                    logger.warn("OpenCode parse error on line {}: {}", lineCount, e.message)
                }
            }

            logger.info("OpenCode stream done: {} lines read, {} JSON events, {} non-JSON lines", lineCount, eventCount, nonJsonLines)

            val exitCode = withContext(Dispatchers.IO) {
                val completed = process.waitFor(60, TimeUnit.SECONDS)
                if (!completed) process.destroyForcibly()
                process.exitValue()
            }

            if (exitCode != 0) {
                logger.error("OpenCode CLI exited with code $exitCode")
            }
        }.flowOn(Dispatchers.IO)

    private suspend fun startCliProcess(
        prompt: String,
        model: String,
        sessionId: String,
    ) = withContext(Dispatchers.IO) {
        val workDir = File(System.getProperty("user.dir"), "_temp/opencode").apply { mkdirs() }

        val command = mutableListOf(
            "opencode", "run",
        )

        // Connect to local daemon if running
        if (isDaemonRunning()) {
            command += listOf("--attach", daemonBaseUrl)
        }

        command += listOf(
            "--agent", agentName,
            "--model", model,
            "--format", "json",
            "--session", sessionId,
            "--dangerously-skip-permissions",
            prompt,
        )

        logger.info("OpenCode CLI: {} [prompt: {} chars, daemon: {}]", command.take(8).joinToString(" "), prompt.length, isDaemonRunning())

        ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
    }

    private suspend fun isDaemonRunning(): Boolean =
        try {
            val response =
                client.get(daemonBaseUrl) {
                    timeout {
                        requestTimeoutMillis = 1_500
                        connectTimeoutMillis = 1_000
                    }
                }
            response.status.isSuccess() || response.bodyAsText().isNotBlank()
        } catch (_: Exception) {
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

    /**
     * Extract text from the "part.text" or "part.reasoning" field of an OpenCode event.
     *
     * Actual format:
     * {"type":"text","part":{"type":"text","text":"Hello there..."}}
     * {"type":"reasoning","part":{"type":"reasoning","text":"Analyzing..."}}
     */
    private fun extractPartText(obj: JsonObject): String? {
        val part = obj["part"]?.jsonObject ?: return null
        return part["text"]?.jsonPrimitive?.contentOrNull
    }

    /**
     * Extract a Smarty tool call from a "tool" event.
     *
     * Actual format:
     * {"type":"tool","part":{"type":"tool","tool":"websearch",
     *   "callID":"call_00_xxx","state":{"status":"completed",
     *   "input":{"query":"...","numResults":5},"output":"..."}}}
     *
     * Only returns tool calls that match Smarty's known tool names.
     * OpenCode internal tools (websearch, bash, read, write) are ignored.
     */
    private fun extractSmartyToolCall(obj: JsonObject): LlmToolCall? {
        val part = obj["part"]?.jsonObject ?: return null
        val toolName = part["tool"]?.jsonPrimitive?.contentOrNull ?: return null

        // Skip OpenCode internal tools â€” Ktor handles these
        if (toolName !in smartyToolNames) {
            return null
        }

        val state = part["state"]?.jsonObject
        val input = state?.get("input")?.jsonObject
        val callId = part["callID"]?.jsonPrimitive?.contentOrNull
            ?: UUID.randomUUID().toString()

        return LlmToolCall(
            id = callId,
            functionName = toolName,
            arguments = input?.toString() ?: "{}",
        )
    }
}