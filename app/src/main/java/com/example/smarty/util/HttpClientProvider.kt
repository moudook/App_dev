package com.example.smarty.util

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Singleton provider for shared OkHttpClient instances.
 *
 * CRITICAL: Creating multiple OkHttpClient instances causes:
 * - Connection pool exhaustion
 * - Thread pool leaks
 * - Memory bloat
 *
 * Always use these shared instances instead of creating new clients.
 *
 * TODO: For production, consider implementing SSL certificate pinning for API providers
 *       (Anthropic, OpenAI, Google, Groq, OpenRouter, Tavily) to prevent MITM attacks.
 *       Use OkHttp's CertificatePinner with real SHA-256 pins from the certificate chain.
 */
object HttpClientProvider {

    // ==================== Standardized Timeout Constants ====================

    /** Connection timeout - time to establish TCP connection */
    const val CONNECT_TIMEOUT_SECONDS = 30L

    /** Read timeout - time to wait for AI response (AI can be slow) */
    const val READ_TIMEOUT_SECONDS = 90L

    /** Write timeout - time to send request data */
    const val WRITE_TIMEOUT_SECONDS = 60L

    /** Quick operation timeouts for metadata fetches */
    const val QUICK_CONNECT_TIMEOUT_SECONDS = 3L
    const val QUICK_READ_TIMEOUT_SECONDS = 5L
    const val QUICK_WRITE_TIMEOUT_SECONDS = 5L

    /** Long-running operation timeouts for file downloads */
    const val LONG_CONNECT_TIMEOUT_SECONDS = 60L
    const val LONG_READ_TIMEOUT_SECONDS = 120L
    const val LONG_WRITE_TIMEOUT_SECONDS = 120L

    /**
     * Default client for general API calls including AI providers.
     * Uses standardized timeouts:
     * - connectTimeout: 30s (TCP connection establishment)
     * - readTimeout: 90s (AI responses can be slow)
     * - writeTimeout: 60s (sending request data)
     */
    val default: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Quick client for fast metadata fetches (URL preview, etc).
     * Short timeouts to avoid blocking UI.
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
     * Extended timeouts for slow connections.
     */
    val longRunning: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(LONG_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(LONG_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(LONG_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
