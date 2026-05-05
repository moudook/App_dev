package com.example.smarty.server.routes

import com.example.smarty.server.data.DatabaseFactory
import com.example.smarty.server.data.GeneratedImageRepository
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
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

fun sanitizeFileName(fileName: String): String? {
    var name = fileName.substringAfterLast(File.separatorChar).substringAfterLast('/').substringAfterLast('\\')
    name = name.replace("..", "").replace("..", "")
    name = name.trim('.', ' ', '\t', '\n', '\r')
    if (name.isEmpty()) return null
    if (!name.matches(Regex("^[a-zA-Z0-9._-]+$"))) return null
    return name
}

fun Application.configureProcessingRoutes() {
    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    val httpClient =
        HttpClient(OkHttp) {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json(json) }
        }

    val visionService = VisionService(httpClient)
    val fileProcessingService = FileProcessingService(visionService, httpClient)
    val contentAnalysisService = ContentAnalysisService(httpClient, visionService)

    routing {
        authenticate("firebase") {
            post("/analyze/content") {
                val user = call.firebaseUser()
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    return@post
                }
                try {
                    val request = call.receive<ContentAnalysisRequest>()
                    call.application.log.info("Content analysis request from user: ${user.userId}")
                    val attachments =
                        request.attachments?.map {
                            com.example.smarty.server.models.AttachmentInfo(it.fileName, it.fileType)
                        }
                    val result = contentAnalysisService.analyzeContent(request.content, attachments)
                    call.respond(HttpStatusCode.OK, result)
                } catch (e: Exception) {
                    call.application.log.error("Content analysis failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Analysis failed: ${e.message}"))
                }
            }

            post("/analyze/document") {
                val user = call.firebaseUser()
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    return@post
                }
                try {
                    val request = call.receive<DocumentAnalysisRequest>()
                    call.application.log.info("Document analysis request from user: ${user.userId}")
                    val result = contentAnalysisService.analyzeDocument(request.text, request.fileName, request.userContext)
                    call.respond(HttpStatusCode.OK, result)
                } catch (e: Exception) {
                    call.application.log.error("Document analysis failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Analysis failed: ${e.message}"))
                }
            }

            post("/process/image") {
                val user = call.firebaseUser()
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    return@post
                }
                try {
                    val request = call.receive<ImageProcessingRequest>()
                    call.application.log.info("Image processing request from user: ${user.userId}")
                    val result =
                        when (request.analysisType) {
                            "analyze" -> {
                                val analysis = visionService.analyzeImage(request.base64Image, request.mimeType ?: "image/png")
                                ImageProcessingResponse(analysis.description, "analysis", analysis.success, analysis.error)
                            }
                            else -> {
                                val ocr = visionService.performOcr(request.base64Image, request.mimeType ?: "image/png")
                                ImageProcessingResponse(ocr.extractedText, ocr.contentType, ocr.success, ocr.error)
                            }
                        }
                    call.respond(HttpStatusCode.OK, result)
                } catch (e: Exception) {
                    call.application.log.error("Image processing failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Processing failed: ${e.message}"))
                }
            }

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
                    val result = fileProcessingService.processPdf(pdfBytes, request.fileName, request.useOcr ?: true)
                    call.respond(HttpStatusCode.OK, result)
                } catch (e: Exception) {
                    call.application.log.error("PDF processing failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Processing failed: ${e.message}"))
                }
            }

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
                                fileName = part.originalFileName?.let { sanitizeFileName(it) }
                                contentType = part.contentType?.toString()
                                @Suppress("DEPRECATION")
                                fileBytes = part.streamProvider().use { it.readBytes() }
                            }
                            is PartData.FormItem -> {
                                if (part.name == "analysisType") analysisType = part.value
                            }
                            else -> {}
                        }
                        part.dispose()
                    }

                    if (fileBytes == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No file uploaded"))
                        return@post
                    }
                    if (fileName == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid filename"))
                        return@post
                    }
                    try {
                        com.example.smarty.server.utils.InputValidation.validateTitle(fileName)
                        if (analysisType !in listOf("content", "document", "ocr")) {
                            throw IllegalArgumentException("Invalid analysis type")
                        }
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid input: ${e.message}"))
                        return@post
                    }

                    call.application.log.info("File upload: $fileName ($contentType) from user: ${user.userId}")

                    val result: Any =
                        when {
                            contentType?.startsWith("application/pdf") == true -> {
                                val pdfResult = fileProcessingService.processPdf(fileBytes!!, fileName)
                                if (analysisType == "document") {
                                    contentAnalysisService.analyzeDocument(
                                        pdfResult.text,
                                        fileName,
                                    )
                                } else {
                                    pdfResult
                                }
                            }
                            contentType?.startsWith("image/") == true -> {
                                val base64 = Base64.getEncoder().encodeToString(fileBytes!!)
                                val ocrResult = visionService.performOcr(base64, contentType!!)
                                if (analysisType == "content") contentAnalysisService.analyzeContent(ocrResult.extractedText) else ocrResult
                            }
                            contentType?.startsWith("text/") == true -> {
                                contentAnalysisService.analyzeContent(String(fileBytes!!, Charsets.UTF_8))
                            }
                            else -> {
                                call.respond(
                                    HttpStatusCode.UnsupportedMediaType,
                                    mapOf("error" to "Unsupported content type: $contentType"),
                                )
                                return@post
                            }
                        }
                    call.respond(HttpStatusCode.OK, result)
                } catch (e: Exception) {
                    call.application.log.error("File upload processing failed", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Upload processing failed: ${e.message}"))
                }
            }

            post("/api/v1/image/direct") {
                val log = call.application.log
                val startTime = System.currentTimeMillis()
                val user = call.firebaseUser()
                if (user == null) {
                    log.warn(" /api/v1/image/direct - Unauthorized request (no Firebase user)")
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    return@post
                }
                try {
                    val request = call.receive<DirectImageGenerationRequest>()
                    log.info("═".repeat(60))
                    log.info(" /api/v1/image/direct - Request received")
                    log.info("   User ID: ${user.userId}")
                    log.info("   Prompt: ${request.prompt.take(80)}${if (request.prompt.length > 80) "..." else ""}")
                    log.info("   Aspect Ratio: ${request.aspectRatio}")
                    log.info("═".repeat(60))
                    log.info(" Creating KreaImageTool instance...")
                    val kreaTool = com.example.smarty.server.tools.KreaImageTool()
                    log.info(" Calling kreaTool.generateImage()...")
                    val jobId = kreaTool.generateImage(request.prompt, request.aspectRatio)
                    log.info(" Image generation triggered successfully! Job ID: $jobId")
                    val dataSource = DatabaseFactory.getDataSource()
                    var imageRepo: GeneratedImageRepository? = null
                    if (dataSource != null) {
                        try {
                            imageRepo = GeneratedImageRepository(dataSource)
                            imageRepo.create(user.userId, null, request.prompt, jobId)
                            log.info(" Job ID saved to database")
                        } catch (e: Exception) {
                            log.warn("️  Failed to save job ID to database: ${e.message}")
                        }
                    } else {
                        log.warn("️  Database not available - skipping job ID storage")
                    }
                    log.info("⏳ Waiting for Krea image generation to complete...")
                    val result = withContext(Dispatchers.IO) { kreaTool.waitForCompletion(jobId) }
                    val kreaImageUrl =
                        result.result?.urls?.firstOrNull()
                            ?: throw IllegalStateException("Image generation completed but no image URL was returned")
                    log.info(" Krea image generated: $kreaImageUrl")
                    var supabaseUrl: String? = null
                    try {
                        log.info(" Uploading image to Supabase Storage...")
                        supabaseUrl =
                            withContext(Dispatchers.IO) {
                                kreaTool.uploadToSupabase(kreaImageUrl, jobId, com.example.smarty.server.factory.SupabaseClientFactory.getImageBucketName())
                            }
                        if (supabaseUrl != null) {
                            log.info(" Image uploaded to Supabase: $supabaseUrl")
                        } else {
                            log.warn("️  Supabase upload returned null - will use Krea URL")
                        }
                    } catch (e: Exception) {
                        log.warn("️  Supabase upload failed, will use Krea URL: ${e.message}")
                    }
                    imageRepo?.let { repo ->
                        try {
                            repo.updateImageUrls(jobId, kreaImageUrl, supabaseUrl)
                            log.info(" Database updated with image URLs")
                        } catch (e: Exception) {
                            log.warn("️  Failed to update database with image URLs: ${e.message}")
                        }
                    }
                    val finalImageUrl = supabaseUrl ?: kreaImageUrl
                    val imageSource = if (supabaseUrl != null) "supabase" else "krea"
                    val elapsed = System.currentTimeMillis() - startTime
                    log.info(" /api/v1/image/direct - Completed in ${elapsed}ms")
                    log.info("   Final URL ($imageSource): $finalImageUrl")
                    log.info("═".repeat(60))
                    call.respond(
                        HttpStatusCode.OK,
                        ImageGenerationSuccessResponse("image", finalImageUrl, imageSource, request.prompt, jobId),
                    )
                } catch (e: IllegalStateException) {
                    val elapsed = System.currentTimeMillis() - startTime
                    log.error(" /api/v1/image/direct - FAILED after ${elapsed}ms")
                    log.error("   Error type: IllegalStateException")
                    log.error("   Cause: KREA_API_KEY not configured")
                    log.error("   Message: ${e.message}")
                    val callRef = call
                    withContext(Dispatchers.IO) {
                        callRef.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ImageGenerationSuccessResponse(
                                "error",
                                "",
                                "error",
                                "",
                                "",
                                "Image generation service is not configured. KREA_API_KEY is missing from server environment.",
                            ),
                        )
                    }
                } catch (e: Exception) {
                    val elapsed = System.currentTimeMillis() - startTime
                    log.error(" /api/v1/image/direct - FAILED after ${elapsed}ms")
                    log.error("   Error type: ${e::class.simpleName}")
                    log.error("   Message: ${e.message}")
                    log.error("   Stack trace: ${e.stackTraceToString().take(500)}")
                    val callRef = call
                    withContext(Dispatchers.IO) {
                        callRef.respond(
                            HttpStatusCode.InternalServerError,
                            ImageGenerationSuccessResponse("error", "", "error", "", "", e.message),
                        )
                    }
                }
            }
        }
    }
}

@Serializable data class ContentAnalysisRequest(val content: String, val attachments: List<AttachmentDto>? = null)

@Serializable data class AttachmentDto(val fileName: String, val fileType: String)

@Serializable data class DocumentAnalysisRequest(val text: String, val fileName: String? = null, val userContext: String? = null)

@Serializable data class ImageProcessingRequest(val base64Image: String, val mimeType: String? = null, val analysisType: String? = "ocr")

@Serializable data class PdfProcessingRequest(val base64Pdf: String, val fileName: String? = null, val useOcr: Boolean? = true)

@Serializable data class ImageProcessingResponse(val text: String, val contentType: String, val success: Boolean, val error: String? = null)

@Serializable data class DirectImageGenerationRequest(val prompt: String, val aspectRatio: String = "1:1")

@Serializable data class DirectImageGenerationResponse(val jobId: String, val success: Boolean, val message: String? = null)

@Serializable data class ImageGenerationSuccessResponse(val type: String, val url: String, val source: String, val prompt: String, val jobId: String, val error: String? = null)
