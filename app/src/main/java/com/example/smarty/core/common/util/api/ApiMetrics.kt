package com.example.smarty.core.common.util.api

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks API call metrics for monitoring and debugging.
 * Persists to SharedPreferences for survival across app restarts.
 */
object ApiMetrics {
    private const val PREFS_NAME = "api_metrics"
    private const val KEY_TOTAL_CALLS = "total_calls"
    private const val KEY_SUCCESSFUL_CALLS = "successful_calls"
    private const val KEY_FAILED_CALLS = "failed_calls"
    private const val KEY_CACHE_HITS = "cache_hits"
    private const val KEY_CACHE_MISSES = "cache_misses"
    private const val KEY_LAST_RESET = "last_reset"

    private lateinit var prefs: SharedPreferences

    private val _totalCalls = AtomicInteger(0)
    private val _successfulCalls = AtomicInteger(0)
    private val _failedCalls = AtomicInteger(0)
    private val _cacheHits = AtomicInteger(0)
    private val _cacheMisses = AtomicInteger(0)
    private var lastResetTime: Long = 0

    // StateFlow for real-time UI updates
    private val _metricsFlow = MutableStateFlow(MetricsSnapshot())
    val metricsFlow: StateFlow<MetricsSnapshot> = _metricsFlow.asStateFlow()

    data class MetricsSnapshot(
        val totalCalls: Int = 0,
        val successfulCalls: Int = 0,
        val failedCalls: Int = 0,
        val cacheHits: Int = 0,
        val cacheMisses: Int = 0,
        val successRate: Float = 0f,
        val cacheHitRate: Float = 0f,
        val lastResetTime: Long = 0
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        _totalCalls.set(prefs.getInt(KEY_TOTAL_CALLS, 0))
        _successfulCalls.set(prefs.getInt(KEY_SUCCESSFUL_CALLS, 0))
        _failedCalls.set(prefs.getInt(KEY_FAILED_CALLS, 0))
        _cacheHits.set(prefs.getInt(KEY_CACHE_HITS, 0))
        _cacheMisses.set(prefs.getInt(KEY_CACHE_MISSES, 0))
        lastResetTime = prefs.getLong(KEY_LAST_RESET, System.currentTimeMillis())
        updateFlow()
    }

    private fun saveToPrefs() {
        prefs.edit()
            .putInt(KEY_TOTAL_CALLS, _totalCalls.get())
            .putInt(KEY_SUCCESSFUL_CALLS, _successfulCalls.get())
            .putInt(KEY_FAILED_CALLS, _failedCalls.get())
            .putInt(KEY_CACHE_HITS, _cacheHits.get())
            .putInt(KEY_CACHE_MISSES, _cacheMisses.get())
            .putLong(KEY_LAST_RESET, lastResetTime)
            .apply()
    }

    private fun updateFlow() {
        val total = _totalCalls.get()
        val successful = _successfulCalls.get()
        val cacheTotal = _cacheHits.get() + _cacheMisses.get()

        _metricsFlow.value = MetricsSnapshot(
            totalCalls = total,
            successfulCalls = successful,
            failedCalls = _failedCalls.get(),
            cacheHits = _cacheHits.get(),
            cacheMisses = _cacheMisses.get(),
            successRate = if (total > 0) successful.toFloat() / total else 0f,
            cacheHitRate = if (cacheTotal > 0) _cacheHits.get().toFloat() / cacheTotal else 0f,
            lastResetTime = lastResetTime
        )
    }

    fun recordApiCall(success: Boolean) {
        _totalCalls.incrementAndGet()
        if (success) {
            _successfulCalls.incrementAndGet()
        } else {
            _failedCalls.incrementAndGet()
        }
        saveToPrefs()
        updateFlow()
    }

    fun recordCacheResult(hit: Boolean) {
        if (hit) {
            _cacheHits.incrementAndGet()
        } else {
            _cacheMisses.incrementAndGet()
        }
        saveToPrefs()
        updateFlow()
    }

    fun reset() {
        _totalCalls.set(0)
        _successfulCalls.set(0)
        _failedCalls.set(0)
        _cacheHits.set(0)
        _cacheMisses.set(0)
        lastResetTime = System.currentTimeMillis()
        saveToPrefs()
        updateFlow()
    }

    fun getSnapshot(): MetricsSnapshot = _metricsFlow.value
}
