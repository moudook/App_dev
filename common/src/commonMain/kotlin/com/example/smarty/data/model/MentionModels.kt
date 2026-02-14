package com.example.smarty.core.domain.model

/**
 * Data models for the Note Tagging/Reference System.
 *
 * Allows users to reference notes in chat using @mention syntax:
 * - @note_title or @"note title" - Reference specific note
 * - @audios, @documents, @images - Filter by type
 * - @recent, @pinned - Special filters
 */

/**
 * Parsed mention extracted from user input text.
 * Represents the raw @ reference before resolution.
 */
data class ParsedMention(
    /** Raw text including @ symbol, e.g., "@hello_world" or "@\"My Note\"" */
    val rawText: String,
    /** Extracted query without @ or quotes, e.g., "hello_world" or "My Note" */
    val query: String,
    /** Start index in original text (position of @) */
    val startIndex: Int,
    /** End index in original text (after mention ends) */
    val endIndex: Int
)

/**
 * Type of mention detected.
 */
enum class MentionType {
    /** Reference to a specific note by title, e.g., @"My Shopping List" */
    SINGLE_NOTE,
    /** Filter by note type, e.g., @audios, @documents */
    TYPE_FILTER,
    /** Filter by category, e.g., @Work, @Personal */
    CATEGORY,
    /** Special filter like @recent or @pinned */
    SPECIAL_FILTER,
    /** Command like @analyze */
    COMMAND
}

/**
 * Resolved mention with actual note(s) found.
 */
data class ResolvedMention(
    /** Original parsed mention */
    val parsedMention: ParsedMention,
    /** Type of mention */
    val type: MentionType,
    /** Resolved notes (empty if no matches) */
    val notes: List<Note> = emptyList(),
    /** For TYPE_FILTER: which NoteType was requested */
    val noteType: NoteType? = null,
    /** For CATEGORY: which Category was requested */
    val category: Category? = null,
    /** For SPECIAL_FILTER: filter name (recent, pinned, all) */
    val specialFilter: String? = null
)

/**
 * Autocomplete suggestion shown in dropdown.
 * Sealed class for type-safe suggestion handling.
 */
sealed class MentionSuggestion {
    /**
     * Suggestion for a specific note.
     * @param note The matching note
     * @param score Match score (0.0 to 1.0, higher = better match)
     */
    data class NoteSuggestion(
        val note: Note,
        val score: Double
    ) : MentionSuggestion()

    /**
     * Suggestion for a category.
     * @param category The matching category
     * @param score Match score
     */
    data class CategorySuggestion(
        val category: Category,
        val score: Double
    ) : MentionSuggestion()

    /**
     * Suggestion for a type filter.
     * @param type The NoteType (null for special filters like "all")
     * @param displayName Display text, e.g., "Audios", "Documents"
     * @param keyword The filter keyword, e.g., "audios", "documents"
     * @param count Number of notes matching this filter
     */
    data class TypeFilter(
        val type: NoteType?,
        val displayName: String,
        val keyword: String,
        val count: Int
    ) : MentionSuggestion()

    /**
     * Suggestion for special filters.
     * @param filterName Filter name: "recent", "pinned", "all"
     * @param displayName Display text
     * @param description Brief description of what this filter does
     * @param count Number of notes
     */
    data class SpecialFilter(
        val filterName: String,
        val displayName: String,
        val description: String,
        val count: Int
    ) : MentionSuggestion()

    /**
     * Suggestion for commands like @analyze.
     * @param commandName Command name without @
     * @param displayName Display text
     * @param description What the command does
     * @param icon Icon name for display
     */
    data class CommandSuggestion(
        val commandName: String,
        val displayName: String,
        val description: String,
        val icon: String = "analytics"
    ) : MentionSuggestion()
}

/**
 * State for the mention autocomplete dropdown.
 * Tracks whether user is actively typing a mention.
 */
data class MentionState(
    /** Whether mention dropdown should be visible */
    val isActive: Boolean = false,
    /** Current query text (after @, without quotes) */
    val query: String = "",
    /** Position of @ character in text field */
    val triggerIndex: Int = -1,
    /** Current suggestions to display (max 4) */
    val suggestions: List<MentionSuggestion> = emptyList(),
    /** Currently highlighted suggestion index (-1 for none) */
    val highlightedIndex: Int = 0
)

/**
 * Chunk of note content for large context processing.
 * Used when total tagged content exceeds AI context limits.
 */
data class NoteChunk(
    /** Chunk index (0-based) */
    val index: Int,
    /** Title of the source note */
    val noteTitle: String,
    /** Note ID for reference */
    val noteId: String,
    /** Chunk content */
    val content: String,
    /** Character count in this chunk */
    val charCount: Int,
    /** Total chunks for this note */
    val totalChunksInNote: Int = 1
)

/**
 * Final context ready for AI processing.
 * Contains resolved mentions and formatted context string.
 */
data class TaggedNoteContext(
    /** All resolved mentions */
    val resolvedMentions: List<ResolvedMention>,
    /** Total character count of all note content */
    val totalChars: Int,
    /** Whether content was too large and needed chunking */
    val needsChunking: Boolean,
    /**
     * Full context string for AI (when needsChunking = false).
     * Empty when chunking is needed - use chunks instead.
     */
    val contextString: String,
    /**
     * Content chunks (only populated when needsChunking = true).
     * Empty for normal single-request processing.
     */
    val chunks: List<NoteChunk> = emptyList(),
    /** Number of unique notes referenced */
    val noteCount: Int = resolvedMentions.flatMap { it.notes }.distinctBy { it.id }.size
) {
    companion object {
        /** Maximum characters before chunking is required (~3000 tokens) */
        const val MAX_TOTAL_CONTEXT = 12000

        /** Characters per chunk when chunking is needed (~1000 tokens) */
        const val CHARS_PER_CHUNK = 4000

        /** Overlap between chunks for context continuity */
        const val OVERLAP_CHARS = 400

        /** Safety limit on number of chunks */
        const val MAX_CHUNKS = 20
    }
}

/**
 * Result of mention detection while typing.
 * Used to determine if autocomplete should be shown.
 */
data class MentionDetectionResult(
    /** Whether a mention is being typed */
    val isTypingMention: Boolean,
    /** Query text after @ */
    val query: String,
    /** Position of @ in text */
    val triggerIndex: Int,
    /** Whether this looks like an email (should NOT trigger mention) */
    val isEmailPattern: Boolean = false
)
