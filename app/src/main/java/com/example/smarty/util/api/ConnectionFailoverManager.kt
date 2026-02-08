package com.example.smarty.util.api

import android.util.Log
import com.example.smarty.data.local.AIConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * API error categories for intelligent failover decisions.
 */
enum class ApiErrorCategory {
    /** Authentication failed (invalid token, expired) - don't retry with same token */
    AUTH_ERROR,
    /** Rate limit exceeded - temporary, retry after cooldown */
    RATE_LIMIT,
    /** Network/connection error - retry with exponential backoff */
    NETWORK_ERROR,
    /** Server error (5xx) - retry with backoff */
    SERVER_ERROR,
    /** Model not available/invalid - try different model or provider */
    MODEL_ERROR,
    /** Request too large (context window) - skip this provider */
    CONTEXT_OVERFLOW,
    /** Unknown error - generic retry */
    UNKNOWN
}

/**
 * Connection health status for circuit breaker pattern.
 */
enum class ConnectionHealthStatus {
    /** Connection is healthy and accepting requests */
    HEALTHY,
    /** Connection has some failures, still trying */
    DEGRADED,
    /** Connection is temporarily disabled (circuit open) */
    CIRCUIT_OPEN,
    /** Connection is in recovery phase (half-open circuit) */
    RECOVERING
}

/**
 * Tracks the health state of a single connection.
 *
 * BUG FIX (TECH-002): Made mutable fields @Volatile to ensure
 * visibility across threads. Without @Volatile, threads may see
 * stale cached values due to CPU caching/hoisting.
 */
data class ConnectionHealthState(
    val connection: AIConnection,
    @Volatile var status: ConnectionHealthStatus = ConnectionHealthStatus.HEALTHY,
    val consecutiveFailures: AtomicInteger = AtomicInteger(0),
    @Volatile var lastFailureTime: Long = 0,
    @Volatile var lastSuccessTime: Long = 0,
    @Volatile var lastErrorCategory: ApiErrorCategory? = null,
    @Volatile var totalFailures: Int = 0,
    @Volatile var totalSuccesses: Int = 0,
    @Volatile var circuitOpenUntil: Long = 0
)

/**
 * Robust Connection Failover Manager with Circuit Breaker pattern.
 *
 * Features:
 * - Tracks connection health across all API calls
 * - Implements circuit breaker to prevent hammering failed APIs
 * - Smart error categorization for intelligent retry decisions
 * - Automatic recovery after cooldown period
 * - Thread-safe for concurrent access
 *
 * Circuit Breaker States:
 * - HEALTHY: Normal operation, all requests pass through
 * - DEGRADED: Some failures detected, still accepting requests
 * - CIRCUIT_OPEN: Too many failures, requests blocked temporarily
 * - RECOVERING: Testing if connection is back online
 *
 * Usage:
 * ```
 * val manager = ConnectionFailoverManager.getInstance()
 * val connections = manager.getHealthyConnections(allConnections)
 *
 * try {
 *     val result = apiCall()
 *     manager.recordSuccess(connection)
 * } catch (e: Exception) {
 *     manager.recordFailure(connection, e)
 *     // Try next connection...
 * }
 * ```
 */
class ConnectionFailoverManager private constructor() {

    companion object {
        private const val TAG = "ConnectionFailover"

        // Circuit breaker thresholds
        private const val FAILURE_THRESHOLD_DEGRADED = 2
        private const val FAILURE_THRESHOLD_OPEN = 5

        // Cooldown periods (in milliseconds)
        private const val BASE_COOLDOWN_MS = 30_000L        // 30 seconds base
        private const val MAX_COOLDOWN_MS = 300_000L        // 5 minutes max
        private const val RATE_LIMIT_COOLDOWN_MS = 60_000L  // 1 minute for rate limits
        private const val AUTH_ERROR_COOLDOWN_MS = 600_000L // 10 minutes for auth errors

        // Connection-specific rate limit cooldowns
        private val CONNECTION_RATE_LIMIT_COOLDOWNS = mapOf(
            AIConnection.LOCAL_PC to 5_000L       // Local PC - short cooldown for local testing
        )

        /**
         * Parse retry-after duration from error message.
         * Handles formats like "retry in 30s", "retry in 30.5 seconds", "Please retry in 56.497434996s"
         */
        fun parseRetryAfterFromMessage(message: String): Long? {
            val lowerMessage = message.lowercase()
            
            // Pattern: "retry in X.Xs" or "retry in X seconds"
            val secondsPatterns = listOf(
                Regex("""retry.*?(\d+\.?\d*)\s*s(?:econds?)?""", RegexOption.IGNORE_CASE),
                Regex("""(\d+\.?\d*)\s*seconds?""", RegexOption.IGNORE_CASE)
            )
            
            for (pattern in secondsPatterns) {
                pattern.find(lowerMessage)?.let { match ->
                    val seconds = match.groupValues[1].toDoubleOrNull()
                    if (seconds != null && seconds > 0) {
                        return (seconds * 1000).toLong().coerceIn(1000L, MAX_COOLDOWN_MS)
                    }
                }
            }
            
            // Pattern: "X minutes"
            val minutesPattern = Regex("""(\d+)\s*minutes?""", RegexOption.IGNORE_CASE)
            minutesPattern.find(lowerMessage)?.let { match ->
                val minutes = match.groupValues[1].toLongOrNull()
                if (minutes != null && minutes > 0) {
                    return (minutes * 60 * 1000).coerceIn(1000L, MAX_COOLDOWN_MS)
                }
            }
            
            return null
        }

        // Recovery settings
        private const val RECOVERY_SUCCESS_THRESHOLD = 2  // Successes needed to fully recover

        @Volatile
        private var instance: ConnectionFailoverManager? = null

        fun getInstance(): ConnectionFailoverManager {
            return instance ?: synchronized(this) {
                instance ?: ConnectionFailoverManager().also { instance = it }
            }
        }
    }

    // Thread-safe map of connection health states
    private val healthStates = ConcurrentHashMap<AIConnection, ConnectionHealthState>()

    /**
     * Get health state for a connection, creating if needed.
     */
    private fun getOrCreateState(connection: AIConnection): ConnectionHealthState {
        return healthStates.getOrPut(connection) { ConnectionHealthState(connection) }
    }

    /**
     * Categorize an exception into an API error category.
     */
    fun categorizeError(exception: Exception): ApiErrorCategory {
        val message = exception.message?.lowercase() ?: ""

        return when {
            // Authentication errors (including 400 bad request - often means invalid key/params)
            message.contains("400") ||
            message.contains("401") ||
            message.contains("403") ||
            message.contains("bad request") ||
            message.contains("unauthorized") ||
            message.contains("invalid connection key") ||
            message.contains("authentication") -> ApiErrorCategory.AUTH_ERROR

            // Rate limiting
            message.contains("429") ||
            message.contains("rate limit") ||
            message.contains("too many requests") ||
            message.contains("quota") -> ApiErrorCategory.RATE_LIMIT

            // Network errors
            message.contains("timeout") ||
            message.contains("connection") ||
            message.contains("network") ||
            message.contains("socket") ||
            message.contains("unreachable") ||
            message.contains("econnrefused") -> ApiErrorCategory.NETWORK_ERROR

            // Server errors
            message.contains("500") ||
            message.contains("502") ||
            message.contains("503") ||
            message.contains("504") ||
            message.contains("internal server error") ||
            message.contains("service unavailable") -> ApiErrorCategory.SERVER_ERROR

            // Model errors
            message.contains("model") ||
            message.contains("not found") ||
            message.contains("not available") ||
            message.contains("invalid model") -> ApiErrorCategory.MODEL_ERROR

            // Context/token overflow
            message.contains("context") ||
            message.contains("token") ||
            message.contains("too long") ||
            message.contains("maximum") -> ApiErrorCategory.CONTEXT_OVERFLOW

            else -> ApiErrorCategory.UNKNOWN
        }
    }

    /**
     * Categorize error from HTTP status code.
     */
    fun categorizeHttpError(statusCode: Int): ApiErrorCategory {
        return when (statusCode) {
            400 -> ApiErrorCategory.AUTH_ERROR
            401, 403 -> ApiErrorCategory.AUTH_ERROR
            429 -> ApiErrorCategory.RATE_LIMIT
            in 500..599 -> ApiErrorCategory.SERVER_ERROR
            404 -> ApiErrorCategory.MODEL_ERROR
            413 -> ApiErrorCategory.CONTEXT_OVERFLOW
            else -> ApiErrorCategory.UNKNOWN
        }
    }

    /**
     * Record a successful API call.
     */
    fun recordSuccess(connection: AIConnection) {
        val state = getOrCreateState(connection)
        val now = System.currentTimeMillis()

        synchronized(state) {
            state.lastSuccessTime = now
            state.totalSuccesses++

            when (state.status) {
                ConnectionHealthStatus.RECOVERING -> {
                    val newValue = state.consecutiveFailures.decrementAndGet()
                    if (newValue <= 0) {
                        state.consecutiveFailures.set(0)
                        state.status = ConnectionHealthStatus.HEALTHY
                        Log.i(TAG, " $connection fully recovered, circuit CLOSED")
                    }
                }
                ConnectionHealthStatus.DEGRADED -> {
                    val newValue = state.consecutiveFailures.decrementAndGet()
                    if (newValue <= 0) {
                        state.consecutiveFailures.set(0)
                        state.status = ConnectionHealthStatus.HEALTHY
                        Log.d(TAG, " $connection back to HEALTHY")
                    }
                }
                else -> {
                    state.consecutiveFailures.set(0)
                    state.status = ConnectionHealthStatus.HEALTHY
                }
            }
        }
    }

    /**
     * Record a failed API call.
     */
    fun recordFailure(connection: AIConnection, exception: Exception) {
        val category = categorizeError(exception)
        recordFailure(connection, category)
    }

    /**
     * Record a failed API call with known error category.
     */
    fun recordFailure(connection: AIConnection, category: ApiErrorCategory) {
        val state = getOrCreateState(connection)
        val now = System.currentTimeMillis()

        synchronized(state) {
            val failureCount = state.consecutiveFailures.incrementAndGet()
            state.totalFailures++
            state.lastFailureTime = now
            state.lastErrorCategory = category

            // Calculate cooldown based on error type
            val cooldown = calculateCooldown(category, failureCount)

            // Update status based on failure count
            state.status = when {
                failureCount >= FAILURE_THRESHOLD_OPEN -> {
                    state.circuitOpenUntil = now + cooldown
                    Log.w(TAG, " $connection circuit OPEN until ${cooldown/1000}s (${category.name})")
                    ConnectionHealthStatus.CIRCUIT_OPEN
                }
                failureCount >= FAILURE_THRESHOLD_DEGRADED -> {
                    Log.w(TAG, " $connection DEGRADED ($failureCount failures)")
                    ConnectionHealthStatus.DEGRADED
                }
                else -> state.status
            }
        }
    }

    /**
     * Calculate cooldown period based on error type and failure count.
     */
    private fun calculateCooldown(category: ApiErrorCategory, failureCount: Int): Long {
        val baseCooldown = when (category) {
            ApiErrorCategory.RATE_LIMIT -> RATE_LIMIT_COOLDOWN_MS
            ApiErrorCategory.AUTH_ERROR -> AUTH_ERROR_COOLDOWN_MS
            ApiErrorCategory.CONTEXT_OVERFLOW -> BASE_COOLDOWN_MS / 2
            else -> BASE_COOLDOWN_MS
        }

        // Exponential backoff capped at max
        val multiplier = min(failureCount, 5)
        return min(baseCooldown * multiplier, MAX_COOLDOWN_MS)
    }

    /**
     * Check if a connection is currently available for requests.
     */
    fun isConnectionAvailable(connection: AIConnection): Boolean {
        val state = healthStates[connection] ?: return true
        val now = System.currentTimeMillis()

        return synchronized(state) {
            when (state.status) {
                ConnectionHealthStatus.HEALTHY,
                ConnectionHealthStatus.DEGRADED -> true

                ConnectionHealthStatus.RECOVERING -> true

                ConnectionHealthStatus.CIRCUIT_OPEN -> {
                    if (now > state.circuitOpenUntil) {
                        state.status = ConnectionHealthStatus.RECOVERING
                        Log.i(TAG, "↻ $connection entering RECOVERY mode")
                        true
                    } else {
                        val remaining = (state.circuitOpenUntil - now) / 1000
                        Log.d(TAG, " $connection circuit open for ${remaining}s more")
                        false
                    }
                }
            }
        }
    }

    /**
     * Get list of healthy connections.
     */
    fun getHealthyConnections(connections: List<AIConnection>): List<AIConnection> {
        return connections.filter { isConnectionAvailable(it) }
    }

    /**
     * Reset health state for a specific connection.
     */
    fun resetConnection(connection: AIConnection) {
        healthStates.remove(connection)
        Log.i(TAG, "Reset health state for $connection")
    }

    /**
     * Reset all health states.
     */
    fun resetAll() {
        healthStates.clear()
        Log.i(TAG, "Reset all connection health states")
    }
}
