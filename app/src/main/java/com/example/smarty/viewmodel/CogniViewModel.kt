package com.example.smarty.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarty.data.cache.CacheManager
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.AIProviderConfig
import com.example.smarty.data.local.CogniDatabase
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.model.AgentAction
import com.example.smarty.data.model.AgentActionResult
import com.example.smarty.data.model.Attachment
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatRole
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteAttachment
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.model.TodoItem
import com.example.smarty.data.model.getAllAttachmentUris
import com.example.smarty.data.model.getTodos
import com.example.smarty.data.model.withAttachments
import com.example.smarty.data.model.withTodos
import com.example.smarty.data.remote.AgentService
import com.example.smarty.data.remote.AIService
import com.example.smarty.ui.components.PendingShareData
import com.example.smarty.data.repository.ChatRepository
import com.example.smarty.data.repository.CogniRepository
import com.example.smarty.data.model.ChatSession
import com.example.smarty.util.ContentTypeDetector
import com.example.smarty.util.FileStorageHelper
import com.example.smarty.util.PDFTextExtractor
import com.example.smarty.util.PDFExtractionResult
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.ShakeDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Single file info for sharing
 */
data class SharedFileInfo(
    val fileUri: String,
    val fileName: String?,
    val mimeType: String?,
    val fileSize: Long?
)

/**
 * Shared content that can include text or multiple files
 */
data class SharedContent(
    val text: String? = null,
    val fileUri: String? = null,  // Legacy single file
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSize: Long? = null,
    val files: List<SharedFileInfo> = emptyList()  // Multiple files
) {
    /** Get all files (combines legacy single + multiple) */
    fun getAllFiles(): List<SharedFileInfo> {
        if (files.isNotEmpty()) return files
        if (fileUri != null) {
            return listOf(SharedFileInfo(fileUri, fileName, mimeType, fileSize))
        }
        return emptyList()
    }
}

class CogniViewModel(application: Application) : AndroidViewModel(application) {

    // Secure preferences and AI service
    private val securePreferences = SecurePreferences.getInstance(application)
    private val aiService = AIService(securePreferences)
    private val pdfExtractor = PDFTextExtractor(application)

    // Repository needs to be initialized before agentService
    private val database = CogniDatabase.getDatabase(application)
    private val repository = CogniRepository(
        database.noteDao(),
        database.categoryDao()
    )

    // Agent service for chat functionality
    private val agentService = AgentService(aiService, repository)

    // Chat repository for persistence
    private val chatRepository = ChatRepository(database.chatDao())

    // Shake detector for toggling chat mode
    private var shakeDetector: ShakeDetector? = null

    // ==================== Delegated Managers ====================

    // Chat Manager - handles chat state and session lifecycle
    private val chatManager = ChatManager(chatRepository, viewModelScope)

    // Share Flow Manager - handles share interception and processing
    private val shareFlowManager = ShareFlowManager(
        repository = repository,
        context = application,
        scope = viewModelScope,
        getNotesSnapshot = { notes.value }
    )

    // ==================== Chat State (delegated to ChatManager) ====================
    val isChatMode: StateFlow<Boolean> = chatManager.isChatMode
    val chatMessages: StateFlow<List<ChatMessage>> = chatManager.chatMessages
    val isChatProcessing: StateFlow<Boolean> = chatManager.isChatProcessing
    val currentSessionId: StateFlow<String?> = chatManager.currentSessionId
    val chatSessions: StateFlow<List<ChatSession>> = chatManager.chatSessions

    // ==================== Share Flow State (delegated to ShareFlowManager) ====================
    val pendingShare: StateFlow<PendingShareData?> = shareFlowManager.pendingShare
    val pendingShareFullPrivacy: StateFlow<Boolean> = shareFlowManager.pendingShareFullPrivacy
    val isActiveShareMode: StateFlow<Boolean> = shareFlowManager.isActiveShareMode

    // AI exclusion state for pending notes (while writing)
    private val _pendingNoteAiExcluded = MutableStateFlow(false)
    val pendingNoteAiExcluded: StateFlow<Boolean> = _pendingNoteAiExcluded.asStateFlow()

    // Current input text (hoisted from UI for shake detection)
    private val _currentInputText = MutableStateFlow("")
    val currentInputText: StateFlow<String> = _currentInputText.asStateFlow()

    // Current input attachments (hoisted from UI for shake detection)
    private val _currentInputAttachments = MutableStateFlow<List<Attachment>>(emptyList())
    val currentInputAttachments: StateFlow<List<Attachment>> = _currentInputAttachments.asStateFlow()

    // Expose secure preferences state for UI
    val geminiKeys: StateFlow<List<String>> = securePreferences.geminiKeys
    val huggingFaceKeys: StateFlow<List<String>> = securePreferences.huggingFaceKeys
    val providerConfigs: StateFlow<Map<AIProvider, AIProviderConfig>> = securePreferences.providerConfigs
    val isPinSet: StateFlow<Boolean> = securePreferences.isPinSet

    // Cache management
    private val cacheManager = CacheManager.getInstance(application)
    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()
    private val _isClearingCache = MutableStateFlow(false)
    val isClearingCache: StateFlow<Boolean> = _isClearingCache.asStateFlow()

    companion object {
        private const val TAG = "CogniViewModel"
    }

    val notes = repository.getAllNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories = repository.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedNote = MutableStateFlow<Note?>(null)
    val selectedNote: StateFlow<Note?> = _selectedNote.asStateFlow()

    private val _selectedCategory = MutableStateFlow<Category?>(null)
    val selectedCategory: StateFlow<Category?> = _selectedCategory.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    init {
        // Sync category counts on app start to fix any existing mismatches
        viewModelScope.launch {
            repository.syncAllCategoryCounts()
        }
        // Initialize chat manager (loads sessions and cleans up empty ones)
        chatManager.initialize()
    }

    // Public sync function for manual recalculation
    fun syncCategoryCounts() {
        viewModelScope.launch {
            repository.syncAllCategoryCounts()
        }
    }

    fun selectNote(note: Note?) {
        _selectedNote.value = note
    }

    fun selectCategory(category: Category?) {
        _selectedCategory.value = category
    }

    // O(1) lookup using enum ordinal comparison
    private fun shouldAnalyze(type: NoteType): Boolean = NoteType.isAnalyzable(type)

    fun addNote(
        content: String,
        type: NoteType = NoteType.BRAIN_DUMP,
        sourceUrl: String? = null,
        excludeFromAiChat: Boolean = false
    ) {
        viewModelScope.launch {
            val detectedType = if (type == NoteType.BRAIN_DUMP) detectContentType(content) else type
            val shouldProcess = shouldAnalyze(detectedType)

            val note = Note(
                title = extractTitle(content, detectedType),
                content = content,
                type = detectedType,
                sourceUrl = sourceUrl ?: if (detectedType != NoteType.BRAIN_DUMP && content.startsWith("http")) content else null,
                processingStatus = if (shouldProcess) ProcessingStatus.PROCESSING else ProcessingStatus.COMPLETED,
                excludeFromAiChat = excludeFromAiChat
            )
            repository.insertNote(note)

            if (shouldProcess) {
                simulateAiProcessing(note)
            } else {
                // Just categorize without AI analysis
                storeWithoutAnalysis(note)
            }
        }
    }

    fun addNoteFromShare(sharedContent: SharedContent) {
        viewModelScope.launch {
            val note = when {
                // File shared
                sharedContent.fileUri != null -> {
                    val type = detectTypeFromMime(sharedContent.mimeType)
                    val shouldProcess = shouldAnalyze(type)

                    Note(
                        title = sharedContent.fileName ?: getDefaultTitle(type),
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
                // Text/link shared
                sharedContent.text != null -> {
                    val type = detectContentType(sharedContent.text)
                    Note(
                        title = extractTitle(sharedContent.text, type),
                        content = sharedContent.text,
                        sourceUrl = if (type != NoteType.BRAIN_DUMP && sharedContent.text.contains("://"))
                            extractUrl(sharedContent.text) else null,
                        type = type,
                        processingStatus = ProcessingStatus.PROCESSING // Text/links always get analyzed
                    )
                }
                else -> return@launch
            }

            repository.insertNote(note)

            if (shouldAnalyze(note.type)) {
                simulateAiProcessing(note)
            } else {
                storeWithoutAnalysis(note)
            }
        }
    }

    /**
     * Add note with attachments from the input field
     * Handles both text content and file attachments
     * Groups multiple attachments into a SINGLE note with attachmentsJson
     *
     * @param content Text content of the note
     * @param attachments List of file attachments
     * @param excludeFromAiChat Whether to exclude this note from AI chat context
     */
    fun addNoteWithAttachments(
        content: String,
        attachments: List<Attachment>,
        excludeFromAiChat: Boolean = _pendingNoteAiExcluded.value
    ) {
        viewModelScope.launch {
            // Capture the AI exclusion state
            val aiExcluded = excludeFromAiChat

            when {
                // Attachments present (with or without text) - create SINGLE grouped note
                attachments.isNotEmpty() -> {
                    // Copy all attachments to internal storage
                    val processedAttachments = mutableListOf<NoteAttachment>()
                    var primaryCopied: Attachment? = null

                    attachments.forEachIndexed { index, attachment ->
                        val copied = copyAttachmentToStorage(attachment)
                        if (index == 0) primaryCopied = copied

                        processedAttachments.add(
                            NoteAttachment(
                                uri = copied.uri.toString(),
                                fileName = copied.fileName,
                                mimeType = copied.mimeType,
                                fileSize = copied.fileSize
                            )
                        )
                    }

                    val primary = primaryCopied ?: return@launch
                    val type = detectTypeFromMime(primary.mimeType)
                    val shouldProcess = shouldAnalyze(type)

                    // Generate appropriate title
                    val title = when {
                        content.isNotBlank() -> extractTitle(content, type)
                        attachments.size > 1 -> "${attachments.size} ${getTypePluralName(type)}"
                        else -> primary.fileName
                    }

                    // Build content description for multiple attachments
                    val noteContent = if (content.isNotBlank()) {
                        content
                    } else {
                        buildMultipleAttachmentsDescription(processedAttachments)
                    }

                    // Create SINGLE note with all attachments
                    val note = Note(
                        title = title,
                        content = noteContent,
                        fileUri = primary.uri.toString(),
                        fileName = primary.fileName,
                        fileMimeType = primary.mimeType,
                        fileSize = primary.fileSize,
                        imageUri = if (type == NoteType.IMAGE) primary.uri.toString() else null,
                        type = type,
                        processingStatus = if (shouldProcess) ProcessingStatus.PROCESSING else ProcessingStatus.COMPLETED,
                        excludeFromAiChat = aiExcluded
                    ).withAttachments(processedAttachments)

                    repository.insertNote(note)

                    if (shouldProcess) {
                        simulateAiProcessing(note)
                    } else {
                        storeWithoutAnalysis(note)
                    }
                }

                // Just text, no attachments
                content.isNotBlank() -> {
                    addNote(content, excludeFromAiChat = aiExcluded)
                }
            }

            // Reset pending note state after submission
            resetPendingNoteState()
        }
    }

    /**
     * Get plural name for a note type (for titles like "3 Images")
     */
    private fun getTypePluralName(type: NoteType): String {
        return when (type) {
            NoteType.IMAGE -> "Images"
            NoteType.VIDEO -> "Videos"
            NoteType.AUDIO -> "Audio Files"
            NoteType.DOCUMENT -> "Documents"
            NoteType.SPREADSHEET -> "Spreadsheets"
            NoteType.PRESENTATION -> "Presentations"
            NoteType.CODE -> "Code Files"
            NoteType.ARCHIVE -> "Archives"
            NoteType.APK -> "APK Files"
            else -> "Files"
        }
    }

    /**
     * Build description for multiple attachments
     */
    private fun buildMultipleAttachmentsDescription(attachments: List<NoteAttachment>): String {
        val sb = StringBuilder()
        sb.append("${attachments.size} files attached:\n\n")
        attachments.forEachIndexed { index, attachment ->
            sb.append("${index + 1}. ${attachment.fileName}")
            if (attachment.fileSize > 0) {
                sb.append(" (${formatFileSize(attachment.fileSize)})")
            }
            if (index < attachments.lastIndex) sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Compresses and stores an attachment to internal storage for persistence.
     * Uses optimal compression based on file type.
     * Returns the original attachment if compression fails.
     */
    private suspend fun copyAttachmentToStorage(attachment: Attachment): Attachment {
        return try {
            val compressed = FileStorageHelper.compressAndStore(
                context = getApplication(),
                sourceUri = attachment.uri,
                mimeType = attachment.mimeType,
                originalFileName = attachment.fileName
            )
            if (compressed != null) {
                // Log compression savings
                if (compressed.isCompressed) {
                    Log.i(TAG, "Attachment compressed: ${attachment.fileName} saved ${formatSize(compressed.savedBytes)} " +
                            "(${String.format("%.1f", compressed.compressionRatio)}% reduction)")
                }
                attachment.copy(
                    uri = Uri.parse(compressed.uri),
                    fileName = compressed.fileName,
                    fileSize = compressed.compressedSize,
                    mimeType = compressed.mimeType
                )
            } else {
                Log.w(TAG, "File compression returned null, using original URI: ${attachment.uri}")
                attachment
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress attachment: ${e.message}")
            attachment // Return original on failure
        }
    }

    /**
     * Build description for an attachment
     */
    private fun buildAttachmentDescription(attachment: Attachment): String {
        val sb = StringBuilder()
        sb.append("File: ").append(attachment.fileName)
        sb.append('\n')
        sb.append("Type: ").append(attachment.mimeType)
        if (attachment.fileSize > 0) {
            sb.append('\n')
            sb.append("Size: ").append(formatFileSize(attachment.fileSize))
        }
        return sb.toString()
    }

    /**
     * Store file without AI analysis - just put in appropriate category
     */
    private suspend fun storeWithoutAnalysis(note: Note) {
        val categoryName = getStorageCategoryName(note.type)
        val category = repository.getOrCreateCategory(categoryName)

        val updatedNote = note.copy(
            categoryId = category.id,
            categoryName = category.name,
            summary = null, // No AI summary
            whySaved = null, // No AI insight
            processingStatus = ProcessingStatus.COMPLETED,
            updatedAt = System.currentTimeMillis()
        )
        repository.updateNote(updatedNote)
    }

    // Delegate to ContentTypeDetector for O(1) storage category lookup
    private fun getStorageCategoryName(type: NoteType): String =
        ContentTypeDetector.getStorageCategoryName(type)

    fun archiveNote(noteId: String) {
        viewModelScope.launch {
            repository.archiveNote(noteId)
        }
    }

    fun unarchiveNote(noteId: String) {
        viewModelScope.launch {
            repository.unarchiveNote(noteId)
        }
    }

    // Archived notes for archive screen
    val archivedNotes = repository.getArchivedNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteNote(note: Note) {
        viewModelScope.launch {
            // Clean up attachment files (only deletes app's copies, not original files)
            val context = getApplication<Application>()
            note.getAllAttachmentUris().forEach { uri ->
                FileStorageHelper.deleteFile(context, uri)
            }
            // Then delete database record
            repository.deleteNote(note)
        }
    }

    fun deleteNoteById(noteId: String) {
        viewModelScope.launch {
            // Search in both active notes and archived notes
            val note = notes.value.find { it.id == noteId }
                ?: archivedNotes.value.find { it.id == noteId }
            note?.let { deleteNote(it) }
        }
    }

    fun updateNoteTodos(noteId: String, todos: List<TodoItem>) {
        viewModelScope.launch {
            // Search in both active notes and archived notes
            val note = notes.value.find { it.id == noteId }
                ?: archivedNotes.value.find { it.id == noteId }
            note?.let {
                val updatedNote = it.withTodos(todos)
                repository.updateNote(updatedNote)
            }
        }
    }

    // Share interception for bottom sheet (delegated to ShareFlowManager)
    fun interceptShareForPreview(sharedContent: SharedContent) {
        shareFlowManager.interceptShareForPreview(sharedContent)
    }

    fun confirmShare(selectedCategory: String?, aiInstructions: String) {
        viewModelScope.launch {
            shareFlowManager.confirmShare(
                selectedCategory = selectedCategory,
                aiInstructions = aiInstructions,
                callback = object : ShareFlowManager.ShareConfirmCallback {
                    override suspend fun processNoteWithAi(note: Note) {
                        simulateAiProcessing(note)
                    }
                }
            )
        }
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

    fun cancelShare() {
        shareFlowManager.cancelShare()
    }

    // Delegate file size formatting to ContentTypeDetector
    private fun formatSize(bytes: Long): String = ContentTypeDetector.formatSize(bytes)

    /**
     * Generate a title for a note based on content and type.
     * Uses content preview for short content, or truncates long content.
     */
    private fun generateTitle(content: String, type: NoteType): String {
        return when {
            content.isNotBlank() && content.length > 30 -> content.take(30) + "..."
            content.isNotBlank() -> content
            else -> ContentTypeDetector.getDefaultTitle(type)
        }
    }

    // Delegate URL extraction to ContentTypeDetector
    private fun extractUrl(text: String): String? = ContentTypeDetector.extractUrl(text)

    // Delegate MIME type detection to ContentTypeDetector
    private fun detectTypeFromMime(mimeType: String?): NoteType =
        ContentTypeDetector.detectTypeFromMime(mimeType)

    // Delegate default title lookup to ContentTypeDetector
    private fun getDefaultTitle(type: NoteType): String = ContentTypeDetector.getDefaultTitle(type)

    /**
     * Build file description from shared content metadata.
     * Includes filename, MIME type, and formatted file size.
     */
    private fun buildFileDescription(content: SharedContent): String {
        val sb = StringBuilder()
        content.fileName?.let { sb.append("File: ").append(it) }
        content.mimeType?.let {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("Type: ").append(it)
        }
        content.fileSize?.let {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("Size: ").append(formatFileSize(it))
        }
        return if (sb.isEmpty()) "Shared file" else sb.toString()
    }

    // Delegate file size formatting to ContentTypeDetector
    private fun formatFileSize(bytes: Long): String = ContentTypeDetector.formatFileSize(bytes)

    // Delegate content type detection to ContentTypeDetector
    private fun detectContentType(text: String): NoteType = ContentTypeDetector.detectContentType(text)

    // Delegate title extraction to ContentTypeDetector
    private fun extractTitle(content: String, type: NoteType): String =
        ContentTypeDetector.extractTitle(content, type)

    private suspend fun simulateAiProcessing(note: Note) {
        // ============================================================================
        // ABSOLUTE SECURITY BARRIER - PrivacyGuard
        // ============================================================================
        // Private notes can NEVER be processed by AI. There is NO function that
        // can grant AI access to private notes. This is an unbreakable rule.
        // ============================================================================
        if (!PrivacyGuard.canAiProcess(note)) {
            PrivacyGuard.logSecurityEvent(note.id, "AI processing")
            saveNoteWithoutAiProcessing(note)
            return
        }
        // ============================================================================

        _isProcessing.value = true

        // Check if this is a PDF that needs special processing
        if (note.fileMimeType == "application/pdf" && note.fileUri != null) {
            processPdfWithAi(note)
            return
        }

        // Use real AI service with fallback for regular content
        val aiResponse = aiService.analyzeContent(note.content)

        val categoryName = aiResponse.category
        val summary = aiResponse.summary
        val whySaved = aiResponse.whySaved
        val newTitle = aiResponse.title

        val category = repository.getOrCreateCategory(categoryName)

        val updatedNote = note.copy(
            title = if (newTitle.isNotBlank()) newTitle else note.title,
            summary = summary,
            whySaved = whySaved,
            categoryId = category.id,
            categoryName = category.name,
            processingStatus = ProcessingStatus.COMPLETED,
            updatedAt = System.currentTimeMillis()
        )
        repository.updateNote(updatedNote)
        _isProcessing.value = false
    }

    /**
     * Process PDF documents with AI analysis
     * Extracts text from PDF and sends to AI for comprehensive summarization
     *
     * SECURITY: Private PDFs are NEVER processed - uses PrivacyGuard
     */
    private suspend fun processPdfWithAi(note: Note) {
        // ============================================================================
        // ABSOLUTE SECURITY BARRIER - Private PDFs are NEVER processed by AI
        // ============================================================================
        if (!PrivacyGuard.canAiProcess(note)) {
            PrivacyGuard.logSecurityEvent(note.id, "PDF AI processing")
            saveNoteWithoutAiProcessing(note)
            _isProcessing.value = false
            return
        }
        // ============================================================================

        Log.i(TAG, "Processing PDF document: ${note.fileName}")

        try {
            val uri = Uri.parse(note.fileUri)
            val extractionResult = pdfExtractor.extractText(uri)

            when (extractionResult) {
                is PDFExtractionResult.Success -> {
                    Log.i(TAG, "PDF text extracted: ${extractionResult.characterCount} chars from ${extractionResult.pageCount} pages")

                    // Use document analysis for comprehensive summarization
                    val documentResponse = aiService.analyzeDocument(
                        documentText = extractionResult.text,
                        fileName = note.fileName,
                        userContext = null // Could pass user instructions if available
                    )

                    val category = repository.getOrCreateCategory(documentResponse.category)

                    // Build comprehensive summary with key points
                    val fullSummary = buildString {
                        append(documentResponse.summary)
                        if (documentResponse.keyPoints.isNotEmpty()) {
                            append("\n\nKey Points:")
                            documentResponse.keyPoints.forEach { point ->
                                append("\n• $point")
                            }
                        }
                        if (documentResponse.actionItems.isNotEmpty()) {
                            append("\n\nAction Items:")
                            documentResponse.actionItems.forEach { item ->
                                append("\n☐ $item")
                            }
                        }
                    }

                    val updatedNote = note.copy(
                        title = documentResponse.title,
                        summary = fullSummary,
                        whySaved = documentResponse.userRelevance,
                        categoryId = category.id,
                        categoryName = category.name,
                        processingStatus = ProcessingStatus.COMPLETED,
                        updatedAt = System.currentTimeMillis()
                    )
                    repository.updateNote(updatedNote)
                    Log.i(TAG, "PDF processed successfully: ${documentResponse.title}")
                }

                is PDFExtractionResult.Empty -> {
                    Log.w(TAG, "PDF has no extractable text: ${extractionResult.message}")

                    // Still categorize the PDF even without text content
                    val category = repository.getOrCreateCategory("Documents")
                    val updatedNote = note.copy(
                        summary = "Image-based PDF (${extractionResult.pageCount} pages). Text extraction not available for scanned documents.",
                        whySaved = "Document saved for reference",
                        categoryId = category.id,
                        categoryName = category.name,
                        processingStatus = ProcessingStatus.COMPLETED,
                        updatedAt = System.currentTimeMillis()
                    )
                    repository.updateNote(updatedNote)
                }

                is PDFExtractionResult.Error -> {
                    Log.e(TAG, "PDF extraction failed: ${extractionResult.message}")

                    // Mark as failed but still save
                    val category = repository.getOrCreateCategory("Documents")
                    val updatedNote = note.copy(
                        summary = "PDF could not be analyzed: ${extractionResult.message}",
                        whySaved = "Document saved",
                        categoryId = category.id,
                        categoryName = category.name,
                        processingStatus = ProcessingStatus.FAILED,
                        updatedAt = System.currentTimeMillis()
                    )
                    repository.updateNote(updatedNote)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing PDF: ${e.message}", e)

            val category = repository.getOrCreateCategory("Documents")
            val updatedNote = note.copy(
                summary = "Error processing PDF",
                whySaved = "Document saved",
                categoryId = category.id,
                categoryName = category.name,
                processingStatus = ProcessingStatus.FAILED,
                updatedAt = System.currentTimeMillis()
            )
            repository.updateNote(updatedNote)
        } finally {
            _isProcessing.value = false
        }
    }

    /**
     * Generate a mock AI response for offline fallback.
     * Delegates to ContentTypeDetector for efficient pattern matching.
     *
     * @param note The note to generate a response for
     * @return Triple of (category tag, summary, intent)
     */
    private fun generateMockAiResponse(note: Note): Triple<String, String, String> =
        ContentTypeDetector.generateMockAiResponse(note.type, note.content)

    // API Key Management
    fun addApiKey(provider: AIProvider, apiKey: String) {
        securePreferences.addProviderKey(provider, apiKey)
    }

    fun removeApiKey(provider: AIProvider, apiKey: String) {
        securePreferences.removeProviderKey(provider, apiKey)
    }

    fun updateApiKey(provider: AIProvider, oldKey: String, newKey: String) {
        securePreferences.updateProviderKey(provider, oldKey, newKey)
    }

    fun setProviderEnabled(provider: AIProvider, enabled: Boolean) {
        securePreferences.setProviderEnabled(provider, enabled)
    }

    fun setSelectedModel(provider: AIProvider, model: String) {
        securePreferences.setSelectedModel(provider, model)
    }

    fun testApiKey(provider: AIProvider, apiKey: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val isValid = aiService.testApiKey(provider, apiKey)
            onResult(isValid)
        }
    }

    // Theme Management
    val isDarkTheme: StateFlow<Boolean> = securePreferences.isDarkTheme

    fun setDarkTheme(isDark: Boolean) {
        securePreferences.setDarkTheme(isDark)
    }

    // Cache Management
    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            _cacheSizeBytes.value = cacheManager.getCacheSize()
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            _isClearingCache.value = true
            try {
                cacheManager.clearCache()
                _cacheSizeBytes.value = 0L
                Log.d(TAG, "Cache cleared successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear cache: ${e.message}")
            } finally {
                _isClearingCache.value = false
            }
        }
    }

    // User Category Creation
    fun createUserCategory(name: String) {
        viewModelScope.launch {
            val category = Category(
                name = name,
                isAiGenerated = false,  // User-created category
                noteCount = 0
            )
            repository.insertCategory(category)
        }
    }

    // Delete Category
    fun deleteCategory(category: Category) {
        viewModelScope.launch {
            // First, uncategorize all notes in this category (both active and archived)
            val allNotesInCategory = notes.value.filter { it.categoryId == category.id } +
                archivedNotes.value.filter { it.categoryId == category.id }

            allNotesInCategory.forEach { note ->
                repository.updateNote(note.copy(
                    categoryId = null,
                    categoryName = null,
                    updatedAt = System.currentTimeMillis()
                ))
            }
            // Then delete the category
            repository.deleteCategory(category)
        }
    }

    // PIN Management
    fun setPin(pin: String) {
        securePreferences.setPin(pin)
    }

    fun verifyPin(pin: String): Boolean {
        return securePreferences.verifyPin(pin)
    }

    fun changePin(oldPin: String, newPin: String): Boolean {
        return securePreferences.changePin(oldPin, newPin)
    }

    fun clearPin() {
        securePreferences.clearPin()
    }

    fun isPinConfigured(): Boolean {
        return securePreferences.isPinConfigured()
    }

    fun isFirstLaunch(): Boolean {
        return securePreferences.isFirstLaunch()
    }

    fun setFirstLaunchComplete() {
        securePreferences.setFirstLaunchComplete()
    }

    // ==================== Chat Mode & Agent Functionality ====================

    /**
     * Update the current input text from UI
     * Used for contextual shake detection
     */
    fun updateInputText(text: String) {
        _currentInputText.value = text
    }

    /**
     * Update the current input attachments from UI
     * Used for contextual shake detection (attachments should trigger privacy mode)
     */
    fun updateInputAttachments(attachments: List<Attachment>) {
        _currentInputAttachments.value = attachments
    }

    /**
     * Toggle AI exclusion for the pending note
     * Called when shaking while typing
     */
    fun togglePendingNoteAiExclusion() {
        _pendingNoteAiExcluded.value = !_pendingNoteAiExcluded.value
        Log.d(TAG, "AI exclusion toggled: ${_pendingNoteAiExcluded.value}")
    }

    /**
     * Reset pending note state after submission
     */
    fun resetPendingNoteState() {
        _pendingNoteAiExcluded.value = false
        _currentInputText.value = ""
        _currentInputAttachments.value = emptyList()
    }

    /**
     * Initialize the shake detector with contextual behavior
     * Call this from MainActivity.onCreate()
     *
     * Shake behavior:
     * - If input has text → toggle AI exclusion for current note
     * - If input is empty → toggle chat mode
     */
    fun initShakeDetector(context: Context) {
        shakeDetector = ShakeDetector(context) {
            handleShake()
        }
        Log.d(TAG, "Shake detector initialized with contextual handler")
    }

    /**
     * Handle shake gesture contextually
     * Priority: Share mode > Chat mode > Input content (text OR attachments) > Empty input
     */
    private fun handleShake() {
        when {
            // Priority 1: During share -> toggle full privacy mode
            shareFlowManager.isInShareMode() -> {
                toggleShareFullPrivacy()
                Log.d(TAG, "Shake: Toggled full privacy mode during share")
            }
            // Priority 2: In chat mode -> just exit chat mode (no AI exclusion)
            chatManager.isChatMode.value -> {
                toggleChatMode()
                Log.d(TAG, "Shake: Exited chat mode")
            }
            // Priority 3: Normal mode + Input has content (text OR attachments) -> toggle AI exclusion
            _currentInputText.value.isNotBlank() || _currentInputAttachments.value.isNotEmpty() -> {
                togglePendingNoteAiExclusion()
                Log.d(TAG, "Shake: Toggled AI exclusion (input has text or attachments)")
            }
            // Priority 4: Normal mode + Input completely empty -> enter chat mode
            else -> {
                toggleChatMode()
                Log.d(TAG, "Shake: Entered chat mode (input empty)")
            }
        }
    }

    /**
     * Toggle full privacy mode for share flow (delegated to ShareFlowManager)
     */
    fun toggleShareFullPrivacy() {
        shareFlowManager.toggleFullPrivacy()
    }

    /**
     * Start shake detection - call from Activity.onResume()
     */
    fun startShakeDetection() {
        shakeDetector?.start()
    }

    /**
     * Stop shake detection - call from Activity.onPause()
     */
    fun stopShakeDetection() {
        shakeDetector?.stop()
    }

    /**
     * Toggle between note input mode and chat mode (delegated to ChatManager)
     */
    fun toggleChatMode() {
        chatManager.toggleChatMode()
    }

    /**
     * Exit chat mode and return to note input mode (delegated to ChatManager)
     */
    fun exitChatMode() {
        chatManager.exitChatMode()
    }

    /**
     * Create a new chat session (delegated to ChatManager)
     */
    fun createNewChatSession() {
        chatManager.createNewChatSession()
    }

    /**
     * Switch to a different chat session (delegated to ChatManager)
     */
    fun switchToChatSession(sessionId: String) {
        chatManager.switchToChatSession(sessionId)
    }

    /**
     * Delete a chat session (delegated to ChatManager)
     */
    fun deleteChatSession(sessionId: String) {
        chatManager.deleteChatSession(sessionId)
    }

    /**
     * Clear current chat history (delegated to ChatManager)
     */
    fun clearChatHistory() {
        chatManager.clearChatHistory()
    }

    /**
     * Send a message in chat mode
     * Processes the message through the agent and executes any resulting actions
     */
    fun sendChatMessage(content: String, attachments: List<Attachment> = emptyList()) {
        if (content.isBlank() && attachments.isEmpty()) return

        viewModelScope.launch {
            chatManager.setProcessing(true)
            chatManager.resetApiCallFlag()

            // Ensure we have a session
            chatManager.ensureSession()

            // Add user message to chat history
            val userMessage = chatManager.addUserMessage(content, attachments)

            try {
                // ============================================================================
                // ABSOLUTE SECURITY BARRIER - PrivacyGuard is the ONLY way AI accesses notes
                // Private notes are INVISIBLE to AI - they DO NOT EXIST
                // ============================================================================
                val aiAccessibleNotes = PrivacyGuard.getAiVisibleNotes(notes.value)

                // Process through agent service
                val response = agentService.processUserMessage(
                    userMessage = content,
                    attachments = attachments,
                    chatHistory = chatManager.chatMessages.value,
                    allNotes = aiAccessibleNotes,
                    allCategories = categories.value
                )

                // ============================================================================
                // SECURITY: Sanitize response before adding to chat history
                // Filter out any private note IDs from referencedNoteIds
                // ============================================================================
                val sanitizedResponse = response.copy(
                    referencedNoteIds = response.referencedNoteIds.filter { noteId ->
                        // Only keep references to notes that are AI-accessible
                        val note = notes.value.find { it.id == noteId }
                        note != null && PrivacyGuard.isAiAccessible(note)
                    }
                )

                // Add sanitized assistant response to chat history
                chatManager.addAssistantMessage(sanitizedResponse)

                // Mark API call as successful (we got a real response)
                chatManager.markApiCallSuccessful()

                // Execute any actions returned by the agent and capture affected IDs
                if (response.hasActions) {
                    val updatedActions = response.executedActions.map { actionResult ->
                        val affectedIds = executeAgentAction(actionResult, content)
                        actionResult.copy(affectedNoteIds = affectedIds)
                    }
                    
                    // Update state with results including note IDs
                    chatManager.updateAssistantMessageActions(sanitizedResponse.id, updatedActions)
                    
                    // Save messages to persistent storage (including the updated actions)
                    chatManager.saveMessagePair(
                        userMessage = userMessage,
                        assistantMessage = sanitizedResponse.copy(executedActions = updatedActions),
                        hasApiKeys = securePreferences.hasAnyApiKeys()
                    )
                } else {
                     // Save without actions update
                    chatManager.saveMessagePair(
                        userMessage = userMessage,
                        assistantMessage = sanitizedResponse,
                        hasApiKeys = securePreferences.hasAnyApiKeys()
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing chat message: ${e.message}", e)

                // Add error message to chat (not persisted)
                val errorMessage = ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "I encountered an error processing your request. Please try again."
                )
                chatManager.addAssistantMessage(errorMessage)
            } finally {
                chatManager.setProcessing(false)
            }
        }
    }

    /**
     * Execute an agent action based on the parsed response
     * Returns list of affected note IDs
     */
    private suspend fun executeAgentAction(actionResult: AgentActionResult, originalMessage: String): List<String> {
        Log.d(TAG, "Executing agent action: ${actionResult.action}")

        // Get the actual action from the last AI response
        val chatHistory = chatManager.chatMessages.value
        val lastAssistantMessage = chatHistory.lastOrNull { it.role == ChatRole.ASSISTANT }
        if (lastAssistantMessage == null) return emptyList()

        // Parse the action from the response
        val action = agentService.getActionFromResponse(
            lastAssistantMessage.content
        ) ?: return emptyList()

        return when (action) {
            is AgentAction.CreateNote -> {
                // Detect correct type and title if not provided
                val detectedType = detectContentType(action.content)
                val title = action.title ?: extractTitle(action.content, detectedType)
                
                val note = Note(
                    title = title,
                    content = action.content,
                    type = detectedType, // Use detected type instead of hardcoded BRAIN_DUMP
                    processingStatus = if (action.category != null) ProcessingStatus.COMPLETED else ProcessingStatus.PROCESSING,
                    isAiCreated = true
                )
                repository.insertNote(note)

                // If category is specified, use it; otherwise let AI process
                if (action.category != null) {
                    val category = repository.getOrCreateCategory(action.category)
                    repository.updateNote(note.copy(
                        categoryId = category.id,
                        categoryName = category.name,
                        processingStatus = ProcessingStatus.COMPLETED
                    ))
                } else {
                    // Launch processing safely - if it fails, fallback to simple storage
                    try {
                        simulateAiProcessing(note)
                    } catch (e: Exception) {
                        Log.e(TAG, "AI processing failed for created note: ${e.message}")
                        // Fallback: Ensure note is visible even if AI fails
                        repository.updateNote(note.copy(
                            processingStatus = ProcessingStatus.COMPLETED
                        ))
                    }
                }
                Log.d(TAG, "Created note: $title")
                listOf(note.id)
            }

            is AgentAction.SearchNotes -> {
                // Search is handled in the response - notes are already selected by AgentService
                Log.d(TAG, "Search executed for: ${action.query}")
                emptyList()
            }

            is AgentAction.DeleteNote -> {
                // ============================================================================
                // SECURITY: PrivacyGuard - private notes are INVISIBLE to AI
                // ============================================================================
                val aiAccessibleNotes = PrivacyGuard.filterForAiModification(notes.value)
                val noteToDelete = if (action.noteId != null) {
                    PrivacyGuard.findByIdForAi(notes.value, action.noteId)
                } else if (action.description != null) {
                    agentService.findNoteByDescription(action.description, aiAccessibleNotes)
                } else null

                noteToDelete?.let {
                    if (PrivacyGuard.canAiProcess(it)) {
                        repository.deleteNote(it)
                        Log.d(TAG, "Deleted note: ${it.title}")
                        listOf(it.id)
                    } else emptyList()
                } ?: emptyList()
            }

            is AgentAction.ArchiveNote -> {
                // ============================================================================
                // SECURITY: PrivacyGuard - private notes are INVISIBLE to AI
                // ============================================================================
                val aiAccessibleNotes = PrivacyGuard.filterForAiModification(notes.value)
                val noteToArchive = if (action.noteId != null) {
                    PrivacyGuard.findByIdForAi(notes.value, action.noteId)
                } else if (action.description != null) {
                    agentService.findNoteByDescription(action.description, aiAccessibleNotes)
                } else null

                noteToArchive?.let {
                    if (PrivacyGuard.canAiProcess(it)) {
                        repository.archiveNote(it.id)
                        Log.d(TAG, "Archived note: ${it.title}")
                        listOf(it.id)
                    } else emptyList()
                } ?: emptyList()
            }

            is AgentAction.UnarchiveNote -> {
                // ============================================================================
                // SECURITY: PrivacyGuard - private notes are INVISIBLE to AI
                // ============================================================================
                val aiAccessibleArchived = PrivacyGuard.filterForAiModification(archivedNotes.value)
                val noteToUnarchive = if (action.noteId != null) {
                    PrivacyGuard.findByIdForAi(archivedNotes.value, action.noteId)
                } else if (action.description != null) {
                    agentService.findNoteByDescription(action.description, aiAccessibleArchived)
                } else null

                noteToUnarchive?.let {
                    if (PrivacyGuard.canAiProcess(it)) {
                        repository.unarchiveNote(it.id)
                        Log.d(TAG, "Unarchived note: ${it.title}")
                        listOf(it.id)
                    } else emptyList()
                } ?: emptyList()
            }

            is AgentAction.UpdateNote -> {
                // ============================================================================
                // SECURITY: PrivacyGuard - private notes are INVISIBLE to AI
                // Double validation: findByIdForAi AND canAiProcess
                // ============================================================================
                val noteToUpdate = PrivacyGuard.findByIdForAi(notes.value, action.noteId)
                noteToUpdate?.let { note ->
                    // SECURITY: Secondary validation - ensure note can be processed
                    if (!PrivacyGuard.canAiProcess(note)) {
                        Log.w(TAG, "SECURITY: Blocked AI update on private note")
                        return@let emptyList()
                    }

                    val updatedNote = note.copy(
                        title = action.newTitle ?: note.title,
                        content = action.newContent ?: note.content,
                        categoryName = action.newCategory ?: note.categoryName,
                        updatedAt = System.currentTimeMillis()
                    )

                    // Update category if changed
                    if (action.newCategory != null && action.newCategory != note.categoryName) {
                        val category = repository.getOrCreateCategory(action.newCategory)
                        repository.updateNote(updatedNote.copy(categoryId = category.id))
                    } else {
                        repository.updateNote(updatedNote)
                    }
                    Log.d(TAG, "Updated note: ${updatedNote.title}")
                    listOf(note.id)
                } ?: emptyList()
            }

            is AgentAction.ListCategories -> {
                // Categories are included in the response context
                Log.d(TAG, "Listed ${categories.value.size} categories")
                emptyList()
            }

            is AgentAction.GetCategoryNotes -> {
                // Notes for category are included in the response
                Log.d(TAG, "Got notes for category: ${action.categoryName}")
                emptyList()
            }

            is AgentAction.AnswerQuestion -> {
                // Answer is in the response - no action needed
                Log.d(TAG, "Answered question: ${action.question}")
                emptyList()
            }

            is AgentAction.SummarizeNote -> {
                // ============================================================================
                // SECURITY: PrivacyGuard - private notes are INVISIBLE to AI
                // Double validation: findByIdForAi AND canAiProcess
                // ============================================================================
                val noteToSummarize = PrivacyGuard.findByIdForAi(notes.value, action.noteId)
                noteToSummarize?.let { note ->
                    // SECURITY: Secondary validation - ensure note can be processed
                    if (!PrivacyGuard.canAiProcess(note)) {
                        Log.w(TAG, "SECURITY: Blocked AI summarization on private note")
                        return@let emptyList()
                    }
                    // Update the note with AI-generated summary (already in response)
                    Log.d(TAG, "Summarized note: ${note.title}")
                    listOf(note.id)
                } ?: emptyList()
            }

            is AgentAction.SuggestActions -> {
                // Suggestions are in the response - no execution needed
                Log.d(TAG, "Suggested actions based on context: ${action.context.take(50)}...")
                emptyList()
            }

            is AgentAction.AddTodos -> {
                // ============================================================================
                // SECURITY: PrivacyGuard - private notes are INVISIBLE to AI
                // Double validation: findByIdForAi AND canAiProcess
                // ============================================================================
                val noteToUpdate = PrivacyGuard.findByIdForAi(notes.value, action.noteId)
                noteToUpdate?.let { note ->
                    // SECURITY: Secondary validation
                    if (!PrivacyGuard.canAiProcess(note)) {
                        Log.w(TAG, "SECURITY: Blocked AI AddTodos on private note")
                        return@let emptyList()
                    }
                    val currentTodos = note.getTodos().toMutableList()
                    val newTodos = action.todos.map { text ->
                        TodoItem(text = text)
                    }
                    currentTodos.addAll(newTodos)
                    val updatedNote = note.withTodos(currentTodos)
                    viewModelScope.launch {
                        repository.updateNote(updatedNote)
                    }
                    Log.d(TAG, "Added ${action.todos.size} todos to note: ${note.title}")
                    listOf(note.id)
                } ?: emptyList()
            }

            is AgentAction.ToggleTodo -> {
                // ============================================================================
                // SECURITY: PrivacyGuard - private notes are INVISIBLE to AI
                // Double validation: findByIdForAi AND canAiProcess
                // ============================================================================
                val noteToUpdate = PrivacyGuard.findByIdForAi(notes.value, action.noteId)
                noteToUpdate?.let { note ->
                    // SECURITY: Secondary validation
                    if (!PrivacyGuard.canAiProcess(note)) {
                        Log.w(TAG, "SECURITY: Blocked AI ToggleTodo on private note")
                        return@let emptyList()
                    }
                    val todos = note.getTodos().toMutableList()
                    val todoIndex = todos.indexOfFirst { it.id == action.todoId }
                    if (todoIndex >= 0) {
                        val todo = todos[todoIndex]
                        todos[todoIndex] = todo.copy(isCompleted = !todo.isCompleted)
                        val updatedNote = note.withTodos(todos)
                        viewModelScope.launch {
                            repository.updateNote(updatedNote)
                        }
                        Log.d(TAG, "Toggled todo '${todo.text}' to ${!todo.isCompleted}")
                        listOf(note.id)
                    } else emptyList()
                } ?: emptyList()
            }

            is AgentAction.DeleteTodo -> {
                // ============================================================================
                // SECURITY: PrivacyGuard - private notes are INVISIBLE to AI
                // Double validation: findByIdForAi AND canAiProcess
                // ============================================================================
                val noteToUpdate = PrivacyGuard.findByIdForAi(notes.value, action.noteId)
                noteToUpdate?.let { note ->
                    // SECURITY: Secondary validation
                    if (!PrivacyGuard.canAiProcess(note)) {
                        Log.w(TAG, "SECURITY: Blocked AI DeleteTodo on private note")
                        return@let emptyList()
                    }
                    val todos = note.getTodos().toMutableList()
                    val removed = todos.removeAll { it.id == action.todoId }
                    if (removed) {
                        val updatedNote = note.withTodos(todos)
                        viewModelScope.launch {
                            repository.updateNote(updatedNote)
                        }
                        Log.d(TAG, "Deleted todo from note: ${note.title}")
                        listOf(note.id)
                    } else emptyList()
                } ?: emptyList()
            }

            is AgentAction.BatchActions -> {
                // Execute each action in the batch
                action.actions.flatMap { subAction ->
                    executeAgentAction(
                        AgentActionResult(
                            action = subAction.javaClass.simpleName,
                            success = true,
                            resultSummary = "Batch action"
                        ),
                        originalMessage
                    )
                }
            }
        }
    }

    /**
     * Clean up resources when ViewModel is cleared
     */
    override fun onCleared() {
        super.onCleared()
        shakeDetector?.stop()
        shakeDetector = null
    }
}
