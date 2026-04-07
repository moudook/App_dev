package com.example.smarty.server.utils

import kotlinx.coroutines.delay

/**
 * Retry Policy with exponential backoff.
 *
 * Single Responsibility: Only handles retry logic.
 * DRY: Replaces repeated retry patterns across services.
 *
 * Usage:
 * ```
 * // Simple retry
 * val result = withRetry {
 *     callExternalService()
 * }
 *
 * // Retry with custom settings
 * val result = withRetry(
 *     maxRetries = 5,
 *     initialDelayMs = 1000,
 *     maxDelayMs = 10000
 * ) {
 *     callExternalService()
 * }
 *
 * // Retry with specific exceptions
 * val result = withRetry(
 *     retryOn = listOf(IOException::class)
 * ) {
 *     callExternalService()
 * }
 * ```
 */

/**
 * Execute a block with retry logic.
 */
suspend fun <T> withRetry(
    maxRetries: Int = 3,
    initialDelayMs: Long = 500,
    maxDelayMs: Long = 5000,
    factor: Double = 2.0,
    retryOn: List<Class<out Throwable>> = emptyList(),
    block: suspend () -> T,
): T {
    var lastException: Throwable? = null

    repeat(maxRetries + 1) { attempt ->
        try {
            return block()
        } catch (e: Throwable) {
            lastException = e

            // Check if we should retry this exception type
            if (retryOn.isNotEmpty()) {
                val shouldRetry =
                    retryOn.any { clazz ->
                        clazz.isInstance(e)
                    }
                if (!shouldRetry) {
                    throw e
                }
            }

            // Don't retry if we've exhausted retries
            if (attempt == maxRetries) {
                throw e
            }

            // Calculate delay with exponential backoff
            val delayMs =
                minOf(
                    initialDelayMs * (factor.toLong() shl attempt),
                    maxDelayMs,
                )

            delay(delayMs)
        }
    }

    throw lastException ?: IllegalStateException("Retry failed")
}

/**
 * Execute a block with retry logic and fallback.
 */
suspend fun <T> withRetryAndFallback(
    maxRetries: Int = 3,
    initialDelayMs: Long = 500,
    maxDelayMs: Long = 5000,
    fallback: suspend () -> T,
    block: suspend () -> T,
): T {
    return try {
        withRetry(maxRetries, initialDelayMs, maxDelayMs, block = block)
    } catch (e: Exception) {
        fallback()
    }
}

/**
 * Retry configuration builder.
 */
class RetryConfig {
    var maxRetries: Int = 3
    var initialDelayMs: Long = 500
    var maxDelayMs: Long = 5000
    var factor: Double = 2.0
    var retryOn: List<Class<out Throwable>> = emptyList()

    fun build(): RetryPolicy =
        RetryPolicy(
            maxRetries,
            initialDelayMs,
            maxDelayMs,
            factor,
            retryOn,
        )
}

/**
 * Retry policy that can be reused.
 */
class RetryPolicy(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 500,
    val maxDelayMs: Long = 5000,
    val factor: Double = 2.0,
    val retryOn: List<Class<out Throwable>> = emptyList(),
) {
    /**
     * Execute a block with this retry policy.
     */
    suspend fun <T> execute(block: suspend () -> T): T {
        return withRetry(
            maxRetries,
            initialDelayMs,
            maxDelayMs,
            factor,
            retryOn,
            block,
        )
    }

    /**
     * Execute with fallback.
     */
    suspend fun <T> executeWithFallback(
        block: suspend () -> T,
        fallback: suspend () -> T,
    ): T {
        return withRetryAndFallback(
            maxRetries,
            initialDelayMs,
            maxDelayMs,
            fallback,
            block,
        )
    }
}

/**
 * Create a retry policy with builder syntax.
 */
fun retryPolicy(block: RetryConfig.() -> Unit): RetryPolicy {
    val config = RetryConfig()
    config.block()
    return config.build()
}

/**
 * Simple retry with default settings.
 */
suspend fun <T> retry(
    times: Int = 3,
    block: suspend () -> T,
): T {
    return withRetry(maxRetries = times, block = block)
}
