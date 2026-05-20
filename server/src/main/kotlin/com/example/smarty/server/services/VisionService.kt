package com.example.smarty.server.services

import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.LlmProvider
import com.example.smarty.server.llm.LlmProviderFactory
import io.ktor.client.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Service for processing images and performing OCR.
 * Routes all requests through OpenCode CLI free models.
 *
 * Since OpenCode free models are text-only, images are sent as base64 data
 * embedded in the prompt text. The LLM processes the textual representation.
 */
class VisionService(
    private val httpClient: HttpClient,
) {
    private val logger = LoggerFactory.getLogger(VisionService::class.java)

    // Lazy provider — always OpenCode CLI, no API keys
    private val llmProvider: LlmProvider by lazy {
        LlmProviderFactory.create(httpClient)
    }

    companion object {
        private const val IMAGE_BLOCK_TEMPLATE = "[Image: data:%s;base64,%s]"

        private val OCR_PROMPT =
            """
            <task>
            Extract ALL visible text from the image. Preserve visual structure.
            </task>

            <formatting_rules>
            - Tables: Use `|` separators for columns
            - Forms: Use `key: value` format
            - Illegible text: Mark as `[unclear]`
            - Output: Text only, no commentary
            </formatting_rules>

            <example>
            Input: Screenshot of a receipt
            Output:
            Store: ABC Market
            Date: 2024-01-15
            Items:
            | Item | Price |
            | Milk | $3.99 |
            | Bread | $2.50 |
            Total: $6.49
            </example>
            """.trimIndent()

        private val IMAGE_ANALYSIS_PROMPT =
            """
            <task>
            Analyze the image and provide a structured description.
            </task>

            <output_format>
            **Subject**: Main subject in 5 words or less
            **Text**: All visible text (if any)
            **Elements**: Key colors, objects, people, layout
            **Context**: Likely purpose or setting
            **Action**: Suggested next step if applicable
            </output_format>

            <style>
            Concise, objective, actionable. No filler words.
            </style>
            """.trimIndent()
    }

    /**
     * Perform OCR on a base64-encoded image.
     * Routes through OpenCode CLI free models.
     */
    suspend fun performOcr(
        base64Image: String,
        mimeType: String = "image/png",
    ): OcrResult {
        logger.info("Performing OCR via OpenCode CLI (type: $mimeType)")

        val imageData =
            if (base64Image.contains(",")) {
                base64Image.substringAfter(",")
            } else {
                base64Image
            }

        val messages =
            listOf(
                LlmMessage(
                    role = LlmMessage.Role.USER,
                    content = "$OCR_PROMPT\n\n" + IMAGE_BLOCK_TEMPLATE.format(mimeType, imageData),
                ),
            )

        return try {
            val response = StringBuilder()
            llmProvider.stream(messages, emptyList(), null).collect { chunk ->
                chunk.content?.let { response.append(it) }
            }

            OcrResult(
                extractedText = response.toString().trim(),
                contentType = detectContentType(response.toString()),
                language = "en",
                confidence = 0.9,
                elements = emptyList(),
                structure = "",
                success = true,
            )
        } catch (e: Exception) {
            logger.error("OCR failed: ${e.message}", e)
            OcrResult(
                extractedText = "",
                contentType = "error",
                language = "unknown",
                confidence = 0.0,
                elements = emptyList(),
                structure = "",
                success = false,
                error = e.message,
            )
        }
    }

    /**
     * Analyze an image and return a description.
     * Routes through OpenCode CLI free models.
     */
    suspend fun analyzeImage(
        base64Image: String,
        mimeType: String = "image/png",
        customPrompt: String? = null,
    ): ImageAnalysisResult {
        logger.info("Analyzing image via OpenCode CLI (type: $mimeType)")

        val imageData =
            if (base64Image.contains(",")) {
                base64Image.substringAfter(",")
            } else {
                base64Image
            }

        val prompt = customPrompt ?: IMAGE_ANALYSIS_PROMPT

        val messages =
            listOf(
                LlmMessage(
                    role = LlmMessage.Role.USER,
                    content = "$prompt\n\n" + IMAGE_BLOCK_TEMPLATE.format(mimeType, imageData),
                ),
            )

        return try {
            val response = StringBuilder()
            llmProvider.stream(messages, emptyList(), null).collect { chunk ->
                chunk.content?.let { response.append(it) }
            }

            ImageAnalysisResult(
                description = response.toString().trim(),
                success = true,
            )
        } catch (e: Exception) {
            logger.error("Image analysis failed: ${e.message}", e)
            ImageAnalysisResult(
                description = "",
                success = false,
                error = e.message,
            )
        }
    }

    private fun detectContentType(text: String): String {
        return when {
            text.contains("|") && text.lines().count { it.contains("|") } > 2 -> "table"
            text.contains(":") && text.lines().count { it.contains(":") } > 3 -> "form"
            text.length > 500 -> "document"
            text.lines().size > 10 -> "structured"
            else -> "text"
        }
    }
}

/**
 * Result of OCR processing.
 */
@Serializable
data class OcrResult(
    val extractedText: String,
    val contentType: String,
    val language: String,
    val confidence: Double,
    val elements: List<String>,
    val structure: String,
    val success: Boolean = true,
    val error: String? = null,
)

/**
 * Result of image analysis.
 */
@Serializable
data class ImageAnalysisResult(
    val description: String,
    val success: Boolean = true,
    val error: String? = null,
)
