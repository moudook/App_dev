package com.example.smarty.server.services

import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.LlmProviderFactory
import io.ktor.client.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Service for processing images and performing OCR.
 * Routes requests through the Antigravity proxy which handles vision AI.
 *
 * The proxy (at ANTHROPIC_BASE_URL) already has vision capabilities built in.
 * We send image data with prompts and receive processed text/analysis.
 */
class VisionService(
    private val httpClient: HttpClient
) {
    private val logger = LoggerFactory.getLogger(VisionService::class.java)

    companion object {
        // Format used to pass image data to LlmProvider (specifically AnthropicProvider which parses this)
        private const val IMAGE_BLOCK_TEMPLATE = "[Image: data:%s;base64,%s]"

        private val OCR_PROMPT = """
            <system_instructions>
            <task>
                Extract ALL visible text from the provided image.
                Preserve the visual structure as much as possible.
            </task>

            <formatting_rules>
                - Use `|` separators for table columns.
                - Use `key: value` format for forms.
                - Mark illegible text as `[unclear]`.
                - Do not add commentary or descriptions. Just the text.
            </formatting_rules>
            </system_instructions>
        """.trimIndent()

        private val IMAGE_ANALYSIS_PROMPT = """
            <system_instructions>
            <task>
                Analyze this image and provide a comprehensive structured description.
            </task>

            <output_requirements>
                1. **Subject**: What is the main subject?
                2. **Text**: Extract any visible text relevant to the context.
                3. **Visual Elements**: Key colors, objects, layout.
                4. **Context**: What is the likely purpose or setting?
            </output_requirements>

            <style>
                Concise, objective, professional.
            </style>
            </system_instructions>
        """.trimIndent()
    }

    /**
     * Perform OCR on a base64-encoded image.
     * Sends request through the proxy which handles vision processing.
     *
     * @param base64Image Base64-encoded image data
     * @param mimeType The MIME type (e.g., "image/png")
     * @return OCR result with extracted text
     */
    suspend fun performOcr(base64Image: String, mimeType: String = "image/png"): OcrResult {
        logger.info("Performing OCR via proxy (type: $mimeType)")

        // Strip data URI prefix if present
        val imageData = if (base64Image.contains(",")) {
            base64Image.substringAfter(",")
        } else {
            base64Image
        }

        // Create provider that routes through GEMINI
        val provider = LlmProviderFactory.create(httpClient, "GEMINI")

        // Send image with OCR prompt
        val messages = listOf(
            LlmMessage(
                role = LlmMessage.Role.USER,
                content = "$OCR_PROMPT\n\n" + IMAGE_BLOCK_TEMPLATE.format(mimeType, imageData)
            )
        )

        return try {
            val response = StringBuilder()
            provider.stream(messages, emptyList(), null).collect { chunk ->
                chunk.content?.let { response.append(it) }
            }

            OcrResult(
                extractedText = response.toString().trim(),
                contentType = detectContentType(response.toString()),
                language = "en",
                confidence = 0.9,
                elements = emptyList(),
                structure = "",
                success = true
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
                error = e.message
            )
        }
    }

    /**
     * Analyze an image and return a description.
     * Routes through proxy for vision processing.
     */
    suspend fun analyzeImage(
        base64Image: String,
        mimeType: String = "image/png",
        customPrompt: String? = null
    ): ImageAnalysisResult {
        logger.info("Analyzing image via proxy (type: $mimeType)")

        val imageData = if (base64Image.contains(",")) {
            base64Image.substringAfter(",")
        } else {
            base64Image
        }

        val provider = LlmProviderFactory.create(httpClient, "GEMINI")
        val prompt = customPrompt ?: IMAGE_ANALYSIS_PROMPT

        val messages = listOf(
            LlmMessage(
                role = LlmMessage.Role.USER,
                content = "$prompt\n\n" + IMAGE_BLOCK_TEMPLATE.format(mimeType, imageData)
            )
        )

        return try {
            val response = StringBuilder()
            provider.stream(messages, emptyList(), null).collect { chunk ->
                chunk.content?.let { response.append(it) }
            }

            ImageAnalysisResult(
                description = response.toString().trim(),
                success = true
            )
        } catch (e: Exception) {
            logger.error("Image analysis failed: ${e.message}", e)
            ImageAnalysisResult(
                description = "",
                success = false,
                error = e.message
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
    val error: String? = null
)

/**
 * Result of image analysis.
 */
@Serializable
data class ImageAnalysisResult(
    val description: String,
    val success: Boolean = true,
    val error: String? = null
)
