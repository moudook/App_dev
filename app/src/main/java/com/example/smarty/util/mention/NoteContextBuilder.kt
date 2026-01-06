package com.example.smarty.util.mention

import android.util.Log
import com.example.smarty.data.model.MentionType
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteChunk
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ResolvedMention
import com.example.smarty.data.model.TaggedNoteContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds AI-ready context from resolved @mentions.
 *
 * Features:
 * - Formats note content for AI prompt injection
 * - Handles large content with intelligent chunking (ONLY when > 12000 chars)
 * - Filters out binary content types (audio, video, etc.)
 * - Provides clear context headers for AI understanding
 *
 * Chunking Strategy (only when needed):
 * - Chunks of ~4000 chars (~1000 tokens)
 * - 400 char overlap for context continuity
 * - Max 20 chunks safety limit
 */
class NoteContextBuilder(
    private val mentionResolver: MentionResolver
) {
    companion object {
        private const val TAG = "NoteContextBuilder"
    }

    /**
     * Build context from resolved mentions.
     * Automatically handles chunking if total content exceeds limits.
     *
     * @param resolvedMentions List of resolved mentions
     * @return TaggedNoteContext ready for AI processing
     */
    suspend fun buildContext(resolvedMentions: List<ResolvedMention>): TaggedNoteContext = withContext(Dispatchers.Default) {
        if (resolvedMentions.isEmpty()) {
            return@withContext TaggedNoteContext(
                resolvedMentions = emptyList(),
                totalChars = 0,
                needsChunking = false,
                contextString = ""
            )
        }

        // Collect all unique notes with readable content
        val uniqueNotes = resolvedMentions
            .flatMap { it.notes }
            .distinctBy { it.id }
            .filter { mentionResolver.hasReadableContent(it.type) }

        Log.d(TAG, "Building context for ${uniqueNotes.size} unique notes")

        // Build content for each note
        val noteContents = uniqueNotes.mapNotNull { note ->
            val content = extractReadableContent(note)
            if (content.isNotBlank()) {
                NoteContentEntry(
                    note = note,
                    content = content,
                    charCount = content.length
                )
            } else null
        }

        val totalChars = noteContents.sumOf { it.charCount }
        Log.d(TAG, "Total content: $totalChars chars from ${noteContents.size} notes")

        // Check if chunking is needed (ONLY when content exceeds limit)
        val needsChunking = totalChars > TaggedNoteContext.MAX_TOTAL_CONTEXT

        if (needsChunking) {
            Log.d(TAG, "Content exceeds ${TaggedNoteContext.MAX_TOTAL_CONTEXT} chars - chunking required")
            val chunks = createChunks(noteContents)
            return@withContext TaggedNoteContext(
                resolvedMentions = resolvedMentions,
                totalChars = totalChars,
                needsChunking = true,
                contextString = "", // Empty when chunked
                chunks = chunks
            )
        } else {
            // Normal case: build single context string
            val contextString = buildContextString(resolvedMentions, noteContents)
            return@withContext TaggedNoteContext(
                resolvedMentions = resolvedMentions,
                totalChars = totalChars,
                needsChunking = false,
                contextString = contextString
            )
        }
    }

    /**
     * Build context string for single-request processing.
     */
    private fun buildContextString(
        resolvedMentions: List<ResolvedMention>,
        noteContents: List<NoteContentEntry>
    ): String {
        if (noteContents.isEmpty()) return ""

        return buildString {
            append("--- REFERENCED NOTES ---\n\n")

            // Group notes by how they were referenced
            val singleNotes = resolvedMentions.filter { it.type == MentionType.SINGLE_NOTE }
            val typeFilters = resolvedMentions.filter { it.type == MentionType.TYPE_FILTER }
            val specialFilters = resolvedMentions.filter { it.type == MentionType.SPECIAL_FILTER }
            val categoryMentions = resolvedMentions.filter { it.type == MentionType.CATEGORY }

            // Single note references
            if (singleNotes.isNotEmpty()) {
                for (mention in singleNotes) {
                    for (note in mention.notes) {
                        val content = noteContents.find { it.note.id == note.id }
                        if (content != null) {
                            append(formatNoteForContext(content.note, content.content, "Referenced"))
                        }
                    }
                }
            }

            // Type filter references
            if (typeFilters.isNotEmpty()) {
                for (mention in typeFilters) {
                    val typeName = mention.noteType?.let {
                        MentionParser.getTypeFilterDisplayName(it)
                    } ?: "Notes"

                    append("[$typeName from @${mention.parsedMention.query}]\n\n")

                    for (note in mention.notes) {
                        val content = noteContents.find { it.note.id == note.id }
                        if (content != null) {
                            append(formatNoteForContext(content.note, content.content, typeName))
                        }
                    }
                }
            }

            // Category references
            if (categoryMentions.isNotEmpty()) {
                for (mention in categoryMentions) {
                    val categoryName = mention.category?.name ?: "Category"

                    append("[Category: $categoryName from @${mention.parsedMention.query}]\n\n")

                    for (note in mention.notes) {
                        val content = noteContents.find { it.note.id == note.id }
                        if (content != null) {
                            append(formatNoteForContext(content.note, content.content, "Category: $categoryName"))
                        }
                    }
                }
            }

            // Special filter references
            if (specialFilters.isNotEmpty()) {
                for (mention in specialFilters) {
                    val filterName = when (mention.specialFilter) {
                        "recent" -> "Recent Notes"
                        "pinned" -> "Pinned Notes"
                        "all" -> "All Notes"
                        else -> "Notes"
                    }

                    append("[$filterName from @${mention.parsedMention.query}]\n\n")

                    for (note in mention.notes) {
                        val content = noteContents.find { it.note.id == note.id }
                        if (content != null) {
                            append(formatNoteForContext(content.note, content.content, filterName))
                        }
                    }
                }
            }

            append("--- END REFERENCED NOTES ---\n")
        }
    }

    /**
     * Format a single note for inclusion in context.
     */
    private fun formatNoteForContext(note: Note, content: String, source: String): String {
        return buildString {
            append("### ${note.title}\n")
            append("Type: ${note.type.name} | Source: $source\n")
            if (!note.categoryName.isNullOrBlank()) {
                append("Category: ${note.categoryName}\n")
            }
            append("\n")
            append(content.trim())
            append("\n\n")
        }
    }

    /**
     * Create chunks from note contents when total exceeds limit.
     */
    private fun createChunks(noteContents: List<NoteContentEntry>): List<NoteChunk> {
        val chunks = mutableListOf<NoteChunk>()
        var chunkIndex = 0

        for (entry in noteContents) {
            // Check safety limit
            if (chunks.size >= TaggedNoteContext.MAX_CHUNKS) {
                Log.w(TAG, "Reached max chunks limit (${TaggedNoteContext.MAX_CHUNKS})")
                break
            }

            val noteChunks = chunkNoteContent(entry, chunkIndex)
            chunks.addAll(noteChunks)
            chunkIndex += noteChunks.size
        }

        return chunks
    }

    /**
     * Chunk a single note's content if it exceeds chunk size.
     */
    private fun chunkNoteContent(entry: NoteContentEntry, startIndex: Int): List<NoteChunk> {
        val content = entry.content
        val chunkSize = TaggedNoteContext.CHARS_PER_CHUNK
        val overlap = TaggedNoteContext.OVERLAP_CHARS

        // If content fits in one chunk, return single chunk
        if (content.length <= chunkSize) {
            return listOf(
                NoteChunk(
                    index = startIndex,
                    noteTitle = entry.note.title,
                    noteId = entry.note.id,
                    content = formatChunkContent(entry.note, content, 1, 1),
                    charCount = content.length,
                    totalChunksInNote = 1
                )
            )
        }

        // Split content into overlapping chunks
        val chunks = mutableListOf<NoteChunk>()
        var position = 0
        var localIndex = 0
        val totalChunks = calculateChunkCount(content.length, chunkSize, overlap)

        while (position < content.length && chunks.size < TaggedNoteContext.MAX_CHUNKS) {
            val endPosition = minOf(position + chunkSize, content.length)

            // Try to break at sentence or word boundary
            val chunkEnd = findGoodBreakPoint(content, position, endPosition)
            val chunkContent = content.substring(position, chunkEnd)

            chunks.add(
                NoteChunk(
                    index = startIndex + localIndex,
                    noteTitle = entry.note.title,
                    noteId = entry.note.id,
                    content = formatChunkContent(
                        entry.note,
                        chunkContent,
                        localIndex + 1,
                        totalChunks
                    ),
                    charCount = chunkContent.length,
                    totalChunksInNote = totalChunks
                )
            )

            // Move position with overlap (except for last chunk)
            position = if (chunkEnd >= content.length) {
                content.length
            } else {
                maxOf(chunkEnd - overlap, position + 1)
            }
            localIndex++
        }

        return chunks
    }

    /**
     * Format chunk content with header for AI context.
     */
    private fun formatChunkContent(
        note: Note,
        content: String,
        chunkNum: Int,
        totalChunks: Int
    ): String {
        return buildString {
            append("### ${note.title}")
            if (totalChunks > 1) {
                append(" (Part $chunkNum of $totalChunks)")
            }
            append("\n")
            append("Type: ${note.type.name}\n\n")
            append(content.trim())
        }
    }

    /**
     * Find a good break point (sentence/word boundary) near target position.
     */
    private fun findGoodBreakPoint(text: String, start: Int, target: Int): Int {
        if (target >= text.length) return text.length

        // Look for sentence endings (.!?) within last 200 chars of target
        val searchStart = maxOf(target - 200, start)
        val searchRange = text.substring(searchStart, target)

        val sentenceEnd = searchRange.lastIndexOfAny(charArrayOf('.', '!', '?'))
        if (sentenceEnd > 0) {
            return searchStart + sentenceEnd + 1
        }

        // Fall back to word boundary (space/newline)
        val wordEnd = searchRange.lastIndexOfAny(charArrayOf(' ', '\n', '\t'))
        if (wordEnd > 0) {
            return searchStart + wordEnd + 1
        }

        return target
    }

    /**
     * Calculate expected number of chunks for content.
     */
    private fun calculateChunkCount(contentLength: Int, chunkSize: Int, overlap: Int): Int {
        if (contentLength <= chunkSize) return 1
        val effectiveChunkSize = chunkSize - overlap
        return ((contentLength - chunkSize).toFloat() / effectiveChunkSize).toInt() + 2
    }

    /**
     * Extract readable text content from a note.
     * Returns empty string for binary content types.
     */
    private fun extractReadableContent(note: Note): String {
        // Skip binary content types
        if (!mentionResolver.hasReadableContent(note.type)) {
            return ""
        }

        return buildString {
            // Primary content
            if (note.content.isNotBlank()) {
                append(note.content)
            }

            // Include summary if available (for processed notes)
            if (!note.summary.isNullOrBlank() && note.summary != note.content) {
                if (isNotEmpty()) append("\n\n")
                append("Summary: ${note.summary}")
            }

            // Include source URL for web content
            if (!note.sourceUrl.isNullOrBlank() && note.type in listOf(
                    NoteType.WEBSITE, NoteType.YOUTUBE, NoteType.TWITTER, NoteType.INSTAGRAM
                )) {
                if (isNotEmpty()) append("\n")
                append("Source: ${note.sourceUrl}")
            }

            // Include why saved if available
            if (!note.whySaved.isNullOrBlank()) {
                if (isNotEmpty()) append("\n")
                append("Context: ${note.whySaved}")
            }
        }
    }

    /**
     * Internal class to hold note content during processing.
     */
    private data class NoteContentEntry(
        val note: Note,
        val content: String,
        val charCount: Int
    )
}
