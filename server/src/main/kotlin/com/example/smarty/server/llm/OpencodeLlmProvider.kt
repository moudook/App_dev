package com.example.smarty.server.llm

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * High-performance LLM provider implementing the OpenCode CLI agentic integration.
 * Supports:
 * 1. Headless HTTP API Daemon Mode (via 'opencode serve')
 * 2. Shell Subprocess Fallback Mode (via 'opencode run')
 */
class OpencodeLlmProvider(
    private val client: HttpClient,
    override val providerName: String = "OpenCode",
    private val defaultModel: String = "opencode/deepseek-v4-flash-free",
    private val daemonPort: Int = 4096,
    private val daemonHost: String = "127.0.0.1"
) : LlmProvider {

    private val logger = LoggerFactory.getLogger(OpencodeLlmProvider::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val daemonBaseUrl = "http://$daemonHost:$daemonPort"

    /**
     * Checks if the headless API server is running on the specified port.
     */
    private suspend fun isDaemonRunning(): Boolean {
        return try {
            val response = client.get("$daemonBaseUrl/models") {
                timeout {
                    requestTimeoutMillis = 2000
                    connectTimeoutMillis = 1000
                }
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Helper to retrieve a delegated OpenAiCompatibleProvider pointing to the local daemon
     */
    private fun getDaemonProvider(model: String?): OpenAiCompatibleProvider {
        return OpenAiCompatibleProvider(
            client = client,
            providerName = "OpenCode-Daemon",
            baseUrl = "$daemonBaseUrl/v1",
            apiKey = "not-needed",
            defaultModel = model ?: defaultModel
        )
    }

    override suspend fun generate(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?
    ): LlmResponse {
        val modelName = model ?: defaultModel
        if (isDaemonRunning()) {
            logger.info("Using OpenCode Headless Daemon for generation.")
            return getDaemonProvider(modelName).generate(messages, tools, modelName)
        }

        logger.info("OpenCode daemon not detected. Falling back to subprocess CLI execution.")
        return executeSubprocess(messages, tools, modelName, stream = false)
    }

    override suspend fun stream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String?
    ): Flow<LlmChunk> {
        val modelName = model ?: defaultModel
        return flow {
            if (isDaemonRunning()) {
                logger.info("Streaming from OpenCode Headless Daemon.")
                getDaemonProvider(modelName).stream(messages, tools, modelName).collect {
                    emit(it)
                }
            } else {
                logger.info("Streaming from OpenCode subprocess fallback.")
                executeSubprocessStream(messages, tools, modelName).collect {
                    emit(it)
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    private fun formatMessagesToPrompt(messages: List<LlmMessage>): String {
        return messages.joinToString("\n\n") { msg ->
            when (msg.role) {
                LlmMessage.Role.SYSTEM -> "<system>\n${msg.content}\n</system>"
                LlmMessage.Role.USER -> "<user>\n${msg.content}\n</user>"
                LlmMessage.Role.ASSISTANT -> {
                    val thinkingPart = if (!msg.thinking.isNullOrBlank()) "<think>\n${msg.thinking}\n</think>\n" else ""
                    "<assistant>\n$thinkingPart${msg.content}\n</assistant>"
                }
                LlmMessage.Role.TOOL -> {
                    val toolName = msg.name ?: "tool"
                    "<tool name=\"$toolName\">\n${msg.content}\n</tool>"
                }
            }
        }
    }

    /**
     * Executes the opencode CLI via ProcessBuilder for one-shot generations.
     */
    private suspend fun executeSubprocess(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String,
        stream: Boolean
    ): LlmResponse = withContext(Dispatchers.IO) {
        val prompt = formatMessagesToPrompt(messages)
        val tempDir = File(System.getProperty("user.dir"), "_temp").apply { if (!exists()) mkdirs() }

        // Construct CLI execution command
        val os = System.getProperty("os.name").lowercase()
        val cmd = mutableListOf<String>()

        if (os.contains("win")) {
            cmd.addAll(listOf("cmd.exe", "/c", "opencode"))
        } else {
            cmd.add("opencode")
        }

        cmd.addAll(
            listOf(
                "run",
                "--model", model,
                "--pure",
                "--format", "json",
                "--dir", tempDir.absolutePath,
                prompt
            )
        )

        try {
            logger.info("Running CLI command: ${cmd.joinToString(" ")}")
            val process = ProcessBuilder(cmd)
                .directory(tempDir)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                line?.let { output.append(it).append("\n") }
            }

            process.waitFor()
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                throw IllegalStateException("OpenCode process exited with code $exitCode. Output:\n$output")
            }

            // Parse response from JSON lines or standard format
            var parsedText = ""
            val outputStr = output.toString()
            outputStr.split("\n").forEach { jsonLine ->
                if (jsonLine.trim().startsWith("{")) {
                    try {
                        val parsed = json.parseToJsonElement(jsonLine).jsonObject
                        val type = parsed["type"]?.jsonPrimitive?.content
                        val text = parsed["text"]?.jsonPrimitive?.content
                        if (type == "text-delta" && text != null) {
                            parsedText += text
                        }
                    } catch (e: Exception) {
                        // ignore malformed JSON lines
                    }
                }
            }

            if (parsedText.isEmpty()) {
                // Fallback: use raw output if not strictly JSON lines formatted
                parsedText = outputStr.replace(Regex("> build · .*"), "").trim()
            }

            LlmResponse(content = parsedText)
        } catch (e: Exception) {
            logger.error("CLI subprocess execution failed", e)
            throw IllegalStateException("Failed to run OpenCode CLI subprocess: ${e.message}", e)
        }
    }

    /**
     * Executes CLI streaming via ProcessBuilder.
     */
    private fun executeSubprocessStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        model: String
    ): Flow<LlmChunk> = flow {
        val prompt = formatMessagesToPrompt(messages)
        val tempDir = File(System.getProperty("user.dir"), "_temp").apply { if (!exists()) mkdirs() }

        val os = System.getProperty("os.name").lowercase()
        val cmd = mutableListOf<String>()

        if (os.contains("win")) {
            cmd.addAll(listOf("cmd.exe", "/c", "opencode"))
        } else {
            cmd.add("opencode")
        }

        cmd.addAll(
            listOf(
                "run",
                "--model", model,
                "--pure",
                "--format", "json",
                "--dir", tempDir.absolutePath,
                prompt
            )
        )

        try {
            logger.info("Spawning CLI stream command: ${cmd.joinToString(" ")}")
            val process = ProcessBuilder(cmd)
                .directory(tempDir)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                if (currentLine.trim().startsWith("{")) {
                    try {
                        val parsed = json.parseToJsonElement(currentLine).jsonObject
                        val type = parsed["type"]?.jsonPrimitive?.content
                        val text = parsed["text"]?.jsonPrimitive?.content ?: ""

                        when (type) {
                            "reasoning-delta" -> emit(LlmChunk(content = null, reasoning = text))
                            "text-delta" -> emit(LlmChunk(content = text, reasoning = null))
                            "tool-call" -> {
                                val id = parsed["id"]?.jsonPrimitive?.content ?: ""
                                val name = parsed["name"]?.jsonPrimitive?.content ?: ""
                                val args = parsed["args"]?.jsonPrimitive?.content ?: "{}"
                                emit(LlmChunk(content = null, toolCall = LlmToolCall(id, name, args)))
                            }
                        }
                    } catch (e: Exception) {
                        // ignore malformed JSON lines
                    }
                } else {
                    // Fallback to text delta if not JSON
                    val clean = currentLine.replace(Regex("> build · .*"), "").trim()
                    if (clean.isNotEmpty()) {
                        emit(LlmChunk(content = clean + "\n", reasoning = null))
                    }
                }
            }

            process.waitFor()
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                logger.warn("OpenCode stream subprocess finished with non-zero exit code $exitCode")
            }
        } catch (e: Exception) {
            logger.error("CLI subprocess stream execution failed", e)
            throw e
        }
    }.flowOn(Dispatchers.IO)
}
