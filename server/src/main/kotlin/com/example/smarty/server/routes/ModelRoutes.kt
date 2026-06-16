package com.example.smarty.server.routes

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

data class ModelInfo(
    val id: String,
    val label: String,
    val provider: String,
)

val HARDCODED_MODELS = listOf(
    ModelInfo("openrouter/auto", "Auto (OpenRouter)", "openrouter"),
    ModelInfo("google/gemini-2.5-pro-exp-03-25:free", "Gemini 2.5 Pro (free)", "google"),
    ModelInfo("deepseek/deepseek-chat-v3-0324:free", "DeepSeek V3 (free)", "deepseek"),
    ModelInfo("meta-llama/llama-4-scout:free", "Llama 4 Scout (free)", "meta"),
    ModelInfo("mistralai/mistral-small-3.1-24b-instruct:free", "Mistral Small 3.1 (free)", "mistral"),
)

data class ModelState(
    val default: String = "openrouter/auto",
    val models: List<ModelInfo> = HARDCODED_MODELS,
    val updatedAt: Long = 0,
)

fun Application.configureModelRoutes() {
    val logger = LoggerFactory.getLogger("ModelRoutes")

    routing {
        route("/api/v1/models") {
            get {
                val state = ModelState()
                logger.info("[ModelRoutes] GET models: {} models available", state.models.size)
                call.respond(state)
            }

            post("/validate") {
                val request = call.receive<ModelValidateRequest>()
                val isValid = HARDCODED_MODELS.any { it.id == request.modelId }
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
                call.respond(ModelState())
            }
        }
    }
}

@Serializable
data class ModelValidateRequest(
    val modelId: String,
    val variant: String? = null,
)
