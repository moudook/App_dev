package com.example.smarty.server.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    val variants: Map<String, JsonElement> = emptyMap(),
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
    private const val MAX_FREE_MODELS = 10

    /**
     * Fallback model ID patterns — used ONLY if CLI discovery fails entirely.
     * These are NOT guaranteed to exist. The real source of truth is `opencode models`.
     * When Zen changes their free models, this list becomes irrelevant — discovery handles it.
     *
     * The pattern rule: if a model ID contains "free" and starts with "opencode/", it's treated as free.
     * We do NOT hardcode specific model names here by design — they rotate.
     */
    val KNOWN_FREE_MODELS = listOf<OpencodeModelInfo>() // Intentionally empty: rely on CLI discovery

    /**
     * Sentinel string returned when no models discovered yet.
     * The daemon will use its own default model (whatever is currently free on Zen).
     */
    private const val DAEMON_DECIDE = "opencode/auto"

    /**
     * Zen API key for direct calls to gateway.opencode.ai.
     * When set, Ktor can bypass the CLI subprocess entirely.
     * Must be set in Hugging Face secrets as: OPENCODE_ZEN_API_KEY
     */
    val zenApiKey: String?
        get() = System.getenv("OPENCODE_ZEN_API_KEY")?.takeIf { it.isNotBlank() }

    val zenBaseUrl: String
        get() =
            System.getenv("OPENCODE_ZEN_BASE_URL")?.takeIf { it.isNotBlank() }
                ?: "https://gateway.opencode.ai/v1"

    val isDirectZenMode: Boolean
        get() = !zenApiKey.isNullOrBlank()

    // Runtime-discovered models — zero hardcoded names, fallback to KNOWN_FREE_MODELS
    private val discoveredModels = AtomicReference<List<OpencodeModelInfo>>(emptyList())
    private val cachedState = AtomicReference<OpencodeModelState?>(null)

    val defaultModel: String
        get() {
            val discovered = discoveredModels.get()
            // Always prefer what the CLI just told us, never hardcode a name
            return discovered.firstOrNull()?.id
                ?: DAEMON_DECIDE // Let the daemon pick its current default free model
        }

    /**
     * Blocking discovery — runs `opencode models` at startup.
     * Filters for "free" in model ID, sorts alphabetically, takes top 3.
     */
    fun discoverAtStartup(timeoutMs: Long = 15_000L) {
        val start = System.currentTimeMillis()
        logger.info("[OpencodeModelRegistry] === PHASE 1: Model Discovery ===")
        logger.info("[OpencodeModelRegistry] Running 'opencode models' to discover free models at runtime...")

        val models =
            runCatching {
                val workDir = java.io.File(System.getProperty("user.dir"))
                logger.info("[OpencodeModelRegistry] Working directory: {}", workDir.absolutePath)

                val cliStart = System.currentTimeMillis()
                val process =
                    ProcessBuilder(listOf("opencode", "models", "--verbose"))
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
                    throw IllegalStateException("opencode models --verbose timed out after ${timeoutMs}ms")
                }

                val exitCode = process.exitValue()
                logger.info(
                    "[OpencodeModelRegistry] CLI exited in {}ms — exit code: {}, raw lines: {}",
                    cliDuration,
                    exitCode,
                    lines.size,
                )

                if (exitCode != 0) {
                    logger.warn("[OpencodeModelRegistry] CLI returned non-zero exit code")
                    lines.forEach { logger.debug("[OpencodeModelRegistry]   RAW: {}", it) }
                } else {
                    lines.forEach { logger.debug("[OpencodeModelRegistry]   RAW: {}", it) }
                }

                parseModelsVerbose(lines)
            }.getOrElse { error ->
                logger.error("[OpencodeModelRegistry] Discovery failed: {}", error.message)
                emptyList()
            }

        discoveredModels.set(models)

        if (models.isEmpty()) {
            logger.error("[OpencodeModelRegistry] CRITICAL: ZERO free models discovered! Chat will fail.")
        } else {
            logger.info(
                "[OpencodeModelRegistry] Discovery complete in {}ms — {} free models:",
                System.currentTimeMillis() - start,
                models.size,
            )
            models.forEachIndexed { i, m ->
                logger.info("[OpencodeModelRegistry]   [${i + 1}] {} ({})", m.id, m.label)
            }
            logger.info("[OpencodeModelRegistry] Default model (first discovered): {}", models.first().id)
        }

        val state =
            OpencodeModelState(
                defaultModel = models.firstOrNull()?.id ?: "",
                activeModel = models.firstOrNull()?.id ?: "",
                models = models,
                source = if (models.isEmpty()) "none" else "cli-discovered",
            )
        cachedState.set(state)
        logger.info("[OpencodeModelRegistry] === PHASE 1 COMPLETE ===")
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
     * Priority: explicit parameter > discovered default > daemon decides.
     * Never falls back to a hardcoded model name — if CLI hasn't run yet, let daemon decide.
     */
    fun requireAllowedFreeModel(model: String?): String {
        val discovered = discoveredModels.get()
        val discoveredDefault = discovered.firstOrNull()?.id

        val paramModel = model?.trim()?.takeIf { it.isNotBlank() }
        if (paramModel != null) {
            return if (isAllowedFreeModel(paramModel)) {
                logger.debug("[OpencodeModelRegistry] Model from parameter: {}", paramModel)
                paramModel
            } else {
                val fallback = discoveredDefault ?: DAEMON_DECIDE
                logger.warn("[OpencodeModelRegistry] Parameter model '{}' rejected (not free) — using: {}", paramModel, fallback)
                fallback
            }
        }

        val result = discoveredDefault ?: DAEMON_DECIDE
        logger.debug("[OpencodeModelRegistry] Using model: {}", result)
        return result
    }

    fun discoveredFreeModels(): List<OpencodeModelInfo> = discoveredModels.get()

    fun currentState(activeModel: String? = null): OpencodeModelState {
        val state = cachedState.get()
        val now = System.currentTimeMillis()

        // If completely null (rare), we must block
        if (state == null) {
            logger.info("[OpencodeModelRegistry] Cache completely null — forcing blocking refresh")
            return runBlockingRefresh()
        }

        val isStale = (now - state.updatedAt) > CACHE_TTL_MS

        if (isStale) {
            logger.info("[OpencodeModelRegistry] Cache stale — triggering background refresh to avoid blocking agent")
            // Update the timestamp right away so we don't spawn multiple coroutines
            cachedState.set(state.copy(updatedAt = now))

            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    refreshFromCli()
                } catch (e: Exception) {
                    logger.error("[OpencodeModelRegistry] Background refresh failed", e)
                }
            }
        }

        return state.copy(
            activeModel = requireAllowedFreeModel(activeModel ?: state.activeModel),
            source = state.source,
        )
    }

    suspend fun refreshFromCli(timeoutMs: Long = 12_000L): OpencodeModelState =
        withContext(Dispatchers.IO) {
            val refreshStart = System.currentTimeMillis()
            logger.info("[OpencodeModelRegistry] === PHASE: Runtime Model Refresh ===")

            val discovered =
                runCatching {
                    val workDir = java.io.File(System.getProperty("user.dir"))
                    val cliStart = System.currentTimeMillis()

                    val process =
                        ProcessBuilder(listOf("opencode", "models", "--verbose"))
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
                        throw IllegalStateException("opencode models --verbose timed out")
                    }

                    val exitCode = process.exitValue()
                    logger.info(
                        "[OpencodeModelRegistry] CLI exited in {}ms — code: {}, lines: {}",
                        cliDuration,
                        exitCode,
                        lines.size,
                    )

                    if (exitCode != 0) {
                        lines.forEach { logger.debug("[OpencodeModelRegistry]   RAW: {}", it) }
                    }

                    parseModelsVerbose(lines)
                }.getOrElse { error ->
                    logger.warn("[OpencodeModelRegistry] Refresh failed: {}", error.message)
                    emptyList()
                }

            val finalModels = discovered.ifEmpty { discoveredModels.get() }
            discoveredModels.set(finalModels)

            val newState =
                OpencodeModelState(
                    defaultModel = finalModels.firstOrNull()?.id ?: "",
                    activeModel = requireAllowedFreeModel(null),
                    models = finalModels,
                    source = if (discovered.isEmpty()) "cached" else "cli-discovered",
                    updatedAt = System.currentTimeMillis(),
                )
            cachedState.set(newState)

            logger.info(
                "[OpencodeModelRegistry] Refresh complete in {}ms — {} models (source: {})",
                System.currentTimeMillis() - refreshStart,
                finalModels.size,
                newState.source,
            )
            finalModels.forEach { logger.info("[OpencodeModelRegistry]   - {} ({})", it.id, it.label) }
            logger.info("[OpencodeModelRegistry] === PHASE COMPLETE ===")
            newState
        }

    private fun runBlockingRefresh(): OpencodeModelState {
        val start = System.currentTimeMillis()
        val discovered =
            runCatching {
                val workDir = java.io.File(System.getProperty("user.dir"))
                val process =
                    ProcessBuilder(listOf("opencode", "models", "--verbose"))
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
                    throw IllegalStateException("opencode models --verbose timed out")
                }

                if (process.exitValue() != 0) {
                    logger.warn("[OpencodeModelRegistry] CLI exited with code ${process.exitValue()}")
                }

                parseModelsVerbose(lines)
            }.getOrElse { error ->
                logger.warn("[OpencodeModelRegistry] Blocking refresh failed: {}", error.message)
                emptyList()
            }

        val finalModels = discovered.ifEmpty { discoveredModels.get() }
        discoveredModels.set(finalModels)

        val newState =
            OpencodeModelState(
                defaultModel = finalModels.firstOrNull()?.id ?: "",
                activeModel = finalModels.firstOrNull()?.id ?: "",
                models = finalModels,
                source = if (discovered.isEmpty()) "cached" else "cli-discovered",
                updatedAt = System.currentTimeMillis(),
            )
        cachedState.set(newState)

        logger.info(
            "[OpencodeModelRegistry] Blocking refresh complete in {}ms — {} models",
            System.currentTimeMillis() - start,
            finalModels.size,
        )
        return newState
    }

    private fun parseModelsVerbose(lines: List<String>): List<OpencodeModelInfo> {
        val allModels = mutableListOf<OpencodeModelInfo>()
        var pendingId: String? = null
        var jsonBuffer: StringBuilder? = null
        var braceDepth = 0

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // If we're accumulating a multi-line JSON object
            if (jsonBuffer != null) {
                jsonBuffer.appendLine(line)
                braceDepth += line.count { it == '{' } - line.count { it == '}' }

                if (braceDepth <= 0) {
                    // Complete JSON object accumulated — parse it
                    val jsonText = jsonBuffer.toString().trim()
                    jsonBuffer = null
                    braceDepth = 0

                    val id = pendingId ?: continue
                    pendingId = null

                    if (!id.contains("free", ignoreCase = true)) {
                        logger.debug("[OpencodeModelRegistry] Skipping non-free: {}", id)
                        continue
                    }
                    if (!id.startsWith("opencode/")) {
                        logger.debug("[OpencodeModelRegistry] Skipping non-opencode: {}", id)
                        continue
                    }

                    val jsonMeta = try {
                        kotlinx.serialization.json.Json.parseToJsonElement(jsonText).jsonObject
                    } catch (e: Exception) {
                        logger.warn("[OpencodeModelRegistry] Failed to parse JSON for model {}: {}", id, e.message)
                        continue
                    }

                    val label = jsonMeta["label"]?.jsonPrimitive?.contentOrNull
                        ?: generateLabel(id)

                    val rawVariants = jsonMeta["variants"]?.jsonObject
                    val variants = rawVariants ?: emptyMap()

                    allModels.add(OpencodeModelInfo(
                        id = id,
                        label = label,
                        variants = variants,
                    ))
                }
                continue
            }

            // If line is a model ID (not JSON), store it as pending
            val modelId = Regex("""^(opencode/[\w.\-]+)$""").find(line)?.groupValues?.get(1)
            if (modelId != null) {
                pendingId = modelId
                continue
            }

            // If line is JSON and we have a pending model ID, start accumulating
            if (line.startsWith("{")) {
                val id = pendingId ?: continue

                val openBraces = line.count { it == '{' }
                val closeBraces = line.count { it == '}' }
                braceDepth = openBraces - closeBraces

                if (braceDepth <= 0) {
                    // Single-line JSON — parse immediately
                    pendingId = null

                    if (!id.contains("free", ignoreCase = true)) {
                        logger.debug("[OpencodeModelRegistry] Skipping non-free: {}", id)
                        continue
                    }
                    if (!id.startsWith("opencode/")) {
                        logger.debug("[OpencodeModelRegistry] Skipping non-opencode: {}", id)
                        continue
                    }

                    val jsonMeta = try {
                        kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject
                    } catch (e: Exception) {
                        logger.warn("[OpencodeModelRegistry] Failed to parse JSON for model {}: {}", id, e.message)
                        continue
                    }

                    val label = jsonMeta["label"]?.jsonPrimitive?.contentOrNull
                        ?: generateLabel(id)

                    val rawVariants = jsonMeta["variants"]?.jsonObject
                    val variants = rawVariants ?: emptyMap()

                    allModels.add(OpencodeModelInfo(
                        id = id,
                        label = label,
                        variants = variants,
                    ))
                } else {
                    // Multi-line JSON — start accumulating
                    jsonBuffer = StringBuilder()
                    jsonBuffer.appendLine(line)
                }
            }
        }

        val sorted = allModels.sortedBy { it.id }
        val top3 = sorted.take(MAX_FREE_MODELS)

        logger.info("[OpencodeModelRegistry] Parsed {} total free models from CLI, taking top {}", sorted.size, top3.size)
        top3.forEach { m ->
            logger.info("[OpencodeModelRegistry]   - {} ({}) variants: {}", m.id, m.label, m.variants.keys)
        }

        return top3
    }

    private fun generateLabel(modelId: String): String {
        return modelId
            .removePrefix("opencode/")
            .replace(Regex("(?i)-free\\b"), "")
            .replace(Regex("(?i)\\bfree\\b"), "")
            .replace(Regex("[-.]+"), " ")
            .split(" ")
            .filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
    }
}
