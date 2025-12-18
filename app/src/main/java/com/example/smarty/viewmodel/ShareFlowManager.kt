package com.example.smarty.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteAttachment
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.repository.CogniRepository
import com.example.smarty.ui.components.PendingShareData
import com.example.smarty.util.ContentTypeDetector
import com.example.smarty.util.FileStorageHelper
import com.example.smarty.util.PrivacyGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages share flow state and operations.
 * Extracted from CogniViewModel for better separation of concerns.
 *
 * Responsibilities:
 * - Pending share state management
 * - Full privacy mode toggle for shares
 * - Active share mode tracking
 * - File compression and storage during share
 * - Related notes discovery
 */
class ShareFlowManager(
    private val repository: CogniRepository,
    private val context: Context,
    private val scope: CoroutineScope,
    private val getNotesSnapshot: () -> List<Note>
) {
    companion object {
        private const val TAG = "ShareFlowManager"
    }

    // Pending share state for bottom sheet
    private val _pendingShare = MutableStateFlow<PendingShareData?>(null)
    val pendingShare: StateFlow<PendingShareData?> = _pendingShare.asStateFlow()

    // Full privacy mode state for share flow (no AI processing at all)
    private val _pendingShareFullPrivacy = MutableStateFlow(false)
    val pendingShareFullPrivacy: StateFlow<Boolean> = _pendingShareFullPrivacy.asStateFlow()

    // Track if we're in active share mode (share bottom sheet is visible)
    private val _isActiveShareMode = MutableStateFlow(false)
    val isActiveShareMode: StateFlow<Boolean> = _isActiveShareMode.asStateFlow()

    /**
     * Intercept shared content for preview in bottom sheet
     */
    fun interceptShareForPreview(sharedContent: SharedContent) {
        scope.launch {
            // Enter share mode and reset privacy state
            _isActiveShareMode.value = true
            _pendingShareFullPrivacy.value = false

            val type = when {
                sharedContent.fileUri != null -> ContentTypeDetector.detectTypeFromMime(sharedContent.mimeType)
                sharedContent.text != null -> ContentTypeDetector.detectContentType(sharedContent.text)
                else -> NoteType.FILE
            }

            // Find potentially related notes
            val relatedNotes = findRelatedNotes(sharedContent)

            _pendingShare.value = PendingShareData(
                text = sharedContent.text,
                fileUri = sharedContent.fileUri,
                fileName = sharedContent.fileName,
                mimeType = sharedContent.mimeType,
                fileSize = sharedContent.fileSize,
                detectedType = type,
                suggestedCategory = null,  // Let AI decide by default
                relatedNotes = relatedNotes
            )
        }
    }

    /**
     * Callback interface for share confirmation result
     */
    interface ShareConfirmCallback {
        suspend fun processNoteWithAi(note: Note)
    }

    /**
     * Confirm the share and create the note
     * Returns the created note for further processing
     */
    suspend fun confirmShare(
        selectedCategory: String?,
        aiInstructions: String,
        callback: ShareConfirmCallback
    ): Note? {
        val pending = _pendingShare.value ?: return null
        val isFullPrivacy = _pendingShareFullPrivacy.value

        // Get all files to process (handles both single and multiple)
        val allFiles = pending.getAllFiles()

        // Process all files - compress and store each one
        val processedAttachments = mutableListOf<NoteAttachment>()
        var firstFileUri: String? = null
        var firstFileName: String? = null
        var firstMimeType: String? = null
        var firstFileSize: Long? = null

        allFiles.forEachIndexed { index, file ->
            try {
                val compressed = FileStorageHelper.compressAndStore(
                    context = context,
                    sourceUri = Uri.parse(file.fileUri),
                    mimeType = file.mimeType,
                    originalFileName = file.fileName
                )

                val finalUri = compressed?.uri ?: file.fileUri
                val finalName = compressed?.fileName ?: file.fileName ?: "file_${index + 1}"
                val finalMime = compressed?.mimeType ?: file.mimeType ?: "application/octet-stream"
                val finalSize = compressed?.compressedSize ?: file.fileSize ?: 0

                // Add to attachments list
                processedAttachments.add(NoteAttachment(
                    uri = finalUri,
                    fileName = finalName,
                    mimeType = finalMime,
                    fileSize = finalSize
                ))

                // Store first file info for backward compatibility
                if (index == 0) {
                    firstFileUri = finalUri
                    firstFileName = finalName
                    firstMimeType = finalMime
                    firstFileSize = finalSize
                }

                // Log compression savings
                compressed?.let {
                    if (it.isCompressed) {
                        Log.i(TAG, "File compressed: ${it.fileName} saved ${formatSize(it.savedBytes)} " +
                                "(${String.format("%.1f", it.compressionRatio)}% reduction)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to compress and store file: ${e.message}")
            }
        }

        // Build content description
        val content = buildString {
            pending.text?.let { append(it) }
            if (processedAttachments.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                if (processedAttachments.size == 1) {
                    append("File: ${processedAttachments[0].fileName}")
                    append("\nType: ${processedAttachments[0].mimeType}")
                    append("\nSize: ${formatSize(processedAttachments[0].fileSize)}")
                } else {
                    append("${processedAttachments.size} files attached:")
                    processedAttachments.forEachIndexed { idx, att ->
                        append("\n${idx + 1}. ${att.fileName}")
                    }
                }
            }
            // Only add AI instructions if NOT in full privacy mode
            if (aiInstructions.isNotBlank() && !isFullPrivacy) {
                append("\n\n[User Context: $aiInstructions]")
            }
        }

        // Generate title
        val title = when {
            processedAttachments.size > 1 -> "${processedAttachments.size} ${getTypeNamePlural(pending.detectedType)}"
            firstFileName != null -> firstFileName
            else -> generateTitle(pending.text ?: "", pending.detectedType)
        }

        // Serialize attachments to JSON
        val attachmentsJson = if (processedAttachments.size > 1) {
            com.google.gson.Gson().toJson(processedAttachments)
        } else null

        val note = Note(
            title = title ?: "Shared content",
            content = content,
            fileUri = firstFileUri,
            fileName = firstFileName,
            fileMimeType = firstMimeType,
            fileSize = firstFileSize,
            imageUri = if (pending.detectedType == NoteType.IMAGE) firstFileUri else null,
            type = pending.detectedType,
            categoryName = if (isFullPrivacy) "Private Notes" else selectedCategory,
            processingStatus = if (isFullPrivacy || selectedCategory != null) ProcessingStatus.PENDING else ProcessingStatus.PROCESSING,
            isFullPrivacy = isFullPrivacy,
            excludeFromAiChat = isFullPrivacy,
            attachmentsJson = attachmentsJson
        )

        repository.insertNote(note)

        if (isFullPrivacy) {
            // Full privacy mode - no AI processing at all
            saveNoteWithoutAiProcessing(note)
            Log.i(TAG, "Note saved in full privacy mode: ${note.title}")
        } else if (selectedCategory != null) {
            // Category selected - just assign category
            val category = repository.getOrCreateCategory(selectedCategory)
            val updatedNote = note.copy(
                categoryId = category.id,
                processingStatus = ProcessingStatus.COMPLETED
            )
            repository.updateNote(updatedNote)
        } else {
            // Normal AI processing - delegate to callback
            callback.processNoteWithAi(note)
        }

        // Reset share mode state
        resetShareState()

        return note
    }

    /**
     * Cancel the share operation
     */
    fun cancelShare() {
        resetShareState()
    }

    /**
     * Toggle full privacy mode for share flow
     */
    fun toggleFullPrivacy() {
        _pendingShareFullPrivacy.value = !_pendingShareFullPrivacy.value
        Log.d(TAG, "Full privacy mode toggled: ${_pendingShareFullPrivacy.value}")
    }

    /**
     * Check if in active share mode
     */
    fun isInShareMode(): Boolean = _isActiveShareMode.value

    /**
     * Reset all share state
     */
    private fun resetShareState() {
        _pendingShare.value = null
        _isActiveShareMode.value = false
        _pendingShareFullPrivacy.value = false
    }

    /**
     * Save note in full privacy mode - no AI processing at all
     */
    private suspend fun saveNoteWithoutAiProcessing(note: Note) {
        val category = repository.getOrCreateCategory("Private Notes")
        val savedNote = note.copy(
            isFullPrivacy = true,
            excludeFromAiChat = true,
            categoryId = category.id,
            categoryName = category.name,
            processingStatus = ProcessingStatus.COMPLETED,
            summary = null,  // No AI summary
            whySaved = null,  // No AI insight
            updatedAt = System.currentTimeMillis()
        )
        repository.updateNote(savedNote)
    }

    /**
     * Find related notes based on shared content
     */
    private fun findRelatedNotes(sharedContent: SharedContent): List<Note> {
        // Simple keyword matching for related notes
        val searchText = sharedContent.text?.lowercase() ?: sharedContent.fileName?.lowercase() ?: ""
        if (searchText.length < 3) return emptyList()

        val keywords = searchText.split(Regex("[\\s,;.!?]+"))
            .filter { it.length >= 3 }
            .take(5)

        // ============================================================================
        // SECURITY: PrivacyGuard - private notes are INVISIBLE
        // They DO NOT EXIST for related notes suggestions
        // ============================================================================
        return PrivacyGuard.getAiVisibleNotes(getNotesSnapshot())
            .filter { note ->
                keywords.any { keyword ->
                    note.title.lowercase().contains(keyword) ||
                    note.content.lowercase().contains(keyword)
                }
            }
            .take(5)
    }

    // Helper functions

    private fun formatSize(bytes: Long): String = ContentTypeDetector.formatSize(bytes)

    private fun getTypeNamePlural(type: NoteType): String {
        return when (type) {
            NoteType.IMAGE -> "Images"
            NoteType.VIDEO -> "Videos"
            NoteType.AUDIO -> "Audio Files"
            NoteType.DOCUMENT -> "Documents"
            else -> "Files"
        }
    }

    private fun generateTitle(content: String, type: NoteType): String {
        return when {
            content.isNotBlank() && content.length > 30 -> content.take(30) + "..."
            content.isNotBlank() -> content
            else -> ContentTypeDetector.getDefaultTitle(type)
        }
    }
}
