package com.example.smarty.core.common.util.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Global rate limiter for API calls.
 *
 * Features:
 * - Sliding window for per-minute limits (30 calls/min)
 * - Daily budget tracking (14,400 calls/day)
 * - Persistent storage for day reset
 * - Intelligent queuing when limits reached
 * - Per-provider distribution awareness
 *
 * Usage:
 * ```
 * val rateLimiter = RateLimiter.getInstance(context)
 *
 * // Before making API call
 * val waitTime = rateLimiter.canMakeCall()
 * if (waitTime != null) {
 *     if (waitTime > 30_000L) {
 *         // Daily limit exceeded
 *         return error
 *     }
 *     delay(waitTime)  // Wait for per-minute limit
 * }
 *
 * // After successful call
 * rateLimiter.recordCall()
 * ```
 */
class RateLimiter private constructor(
    context: Context,
) {
    companion object {
        private const val TAG = "RateLimiter"
        private const val PREFS_NAME = "rate_limiter_prefs"

        // Default limits - optimized for 30 calls/min, 14.4k calls/day
        const val DEFAULT_CALLS_PER_MINUTE = 30
        const val DEFAULT_DAILY_BUDGET = 14_400

        // Sliding window size (1 minute)
        private const val WINDOW_SIZE_MS = 60_000L

        // Prefs keys
        private const val KEY_DAILY_COUNT = "daily_count"
        private const val KEY_DAY_START = "day_start"

        @Volatile
        private var instance: RateLimiter? = null

        fun getInstance(context: Context): RateLimiter =
            instance ?: synchronized(this) {
                instance ?: RateLimiter(context.applicationContext).also { instance = it }
            }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val lock = Any() // For synchronizing non-suspend functions

    // Sliding window queue for per-minute tracking (timestamps of recent calls)
    private val callTimestamps = ConcurrentLinkedQueue<Long>()

    // Daily budget tracking
    private var dailyCallCount: Int = 0
    private var dayStartTimestamp: Long = 0L

    // Configurable limits
    var callsPerMinute: Int = DEFAULT_CALLS_PER_MINUTE
        private set
    var dailyBudget: Int = DEFAULT_DAILY_BUDGET
        private set

    init {
        loadState()
        Log.d(TAG, "RateLimiter initialized: $dailyCallCount/$dailyBudget calls today")
    }

    /**
     * Check if a call can be made right now.
     * @return null if allowed, or wait time in ms if rate limited
     */
    suspend fun canMakeCall(): Long? =
        mutex.withLock {
            checkDayReset()
            cleanupOldTimestamps()

            // Check daily budget first
            if (dailyCallCount >= dailyBudget) {
                val msUntilReset = getMsUntilDayReset()
                Log.w(TAG, "Daily budget exhausted ($dailyCallCount/$dailyBudget). Resets in ${msUntilReset / 3600_000}h")
                return@withLock msUntilReset
            }

            // Check per-minute limit
            if (callTimestamps.size >= callsPerMinute) {
                val oldestCall = callTimestamps.peek() ?: return@withLock null
                val waitTime = oldestCall + WINDOW_SIZE_MS - System.currentTimeMillis()
                if (waitTime > 0) {
                    Log.d(TAG, "Per-minute limit reached (${callTimestamps.size}/$callsPerMinute). Wait ${waitTime}ms")
                    return@withLock waitTime
                }
            }

            null // Can proceed
        }

    /**
     * Record a successful API call.
     * Call this AFTER the API call succeeds.
     */
    suspend fun recordCall() =
        mutex.withLock {
            checkDayReset()

            val now = System.currentTimeMillis()
            callTimestamps.add(now)
            dailyCallCount++

            // Persist daily count
            saveState()

            Log.d(TAG, "API call recorded. Minute: ${callTimestamps.size}/$callsPerMinute, Daily: $dailyCallCount/$dailyBudget")
        }

    /**
     * Get remaining calls available today.
     */
    fun getRemainingDailyBudget(): Int =
        synchronized(lock) {
            checkDayReset()
            maxOf(0, dailyBudget - dailyCallCount)
        }

    /**
     * Get remaining calls available this minute.
     */
    fun getRemainingMinuteBudget(): Int =
        synchronized(lock) {
            cleanupOldTimestamps()
            maxOf(0, callsPerMinute - callTimestamps.size)
        }

    /**
     * Get comprehensive usage statistics.
     */
    fun getUsageStats(): RateLimitStats =
        synchronized(lock) {
            checkDayReset()
            cleanupOldTimestamps()
            RateLimitStats(
                dailyUsed = dailyCallCount,
                dailyLimit = dailyBudget,
                minuteUsed = callTimestamps.size,
                minuteLimit = callsPerMinute,
                msUntilMinuteReset = getOldestCallAge(),
                msUntilDayReset = getMsUntilDayReset(),
            )
        }

    /**
     * Update rate limits (for dynamic configuration).
     */
    fun updateLimits(
        callsPerMinute: Int? = null,
        dailyBudget: Int? = null,
    ) = synchronized(lock) {
        callsPerMinute?.let {
            this.callsPerMinute = it.coerceIn(1, 100)
            Log.i(TAG, "Per-minute limit updated to ${this.callsPerMinute}")
        }
        dailyBudget?.let {
            this.dailyBudget = it.coerceIn(100, 50_000)
            Log.i(TAG, "Daily budget updated to ${this.dailyBudget}")
        }
    }

    /**
     * Reset daily counter (for testing or manual reset).
     */
    suspend fun resetDailyCounter() =
        mutex.withLock {
            dailyCallCount = 0
            dayStartTimestamp = getTodayStartMs()
            saveState()
            Log.i(TAG, "Daily counter reset")
        }

    /**
     * Check if new day started and reset counter if needed.
     */
    private fun checkDayReset() {
        val todayStart = getTodayStartMs()

        if (dayStartTimestamp < todayStart) {
            // New day, reset counter
            Log.i(TAG, "New day detected. Resetting daily counter (was $dailyCallCount)")
            dailyCallCount = 0
            dayStartTimestamp = todayStart
            saveState()
        }
    }

    /**
     * Remove timestamps older than the sliding window.
     */
    private fun cleanupOldTimestamps() {
        val cutoff = System.currentTimeMillis() - WINDOW_SIZE_MS
        while (callTimestamps.isNotEmpty() && (callTimestamps.peek() ?: 0) < cutoff) {
            callTimestamps.poll()
        }
    }

    /**
     * Get the start of today in milliseconds.
     */
    private fun getTodayStartMs(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * Get milliseconds until midnight (daily reset).
     */
    private fun getMsUntilDayReset(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis - System.currentTimeMillis()
    }

    /**
     * Get age of oldest call in the sliding window.
     */
    private fun getOldestCallAge(): Long {
        val oldest = callTimestamps.peek() ?: return 0L
        val age = System.currentTimeMillis() - oldest
        return maxOf(0, WINDOW_SIZE_MS - age)
    }

    /**
     * Load persisted state from SharedPreferences.
     */
    private fun loadState() {
        dailyCallCount = prefs.getInt(KEY_DAILY_COUNT, 0)
        dayStartTimestamp = prefs.getLong(KEY_DAY_START, 0L)
        checkDayReset()
    }

    /**
     * Save state to SharedPreferences.
     */
    private fun saveState() {
        prefs
            .edit()
            .putInt(KEY_DAILY_COUNT, dailyCallCount)
            .putLong(KEY_DAY_START, dayStartTimestamp)
            .apply()
    }
}

/**
 * Comprehensive rate limit statistics.
 */
data class RateLimitStats(
    val dailyUsed: Int,
    val dailyLimit: Int,
    val minuteUsed: Int,
    val minuteLimit: Int,
    val msUntilMinuteReset: Long,
    val msUntilDayReset: Long,
) {
    /** Percentage of daily budget used (0.0 - 1.0) */
    val dailyPercentUsed: Float get() = dailyUsed.toFloat() / dailyLimit

    /** Percentage of per-minute budget used (0.0 - 1.0) */
    val minutePercentUsed: Float get() = minuteUsed.toFloat() / minuteLimit

    /** Remaining daily calls */
    val dailyRemaining: Int get() = maxOf(0, dailyLimit - dailyUsed)

    /** Remaining per-minute calls */
    val minuteRemaining: Int get() = maxOf(0, minuteLimit - minuteUsed)

    /** Human-readable time until daily reset */
    val timeUntilDayReset: String
        get() {
            val hours = msUntilDayReset / 3600_000
            val minutes = (msUntilDayReset / 60_000) % 60
            return "${hours}h ${minutes}m"
        }

    /** Is daily budget exhausted? */
    val isDailyExhausted: Boolean get() = dailyUsed >= dailyLimit

    /** Is per-minute budget exhausted? */
    val isMinuteExhausted: Boolean get() = minuteUsed >= minuteLimit

    override fun toString(): String =
        "RateLimitStats(daily=$dailyUsed/$dailyLimit, minute=$minuteUsed/$minuteLimit, " +
            "dailyRemaining=$dailyRemaining, minuteRemaining=$minuteRemaining, " +
            "resetIn=$timeUntilDayReset)"
}
