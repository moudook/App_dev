package com.example.smarty.core.domain.model

import kotlinx.serialization.Serializable
import com.example.smarty.core.common.util.PrivacyAware
import java.util.UUID

/**
 * Source of audio track - where it originated from
 */
enum class AudioSource {
    NOTE_ATTACHMENT,   // Audio attached to a note (internal storage)
    DEVICE_STORAGE     // Audio from device's shared storage (MediaStore)
}

/**
 * Represents an audio track for playback (Agent version, serializable)
 * Can be from a note attachment, standalone audio note, or device storage
 */
@Serializable
data class AgentAudioTrack(
    val id: String = UUID.randomUUID().toString(),
    val uri: String,
    val title: String,
    val fileName: String? = null,
    val duration: Long = 0L,  // Duration in milliseconds
    val sourceNoteId: String? = null,  // If from a Note
    val sourceAttachmentId: String? = null,  // If from an Attachment
    val mimeType: String? = null,
    // NEW: Fields for device storage audio (all have defaults for backward compatibility)
    val source: AudioSource = AudioSource.NOTE_ATTACHMENT,
    val artist: String? = null,
    val album: String? = null
)

/**
 * Search result for audio tracks
 */
sealed class AudioSearchResult {
    /** Exact or partial match found */
    data class ExactMatch(val track: AgentAudioTrack) : AudioSearchResult()

    /** No direct match found, providing fallback recommendations */
    data class Fallback(val tracks: List<AgentAudioTrack>, val reason: String) : AudioSearchResult()
}
