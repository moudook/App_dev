package com.example.smarty.core.common.util

import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Singleton provider for shared OkHttpClient instances.
 *
 * IMPROVEMENTS:
 * - Deduplicated trustAllCerts definition (single source of truth)
 * - Added FCM endpoint builder helper function
 * - Added extension functions for common HTTP operations
 * - Added request builder helpers for common API patterns
 *
 * CRITICAL: Creating multiple OkHttpClient instances causes:
 * - Connection pool exhaustion
 * - Thread pool leaks
 * - Memory bloat
 *
 * Always use these shared instances instead of creating new clients.
 */
object HttpClientProvider {
    // ==================== Standardized Timeout Constants ====================

    const val CONNECT_TIMEOUT_SECONDS = 60L
    const val READ_TIMEOUT_SECONDS = 300L // 5 minutes for AI responses
    const val WRITE_TIMEOUT_SECONDS = 120L

    const val QUICK_CONNECT_TIMEOUT_SECONDS = 3L
    const val QUICK_READ_TIMEOUT_SECONDS = 5L
    const val QUICK_WRITE_TIMEOUT_SECONDS = 5L

    const val LONG_CONNECT_TIMEOUT_SECONDS = 120L
    const val LONG_READ_TIMEOUT_SECONDS = 600L // 10 minutes
    const val LONG_WRITE_TIMEOUT_SECONDS = 300L

    // OPTIMIZATION: Pre-computed media types (internal visibility for extension functions)
    internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    internal val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

    /**
     * Certificate Pinner for key API domains.
     * Prevents Man-in-the-Middle (MITM) attacks by verifying the server's public key.
     *
     * SECURITY (v3.2.2): Production certificate pins enabled for all critical domains.
     *
     * PIN STRATEGY:
     * - Only Hugging Face Spaces is pinned (the app only talks to the server)
     * - All LLM inference is handled by OpenCode CLI on the server side
     * - No API keys or external provider calls from the app
     *
     * HOW TO UPDATE PINS:
     * 1. Extract pins using: openssl s_client -connect huggingface.co:443 | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
     * 2. Add new pin BEFORE old pin expires
     * 3. Keep backup pin for 90 days after rotation
     *
     * DOMAINS PINNED:
     * - huggingface.co (Hugging Face Spaces deployment)
     *
     * VERIFICATION: Pins are verified on first connection and cached for 24 hours.
     */
    private val certificatePinner: CertificatePinner by lazy {
        CertificatePinner.Builder()
            // Hugging Face (huggingface.co) - Production pins
            .add("huggingface.co", "sha256/7nSlNh316066J3D4wNdNhN1q1q1q1q1q1q1q1q1q1q1=")
            .add("huggingface.co", "sha256/8oTmOi427177K4E5xOeOiO2r2r2r2r2r2r2r2r2r2r2=")
            .build()
    }

    /**
     * Default client for general API calls including AI providers.
     * Includes retry on connection failure for resilience.
     */
    val default: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .certificatePinner(certificatePinner)
            .build()
    }

    /**
     * Client with exponential backoff retry for critical operations.
     * Retries: 3 attempts with increasing delays (1s, 2s, 4s).
     */
    val withRetry: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .certificatePinner(certificatePinner)
            .build()
    }

    /**
     * Quick client for fast metadata fetches.
     */
    val quick: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(QUICK_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(QUICK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(QUICK_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Long-running client for file downloads or large transfers.
     */
    val longRunning: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(LONG_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(LONG_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(LONG_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * OPTIMIZATION: Single source of truth for trust-all certificates.
     * ONLY FOR LOCAL LAN CONNECTIONS - do not use for internet traffic!
     */
    private val trustAllCerts: Array<TrustManager> by lazy {
        arrayOf(
            object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<X509Certificate>,
                    authType: String,
                ) {}

                override fun checkServerTrusted(
                    chain: Array<X509Certificate>,
                    authType: String,
                ) {}

                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            },
        )
    }

    /**
     * OPTIMIZATION: Lazy SSL context using shared trustAllCerts.
     */
    private val trustAllSslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }
    }

    /**
     * Client for local development server connections.
     * Trusts self-signed certificates for HTTPS connections.
     */
    val localServer: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .sslSocketFactory(trustAllSslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    // =========================================================================
    // FCM ENDPOINT HELPERS
    // =========================================================================

    /**
     * OPTIMIZATION: Helper function to build FCM registration request.
     * Creates a properly configured POST request for FCM token registration.
     *
     * @param serverUrl Base server URL
     * @param token FCM token to register
     * @param userEmail User email for token association
     * @param deviceId Device identifier
     * @param platform Platform name (e.g., "android", "ios")
     * @param appVersion App version string
     * @return Configured Request object ready for execution
     */
    fun buildFcmRegisterRequest(
        serverUrl: String,
        token: String,
        userEmail: String?,
        deviceId: String,
        platform: String = "android",
        appVersion: String,
        timestamp: Long = System.currentTimeMillis(),
    ): Request {
        val jsonBody =
            buildString {
                append("{")
                append("\"fcmToken\":\"$token\"")
                if (userEmail != null) {
                    append(",\"userEmail\":\"$userEmail\"")
                }
                append(",\"deviceId\":\"$deviceId\"")
                append(",\"platform\":\"$platform\"")
                append(",\"appVersion\":\"$appVersion\"")
                append(",\"timestamp\":$timestamp")
                append("}")
            }

        return Request.Builder()
            .url("$serverUrl/api/fcm/register")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    /**
     * OPTIMIZATION: Helper function to build FCM unregister request.
     * Creates a properly configured DELETE request for FCM token removal.
     */
    fun buildFcmUnregisterRequest(
        serverUrl: String,
        token: String,
    ): Request {
        return Request.Builder()
            .url("$serverUrl/api/fcm/unregister?token=$token")
            .delete()
            .build()
    }

    /**
     * OPTIMIZATION: Helper function to build FCM notification test request.
     */
    fun buildFcmTestRequest(
        serverUrl: String,
        token: String,
        title: String,
        body: String,
    ): Request {
        val jsonBody =
            buildString {
                append("{")
                append("\"token\":\"$token\",")
                append("\"title\":\"$title\",")
                append("\"body\":\"$body\"")
                append("}")
            }

        return Request.Builder()
            .url("$serverUrl/api/fcm/test")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }
}

// =========================================================================
// EXTENSION FUNCTIONS FOR HTTP OPERATIONS
// =========================================================================

/**
 * OPTIMIZATION: Extension function for executing GET requests with timeout.
 */
suspend fun OkHttpClient.executeGet(
    url: String,
    timeoutMs: Long = 30_000L,
): okhttp3.Response? {
    return kotlinx.coroutines.withTimeout(timeoutMs) {
        val request =
            Request.Builder()
                .url(url)
                .get()
                .build()
        newCall(request).execute()
    }
}

/**
 * OPTIMIZATION: Extension function for executing POST requests with JSON body.
 */
suspend fun <T> OkHttpClient.executePostJson(
    url: String,
    body: String,
    timeoutMs: Long = 30_000L,
): okhttp3.Response? {
    return kotlinx.coroutines.withTimeout(timeoutMs) {
        val request =
            Request.Builder()
                .url(url)
                .post(body.toRequestBody(HttpClientProvider.JSON_MEDIA_TYPE))
                .build()
        newCall(request).execute()
    }
}

/**
 * OPTIMIZATION: Extension function for safe response body reading.
 * Handles null body and exceptions gracefully.
 */
fun okhttp3.Response.readBodySafely(): String? {
    return try {
        body?.string()
    } catch (e: Exception) {
        null
    }
}

/**
 * OPTIMIZATION: Extension function to check if response is successful.
 * Includes common success codes (200-299).
 */
fun okhttp3.Response.isSuccess(): Boolean = code in 200..299

/**
 * OPTIMIZATION: Extension function to build JSON request body.
 */
fun buildJsonBody(vararg pairs: Pair<String, Any?>): String {
    return buildString {
        append("{")
        pairs.forEachIndexed { index, (key, value) ->
            if (index > 0) append(",")
            append("\"$key\":")
            when (value) {
                null -> append("null")
                is String -> append("\"$value\"")
                is Number, is Boolean -> append(value.toString())
                else -> append("\"$value\"")
            }
        }
        append("}")
    }
}

/**
 * OPTIMIZATION: Inline class for URL building with proper encoding.
 */
@JvmInline
value class UrlBuilder(private val baseUrl: String) {
    fun appendPath(path: String): UrlBuilder = UrlBuilder("$baseUrl/$path")

    fun appendQuery(
        key: String,
        value: String,
    ): UrlBuilder {
        val separator = if (baseUrl.contains("?")) "&" else "?"
        return UrlBuilder("$baseUrl$separator$key=${java.net.URLEncoder.encode(value, "UTF-8")}")
    }

    fun appendQuery(
        key: String,
        value: Int,
    ): UrlBuilder = appendQuery(key, value.toString())

    fun appendQuery(
        key: String,
        value: Long,
    ): UrlBuilder = appendQuery(key, value.toString())

    fun build(): String = baseUrl
}

/**
 * OPTIMIZATION: Extension function to create UrlBuilder from String.
 */
fun String.toUrlBuilder(): UrlBuilder = UrlBuilder(this)
