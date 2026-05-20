package com.example.smarty.server.llm

import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

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
            logger.info("[OpenCode] === PHASE 2: LLM Inference ===")
            logger.info("[OpenCode] stream() starting — model={}, messages={}, tools={}", model ?: "default", messages.size, tools.size)

            val selectedModel = OpencodeModelRegistry.requireAllowedFreeModel(model ?: defaultModel)
            logger.info("[OpenCode] Model selected: {} (requested: {})", selectedModel, model ?: "default")

            val prompt = buildCliPrompt(messages, tools)
            logger.info("[OpenCode] Prompt built — {} chars, {} messages, {} tools", prompt.length, messages.size, tools.size)

            val sessionId = deriveSessionId(messages)
            logger.info("[OpenCode] Session ID: {}", sessionId)

            val process = startCliProcess(prompt, selectedModel, sessionId)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            logger.info("[OpenCode] CLI process started (PID: {}) — model: {}, session: {}", process.pid(), selectedModel, sessionId)

            // opencode run outputs plain text (not JSON events).
            // We read all stdout as the LLM response.
            var lineCount = 0
            var charCount = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                lineCount++
                val current = line?.trim().orEmpty()

                if (current.isEmpty()) continue

                charCount += current.length
                logger.debug("[OpenCode] Output line [{}]: {} chars", lineCount, current.length)
                emit(LlmChunk(content = current + "\n", reasoning = null))
            }

            val streamDuration = System.currentTimeMillis() - streamStartTime
            logger.info("[OpenCode] stream() completed — {} lines, {} chars, {}ms elapsed", lineCount, charCount, streamDuration)

            val exitCode = withContext(Dispatchers.IO) {
                val completed = process.waitFor(120, TimeUnit.SECONDS)
                if (!completed) {
                    logger.warn("[OpenCode] Process did not exit within 120s — destroying")
                    process.destroyForcibly()
                }
                process.exitValue()
            }

            if (exitCode != 0) {
                logger.error("[OpenCode] CLI exited with code {} after {} lines (session: {})", exitCode, lineCount, sessionId)
                if (lineCount == 0) {
                    emit(LlmChunk(content = "\n[OpenCode CLI exited with code $exitCode — no output produced]\n", reasoning = null))
                }
            } else {
                logger.info("[OpenCode] CLI exited cleanly (code 0) — session: {}", sessionId)
            }

            logger.info("[OpenCode] === PHASE 2 COMPLETE ===")
        }.flowOn(Dispatchers.IO)

    private suspend fun startCliProcess(
        prompt: String,
        model: String,
        sessionId: String,
    ) = withContext(Dispatchers.IO) {
        val workDir = File(System.getProperty("user.dir"), "_temp/opencode").apply { mkdirs() }
        logger.info("[OpenCode] Working directory: {}", workDir.absolutePath)

        // NOTE: 'opencode run' does NOT support --attach. The --attach flag belongs to
        // 'opencode attach <url>' which is for TUI connections. For headless LLM calls,
        // we use 'opencode run' in one-shot mode (spawns its own process per call).
        val command = mutableListOf(
            "opencode", "run",
            "--agent", agentName,
            "--model", model,
            "--session", sessionId,
            "--dangerously-skip-permissions",
            "--print-logs",
            "--log-level", "ERROR",
            prompt,
        )

        logger.info("[OpenCode] CLI command: {} [prompt: {} chars, agent: {}, one-shot mode]",
            command.take(8).joinToString(" "), prompt.length, agentName)

        val processStart = System.currentTimeMillis()
        val process = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        logger.info("[OpenCode] Process started in {}ms — PID: {}", System.currentTimeMillis() - processStart, process.pid())
        process
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
}
