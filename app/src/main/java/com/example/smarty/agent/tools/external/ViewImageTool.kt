package com.example.smarty.agent.tools.external

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.util.Log
import com.example.smarty.agent.ImageDisplayItem
import com.example.smarty.agent.tools.base.ImageDisplayResult
import com.example.smarty.agent.tools.base.ImageInfo
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.getAttachments
import com.example.smarty.data.model.getTags
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.search.SemanticSearchEngine
import kotlinx.serialization.Serializable

@Serializable
data class ViewImageArgs(
    @property:LLMDescription("Search query to find image (note title, description, image filename, etc.)")
    val query: String,
    @property:LLMDescription("Optional specific note ID if viewing images from a known note")
    val noteId: String? = null,
    @property:LLMDescription("Whether to show all images in the note (default: false, shows only first)")
    val showAll: Boolean = false
)

/**
 * Tool for viewing images from notes inline in chat.
 * Searches note attachments to find images and displays them in the chat message.
 */
class ViewImageTool(
    private val getActiveNotes: () -> List<Note>,
    private val onDisplayImages: (List<ImageDisplayItem>) -> Unit
) : Tool<ViewImageArgs, ImageDisplayResult>(
    argsSerializer = ViewImageArgs.serializer(),
    resultSerializer = ImageDisplayResult.serializer(),
    name = "view_image",
    description = """
        Displays images, photos, or screenshots stored in the user's notes.
        Triggers: "show me [image]", "view the [photo]", "display [screenshot]", "see the picture of [X]".
        DO NOT use for: General questions ("What does a cat look like?") or finding images online.
        ONLY use when the user EXPLICITLY asks to "show", "view", or "display" an existing image note.
    """.trimIndent()
) {
    companion object {
        private const val TAG = "ViewImageTool"

        // Pre-compiled regex patterns for performance
        private val NON_ALPHANUMERIC_SPACE_PATTERN = Regex("[^a-z0-9\\s]")
        private val NON_ALPHANUMERIC_PATTERN = Regex("[^a-z0-9]")

        // Command words to strip from search queries
        private val COMMAND_WORDS = setOf(
            "open", "view", "show", "display", "look", "see", "find", "get",
            "the", "a", "an", "me", "my", "image", "photo", "picture", "screenshot",
            "in", "from", "of", "that", "this", "some"
        )
    }

    /**
     * Clean query by removing command words and normalizing.
     * "show the beach photo" -> "beach"
     * "open my vacation images" -> "vacation"
     */
    private fun cleanQuery(query: String): String {
        val words = query.lowercase().trim().split(Regex("\\s+"))
        val cleaned = words.filter { it !in COMMAND_WORDS && it.length >= 2 }
        val result = cleaned.joinToString(" ")
        Log.d(TAG, "Cleaned query: '$query' -> '$result'")
        return result.ifEmpty { query.lowercase().trim() }
    }

    override suspend fun execute(args: ViewImageArgs): ImageDisplayResult {
        Log.i(TAG, "ViewImageTool.execute() CALLED - query='${args.query}', noteId=${args.noteId}")

        return try {
            if (args.query.isBlank() && args.noteId == null) {
                Log.w(TAG, "Query is blank and no noteId provided")
                return ImageDisplayResult(
                    success = false,
                    message = "Please specify what image to view",
                    error = "Empty query"
                )
            }

            // SECURITY: Filter to only AI-accessible notes (excludes private notes)
            val rawNotes = getActiveNotes()
            val allNotes = PrivacyGuard.getAiVisibleNotes(rawNotes)
            Log.d(TAG, "Notes: raw=${rawNotes.size}, afterPrivacy=${allNotes.size}")

            // If noteId provided, get images from that specific note
            if (args.noteId != null) {
                return getImagesFromNote(allNotes, args.noteId, args.showAll)
            }

            // Filter to only notes with images
            val imageNotes = filterImageNotes(allNotes)
            Log.d(TAG, "Found ${imageNotes.size} notes with images")

            if (imageNotes.isEmpty()) {
                return ImageDisplayResult(
                    success = false,
                    message = "No notes with images found",
                    error = "No image notes"
                )
            }

            // Search for matching note
            val matchingNote = findMatchingImageNote(imageNotes, args.query)

            if (matchingNote != null) {
                return getImagesFromNote(listOf(matchingNote), matchingNote.id, args.showAll)
            }

            // No match found
            ImageDisplayResult(
                success = false,
                message = "No images found matching '${args.query}'",
                error = "Image not found"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Image view error: ${e.message}", e)
            ImageDisplayResult(
                success = false,
                message = "Failed to view image",
                error = e.message
            )
        }
    }

    /**
     * Filter notes to only those containing images.
     * Checks both modern attachmentsJson and legacy imageUri/fileUri fields.
     */
    private fun filterImageNotes(notes: List<Note>): List<Note> {
        return notes.filter { note ->
            // Check note type
            val isImageType = note.type == NoteType.IMAGE

            // Check attachmentsJson for image attachments
            val hasImageAttachments = note.getAttachments().any { it.mimeType.startsWith("image/") }

            // Check legacy imageUri
            val hasLegacyImage = !note.imageUri.isNullOrBlank()

            // Check legacy fileUri if it's an image
            val hasLegacyFileImage = note.fileUri != null &&
                    note.fileMimeType?.startsWith("image/") == true

            isImageType || hasImageAttachments || hasLegacyImage || hasLegacyFileImage
        }
    }

    /**
     * Get images from a specific note by ID.
     */
    private fun getImagesFromNote(
        notes: List<Note>,
        noteId: String,
        showAll: Boolean
    ): ImageDisplayResult {
        val note = notes.find { it.id == noteId }
            ?.takeIf { PrivacyGuard.canAiProcess(it) }
            ?: return ImageDisplayResult(
                success = false,
                message = "Note not found or not accessible",
                error = "Note not found"
            )

        val images = extractImageAttachments(note)

        if (images.isEmpty()) {
            return ImageDisplayResult(
                success = false,
                noteTitle = note.title,
                message = "No images found in this note",
                error = "No image attachments"
            )
        }

        // Select images to display
        val selectedImages = if (showAll) {
            images
        } else {
            listOf(images.first())
        }

        // Trigger UI callback to display images (URIs passed internally, not to AI)
        val displayItems = selectedImages.map {
            ImageDisplayItem(
                uri = it.uri,
                fileName = it.fileName,
                noteTitle = note.title
            )
        }
        Log.i(TAG, "Invoking onDisplayImages callback with ${displayItems.size} images")
        onDisplayImages(displayItems)

        // SECURITY: Return result WITHOUT URIs - only metadata for AI response
        return ImageDisplayResult(
            success = true,
            noteTitle = note.title,
            imageCount = selectedImages.size,
            message = if (selectedImages.size == 1) {
                "Showing image from \"${note.title}\""
            } else {
                "Showing ${selectedImages.size} images from \"${note.title}\""
            }
        )
    }

    /**
     * Extract all image attachments from a note.
     * Handles both modern attachmentsJson and legacy storage methods.
     */
    private fun extractImageAttachments(note: Note): List<ImageInfo> {
        val images = mutableListOf<ImageInfo>()
        var index = 0

        // METHOD 1: Modern attachmentsJson
        val attachments = note.getAttachments()
            .filter { it.mimeType.startsWith("image/") }

        for (attachment in attachments) {
            images.add(ImageInfo(
                uri = attachment.uri,
                fileName = attachment.fileName,
                mimeType = attachment.mimeType,
                index = index++,
                attachmentId = attachment.id
            ))
        }

        // METHOD 2: Legacy imageUri (only if no modern attachments)
        if (images.isEmpty() && !note.imageUri.isNullOrBlank()) {
            images.add(ImageInfo(
                uri = note.imageUri,
                fileName = note.fileName ?: "image_${note.id.take(8)}",
                mimeType = note.fileMimeType ?: "image/*",
                index = 0,
                attachmentId = null
            ))
        }

        // METHOD 3: Legacy fileUri if it's an image (only if still empty)
        if (images.isEmpty() &&
            note.fileUri != null &&
            note.fileMimeType?.startsWith("image/") == true) {
            images.add(ImageInfo(
                uri = note.fileUri,
                fileName = note.fileName ?: "image_${note.id.take(8)}",
                mimeType = note.fileMimeType,
                index = 0,
                attachmentId = null
            ))
        }

        Log.d(TAG, "Extracted ${images.size} images from note: ${note.id.take(8)}")
        return images
    }

    /**
     * Search notes for images matching the query using semantic search.
     * Searches across multiple fields: filename, title, content, tags, summary, category.
     */
    private fun findMatchingImageNote(notes: List<Note>, query: String): Note? {
        val cleanedQuery = cleanQuery(query)
        Log.i(TAG, "Searching: query='$query' -> cleaned='$cleanedQuery', notes=${notes.size}")

        // Build searchable items with all metadata
        data class ImageNoteItem(
            val note: Note,
            val imageFileNames: List<String>
        )

        val items = notes.map { note ->
            val fileNames = note.getAttachments()
                .filter { it.mimeType.startsWith("image/") }
                .map { it.fileName }
            ImageNoteItem(note, fileNames)
        }

        // Use semantic search with all available text fields
        val results = SemanticSearchEngine.search(
            query = cleanedQuery,
            items = items,
            textExtractor = { item ->
                val searchableTexts = mutableListOf<String>()

                // Primary: image filenames
                searchableTexts.addAll(item.imageFileNames)

                // Secondary: note title
                searchableTexts.add(item.note.title)

                // Tertiary: tags
                val tags = item.note.getTags()
                if (tags.isNotEmpty()) {
                    searchableTexts.add(tags.joinToString(" "))
                }

                // Quaternary: summary
                item.note.summary?.let { searchableTexts.add(it) }

                // Quinary: category
                item.note.categoryName?.let { searchableTexts.add(it) }

                // Last: content (truncated)
                searchableTexts.add(item.note.content.take(500))

                searchableTexts
            },
            minScore = 0.20  // Low threshold for inclusivity
        )

        if (results.isNotEmpty()) {
            val best = results.first()
            Log.d(TAG, "Best match: ${best.item.note.title} (score: ${String.format("%.2f", best.score)})")
            return best.item.note
        }

        // Fallback: Try word-by-word matching
        Log.d(TAG, "No semantic match, trying fallback...")
        val queryWords = SemanticSearchEngine.tokenize(cleanedQuery)

        val fallbackMatch = items.firstOrNull { item ->
            val combinedText = buildString {
                append(item.imageFileNames.joinToString(" ")).append(" ")
                append(item.note.title).append(" ")
                append(item.note.getTags().joinToString(" ")).append(" ")
                item.note.summary?.let { append(it).append(" ") }
                item.note.categoryName?.let { append(it).append(" ") }
                append(item.note.content.take(300))
            }.lowercase()

            queryWords.any { word ->
                word.length >= 3 && combinedText.contains(word)
            }
        }

        if (fallbackMatch != null) {
            Log.d(TAG, "Fallback match: ${fallbackMatch.note.title}")
            return fallbackMatch.note
        }

        // Ultra fallback: Check if any word contains query substring
        val normalizedQuery = cleanedQuery.lowercase().replace(NON_ALPHANUMERIC_SPACE_PATTERN, "")
        val queryParts = normalizedQuery.split(" ").filter { it.length >= 3 }

        val ultraFallback = items.firstOrNull { item ->
            queryParts.any { part ->
                item.imageFileNames.any { it.lowercase().contains(part) } ||
                        item.note.title.lowercase().contains(part) ||
                        item.note.getTags().any { it.lowercase().contains(part) }
            }
        }

        if (ultraFallback != null) {
            Log.d(TAG, "Ultra fallback match: ${ultraFallback.note.title}")
            return ultraFallback.note
        }

        Log.d(TAG, "No match found for '$cleanedQuery'")
        return null
    }
}
