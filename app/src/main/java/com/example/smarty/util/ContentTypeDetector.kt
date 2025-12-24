package com.example.smarty.util

import com.example.smarty.data.model.NoteType

/**
 * Utility object for detecting content types from text, URLs, and MIME types.
 *
 * This detector uses pre-compiled regex patterns for efficient O(1) amortized matching
 * and hash maps for O(1) average lookup of MIME types to note types.
 *
 * Usage:
 * ```kotlin
 * val type = ContentTypeDetector.detectContentType("https://youtube.com/watch?v=abc")
 * // Returns NoteType.YOUTUBE
 *
 * val mimeType = ContentTypeDetector.detectTypeFromMime("application/pdf")
 * // Returns NoteType.DOCUMENT
 * ```
 *
 * @see NoteType for the list of supported content types
 */
object ContentTypeDetector {

    // ==================== URL Detection Patterns ====================

    /**
     * Pattern to extract URLs from text content.
     * Matches http:// and https:// URLs, excluding whitespace and special characters.
     */
    private val URL_PATTERN = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")

    /**
     * Pattern to detect YouTube video URLs.
     * Matches both youtube.com and youtu.be domains.
     */
    private val YOUTUBE_PATTERN = Regex("""youtube\.com|youtu\.be""", RegexOption.IGNORE_CASE)

    /**
     * Pattern to extract YouTube video ID.
     * Supports all major YouTube URL formats:
     * - Standard: youtube.com/watch?v=VIDEO_ID
     * - Short: youtu.be/VIDEO_ID
     * - Shorts: youtube.com/shorts/VIDEO_ID
     * - Embed: youtube.com/embed/VIDEO_ID
     * - Live: youtube.com/live/VIDEO_ID
     * - With tracking params: ?si=xxx, &feature=share, etc.
     */
    private val YOUTUBE_ID_PATTERN = Regex(
        """(?:https?://)?(?:www\.|m\.)?(?:youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|embed/|v/|live/)|youtu\.be/)([a-zA-Z0-9_-]{11})"""
    )

    /**
     * Pattern to detect Twitter/X social media URLs.
     * Matches both twitter.com and x.com domains.
     */
    private val TWITTER_PATTERN = Regex("""twitter\.com|x\.com""", RegexOption.IGNORE_CASE)

    /**
     * Pattern to detect Instagram URLs.
     */
    private val INSTAGRAM_PATTERN = Regex("""instagram\.com""", RegexOption.IGNORE_CASE)

    /**
     * Pattern to detect if text contains a protocol (indicates it's a URL).
     */
    private val PROTOCOL_PATTERN = Regex("""://""")

    // ==================== Brain Dump Content Patterns ====================

    /**
     * Pattern to detect idea/thought content in brain dumps.
     * Matches keywords like "idea", "think", "concept", "maybe", "could", "what if".
     */
    private val IDEA_PATTERN = Regex("""idea|think|concept|maybe|could|what if""", RegexOption.IGNORE_CASE)

    /**
     * Pattern to detect task/todo content in brain dumps.
     * Matches keywords like "todo", "task", "remember", "don't forget", "need to", etc.
     */
    private val TASK_PATTERN = Regex("""todo|task|remember|don't forget|need to|must|should""", RegexOption.IGNORE_CASE)

    /**
     * Pattern to detect learning/research content in brain dumps.
     * Matches keywords like "learn", "study", "research", "understand", etc.
     */
    private val LEARN_PATTERN = Regex("""learn|study|research|understand|figure out|how to""", RegexOption.IGNORE_CASE)

    /**
     * Pattern to detect shopping/purchase content in brain dumps.
     * Matches keywords like "buy", "purchase", "order", "get", "shop", "price".
     */
    private val BUY_PATTERN = Regex("""buy|purchase|order|get|shop|price""", RegexOption.IGNORE_CASE)

    /**
     * Pattern to detect quote content in brain dumps.
     * Matches quoted text or keywords like "said", "quote".
     */
    private val QUOTE_PATTERN = Regex("""[""].*[""]|said|quote""", RegexOption.IGNORE_CASE)

    /**
     * Pattern to detect code content in brain dumps.
     * Matches code blocks and common programming keywords.
     */
    private val CODE_PATTERN = Regex("""```|func|def |class |const |let |var |import |function""", RegexOption.IGNORE_CASE)

    // ==================== MIME Type Mappings ====================

    /**
     * Hash map for O(1) average lookup of MIME types to NoteType.
     * Covers documents, spreadsheets, presentations, code files, archives, and APKs.
     */
    private val MIME_TYPE_MAP: Map<String, NoteType> = hashMapOf(
        // Documents
        "application/pdf" to NoteType.DOCUMENT,
        "application/msword" to NoteType.DOCUMENT,
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to NoteType.DOCUMENT,
        "text/plain" to NoteType.DOCUMENT,
        "application/rtf" to NoteType.DOCUMENT,
        // Spreadsheets
        "application/vnd.ms-excel" to NoteType.SPREADSHEET,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to NoteType.SPREADSHEET,
        "text/csv" to NoteType.SPREADSHEET,
        // Presentations
        "application/vnd.ms-powerpoint" to NoteType.PRESENTATION,
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to NoteType.PRESENTATION,
        // Code
        "application/javascript" to NoteType.CODE,
        "application/json" to NoteType.CODE,
        "application/xml" to NoteType.CODE,
        "text/html" to NoteType.CODE,
        "text/css" to NoteType.CODE,
        // Archives
        "application/zip" to NoteType.ARCHIVE,
        "application/x-rar-compressed" to NoteType.ARCHIVE,
        "application/x-7z-compressed" to NoteType.ARCHIVE,
        "application/gzip" to NoteType.ARCHIVE,
        "application/x-tar" to NoteType.ARCHIVE,
        // APK
        "application/vnd.android.package-archive" to NoteType.APK
    )

    /**
     * Storage category names for non-analyzable file types.
     * Used to automatically categorize files that don't need AI processing.
     */
    private val STORAGE_CATEGORY_MAP: Map<NoteType, String> = hashMapOf(
        NoteType.APK to "Saved Apps",
        NoteType.ARCHIVE to "Saved Archives",
        NoteType.VIDEO to "Saved Videos",
        NoteType.AUDIO to "Saved Audio",
        NoteType.FILE to "Saved Files"
    )

    // ==================== Title Generation ====================

    /**
     * O(1) title lookup array indexed by NoteType ordinal.
     * Returns null for BRAIN_DUMP (uses content preview instead).
     */
    private val TYPE_TITLES = arrayOf(
        null,              // BRAIN_DUMP - uses content preview
        "YouTube Video",   // YOUTUBE
        "Web Link",        // WEBSITE
        "Image",           // IMAGE
        "Tweet",           // TWITTER
        "Instagram Post",  // INSTAGRAM
        "Document",        // DOCUMENT
        "Spreadsheet",     // SPREADSHEET
        "Presentation",    // PRESENTATION
        "Video",           // VIDEO
        "Audio",           // AUDIO
        "Code File",       // CODE
        "Archive",         // ARCHIVE
        "APK File",        // APK
        "File"             // FILE
    )

    /**
     * Default titles for shared files by type.
     * Used when no specific title can be extracted.
     */
    private val DEFAULT_TITLES = arrayOf(
        "Shared Content",     // BRAIN_DUMP
        "Shared Content",     // YOUTUBE
        "Shared Content",     // WEBSITE
        "Shared Image",       // IMAGE
        "Shared Content",     // TWITTER
        "Shared Content",     // INSTAGRAM
        "Shared Document",    // DOCUMENT
        "Shared Spreadsheet", // SPREADSHEET
        "Shared Presentation",// PRESENTATION
        "Shared Video",       // VIDEO
        "Shared Audio",       // AUDIO
        "Shared Code",        // CODE
        "Shared Archive",     // ARCHIVE
        "Shared APK",         // APK
        "Shared File"         // FILE
    )

    // ==================== File Size Formatting ====================

    /**
     * File size unit labels for formatting.
     */
    private val SIZE_UNITS = arrayOf("B", "KB", "MB", "GB")

    /**
     * Threshold for file size unit conversion (1024 bytes = 1 KB).
     */
    private const val SIZE_THRESHOLD = 1024L

    // ==================== AI Response Templates ====================

    /**
     * Pre-computed AI responses indexed by NoteType ordinal.
     * Format: Triple(tag (max 2 words), brutal summary (3 lines max), intent)
     * Returns null for types that require content analysis (BRAIN_DUMP) or aren't analyzable.
     */
    private val AI_RESPONSES = arrayOf<Triple<String, String, String>?>(
        null, // BRAIN_DUMP - requires content analysis
        Triple("Learn", "Tutorial or educational content. Watch to level up skills.", "To learn something"),
        Triple("Read", "Article worth reading. Key info inside.", "To read later"),
        Triple("Visual", "Image reference. Useful for inspiration or context.", "For reference"),
        Triple("Tweet", "Hot take or insight. Social signal worth noting.", "Perspective matters"),
        Triple("Inspo", "Visual content. Creative fuel for future projects.", "Creative spark"),
        Triple("Docs", "Document to review. Contains important information.", "Need this info"),
        Triple("Data", "Numbers and data. Analyze when needed.", "For analysis"),
        Triple("Slides", "Presentation deck. Review the key points.", "To review"),
        Triple("Watch", "Video content. Queue for later viewing.", "To watch later")
    )

    // ==================== Public API ====================

    /**
     * Detects the content type from text content.
     *
     * Checks for URL patterns in the following order:
     * 1. YouTube URLs
     * 2. Twitter/X URLs
     * 3. Instagram URLs
     * 4. Other URLs (websites)
     * 5. Falls back to BRAIN_DUMP for plain text
     *
     * @param text The text content to analyze
     * @return The detected [NoteType]
     */
    fun detectContentType(text: String): NoteType {
        return when {
            YOUTUBE_PATTERN.containsMatchIn(text) -> NoteType.YOUTUBE
            TWITTER_PATTERN.containsMatchIn(text) -> NoteType.TWITTER
            INSTAGRAM_PATTERN.containsMatchIn(text) -> NoteType.INSTAGRAM
            PROTOCOL_PATTERN.containsMatchIn(text) -> NoteType.WEBSITE
            else -> NoteType.BRAIN_DUMP
        }
    }

    /**
     * Detects the content type from a MIME type string.
     *
     * Uses O(1) hash map lookup for exact matches, then falls back to
     * prefix matching for media types (image, video, audio wildcards).
     *
     * @param mimeType The MIME type string (e.g., "application/pdf")
     * @return The detected [NoteType], defaults to FILE if unknown
     */
    fun detectTypeFromMime(mimeType: String?): NoteType {
        if (mimeType == null) return NoteType.FILE

        // Fast path: direct lookup in hash map - O(1) average
        MIME_TYPE_MAP[mimeType]?.let { return it }

        // Prefix matching for media categories - O(1) with substring check
        return when {
            mimeType.startsWith("image/") -> NoteType.IMAGE
            mimeType.startsWith("video/") -> NoteType.VIDEO
            mimeType.startsWith("audio/") -> NoteType.AUDIO
            mimeType.startsWith("text/x-") -> NoteType.CODE
            else -> NoteType.FILE
        }
    }

    /**
     * Extracts the first URL from text content.
     *
     * @param text The text to search for URLs
     * @return The first URL found, or null if none
     */
    fun extractUrl(text: String): String? = URL_PATTERN.find(text)?.value

    /**
     * Extracts the YouTube video ID from a URL or text containing a URL.
     *
     * @param text The text to search
     * @return The video ID if found, null otherwise
     */
    fun extractYouTubeId(text: String): String? {
        return YOUTUBE_ID_PATTERN.find(text)?.groupValues?.get(1)
    }

    /**
     * Generates a title for a note based on its content and type.
     *
     * For specific types (YouTube, Twitter, etc.), returns a descriptive title.
     * For BRAIN_DUMP, creates a preview from the content (max 50 chars).
     *
     * @param content The note content
     * @param type The detected note type
     * @return A suitable title for the note
     */
    fun extractTitle(content: String, type: NoteType): String {
        TYPE_TITLES[type.ordinal]?.let { return it }
        // BRAIN_DUMP: create preview from content
        return if (content.length > 50) "${content.take(50)}..." else content
    }

    /**
     * Gets the default title for a note type.
     * Used when no specific title can be extracted from content.
     *
     * @param type The note type
     * @return The default title for that type
     */
    fun getDefaultTitle(type: NoteType): String = DEFAULT_TITLES[type.ordinal]

    /**
     * Gets the storage category name for a file type.
     * Used for non-analyzable files that are automatically categorized.
     *
     * @param type The note type
     * @return The category name (e.g., "Saved Apps" for APK)
     */
    fun getStorageCategoryName(type: NoteType): String =
        STORAGE_CATEGORY_MAP[type] ?: "Saved Files"

    /**
     * Formats a file size in bytes to a human-readable string.
     * Uses logarithmic calculation for efficient unit selection.
     *
     * @param bytes The file size in bytes
     * @return Formatted string (e.g., "1.5 MB", "256 KB")
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes < SIZE_THRESHOLD) return "$bytes ${SIZE_UNITS[0]}"

        // Use log base 1024 to find the right unit - O(1)
        val unitIndex = minOf(
            (63 - java.lang.Long.numberOfLeadingZeros(bytes)) / 10,
            SIZE_UNITS.size - 1
        )
        val divisor = 1L shl (unitIndex * 10) // 2^(unitIndex * 10) using bit shift
        return "${bytes / divisor} ${SIZE_UNITS[unitIndex]}"
    }

    /**
     * Formats a file size with decimal precision for larger sizes.
     *
     * @param bytes The file size in bytes
     * @return Formatted string with decimal for MB+ sizes
     */
    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    /**
     * Generates a mock AI response for a note type.
     * Used for offline fallback when AI service is unavailable.
     *
     * For BRAIN_DUMP content, analyzes keywords to categorize the note.
     * For other types, returns pre-computed responses.
     *
     * @param type The note type
     * @param content The note content (used for BRAIN_DUMP analysis)
     * @return Triple of (category tag, summary, intent)
     */
    fun generateMockAiResponse(type: NoteType, content: String): Triple<String, String, String> {
        val ordinal = type.ordinal

        // Fast path: O(1) array lookup for pre-computed responses
        if (ordinal in 1..9) {
            AI_RESPONSES[ordinal]?.let { return it }
        }

        // Non-analyzable types fallback - O(1) using storage category map
        if (!NoteType.isAnalyzable(type)) {
            return Triple(getStorageCategoryName(type), "", "")
        }

        // BRAIN_DUMP: keyword analysis with pre-compiled regex - O(n)
        return analyzeBrainDumpContent(content)
    }

    /**
     * Analyzes brain dump content to categorize based on keywords.
     * Uses pre-compiled regex patterns for efficient matching.
     *
     * @param content The brain dump content to analyze
     * @return Triple of (category tag (2 words max), brutal summary, intent)
     */
    private fun analyzeBrainDumpContent(content: String): Triple<String, String, String> {
        return when {
            CODE_PATTERN.containsMatchIn(content) -> Triple(
                "Code",
                "Code snippet saved. Copy-paste ready when needed.",
                "Dev reference"
            )
            QUOTE_PATTERN.containsMatchIn(content) -> Triple(
                "Quote",
                "Words worth remembering. Wisdom captured.",
                "Words matter"
            )
            BUY_PATTERN.containsMatchIn(content) -> Triple(
                "Buy",
                "Shopping note. Item to purchase or consider.",
                "Need to get this"
            )
            TASK_PATTERN.containsMatchIn(content) -> Triple(
                "Todo",
                "Action required. Don't forget to execute.",
                "Must do this"
            )
            LEARN_PATTERN.containsMatchIn(content) -> Triple(
                "Study",
                "Learning note. Review to retain knowledge.",
                "Building knowledge"
            )
            IDEA_PATTERN.containsMatchIn(content) -> Triple(
                "Idea",
                "Raw thought captured. Revisit and expand later.",
                "Future me needs this"
            )
            else -> Triple(
                "Note",
                "Quick capture. Context preserved for later.",
                "Just needed to save"
            )
        }
    }

    /**
     * Checks if content contains a URL.
     *
     * @param text The text to check
     * @return true if the text contains a URL
     */
    fun containsUrl(text: String): Boolean = PROTOCOL_PATTERN.containsMatchIn(text)

    /**
     * Checks if content is a YouTube URL.
     *
     * @param text The text to check
     * @return true if the text contains a YouTube URL
     */
    fun isYouTubeUrl(text: String): Boolean = YOUTUBE_PATTERN.containsMatchIn(text)

    /**
     * Checks if content is a Twitter/X URL.
     *
     * @param text The text to check
     * @return true if the text contains a Twitter/X URL
     */
    fun isTwitterUrl(text: String): Boolean = TWITTER_PATTERN.containsMatchIn(text)

    /**
     * Checks if content is an Instagram URL.
     *
     * @param text The text to check
     * @return true if the text contains an Instagram URL
     */
    fun isInstagramUrl(text: String): Boolean = INSTAGRAM_PATTERN.containsMatchIn(text)
}
