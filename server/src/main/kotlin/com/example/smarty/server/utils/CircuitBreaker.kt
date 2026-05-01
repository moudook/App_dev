class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val resetTimeoutMs: Long = 60_000,
    private val halfOpenMaxCalls: Int = 3,
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

    // FIXED: Added lock for atomic state transitions to prevent race conditions
    private val lock = Any()

    enum class CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN,
    }

    class CircuitOpenException(message: String) : Exception(message)

    /**
     * Execute a block with circuit breaker protection.
     * 
     * FIXED: Uses synchronized lock for atomic state check-and-update operations
     * to prevent race conditions in concurrent environments.
     */
    suspend fun <T> execute(block: suspend () -> T): T {
        val (currentState, shouldProceed) = synchronized(lock) {
            val cs = state
            val proceed = when (cs) {
                CircuitState.OPEN -> {
                    if (shouldTryReset()) {
                        state = CircuitState.HALF_OPEN
                        halfOpenCallCount = 0
                        true
                    } else {
                        false
                    }
                }
                CircuitState.HALF_OPEN -> {
                    if (halfOpenCallCount >= halfOpenMaxCalls) {
                        false
                    } else {
                        halfOpenCallCount++
                        true
                    }
                }
                CircuitState.CLOSED -> true
            }
            cs to proceed
        }

        if (!shouldProceed) {
            throw CircuitOpenException("Circuit breaker is $currentState. Try again in ${resetTimeoutMs}ms")
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
        fallback: suspend () -> T,
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
        return synchronized(lock) { state }
    }

    /**
     * Get circuit breaker statistics.
     */
    fun getStats(): Map<String, Any> = synchronized(lock) {
        mapOf(
            "state" to state.name,
            "failure_count" to failureCount,
            "success_count" to successCount,
            "last_failure_time" to lastFailureTime,
            "half_open_calls" to halfOpenCallCount,
        )
    }

    /**
     * Reset the circuit breaker to closed state.
     */
    fun reset() {
        synchronized(lock) {
            failureCount = 0
            successCount = 0
            lastFailureTime = 0
            state = CircuitState.CLOSED
            halfOpenCallCount = 0
        }
    }

    private fun onSuccess() {
        synchronized(lock) {
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
    }

    private fun onFailure() {
        synchronized(lock) {
            failureCount++
            lastFailureTime = System.currentTimeMillis()

            if (failureCount >= failureThreshold) {
                state = CircuitState.OPEN
            }
        }
    }

    private fun shouldTryReset(): Boolean {
        return System.currentTimeMillis() - lastFailureTime >= resetTimeoutMs
    }
}