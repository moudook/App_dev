package com.example.smarty.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Centralized FCM token management with caching.
 * Reduces redundant token registrations by caching tokens for 24 hours.
 *
 * Thread-safe implementation using Mutex for concurrent access protection.
 *
 * @param context Application context
 */
class TokenManager(private val context: Context) {
    companion object {
        private const val TAG = "TokenManager"
        private const val PREFS_NAME = "fcm_token_prefs"
        private const val KEY_TOKEN = "fcm_token"
        private const val KEY_TIMESTAMP = "fcm_timestamp"
        
        /** Token validity period: 24 hours */
        private const val TOKEN_VALIDITY_MS = 24 * 60 * 60 * 1000L
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val mutex = Mutex()
    
    private val _tokenState = MutableStateFlow<TokenState>(TokenState.Unknown)
    val tokenState: StateFlow<TokenState> = _tokenState.asStateFlow()

    /**
     * Cache FCM token with timestamp.
     * Thread-safe with Mutex protection.
     *
     * @param token FCM registration token
     */
    suspend fun cacheToken(token: String) = mutex.withLock {
        try {
            prefs.edit()
                .putString(KEY_TOKEN, token)
                .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                .apply()
            
            _tokenState.value = TokenState.Cached(token)
            Log.d(TAG, "Token cached successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache token", e)
            _tokenState.value = TokenState.Failed("Cache error: ${e.message}")
        }
    }

    /**
     * Get cached token if valid (not expired).
     * Thread-safe with Mutex protection.
     *
     * @return Cached token if valid, null if expired or not found
     */
    suspend fun getCachedToken(): String? = mutex.withLock {
        try {
            val token = prefs.getString(KEY_TOKEN, null) ?: return@withLock null
            val timestamp = prefs.getLong(KEY_TIMESTAMP, -1)
            
            if (timestamp < 0) {
                Log.d(TAG, "No timestamp found for token")
                return@withLock null
            }
            
            val age = System.currentTimeMillis() - timestamp
            if (age > TOKEN_VALIDITY_MS) {
                Log.d(TAG, "Token expired (age: ${age / 1000}s)")
                clearCachedToken()
                return@withLock null
            }
            
            Log.d(TAG, "Valid cached token found (age: ${age / 1000}s)")
            _tokenState.value = TokenState.Cached(token)
            token
        } catch (e: Exception) {
            Log.e(TAG, "Error reading cached token", e)
            null
        }
    }

    /**
     * Mark token as successfully registered on server.
     *
     * @param token Registered token
     */
    suspend fun markAsRegistered(token: String) = mutex.withLock {
        _tokenState.value = TokenState.Registered(token)
        Log.d(TAG, "Token marked as registered")
    }

    /**
     * Clear cached token (on logout or expiration).
     * Thread-safe with Mutex protection.
     */
    suspend fun clearCachedToken() = mutex.withLock {
        try {
            prefs.edit()
                .remove(KEY_TOKEN)
                .remove(KEY_TIMESTAMP)
                .apply()
            
            _tokenState.value = TokenState.Unknown
            Log.d(TAG, "Cached token cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cached token", e)
        }
    }

    /**
     * Check if token is cached and valid.
     * Non-blocking check without Mutex (read-only operation).
     *
     * @return true if valid cached token exists
     */
    fun hasValidCachedToken(): Boolean {
        val token = prefs.getString(KEY_TOKEN, null) ?: return false
        val timestamp = prefs.getLong(KEY_TIMESTAMP, -1)
        if (timestamp < 0) return false
        
        val age = System.currentTimeMillis() - timestamp
        return age <= TOKEN_VALIDITY_MS
    }
}

/**
 * Sealed class representing FCM token state.
 * Provides type-safe state management.
 */
sealed class TokenState {
    /** Initial state - token not yet retrieved */
    object Unknown : TokenState()
    
    /** Token cached locally, not yet registered */
    data class Cached(val token: String) : TokenState()
    
    /** Token successfully registered on server */
    data class Registered(val token: String) : TokenState()
    
    /** Token registration or caching failed */
    data class Failed(val reason: String) : TokenState()
}
