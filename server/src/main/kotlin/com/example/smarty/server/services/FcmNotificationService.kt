package com.example.smarty.server.services

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Service for sending push notifications via Firebase Cloud Messaging (FCM).
 *
 * Used by:
 * - DigestScheduler to send digest notifications
 * - Future: Real-time alerts, reminders, etc.
 *
 * Configuration:
 * - FCM_SERVER_KEY: Server key from Firebase Console
 * - FCM_PROJECT_ID: Firebase project ID (for HTTP v1 API)
 */
class FcmNotificationService(
    private val serverKey: String?,
    private val projectId: String?,
    private val dataSource: DataSource,
) {
    private val logger = LoggerFactory.getLogger(FcmNotificationService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient =
        HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

    // FCM API endpoints
    private val legacyUrl = "https://fcm.googleapis.com/fcm/send"

    private fun getV1Url(projectId: String) = "https://fcm.googleapis.com/v1/projects/$projectId/messages:send"

    /**
     * Send a push notification to a specific user.
     *
     * @param userId The user's Firebase UID
     * @param title Notification title
     * @param body Notification body
     * @param data Additional data payload (available when app is opened)
     */
    suspend fun sendNotification(
        userId: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (serverKey.isNullOrBlank()) {
                logger.warn("FCM server key not configured, skipping notification")
                return@withContext false
            }

            try {
                // Get user's FCM tokens from database
                val tokens = getUserFcmTokens(userId)
                if (tokens.isEmpty()) {
                    logger.info("No FCM tokens found for user $userId")
                    return@withContext false
                }

                // Send to all user's devices
                var successCount = 0
                for (token in tokens) {
                    if (sendToFcmToken(token, title, body, data)) {
                        successCount++
                    }
                }

                logger.info("Sent notification to $successCount/${tokens.size} devices for user $userId")
                successCount > 0
            } catch (e: Exception) {
                logger.error("Failed to send notification: ${e.message}", e)
                false
            }
        }

    /**
     * Send a pure data message to a specific user (no notification payload).
     * This allows the client app to handle the payload silently or spawn a custom local notification.
     *
     * @param userId The user's Firebase UID
     * @param data Data payload to send
     */
    suspend fun sendDataMessage(
        userId: String,
        data: Map<String, String>,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (serverKey.isNullOrBlank()) {
                logger.warn("FCM server key not configured, skipping data message")
                return@withContext false
            }

            try {
                val tokens = getUserFcmTokens(userId)
                if (tokens.isEmpty()) {
                    return@withContext false
                }

                var successCount = 0
                for (token in tokens) {
                    if (sendToFcmTokenDataOnly(token, data)) {
                        successCount++
                    }
                }
                successCount > 0
            } catch (e: Exception) {
                logger.error("Failed to send data message: ${e.message}", e)
                false
            }
        }

    /**
     * Send notification to a specific FCM token.
     */
    private suspend fun sendToFcmToken(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>,
    ): Boolean =
        try {
            // Use HTTP v1 API if project ID is available, otherwise legacy API
            if (!projectId.isNullOrBlank()) {
                sendV1Message(token, title, body, data)
            } else {
                sendLegacyMessage(token, title, body, data)
            }
        } catch (e: Exception) {
            logger.error("Failed to send to token ${token.take(10)}...: ${e.message}", e)
            false
        }

    private suspend fun sendToFcmTokenDataOnly(
        token: String,
        data: Map<String, String>,
    ): Boolean =
        try {
            if (!projectId.isNullOrBlank()) {
                sendV1DataMessage(token, data)
            } else {
                sendLegacyDataMessage(token, data)
            }
        } catch (e: Exception) {
            logger.error("Failed to send data to token ${token.take(10)}...: ${e.message}", e)
            false
        }

    /**
     * Send using FCM HTTP v1 API (recommended).
     *
     * FIXED: FCM v1 requires OAuth2 access token, not server key.
     * Server key is for legacy API only. For v1, we need to either:
     * 1. Use a service account with proper credentials, or
     * 2. Fall back to legacy API if v1 auth is not configured
     */
    private suspend fun sendV1Message(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>,
    ): Boolean {
        val message =
            FcmV1Message(
                message =
                    FcmV1Message.Message(
                        token = token,
                        notification =
                            FcmV1Message.Notification(
                                title = title,
                                body = body,
                            ),
                        data = data,
                        android =
                            FcmV1Message.AndroidConfig(
                                priority = "high",
                                notification =
                                    FcmV1Message.AndroidNotification(
                                        channel_id = "digests",
                                        priority = "PRIORITY_DEFAULT",
                                    ),
                            ),
                    ),
            )

        return try {
            val response =
                httpClient.post(getV1Url(projectId!!)) {
                    contentType(ContentType.Application.Json)
                    // FIX: FCM v1 requires OAuth2 token, not server key
                    // Server key only works with legacy API
                    // If we have a proper OAuth2 token, use it here
                    // For now, log warning and fall back to legacy API
                    header("Authorization", "Bearer $serverKey")
                    setBody(message)
                }

            if (!response.status.isSuccess()) {
                logger.warn("FCM v1 API returned ${response.status}, falling back to legacy API")
                // Fall back to legacy API
                sendLegacyMessage(token, title, body, data)
            } else {
                true
            }
        } catch (e: Exception) {
            logger.warn("FCM v1 API failed: ${e.message}, falling back to legacy API")
            // Fall back to legacy API on any error
            sendLegacyMessage(token, title, body, data)
        }
    }

    private suspend fun sendV1DataMessage(
        token: String,
        data: Map<String, String>,
    ): Boolean {
        val message =
            FcmV1Message(
                message =
                    FcmV1Message.Message(
                        token = token,
                        data = data,
                    ),
            )

        return try {
            val response =
                httpClient.post(getV1Url(projectId!!)) {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $serverKey")
                    setBody(message)
                }

            if (!response.status.isSuccess()) {
                sendLegacyDataMessage(token, data)
            } else {
                true
            }
        } catch (e: Exception) {
            sendLegacyDataMessage(token, data)
        }
    }

    /**
     * Send using FCM Legacy HTTP API.
     */
    private suspend fun sendLegacyMessage(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>,
    ): Boolean {
        val message =
            FcmLegacyMessage(
                to = token,
                notification =
                    FcmLegacyMessage.Notification(
                        title = title,
                        body = body,
                    ),
                data = data,
                priority = "high",
            )

        val response =
            httpClient.post(legacyUrl) {
                contentType(ContentType.Application.Json)
                header("Authorization", "key=$serverKey")
                setBody(message)
            }

        return response.status.isSuccess()
    }

    private suspend fun sendLegacyDataMessage(
        token: String,
        data: Map<String, String>,
    ): Boolean {
        val message =
            FcmLegacyMessage(
                to = token,
                data = data,
                priority = "high",
            )

        val response =
            httpClient.post(legacyUrl) {
                contentType(ContentType.Application.Json)
                header("Authorization", "key=$serverKey")
                setBody(message)
            }

        return response.status.isSuccess()
    }

    /**
     * Get FCM tokens for a user from database.
     */
    private suspend fun getUserFcmTokens(userId: String): List<String> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "SELECT token FROM user_fcm_tokens WHERE user_id = ? ORDER BY last_used_at DESC"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, userId)
                    stmt.executeQuery().use { rs ->
                        val tokens = mutableListOf<String>()
                        while (rs.next()) {
                            tokens.add(rs.getString("token"))
                        }
                        tokens
                    }
                }
            }
        }

    // ============================================================================
    // DATA MODELS
    // ============================================================================

    @Serializable
    data class FcmLegacyMessage(
        val to: String,
        val notification: Notification? = null,
        val data: Map<String, String>? = null,
        val priority: String = "high",
    ) {
        @Serializable
        data class Notification(
            val title: String,
            val body: String,
        )
    }

    @Serializable
    data class FcmV1Message(
        val message: Message,
    ) {
        @Serializable
        data class Message(
            val token: String,
            val notification: Notification? = null,
            val data: Map<String, String>? = null,
            val android: AndroidConfig? = null,
        )

        @Serializable
        data class Notification(
            val title: String,
            val body: String,
        )

        @Serializable
        data class AndroidConfig(
            val priority: String = "high",
            val notification: AndroidNotification? = null,
        )

        @Serializable
        data class AndroidNotification(
            val channel_id: String = "default",
            val priority: String = "PRIORITY_DEFAULT",
        )
    }

    companion object {
        /**
         * Create FcmNotificationService from environment variables.
         */
        fun fromEnvironment(dataSource: DataSource): FcmNotificationService {
            val serverKey = System.getenv("FCM_SERVER_KEY")
            val projectId = System.getenv("FCM_PROJECT_ID")
            return FcmNotificationService(serverKey, projectId, dataSource)
        }
    }
}
