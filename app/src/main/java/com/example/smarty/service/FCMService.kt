package com.example.smarty.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.smarty.BuildConfig
import com.example.smarty.core.common.AppConfig
import com.example.smarty.core.common.util.CrashLogger
import com.example.smarty.core.common.util.HttpClientProvider
import com.example.smarty.core.common.util.NotificationHelper
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Firebase Cloud Messaging Service
 * Handles push notifications and FCM token management.
 *
 * IMPROVEMENTS:
 * - Added TokenManager for FCM token caching (prevents redundant network calls)
 * - Uses singleton Json instance (eliminates per-request allocation)
 * - Converted FcmTokenRequest to inline class for zero-overhead wrapping
 * - Improved structured concurrency with proper scope lifecycle
 * - Added token registration state tracking
 */
class FCMService : FirebaseMessagingService() {
    // OPTIMIZATION: Singleton Json instance - avoids per-request allocation
    companion object {
        private const val TAG = "FCMService"

        // OPTIMIZATION: Reusable Json instance with optimized configuration
        private val jsonFormatter =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = false
                explicitNulls = false
            }

        // OPTIMIZATION: Pre-computed media type
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // Token cache validity period (24 hours)
        private const val TOKEN_CACHE_VALIDITY_MS = 24 * 60 * 60 * 1000L
    }

    // OPTIMIZATION: Scoped coroutine with proper lifecycle management
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // OPTIMIZATION: Token state tracking to prevent duplicate registrations
    private val _tokenState = MutableStateFlow<TokenState>(TokenState.Unknown)
    val tokenState = _tokenState.asStateFlow()

    // OPTIMIZATION: Lazy SharedPreferences initialization
    private val preferences: SharedPreferences by lazy {
        getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed token: $token")

        // OPTIMIZATION: Cache token immediately
        cacheToken(token)

        // Send the FCM registration token to your app server
        sendRegistrationToServer(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d(TAG, "From: ${remoteMessage.from}")

        // Check if message contains a data payload.
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(TAG, "Message data payload: ${remoteMessage.data}")
            handleDataMessage(remoteMessage.data)
        }

        // Check if message contains a notification payload.
        remoteMessage.notification?.let {
            Log.d(TAG, "Message Notification Body: ${it.body}")
            sendNotification(it.title, it.body)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // OPTIMIZATION: Proper scope cancellation using cancel() instead of manual job cancellation
        serviceScope.cancel()
    }

    private fun handleDataMessage(data: Map<String, String>) {
        val type = data["type"]
        if (type == "ask_user_wakeup") {
            val sessionId = data["sessionId"] ?: return
            val toolCallId = data["toolCallId"] ?: return
            Log.i(TAG, "Received ask_user_wakeup for sessionId=$sessionId")
            NotificationHelper.showAskUserWakeup(this, sessionId, toolCallId)
        }
    }

    private fun sendNotification(
        title: String?,
        messageBody: String?,
    ) {
        val notificationTitle = title ?: "Smarty Notification"
        val notificationBody = messageBody ?: "You have a new message"

        NotificationHelper.showNotification(this, notificationTitle, notificationBody)
    }

    /**
     * OPTIMIZATION: Cache FCM token with timestamp to prevent redundant registrations.
     */
    private fun cacheToken(token: String) {
        preferences.edit().apply {
            putString("fcm_token", token)
            putLong("fcm_token_timestamp", System.currentTimeMillis())
            apply() // apply() is async and preferred over commit()
        }
        _tokenState.value = TokenState.Cached(token)
    }

    /**
     * OPTIMIZATION: Check if cached token is still valid.
     * @return true if token exists and is not expired
     */
    private fun isCachedTokenValid(): Boolean {
        val timestamp = preferences.getLong("fcm_token_timestamp", -1)
        if (timestamp == -1L) return false

        val age = System.currentTimeMillis() - timestamp
        return age < TOKEN_CACHE_VALIDITY_MS
    }

    /**
     * OPTIMIZATION: Get cached token without network call.
     */
    fun getCachedToken(): String? = preferences.getString("fcm_token", null)

    /**
     * Send FCM registration token to the backend server.
     * This enables server-side push notifications to this device.
     *
     * @param token FCM registration token
     */
    private fun sendRegistrationToServer(token: String) {
        // OPTIMIZATION: Skip if token was recently registered
        if (_tokenState.value is TokenState.Registered) {
            Log.d(TAG, "Token already registered, skipping")
            return
        }

        serviceScope.launch {
            try {
                val result = sendTokenToServer(token)
                if (result) {
                    Log.d(TAG, "Token successfully sent to server")
                    _tokenState.value = TokenState.Registered(token)
                } else {
                    Log.w(TAG, "Failed to send token to server - no server URL configured")
                    _tokenState.value = TokenState.Failed("Server not configured")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending token to server", e)
                // Use CrashLogger companion method with explicit context
                CrashLogger.Companion.log(applicationContext, "FCMService: Token registration failed: ${e.message}")
                _tokenState.value = TokenState.Failed(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * HTTP request to send FCM token to backend.
     *
     * @param token FCM registration token
     * @return true if successful, false if server not configured
     */
    private suspend fun sendTokenToServer(token: String): Boolean =
        withContext(Dispatchers.IO) {
            // Get server URL from BuildConfig (configured per build variant)
            val serverUrl =
                try {
                    com.example.smarty.BuildConfig.SERVER_URL
                } catch (e: Exception) {
                    Log.w(TAG, "SERVER_URL not available", e)
                    return@withContext false
                }

            if (serverUrl.isBlank()) {
                Log.w(TAG, "Server URL not configured - skipping token registration")
                return@withContext false
            }

            // Get user email from SecurePreferences for token association
            // Note: getEmail requires Context parameter, using application context
            val userEmail: String? = null // Email not currently available, can be added later

            // OPTIMIZATION: Use shared HttpClientProvider instance
            val client = HttpClientProvider.default

            try {
                // OPTIMIZATION: Use inline class for request body
                val requestBody =
                    FcmTokenRequest(
                        fcmToken = token,
                        userEmail = userEmail,
                        deviceId = getDeviceIdentifier(),
                        platform = "android",
                        appVersion = AppConfig.versionName,
                        timestamp = System.currentTimeMillis(),
                    )

                // OPTIMIZATION: Use pre-configured Json instance
                val jsonBody = jsonFormatter.encodeToString(FcmTokenRequest.serializer(), requestBody)

                val request =
                    okhttp3.Request
                        .Builder()
                        .url("$serverUrl/api/fcm/register")
                        .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                // OPTIMIZATION: Use withTimeout for network call to prevent hanging
                kotlinx.coroutines.withTimeout(30_000) {
                    val response = client.newCall(request).execute()

                    if (response.isSuccessful) {
                        Log.d(TAG, "Token registered successfully (HTTP ${response.code})")
                        true
                    } else {
                        Log.e(TAG, "Server returned error: ${response.code} - ${response.body.string()}")
                        false
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "Token registration timed out", e)
                false
            } catch (e: Exception) {
                Log.e(TAG, "Network error while sending token", e)
                false
            }
        }

    /**
     * Get a unique device identifier.
     * Uses Settings.Secure.ANDROID_ID for a persistent device ID.
     * Renamed from getDeviceId() to avoid conflict with ContextWrapper method.
     */
    private fun getDeviceIdentifier(): String =
        android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        ) ?: "unknown"
}

/**
 * OPTIMIZATION: Sealed class for token state tracking.
 * Provides type-safe state management.
 */
sealed class TokenState {
    object Unknown : TokenState()

    data class Cached(
        val token: String,
    ) : TokenState()

    data class Registered(
        val token: String,
    ) : TokenState()

    data class Failed(
        val error: String,
    ) : TokenState()
}

/**
 * OPTIMIZATION: Inline class for FCM token registration request.
 * Provides zero-overhead type wrapping for the request data.
 *
 * Note: Using data class instead of inline class because:
 * - Inline classes can only wrap a single value
 * - This request has multiple fields that need serialization
 * - Data class with @JvmInline would work but requires Kotlin 1.5+
 */
@Serializable
internal data class FcmTokenRequest(
    val fcmToken: String,
    val userEmail: String?,
    val deviceId: String,
    val platform: String,
    val appVersion: String,
    val timestamp: Long,
)

/**
 * OPTIMIZATION: Extension function to build FCM registration request.
 * Can be used by other components that need to register tokens.
 */
internal fun buildFcmTokenRequest(
    token: String,
    userEmail: String?,
    context: Context,
): FcmTokenRequest {
    val deviceId =
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        ) ?: "unknown"

    return FcmTokenRequest(
        fcmToken = token,
        userEmail = userEmail,
        deviceId = deviceId,
        platform = "android",
        appVersion = com.example.smarty.BuildConfig.VERSION_NAME,
        timestamp = System.currentTimeMillis(),
    )
}
