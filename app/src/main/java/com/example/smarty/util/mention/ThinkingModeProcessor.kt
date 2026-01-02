package com.example.smarty.util.mention

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.smarty.data.model.DocumentChunk
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ParsedMention
import com.example.smarty.data.model.ThinkingModeContext
import com.example.smarty.util.PDFTextExtractor
import com.example.smarty.util.PDFChunkedResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Processor for @thinking deep document analysis mode.
 *
 * When user types @thinking with a note reference, this processor:
 * 1. Extracts the FULL document content (not just summary)
 * 2. Splits it into processable chunks with overlap
 * 3. Prepares context for deep AI analysis
 *
 * Usage patterns:
 * - "@thinking @MyDocument What are the key points?"
 * - "@thinking @recent summarize the documents"
 * - "@thinking analyze this in depth" (uses last mentioned note)
 */
class ThinkingModeProcessor(
    private val context: Context
) {
    companion object {
        private const val TAG = "ThinkingModeProcessor"
    }

    private val pdfExtractor by lazy { PDFTextExtractor(context) }

    /**
     * Check if a message contains @thinking command.
     */
    fun hasThinkingCommand(message: String): Boolean {
        val mentions = MentionParser.parseAllMentions(message)
        return mentions.any { mention ->
            MentionParser.matchCommand(mention.query) != null
        }
    }

    /**
     * Process a message with @thinking and extract full document context.
     *
     * @param message User's message (may contain @thinking and note references)
     * @param referencedNotes Notes that were mentioned in the message
     * @return ThinkingModeContext with full document content for deep analysis
     */
    suspend fun processThinkingMode(
        message: String,
        referencedNotes: List<Note>
    ): ThinkingModeContext = withContext(Dispatchers.IO) {
        Log.d(TAG, "Processing @thinking mode with ${referencedNotes.size} referenced notes")

        // Parse mentions to find the @thinking command
        val mentions = MentionParser.parseAllMentions(message)
        val thinkingMention = mentions.find { mention ->
            MentionParser.matchCommand(mention.query) != null
        }

        if (thinkingMention == null) {
            Log.d(TAG, "No @thinking command found in message")
            return@withContext ThinkingModeContext(isThinkingMode = false)
        }

        // Get the user's query (message without the @thinking and note mentions)
        val userQuery = extractUserQuery(message, mentions)
        Log.d(TAG, "User query: $userQuery")

        // Find notes with documents
        val notesWithDocuments = referencedNotes.filter { note ->
            note.fileUri != null && isDocumentType(note.type)
        }

        if (notesWithDocuments.isEmpty()) {
            // No documents to analyze - use note content directly
            Log.d(TAG, "No documents found, using note content directly")
            return@withContext createContextFromNoteContent(referencedNotes, userQuery)
        }

        // Process the first document (or we could combine multiple)
        val targetNote = notesWithDocuments.first()
        Log.d(TAG, "Processing document from note: ${targetNote.title}")

        var documentContent = extractFullDocumentContent(targetNote)

        if (documentContent.isBlank()) {
            // Fall back to note content if document extraction fails
            Log.w(TAG, "Document extraction returned empty, falling back to note content")
            return@withContext createContextFromNoteContent(referencedNotes, userQuery)
        }

        // Truncate if document exceeds maximum size to fit in context window
        val wasTruncated = documentContent.length > ThinkingModeContext.MAX_DOCUMENT_SIZE
        if (wasTruncated) {
            Log.w(TAG, "Document too large (${documentContent.length} chars), truncating to ${ThinkingModeContext.MAX_DOCUMENT_SIZE}")
            documentContent = truncateAtGoodBreakPoint(documentContent, ThinkingModeContext.MAX_DOCUMENT_SIZE)
        }

        // Split document into chunks
        val chunks = splitIntoChunks(documentContent)
        Log.d(TAG, "Document split into ${chunks.size} chunks, total ${documentContent.length} chars${if (wasTruncated) " (truncated)" else ""}")

        ThinkingModeContext(
            isThinkingMode = true,
            targetNote = targetNote,
            fullDocumentContent = documentContent,
            documentChunks = chunks,
            totalChars = documentContent.length,
            userQuery = userQuery,
            documentFileName = targetNote.fileName
        )
    }

    /**
     * Extract user's question from the message (removing commands and mentions).
     */
    private fun extractUserQuery(message: String, mentions: List<ParsedMention>): String {
        var query = message

        // Remove all mentions from the message
        mentions.sortedByDescending { it.startIndex }.forEach { mention ->
            query = query.removeRange(mention.startIndex, mention.endIndex)
        }

        return query.trim()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Check if a note type is a document type that can be analyzed.
     */
    private fun isDocumentType(type: NoteType): Boolean {
        return when (type) {
            NoteType.DOCUMENT,
            NoteType.SPREADSHEET,
            NoteType.PRESENTATION,
            NoteType.CODE -> true
            else -> false
        }
    }

    /**
     * Extract full content from a document file attached to a note.
     */
    private suspend fun extractFullDocumentContent(note: Note): String {
        val fileUri = note.fileUri ?: return ""
        val mimeType = note.fileMimeType ?: ""

        Log.d(TAG, "Extracting content from: ${note.fileName}, type: $mimeType")

        return try {
            when {
                mimeType.contains("pdf", ignoreCase = true) -> {
                    extractPdfContent(Uri.parse(fileUri))
                }
                mimeType.contains("text", ignoreCase = true) ||
                mimeType.contains("plain", ignoreCase = true) ||
                mimeType.contains("json", ignoreCase = true) ||
                mimeType.contains("xml", ignoreCase = true) -> {
                    extractTextContent(Uri.parse(fileUri))
                }
                else -> {
                    // Try to use the note's existing content if available
                    Log.d(TAG, "Unsupported mime type, using note content: $mimeType")
                    note.content
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract document content: ${e.message}", e)
            note.content // Fall back to existing note content
        }
    }

    /**
     * Extract full PDF content using chunked extraction for large documents.
     */
    private suspend fun extractPdfContent(uri: Uri): String {
        val result = pdfExtractor.extractTextChunked(uri)

        return when (result) {
            is PDFChunkedResult.Success -> {
                // Combine all chunks into full document content
                result.getCombinedText().also {
                    Log.d(TAG, "PDF extracted: ${result.totalPages} pages, ${result.totalCharacters} chars")
                }
            }
            is PDFChunkedResult.Empty -> {
                Log.w(TAG, "PDF is empty or image-only: ${result.message}")
                ""
            }
            is PDFChunkedResult.Error -> {
                Log.e(TAG, "PDF extraction error: ${result.message}")
                ""
            }
        }
    }

    /**
     * Extract content from plain text files.
     */
    private suspend fun extractTextContent(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read text file: ${e.message}")
            ""
        }
    }

    /**
     * Split document content into chunks for processing.
     */
    private fun splitIntoChunks(content: String): List<DocumentChunk> {
        if (content.length <= ThinkingModeContext.CHARS_PER_CHUNK) {
            // Single chunk for small documents
            return listOf(
                DocumentChunk(
                    index = 0,
                    totalChunks = 1,
                    content = content,
                    charCount = content.length,
                    startPosition = 0,
                    endPosition = content.length
                )
            )
        }

        val chunks = mutableListOf<DocumentChunk>()
        var position = 0
        var chunkIndex = 0

        while (position < content.length) {
            val endPosition = minOf(
                position + ThinkingModeContext.CHARS_PER_CHUNK,
                content.length
            )

            // Try to break at a paragraph or sentence boundary
            val actualEnd = findGoodBreakPoint(content, position, endPosition)

            val chunkContent = content.substring(position, actualEnd)

            chunks.add(
                DocumentChunk(
                    index = chunkIndex,
                    totalChunks = -1, // Will be updated after all chunks are created
                    content = chunkContent,
                    charCount = chunkContent.length,
                    startPosition = position,
                    endPosition = actualEnd
                )
            )

            // Move position with overlap
            position = actualEnd - ThinkingModeContext.OVERLAP_CHARS
            if (position < 0 || position >= content.length) break
            chunkIndex++

            // Safety limit to stay within context window
            if (chunkIndex > 20) {
                Log.w(TAG, "Hit chunk limit (20), truncating document")
                break
            }
        }

        // Update totalChunks in all chunks
        return chunks.map { it.copy(totalChunks = chunks.size) }
    }

    /**
     * Truncate content at a good break point (paragraph or sentence).
     */
    private fun truncateAtGoodBreakPoint(content: String, maxLength: Int): String {
        if (content.length <= maxLength) return content

        // Search backwards from max length for good break points
        val searchStart = maxOf(0, maxLength - 1000)
        val searchWindow = content.substring(searchStart, maxLength)

        // Try to break at paragraph (double newline)
        val paragraphBreak = searchWindow.lastIndexOf("\n\n")
        if (paragraphBreak > searchWindow.length / 2) {
            val breakPoint = searchStart + paragraphBreak
            return content.substring(0, breakPoint) + "\n\n[... Document truncated to fit context window ...]"
        }

        // Try to break at sentence (period followed by space or newline)
        val sentenceBreak = searchWindow.lastIndexOf(". ")
        if (sentenceBreak > searchWindow.length / 2) {
            val breakPoint = searchStart + sentenceBreak + 1
            return content.substring(0, breakPoint) + "\n\n[... Document truncated to fit context window ...]"
        }

        // Fall back to word boundary
        val wordBreak = searchWindow.lastIndexOf(" ")
        if (wordBreak > 0) {
            val breakPoint = searchStart + wordBreak
            return content.substring(0, breakPoint) + "\n\n[... Document truncated to fit context window ...]"
        }

        return content.substring(0, maxLength) + "\n\n[... Document truncated to fit context window ...]"
    }

    /**
     * Find a good break point for chunking (paragraph or sentence boundary).
     */
    private fun findGoodBreakPoint(content: String, start: Int, end: Int): Int {
        if (end >= content.length) return content.length

        // Search backwards from end position for good break points
        val searchWindow = content.substring(maxOf(start, end - 500), end)

        // Try to break at paragraph (double newline)
        val paragraphBreak = searchWindow.lastIndexOf("\n\n")
        if (paragraphBreak > searchWindow.length / 2) {
            return end - (searchWindow.length - paragraphBreak) + 2
        }

        // Try to break at sentence (period followed by space)
        val sentenceBreak = searchWindow.lastIndexOf(". ")
        if (sentenceBreak > searchWindow.length / 2) {
            return end - (searchWindow.length - sentenceBreak) + 2
        }

        // Fall back to word boundary
        val wordBreak = searchWindow.lastIndexOf(" ")
        if (wordBreak > 0) {
            return end - (searchWindow.length - wordBreak) + 1
        }

        return end
    }

    /**
     * Create context from note content when no document is attached.
     */
    private fun createContextFromNoteContent(
        notes: List<Note>,
        userQuery: String
    ): ThinkingModeContext {
        val combinedContent = notes.joinToString("\n\n---\n\n") { note ->
            "# ${note.title}\n\n${note.content}"
        }

        val chunks = splitIntoChunks(combinedContent)

        return ThinkingModeContext(
            isThinkingMode = true,
            targetNote = notes.firstOrNull(),
            fullDocumentContent = combinedContent,
            documentChunks = chunks,
            totalChars = combinedContent.length,
            userQuery = userQuery,
            documentFileName = null
        )
    }

    /**
     * Format thinking mode context for AI prompt.
     * Creates a special prompt structure for deep document analysis.
     */
    fun formatForAIPrompt(thinkingContext: ThinkingModeContext): String {
        if (!thinkingContext.isThinkingMode) {
            return ""
        }

        val sb = StringBuilder()

        sb.appendLine("=== DEEP THINKING MODE ===")
        sb.appendLine()

        if (thinkingContext.documentFileName != null) {
            sb.appendLine("Document: ${thinkingContext.documentFileName}")
        }
        if (thinkingContext.targetNote != null) {
            sb.appendLine("Note Title: ${thinkingContext.targetNote.title}")
        }
        sb.appendLine("Total Characters: ${thinkingContext.totalChars}")
        sb.appendLine("Chunks: ${thinkingContext.documentChunks.size}")
        sb.appendLine()

        sb.appendLine("USER'S QUESTION:")
        sb.appendLine(thinkingContext.userQuery.ifBlank { "Please analyze this document in depth." })
        sb.appendLine()

        sb.appendLine("=== FULL DOCUMENT CONTENT ===")
        sb.appendLine()

        // Include all chunks with clear markers
        thinkingContext.documentChunks.forEach { chunk ->
            sb.appendLine("[CHUNK ${chunk.index + 1}/${chunk.totalChunks}]")
            sb.appendLine(chunk.content)
            sb.appendLine()
        }

        sb.appendLine("=== END OF DOCUMENT ===")
        sb.appendLine()
        sb.appendLine("INSTRUCTIONS: You are in DEEP THINKING mode. Analyze the ENTIRE document above thoroughly to answer the user's question. Consider all sections and details. Provide a comprehensive, well-structured response based on the full document content.")

        return sb.toString()
    }
}
