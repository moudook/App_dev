package com.example.smarty.server.factory

import com.example.smarty.server.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Centralized HTTP Client Factory.
 *
 * Single Responsibility: Only handles HTTP client creation.
 * DRY: Replaces ad-hoc HttpClient creation in 10+ files.
 * Global State: Manages shared client configuration.
 *
 * Usage:
 * ```
 * val client = HttpClientFactory.createDefault()
 * val shortTimeoutClient = HttpClientFactory.createShortTimeout()
 * ```
 */
object HttpClientFactory {
    /**
     * Shared JSON configuration for all clients.
     */
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            isLenient = true
        }

    /**
     * Create a default HTTP client with standard timeouts.
     * Suitable for most operations including LLM calls.
     */
    fun createDefault(): HttpClient =
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }

            install(HttpTimeout) {
                requestTimeoutMillis = AppConfig.httpTimeoutMs
                connectTimeoutMillis = AppConfig.connectionTimeoutMs
                socketTimeoutMillis = AppConfig.httpTimeoutMs
            }

            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 3)
                exponentialDelay()
            }

            engine {
                config {
                    connectTimeout(AppConfig.connectionTimeoutMs, TimeUnit.MILLISECONDS)
                    readTimeout(AppConfig.httpTimeoutMs, TimeUnit.MILLISECONDS)
                    writeTimeout(AppConfig.httpTimeoutMs, TimeUnit.MILLISECONDS)

                    // Retry on connection failure
                    retryOnConnectionFailure(true)
                }
            }
        }

    /**
     * Create a short-timeout HTTP client for quick operations.
     * Suitable for health checks, simple API calls.
     */
    fun createShortTimeout(): HttpClient =
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 10_000
            }

            engine {
                config {
                    connectTimeout(5_000, TimeUnit.MILLISECONDS)
                    readTimeout(10_000, TimeUnit.MILLISECONDS)
                }
            }
        }

    /**
     * Create a long-timeout HTTP client for streaming operations.
     * Suitable for SSE streaming, large file uploads.
     */
    fun createLongTimeout(): HttpClient =
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 600_000 // 10 minutes
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 600_000
            }

            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 5)
                exponentialDelay(baseDelayMs = 1000)
            }

            engine {
                config {
                    connectTimeout(30_000, TimeUnit.MILLISECONDS)
                    readTimeout(600_000, TimeUnit.MILLISECONDS)
                    writeTimeout(600_000, TimeUnit.MILLISECONDS)

                    // Retry on connection failure for streaming
                    retryOnConnectionFailure(true)
                }
            }
        }

    /**
     * Create an HTTP client with custom timeout settings.
     */
    fun create(
        requestTimeoutMs: Long,
        connectTimeoutMs: Long,
    ): HttpClient =
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }

            install(HttpTimeout) {
                requestTimeoutMillis = requestTimeoutMs
                connectTimeoutMillis = connectTimeoutMs
                socketTimeoutMillis = requestTimeoutMs
            }

            engine {
                config {
                    connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                    readTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
                }
            }
        }
}
