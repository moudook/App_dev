package com.example.smarty.core.common.util

import android.util.Log
import com.example.smarty.data.remote.AIService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * =============================================================================
 * TITLE COMPRESSOR
 * =============================================================================
 *
 * AI-powered utility to compress long music/audio titles to 2-3 words.
 *
 * Features:
 * - Compresses verbose titles to concise 2-3 word versions
 * - Preserves the essence/meaning of the title
 * - Falls back to simple truncation if AI unavailable
 * - Memory-efficient with minimal allocations
 *
 * Usage:
 *   val compressed = TitleCompressor.compressTitle(aiService, "My Very Long Song Title - Remix Version")
 *   // Returns: "Long Song Remix"
 *
 * =============================================================================
 */
object TitleCompressor {
    private const val TAG = "TitleCompressor"

    // Pre-compiled regex patterns for performance
    private val WHITESPACE_PATTERN = Regex("\\s+")
    private val JSON_RESPONSE_PATTERN = Regex(""""response"\s*:\s*"([^"]+)"""")
    private val QUOTES_PATTERN = Regex("""^["'\s]+|["'\s]+$""")
    private val PARENTHESES_PATTERN = Regex("""\([^)]*\)""")
    private val BRACKETS_PATTERN = Regex("""\[[^\]]*\]""")
    private val SUFFIX_PATTERN = Regex("""-\s*(remix|version|remaster|official|live|acoustic).*""", RegexOption.IGNORE_CASE)
    private val FEAT_PATTERN = Regex("""feat\.?\s+.*""", RegexOption.IGNORE_CASE)

    // System prompt for title compression
    private const val COMPRESSION_SYSTEM_PROMPT = """
        <system_instructions>
        <identity>
            You are a title compressor. Your ONLY job is to compress music/audio titles to exactly 2-3 words.
        </identity>

        <rules>
            1. **Output**: ONLY the compressed title (2-3 words).
            2. **Format**: No quotes, no punctuation, no explanation.
            3. **Content**: Keep the essence/meaning of the original.
            4. **Filtering**: Remove common filler words (feat, remix, version, official, etc) unless essential.
            5. **Priority**: Prioritize the main subject/theme.
        </rules>

        <examples>
            <example>Input: "Shape of You (Official Music Video)" -> Output: Shape of You</example>
            <example>Input: "Bohemian Rhapsody - 2011 Remaster" -> Output: Bohemian Rhapsody</example>
            <example>Input: "Don't Stop Me Now - Live at Wembley" -> Output: Don't Stop Now</example>
            <example>Input: "Despacito (Remix) [feat. Justin Bieber]" -> Output: Despacito Remix</example>
            <example>Input: "Symphony No. 5 in C minor, Op. 67: I. Allegro con brio" -> Output: Symphony Fifth Allegro</example>
        </examples>
        </system_instructions>
    """

    /**
     * Compress a title to 2-3 words using AI.
     *
     * @param aiService The AI service to use for compression
     * @param title The original title to compress
     * @return Compressed title (2-3 words) or fallback if AI fails
     */
    suspend fun compressTitle(
        aiService: AIService,
        title: String,
    ): String =
        withContext(Dispatchers.IO) {
            // Skip if already short enough
            val wordCount = title.trim().split(WHITESPACE_PATTERN).size
            if (wordCount <= 3) {
                Log.d(TAG, "Title already short: $title")
                return@withContext title.trim()
            }

            try {
                Log.d(TAG, "Compressing title with AI: $title")

                val response =
                    aiService.simpleChat(
                        systemPrompt = COMPRESSION_SYSTEM_PROMPT,
                        userPrompt = "Compress this title to 2-3 words: $title",
                    )

                // Extract just the compressed title from response
                val compressed = extractCompressedTitle(response, title)
                Log.d(TAG, "Compressed: '$title' -> '$compressed'")
                compressed
            } catch (e: Exception) {
                Log.w(TAG, "AI compression failed, using fallback: ${e.message}")
                fallbackCompress(title)
            }
        }

    /**
     * Extract the compressed title from AI response.
     * Handles various response formats (JSON, plain text, etc.)
     */
    private fun extractCompressedTitle(
        response: String,
        originalTitle: String,
    ): String {
        // Try to extract from JSON response field
        val jsonMatch = JSON_RESPONSE_PATTERN.find(response)
        if (jsonMatch != null) {
            return cleanTitle(jsonMatch.groupValues[1])
        }

        // Try to find a short title in the response (2-5 words on a line)
        val lines = response.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        for (line in lines) {
            val cleaned = cleanTitle(line)
            val words = cleaned.split(WHITESPACE_PATTERN)
            if (words.size in 2..4 && !cleaned.contains("{") && !cleaned.contains(":")) {
                return cleaned
            }
        }

        // If response looks like just the title (short and clean)
        val cleanedResponse = cleanTitle(response)
        val words = cleanedResponse.split(WHITESPACE_PATTERN)
        if (words.size in 2..4) {
            return cleanedResponse
        }

        // Fallback to simple compression
        return fallbackCompress(originalTitle)
    }

    /**
     * Clean up title by removing quotes, extra punctuation, etc.
     */
    private fun cleanTitle(title: String): String =
        title
            .replace(QUOTES_PATTERN, "") // Remove quotes at start/end
            .replace(WHITESPACE_PATTERN, " ") // Normalize whitespace
            .trim()

    /**
     * Fallback compression when AI is unavailable.
     * Takes first 2-3 meaningful words.
     */
    fun fallbackCompress(title: String): String {
        // Remove common patterns
        val cleaned =
            title
                .replace(PARENTHESES_PATTERN, "") // Remove (parentheses)
                .replace(BRACKETS_PATTERN, "") // Remove [brackets]
                .replace(SUFFIX_PATTERN, "")
                .replace(FEAT_PATTERN, "")
                .replace(WHITESPACE_PATTERN, " ")
                .trim()

        // Take first 3 words
        val words = cleaned.split(WHITESPACE_PATTERN)
        return words.take(3).joinToString(" ").ifBlank {
            title.split(WHITESPACE_PATTERN).take(3).joinToString(" ")
        }
    }

    /**
     * Batch compress multiple titles efficiently.
     * Uses parallel processing for speed on capable devices.
     */
    suspend fun compressTitles(
        aiService: AIService,
        titles: List<String>,
    ): List<String> =
        withContext(Dispatchers.IO) {
            val maxParallel =
                try {
                    ResourceManager.getMaxParallelOperations()
                } catch (e: Exception) {
                    2
                }

            if (maxParallel <= 1 || titles.size <= 2) {
                // Sequential for edge devices
                titles.map { compressTitle(aiService, it) }
            } else {
                // Parallel for capable devices using coroutineScope
                coroutineScope {
                    titles
                        .map { title ->
                            async { compressTitle(aiService, title) }
                        }.awaitAll()
                }
            }
        }
}
