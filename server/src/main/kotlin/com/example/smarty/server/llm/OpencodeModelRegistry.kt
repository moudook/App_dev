package com.example.smarty.server.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class OpencodeModelInfo(
    val id: String,
    val label: String,
    val provider: String = "opencode",
    val free: Boolean = true,
    val available: Boolean = true,
)

@Serializable
data class OpencodeModelState(
    val provider: String = "OPENCODE",
    val defaultModel: String,
    val activeModel: String,
    val models: List<OpencodeModelInfo>,
    val source: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

object OpencodeModelRegistry {
    private val logger = LoggerFactory.getLogger(OpencodeModelRegistry::class.java)

    const val DEFAULT_MODEL = "opencode/deepseek-v4-flash-free"

    private val bundledFreeModels =
        listOf(
            OpencodeModelInfo("opencode/deepseek-v4-flash-free", "DeepSeek V4 Flash Free"),
            OpencodeModelInfo("opencode/minimax-m2.5-free", "MiniMax M2.5 Free"),
            OpencodeModelInfo("opencode/nemotron-3-super-free", "Nemotron 3 Super Free"),
            OpencodeModelInfo("opencode/qwen3.6-plus-free", "Qwen 3.6 Plus Free"),
            OpencodeModelInfo("opencode/big-pickle", "Big Pickle"),
        )

    private val allowList = bundledFreeModels.map { it.id }.toSet()
    private val cachedState = AtomicReference(
        OpencodeModelState(
            defaultModel = DEFAULT_MODEL,
            activeModel = DEFAULT_MODEL,
            models = bundledFreeModels,
            source = "bundled",
        ),
    )

    fun isAllowedFreeModel(model: String?): Boolean {
        val normalized = model?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return normalized in allowList || (normalized.startsWith("opencode/") && normalized.endsWith("-free"))
    }

    fun requireAllowedFreeModel(model: String?): String {
        val candidate =
            model
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: System.getenv("OPENCODE_MODEL")
                ?: System.getenv("LLM_MODEL_ID")
                ?: DEFAULT_MODEL

        return if (isAllowedFreeModel(candidate)) {
            candidate
        } else {
            logger.warn("Rejected non-free or unknown OpenCode model override: {}", candidate)
            DEFAULT_MODEL
        }
    }

    fun fallbackModels(): List<OpencodeModelInfo> = bundledFreeModels

    fun currentState(activeModel: String? = null): OpencodeModelState {
        val state = cachedState.get()
        val validatedActive = requireAllowedFreeModel(activeModel ?: state.activeModel)
        return state.copy(activeModel = validatedActive)
    }

    suspend fun refreshFromCli(timeoutMs: Long = 12_000L): OpencodeModelState =
        withContext(Dispatchers.IO) {
            val output =
                runCatching {
                    val process =
                        ProcessBuilder(resolveCommand("opencode") + listOf("models"))
                            .redirectErrorStream(true)
                            .start()

                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val text = buildString {
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            appendLine(line)
                        }
                    }

                    val completed = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (!completed) {
                        process.destroyForcibly()
                        throw IllegalStateException("opencode models timed out after ${timeoutMs}ms")
                    }
                    text
                }.getOrElse { error ->
                    logger.warn("Could not refresh OpenCode models from CLI: {}", error.message)
                    ""
                }

            val discovered =
                allowList
                    .filter { id -> output.contains(id, ignoreCase = true) }
                    .mapNotNull { id -> bundledFreeModels.firstOrNull { it.id == id } }
                    .ifEmpty { bundledFreeModels }

            val state =
                OpencodeModelState(
                    defaultModel = DEFAULT_MODEL,
                    activeModel = requireAllowedFreeModel(System.getenv("OPENCODE_MODEL") ?: System.getenv("LLM_MODEL_ID")),
                    models = discovered,
                    source = if (output.isBlank()) "bundled" else "cli-filtered",
                )
            cachedState.set(state)
            state
        }

    internal fun resolveCommand(binary: String): List<String> {
        return listOf(binary)
    }
}
