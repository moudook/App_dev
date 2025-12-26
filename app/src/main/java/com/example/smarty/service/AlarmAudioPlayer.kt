package com.example.smarty.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Plays alarm audio for a specified duration.
 * Uses a singleton pattern to manage a single MediaPlayer instance.
 *
 * NOTE: User will provide the alarm audio file in ~2 days.
 * Currently using a placeholder implementation that logs playback.
 */
object AlarmAudioPlayer {

    private const val TAG = "AlarmAudioPlayer"

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null
    private var failsafeRunnable: Runnable? = null

    // Maximum allowed duration to prevent indefinite playback (failsafe)
    private const val MAX_DURATION_MS = 60_000L  // 60 seconds max

    /**
     * Play alarm audio for the specified duration.
     *
     * @param context Application context
     * @param duration Duration in milliseconds (default 5000ms = 5 seconds, max 60 seconds)
     */
    fun play(context: Context, duration: Long = 5000) {
        // Clamp duration to prevent indefinite playback
        val safeDuration = duration.coerceIn(1000L, MAX_DURATION_MS)
        Log.d(TAG, "Playing alarm audio for ${safeDuration}ms")

        // Stop any existing playback
        stop()

        // Cancel any existing runnables first
        stopRunnable?.let { handler.removeCallbacks(it) }
        failsafeRunnable?.let { handler.removeCallbacks(it) }

        try {
            // TODO: User will provide audio file in ~2 days
            // PLACEHOLDER: Replace with actual audio URI when provided
            // The audio file should be placed in res/raw/alarm_sound.mp3 (or .ogg, .wav)
            //
            // When audio is provided, uncomment and use:
            // val audioUri = Uri.parse("android.resource://${context.packageName}/${R.raw.alarm_sound}")
            //
            // mediaPlayer = MediaPlayer().apply {
            //     setAudioAttributes(
            //         AudioAttributes.Builder()
            //             .setUsage(AudioAttributes.USAGE_ALARM)
            //             .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            //             .build()
            //     )
            //     setDataSource(context, audioUri)
            //     prepare()
            //     start()
            // }

            // TEMPORARY: Use system default notification sound as placeholder
            val defaultAlarmUri = android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_ALARM
            ) ?: android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_NOTIFICATION
            )

            if (defaultAlarmUri != null) {
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )

                    // Add error listener to prevent orphaned MediaPlayer (fixes memory leak)
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                        stop()
                        true
                    }

                    // Add completion listener as backup
                    setOnCompletionListener {
                        Log.d(TAG, "MediaPlayer completed")
                        // Don't call stop() here as looping should handle it
                    }

                    setDataSource(context, defaultAlarmUri)
                    isLooping = true  // Loop until stopped
                    prepare()
                    start()
                }
                Log.d(TAG, "Playing system default alarm sound")
            } else {
                Log.w(TAG, "No alarm sound available")
            }

            // Schedule stop after duration (primary timeout)
            stopRunnable = Runnable { stop() }
            handler.postDelayed(stopRunnable!!, safeDuration)

            // Failsafe timeout at 2x duration to prevent indefinite playback - now tracked so it can be removed
            failsafeRunnable = Runnable {
                if (isPlaying()) {
                    Log.w(TAG, "Failsafe timeout triggered - stopping alarm")
                    stop()
                }
            }
            handler.postDelayed(failsafeRunnable!!, safeDuration * 2)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to play alarm audio: ${e.message}", e)
            stop()
        }
    }

    /**
     * Stop any currently playing alarm audio.
     */
    fun stop() {
        Log.d(TAG, "Stopping alarm audio")

        // Remove ALL pending runnables
        stopRunnable?.let { handler.removeCallbacks(it) }
        failsafeRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
        failsafeRunnable = null

        // Release media player
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media player: ${e.message}")
        }
        mediaPlayer = null
    }

    /**
     * Check if audio is currently playing.
     */
    fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying == true
        } catch (e: Exception) {
            false
        }
    }
}
