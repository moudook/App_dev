package com.example.smarty.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarty.data.model.AudioPlayerUiState
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.PlaybackState
import com.example.smarty.data.model.formatDuration
import com.example.smarty.service.AudioPlayerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
        _waveformData
    ) { playerState, waveform ->
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
            playbackState = playerState.playbackState
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AudioPlayerUiState()
    )

    init {
        // Observe player state to show/hide mini player
        viewModelScope.launch {
            AudioPlayerService.playerState.collect { state ->
                _isMiniPlayerVisible.value = state.currentTrack != null &&
                        state.playbackState != PlaybackState.IDLE
            }
        }
    }

    /**
     * Play an audio track
     */
    fun playAudio(track: AudioTrack) {
        Log.d(TAG, "Playing audio: ${track.title}")
        AudioPlayerService.play(getApplication(), track)
        _isMiniPlayerVisible.value = true
    }

    /**
     * Toggle play/pause
     */
    fun togglePlayPause() {
        val currentState = AudioPlayerService.playerState.value
        if (currentState.isPlaying) {
            AudioPlayerService.pause(getApplication())
        } else {
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
