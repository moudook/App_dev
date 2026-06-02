package com.example.smarty.features.chat.domain.thinking

import android.content.Context
import android.net.Uri
import com.example.smarty.core.common.util.mention.MentionParser
import com.example.smarty.core.domain.model.*
import com.example.smarty.data.model.DocumentChunk
import com.example.smarty.data.model.ThinkingModeContext
import com.example.smarty.util.PDFChunkedResult
import com.example.smarty.util.PDFTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Processor for @thinking deep document analysis mode.
 */
class ThinkingModeProcessor(
    private val context: Context,
) {
    companion object {
        private const val TAG = "ThinkingModeProcessor"
    }

    private val pdfExtractor by lazy { PDFTextExtractor(context) }

    fun hasThinkingCommand(message: String): Boolean {
        val mentions = MentionParser.parseAllMentions(message)
        return mentions.any { mention ->
            MentionParser.matchCommand(mention.query) != null
        }
    }

    suspend fun processThinkingMode(
        message: String,
        referencedNotes: List<Note>,
    ): ThinkingModeContext =
        withContext(Dispatchers.IO) {
            val mentions = MentionParser.parseAllMentions(message)
            val userQuery = extractUserQuery(message, mentions)

            val notesWithDocuments =
                referencedNotes.filter { note ->
                    note.fileUri != null && isDocumentType(note.type)
                }

            if (notesWithDocuments.isEmpty()) {
                return@withContext ThinkingModeContext(
                    isThinkingMode = true,
                    userQuery = userQuery,
                    relatedNotes = referencedNotes,
                )
            }

            val targetNote = notesWithDocuments.first()
            var documentContent = extractFullDocumentContent(targetNote)

            if (documentContent.length > ThinkingModeContext.MAX_DOCUMENT_SIZE) {
                documentContent = documentContent.take(ThinkingModeContext.MAX_DOCUMENT_SIZE)
            }

            val chunks = splitIntoChunks(documentContent)

            ThinkingModeContext(
                isThinkingMode = true,
                targetNote = targetNote,
                fullDocumentContent = documentContent,
                documentChunks = chunks,
                totalChars = documentContent.length,
                totalChunks = chunks.size,
                userQuery = userQuery,
                documentFileName = targetNote.fileName,
            )
        }

    private fun extractUserQuery(
        message: String,
        mentions: List<ParsedMention>,
    ): String {
        var query = message
        mentions.sortedByDescending { it.startIndex }.forEach { mention ->
            if (mention.startIndex in query.indices && mention.endIndex <= query.length) {
                query = query.removeRange(mention.startIndex, mention.endIndex)
            }
        }
        return query.trim()
    }

    private fun isDocumentType(type: NoteType): Boolean =
        type == NoteType.DOCUMENT || type == NoteType.CODE || type == NoteType.SPREADSHEET || type == NoteType.PRESENTATION

    private suspend fun extractFullDocumentContent(note: Note): String {
        val fileUri = note.fileUri ?: return note.content
        val mimeType = note.fileMimeType ?: ""

        return try {
            if (mimeType.contains("pdf", ignoreCase = true)) {
                extractPdfContent(Uri.parse(fileUri))
            } else {
                extractTextContent(Uri.parse(fileUri))
            }
        } catch (e: Exception) {
            note.content
        }
    }

    private suspend fun extractPdfContent(uri: Uri): String {
        val result = pdfExtractor.extractTextChunked(uri)
        return when (result) {
            is PDFChunkedResult.Success -> result.fullText
            else -> ""
        }
    }

    private suspend fun extractTextContent(uri: Uri): String =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader().readText()
                } ?: ""
            } catch (e: Exception) {
                ""
            }
        }

    private fun splitIntoChunks(content: String): List<DocumentChunk> {
        val chunks = mutableListOf<DocumentChunk>()
        val chunkSize = ThinkingModeContext.CHARS_PER_CHUNK
        val overlap = ThinkingModeContext.OVERLAP_CHARS

        var start = 0
        var index = 0

        while (start < content.length) {
            val end = minOf(start + chunkSize, content.length)
            val chunkContent = content.substring(start, end)
            chunks.add(
                DocumentChunk(
                    index = index,
                    totalChunks = 0, // updated later
                    content = chunkContent,
                    charCount = chunkContent.length,
                    startPosition = start,
                    endPosition = end,
                ),
            )
            start = if (end == content.length) end else end - overlap
            index++
            if (start >= content.length || index > 20) break
        }

        return chunks.map { it.copy(totalChunks = chunks.size) }
    }
}
