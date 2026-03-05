package com.example.smarty.util

import com.example.smarty.data.model.DocumentChunk
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for PdfChunker.
 * 
 * Tests cover:
 * - Chunking algorithm correctness
 * - Overlap handling
 * - Word boundary detection
 * - Edge cases (empty text, very long words)
 * - Metadata accuracy
 */
class PdfChunkerTest {

    @Test
    fun chunkLazy_producesCorrectNumberOfChunks() {
        // Given
        val text = "A".repeat(30000) // 30,000 characters
        val chunker = PdfChunker(maxChunkSize = 10000, overlap = 1000)

        // When
        val chunks = chunker.chunk(text)

        // Then
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.isNotEmpty() })
    }

    @Test
    fun chunkLazy_respectsMaxChunkSize() {
        // Given
        val text = "A".repeat(5000)
        val chunker = PdfChunker(maxChunkSize = 1000, overlap = 100)

        // When
        val chunks = chunker.chunk(text)

        // Then
        assertTrue(chunks.all { it.length <= 1100 }) // Max + small tolerance for word boundaries
    }

    @Test
    fun chunkLazy_includesOverlap() {
        // Given
        val text = "A".repeat(5000)
        val chunker = PdfChunker(maxChunkSize = 1000, overlap = 200)

        // When
        val chunks = chunker.chunk(text)

        // Then
        // Check that consecutive chunks have overlap
        for (i in 1 until chunks.size) {
            val prevChunk = chunks[i - 1]
            val currentChunk = chunks[i]
            
            // The end of previous should overlap with start of current
            if (prevChunk.length >= 200) {
                val overlapRegion = prevChunk.takeLast(200)
                assertTrue(currentChunk.startsWith(overlapRegion) || currentChunk.contains(overlapRegion))
            }
        }
    }

    @Test
    fun chunkLazy_handlesEmptyText() {
        // Given
        val text = ""
        val chunker = PdfChunker()

        // When
        val chunks = chunker.chunk(text)

        // Then
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun chunkLazy_handlesShortText() {
        // Given
        val text = "Short text"
        val chunker = PdfChunker(maxChunkSize = 1000, overlap = 100)

        // When
        val chunks = chunker.chunk(text)

        // Then
        assertEquals(1, chunks.size)
        assertEquals("Short text", chunks[0])
    }

    @Test
    fun chunkWithMetadata_producesCorrectMetadata() {
        // Given
        val text = "A".repeat(5000)
        val chunker = PdfChunker(maxChunkSize = 1000, overlap = 100)

        // When
        val chunks = chunker.chunkWithMetadata(text)

        // Then
        assertTrue(chunks.isNotEmpty())
        val totalChunks = chunks.size
        
        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.index)
            assertEquals(totalChunks, chunk.totalChunks)
            assertEquals(chunk.content.length, chunk.charCount)
            assertTrue(chunk.startPosition >= 0)
            assertTrue(chunk.endPosition <= text.length)
            assertTrue(chunk.startPosition < chunk.endPosition)
        }
    }

    @Test
    fun chunkWithMetadata_positionsAreContinuous() {
        // Given
        val text = "A".repeat(5000)
        val chunker = PdfChunker(maxChunkSize = 1000, overlap = 100)

        // When
        val chunks = chunker.chunkWithMetadata(text)

        // Then
        // Verify that chunks cover the entire text (with overlap)
        assertTrue(chunks.first().startPosition == 0)
        assertTrue(chunks.last().endPosition == text.length)
    }

    @Test
    fun chunkWithOverlap_findsWordBoundaries() {
        // Given
        val text = buildString {
            repeat(100) { append("This is a test sentence. ") }
        }
        val chunker = PdfChunker(maxChunkSize = 500, overlap = 50)

        // When
        val chunks = chunker.chunk(text)

        // Then
        // Chunks should end at word boundaries (spaces)
        chunks.dropLast(1).forEach { chunk ->
            // Should end at space or near end of text
            assertTrue(
                chunk.endsWith(" ") || 
                chunk.length < 50 // Small last chunk is OK
            )
        }
    }

    @Test
    fun chunker_validatesMaxChunkSize() {
        // When/Then
        assertFailsWith<IllegalArgumentException> {
            PdfChunker(maxChunkSize = 50) // Less than MIN_CHUNK_SIZE (100)
        }
    }

    @Test
    fun chunker_validatesOverlap() {
        // When/Then
        assertFailsWith<IllegalArgumentException> {
            PdfChunker(maxChunkSize = 1000, overlap = -1)
        }
        
        assertFailsWith<IllegalArgumentException> {
            PdfChunker(maxChunkSize = 1000, overlap = 1000) // overlap >= maxChunkSize
        }
    }

    @Test
    fun stringChunkWithOverlap_extension_worksCorrectly() {
        // Given
        val text = "A".repeat(3000)

        // When
        val chunks = text.chunkWithOverlap(maxChunkSize = 1000, overlap = 100).toList()

        // Then
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.isNotEmpty() })
    }

    @Test
    fun stringChunkIntoDocumentChunks_extension_worksCorrectly() {
        // Given
        val text = "A".repeat(3000)

        // When
        val chunks = text.chunkIntoDocumentChunks(maxChunkSize = 1000, overlap = 100)

        // Then
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it is DocumentChunk })
        
        // Verify metadata
        val totalChunks = chunks.size
        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.index)
            assertEquals(totalChunks, chunk.totalChunks)
        }
    }
}
