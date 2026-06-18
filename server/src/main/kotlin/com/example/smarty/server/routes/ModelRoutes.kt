package com.example.smarty.server.routes

import com.example.smarty.server.HttpClientSingleton
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

@Serializable
data class ModelInfo(
    val id: String,
    val label: String,
    val provider: String,
)

val HARDCODED_MODELS = listOf(
    ModelInfo("openrouter/auto", "OpenRouter Auto", "openrouter"),
)

@Serializable
data class ModelState(
    val default: String,
    val models: List<ModelInfo>,
    val updatedAt: Long,
)

object OpenRouterModelCache {
    private val mutex = Mutex()
    var cachedModels: List<ModelInfo> = HARDCODED_MODELS
        private set
    var lastFetchTime: Long = 0L
        private set
    private val cacheDurationMs = 1000 * 60 * 60 // 1 hour

    suspend fun getModels(forceRefresh: Boolean = false): List<ModelInfo> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && now - lastFetchTime < cacheDurationMs && cachedModels.size > 1) {
            return cachedModels
        }
        
        mutex.withLock {
            if (!forceRefresh && now - lastFetchTime < cacheDurationMs && cachedModels.size > 1) {
                return cachedModels
            }
            
            try {
                val response = HttpClientSingleton.client.get("https://openrouter.ai/api/v1/models")
                val jsonBody = response.bodyAsText()
                val jsonElement = Json { ignoreUnknownKeys = true }.parseToJsonElement(jsonBody)
                val dataArray = jsonElement.jsonObject["data"]?.jsonArray
                
                val fetched = mutableListOf<ModelInfo>()
                fetched.add(ModelInfo("openrouter/auto", "OpenRouter Auto", "openrouter"))
                
                dataArray?.forEach { item ->
                    val obj = item.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content ?: ""
                    val name = obj["name"]?.jsonPrimitive?.content ?: id
                    if (id.isNotBlank()) {
                        fetched.add(ModelInfo(id, name, "openrouter"))
                    }
                }
                
                if (fetched.size > 1) {
                    cachedModels = fetched
                    lastFetchTime = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                LoggerFactory.getLogger("OpenRouterModelCache").error("Failed to fetch models from OpenRouter", e)
            }
        }
        return cachedModels
    }
}

fun Application.configureModelRoutes() {
    val logger = LoggerFactory.getLogger("ModelRoutes")

    routing {
        route("/api/v1/models") {
            get {
                val refresh = call.request.queryParameters["refresh"] == "true"
                val models = OpenRouterModelCache.getModels(refresh)
                val state = ModelState(default = "openrouter/auto", models = models, updatedAt = OpenRouterModelCache.lastFetchTime)
                logger.info("[ModelRoutes] GET models: {} models available", state.models.size)
                call.respond(state)
            }

            post("/validate") {
                val request = call.receive<ModelValidateRequest>()
                val models = OpenRouterModelCache.getModels()
                val isValid = models.any { it.id == request.modelId }
                call.respond(
                    mapOf(
                        "valid" to isValid,
                        "modelId" to request.modelId,
                    ),
                )
            }

            get("/default") {
                call.respond(mapOf("modelId" to "openrouter/auto", "available" to true))
            }

            post("/refresh") {
                logger.info("[ModelRoutes] Manual refresh requested")
                val models = OpenRouterModelCache.getModels(forceRefresh = true)
                call.respond(ModelState(default = "openrouter/auto", models = models, updatedAt = System.currentTimeMillis()))
            }
        }
    }
}

@Serializable
data class ModelValidateRequest(
    val modelId: String,
    val variant: String? = null,
)
