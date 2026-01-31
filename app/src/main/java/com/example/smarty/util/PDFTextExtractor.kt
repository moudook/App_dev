package com.example.smarty.util

import android.content.Context
import com.example.smarty.R
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Utility class for extracting text content from PDF files
 * Uses PDFBox-Android for reliable PDF text extraction
 */
class PDFTextExtractor(private val context: Context) {

    companion object {
        private const val TAG = "PDFTextExtractor"
        
        // Legacy limits (kept for backward compatibility with extractText)
        private const val MAX_TEXT_LENGTH = 15000  // Limit text to avoid token limits
        private const val MAX_PAGES = 50  // Reasonable page limit for single extraction
        
        // Chunked extraction parameters for processing ALL pages
        // These allow comprehensive analysis of 150+ page documents
        private const val PAGES_PER_CHUNK = 5           // Process 5 pages at a time
        private const val CHARS_PER_CHUNK = 4000        // ~1000 tokens per chunk
        private const val OVERLAP_CHARS = 400           // 10% overlap for context
        private const val MAX_CHUNKS = 50               // Safety limit: 50 chunks = 250 pages max
        private const val CHUNK_SUMMARY_TARGET = 300    // Target chars per chunk summary
        
        // No page limit for chunked extraction - process ALL pages
        private const val CHUNKED_MAX_PAGES = Int.MAX_VALUE
        
        // OCR fallback for scanned/image-based PDFs
        private const val OCR_MAX_PAGES = 20            // Max pages to OCR (memory-intensive)
        private const val OCR_DPI = 150                 // DPI for rendering (higher = better quality but slower)

        @Volatile
        private var isInitialized = false

        // Pre-compiled regex patterns for performance
        private val WHITESPACE_PATTERN = Regex("[ \\t]+")
        private val MULTIPLE_NEWLINES_PATTERN = Regex("\\n{3,}")
        private val PAGE_BREAK_PATTERN = Regex("\\f")
    }

    init {
        initializePDFBox()
    }

    /**
     * Initialize PDFBox resources (required for Android)
     */
    private fun initializePDFBox() {
        if (!isInitialized) {
            synchronized(this) {
                if (!isInitialized) {
                    try {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        isInitialized = true
                        Log.i(TAG, "PDFBox initialized successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to initialize PDFBox: ${e.message}", e)
                    }
                }
            }
        }
    }

    /**
     * Extract text from a PDF file URI
     *
     * @param uri The content URI of the PDF file
     * @return PDFExtractionResult containing the extracted text and metadata
     */
    suspend fun extractText(uri: Uri): PDFExtractionResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting PDF extraction from URI: $uri")

        var inputStream: InputStream? = null
        var document: PDDocument? = null

        try {
            inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext PDFExtractionResult.Error(context.getString(R.string.error_pdf_open))

            document = PDDocument.load(inputStream)

            val pageCount = document.numberOfPages
            Log.i(TAG, "PDF loaded: $pageCount pages")

            if (pageCount == 0) {
                return@withContext PDFExtractionResult.Error(context.getString(R.string.error_pdf_no_pages))
            }

            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                startPage = 1
                endPage = minOf(pageCount, MAX_PAGES)
            }

            val rawText = stripper.getText(document)

            // Clean and normalize the extracted text
            val cleanedText = cleanText(rawText)

            if (cleanedText.isBlank()) {
                Log.w(TAG, "No text content extracted (might be image-based PDF)")
                return@withContext PDFExtractionResult.Empty(
                    pageCount = pageCount,
                    message = context.getString(R.string.error_pdf_images_only)
                )
            }

            // Truncate if too long
            val finalText = if (cleanedText.length > MAX_TEXT_LENGTH) {
                Log.d(TAG, "Text truncated from ${cleanedText.length} to $MAX_TEXT_LENGTH chars")
                cleanedText.take(MAX_TEXT_LENGTH) + "\n\n[Content truncated for analysis...]"
            } else {
                cleanedText
            }

            Log.i(TAG, "Successfully extracted ${finalText.length} chars from $pageCount pages")

            PDFExtractionResult.Success(
                text = finalText,
                pageCount = pageCount,
                characterCount = finalText.length,
                wasTrauncated = cleanedText.length > MAX_TEXT_LENGTH
            )

        } catch (e: Exception) {
            Log.e(TAG, "PDF extraction failed: ${e.message}", e)
            PDFExtractionResult.Error(
                message = context.getString(R.string.error_pdf_extract_failed)
            )
        } finally {
            try {
                document?.close()
                inputStream?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing resources: ${e.message}")
            }
        }
    }

    /**
     * Extract text from a PDF input stream (for files not accessed via URI)
     */
    suspend fun extractText(inputStream: InputStream): PDFExtractionResult = withContext(Dispatchers.IO) {
        var document: PDDocument? = null

        try {
            document = PDDocument.load(inputStream)

            val pageCount = document.numberOfPages
            Log.i(TAG, "PDF loaded from stream: $pageCount pages")

            if (pageCount == 0) {
                return@withContext PDFExtractionResult.Error(context.getString(R.string.error_pdf_no_pages))
            }

            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                startPage = 1
                endPage = minOf(pageCount, MAX_PAGES)
            }

            val rawText = stripper.getText(document)
            val cleanedText = cleanText(rawText)

            if (cleanedText.isBlank()) {
                return@withContext PDFExtractionResult.Empty(
                    pageCount = pageCount,
                    message = context.getString(R.string.error_pdf_images_only)
                )
            }

            val finalText = if (cleanedText.length > MAX_TEXT_LENGTH) {
                cleanedText.take(MAX_TEXT_LENGTH) + "\n\n[Content truncated for analysis...]"
            } else {
                cleanedText
            }

            PDFExtractionResult.Success(
                text = finalText,
                pageCount = pageCount,
                characterCount = finalText.length,
                wasTrauncated = cleanedText.length > MAX_TEXT_LENGTH
            )

        } catch (e: Exception) {
            Log.e(TAG, "PDF extraction from stream failed: ${e.message}", e)
            PDFExtractionResult.Error(context.getString(R.string.error_pdf_extract_failed))
        } finally {
            try {
                document?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing document: ${e.message}")
            }
        }
    }

    /**
     * Clean and normalize extracted text
     */
    private fun cleanText(text: String): String {
        return text
            // Normalize whitespace
            .replace(WHITESPACE_PATTERN, " ")
            // Normalize multiple newlines to double newline (paragraph breaks)
            .replace(MULTIPLE_NEWLINES_PATTERN, "\n\n")
            // Remove page break artifacts
            .replace(PAGE_BREAK_PATTERN, "\n\n")
            // Remove trailing whitespace from lines
            .lines()
            .map { it.trimEnd() }
            .joinToString("\n")
            .trim()
    }

    /**
     * Get basic PDF info without full text extraction
     */
    suspend fun getPDFInfo(uri: Uri): PDFInfo? = withContext(Dispatchers.IO) {
        var inputStream: InputStream? = null
        var document: PDDocument? = null

        try {
            inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            document = PDDocument.load(inputStream)

            val info = document.documentInformation

            PDFInfo(
                pageCount = document.numberOfPages,
                title = info?.title,
                author = info?.author,
                subject = info?.subject,
                creator = info?.creator,
                creationDate = info?.creationDate?.time
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get PDF info: ${e.message}")
            null
        } finally {
            try {
                document?.close()
                inputStream?.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Extract text from PDF in chunks for processing long documents (150+ pages).
     * 
     * This method processes ALL pages of the PDF, not just the first 50.
     * Each chunk contains text from a configurable number of pages with overlap
     * to maintain context across chunk boundaries.
     * 
     * Memory-efficient: Processes page-by-page, doesn't load entire document.
     * Model-agnostic: Returns chunks suitable for any LLM provider.
     * 
     * @param uri The content URI of the PDF file
     * @return PDFChunkedResult containing chunks for map-reduce summarization
     */
    suspend fun extractTextChunked(uri: Uri): PDFChunkedResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting chunked PDF extraction from URI: $uri")

        var inputStream: InputStream? = null
        var document: PDDocument? = null

        try {
            inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext PDFChunkedResult.Error(context.getString(R.string.error_pdf_open))

            document = PDDocument.load(inputStream)

            val totalPages = document.numberOfPages
            Log.i(TAG, "PDF loaded for chunked extraction: $totalPages pages")

            if (totalPages == 0) {
                return@withContext PDFChunkedResult.Error(context.getString(R.string.error_pdf_no_pages))
            }

            val chunks = mutableListOf<PDFChunk>()
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            
            var chunkIndex = 0
            var currentPage = 1
            var previousOverlapText = ""
            var totalCharactersExtracted = 0

            // Process pages in groups of PAGES_PER_CHUNK
            while (currentPage <= totalPages && chunkIndex < MAX_CHUNKS) {
                val startPage = currentPage
                val endPage = minOf(currentPage + PAGES_PER_CHUNK - 1, totalPages)

                // Extract text for this chunk of pages
                stripper.startPage = startPage
                stripper.endPage = endPage
                
                val rawText = try {
                    stripper.getText(document)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to extract pages $startPage-$endPage: ${e.message}")
                    currentPage = endPage + 1
                    continue
                }

                val cleanedText = cleanText(rawText)
                
                if (cleanedText.isNotBlank()) {
                    // Add overlap from previous chunk for context continuity
                    val textWithContext = if (previousOverlapText.isNotEmpty()) {
                        "[...] $previousOverlapText\n\n$cleanedText"
                    } else {
                        cleanedText
                    }
                    
                    // Truncate if individual chunk is too large
                    val chunkText = if (textWithContext.length > CHARS_PER_CHUNK) {
                        textWithContext.take(CHARS_PER_CHUNK) + "\n[...]"
                    } else {
                        textWithContext
                    }

                    chunks.add(
                        PDFChunk(
                            index = chunkIndex,
                            startPage = startPage,
                            endPage = endPage,
                            text = chunkText,
                            characterCount = chunkText.length
                        )
                    )

                    totalCharactersExtracted += cleanedText.length
                    chunkIndex++

                    // Save overlap for next chunk (last OVERLAP_CHARS of current text)
                    previousOverlapText = if (cleanedText.length > OVERLAP_CHARS) {
                        cleanedText.takeLast(OVERLAP_CHARS)
                    } else {
                        cleanedText
                    }
                }

                currentPage = endPage + 1
            }

            if (chunks.isEmpty()) {
                Log.w(TAG, "No text content extracted (might be image-based PDF)")
                return@withContext PDFChunkedResult.Empty(
                    pageCount = totalPages,
                    message = context.getString(R.string.error_pdf_images_only)
                )
            }

            val pagesProcessed = chunks.lastOrNull()?.endPage ?: 0
            val wasLimited = chunkIndex >= MAX_CHUNKS && currentPage <= totalPages

            Log.i(TAG, "Chunked extraction complete: ${chunks.size} chunks, $totalCharactersExtracted chars from $pagesProcessed pages")

            PDFChunkedResult.Success(
                chunks = chunks,
                totalPages = totalPages,
                pagesProcessed = pagesProcessed,
                totalCharacters = totalCharactersExtracted,
                chunkCount = chunks.size,
                wasLimited = wasLimited
            )

        } catch (e: Exception) {
            Log.e(TAG, "Chunked PDF extraction failed: ${e.message}", e)
            PDFChunkedResult.Error(
                message = context.getString(R.string.error_pdf_extract_failed)
            )
        } finally {
            try {
                document?.close()
                inputStream?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing resources: ${e.message}")
            }
        }
    }

    /**
     * Check if a PDF is considered "long" and should use chunked processing.
     * Returns true for PDFs over 30 pages (where chunking provides significant benefit).
     */
    suspend fun isLongDocument(uri: Uri): Boolean {
        val info = getPDFInfo(uri)
        return info != null && info.pageCount > 30
    }

    /**
     * Get recommended processing strategy for a PDF.
     * Returns CHUNKED for long documents (>30 pages), DIRECT for shorter ones.
     */
    suspend fun getProcessingStrategy(uri: Uri): ProcessingStrategy {
        val info = getPDFInfo(uri) ?: return ProcessingStrategy.DIRECT
        return when {
            info.pageCount > 100 -> ProcessingStrategy.CHUNKED_HIERARCHICAL
            info.pageCount > 30 -> ProcessingStrategy.CHUNKED
            else -> ProcessingStrategy.DIRECT
        }
    }
    
    /**
     * Extract text from a scanned/image-based PDF using OCR.
     * 
     * This method is called as a FALLBACK when regular text extraction returns empty,
     * indicating the PDF contains images instead of selectable text.
     * 
     * Process:
     * 1. Render each PDF page as a bitmap
     * 2. Run ML Kit OCR on each bitmap
     * 3. Combine extracted text from all pages
     * 
     * Memory-efficient: Renders and processes one page at a time.
     * 
     * @param uri The content URI of the PDF file
     * @return PDFExtractionResult with OCR-extracted text
     */
    suspend fun extractTextWithOcr(uri: Uri): PDFExtractionResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting OCR extraction for scanned PDF: $uri")
        
        var inputStream: InputStream? = null
        var document: PDDocument? = null
        
        try {
            inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext PDFExtractionResult.Error(context.getString(R.string.error_pdf_open))

            document = PDDocument.load(inputStream)
            val pageCount = document.numberOfPages
            val pagesToProcess = minOf(pageCount, OCR_MAX_PAGES)

            Log.i(TAG, "OCR: Processing $pagesToProcess of $pageCount pages")

            if (pageCount == 0) {
                return@withContext PDFExtractionResult.Error(context.getString(R.string.error_pdf_no_pages))
            }
            
            val renderer = PDFRenderer(document)
            val extractedTexts = mutableListOf<String>()
            var totalCharacters = 0
            
            for (pageIndex in 0 until pagesToProcess) {
                try {
                    // Render page to bitmap
                    val bitmap: Bitmap = renderer.renderImageWithDPI(pageIndex, OCR_DPI.toFloat())
                    
                    // Run OCR on the bitmap
                    val ocrResult = ImageTextExtractor.extractTextFromBitmap(bitmap)
                    
                    // Recycle bitmap immediately to free memory
                    bitmap.recycle()
                    
                    if (ocrResult.hasText) {
                        val pageText = "[Page ${pageIndex + 1}]\n${ocrResult.text}"
                        extractedTexts.add(pageText)
                        totalCharacters += ocrResult.text.length
                        Log.d(TAG, "OCR Page ${pageIndex + 1}: ${ocrResult.text.length} chars extracted")
                    } else {
                        Log.d(TAG, "OCR Page ${pageIndex + 1}: No text found")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "OCR failed for page ${pageIndex + 1}: ${e.message}")
                    // Continue with other pages
                }
            }
            
            if (extractedTexts.isEmpty()) {
                Log.w(TAG, "OCR extraction found no text in any pages")
                return@withContext PDFExtractionResult.Empty(
                    pageCount = pageCount,
                    message = context.getString(R.string.error_pdf_ocr_no_text)
                )
            }
            
            val combinedText = extractedTexts.joinToString("\n\n")
            val wasTruncated = pagesToProcess < pageCount
            
            val finalText = if (combinedText.length > MAX_TEXT_LENGTH) {
                Log.d(TAG, "OCR text truncated from ${combinedText.length} to $MAX_TEXT_LENGTH chars")
                combinedText.take(MAX_TEXT_LENGTH) + "\n\n[OCR content truncated...]"
            } else {
                combinedText
            }
            
            Log.i(TAG, "OCR extraction complete: ${finalText.length} chars from $pagesToProcess pages")
            
            PDFExtractionResult.Success(
                text = "[OCR Extracted Text]\n$finalText",
                pageCount = pageCount,
                characterCount = finalText.length,
                wasTrauncated = wasTruncated || combinedText.length > MAX_TEXT_LENGTH
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "OCR extraction failed: ${e.message}", e)
            PDFExtractionResult.Error(
                message = context.getString(R.string.error_pdf_ocr_failed)
            )
        } finally {
            try {
                document?.close()
                inputStream?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing resources: ${e.message}")
            }
        }
    }
    
    /**
     * Smart extraction that tries regular text extraction first,
     * then falls back to OCR if the PDF is image-based.
     * 
     * @param uri The content URI of the PDF file
     * @return PDFExtractionResult with extracted text (from text layer or OCR)
     */
    suspend fun extractTextWithOcrFallback(uri: Uri): PDFExtractionResult {
        // First, try regular text extraction
        val result = extractText(uri)
        
        return when (result) {
            is PDFExtractionResult.Empty -> {
                // PDF is image-based - try OCR
                Log.i(TAG, "Regular extraction empty, attempting OCR fallback")
                extractTextWithOcr(uri)
            }
            else -> result
        }
    }
}

/**
 * Result of PDF text extraction
 */
sealed class PDFExtractionResult {
    data class Success(
        val text: String,
        val pageCount: Int,
        val characterCount: Int,
        val wasTrauncated: Boolean
    ) : PDFExtractionResult()

    data class Empty(
        val pageCount: Int,
        val message: String
    ) : PDFExtractionResult()

    data class Error(
        val message: String
    ) : PDFExtractionResult()
}

/**
 * Basic PDF metadata
 */
data class PDFInfo(
    val pageCount: Int,
    val title: String?,
    val author: String?,
    val subject: String?,
    val creator: String?,
    val creationDate: java.util.Date?
)

/**
 * A single chunk from a PDF for map-reduce summarization.
 * Contains text from a range of pages with metadata.
 */
data class PDFChunk(
    val index: Int,           // Chunk number (0-based)
    val startPage: Int,       // First page in this chunk (1-based)
    val endPage: Int,         // Last page in this chunk (1-based)
    val text: String,         // Extracted and cleaned text
    val characterCount: Int   // Number of characters in this chunk
) {
    /**
     * Format chunk for AI prompt with page context.
     */
    fun toPromptContext(): String {
        return "[Pages $startPage-$endPage]\n$text"
    }
}

/**
 * Result of chunked PDF extraction for long documents.
 * Contains all chunks ready for map-reduce summarization.
 */
sealed class PDFChunkedResult {
    data class Success(
        val chunks: List<PDFChunk>,   // All extracted chunks
        val totalPages: Int,          // Total pages in PDF
        val pagesProcessed: Int,      // Pages actually processed
        val totalCharacters: Int,     // Total characters extracted
        val chunkCount: Int,          // Number of chunks
        val wasLimited: Boolean       // True if hit MAX_CHUNKS limit
    ) : PDFChunkedResult() {
        
        /**
         * Get combined text from all chunks (for direct processing if context allows).
         * Use only if total characters fit in model context window.
         */
        fun getCombinedText(): String {
            return chunks.joinToString("\n\n---\n\n") { it.toPromptContext() }
        }
        
        /**
         * Check if document was fully processed (no pages skipped).
         */
        fun isComplete(): Boolean = pagesProcessed >= totalPages && !wasLimited
    }

    data class Empty(
        val pageCount: Int,
        val message: String
    ) : PDFChunkedResult()

    data class Error(
        val message: String
    ) : PDFChunkedResult()
}

/**
 * Processing strategy for PDFs based on document length.
 */
enum class ProcessingStrategy {
    /** Direct extraction - for short documents (≤30 pages) */
    DIRECT,
    
    /** Chunked extraction with map-reduce - for medium documents (31-100 pages) */
    CHUNKED,
    
    /** Hierarchical chunked extraction - for very long documents (>100 pages) */
    CHUNKED_HIERARCHICAL
}
