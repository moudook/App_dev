package com.example.smarty.core.common.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.smarty.MainActivity
import com.example.smarty.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages completion sound playback for AI agent and notecard processing.
 *
 * Features:
 * - Thread-safe playback with mutex lock
 * - Foreground: Plays audio directly
 * - Background: Sends notification with custom sound
 * - Prevents overlapping with music playback
 * - Singleton pattern for consistent state
 *
 * Usage:
 * - Call playCompletionSound() when AI agent or notecard processing completes
 * - Automatically detects foreground/background state
 * - Does NOT play for AI assistant (voice mode) - caller should filter
 */
class CompletionSoundManager private constructor(
    private val context: Context,
) {
    companion object {
        private const val TAG = "CompletionSound"

        // Notification channel for processing completion
        const val CHANNEL_ID = "processing_completion"
        private const val CHANNEL_NAME = "Processing Complete"
        private const val CHANNEL_DESCRIPTION = "Notifications when AI processing completes"

        // Notification IDs
        private const val NOTIFICATION_ID_AGENT = 2001
        private const val NOTIFICATION_ID_NOTECARD = 2002

        @Volatile
        private var instance: CompletionSoundManager? = null

        fun getInstance(context: Context): CompletionSoundManager =
            instance ?: synchronized(this) {
                instance ?: CompletionSoundManager(context.applicationContext).also {
                    instance = it
                }
            }
    }

    // Thread-safety
    private val playbackMutex = Mutex()
    private val isPlaying = AtomicBoolean(false)

    // MediaPlayer for foreground playback
    private var mediaPlayer: MediaPlayer? = null

    // Audio manager for checking music playback
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    /**
     * Create notification channel with custom sound.
     * Must be called before sending notifications (done in init).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Sound URI for notification
            val soundUri = Uri.parse("android.resource://${context.packageName}/${R.raw.completion_sound}")

            val audioAttributes =
                AudioAttributes
                    .Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = CHANNEL_DESCRIPTION
                    setSound(soundUri, audioAttributes)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 200, 100, 200)
                }

            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created with custom sound")
        }
    }

    /**
     * Play completion sound when AI agent finishes processing.
     *
     * @param isAppInForeground True if app is visible to user
     * @param title Optional title for notification (used in background)
     */
    suspend fun playAgentCompletionSound(
        isAppInForeground: Boolean,
        title: String = context.getString(R.string.ai_response_ready),
    ) = withContext(Dispatchers.Main) {
        playCompletionSound(
            isAppInForeground = isAppInForeground,
            notificationId = NOTIFICATION_ID_AGENT,
            title = title,
            message = context.getString(R.string.ai_finished_processing),
        )
    }

    /**
     * Play completion sound when notecard processing finishes.
     *
     * @param isAppInForeground True if app is visible to user
     * @param noteTitle Optional note title for notification
     */
    suspend fun playNotecardCompletionSound(
        isAppInForeground: Boolean,
        noteTitle: String? = null,
    ) = withContext(Dispatchers.Main) {
        val message =
            if (noteTitle != null) {
                context.getString(R.string.note_processed_detail, noteTitle)
            } else {
                context.getString(R.string.note_categorized)
            }

        playCompletionSound(
            isAppInForeground = isAppInForeground,
            notificationId = NOTIFICATION_ID_NOTECARD,
            title = context.getString(R.string.note_processed),
            message = message,
        )
    }

    /**
     * Core playback logic with thread-safety and overlap prevention.
     */
    private suspend fun playCompletionSound(
        isAppInForeground: Boolean,
        notificationId: Int,
        title: String,
        message: String,
    ) {
        // Use mutex to prevent concurrent playback attempts
        playbackMutex.withLock {
            // Skip if already playing
            if (isPlaying.get()) {
                Log.d(TAG, "Skipping - already playing completion sound")
                return@withLock
            }

            // Skip if music is playing (user is listening to something)
            if (isMusicPlaying()) {
                Log.d(TAG, "Skipping - music is playing, don't interrupt")
                return@withLock
            }

            try {
                isPlaying.set(true)

                if (isAppInForeground) {
                    // Foreground: Play sound directly
                    playDirectSound()
                } else {
                    // Background: Send notification with sound
                    sendNotification(notificationId, title, message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing completion sound: ${e.message}", e)
            } finally {
                // Note: isPlaying is reset in onCompletion callback for direct playback
                // For notifications, reset immediately since notification handles its own sound
                if (!isAppInForeground) {
                    isPlaying.set(false)
                }
            }
        }
    }

    /**
     * Play sound directly using MediaPlayer (foreground mode).
     */
    private fun playDirectSound() {
        // Capture reference to outer class's isPlaying (MediaPlayer has its own isPlaying property)
        val playingState = this.isPlaying

        try {
            // Release any existing player
            releaseMediaPlayer()

            val player = MediaPlayer()
            mediaPlayer = player
            
            player.apply {
                setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .build(),
                    )

                    // Use AssetFileDescriptor for raw resource (safest approach)
                    val afd = context.resources.openRawResourceFd(R.raw.completion_sound)
                    if (afd != null) {
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        afd.close()
                    } else {
                        Log.e(TAG, "Failed to open raw resource fd for completion_sound")
                        playingState.set(false)
                        releaseMediaPlayer()
                        return
                    }

                    setOnCompletionListener {
                        Log.d(TAG, "Completion sound finished")
                        playingState.set(false)
                        releaseMediaPlayer()
                    }

                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                        playingState.set(false)
                        releaseMediaPlayer()
                        true
                    }

                    prepare()
                    start()
                    Log.d(TAG, "Playing completion sound (foreground)")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play direct sound: ${e.message}", e)
            playingState.set(false)
            releaseMediaPlayer()
        }
    }

    /**
     * Send notification with custom sound (background mode).
     */
    private fun sendNotification(
        notificationId: Int,
        title: String,
        message: String,
    ) {
        try {
            // Intent to open app when notification is tapped
            val intent =
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }

            val pendingIntent =
                PendingIntent.getActivity(
                    context,
                    notificationId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    // Sound is set via notification channel on Android O+
                    // For older versions, set it explicitly
                    .apply {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                            setSound(Uri.parse("android.resource://${context.packageName}/${R.raw.completion_sound}"))
                        }
                    }.build()

            notificationManager.notify(notificationId, notification)
            Log.d(TAG, "Sent completion notification: $title")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send notification: ${e.message}", e)
        }
    }

    /**
     * Check if music is currently playing.
     * We don't want to interrupt the user's music.
     */
    private fun isMusicPlaying(): Boolean =
        try {
            audioManager.isMusicActive
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check music state: ${e.message}")
            false
        }

    /**
     * Release MediaPlayer resources.
     */
    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaPlayer: ${e.message}")
        }
        mediaPlayer = null
    }

    /**
     * Clean up resources. Call when app is destroyed.
     */
    fun shutdown() {
        releaseMediaPlayer()
        isPlaying.set(false)
        Log.d(TAG, "CompletionSoundManager shutdown")
    }
}
