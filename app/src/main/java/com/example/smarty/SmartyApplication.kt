package com.example.smarty

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.smarty.util.LazyDecompressor
import com.example.smarty.util.ResourceManager
import com.example.smarty.util.api.ApiMetrics
import java.lang.ref.WeakReference

/**
 * Application class for Smarty.
 *
 * Initializes global components:
 * - ResourceManager: Device capability detection and memory monitoring
 * - LazyDecompressor: On-demand decompression with intelligent caching
 * - WorkManager: Custom configuration to prevent memory leaks
 */
class SmartyApplication : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "SmartyApplication"

        // Strong reference to the application instance
        // Application context lives for the entire process duration, so this is safe from leaks
        private var instance: SmartyApplication? = null

        /**
         * Get application instance safely (may be null if not yet created)
         */
        fun getInstance(): SmartyApplication? = instance

        /**
         * Convenience property for accessing the singleton instance.
         * Throws IllegalStateException if not initialized.
         */
        val appInstance: SmartyApplication
            get() = instance ?: throw IllegalStateException("SmartyApplication not initialized - call onCreate first")
    }

    /**
     * Custom WorkManager configuration to prevent SystemForegroundService memory leaks.
     * Uses application context and minimal thread pool to reduce memory footprint.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG) // Changed to DEBUG to see WorkManager logs
            .setMaxSchedulerLimit(20) // Limit concurrent jobs to prevent resource exhaustion
            .build()

    override fun onCreate() {
        super.onCreate()

        // Initialize CrashLogger first to catch early startup crashes
        try {
            com.example.smarty.util.CrashLogger.init(this)
            com.example.smarty.util.CrashLogger.log(this, "Application onCreate started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init CrashLogger", e)
        }

        // Store strong reference to application
        instance = this

        // Initialize ResourceManager first (detects device capabilities)
        ResourceManager.initialize(this)
        Log.d(TAG, "ResourceManager initialized")
        Log.d(TAG, ResourceManager.getDebugInfo())

        // Initialize LazyDecompressor (uses ResourceManager settings)
        LazyDecompressor.initialize(this)
        Log.d(TAG, "LazyDecompressor initialized")

        // Initialize ApiMetrics for tracking API calls and cache hits
        ApiMetrics.init(this)
        Log.d(TAG, "ApiMetrics initialized")

        // Setup app shortcuts (launcher long-press menu)
        com.example.smarty.util.AppShortcutsManager.setupShortcuts(this)
        Log.d(TAG, "App shortcuts initialized")

        // Setup daily digest notification channel and schedule worker
        try {
            com.example.smarty.worker.DailyDigestWorker.createNotificationChannel(this)
            com.example.smarty.worker.DailyDigestWorker.schedule(this)
            Log.d(TAG, "Daily digest scheduled for 6:30 AM")

            // Setup automated memory sync worker (daily)
            com.example.smarty.worker.MemorySyncWorker.schedule(this)
            Log.d(TAG, "Automated memory sync scheduled")

            // Setup periodic calendar sync worker (30 min)
            com.example.smarty.data.worker.CalendarSyncWorker.schedule(this)
            Log.d(TAG, "Calendar sync scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule workers", e)
            com.example.smarty.util.CrashLogger.log(this, "Worker scheduling failed: ${e.message}")
        }

        com.example.smarty.util.CrashLogger.log(this, "Application onCreate finished")
    }

    override fun onTerminate() {
        super.onTerminate()

        // Cancel all pending WorkManager work to prevent SystemForegroundService leaks
        try {
            WorkManager.getInstance(this).cancelAllWork()
            Log.d(TAG, "WorkManager work cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling WorkManager work", e)
        }

        // Clean shutdown of resources
        ResourceManager.shutdown()
        LazyDecompressor.shutdown()

        // Clear application reference
        instance = null

        Log.d(TAG, "Application terminated cleanly")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // ResourceManager handles this via ComponentCallbacks2
        Log.w(TAG, "Low memory detected")
    }
}
