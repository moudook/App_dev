package com.example.smarty.util.retry

import android.util.Log
import kotlinx.coroutines.delay

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
                val delayMs = initialDelayMs * attempt
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
}
