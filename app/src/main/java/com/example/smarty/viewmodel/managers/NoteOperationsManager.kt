package com.example.smarty.viewmodel.managers

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.smarty.data.model.Attachment
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteAttachment
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.model.TodoItem
import com.example.smarty.data.model.getAllAttachmentUris
import com.example.smarty.data.model.withAttachments
import com.example.smarty.data.model.withTodos
import com.example.smarty.data.remote.AIService
import com.example.smarty.data.repository.CogniRepository
import com.example.smarty.util.ContentTypeDetector
import com.example.smarty.util.FileStorageHelper
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.viewmodel.SharedContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages all note CRUD operations.
 *
 * Responsibilities:
 * - Note creation (text, share, attachments)
 * - Note updates, archiving, deletion
 * - Todo item management
 * - AI processing coordination
 *
 * @property repository Note repository for persistence
 * @property aiService AI service for note analysis
 * @property context Application context for file operations
 * @property scope Coroutine scope for async operations
 */
class NoteOperationsManager(
    private val repository: CogniRepository,
    private val aiService: AIService,
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "NoteOperationsManager"
    }

    // Thread-safe mutex for note operations
    private val noteOperationMutex = Mutex()

    // Processing state
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    // Callback for AI processing completion
    interface AiProcessingCallback {
        suspend fun onProcessingComplete(note: Note)
        suspend fun onProcessingError(note: Note, error: String)
    }

    private var aiCallback: AiProcessingCallback? = null

    fun setAiProcessingCallback(callback: AiProcessingCallback) {
        aiCallback = callback
    }

    /**
     * Add a simple text note.
     */
    fun addNote(
        content: String,
        type: NoteType = NoteType.BRAIN_DUMP,
        sourceUrl: String? = null,
        excludeFromAiChat: Boolean = false
    ) {
        scope.launch {
            val detectedType = if (type == NoteType.BRAIN_DUMP) {
                ContentTypeDetector.detectContentType(content)
            } else type

            val shouldProcess = NoteType.isAnalyzable(detectedType)

            val note = Note(
                title = ContentTypeDetector.extractTitle(content, detectedType),
                content = content,
                type = detectedType,
                sourceUrl = sourceUrl ?: if (detectedType != NoteType.BRAIN_DUMP && content.startsWith("http")) content else null,
                processingStatus = if (shouldProcess) ProcessingStatus.PROCESSING else ProcessingStatus.COMPLETED,
                excludeFromAiChat = excludeFromAiChat
            )
            repository.insertNote(note)

            if (shouldProcess) {
                processNoteWithAi(note)
            } else {
                storeWithoutAnalysis(note)
            }
        }
    }

    /**
     * Add note from shared content.
     */
    fun addNoteFromShare(sharedContent: SharedContent) {
        scope.launch {
            val note = when {
                sharedContent.fileUri != null -> {
                    val type = ContentTypeDetector.detectTypeFromMime(sharedContent.mimeType)
                    val shouldProcess = NoteType.isAnalyzable(type)

                    Note(
                        title = sharedContent.fileName ?: ContentTypeDetector.getDefaultTitle(type),
                        content = buildFileDescription(sharedContent),
                        fileUri = sharedContent.fileUri,
                        fileName = sharedContent.fileName,
                        fileMimeType = sharedContent.mimeType,
                        fileSize = sharedContent.fileSize,
                        imageUri = if (type == NoteType.IMAGE) sharedContent.fileUri else null,
                        type = type,
                        processingStatus = if (shouldProcess) ProcessingStatus.PROCESSING else ProcessingStatus.COMPLETED
                    )
                }
                sharedContent.text != null -> {
                    val type = ContentTypeDetector.detectContentType(sharedContent.text)
                    Note(
                        title = ContentTypeDetector.extractTitle(sharedContent.text, type),
                        content = sharedContent.text,
                        sourceUrl = if (type != NoteType.BRAIN_DUMP && sharedContent.text.contains("://"))
                            ContentTypeDetector.extractUrl(sharedContent.text) else null,
                        type = type,
                        processingStatus = ProcessingStatus.PROCESSING
                    )
                }
                else -> return@launch
            }

            repository.insertNote(note)

            if (NoteType.isAnalyzable(note.type)) {
                processNoteWithAi(note)
            } else {
                storeWithoutAnalysis(note)
            }
        }
    }

    /**
     * Add note with attachments.
     */
    fun addNoteWithAttachments(
        content: String,
        attachments: List<Attachment>,
        excludeFromAiChat: Boolean = false
    ) {
        scope.launch {
            if (attachments.isEmpty() && content.isBlank()) return@launch

            if (attachments.isEmpty()) {
                addNote(content, excludeFromAiChat = excludeFromAiChat)
                return@launch
            }

            // Create initial note with pending status
            val primaryOriginal = attachments[0]
            val type = ContentTypeDetector.detectTypeFromMime(primaryOriginal.mimeType)

            val tempAttachments = attachments.map {
                NoteAttachment(
                    uri = it.uri.toString(),
                    fileName = it.fileName,
                    mimeType = it.mimeType,
                    fileSize = it.fileSize
                )
            }

            val title = when {
                content.isNotBlank() -> ContentTypeDetector.extractTitle(content, type)
                attachments.size > 1 -> "${attachments.size} ${getTypePluralName(type)}"
                else -> primaryOriginal.fileName
            }

            val initialContent = if (content.isNotBlank()) {
                content
            } else {
                buildMultipleAttachmentsDescription(tempAttachments)
            }

            val initialNote = Note(
                title = title,
                content = initialContent,
                fileUri = primaryOriginal.uri.toString(),
                fileName = primaryOriginal.fileName,
                fileMimeType = primaryOriginal.mimeType,
                fileSize = primaryOriginal.fileSize,
                imageUri = if (type == NoteType.IMAGE) primaryOriginal.uri.toString() else null,
                type = type,
                processingStatus = ProcessingStatus.PENDING,
                excludeFromAiChat = excludeFromAiChat
            ).withAttachments(tempAttachments)

            repository.insertNote(initialNote)

            // Copy and compress attachments in background
            val processedAttachments = mutableListOf<NoteAttachment>()
            var primaryProcessed: Attachment? = null

            attachments.forEachIndexed { index, attachment ->
                val copied = copyAttachmentToStorage(attachment)
                if (index == 0) primaryProcessed = copied

                processedAttachments.add(
                    NoteAttachment(
                        uri = copied.uri.toString(),
                        fileName = copied.fileName,
                        mimeType = copied.mimeType,
                        fileSize = copied.fileSize
                    )
                )
            }

            val primary = primaryProcessed ?: attachments[0]
            val shouldProcess = NoteType.isAnalyzable(type)

            val finalContent = if (content.isNotBlank()) {
                content
            } else {
                buildMultipleAttachmentsDescription(processedAttachments)
            }

            val updatedNote = initialNote.copy(
                content = finalContent,
                fileUri = primary.uri.toString(),
                fileName = primary.fileName,
                fileSize = primary.fileSize,
                fileMimeType = primary.mimeType,
                imageUri = if (type == NoteType.IMAGE) primary.uri.toString() else null,
                processingStatus = if (shouldProcess) ProcessingStatus.PROCESSING else ProcessingStatus.COMPLETED
            ).withAttachments(processedAttachments)

            repository.updateNote(updatedNote)

            if (shouldProcess) {
                processNoteWithAi(updatedNote)
            } else {
                storeWithoutAnalysis(updatedNote)
            }
        }
    }

    /**
     * Archive a note.
     */
    fun archiveNote(noteId: String) {
        scope.launch {
            try {
                noteOperationMutex.withLock {
                    repository.archiveNote(noteId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error archiving note: ${e.message}", e)
            }
        }
    }

    /**
     * Unarchive a note.
     */
    fun unarchiveNote(noteId: String) {
        scope.launch {
            try {
                noteOperationMutex.withLock {
                    repository.unarchiveNote(noteId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error unarchiving note: ${e.message}", e)
            }
        }
    }

    /**
     * Delete a note and its attachments.
     */
    fun deleteNote(note: Note) {
        scope.launch {
            try {
                noteOperationMutex.withLock {
                    note.getAllAttachmentUris().forEach { uri ->
                        FileStorageHelper.deleteFile(context, uri)
                    }
                    repository.deleteNote(note)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting note: ${e.message}", e)
            }
        }
    }

    /**
     * Delete a note by ID.
     */
    suspend fun deleteNoteById(noteId: String, activeNotes: List<Note>, archivedNotes: List<Note>) {
        try {
            val note = activeNotes.find { it.id == noteId }
                ?: archivedNotes.find { it.id == noteId }
            note?.let { deleteNote(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting note by ID: ${e.message}", e)
        }
    }

    /**
     * Update todos on a note.
     */
    fun updateNoteTodos(noteId: String, todos: List<TodoItem>, activeNotes: List<Note>, archivedNotes: List<Note>) {
        scope.launch {
            try {
                noteOperationMutex.withLock {
                    val note = activeNotes.find { it.id == noteId }
                        ?: archivedNotes.find { it.id == noteId }
                    note?.let {
                        val updatedNote = it.withTodos(todos)
                        repository.updateNote(updatedNote)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating note todos: ${e.message}", e)
            }
        }
    }

    /**
     * Update a note's content and title.
     */
    suspend fun updateNote(
        noteId: String,
        newTitle: String? = null,
        newContent: String? = null,
        activeNotes: List<Note>,
        archivedNotes: List<Note>
    ): Boolean {
        return try {
            noteOperationMutex.withLock {
                val note = activeNotes.find { it.id == noteId }
                    ?: archivedNotes.find { it.id == noteId }

                if (note != null) {
                    val updated = note.copy(
                        title = newTitle ?: note.title,
                        content = newContent ?: note.content,
                        updatedAt = System.currentTimeMillis()
                    )
                    repository.updateNote(updated)
                    true
                } else false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating note: ${e.message}", e)
            false
        }
    }

    // ==================== AI Processing ====================

    /**
     * Process note with AI for categorization and summary.
     */
    suspend fun processNoteWithAi(note: Note) {
        if (!PrivacyGuard.canAiProcess(note)) {
            PrivacyGuard.logSecurityEvent(note.id, "AI processing")
            saveNoteWithoutAiProcessing(note)
            return
        }

        _isProcessing.value = true

        try {
            val result = aiService.analyzeContent(note.content)

            if (result.success) {
                val category = repository.getOrCreateCategory(result.category)
                val updatedNote = note.copy(
                    title = result.title,
                    summary = result.summary,
                    categoryId = category.id,
                    categoryName = category.name,
                    whySaved = result.whySaved,
                    processingStatus = ProcessingStatus.COMPLETED,
                    updatedAt = System.currentTimeMillis()
                )
                repository.updateNote(updatedNote)
                aiCallback?.onProcessingComplete(updatedNote)
            } else {
                storeWithoutAnalysis(note)
                aiCallback?.onProcessingError(note, result.error ?: "Analysis failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI processing failed: ${e.message}", e)
            storeWithoutAnalysis(note)
            aiCallback?.onProcessingError(note, e.message ?: "Unknown error")
        } finally {
            _isProcessing.value = false
        }
    }

    private suspend fun storeWithoutAnalysis(note: Note) {
        val categoryName = ContentTypeDetector.getStorageCategoryName(note.type)
        val category = repository.getOrCreateCategory(categoryName)
        val updatedNote = note.copy(
            categoryId = category.id,
            categoryName = category.name,
            summary = null,
            whySaved = null,
            processingStatus = ProcessingStatus.COMPLETED,
            updatedAt = System.currentTimeMillis()
        )
        repository.updateNote(updatedNote)
    }

    private suspend fun saveNoteWithoutAiProcessing(note: Note) {
        val category = repository.getOrCreateCategory("Private Notes")
        val savedNote = note.copy(
            isFullPrivacy = true,
            excludeFromAiChat = true,
            categoryId = category.id,
            categoryName = category.name,
            processingStatus = ProcessingStatus.COMPLETED,
            summary = null,
            whySaved = null,
            updatedAt = System.currentTimeMillis()
        )
        repository.updateNote(savedNote)
    }

    // ==================== Helper Functions ====================

    private fun buildFileDescription(content: SharedContent): String {
        val sb = StringBuilder()
        content.fileName?.let { sb.append("File: ").append(it) }
        content.mimeType?.let {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("Type: ").append(it)
        }
        content.fileSize?.let {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("Size: ").append(ContentTypeDetector.formatFileSize(it))
        }
        return if (sb.isEmpty()) "Shared file" else sb.toString()
    }

    private fun buildMultipleAttachmentsDescription(attachments: List<NoteAttachment>): String {
        return attachments.joinToString("\n") { att ->
            "${att.fileName} (${ContentTypeDetector.formatFileSize(att.fileSize)})"
        }
    }

    private fun getTypePluralName(type: NoteType): String = when (type) {
        NoteType.IMAGE -> "images"
        NoteType.DOCUMENT -> "PDFs"
        NoteType.VIDEO -> "videos"
        NoteType.AUDIO -> "audio files"
        else -> "files"
    }

    private suspend fun copyAttachmentToStorage(attachment: Attachment): Attachment {
        return try {
            val result = FileStorageHelper.copyToInternalStorage(
                context = context,
                sourceUri = attachment.uri,
                mimeType = attachment.mimeType,
                originalFileName = attachment.fileName
            )
            if (result != null) {
                attachment.copy(
                    uri = Uri.parse(result.uri),
                    fileSize = result.fileSize
                )
            } else {
                attachment
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy attachment: ${e.message}", e)
            attachment
        }
    }
}
