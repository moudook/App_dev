package com.example.smarty.util.mention

import com.example.smarty.data.model.MentionDetectionResult
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ParsedMention

/**
 * Parser for @mention syntax in chat messages.
 *
 * Supports multiple formats:
 * - @note_title (underscores converted to spaces for matching)
 * - @"note title with spaces" (quoted for exact matching)
 * - @audios, @documents, etc. (type filters)
 * - @recent, @pinned, @all (special filters)
 *
 * Explicitly ignores email patterns like user@domain.com
 */
object MentionParser {

    /**
     * Map of type filter keywords to NoteType.
     * Keys are lowercase for case-insensitive matching.
     */
    val TYPE_FILTERS: Map<String, NoteType> = mapOf(
        "audios" to NoteType.AUDIO,
        "audio" to NoteType.AUDIO,
        "documents" to NoteType.DOCUMENT,
        "document" to NoteType.DOCUMENT,
        "docs" to NoteType.DOCUMENT,
        "doc" to NoteType.DOCUMENT,
        "images" to NoteType.IMAGE,
        "image" to NoteType.IMAGE,
        "img" to NoteType.IMAGE,
        "photos" to NoteType.IMAGE,
        "photo" to NoteType.IMAGE,
        "videos" to NoteType.VIDEO,
        "video" to NoteType.VIDEO,
        "code" to NoteType.CODE,
        "codes" to NoteType.CODE,
        "websites" to NoteType.WEBSITE,
        "website" to NoteType.WEBSITE,
        "web" to NoteType.WEBSITE,
        "links" to NoteType.WEBSITE,
        "youtube" to NoteType.YOUTUBE,
        "yt" to NoteType.YOUTUBE,
        "spreadsheets" to NoteType.SPREADSHEET,
        "spreadsheet" to NoteType.SPREADSHEET,
        "excel" to NoteType.SPREADSHEET,
        "presentations" to NoteType.PRESENTATION,
        "presentation" to NoteType.PRESENTATION,
        "ppt" to NoteType.PRESENTATION,
        "slides" to NoteType.PRESENTATION,
        "braindumps" to NoteType.BRAIN_DUMP,
        "braindump" to NoteType.BRAIN_DUMP,
        "notes" to NoteType.BRAIN_DUMP,
        "note" to NoteType.BRAIN_DUMP,
        "twitter" to NoteType.TWITTER,
        "tweet" to NoteType.TWITTER,
        "tweets" to NoteType.TWITTER,
        "x" to NoteType.TWITTER,
        "instagram" to NoteType.INSTAGRAM,
        "insta" to NoteType.INSTAGRAM,
        "ig" to NoteType.INSTAGRAM,
        "files" to NoteType.FILE,
        "file" to NoteType.FILE,
        "archives" to NoteType.ARCHIVE,
        "archive" to NoteType.ARCHIVE,
        "zip" to NoteType.ARCHIVE,
        "apks" to NoteType.APK,
        "apk" to NoteType.APK,
        "apps" to NoteType.APK
    )

    /**
     * Special filter keywords (not tied to NoteType).
     */
    val SPECIAL_FILTERS: Set<String> = setOf(
        "recent",
        "pinned",
        "all",
        "starred",  // Alias for pinned
        "favorites" // Alias for pinned
    )

    /**
     * Command keywords for special AI processing modes.
     * @thinking: Deep document analysis - reads full document content (not just summary)
     *            and processes in chunks for thorough analysis.
     */
    val COMMANDS: Map<String, CommandInfo> = mapOf(
        "thinking" to CommandInfo(
            name = "thinking",
            displayName = "Deep Thinking",
            description = "Analyze full document content in depth",
            icon = "psychology"
        ),
        "think" to CommandInfo(
            name = "thinking", // Alias
            displayName = "Deep Thinking",
            description = "Analyze full document content in depth",
            icon = "psychology"
        ),
        "analyze" to CommandInfo(
            name = "thinking", // Alias
            displayName = "Deep Analysis",
            description = "Perform deep analysis on document",
            icon = "analytics"
        )
    )

    /**
     * Info about a command.
     */
    data class CommandInfo(
        val name: String,
        val displayName: String,
        val description: String,
        val icon: String
    )

    /**
     * Regex to detect email patterns.
     * Matches: word@word.word (like user@domain.com)
     */
    private val EMAIL_PATTERN = Regex(
        """[\w.+-]+@[\w-]+\.[\w.-]+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Regex to match quoted mentions: @"some text here"
     */
    private val QUOTED_MENTION_REGEX = Regex(
        """@"([^"]+)"""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Regex to match unquoted mentions: @word_word or @word
     * Stops at whitespace, punctuation (except underscore), or end of string.
     * Does NOT match if preceded by alphanumeric (to avoid email false positives).
     */
    private val UNQUOTED_MENTION_REGEX = Regex(
        """(?<![a-zA-Z0-9.])@([\w]+)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Detect if user is currently typing a mention (for autocomplete trigger).
     *
     * IMPROVED: Now supports space-separated queries without quotes.
     * - Single spaces are allowed within the mention (e.g., @Note card no 1)
     * - Mention terminates at: double-space, newline, tab, or common punctuation
     * - Uses smart word-boundary detection for natural typing
     *
     * @param text Current text field content
     * @param cursorPosition Current cursor position in text
     * @return Detection result with query if mention is active
     */
    fun detectActiveMention(text: String, cursorPosition: Int): MentionDetectionResult {
        if (text.isEmpty() || cursorPosition <= 0 || cursorPosition > text.length) {
            return MentionDetectionResult(
                isTypingMention = false,
                query = "",
                triggerIndex = -1
            )
        }

        // Get text up to cursor
        val textToCursor = text.substring(0, cursorPosition)

        // Find the last @ symbol before cursor
        val lastAtIndex = textToCursor.lastIndexOf('@')

        if (lastAtIndex == -1) {
            return MentionDetectionResult(
                isTypingMention = false,
                query = "",
                triggerIndex = -1
            )
        }

        // Check if this could be an email pattern
        // Look for characters before @ that would indicate email
        if (lastAtIndex > 0) {
            val charBefore = textToCursor[lastAtIndex - 1]
            // If preceded by alphanumeric or dot/plus (email chars), likely email
            if (charBefore.isLetterOrDigit() || charBefore == '.' || charBefore == '+' || charBefore == '-') {
                // Additional check: if there's text after @ that looks like domain
                val afterAt = textToCursor.substring(lastAtIndex + 1)
                if (afterAt.contains('.') || afterAt.matches(Regex("""[\w-]+"""))) {
                    // Could be email, but let's check if full text matches email pattern
                    val potentialEmail = textToCursor.substring(
                        maxOf(0, lastAtIndex - 50), // Look back for email start
                        cursorPosition
                    )
                    if (EMAIL_PATTERN.containsMatchIn(potentialEmail)) {
                        return MentionDetectionResult(
                            isTypingMention = false,
                            query = "",
                            triggerIndex = -1,
                            isEmailPattern = true
                        )
                    }
                }
            }
        }

        // Extract text after @
        val afterAt = textToCursor.substring(lastAtIndex + 1)

        // Check if we're inside a quoted mention
        if (afterAt.startsWith("\"")) {
            // Quoted mention: @"query here
            val query = afterAt.substring(1) // Remove opening quote
            // If there's a closing quote before cursor, mention is complete
            if (query.contains("\"")) {
                return MentionDetectionResult(
                    isTypingMention = false,
                    query = "",
                    triggerIndex = -1
                )
            }
            return MentionDetectionResult(
                isTypingMention = true,
                query = query,
                triggerIndex = lastAtIndex
            )
        }

        // ═══════════════════════════════════════════════════════════════════════
        // IMPROVED: Space-tolerant unquoted mention detection
        // Allow single spaces within mention text (for natural typing like @Note card no 1)
        // Terminate at: double-space, newline, tab, or punctuation-space patterns
        // ═══════════════════════════════════════════════════════════════════════

        // Find termination point using smart detection
        val terminationIndex = findMentionTerminationIndex(afterAt)

        if (terminationIndex != -1) {
            // Mention has terminated - check if cursor is past termination
            val mentionText = afterAt.substring(0, terminationIndex).trimEnd()

            // If the mention text is non-empty and cursor is at termination, still active
            // But if there's content after termination, we're past the mention
            val afterTermination = afterAt.substring(terminationIndex)
            if (afterTermination.isNotEmpty() && afterTermination.first().isWhitespace()) {
                // Cursor is past the mention termination
                return MentionDetectionResult(
                    isTypingMention = false,
                    query = "",
                    triggerIndex = -1
                )
            }
        }

        // Still typing the mention - trim trailing whitespace for clean query
        val query = afterAt.trimEnd()

        return MentionDetectionResult(
            isTypingMention = true,
            query = query,
            triggerIndex = lastAtIndex
        )
    }

    /**
     * Find where a mention should terminate in unquoted text.
     *
     * Termination patterns:
     * - Double space "  " (explicit end of mention)
     * - Newline or tab (clear line break)
     * - Punctuation followed by space (e.g., ". " or ", ")
     * - Start of new sentence patterns
     *
     * @param text Text after @ symbol
     * @return Index where mention terminates, or -1 if still active
     */
    private fun findMentionTerminationIndex(text: String): Int {
        // Pattern 1: Double space - explicit termination
        val doubleSpaceIndex = text.indexOf("  ")
        if (doubleSpaceIndex != -1) return doubleSpaceIndex

        // Pattern 2: Newline or tab
        for ((index, char) in text.withIndex()) {
            if (char == '\n' || char == '\t') {
                return index
            }
        }

        // Pattern 3: Punctuation followed by space (sentence boundary)
        val sentenceEndPattern = Regex("""[.!?;:,]\s""")
        val match = sentenceEndPattern.find(text)
        if (match != null) {
            return match.range.first
        }

        // No termination found - mention is still active
        return -1
    }

    /**
     * Parse all mentions from a complete message.
     *
     * @param text Message text to parse
     * @return List of parsed mentions found
     */
    fun parseAllMentions(text: String): List<ParsedMention> {
        if (text.isBlank()) return emptyList()

        val mentions = mutableListOf<ParsedMention>()

        // First, find and exclude email patterns
        val emailRanges = EMAIL_PATTERN.findAll(text).map { it.range }.toList()

        // Find quoted mentions: @"..."
        QUOTED_MENTION_REGEX.findAll(text).forEach { match ->
            // Skip if this @ is part of an email
            if (!isWithinRanges(match.range.first, emailRanges)) {
                val query = match.groupValues[1] // Text inside quotes
                mentions.add(
                    ParsedMention(
                        rawText = match.value,
                        query = query,
                        startIndex = match.range.first,
                        endIndex = match.range.last + 1
                    )
                )
            }
        }

        // Find unquoted mentions: @word
        UNQUOTED_MENTION_REGEX.findAll(text).forEach { match ->
            // Skip if this @ is part of an email
            if (!isWithinRanges(match.range.first, emailRanges)) {
                // Skip if this overlaps with a quoted mention
                val overlapsQuoted = mentions.any { existing ->
                    match.range.first >= existing.startIndex && match.range.first < existing.endIndex
                }
                if (!overlapsQuoted) {
                    val query = match.groupValues[1] // Text after @
                    mentions.add(
                        ParsedMention(
                            rawText = match.value,
                            query = query,
                            startIndex = match.range.first,
                            endIndex = match.range.last + 1
                        )
                    )
                }
            }
        }

        // Sort by position in text
        return mentions.sortedBy { it.startIndex }
    }

    /**
     * Normalize a query for matching.
     *
     * IMPROVED: Better handling of space-separated queries.
     * - Converts underscores to spaces
     * - Collapses multiple spaces to single space
     * - Trims whitespace
     * - Lowercases for case-insensitive matching
     *
     * @param query Raw query from mention
     * @return Normalized query for search
     */
    fun normalizeQuery(query: String): String {
        return query
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ") // Collapse multiple spaces
            .trim()
            .lowercase()
    }

    /**
     * Calculate similarity score between query and target text.
     * Uses a combination of:
     * - Subsequence matching (letters in order, but not necessarily consecutive)
     * - Word overlap (how many query words appear in target)
     * - Prefix matching (bonus for matching start of words)
     *
     * @param query Normalized query string
     * @param target Target string to match against
     * @return Score from 0.0 to 1.0
     */
    fun calculateSimilarity(query: String, target: String): Double {
        if (query.isEmpty()) return 0.0
        if (target.isEmpty()) return 0.0

        val normalizedQuery = query.lowercase()
        val normalizedTarget = target.lowercase()

        // 1. Subsequence match score (fuzzy character-level matching)
        val subsequenceScore = calculateSubsequenceScore(normalizedQuery, normalizedTarget)

        // 2. Word overlap score
        val queryWords = normalizedQuery.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val targetWords = normalizedTarget.split(Regex("""\s+""")).filter { it.isNotEmpty() }

        val wordMatchCount = queryWords.count { queryWord ->
            targetWords.any { targetWord ->
                targetWord.contains(queryWord) || queryWord.contains(targetWord)
            }
        }
        val wordOverlapScore = if (queryWords.isNotEmpty()) {
            wordMatchCount.toDouble() / queryWords.size
        } else 0.0

        // 3. Prefix match bonus (reward matching start of words)
        val prefixMatchCount = queryWords.count { queryWord ->
            targetWords.any { targetWord -> targetWord.startsWith(queryWord) }
        }
        val prefixScore = if (queryWords.isNotEmpty()) {
            prefixMatchCount.toDouble() / queryWords.size * 0.3 // 30% bonus weight
        } else 0.0

        // Combine scores with weighted average
        return (subsequenceScore * 0.4 + wordOverlapScore * 0.4 + prefixScore * 0.2)
            .coerceIn(0.0, 1.0)
    }

    /**
     * Calculate subsequence matching score.
     * Checks if query characters appear in order in target (not necessarily consecutive).
     * This handles typos and partial matches well.
     */
    private fun calculateSubsequenceScore(query: String, target: String): Double {
        var queryIndex = 0
        var targetIndex = 0
        var matchedChars = 0

        while (queryIndex < query.length && targetIndex < target.length) {
            if (query[queryIndex] == target[targetIndex]) {
                matchedChars++
                queryIndex++
            }
            targetIndex++
        }

        return if (query.isNotEmpty()) {
            matchedChars.toDouble() / query.length
        } else 0.0
    }

    /**
     * Check if a query matches a type filter keyword.
     *
     * @param query Normalized query
     * @return Matching NoteType or null
     */
    fun matchTypeFilter(query: String): NoteType? {
        return TYPE_FILTERS[query.lowercase()]
    }

    /**
     * Check if a query matches a special filter.
     *
     * @param query Normalized query
     * @return Filter name if matched, null otherwise
     */
    fun matchSpecialFilter(query: String): String? {
        val normalized = query.lowercase()
        return when {
            normalized in SPECIAL_FILTERS -> normalized
            normalized == "starred" || normalized == "favorites" -> "pinned"
            else -> null
        }
    }

    /**
     * Check if a query matches a command (like @thinking).
     *
     * @param query Normalized query
     * @return CommandInfo if matched, null otherwise
     */
    fun matchCommand(query: String): CommandInfo? {
        return COMMANDS[query.lowercase()]
    }

    /**
     * Check if a query starts with any command (for autocomplete).
     *
     * @param query Partial query
     * @return List of matching CommandInfo
     */
    fun getMatchingCommands(query: String): List<CommandInfo> {
        val normalized = query.lowercase()
        return COMMANDS.entries
            .filter { (keyword, _) -> keyword.startsWith(normalized) }
            .map { it.value }
            .distinctBy { it.name }
    }

    /**
     * Remove all mentions from text, leaving clean message for AI.
     *
     * @param text Original text with mentions
     * @param mentions Parsed mentions to remove
     * @return Clean text without mentions
     */
    fun cleanMessage(text: String, mentions: List<ParsedMention>): String {
        if (mentions.isEmpty()) return text

        val result = StringBuilder(text)

        // Remove mentions from end to start to preserve indices
        mentions.sortedByDescending { it.startIndex }.forEach { mention ->
            result.delete(mention.startIndex, mention.endIndex)
        }

        // Clean up extra whitespace
        return result.toString()
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    /**
     * Check if a position falls within any of the given ranges.
     */
    private fun isWithinRanges(position: Int, ranges: List<IntRange>): Boolean {
        return ranges.any { position in it }
    }

    /**
     * Get display text for a type filter suggestion.
     */
    fun getTypeFilterDisplayName(type: NoteType): String {
        return when (type) {
            NoteType.AUDIO -> "Audios"
            NoteType.DOCUMENT -> "Documents"
            NoteType.IMAGE -> "Images"
            NoteType.VIDEO -> "Videos"
            NoteType.CODE -> "Code Files"
            NoteType.WEBSITE -> "Websites"
            NoteType.YOUTUBE -> "YouTube"
            NoteType.SPREADSHEET -> "Spreadsheets"
            NoteType.PRESENTATION -> "Presentations"
            NoteType.BRAIN_DUMP -> "Brain Dumps"
            NoteType.TWITTER -> "Twitter/X"
            NoteType.INSTAGRAM -> "Instagram"
            NoteType.FILE -> "Files"
            NoteType.ARCHIVE -> "Archives"
            NoteType.APK -> "APK Files"
        }
    }

    /**
     * Get the primary keyword for a type filter.
     */
    fun getTypeFilterKeyword(type: NoteType): String {
        return when (type) {
            NoteType.AUDIO -> "audios"
            NoteType.DOCUMENT -> "documents"
            NoteType.IMAGE -> "images"
            NoteType.VIDEO -> "videos"
            NoteType.CODE -> "code"
            NoteType.WEBSITE -> "websites"
            NoteType.YOUTUBE -> "youtube"
            NoteType.SPREADSHEET -> "spreadsheets"
            NoteType.PRESENTATION -> "presentations"
            NoteType.BRAIN_DUMP -> "braindumps"
            NoteType.TWITTER -> "twitter"
            NoteType.INSTAGRAM -> "instagram"
            NoteType.FILE -> "files"
            NoteType.ARCHIVE -> "archives"
            NoteType.APK -> "apks"
        }
    }

    /**
     * Get description for special filters.
     */
    fun getSpecialFilterDescription(filterName: String): String {
        return when (filterName.lowercase()) {
            "recent" -> "Most recently created notes"
            "pinned" -> "Pinned/starred notes"
            "all" -> "All accessible notes"
            else -> ""
        }
    }
}
