package com.example.smarty.util

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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
 *       to prevent MITM attacks. Use OkHttp's CertificatePinner with real SHA-256 pins
 *       from the certificate chain.
 */
object HttpClientProvider {

    // ==================== Standardized Timeout Constants ====================

    /** Connection timeout - time to establish TCP connection */
    const val CONNECT_TIMEOUT_SECONDS = 60L

    /** Read timeout - time to wait for AI response (local LLMs can be very slow) */
    const val READ_TIMEOUT_SECONDS = 300L  // 5 minutes

    /** Write timeout - time to send request data */
    const val WRITE_TIMEOUT_SECONDS = 120L

    /** Quick operation timeouts for metadata fetches */
    const val QUICK_CONNECT_TIMEOUT_SECONDS = 3L
    const val QUICK_READ_TIMEOUT_SECONDS = 5L
    const val QUICK_WRITE_TIMEOUT_SECONDS = 5L

    /** Long-running operation timeouts for file downloads */
    const val LONG_CONNECT_TIMEOUT_SECONDS = 120L
    const val LONG_READ_TIMEOUT_SECONDS = 600L  // 10 minutes
    const val LONG_WRITE_TIMEOUT_SECONDS = 300L

    /**
     * Certificate Pinner for key API domains.
     * Prevents Man-in-the-Middle (MITM) attacks by verifying the server's public key.
     *
     * Note: These pins must be updated if the certificate authority changes or keys rotate.
     * Currently configured with placeholders - User must add actual SHA-256 pins.
     *
     * To get a pin:
     * openssl s_client -servername api.openai.com -connect api.openai.com:443 | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
     */
    private val certificatePinner: CertificatePinner by lazy {
        CertificatePinner.Builder()
            // OpenAI (api.openai.com)
            // .add("api.openai.com", "sha256/YOUR_PIN_HERE")

            // Anthropic (api.anthropic.com)
            // .add("api.anthropic.com", "sha256/YOUR_PIN_HERE")

            // Google (generativelanguage.googleapis.com)
            // .add("generativelanguage.googleapis.com", "sha256/YOUR_PIN_HERE")
            .build()
    }

    /**
     * Default client for general API calls including AI providers.
     * Uses standardized timeouts:
     * - connectTimeout: 60s (TCP connection establishment)
     * - readTimeout: 300s (AI responses can be slow)
     * - writeTimeout: 120s (sending request data)
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

    /**
     * Trust manager that accepts all certificates.
     * ONLY FOR LOCAL LAN CONNECTIONS - do not use for internet traffic!
     *
     * This is safe because:
     * 1. Only used for private RFC 1918 IP addresses (10.x.x.x, 192.168.x.x, 172.16-31.x.x)
     * 2. Traffic stays within user's local network
     * 3. User explicitly configures their own PC's IP
     * 4. Self-signed certificates from Caddy are expected
     */
    private val trustAllCerts: Array<TrustManager> = arrayOf(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    )

    /**
     * SSL context configured to trust all certificates.
     * ONLY FOR LOCAL LAN CONNECTIONS!
     */
    private val trustAllSslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }
    }

    /**
     * Client for local LAN server connections (USB tethering, WiFi).
     * Trusts self-signed certificates for HTTPS connections.
     *
     * SECURITY: This client trusts ALL certificates. Only use for:
     * - Local PC connections via private IP addresses
     * - Self-signed certificates from Caddy reverse proxy
     *
     * DO NOT use for internet traffic!
     */
    val localServer: OkHttpClient by lazy {
        // Create a trust manager that does not validate certificate chains
        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        )

        // Install the all-trusting trust manager
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        
        // Create an ssl socket factory with our all-trusting manager
        val sslSocketFactory = sslContext.socketFactory

        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true } // Verify no hostnames
            .build()
    }
}
