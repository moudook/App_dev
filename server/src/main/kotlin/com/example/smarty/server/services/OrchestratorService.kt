package com.example.smarty.server.services

import com.example.smarty.server.llm.ProviderRouter
import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.RoutingStrategy
import com.example.smarty.server.llm.Capability
import com.example.smarty.protocol.AgentEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Orchestrator Service ("The Brain").
 * Decides whether to use Vision, Generation, or Standard Chat based on user input.
 */
class OrchestratorService(
    private val providerRouter: ProviderRouter,
    private val visionService: VisionService
) {
    private val logger = LoggerFactory.getLogger(OrchestratorService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    enum class ActionType {
        REPLY,              // Standard text response
        UNDERSTAND_IMAGE,   // Analyze attached image
        GENERATE_IMAGE,     // Generate new image
        EDIT_IMAGE          // Edit existing image
    }

    @Serializable
    data class Decision(
        val action: ActionType,
        val reasoning: String
    )

    /**
     * Analyze request and route to appropriate service.
     */
    suspend fun processRequest(
        query: String,
        attachments: List<ByteArray> = emptyList(),
        eventEmitter: suspend (AgentEvent) -> Unit
    ): String {
        logger.info("Orchestrator processing request: $query (Attachments: ${attachments.size})")

        // 1. Decision Step
        // If attachments exist, default to UNDERSTAND_IMAGE unless explicitly asked to edit.
        // If no attachments, check if user wants to generate an image.

        val action = decideAction(query, attachments.isNotEmpty())
        logger.info("Orchestrator decision: ${action.action} (${action.reasoning})")

        return when (action.action) {
            ActionType.UNDERSTAND_IMAGE -> {
                if (attachments.isEmpty()) {
                    "I need an image to analyze. Please upload one."
                } else {
                    emitProcessing(eventEmitter, "Analyzing image...")
                    // Analyze first image for now
                    val base64Image = java.util.Base64.getEncoder().encodeToString(attachments.first())
                    val analysisResult = visionService.analyzeImage(base64Image, "image/png", query)
                    // Feed description back into chat context (this is returned to the caller to handle in chat flow)
                    analysisResult.description
                }
            }
            ActionType.GENERATE_IMAGE -> {
                emitProcessing(eventEmitter, "Generating image...")
                // Placeholder for Image Generation Service
                // val imageUrl = imageGenService.generate(query)
                // "Here is your image: $imageUrl"
                "Image generation is coming soon! (Capability: ${providerRouter.getModelForCapability(Capability.IMAGE_GENERATION)})"
            }
            ActionType.EDIT_IMAGE -> {
                "Image editing is coming soon!"
            }
            ActionType.REPLY -> {
                // Return null or special signal to let the standard ChatAgent handle it
                // For now, we return a string that the caller (ChatRoutes) will interpret.
                // Actually, Orchestrator might just return the "intent" or "context"
                // and let ServerAgent do the final response.

                // In this architecture, Orchestrator wraps the capabilities.
                // If it's a simple reply, we pass through.
                "CONTINUE_CHAT"
            }
        }
    }

    private suspend fun decideAction(query: String, hasAttachments: Boolean): Decision {
        if (hasAttachments) {
            return Decision(ActionType.UNDERSTAND_IMAGE, "User uploaded an image.")
        }

        // Simple keyword heuristic for speed (Flash model could be used here too)
        val lowerQuery = query.lowercase()
        if (lowerQuery.contains("generate image") || lowerQuery.contains("create an image") || lowerQuery.contains("draw")) {
            return Decision(ActionType.GENERATE_IMAGE, "User requested image generation.")
        }

        return Decision(ActionType.REPLY, "Standard text chat.")
    }

    private suspend fun emitProcessing(emitter: suspend (AgentEvent) -> Unit, message: String) {
        emitter(AgentEvent.Processing(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            content = message
        ))
    }
}
