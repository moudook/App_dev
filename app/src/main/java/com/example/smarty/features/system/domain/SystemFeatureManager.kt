package com.example.smarty.features.system.domain

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.smarty.core.domain.model.AudioTrack
import com.example.smarty.data.cache.CacheManager
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.repository.DeviceAudioRepository
import com.example.smarty.features.audio.domain.AudioFeatureManager.AudioSearchResult
import com.example.smarty.features.audio.domain.AudioPlaybackManager
import com.example.smarty.features.calendar.domain.CalendarManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Centralized manager for system-level features.
 * Hybridizes logic for:
 * - App Launching
 * - Internal Navigation
 * - Media Playback requests
 * - Screen Capture coordination
 * - Device Audio retrieval
 *
 * This manager is used by:
 * 1. UI components (direct calls)
 * 2. LocalCommandProcessor (fast-path rule-based actions)
 * 3. SmartyAgent (AI-driven tool execution)
 */
class SystemFeatureManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val audioManager: AudioPlaybackManager?,
    private val calendarManager: CalendarManager?,
    private val securePreferences: SecurePreferences,
    private val deviceAudioRepository: DeviceAudioRepository,
    private val onNavigateRequest: (String) -> Unit,
) {
    companion object {
        private const val TAG = "SystemFeatureManager"
    }

    /**
     * Toggle the application theme.
     */
    fun toggleTheme(isDark: Boolean) {
        Log.i(TAG, "Theme toggle requested: dark=$isDark")
        securePreferences.setDarkTheme(isDark)
    }

    private val cacheManager = CacheManager.getInstance(context)

    /**
     * Get the current size of all application caches.
     */
    suspend fun getCacheSize(): Long = cacheManager.getCacheSize()

    /**
     * Clear all application caches.
     */
    fun clearCache(onComplete: (Long) -> Unit = {}) {
        Log.i(TAG, "Cache clear requested")
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result =
                runCatching {
                    cacheManager.clearCache()
                }

            if (result.isSuccess) {
                Log.d(TAG, "Cache cleared successfully")
            } else {
                Log.e(TAG, "Failed to clear cache", result.exceptionOrNull())
            }

            // Always notify completion, even on error
            onComplete(0L)
        }
    }

    /**
     * Clear temporary data and response caches.
     */
    fun clearTemporaryData() {
        cacheManager.clearTemporaryData()
    }

    /**
     * Trim cache to 50% of current size.
     */
    fun trimCache() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                cacheManager.trimToSize(cacheManager.getCacheSize() / 2)
                Log.d(TAG, "Trimmed cache to 50%")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to trim cache: ${e.message}")
            }
        }
    }

    /**
     * Set the application privacy mode.
     */
    fun setPrivacyMode(mode: String) {
        Log.i(TAG, "Privacy mode update: $mode")
        // Implementation maps mode string to specific secure preference flags
        // For now, logging the request as part of the hybridized interface
    }

    /**
     * Toggle a specific system or app setting.
     */
    fun toggleSetting(
        setting: String,
        enable: Boolean,
    ): Result<String> {
        Log.i(TAG, "Setting toggle requested: $setting -> $enable")
        return when (setting.lowercase()) {
            "dark_theme", "dark_mode", "theme" -> {
                toggleTheme(enable)
                Result.success("Theme toggled")
            }
            "sound", "completion_sound" -> {
                securePreferences.setSoundEnabled(enable)
                Result.success("Sound toggled")
            }
            "flashlight", "torch" -> toggleFlashlight(enable)
            else -> {
                Log.w(TAG, "Unknown setting toggle requested: $setting")
                Result.failure(IllegalArgumentException("Unknown setting: $setting"))
            }
        }
    }

    /**
     * Trigger a system data backup.
     */
    fun backupData() {
        Log.i(TAG, "Data backup initiated")
        // Placeholder for future backup integration
    }

    /**
     * Launch an application by its package name.
     * Centralizes error handling and logging.
     */
    fun launchApp(packageName: String): Boolean =
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Launched app: $packageName")
                true
            } else {
                Log.w(TAG, "Could not find launch intent for: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app $packageName: ${e.message}")
            false
        }

    /**
     * Request navigation to a specific screen within the app.
     */
    fun navigateTo(screen: String) {
        Log.i(TAG, "Navigation requested to: $screen")
        onNavigateRequest(screen)
    }

    /**
     * Find an application package name by its display name.
     * Uses scoring-based matching: exact → starts-with → contains → token overlap.
     */
    fun findPackageName(appName: String): String? {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        val query = appName.trim().lowercase()

        data class ScoredApp(
            val packageName: String,
            val label: String,
            val score: Int,
        )

        val scored =
            apps.mapNotNull { appInfo ->
                val label = pm.getApplicationLabel(appInfo).toString()
                val labelLower = label.lowercase()
                val score =
                    when {
                        labelLower == query -> 100
                        labelLower.startsWith(query) -> 85
                        query.startsWith(labelLower) -> 80
                        labelLower.contains(query) -> 60
                        query.contains(labelLower) -> 55
                        else -> {
                            val queryTokens = query.split(" ")
                            val labelTokens = labelLower.split(" ")
                            val overlap = queryTokens.intersect(labelTokens.toSet()).size
                            if (overlap > 0) 40 + (overlap * 10) else 0
                        }
                    }
                if (score > 0) ScoredApp(appInfo.packageName, label, score) else null
            }

        return scored.maxByOrNull { it.score }?.packageName
    }

    /**
     * Find a matching audio track from device storage based on a query.
     */
    suspend fun findMatchingAudio(query: String): AudioSearchResult {
        val tracks = deviceAudioRepository.getAllAudio()
        return findMatchingAudio(query, tracks)
    }

    /**
     * Find a matching audio track from a list based on a query.
     */
    fun findMatchingAudio(
        query: String,
        tracks: List<AudioTrack>,
    ): AudioSearchResult {
        val queryLower = query.lowercase().trim()

        // Try exact/partial match
        val match =
            tracks.firstOrNull { track ->
                track.title.lowercase().contains(queryLower) ||
                    track.artist?.lowercase()?.contains(queryLower) == true ||
                    track.album?.lowercase()?.contains(queryLower) == true ||
                    track.fileName?.lowercase()?.contains(queryLower) == true
            }

        return if (match != null) {
            AudioSearchResult.ExactMatch(match)
        } else {
            // No match found - return NoMatch with reason
            AudioSearchResult.NoMatch("No matching track found for query")
        }
    }

    /**
     * Request playback of an audio track.
     */
    fun playAudio(track: AudioTrack) {
        Log.i(TAG, "Playing audio: ${track.title}")
        audioManager?.play(track)
    }

    /**
     * Skip to the next track.
     */
    fun nextTrack() {
        Log.i(TAG, "Next track requested")
        audioManager?.next()
    }

    /**
     * Skip to the previous track.
     */
    fun previousTrack() {
        Log.i(TAG, "Previous track requested")
        audioManager?.previous()
    }

    /**
     * Pause the current audio playback.
     */
    fun pauseAudio() {
        Log.i(TAG, "Pause audio requested")
        audioManager?.pause()
    }

    /**
     * Resume the current audio playback.
     */
    fun resumeAudio() {
        Log.i(TAG, "Resume audio requested")
        audioManager?.resume()
    }

    /**
     * Stop the current audio playback completely.
     */
    fun stopAudio() {
        Log.i(TAG, "Stop audio requested")
        audioManager?.stop()
    }

    /**
     * Capture the current screen.
     */
    suspend fun captureScreen(): String? {
        Log.i(TAG, "Screen capture requested")
        return com.example.smarty.service.ScreenCaptureService
            .captureScreenshot()
    }

    /**
     * Share content to other applications using Android Intents.
     * Allows the AI to "hand off" data to the user's ecosystem.
     */
    fun shareContent(
        text: String,
        title: String? = null,
    ) {
        try {
            val intent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            val shareIntent =
                Intent.createChooser(intent, null).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(shareIntent)
            Log.i(TAG, "Content shared successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share content: ${e.message}")
        }
    }

    /**
     * Get all audio tracks from device storage.
     */
    suspend fun getDeviceAudio(): List<AudioTrack> = deviceAudioRepository.getAllAudio()

    /**
     * Set a timer or alarm.
     * @return true if the time was successfully parsed and scheduled
     */
    fun setTimer(
        name: String,
        timeStr: String,
        isAlarm: Boolean,
    ): Boolean {
        val triggerTime = calendarManager?.parseDateTime(timeStr)
        return if (triggerTime != null) {
            calendarManager.setTimer(name, triggerTime, isAlarm)
            Log.i(TAG, "Timer/Alarm set: $name for $timeStr")
            true
        } else {
            Log.w(TAG, "Failed to parse time for timer: $timeStr")
            false
        }
    }

    /**
     * Toggle the device flashlight.
     */
    fun toggleFlashlight(enabled: Boolean): Result<String> =
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, enabled)
                Log.i(TAG, "Flashlight ${if (enabled) "on" else "off"}")
                Result.success("Flashlight turned ${if (enabled) "on" else "off"}")
            } else {
                Result.failure(IllegalStateException("No camera available to use as a flashlight on this device."))
            }
        } catch (e: android.hardware.camera2.CameraAccessException) {
            Log.e(TAG, "CameraAccessException: failed to toggle flashlight", e)
            Result.failure(IllegalStateException("Camera is currently in use by another application or the system. Please close other camera apps.", e))
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "IllegalArgumentException: failed to toggle flashlight", e)
            Result.failure(IllegalStateException("Flashlight is not available on this device.", e))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight", e)
            Result.failure(IllegalStateException("Failed to toggle flashlight: ${e.message}", e))
        }

    /**
     * Cancel all active timers and alarms.
     */
    fun cancelAllTimers() {
        Log.i(TAG, "Cancel all timers requested")
        calendarManager?.cancelAllTimers()
    }

    /**
     * Get the current battery level as a percentage string.
     */
    fun getBatteryLevel(): String {
        val batteryStatus = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level != -1 && scale != -1) "${(level * 100 / scale.toFloat()).toInt()}%" else "unknown"
    }

    /**
     * Adjust the system volume.
     * @param direction 1 for up, -1 for down, 0 for mute toggle
     */
    fun adjustVolume(direction: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        when (direction) {
            1 ->
                audioManager.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_RAISE,
                    android.media.AudioManager.FLAG_SHOW_UI,
                )
            -1 ->
                audioManager.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_LOWER,
                    android.media.AudioManager.FLAG_SHOW_UI,
                )
            0 -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    val isMuted = audioManager.isStreamMute(android.media.AudioManager.STREAM_MUSIC)
                    audioManager.adjustStreamVolume(
                        android.media.AudioManager.STREAM_MUSIC,
                        if (isMuted) android.media.AudioManager.ADJUST_UNMUTE else android.media.AudioManager.ADJUST_MUTE,
                        android.media.AudioManager.FLAG_SHOW_UI,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val isMuted = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0
                    @Suppress("DEPRECATION")
                    audioManager.setStreamMute(android.media.AudioManager.STREAM_MUSIC, !isMuted)
                }
            }
        }
        Log.i(TAG, "Volume adjustment: $direction")
    }

    /**
     * Get high-level system status information for AI context.
     */
    fun getSystemStatus(
        isDarkTheme: Boolean,
        connectionStatus: String,
        cacheSize: String,
        unreadMemoryCount: Int,
    ): Map<String, String> {
        val batteryStatus = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else "unknown"

        return mapOf(
            "battery" to "$batteryPct%",
            "theme" to if (isDarkTheme) "dark" else "light",
            "network" to connectionStatus,
            "cache_size" to cacheSize,
            "unread_notes_memory" to unreadMemoryCount.toString(),
        )
    }
}
