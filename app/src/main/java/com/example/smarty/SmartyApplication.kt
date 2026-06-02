package com.example.smarty

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.smarty.core.common.util.LazyDecompressor
import com.example.smarty.core.common.util.NetworkMonitor
import com.example.smarty.core.common.util.ResourceManager
import com.example.smarty.core.common.util.api.ApiMetrics
import com.example.smarty.data.worker.SyncWorker
import com.example.smarty.ui.components.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Application class for Smarty.
 *
 * Initializes global components:
 * - ResourceManager: Device capability detection and memory monitoring
 * - LazyDecompressor: On-demand decompression with intelligent caching
 * - WorkManager: Custom configuration to prevent memory leaks
 */
class SmartyApplication :
    Application(),
    Configuration.Provider {
    companion object {
        private const val TAG = "SmartyApplication"
        private var instance: SmartyApplication? = null
        private var wasOffline: Boolean = false

        fun getInstance(): SmartyApplication? = instance

        val appInstance: SmartyApplication
            get() = instance ?: throw IllegalStateException("SmartyApplication not initialized - call onCreate first")
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setMaxSchedulerLimit(20)
                .build()

    override fun onCreate() {
        super.onCreate()

        try {
            com.example.smarty.core.common.util.CrashLogger
                .init(this)
            com.example.smarty.core.common.util.CrashLogger
                .log(this, "Application onCreate started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init CrashLogger", e)
        }

        instance = this

        ResourceManager.initialize(this)
        Log.d(TAG, "ResourceManager initialized")
        Log.d(TAG, ResourceManager.getDebugInfo())

        LazyDecompressor.initialize(this)
        Log.d(TAG, "LazyDecompressor initialized")

        ApiMetrics.init(this)
        Log.d(TAG, "ApiMetrics initialized")

        com.example.smarty.core.common.util.AppShortcutsManager
            .setupShortcuts(this)
        Log.d(TAG, "App shortcuts initialized")

        try {
            com.example.smarty.core.common.util.NotificationHelper
                .createNotificationChannels(this)

            com.example.smarty.core.common.worker.DailyDigestWorker
                .createNotificationChannel(this)
            com.example.smarty.core.common.worker.DailyDigestWorker
                .schedule(this)
            Log.d(TAG, "Daily digest scheduled for 6:30 AM")

            com.example.smarty.core.common.worker.DailyBriefingWorker
                .schedule(this)
            Log.d(TAG, "Daily briefing scheduled for 7:30 AM")

            com.example.smarty.data.worker.CalendarSyncWorker
                .schedule(this)
            Log.d(TAG, "Calendar sync scheduled")

            SyncWorker.schedule(this)
            Log.d(TAG, "Sync worker scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule workers", e)
            com.example.smarty.core.common.util.CrashLogger
                .log(this, "Worker scheduling failed: ${e.message}")
        }

        setupNetworkCallback()

        appScope.launch {
            performInitialSync()
        }

        setupEngagementTracking()

        com.example.smarty.core.common.util.CrashLogger
            .log(this, "Application onCreate finished")
    }

    private fun setupNetworkCallback() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available: $network")
                    if (wasOffline) {
                        Log.i(TAG, "Transition from offline to online - triggering sync")
                        appScope.launch {
                            SyncWorker.syncNow(this@SmartyApplication)
                        }
                    }
                    wasOffline = false
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "Network lost: $network")
                    wasOffline = true
                }
            }

        val request =
            android.net.NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private suspend fun performInitialSync() {
        try {
            val networkMonitor = NetworkMonitor(this)

            if (networkMonitor.connectionStatus.first() == ConnectionStatus.CONNECTED) {
                Log.i(TAG, "Online at startup - performing initial sync")
                SyncWorker.syncNow(this)
            } else {
                Log.d(TAG, "Offline at startup - skipping initial sync")
                wasOffline = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Initial sync failed", e)
        }
    }

    private fun setupEngagementTracking() {
        val engagementManager =
            com.example.smarty.di.ServiceLocator
                .provideNoteEngagementManager(this)
        val appState =
            com.example.smarty.di.ServiceLocator
                .provideSharedAppState()

        appScope.launch {
            engagementManager.streakCount.collectLatest { count ->
                appState.setNoteStreak(count)
            }
        }

        appScope.launch {
            engagementManager.noteOfTheDay.collectLatest { note ->
                appState.setNoteOfTheDay(note)
            }
        }

        appScope.launch {
            engagementManager.smartSuggestions.collectLatest { notes ->
                appState.setSmartSuggestions(notes)
            }
        }

        appScope.launch {
            val noteOfTheDay = engagementManager.getNoteOfTheDay()
            if (noteOfTheDay != null) {
                appState.setNoteOfTheDay(noteOfTheDay)
            }
        }

        appScope.launch {
            val suggestions = engagementManager.getSmartSuggestions()
            if (suggestions.isNotEmpty()) {
                appState.setSmartSuggestions(suggestions)
            }
        }

        Log.d(TAG, "Engagement tracking initialized")
    }

    override fun onTerminate() {
        super.onTerminate()

        try {
            networkCallback?.let {
                val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering network callback", e)
        }

        try {
            WorkManager.getInstance(this).cancelAllWork()
            Log.d(TAG, "WorkManager work cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling WorkManager work", e)
        }

        ResourceManager.shutdown()
        LazyDecompressor.shutdown()

        instance = null

        Log.d(TAG, "Application terminated cleanly")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Low memory detected")
    }
}
