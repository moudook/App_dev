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

@Serializable
data class KreaImageResult(
    val url: String,
    val width: Int,
    val height: Int
)

/**
 * Tool for triggering Krea AI Image Generation.
 * Supports both text-to-image and image-to-image workflows.
 */
class KreaImageTool {
    private val logger = LoggerFactory.getLogger(KreaImageTool::class.java)

    private val kreaApiKey = System.getenv("KREA_API_KEY") ?: ""
    private val baseUrl = "https://api.krea.ai"

    // Default model paths
    private val textToImageModel = "/generate/image/bfl/flux-1-dev"
    private val imageToImageModel = "/generate/image/google/nano-banana-pro"

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
        if (kreaApiKey.isBlank()) {
            logger.warn("KREA_API_KEY is missing. Mocking success for development.")
            return "mock-job-${UUID.randomUUID()}"
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
                    header(HttpHeaders.Authorization, "Bearer $kreaApiKey")
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
                    header(HttpHeaders.Authorization, "Bearer $kreaApiKey")
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

            if (response.status.isSuccess()) {
                val body = response.body<KreaJobResponse>()
                logger.info(
                    "Successfully triggered Krea image generation. Job ID: {} (status: {})",
                    body.job_id,
                    body.status
                )
                return body.job_id
            } else {
                val errorText = response.bodyAsText()
                logger.error("Krea API failed: ${response.status} - $errorText")
                
                when {
                    response.status == HttpStatusCode.BadRequest && 
                        errorText.contains("filter", ignoreCase = true) -> {
                        throw IllegalStateException("Prompt rejected by safety filters.")
                    }
                    response.status == HttpStatusCode.Unauthorized -> {
                        throw IllegalStateException("Krea API authentication failed. Check API key.")
                    }
                    response.status == HttpStatusCode.Forbidden -> {
                        throw IllegalStateException("Krea API access denied. Check API key permissions.")
                    }
                    else -> {
                        throw RuntimeException("Krea API returned ${response.status}: ${errorText.take(200)}")
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
        if (kreaApiKey.isBlank()) {
            // Return mock completed job for development
            return KreaJobResult(
                job_id = jobId,
                status = "completed",
                result = KreaImageResult(
                    url = "https://via.placeholder.com/1024x1024.png?text=Mock+Krea+Image",
                    width = 1024,
                    height = 1024
                ),
                completed_at = java.time.Instant.now().toString()
            )
        }

        try {
            val response: HttpResponse = client.get("$baseUrl/jobs/$jobId") {
                header(HttpHeaders.Authorization, "Bearer $kreaApiKey")
            }

            if (response.status.isSuccess()) {
                return response.body<KreaJobResult>()
            } else {
                val errorText = response.bodyAsText()
                logger.error("Failed to poll job status: ${response.status} - $errorText")
                throw RuntimeException("Failed to poll job status: ${response.status}")
            }
        } catch (e: Exception) {
            logger.error("Error polling Krea job status", e)
            throw RuntimeException("Failed to poll job status: ${e.message}", e)
        }
    }

    /**
     * Waits for job completion with polling.
     * @param jobId The job ID to wait for
     * @param maxAttempts Maximum number of polling attempts
     * @param pollIntervalMs Interval between polls in milliseconds
     * @return The completed job result
     */
    suspend fun waitForCompletion(
        jobId: String,
        maxAttempts: Int = 60,
        pollIntervalMs: Long = 2000L
    ): KreaJobResult {
        var attempts = 0
        
        while (attempts < maxAttempts) {
            val result = pollJobStatus(jobId)
            
            when (result.status.lowercase()) {
                "completed" -> {
                    logger.info("Job {} completed successfully. Image URL: {}", jobId, result.result?.url)
                    return result
                }
                "failed", "error", "cancelled" -> {
                    logger.error("Job {} failed with status: {}", jobId, result.status)
                    throw IllegalStateException("Image generation failed: ${result.status}")
                }
                "queued", "processing", "pending" -> {
                    logger.debug("Job {} still {} (attempt {}/{})", jobId, result.status, attempts + 1, maxAttempts)
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
        
        throw IllegalStateException("Job $jobId did not complete within $maxAttempts attempts")
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
