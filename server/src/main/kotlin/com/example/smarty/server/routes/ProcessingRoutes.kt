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
                val user = call.firebaseUser()
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    return@post
                }

                try {
                    val request = call.receive<DirectImageGenerationRequest>()
                    call.application.log.info("Direct image generation requested by user: ${user.userId} with prompt: ${request.prompt}")

                    val kreaTool = com.example.smarty.server.tools.KreaImageTool()
                    val jobId = kreaTool.generateImage(request.prompt, request.aspectRatio)

                    val dataSource = DatabaseFactory.getDataSource()
                    if (dataSource != null) {
                        val imageRepo = GeneratedImageRepository(dataSource)
                        imageRepo.create(
                            userId = user.userId,
                            sessionId = null,
                            prompt = request.prompt,
                            kreaJobId = jobId
                        )
                    }

                    call.respond(HttpStatusCode.OK, DirectImageGenerationResponse(jobId = jobId, success = true))
                } catch (e: IllegalStateException) {
                    // KREA_API_KEY not configured - return descriptive error
                    call.application.log.error("Direct image generation failed: KREA_API_KEY not configured")
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        DirectImageGenerationResponse(
                            jobId = "",
                            success = false,
                            message = "Image generation service is not configured. KREA_API_KEY is missing from server environment."
                        )
                    )
                } catch (e: Exception) {
                    call.application.log.error("Direct image generation failed", e)
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
