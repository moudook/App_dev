package com.example.smarty.agent.tools.external

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.util.Log
import com.example.smarty.agent.tools.base.AudioPlaybackResult
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.getAttachments
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.search.SemanticSearchEngine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable
data class PlayAudioArgs(
    @property:LLMDescription("Search query describing what audio to play (song name, artist, audio file name, etc.)")
    val query: String,
    @property:LLMDescription("Optional note ID if playing audio attached to a specific note")
    val noteId: String? = null,
    @property:LLMDescription("Index of the audio attachment in the note (default: 0)")
    val attachmentIndex: Int = 0,
    @property:LLMDescription("Optional start time like '1:30', '2 minutes', '45 seconds'")
    val startTime: String? = null
)

/**
 * Tool for requesting audio playback from notes.
 * Searches note attachments to find audio files and plays them via the audio service.
 */
class PlayAudioTool(
    private val getActiveNotes: () -> List<Note>,
    private val onPlayAudio: (AudioTrack) -> Unit
) : Tool<PlayAudioArgs, AudioPlaybackResult>() {

    override val argsSerializer: KSerializer<PlayAudioArgs> = PlayAudioArgs.serializer()
    override val resultSerializer: KSerializer<AudioPlaybackResult> = AudioPlaybackResult.serializer()

    companion object {
        private const val TAG = "PlayAudioTool"
    }

    override val name = "play_audio"

    override val description = """
        MUST USE THIS TOOL when user says: "play", "play music", "play audio", "play song", "play podcast".
        Searches and plays audio files attached to user's notes.
        Required: query parameter with search term (filename, song name, or keyword).
        Example: User says "play jazz" → call play_audio with query="jazz"
        Example: User says "play my podcast" → call play_audio with query="podcast"
    """.trimIndent()

    override suspend fun execute(args: PlayAudioArgs): AudioPlaybackResult {
        return try {
            if (args.query.isBlank()) {
                return AudioPlaybackResult(
                    success = false,
                    action = "play",
                    message = "Query cannot be empty",
                    error = "Empty query"
                )
            }

            Log.d(TAG, "Searching for audio: '${args.query}' noteId=${args.noteId}")

            // If noteId provided, get audio from that specific note
            // SECURITY: Must verify note is AI-accessible (not private)
            if (args.noteId != null) {
                val note = getActiveNotes().find { it.id == args.noteId }
                    ?.takeIf { PrivacyGuard.canAiProcess(it) }
                    ?: return AudioPlaybackResult(
                        success = false,
                        action = "play",
                        message = "Audio not found",  // Generic message - don't leak existence
                        error = "Note not found"
                    )

                val attachments = note.getAttachments()
                    .filter { it.mimeType.startsWith("audio/") }

                if (attachments.isEmpty()) {
                    return AudioPlaybackResult(
                        success = false,
                        action = "play",
                        message = "No audio files found in this note",
                        error = "No audio attachments"
                    )
                }

                val attachment = attachments.getOrNull(args.attachmentIndex) ?: attachments[0]
                val startPositionMs = parseTimeToMs(args.startTime)
                val track = AudioTrack(
                    uri = attachment.uri,
                    title = attachment.fileName,
                    fileName = attachment.fileName,
                    mimeType = attachment.mimeType,
                    sourceNoteId = note.id,
                    sourceAttachmentId = attachment.id
                )

                Log.d(TAG, "Playing from note: ${track.title}, startPosition=${startPositionMs}ms")
                onPlayAudio(track)

                return AudioPlaybackResult(
                    success = true,
                    action = "play",
                    trackTitle = attachment.fileName,
                    message = "Now playing '${attachment.fileName}' from note '${note.title}'"
                )
            }

            // OPTIMIZED: Search audio-categorized notes FIRST for better performance
            // SECURITY: Filter to only AI-accessible notes (excludes private notes)
            val allNotes = PrivacyGuard.getAiVisibleNotes(getActiveNotes())

            // Step 1: Search only audio-type notes first (faster, more accurate)
            val audioNotes = allNotes.filter { note ->
                note.type == com.example.smarty.data.model.NoteType.AUDIO ||
                note.getAttachments().any { it.mimeType.startsWith("audio/") } ||
                note.fileMimeType?.startsWith("audio/") == true
            }

            Log.d(TAG, "Found ${audioNotes.size} audio-categorized notes to search")

            // Try audio notes first
            var matchingAudio = findMatchingAudio(audioNotes, args.query)

            // Step 2: If not found in audio notes, fall back to all notes
            if (matchingAudio == null && audioNotes.size < allNotes.size) {
                Log.d(TAG, "No match in audio notes, searching all ${allNotes.size} notes")
                matchingAudio = findMatchingAudio(allNotes, args.query)
            }

            if (matchingAudio != null) {
                Log.d(TAG, "Found matching audio: ${matchingAudio.title}")
                onPlayAudio(matchingAudio)

                return AudioPlaybackResult(
                    success = true,
                    action = "play",
                    trackTitle = matchingAudio.title,
                    message = "Now playing '${matchingAudio.title}'"
                )
            }

            // No audio found
            AudioPlaybackResult(
                success = false,
                action = "play",
                message = "No audio file found matching '${args.query}'. Make sure the audio is saved in a note.",
                error = "Audio not found"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Audio playback error: ${e.message}", e)
            AudioPlaybackResult(
                success = false,
                action = "play",
                message = "Failed to play audio",
                error = e.message
            )
        }
    }

    /**
     * Search notes for audio files matching the query using SEMANTIC SEARCH.
     * Uses fuzzy matching, phonetic similarity, and token overlap for better results.
     * Can find "pretty little baby" even if file is "pretty_baby.mp3" or "prettybaby.mp3".
     */
    private fun findMatchingAudio(notes: List<Note>, query: String): AudioTrack? {
        Log.d(TAG, "Semantic search for audio in ${notes.size} notes: '$query'")

        // Build a list of searchable audio items
        data class AudioItem(
            val track: AudioTrack,
            val noteTitle: String,
            val noteContent: String,
            val fileName: String
        )

        val audioItems = mutableListOf<AudioItem>()

        for (note in notes) {
            val attachments = note.getAttachments()
                .filter { it.mimeType.startsWith("audio/") }

            if (attachments.isEmpty()) continue

            for (attachment in attachments) {
                val track = AudioTrack(
                    uri = attachment.uri,
                    title = attachment.fileName,
                    fileName = attachment.fileName,
                    mimeType = attachment.mimeType,
                    sourceNoteId = note.id,
                    sourceAttachmentId = attachment.id
                )
                audioItems.add(AudioItem(
                    track = track,
                    noteTitle = note.title,
                    noteContent = note.content,
                    fileName = attachment.fileName
                ))
            }
        }

        if (audioItems.isEmpty()) {
            Log.d(TAG, "No audio items found in notes")
            return null
        }

        Log.d(TAG, "Found ${audioItems.size} audio items to search")

        // Use semantic search with multiple text fields per item
        val results = SemanticSearchEngine.search(
            query = query,
            items = audioItems,
            textExtractor = { item ->
                listOf(
                    item.fileName,           // Primary: filename
                    item.noteTitle,          // Secondary: note title
                    item.noteContent.take(500)  // Tertiary: note content (truncated)
                )
            },
            minScore = 0.25  // Lower threshold for more inclusive matching
        )

        if (results.isEmpty()) {
            Log.d(TAG, "No semantic matches found for '$query'")

            // Fallback: Try individual word matching with very low threshold
            val queryWords = SemanticSearchEngine.tokenize(query)
            val fallbackResults = audioItems.mapNotNull { item ->
                val combinedText = "${item.fileName} ${item.noteTitle} ${item.noteContent}"
                var bestWordScore = 0.0

                for (word in queryWords) {
                    val wordScore = SemanticSearchEngine.calculateSimilarity(word, combinedText)
                    if (wordScore > bestWordScore) bestWordScore = wordScore
                }

                if (bestWordScore >= 0.20) {
                    bestWordScore to item
                } else null
            }.sortedByDescending { it.first }

            if (fallbackResults.isNotEmpty()) {
                val best = fallbackResults.first().second
                Log.d(TAG, "Fallback match: ${best.fileName} (note: ${best.noteTitle})")
                return best.track
            }

            return null
        }

        val bestMatch = results.first()
        Log.d(TAG, "Best semantic match: ${bestMatch.item.fileName} " +
                "(score: ${String.format("%.2f", bestMatch.score)}, " +
                "type: ${bestMatch.matchType}, note: ${bestMatch.item.noteTitle})")

        return bestMatch.item.track
    }

    /**
     * Parse time strings like "1:30", "2 minutes", "45 seconds" to milliseconds.
     */
    private fun parseTimeToMs(timeStr: String?): Long {
        if (timeStr.isNullOrBlank()) return 0L

        val str = timeStr.lowercase().trim()

        // Format: "1:30" or "01:30" (mm:ss)
        val colonPattern = Regex("""(\d+):(\d{1,2})""")
        colonPattern.find(str)?.let { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: 0
            val seconds = match.groupValues[2].toLongOrNull() ?: 0
            return (minutes * 60 + seconds) * 1000
        }

        // Format: "2 minutes" or "2 min"
        val minutesPattern = Regex("""(\d+)\s*(?:minutes?|mins?)""")
        minutesPattern.find(str)?.let { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: 0
            return minutes * 60 * 1000
        }

        // Format: "45 seconds" or "45 sec"
        val secondsPattern = Regex("""(\d+)\s*(?:seconds?|secs?)""")
        secondsPattern.find(str)?.let { match ->
            val seconds = match.groupValues[1].toLongOrNull() ?: 0
            return seconds * 1000
        }

        return 0L
    }
}
