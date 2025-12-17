package com.example.smarty.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.smarty.R
import com.example.smarty.data.model.AudioPlayerState
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service for audio playback using Media3 ExoPlayer
 * Handles audio focus, notification controls, and background playback
 */
class AudioPlayerService : MediaSessionService() {

    companion object {
        private const val TAG = "AudioPlayerService"
        private const val NOTIFICATION_CHANNEL_ID = "cogni_audio_player"
        private const val NOTIFICATION_ID = 1001

        // Action constants
        const val ACTION_PLAY = "com.example.smarty.action.PLAY"
        const val ACTION_PAUSE = "com.example.smarty.action.PAUSE"
        const val ACTION_RESUME = "com.example.smarty.action.RESUME"
        const val ACTION_STOP = "com.example.smarty.action.STOP"
        const val ACTION_SEEK = "com.example.smarty.action.SEEK"

        // Extra constants
        const val EXTRA_TRACK = "extra_track"
        const val EXTRA_POSITION = "extra_position"

        // Singleton state flow accessible from ViewModel
        private val _playerState = MutableStateFlow(AudioPlayerState())
        val playerState: StateFlow<AudioPlayerState> = _playerState.asStateFlow()

        private var currentTrack: AudioTrack? = null

        /**
         * Start playing an audio track
         */
        fun play(context: Context, track: AudioTrack) {
            val intent = Intent(context, AudioPlayerService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_TRACK, track)
            }
            context.startForegroundService(intent)
        }

        /**
         * Pause playback
         */
        fun pause(context: Context) {
            val intent = Intent(context, AudioPlayerService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        /**
         * Resume playback
         */
        fun resume(context: Context) {
            val intent = Intent(context, AudioPlayerService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }

        /**
         * Stop playback
         */
        fun stop(context: Context) {
            val intent = Intent(context, AudioPlayerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Seek to position
         */
        fun seekTo(context: Context, position: Long) {
            val intent = Intent(context, AudioPlayerService::class.java).apply {
                action = ACTION_SEEK
                putExtra(EXTRA_POSITION, position)
            }
            context.startService(intent)
        }
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionUpdateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        initializePlayer()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Audio Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Audio playback controls"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun initializePlayer() {
        // Audio attributes for music playback
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)  // Handle audio focus automatically
            .setHandleAudioBecomingNoisy(true)  // Pause when headphones disconnected
            .build()
            .apply {
                addListener(playerListener)
            }

        mediaSession = MediaSession.Builder(this, player!!)
            .build()

        Log.d(TAG, "Player initialized")
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val playbackState = when (state) {
                Player.STATE_IDLE -> PlaybackState.IDLE
                Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                Player.STATE_READY -> if (player?.isPlaying == true) PlaybackState.PLAYING else PlaybackState.READY
                Player.STATE_ENDED -> PlaybackState.ENDED
                else -> PlaybackState.IDLE
            }

            updateState { copy(playbackState = playbackState) }

            if (state == Player.STATE_ENDED) {
                // Reset position when track ends
                updateState { copy(currentPosition = 0L, isPlaying = false) }
                stopPositionUpdates()
            }

            Log.d(TAG, "Playback state changed: $playbackState")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateState {
                copy(
                    isPlaying = isPlaying,
                    playbackState = if (isPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED
                )
            }

            if (isPlaying) {
                startPositionUpdates()
            } else {
                stopPositionUpdates()
            }

            Log.d(TAG, "isPlaying changed: $isPlaying")
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = serviceScope.launch {
            while (isActive) {
                player?.let { player ->
                    updateState {
                        copy(
                            currentPosition = player.currentPosition,
                            duration = player.duration.coerceAtLeast(0L)
                        )
                    }
                }
                delay(100)  // Update every 100ms for smooth progress
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    private inline fun updateState(update: AudioPlayerState.() -> AudioPlayerState) {
        _playerState.value = _playerState.value.update()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        intent?.let { intentVal ->
            when (intentVal.action) {
                ACTION_PLAY -> {
                    val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intentVal.getParcelableExtra(EXTRA_TRACK, AudioTrack::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intentVal.getParcelableExtra(EXTRA_TRACK)
                    }
                    track?.let { playTrack(it) }
                }
                ACTION_PAUSE -> pause()
                ACTION_RESUME -> resume()
                ACTION_STOP -> stop()
                ACTION_SEEK -> {
                    val position = intentVal.getLongExtra(EXTRA_POSITION, 0L)
                    seekTo(position)
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun playTrack(track: AudioTrack) {
        Log.d(TAG, "Playing track: ${track.title}")
        currentTrack = track

        player?.apply {
            // Create MediaItem - works for both content:// URIs and http:// URLs
            val mediaItem = if (track.uri.startsWith("http")) {
                // For HTTP URLs, use URI directly
                Log.d(TAG, "Playing HTTP stream: ${track.uri.take(100)}...")
                MediaItem.fromUri(track.uri)
            } else {
                // For local content URIs
                MediaItem.fromUri(track.uri.toUri())
            }

            setMediaItem(mediaItem)
            prepare()
            play()
        }

        updateState {
            copy(
                currentTrack = track,
                currentPosition = 0L,
                isPlaying = true,
                playbackState = PlaybackState.BUFFERING
            )
        }

        // Start foreground service with notification
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun pause() {
        player?.pause()
    }

    private fun resume() {
        player?.play()
    }

    private fun stop() {
        Log.d(TAG, "Stopping playback")
        player?.stop()
        player?.clearMediaItems()
        currentTrack = null

        updateState { AudioPlayerState() }
        stopPositionUpdates()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun seekTo(position: Long) {
        player?.seekTo(position)
        updateState { copy(currentPosition = position) }
    }

    private fun createNotification(): Notification {
        val track = currentTrack

        // Use filename for audio files
        val subtitle = when {
            track?.fileName != null -> track.fileName
            else -> "Playing"
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(track?.title ?: "Audio")
            .setContentText(subtitle)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        stopPositionUpdates()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }
}
