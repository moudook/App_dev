package com.example.smarty.agent.tools.categories

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.util.Log
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.getAttachments
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.search.SemanticSearchEngine
import com.example.smarty.util.toon.ToonManager
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = false }

// ==================== AUDIO CATEGORY SEARCH ====================

@Serializable
data class SearchAudioArgs(
    @property:LLMDescription("Optional search query to filter audio notes (song name, artist, etc.)")
    val query: String? = null,
    @property:LLMDescription("Maximum number of results to return (default: 10)")
    val limit: Int = 10
)

@Serializable
data class AudioNoteInfo(
    val noteId: String,
    val noteTitle: String,
    val audioFiles: List<String>,  // List of audio filenames
    val audioCount: Int
)

@Serializable
data class SearchAudioResult(
    val success: Boolean,
    val notes: List<AudioNoteInfo>,
    val totalCount: Int,
    val message: String
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

/**
 * Tool for searching notes that contain audio files.
 * Filters notes by AUDIO type or notes with audio attachments.
 * Use this BEFORE play_audio for better results.
 */
class SearchAudioNotesTool(
    private val getActiveNotes: () -> List<Note>
) : Tool<SearchAudioArgs, SearchAudioResult>() {

    override val argsSerializer: KSerializer<SearchAudioArgs> = SearchAudioArgs.serializer()
    override val resultSerializer: KSerializer<SearchAudioResult> = SearchAudioResult.serializer()

    companion object {
        private const val TAG = "SearchAudioNotesTool"
    }

    override val name = "search_audio_notes"

    override val description = """
        Search for notes containing audio files (music, podcasts, recordings).
        Use this to find audio content before playing.
        Returns notes with audio attachments or AUDIO type.
        Example: "find my music" → search_audio_notes(query="music")
        Example: "what audio do I have?" → search_audio_notes()
    """.trimIndent()

    override suspend fun execute(args: SearchAudioArgs): SearchAudioResult {
        return try {
            val allNotes = PrivacyGuard.getAiVisibleNotes(getActiveNotes())

            // Filter notes that have audio content
            val audioNotes = allNotes.filter { note ->
                // Check if note type is AUDIO
                note.type == NoteType.AUDIO ||
                // Check if note has audio attachments
                note.getAttachments().any { it.mimeType.startsWith("audio/") } ||
                // Check if primary file is audio
                note.fileMimeType?.startsWith("audio/") == true
            }

            Log.d(TAG, "Found ${audioNotes.size} audio notes out of ${allNotes.size} total")

            // Apply SEMANTIC query filter if provided
            val filteredNotes = if (!args.query.isNullOrBlank()) {
                // Use semantic search for fuzzy matching
                val searchResults = SemanticSearchEngine.search(
                    query = args.query,
                    items = audioNotes,
                    textExtractor = { note ->
                        val audioFiles = note.getAttachments()
                            .filter { it.mimeType.startsWith("audio/") }
                            .map { it.fileName }
                        listOf(note.title, note.content) + audioFiles
                    },
                    minScore = 0.25  // Lower threshold for inclusive matching
                )

                if (searchResults.isNotEmpty()) {
                    searchResults.map { it.item }
                } else {
                    // Fallback to basic contains for simple queries
                    val queryLower = args.query.lowercase()
                    audioNotes.filter { note ->
                        note.title.lowercase().contains(queryLower) ||
                        note.content.lowercase().contains(queryLower) ||
                        note.getAttachments().any { it.fileName.lowercase().contains(queryLower) }
                    }
                }
            } else {
                audioNotes
            }

            // Map to AudioNoteInfo with limited results
            val results = filteredNotes.take(args.limit).map { note ->
                val audioFiles = note.getAttachments()
                    .filter { it.mimeType.startsWith("audio/") }
                    .map { it.fileName }

                AudioNoteInfo(
                    noteId = note.id,
                    noteTitle = note.title,
                    audioFiles = audioFiles.ifEmpty { listOf(note.fileName ?: "audio") },
                    audioCount = audioFiles.size.coerceAtLeast(1)
                )
            }

            val message = when {
                results.isEmpty() && args.query != null -> "No audio found matching '${args.query}'"
                results.isEmpty() -> "No audio notes found"
                else -> "Found ${results.size} audio note${if (results.size > 1) "s" else ""}"
            }

            SearchAudioResult(
                success = results.isNotEmpty(),
                notes = results,
                totalCount = filteredNotes.size,
                message = message
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error searching audio notes: ${e.message}", e)
            SearchAudioResult(
                success = false,
                notes = emptyList(),
                totalCount = 0,
                message = "Error searching audio: ${e.message}"
            )
        }
    }
}

// ==================== IMAGE CATEGORY SEARCH ====================

@Serializable
data class SearchImageArgs(
    @property:LLMDescription("Optional search query to filter image notes")
    val query: String? = null,
    @property:LLMDescription("Maximum number of results to return (default: 10)")
    val limit: Int = 10
)

@Serializable
data class ImageNoteInfo(
    val noteId: String,
    val noteTitle: String,
    val imageFiles: List<String>,  // List of image filenames
    val imageCount: Int,
    val hasImageUri: Boolean  // Has a primary image URI
)

@Serializable
data class SearchImageResult(
    val success: Boolean,
    val notes: List<ImageNoteInfo>,
    val totalCount: Int,
    val message: String
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

/**
 * Tool for searching notes that contain images.
 * Filters notes by IMAGE type or notes with image attachments.
 */
class SearchImageNotesTool(
    private val getActiveNotes: () -> List<Note>
) : Tool<SearchImageArgs, SearchImageResult>() {

    override val argsSerializer: KSerializer<SearchImageArgs> = SearchImageArgs.serializer()
    override val resultSerializer: KSerializer<SearchImageResult> = SearchImageResult.serializer()

    companion object {
        private const val TAG = "SearchImageNotesTool"
    }

    override val name = "search_image_notes"

    override val description = """
        Search for notes containing images (photos, screenshots, graphics).
        Returns notes with image attachments or IMAGE type.
        Example: "find my photos" → search_image_notes(query="photos")
        Example: "show my images" → search_image_notes()
    """.trimIndent()

    override suspend fun execute(args: SearchImageArgs): SearchImageResult {
        return try {
            val allNotes = PrivacyGuard.getAiVisibleNotes(getActiveNotes())

            // Filter notes that have image content
            val imageNotes = allNotes.filter { note ->
                // Check if note type is IMAGE
                note.type == NoteType.IMAGE ||
                // Check if note has image attachments
                note.getAttachments().any { it.mimeType.startsWith("image/") } ||
                // Check if primary file is image
                note.fileMimeType?.startsWith("image/") == true ||
                // Check if has imageUri
                !note.imageUri.isNullOrBlank()
            }

            Log.d(TAG, "Found ${imageNotes.size} image notes out of ${allNotes.size} total")

            // Apply SEMANTIC query filter if provided
            val filteredNotes = if (!args.query.isNullOrBlank()) {
                // Use semantic search for fuzzy matching
                val searchResults = SemanticSearchEngine.search(
                    query = args.query,
                    items = imageNotes,
                    textExtractor = { note ->
                        val imageFiles = note.getAttachments()
                            .filter { it.mimeType.startsWith("image/") }
                            .map { it.fileName }
                        listOf(note.title, note.content) + imageFiles
                    },
                    minScore = 0.25
                )

                if (searchResults.isNotEmpty()) {
                    searchResults.map { it.item }
                } else {
                    // Fallback to basic contains
                    val queryLower = args.query.lowercase()
                    imageNotes.filter { note ->
                        note.title.lowercase().contains(queryLower) ||
                        note.content.lowercase().contains(queryLower) ||
                        note.getAttachments().any { it.fileName.lowercase().contains(queryLower) }
                    }
                }
            } else {
                imageNotes
            }

            // Map to ImageNoteInfo with limited results
            val results = filteredNotes.take(args.limit).map { note ->
                val imageFiles = note.getAttachments()
                    .filter { it.mimeType.startsWith("image/") }
                    .map { it.fileName }

                ImageNoteInfo(
                    noteId = note.id,
                    noteTitle = note.title,
                    imageFiles = imageFiles.ifEmpty { listOfNotNull(note.fileName) },
                    imageCount = imageFiles.size.coerceAtLeast(if (note.imageUri != null) 1 else 0),
                    hasImageUri = !note.imageUri.isNullOrBlank()
                )
            }

            val message = when {
                results.isEmpty() && args.query != null -> "No images found matching '${args.query}'"
                results.isEmpty() -> "No image notes found"
                else -> "Found ${results.size} image note${if (results.size > 1) "s" else ""}"
            }

            SearchImageResult(
                success = results.isNotEmpty(),
                notes = results,
                totalCount = filteredNotes.size,
                message = message
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error searching image notes: ${e.message}", e)
            SearchImageResult(
                success = false,
                notes = emptyList(),
                totalCount = 0,
                message = "Error searching images: ${e.message}"
            )
        }
    }
}

// ==================== DOCUMENT CATEGORY SEARCH ====================

@Serializable
data class SearchDocumentArgs(
    @property:LLMDescription("Optional search query to filter document notes")
    val query: String? = null,
    @property:LLMDescription("Maximum number of results to return (default: 10)")
    val limit: Int = 10
)

@Serializable
data class DocumentNoteInfo(
    val noteId: String,
    val noteTitle: String,
    val fileName: String?,
    val fileType: String?
)

@Serializable
data class SearchDocumentResult(
    val success: Boolean,
    val notes: List<DocumentNoteInfo>,
    val totalCount: Int,
    val message: String
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

/**
 * Tool for searching notes that contain documents (PDFs, Word docs, etc).
 */
class SearchDocumentNotesTool(
    private val getActiveNotes: () -> List<Note>
) : Tool<SearchDocumentArgs, SearchDocumentResult>() {

    override val argsSerializer: KSerializer<SearchDocumentArgs> = SearchDocumentArgs.serializer()
    override val resultSerializer: KSerializer<SearchDocumentResult> = SearchDocumentResult.serializer()

    companion object {
        private const val TAG = "SearchDocumentNotesTool"
        private val DOCUMENT_TYPES = setOf(NoteType.DOCUMENT, NoteType.SPREADSHEET, NoteType.PRESENTATION)
        private val DOCUMENT_MIMES = listOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "text/plain",
            "text/csv"
        )
    }

    override val name = "search_document_notes"

    override val description = """
        Search for notes containing documents (PDFs, Word docs, spreadsheets, etc).
        Example: "find my PDFs" → search_document_notes(query="pdf")
        Example: "show my documents" → search_document_notes()
    """.trimIndent()

    override suspend fun execute(args: SearchDocumentArgs): SearchDocumentResult {
        return try {
            val allNotes = PrivacyGuard.getAiVisibleNotes(getActiveNotes())

            // Filter notes that have document content
            val documentNotes = allNotes.filter { note ->
                note.type in DOCUMENT_TYPES ||
                note.getAttachments().any { att ->
                    DOCUMENT_MIMES.any { att.mimeType.startsWith(it) }
                } ||
                DOCUMENT_MIMES.any { note.fileMimeType?.startsWith(it) == true }
            }

            Log.d(TAG, "Found ${documentNotes.size} document notes")

            // Apply SEMANTIC query filter if provided
            val filteredNotes = if (!args.query.isNullOrBlank()) {
                // Use semantic search for fuzzy matching
                val searchResults = SemanticSearchEngine.search(
                    query = args.query,
                    items = documentNotes,
                    textExtractor = { note ->
                        listOfNotNull(note.title, note.content, note.fileName)
                    },
                    minScore = 0.25
                )

                if (searchResults.isNotEmpty()) {
                    searchResults.map { it.item }
                } else {
                    // Fallback to basic contains
                    val queryLower = args.query.lowercase()
                    documentNotes.filter { note ->
                        note.title.lowercase().contains(queryLower) ||
                        note.content.lowercase().contains(queryLower) ||
                        note.fileName?.lowercase()?.contains(queryLower) == true
                    }
                }
            } else {
                documentNotes
            }

            val results = filteredNotes.take(args.limit).map { note ->
                DocumentNoteInfo(
                    noteId = note.id,
                    noteTitle = note.title,
                    fileName = note.fileName,
                    fileType = note.fileMimeType
                )
            }

            val message = when {
                results.isEmpty() && args.query != null -> "No documents found matching '${args.query}'"
                results.isEmpty() -> "No document notes found"
                else -> "Found ${results.size} document${if (results.size > 1) "s" else ""}"
            }

            SearchDocumentResult(
                success = results.isNotEmpty(),
                notes = results,
                totalCount = filteredNotes.size,
                message = message
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error searching document notes: ${e.message}", e)
            SearchDocumentResult(
                success = false,
                notes = emptyList(),
                totalCount = 0,
                message = "Error searching documents: ${e.message}"
            )
        }
    }
}
