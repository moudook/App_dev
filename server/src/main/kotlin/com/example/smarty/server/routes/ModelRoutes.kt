package com.example.smarty.server.routes

import com.example.smarty.server.llm.OpencodeModelRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Model management routes for CRUD operations.
 * Allows clients to manage their preferred models and get server model info.
 */
fun Application.configureModelRoutes() {
    val logger = LoggerFactory.getLogger("ModelRoutes")
    
    routing {
        route("/api/v1/models") {
            /**
             * GET /api/v1/models - Get all available models from server
             * Same as /api/v1/opencode/models but with additional metadata
             */
            get {
                val refresh = call.request.queryParameters["refresh"] == "true"
                val state = if (refresh) {
                    OpencodeModelRegistry.refreshFromCli()
                } else {
                    OpencodeModelRegistry.currentState()
                }
                
                logger.info("[ModelRoutes] GET models: {} models available", state.models.size)
                call.respond(state)
            }
            
            /**
             * POST /api/v1/models/validate - Validate if a model is available
             * Body: {"modelId": "opencode/deepseek-v4-flash-free"}
             * Response: {"valid": true, "model": {...}}
             */
            post("/validate") {
                val request = call.receive<ModelValidateRequest>()
                val isValid = OpencodeModelRegistry.isAllowedFreeModel(request.modelId)
                val model = OpencodeModelRegistry.discoveredFreeModels()
                    .find { it.id == request.modelId }
                
                call.respond(ModelValidateResponse(
                    valid = isValid,
                    model = model,
                    availableModels = OpencodeModelRegistry.discoveredFreeModels()
                ))
            }
            
            /**
             * GET /api/v1/models/default - Get the default model
             */
            get("/default") {
                val defaultModel = OpencodeModelRegistry.defaultModel
                call.respond(mapOf(
                    "modelId" to defaultModel,
                    "available" to defaultModel.isNotBlank()
                ))
            }
            
            /**
             * POST /api/v1/models/refresh - Force refresh model list from CLI
             */
            post("/refresh") {
                logger.info("[ModelRoutes] Manual refresh requested")
                val state = OpencodeModelRegistry.refreshFromCli()
                call.respond(state)
            }
        }
    }
}

@Serializable
data class ModelValidateRequest(
    val modelId: String
)

@Serializable
data class ModelValidateResponse(
    val valid: Boolean,
    val model: com.example.smarty.server.llm.OpencodeModelInfo?,
    val availableModels: List<com.example.smarty.server.llm.OpencodeModelInfo>
)
