package com.example.smarty.server.agent2

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class ModelInfo(
    val id: String,
    val contextLength: Int,
)

interface ModelContextWindowProvider {
    suspend fun getContextWindow(modelId: String): Int
    suspend fun getAllModels(): List<ModelInfo>
}

class OpenRouterModelProvider(
    private val apiKey: String = System.getenv("OPENCODE_API_KEY") ?: "",
    private val baseUrl: String = "https://openrouter.ai/api/v1",
) : ModelContextWindowProvider {
    private val logger = LoggerFactory.getLogger(OpenRouterModelProvider::class.java)
    private val cache = ConcurrentHashMap<String, CachedModelInfo>()
    private var allModelsCache: List<ModelInfo>? = null
    private var lastFetchTime = 0L
    private val cacheTtlMs = 5 * 60 * 1000L

    private data class CachedModelInfo(
        val contextLength: Int,
        val fetchedAt: Long,
    )

    companion object {
        private const val DEFAULT_CONTEXT_WINDOW = 128_000
    }

    override suspend fun getContextWindow(modelId: String): Int {
        val stripped = modelId.removeSuffix(":free")
        cache[stripped]?.let {
            if (System.currentTimeMillis() - it.fetchedAt < cacheTtlMs) {
                return it.contextLength
            }
        }
        refreshModels()
        cache[stripped]?.let {
            return it.contextLength
        }
        return DEFAULT_CONTEXT_WINDOW
    }

    override suspend fun getAllModels(): List<ModelInfo> {
        if (allModelsCache != null && System.currentTimeMillis() - lastFetchTime < cacheTtlMs) {
            return allModelsCache!!
        }
        return refreshModels()
    }

    private suspend fun refreshModels(): List<ModelInfo> {
        return try {
            val url = URL("$baseUrl/models")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(response).jsonObject
            val data = root["data"]?.jsonArray ?: return allModelsCache ?: emptyList()

            val models = mutableListOf<ModelInfo>()
            for (item in data) {
                val obj = item.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: continue
                val ctxLength = obj["context_length"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_CONTEXT_WINDOW
                models.add(ModelInfo(id = id, contextLength = ctxLength))
                cache[id] = CachedModelInfo(contextLength = ctxLength, fetchedAt = System.currentTimeMillis())
            }
            allModelsCache = models
            lastFetchTime = System.currentTimeMillis()
            logger.info("[OpenRouterModelProvider] Discovered ${models.size} models from $baseUrl/models")
            models
        } catch (e: Exception) {
            logger.error("[OpenRouterModelProvider] Failed to fetch models: ${e.message}")
            allModelsCache ?: emptyList()
        }
    }
}
