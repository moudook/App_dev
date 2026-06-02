package com.example.smarty.core.common.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Network connectivity monitoring utility
 * Provides real-time network status updates
 */
object NetworkMonitorUtil {
    /**
     * Observe network connectivity changes
     * Returns Flow<Boolean> indicating online/offline status
     */
    fun observeConnectivity(context: Context): Flow<Boolean> =
        callbackFlow {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        trySend(true)
                    }

                    override fun onLost(network: Network) {
                        trySend(false)
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        trySend(hasInternet)
                    }
                }

            val request =
                NetworkRequest
                    .Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()

            connectivityManager.registerNetworkCallback(request, callback)

            // Send initial state
            trySend(isOnlineUtil(context))

            awaitClose {
                connectivityManager.unregisterNetworkCallback(callback)
            }
        }.distinctUntilChanged()

    /**
     * Check if device is currently online
     */
    fun isOnlineUtil(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Check if connection is WiFi
     */
    fun isWifi(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Check if connection is cellular
     */
    fun isCellular(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}

/**
 * Memory monitoring utility
 * Tracks app memory usage
 */
object MemoryMonitor {
    /**
     * Get current memory usage in MB
     */
    fun getCurrentMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    }

    /**
     * Get max available memory in MB
     */
    fun getMaxMemory(): Long = Runtime.getRuntime().maxMemory() / (1024 * 1024)

    /**
     * Get memory usage percentage
     */
    fun getMemoryUsagePercent(): Float = getCurrentMemoryUsage().toFloat() / getMaxMemory() * 100

    /**
     * Check if memory usage is critical (>80%)
     */
    fun isMemoryCritical(): Boolean = getMemoryUsagePercent() > 80f

    /**
     * Suggest garbage collection if memory is high
     */
    fun suggestGC() {
        if (isMemoryCritical()) {
            System.gc()
        }
    }
}

/**
 * Battery monitoring utility
 * Provides battery status information
 */
object BatteryMonitor {
    /**
     * Check if device is in power save mode
     */
    fun isPowerSaveMode(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return powerManager.isPowerSaveMode
    }
}

/**
 * Storage monitoring utility
 * Tracks available storage space
 */
object StorageMonitor {
    /**
     * Get available internal storage in MB
     */
    fun getAvailableStorage(context: Context): Long {
        val statFs = android.os.StatFs(context.filesDir.path)
        return statFs.availableBytes / (1024 * 1024)
    }

    /**
     * Get total internal storage in MB
     */
    fun getTotalStorage(context: Context): Long {
        val statFs = android.os.StatFs(context.filesDir.path)
        return statFs.totalBytes / (1024 * 1024)
    }

    /**
     * Get storage usage percentage
     */
    fun getStorageUsagePercent(context: Context): Float {
        val available = getAvailableStorage(context)
        val total = getTotalStorage(context)
        return ((total - available).toFloat() / total * 100)
    }

    /**
     * Check if storage is critically low (<100MB)
     */
    fun isStorageCritical(context: Context): Boolean = getAvailableStorage(context) < 100
}

/**
 * App version utility
 * Provides app version information
 */
object AppVersionUtil {
    /**
     * Get app version name
     */
    fun getVersionName(context: Context): String =
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

    /**
     * Get app version code
     */
    fun getVersionCode(context: Context): Long =
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.longVersionCode
        } catch (e: Exception) {
            0
        }

    /**
     * Get formatted version string
     */
    fun getFormattedVersion(context: Context): String = "v${getVersionName(context)} (${getVersionCode(context)})"
}
