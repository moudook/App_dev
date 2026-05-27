package com.example.smarty.server.services

import com.example.smarty.protocol.AgentEvent
import com.example.smarty.server.tools.KreaImageTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Orchestrator Service ("The Brain").
 * Decides whether to use Vision, Generation, or Standard Chat based on user input.
 */
class OrchestratorService(
    private val visionService: VisionService,
    private val kreaImageTool: KreaImageTool,
) {
    private val logger = LoggerFactory.getLogger(OrchestratorService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    enum class ActionType {
        REPLY, // Standard text response
        UNDERSTAND_IMAGE, // Analyze attached image
        GENERATE_IMAGE, // Generate new image
        EDIT_IMAGE, // Edit existing image
    }

    @Serializable
    data class Decision(
        val action: ActionType,
        val reasoning: String,
    )

    /**
     * Analyze request and route to appropriate service.
     */
    suspend fun processRequest(
        query: String,
        attachments: List<ByteArray> = emptyList(),
        eventEmitter: suspend (AgentEvent) -> Unit,
    ): String {
        logger.info("Orchestrator processing request: $query (Attachments: ${attachments.size})")

        // 1. Decision Step
        val action = decideAction(query, attachments.isNotEmpty())
        logger.info("Orchestrator decision: ${action.action} (${action.reasoning})")

        return when (action.action) {
            ActionType.UNDERSTAND_IMAGE -> {
                if (attachments.isEmpty()) {
                    "I need an image to analyze. Please upload one."
                } else {
                    emitProcessing(eventEmitter, "Analyzing image...")
                    val base64Image =
                        java.util.Base64
                            .getEncoder()
                            .encodeToString(attachments.first())
                    val analysisResult = visionService.analyzeImage(base64Image, "image/png", query)
                    analysisResult.description
                }
            }
            ActionType.GENERATE_IMAGE -> {
                emitProcessing(eventEmitter, "Generating image...")
                try {
                    val aspectRatio = extractAspectRatio(query)
                    val jobId =
                        kreaImageTool.generateImage(
                            prompt = enhancePrompt(query),
                            aspectRatio = aspectRatio,
                        )
                    "IMAGE_GENERATION_STARTED:$jobId"
                } catch (e: Exception) {
                    logger.error("Image generation failed", e)
                    "Failed to generate image: ${e.message}"
                }
            }
            ActionType.EDIT_IMAGE -> {
                "Image editing will be available soon! For now, you can request new image generation."
            }
            ActionType.REPLY -> {
                "CONTINUE_CHAT"
            }
        }
    }

    /**
     * Decide action based on query and attachments (public for routes)
     */
    fun decideAction(
        query: String,
        hasAttachments: Boolean,
    ): Decision {
        if (hasAttachments) {
            return Decision(ActionType.UNDERSTAND_IMAGE, "User uploaded an image.")
        }

        val lowerQuery = query.lowercase()
        if (lowerQuery.contains("generate image") ||
            lowerQuery.contains("create an image") ||
            lowerQuery.contains("draw") ||
            lowerQuery.contains("create image") ||
            lowerQuery.contains("make an image") ||
            lowerQuery.contains("image of") ||
            lowerQuery.contains("picture of")
        ) {
            return Decision(ActionType.GENERATE_IMAGE, "User requested image generation.")
        }

        return Decision(ActionType.REPLY, "Standard text chat.")
    }

    private suspend fun emitProcessing(
        emitter: suspend (AgentEvent) -> Unit,
        message: String,
    ) {
        emitter(
            AgentEvent.Processing(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                content = message,
            ),
        )
    }

    /**
     * Extract aspect ratio from query if specified
     */
    private fun extractAspectRatio(query: String): String {
        val ratios = listOf("16:9", "9:16", "1:1", "4:3", "3:4", "21:9", "9:21")
        val lowerQuery = query.lowercase()

        for (ratio in ratios) {
            if (lowerQuery.contains(ratio)) {
                return ratio
            }
        }

        // Default to square
        return "1:1"
    }

    /**
     * Enhance prompt with artistic direction (Art Director-style)
     */
    private fun enhancePrompt(query: String): String {
        // Basic prompt enhancement
        val enhancements =
            listOf(
                "highly detailed",
                "professional quality",
                "8k resolution",
                "cinematic lighting",
            )

        return "$query, ${enhancements.joinToString(", ")}"
    }
}
