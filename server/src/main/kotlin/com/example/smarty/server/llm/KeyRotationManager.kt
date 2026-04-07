package com.example.smarty.server.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped API Key Rotation Manager.
 *
 * **Design Principles:**
 * 1. **Session Affinity**: Same API key is used for the entire duration of a user request/session
 * 2. **Error-Driven Rotation**: Keys are only rotated when errors occur (401, 403, 429, network errors)
 * 3. **Thread-Safe**: Safe for concurrent use across multiple coroutines and sessions
 * 4. **Minimal Overhead**: O(1) key lookup, no unnecessary allocations
 *
 * **Architecture:**
 * - Each session gets a dedicated [KeyRotationState] that tracks which key it's using
 * - Global [invalidKeys] set prevents reuse of permanently invalid keys across all sessions
 * - Mutex ensures thread-safe state mutations
 *
 * **Usage Pattern:**
 * ```kotlin
 * val rotationManager = KeyRotationManager(apiKeys)
 *
 * // At start of user request
 * val sessionContext = rotationManager.createSessionContext(sessionId)
 *
 * // For each LLM call within the session
 * val keyIndex = sessionContext.getCurrentKeyIndex()
 * val apiKey = apiKeys[keyIndex]
 *
 * // On error
 * sessionContext.markKeyInvalid(keyIndex)
 *
 * // On new user request (optional cleanup)
 * rotationManager.removeSessionContext(sessionId)
 * ```
 *
 * **Time Complexity:**
 * - [getCurrentKeyIndex]: O(1)
 * - [markKeyInvalid]: O(1) amortized
 * - [hasValidKey]: O(1)
 *
 * **Space Complexity:**
 * - Global state: O(K) where K = number of API keys
 * - Per-session state: O(1)
 * - Total: O(K + S) where S = number of active sessions
 *
 * @param apiKeys List of API keys to rotate through
 * @param providerName Name of the LLM provider for logging
 */
class KeyRotationManager(
    private val apiKeys: List<String>,
    private val providerName: String,
) {
    private val logger = LoggerFactory.getLogger(KeyRotationManager::class.java)

    /**
     * Global set of invalid key indices (shared across all sessions).
     * Thread-safe via mutex protection.
     */
    private val invalidKeys = mutableSetOf<Int>()

    /**
     * Per-session rotation state.
     * ConcurrentHashMap provides thread-safe access without global locking.
     */
    private val sessionStates = ConcurrentHashMap<String, KeyRotationState>()

    /**
     * Mutex for protecting mutable state (invalidKeys set).
     * Used only for write operations to minimize contention.
     */
    private val stateMutex = Mutex()

    /**
     * Creates or retrieves a session-specific key rotation state.
     *
     * **Session Affinity**: Once created, the same [KeyRotationState] is reused
     * for all LLM calls within that session, ensuring the same API key is used
     * throughout the request lifecycle.
     *
     * @param sessionId Unique identifier for the user session/request
     * @return Session-specific rotation state
     */
    fun createSessionContext(sessionId: String): KeyRotationState {
        return sessionStates.computeIfAbsent(sessionId) { key ->
            KeyRotationState(
                sessionId = key,
                apiKeysSize = apiKeys.size,
                invalidKeysProvider = { invalidKeys },
            )
        }
    }

    /**
     * Removes session state (cleanup after request completion).
     * Call this when a user request is fully completed to free memory.
     *
     * @param sessionId Session identifier to remove
     */
    fun removeSessionContext(sessionId: String) {
        sessionStates.remove(sessionId)
    }

    /**
     * Marks a key as permanently invalid across all sessions.
     * Used for 401/403 errors indicating a bad API key.
     *
     * @param keyIndex Index of the key to mark invalid
     */
    suspend fun markKeyInvalid(keyIndex: Int) {
        stateMutex.withLock {
            if (invalidKeys.add(keyIndex)) {
                logger.error(
                    "[$providerName] Marked key #$keyIndex as PERMANENTLY INVALID. " +
                        "Valid keys remaining: ${apiKeys.size - invalidKeys.size}/${apiKeys.size}",
                )
            }
        }
    }

    /**
     * Checks if there are any valid keys remaining.
     * Quick O(1) check to determine if rotation is still possible.
     *
     * @return true if at least one valid key exists
     */
    suspend fun hasValidKey(): Boolean {
        return stateMutex.withLock {
            invalidKeys.size < apiKeys.size
        }
    }

    /**
     * Gets the count of currently valid keys.
     * Useful for logging and monitoring.
     *
     * @return Number of valid keys remaining
     */
    suspend fun getValidKeyCount(): Int {
        return stateMutex.withLock {
            apiKeys.size - invalidKeys.size
        }
    }

    /**
     * Gets all invalid key indices (for debugging/monitoring).
     *
     * @return Set of invalid key indices
     */
    suspend fun getInvalidKeys(): Set<Int> {
        return stateMutex.withLock {
            invalidKeys.toSet()
        }
    }

    /**
     * Clears all session states (useful for testing or full reset).
     */
    fun clearAllSessions() {
        sessionStates.clear()
    }

    /**
     * Resets invalid keys set (useful for testing or manual recovery).
     */
    suspend fun resetInvalidKeys() {
        stateMutex.withLock {
            invalidKeys.clear()
        }
    }
}

/**
 * Session-specific key rotation state.
 *
 * **Purpose**: Maintains which API key a specific session is using,
 * ensuring session affinity while allowing error-driven rotation.
 *
 * **Thread Safety**:
 * - [currentKeyIndex] uses AtomicInteger for lock-free thread-safe updates
 * - [invalidKeysProvider] returns a shared set protected by mutex
 *
 * **State Machine**:
 * ```
 * Initial → Using Key #N → (on error) → Using Key #N+1 → ... → (on success) → Complete
 *                                      ↓
 *                              (all keys invalid) → Error
 * ```
 *
 * @param sessionId Unique session identifier
 * @param apiKeysSize Total number of API keys available
 * @param invalidKeysProvider Lambda providing access to global invalid keys set
 */
class KeyRotationState(
    private val sessionId: String,
    private val apiKeysSize: Int,
    private val invalidKeysProvider: () -> Set<Int>,
) {
    /**
     * Current key index for this session.
     * Uses modulo arithmetic to wrap around the key list.
     *
     * **Note**: [getAndIncrement] ensures each call gets a unique index,
     * but we only call this on errors, not on every request.
     */
    private val currentKeyIndex = java.util.concurrent.atomic.AtomicInteger(0)

    private val logger = LoggerFactory.getLogger(KeyRotationState::class.java)

    /**
     * Gets the current key index for this session.
     *
     * **Session Affinity**: Returns the same index on repeated calls,
     * ensuring the same API key is used throughout the session.
     *
     * **Rotation Logic**: Only advances when [rotateToNextKey] is called
     * (typically after an error).
     *
     * @param invalidKeys Optional set of invalid keys to skip. If null, uses provider.
     * @return Key index to use, or null if no valid keys remain
     */
    fun getCurrentKeyIndex(invalidKeys: Set<Int>? = null): Int? {
        val invalid = invalidKeys ?: invalidKeysProvider()
        val startIndex = currentKeyIndex.get() % apiKeysSize

        // Try each key starting from current index
        for (offset in 0 until apiKeysSize) {
            val index = (startIndex + offset) % apiKeysSize
            if (index !in invalid) {
                return index
            }
        }

        // No valid keys found
        return null
    }

    /**
     * Rotates to the next key (called on error).
     *
     * **Advances** the internal counter, ensuring the next [getCurrentKeyIndex]
     * call will return a different key (if available).
     *
     * **Logs** the rotation for debugging and monitoring.
     *
     * @param reason Reason for rotation (e.g., "401 Unauthorized", "429 Rate Limit")
     * @param previousKeyIndex The key index that failed
     */
    fun rotateToNextKey(
        reason: String,
        previousKeyIndex: Int,
    ) {
        val oldIndex = currentKeyIndex.getAndIncrement()
        val newIndex = currentKeyIndex.get() % apiKeysSize

        logger.warn(
            "[$sessionId] Rotating API key: #$oldIndex → #$newIndex. " +
                "Reason: $reason",
        )
    }

    /**
     * Resets the session state to use the first valid key.
     * Call this at the start of a new user request within the same session
     * if you want to reset to the primary key.
     *
     * **Optional**: Only needed if you want to prefer the first key for new requests.
     */
    fun resetToFirstValidKey() {
        currentKeyIndex.set(0)
        logger.debug("[$sessionId] Reset key index to 0 (will use first valid key)")
    }
}

/**
 * Error classification for API key rotation decisions.
 *
 * **Usage**: Determines whether to rotate keys, retry, or fail immediately.
 */
sealed class ApiKeyError {
    /**
     * Permanent key failure (401, 403, invalid token).
     * **Action**: Mark key as invalid, rotate to next key.
     */
    data class InvalidKey(val message: String) : ApiKeyError()

    /**
     * Rate limit exceeded (429).
     * **Action**: Rotate to next key (may have separate rate limits).
     */
    data class RateLimited(val message: String) : ApiKeyError()

    /**
     * Temporary server error (500, 502, 503, timeout).
     * **Action**: Retry with backoff using same key, then rotate if persistent.
     */
    data class ServerError(val message: String) : ApiKeyError()

    /**
     * Network/connectivity error.
     * **Action**: Retry with backoff, rotate if persistent.
     */
    data class NetworkError(val message: String) : ApiKeyError()

    /**
     * Unknown error.
     * **Action**: Fail immediately, don't rotate.
     */
    data class UnknownError(val message: String) : ApiKeyError()

    /**
     * Determines if this error type allows retry with backoff.
     *
     * @return true if retry is appropriate, false if rotation or immediate failure is better
     */
    fun isRetryable(): Boolean =
        when (this) {
            is InvalidKey -> false // Don't retry invalid keys
            is RateLimited -> true // Retry may succeed with different key or after backoff
            is ServerError -> true // Server may recover
            is NetworkError -> true // Network may recover
            is UnknownError -> false // Unknown errors should fail fast
        }

    /**
     * Determines if this error type requires key rotation.
     *
     * @return true if key should be rotated, false if retry on same key is better
     */
    fun requiresRotation(): Boolean =
        when (this) {
            is InvalidKey -> true // Key is permanently invalid
            is RateLimited -> true // Try different key with separate rate limit
            is ServerError -> false // Retry same key first (transient issue)
            is NetworkError -> false // Retry same key first (transient issue)
            is UnknownError -> false // Fail fast, no rotation
        }
}

/**
 * Utility functions for classifying API errors.
 */
object ApiKeyErrorClassifier {
    /**
     * Classifies an exception into an [ApiKeyError] type.
     *
     * **Classification Logic**:
     * - 401/403/"unauthorized"/"invalid token" → [ApiKeyError.InvalidKey]
     * - 429/"rate limit" → [ApiKeyError.RateLimited]
     * - 500/502/503/"timeout"/"connection" → [ApiKeyError.ServerError] or [ApiKeyError.NetworkError]
     * - Everything else → [ApiKeyError.UnknownError]
     *
     * @param exception The exception to classify
     * @return Classified error type
     */
    fun classify(exception: Throwable): ApiKeyError {
        val message = exception.message?.lowercase() ?: ""

        return when {
            // Permanent authentication failures
            message.contains("401") ||
                message.contains("unauthorized") ||
                message.contains("invalid token") ||
                message.contains("api key is not valid") ||
                message.contains("authentication failed")
            -> ApiKeyError.InvalidKey(exception.message ?: "Authentication failed")

            // Rate limiting
            message.contains("429") ||
                message.contains("rate limit") ||
                message.contains("too many requests") ||
                message.contains("quota exceeded")
            -> ApiKeyError.RateLimited(exception.message ?: "Rate limited")

            // Server errors (may be transient)
            message.contains("500") ||
                message.contains("502") ||
                message.contains("503") ||
                message.contains("504")
            -> ApiKeyError.ServerError(exception.message ?: "Server error")

            // Network/connectivity issues
            message.contains("timeout") ||
                message.contains("connection") ||
                message.contains("closed") ||
                message.contains("broken") ||
                message.contains("reset") ||
                message.contains("network")
            -> ApiKeyError.NetworkError(exception.message ?: "Network error")

            // Unknown errors
            else -> ApiKeyError.UnknownError(exception.message ?: "Unknown error")
        }
    }

    /**
     * Classifies an HTTP status code into an [ApiKeyError] type.
     *
     * @param statusCode HTTP status code
     * @param statusText Optional status text for context
     * @return Classified error type
     */
    fun classify(
        statusCode: Int,
        statusText: String? = null,
    ): ApiKeyError {
        val message = statusText ?: "HTTP $statusCode"

        return when (statusCode) {
            401, 403 -> ApiKeyError.InvalidKey(message)
            429 -> ApiKeyError.RateLimited(message)
            500, 502, 503, 504 -> ApiKeyError.ServerError(message)
            else -> ApiKeyError.UnknownError(message)
        }
    }
}
