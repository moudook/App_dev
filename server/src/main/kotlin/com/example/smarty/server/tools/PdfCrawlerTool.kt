package com.example.smarty.server.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * PDF Crawler Tool - Extract text from PDF documents.
 *
 * Supports:
 * - Remote PDFs (via URL)
 * - Local PDFs (via file path)
 * - Academic papers, technical reports, government documents
 * - OCR-ready (can be extended with PDFBox OCR)
 *
 * Use cases:
 * - Extract content from academic papers (.pdf)
 * - Process government reports (NIST, CISA, NSA)
 * - Extract technical documentation
 * - Parse research articles
 */
class PdfCrawlerTool {
    private val logger = LoggerFactory.getLogger(PdfCrawlerTool::class.java)

    companion object {
        private const val MAX_FILE_SIZE_MB = 50 // Max PDF size to process
        private const val MAX_PAGES = 100 // Max pages to extract
        private const val TIMEOUT_MS = 30000 // 30 second timeout
    }

    /**
     * Extract text from a PDF URL
     * SECURITY: Validates URL to prevent SSRF attacks
     */
    suspend fun extractFromUrl(pdfUrl: String): PdfExtractionResult {
        logger.info("Extracting text from PDF: $pdfUrl")

        return withContext(Dispatchers.IO) {
            try {
                // SECURITY: Validate URL to prevent SSRF
                val validatedUrl =
                    validateUrl(pdfUrl) ?: return@withContext PdfExtractionResult(
                        success = false,
                        errorMessage = "Invalid or blocked URL: $pdfUrl",
                    )

                val connection = URL(validatedUrl).openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    // SECURITY: Disable redirects to prevent SSRF via redirect chains
                    instanceFollowRedirects = false
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext PdfExtractionResult(
                        success = false,
                        errorMessage = "HTTP error: $responseCode",
                    )
                }

                // Check content type
                val contentType = connection.contentType
                if (contentType != "application/pdf") {
                    return@withContext PdfExtractionResult(
                        success = false,
                        errorMessage = "Not a PDF: $contentType",
                    )
                }

                // Check file size
                val fileSize = connection.contentLengthLong
                if (fileSize > MAX_FILE_SIZE_MB * 1024 * 1024) {
                    return@withContext PdfExtractionResult(
                        success = false,
                        errorMessage = "PDF too large: ${fileSize / 1024 / 1024}MB",
                    )
                }

                // Download to temp file
                val tempFile = File.createTempFile("pdf_", ".tmp")
                try {
                    tempFile.outputStream().use { output ->
                        connection.inputStream.use { input ->
                            input.copyTo(output)
                        }
                    }

                    // Extract text
                    extractFromFile(tempFile.absolutePath)
                } finally {
                    tempFile.delete()
                }
            } catch (e: Exception) {
                logger.error("Failed to extract PDF from URL: $pdfUrl", e)
                PdfExtractionResult(
                    success = false,
                    errorMessage = e.message ?: "Unknown error",
                )
            }
        }
    }

    /**
     * Validate URL to prevent SSRF attacks.
     * Returns the validated URL string or null if invalid/blocked.
     */
    private fun validateUrl(url: String): String? {
        return try {
            val parsedUrl = java.net.URL(url)
            val protocol = parsedUrl.protocol.lowercase()
            val host = parsedUrl.host

            // Only allow HTTP/HTTPS
            if (protocol != "http" && protocol != "https") {
                logger.warn("Blocked URL with disallowed protocol: $protocol - $url")
                return null
            }

            // Check if host is an IP address
            val ipAddress =
                try {
                    java.net.InetAddress.getByName(host)
                } catch (e: Exception) {
                    // If not an IP, it's a domain name - allow it
                    return url
                }

            // Check if IP is in private/blocked ranges
            val ipBytes = ipAddress.address

            // Check for private IP ranges (RFC 1918, loopback, link-local)
            val firstOctet = ipBytes[0].toInt() and 0xFF
            val secondOctet = ipBytes[1].toInt() and 0xFF

            // 127.0.0.0/8 (loopback)
            if (firstOctet == 127) {
                logger.warn("Blocked URL with loopback IP: $url")
                return null
            }

            // 10.0.0.0/8 (private)
            if (firstOctet == 10) {
                logger.warn("Blocked URL with private IP (10.x.x.x): $url")
                return null
            }

            // 172.16.0.0/12 (private)
            if (firstOctet == 172 && secondOctet in 16..31) {
                logger.warn("Blocked URL with private IP (172.16-31.x.x): $url")
                return null
            }

            // 192.168.0.0/16 (private)
            if (firstOctet == 192 && secondOctet == 168) {
                logger.warn("Blocked URL with private IP (192.168.x.x): $url")
                return null
            }

            // 169.254.0.0/16 (link-local)
            if (firstOctet == 169 && secondOctet == 254) {
                logger.warn("Blocked URL with link-local IP: $url")
                return null
            }

            url
        } catch (e: Exception) {
            logger.warn("Invalid URL format: $url - ${e.message}")
            null
        }
    }

    /**
     * Extract text from a local PDF file
     */
    suspend fun extractFromFile(filePath: String): PdfExtractionResult {
        logger.info("Extracting text from local PDF: $filePath")

        return withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    return@withContext PdfExtractionResult(
                        success = false,
                        errorMessage = "File not found: $filePath",
                    )
                }

                // Check file size
                if (file.length() > MAX_FILE_SIZE_MB * 1024 * 1024) {
                    return@withContext PdfExtractionResult(
                        success = false,
                        errorMessage = "PDF too large: ${file.length() / 1024 / 1024}MB",
                    )
                }

                // Load PDF using PDFBox
                val document = Loader.loadPDF(file)
                try {
                    val totalPages = document.numberOfPages
                    val pagesToExtract = minOf(totalPages, MAX_PAGES)

                    val text =
                        buildString {
                            for (i in 1..pagesToExtract) {
                                val stripper = PDFTextStripper()
                                stripper.startPage = i
                                stripper.endPage = i
                                val pageText = stripper.getText(document)
                                appendLine("=== PAGE $i ===")
                                appendLine(pageText)
                                appendLine()
                            }
                        }

                    val metadata = extractMetadata(document)

                    PdfExtractionResult(
                        success = true,
                        content = text.trim(),
                        totalPages = totalPages,
                        extractedPages = pagesToExtract,
                        wordCount = text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size,
                        metadata = metadata,
                    )
                } finally {
                    document.close()
                }
            } catch (e: IOException) {
                logger.error("Failed to extract PDF: $filePath", e)
                PdfExtractionResult(
                    success = false,
                    errorMessage = "PDF extraction error: ${e.message}",
                )
            } catch (e: Exception) {
                logger.error("Unexpected error extracting PDF: $filePath", e)
                PdfExtractionResult(
                    success = false,
                    errorMessage = e.message ?: "Unknown error",
                )
            }
        }
    }

    /**
     * Extract metadata from PDF
     */
    private fun extractMetadata(document: PDDocument): PdfMetadata {
        val docInfo = document.documentInformation

        return PdfMetadata(
            title = docInfo.title,
            author = docInfo.author,
            subject = docInfo.subject,
            keywords = docInfo.keywords,
            creator = docInfo.creator,
            producer = docInfo.producer,
            creationDate = docInfo.creationDate?.toString(),
            modificationDate = docInfo.modificationDate?.toString(),
        )
    }

    /**
     * Check if a URL points to a PDF
     */
    suspend fun isPdfUrl(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "HEAD"
                    connectTimeout = 10000
                    instanceFollowRedirects = true
                }

                val contentType = connection.contentType
                contentType == "application/pdf" || url.endsWith(".pdf", ignoreCase = true)
            } catch (e: Exception) {
                logger.warn("Failed to check if URL is PDF: $url", e)
                false
            }
        }
    }

    /**
     * Extract text from byte array (for uploaded files)
     */
    suspend fun extractFromBytes(
        bytes: ByteArray,
        fileName: String = "uploaded.pdf",
    ): PdfExtractionResult {
        logger.info("Extracting text from PDF bytes: $fileName")

        return withContext(Dispatchers.IO) {
            try {
                // Check file size
                if (bytes.size > MAX_FILE_SIZE_MB * 1024 * 1024) {
                    return@withContext PdfExtractionResult(
                        success = false,
                        errorMessage = "PDF too large: ${bytes.size / 1024 / 1024}MB",
                    )
                }

                // Save to temp file
                val tempFile = File.createTempFile("pdf_", ".tmp")
                try {
                    tempFile.writeBytes(bytes)
                    extractFromFile(tempFile.absolutePath)
                } finally {
                    tempFile.delete()
                }
            } catch (e: Exception) {
                logger.error("Failed to extract PDF from bytes", e)
                PdfExtractionResult(
                    success = false,
                    errorMessage = e.message ?: "Unknown error",
                )
            }
        }
    }
}

/**
 * PDF extraction result
 */
data class PdfExtractionResult(
    val success: Boolean,
    val content: String? = null,
    val totalPages: Int = 0,
    val extractedPages: Int = 0,
    val wordCount: Int = 0,
    val metadata: PdfMetadata? = null,
    val errorMessage: String? = null,
)

/**
 * PDF metadata
 */
data class PdfMetadata(
    val title: String?,
    val author: String?,
    val subject: String?,
    val keywords: String?,
    val creator: String?,
    val producer: String?,
    val creationDate: String?,
    val modificationDate: String?,
)
