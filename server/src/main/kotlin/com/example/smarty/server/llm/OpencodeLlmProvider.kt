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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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

    private val daemonBaseUrl = "http://$daemonHost:$daemonPort"
    private val agentName = System.getenv("OPENCODE_AGENT")?.takeIf { it.isNotBlank() } ?: "smarty-headless-agent"

    override suspend fun generate(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?,
    ): LlmResponse {
        val content = StringBuilder()
        val toolCalls = mutableListOf<LlmToolCall>()
        stream(messages, tools, model).collect { chunk ->
            chunk.content?.let { content.append(it) }
            chunk.toolCall?.let { toolCalls.add(it) }
        }
        return LlmResponse(content = content.toString().ifBlank { null }, toolCalls = toolCalls)
    }

    override suspend fun stream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?,
    ): Flow<LlmChunk> =
        flow {
            val selectedModel = OpencodeModelRegistry.requireAllowedFreeModel(model ?: defaultModel)
            val prompt = buildCliPrompt(messages, tools)
            val process = startCliProcess(prompt, selectedModel, deriveSessionId(messages))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val textParser = ToolBlockTextParser()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val current = line ?: continue
                val parsed = parseJsonEvent(current)
                if (parsed != null) {
                    if (!parsed.reasoning.isNullOrEmpty()) emit(LlmChunk(content = null, reasoning = parsed.reasoning))
                    if (parsed.toolCall != null) emit(LlmChunk(content = null, toolCall = parsed.toolCall))
                    if (!parsed.content.isNullOrEmpty()) {
                        textParser.accept(parsed.content).forEach { emit(it) }
                    }
                } else {
                    textParser.accept(cleanTerminalLine(current)).forEach { emit(it) }
                }
            }

            textParser.flush().forEach { emit(it) }

            val completed = process.waitFor(30, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                throw IllegalStateException("OpenCode CLI did not exit after its output stream closed.")
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                throw IllegalStateException("OpenCode CLI exited with code $exitCode. Ensure a verified free OpenCode model is selected.")
            }
        }.flowOn(Dispatchers.IO)

    private suspend fun startCliProcess(
        prompt: String,
        model: String,
        sessionId: String,
    ) = withContext(Dispatchers.IO) {
        val workDir =
            File(System.getProperty("user.dir"), "_temp/opencode")
                .apply { mkdirs() }

        val command = mutableListOf<String>()
        command += OpencodeModelRegistry.resolveCommand(System.getenv("OPENCODE_BINARY")?.takeIf { it.isNotBlank() } ?: "opencode")
        command += listOf("run")

        if (isDaemonRunning()) {
            command += listOf("--attach", daemonBaseUrl)
        }

        command += listOf(
            "--agent",
            agentName,
            "--model",
            model,
            "--format",
            "json",
            "--session",
            sessionId,
            prompt,
        )

        logger.info("Starting OpenCode CLI stream: {}", command.take(command.size - 1).joinToString(" "))

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

    private fun parseJsonEvent(line: String): ParsedCliEvent? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return null

        return runCatching {
            val element = json.parseToJsonElement(trimmed)
            val type = element.findString("type", "kind", "event", "name")?.lowercase().orEmpty()
            val toolCall = element.toToolCall()
            val text = element.findString("text", "content", "delta", "message", "output")

            when {
                toolCall != null -> ParsedCliEvent(toolCall = toolCall)
                type.contains("reason") || type.contains("think") ->
                    ParsedCliEvent(reasoning = text)
                type.contains("text") ||
                    type.contains("content") ||
                    type.contains("assistant") ||
                    type.contains("message") ||
                    type.contains("output") ->
                    ParsedCliEvent(content = text)
                text != null && type.isBlank() -> ParsedCliEvent(content = text)
                else -> null
            }
        }.getOrNull()
    }

    private fun cleanTerminalLine(line: String): String =
        line
            .replace(Regex("""^\s*[│┃╎>]*\s*"""), "")
            .replace(Regex("""\u001B\[[;\d]*m"""), "")
            .trimEnd()
            .let { if (it.startsWith("build ", ignoreCase = true)) "" else it }

    private data class ParsedCliEvent(
        val content: String? = null,
        val reasoning: String? = null,
        val toolCall: LlmToolCall? = null,
    )

    private inner class ToolBlockTextParser {
        private val buffer = StringBuilder()
        private var inToolBlock = false

        fun accept(text: String): List<LlmChunk> {
            if (text.isEmpty()) return emptyList()
            val chunks = mutableListOf<LlmChunk>()
            buffer.append(text)

            while (true) {
                val current = buffer.toString()
                val start = current.indexOf("<tool_call>")
                if (start < 0) {
                    if (!inToolBlock && buffer.isNotEmpty()) {
                        chunks += LlmChunk(content = buffer.toString())
                        buffer.clear()
                    }
                    break
                }

                if (start > 0 && !inToolBlock) {
                    chunks += LlmChunk(content = current.substring(0, start))
                    buffer.delete(0, start)
                }

                inToolBlock = true
                val block = buffer.toString()
                val end = block.indexOf("</tool_call>")
                if (end < 0) break

                val payload = block.substringAfter("<tool_call>").substringBefore("</tool_call>").trim()
                parseToolBlock(payload)?.let { chunks += LlmChunk(content = null, toolCall = it) }
                buffer.delete(0, end + "</tool_call>".length)
                inToolBlock = false
            }

            return chunks
        }

        fun flush(): List<LlmChunk> {
            val remaining = buffer.toString()
            buffer.clear()
            inToolBlock = false
            if (remaining.isBlank()) return emptyList()
            return listOf(LlmChunk(content = remaining))
        }

        private fun parseToolBlock(payload: String): LlmToolCall? =
            runCatching {
                val element = json.parseToJsonElement(payload)
                element.toToolCall() ?: run {
                    val obj = element.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
                    val args = obj["arguments"] ?: obj["args"] ?: JsonObject(emptyMap())
                    LlmToolCall(
                        id = obj["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString(),
                        functionName = name,
                        arguments = args.toString(),
                    )
                }
            }.getOrNull()
    }
}

private fun JsonElement.findString(vararg keys: String): String? {
    when (this) {
        is JsonPrimitive -> return contentOrNull
        is JsonObject -> {
            keys.forEach { key ->
                val value = this[key]
                if (value is JsonPrimitive) value.contentOrNull?.let { return it }
            }
            this["message"]?.let { nested ->
                if (nested is JsonObject) nested.findString(*keys)?.let { return it }
            }
            this["delta"]?.let { nested ->
                if (nested is JsonObject) nested.findString(*keys)?.let { return it }
            }
            values.forEach { value ->
                if (value is JsonObject) value.findString(*keys)?.let { return it }
            }
        }
        is JsonArray -> {
            forEach { element -> element.findString(*keys)?.let { return it } }
        }
    }
    return null
}

private fun JsonElement.toToolCall(): LlmToolCall? {
    val obj = this as? JsonObject ?: return null

    val directName =
        obj["tool"]?.jsonPrimitiveOrNull()?.contentOrNull
            ?: obj["toolName"]?.jsonPrimitiveOrNull()?.contentOrNull
            ?: obj["tool_name"]?.jsonPrimitiveOrNull()?.contentOrNull
            ?: obj["functionName"]?.jsonPrimitiveOrNull()?.contentOrNull
            ?: obj["name"]?.jsonPrimitiveOrNull()?.contentOrNull?.takeIf {
                obj["arguments"] != null || obj["args"] != null
            }

    if (directName != null) {
        val args = obj["arguments"] ?: obj["args"] ?: JsonObject(emptyMap())
        return LlmToolCall(
            id = obj["id"]?.jsonPrimitiveOrNull()?.contentOrNull ?: UUID.randomUUID().toString(),
            functionName = directName,
            arguments = args.toString(),
        )
    }

    val nestedFunction = obj["function"] as? JsonObject
    if (nestedFunction != null) {
        val name = nestedFunction["name"]?.jsonPrimitiveOrNull()?.contentOrNull
        val args = nestedFunction["arguments"] ?: nestedFunction["args"] ?: JsonObject(emptyMap())
        if (name != null) {
            return LlmToolCall(
                id = obj["id"]?.jsonPrimitiveOrNull()?.contentOrNull ?: UUID.randomUUID().toString(),
                functionName = name,
                arguments = if (args is JsonPrimitive) args.contentOrNull ?: "{}" else args.toString(),
            )
        }
    }

    val toolCalls = obj["tool_calls"] ?: obj["toolCalls"]
    if (toolCalls is JsonArray) {
        toolCalls.firstOrNull()?.toToolCall()?.let { return it }
    }

    return null
}

private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
