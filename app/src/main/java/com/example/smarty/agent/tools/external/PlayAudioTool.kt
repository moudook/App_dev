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
) : Tool<PlayAudioArgs, AudioPlaybackResult>(
    argsSerializer = PlayAudioArgs.serializer(),
    resultSerializer = AudioPlaybackResult.serializer(),
    name = "play_audio",
    description = """ONLY use when user says "play" followed by audio name. Do NOT use otherwise.""".trimIndent()
) {
    companion object {
        private const val TAG = "PlayAudioTool"

        // Pre-compiled regex patterns for performance
        private val NON_ALPHANUMERIC_SPACE_PATTERN = Regex("[^a-z0-9\\s]")
        private val NON_ALPHANUMERIC_PATTERN = Regex("[^a-z0-9]")
        private val TIME_COLON_PATTERN = Regex("""(\d+):(\d{1,2})""")
        private val TIME_MINUTES_PATTERN = Regex("""(\d+)\s*(?:minutes?|mins?)""")
        private val TIME_SECONDS_PATTERN = Regex("""(\d+)\s*(?:seconds?|secs?)""")

        // Command words to strip from search queries
        private val COMMAND_WORDS = setOf(
            "play", "find", "search", "open", "start", "listen", "put", "get",
            "give", "show", "the", "a", "an", "me", "my", "some", "that", "this"
        )
    }

    /**
     * Clean query by removing command words and normalizing.
     * "play deep in your love" → "deep in your love"
     * "play the song deep in your love" → "song deep in your love"
     */
    private fun cleanQuery(query: String): String {
        val words = query.lowercase().trim().split(Regex("\\s+"))
        val cleaned = words.filter { it !in COMMAND_WORDS && it.length >= 2 }
        val result = cleaned.joinToString(" ")
        Log.d(TAG, "Cleaned query: '$query' → '$result'")
        return result.ifEmpty { query.lowercase().trim() }
    }

    override suspend fun execute(args: PlayAudioArgs): AudioPlaybackResult {
        // BUG FIX (ISSUE 3): Add explicit entry point logging
        Log.i(TAG, "▶ PlayAudioTool.execute() CALLED - query='${args.query}', noteId=${args.noteId}")

        return try {
            if (args.query.isBlank()) {
                Log.w(TAG, " Query is blank, returning error")
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

                // Build audio track - check BOTH storage methods
                val track: AudioTrack?
                val trackTitle: String

                // METHOD 1: Check multiple attachments from attachmentsJson
                val attachments = note.getAttachments()
                    .filter { it.mimeType.startsWith("audio/") }

                if (attachments.isNotEmpty()) {
                    val attachment = attachments.getOrNull(args.attachmentIndex) ?: attachments[0]
                    track = AudioTrack(
                        uri = attachment.uri,
                        title = attachment.fileName,
                        fileName = attachment.fileName,
                        mimeType = attachment.mimeType,
                        sourceNoteId = note.id,
                        sourceAttachmentId = attachment.id
                    )
                    trackTitle = attachment.fileName
                }
                // METHOD 2: Check legacy single attachment (fileUri field)
                else if (note.fileUri != null && note.fileMimeType?.startsWith("audio/") == true) {
                    val legacyFileName = note.fileName ?: "audio_${note.id.take(8)}"
                    track = AudioTrack(
                        uri = note.fileUri,
                        title = legacyFileName,
                        fileName = legacyFileName,
                        mimeType = note.fileMimeType,
                        sourceNoteId = note.id,
                        sourceAttachmentId = null
                    )
                    trackTitle = legacyFileName
                    // SECURITY: Don't log note titles to prevent data leakage
                    Log.d(TAG, "Using legacy audio from note: id=${note.id.take(8)}...")
                }
                else {
                    return AudioPlaybackResult(
                        success = false,
                        action = "play",
                        message = "No audio files found in this note",
                        error = "No audio attachments"
                    )
                }

                val startPositionMs = parseTimeToMs(args.startTime)
                Log.d(TAG, "Playing from note: ${track.title}, startPosition=${startPositionMs}ms")
                Log.i(TAG, " INVOKING onPlayAudio callback for: ${track.title}")
                onPlayAudio(track)

                return AudioPlaybackResult(
                    success = true,
                    action = "play",
                    trackTitle = trackTitle,
                    message = "Playing"  // Keep brief - AI will respond naturally
                )
            }

            // OPTIMIZED: Search audio-categorized notes FIRST for better performance
            // SECURITY: Filter to only AI-accessible notes (excludes private notes)
            val rawNotes = getActiveNotes()
            val allNotes = PrivacyGuard.getAiVisibleNotes(rawNotes)

            // DIAGNOSTIC: Log note counts to debug audio search issues
            Log.i(TAG, " NOTES DEBUG: raw=${rawNotes.size}, afterPrivacy=${allNotes.size}")

            // 1. SPECIAL HANDLING: "Latest", "Last", "Recent"
            // If user asks for "latest recording", skip search and play the newest one
            val queryLower = args.query.lowercase()
            val isRecencyRequest = queryLower.contains("latest") || 
                                  queryLower.contains("last") || 
                                  queryLower.contains("recent") ||
                                  queryLower.contains("newest")

            if (isRecencyRequest) {
                 Log.d(TAG, "Recency request detected ('$queryLower') - finding newest audio")
                 // Sort notes by update time (newest first)
                 val sortedNotes = allNotes.sortedByDescending { it.updatedAt }
                 val recentAudio = getAllAudioFiles(sortedNotes).firstOrNull()
                 
                 if (recentAudio != null) {
                      Log.i(TAG, " FOUND RECENT: Playing most recent audio: ${recentAudio.title}")
                      onPlayAudio(recentAudio)
                      return AudioPlaybackResult(
                          success = true,
                          action = "play",
                          trackTitle = recentAudio.title,
                          message = "Playing your latest audio: ${recentAudio.title}"
                      )
                 }
            }

            // Step 1: Search only audio-type notes first (faster, more accurate)
            val audioNotes = allNotes.filter { note ->
                val isAudioType = note.type == com.example.smarty.data.model.NoteType.AUDIO
                val hasAudioAttachments = note.getAttachments().any { it.mimeType.startsWith("audio/") }
                val hasLegacyAudio = note.fileMimeType?.startsWith("audio/") == true

                // Log each note's audio detection for debugging
                if (isAudioType || hasAudioAttachments || hasLegacyAudio) {
                    Log.d(TAG, " Audio note found: id=${note.id.take(8)}, type=$isAudioType, attachments=$hasAudioAttachments, legacy=$hasLegacyAudio, title='${note.title.take(30)}'")
                }

                isAudioType || hasAudioAttachments || hasLegacyAudio
            }

            Log.i(TAG, " AUDIO FILTER: Found ${audioNotes.size} audio-categorized notes to search")

            // Try audio notes first
            var matchingAudio = findMatchingAudio(audioNotes, args.query)

            // Step 2: If not found in audio notes, fall back to all notes
            if (matchingAudio == null && audioNotes.size < allNotes.size) {
                Log.d(TAG, "No match in audio notes, searching all ${allNotes.size} notes")
                matchingAudio = findMatchingAudio(allNotes, args.query)
            }

            if (matchingAudio != null) {
                Log.d(TAG, "Found matching audio: ${matchingAudio.title}")
                Log.i(TAG, " INVOKING onPlayAudio callback for: ${matchingAudio.title}")
                onPlayAudio(matchingAudio)

                return AudioPlaybackResult(
                    success = true,
                    action = "play",
                    trackTitle = matchingAudio.title,
                    message = "Playing"  // Keep brief
                )
            }

            // No audio found - but let's check if there's ANY audio at all
            val allAudioFiles = getAllAudioFiles(allNotes)

            if (allAudioFiles.isEmpty()) {
                // No audio files exist at all - AI should respond gracefully
                AudioPlaybackResult(
                    success = false,
                    action = "play",
                    message = "No audio files found in any notes. Save some audio first!",
                    error = "No audio in notes",
                    shouldFallbackToAI = true  // Let AI respond gracefully
                )
            } else {
                // Audio exists but query didn't match - show available options
                val availableNames = allAudioFiles.take(5).joinToString(", ") { it.title }
                Log.d(TAG, "Query '${args.query}' didn't match. Available: $availableNames")

                // AGGRESSIVE FALLBACK: If user clearly wants audio, play the first available
                val queryLower = args.query.lowercase()
                val wantsAnyAudio = queryLower.contains("any") ||
                                   queryLower.contains("something") ||
                                   queryLower.contains("random") ||
                                   queryLower == "music" ||
                                   queryLower == "audio" ||
                                   queryLower == "song"

                if (wantsAnyAudio && allAudioFiles.isNotEmpty()) {
                    val firstTrack = allAudioFiles.first()
                    Log.d(TAG, "Playing first available audio: ${firstTrack.title}")
                    Log.i(TAG, " INVOKING onPlayAudio callback (fallback) for: ${firstTrack.title}")
                    onPlayAudio(firstTrack)

                    AudioPlaybackResult(
                        success = true,
                        action = "play",
                        trackTitle = firstTrack.title,
                        message = "Playing"  // Keep brief
                    )
                } else {
                    // Audio exists but query didn't match - AI should handle gracefully
                    AudioPlaybackResult(
                        success = false,
                        action = "play",
                        message = "Audio not found",  // Keep brief, don't list alternatives
                        error = "Audio not found",
                        availableAudio = null,  // Don't expose internal data
                        shouldFallbackToAI = true  // Let AI respond gracefully to maintain decorum
                    )
                }
            }
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
     *
     * IMPORTANT: Handles BOTH storage methods:
     * 1. Legacy single attachment: note.fileUri, note.fileName, note.fileMimeType
     * 2. Multiple attachments: note.getAttachments() from attachmentsJson
     */
    private fun findMatchingAudio(notes: List<Note>, query: String): AudioTrack? {
        // Clean the query by removing command words
        val cleanedQuery = cleanQuery(query)
        Log.i(TAG, " SEARCH: query='$query' → cleaned='$cleanedQuery', searching ${notes.size} notes")

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
            // Get all searchable metadata from the note
            val tags = note.getTags()
            val summary = note.summary ?: ""
            val category = note.categoryName

            // METHOD 1: Check multiple attachments from attachmentsJson
            val attachments = note.getAttachments()
                .filter { it.mimeType.startsWith("audio/") }

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

            // METHOD 2: Check legacy single attachment (fileUri field)
            // Only if no audio attachments found in attachmentsJson AND legacy fields have audio
            if (attachments.isEmpty() &&
                note.fileUri != null &&
                note.fileMimeType?.startsWith("audio/") == true) {

                val legacyFileName = note.fileName ?: "audio_${note.id.take(8)}"
                val track = AudioTrack(
                    uri = note.fileUri,
                    title = legacyFileName,
                    fileName = legacyFileName,
                    mimeType = note.fileMimeType,
                    sourceNoteId = note.id,
                    sourceAttachmentId = null  // Legacy attachment doesn't have separate ID
                )
                audioItems.add(AudioItem(
                    track = track,
                    noteTitle = note.title,
                    noteContent = note.content,
                    noteSummary = summary,
                    noteTags = tags,
                    noteCategory = category,
                    fileName = legacyFileName
                ))
                // SECURITY: Don't log note titles to prevent data leakage
                Log.d(TAG, "Found legacy audio attachment in note: id=${note.id.take(8)}..., file: $legacyFileName")
            }
        }

        if (audioItems.isEmpty()) {
            Log.w(TAG, " NO AUDIO ITEMS found in ${notes.size} notes - check attachmentsJson/fileUri fields")
            return null
        }

        // Log all found audio files for debugging
        Log.i(TAG, " AUDIO ITEMS: Found ${audioItems.size} audio files:")
        audioItems.forEachIndexed { index, item ->
            Log.d(TAG, "  [$index] '${item.fileName}' in note '${item.noteTitle.take(20)}'")
        }

        // Use semantic search with ALL available text fields
        val results = SemanticSearchEngine.search(
            query = cleanedQuery,
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
            Log.d(TAG, "No semantic matches found for '$cleanedQuery', trying deep fallback...")

            // Deep fallback: Try individual word matching against ALL fields
            val queryWords = SemanticSearchEngine.tokenize(cleanedQuery)
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
            val normalizedQuery = cleanedQuery.lowercase().replace(NON_ALPHANUMERIC_SPACE_PATTERN, "")
            val queryParts = normalizedQuery.split(" ").filter { it.length >= 2 }

            val ultraFallback = audioItems.firstOrNull { item ->
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

            // SUPER fallback: Check if filename contains ANY single character sequence from query (3+ chars)
            val superFallback = audioItems.firstOrNull { item ->
                val fileNameLower = item.fileName.lowercase().replace(NON_ALPHANUMERIC_PATTERN, "")
                val queryClean = cleanedQuery.lowercase().replace(NON_ALPHANUMERIC_PATTERN, "")

                // Check if there's any 3+ char overlap
                queryClean.length >= 3 && (
                    fileNameLower.contains(queryClean) ||
                    queryClean.contains(fileNameLower.take(5)) ||
                    queryParts.any { part -> part.length >= 3 && fileNameLower.contains(part) }
                )
            }

            if (superFallback != null) {
                Log.d(TAG, "Super fallback match: ${superFallback.fileName}")
                return superFallback.track
            }

            Log.d(TAG, "No match found for '$cleanedQuery' (original: '$query') in ${audioItems.size} audio items")
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
     * Get ALL audio files from notes (for fallback and listing available audio).
     *
     * IMPORTANT: Handles BOTH storage methods:
     * 1. Legacy single attachment: note.fileUri, note.fileName, note.fileMimeType
     * 2. Multiple attachments: note.getAttachments() from attachmentsJson
     */
    private fun getAllAudioFiles(notes: List<Note>): List<AudioTrack> {
        val audioTracks = mutableListOf<AudioTrack>()

        for (note in notes) {
            // METHOD 1: Check multiple attachments from attachmentsJson
            val attachments = note.getAttachments()
                .filter { it.mimeType.startsWith("audio/") }

            for (attachment in attachments) {
                audioTracks.add(AudioTrack(
                    uri = attachment.uri,
                    title = attachment.fileName,
                    fileName = attachment.fileName,
                    mimeType = attachment.mimeType,
                    sourceNoteId = note.id,
                    sourceAttachmentId = attachment.id
                ))
            }

            // METHOD 2: Check legacy single attachment (fileUri field)
            // Only if no audio attachments found in attachmentsJson AND legacy fields have audio
            if (attachments.isEmpty() &&
                note.fileUri != null &&
                note.fileMimeType?.startsWith("audio/") == true) {

                val legacyFileName = note.fileName ?: "audio_${note.id.take(8)}"
                audioTracks.add(AudioTrack(
                    uri = note.fileUri,
                    title = legacyFileName,
                    fileName = legacyFileName,
                    mimeType = note.fileMimeType,
                    sourceNoteId = note.id,
                    sourceAttachmentId = null
                ))
            }
        }

        return audioTracks
    }

    /**
     * Parse time strings like "1:30", "2 minutes", "45 seconds" to milliseconds.
     */
    private fun parseTimeToMs(timeStr: String?): Long {
        if (timeStr.isNullOrBlank()) return 0L

        val str = timeStr.lowercase().trim()

        // Format: "1:30" or "01:30" (mm:ss)
        TIME_COLON_PATTERN.find(str)?.let { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: 0
            val seconds = match.groupValues[2].toLongOrNull() ?: 0
            return (minutes * 60 + seconds) * 1000
        }

        // Format: "2 minutes" or "2 min"
        TIME_MINUTES_PATTERN.find(str)?.let { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: 0
            return minutes * 60 * 1000
        }

        // Format: "45 seconds" or "45 sec"
        TIME_SECONDS_PATTERN.find(str)?.let { match ->
            val seconds = match.groupValues[1].toLongOrNull() ?: 0
            return seconds * 1000
        }

        return 0L
    }
}
