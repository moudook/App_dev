package com.example.smarty.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
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
        private const val MAX_TEXT_LENGTH = 15000  // Limit text to avoid token limits
        private const val MAX_PAGES = 50  // Reasonable page limit

        @Volatile
        private var isInitialized = false
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
                ?: return@withContext PDFExtractionResult.Error("Could not open PDF file")

            document = PDDocument.load(inputStream)

            val pageCount = document.numberOfPages
            Log.i(TAG, "PDF loaded: $pageCount pages")

            if (pageCount == 0) {
                return@withContext PDFExtractionResult.Error("PDF has no pages")
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
                    message = "This PDF appears to contain images only. Text extraction is not available for scanned documents."
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
                message = "Failed to extract text: ${e.message ?: "Unknown error"}"
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
                return@withContext PDFExtractionResult.Error("PDF has no pages")
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
                    message = "This PDF appears to contain images only."
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
            PDFExtractionResult.Error("Failed to extract text: ${e.message ?: "Unknown error"}")
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
            .replace(Regex("[ \\t]+"), " ")
            // Normalize multiple newlines to double newline (paragraph breaks)
            .replace(Regex("\\n{3,}"), "\n\n")
            // Remove page break artifacts
            .replace(Regex("\\f"), "\n\n")
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
