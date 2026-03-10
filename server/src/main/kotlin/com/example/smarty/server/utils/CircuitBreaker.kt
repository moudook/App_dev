package com.example.smarty.server.utils

import kotlinx.coroutines.delay

/**
 * Circuit Breaker pattern implementation.
 * 
 * Single Responsibility: Only handles circuit breaker logic.
 * DRY: Replaces repeated retry/fallback logic across services.
 * Global State: Tracks failure counts and circuit state.
 * 
 * Usage:
 * ```
 * val circuitBreaker = CircuitBreaker(failureThreshold = 5)
 * 
 * try {
 *     val result = circuitBreaker.execute {
 *         callExternalService()
 *     }
 * } catch (e: CircuitOpenException) {
 *     // Use fallback
 * }
 * ```
 */
class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val resetTimeoutMs: Long = 60_000,
    private val halfOpenMaxCalls: Int = 3
) {
    
    @Volatile
    private var failureCount: Long = 0
    
    @Volatile
    private var successCount: Long = 0
    
    @Volatile
    private var lastFailureTime: Long = 0
    
    @Volatile
    private var state: CircuitState = CircuitState.CLOSED
    
    @Volatile
    private var halfOpenCallCount: Int = 0
    
    enum class CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
    
    /**
     * Execute a block with circuit breaker protection.
     */
    suspend fun <T> execute(block: suspend () -> T): T {
        val currentState = getState()
        
        when (currentState) {
            CircuitState.OPEN -> {
                if (shouldTryReset()) {
                    state = CircuitState.HALF_OPEN
                    halfOpenCallCount = 0
                } else {
                    throw CircuitOpenException("Circuit breaker is open. Try again in ${resetTimeoutMs}ms")
                }
            }
            CircuitState.HALF_OPEN -> {
                if (halfOpenCallCount >= halfOpenMaxCalls) {
                    throw CircuitOpenException("Circuit breaker is half-open. Max calls reached.")
                }
                halfOpenCallCount++
            }
            CircuitState.CLOSED -> {
                // Allow request
            }
        }
        
        return try {
            val result = block()
            onSuccess()
            result
        } catch (e: Exception) {
            onFailure()
            throw e
        }
    }
    
    /**
     * Execute with fallback if circuit is open.
     */
    suspend fun <T> executeWithFallback(
        block: suspend () -> T,
        fallback: suspend () -> T
    ): T {
        return try {
            execute(block)
        } catch (e: CircuitOpenException) {
            fallback()
        } catch (e: Exception) {
            fallback()
        }
    }
    
    /**
     * Get the current circuit state.
     */
    fun getState(): CircuitState {
        return state
    }
    
    /**
     * Get circuit breaker statistics.
     */
    fun getStats(): Map<String, Any> = mapOf(
        "state" to state.name,
        "failure_count" to failureCount,
        "success_count" to successCount,
        "last_failure_time" to lastFailureTime,
        "half_open_calls" to halfOpenCallCount
    )
    
    /**
     * Reset the circuit breaker to closed state.
     */
    fun reset() {
        failureCount = 0
        successCount = 0
        lastFailureTime = 0
        state = CircuitState.CLOSED
        halfOpenCallCount = 0
    }
    
    private fun onSuccess() {
        failureCount = 0
        successCount++
        
        if (state == CircuitState.HALF_OPEN) {
            if (successCount >= halfOpenMaxCalls) {
                state = CircuitState.CLOSED
            }
        } else {
            state = CircuitState.CLOSED
        }
    }
    
    private fun onFailure() {
        failureCount++
        lastFailureTime = System.currentTimeMillis()
        
        if (failureCount >= failureThreshold) {
            state = CircuitState.OPEN
        }
    }
    
    private fun shouldTryReset(): Boolean {
        return System.currentTimeMillis() - lastFailureTime >= resetTimeoutMs
    }
}

/**
 * Exception thrown when circuit breaker is open.
 */
class CircuitOpenException(message: String) : Exception(message)

/**
 * Create a circuit breaker with default settings.
 */
fun circuitBreaker(
    failureThreshold: Int = 5,
    resetTimeoutMs: Long = 60_000
): CircuitBreaker {
    return CircuitBreaker(failureThreshold, resetTimeoutMs)
}
