package com.example.smarty.util.retry

import android.util.Log
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Generic retry executor with exponential backoff.
 * Eliminates duplicate retry logic across the codebase.
 *
 * Usage:
 * ```
 * val result = RetryExecutor.withRetry(3, 1000L) { apiCall() }
 * ```
 */
object RetryExecutor {

    private const val TAG = "RetryExecutor"
    private const val MAX_DELAY_MS = 30_000L
    private const val JITTER_FACTOR = 0.15 // +/- 15% jitter

    /**
     * Execute an action with retry logic and exponential backoff.
     *
     * @param maxRetries Maximum number of attempts
     * @param initialDelayMs Initial delay between retries (multiplied by attempt number)
     * @param successCheck Lambda to determine if result is successful (default: not null)
     * @param action The suspend action to execute
     * @return The result if successful, null if all retries failed
     */
    suspend fun <T> withRetry(
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000L,
        successCheck: (T?) -> Boolean = { it != null },
        action: suspend () -> T?
    ): T? {
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                val result = action()
                if (successCheck(result)) {
                    return result
                }
                Log.w(TAG, "Attempt $attempt: Result not successful")
            } catch (e: Exception) {
                lastException = e
                Log.e(TAG, "Attempt $attempt exception: ${e.message}")
            }

            if (attempt < maxRetries) {
                val delayMs = calculateExponentialBackoff(initialDelayMs, attempt)
                Log.d(TAG, "Retrying in ${delayMs}ms...")
                delay(delayMs)
            }
        }

        lastException?.let { Log.e(TAG, "All $maxRetries retries failed", it) }
        return null
    }

    /**
     * Execute an action with retry logic for non-null string results.
     * Convenience method for string-returning APIs.
     *
     * @param maxRetries Maximum number of attempts
     * @param initialDelayMs Initial delay between retries
     * @param action The suspend action to execute
     * @return The result string if successful, null if all retries failed
     */
    suspend fun withStringRetry(
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000L,
        action: suspend () -> String?
    ): String? {
        return withRetry(
            maxRetries = maxRetries,
            initialDelayMs = initialDelayMs,
            successCheck = { !it.isNullOrBlank() },
            action = action
        )
    }

    /**
     * Calculate exponential backoff delay with jitter.
     *
     * @param baseDelayMs Base delay in milliseconds
     * @param attempt Current attempt number (1-indexed)
     * @return Delay in milliseconds with exponential backoff and jitter, capped at MAX_DELAY_MS
     */
    private fun calculateExponentialBackoff(baseDelayMs: Long, attempt: Int): Long {
        // Exponential backoff: baseDelay * 2^(attempt-1)
        // attempt-1 because attempt is 1-indexed; first retry should use baseDelay * 2^0 = baseDelay
        val exponentialDelay = baseDelayMs * 2.0.pow(attempt - 1)

        // Cap at maximum delay
        val cappedDelay = min(exponentialDelay, MAX_DELAY_MS.toDouble())

        // Add jitter: +/- JITTER_FACTOR of the delay
        val jitterRange = cappedDelay * JITTER_FACTOR
        val jitter = Random.nextDouble(-jitterRange, jitterRange)

        return (cappedDelay + jitter).toLong().coerceAtLeast(0L)
    }
}
