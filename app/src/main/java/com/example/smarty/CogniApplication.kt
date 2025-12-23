package com.example.smarty

import android.app.Application
import android.util.Log
import com.example.smarty.util.LazyDecompressor
import com.example.smarty.util.ResourceManager

/**
 * Application class for Cogni.
 *
 * Initializes global components:
 * - ResourceManager: Device capability detection and memory monitoring
 * - LazyDecompressor: On-demand decompression with intelligent caching
 */
class CogniApplication : Application() {

    companion object {
        private const val TAG = "CogniApplication"
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize ResourceManager first (detects device capabilities)
        ResourceManager.initialize(this)
        Log.d(TAG, "ResourceManager initialized")
        Log.d(TAG, ResourceManager.getDebugInfo())

        // Initialize LazyDecompressor (uses ResourceManager settings)
        LazyDecompressor.initialize(this)
        Log.d(TAG, "LazyDecompressor initialized")

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
        // Clean shutdown
        LazyDecompressor.shutdown()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // ResourceManager handles this via ComponentCallbacks2
        Log.w(TAG, "Low memory detected")
    }
}
