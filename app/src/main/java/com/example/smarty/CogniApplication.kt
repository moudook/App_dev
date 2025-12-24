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
 * Application class for Cogni.
 *
 * Initializes global components:
 * - ResourceManager: Device capability detection and memory monitoring
 * - LazyDecompressor: On-demand decompression with intelligent caching
 * - WorkManager: Custom configuration to prevent memory leaks
 */
class CogniApplication : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "CogniApplication"

        // WeakReference to prevent memory leaks from static context references
        private var applicationRef: WeakReference<CogniApplication>? = null

        /**
         * Get application instance safely (may be null if app terminated)
         */
        fun getInstance(): CogniApplication? = applicationRef?.get()
    }

    /**
     * Custom WorkManager configuration to prevent SystemForegroundService memory leaks.
     * Uses application context and minimal thread pool to reduce memory footprint.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .setMaxSchedulerLimit(20) // Limit concurrent jobs to prevent resource exhaustion
            .build()

    override fun onCreate() {
        super.onCreate()

        // Store weak reference to application (prevents memory leaks)
        applicationRef = WeakReference(this)

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
        com.example.smarty.worker.DailyDigestWorker.createNotificationChannel(this)
        com.example.smarty.worker.DailyDigestWorker.schedule(this)
        Log.d(TAG, "Daily digest scheduled for 6:30 AM")
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
        applicationRef = null

        Log.d(TAG, "Application terminated cleanly")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // ResourceManager handles this via ComponentCallbacks2
        Log.w(TAG, "Low memory detected")
    }
}
