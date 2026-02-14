package com.example.smarty.core.common.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.example.smarty.ui.components.ConnectionStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Network connectivity monitor.
 * Observes network state changes and exposes as a Flow.
 *
 * IMPORTANT: Supports both server-side AI and local LLM servers:
 * - Server-side AI: Requires validated internet (NET_CAPABILITY_VALIDATED)
 * - Local LLM: Only requires WiFi/USB/Ethernet transport (no internet validation needed)
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    companion object {
        private const val TAG = "NetworkMonitor"
    }

    val connectionStatus: Flow<ConnectionStatus> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "onAvailable: network=$network")
                // Check current network capabilities
                val status = checkCurrentNetworkStatus()
                trySend(status)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                Log.d(TAG, "onCapabilitiesChanged: network=$network, hasValidated=${networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
                val status = determineStatus(networkCapabilities)
                trySend(status)
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                Log.d(TAG, "onLosing: network=$network, maxMsToLive=$maxMsToLive")
                trySend(ConnectionStatus.CONNECTING)
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "onLost: network=$network")
                // Check if there are other available networks before reporting disconnected
                val status = checkCurrentNetworkStatus()
                trySend(status)
            }

            override fun onUnavailable() {
                Log.d(TAG, "onUnavailable")
                trySend(ConnectionStatus.OFFLINE)
            }
        }

        // Use a broad network request that captures WiFi, USB, and Ethernet
        // Don't require NET_CAPABILITY_INTERNET as local networks may not have it
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            // Note: TRANSPORT_USB might not be directly available on all devices
            .build()

        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
            // Fallback: try default network callback
            try {
                connectivityManager.registerDefaultNetworkCallback(callback)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to register default network callback: ${e2.message}")
            }
        }

        // Emit initial state
        val initialStatus = checkCurrentNetworkStatus()
        Log.d(TAG, "Initial status: $initialStatus")
        trySend(initialStatus)

        awaitClose {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister network callback: ${e.message}")
            }
        }
    }.distinctUntilChanged()

    /**
     * Check current network status using active network.
     * This is called on initial state and when networks are lost to find alternatives.
     */
    private fun checkCurrentNetworkStatus(): ConnectionStatus {
        val currentNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(currentNetwork)
        
        Log.d(TAG, "checkCurrentNetworkStatus: hasNetwork=${currentNetwork != null}, " +
                "hasWifi=${capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}, " +
                "hasVpn=${capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}, " +
                "hasValidated=${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
        
        return determineStatus(capabilities)
    }

    /**
     * Determine connection status based on network capabilities.
     * Handles both cloud APIs (validated internet) and local LLM scenarios (WiFi/USB without validation).
     */
    private fun determineStatus(caps: NetworkCapabilities?): ConnectionStatus {
        return when {
            caps == null -> ConnectionStatus.OFFLINE
            
            // Fully validated internet (for server-side AI processing)
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ->
                ConnectionStatus.CONNECTED
            
            // Local network scenarios: WiFi, USB tethering, or Ethernet without internet validation
            // This allows connections to local LLM servers (e.g., llama.cpp, Ollama)
            // Even without internet validation, these transports can reach local servers
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ->
                ConnectionStatus.CONNECTED
            
            // Cellular without validation - likely connecting
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                ConnectionStatus.CONNECTING
            
            // Other network types without validation
            else -> ConnectionStatus.CONNECTING
        }
    }
}
