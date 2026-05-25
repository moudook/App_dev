package com.example.smarty.server.routes

import com.example.smarty.server.services.GoogleDriveService
import com.example.smarty.server.services.GroqWhisperService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class UploadUrlRequest(
    val fileName: String,
    val mimeType: String,
)

@Serializable
data class UploadUrlResponse(
    val uploadUrl: String,
    val success: Boolean = true
)

@Serializable
data class DownloadUrlResponse(
    val downloadUrl: String,
    val success: Boolean = true
)

fun Application.configureFileRoutes(
    googleDriveService: GoogleDriveService,
    groqWhisperService: GroqWhisperService
) {
    routing {
        authenticate("auth-jwt") {
            route("/files") {
                
                post("/upload-url") {
                    val request = try {
                        call.receive<UploadUrlRequest>()
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request format"))
                        return@post
                    }

                    try {
                        val url = googleDriveService.generateUploadUrl(request.fileName, request.mimeType)
                        if (url != null) {
                            call.respond(HttpStatusCode.OK, UploadUrlResponse(url))
                        } else {
                            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to generate upload URL"))
                        }
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "External service unavailable: ${e.message}"))
                    }
                }

                get("/download-url/{fileId}") {
                    val fileId = call.parameters["fileId"] ?: return@get call.respond(
                        HttpStatusCode.BadRequest, mapOf("error" to "Missing fileId")
                    )

                    try {
                        val url = googleDriveService.generateDownloadUrl(fileId)
                        if (url != null) {
                            call.respond(HttpStatusCode.OK, DownloadUrlResponse(url))
                        } else {
                            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to generate download URL"))
                        }
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "External service unavailable: ${e.message}"))
                    }
                }

                post("/transcribe") {
                    // For short audio files (<25MB), upload directly to Ktor and forward to Groq
                    val multipart = call.receiveMultipart()
                    var fileBytes: ByteArray? = null
                    var fileName = "audio.m4a"

                    multipart.forEachPart { part ->
                        if (part is io.ktor.http.content.PartData.FileItem) {
                            fileName = part.originalFileName ?: "audio.m4a"
                            fileBytes = part.streamProvider().readBytes()
                        }
                        part.dispose()
                    }

                    if (fileBytes == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing file data"))
                        return@post
                    }

                    if (fileBytes!!.size > 25 * 1024 * 1024) {
                        call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "File exceeds 25MB limit for transcription"))
                        return@post
                    }

                    try {
                        val text = groqWhisperService.transcribeAudio(fileBytes!!, fileName)
                        if (text != null) {
                            call.respond(HttpStatusCode.OK, mapOf("text" to text))
                        } else {
                            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Transcription failed"))
                        }
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Transcription service unavailable: ${e.message}"))
                    }
                }
            }
        }
    }
}
