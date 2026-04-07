package com.example.smarty.core.common.util

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Utility for retrying operations with exponential backoff.
 */
object RetryHelper {
    private const val TAG = "RetryHelper"

    suspend fun <T> withRetry(
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000,
        maxDelayMs: Long = 10000,
        factor: Double = 2.0,
        retryOn: (Exception) -> Boolean = { isTransientError(it) },
        block: suspend () -> T,
    ): T {
        var currentDelay = initialDelayMs
        var lastException: Exception? = null

        repeat(maxRetries + 1) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e

                if (attempt == maxRetries || !retryOn(e)) {
                    Log.e(TAG, "Failed after ${attempt + 1} attempts", e)
                    throw e
                }

                Log.w(TAG, "Attempt ${attempt + 1} failed, retrying in ${currentDelay}ms: ${e.message}")
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelayMs)
            }
        }

        throw lastException ?: IllegalStateException("Retry failed without exception")
    }

    fun isTransientError(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: ""
        return message.contains("timeout") ||
            message.contains("connection") ||
            message.contains("socket") ||
            message.contains("502") ||
            message.contains("503") ||
            message.contains("504") ||
            message.contains("rate limit") ||
            message.contains("too many requests")
    }
}
