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

    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    private const val MAX_FREE_MODELS = 3

    // Dynamically discovered at startup — NO hardcoded model names
    private val discoveredModels = AtomicReference<List<OpencodeModelInfo>>(emptyList())
    private val cachedState = AtomicReference<OpencodeModelState?>(null)

    /**
     * Default model — resolved at runtime from discovered free models.
     * Returns the first discovered free model, or empty string if none found.
     */
    val defaultModel: String
        get() = discoveredModels.get().firstOrNull()?.id ?: ""

    /**
     * Blocking discovery — runs `opencode models` at startup.
     * Filters for "free" in model ID, sorts, takes top 3.
     */
    fun discoverAtStartup(timeoutMs: Long = 15_000L) {
        val start = System.currentTimeMillis()
        logger.info("[OpencodeModelRegistry] Discovering free models at startup...")

        val models = runCatching {
            val workDir = java.io.File(System.getProperty("user.dir"))
            logger.info("[OpencodeModelRegistry] Running 'opencode models' in: {}", workDir.absolutePath)

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
            if (!completed) {
                process.destroyForcibly()
                throw IllegalStateException("opencode models timed out after ${timeoutMs}ms")
            }

            val exitCode = process.exitValue()
            logger.info("[OpencodeModelRegistry] CLI exited in {}ms — code: {}, lines: {}",
                System.currentTimeMillis() - start, exitCode, lines.size)

            if (exitCode != 0) {
                logger.warn("[OpencodeModelRegistry] 'opencode models' exited with code $exitCode")
                lines.forEach { logger.debug("[OpencodeModelRegistry]   CLI output: {}", it) }
            }

            parseAndSortFreeModels(lines)
        }.getOrElse { error ->
            logger.error("[OpencodeModelRegistry] Startup model discovery failed: {}", error.message)
            emptyList()
        }

        discoveredModels.set(models)

        if (models.isEmpty()) {
            logger.error("[OpencodeModelRegistry] NO free models discovered at startup! Chat will fail until models are available.")
        } else {
            logger.info("[OpencodeModelRegistry] Startup discovery complete in {}ms — {} free models found:",
                System.currentTimeMillis() - start, models.size)
            models.forEachIndexed { i, m ->
                logger.info("[OpencodeModelRegistry]   [${i + 1}] {} ({})", m.id, m.label)
            }
            logger.info("[OpencodeModelRegistry] Default model: {}", models.first().id)
        }

        // Initialize cached state
        val state = OpencodeModelState(
            defaultModel = models.firstOrNull()?.id ?: "",
            activeModel = models.firstOrNull()?.id ?: "",
            models = models,
            source = if (models.isEmpty()) "none" else "cli-discovered",
        )
        cachedState.set(state)
    }

    /**
     * Check if a model is allowed — must be in discovered list OR match the free pattern.
     */
    fun isAllowedFreeModel(model: String?): Boolean {
        val normalized = model?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val discovered = discoveredModels.get()

        // If in discovered list, allow
        if (discovered.any { it.id == normalized }) {
            logger.debug("[OpencodeModelRegistry] isAllowedFreeModel('{}') = true (discovered)", normalized)
            return true
        }

        // Pattern match: opencode/...-free
        val patternMatch = normalized.startsWith("opencode/") && normalized.contains("free", ignoreCase = true)
        logger.debug("[OpencodeModelRegistry] isAllowedFreeModel('{}') = {} (pattern match)", normalized, patternMatch)
        return patternMatch
    }

    /**
     * Validate and return a free model. Falls back to first discovered free model.
     */
    fun requireAllowedFreeModel(model: String?): String {
        val discovered = discoveredModels.get()
        val fallback = discovered.firstOrNull()?.id ?: ""

        val candidate = model?.trim()?.takeIf { it.isNotBlank() }
            ?: System.getenv("OPENCODE_MODEL")
            ?: System.getenv("LLM_MODEL_ID")
            ?: fallback

        return if (isAllowedFreeModel(candidate)) {
            logger.info("[OpencodeModelRegistry] Model validated: {} (source: {})", candidate,
                when {
                    model != null -> "parameter"
                    System.getenv("OPENCODE_MODEL") != null -> "OPENCODE_MODEL env"
                    System.getenv("LLM_MODEL_ID") != null -> "LLM_MODEL_ID env"
                    else -> "default (first discovered)"
                })
            candidate
        } else {
            logger.warn("[OpencodeModelRegistry] Rejected non-free model: '{}' — falling back to {}", candidate, fallback)
            fallback.ifEmpty {
                throw IllegalStateException("[OpencodeModelRegistry] NO free models available — 'opencode models' returned zero free models. Check CLI installation.")
            }
        }
    }

    /**
     * Return all discovered free models.
     */
    fun discoveredFreeModels(): List<OpencodeModelInfo> = discoveredModels.get()

    /**
     * Get current state, triggering a refresh if cache is stale.
     */
    fun currentState(activeModel: String? = null): OpencodeModelState {
        val state = cachedState.get()
        val now = System.currentTimeMillis()
        val isStale = state == null || (now - state.updatedAt) > CACHE_TTL_MS

        return if (isStale) {
            logger.info("[OpencodeModelRegistry] Cache stale or missing — triggering refresh")
            // Synchronous refresh for currentState (called from routes)
            runBlockingRefresh()
        } else {
            state.copy(
                activeModel = requireAllowedFreeModel(activeModel ?: state.activeModel),
                source = state.source,
            )
        }
    }

    /**
     * Refresh models from CLI (async, for route handlers).
     */
    suspend fun refreshFromCli(timeoutMs: Long = 12_000L): OpencodeModelState =
        withContext(Dispatchers.IO) {
            val refreshStart = System.currentTimeMillis()
            logger.info("[OpencodeModelRegistry] Refreshing models from CLI...")

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
                    throw IllegalStateException("opencode models timed out after ${timeoutMs}ms")
                }

                val exitCode = process.exitValue()
                logger.info("[OpencodeModelRegistry] CLI exited in {}ms — code: {}, lines: {}",
                    cliDuration, exitCode, lines.size)

                if (exitCode != 0) {
                    lines.forEach { logger.debug("[OpencodeModelRegistry]   CLI output: {}", it) }
                }

                parseAndSortFreeModels(lines)
            }.getOrElse { error ->
                logger.warn("[OpencodeModelRegistry] CLI refresh failed: {}", error.message)
                emptyList()
            }

            val finalModels = discovered.ifEmpty { discoveredModels.get() }
            discoveredModels.set(finalModels)

            val refreshDuration = System.currentTimeMillis() - refreshStart

            val newState = OpencodeModelState(
                defaultModel = finalModels.firstOrNull()?.id ?: "",
                activeModel = requireAllowedFreeModel(System.getenv("OPENCODE_MODEL") ?: System.getenv("LLM_MODEL_ID")),
                models = finalModels,
                source = if (discovered.isEmpty()) "cached" else "cli-discovered",
                updatedAt = System.currentTimeMillis(),
            )
            cachedState.set(newState)

            if (discovered.isEmpty()) {
                logger.warn("[OpencodeModelRegistry] No new models from CLI — keeping {} cached models ({}ms)",
                    finalModels.size, refreshDuration)
            } else {
                logger.info("[OpencodeModelRegistry] {} free models discovered ({}ms)", discovered.size, refreshDuration)
                discovered.forEach { logger.info("[OpencodeModelRegistry]   - {} ({})", it.id, it.label) }
            }
            newState
        }

    /**
     * Blocking refresh for currentState() when cache is stale.
     */
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
                logger.warn("[OpencodeModelRegistry] 'opencode models' exited with code ${process.exitValue()}")
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

    /**
     * Parse CLI output, filter for "free" models, sort alphabetically, take top 3.
     */
    private fun parseAndSortFreeModels(lines: List<String>): List<OpencodeModelInfo> {
        val allModels = mutableListOf<String>()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("─") || line.startsWith("│")) {
                continue
            }

            val modelId = extractModelId(line) ?: continue

            // Filter: must contain "free" (case-insensitive)
            if (!modelId.contains("free", ignoreCase = true)) {
                logger.debug("[OpencodeModelRegistry] Skipping non-free model: {}", modelId)
                continue
            }

            // Must be opencode-scoped
            if (!modelId.startsWith("opencode/")) {
                logger.debug("[OpencodeModelRegistry] Skipping non-opencode model: {}", modelId)
                continue
            }

            allModels.add(modelId)
        }

        // Sort alphabetically and take top 3
        val sorted = allModels.sorted()
        val top3 = sorted.take(MAX_FREE_MODELS)

        logger.info("[OpencodeModelRegistry] Found {} free models, sorted, taking top {}", sorted.size, top3.size)

        return top3.map { modelId ->
            val label = modelId
                .removePrefix("opencode/")
                .replace(Regex("[-.]+"), " ")
                .split(" ")
                .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }

            OpencodeModelInfo(id = modelId, label = label)
        }
    }

    /**
     * Extract model ID from a CLI output line.
     * Handles: plain IDs, table formatting (│ ... │), leading whitespace.
     */
    private fun extractModelId(line: String): String? {
        val stripped = line.replace("│", "").trim()
        val match = Regex("""(opencode/[\w.\-]+)""").find(stripped)
        return match?.groupValues?.get(1)
    }
}
