package com.example.smarty.server.monitoring

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Centralized Error Tracking.
 *
 * Single Responsibility: Only handles error tracking and aggregation.
 * DRY: Replaces scattered error logging across services.
 * Global State: Single source of truth for error metrics.
 *
 * Usage:
 * ```
 * // Track an error
 * ErrorTracker.track("ChatService", exception, userId = "user123")
 *
 * // Get error stats
 * val stats = ErrorTracker.getErrorStats()
 * val recentErrors = ErrorTracker.getRecentErrors()
 * ```
 */
object ErrorTracker {
    /**
     * Error record for tracking.
     */
    data class ErrorRecord(
        val timestamp: Long,
        val component: String,
        val errorType: String,
        val message: String?,
        val userId: String?,
        val sessionId: String?,
    )

    // Error counts by type
    private val errorCounts = ConcurrentHashMap<String, AtomicLong>()

    // Error counts by component
    private val componentErrorCounts = ConcurrentHashMap<String, AtomicLong>()

    // Recent errors (kept in memory for debugging)
    private val recentErrors = ConcurrentLinkedDeque<ErrorRecord>()

    // Total error count
    private val totalErrorCount = AtomicLong(0)

    // Start time for tracking
    private val startTime = System.currentTimeMillis()

    /**
     * Track an error.
     */
    fun track(
        component: String,
        error: Throwable,
        userId: String? = null,
        sessionId: String? = null,
    ) {
        val errorType = error::class.simpleName ?: "Unknown"
        val message = error.message

        // Increment error count by type
        errorCounts.computeIfAbsent(errorType) { AtomicLong(0) }.incrementAndGet()

        // Increment error count by component
        componentErrorCounts.computeIfAbsent(component) { AtomicLong(0) }.incrementAndGet()

        // Increment total count
        totalErrorCount.incrementAndGet()

        // Add to recent errors
        val record =
            ErrorRecord(
                timestamp = System.currentTimeMillis(),
                component = component,
                errorType = errorType,
                message = message,
                userId = userId,
                sessionId = sessionId,
            )

        recentErrors.addFirst(record)

        // Keep only last 100 errors
        while (recentErrors.size > 100) {
            recentErrors.removeLast()
        }
    }

    /**
     * Get error statistics.
     */
    fun getErrorStats(): Map<String, Any> =
        mapOf(
            "total_errors" to totalErrorCount.get(),
            "errors_by_type" to errorCounts.mapValues { it.value.get() },
            "errors_by_component" to componentErrorCounts.mapValues { it.value.get() },
            "uptime_minutes" to ((System.currentTimeMillis() - startTime) / 60000),
            "recent_error_count" to recentErrors.size,
        )

    /**
     * Get recent errors.
     */
    fun getRecentErrors(limit: Int = 10): List<ErrorRecord> {
        return recentErrors.take(limit)
    }

    /**
     * Get errors by component.
     */
    fun getErrorsByComponent(component: String): List<ErrorRecord> {
        return recentErrors.filter { it.component == component }
    }

    /**
     * Get errors by type.
     */
    fun getErrorsByType(errorType: String): List<ErrorRecord> {
        return recentErrors.filter { it.errorType == errorType }
    }

    /**
     * Clear all tracked errors.
     */
    fun clear() {
        errorCounts.clear()
        componentErrorCounts.clear()
        recentErrors.clear()
        totalErrorCount.set(0)
    }

    /**
     * Get error rate (errors per minute).
     */
    fun getErrorRate(): Double {
        val uptimeMinutes = (System.currentTimeMillis() - startTime) / 60000.0
        return if (uptimeMinutes > 0) {
            totalErrorCount.get() / uptimeMinutes
        } else {
            0.0
        }
    }

    /**
     * Check if error rate exceeds threshold.
     */
    fun isErrorRateHigh(threshold: Double = 10.0): Boolean {
        return getErrorRate() > threshold
    }
}

/**
 * Extension function to track errors easily.
 */
fun Throwable.track(
    component: String,
    userId: String? = null,
    sessionId: String? = null,
) {
    ErrorTracker.track(component, this, userId, sessionId)
}

/**
 * Track errors in a try-catch block.
 */
suspend fun <T> trackErrors(
    component: String,
    userId: String? = null,
    sessionId: String? = null,
    block: suspend () -> T,
): T {
    return try {
        block()
    } catch (e: Exception) {
        e.track(component, userId, sessionId)
        throw e
    }
}
