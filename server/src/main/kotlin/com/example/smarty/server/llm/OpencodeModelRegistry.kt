package com.example.smarty.server.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URL
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

    /**
     * Fallback model ID patterns. We no longer use CLI discovery.
     */
    val KNOWN_FREE_MODELS =
        listOf(
            OpencodeModelInfo(id = "opencode/gemini-2.5-pro-free", label = "Gemini 2.5 Pro Free"),
            OpencodeModelInfo(id = "opencode/deepseek-r1-free", label = "DeepSeek R1 Free"),
            OpencodeModelInfo(id = "opencode/claude-3.5-haiku-free", label = "Claude 3.5 Haiku Free"),
        )

    private const val DAEMON_DECIDE = "opencode/auto"

    val zenApiKey: String?
        get() = System.getenv("OPENCODE_ZEN_API_KEY")?.takeIf { it.isNotBlank() }

    val zenBaseUrl: String
        get() =
            System.getenv("OPENCODE_ZEN_BASE_URL")?.takeIf { it.isNotBlank() }
                ?: "https://opencode.ai/zen/v1"

    val isDirectZenMode: Boolean
        get() = !zenApiKey.isNullOrBlank()

    private val discoveredModels = AtomicReference<List<OpencodeModelInfo>>(KNOWN_FREE_MODELS)
    private val cachedState = AtomicReference<OpencodeModelState?>(null)

    val defaultModel: String
        get() = discoveredModels.get().firstOrNull()?.id ?: DAEMON_DECIDE

    fun discoverAtStartup(timeoutMs: Long = 15_000L) {
        logger.info("[OpencodeModelRegistry] === Static Model Initializer ===")
        val models = KNOWN_FREE_MODELS
        discoveredModels.set(models)
        val state =
            OpencodeModelState(
                defaultModel = models.firstOrNull()?.id ?: "",
                activeModel = models.firstOrNull()?.id ?: "",
                models = models,
                source = "static",
            )
        cachedState.set(state)
        logger.info("[OpencodeModelRegistry] Initialized static Zen models.")
    }

    fun isAllowedFreeModel(model: String?): Boolean {
        val normalized = model?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val discovered = discoveredModels.get()

        if (discovered.any { it.id == normalized }) {
            return true
        }
        if (isDirectZenMode) return true
        return normalized.startsWith("opencode/") && normalized.contains("free", ignoreCase = true)
    }

    fun requireAllowedFreeModel(model: String?): String {
        val discovered = discoveredModels.get()
        val discoveredDefault = discovered.firstOrNull()?.id

        val paramModel = model?.trim()?.takeIf { it.isNotBlank() }
        if (paramModel != null) {
            return if (isAllowedFreeModel(paramModel)) {
                paramModel
            } else {
                val fallback = discoveredDefault ?: DAEMON_DECIDE
                fallback
            }
        }
        return discoveredDefault ?: DAEMON_DECIDE
    }

    fun discoveredFreeModels(): List<OpencodeModelInfo> = discoveredModels.get()

    fun currentState(activeModel: String? = null): OpencodeModelState {
        val state = cachedState.get() ?: runBlockingRefresh()
        return state.copy(
            activeModel = requireAllowedFreeModel(activeModel ?: state.activeModel),
            source = state.source,
        )
    }

    suspend fun refreshFromCli(timeoutMs: Long = 12_000L): OpencodeModelState = runBlockingRefresh()

    private fun runBlockingRefresh(): OpencodeModelState =
        try {
            val url = URL("$zenBaseUrl/models")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            val apiKey = zenApiKey
            if (!apiKey.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(response).jsonObject
            val data = root["data"]?.jsonArray

            val fetchedModels = mutableListOf<OpencodeModelInfo>()
            if (data != null) {
                for (item in data) {
                    val rawId = item.jsonObject["id"]?.jsonPrimitive?.content ?: continue
                    if (isDirectZenMode || rawId.endsWith("-free")) {
                        val prefixedId = if (rawId.startsWith("opencode/")) rawId else "opencode/$rawId"
                        val labelName = rawId.removePrefix("opencode/").replace("-free", "").replace("-", " ")
                        val capitalized =
                            labelName.split(" ").joinToString(" ") {
                                it.replaceFirstChar { c -> c.uppercase() }
                            }
                        val finalLabel = if (rawId.endsWith("-free") && !capitalized.endsWith("Free")) "$capitalized Free" else capitalized
                        fetchedModels.add(OpencodeModelInfo(id = prefixedId, label = finalLabel))
                    }
                }
            }

            val models = if (fetchedModels.isNotEmpty()) fetchedModels else KNOWN_FREE_MODELS
            discoveredModels.set(models)

            val newState =
                OpencodeModelState(
                    defaultModel = models.firstOrNull()?.id ?: "",
                    activeModel = models.firstOrNull()?.id ?: "",
                    models = models,
                    source = "api",
                    updatedAt = System.currentTimeMillis(),
                )
            cachedState.set(newState)
            newState
        } catch (e: Exception) {
            logger.error("[OpencodeModelRegistry] Failed to fetch models from API: ${e.message}", e)
            val models = KNOWN_FREE_MODELS
            discoveredModels.set(models)
            val newState =
                OpencodeModelState(
                    defaultModel = models.firstOrNull()?.id ?: "",
                    activeModel = models.firstOrNull()?.id ?: "",
                    models = models,
                    source = "fallback",
                    updatedAt = System.currentTimeMillis(),
                )
            cachedState.set(newState)
            newState
        }
}
