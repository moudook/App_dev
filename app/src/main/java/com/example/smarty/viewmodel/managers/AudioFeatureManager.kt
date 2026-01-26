package com.example.smarty.viewmodel.managers

import android.util.Log
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.repository.DeviceAudioRepository
import kotlinx.coroutines.CoroutineScope

/**
 * Centralized manager for Audio playback and control.
 * Hybridizes logic for:
 * - Audio playback control (play/pause/resume/stop/seek)
 * - Device audio discovery and search
 * - Playback state management
 * - Audio file metadata retrieval
 *
 * This manager ensures consistent audio control across:
 * 1. UI components (direct calls)
 * 2. LocalCommandProcessor (voice commands)
 * 3. JarvisAgent (AI-driven audio control)
 *
 * INTEGRATION POINTS:
 * - AudioPlaybackManager (for playback control)
 * - DeviceAudioRepository (for audio file discovery)
 * - AudioPlayerService (indirectly via AudioPlaybackManager)
 */
class AudioFeatureManager(
    private val audioPlaybackManager: AudioPlaybackManager,
    private val deviceAudioRepository: DeviceAudioRepository,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AudioFeatureManager"
    }

    // ==================== Playback Control ====================

    /**
     * Play an audio track.
     */
    fun play(track: AudioTrack) {
        Log.i(TAG, "Playing: ${track.title}")
        audioPlaybackManager.play(track)
    }

    /**
     * Pause the current playback.
     */
    fun pause() {
        Log.i(TAG, "Pausing playback")
        audioPlaybackManager.pause()
    }

    /**
     * Resume the paused playback.
     */
    fun resume() {
        Log.i(TAG, "Resuming playback")
        audioPlaybackManager.resume()
    }

    /**
     * Stop playback completely.
     */
    fun stop() {
        Log.i(TAG, "Stopping playback")
        audioPlaybackManager.stop()
    }

    /**
     * Seek to a specific position in milliseconds.
     */
    fun seekTo(positionMs: Long) {
        Log.i(TAG, "Seeking to: $positionMs ms")
        audioPlaybackManager.seekTo(positionMs)
    }

    /**
     * Toggle between play and pause.
     */
    fun togglePlayPause() {
        Log.i(TAG, "Toggling play/pause")
        audioPlaybackManager.togglePlayPause()
    }

    // ==================== State Queries ====================

    /**
     * Get the current track being played.
     */
    fun getCurrentTrack(): AudioTrack? {
        return audioPlaybackManager.currentTrack
    }

    /**
     * Check if audio is currently playing.
     */
    fun isPlaying(): Boolean {
        return audioPlaybackManager.isPlaying
    }

    /**
     * Get the current playback position in milliseconds.
     */
    fun getCurrentPosition(): Long {
        return audioPlaybackManager.currentPosition
    }

    /**
     * Get the total duration of the current track in milliseconds.
     */
    fun getDuration(): Long {
        return audioPlaybackManager.duration
    }

    /**
     * Get the current playback progress (0-1).
     */
    fun getProgress(): Float {
        return audioPlaybackManager.progress
    }

    // ==================== Audio Discovery ====================

    /**
     * Get all audio files from device storage.
     */
    fun getAllAudioTracks(): List<AudioTrack> {
        return deviceAudioRepository.getAllAudio()
    }

    /**
     * Find an audio track matching a query string.
     * Searches across title, artist, album, and filename.
     *
     * @param query Search query
     * @return Matching audio track, or null if not found
     */
    fun findAudioTrack(query: String): AudioTrack? {
        val tracks = getAllAudioTracks()
        return findMatchingAudio(query, tracks)
    }

    /**
     * Find a matching audio track from a provided list.
     * Useful for pre-filtered searches (e.g., by artist).
     *
     * @param query Search query
     * @param tracks List of tracks to search within
     * @return Matching audio track, or null if not found
     */
    fun findMatchingAudio(query: String, tracks: List<AudioTrack>): AudioTrack? {
        val queryLower = query.lowercase().trim()

        // Try exact match first
        tracks.firstOrNull { track ->
            track.title.lowercase() == queryLower ||
            track.artist?.lowercase() == queryLower ||
            track.album?.lowercase() == queryLower
        }?.let { return it }

        // Fallback to partial match
        return tracks.firstOrNull { track ->
            track.title.lowercase().contains(queryLower) ||
            track.artist?.lowercase()?.contains(queryLower) == true ||
            track.album?.lowercase()?.contains(queryLower) == true ||
            track.fileName?.lowercase()?.contains(queryLower) == true
        }
    }

    /**
     * Search for audio tracks by artist.
     *
     * @param artist Artist name
     * @return List of tracks by that artist
     */
    fun findTracksByArtist(artist: String): List<AudioTrack> {
        val artistLower = artist.lowercase().trim()
        return getAllAudioTracks().filter {
            it.artist?.lowercase()?.contains(artistLower) == true
        }
    }

    /**
     * Search for audio tracks by album.
     *
     * @param album Album name
     * @return List of tracks from that album
     */
    fun findTracksByAlbum(album: String): List<AudioTrack> {
        val albumLower = album.lowercase().trim()
        return getAllAudioTracks().filter {
            it.album?.lowercase()?.contains(albumLower) == true
        }
    }

    /**
     * Get audio statistics for reporting.
     *
     * @return Map of statistics (total tracks, artists, albums)
     */
    fun getAudioStats(): Map<String, Int> {
        val tracks = getAllAudioTracks()
        val artists = tracks.mapNotNull { it.artist }.distinct().size
        val albums = tracks.mapNotNull { it.album }.distinct().size

        return mapOf(
            "total_tracks" to tracks.size,
            "unique_artists" to artists,
            "unique_albums" to albums
        )
    }

    // ==================== Playback Requests ====================

    /**
     * Request audio playback (used by AI agent).
     * This sets a pending state that the UI observes.
     */
    fun requestPlayback(track: AudioTrack) {
        Log.i(TAG, "Playback requested: ${track.title}")
        audioPlaybackManager.requestPlayback(track)
    }

    /**
     * Clear pending audio playback request.
     * Called by UI after starting playback.
     */
    fun clearPendingPlayback() {
        audioPlaybackManager.clearPendingAudioPlayback()
    }
}
