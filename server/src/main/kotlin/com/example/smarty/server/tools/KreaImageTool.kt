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

@Serializable
data class KreaImageRequest(
    val prompt: String,
    val aspect_ratio: String = "1:1",
    val webhook: String? = null
)

@Serializable
data class KreaImageResponse(
    val id: String, // job_id
    val status: String? = null
)

/**
 * Tool for triggering Krea AI Image Generation.
 * Shared by both the AI Agent (Workflow A) and Direct Request (Workflow B).
 */
class KreaImageTool {
    private val logger = LoggerFactory.getLogger(KreaImageTool::class.java)

    private val kreaApiKey = System.getenv("KREA_API_KEY") ?: ""
    private val webhookUrl = System.getenv("SUPABASE_KREA_WEBHOOK_URL") 
        ?: "https://project_ref.supabase.co/functions/v1/krea-webhook"

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    /**
     * Triggers Krea image generation asynchronously using webhooks.
     * @param prompt The prompt (raw from user or enhanced by Art Director)
     * @param aspectRatio e.g., "16:9", "1:1", "9:16"
     * @return The job ID returned by Krea AI
     */
    suspend fun generateImage(prompt: String, aspectRatio: String = "1:1"): String {
        if (kreaApiKey.isBlank()) {
            logger.warn("KREA_API_KEY is missing. Mocking success for development.")
            // Return a mock job ID if apiKey is missing so UI testing can proceed
            return "mock-job-${UUID.randomUUID()}"
        }

        try {
            // NOTE: The exact Krea endpoint may vary based on model (e.g. flux-1-dev vs other)
            // Defaulting to standard v1 generative endpoint pattern
            val response: HttpResponse = client.post("https://api.krea.ai/v1/generate/image/bfl/flux-1-dev") {
                header(HttpHeaders.Authorization, "Bearer $kreaApiKey")
                header("X-Webhook-URL", webhookUrl)
                contentType(ContentType.Application.Json)
                setBody(
                    KreaImageRequest(
                        prompt = prompt,
                        aspect_ratio = aspectRatio,
                        webhook = webhookUrl // Sometimes passed in body depending on specific API revision
                    )
                )
            }

            if (response.status.isSuccess()) {
                val body = response.body<KreaImageResponse>()
                logger.info("Successfully triggered Krea image generation. Job ID: ${body.id}")
                return body.id
            } else {
                val errorText = response.bodyAsText()
                if (response.status == HttpStatusCode.BadRequest && errorText.contains("filter", ignoreCase = true)) {
                    throw IllegalStateException("Prompt rejected by safety filters.")
                }
                logger.error("Krea API failed: ${response.status} - $errorText")
                throw RuntimeException("Krea API returned ${response.status}")
            }
        } catch (e: Exception) {
            logger.error("Error triggering Krea AI", e)
            throw e
        }
    }
}
