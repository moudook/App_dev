package com.example.smarty.features.audio.domain

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarty.core.domain.model.AudioPlayerUiState
import com.example.smarty.core.domain.model.AudioTrack
import com.example.smarty.core.domain.model.PlaybackState
import com.example.smarty.core.domain.model.formatDuration
import com.example.smarty.service.AudioPlayerService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for managing audio player state and UI
 * Observes AudioPlayerService state and provides UI-friendly state
 */
class AudioPlayerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AudioPlayerViewModel"
    }

    // Mini player visibility
    private val _isMiniPlayerVisible = MutableStateFlow(false)
    val isMiniPlayerVisible: StateFlow<Boolean> = _isMiniPlayerVisible.asStateFlow()

    // Full player visibility
    private val _isFullPlayerVisible = MutableStateFlow(false)
    val isFullPlayerVisible: StateFlow<Boolean> = _isFullPlayerVisible.asStateFlow()

    // Waveform data (can be set when audio is loaded)
    private val _waveformData = MutableStateFlow<List<Float>>(emptyList())
    val waveformData: StateFlow<List<Float>> = _waveformData.asStateFlow()

    // UI state derived from service state
    val uiState: StateFlow<AudioPlayerUiState> = combine(
        AudioPlayerService.playerState,
        _waveformData,
        AudioPlayerService.currentAmplitude,
        AudioPlayerService.bassAmplitude,
        AudioPlayerService.midAmplitude,
        AudioPlayerService.trebleAmplitude
    ) { flows ->
        val playerState = flows[0] as com.example.smarty.core.domain.model.AudioPlayerState
        @Suppress("UNCHECKED_CAST")
        val waveform = flows[1] as List<Float>
        val amplitude = flows[2] as Float
        val bass = flows[3] as Float
        val mid = flows[4] as Float
        val treble = flows[5] as Float

        val progress = if (playerState.duration > 0) {
            (playerState.currentPosition.toFloat() / playerState.duration).coerceIn(0f, 1f)
        } else 0f

        AudioPlayerUiState(
            isPlaying = playerState.isPlaying,
            currentTrack = playerState.currentTrack,
            progress = progress,
            currentPositionFormatted = formatDuration(playerState.currentPosition),
            durationFormatted = formatDuration(playerState.duration),
            waveformData = waveform,
            playbackState = playerState.playbackState,
            currentAmplitude = amplitude,
            bassAmplitude = bass,
            midAmplitude = mid,
            trebleAmplitude = treble
        )
    }
    .distinctUntilChanged()
    .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AudioPlayerUiState()
    )

    // Track if playback was explicitly requested to prevent auto-hiding during state transitions
    private var isPlaybackRequested = false

    // Track if paused due to speech recognition
    private val _pausedDueToSpeech = MutableStateFlow(false)

    // Job for auto-hiding player after playback ends
    private var autoHideJob: Job? = null

    // Auto-hide delay in milliseconds
    private val AUTO_HIDE_DELAY = 1500L

    init {
        // Observe player state to show/hide mini player
        viewModelScope.launch {
            AudioPlayerService.playerState.collect { state ->
                when (state.playbackState) {
                    PlaybackState.ERROR -> {
                        _isMiniPlayerVisible.value = false
                        isPlaybackRequested = false
                        autoHideJob?.cancel()
                        android.widget.Toast.makeText(
                            getApplication(),
                            getApplication<Application>().getString(com.example.smarty.R.string.audio_playback_error),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    PlaybackState.ENDED -> {
                        // Auto-hide after playback ends
                        autoHideJob?.cancel()
                        autoHideJob = viewModelScope.launch {
                            delay(AUTO_HIDE_DELAY)
                            _isMiniPlayerVisible.value = false
                            _isFullPlayerVisible.value = false
                        }
                    }
                    PlaybackState.PLAYING -> {
                        // Cancel any pending auto-hide when playback resumes
                        autoHideJob?.cancel()
                        isPlaybackRequested = false
                        _isMiniPlayerVisible.value = true
                    }
                    else -> {
                        // Only update visibility if we are NOT waiting for a requested playback to start
                        // OR if the state confirms playback/buffering has begun
                        val isActive = state.currentTrack != null && state.playbackState != PlaybackState.IDLE

                        if (isActive) {
                            // Playback is confirmed active, so we can clear the request flag and show player
                            isPlaybackRequested = false
                            _isMiniPlayerVisible.value = true
                        } else if (!isPlaybackRequested) {
                            // Only hide if we aren't waiting for a request to fulfill
                            _isMiniPlayerVisible.value = false
                        }
                        // Else: keep current visibility (true) while waiting for request to process
                    }
                }
            }
        }
    }

    /**
     * Play an audio track
     */
    fun playAudio(track: AudioTrack) {
        Log.d(TAG, "Playing audio: ${track.title}")
        isPlaybackRequested = true
        _isMiniPlayerVisible.value = true // Show immediately for responsiveness
        AudioPlayerService.play(getApplication(), track)
    }

    /**
     * Toggle play/pause
     */
    fun togglePlayPause() {
        // Reset speech pause flag on manual toggle
        _pausedDueToSpeech.value = false
        val currentState = AudioPlayerService.playerState.value
        if (currentState.isPlaying) {
            AudioPlayerService.pause(getApplication())
        } else {
            AudioPlayerService.resume(getApplication())
        }
    }

    /**
     * Handle speech recognition state changes.
     * Pauses audio when speech recognition starts and resumes when it stops.
     */
    fun onSpeechListeningChanged(isListening: Boolean) {
        val currentState = AudioPlayerService.playerState.value

        if (isListening && currentState.isPlaying) {
            // Speech recognition started while audio is playing - pause
            Log.d(TAG, "Pausing audio for speech recognition")
            _pausedDueToSpeech.value = true
            AudioPlayerService.pause(getApplication())
        } else if (!isListening && _pausedDueToSpeech.value) {
            // Speech recognition stopped and we previously paused - resume
            Log.d(TAG, "Resuming audio after speech recognition")
            _pausedDueToSpeech.value = false
            AudioPlayerService.resume(getApplication())
        }
    }

    /**
     * Seek to a specific position (0-1 progress)
     */
    fun seekTo(progress: Float) {
        val duration = AudioPlayerService.playerState.value.duration
        if (duration > 0) {
            val position = (progress * duration).toLong()
            AudioPlayerService.seekTo(getApplication(), position)
        }
    }

    /**
     * Seek to a specific position in milliseconds
     */
    fun seekToPosition(position: Long) {
        AudioPlayerService.seekTo(getApplication(), position)
    }

    /**
     * Stop playback and hide player
     */
    fun stop() {
        Log.d(TAG, "Stopping playback")
        isPlaybackRequested = false
        _pausedDueToSpeech.value = false
        autoHideJob?.cancel()
        AudioPlayerService.stop(getApplication())
        _isMiniPlayerVisible.value = false
        _isFullPlayerVisible.value = false
    }

    /**
     * Expand mini player to full screen
     */
    fun expandToFullPlayer() {
        _isFullPlayerVisible.value = true
    }

    /**
     * Collapse full player to mini player
     */
    fun collapseToMiniPlayer() {
        _isFullPlayerVisible.value = false
    }

    /**
     * Set waveform data for visualization
     */
    fun setWaveformData(data: List<Float>) {
        _waveformData.value = data
    }

    /**
     * Clear waveform data
     */
    fun clearWaveformData() {
        _waveformData.value = emptyList()
    }
}
