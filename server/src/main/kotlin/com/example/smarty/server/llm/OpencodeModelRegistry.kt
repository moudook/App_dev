package com.example.smarty.server.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val MAX_FREE_MODELS = 3

    // Runtime-discovered models — zero hardcoded names
    private val discoveredModels = AtomicReference<List<OpencodeModelInfo>>(emptyList())
    private val cachedState = AtomicReference<OpencodeModelState?>(null)

    // Track which env vars are known-invalid so we don't log warnings repeatedly
    private val invalidEnvModelsLogged = AtomicBoolean(false)
    private var invalidEnvModelValue: String? = null

    val defaultModel: String
        get() = discoveredModels.get().firstOrNull()?.id ?: ""

    /**
     * Blocking discovery — runs `opencode models` at startup.
     * Filters for "free" in model ID, sorts alphabetically, takes top 3.
     */
    fun discoverAtStartup(timeoutMs: Long = 15_000L) {
        val start = System.currentTimeMillis()
        logger.info("[OpencodeModelRegistry] === PHASE 1: Model Discovery ===")
        logger.info("[OpencodeModelRegistry] Running 'opencode models' to discover free models at runtime...")

        // Log env var state upfront
        val envOpenCodeModel = System.getenv("OPENCODE_MODEL")
        val envLlmModelId = System.getenv("LLM_MODEL_ID")
        logger.info("[OpencodeModelRegistry] Environment: OPENCODE_MODEL={}, LLM_MODEL_ID={}",
            envOpenCodeModel ?: "(not set)", envLlmModelId ?: "(not set)")

        val models = runCatching {
            val workDir = java.io.File(System.getProperty("user.dir"))
            logger.info("[OpencodeModelRegistry] Working directory: {}", workDir.absolutePath)

            val cliStart = System.currentTimeMillis()
            val process = ProcessBuilder(listOf("opencode", "models"))
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
            val cliDuration = System.currentTimeMillis() - cliStart

            if (!completed) {
                process.destroyForcibly()
                throw IllegalStateException("opencode models timed out after ${timeoutMs}ms")
            }

            val exitCode = process.exitValue()
            logger.info("[OpencodeModelRegistry] CLI exited in {}ms — exit code: {}, raw lines: {}",
                cliDuration, exitCode, lines.size)

            if (exitCode != 0) {
                logger.warn("[OpencodeModelRegistry] CLI returned non-zero exit code")
                lines.forEach { logger.debug("[OpencodeModelRegistry]   RAW: {}", it) }
            } else {
                lines.forEach { logger.debug("[OpencodeModelRegistry]   RAW: {}", it) }
            }

            parseAndSortFreeModels(lines)
        }.getOrElse { error ->
            logger.error("[OpencodeModelRegistry] Discovery failed: {}", error.message)
            emptyList()
        }

        discoveredModels.set(models)

        // Validate env vars against discovered models — log ONCE if invalid
        validateEnvVarsAgainstDiscovered(models)

        if (models.isEmpty()) {
            logger.error("[OpencodeModelRegistry] CRITICAL: ZERO free models discovered! Chat will fail.")
        } else {
            logger.info("[OpencodeModelRegistry] Discovery complete in {}ms — {} free models:",
                System.currentTimeMillis() - start, models.size)
            models.forEachIndexed { i, m ->
                logger.info("[OpencodeModelRegistry]   [${i + 1}] {} ({})", m.id, m.label)
            }
            logger.info("[OpencodeModelRegistry] Default model (first discovered): {}", models.first().id)
        }

        val state = OpencodeModelState(
            defaultModel = models.firstOrNull()?.id ?: "",
            activeModel = models.firstOrNull()?.id ?: "",
            models = models,
            source = if (models.isEmpty()) "none" else "cli-discovered",
        )
        cachedState.set(state)
        logger.info("[OpencodeModelRegistry] === PHASE 1 COMPLETE ===")
    }

    /**
     * Check env vars at startup. If they point to non-free models, log ONCE and remember
     * so we don't spam warnings on every request.
     */
    private fun validateEnvVarsAgainstDiscovered(models: List<OpencodeModelInfo>) {
        val envModel = System.getenv("OPENCODE_MODEL") ?: System.getenv("LLM_MODEL_ID") ?: return

        val discoveredIds = models.map { it.id }.toSet()
        val isFree = envModel in discoveredIds ||
            (envModel.startsWith("opencode/") && envModel.contains("free", ignoreCase = true))

        if (!isFree) {
            invalidEnvModelValue = envModel
            invalidEnvModelsLogged.set(true)
            logger.warn("[OpencodeModelRegistry] Env var model '{}' is NOT a free OpenCode model.", envModel)
            logger.warn("[OpencodeModelRegistry]   This env var will be IGNORED. Using discovered default: {}", models.firstOrNull()?.id)
            logger.warn("[OpencodeModelRegistry]   To fix: unset LLM_MODEL_ID or set it to a free model (e.g., {}) on HF Space settings.", models.firstOrNull()?.id)
        } else {
            logger.info("[OpencodeModelRegistry] Env var model '{}' is valid (free model).", envModel)
        }
    }

    fun isAllowedFreeModel(model: String?): Boolean {
        val normalized = model?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val discovered = discoveredModels.get()

        if (discovered.any { it.id == normalized }) {
            return true
        }

        return normalized.startsWith("opencode/") && normalized.contains("free", ignoreCase = true)
    }

    /**
     * Validate and return a free model.
     *
     * Priority: explicit parameter > discovered default.
     * Env vars (LLM_MODEL_ID, OPENCODE_MODEL) are IGNORED if they point to non-free models
     * (detected at startup, logged once, then silently skipped).
     */
    fun requireAllowedFreeModel(model: String?): String {
        val discovered = discoveredModels.get()
        val fallback = discovered.firstOrNull()?.id ?: ""

        // Step 1: Use explicit parameter if provided and valid
        val paramModel = model?.trim()?.takeIf { it.isNotBlank() }
        if (paramModel != null) {
            return if (isAllowedFreeModel(paramModel)) {
                logger.debug("[OpencodeModelRegistry] Model from parameter: {}", paramModel)
                paramModel
            } else {
                logger.warn("[OpencodeModelRegistry] Parameter model '{}' rejected (not free) — using default: {}", paramModel, fallback)
                fallback.ifEmpty { throwNoModelsError() }
            }
        }

        // Step 2: Check env vars — but skip if we know they're invalid (logged at startup)
        if (invalidEnvModelsLogged.get()) {
            logger.debug("[OpencodeModelRegistry] Skipping invalid env var model '{}' (logged at startup) — using default: {}",
                invalidEnvModelValue, fallback)
            return fallback.ifEmpty { throwNoModelsError() }
        }

        // Step 3: Try env vars (only if not previously flagged as invalid)
        val envModel = System.getenv("OPENCODE_MODEL") ?: System.getenv("LLM_MODEL_ID")
        if (envModel != null && envModel.isNotBlank()) {
            return if (isAllowedFreeModel(envModel)) {
                logger.debug("[OpencodeModelRegistry] Model from env var: {}", envModel)
                envModel
            } else {
                // First time seeing this — flag it
                invalidEnvModelValue = envModel
                invalidEnvModelsLogged.set(true)
                logger.warn("[OpencodeModelRegistry] Env var model '{}' is not free — ignoring, using default: {}", envModel, fallback)
                fallback.ifEmpty { throwNoModelsError() }
            }
        }

        // Step 4: Use discovered default
        logger.debug("[OpencodeModelRegistry] Using discovered default model: {}", fallback)
        return fallback.ifEmpty { throwNoModelsError() }
    }

    private fun throwNoModelsError(): Nothing {
        throw IllegalStateException("[OpencodeModelRegistry] NO free models available. Run 'opencode models' to verify CLI installation.")
    }

    fun discoveredFreeModels(): List<OpencodeModelInfo> = discoveredModels.get()

    fun currentState(activeModel: String? = null): OpencodeModelState {
        val state = cachedState.get()
        val now = System.currentTimeMillis()
        val isStale = state == null || (now - state.updatedAt) > CACHE_TTL_MS

        return if (isStale) {
            logger.info("[OpencodeModelRegistry] Cache stale — refreshing")
            runBlockingRefresh()
        } else {
            state.copy(
                activeModel = requireAllowedFreeModel(activeModel ?: state.activeModel),
                source = state.source,
            )
        }
    }

    suspend fun refreshFromCli(timeoutMs: Long = 12_000L): OpencodeModelState =
        withContext(Dispatchers.IO) {
            val refreshStart = System.currentTimeMillis()
            logger.info("[OpencodeModelRegistry] === PHASE: Runtime Model Refresh ===")

            val discovered = runCatching {
                val workDir = java.io.File(System.getProperty("user.dir"))
                val cliStart = System.currentTimeMillis()

                val process = ProcessBuilder(listOf("opencode", "models"))
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
                val cliDuration = System.currentTimeMillis() - cliStart

                if (!completed) {
                    process.destroyForcibly()
                    throw IllegalStateException("opencode models timed out")
                }

                val exitCode = process.exitValue()
                logger.info("[OpencodeModelRegistry] CLI exited in {}ms — code: {}, lines: {}",
                    cliDuration, exitCode, lines.size)

                if (exitCode != 0) {
                    lines.forEach { logger.debug("[OpencodeModelRegistry]   RAW: {}", it) }
                }

                parseAndSortFreeModels(lines)
            }.getOrElse { error ->
                logger.warn("[OpencodeModelRegistry] Refresh failed: {}", error.message)
                emptyList()
            }

            val finalModels = discovered.ifEmpty { discoveredModels.get() }
            discoveredModels.set(finalModels)

            val newState = OpencodeModelState(
                defaultModel = finalModels.firstOrNull()?.id ?: "",
                activeModel = requireAllowedFreeModel(null),
                models = finalModels,
                source = if (discovered.isEmpty()) "cached" else "cli-discovered",
                updatedAt = System.currentTimeMillis(),
            )
            cachedState.set(newState)

            logger.info("[OpencodeModelRegistry] Refresh complete in {}ms — {} models (source: {})",
                System.currentTimeMillis() - refreshStart, finalModels.size, newState.source)
            finalModels.forEach { logger.info("[OpencodeModelRegistry]   - {} ({})", it.id, it.label) }
            logger.info("[OpencodeModelRegistry] === PHASE COMPLETE ===")
            newState
        }

    private fun runBlockingRefresh(): OpencodeModelState {
        val start = System.currentTimeMillis()
        val discovered = runCatching {
            val workDir = java.io.File(System.getProperty("user.dir"))
            val process = ProcessBuilder(listOf("opencode", "models"))
                .directory(workDir)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val lines = mutableListOf<String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                lines.add(line!!)
            }

            val completed = process.waitFor(12_000L, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                throw IllegalStateException("opencode models timed out")
            }

            if (process.exitValue() != 0) {
                logger.warn("[OpencodeModelRegistry] CLI exited with code ${process.exitValue()}")
            }

            parseAndSortFreeModels(lines)
        }.getOrElse { error ->
            logger.warn("[OpencodeModelRegistry] Blocking refresh failed: {}", error.message)
            emptyList()
        }

        val finalModels = discovered.ifEmpty { discoveredModels.get() }
        discoveredModels.set(finalModels)

        val newState = OpencodeModelState(
            defaultModel = finalModels.firstOrNull()?.id ?: "",
            activeModel = finalModels.firstOrNull()?.id ?: "",
            models = finalModels,
            source = if (discovered.isEmpty()) "cached" else "cli-discovered",
            updatedAt = System.currentTimeMillis(),
        )
        cachedState.set(newState)

        logger.info("[OpencodeModelRegistry] Blocking refresh complete in {}ms — {} models",
            System.currentTimeMillis() - start, finalModels.size)
        return newState
    }

    private fun parseAndSortFreeModels(lines: List<String>): List<OpencodeModelInfo> {
        val allModels = mutableListOf<String>()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("─") || line.startsWith("│")) {
                continue
            }

            val modelId = extractModelId(line) ?: continue

            if (!modelId.contains("free", ignoreCase = true)) {
                logger.debug("[OpencodeModelRegistry] Skipping non-free: {}", modelId)
                continue
            }

            if (!modelId.startsWith("opencode/")) {
                logger.debug("[OpencodeModelRegistry] Skipping non-opencode: {}", modelId)
                continue
            }

            allModels.add(modelId)
        }

        val sorted = allModels.sorted()
        val top3 = sorted.take(MAX_FREE_MODELS)

        logger.info("[OpencodeModelRegistry] Parsed {} total free models from CLI, taking top {}", sorted.size, top3.size)

        return top3.map { modelId ->
            val label = modelId
                .removePrefix("opencode/")
                .replace(Regex("[-.]+"), " ")
                .split(" ")
                .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
            OpencodeModelInfo(id = modelId, label = label)
        }
    }

    private fun extractModelId(line: String): String? {
        val stripped = line.replace("│", "").trim()
        val match = Regex("""(opencode/[\w.\-]+)""").find(stripped)
        return match?.groupValues?.get(1)
    }
}
