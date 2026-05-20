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

    // Bundled fallback — used when CLI discovery fails or cache is stale
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

    /**
     * Discover free models by running `opencode models` on the server.
     *
     * The CLI outputs one model ID per line:
     *   opencode/big-pickle
     *   opencode/deepseek-v4-flash-free
     *   opencode/minimax-m2.5-free
     *   ...
     *
     * We filter for lines containing "-free" suffix to identify free Zen models.
     * Results are cached for 5 minutes to avoid excessive CLI invocations.
     */
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
                    // Run in the app working directory where opencode.json lives
                    val workDir = java.io.File(System.getProperty("user.dir"))
                    logger.info("Running 'opencode models' in working directory: ${workDir.absolutePath}")

                    val process =
                        ProcessBuilder(listOf("opencode", "models"))
                            .directory(workDir)
                            .redirectErrorStream(true)
                            .start()

                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val lines = mutableListOf<String>()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        lines.add(line!!)
                    }

                    val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                    if (!completed) {
                        process.destroyForcibly()
                        throw IllegalStateException("opencode models timed out after ${timeoutMs}ms")
                    }

                    val exitCode = process.exitValue()
                    if (exitCode != 0) {
                        logger.warn("opencode models exited with code $exitCode")
                    }

                    logger.info("opencode models output: {} lines", lines.size)
                    lines.forEach { logger.debug("  CLI line: {}", it) }

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
                    source = if (discovered.isEmpty()) "bundled" else "cli-discovered",
                    updatedAt = System.currentTimeMillis(),
                )
            cachedState.set(newState)
            logger.info("OpenCode models refreshed: {} free models (source: {})", finalModels.size, newState.source)
            newState
        }

    /**
     * Parse `opencode models` output.
     *
     * Each line is a model ID like `opencode/deepseek-v4-flash-free`.
     * We filter for lines that:
     *   1. Start with "opencode/"
     *   2. End with "-free"
     *
     * This works on both Linux (HF Spaces) and Windows (dev machine).
     */
    private fun parseFreeModelsFromCli(lines: List<String>): List<OpencodeModelInfo> {
        val parsed = mutableListOf<OpencodeModelInfo>()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("─") || line.startsWith("│")) {
                continue
            }

            // Extract model ID — handle both plain IDs and table-formatted lines
            val modelId = extractModelId(line) ?: continue

            // Only accept models with -free suffix
            if (!modelId.endsWith("-free")) {
                logger.debug("Skipping non-free model from CLI: {}", modelId)
                continue
            }

            // Validate it's an opencode-scoped model
            if (!modelId.startsWith("opencode/")) {
                logger.debug("Skipping non-opencode model from CLI: {}", modelId)
                continue
            }

            val label = modelId
                .removePrefix("opencode/")
                .removeSuffix("-free")
                .replace(Regex("[-.]+"), " ")
                .split(" ")
                .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } } + " Free"

            parsed.add(OpencodeModelInfo(id = modelId, label = label))
            logger.debug("Discovered free model from CLI: {} ({})", modelId, label)
        }

        if (parsed.isNotEmpty()) {
            logger.info("Parsed {} free models from CLI output", parsed.size)
        }
        return parsed
    }

    /**
     * Extract a model ID from a CLI output line.
     *
     * Handles formats:
     *   - Plain: `opencode/deepseek-v4-flash-free`
     *   - Table: `│ opencode/deepseek-v4-flash-free │ ...`
     *   - Leading whitespace: `  opencode/deepseek-v4-flash-free`
     */
    private fun extractModelId(line: String): String? {
        // Strip table formatting: │ ... │
        val stripped = line.replace("│", "").trim()

        // Match opencode/... pattern
        val match = Regex("""(opencode/[\w.\-]+)""").find(stripped)
        return match?.groupValues?.get(1)
    }
}
