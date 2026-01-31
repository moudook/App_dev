package com.example.smarty.util

import com.example.smarty.data.model.NoteType
import android.content.Context
import com.example.smarty.R

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
     * Storage category resource IDs for non-analyzable file types.
     */
    private val STORAGE_CATEGORY_RES_MAP: Map<NoteType, Int> = hashMapOf(
        NoteType.APK to R.string.category_saved_apps,
        NoteType.ARCHIVE to R.string.category_saved_archives,
        NoteType.VIDEO to R.string.category_saved_videos,
        NoteType.AUDIO to R.string.category_saved_audio,
        NoteType.FILE to R.string.category_saved_files
    )

    /**
     * Note type title resource IDs.
     */
    private val TYPE_TITLE_RES = arrayOf(
        0,                             // BRAIN_DUMP
        R.string.note_type_youtube,    // YOUTUBE
        R.string.note_type_website,    // WEBSITE
        R.string.note_type_image,      // IMAGE
        R.string.note_type_twitter,    // TWITTER
        R.string.note_type_instagram,  // INSTAGRAM
        R.string.note_type_document,   // DOCUMENT
        R.string.note_type_spreadsheet,// SPREADSHEET
        R.string.note_type_presentation,// PRESENTATION
        R.string.note_type_video,      // VIDEO
        R.string.note_type_audio,      // AUDIO
        R.string.note_type_code,       // CODE
        R.string.note_type_archive,    // ARCHIVE
        R.string.note_type_apk,        // APK
        R.string.note_type_file        // FILE
    )

    /**
     * Default title resource IDs for shared files.
     */
    private val DEFAULT_TITLE_RES = arrayOf(
        R.string.default_title_content,     // BRAIN_DUMP
        R.string.default_title_content,     // YOUTUBE
        R.string.default_title_content,     // WEBSITE
        R.string.default_title_image,       // IMAGE
        R.string.default_title_content,     // TWITTER
        R.string.default_title_content,     // INSTAGRAM
        R.string.default_title_document,    // DOCUMENT
        R.string.default_title_spreadsheet, // SPREADSHEET
        R.string.default_title_presentation,// PRESENTATION
        R.string.default_title_video,       // VIDEO
        R.string.default_title_audio,       // AUDIO
        R.string.default_title_code,        // CODE
        R.string.default_title_archive,     // ARCHIVE
        R.string.default_title_apk,         // APK
        R.string.default_title_file         // FILE
    )

    /**
     * File size unit labels for formatting.
     */
    private val SIZE_UNITS = arrayOf("B", "KB", "MB", "GB")

    /**
     * Threshold for file size unit conversion (1024 bytes = 1 KB).
     */
    private const val SIZE_THRESHOLD = 1024L

    // ==================== AI Response Template resource keys ====================

    private val MOCK_AI_RES_PREFIXES = arrayOf(
        null,    // BRAIN_DUMP
        "learn", // YOUTUBE
        "read",  // WEBSITE
        "visual",// IMAGE
        "tweet", // TWITTER
        "inspo", // INSTAGRAM
        "docs",  // DOCUMENT
        "data",  // SPREADSHEET
        "slides",// PRESENTATION
        "watch"  // VIDEO
    )

    // ==================== Public API ====================

    /**
     * Detects the content type from text content.
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
     */
    fun detectTypeFromMime(mimeType: String?): NoteType {
        if (mimeType == null) return NoteType.FILE

        // Fast path: direct lookup in hash map
        MIME_TYPE_MAP[mimeType]?.let { return it }

        // Prefix matching for media categories
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
     */
    fun extractUrl(text: String): String? = URL_PATTERN.find(text)?.value

    /**
     * Extracts the YouTube video ID from a URL or text containing a URL.
     */
    fun extractYouTubeId(text: String): String? {
        return YOUTUBE_ID_PATTERN.find(text)?.groupValues?.get(1)
    }

    /**
     * Generates a title for a note based on its content and type.
     */
    fun extractTitle(context: Context, content: String, type: NoteType): String {
        val resId = TYPE_TITLE_RES.getOrNull(type.ordinal) ?: 0
        if (resId != 0) return context.getString(resId)

        // BRAIN_DUMP: create preview from content
        return if (content.length > 50) "${content.take(50)}..." else content
    }

    /**
     * Gets the default title for a note type.
     */
    fun getDefaultTitle(context: Context, type: NoteType): String {
        val resId = DEFAULT_TITLE_RES.getOrNull(type.ordinal) ?: R.string.default_title_content
        return context.getString(resId)
    }

    /**
     * Gets the storage category name for a file type.
     */
    fun getStorageCategoryName(context: Context, type: NoteType): String {
        val resId = STORAGE_CATEGORY_RES_MAP[type] ?: R.string.category_saved_files
        return context.getString(resId)
    }

    /**
     * Formats a file size in bytes to a human-readable string.
     */
    fun formatFileSize(context: Context, bytes: Long): String {
        if (bytes < SIZE_THRESHOLD) return "$bytes ${context.getString(R.string.size_unit_b)}"
        val unitIndex = minOf(
            (63 - java.lang.Long.numberOfLeadingZeros(bytes)) / 10,
            SIZE_UNITS.size - 1
        )
        val divisor = 1L shl (unitIndex * 10)
        val unitResId = when (unitIndex) {
            1 -> R.string.size_unit_kb
            2 -> R.string.size_unit_mb
            3 -> R.string.size_unit_gb
            else -> R.string.size_unit_b
        }
        return "${bytes / divisor} ${context.getString(unitResId)}"
    }

    /**
     * Formats a file size with decimal precision for larger sizes.
     */
    fun formatSize(context: android.content.Context, bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes ${context.getString(R.string.size_unit_b)}"
            bytes < 1024 * 1024 -> "${bytes / 1024} ${context.getString(R.string.size_unit_kb)}"
            else -> String.format("%.1f ${context.getString(R.string.size_unit_mb)}", bytes / (1024.0 * 1024.0))
        }
    }

    /**
     * Generates a mock AI response for a note type.
     */
    fun generateMockAiResponse(context: Context, type: NoteType, content: String): Triple<String, String, String> {
        val ordinal = type.ordinal

        if (ordinal in 1..9) {
            MOCK_AI_RES_PREFIXES[ordinal]?.let { prefix ->
                val tag = context.getString(context.resources.getIdentifier("mock_ai_tag_$prefix", "string", context.packageName))
                val summary = context.getString(context.resources.getIdentifier("mock_ai_summary_$prefix", "string", context.packageName))
                val intent = context.getString(context.resources.getIdentifier("mock_ai_intent_$prefix", "string", context.packageName))
                return Triple(tag, summary, intent)
            }
        }

        if (!NoteType.isAnalyzable(type)) {
            return Triple(getStorageCategoryName(context, type), "", "")
        }

        return analyzeBrainDumpContent(context, content)
    }

    private fun analyzeBrainDumpContent(context: Context, content: String): Triple<String, String, String> {
        val prefix = when {
            CODE_PATTERN.containsMatchIn(content) -> "code"
            QUOTE_PATTERN.containsMatchIn(content) -> "quote"
            BUY_PATTERN.containsMatchIn(content) -> "buy"
            TASK_PATTERN.containsMatchIn(content) -> "todo"
            LEARN_PATTERN.containsMatchIn(content) -> "study"
            IDEA_PATTERN.containsMatchIn(content) -> "idea"
            else -> "note"
        }

        val tag = context.getString(context.resources.getIdentifier("mock_ai_tag_$prefix", "string", context.packageName))
        val summary = context.getString(context.resources.getIdentifier("mock_ai_summary_$prefix", "string", context.packageName))
        val intent = context.getString(context.resources.getIdentifier("mock_ai_intent_$prefix", "string", context.packageName))
        return Triple(tag, summary, intent)
    }

    /**
     * Checks if content contains a URL.
     */
    fun containsUrl(text: String): Boolean = PROTOCOL_PATTERN.containsMatchIn(text)

    /**
     * Checks if content is a YouTube URL.
     */
    fun isYouTubeUrl(text: String): Boolean = YOUTUBE_PATTERN.containsMatchIn(text)

    /**
     * Checks if content is a Twitter/X URL.
     */
    fun isTwitterUrl(text: String): Boolean = TWITTER_PATTERN.containsMatchIn(text)

    /**
     * Checks if content is an Instagram URL.
     */
    fun isInstagramUrl(text: String): Boolean = INSTAGRAM_PATTERN.containsMatchIn(text)
}