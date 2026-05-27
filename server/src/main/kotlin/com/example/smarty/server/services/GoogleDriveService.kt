package com.example.smarty.server.services

import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.contentType
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.FileInputStream
import java.io.InputStream

class GoogleDriveService(
    private val httpClient: HttpClient,
    private val serviceAccountJsonPath: String?,
) {
    private val logger = LoggerFactory.getLogger(GoogleDriveService::class.java)

    // Using Google Drive API v3
    private val scope = "https://www.googleapis.com/auth/drive.file"

    private var cachedToken: String? = null
    private var tokenExpiration: Long = 0

    @Serializable
    private data class DriveFileMetadata(
        val name: String,
        val mimeType: String,
        val parents: List<String>? = null,
    )

    private fun getAccessToken(): String? {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiration) {
            return cachedToken
        }

        try {
            val stream: InputStream =
                if (serviceAccountJsonPath != null && java.io.File(serviceAccountJsonPath).exists()) {
                    FileInputStream(serviceAccountJsonPath)
                } else {
                    // Try from classpath or env var if file doesn't exist
                    val envJson = System.getenv("GOOGLE_APPLICATION_CREDENTIALS_JSON")
                    if (envJson != null) {
                        envJson.byteInputStream()
                    } else {
                        javaClass.getResourceAsStream("/service-account.json") ?: return null
                    }
                }

            val credentials = GoogleCredentials.fromStream(stream).createScoped(listOf(scope))
            credentials.refreshIfExpired()

            cachedToken = credentials.accessToken.tokenValue
            // Token usually valid for 1 hour, expire 5 mins early
            tokenExpiration = System.currentTimeMillis() + (55 * 60 * 1000)

            return cachedToken
        } catch (e: Exception) {
            logger.error("Failed to get Google Drive access token", e)
            return null
        }
    }

    /**
     * Generates a Resumable Upload URL that the Android client can use to PUT the file directly.
     * This acts as our "Signed Upload URL" for Google Drive.
     */
    suspend fun generateUploadUrl(
        fileName: String,
        mimeType: String,
        folderId: String? = null,
    ): String? {
        val token = getAccessToken() ?: return null

        val metadata =
            DriveFileMetadata(
                name = fileName,
                mimeType = mimeType,
                parents = if (folderId != null) listOf(folderId) else null,
            )

        try {
            val response =
                httpClient.post("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(metadata))
                }

            if (response.status.isSuccess()) {
                val uploadUrl = response.headers[HttpHeaders.Location]
                if (uploadUrl != null) {
                    return uploadUrl
                } else {
                    logger.error("Location header missing in Drive resumable upload response")
                    return null
                }
            } else {
                logger.error("Failed to create resumable upload: ${response.status} - ${response.body<String>()}")
                return null
            }
        } catch (e: Exception) {
            logger.error("Exception generating upload URL", e)
            return null
        }
    }

    /**
     * Gives a direct download URL that includes the access token for temporary access.
     */
    fun generateDownloadUrl(fileId: String): String? {
        val token = getAccessToken() ?: return null
        return "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&access_token=$token"
    }
}
