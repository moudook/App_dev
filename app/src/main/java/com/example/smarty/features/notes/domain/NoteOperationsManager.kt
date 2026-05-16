package com.example.smarty.features.notes.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.smarty.core.common.util.ContentTypeDetector
import com.example.smarty.core.common.util.DatabaseWriteBatcher
import com.example.smarty.core.common.util.FileStorageHelper
import com.example.smarty.core.common.util.PrivacyGuard
import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.Category
import com.example.smarty.core.domain.model.CategoryStatInfo
import com.example.smarty.core.domain.model.ChunkAnalysis
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.NoteAttachment
import com.example.smarty.core.domain.model.NoteType
import com.example.smarty.core.domain.model.ProcessingStatus
import com.example.smarty.core.domain.model.SharedContent
import com.example.smarty.core.domain.model.TodoItem
import com.example.smarty.core.domain.model.getAllAttachmentUris
import com.example.smarty.core.domain.model.getAttachments
import com.example.smarty.core.domain.model.getTags
import com.example.smarty.core.domain.model.getTodos
import com.example.smarty.core.domain.model.withAttachments
import com.example.smarty.core.domain.model.withChunkAnalyses
import com.example.smarty.core.domain.model.withTags
import com.example.smarty.core.domain.model.withTodos
import com.example.smarty.data.remote.AIService
import com.example.smarty.data.remote.DocumentAnalysisResponse
import com.example.smarty.data.repository.SmartyRepository
import com.example.smarty.features.notes.ui.widget.QuickNoteWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

class NoteOperationsManager(
    private val repository: SmartyRepository,
    private val aiService: AIService,
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "NoteOperationsManager"

        private fun String.sha256(): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(this.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    private var engagementManager: NoteEngagementManager? = null
    fun setEngagementManager(manager: NoteEngagementManager) { engagementManager = manager }
    private fun trackNoteCreation() { engagementManager?.onNoteCreated() }

    private val writeBatcher = DatabaseWriteBatcher(repository, scope).also { it.start() }

    private val noteCreationTimes = mutableListOf<Long>()
    private val maxNotesPerMinute = 30
    private val noteCreationMutex = Mutex()
    private val filesInUse = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val fileInUseMutex = Mutex()

    fun markFileInUse(uriString: String) { filesInUse.add(uriString) }
    fun releaseFile(uriString: String) { filesInUse.remove(uriString) }
    fun isFileInUse(uriString: String): Boolean = filesInUse.contains(uriString)

    private suspend fun checkNoteCreationRateLimit(): Boolean = noteCreationMutex.withLock {
        val now = System.currentTimeMillis()
        noteCreationTimes.removeAll { it < now - 60_000 }
        if (noteCreationTimes.size >= maxNotesPerMinute) return@withLock false
        noteCreationTimes.add(now)
        true
    }

    private val noteOperationMutex = Mutex()
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    interface AiProcessingCallback {
        suspend fun onProcessingComplete(note: Note)
        suspend fun onProcessingError(note: Note, error: String)
    }

    private var aiCallback: AiProcessingCallback? = null
    fun setAiProcessingCallback(callback: AiProcessingCallback) { aiCallback = callback }

    fun addNote(
        content: String,
        type: NoteType = NoteType.BRAIN_DUMP,
        sourceUrl: String? = null,
        excludeFromAiChat: Boolean = false,
        initialCategory: String? = null
    ) {
        scope.launch {
            noteOperationMutex.withLock {
                if (!checkNoteCreationRateLimit()) {
                    Log.w("NoteOps", "Note creation rate limit exceeded")
                    return@withLock
                }
                val detectedType = if (type == NoteType.BRAIN_DUMP) ContentTypeDetector.detectContentType(content) else type
                val shouldProcess = NoteType.isAnalyzable(detectedType)
                val category = initialCategory?.let { repository.getOrCreateCategory(it) } ?: repository.getOrCreateCategory(context.getString(com.example.smarty.R.string.category_quick_notes))
                val contentHash = content.sha256()
                val note = Note(
                    id = java.util.UUID.randomUUID().toString(),
                    title = ContentTypeDetector.extractTitle(context, content, detectedType),
                    content = content,
                    type = detectedType,
                    categoryId = category.id,
                    categoryName = category.name,
                    sourceUrl = sourceUrl ?: if (detectedType != NoteType.BRAIN_DUMP && content.startsWith("http")) content else null,
                    processingStatus = if (shouldProcess) ProcessingStatus.PROCESSING else ProcessingStatus.COMPLETED,
                    contentHash = contentHash,
                    processedContentHash = if (!shouldProcess) contentHash else null,
                    excludeFromAiChat = excludeFromAiChat
                )
                repository.insertNote(note)
                Log.d(TAG, "Note inserted: ${note.id}")
                trackNoteCreation()
                QuickNoteWidgetProvider.updateAllWidgets(context)
                if (shouldProcess) processNoteWithAi(note) else storeWithoutAnalysis(note)
            }
        }
    }

    fun addNoteFromShare(sharedContent: SharedContent) {
        scope.launch {
            if (!checkNoteCreationRateLimit()) {
                Log.w("NoteOps", "Note creation rate limit exceeded")
                return@launch
            }
            val note = when {
                sharedContent.fileUri != null -> {
                    val type = ContentTypeDetector.detectTypeFromMime(sharedContent.mimeType)
                    val shouldProcess = NoteType.isAnalyzable(type)
                    val textContent = buildFileDescription(sharedContent)
                    val contentHash = textContent.sha256()
                    Note(
                        id = java.util.UUID.randomUUID().toString(),
                        title = sharedContent.fileName ?: ContentTypeDetector.getDefaultTitle(context, type),
                        content = textContent,
                        fileUri = sharedContent.fileUri,
                        fileName = sharedContent.fileName,
                        fileMimeType = sharedContent.mimeType,
                        fileSize = sharedContent.fileSize,
                        imageUri = if (type == NoteType.IMAGE) sharedContent.fileUri else null,
                        type = type,
                        processingStatus = if (shouldProcess) ProcessingStatus.PROCESSING else ProcessingStatus.COMPLETED,
                        contentHash = contentHash,
                        processedContentHash = if (!shouldProcess) contentHash else null
                    )
                }
                sharedContent.text != null -> {
                    val type = ContentTypeDetector.detectContentType(sharedContent.text!!)
                    val contentHash = sharedContent.text!!.sha256()
                    Note(
                        id = java.util.UUID.randomUUID().toString(),
                        title = ContentTypeDetector.extractTitle(context, sharedContent.text!!, type),
                        content = sharedContent.text!!,
                        sourceUrl = if (type != NoteType.BRAIN_DUMP && sharedContent.text!!.contains("://")) ContentTypeDetector.extractUrl(sharedContent.text!!) else null,
                        type = type,
                        processingStatus = ProcessingStatus.PROCESSING,
                        contentHash = contentHash
                    )
                }
                else -> return@launch
            }
            repository.insertNote(note)
            trackNoteCreation()
            if (NoteType.isAnalyzable(note.type)) processNoteWithAi(note) else storeWithoutAnalysis(note)
        }
    }

    fun addNoteWithAttachments(
        content: String,
        attachments: List<Attachment>,
        excludeFromAiChat: Boolean = false
    ) {
        scope.launch {
            if (!checkNoteCreationRateLimit()) {
                Log.w("NoteOps", "Note creation rate limit exceeded")
                return@launch
            }
            if (attachments.isEmpty() && content.isBlank()) return@launch
            if (attachments.isEmpty()) {
                addNote(content, excludeFromAiChat = excludeFromAiChat)
                return@launch
            }
            val primaryOriginal = attachments[0]
            val type = ContentTypeDetector.detectTypeFromMime(primaryOriginal.mimeType)
            val hasUserText = content.isNotBlank()
            if (attachments.size == 1 && type == NoteType.IMAGE && hasUserText) {
                Log.d(TAG, "Fast path: Single IMAGE + TEXT")
                processSingleImageWithText(primaryOriginal, content, excludeFromAiChat)
                return@launch
            }
            val tempAttachments = attachments.map {
                NoteAttachment(id = java.util.UUID.randomUUID().toString(), uri = it.uri, fileName = it.fileName, mimeType = it.mimeType, fileSize = it.fileSize)
            }
            val title = when {
                hasUserText -> ContentTypeDetector.extractTitle(context, content, type)
                attachments.size > 1 -> "${attachments.size} ${getTypePluralName(type)}"
                else -> primaryOriginal.fileName
            }
            val initialContent = if (hasUserText) content else buildMultipleAttachmentsDescription(tempAttachments)
            val contentHash = initialContent.sha256()
            val initialNote = Note(
                id = java.util.UUID.randomUUID().toString(),
                title = title,
                content = initialContent,
                fileUri = primaryOriginal.uri.toString(),
                fileName = primaryOriginal.fileName,
                fileMimeType = primaryOriginal.mimeType,
                fileSize = primaryOriginal.fileSize,
                imageUri = if (type == NoteType.IMAGE) primaryOriginal.uri.toString() else null,
                type = type,
                processingStatus = ProcessingStatus.PENDING,
                contentHash = contentHash,
                excludeFromAiChat = excludeFromAiChat
            ).withAttachments(tempAttachments)
            repository.insertNote(initialNote)
            trackNoteCreation()
            val processedResults = coroutineScope {
                attachments.mapIndexed { index, attachment ->
                    async(Dispatchers.IO) {
                        val copied = copyAttachmentToStorage(attachment)
                        index to NoteAttachment(id = java.util.UUID.randomUUID().toString(), uri = copied.uri, fileName = copied.fileName, mimeType = copied.mimeType, fileSize = copied.fileSize)
                    }
                }.map { it.await() }
            }.sortedBy { it.first }
            val processedAttachments = processedResults.map { it.second }
            val primaryProcessed = processedResults.firstOrNull()?.second
            val primary = if (primaryProcessed != null) {
                Attachment(id = java.util.UUID.randomUUID().toString(), uri = primaryProcessed.uri, fileName = primaryProcessed.fileName, mimeType = primaryProcessed.mimeType, fileSize = primaryProcessed.fileSize)
            } else attachments[0]
            val isTypeAnalyzable = NoteType.isAnalyzable(type)
            val shouldProcess = isTypeAnalyzable || hasUserText
            val finalContent = when {
                !isTypeAnalyzable && hasUserText -> {
                    val refs = processedAttachments.joinToString(", ") { it.fileName }
                    "[Attached: $refs]\n\n$content"
                }
                hasUserText -> content
                else -> buildMultipleAttachmentsDescription(processedAttachments)
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
            writeBatcher.queueUpdate(updatedNote)
            if (shouldProcess) processNoteWithAi(updatedNote) else storeWithoutAnalysis(updatedNote)
        }
    }

    private suspend fun processSingleImageWithText(attachment: Attachment, content: String, excludeFromAiChat: Boolean) {
        val processed = copyAttachmentToStorage(attachment)
        val copiedFileUri = processed.uri.toString()
        try {
            val noteAttachment = NoteAttachment(id = java.util.UUID.randomUUID().toString(), uri = copiedFileUri, fileName = processed.fileName, mimeType = processed.mimeType, fileSize = processed.fileSize)
            val contentHash = content.sha256()
            val note = Note(
                id = java.util.UUID.randomUUID().toString(),
                title = ContentTypeDetector.extractTitle(context, content, NoteType.IMAGE),
                content = content,
                fileUri = copiedFileUri,
                fileName = processed.fileName,
                fileMimeType = processed.mimeType,
                fileSize = processed.fileSize,
                imageUri = copiedFileUri,
                type = NoteType.IMAGE,
                processingStatus = ProcessingStatus.PROCESSING,
                contentHash = contentHash,
                excludeFromAiChat = excludeFromAiChat
            ).withAttachments(listOf(noteAttachment))
            repository.insertNote(note)
            trackNoteCreation()
            processNoteWithAi(note)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert note, cleaning up orphaned file: ${e.message}", e)
            cleanupOrphanedFile(copiedFileUri)
            throw e
        }
    }

    private fun cleanupOrphanedFile(uriString: String) {
        try {
            val uri = Uri.parse(uriString)
            if (uriString.contains(context.filesDir.absolutePath)) {
                val file = java.io.File(uri.path ?: return)
                if (file.exists() && file.delete()) Log.d(TAG, "Cleaned up orphaned file: ${file.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup orphaned file: ${e.message}")
        }
    }

    suspend fun getOrCreateCategory(name: String): Category = repository.getOrCreateCategory(name)

    fun createUserCategory(name: String) {
        scope.launch {
            if (name.length > 10) return@launch
            val category = Category(id = java.util.UUID.randomUUID().toString(), name = name, isAiGenerated = false, noteCount = 0)
            repository.insertCategory(category)
        }
    }

    fun renameCategory(category: Category, newName: String) {
        scope.launch {
            if (newName.length > 20) return@launch
            repository.updateCategory(category.copy(name = newName, lastUpdated = System.currentTimeMillis()))
            repository.refreshNotes()
        }
    }

    fun deleteCategory(category: Category) {
        scope.launch {
            try { repository.deleteCategoryWithCleanup(category) }
            catch (e: Exception) { Log.e(TAG, "Failed to delete category: ${category.name}", e) }
        }
    }

    fun refreshNotes() { scope.launch { repository.refreshNotes() } }
    fun syncCategoryCounts() { scope.launch { repository.syncAllCategoryCounts() } }
    suspend fun getNoteById(id: String): Note? = repository.getNoteById(id)
    suspend fun getCategoryById(id: String): Category? = repository.getCategoryById(id)

    fun optimizeSearchIndex() {
        scope.launch(Dispatchers.IO) {
            try {
                repository.optimizeSearchIndex()
                Log.i(TAG, "Search index optimized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to optimize search index: ${e.message}")
            }
        }
    }

    suspend fun getAllCategoriesSync(): List<Category> = repository.getAllCategoriesOneShot()
    fun getAllNotes() = repository.getAllNotes()
    fun getArchivedNotes() = repository.getArchivedNotes()
    fun getAllCategories() = repository.getAllCategories()
    fun getNoteByIdFlow(id: String) = repository.getNoteByIdFlow(id)

    fun findNoteByDescription(description: String, notes: List<Note>): Note? {
        val lower = description.lowercase()
        return notes.find { note -> note.title.lowercase().contains(lower) || note.content.lowercase().contains(lower) }
    }

    fun getNoteVersionsFlow(noteId: String) = repository.getNoteVersions(noteId)

    fun archiveNote(noteId: String) {
        scope.launch {
            try {
                noteOperationMutex.withLock { repository.archiveNote(noteId) }
                repository.refreshNotes()
            } catch (e: Exception) { Log.e(TAG, "Error archiving note: ${e.message}", e) }
        }
    }

    fun unarchiveNote(noteId: String) {
        scope.launch {
            try {
                noteOperationMutex.withLock { repository.unarchiveNote(noteId) }
                repository.refreshNotes()
            } catch (e: Exception) { Log.e(TAG, "Error unarchiving note: ${e.message}", e) }
        }
    }

    fun deleteNote(note: Note) {
        scope.launch {
            try {
                noteOperationMutex.withLock {
                    note.getAllAttachmentUris().forEach { uri ->
                        if (isFileInUse(uri)) {
                            Log.w(TAG, "Skipping deletion of in-use file: ${uri.takeLast(30)}")
                            scope.launch {
                                var attempts = 0
                                while (isFileInUse(uri) && attempts < 30) { kotlinx.coroutines.delay(100); attempts++ }
                                if (!isFileInUse(uri)) {
                                    FileStorageHelper.deleteFile(context, uri)
                                    Log.d(TAG, "Deferred deletion completed: ${uri.takeLast(30)}")
                                }
                            }
                        } else {
                            FileStorageHelper.deleteFile(context, uri)
                        }
                    }
                    repository.deleteNote(note)
                }
            } catch (e: Exception) { Log.e(TAG, "Error deleting note: ${e.message}", e) }
        }
    }

    suspend fun deleteNoteById(noteId: String, activeNotes: List<Note>, archivedNotes: List<Note>) {
        try {
            val note = activeNotes.find { it.id == noteId } ?: archivedNotes.find { it.id == noteId }
            note?.let { deleteNote(it) }
        } catch (e: Exception) { Log.e(TAG, "Error deleting note by ID: ${e.message}", e) }
    }

    fun bulkArchiveNotes(noteIds: List<String>) {
        scope.launch {
            try {
                noteOperationMutex.withLock { noteIds.forEach { repository.archiveNote(it) } }
                repository.refreshNotes()
                Log.i(TAG, "Bulk archived ${noteIds.size} notes")
            } catch (e: Exception) { Log.e(TAG, "Bulk archive failed: ${e.message}") }
        }
    }

    fun bulkDeleteNotes(noteIds: List<String>, activeNotes: List<Note>, archivedNotes: List<Note>) {
        scope.launch {
            try {
                noteOperationMutex.withLock {
                    noteIds.forEach { id ->
                        val note = activeNotes.find { it.id == id } ?: archivedNotes.find { it.id == id }
                        note?.let {
                            it.getAllAttachmentUris().forEach { uri ->
                                if (!isFileInUse(uri)) FileStorageHelper.deleteFile(context, uri)
                            }
                            repository.deleteNote(it)
                        }
                    }
                }
                Log.i(TAG, "Bulk deleted ${noteIds.size} notes")
            } catch (e: Exception) { Log.e(TAG, "Bulk delete failed: ${e.message}") }
        }
    }

    fun bulkMoveToCategory(noteIds: List<String>, categoryName: String) {
        scope.launch {
            try {
                val category = repository.getOrCreateCategory(categoryName)
                noteOperationMutex.withLock {
                    noteIds.forEach { id ->
                        repository.getNoteById(id)?.let { note ->
                            repository.updateNote(note.copy(categoryId = category.id, categoryName = category.name, updatedAt = System.currentTimeMillis()))
                        }
                    }
                }
                Log.i(TAG, "Bulk moved ${noteIds.size} notes to $categoryName")
            } catch (e: Exception) { Log.e(TAG, "Bulk move failed: ${e.message}") }
        }
    }

    fun bulkAddTags(noteIds: List<String>, tags: List<String>) {
        scope.launch {
            try {
                noteOperationMutex.withLock {
                    noteIds.forEach { id ->
                        repository.getNoteById(id)?.let { note ->
                            val currentTags = note.getTags().toMutableSet()
                            currentTags.addAll(tags)
                        }
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Bulk tagging failed: ${e.message}") }
        }
    }

    fun updateNoteTodos(noteId: String, todos: List<TodoItem>, activeNotes: List<Note>, archivedNotes: List<Note>) {
        scope.launch {
            try {
                noteOperationMutex.withLock {
                    val note = activeNotes.find { it.id == noteId } ?: archivedNotes.find { it.id == noteId }
                    note?.let { repository.updateNote(it.withTodos(todos)) }
                }
            } catch (e: Exception) { Log.e(TAG, "Error updating note todos: ${e.message}", e) }
        }
    }

    suspend fun addTodoToNote(noteId: String, text: String) {
        try {
            noteOperationMutex.withLock {
                val note = repository.getNoteById(noteId) ?: return
                val newTodo = TodoItem(id = java.util.UUID.randomUUID().toString(), text = text, createdAt = System.currentTimeMillis())
                repository.updateNote(note.withTodos(note.getTodos() + newTodo))
            }
        } catch (e: Exception) { Log.e(TAG, "Error adding todo to note: ${e.message}", e) }
    }

    suspend fun updateNote(noteId: String, newTitle: String? = null, newContent: String? = null, activeNotes: List<Note>, archivedNotes: List<Note>): Boolean {
        return try {
            noteOperationMutex.withLock {
                val note = activeNotes.find { it.id == noteId } ?: archivedNotes.find { it.id == noteId }
                if (note != null) {
                    repository.updateNote(note.copy(title = newTitle ?: note.title, content = newContent ?: note.content, updatedAt = System.currentTimeMillis()))
                    true
                } else false
            }
        } catch (e: Exception) { Log.e(TAG, "Error updating note: ${e.message}", e); false }
    }

    suspend fun processPdfWithAi(note: Note) { processNoteWithAi(note) }

    fun pinNote(noteId: String) { scope.launch { repository.pinNote(noteId) } }
    fun unpinNote(noteId: String) { scope.launch { repository.unpinNote(noteId) } }
    fun toggleNotePin(noteId: String) { scope.launch { repository.toggleNotePin(noteId) } }

    fun setNoteReminder(noteId: String, reminderText: String, durationMs: Long? = null) {
        scope.launch {
            val expiresAt = durationMs?.let { System.currentTimeMillis() + it }
            repository.setNoteReminder(noteId, reminderText, expiresAt)
        }
    }

    fun clearNoteReminder(noteId: String) { scope.launch { repository.clearNoteReminder(noteId) } }

    suspend fun getNoteVersions(noteId: String) = repository.getNoteVersionsOnce(noteId)
    suspend fun restoreNoteVersion(noteId: String, versionId: String): Boolean = repository.restoreNoteVersion(noteId, versionId)

    fun editNote(noteId: String, newTitle: String, newContent: String, newSummary: String?, newWhySaved: String?, newAttachments: List<NoteAttachment>? = null) {
        scope.launch {
            try {
                noteOperationMutex.withLock {
                    val note = repository.getNoteById(noteId)
                    note?.let {
                        val newContentHash = newContent.sha256()
                        var updatedNote = it.copy(
                            title = newTitle, content = newContent, summary = newSummary, whySaved = newWhySaved,
                            contentHash = newContentHash, processedContentHash = null,
                            processingStatus = ProcessingStatus.PENDING, updatedAt = System.currentTimeMillis()
                        )
                        if (newAttachments != null) {
                            updatedNote = updatedNote.withAttachments(newAttachments)
                            val primary = newAttachments.firstOrNull()
                            updatedNote = if (primary != null) updatedNote.copy(
                                fileUri = primary.uri, fileName = primary.fileName, fileMimeType = primary.mimeType,
                                fileSize = primary.fileSize, imageUri = if (primary.mimeType.startsWith("image/")) primary.uri else null
                            ) else updatedNote.copy(fileUri = null, fileName = null, fileMimeType = null, fileSize = null, imageUri = null)
                        }
                        repository.updateNoteWithVersion(updatedNote, "User edit")
                        if (it.content != newContent) processNoteWithAi(updatedNote)
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Error editing note: ${e.message}") }
        }
    }

    fun markNoteAsViewed(noteId: String) { scope.launch { repository.updateNoteViewedStatus(noteId, true) } }
    fun updateNoteCategory(noteId: String, categoryId: String, categoryName: String) { scope.launch { repository.updateNoteCategory(noteId, categoryId, categoryName) } }

    suspend fun processNoteWithAi(note: Note) {
        if (!PrivacyGuard.canAiProcess(note)) {
            PrivacyGuard.logSecurityEvent(note.id, "AI processing")
            saveNoteWithoutAiProcessing(note)
            return
        }
        _isProcessing.value = true
        try {
            val attachments = note.getAttachments()
            val extractedTexts = mutableListOf<String>()
            val userText = note.content.takeIf { it.isNotBlank() && !it.startsWith("[Attached:") && !it.all { c -> c.isWhitespace() } }
            if (userText != null) {
                extractedTexts.add(context.getString(com.example.smarty.R.string.user_content_label, userText))
                Log.d(TAG, "Added user text: ${userText.length} chars")
            }
            for (attachment in attachments) {
                try {
                    val uri = Uri.parse(attachment.uri)
                    val mimeType = attachment.mimeType.lowercase()
                    when {
                        mimeType.startsWith("image/") -> {
                            Log.d(TAG, "Processing image via Server: ${attachment.fileName}")
                            val fileBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            if (fileBytes != null) {
                                try {
                                    val extractedText = aiService.processImage(fileBytes, mimeType)
                                    extractedTexts.add(context.getString(com.example.smarty.R.string.image_content_label, attachment.fileName, extractedText))
                                    Log.d(TAG, "Server OCR extracted ${extractedText.length} chars from ${attachment.fileName}")
                                } catch (e: Exception) { Log.e(TAG, "Server image processing failed: ${e.message}") }
                            }
                        }
                        mimeType == "application/pdf" -> {
                            Log.d(TAG, "Routing PDF to Server: ${attachment.fileName}")
                            val fileBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            if (fileBytes != null) {
                                try {
                                    val extractedText = aiService.processPdf(fileBytes, attachment.fileName)
                                    extractedTexts.add(context.getString(com.example.smarty.R.string.file_content_label, attachment.fileName, extractedText))
                                } catch (e: Exception) { Log.e(TAG, "Server PDF processing failed: ${e.message}") }
                            }
                        }
                        mimeType.startsWith("text/") -> {
                            Log.d(TAG, "Processing text file: ${attachment.fileName}")
                            try {
                                val textContent = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText().take(10000) }
                                if (!textContent.isNullOrBlank()) {
                                    extractedTexts.add(context.getString(com.example.smarty.R.string.file_content_label, attachment.fileName, textContent))
                                    Log.d(TAG, "Text file: ${textContent.length} chars from ${attachment.fileName}")
                                }
                            } catch (e: Exception) { Log.w(TAG, "Failed to read text file: ${e.message}") }
                        }
                        else -> Log.d(TAG, "Skipping unsupported type: $mimeType (${attachment.fileName})")
                    }
                } catch (e: Exception) { Log.w(TAG, "Failed to process attachment ${attachment.fileName}: ${e.message}") }
            }
            val enhancedContent = if (extractedTexts.isNotEmpty()) extractedTexts.joinToString("\n\n---\n\n") else context.getString(com.example.smarty.R.string.attachments_label, attachments.joinToString(", ") { it.fileName })
            Log.i(TAG, "Combined content for AI: ${enhancedContent.length} chars from ${extractedTexts.size} sources")
            val attachmentMetadata = attachments.map { com.example.smarty.core.domain.model.AttachmentMetadata.fromNoteAttachment(it) }.takeIf { it.isNotEmpty() }
            val result = aiService.analyzeContent(enhancedContent, attachmentMetadata)
            if (result.success) {
                val category = repository.getOrCreateCategory(result.category)
                val existingTodos = note.getTodos()
                val newTodos = result.todos.mapIndexed { index, todoText -> TodoItem(id = java.util.UUID.randomUUID().toString(), text = todoText, isCompleted = false, createdAt = System.currentTimeMillis()) }
                val contentHash = note.contentHash ?: enhancedContent.sha256()
                val updatedNote = note.copy(
                    title = result.title, summary = result.summary, categoryId = category.id, categoryName = category.name,
                    whySaved = result.whySaved, processingStatus = ProcessingStatus.COMPLETED, contentHash = contentHash,
                    processedContentHash = contentHash, updatedAt = System.currentTimeMillis()
                ).withTodos(existingTodos + newTodos)
                writeBatcher.queueUpdate(updatedNote)
                writeBatcher.forceFlush()
                Log.i(TAG, "Note processed: category=${result.category}, title=${result.title}")
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

    fun bulkUnarchiveNotes(noteIds: List<String>) {
        scope.launch {
            try {
                noteOperationMutex.withLock { noteIds.forEach { repository.unarchiveNote(it) } }
                repository.refreshNotes()
                Log.i(TAG, "Bulk unarchived ${noteIds.size} notes")
            } catch (e: Exception) { Log.e(TAG, "Bulk unarchive failed: ${e.message}") }
        }
    }

    suspend fun storeWithoutAnalysis(note: Note) {
        val fallbackResponse = com.example.smarty.data.remote.AIResponseParser.smartFallbackCategorization(context, note.content)
        val category = repository.getOrCreateCategory(fallbackResponse.category)
        val contentHash = note.contentHash ?: note.content.sha256()
        val updatedNote = note.copy(
            categoryId = category.id, categoryName = category.name,
            summary = fallbackResponse.summary.takeIf { it.isNotBlank() }, whySaved = fallbackResponse.whySaved.takeIf { it.isNotBlank() },
            processingStatus = ProcessingStatus.COMPLETED, contentHash = contentHash, processedContentHash = contentHash,
            updatedAt = System.currentTimeMillis()
        )
        repository.updateNote(updatedNote)
        Log.d(TAG, "Stored note ${note.id} with fallback category: ${fallbackResponse.category}")
        writeBatcher.forceFlush()
        QuickNoteWidgetProvider.updateAllWidgets(context)
    }

    private suspend fun saveNoteWithoutAiProcessing(note: Note) {
        val category = repository.getOrCreateCategory(context.getString(com.example.smarty.R.string.category_private_notes))
        val contentHash = note.contentHash ?: note.content.sha256()
        val savedNote = note.copy(
            isFullPrivacy = true, excludeFromAiChat = true, categoryId = category.id, categoryName = category.name,
            processingStatus = ProcessingStatus.COMPLETED, contentHash = contentHash, processedContentHash = contentHash,
            summary = null, whySaved = null, updatedAt = System.currentTimeMillis()
        )
        writeBatcher.queueUpdate(savedNote)
        writeBatcher.forceFlush()
    }

    fun summarizeNote(noteId: String, activeNotes: List<Note>, archivedNotes: List<Note>) {
        scope.launch {
            val note = activeNotes.find { it.id == noteId } ?: archivedNotes.find { it.id == noteId }
            note?.let { processNoteWithAi(it) }
        }
    }

    fun getCategoryStats(categories: List<Category>, activeNotes: List<Note>): List<CategoryStatInfo> {
        return categories.map { cat -> CategoryStatInfo(cat.name, activeNotes.count { it.categoryId == cat.id }) }
    }

    suspend fun flushPendingWrites() { writeBatcher.forceFlush() }
    suspend fun cleanup() { writeBatcher.stop() }

    private fun buildFileDescription(content: SharedContent): String {
        val sb = StringBuilder()
        content.fileName?.let { sb.append(context.getString(com.example.smarty.R.string.label_file, it)) }
        content.mimeType?.let { if (sb.isNotEmpty()) sb.append('\n'); sb.append(context.getString(com.example.smarty.R.string.label_type, it)) }
        content.fileSize?.let { if (sb.isNotEmpty()) sb.append('\n'); sb.append(context.getString(com.example.smarty.R.string.label_size, ContentTypeDetector.formatFileSize(context, it))) }
        return if (sb.isEmpty()) context.getString(com.example.smarty.R.string.label_shared_file) else sb.toString()
    }

    private fun buildMultipleAttachmentsDescription(attachments: List<NoteAttachment>): String {
        return attachments.joinToString("\n") { att -> "${att.fileName} (${ContentTypeDetector.formatFileSize(context, att.fileSize)})" }
    }

    private fun getTypePluralName(type: NoteType): String = when (type) {
        NoteType.IMAGE -> context.getString(com.example.smarty.R.string.type_images_plural)
        NoteType.DOCUMENT -> context.getString(com.example.smarty.R.string.type_pdfs_plural)
        NoteType.VIDEO -> context.getString(com.example.smarty.R.string.type_videos_plural)
        NoteType.AUDIO -> context.getString(com.example.smarty.R.string.type_audio_plural)
        else -> context.getString(com.example.smarty.R.string.type_files_plural)
    }

    private suspend fun copyAttachmentToStorage(attachment: Attachment): Attachment {
        return try {
            val result = FileStorageHelper.compressAndStore(context = context, sourceUri = android.net.Uri.parse(attachment.uri), mimeType = attachment.mimeType, originalFileName = attachment.fileName)
            if (result != null) {
                Log.d(TAG, "Attachment compressed: ${attachment.fileName} (${result.compressionType}, saved ${result.savedBytes} bytes)")
                attachment.copy(uri = result.uri, fileSize = result.compressedSize)
            } else attachment
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress attachment: ${e.message}", e)
            attachment
        }
    }
}
