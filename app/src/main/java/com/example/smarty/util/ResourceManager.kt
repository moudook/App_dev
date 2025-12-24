package com.example.smarty.util

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * =============================================================================
 * RESOURCE MANAGER
 * =============================================================================
 *
 * Intelligent resource management for optimal performance on all devices.
 *
 * Features:
 * - Device capability detection (edge/low-end vs high-end)
 * - Memory pressure monitoring
 * - Adaptive buffer sizing based on available resources
 * - Cache size limits based on device class
 * - Background operation throttling
 *
 * Usage:
 *   ResourceManager.initialize(context)
 *   val bufferSize = ResourceManager.getOptimalBufferSize()
 *   if (ResourceManager.shouldThrottle()) { delay(100) }
 *
 * =============================================================================
 */
object ResourceManager : ComponentCallbacks2 {

    private const val TAG = "ResourceManager"

    // Device classification thresholds
    private const val LOW_RAM_THRESHOLD_MB = 2048      // 2GB
    private const val MEDIUM_RAM_THRESHOLD_MB = 4096   // 4GB
    private const val LOW_CORES_THRESHOLD = 4

    // Buffer sizes based on device class
    private const val BUFFER_SIZE_LOW = 16384          // 16KB for edge devices
    private const val BUFFER_SIZE_MEDIUM = 32768       // 32KB for medium devices
    private const val BUFFER_SIZE_HIGH = 65536         // 64KB for high-end devices
    private const val BUFFER_SIZE_ULTRA = 131072       // 128KB for flagship devices

    // Cache limits based on device class
    private const val CACHE_LIMIT_LOW_MB = 25          // 25MB for edge devices
    private const val CACHE_LIMIT_MEDIUM_MB = 50       // 50MB for medium devices
    private const val CACHE_LIMIT_HIGH_MB = 100        // 100MB for high-end devices

    // Parallel operation limits
    private const val PARALLEL_OPS_LOW = 1             // Sequential for edge devices
    private const val PARALLEL_OPS_MEDIUM = 2
    private const val PARALLEL_OPS_HIGH = 4

    // State
    private var isInitialized = false
    private var appContext: Context? = null
    private var activityManager: ActivityManager? = null

    // Device capabilities (cached at initialization)
    private var deviceClass: DeviceClass = DeviceClass.MEDIUM
    private var totalRamMB: Long = 0
    private var availableCores: Int = Runtime.getRuntime().availableProcessors()

    // Memory pressure state
    private val _memoryPressure = MutableStateFlow(MemoryPressure.NORMAL)
    val memoryPressure: StateFlow<MemoryPressure> = _memoryPressure.asStateFlow()

    // Low memory mode flag
    private val _isLowMemoryMode = MutableStateFlow(false)
    val isLowMemoryMode: StateFlow<Boolean> = _isLowMemoryMode.asStateFlow()

    // Scoped coroutine scope to replace GlobalScope (fixes memory leak)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Device classification based on hardware capabilities
     */
    enum class DeviceClass {
        EDGE,       // Very low-end devices (< 2GB RAM, < 4 cores)
        LOW,        // Low-end devices (2GB RAM)
        MEDIUM,     // Mid-range devices (2-4GB RAM)
        HIGH,       // High-end devices (4GB+ RAM)
        FLAGSHIP    // Flagship devices (8GB+ RAM, 8+ cores)
    }

    /**
     * Memory pressure levels
     */
    enum class MemoryPressure {
        NORMAL,     // Plenty of memory available
        MODERATE,   // Some memory pressure
        CRITICAL    // Low memory, release caches
    }

    /**
     * Initialize ResourceManager with application context.
     * Should be called in Application.onCreate()
     */
    fun initialize(context: Context) {
        if (isInitialized) return

        appContext = context.applicationContext
        activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        // Detect device capabilities
        detectDeviceCapabilities()

        // Register for memory callbacks
        context.applicationContext.registerComponentCallbacks(this)

        isInitialized = true
        Log.d(TAG, "ResourceManager initialized: $deviceClass, RAM: ${totalRamMB}MB, Cores: $availableCores")
    }

    /**
     * Detect device hardware capabilities
     */
    private fun detectDeviceCapabilities() {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)

        totalRamMB = memInfo.totalMem / (1024 * 1024)
        availableCores = Runtime.getRuntime().availableProcessors()

        // Check if device is considered low RAM by Android
        val isLowRamDevice = activityManager?.isLowRamDevice ?: false

        deviceClass = when {
            isLowRamDevice || totalRamMB < LOW_RAM_THRESHOLD_MB / 2 -> DeviceClass.EDGE
            totalRamMB < LOW_RAM_THRESHOLD_MB -> DeviceClass.LOW
            totalRamMB < MEDIUM_RAM_THRESHOLD_MB -> DeviceClass.MEDIUM
            totalRamMB < 8192 || availableCores < 8 -> DeviceClass.HIGH
            else -> DeviceClass.FLAGSHIP
        }

        Log.d(TAG, "Device class: $deviceClass (RAM: ${totalRamMB}MB, Cores: $availableCores)")
    }

    // =========================================================================
    // RESOURCE OPTIMIZATION APIs
    // =========================================================================

    /**
     * Get optimal buffer size based on device class and memory pressure
     */
    fun getOptimalBufferSize(): Int {
        val baseSize = when (deviceClass) {
            DeviceClass.EDGE -> BUFFER_SIZE_LOW
            DeviceClass.LOW -> BUFFER_SIZE_LOW
            DeviceClass.MEDIUM -> BUFFER_SIZE_MEDIUM
            DeviceClass.HIGH -> BUFFER_SIZE_HIGH
            DeviceClass.FLAGSHIP -> BUFFER_SIZE_ULTRA
        }

        // Reduce buffer size under memory pressure
        return when (_memoryPressure.value) {
            MemoryPressure.CRITICAL -> baseSize / 4
            MemoryPressure.MODERATE -> baseSize / 2
            MemoryPressure.NORMAL -> baseSize
        }
    }

    /**
     * Get optimal NIO buffer size for large file operations
     */
    fun getOptimalNioBufferSize(): Int {
        return getOptimalBufferSize() * 2
    }

    /**
     * Get maximum cache size in bytes
     */
    fun getMaxCacheSize(): Long {
        val baseSizeMB = when (deviceClass) {
            DeviceClass.EDGE -> CACHE_LIMIT_LOW_MB / 2
            DeviceClass.LOW -> CACHE_LIMIT_LOW_MB
            DeviceClass.MEDIUM -> CACHE_LIMIT_MEDIUM_MB
            DeviceClass.HIGH -> CACHE_LIMIT_HIGH_MB
            DeviceClass.FLAGSHIP -> CACHE_LIMIT_HIGH_MB * 2
        }

        // Reduce cache under memory pressure
        val adjustedMB = when (_memoryPressure.value) {
            MemoryPressure.CRITICAL -> baseSizeMB / 4
            MemoryPressure.MODERATE -> baseSizeMB / 2
            MemoryPressure.NORMAL -> baseSizeMB
        }

        return adjustedMB * 1024 * 1024L
    }

    /**
     * Get maximum number of parallel operations
     */
    fun getMaxParallelOperations(): Int {
        val baseOps = when (deviceClass) {
            DeviceClass.EDGE -> PARALLEL_OPS_LOW
            DeviceClass.LOW -> PARALLEL_OPS_LOW
            DeviceClass.MEDIUM -> PARALLEL_OPS_MEDIUM
            DeviceClass.HIGH -> PARALLEL_OPS_HIGH
            DeviceClass.FLAGSHIP -> PARALLEL_OPS_HIGH * 2
        }

        // Reduce under memory pressure
        return when (_memoryPressure.value) {
            MemoryPressure.CRITICAL -> 1
            MemoryPressure.MODERATE -> maxOf(1, baseOps / 2)
            MemoryPressure.NORMAL -> baseOps
        }
    }

    /**
     * Get maximum image dimension for subsampling
     */
    fun getMaxImageDimension(): Int {
        return when (deviceClass) {
            DeviceClass.EDGE -> 1024
            DeviceClass.LOW -> 1536
            DeviceClass.MEDIUM -> 2048
            DeviceClass.HIGH -> 3072
            DeviceClass.FLAGSHIP -> 4096
        }
    }

    /**
     * Get file size threshold for using NIO
     */
    fun getLargeFileThreshold(): Long {
        return when (deviceClass) {
            DeviceClass.EDGE -> 1 * 1024 * 1024L      // 1MB
            DeviceClass.LOW -> 2 * 1024 * 1024L       // 2MB
            DeviceClass.MEDIUM -> 5 * 1024 * 1024L    // 5MB
            DeviceClass.HIGH -> 10 * 1024 * 1024L     // 10MB
            DeviceClass.FLAGSHIP -> 20 * 1024 * 1024L // 20MB
        }
    }

    /**
     * Check if operations should be throttled
     */
    fun shouldThrottle(): Boolean {
        return _memoryPressure.value == MemoryPressure.CRITICAL ||
               deviceClass == DeviceClass.EDGE
    }

    /**
     * Get throttle delay in milliseconds
     */
    fun getThrottleDelay(): Long {
        return when {
            _memoryPressure.value == MemoryPressure.CRITICAL -> 200L
            deviceClass == DeviceClass.EDGE -> 100L
            _memoryPressure.value == MemoryPressure.MODERATE -> 50L
            else -> 0L
        }
    }

    /**
     * Check if device is an edge/low-end device
     */
    fun isEdgeDevice(): Boolean {
        return deviceClass == DeviceClass.EDGE || deviceClass == DeviceClass.LOW
    }

    /**
     * Get current device class
     */
    fun getDeviceClass(): DeviceClass = deviceClass

    /**
     * Get available memory in MB
     */
    fun getAvailableMemoryMB(): Long {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024 * 1024)
    }

    /**
     * Check if there's enough memory for an operation
     */
    fun hasEnoughMemory(requiredMB: Long): Boolean {
        val availableMB = getAvailableMemoryMB()
        // Keep at least 100MB free for system
        return availableMB > requiredMB + 100
    }

    // =========================================================================
    // MEMORY CALLBACK IMPLEMENTATION
    // =========================================================================

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        Log.d(TAG, "onTrimMemory: level=$level")

        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                _memoryPressure.value = MemoryPressure.CRITICAL
                _isLowMemoryMode.value = true
                // Trigger aggressive cache cleanup
                triggerCacheCleanup(aggressive = true)
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                _memoryPressure.value = MemoryPressure.MODERATE
                _isLowMemoryMode.value = true
                // Trigger moderate cache cleanup
                triggerCacheCleanup(aggressive = false)
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                _memoryPressure.value = MemoryPressure.MODERATE
                _isLowMemoryMode.value = false
            }
            else -> {
                _memoryPressure.value = MemoryPressure.NORMAL
                _isLowMemoryMode.value = false
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // No action needed
    }

    @Deprecated("Deprecated in ComponentCallbacks")
    override fun onLowMemory() {
        Log.w(TAG, "onLowMemory triggered")
        _memoryPressure.value = MemoryPressure.CRITICAL
        _isLowMemoryMode.value = true
        triggerCacheCleanup(aggressive = true)
    }

    /**
     * Trigger cache cleanup across the app.
     * Uses scoped coroutine instead of GlobalScope to prevent memory leaks.
     */
    private fun triggerCacheCleanup(aggressive: Boolean) {
        Log.d(TAG, "Triggering cache cleanup (aggressive=$aggressive)")

        // Clear decompression cache using scoped coroutine (fixes GlobalScope leak)
        cleanupScope.launch {
            try {
                FileCompressor.clearCache()

                if (aggressive) {
                    // Clear additional caches
                    appContext?.cacheDir?.let { cacheDir ->
                        clearOldCacheFiles(cacheDir, maxAgeMs = 5 * 60 * 1000) // 5 minutes
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cache cleanup failed", e)
            }
        }
    }

    /**
     * Shutdown ResourceManager and cancel pending operations.
     * Call this when the application is being destroyed.
     */
    fun shutdown() {
        try {
            // Cancel all pending cleanup operations
            cleanupScope.cancel()

            // Unregister memory callbacks to prevent leaks
            appContext?.applicationContext?.unregisterComponentCallbacks(this)

            // Clear all references to prevent memory leaks
            appContext = null
            activityManager = null
            isInitialized = false

            // Reset state flows
            _memoryPressure.value = MemoryPressure.NORMAL
            _isLowMemoryMode.value = false

            Log.d(TAG, "ResourceManager shutdown complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
        }
    }

    /**
     * Clear old files from cache directory
     */
    private fun clearOldCacheFiles(cacheDir: File, maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && (now - file.lastModified()) > maxAgeMs) {
                file.delete()
            } else if (file.isDirectory) {
                clearOldCacheFiles(file, maxAgeMs)
            }
        }
    }

    // =========================================================================
    // DEBUG/LOGGING
    // =========================================================================

    /**
     * Get debug info as string
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== ResourceManager Debug Info ===")
            appendLine("Device Class: $deviceClass")
            appendLine("Total RAM: ${totalRamMB}MB")
            appendLine("Available RAM: ${getAvailableMemoryMB()}MB")
            appendLine("CPU Cores: $availableCores")
            appendLine("Memory Pressure: ${_memoryPressure.value}")
            appendLine("Low Memory Mode: ${_isLowMemoryMode.value}")
            appendLine("Buffer Size: ${getOptimalBufferSize()} bytes")
            appendLine("Max Cache: ${getMaxCacheSize() / (1024 * 1024)}MB")
            appendLine("Max Parallel Ops: ${getMaxParallelOperations()}")
            appendLine("Max Image Dim: ${getMaxImageDimension()}px")
            appendLine("Should Throttle: ${shouldThrottle()}")
        }
    }
}
