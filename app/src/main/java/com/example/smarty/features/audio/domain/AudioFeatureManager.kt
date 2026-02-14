package com.example.smarty.features.audio.domain

import android.util.Log
import com.example.smarty.core.domain.model.AudioTrack
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
 * 3. SmartyAgent (AI-driven audio control)
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
     * Play a list of audio tracks.
     */
    fun playList(tracks: List<AudioTrack>) {
        Log.i(TAG, "Playing list: ${tracks.size} tracks")
        audioPlaybackManager.playList(tracks)
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
     * Skip to the next track.
     */
    fun next() {
        Log.i(TAG, "Next track requested")
        audioPlaybackManager.next()
    }

    /**
     * Skip to the previous track.
     */
    fun previous() {
        Log.i(TAG, "Previous track requested")
        audioPlaybackManager.previous()
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
     * Result of an audio search operation.
     */
    sealed class AudioSearchResult {
        /** Exact or partial match found - safe to auto-play */
        data class ExactMatch(val track: AudioTrack) : AudioSearchResult()

        /** Fuzzy match with confidence score - auto-play if score is high */
        data class FuzzyMatch(val track: AudioTrack, val confidence: Double) : AudioSearchResult()

        /** Multiple possible matches - show suggestions to user */
        data class Suggestions(val tracks: List<AudioTrack>, val message: String) : AudioSearchResult()

        /** No match found - do NOT play random music */
        data class NoMatch(val reason: String) : AudioSearchResult()

        /** Legacy: No direct match found, providing fallback recommendations */
        @Deprecated("Use Suggestions or NoMatch instead")
        data class Fallback(val tracks: List<AudioTrack>, val reason: String) : AudioSearchResult()
    }

    /**
     * Get all audio files from device storage.
     *
     * @return List of AudioTrack
     */
    suspend fun getAllAudioTracks(): List<AudioTrack> {
        return deviceAudioRepository.getAllAudio()
    }

    /**
     * Find an audio track matching a query string.
     * Searches across title, artist, album, and filename.
     *
     * @param query Search query
     * @return AudioSearchResult with matching track or fallback tracks
     */
    suspend fun findAudioTrack(query: String): AudioSearchResult {
        val tracks = getAllAudioTracks()
        return findMatchingAudio(query, tracks)
    }

    /**
     * Find a matching audio track from a provided list.
     * Useful for pre-filtered searches (e.g., by artist).
     *
     * @param query Search query
     * @param tracks List of tracks to search within
     * @return AudioSearchResult with matching track or fallback tracks
     */
    fun findMatchingAudio(query: String, tracks: List<AudioTrack>): AudioSearchResult {
        val queryLower = query.lowercase().trim()
        if (queryLower.isBlank()) return AudioSearchResult.NoMatch("Empty search query")

        // Try exact match first
        val exactMatch = tracks.firstOrNull { track ->
            track.title.lowercase() == queryLower ||
            track.artist?.lowercase() == queryLower ||
            track.album?.lowercase() == queryLower
        }

        if (exactMatch != null) return AudioSearchResult.ExactMatch(exactMatch)

        // Fallback to partial match (contains)
        val partialMatch = tracks.firstOrNull { track ->
            track.title.lowercase().contains(queryLower) ||
            track.artist?.lowercase()?.contains(queryLower) == true ||
            track.album?.lowercase()?.contains(queryLower) == true ||
            track.fileName?.lowercase()?.contains(queryLower) == true
        }

        if (partialMatch != null) return AudioSearchResult.ExactMatch(partialMatch)

        // Try fuzzy matching with similarity scoring
        val queryWords = queryLower.split(" ").filter { it.length > 2 }
        if (queryWords.isEmpty()) return AudioSearchResult.NoMatch("No match found for '$query'")

        // Score each track based on word overlap and similarity
        val scoredTracks = tracks.mapNotNull { track ->
            val trackWords = buildList {
                addAll(track.title.lowercase().split(" "))
                track.artist?.lowercase()?.split(" ")?.let { addAll(it) }
                track.album?.lowercase()?.split(" ")?.let { addAll(it) }
            }.filter { it.length > 2 }

            // Calculate similarity score
            var score = 0.0

            for (queryWord in queryWords) {
                // Check for word contains
                val containsBonus = trackWords.count { it.contains(queryWord) || queryWord.contains(it) }
                score += containsBonus * 2.0

                // Check for similar words (Levenshtein-like)
                val bestWordMatch = trackWords.maxOfOrNull { trackWord ->
                    calculateSimilarity(queryWord, trackWord)
                } ?: 0.0
                score += bestWordMatch
            }

            // Normalize by query length
            val normalizedScore = score / queryWords.size.coerceAtLeast(1)

            if (normalizedScore > 0.5) {
                Pair(track, normalizedScore)
            } else null
        }.sortedByDescending { it.second }

        return when {
            scoredTracks.isNotEmpty() && scoredTracks.first().second >= 1.5 -> {
                // High confidence fuzzy match - return as close match
                AudioSearchResult.FuzzyMatch(scoredTracks.first().first, scoredTracks.first().second)
            }
            scoredTracks.isNotEmpty() -> {
                // Lower confidence - return suggestions without auto-playing
                val suggestions = scoredTracks.take(5).map { it.first }
                AudioSearchResult.Suggestions(suggestions, "Did you mean one of these?")
            }
            else -> {
                // No match found - do NOT return random tracks
                AudioSearchResult.NoMatch("No audio found matching '$query'")
            }
        }
    }

    /**
     * Calculate similarity between two strings (0.0 to 1.0).
     * Uses a simple character-based similarity metric.
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1

        // Simple containment check
        if (longer.contains(shorter)) return 0.8

        // Character overlap ratio
        val s1Chars = s1.toSet()
        val s2Chars = s2.toSet()
        val intersection = s1Chars.intersect(s2Chars).size
        val union = s1Chars.union(s2Chars).size

        return if (union > 0) intersection.toDouble() / union else 0.0
    }

    /**
     * Search for audio tracks by artist.
     *
     * @param artist Artist name
     * @return List of tracks by that artist
     */
    suspend fun findTracksByArtist(artist: String): List<AudioTrack> {
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
    suspend fun findTracksByAlbum(album: String): List<AudioTrack> {
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
    suspend fun getAudioStats(): Map<String, Int> {
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

