package com.example.smarty.util

import com.example.smarty.data.model.DocumentChunk
import kotlin.math.max
import kotlin.math.min

/**
 * Data class for PDF chunker configuration.
 * Allows easy customization of chunking behavior.
 *
 * @param chunkSize Number of characters per chunk
 * @param overlap Number of overlapping characters between chunks
 */
data class ChunkerConfig(
    val chunkSize: Int,
    val overlap: Int,
) {
    companion object {
        val SMALL = ChunkerConfig(8_000, 300)
        val MEDIUM = ChunkerConfig(32_000, 500)
        val LARGE = ChunkerConfig(64_000, 800)
        val XLARGE = ChunkerConfig(128_000, 1_000)

        fun forContextWindow(contextSize: Int): ChunkerConfig =
            when {
                contextSize < 32_000 -> SMALL
                contextSize < 128_000 -> MEDIUM
                contextSize < 256_000 -> LARGE
                else -> XLARGE
            }
    }
}

/**
 * High-performance PDF text chunker with intelligent boundary detection.
 *
 * Architecture:
 * - Strategy pattern for different model configurations
 * - Lazy evaluation for memory efficiency (O(1) space)
 * - Single-pass algorithm (O(n) time)
 * - Word-boundary-aware splitting
 * - Comprehensive error handling
 *
 * Usage:
 * ```kotlin
 * val chunker = PdfChunker()  // Uses default MEDIUM config
 * val chunks = chunker.chunkWithMetadata(largeText)
 *
 * // Or use specific config
 * val chunker = PdfChunker(ChunkerConfig.LARGE)
 * ```
 */
class PdfChunker(
    private val config: ChunkerConfig = ChunkerConfig.MEDIUM,
) {
    // Validate configuration at construction time
    init {
        require(config.chunkSize >= MIN_CHUNK_SIZE) {
            "Chunk size must be at least $MIN_CHUNK_SIZE, got ${config.chunkSize}"
        }
        require(config.overlap >= 0) {
            "Overlap must be non-negative, got ${config.overlap}"
        }
        require(config.overlap < config.chunkSize) {
            "Overlap (${config.overlap}) must be less than chunk size (${config.chunkSize})"
        }
    }

    companion object {
        private const val MIN_CHUNK_SIZE = 100
        private const val WORD_BOUNDARY_SEARCH_RANGE = 500
    }

    /**
     * Chunk text lazily using Sequence for O(1) memory footprint.
     * Ideal for processing large documents without loading everything into memory.
     *
     * Time Complexity: O(n) where n is text length
     * Space Complexity: O(1) - lazy evaluation
     *
     * @param text Full text to chunk
     * @return Sequence of text chunks (evaluated on-demand)
     */
    fun chunkLazy(text: String): Sequence<String> =
        sequence {
            if (text.isEmpty()) return@sequence

            var startIndex = 0
            val textLength = text.length

            while (startIndex < textLength) {
                val endIndex = calculateChunkEnd(text, startIndex, textLength)
                if (endIndex <= startIndex) break

                val chunk = text.substring(startIndex, endIndex).trim()
                if (chunk.isNotEmpty()) {
                    yield(chunk)
                }

                startIndex = calculateNextStart(startIndex, endIndex, textLength)
            }
        }

    /**
     * Chunk text eagerly into a List.
     * Use for smaller documents or when random access is needed.
     *
     * Time Complexity: O(n)
     * Space Complexity: O(n)
     *
     * @param text Full text to chunk
     * @return List of text chunks
     */
    fun chunk(text: String): List<String> = chunkLazy(text).toList()

    /**
     * Chunk text with full metadata for DocumentChunk creation.
     * Single-pass algorithm with pre-calculated boundaries.
     *
     * Time Complexity: O(n) - single pass
     * Space Complexity: O(k) where k is number of chunks
     *
     * @param text Full text to chunk
     * @return List of DocumentChunk with complete metadata
     */
    fun chunkWithMetadata(text: String): List<DocumentChunk> {
        if (text.isEmpty()) return emptyList()

        // Pre-calculate all boundaries in single pass (optimization)
        val boundaries = calculateChunkBoundaries(text)
        val totalChunks = boundaries.size

        // Transform boundaries to DocumentChunks with correct metadata
        return boundaries.mapIndexed { index, boundary ->
            val chunkText = text.substring(boundary.start, boundary.end).trim()
            DocumentChunk(
                index = index,
                totalChunks = totalChunks,
                content = chunkText,
                charCount = chunkText.length,
                startPosition = boundary.start,
                endPosition = boundary.end,
            )
        }
    }

    /**
     * Pre-calculate all chunk boundaries in a single pass.
     * This eliminates repeated calculations and ensures O(n) complexity.
     *
     * Algorithm:
     * 1. Start at beginning of text
     * 2. Calculate end position (start + chunkSize)
     * 3. Find nearest word boundary before end
     * 4. Store boundary, move to next chunk
     * 5. Repeat until end of text
     *
     * @return List of ChunkBoundary objects
     */
    private fun calculateChunkBoundaries(text: String): List<ChunkBoundary> {
        val estimatedChunks = text.length / config.chunkSize + 1
        val boundaries = ArrayList<ChunkBoundary>(estimatedChunks)
        var startIndex = 0
        val textLength = text.length

        while (startIndex < textLength) {
            val endIndex = calculateChunkEnd(text, startIndex, textLength)
            if (endIndex <= startIndex) break

            boundaries.add(ChunkBoundary(startIndex, endIndex))
            startIndex = calculateNextStart(startIndex, endIndex, textLength)
        }

        return boundaries
    }

    /**
     * Calculate the end position for a chunk starting at startIndex.
     * Finds word boundary for clean breaks to avoid splitting words.
     */
    private fun calculateChunkEnd(
        text: String,
        startIndex: Int,
        textLength: Int,
    ): Int {
        // Calculate rough end position
        val roughEnd = min(startIndex + config.chunkSize, textLength)

        // If at end of text, no need to find word boundary
        if (roughEnd >= textLength) return textLength

        // Find last word boundary within search range (optimization)
        val searchStart = max(startIndex, roughEnd - WORD_BOUNDARY_SEARCH_RANGE)
        return findLastWordBoundary(text, searchStart, roughEnd)
    }

    /**
     * Find the last word boundary in a range using efficient character iteration.
     * Falls back to roughEnd if no boundary found.
     *
     * Optimization: Uses direct character comparison instead of regex for speed.
     */
    private fun findLastWordBoundary(
        text: String,
        searchStart: Int,
        roughEnd: Int,
    ): Int {
        // Iterate backwards from roughEnd to find whitespace
        for (i in roughEnd - 1 downTo searchStart) {
            if (text[i].isWhitespace()) {
                return i + 1 // Include the whitespace
            }
        }
        // No boundary found, use roughEnd
        return roughEnd
    }

    /**
     * Calculate next chunk's start position with overlap.
     * Ensures continuity between chunks for context preservation.
     */
    private fun calculateNextStart(
        currentStart: Int,
        currentEnd: Int,
        textLength: Int,
    ): Int =
        if (currentEnd < textLength) {
            // Move back by overlap amount for context continuity
            max(currentStart + 1, currentEnd - config.overlap)
        } else {
            // Signal to stop
            textLength
        }
}

/**
 * Internal data class for tracking chunk boundaries.
 * Used for pre-calculation optimization.
 *
 * @property start Start index (inclusive)
 * @property end End index (exclusive)
 */
private data class ChunkBoundary(
    val start: Int,
    val end: Int,
)

/**
 * Extension function on String for convenient chunking.
 *
 * @param config Chunker configuration (default: MEDIUM)
 * @return Sequence of chunks (lazy evaluation)
 */
fun String.chunkWithOverlap(config: ChunkerConfig = ChunkerConfig.MEDIUM): Sequence<String> {
    val chunker = PdfChunker(config)
    return chunker.chunkLazy(this)
}

/**
 * Extension function to chunk text directly into DocumentChunk list.
 *
 * @param config Chunker configuration (default: MEDIUM)
 * @return List of DocumentChunk with metadata
 */
fun String.chunkIntoDocumentChunks(config: ChunkerConfig = ChunkerConfig.MEDIUM): List<DocumentChunk> {
    val chunker = PdfChunker(config)
    return chunker.chunkWithMetadata(this)
}
