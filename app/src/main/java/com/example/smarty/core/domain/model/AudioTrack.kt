package com.example.smarty.core.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

/**
 * Represents an audio track for playback
 * Can be from a note attachment, standalone audio note, or device storage
 */
@Parcelize
data class AudioTrack(
    val id: String = UUID.randomUUID().toString(),
    val uri: String,
    val title: String,
    val fileName: String? = null,
    val duration: Long = 0L, // Duration in milliseconds
    val sourceNoteId: String? = null, // If from a Note
    val sourceAttachmentId: String? = null, // If from an Attachment
    val mimeType: String? = null,
    // NEW: Fields for device storage audio (all have defaults for backward compatibility)
    val source: com.example.smarty.core.domain.model.AudioSource = com.example.smarty.core.domain.model.AudioSource.NOTE_ATTACHMENT,
    val artist: String? = null,
    val album: String? = null,
) : Parcelable

/**
 * State of the audio player
 */
data class AudioPlayerState(
    val isPlaying: Boolean = false,
    val currentTrack: AudioTrack? = null,
    val currentPosition: Long = 0L, // Current position in milliseconds
    val duration: Long = 0L, // Total duration in milliseconds
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val waveformData: List<Float> = emptyList(), // Normalized amplitudes (0-1)
)

/**
 * Playback state enum
 */
enum class PlaybackState {
    IDLE,
    BUFFERING,
    READY,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR,
}

/**
 * UI state for audio player components
 */
data class AudioPlayerUiState(
    val isPlaying: Boolean = false,
    val currentTrack: AudioTrack? = null,
    val progress: Float = 0f, // 0-1 progress
    val currentPositionFormatted: String = "0:00",
    val durationFormatted: String = "0:00",
    val waveformData: List<Float> = emptyList(),
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val currentAmplitude: Float = 0f, // Real-time audio amplitude (0-1)
    // Frequency band amplitudes for multi-band visualization
    val bassAmplitude: Float = 0f, // 20-250 Hz - Deep, punchy movements
    val midAmplitude: Float = 0f, // 250-2000 Hz - Melody, vocals
    val trebleAmplitude: Float = 0f, // 2000-20000 Hz - Highs, sparkles
) {
    val hasTrack: Boolean get() = currentTrack != null
}

/**
 * Format milliseconds to MM:SS string
 */
fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
