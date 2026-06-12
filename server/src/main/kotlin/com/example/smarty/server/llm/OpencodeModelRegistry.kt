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
    val KNOWN_MODELS =
        listOf(
            OpencodeModelInfo(id = "openrouter/owl-alpha", label = "Owl Alpha"),
        )

    private const val DAEMON_DECIDE = "openrouter/owl-alpha"

    val zenApiKey: String?
        get() = System.getenv("OPENCODE_API_KEY")?.takeIf { it.isNotBlank() }

    val zenBaseUrl: String
        get() =
            System.getenv("OPENCODE_ZEN_BASE_URL")?.takeIf { it.isNotBlank() }
                ?: "https://openrouter.ai/api/v1"

    val isDirectZenMode: Boolean
        get() = !zenApiKey.isNullOrBlank()

    private val discoveredModels = AtomicReference<List<OpencodeModelInfo>>(KNOWN_MODELS)
    private val cachedState = AtomicReference<OpencodeModelState?>(null)

    val defaultModel: String
        get() = discoveredModels.get().firstOrNull()?.id ?: DAEMON_DECIDE

    fun discoverAtStartup(timeoutMs: Long = 15_000L) {
        logger.info("[OpencodeModelRegistry] === Dynamic Model Initializer ===")
        try {
            runBlockingRefresh()
            logger.info("[OpencodeModelRegistry] Initialized Zen models dynamically from API.")
        } catch (e: Exception) {
            logger.error("[OpencodeModelRegistry] Failed to initialize dynamic models, using fallback.", e)
            val models = KNOWN_MODELS
            discoveredModels.set(models)
            val state =
                OpencodeModelState(
                    defaultModel = models.firstOrNull()?.id ?: "",
                    activeModel = models.firstOrNull()?.id ?: "",
                    models = models,
                    source = "static",
                )
            cachedState.set(state)
        }
    }

    fun isAllowedFreeModel(model: String?): Boolean {
        return true
    }

    fun requireAllowedFreeModel(model: String?): String {
        return "openrouter/owl-alpha"
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
                    val prefixedId = if (rawId.startsWith("opencode/")) rawId else "opencode/$rawId"
                    val labelName = rawId.removePrefix("opencode/").replace("-", " ")
                    val capitalized = labelName.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                    fetchedModels.add(OpencodeModelInfo(id = prefixedId, label = capitalized))
                }
            }

            val models =
                if (fetchedModels.isNotEmpty()) {
                    fetchedModels
                } else {
                    listOf(
                        OpencodeModelInfo("openrouter/owl-alpha", "Owl Alpha"),
                    )
                }
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
            val models = listOf(OpencodeModelInfo("openrouter/owl-alpha", "Owl Alpha"))
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
