package com.example.smarty.data.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.protocol.*
import kotlinx.serialization.json.Json

/**
 * =============================================================================
 * CAPABILITY NEGOTIATOR - Device-Server Handshake for Cloud-First Architecture
 * =============================================================================
 *
 * Performs capability negotiation handshake with the server on session init.
 * This tells the server what the device can do, so the server can:
 * 1. Route commands appropriately (server-side vs device-side)
 * 2. Optimize responses based on device capabilities
 * 3. Track device state for sync
 *
 * HANDSHAKE FLOW:
 * 1. Device collects capabilities (hardware, processing, media, system)
 * 2. Device sends POST /session/init with HandshakeRequest
 * 3. Server responds with HandshakeResponse (sessionId, executionPolicy, syncState)
 * 4. Device stores sessionId and uses it for subsequent requests
 *
 * EXECUTION POLICY:
 * - serverSide: Actions the server will execute (CRUD, RAG, LLM, etc.)
 * - deviceSide: Actions the device must execute (hardware access)
 * - hybrid: Actions with fallback logic
 *
 * =============================================================================
 */

class CapabilityNegotiator(
    private val context: Context,
    private val securePreferences: SecurePreferences,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    companion object {
        private const val TAG = "CapabilityNegotiator"
        private const val PROTOCOL_VERSION = "2.0"
    }

    /**
     * Build a handshake request with current device capabilities.
     */
    fun buildHandshakeRequest(): HandshakeRequest =
        HandshakeRequest(
            protocolVersion = PROTOCOL_VERSION,
            deviceId = securePreferences.getDeviceId(),
            capabilities = buildDeviceCapabilities(),
            context = buildDeviceContext(),
        )

    /**
     * Build device capabilities structure.
     */
    private fun buildDeviceCapabilities(): DeviceCapabilities =
        DeviceCapabilities(
            hardware = buildHardwareCapabilities(),
            localProcessing = buildLocalProcessingCapabilities(),
            media = buildMediaCapabilities(),
            system = buildSystemCapabilities(),
        )

    /**
     * Build hardware capabilities.
     */
    private fun buildHardwareCapabilities(): HardwareCapabilities =
        HardwareCapabilities(
            flashlight = hasFlashlight(),
            camera = hasCamera(),
            microphone = hasMicrophone(),
            haptics = hasHaptics(),
            screenCapture = false, // Requires MediaProjection, not available by default
            biometric = hasBiometric(),
        )

    /**
     * Build local processing capabilities.
     */
    private fun buildLocalProcessingCapabilities(): LocalProcessingCapabilities =
        LocalProcessingCapabilities(
            stt = false, // Vosk removed, using server-side Whisper
            contentTypeDetection = true, // ContentTypeDetector is always available
            fts5Search = true, // FTS5 is always available
            localCommands =
                listOf(
                    "time",
                    "date",
                    "battery",
                    "flashlight",
                    "volume",
                ),
        )

    /**
     * Build media capabilities.
     */
    private fun buildMediaCapabilities(): MediaCapabilities =
        MediaCapabilities(
            audioPlayback = true, // Media3 ExoPlayer available
            localMusicLibrary = hasExternalStoragePermission(),
            visualizer = true, // Audio visualizer available
        )

    /**
     * Build system capabilities.
     */
    private fun buildSystemCapabilities(): SystemCapabilities {
        val deviceClass = determineDeviceClass()
        val (availableMemory, storageFree) = getMemoryAndStorage()

        return SystemCapabilities(
            osVersion = Build.VERSION.SDK_INT,
            deviceClass = deviceClass,
            availableMemoryMb = availableMemory,
            storageFreeMb = storageFree,
        )
    }

    /**
     * Build device context (current state).
     */
    private fun buildDeviceContext(): DeviceContext {
        val batteryInfo = getBatteryInfo()
        val networkInfo = getNetworkInfo()

        return DeviceContext(
            batteryLevel = batteryInfo.first,
            isCharging = batteryInfo.second,
            networkType = networkInfo.first,
            isNetworkValidated = networkInfo.second,
        )
    }

    // =============================================================================
    // HARDWARE CHECKS
    // =============================================================================

    private fun hasFlashlight(): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)

    private fun hasCamera(): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    private fun hasMicrophone(): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

    private fun hasHaptics(): Boolean {
        // Most modern Android devices have vibration support
        return true
    }

    private fun hasBiometric(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) ||
                context.packageManager.hasSystemFeature("android.hardware.biometrics.face")
        } else {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        }

    private fun hasExternalStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }

    // =============================================================================
    // SYSTEM INFO
    // =============================================================================

    private fun determineDeviceClass(): String {
        val (availableMemory, _) = getMemoryAndStorage()
        val cpuCount = Runtime.getRuntime().availableProcessors()

        return when {
            availableMemory >= 4096 && cpuCount >= 8 -> "HIGH"
            availableMemory >= 2048 && cpuCount >= 4 -> "MEDIUM"
            else -> "LOW"
        }
    }

    private fun getMemoryAndStorage(): Pair<Long, Long> {
        // Available memory (approximate)
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / (1024 * 1024) // MB

        // Available storage
        val dataDir = context.filesDir
        val stat = StatFs(dataDir.path)
        val availableStorage = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024) // MB

        return Pair(maxMemory, availableStorage)
    }

    // =============================================================================
    // BATTERY INFO
    // =============================================================================

    private fun getBatteryInfo(): Pair<Int, Boolean> {
        val batteryIntent =
            context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                ?: return Pair(0, false)

        val level = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        val status = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)

        val batteryPercent =
            if (level >= 0 && scale > 0) {
                (level * 100) / scale
            } else {
                0
            }

        val isCharging =
            status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL

        return Pair(batteryPercent, isCharging)
    }

    // =============================================================================
    // NETWORK INFO
    // =============================================================================

    private fun getNetworkInfo(): Pair<String, Boolean> {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager

        val network = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(network)

        if (caps == null) {
            return Pair("none", false)
        }

        val networkType =
            when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "unknown"
            }

        val isValidated = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        return Pair(networkType, isValidated)
    }

    // =============================================================================
    // HANDSHAKE EXECUTION
    // =============================================================================

    /**
     * Perform handshake with server.
     * Returns HandshakeResponse on success, null on failure.
     */
    suspend fun performHandshake(client: io.ktor.client.HttpClient): HandshakeResponse? =
        try {
            val request = buildHandshakeRequest()
            Log.d(TAG, "Performing handshake: deviceId=${request.deviceId}, protocol=${request.protocolVersion}")

            // Server integration point: POST /api/session/init
            // Expected request: HandshakeRequest(deviceId, protocolVersion, capabilities)
            // Expected response: HandshakeResponse(sessionId, executionPolicy)
            //
            // Example implementation:
            // val response = client.post("${serverUrl}/api/session/init") {
            //     contentType(ContentType.Application.Json)
            //     setBody(request)
            // }.body<HandshakeResponse>()

            // Mock response for development/testing
            HandshakeResponse(
                sessionId = "session_${System.currentTimeMillis()}",
                executionPolicy =
                    ExecutionPolicy(
                        serverSide =
                            listOf(
                                "CREATE_NOTE",
                                "UPDATE_NOTE",
                                "DELETE_NOTE",
                                "SEARCH_NOTES",
                                "SUMMARIZE_NOTE",
                                "WEB_SEARCH",
                                "BATCH_ACTIONS",
                                "SCHEDULE_EVENT",
                                "MEMORY_STORE",
                                "GENERATE_BRIEFING",
                                "ANALYZE_DOCUMENT",
                            ),
                        deviceSide =
                            listOf(
                                "TOGGLE_FLASHLIGHT",
                                "SET_VOLUME",
                                "PLAY_AUDIO",
                                "LAUNCH_APP",
                                "TAKE_SCREENSHOT",
                                "TRIGGER_HAPTIC",
                                "CAPTURE_SCREEN_CONTEXT",
                            ),
                        hybrid =
                            listOf(
                                HybridActionPolicy(
                                    action = "TRANSCRIBE_AUDIO",
                                    prefer = "server",
                                    fallback = "device",
                                    condition = "network_validated == true",
                                ),
                            ),
                    ),
                syncState =
                    RemoteSyncState(
                        lastServerTimestamp =
                            java.time.Instant
                                .now()
                                .toString(),
                        pendingSyncCount = 0,
                    ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Handshake failed: ${e.message}", e)
            null
        }
}
