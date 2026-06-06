package com.example.smarty.server.llm

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
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
                ?: "https://opencode.ai/zen/v1"

    val isDirectZenMode: Boolean
        get() = !zenApiKey.isNullOrBlank()

    // Runtime-discovered models — ZERO hardcoded names. Everything comes from
    // (1) the opencode CLI's own `opencode models` output, or
    // (2) the Zen API's /models endpoint as a fallback.
    // Filtered dynamically by the "free" pattern.
    private val discoveredModels = AtomicReference<List<OpencodeModelInfo>>(emptyList())
    private val cachedState = AtomicReference<OpencodeModelState?>(null)

    val defaultModel: String
        get() {
            val discovered = discoveredModels.get()
            return discovered.firstOrNull()?.id
                ?: DAEMON_DECIDE
        }

    /**
     * Blocking discovery — runs `opencode models` at startup.
     * If CLI discovery returns 0 free models, falls back to dynamic Zen API fetch.
     * NEVER hardcodes model names.
     */
    fun discoverAtStartup(timeoutMs: Long = 15_000L) {
        val start = System.currentTimeMillis()
        logger.info("[OpencodeModelRegistry] === PHASE 1: Model Discovery ===")
        logger.info("[OpencodeModelRegistry] Running 'opencode models' to discover free models at runtime...")

        val cliModels = runCliDiscovery(timeoutMs)

        val models =
            if (cliModels.isNotEmpty()) {
                logger.info("[OpencodeModelRegistry] CLI discovery returned {} free models", cliModels.size)
                cliModels
            } else {
                logger.warn("[OpencodeModelRegistry] CLI discovery returned 0 free models — falling back to dynamic Zen /models fetch")
                val zenModels = runZenApiDiscovery()
                if (zenModels.isEmpty()) {
                    logger.error("[OpencodeModelRegistry] Zen API discovery also returned 0 — daemon will pick its own default")
                } else {
                    logger.info("[OpencodeModelRegistry] Zen API discovery returned {} free models", zenModels.size)
                }
                zenModels
            }

        discoveredModels.set(models)

        if (models.isEmpty()) {
            logger.error("[OpencodeModelRegistry] ZERO free models discovered from any source")
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
                source = when {
                    cliModels.isNotEmpty() -> "cli-discovered"
                    models.isNotEmpty() -> "zen-api-discovered"
                    else -> "none"
                },
            )
        cachedState.set(state)
        logger.info("[OpencodeModelRegistry] === PHASE 1 COMPLETE ===")
    }

    private fun runCliDiscovery(timeoutMs: Long): List<OpencodeModelInfo> {
        return runCatching {
            val workDir = java.io.File(System.getProperty("user.dir"))
            logger.info("[OpencodeModelRegistry] Working directory: {}", workDir.absolutePath)

            val cliStart = System.currentTimeMillis()
            val process =
                ProcessBuilder(listOf("opencode", "models", "--verbose"))
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .start()

            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val cliDuration = System.currentTimeMillis() - cliStart

            if (!completed) {
                process.destroyForcibly()
                throw IllegalStateException("opencode models --verbose timed out after ${timeoutMs}ms")
            }

            val lines = process.inputStream.bufferedReader().readLines()
            val exitCode = process.exitValue()
            logger.info(
                "[OpencodeModelRegistry] CLI exited in {}ms — exit code: {}, raw lines: {}",
                cliDuration,
                exitCode,
                lines.size,
            )

            if (lines.isEmpty()) {
                logger.error("[OpencodeModelRegistry] CLI returned ZERO lines — discovery will fail")
            } else {
                logger.info("[OpencodeModelRegistry] CLI output (first 30 lines):")
                lines.take(30).forEachIndexed { i, l -> logger.info("  [{}] {}", i, l.take(200)) }
            }

            parseModelsVerbose(lines)
        }.getOrElse { error ->
            logger.error("[OpencodeModelRegistry] CLI discovery failed: {}", error.message)
            emptyList()
        }
    }

    /**
     * Dynamic fallback — calls Zen's GET /models endpoint and filters the response.
     * Returns 0 free models on any error. NEVER uses hardcoded names.
     */
    private fun runZenApiDiscovery(): List<OpencodeModelInfo> {
        return runCatching {
            val baseUrl = zenBaseUrl.trimEnd('/')
            val url = "$baseUrl/models"
            val key = zenApiKey ?: "public"
            logger.info("[OpencodeModelRegistry] Fetching live model list from GET {}", url)

            kotlinx.coroutines.runBlocking {
                val client = HttpClient()
                try {
                    val response = client.get(url) {
                        header("Authorization", "Bearer $key")
                        header("Accept", "application/json")
                    }
                    if (response.status != HttpStatusCode.OK) {
                        logger.error("[OpencodeModelRegistry] Zen /models returned status {}", response.status)
                        return@runBlocking emptyList<OpencodeModelInfo>()
                    }
                    val body = response.bodyAsText()
                    parseZenApiModels(body)
                } finally {
                    client.close()
                }
            }
        }.getOrElse { error ->
            logger.error("[OpencodeModelRegistry] Zen API discovery failed: {}", error.message)
            emptyList()
        }
    }

    /**
     * Parses the response from Zen's GET /models endpoint.
     * Accepts both shapes:
     *   1) { "data": [ {"id":"opencode/...","free":true,...}, ... ] }   (OpenAI shape)
     *   2) { "models": [ {"id":"...","name":"..."}, ... ] }              (Zen native shape)
     * Filters dynamically: model.id starts with "opencode/" AND contains "free" (case-insensitive).
     */
    private fun parseZenApiModels(body: String): List<OpencodeModelInfo> {
        val root =
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject }
                .getOrElse { error ->
                    logger.error("[OpencodeModelRegistry] Failed to parse Zen /models body: {}", error.message)
                    return emptyList()
                }

        val arr =
            root["data"]?.jsonArray
                ?: root["models"]?.jsonArray
                ?: run {
                    logger.error("[OpencodeModelRegistry] Zen /models body has no 'data' or 'models' array")
                    return emptyList()
                }

        val all = mutableListOf<OpencodeModelInfo>()
        for (element in arr) {
            val obj = element.jsonObject
            val rawId = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val normalizedId =
                if (rawId.contains('/')) rawId
                else "opencode/$rawId"
            if (!normalizedId.startsWith("opencode/")) continue
            // Dynamic filter: must contain "free" (Zen rotates these names)
            if (!normalizedId.contains("free", ignoreCase = true)) continue

            val label =
                obj["name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["label"]?.jsonPrimitive?.contentOrNull
                    ?: generateLabel(normalizedId)
            all.add(OpencodeModelInfo(id = normalizedId, label = label))
        }

        val sorted = all.sortedBy { it.id }
        val top = sorted.take(MAX_FREE_MODELS)
        logger.info("[OpencodeModelRegistry] Zen /models returned {} free models (cap {})", sorted.size, MAX_FREE_MODELS)
        return top
    }

    fun isAllowedFreeModel(model: String?): Boolean {
        val normalized = model?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val discovered = discoveredModels.get()

        if (discovered.any { it.id == normalized }) {
            return true
        }

        // Dynamic pattern: any model with "opencode/" prefix AND "free" in name.
        return normalized.startsWith("opencode/") && normalized.contains("free", ignoreCase = true)
    }

    /**
     * Validate and return a free model.
     * Priority: explicit parameter > discovered default > daemon decides.
     * Never falls back to a hardcoded model name.
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

        if (state == null) {
            logger.info("[OpencodeModelRegistry] Cache completely null — forcing blocking refresh")
            return runBlockingRefresh()
        }

        val isStale = (now - state.updatedAt) > CACHE_TTL_MS
        if (isStale) {
            logger.debug("[OpencodeModelRegistry] Cache stale — will refresh on next explicit request")
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

            val cliDiscovered = runCliDiscovery(timeoutMs)
            val discovered =
                if (cliDiscovered.isNotEmpty()) cliDiscovered
                else {
                    logger.warn("[OpencodeModelRegistry] CLI refresh returned 0 — trying Zen /models fallback")
                    runZenApiDiscovery()
                }

            val finalModels = discovered.ifEmpty { discoveredModels.get() }
            discoveredModels.set(finalModels)

            val newState =
                OpencodeModelState(
                    defaultModel = finalModels.firstOrNull()?.id ?: "",
                    activeModel = requireAllowedFreeModel(null),
                    models = finalModels,
                    source = when {
                        cliDiscovered.isNotEmpty() -> "cli-discovered"
                        discovered.isNotEmpty() -> "zen-api-discovered"
                        else -> "cached"
                    },
                    updatedAt = System.currentTimeMillis(),
                )
            cachedState.set(newState)

            logger.info(
                "[OpencodeModelRegistry] Refresh complete in {}ms — {} models (source: {})",
                System.currentTimeMillis() - refreshStart,
                finalModels.size,
                newState.source,
            )
            newState
        }

    private fun runBlockingRefresh(): OpencodeModelState {
        val start = System.currentTimeMillis()
        val discovered = runCliDiscovery(12_000L)
        val final = discovered.ifEmpty { discoveredModels.get() }
        discoveredModels.set(final)

        val newState =
            OpencodeModelState(
                defaultModel = final.firstOrNull()?.id ?: "",
                activeModel = final.firstOrNull()?.id ?: "",
                models = final,
                source = if (discovered.isEmpty()) "cached" else "cli-discovered",
                updatedAt = System.currentTimeMillis(),
            )
        cachedState.set(newState)
        logger.info(
            "[OpencodeModelRegistry] Blocking refresh complete in {}ms — {} models",
            System.currentTimeMillis() - start,
            final.size,
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

            if (jsonBuffer != null) {
                jsonBuffer.appendLine(line)
                braceDepth += line.count { it == '{' } - line.count { it == '}' }

                if (braceDepth <= 0) {
                    val jsonText = jsonBuffer.toString().trim()
                    jsonBuffer = null
                    braceDepth = 0

                    val id = pendingId ?: continue
                    pendingId = null

                    if (!isAllowedFreeModel(id)) continue

                    val jsonMeta =
                        try {
                            kotlinx.serialization.json.Json
                                .parseToJsonElement(jsonText)
                                .jsonObject
                        } catch (e: Exception) {
                            logger.warn("[OpencodeModelRegistry] Failed to parse JSON for model {}: {}", id, e.message)
                            continue
                        }

                    val label =
                        jsonMeta["label"]?.jsonPrimitive?.contentOrNull
                            ?: generateLabel(id)

                    val rawVariants = jsonMeta["variants"]?.jsonObject
                    val variants = rawVariants ?: emptyMap()

                    allModels.add(OpencodeModelInfo(id = id, label = label, variants = variants))
                }
                continue
            }

            val modelId = Regex("""^(opencode/[\w.\-]+)$""").find(line)?.groupValues?.get(1)
            if (modelId != null) {
                pendingId = modelId
                continue
            }

            if (line.startsWith("{")) {
                val id = pendingId ?: continue

                val openBraces = line.count { it == '{' }
                val closeBraces = line.count { it == '}' }
                braceDepth = openBraces - closeBraces

                if (braceDepth <= 0) {
                    pendingId = null
                    if (!isAllowedFreeModel(id)) continue

                    val jsonMeta =
                        try {
                            kotlinx.serialization.json.Json
                                .parseToJsonElement(line)
                                .jsonObject
                        } catch (e: Exception) {
                            logger.warn("[OpencodeModelRegistry] Failed to parse JSON for model {}: {}", id, e.message)
                            continue
                        }

                    val label =
                        jsonMeta["label"]?.jsonPrimitive?.contentOrNull
                            ?: generateLabel(id)

                    val rawVariants = jsonMeta["variants"]?.jsonObject
                    val variants = rawVariants ?: emptyMap()

                    allModels.add(OpencodeModelInfo(id = id, label = label, variants = variants))
                } else {
                    jsonBuffer = StringBuilder()
                    jsonBuffer.appendLine(line)
                }
            }
        }

        val sorted = allModels.sortedBy { it.id }
        val top = sorted.take(MAX_FREE_MODELS)

        logger.info("[OpencodeModelRegistry] Parsed {} total free models from CLI, taking top {}", sorted.size, top.size)
        top.forEach { m ->
            logger.info("[OpencodeModelRegistry]   - {} ({}) variants: {}", m.id, m.label, m.variants.keys)
        }
        return top
    }

    private fun generateLabel(modelId: String): String =
        modelId
            .removePrefix("opencode/")
            .replace(Regex("(?i)-free\\b"), "")
            .replace(Regex("(?i)\\bfree\\b"), "")
            .replace(Regex("[-.]+"), " ")
            .split(" ")
            .filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
}
