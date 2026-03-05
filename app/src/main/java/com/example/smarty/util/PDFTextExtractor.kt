package com.example.smarty.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.smarty.core.common.AppConfig
import com.example.smarty.data.model.DocumentChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Optimized PDF text extractor with efficient chunking.
 *
 * IMPROVEMENTS:
 * - Reduced chunking complexity from O(n²) to O(n) by pre-calculating boundaries
 * - Eliminated double list allocation using single-pass chunk creation
 * - Added lazy Sequence-based chunking for memory efficiency
 * - Used tail-recursive chunk boundary calculation
 * - OPTIMIZED: Larger chunk sizes for modern LLMs (32K default, up to 128K supported)
 *
 * Note: PDFBox Android library is used for actual PDF text extraction.
 * If PDFBox is not available, returns empty result.
 *
 * @param context Android context for URI access
 */
class PDFTextExtractor(private val context: Context) {
    companion object {
        private const val TAG = "PDFTextExtractor"
        
        // Optimized defaults for large language models
        private const val DEFAULT_MAX_CHUNK_SIZE = 32_000  // Increased from 12K for larger models
        private const val DEFAULT_OVERLAP = 500  // Reduced from 1K - sufficient for context
    }

    /**
     * Extract text from a PDF file in chunks for efficient processing.
     * Uses PdfChunker for efficient O(n) chunking.
     *
     * Note: This is a stub implementation. PDFBox Android integration requires:
     * 1. Add dependency: implementation("com.tom-roush:pdfbox-android:2.0.27.0")
     * 2. Import: import com.tomroush.pdfbox.pdmodel.PDDocument
     * 3. Import: import com.tomroush.pdfbox.text.PDFTextStripper
     *
     * @param uri URI of the PDF file to extract
     * @param maxChunkSize Maximum characters per chunk (default: from AppConfig)
     * @param overlap Character overlap between chunks (default: from AppConfig)
     * @return PDFChunkedResult containing extracted text and metadata
     */
    suspend fun extractTextChunked(
        uri: Uri,
        maxChunkSize: Int = AppConfig.pdf.chunkSize,
        overlap: Int = AppConfig.pdf.overlap
    ): PDFChunkedResult = withContext(Dispatchers.IO) {
        try {
            // Open input stream
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext PDFChunkedResult.Error("Could not open PDF file")
            
            inputStream.use { stream ->
                // Try to load PDF using PDFBox Android
                try {
                    // PDFBox integration (requires pdfbox-android dependency)
                    val documentClass = Class.forName("com.tomroush.pdfbox.pdmodel.PDDocument")
                    val textStripperClass = Class.forName("com.tomroush.pdfbox.text.PDFTextStripper")
                    
                    // Load document via reflection (avoids compile-time dependency)
                    val loadMethod = documentClass.getMethod("load", java.io.InputStream::class.java)
                    val document = loadMethod.invoke(null, stream)
                    
                    try {
                        // Get number of pages
                        val numberOfPagesMethod = documentClass.getMethod("numberOfPages")
                        val totalPages = numberOfPagesMethod.invoke(document) as Int
                        
                        if (totalPages == 0) {
                            return@use PDFChunkedResult.Empty("PDF has no pages")
                        }
                        
                        // Extract text
                        val textStripper = textStripperClass.getDeclaredConstructor().newInstance()
                        val getTextMethod = textStripperClass.getMethod("getText", documentClass)
                        val fullText = getTextMethod.invoke(textStripper, document) as String
                        
                        if (fullText.isBlank()) {
                            return@use PDFChunkedResult.Empty("PDF contains no extractable text")
                        }
                        
                        // Use optimized PdfChunker for efficient chunking
                        val chunker = PdfChunker(
                            config = ChunkerConfig(
                                chunkSize = maxChunkSize,
                                overlap = overlap
                            )
                        )
                        
                        val chunks = chunker.chunkWithMetadata(fullText)
                        
                        Log.d(TAG, "Extracted ${chunks.size} chunks from $totalPages pages (${fullText.length} chars)")
                        
                        PDFChunkedResult.Success(
                            chunks = chunks,
                            fullText = fullText,
                            totalPages = totalPages,
                            totalCharacters = fullText.length
                        )
                    } finally {
                        // Close document
                        val closeMethod = documentClass.getMethod("close")
                        closeMethod.invoke(document)
                    }
                } catch (e: ClassNotFoundException) {
                    // PDFBox not available - return error with helpful message
                    Log.e(TAG, "PDFBox Android not available. Add dependency: com.tom-roush:pdfbox-android", e)
                    PDFChunkedResult.Error("PDF library not available. Please install PDFBox Android.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting PDF text", e)
            PDFChunkedResult.Error("Extraction failed: ${e.message ?: "Unknown error"}")
        }
    }
}
