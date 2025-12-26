package com.example.smarty.util.api

import android.util.Log
import com.example.smarty.data.local.AIProvider
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * API error categories for intelligent failover decisions.
 */
enum class ApiErrorCategory {
    /** Authentication failed (invalid key, expired) - don't retry with same key */
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
 * Provider health status for circuit breaker pattern.
 */
enum class ProviderHealthStatus {
    /** Provider is healthy and accepting requests */
    HEALTHY,
    /** Provider has some failures, still trying */
    DEGRADED,
    /** Provider is temporarily disabled (circuit open) */
    CIRCUIT_OPEN,
    /** Provider is in recovery phase (half-open circuit) */
    RECOVERING
}

/**
 * Tracks the health state of a single provider.
 *
 * BUG FIX (TECH-002): Made mutable fields @Volatile to ensure
 * visibility across threads. Without @Volatile, threads may see
 * stale cached values due to CPU caching/hoisting.
 */
data class ProviderHealthState(
    val provider: AIProvider,
    @Volatile var status: ProviderHealthStatus = ProviderHealthStatus.HEALTHY,
    @Volatile var consecutiveFailures: Int = 0,
    @Volatile var lastFailureTime: Long = 0,
    @Volatile var lastSuccessTime: Long = 0,
    @Volatile var lastErrorCategory: ApiErrorCategory? = null,
    @Volatile var totalFailures: Int = 0,
    @Volatile var totalSuccesses: Int = 0,
    @Volatile var circuitOpenUntil: Long = 0
)

/**
 * Robust Provider Failover Manager with Circuit Breaker pattern.
 *
 * Features:
 * - Tracks provider health across all API calls
 * - Implements circuit breaker to prevent hammering failed APIs
 * - Smart error categorization for intelligent retry decisions
 * - Automatic recovery after cooldown period
 * - Thread-safe for concurrent access
 *
 * Circuit Breaker States:
 * - HEALTHY: Normal operation, all requests pass through
 * - DEGRADED: Some failures detected, still accepting requests
 * - CIRCUIT_OPEN: Too many failures, requests blocked temporarily
 * - RECOVERING: Testing if provider is back online
 *
 * Usage:
 * ```
 * val manager = ProviderFailoverManager.getInstance()
 * val providers = manager.getHealthyProviders(allProviders)
 *
 * try {
 *     val result = apiCall()
 *     manager.recordSuccess(provider)
 * } catch (e: Exception) {
 *     manager.recordFailure(provider, e)
 *     // Try next provider...
 * }
 * ```
 */
class ProviderFailoverManager private constructor() {

    companion object {
        private const val TAG = "ProviderFailover"

        // Circuit breaker thresholds
        private const val FAILURE_THRESHOLD_DEGRADED = 2
        private const val FAILURE_THRESHOLD_OPEN = 5

        // Cooldown periods (in milliseconds)
        private const val BASE_COOLDOWN_MS = 30_000L        // 30 seconds base
        private const val MAX_COOLDOWN_MS = 300_000L        // 5 minutes max
        private const val RATE_LIMIT_COOLDOWN_MS = 60_000L  // 1 minute for rate limits
        private const val AUTH_ERROR_COOLDOWN_MS = 600_000L // 10 minutes for auth errors

        // Provider-specific rate limit cooldowns (based on API documentation)
        private val PROVIDER_RATE_LIMIT_COOLDOWNS = mapOf(
            AIProvider.GEMINI to 60_000L,      // Gemini typically suggests 30-60s retry
            AIProvider.GROQ to 60_000L,         // Groq uses retry-after header
            AIProvider.OPENAI to 60_000L,       // OpenAI standard rate limit
            AIProvider.ANTHROPIC to 60_000L,    // Anthropic standard rate limit
            AIProvider.CEREBRAS to 30_000L,     // Cerebras faster retry
            AIProvider.COHERE to 60_000L,       // Cohere standard
            AIProvider.DEEPSEEK to 60_000L,     // DeepSeek standard
            AIProvider.OPENROUTER to 60_000L,   // OpenRouter standard
            AIProvider.HUGGINGFACE to 120_000L  // HuggingFace free tier is slower
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
        private var instance: ProviderFailoverManager? = null

        fun getInstance(): ProviderFailoverManager {
            return instance ?: synchronized(this) {
                instance ?: ProviderFailoverManager().also { instance = it }
            }
        }
    }

    // Thread-safe map of provider health states
    private val healthStates = ConcurrentHashMap<AIProvider, ProviderHealthState>()

    // Track which key failed for multi-key scenarios
    private val failedKeys = ConcurrentHashMap<String, Long>() // key -> failure time

    /**
     * Get health state for a provider, creating if needed.
     */
    private fun getOrCreateState(provider: AIProvider): ProviderHealthState {
        return healthStates.getOrPut(provider) { ProviderHealthState(provider) }
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
            message.contains("invalid api key") ||
            message.contains("invalid_api_key") ||
            message.contains("authentication") -> ApiErrorCategory.AUTH_ERROR

            // Rate limiting
            message.contains("429") ||
            message.contains("rate limit") ||
            message.contains("rate_limit") ||
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
     * 400 = Bad Request - treat as key/request issue, try next key
     */
    fun categorizeHttpError(statusCode: Int): ApiErrorCategory {
        return when (statusCode) {
            400 -> ApiErrorCategory.AUTH_ERROR  // Bad request often means invalid key format/params
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
     * Resets failure count and potentially closes circuit.
     */
    fun recordSuccess(provider: AIProvider) {
        val state = getOrCreateState(provider)
        val now = System.currentTimeMillis()

        synchronized(state) {
            state.lastSuccessTime = now
            state.totalSuccesses++

            when (state.status) {
                ProviderHealthStatus.RECOVERING -> {
                    // Need multiple successes to fully recover
                    state.consecutiveFailures = maxOf(0, state.consecutiveFailures - 1)
                    if (state.consecutiveFailures == 0) {
                        state.status = ProviderHealthStatus.HEALTHY
                        Log.i(TAG, "✓ $provider fully recovered, circuit CLOSED")
                    }
                }
                ProviderHealthStatus.DEGRADED -> {
                    state.consecutiveFailures = maxOf(0, state.consecutiveFailures - 1)
                    if (state.consecutiveFailures == 0) {
                        state.status = ProviderHealthStatus.HEALTHY
                        Log.d(TAG, "✓ $provider back to HEALTHY")
                    }
                }
                else -> {
                    state.consecutiveFailures = 0
                    state.status = ProviderHealthStatus.HEALTHY
                }
            }
        }
    }

    /**
     * Record a failed API call.
     * Updates failure count and potentially opens circuit.
     */
    fun recordFailure(provider: AIProvider, exception: Exception) {
        val category = categorizeError(exception)
        recordFailure(provider, category)
    }

    /**
     * Record a failed API call with known error category.
     */
    fun recordFailure(provider: AIProvider, category: ApiErrorCategory) {
        val state = getOrCreateState(provider)
        val now = System.currentTimeMillis()

        synchronized(state) {
            state.consecutiveFailures++
            state.totalFailures++
            state.lastFailureTime = now
            state.lastErrorCategory = category

            // Calculate cooldown based on error type
            val cooldown = calculateCooldown(category, state.consecutiveFailures)

            // Update status based on failure count
            state.status = when {
                state.consecutiveFailures >= FAILURE_THRESHOLD_OPEN -> {
                    state.circuitOpenUntil = now + cooldown
                    Log.w(TAG, "⚠ $provider circuit OPEN until ${cooldown/1000}s (${category.name})")
                    ProviderHealthStatus.CIRCUIT_OPEN
                }
                state.consecutiveFailures >= FAILURE_THRESHOLD_DEGRADED -> {
                    Log.w(TAG, "⚠ $provider DEGRADED (${state.consecutiveFailures} failures)")
                    ProviderHealthStatus.DEGRADED
                }
                else -> state.status
            }
        }
    }

    /**
     * Record failure for a specific API key.
     */
    fun recordKeyFailure(apiKey: String, category: ApiErrorCategory) {
        val keyHash = apiKey.takeLast(8) // Just use last 8 chars for identification
        val cooldown = if (category == ApiErrorCategory.AUTH_ERROR) {
            AUTH_ERROR_COOLDOWN_MS
        } else {
            BASE_COOLDOWN_MS
        }
        failedKeys[keyHash] = System.currentTimeMillis() + cooldown
        Log.d(TAG, "Key ...$keyHash marked failed for ${cooldown/1000}s")
    }

    /**
     * Record failure for a specific API key with dynamic cooldown duration.
     * Used when retry-after duration is parsed from API response.
     */
    fun recordKeyFailureWithDuration(apiKey: String, cooldownMs: Long) {
        val keyHash = apiKey.takeLast(8)
        val safeCooldown = cooldownMs.coerceIn(1000L, MAX_COOLDOWN_MS)
        failedKeys[keyHash] = System.currentTimeMillis() + safeCooldown
        Log.d(TAG, "Key ...$keyHash marked failed for ${safeCooldown/1000}s (dynamic)")
    }

    /**
     * Record failure for a specific API key with provider-specific cooldown.
     */
    fun recordKeyFailureForProvider(apiKey: String, provider: AIProvider, category: ApiErrorCategory, errorMessage: String? = null) {
        val keyHash = apiKey.takeLast(8)
        
        // Try to parse retry-after from error message first
        val parsedCooldown = errorMessage?.let { parseRetryAfterFromMessage(it) }
        
        val cooldown = when {
            // Use parsed retry-after if available
            parsedCooldown != null -> parsedCooldown
            // Auth errors get long cooldown (key is invalid)
            category == ApiErrorCategory.AUTH_ERROR -> AUTH_ERROR_COOLDOWN_MS
            // Rate limits use provider-specific cooldowns
            category == ApiErrorCategory.RATE_LIMIT -> PROVIDER_RATE_LIMIT_COOLDOWNS[provider] ?: RATE_LIMIT_COOLDOWN_MS
            // Other errors use base cooldown
            else -> BASE_COOLDOWN_MS
        }
        
        failedKeys[keyHash] = System.currentTimeMillis() + cooldown
        Log.d(TAG, "Key ...$keyHash ($provider) marked failed for ${cooldown/1000}s [$category]")
    }

    /**
     * Check if a specific key is temporarily failed.
     */
    fun isKeyFailed(apiKey: String): Boolean {
        val keyHash = apiKey.takeLast(8)
        val failedUntil = failedKeys[keyHash] ?: return false
        if (System.currentTimeMillis() > failedUntil) {
            failedKeys.remove(keyHash)
            return false
        }
        return true
    }

    /**
     * Calculate cooldown period based on error type and failure count.
     */
    private fun calculateCooldown(category: ApiErrorCategory, failureCount: Int): Long {
        val baseCooldown = when (category) {
            ApiErrorCategory.RATE_LIMIT -> RATE_LIMIT_COOLDOWN_MS
            ApiErrorCategory.AUTH_ERROR -> AUTH_ERROR_COOLDOWN_MS
            ApiErrorCategory.CONTEXT_OVERFLOW -> BASE_COOLDOWN_MS / 2 // Quick retry with different content
            else -> BASE_COOLDOWN_MS
        }

        // Exponential backoff capped at max
        val multiplier = min(failureCount, 5)
        return min(baseCooldown * multiplier, MAX_COOLDOWN_MS)
    }

    /**
     * Check if a provider is currently available for requests.
     */
    fun isProviderAvailable(provider: AIProvider): Boolean {
        val state = healthStates[provider] ?: return true // New provider, assume healthy
        val now = System.currentTimeMillis()

        return synchronized(state) {
            when (state.status) {
                ProviderHealthStatus.HEALTHY,
                ProviderHealthStatus.DEGRADED -> true

                ProviderHealthStatus.RECOVERING -> true // Allow test requests

                ProviderHealthStatus.CIRCUIT_OPEN -> {
                    // Check if cooldown has expired
                    if (now > state.circuitOpenUntil) {
                        state.status = ProviderHealthStatus.RECOVERING
                        Log.i(TAG, "↻ $provider entering RECOVERY mode")
                        true
                    } else {
                        val remaining = (state.circuitOpenUntil - now) / 1000
                        Log.d(TAG, "✗ $provider circuit open for ${remaining}s more")
                        false
                    }
                }
            }
        }
    }

    /**
     * Get ordered list of healthy providers from the given list.
     * Filters out providers with open circuits.
     */
    fun getHealthyProviders(providers: List<AIProvider>): List<AIProvider> {
        return providers.filter { isProviderAvailable(it) }
    }

    /**
     * Get ordered list of all providers, preserving user priority order.
     * Only filters out providers with open circuits.
     *
     * IMPORTANT: User priority is ALWAYS respected. We don't reorder based on health
     * because the user explicitly chose their preference. We only skip unavailable ones.
     */
    fun getOrderedHealthyProviders(providers: List<AIProvider>): List<AIProvider> {
        // Simply filter unavailable providers while preserving user's priority order
        return providers.filter { isProviderAvailable(it) }
    }

    /**
     * Get current health status for a provider.
     */
    fun getProviderStatus(provider: AIProvider): ProviderHealthStatus {
        return healthStates[provider]?.status ?: ProviderHealthStatus.HEALTHY
    }

    /**
     * Get detailed health information for debugging.
     */
    fun getHealthReport(): Map<AIProvider, ProviderHealthState> {
        return healthStates.toMap()
    }

    /**
     * Reset health state for a specific provider.
     * Call this when user updates API key.
     */
    fun resetProvider(provider: AIProvider) {
        healthStates.remove(provider)
        Log.i(TAG, "Reset health state for $provider")
    }

    /**
     * Reset all health states.
     */
    fun resetAll() {
        healthStates.clear()
        failedKeys.clear()
        Log.i(TAG, "Reset all provider health states")
    }

    /**
     * Should we retry with a different provider for this error category?
     */
    fun shouldTryNextProvider(category: ApiErrorCategory): Boolean {
        return when (category) {
            ApiErrorCategory.AUTH_ERROR -> true      // Key is bad, try different provider
            ApiErrorCategory.RATE_LIMIT -> true      // This provider is limited
            ApiErrorCategory.SERVER_ERROR -> true    // Provider having issues
            ApiErrorCategory.MODEL_ERROR -> true     // Model not available
            ApiErrorCategory.CONTEXT_OVERFLOW -> true // Need provider with larger context
            ApiErrorCategory.NETWORK_ERROR -> true   // Connection issue, might be temporary
            ApiErrorCategory.UNKNOWN -> true         // Generic retry
        }
    }

    /**
     * Should we retry the same provider with a different key?
     */
    fun shouldTryDifferentKey(category: ApiErrorCategory): Boolean {
        return when (category) {
            ApiErrorCategory.AUTH_ERROR -> true      // Maybe other key works
            ApiErrorCategory.RATE_LIMIT -> true      // Different key has different quota
            else -> false                            // Other errors are provider-level
        }
    }
}
