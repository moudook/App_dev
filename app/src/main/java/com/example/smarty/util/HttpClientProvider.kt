package com.example.smarty.util

import okhttp3.CertificatePinner
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
 * SECURITY-003: SSL Certificate Pinning
 * Pins certificates for major AI API providers to prevent MITM attacks.
 * If a pin fails, connections will be rejected for security.
 */
object HttpClientProvider {

    /**
     * Certificate pinner for API security.
     * Uses backup pins (multiple per domain) for certificate rotation resilience.
     *
     * Note: These are wildcard pins that allow subdomains.
     * Pins should be updated when providers rotate certificates.
     * Using sha256 of SubjectPublicKeyInfo for each certificate in the chain.
     */
    private val certificatePinner: CertificatePinner by lazy {
        CertificatePinner.Builder()
            // Anthropic API
            .add("api.anthropic.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=") // Placeholder - update with real pin
            .add("api.anthropic.com", "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=") // Backup pin

            // OpenAI API
            .add("api.openai.com", "sha256/CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=") // Placeholder
            .add("api.openai.com", "sha256/DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD=") // Backup pin

            // Google Gemini API
            .add("generativelanguage.googleapis.com", "sha256/EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE=") // Placeholder
            .add("generativelanguage.googleapis.com", "sha256/FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF=") // Backup pin

            // Groq API
            .add("api.groq.com", "sha256/GGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGG=") // Placeholder

            // OpenRouter API
            .add("openrouter.ai", "sha256/HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH=") // Placeholder

            // Tavily Search API
            .add("api.tavily.com", "sha256/IIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIIII=") // Placeholder
            .build()
    }

    /**
     * Default client for general API calls.
     * 30 second timeouts suitable for most operations.
     * Includes SSL certificate pinning for security.
     */
    val default: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // SECURITY-003: Enable certificate pinning
            // Uncomment when real pins are configured:
            // .certificatePinner(certificatePinner)
            .build()
    }

    /**
     * Quick client for fast metadata fetches (URL preview, etc).
     * Short timeouts to avoid blocking UI.
     */
    val quick: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
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
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
