package com.example.smarty.server.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
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
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes

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
        val now = System.currentTimeMillis()
        val isStale = (now - state.updatedAt) > CACHE_TTL_MS
        return state.copy(
            activeModel = validatedActive,
            source = if (isStale) "stale" else state.source,
        )
    }

    suspend fun refreshFromCli(timeoutMs: Long = 12_000L): OpencodeModelState =
        withContext(Dispatchers.IO) {
            val state = cachedState.get()
            val now = System.currentTimeMillis()

            if ((now - state.updatedAt) < CACHE_TTL_MS && state.source != "bundled") {
                logger.info("OpenCode models cache is fresh (age: ${(now - state.updatedAt) / 1000}s), returning cached")
                return@withContext state
            }

            val discovered =
                runCatching {
                    val process =
                        ProcessBuilder(listOf("opencode", "models", "opencode"))
                            .redirectErrorStream(true)
                            .start()

                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val lines = mutableListOf<String>()
                    var line = reader.readLine()
                    while (line != null) {
                        lines.add(line)
                        line = reader.readLine()
                    }

                    val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                    if (!completed) {
                        process.destroyForcibly()
                        throw IllegalStateException("opencode models timed out after ${timeoutMs}ms")
                    }

                    parseFreeModelsFromCli(lines)
                }.getOrElse { error ->
                    logger.warn("Could not refresh OpenCode models from CLI: {}", error.message)
                    emptyList()
                }

            val finalModels = discovered.ifEmpty { bundledFreeModels }

            val newState =
                OpencodeModelState(
                    defaultModel = DEFAULT_MODEL,
                    activeModel = requireAllowedFreeModel(System.getenv("OPENCODE_MODEL") ?: System.getenv("LLM_MODEL_ID")),
                    models = finalModels,
                    source = if (discovered.isEmpty()) "bundled" else "cli-filtered",
                    updatedAt = System.currentTimeMillis(),
                )
            cachedState.set(newState)
            logger.info("OpenCode models refreshed: {} free models (source: {})", finalModels.size, newState.source)
            newState
        }

    private fun parseFreeModelsFromCli(lines: List<String>): List<OpencodeModelInfo> {
        val modelRegex = Regex("""(opencode/[-\w.]+)\s+(.+?)\s+(Free|free)""")
        val parsed = mutableListOf<OpencodeModelInfo>()

        for (line in lines) {
            val match = modelRegex.find(line) ?: continue
            val modelId = match.groupValues[1].trim()
            val label = match.groupValues[2].trim()
            if (modelId in allowList || modelId.endsWith("-free")) {
                parsed.add(OpencodeModelInfo(id = modelId, label = label))
                logger.debug("Discovered free model from CLI: {} ({})", modelId, label)
            }
        }

        if (parsed.isNotEmpty()) {
            logger.info("Parsed {} free models from CLI output", parsed.size)
        }
        return parsed
    }
}
