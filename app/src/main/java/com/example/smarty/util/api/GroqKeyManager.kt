package com.example.smarty.util.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Configuration for a single GROQ API key.
 */
@Serializable
data class GroqKeyConfig(
    val key: String,
    val label: String = "",                    // User-friendly label (e.g., "Orchestrator")
    val rateLimit: Int = 30,                   // Calls per minute
    val dailyLimit: Int = 14400,               // Calls per day (default GROQ free tier)
    val purpose: KeyPurpose = KeyPurpose.GENERAL
)

/**
 * Purpose categories for API keys.
 */
@Serializable
enum class KeyPurpose {
    ORCHESTRATOR,      // Main agent orchestration
    NOTE_PROCESSING,   // Background note AI processing
    RESEARCH,          // Deep research tool
    GENERAL            // General purpose / fallback
}

/**
 * Real-time usage statistics for a single key.
 */
@Serializable
data class KeyUsageStats(
    val key: String,
    val label: String,
    val purpose: KeyPurpose,
    val rateLimit: Int,
    val dailyLimit: Int,
    val callsThisMinute: Int,
    val callsToday: Int,
    val lastCallTime: Long,
    val isInCooldown: Boolean,
    val cooldownEndsAt: Long,
    val healthStatus: KeyHealthStatus
)

/**
 * Health status of a key.
 */
@Serializable
enum class KeyHealthStatus {
    HEALTHY,           // Working normally
    RATE_LIMITED,      // Hit per-minute limit, waiting
    DAILY_EXHAUSTED,   // Hit daily limit
    ERROR,             // API error (invalid key, etc.)
    COOLDOWN           // In cooldown after failure
}

/**
 * Manages multiple GROQ API keys with per-key rate limiting and usage tracking.
 *
 * Features:
 * - Per-key rate limits (calls/minute)
 * - Per-key daily limits
 * - Purpose-based key selection (orchestrator, note processing, etc.)
 * - Real-time usage statistics
 * - Automatic key rotation on rate limit
 * - Persistent usage tracking across app restarts
 *
 * BUG FIX (TECH-003): Now uses EncryptedSharedPreferences to store API keys
 * securely at rest. Previously stored in plaintext SharedPreferences.
 */
class GroqKeyManager private constructor(context: Context) {

    companion object {
        private const val TAG = "GroqKeyManager"
        private const val PREFS_NAME = "groq_key_manager_secure"
        private const val KEY_CONFIGS = "key_configs"
        private const val KEY_DAILY_COUNTS = "daily_counts"
        private const val KEY_LAST_RESET_DATE = "last_reset_date"

        private const val MINUTE_MS = 60_000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val DEFAULT_COOLDOWN_MS = 60_000L

        @Volatile
        private var INSTANCE: GroqKeyManager? = null

        fun getInstance(context: Context): GroqKeyManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GroqKeyManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        /**
         * Creates EncryptedSharedPreferences. Throws if encryption unavailable.
         * This prevents accidental storage of API keys in plaintext.
         */
        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e(TAG, "CRITICAL: Failed to create encrypted storage for API keys", e)
                // SECURITY: Fail hard - never fall back to insecure plaintext storage
                throw SecurityException(
                    "Cannot create secure storage for API keys. " +
                    "Device may not support encryption or KeyStore is unavailable. " +
                    "Error: ${e.message}",
                    e
                )
            }
        }
    }

    /**
     * BUG FIX (TECH-003): Use EncryptedSharedPreferences for API key storage.
     * SECURITY: Fails hard if encryption is unavailable - never falls back to plaintext.
     */
    private val prefs: SharedPreferences = createEncryptedPrefs(context)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    // Per-key call timestamps for sliding window rate limiting
    private val callTimestamps = ConcurrentHashMap<String, MutableList<Long>>()

    // Per-key daily call counts
    private val dailyCounts = ConcurrentHashMap<String, Int>()

    // Per-key cooldown end times
    private val cooldownEndTimes = ConcurrentHashMap<String, Long>()

    // Per-key error status
    private val keyErrors = ConcurrentHashMap<String, KeyHealthStatus>()

    // Key configurations
    private val _keyConfigs = MutableStateFlow<List<GroqKeyConfig>>(emptyList())
    val keyConfigs: StateFlow<List<GroqKeyConfig>> = _keyConfigs.asStateFlow()

    // Real-time usage stats (updated after each call)
    private val _usageStats = MutableStateFlow<List<KeyUsageStats>>(emptyList())
    val usageStats: StateFlow<List<KeyUsageStats>> = _usageStats.asStateFlow()

    init {
        loadConfigs()
        loadDailyCounts()
        checkDailyReset()
        updateUsageStats()
    }

    /**
     * Configure API keys with their rate limits and purposes.
     */
    suspend fun configureKeys(configs: List<GroqKeyConfig>) = mutex.withLock {
        _keyConfigs.value = configs
        saveConfigs()

        // Initialize tracking for new keys
        configs.forEach { config ->
            if (!callTimestamps.containsKey(config.key)) {
                callTimestamps[config.key] = mutableListOf()
            }
            if (!dailyCounts.containsKey(config.key)) {
                dailyCounts[config.key] = 0
            }
        }

        updateUsageStats()
        Log.i(TAG, "Configured ${configs.size} GROQ API keys")
    }

    /**
     * Get the best available key for a specific purpose.
     * Returns null if no healthy key is available.
     */
    suspend fun getKeyForPurpose(purpose: KeyPurpose): GroqKeyConfig? = mutex.withLock {
        checkDailyReset()
        cleanupOldTimestamps()

        val now = System.currentTimeMillis()
        val configs = _keyConfigs.value

        // First, try keys with matching purpose
        val matchingKeys = configs.filter { it.purpose == purpose }
        val healthyMatchingKey = matchingKeys.firstOrNull { isKeyHealthy(it, now) }
        if (healthyMatchingKey != null) {
            return@withLock healthyMatchingKey
        }

        // Fall back to GENERAL purpose keys
        val generalKeys = configs.filter { it.purpose == KeyPurpose.GENERAL }
        val healthyGeneralKey = generalKeys.firstOrNull { isKeyHealthy(it, now) }
        if (healthyGeneralKey != null) {
            return@withLock healthyGeneralKey
        }

        // Last resort: any healthy key
        return@withLock configs.firstOrNull { isKeyHealthy(it, now) }
    }

    /**
     * Get the next available key (any purpose).
     * Uses round-robin with health checking.
     */
    suspend fun getNextAvailableKey(): GroqKeyConfig? = mutex.withLock {
        checkDailyReset()
        cleanupOldTimestamps()

        val now = System.currentTimeMillis()
        val configs = _keyConfigs.value

        // Sort by calls this minute (ascending) to distribute load
        return@withLock configs
            .filter { isKeyHealthy(it, now) }
            .minByOrNull { getCallsThisMinute(it.key, now) }
    }

    /**
     * Record a successful API call for a key.
     */
    suspend fun recordCall(apiKey: String) = mutex.withLock {
        val now = System.currentTimeMillis()

        // Add timestamp for rate limiting
        val timestamps = callTimestamps.getOrPut(apiKey) { mutableListOf() }
        timestamps.add(now)

        // Increment daily count
        dailyCounts[apiKey] = (dailyCounts[apiKey] ?: 0) + 1
        saveDailyCounts()

        // Clear any error status
        keyErrors.remove(apiKey)
        cooldownEndTimes.remove(apiKey)

        updateUsageStats()
        Log.d(TAG, "Recorded call for key ${maskKey(apiKey)}")
    }

    /**
     * Record a rate limit hit for a key.
     */
    suspend fun recordRateLimit(apiKey: String, retryAfterMs: Long? = null) = mutex.withLock {
        val cooldownMs = retryAfterMs ?: DEFAULT_COOLDOWN_MS
        cooldownEndTimes[apiKey] = System.currentTimeMillis() + cooldownMs
        keyErrors[apiKey] = KeyHealthStatus.RATE_LIMITED

        updateUsageStats()
        Log.w(TAG, "Rate limit hit for key ${maskKey(apiKey)}, cooldown ${cooldownMs}ms")
    }

    /**
     * Record an error for a key.
     */
    suspend fun recordError(apiKey: String, isAuthError: Boolean = false) = mutex.withLock {
        if (isAuthError) {
            keyErrors[apiKey] = KeyHealthStatus.ERROR
            // Longer cooldown for auth errors
            cooldownEndTimes[apiKey] = System.currentTimeMillis() + (5 * MINUTE_MS)
        } else {
            keyErrors[apiKey] = KeyHealthStatus.COOLDOWN
            cooldownEndTimes[apiKey] = System.currentTimeMillis() + DEFAULT_COOLDOWN_MS
        }

        updateUsageStats()
        Log.w(TAG, "Error recorded for key ${maskKey(apiKey)}, auth=$isAuthError")
    }

    /**
     * Get current usage stats for all keys.
     */
    fun getAllUsageStats(): List<KeyUsageStats> = _usageStats.value

    /**
     * Get usage stats for a specific key.
     */
    fun getKeyStats(apiKey: String): KeyUsageStats? {
        return _usageStats.value.find { it.key == apiKey }
    }

    /**
     * Get total calls today across all keys.
     */
    fun getTotalCallsToday(): Int = dailyCounts.values.sum()

    /**
     * Get total remaining daily capacity.
     */
    fun getRemainingDailyCapacity(): Int {
        val configs = _keyConfigs.value
        val totalLimit = configs.sumOf { it.dailyLimit }
        return (totalLimit - getTotalCallsToday()).coerceAtLeast(0)
    }

    /**
     * Check if any key is available for a call.
     */
    suspend fun hasAvailableKey(): Boolean {
        return getNextAvailableKey() != null
    }

    /**
     * Get wait time until a key becomes available (in ms).
     * Returns 0 if a key is available now.
     */
    suspend fun getWaitTimeMs(): Long = mutex.withLock {
        val now = System.currentTimeMillis()
        val configs = _keyConfigs.value

        if (configs.isEmpty()) return@withLock Long.MAX_VALUE

        // Check if any key is healthy
        if (configs.any { isKeyHealthy(it, now) }) {
            return@withLock 0L
        }

        // Find minimum wait time across all keys
        var minWait = Long.MAX_VALUE

        configs.forEach { config ->
            val cooldownEnd = cooldownEndTimes[config.key] ?: 0L
            if (cooldownEnd > now) {
                minWait = minOf(minWait, cooldownEnd - now)
            }

            // Check rate limit window
            val timestamps = callTimestamps[config.key] ?: emptyList<Long>()
            val callsThisMinute = timestamps.count { it > now - MINUTE_MS }
            if (callsThisMinute >= config.rateLimit) {
                val oldestCall = timestamps.filter { it > now - MINUTE_MS }.minOrNull() ?: now
                val waitTime = (oldestCall + MINUTE_MS) - now
                minWait = minOf(minWait, waitTime)
            }
        }

        return@withLock minWait
    }

    // ==================== Private Methods ====================

    private fun isKeyHealthy(config: GroqKeyConfig, now: Long): Boolean {
        val key = config.key

        // Check for persistent error
        val errorStatus = keyErrors[key]
        if (errorStatus == KeyHealthStatus.ERROR) {
            return false
        }

        // Check cooldown
        val cooldownEnd = cooldownEndTimes[key] ?: 0L
        if (now < cooldownEnd) {
            return false
        }

        // Check rate limit
        val callsThisMinute = getCallsThisMinute(key, now)
        if (callsThisMinute >= config.rateLimit) {
            return false
        }

        // Check daily limit
        val callsToday = dailyCounts[key] ?: 0
        if (callsToday >= config.dailyLimit) {
            return false
        }

        return true
    }

    private fun getCallsThisMinute(apiKey: String, now: Long): Int {
        val timestamps = callTimestamps[apiKey] ?: return 0
        return timestamps.count { it > now - MINUTE_MS }
    }

    private fun cleanupOldTimestamps() {
        val now = System.currentTimeMillis()
        val cutoff = now - MINUTE_MS

        callTimestamps.forEach { (_, timestamps) ->
            timestamps.removeAll { it < cutoff }
        }
    }

    private fun checkDailyReset() {
        val today = System.currentTimeMillis() / DAY_MS
        val lastResetDay = prefs.getLong(KEY_LAST_RESET_DATE, 0L)

        if (today > lastResetDay) {
            // New day, reset all daily counts
            dailyCounts.clear()
            _keyConfigs.value.forEach { config ->
                dailyCounts[config.key] = 0
            }
            prefs.edit().putLong(KEY_LAST_RESET_DATE, today).apply()
            saveDailyCounts()
            Log.i(TAG, "Daily counts reset for new day")
        }
    }

    private fun updateUsageStats() {
        val now = System.currentTimeMillis()
        val stats = _keyConfigs.value.map { config ->
            val key = config.key
            val callsThisMinute = getCallsThisMinute(key, now)
            val callsToday = dailyCounts[key] ?: 0
            val cooldownEnd = cooldownEndTimes[key] ?: 0L
            val isInCooldown = now < cooldownEnd

            val healthStatus = when {
                keyErrors[key] == KeyHealthStatus.ERROR -> KeyHealthStatus.ERROR
                callsToday >= config.dailyLimit -> KeyHealthStatus.DAILY_EXHAUSTED
                isInCooldown -> KeyHealthStatus.COOLDOWN
                callsThisMinute >= config.rateLimit -> KeyHealthStatus.RATE_LIMITED
                else -> KeyHealthStatus.HEALTHY
            }

            KeyUsageStats(
                key = key,
                label = config.label,
                purpose = config.purpose,
                rateLimit = config.rateLimit,
                dailyLimit = config.dailyLimit,
                callsThisMinute = callsThisMinute,
                callsToday = callsToday,
                lastCallTime = callTimestamps[key]?.maxOrNull() ?: 0L,
                isInCooldown = isInCooldown,
                cooldownEndsAt = cooldownEnd,
                healthStatus = healthStatus
            )
        }
        _usageStats.value = stats
    }

    private fun saveConfigs() {
        try {
            val jsonStr = json.encodeToString(_keyConfigs.value)
            prefs.edit().putString(KEY_CONFIGS, jsonStr).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save key configs", e)
        }
    }

    private fun loadConfigs() {
        try {
            val jsonStr = prefs.getString(KEY_CONFIGS, null) ?: return
            _keyConfigs.value = json.decodeFromString(jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load key configs", e)
        }
    }

    private fun saveDailyCounts() {
        try {
            val map = dailyCounts.toMap()
            val jsonStr = json.encodeToString(map)
            prefs.edit().putString(KEY_DAILY_COUNTS, jsonStr).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save daily counts", e)
        }
    }

    private fun loadDailyCounts() {
        try {
            val jsonStr = prefs.getString(KEY_DAILY_COUNTS, null) ?: return
            val map: Map<String, Int> = json.decodeFromString(jsonStr)
            dailyCounts.putAll(map)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load daily counts", e)
        }
    }

    private fun maskKey(key: String): String {
        return if (key.length > 8) {
            "${key.take(4)}...${key.takeLast(4)}"
        } else {
            "****"
        }
    }
}
