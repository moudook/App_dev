package com.example.smarty.server.tools

import com.example.smarty.server.data.DatabaseFactory
import com.example.smarty.server.data.GeneratedImageRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Request schema for Text-to-Image generation.
 */
@Serializable
data class KreaTextToImageRequest(
    val prompt: String,
    val width: Int = 1024,
    val height: Int = 1024,
    val steps: Int = 28,
)

/**
 * Request schema for Image-to-Image / Reference generation.
 */
@Serializable
data class KreaImageToImageRequest(
    val prompt: String,
    val imageUrls: List<String>,
)

/**
 * Job response from Krea API.
 */
@Serializable
data class KreaJobResponse(
    val job_id: String,
    val status: String,
    val created_at: String? = null,
)

/**
 * Completed job result.
 */
@Serializable
data class KreaJobResult(
    val job_id: String,
    val status: String,
    val result: KreaImageResult? = null,
    val completed_at: String? = null,
)

/**
 * Image result from Krea API.
 * Note: Krea returns urls as an array, not individual url/width/height fields.
 * See: https://docs.krea.ai/api-reference/general/get-a-job-by-id
 */
@Serializable
data class KreaImageResult(
    val urls: List<String>? = null, // Array of image URLs
    val style_id: String? = null, // Optional, for LoRA training jobs
)

/**
 * Structured response for image generation results.
 * Used by the frontend Image Visualizer to detect and render images.
 */
@Serializable
data class ImageGenerationResult(
    val type: String = "image",
    val url: String,
    val source: String = "krea", // "krea" or "supabase"
    val prompt: String? = null,
    val jobId: String? = null,
)

/**
 * Tool for triggering Krea AI Image Generation.
 * Supports both text-to-image and image-to-image workflows.
 */
class KreaImageTool {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        val shared by lazy { KreaImageTool() }
    }

    private val logger = LoggerFactory.getLogger(KreaImageTool::class.java)

    private val kreaApiKey = System.getenv("KREA_API_KEY")?.trim()?.ifBlank { null }
    private val baseUrl = "https://api.krea.ai"

    // Default model paths
    private val textToImageModel = "/generate/image/bfl/flux-1-dev"
    private val imageToImageModel = "/generate/image/google/nano-banana-pro"

    init {
        logger.info("============================================================")
        logger.info("KreaImageTool Initialization")
        logger.info("============================================================")

        if (kreaApiKey.isNullOrBlank()) {
            logger.error("X KREA_API_KEY environment variable is NOT SET")
            logger.error("   Hugging Face Spaces: Go to Settings -> Secrets -> Add secret named 'KREA_API_KEY'")
            logger.error("   GitHub: Go to Settings -> Secrets -> Add secret named 'KREA_API_KEY'")
            logger.error("   Image generation will FAIL until this is configured.")
        } else {
            // Redact API key in logs - only show length and first/last 2 chars
            val keyPreview =
                if (kreaApiKey.length > 4) {
                    "${kreaApiKey.take(2)}...${kreaApiKey.takeLast(2)}"
                } else {
                    "***"
                }
            logger.info("OK KREA_API_KEY is configured")
            logger.info("   Key length: ${kreaApiKey.length} chars")
            logger.info("   Key preview: $keyPreview")
            logger.info("   Base URL: $baseUrl")
            logger.info("   Text-to-Image Model: $textToImageModel")
            logger.info("   Image-to-Image Model: $imageToImageModel")
        }

        logger.info("============================================================")
    }

    private val client =
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                        explicitNulls = false
                    },
                )
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
        referenceImageUrl: String? = null,
    ): String {
        logger.info("------------------------------------------------------------")
        logger.info("IMAGE GEN: generateImage() called")
        val promptPreview = if (prompt.length > 80) prompt.take(80) + "..." else prompt
        logger.info("   Prompt: $promptPreview")
        logger.info("   Aspect Ratio: $aspectRatio")
        val refInfo = referenceImageUrl ?: "none (text-to-image)"
        logger.info("   Reference Image: $refInfo")
        logger.info("------------------------------------------------------------")

        // Fail fast if API key is missing - don't make HTTP request
        if (kreaApiKey.isNullOrBlank()) {
            logger.error("X ABORT: KREA_API_KEY is not set. Cannot proceed with image generation.")
            logger.error("   Troubleshooting:")
            logger.error("   1. Hugging Face Spaces: Settings -> Secrets -> Add 'KREA_API_KEY'")
            logger.error("   2. Restart the Space after adding the secret")
            logger.error("   3. Check build logs to verify secret was loaded")
            throw IllegalStateException(
                "KREA_API_KEY is not configured. Set this environment variable in your deployment " +
                    "environment before using image generation. Check Hugging Face Spaces secrets or " +
                    "GitHub repository secrets.",
            )
        }

        try {
            // Parse aspect ratio to dimensions
            val (width, height) = parseAspectRatio(aspectRatio)
            logger.info("DIMENSIONS: ${width}x$height from aspect ratio '$aspectRatio'")

            // Choose endpoint based on whether we have a reference image
            val endpoint =
                if (referenceImageUrl != null) {
                    logger.info("Using Image-to-Image endpoint: $imageToImageModel")
                    imageToImageModel
                } else {
                    logger.info("Using Text-to-Image endpoint: $textToImageModel")
                    textToImageModel
                }

            val fullUrl = "$baseUrl$endpoint"
            logger.info("REQUEST URL: $fullUrl")
            logger.info("AUTHORIZATION: Bearer [REDACTED] (key length: ${kreaApiKey.length} chars)")

            val response: HttpResponse =
                if (referenceImageUrl != null) {
                    // Image-to-Image request
                    logger.info("SENDING: Image-to-Image request...")
                    val promptShort = prompt.take(50)
                    logger.info("   Request: { prompt: \"$promptShort...\", imageUrls: [\"$referenceImageUrl\"] }")
                    client.post("$baseUrl$endpoint") {
                        header(HttpHeaders.Authorization, "Bearer $kreaApiKey")
                        contentType(ContentType.Application.Json)
                        setBody(
                            KreaImageToImageRequest(
                                prompt = prompt,
                                imageUrls = listOf(referenceImageUrl),
                            ),
                        )
                    }
                } else {
                    // Text-to-Image request
                    logger.info("SENDING: Text-to-Image request...")
                    val promptShort = prompt.take(50)
                    logger.info("   Request: { prompt: \"$promptShort...\", width: $width, height: $height, steps: 28 }")
                    client.post("$baseUrl$endpoint") {
                        header(HttpHeaders.Authorization, "Bearer $kreaApiKey")
                        contentType(ContentType.Application.Json)
                        setBody(
                            KreaTextToImageRequest(
                                prompt = prompt,
                                width = width,
                                height = height,
                                steps = 28,
                            ),
                        )
                    }
                }

            logger.info("RESPONSE: HTTP ${response.status}")

            // Always read raw response body first for debugging
            val rawBody = response.bodyAsText()
            logger.info("RAW RESPONSE (${rawBody.length} chars):")
            logger.info(rawBody.take(500))

            if (response.status.isSuccess()) {
                logger.info("OK Response status is SUCCESS (2xx)")

                // Parse manually to expose exact response structure
                val body =
                    try {
                        json.decodeFromString<KreaJobResponse>(rawBody)
                    } catch (e: Exception) {
                        logger.error("X Failed to parse Krea response as KreaJobResponse")
                        logger.error("   Error: ${e.message}")
                        logger.error("   Raw body: $rawBody")
                        logger.error("   Expected schema: { job_id: String, status: String, created_at: String? }")
                        throw RuntimeException("Failed to parse Krea API response: ${e.message}", e)
                    }

                logger.info("OK Successfully parsed response:")
                logger.info("   Job ID: ${body.job_id}")
                logger.info("   Status: ${body.status}")
                logger.info("   Created At: ${body.created_at ?: "not provided"}")
                logger.info("------------------------------------------------------------")
                return body.job_id
            } else {
                logger.error("X Response status is ERROR (${response.status})")
                logger.error("   Response body: $rawBody")

                when {
                    response.status == HttpStatusCode.Unauthorized -> {
                        logger.error("X HTTP 401 Unauthorized - API key is INVALID or EXPIRED")
                        logger.error("   Troubleshooting:")
                        logger.error("   1. Verify the API key in Hugging Face Secrets is correct")
                        logger.error("   2. Check if the key has expired on Krea.ai")
                        logger.error("   3. Ensure there are no extra spaces in the secret value")
                        throw IllegalStateException("Krea API authentication failed (401). Check KREA_API_KEY in deployment environment.")
                    }
                    response.status == HttpStatusCode.Forbidden -> {
                        logger.error("X HTTP 403 Forbidden - API key lacks permissions")
                        logger.error("   Check if your Krea.ai account has API access enabled")
                        throw IllegalStateException("Krea API access denied (403). Check API key permissions.")
                    }
                    response.status == HttpStatusCode.BadRequest &&
                        rawBody.contains("filter", ignoreCase = true) -> {
                        logger.error("X HTTP 400 Bad Request - Prompt rejected by safety filters")
                        throw IllegalStateException("Prompt rejected by safety filters.")
                    }
                    else -> {
                        logger.error("X HTTP ${response.status} - Unexpected error")
                        throw RuntimeException("Krea API returned ${response.status}: ${rawBody.take(200)}")
                    }
                }
            }
        } catch (e: Exception) {
            if (e is IllegalStateException) throw e
            logger.error("X Unexpected error in generateImage(): ${e.message}")
            logger.error("   Stack trace: ${e.stackTraceToString().take(500)}")
            throw RuntimeException("Failed to trigger Krea image generation: ${e.message}", e)
        }
    }

    /**
     * Polls the job status from Krea API.
     * @param jobId The job ID to check
     * @return The job result with image URL if completed
     */
    suspend fun pollJobStatus(jobId: String): KreaJobResult {
        logger.debug("POLL: Checking status for job: $jobId")

        // Fail fast if API key is missing
        if (kreaApiKey.isNullOrBlank()) {
            logger.error("X ABORT: pollJobStatus() called but KREA_API_KEY is not set")
            throw IllegalStateException("KREA_API_KEY is not configured. Cannot poll job status.")
        }

        try {
            val pollUrl = "$baseUrl/jobs/$jobId"
            logger.debug("POLL URL: $pollUrl")

            val response: HttpResponse =
                client.get("$baseUrl/jobs/$jobId") {
                    header(HttpHeaders.Authorization, "Bearer $kreaApiKey")
                }

            // Always read raw response body first for debugging
            val rawBody = response.bodyAsText()
            logger.debug("POLL RESPONSE: HTTP ${response.status}")
            logger.debug("RESPONSE BODY: $rawBody")

            if (response.status.isSuccess()) {
                logger.debug("OK Poll successful (2xx)")

                return try {
                    val result = json.decodeFromString<KreaJobResult>(rawBody)
                    logger.debug("   Job status: ${result.status}")
                    if (result.result?.urls != null) {
                        logger.debug("   Result URLs count: ${result.result.urls.size}")
                    }
                    result
                } catch (e: Exception) {
                    logger.error("X Failed to parse poll response as KreaJobResult")
                    logger.error("   Error: ${e.message}")
                    logger.error("   Raw body: $rawBody")
                    throw RuntimeException("Failed to parse Krea poll response: ${e.message}", e)
                }
            } else {
                logger.error("X Poll failed with status: ${response.status}")
                logger.error("   Response: $rawBody")
                throw RuntimeException("Failed to poll job status: ${response.status}")
            }
        } catch (e: Exception) {
            logger.error("X Error polling Krea job status for job $jobId: ${e.message}")
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
        maxAttempts: Int = 150, // 5 minutes max (150 * 2000ms)
        pollIntervalMs: Long = 2000L,
    ): KreaJobResult {
        logger.info("WAIT: Starting waitForCompletion() for job: $jobId")
        logger.info("   Max attempts: $maxAttempts")
        logger.info("   Poll interval: ${pollIntervalMs}ms")
        logger.info("   Max wait time: ${maxAttempts * pollIntervalMs / 1000}s (${maxAttempts * pollIntervalMs / 60000} min)")

        var attempts = 0
        val startTime = System.currentTimeMillis()

        while (attempts < maxAttempts) {
            attempts++
            val elapsed = (System.currentTimeMillis() - startTime) / 1000
            logger.info("POLL: Attempt $attempts/$maxAttempts (elapsed: ${elapsed}s)...")

            val result = pollJobStatus(jobId)

            when (result.status.lowercase()) {
                "completed" -> {
                    val elapsedTime = (System.currentTimeMillis() - startTime) / 1000
                    logger.info("OK Job COMPLETED after ${elapsedTime}s!")

                    // Validate result has URLs
                    val imageUrl = result.result?.urls?.firstOrNull()
                    if (imageUrl.isNullOrBlank()) {
                        logger.error("X Job completed but result.urls is empty or null")
                        logger.error("   Full result: $result")
                        throw IllegalStateException("Image generation completed but no image URL was returned")
                    }
                    logger.info("IMAGE URL: $imageUrl")
                    logger.info("Total wait time: ${elapsedTime}s")
                    return result
                }
                "failed", "error", "cancelled" -> {
                    logger.error("X Job FAILED with status: ${result.status}")
                    logger.error("   Full result: $result")
                    throw IllegalStateException("Image generation failed: ${result.status}")
                }
                "queued", "processing", "pending", "backlogged", "scheduled", "sampling" -> {
                    logger.info("WAITING: Job still '$result.status' - waiting ${pollIntervalMs}ms before next poll...")
                    if (attempts < maxAttempts) {
                        kotlinx.coroutines.delay(pollIntervalMs)
                    }
                }
                "intermediate-complete" -> {
                    logger.info("INTERMEDIATE: Job has intermediate result - continuing to poll for final result...")
                    if (attempts < maxAttempts) {
                        kotlinx.coroutines.delay(pollIntervalMs)
                    }
                }
                else -> {
                    logger.warn("WARN: Job has unknown status: '${result.status}' - continuing to poll...")
                    if (attempts < maxAttempts) {
                        kotlinx.coroutines.delay(pollIntervalMs)
                    }
                }
            }
        }

        val totalTime = (System.currentTimeMillis() - startTime) / 1000
        logger.error("X Job did not complete within $maxAttempts attempts (${totalTime}s)")
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

    /**
     * Downloads image bytes from Krea and stores it directly in local database.
     * @param imageUrl The source image URL (from Krea)
     * @param jobId The Krea job ID
     * @return Local server image URL that will serve the image
     */
    suspend fun storeImageLocally(
        imageUrl: String,
        jobId: String,
    ): String {
        logger.info("IMAGE STORE: Downloading image and storing in database")
        logger.info("   Source URL: $imageUrl")
        logger.info("   Job ID: $jobId")

        try {
            // Download image bytes from Krea
            logger.info("DOWNLOAD: Fetching image bytes from Krea...")
            val imageBytes = client.get(imageUrl).body<ByteArray>()
            logger.info("DOWNLOAD: Successfully downloaded ${imageBytes.size} bytes")

            // Detect content type
            val contentType =
                when (imageUrl.substringAfterLast('.', "png").lowercase()) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "webp" -> "image/webp"
                    else -> "image/png"
                }

            // Store directly in database
            GeneratedImageRepository(DatabaseFactory.getDataSource()!!).storeImageBytes(jobId, imageBytes, contentType)

            // Get generated image id from database
            val storedImage = GeneratedImageRepository(DatabaseFactory.getDataSource()!!).getByJobId(jobId)
            val serverUrl = System.getenv("SERVER_URL") ?: "http://localhost:7860"
            val localImageUrl = "$serverUrl/generated-images/${storedImage?.id}"

            logger.info("IMAGE STORED: Successfully saved to database")
            logger.info("   Local URL: $localImageUrl")

            return localImageUrl
        } catch (e: Exception) {
            logger.error("IMAGE STORE: Failed to store image: ${e.message}", e)
            logger.warn("FALLBACK: Will use original Krea URL instead")
            return imageUrl
        }
    }

    /**
     * Uploads image to Supabase storage (dummy implementation - just returns Krea URL)
     * @param imageUrl The source image URL (from Krea)
     * @param jobId The Krea job ID
     * @param bucketName Supabase bucket name
     * @return Supabase URL or Krea URL as fallback
     */
    suspend fun uploadToSupabase(
        imageUrl: String,
        jobId: String,
        bucketName: String,
    ): String {
        logger.warn("uploadToSupabase not implemented - using Krea URL directly")
        return imageUrl
    }
}
