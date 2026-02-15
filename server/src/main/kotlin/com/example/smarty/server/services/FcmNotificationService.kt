package com.example.smarty.server.services

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
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
    private val dataSource: DataSource
) {
    private val logger = LoggerFactory.getLogger(FcmNotificationService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val httpClient = HttpClient {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            this.register(io.ktor.http.ContentType.Application.Json, io.ktor.serialization.kotlinx.json.KotlinxSerializationConverter(json))
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
        data: Map<String, String> = emptyMap()
    ): Boolean = withContext(Dispatchers.IO) {
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
     * Send notification to a specific FCM token.
     */
    private suspend fun sendToFcmToken(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>
    ): Boolean {
        return try {
            // Use HTTP v1 API if project ID is available, otherwise legacy API
            if (!projectId.isNullOrBlank()) {
                sendV1Message(token, title, body, data)
            } else {
                sendLegacyMessage(token, title, body, data)
            }
        } catch (e: Exception) {
            logger.error("Failed to send to token ${token.take(10)}...: ${e.message}")
            false
        }
    }

    /**
     * Send using FCM HTTP v1 API (recommended).
     */
    private suspend fun sendV1Message(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>
    ): Boolean {
        val message = FcmV1Message(
            message = FcmV1Message.Message(
                token = token,
                notification = FcmV1Message.Notification(
                    title = title,
                    body = body
                ),
                data = data,
                android = FcmV1Message.AndroidConfig(
                    priority = "high",
                    notification = FcmV1Message.AndroidNotification(
                        channel_id = "digests",
                        priority = "PRIORITY_DEFAULT"
                    )
                )
            )
        )

        val response = httpClient.post(getV1Url(projectId!!)) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $serverKey")
            setBody(message)
        }

        return response.status.isSuccess()
    }

    /**
     * Send using FCM Legacy HTTP API.
     */
    private suspend fun sendLegacyMessage(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>
    ): Boolean {
        val message = FcmLegacyMessage(
            to = token,
            notification = FcmLegacyMessage.Notification(
                title = title,
                body = body
            ),
            data = data,
            priority = "high"
        )

        val response = httpClient.post(legacyUrl) {
            contentType(ContentType.Application.Json)
            header("Authorization", "key=$serverKey")
            setBody(message)
        }

        return response.status.isSuccess()
    }

    /**
     * Get FCM tokens for a user from database.
     */
    private suspend fun getUserFcmTokens(userId: String): List<String> = withContext(Dispatchers.IO) {
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
        val notification: Notification,
        val data: Map<String, String>? = null,
        val priority: String = "high"
    ) {
        @Serializable
        data class Notification(
            val title: String,
            val body: String
        )
    }

    @Serializable
    data class FcmV1Message(
        val message: Message
    ) {
        @Serializable
        data class Message(
            val token: String,
            val notification: Notification? = null,
            val data: Map<String, String>? = null,
            val android: AndroidConfig? = null
        )

        @Serializable
        data class Notification(
            val title: String,
            val body: String
        )

        @Serializable
        data class AndroidConfig(
            val priority: String = "high",
            val notification: AndroidNotification? = null
        )

        @Serializable
        data class AndroidNotification(
            val channel_id: String = "default",
            val priority: String = "PRIORITY_DEFAULT"
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
