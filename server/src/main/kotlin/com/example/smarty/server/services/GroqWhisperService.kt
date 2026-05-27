package com.example.smarty.server.services

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class GroqWhisperService(
    private val httpClient: HttpClient,
    private val apiKey: String,
) {
    private val logger = LoggerFactory.getLogger(GroqWhisperService::class.java)

    @Serializable
    data class WhisperResponse(
        val text: String,
    )

    suspend fun transcribeAudio(
        fileBytes: ByteArray,
        filename: String,
    ): String? {
        logger.info("Transcribing audio file: $filename (${fileBytes.size} bytes)")
        try {
            val response: HttpResponse =
                httpClient.submitFormWithBinaryData(
                    url = "https://api.groq.com/openai/v1/audio/transcriptions",
                    formData =
                        formData {
                            append(
                                "file",
                                fileBytes,
                                Headers.build {
                                    append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                                    // Adjust ContentType based on file extension
                                    val ext = filename.substringAfterLast('.', "")
                                    val mime =
                                        when (ext.lowercase()) {
                                            "mp3" -> "audio/mpeg"
                                            "m4a" -> "audio/m4a"
                                            "wav" -> "audio/wav"
                                            "ogg" -> "audio/ogg"
                                            "flac" -> "audio/flac"
                                            "webm" -> "audio/webm"
                                            "mp4" -> "video/mp4"
                                            else -> "application/octet-stream"
                                        }
                                    append(HttpHeaders.ContentType, mime)
                                },
                            )
                            append("model", "whisper-large-v3")
                            append("response_format", "json")
                        },
                ) {
                    header("Authorization", "Bearer $apiKey")
                }

            if (response.status.isSuccess()) {
                val jsonText = response.bodyAsText()
                val json = Json { ignoreUnknownKeys = true }
                val whisperResponse = json.decodeFromString<WhisperResponse>(jsonText)
                return whisperResponse.text
            } else {
                logger.error("Groq API error: ${response.status} - ${response.bodyAsText()}")
                return null
            }
        } catch (e: Exception) {
            logger.error("Failed to transcribe audio", e)
            return null
        }
    }
}
