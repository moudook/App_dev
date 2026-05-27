package com.example.smarty.server.services

import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Service for processing files (PDF, images).
 * Uses PDFBox for PDF text extraction and page rendering.
 * Routes image processing through VisionService.
 */
class FileProcessingService(
    private val visionService: VisionService,
    private val httpClient: HttpClient,
) {
    private val logger = LoggerFactory.getLogger(FileProcessingService::class.java)

    companion object {
        private const val MAX_PDF_PAGES = 50
        private const val MAX_FILE_SIZE_MB = 20
        private const val MAX_TEXT_LENGTH = 100_000
    }

    /**
     * Process a PDF file and extract text.
     * For complex layouts with images, renders pages and uses OCR.
     *
     * @param pdfBytes The PDF file as bytes
     * @param fileName Optional filename for context
     * @param useOcrForImages Whether to OCR image-heavy pages
     * @return Extracted text and metadata
     */
    suspend fun processPdf(
        pdfBytes: ByteArray,
        fileName: String? = null,
        useOcrForImages: Boolean = true,
    ): PdfProcessingResult =
        withContext(Dispatchers.IO) {
            logger.info("Processing PDF: $fileName (${pdfBytes.size / 1024} KB)")

            if (pdfBytes.size > MAX_FILE_SIZE_MB * 1024 * 1024) {
                return@withContext PdfProcessingResult(
                    text = "",
                    pageCount = 0,
                    hasImages = false,
                    success = false,
                    error = "File too large (max ${MAX_FILE_SIZE_MB}MB)",
                )
            }

            try {
                val document = Loader.loadPDF(pdfBytes)
                document.use { pdf ->
                    val pageCount = minOf(pdf.getNumberOfPages(), MAX_PDF_PAGES)
                    val textStripper = PDFTextStripper()

                    // Extract text from all pages
                    textStripper.startPage = 1
                    textStripper.endPage = pageCount
                    var extractedText = textStripper.getText(pdf)

                    // Check if text extraction yielded meaningful content
                    val hasMinimalText = extractedText.replace(Regex("\\s+"), "").length < 100 && pageCount > 0
                    val hasImages = detectImages(pdf)

                    // If minimal text and has images, use OCR on rendered pages
                    if (hasMinimalText && hasImages && useOcrForImages) {
                        logger.info("PDF appears image-heavy, using OCR for $pageCount pages")
                        val ocrText = ocrPdfPages(pdf, minOf(pageCount, 10)) // Limit OCR to first 10 pages
                        if (ocrText.isNotBlank()) {
                            extractedText = ocrText
                        }
                    }

                    // Truncate if too long
                    val finalText =
                        if (extractedText.length > MAX_TEXT_LENGTH) {
                            extractedText.take(MAX_TEXT_LENGTH) + "\n\n[... content truncated ...]"
                        } else {
                            extractedText
                        }

                    PdfProcessingResult(
                        text = finalText.trim(),
                        pageCount = pageCount,
                        hasImages = hasImages,
                        success = true,
                    )
                }
            } catch (e: Exception) {
                logger.error("PDF processing failed: ${e.message}", e)
                PdfProcessingResult(
                    text = "",
                    pageCount = 0,
                    hasImages = false,
                    success = false,
                    error = "PDF processing failed: ${e.message}",
                )
            }
        }

    /**
     * Process an image file and extract text via OCR.
     *
     * @param imageBytes The image file as bytes
     * @param mimeType The MIME type
     * @param fileName Optional filename
     * @return OCR result
     */
    suspend fun processImage(
        imageBytes: ByteArray,
        mimeType: String,
        fileName: String? = null,
    ): ImageProcessingResult {
        logger.info("Processing image: $fileName (${imageBytes.size / 1024} KB, type: $mimeType)")

        val base64Image = Base64.getEncoder().encodeToString(imageBytes)
        val ocrResult = visionService.performOcr(base64Image, mimeType)

        return ImageProcessingResult(
            text = ocrResult.extractedText,
            contentType = ocrResult.contentType,
            success = ocrResult.success,
            error = ocrResult.error,
        )
    }

    /**
     * Detect if a PDF contains significant image content.
     */
    private fun detectImages(document: PDDocument): Boolean {
        return try {
            for (page in document.pages) {
                val resources = page.resources
                if (resources.xObjectNames.any { name ->
                        try {
                            resources.isImageXObject(name)
                        } catch (e: Exception) {
                            false
                        }
                    }
                ) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            logger.warn("Failed to detect images in PDF: ${e.message}")
            false
        }
    }

    /**
     * Render PDF pages to images and OCR them.
     */
    private suspend fun ocrPdfPages(
        document: PDDocument,
        maxPages: Int,
    ): String {
        val renderer = PDFRenderer(document)
        val texts = mutableListOf<String>()

        for (pageIndex in 0 until minOf(document.getNumberOfPages(), maxPages)) {
            try {
                // Render page at 150 DPI
                val image = renderer.renderImageWithDPI(pageIndex, 150f)

                // Convert to base64 PNG
                val outputStream = ByteArrayOutputStream()
                ImageIO.write(image, "PNG", outputStream)
                val base64Image = Base64.getEncoder().encodeToString(outputStream.toByteArray())

                // OCR the rendered page
                val ocrResult = visionService.performOcr(base64Image, "image/png")
                if (ocrResult.success && ocrResult.extractedText.isNotBlank()) {
                    texts.add("--- Page ${pageIndex + 1} ---\n${ocrResult.extractedText}")
                }
            } catch (e: Exception) {
                logger.warn("Failed to OCR page $pageIndex: ${e.message}")
            }
        }

        return texts.joinToString("\n\n")
    }
}

/**
 * Result of PDF processing.
 */
@Serializable
data class PdfProcessingResult(
    val text: String,
    val pageCount: Int,
    val hasImages: Boolean,
    val success: Boolean = true,
    val error: String? = null,
)

/**
 * Result of image processing.
 */
@Serializable
data class ImageProcessingResult(
    val text: String,
    val contentType: String,
    val success: Boolean = true,
    val error: String? = null,
)
