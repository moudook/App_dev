package com.example.smarty.server.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
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
    val KNOWN_FREE_MODELS = listOf(
        OpencodeModelInfo(id = "opencode/gemini-2.5-pro", label = "Gemini 2.5 Pro"),
        OpencodeModelInfo(id = "opencode/deepseek-r1", label = "DeepSeek R1"),
        OpencodeModelInfo(id = "opencode/claude-3.5-haiku", label = "Claude 3.5 Haiku")
    )

    private const val DAEMON_DECIDE = "opencode/auto"

    val zenApiKey: String?
        get() = System.getenv("OPENCODE_ZEN_API_KEY")?.takeIf { it.isNotBlank() }

    val zenBaseUrl: String
        get() =
            System.getenv("OPENCODE_ZEN_BASE_URL")?.takeIf { it.isNotBlank() }
                ?: "https://gateway.opencode.ai/v1"

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

    suspend fun refreshFromCli(timeoutMs: Long = 12_000L): OpencodeModelState {
        return runBlockingRefresh()
    }

    private fun runBlockingRefresh(): OpencodeModelState {
        val models = KNOWN_FREE_MODELS
        discoveredModels.set(models)
        val newState =
            OpencodeModelState(
                defaultModel = models.firstOrNull()?.id ?: "",
                activeModel = models.firstOrNull()?.id ?: "",
                models = models,
                source = "static",
                updatedAt = System.currentTimeMillis(),
            )
        cachedState.set(newState)
        return newState
    }
}

