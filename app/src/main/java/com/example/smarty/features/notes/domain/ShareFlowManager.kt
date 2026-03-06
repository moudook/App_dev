package com.example.smarty.features.notes.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.NoteAttachment
import com.example.smarty.core.domain.model.NoteType
import com.example.smarty.core.domain.model.ProcessingStatus
import com.example.smarty.data.repository.SmartyRepository
import com.example.smarty.ui.components.PendingShareData
import com.example.smarty.ui.components.PendingFileInfo
import com.example.smarty.core.common.util.ContentTypeDetector
import com.example.smarty.core.common.util.FileStorageHelper
import com.example.smarty.core.common.util.PrivacyGuard
import com.example.smarty.core.common.util.UrlMetadataExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.example.smarty.core.domain.model.SharedContent
import com.example.smarty.core.domain.model.SharedFileInfo

/**
 * Manages share flow state and operations.
 * Extracted from SmartyViewModel for better separation of concerns.
 *
 * Responsibilities:
 * - Pending share state management
 * - Full privacy mode toggle for shares
 * - Active share mode tracking
 * - File compression and storage during share
 * - Related notes discovery
 */
class ShareFlowManager(
    private val repository: SmartyRepository,
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
     * Intercept shared content for preview in bottom sheet.
     * Enhanced with URL metadata extraction for web clipper functionality.
     * 
     * CRITICAL FIX (BUG-061): Files are now copied to app storage IMMEDIATELY to prevent
     * data loss from expired content URI permissions. Previously, files were only copied
     * in confirmShare(), which could fail if permissions expired.
     */
    fun interceptShareForPreview(sharedContent: SharedContent) {
        scope.launch {
            // Enter share mode and reset privacy state
            _isActiveShareMode.value = true
            _pendingShareFullPrivacy.value = false

            var type = when {
                sharedContent.fileUri != null -> ContentTypeDetector.detectTypeFromMime(sharedContent.mimeType)
                sharedContent.text != null -> ContentTypeDetector.detectContentType(sharedContent.text)
                else -> NoteType.FILE
            }

            // Enhanced text for URLs with metadata
            var enhancedText = sharedContent.text
            var urlMetadata: UrlMetadataExtractor.UrlMetadata? = null

            // Web Clipper: If text contains a URL, try to fetch its metadata
            if (sharedContent.text != null && UrlMetadataExtractor.containsUrl(sharedContent.text)) {
                val url = UrlMetadataExtractor.extractUrl(sharedContent.text)
                if (url != null) {
                    Log.d(TAG, "Web clipper: Fetching metadata for URL: $url")
                    urlMetadata = UrlMetadataExtractor.fetchMetadata(url)

                    if (urlMetadata != null) {
                        Log.d(TAG, "Web clipper: Got metadata - title: ${urlMetadata.title}")
                        type = NoteType.WEBSITE

                        // Build enhanced text with metadata AND full article content (Reader Mode)
                        enhancedText = buildString {
                            append(" ${urlMetadata.title}\n")
                            append(" ${urlMetadata.domain ?: url}\n")
                            urlMetadata.description?.let {
                                append("\n$it\n")
                            }
                            append("\nSource: $url")

                            // READER MODE: Append full article text for AI searchability
                            urlMetadata.articleContent?.let { article ->
                                append("\n\n--- Article Content ---\n\n")
                                append(article)
                            }
                        }

                        Log.d(TAG, "Web clipper: Saved ${urlMetadata.articleContent?.length ?: 0} chars of article content")
                    }
                }
            }

            // CRITICAL FIX: Copy files to app storage IMMEDIATELY to prevent permission expiration
            var copiedFileUri: String? = null
            var copiedFileName: String? = null
            var copiedFileSize: Long? = null
            
            // Handle single file share
            if (sharedContent.fileUri != null) {
                withContext(Dispatchers.IO) {
                    try {
                        val sourceUri = Uri.parse(sharedContent.fileUri)
                        val result = FileStorageHelper.compressAndStore(
                            context = context,
                            sourceUri = sourceUri,
                            mimeType = sharedContent.mimeType,
                            originalFileName = sharedContent.fileName
                        )
                        
                        if (result != null) {
                            copiedFileUri = result.uri
                            copiedFileName = result.fileName
                            copiedFileSize = result.compressedSize
                            Log.d(TAG, "File copied immediately: ${sharedContent.fileName} -> ${result.fileName}")
                        } else {
                            // Fallback: use original URI (might fail later if permissions expire)
                            copiedFileUri = sharedContent.fileUri
                            copiedFileName = sharedContent.fileName
                            copiedFileSize = sharedContent.fileSize
                            Log.w(TAG, "File copy returned null, using original URI")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to copy shared file immediately: ${e.message}", e)
                        // Fallback to original URI
                        copiedFileUri = sharedContent.fileUri
                        copiedFileName = sharedContent.fileName
                        copiedFileSize = sharedContent.fileSize
                    }
                }
            }
            
            // Handle multiple file shares
            val copiedFiles = if (sharedContent.files.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    sharedContent.files.mapNotNull { file ->
                        try {
                            val sourceUri = Uri.parse(file.fileUri)
                            val result = FileStorageHelper.compressAndStore(
                                context = context,
                                sourceUri = sourceUri,
                                mimeType = file.mimeType,
                                originalFileName = file.fileName
                            )
                            
                            if (result != null) {
                                Log.d(TAG, "File copied immediately: ${file.fileName} -> ${result.fileName}")
                                // Convert to PendingFileInfo for PendingShareData
                                PendingFileInfo(
                                    fileUri = result.uri,
                                    fileName = result.fileName,
                                    mimeType = file.mimeType,
                                    fileSize = result.compressedSize
                                )
                            } else {
                                Log.w(TAG, "File copy returned null for ${file.fileName}")
                                PendingFileInfo(
                                    fileUri = file.fileUri,
                                    fileName = file.fileName,
                                    mimeType = file.mimeType,
                                    fileSize = file.fileSize
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to copy shared file immediately: ${e.message}", e)
                            PendingFileInfo(
                                fileUri = file.fileUri,
                                fileName = file.fileName,
                                mimeType = file.mimeType,
                                fileSize = file.fileSize
                            )
                        }
                    }
                }
            } else {
                emptyList()
            }

            // Find potentially related notes
            val relatedNotes = findRelatedNotes(sharedContent)

            _pendingShare.value = PendingShareData(
                text = enhancedText,
                fileUri = copiedFileUri ?: sharedContent.fileUri,
                fileName = urlMetadata?.title ?: copiedFileName ?: sharedContent.fileName,
                mimeType = sharedContent.mimeType,
                fileSize = copiedFileSize ?: sharedContent.fileSize,
                detectedType = type,
                suggestedCategory = null,  // Let AI decide by default
                relatedNotes = relatedNotes,
                files = copiedFiles
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
     * Confirm the share and create the note.
     * Returns the created note for further processing.
     * 
     * NOTE: Files are already copied to app storage in interceptShareForPreview(),
     * so this function just uses the already-copied file URIs.
     */
    suspend fun confirmShare(
        selectedCategory: String?,
        aiInstructions: String,
        callback: ShareConfirmCallback
    ): Note? {
        val pending = _pendingShare.value ?: return null
        val isFullPrivacy = _pendingShareFullPrivacy.value

        // Get all files (already copied to app storage in interceptShareForPreview)
        val allFiles = pending.getAllFiles()

        // Convert to NoteAttachment - files are already copied, no need to compress again
        val processedAttachments = allFiles.map { file ->
            NoteAttachment(
                id = java.util.UUID.randomUUID().toString(),
                uri = file.fileUri,
                fileName = file.fileName ?: "unknown",
                mimeType = file.mimeType ?: "application/octet-stream",
                fileSize = file.fileSize ?: 0
            )
        }

        // Get first file info for backward compatibility
        val firstFile = allFiles.firstOrNull()
        val firstFileUri = firstFile?.fileUri
        val firstFileName = firstFile?.fileName
        val firstMimeType = firstFile?.mimeType
        val firstFileSize = firstFile?.fileSize

        // Log if files failed to process
        if (allFiles.isEmpty() && pending.fileUri != null) {
            Log.w(TAG, "BUG-061: No files in pending share data")
        }

        // Build content description
        val content = buildString {
            pending.text?.let { append(it) }
            if (processedAttachments.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                if (processedAttachments.size == 1) {
                    append("File: ${processedAttachments[0].fileName}")
                    append("\nType: ${processedAttachments[0].mimeType}")
                    append("\nSize: ${formatSize(context, processedAttachments[0].fileSize)}")
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
            else -> generateTitle(context, pending.text ?: "", pending.detectedType)
        }

        // Serialize attachments to JSON
        val attachmentsJson = if (processedAttachments.size > 1) {
            com.google.gson.Gson().toJson(processedAttachments)
        } else null

        val note = Note(
            id = java.util.UUID.randomUUID().toString(),
            title = title ?: "Shared content",
            content = content,
            fileUri = firstFileUri,
            fileName = firstFileName,
            fileMimeType = firstMimeType,
            fileSize = firstFileSize,
            imageUri = if (pending.detectedType == NoteType.IMAGE) firstFileUri else null,
            type = pending.detectedType,
            categoryName = if (isFullPrivacy) context.getString(com.example.smarty.R.string.category_private_notes) else selectedCategory,
            processingStatus = if (isFullPrivacy || selectedCategory != null) ProcessingStatus.PENDING else ProcessingStatus.PROCESSING,
            isFullPrivacy = isFullPrivacy,
            excludeFromAiChat = isFullPrivacy,
            attachmentsJson = attachmentsJson
        )

        repository.insertNote(note)

        if (isFullPrivacy) {
            // Full privacy mode - no AI processing at all
            saveNoteWithoutAiProcessing(note)
            // SECURITY: Don't log private note titles to prevent data leakage via logcat
            Log.d(TAG, "Note saved in full privacy mode: id=${note.id.take(8)}...")
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
        val category = repository.getOrCreateCategory(context.getString(com.example.smarty.R.string.category_private_notes))
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

    private fun formatSize(context: Context, bytes: Long): String = ContentTypeDetector.formatSize(context, bytes)

    private fun getTypeNamePlural(type: NoteType): String {
        return when (type) {
            NoteType.IMAGE -> context.getString(com.example.smarty.R.string.note_type_images)
            NoteType.VIDEO -> context.getString(com.example.smarty.R.string.note_type_videos)
            NoteType.AUDIO -> context.getString(com.example.smarty.R.string.note_type_audio_files)
            NoteType.DOCUMENT -> context.getString(com.example.smarty.R.string.note_type_documents)
            else -> context.getString(com.example.smarty.R.string.note_type_files)
        }
    }

    private fun generateTitle(context: Context, content: String, type: NoteType): String {
        return when {
            content.isNotBlank() && content.length > 30 -> content.take(30) + "..."
            content.isNotBlank() -> content
            else -> ContentTypeDetector.getDefaultTitle(context, type)
        }
    }
}
