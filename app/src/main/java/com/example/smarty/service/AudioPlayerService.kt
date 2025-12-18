package com.example.smarty.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.audiofx.Visualizer
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

        // Real-time audio amplitude (0-1 normalized)
        private val _currentAmplitude = MutableStateFlow(0f)
        val currentAmplitude: StateFlow<Float> = _currentAmplitude.asStateFlow()

        // Frequency band amplitudes (0-1 normalized) for multi-band visualization
        // Bass: 20-250 Hz - Deep, punchy movements
        // Mid: 250-2000 Hz - Melody, vocals
        // Treble: 2000-20000 Hz - Highs, sparkles
        private val _bassAmplitude = MutableStateFlow(0f)
        val bassAmplitude: StateFlow<Float> = _bassAmplitude.asStateFlow()

        private val _midAmplitude = MutableStateFlow(0f)
        val midAmplitude: StateFlow<Float> = _midAmplitude.asStateFlow()

        private val _trebleAmplitude = MutableStateFlow(0f)
        val trebleAmplitude: StateFlow<Float> = _trebleAmplitude.asStateFlow()

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
    private var visualizer: Visualizer? = null
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
                playWhenReady = true // Auto-play when ready
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

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "Player error: ${error.message}", error)
            updateState {
                copy(
                    isPlaying = false,
                    playbackState = PlaybackState.ERROR
                )
            }
            stopPositionUpdates()
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

    private fun setupVisualizer() {
        try {
            val audioSessionId = player?.audioSessionId ?: return
            if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
                Log.w(TAG, "Audio session ID not set yet")
                return
            }

            // Release existing visualizer
            releaseVisualizer()

            visualizer = Visualizer(audioSessionId).apply {
                // Use larger capture size for better frequency resolution
                // 512 samples at 44100 Hz = ~86 Hz per bin (good for bass separation)
                val sizes = Visualizer.getCaptureSizeRange()
                captureSize = minOf(512, sizes[1]) // 512 or max available

                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {
                            waveform?.let { data ->
                                // Calculate RMS amplitude from waveform
                                var sum = 0.0
                                for (byte in data) {
                                    // Convert unsigned byte to signed value (-128 to 127 centered at 128)
                                    val sample = (byte.toInt() and 0xFF) - 128
                                    sum += sample * sample
                                }
                                val rms = kotlin.math.sqrt(sum / data.size)
                                // Normalize to 0-1 range (max RMS for full scale is ~90)
                                val normalizedAmplitude = (rms / 90.0).coerceIn(0.0, 1.0).toFloat()
                                _currentAmplitude.value = normalizedAmplitude
                            }
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) {
                            fft?.let { data ->
                                // FFT data format: [DC, bin1_real, bin1_imag, bin2_real, bin2_imag, ...]
                                // Number of usable bins = captureSize / 2
                                // Frequency per bin = samplingRate / captureSize
                                val captureSize = data.size
                                val freqPerBin = samplingRate.toFloat() / captureSize

                                // Calculate frequency band boundaries
                                // Bass: 20-250 Hz, Mid: 250-2000 Hz, Treble: 2000-20000 Hz
                                val bassEndBin = (250 / freqPerBin).toInt().coerceIn(1, captureSize / 4)
                                val midEndBin = (2000 / freqPerBin).toInt().coerceIn(bassEndBin + 1, captureSize / 2)
                                val trebleEndBin = (captureSize / 2 - 1).coerceAtLeast(midEndBin + 1)

                                // Calculate magnitude for each frequency band
                                var bassSum = 0.0
                                var midSum = 0.0
                                var trebleSum = 0.0
                                var bassCount = 0
                                var midCount = 0
                                var trebleCount = 0

                                // Skip DC component (index 0), process real/imag pairs
                                for (i in 1 until captureSize / 2) {
                                    val realIndex = i * 2
                                    val imagIndex = realIndex + 1

                                    if (imagIndex >= data.size) break

                                    // Get real and imaginary parts (signed bytes)
                                    val real = data[realIndex].toInt()
                                    val imag = data[imagIndex].toInt()

                                    // Calculate magnitude: sqrt(real² + imag²)
                                    val magnitude = kotlin.math.sqrt((real * real + imag * imag).toDouble())

                                    // Assign to appropriate band
                                    when {
                                        i <= bassEndBin -> {
                                            // Apply bass boost (low frequencies are naturally quieter)
                                            bassSum += magnitude * 1.5
                                            bassCount++
                                        }
                                        i <= midEndBin -> {
                                            midSum += magnitude
                                            midCount++
                                        }
                                        i <= trebleEndBin -> {
                                            // Apply treble boost (high frequencies decay faster)
                                            trebleSum += magnitude * 1.2
                                            trebleCount++
                                        }
                                    }
                                }

                                // Average and normalize each band (max magnitude ~180 for byte range)
                                val bassAvg = if (bassCount > 0) bassSum / bassCount else 0.0
                                val midAvg = if (midCount > 0) midSum / midCount else 0.0
                                val trebleAvg = if (trebleCount > 0) trebleSum / trebleCount else 0.0

                                // Normalize to 0-1 with some headroom
                                _bassAmplitude.value = (bassAvg / 120.0).coerceIn(0.0, 1.0).toFloat()
                                _midAmplitude.value = (midAvg / 100.0).coerceIn(0.0, 1.0).toFloat()
                                _trebleAmplitude.value = (trebleAvg / 80.0).coerceIn(0.0, 1.0).toFloat()
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate(), // Max capture rate for responsive visualization
                    true,  // Waveform (for overall amplitude)
                    true   // FFT (for frequency bands)
                )
                enabled = true
            }
            Log.d(TAG, "Visualizer setup complete for session: $audioSessionId, captureSize: ${visualizer?.captureSize}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup visualizer", e)
        }
    }

    private fun releaseVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null
            _currentAmplitude.value = 0f
            _bassAmplitude.value = 0f
            _midAmplitude.value = 0f
            _trebleAmplitude.value = 0f
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing visualizer", e)
        }
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
            val uri = if (track.uri.startsWith("http")) {
                android.net.Uri.parse(track.uri)
            } else {
                track.uri.toUri()
            }

            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .apply {
                    track.mimeType?.let { setMimeType(it) }
                }
                .build()

            Log.d(TAG, "Setting media item: $uri, mimeType: ${track.mimeType}")

            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }

        updateState {
            copy(
                currentTrack = track,
                currentPosition = 0L,
                isPlaying = true,
                playbackState = PlaybackState.BUFFERING
            )
        }

        // Setup visualizer for audio amplitude capture
        setupVisualizer()

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
        releaseVisualizer()
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
            .setSmallIcon(android.R.drawable.ic_media_play) // Safer system icon
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        releaseVisualizer()
        stopPositionUpdates()
        // Safely release player and media session
        player?.release()
        player = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
