package com.example.smarty.server.tools

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Request schema for Text-to-Image generation.
 */
@Serializable
data class KreaTextToImageRequest(
    val prompt: String,
    val width: Int = 1024,
    val height: Int = 1024,
    val steps: Int = 28
)

/**
 * Request schema for Image-to-Image / Reference generation.
 */
@Serializable
data class KreaImageToImageRequest(
    val prompt: String,
    val imageUrls: List<String>
)

/**
 * Job response from Krea API.
 */
@Serializable
data class KreaJobResponse(
    val job_id: String,
    val status: String,
    val created_at: String? = null
)

/**
 * Completed job result.
 */
@Serializable
data class KreaJobResult(
    val job_id: String,
    val status: String,
    val result: KreaImageResult? = null,
    val completed_at: String? = null
)

/**
 * Image result from Krea API.
 * Note: Krea returns urls as an array, not individual url/width/height fields.
 * See: https://docs.krea.ai/api-reference/general/get-a-job-by-id
 */
@Serializable
data class KreaImageResult(
    val urls: List<String>? = null,  // Array of image URLs
    val style_id: String? = null     // Optional, for LoRA training jobs
)

/**
 * Tool for triggering Krea AI Image Generation.
 * Supports both text-to-image and image-to-image workflows.
 */
class KreaImageTool {
    private val logger = LoggerFactory.getLogger(KreaImageTool::class.java)

    private val kreaApiKey = System.getenv("KREA_API_KEY")
    private val baseUrl = "https://api.krea.ai"

    // Default model paths
    private val textToImageModel = "/generate/image/bfl/flux-1-dev"
    private val imageToImageModel = "/generate/image/google/nano-banana-pro"

    init {
        if (kreaApiKey.isNullOrBlank()) {
            logger.error("KREA_API_KEY environment variable is not set. Image generation will fail. " +
                "Set KREA_API_KEY in your deployment environment (Hugging Face Spaces secrets or GitHub secrets).")
        } else {
            // Redact API key in logs - only show length and first/last 2 chars
            val keyPreview = if (kreaApiKey.length > 4) {
                "${kreaApiKey.take(2)}...${kreaApiKey.takeLast(2)}"
            } else {
                "***"
            }
            logger.info("KreaImageTool initialized with API key (length: ${kreaApiKey.length} chars, preview: $keyPreview)")
        }
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30000
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 30000
        }
    }

    /**
     * Triggers Krea image generation asynchronously.
     * @param prompt The text description of the image
     * @param aspectRatio e.g., "16:9", "1:1", "9:16"
     * @param referenceImageUrl Optional URL for image-to-image generation
     * @return The job ID for polling
     */
    suspend fun generateImage(
        prompt: String,
        aspectRatio: String = "1:1",
        referenceImageUrl: String? = null
    ): String {
        // Fail fast if API key is missing - don't make HTTP request
        if (kreaApiKey.isNullOrBlank()) {
            logger.error("generateImage() called but KREA_API_KEY is not set. Cannot proceed.")
            throw IllegalStateException(
                "KREA_API_KEY is not configured. Set this environment variable in your deployment " +
                "environment before using image generation. Check Hugging Face Spaces secrets or " +
                "GitHub repository secrets."
            )
        }

        try {
            // Parse aspect ratio to dimensions
            val (width, height) = parseAspectRatio(aspectRatio)

            // Choose endpoint based on whether we have a reference image
            val endpoint = if (referenceImageUrl != null) {
                imageToImageModel
            } else {
                textToImageModel
            }

            val response: HttpResponse = if (referenceImageUrl != null) {
                // Image-to-Image request
                client.post("$baseUrl$endpoint") {
                    header(HttpHeaders.Authorization, "Bearer [REDACTED]")  // Never log actual API key
                    contentType(ContentType.Application.Json)
                    setBody(
                        KreaImageToImageRequest(
                            prompt = prompt,
                            imageUrls = listOf(referenceImageUrl)
                        )
                    )
                }
            } else {
                // Text-to-Image request
                client.post("$baseUrl$endpoint") {
                    header(HttpHeaders.Authorization, "Bearer [REDACTED]")  // Never log actual API key
                    contentType(ContentType.Application.Json)
                    setBody(
                        KreaTextToImageRequest(
                            prompt = prompt,
                            width = width,
                            height = height,
                            steps = 28
                        )
                    )
                }
            }

            // Always read raw response body first for debugging
            val rawBody = response.bodyAsText()

            if (response.status.isSuccess()) {
                // Log raw response for debugging (truncated to prevent log spam)
                logger.info("Krea raw response: {}", rawBody.take(300))

                // Parse manually to expose exact response structure
                val body = try {
                    Json { ignoreUnknownKeys = true }.decodeFromString<KreaJobResponse>(rawBody)
                } catch (e: Exception) {
                    logger.error("Failed to parse Krea response as KreaJobResponse. Raw body: {}", rawBody.take(300), e)
                    throw RuntimeException("Failed to parse Krea API response: ${e.message}", e)
                }

                logger.info(
                    "Successfully triggered Krea image generation. Job ID: {} (status: {})",
                    body.job_id,
                    body.status
                )
                return body.job_id
            } else {
                // Log raw error response (truncated)
                logger.error("Krea API failed: {} - response: {}", response.status, rawBody.take(300))

                when {
                    response.status == HttpStatusCode.BadRequest &&
                        rawBody.contains("filter", ignoreCase = true) -> {
                        throw IllegalStateException("Prompt rejected by safety filters.")
                    }
                    response.status == HttpStatusCode.Unauthorized -> {
                        throw IllegalStateException("Krea API authentication failed (401). Check KREA_API_KEY in deployment environment.")
                    }
                    response.status == HttpStatusCode.Forbidden -> {
                        throw IllegalStateException("Krea API access denied (403). Check API key permissions.")
                    }
                    else -> {
                        throw RuntimeException("Krea API returned ${response.status}: ${rawBody.take(200)}")
                    }
                }
            }
        } catch (e: Exception) {
            if (e is IllegalStateException) throw e
            logger.error("Error triggering Krea AI", e)
            throw RuntimeException("Failed to trigger Krea image generation: ${e.message}", e)
        }
    }

    /**
     * Polls the job status from Krea API.
     * @param jobId The job ID to check
     * @return The job result with image URL if completed
     */
    suspend fun pollJobStatus(jobId: String): KreaJobResult {
        // Fail fast if API key is missing
        if (kreaApiKey.isNullOrBlank()) {
            logger.error("pollJobStatus() called but KREA_API_KEY is not set")
            throw IllegalStateException("KREA_API_KEY is not configured. Cannot poll job status.")
        }

        try {
            val response: HttpResponse = client.get("$baseUrl/jobs/$jobId") {
                header(HttpHeaders.Authorization, "Bearer [REDACTED]")  // Never log actual key
            }

            // Always read raw response body first for debugging
            val rawBody = response.bodyAsText()

            if (response.status.isSuccess()) {
                logger.debug("Poll job {} raw response: {}", jobId, rawBody.take(200))

                return try {
                    Json { ignoreUnknownKeys = true }.decodeFromString<KreaJobResult>(rawBody)
                } catch (e: Exception) {
                    logger.error("Failed to parse poll response as KreaJobResult. Raw body: {}", rawBody.take(200), e)
                    throw RuntimeException("Failed to parse Krea poll response: ${e.message}", e)
                }
            } else {
                // Log error response (safe, no API key in response)
                logger.error("Failed to poll job {}: status {} - response: {}", jobId, response.status, rawBody.take(200))
                throw RuntimeException("Failed to poll job status: ${response.status}")
            }
        } catch (e: Exception) {
            logger.error("Error polling Krea job status for job {}: {}", jobId, e.message, e)
            throw RuntimeException("Failed to poll job status: ${e.message}", e)
        }
    }

    /**
     * Waits for job completion with polling.
     * @param jobId The job ID to wait for
     * @param maxAttempts Maximum number of polling attempts (default: 150 = 5 minutes at 2s intervals)
     * @param pollIntervalMs Interval between polls in milliseconds
     * @return The completed job result
     */
    suspend fun waitForCompletion(
        jobId: String,
        maxAttempts: Int = 150,  // 5 minutes max (150 * 2000ms)
        pollIntervalMs: Long = 2000L
    ): KreaJobResult {
        var attempts = 0

        while (attempts < maxAttempts) {
            val result = pollJobStatus(jobId)

            when (result.status.lowercase()) {
                "completed" -> {
                    // Validate result has URLs
                    val imageUrl = result.result?.urls?.firstOrNull()
                    if (imageUrl.isNullOrBlank()) {
                        logger.error("Job {} completed but result.urls is empty or null. Raw result: {}", jobId, result.result)
                        throw IllegalStateException("Image generation completed but no image URL was returned")
                    }
                    logger.info("Job {} completed successfully. Image URL: {}", jobId, imageUrl)
                    return result
                }
                "failed", "error", "cancelled" -> {
                    logger.error("Job {} failed with status: {}", jobId, result.status)
                    throw IllegalStateException("Image generation failed: ${result.status}")
                }
                "queued", "processing", "pending", "backlogged", "scheduled", "sampling" -> {
                    logger.debug("Job {} still {} (attempt {}/{})", jobId, result.status, attempts + 1, maxAttempts)
                    attempts++
                    if (attempts < maxAttempts) {
                        kotlinx.coroutines.delay(pollIntervalMs)
                    }
                }
                "intermediate-complete" -> {
                    // Intermediate result available, continue polling for final
                    logger.debug("Job {} has intermediate result, continuing to poll...", jobId)
                    attempts++
                    if (attempts < maxAttempts) {
                        kotlinx.coroutines.delay(pollIntervalMs)
                    }
                }
                else -> {
                    logger.warn("Job {} has unknown status: {}", jobId, result.status)
                    attempts++
                    if (attempts < maxAttempts) {
                        kotlinx.coroutines.delay(pollIntervalMs)
                    }
                }
            }
        }

        throw IllegalStateException("Job $jobId did not complete within ${maxAttempts * pollIntervalMs / 1000} seconds")
    }

    /**
     * Parses aspect ratio string to pixel dimensions.
     * @param aspectRatio e.g., "16:9", "1:1", "9:16"
     * @return Pair of (width, height)
     */
    private fun parseAspectRatio(aspectRatio: String): Pair<Int, Int> {
        val baseSize = 1024

        return when (aspectRatio) {
            "16:9" -> Pair(baseSize, (baseSize * 9 / 16).coerceAtLeast(512))
            "9:16" -> Pair((baseSize * 9 / 16).coerceAtLeast(512), baseSize)
            "4:3" -> Pair(baseSize, (baseSize * 3 / 4).coerceAtLeast(512))
            "3:4" -> Pair((baseSize * 3 / 4).coerceAtLeast(512), baseSize)
            "21:9" -> Pair(baseSize, (baseSize * 9 / 21).coerceAtLeast(512))
            "9:21" -> Pair((baseSize * 9 / 21).coerceAtLeast(512), baseSize)
            "1:1" -> Pair(baseSize, baseSize)
            else -> Pair(baseSize, baseSize)
        }
    }
}
