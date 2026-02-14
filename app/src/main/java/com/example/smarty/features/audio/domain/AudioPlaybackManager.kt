package com.example.smarty.features.audio.domain

import android.content.Context
import android.util.Log
import com.example.smarty.core.domain.model.AudioPlayerState
import com.example.smarty.core.domain.model.AudioTrack
import com.example.smarty.core.domain.model.PlaybackState
import com.example.smarty.service.AudioPlayerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages audio playback coordination between ViewModel and AudioPlayerService.
 *
 * Responsibilities:
 * - Track pending audio playback requests (from AI agent or user actions)
 * - Provide convenience methods for playback control
 * - Expose audio player state for UI components
 * - Handle transitions between tracks
 *
 * Note: Actual playback is handled by AudioPlayerService. This manager
 * provides a clean interface for the ViewModel to interact with audio playback.
 *
 * @property context Application context for starting the service
 * @property scope Coroutine scope for async operations
 */
class AudioPlaybackManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AudioPlaybackManager"
    }

    // ==================== Pending Playback State ====================

    /**
     * Audio playback request from AI agent (observed by UI to trigger playback).
     * When non-null, the UI should start playback and then clear this.
     */
    private val _pendingAudioPlayback = MutableStateFlow<AudioTrack?>(null)
    val pendingAudioPlayback: StateFlow<AudioTrack?> = _pendingAudioPlayback.asStateFlow()

    /**
     * Request playback of an audio track.
     * Sets the pending track which will be observed by UI components.
     */
    fun requestPlayback(track: AudioTrack) {
        Log.i(TAG, "Playback requested: ${track.title}")
        _pendingAudioPlayback.value = track
    }

    /**
     * Clear the pending audio playback request.
     * Call this after the UI has started playback.
     */
    fun clearPendingAudioPlayback() {
        _pendingAudioPlayback.value = null
    }

    // ==================== Direct Playback Control ====================

    /**
     * Start playing an audio track immediately.
     * This bypasses the pending mechanism and directly starts playback.
     */
    fun play(track: AudioTrack) {
        Log.d(TAG, "Playing track: ${track.title}")
        AudioPlayerService.play(context, track)
    }

    /**
     * Start playing a list of audio tracks immediately.
     */
    fun playList(tracks: List<AudioTrack>) {
        if (tracks.isEmpty()) return
        Log.d(TAG, "Playing list: ${tracks.size} tracks")
        AudioPlayerService.playList(context, tracks)
    }

    /**
     * Pause the current playback.
     */
    fun pause() {
        Log.d(TAG, "Pausing playback")
        AudioPlayerService.pause(context)
    }

    /**
     * Resume the paused playback.
     */
    fun resume() {
        Log.d(TAG, "Resuming playback")
        AudioPlayerService.resume(context)
    }

    /**
     * Stop playback completely.
     */
    fun stop() {
        Log.d(TAG, "Stopping playback")
        AudioPlayerService.stop(context)
    }

    /**
     * Seek to a specific position in the current track.
     *
     * @param position Position in milliseconds
     */
    fun seekTo(position: Long) {
        Log.d(TAG, "Seeking to: $position ms")
        AudioPlayerService.seekTo(context, position)
    }

    /**
     * Skip to next track.
     */
    fun next() {
        Log.d(TAG, "Skipping to next track")
        AudioPlayerService.next(context)
    }

    /**
     * Skip to previous track.
     */
    fun previous() {
        Log.d(TAG, "Skipping to previous track")
        AudioPlayerService.previous(context)
    }

    /**
     * Toggle play/pause state.
     */
    fun togglePlayPause() {
        val state = AudioPlayerService.playerState.value
        if (state.playbackState == PlaybackState.PLAYING) {
            pause()
        } else if (state.currentTrack != null) {
            resume()
        }
    }

    // ==================== Player State Exposure ====================

    /**
     * Current player state (from AudioPlayerService).
     * Includes current track, playback state, position, duration, etc.
     */
    val playerState: StateFlow<AudioPlayerState> = AudioPlayerService.playerState

    /**
     * Current audio amplitude (0-1 normalized) for visualizations.
     */
    val currentAmplitude: StateFlow<Float> = AudioPlayerService.currentAmplitude

    /**
     * Bass frequency amplitude (20-250 Hz) for visualizations.
     */
    val bassAmplitude: StateFlow<Float> = AudioPlayerService.bassAmplitude

    /**
     * Mid frequency amplitude (250-2000 Hz) for visualizations.
     */
    val midAmplitude: StateFlow<Float> = AudioPlayerService.midAmplitude

    /**
     * Treble frequency amplitude (2000-20000 Hz) for visualizations.
     */
    val trebleAmplitude: StateFlow<Float> = AudioPlayerService.trebleAmplitude

    // ==================== Convenience Properties ====================

    /**
     * Check if audio is currently playing.
     */
    val isPlaying: Boolean
        get() = playerState.value.playbackState == PlaybackState.PLAYING

    /**
     * Check if there's a current track loaded.
     */
    val hasTrack: Boolean
        get() = playerState.value.currentTrack != null

    /**
     * Get the current track, if any.
     */
    val currentTrack: AudioTrack?
        get() = playerState.value.currentTrack

    /**
     * Get the current playback position in milliseconds.
     */
    val currentPosition: Long
        get() = playerState.value.currentPosition

    /**
     * Get the total duration of the current track in milliseconds.
     */
    val duration: Long
        get() = playerState.value.duration

    /**
     * Get the current playback progress (0-1).
     */
    val progress: Float
        get() {
            val state = playerState.value
            return if (state.duration > 0) {
                state.currentPosition.toFloat() / state.duration.toFloat()
            } else 0f
        }

    // ==================== Lifecycle Methods ====================

    /**
     * Notify service that app entered foreground.
     * Enables high-frequency position updates for smooth UI.
     */
    fun onAppEnterForeground() {
        AudioPlayerService.enterForeground(context)
    }

    /**
     * Notify service that app entered background.
     * Reduces resource usage while maintaining playback.
     */
    fun onAppEnterBackground() {
        AudioPlayerService.enterBackground(context)
    }
}
