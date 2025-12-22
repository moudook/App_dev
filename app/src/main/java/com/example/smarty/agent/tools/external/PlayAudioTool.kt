package com.example.smarty.agent.tools.external

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.util.Log
import com.example.smarty.agent.tools.base.AudioPlaybackResult
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.getAttachments
import com.example.smarty.data.model.getTags
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

        ROBUST SEARCH: Searches across filename, note title, tags, summary, category, and content.
        Uses fuzzy matching - can find audio even with partial or similar names.

        Required: query parameter with search term.
        Examples:
        - User says "play jazz" → query="jazz"
        - User says "play my podcast" → query="podcast"
        - User says "play pretty little baby" → query="pretty little baby"
        - User says "play that song I tagged as workout" → query="workout"
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
     * Search notes for audio files matching the query using ROBUST SEMANTIC SEARCH.
     * Searches across multiple fields: filename, title, content, tags, summary, category.
     * Uses fuzzy matching, phonetic similarity, and token overlap for better results.
     * Can find "pretty little baby" even if file is "pretty_baby.mp3" or tagged as "baby song".
     */
    private fun findMatchingAudio(notes: List<Note>, query: String): AudioTrack? {
        Log.d(TAG, "Robust semantic search for audio in ${notes.size} notes: '$query'")

        // Build a list of searchable audio items with ALL available metadata
        data class AudioItem(
            val track: AudioTrack,
            val noteTitle: String,
            val noteContent: String,
            val noteSummary: String,
            val noteTags: List<String>,
            val noteCategory: String?,
            val fileName: String
        )

        val audioItems = mutableListOf<AudioItem>()

        for (note in notes) {
            val attachments = note.getAttachments()
                .filter { it.mimeType.startsWith("audio/") }

            if (attachments.isEmpty()) continue

            // Get all searchable metadata from the note
            val tags = note.getTags()
            val summary = note.summary ?: ""
            val category = note.categoryName

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
                    noteSummary = summary,
                    noteTags = tags,
                    noteCategory = category,
                    fileName = attachment.fileName
                ))
            }
        }

        if (audioItems.isEmpty()) {
            Log.d(TAG, "No audio items found in notes")
            return null
        }

        Log.d(TAG, "Found ${audioItems.size} audio items to search across all metadata")

        // Use semantic search with ALL available text fields
        val results = SemanticSearchEngine.search(
            query = query,
            items = audioItems,
            textExtractor = { item ->
                // Build comprehensive searchable text list
                val searchableTexts = mutableListOf<String>()

                // Primary: filename (most important for audio)
                searchableTexts.add(item.fileName)

                // Secondary: note title
                searchableTexts.add(item.noteTitle)

                // Tertiary: tags (joined as space-separated string)
                if (item.noteTags.isNotEmpty()) {
                    searchableTexts.add(item.noteTags.joinToString(" "))
                }

                // Quaternary: summary (if available)
                if (item.noteSummary.isNotBlank()) {
                    searchableTexts.add(item.noteSummary)
                }

                // Quinary: category name (if available)
                item.noteCategory?.let { searchableTexts.add(it) }

                // Last: note content (truncated for performance)
                searchableTexts.add(item.noteContent.take(500))

                searchableTexts
            },
            minScore = 0.20  // Very low threshold for maximum inclusivity
        )

        if (results.isEmpty()) {
            Log.d(TAG, "No semantic matches found for '$query', trying deep fallback...")

            // Deep fallback: Try individual word matching against ALL fields
            val queryWords = SemanticSearchEngine.tokenize(query)
            val fallbackResults = audioItems.mapNotNull { item ->
                // Combine ALL searchable fields
                val combinedText = buildString {
                    append(item.fileName).append(" ")
                    append(item.noteTitle).append(" ")
                    append(item.noteTags.joinToString(" ")).append(" ")
                    append(item.noteSummary).append(" ")
                    item.noteCategory?.let { append(it).append(" ") }
                    append(item.noteContent)
                }

                var bestWordScore = 0.0
                var matchedWord = ""

                for (word in queryWords) {
                    val wordScore = SemanticSearchEngine.calculateSimilarity(word, combinedText)
                    if (wordScore > bestWordScore) {
                        bestWordScore = wordScore
                        matchedWord = word
                    }

                    // Also check each tag individually for better tag matching
                    for (tag in item.noteTags) {
                        val tagScore = SemanticSearchEngine.calculateSimilarity(word, tag)
                        if (tagScore > bestWordScore) {
                            bestWordScore = tagScore
                            matchedWord = "$word (tag: $tag)"
                        }
                    }
                }

                if (bestWordScore >= 0.15) {  // Very low threshold
                    Log.d(TAG, "Fallback candidate: ${item.fileName} score=${bestWordScore} matched='$matchedWord'")
                    bestWordScore to item
                } else null
            }.sortedByDescending { it.first }

            if (fallbackResults.isNotEmpty()) {
                val best = fallbackResults.first().second
                Log.d(TAG, "Fallback match: ${best.fileName} (note: ${best.noteTitle}, tags: ${best.noteTags})")
                return best.track
            }

            // Ultra fallback: Check if any filename or tag CONTAINS any query word
            val ultraFallback = audioItems.firstOrNull { item ->
                val normalizedQuery = query.lowercase().replace(Regex("[^a-z0-9\\s]"), "")
                val queryParts = normalizedQuery.split(" ").filter { it.length >= 2 }

                queryParts.any { part ->
                    item.fileName.lowercase().contains(part) ||
                    item.noteTitle.lowercase().contains(part) ||
                    item.noteTags.any { tag -> tag.lowercase().contains(part) } ||
                    item.noteSummary.lowercase().contains(part)
                }
            }

            if (ultraFallback != null) {
                Log.d(TAG, "Ultra fallback match: ${ultraFallback.fileName}")
                return ultraFallback.track
            }

            return null
        }

        val bestMatch = results.first()
        Log.d(TAG, "Best semantic match: ${bestMatch.item.fileName} " +
                "(score: ${String.format("%.2f", bestMatch.score)}, " +
                "type: ${bestMatch.matchType}, note: ${bestMatch.item.noteTitle}, " +
                "tags: ${bestMatch.item.noteTags})")

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
