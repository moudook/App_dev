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
import com.example.smarty.core.domain.model.AudioPlayerState
import com.example.smarty.core.domain.model.AudioTrack
import com.example.smarty.core.domain.model.PlaybackState
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service for audio playback using Media3 ExoPlayer
 * Handles audio focus, notification controls, and background playback
 */
class AudioPlayerService : MediaSessionService() {

    companion object {
        private const val TAG = "AudioPlayerService"
        private const val NOTIFICATION_CHANNEL_ID = "Smarty_audio_player"
        private const val NOTIFICATION_ID = 1001

        // Action constants
        const val ACTION_PLAY = "com.example.smarty.action.PLAY"
        const val ACTION_PLAY_LIST = "com.example.smarty.action.PLAY_LIST"
        const val ACTION_PAUSE = "com.example.smarty.action.PAUSE"
        const val ACTION_RESUME = "com.example.smarty.action.RESUME"
        const val ACTION_STOP = "com.example.smarty.action.STOP"
        const val ACTION_NEXT = "com.example.smarty.action.NEXT"
        const val ACTION_PREV = "com.example.smarty.action.PREV"
        const val ACTION_SEEK = "com.example.smarty.action.SEEK"
        const val ACTION_ENTER_FOREGROUND = "com.example.smarty.action.ENTER_FOREGROUND"
        const val ACTION_ENTER_BACKGROUND = "com.example.smarty.action.ENTER_BACKGROUND"

        // Extra constants
        const val EXTRA_TRACK = "extra_track"
        const val EXTRA_TRACKS = "extra_tracks"
        const val EXTRA_POSITION = "extra_position"

        // Track if app is in foreground for resource optimization
        @Volatile
        private var isAppInForeground = true

        // Position update intervals
        private const val UPDATE_INTERVAL_FOREGROUND = 100L  // 100ms for smooth UI
        private const val UPDATE_INTERVAL_BACKGROUND = 1000L // 1000ms when in background

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

        // Mutex for synchronizing player state access
        private val stateMutex = Mutex()

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
         * Play a list of tracks (queues them after the first one)
         */
        fun playList(context: Context, tracks: List<AudioTrack>) {
            if (tracks.isEmpty()) return
            val intent = Intent(context, AudioPlayerService::class.java).apply {
                action = ACTION_PLAY_LIST
                putParcelableArrayListExtra(EXTRA_TRACKS, ArrayList(tracks))
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
         * Skip to next track
         */
        fun next(context: Context) {
            val intent = Intent(context, AudioPlayerService::class.java).apply {
                action = ACTION_NEXT
            }
            context.startService(intent)
        }

        /**
         * Skip to previous track
         */
        fun previous(context: Context) {
            val intent = Intent(context, AudioPlayerService::class.java).apply {
                action = ACTION_PREV
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
         * Notify service that app entered foreground - enable high-frequency updates
         */
        fun enterForeground(context: Context) {
            isAppInForeground = true
            val intent = Intent(context, AudioPlayerService::class.java).apply {
                action = ACTION_ENTER_FOREGROUND
            }
            context.startService(intent)
        }

        /**
         * Notify service that app entered background - reduce resource usage
         */
        fun enterBackground(context: Context) {
            isAppInForeground = false
            val intent = Intent(context, AudioPlayerService::class.java).apply {
                action = ACTION_ENTER_BACKGROUND
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
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)
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
            getString(R.string.channel_audio_playback_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_audio_playback_desc)
            setShowBadge(false)
        }

        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun initializePlayer() {
        // Audio attributes for music playback
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Use applicationContext to prevent service from being cached in static fields
        val exoPlayer = ExoPlayer.Builder(applicationContext)
            .setAudioAttributes(audioAttributes, true)  // Handle audio focus automatically
            .setHandleAudioBecomingNoisy(true)  // Pause when headphones disconnected
            .build()
            .apply {
                playWhenReady = true // Auto-play when ready
                addListener(playerListener)
            }

        player = exoPlayer

        // Use applicationContext to prevent service from being cached
        mediaSession = MediaSession.Builder(applicationContext, exoPlayer)
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

            serviceScope.launch {
                updateState { copy(playbackState = playbackState) }

                if (state == Player.STATE_ENDED) {
                    // Reset position when track ends
                    updateState { copy(currentPosition = 0L, isPlaying = false) }
                    stopPositionUpdates()
                }
            }

            Log.d(TAG, "Playback state changed: $playbackState")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            serviceScope.launch {
                updateState {
                    copy(
                        isPlaying = isPlaying,
                        playbackState = if (isPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED
                    )
                }

                if (isPlaying) {
                    startPositionUpdates()
                    // Setup visualizer AFTER playback actually starts to avoid audio pops
                    // Small delay ensures audio session is fully initialized
                    delay(150) // Wait for audio to stabilize
                    setupVisualizer()
                } else {
                    stopPositionUpdates()
                }
            }

            Log.d(TAG, "isPlaying changed: $isPlaying")
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "Player error: ${error.message}", error)
            serviceScope.launch {
                updateState {
                    copy(
                        isPlaying = false,
                        playbackState = PlaybackState.ERROR
                    )
                }
                stopPositionUpdates()
            }
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
                // Use faster updates in foreground for smooth UI, slower in background to save resources
                val interval = if (isAppInForeground) UPDATE_INTERVAL_FOREGROUND else UPDATE_INTERVAL_BACKGROUND
                delay(interval)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    /**
     * Called when app enters foreground - enable high-frequency updates and visualizer
     */
    private fun onEnterForeground() {
        Log.d(TAG, "App entered foreground - enabling visualizer")
        // Restart position updates with foreground interval
        if (_playerState.value.playbackState == PlaybackState.PLAYING) {
            startPositionUpdates()
        }
        // Re-enable visualizer for UI
        if (player?.isPlaying == true) {
            setupVisualizer()
        }
    }

    /**
     * Called when app enters background - reduce resource usage
     */
    private fun onEnterBackground() {
        Log.d(TAG, "App entered background - disabling visualizer to save resources")
        // Restart position updates with background interval (slower)
        if (_playerState.value.playbackState == PlaybackState.PLAYING) {
            startPositionUpdates()
        }
        // Disable visualizer to save CPU - no one is watching the UI
        releaseVisualizer()
        // Reset amplitude values since visualizer is off
        _currentAmplitude.value = 0f
        _bassAmplitude.value = 0f
        _midAmplitude.value = 0f
        _trebleAmplitude.value = 0f
    }

    private fun setupVisualizer() {
        var newVisualizer: Visualizer? = null
        try {
            val audioSessionId = player?.audioSessionId ?: return
            if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
                Log.w(TAG, "Audio session ID not set yet")
                return
            }

            // Release existing visualizer
            releaseVisualizer()

            newVisualizer = Visualizer(audioSessionId)
            newVisualizer.apply {
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
                                serviceScope.launch {
                                    stateMutex.withLock {
                                        _currentAmplitude.value = normalizedAmplitude
                                    }
                                }
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
                                val bassNorm = (bassAvg / 120.0).coerceIn(0.0, 1.0).toFloat()
                                val midNorm = (midAvg / 100.0).coerceIn(0.0, 1.0).toFloat()
                                val trebleNorm = (trebleAvg / 80.0).coerceIn(0.0, 1.0).toFloat()

                                serviceScope.launch {
                                    stateMutex.withLock {
                                        _bassAmplitude.value = bassNorm
                                        _midAmplitude.value = midNorm
                                        _trebleAmplitude.value = trebleNorm
                                    }
                                }
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2, // Lower rate to prevent audio artifacts
                    true,  // Waveform (for overall amplitude)
                    true   // FFT (for frequency bands)
                )
                // Small delay before enabling to ensure audio is stable
                enabled = true
            }
            visualizer = newVisualizer
            newVisualizer = null // Transfer ownership
            Log.d(TAG, "Visualizer setup complete")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup visualizer", e)
        } finally {
            // Release if setup failed (newVisualizer wasn't transferred)
            try {
                newVisualizer?.enabled = false
                newVisualizer?.release()
            } catch (_: Exception) {}
        }
    }

    private fun releaseVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null
            serviceScope.launch {
                stateMutex.withLock {
                    _currentAmplitude.value = 0f
                    _bassAmplitude.value = 0f
                    _midAmplitude.value = 0f
                    _trebleAmplitude.value = 0f
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing visualizer", e)
        }
    }

    private suspend inline fun updateState(update: AudioPlayerState.() -> AudioPlayerState) = stateMutex.withLock {
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
                ACTION_PLAY_LIST -> {
                    val tracks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intentVal.getParcelableArrayListExtra(EXTRA_TRACKS, AudioTrack::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intentVal.getParcelableArrayListExtra(EXTRA_TRACKS)
                    }
                    tracks?.let { playTracks(it) }
                }
                ACTION_PAUSE -> pause()
                ACTION_RESUME -> resume()
                ACTION_STOP -> stop()
                ACTION_NEXT -> player?.seekToNextMediaItem()
                ACTION_PREV -> player?.seekToPreviousMediaItem()
                ACTION_SEEK -> {
                    val position = intentVal.getLongExtra(EXTRA_POSITION, 0L)
                    seekTo(position)
                }
                ACTION_ENTER_FOREGROUND -> onEnterForeground()
                ACTION_ENTER_BACKGROUND -> onEnterBackground()
            }
        }

        return START_NOT_STICKY
    }

    private fun playTrack(track: AudioTrack) {
        playTracks(listOf(track))
    }

    private fun playTracks(tracks: List<AudioTrack>) {
        if (tracks.isEmpty()) return
        val firstTrack = tracks[0]
        Log.d(TAG, "Playing tracks: ${tracks.size} items, starting with: ${firstTrack.title}")

        // Safety: Ensure player is initialized (handles service restart scenarios)
        if (player == null) {
            Log.w(TAG, "Player not initialized, reinitializing...")
            initializePlayer()
        }

        // CRITICAL: Stop any currently playing audio first to prevent conflicts
        player?.let { exoPlayer ->
            if (exoPlayer.isPlaying || exoPlayer.playbackState == Player.STATE_READY ||
                exoPlayer.playbackState == Player.STATE_BUFFERING) {
                Log.d(TAG, "Stopping current playback before playing new tracks")
                releaseVisualizer()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
        }

        serviceScope.launch {
            stateMutex.withLock {
                currentTrack = firstTrack
            }
        }

        player?.apply {
            val mediaItems = tracks.map { track ->
                val uri = if (track.uri.startsWith("http")) {
                    android.net.Uri.parse(track.uri)
                } else {
                    track.uri.toUri()
                }
                MediaItem.Builder()
                    .setUri(uri)
                    .apply {
                        track.mimeType?.let { setMimeType(it) }
                    }
                    .build()
            }

            setMediaItems(mediaItems)
            prepare()
            playWhenReady = true
        }

        serviceScope.launch {
            updateState {
                copy(
                    currentTrack = firstTrack,
                    currentPosition = 0L,
                    isPlaying = true,
                    playbackState = PlaybackState.BUFFERING
                )
            }
        }

        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    private fun pause() {
        player?.pause()
    }

    private fun resume() {
        player?.play()
    }

    private fun stop() {
        Log.d(TAG, "Stopping playback - full cleanup")

        // 1. Stop position updates FIRST to prevent state race conditions
        stopPositionUpdates()

        // 2. Remove notification IMMEDIATELY for responsive UI
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground: ${e.message}")
        }

        // 3. Force cancel notification (belt and suspenders approach)
        try {
            val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Error canceling notification: ${e.message}")
        }

        // 4. Release audio resources
        releaseVisualizer()
        player?.stop()
        player?.clearMediaItems()
        player?.release() // Fully release the player to free up audio resources

        // 5. Reset state synchronously to avoid race conditions
        currentTrack = null
        _playerState.value = AudioPlayerState()
        _currentAmplitude.value = 0f
        _bassAmplitude.value = 0f
        _midAmplitude.value = 0f
        _trebleAmplitude.value = 0f

        Log.d(TAG, "Playback stopped, state reset - wake word can now resume")

        // 6. Finally stop the service
        stopSelf()
    }

    private fun seekTo(position: Long) {
        player?.seekTo(position)
        serviceScope.launch {
            updateState { copy(currentPosition = position) }
        }
    }

    private fun createNotification(): Notification {
        // Access currentTrack without lock for notification (read-only, nullable safe)
        val track = currentTrack

        // Use filename for audio files
        val subtitle = when {
            track?.fileName != null -> track.fileName
            else -> getString(R.string.playing)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(track?.title ?: getString(R.string.audio_label))
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_media_play) // Safer system icon
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }

    /**
     * SERVICE-002: Called when the app's task is removed from recents.
     * Ensures graceful cleanup even when user swipes app away.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed from recents - cleaning up")
        stop()
        super.onTaskRemoved(rootIntent)
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

        // Cancel the service scope's job to prevent leak
        serviceJob.cancel()

        // Reset state synchronously since serviceScope is cancelled
        currentTrack = null
        _playerState.value = AudioPlayerState()
        _currentAmplitude.value = 0f
        _bassAmplitude.value = 0f
        _midAmplitude.value = 0f
        _trebleAmplitude.value = 0f

        super.onDestroy()
    }
}
