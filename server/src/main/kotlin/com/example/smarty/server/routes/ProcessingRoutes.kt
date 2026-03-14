package com.example.smarty.server.routes

import com.example.smarty.server.models.*
import com.example.smarty.server.plugins.firebaseUser
import com.example.smarty.server.services.*
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.example.smarty.server.data.DatabaseFactory
import com.example.smarty.server.data.GeneratedImageRepository
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.*

/**
 * Configure processing routes for content analysis, OCR, and file handling.
 *
 * All routes require Firebase authentication.
 *
 * Endpoints:
 * - POST /analyze/content - Analyze text content
 * - POST /analyze/document - Analyze document (PDF/text)
 * - POST /process/image - Image OCR/analysis
 * - POST /process/pdf - PDF text extraction
 * - POST /upload - File upload for processing
 */
fun Application.configureProcessingRoutes() {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Initialize HTTP client
    val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    // Initialize services
    val visionService = VisionService(httpClient)
    val fileProcessingService = FileProcessingService(visionService, httpClient)
    val contentAnalysisService = ContentAnalysisService(httpClient, visionService)

    routing {
        // All processing routes require authentication
        authenticate("firebase") {

            /**
             * Analyze text content and extract metadata.
             *
             * Request body:
             * {
             *   "content": "The text to analyze",
             *   "attachments": [{"fileName": "file.pdf", "fileType": "application/pdf"}]
             * }
             */
            post("/analyze/content") {
                val user = call.firebaseUser()
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    return@post
                }

                try {
                    val request = call.receive<ContentAnalysisRequest>()
                    call.application.log.info("Content analysis request from user: ${user.userId}")

                    val attachments = request.attachments?.map {
                        com.example.smarty.server.models.AttachmentInfo(it.fileName, it.fileType)
                    }

                    val result = contentAnalysisService.analyzeContent(request.content, attachments)
                    call.respond(HttpStatusCode.OK, result)
                } catch (e: Exception) {
                    call.application.log.error("Content analysis failed", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Analysis failed: ${e.message}")
                    )
                }
            }

            /**
             * Analyze a document (PDF text or long-form content).
             *
             * Request body:
             * {
             *   "text": "The document text",
             *   "fileName": "document.pdf",
             *   "userContext": "Optional context about user intent"
             * }
             */
            post("/analyze/document") {
                val user = call.firebaseUser()
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    return@post
                }

                try {
                    val request = call.receive<DocumentAnalysisRequest>()
                    call.application.log.info("Document analysis request from user: ${user.userId}")

                    val result = contentAnalysisService.analyzeDocument(
                        documentText = request.text,
                        fileName = request.fileName,
                        userContext = request.userContext
                    )
                    call.respond(HttpStatusCode.OK, result)
                } catch (e: Exception) {
                    call.application.log.error("Document analysis failed", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Analysis failed: ${e.message}")
                    )
                }
            }

            /**
             * Process an image with OCR.
             *
             * Request body:
             * {
             *   "base64Image": "base64-encoded image data",
             *   "mimeType": "image/png",
             *   "analysisType": "ocr" | "analyze"
             * }
             */
            post("/process/image") {
                val user = call.firebaseUser()
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    return@post
                }

                try {
                    val request = call.receive<ImageProcessingRequest>()
                    call.application.log.info("Image processing request from user: ${user.userId}")

                    val result = when (request.analysisType) {
                        "analyze" -> {
                            val analysis = visionService.analyzeImage(
                                base64Image = request.base64Image,
                                mimeType = request.mimeType ?: "image/png"
                            )
                            ImageProcessingResponse(
                                text = analysis.description,
                                contentType = "analysis",
                                success = analysis.success,
                                error = analysis.error
                            )
                        }
                        else -> { // Default to OCR
                            val ocr = visionService.performOcr(
                                base64Image = request.base64Image,
                                mimeType = request.mimeType ?: "image/png"
                            )
                            ImageProcessingResponse(
                                text = ocr.extractedText,
                                contentType = ocr.contentType,
                                success = ocr.success,
                                error = ocr.error
                            )
                        }
                    }
                    call.respond(HttpStatusCode.OK, result)
                } catch (e: Exception) {
                    call.application.log.error("Image processing failed", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Processing failed: ${e.message}")
                    )
                }
            }

            /**
             * Process a PDF and extract text.
             *
             * Request body:
             * {
             *   "base64Pdf": "base64-encoded PDF data",
             *   "fileName": "document.pdf",
             *   "useOcr": true
             * }
             */
            post("/process/pdf") {
                val user = call.firebaseUser()
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    return@post
                }

                try {
                    val request = call.receive<PdfProcessingRequest>()
                    call.application.log.info("PDF processing request from user: ${user.userId}")

                    val pdfBytes = Base64.getDecoder().decode(request.base64Pdf)
                    val result = fileProcessingService.processPdf(
                        pdfBytes = pdfBytes,
                        fileName = request.fileName,
                        useOcrForImages = request.useOcr ?: true
                    )

                    call.respond(HttpStatusCode.OK, result)
                } catch (e: Exception) {
                    call.application.log.error("PDF processing failed", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Processing failed: ${e.message}")
                    )
                }
            }

            /**
             * Upload a file for processing.
             * Accepts multipart/form-data.
             *
             * Form fields:
             * - file: The file to upload
             * - analysisType: "content" | "document" | "ocr"
             */
            post("/upload") {
                val user = call.firebaseUser()
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    return@post
                }

                try {
                    val multipart = call.receiveMultipart()
                    var fileBytes: ByteArray? = null
                    var fileName: String? = null
                    var contentType: String? = null
                    var analysisType: String = "content"

                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FileItem -> {
                                fileName = part.originalFileName
                                contentType = part.contentType?.toString()
                                @Suppress("DEPRECATION")
                                fileBytes = part.streamProvider().use { stream ->
                                    stream.readBytes()
                                }
                            }
                            is PartData.FormItem -> {
                                if (part.name == "analysisType") {
                                    analysisType = part.value
                                }
                            }
                            else -> {}
                        }
                        part.dispose()
                    }

                    if (fileBytes == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No file uploaded"))
                        return@post
                    }

                    call.application.log.info("File upload: $fileName ($contentType) from user: ${user.userId}")

                    // Process based on content type
                    val result: Any = when {
                        contentType?.startsWith("application/pdf") == true -> {
                            val pdfResult = fileProcessingService.processPdf(fileBytes!!, fileName)
                            if (analysisType == "document") {
                                contentAnalysisService.analyzeDocument(pdfResult.text, fileName)
                            } else {
                                pdfResult
                            }
                        }
                        contentType?.startsWith("image/") == true -> {
                            val base64 = Base64.getEncoder().encodeToString(fileBytes!!)
                            val ocrResult = visionService.performOcr(base64, contentType!!)
                            if (analysisType == "content") {
                                contentAnalysisService.analyzeContent(ocrResult.extractedText)
                            } else {
                                ocrResult
                            }
                        }
                        else -> {
                            // Treat as text
                            val text = String(fileBytes!!, Charsets.UTF_8)
                            contentAnalysisService.analyzeContent(text)
                        }
                    }

                    call.respond(HttpStatusCode.OK, result)
                } catch (e: Exception) {
                    call.application.log.error("File upload processing failed", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Upload processing failed: ${e.message}")
                    )
                }
            }

            /**
             * Direct Image Generation Endpoint (Workflow B)
             * Bypasses the AI Agent and directly triggers Krea.
             */
            post("/api/v1/image/direct") {
                val log = call.application.log
                val startTime = System.currentTimeMillis()
                
                val user = call.firebaseUser()
                if (user == null) {
                    log.warn("❌ /api/v1/image/direct - Unauthorized request (no Firebase user)")
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    return@post
                }

                try {
                    val request = call.receive<DirectImageGenerationRequest>()
                    log.info("═".repeat(60))
                    log.info("📸 /api/v1/image/direct - Request received")
                    log.info("   User ID: ${user.userId}")
                    log.info("   Prompt: ${request.prompt.take(80)}${if (request.prompt.length > 80) "..." else ""}")
                    log.info("   Aspect Ratio: ${request.aspectRatio}")
                    log.info("═".repeat(60))

                    log.info("🔧 Creating KreaImageTool instance...")
                    val kreaTool = com.example.smarty.server.tools.KreaImageTool()
                    
                    log.info("🚀 Calling kreaTool.generateImage()...")
                    val jobId = kreaTool.generateImage(request.prompt, request.aspectRatio)
                    
                    log.info("✅ Image generation triggered successfully!")
                    log.info("   Job ID: $jobId")

                    val dataSource = DatabaseFactory.getDataSource()
                    if (dataSource != null) {
                        try {
                            val imageRepo = GeneratedImageRepository(dataSource)
                            imageRepo.create(
                                userId = user.userId,
                                sessionId = null,
                                prompt = request.prompt,
                                kreaJobId = jobId
                            )
                            log.info("💾 Job ID saved to database")
                        } catch (e: Exception) {
                            log.warn("⚠️  Failed to save job ID to database: ${e.message}")
                            // Continue anyway - image generation succeeded
                        }
                    } else {
                        log.warn("⚠️  Database not available - skipping job ID storage")
                    }

                    val elapsed = System.currentTimeMillis() - startTime
                    log.info("✅ /api/v1/image/direct - Completed in ${elapsed}ms")
                    log.info("═".repeat(60))
                    
                    call.respond(HttpStatusCode.OK, DirectImageGenerationResponse(jobId = jobId, success = true))
                } catch (e: IllegalStateException) {
                    // KREA_API_KEY not configured - return descriptive error
                    val elapsed = System.currentTimeMillis() - startTime
                    log.error("❌ /api/v1/image/direct - FAILED after ${elapsed}ms")
                    log.error("   Error type: IllegalStateException")
                    log.error("   Cause: KREA_API_KEY not configured")
                    log.error("   Message: ${e.message}")
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        DirectImageGenerationResponse(
                            jobId = "",
                            success = false,
                            message = "Image generation service is not configured. KREA_API_KEY is missing from server environment."
                        )
                    )
                } catch (e: Exception) {
                    val elapsed = System.currentTimeMillis() - startTime
                    log.error("❌ /api/v1/image/direct - FAILED after ${elapsed}ms")
                    log.error("   Error type: ${e::class.simpleName}")
                    log.error("   Message: ${e.message}")
                    log.error("   Stack trace: ${e.stackTraceToString().take(500)}")
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        DirectImageGenerationResponse(jobId = "", success = false, message = e.message)
                    )
                }
            }
        }
    }
}

// Request DTOs

@Serializable
data class ContentAnalysisRequest(
    val content: String,
    val attachments: List<AttachmentDto>? = null
)

@Serializable
data class AttachmentDto(
    val fileName: String,
    val fileType: String
)

@Serializable
data class DocumentAnalysisRequest(
    val text: String,
    val fileName: String? = null,
    val userContext: String? = null
)

@Serializable
data class ImageProcessingRequest(
    val base64Image: String,
    val mimeType: String? = null,
    val analysisType: String? = "ocr" // "ocr" or "analyze"
)

@Serializable
data class PdfProcessingRequest(
    val base64Pdf: String,
    val fileName: String? = null,
    val useOcr: Boolean? = true
)

// Response DTOs

@Serializable
data class ImageProcessingResponse(
    val text: String,
    val contentType: String,
    val success: Boolean,
    val error: String? = null
)

@Serializable
data class DirectImageGenerationRequest(
    val prompt: String,
    val aspectRatio: String = "1:1"
)

@Serializable
data class DirectImageGenerationResponse(
    val jobId: String,
    val success: Boolean,
    val message: String? = null
)
