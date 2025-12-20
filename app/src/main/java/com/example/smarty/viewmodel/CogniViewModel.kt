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
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatRole
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteAttachment
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.model.TodoItem
import com.example.smarty.data.model.AgentState
import com.example.smarty.data.model.getAllAttachmentUris
import com.example.smarty.data.model.getAttachments
import com.example.smarty.data.model.getTodos
import com.example.smarty.data.model.withAttachments
import com.example.smarty.data.model.withTodos
import com.example.smarty.data.remote.AgentService
import com.example.smarty.data.remote.AgentService.AgentMode
import com.example.smarty.data.remote.AgentChatResponse
import com.example.smarty.data.remote.AIService
import com.example.smarty.data.remote.providers.TavilySearchProvider
import com.example.smarty.ui.components.DynamicIslandState
import com.example.smarty.ui.components.PendingShareData
import com.google.gson.Gson
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
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
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.ViewModelProvider
import androidx.savedstate.SavedStateRegistryOwner

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

class CogniViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    // SavedStateHandle keys for state preservation across process death (BUG-053)
    companion object {
        private const val TAG = "CogniViewModel"
        private const val KEY_SELECTED_NOTE_ID = "selectedNoteId"
        private const val KEY_SELECTED_CATEGORY_ID = "selectedCategoryId"
        private const val KEY_IS_CHAT_MODE = "isChatMode"
    }

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

    // Web search provider for agent actions
    private val tavilySearchProvider: TavilySearchProvider by lazy {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        TavilySearchProvider(httpClient, Gson())
    }

    // Chat repository for persistence
    private val chatRepository = ChatRepository(database.chatDao())

    // Calendar DAO for event management
    private val calendarDao = database.calendarDao()

    // Shake detector for toggling chat mode
    private var shakeDetector: ShakeDetector? = null

    // Mutex for thread-safe note operations (BUG-016 fix)
    private val noteOperationMutex = Mutex()

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

    // Microphone listening state (hoisted from UI for shake detection)
    private val _isMicListening = MutableStateFlow(false)
    val isMicListening: StateFlow<Boolean> = _isMicListening.asStateFlow()

    // Shared flow for speech results to be consumed by screens
    private val _speechResults = kotlinx.coroutines.flow.MutableSharedFlow<String>()
    val speechResults = _speechResults.asSharedFlow()

    fun onSpeechResult(text: String) {
        viewModelScope.launch {
            _speechResults.emit(text)
        }
    }

    // ==================== AGENT STATE - Track AI Agent Status ====================

    private val _agentState = MutableStateFlow<DynamicIslandState>(DynamicIslandState.Contracted)
    val agentState: StateFlow<DynamicIslandState> = _agentState.asStateFlow()

    // Timer job for updating elapsed time during tool execution
    private var agentTimerJob: kotlinx.coroutines.Job? = null

    // Track tools used in current agent run
    private var toolsUsedInRun = mutableListOf<String>()
    private var agentRunStartTime = 0L

    /**
     * Update agent state and optionally start/stop timer
     */
    private fun setAgentState(state: DynamicIslandState) {
        _agentState.value = state

        // Manage timer based on state
        when (state) {
            is DynamicIslandState.AgentExecutingTool,
            is DynamicIslandState.AgentWaitingForResult -> {
                startAgentTimer(state)
            }
            is DynamicIslandState.Contracted,
            is DynamicIslandState.AgentCompleted,
            is DynamicIslandState.AgentError -> {
                stopAgentTimer()
            }
            else -> {} // Keep timer running for other states
        }
    }

    /**
     * Start timer to update elapsed seconds during tool execution
     */
    private fun startAgentTimer(initialState: DynamicIslandState) {
        agentTimerJob?.cancel()
        val startTime = System.currentTimeMillis()

        agentTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000) // Update every second
                val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()

                // Update state with new elapsed time
                when (val current = _agentState.value) {
                    is DynamicIslandState.AgentExecutingTool -> {
                        _agentState.value = current.copy(elapsedSeconds = elapsed)
                    }
                    is DynamicIslandState.AgentWaitingForResult -> {
                        _agentState.value = current.copy(elapsedSeconds = elapsed)
                    }
                    else -> break // Stop timer if state changed
                }

                // Check timeout (45 seconds for tools)
                if (elapsed > 45) {
                    Log.w(TAG, "Tool execution timeout after ${elapsed}s")
                    break
                }
            }
        }
    }

    /**
     * Stop the agent timer
     */
    private fun stopAgentTimer() {
        agentTimerJob?.cancel()
        agentTimerJob = null
    }

    /**
     * Show completion state briefly then contract
     */
    private fun showAgentCompleted(toolsUsed: Int) {
        val totalTime = if (agentRunStartTime > 0) {
            ((System.currentTimeMillis() - agentRunStartTime) / 1000).toInt()
        } else 0

        setAgentState(DynamicIslandState.AgentCompleted(toolsUsed, totalTime))

        viewModelScope.launch {
            delay(2000) // Show for 2 seconds
            setAgentState(DynamicIslandState.Contracted)
        }
    }

    // Expose secure preferences state for UI
    val geminiKeys: StateFlow<List<String>> = securePreferences.geminiKeys
    val huggingFaceKeys: StateFlow<List<String>> = securePreferences.huggingFaceKeys
    val providerConfigs: StateFlow<Map<AIProvider, AIProviderConfig>> = securePreferences.providerConfigs
    val isPinSet: StateFlow<Boolean> = securePreferences.isPinSet

    fun setProviderPriority(priority: List<AIProvider>) {
        securePreferences.setProviderPriority(priority)
    }

    // Cache management
    private val cacheManager = CacheManager.getInstance(application)
    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()
    private val _isClearingCache = MutableStateFlow(false)
    val isClearingCache: StateFlow<Boolean> = _isClearingCache.asStateFlow()

    // Audio playback request from AI agent (observed by MainActivity to trigger playback)
    private val _pendingAudioPlayback = MutableStateFlow<AudioTrack?>(null)
    val pendingAudioPlayback: StateFlow<AudioTrack?> = _pendingAudioPlayback.asStateFlow()

    fun clearPendingAudioPlayback() {
        _pendingAudioPlayback.value = null
    }

    val notes = repository.getAllNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories = repository.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Calendar events
    val calendarEvents = calendarDao.getAllEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedNote = MutableStateFlow<Note?>(null)
    val selectedNote: StateFlow<Note?> = _selectedNote.asStateFlow()

    private val _selectedCategory = MutableStateFlow<Category?>(null)
    val selectedCategory: StateFlow<Category?> = _selectedCategory.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    // Transient Dynamic Island state (e.g., category changes, successes)
    private val _transientIslandState = MutableStateFlow<com.example.smarty.ui.components.DynamicIslandState?>(null)
    val transientIslandState: StateFlow<com.example.smarty.ui.components.DynamicIslandState?> = _transientIslandState.asStateFlow()

    private var islandJob: kotlinx.coroutines.Job? = null

    /**
     * Show a transient state on the Dynamic Island for a set duration
     */
    fun showTransientIsland(state: com.example.smarty.ui.components.DynamicIslandState, durationMs: Long = 2500L) {
        islandJob?.cancel()
        islandJob = viewModelScope.launch {
            _transientIslandState.value = state
            delay(durationMs)
            _transientIslandState.value = null
        }
    }

    init {
        // Sync category counts on app start to fix any existing mismatches
        viewModelScope.launch {
            repository.syncAllCategoryCounts()
        }
        // Initialize chat manager (loads sessions and cleans up empty ones)
        chatManager.initialize()

        // Restore state from SavedStateHandle after process death (BUG-053)
        restoreState()
    }

    /**
     * Restore navigation state from SavedStateHandle after process death.
     * This preserves selectedNote, selectedCategory, and chatMode across restarts.
     */
    private fun restoreState() {
        viewModelScope.launch {
            // Restore selected note by ID
            savedStateHandle.get<String>(KEY_SELECTED_NOTE_ID)?.let { noteId ->
                val note = repository.getNoteById(noteId)
                if (note != null) {
                    _selectedNote.value = note
                    Log.d(TAG, "Restored selectedNote: ${note.id}")
                }
            }

            // Restore selected category by ID
            savedStateHandle.get<String>(KEY_SELECTED_CATEGORY_ID)?.let { categoryId ->
                val category = repository.getCategoryById(categoryId)
                if (category != null) {
                    _selectedCategory.value = category
                    Log.d(TAG, "Restored selectedCategory: ${category.id}")
                }
            }

            // Restore chat mode state
            savedStateHandle.get<Boolean>(KEY_IS_CHAT_MODE)?.let { wasChatMode ->
                if (wasChatMode) {
                    chatManager.enterChatMode()
                    Log.d(TAG, "Restored chat mode")
                }
            }
        }
    }

    // Public sync function for manual recalculation
    fun syncCategoryCounts() {
        viewModelScope.launch {
            repository.syncAllCategoryCounts()
        }
    }

    fun selectNote(note: Note?) {
        _selectedNote.value = note
        // Persist to SavedStateHandle for process death recovery (BUG-053)
        savedStateHandle[KEY_SELECTED_NOTE_ID] = note?.id
    }

    fun selectCategory(category: Category?) {
        _selectedCategory.value = category
        // Persist to SavedStateHandle for process death recovery (BUG-053)
        savedStateHandle[KEY_SELECTED_CATEGORY_ID] = category?.id
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
                    // 1. OPTIMISTIC UPDATE: Insert PENDING note immediately with original attachments
                    // This triggers the UI shimmer instantly while we do heavy compression in background
                    val primaryOriginal = attachments[0]
                    val type = detectTypeFromMime(primaryOriginal.mimeType)
                    
                    val tempAttachments = attachments.map { 
                        NoteAttachment(
                            uri = it.uri.toString(),
                            fileName = it.fileName,
                            mimeType = it.mimeType,
                            fileSize = it.fileSize
                        )
                    }

                    val title = when {
                        content.isNotBlank() -> extractTitle(content, type)
                        attachments.size > 1 -> "${attachments.size} ${getTypePluralName(type)}"
                        else -> primaryOriginal.fileName
                    }
                    
                    val initialContent = if (content.isNotBlank()) {
                        content
                    } else {
                        buildMultipleAttachmentsDescription(tempAttachments)
                    }

                    // Create and INSERT PENDING note instantly
                    val initialNote = Note(
                        title = title,
                        content = initialContent,
                        fileUri = primaryOriginal.uri.toString(),
                        fileName = primaryOriginal.fileName,
                        fileMimeType = primaryOriginal.mimeType,
                        fileSize = primaryOriginal.fileSize,
                        imageUri = if (type == NoteType.IMAGE) primaryOriginal.uri.toString() else null,
                        type = type,
                        processingStatus = ProcessingStatus.PENDING, // Triggers shimmer
                        excludeFromAiChat = aiExcluded
                    ).withAttachments(tempAttachments)

                    // Insert immediately to update UI
                    repository.insertNote(initialNote)

                    // 2. BACKGROUND WORK: Copy and compress all attachments
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

                    // 3. UPDATE: Update note with optimized files and correct status
                    val primary = primaryProcessed ?: attachments[0] 
                    val shouldProcess = shouldAnalyze(type)
                    
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
                        simulateAiProcessing(updatedNote)
                    } else {
                        storeWithoutAnalysis(updatedNote)
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
            try {
                noteOperationMutex.withLock {
                    repository.archiveNote(noteId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error archiving note: ${e.message}", e)
            }
        }
    }

    fun unarchiveNote(noteId: String) {
        viewModelScope.launch {
            try {
                noteOperationMutex.withLock {
                    repository.unarchiveNote(noteId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error unarchiving note: ${e.message}", e)
            }
        }
    }

    // Archived notes for archive screen
    val archivedNotes = repository.getArchivedNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteNote(note: Note) {
        viewModelScope.launch {
            try {
                noteOperationMutex.withLock {
                    // Clean up attachment files (only deletes app's copies, not original files)
                    val context = getApplication<Application>()
                    note.getAllAttachmentUris().forEach { uri ->
                        FileStorageHelper.deleteFile(context, uri)
                    }
                    // Then delete database record
                    repository.deleteNote(note)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting note: ${e.message}", e)
            }
        }
    }

    fun deleteNoteById(noteId: String) {
        viewModelScope.launch {
            try {
                // Search in both active notes and archived notes
                val note = notes.value.find { it.id == noteId }
                    ?: archivedNotes.value.find { it.id == noteId }
                note?.let { deleteNote(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting note by ID: ${e.message}", e)
            }
        }
    }

    fun updateNoteTodos(noteId: String, todos: List<TodoItem>) {
        viewModelScope.launch {
            try {
                noteOperationMutex.withLock {
                    // Search in both active notes and archived notes
                    val note = notes.value.find { it.id == noteId }
                        ?: archivedNotes.value.find { it.id == noteId }
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

    // Tavily Web Search API Management
    private val _tavilyApiKey = MutableStateFlow(securePreferences.getTavilyApiKey())
    val tavilyApiKey: StateFlow<String?> = _tavilyApiKey.asStateFlow()

    fun setTavilyApiKey(key: String?) {
        securePreferences.setTavilyApiKey(key)
        _tavilyApiKey.value = key
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

    // Delete Category (BUG-028: Proper cascade cleanup)
    fun deleteCategory(category: Category) {
        viewModelScope.launch {
            try {
                // Use atomic cleanup method that handles all notes via SQL UPDATE
                // This is more reliable than filtering from StateFlow which may be stale
                repository.deleteCategoryWithCleanup(category)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete category: ${category.name}", e)
            }
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
     * Update microphone listening state from UI
     * Used for shake detection - mic active should trigger privacy mode
     */
    fun updateMicListening(isListening: Boolean) {
        _isMicListening.value = isListening
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
            // Priority 1: During share flow -> toggle full privacy mode
            shareFlowManager.isInShareMode() -> {
                toggleShareFullPrivacy()
                Log.d(TAG, "Shake: Toggled full privacy mode during share")
            }
            // Priority 2: Mic is listening OR has text OR has attachments -> toggle AI exclusion/privacy
            _isMicListening.value || _currentInputText.value.isNotBlank() || _currentInputAttachments.value.isNotEmpty() -> {
                togglePendingNoteAiExclusion()
                Log.d(TAG, "Shake: Toggled AI exclusion (active content)")
            }
            // Priority 3: Completely empty (no mic, no text, no attachments) -> toggle chat mode
            else -> {
                toggleChatMode()
                Log.d(TAG, "Shake: Toggled chat mode (empty state)")
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
        // Persist chat mode state for process death recovery (BUG-053)
        savedStateHandle[KEY_IS_CHAT_MODE] = !isChatMode.value  // Will be opposite after toggle
    }

    /**
     * Exit chat mode and return to note input mode (delegated to ChatManager)
     */
    fun exitChatMode() {
        chatManager.exitChatMode()
        // Persist chat mode state for process death recovery (BUG-053)
        savedStateHandle[KEY_IS_CHAT_MODE] = false
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
     * Send a message in chat mode with proper AI agent loop.
     *
     * ARCHITECTURE: This implements a real AI agent that:
     * 1. Sends user message to LLM
     * 2. LLM returns response with optional action
     * 3. If action exists, execute it and get result
     * 4. Feed result back to LLM for continuation
     * 5. Loop until no more actions or max iterations reached
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
                // ============================================================================
                val aiAccessibleNotes = PrivacyGuard.getAiVisibleNotes(notes.value)
                val privateNotes = notes.value.filter { PrivacyGuard.isPrivate(it) }

                // ============================================================================
                // AGENT LOOP - Execute actions, WAIT for results, feed back to LLM
                // Uses DUAL-API architecture:
                // - REASONING API (1st key): Tool execution and reasoning iterations
                // - FINAL_RESPONSE API (2nd key): Final user-facing response
                // ============================================================================
                var currentMessage = content
                var currentAttachments = attachments
                var iterationCount = 0
                val maxIterations = 5 // Prevent infinite loops
                var toolResultsCollected = mutableListOf<String>() // Collect all tool results

                // Initialize agent run tracking
                toolsUsedInRun.clear()
                agentRunStartTime = System.currentTimeMillis()

                while (iterationCount < maxIterations) {
                    iterationCount++
                    Log.d(TAG, "Agent loop iteration $iterationCount (REASONING MODE)")

                    // ========== UPDATE UI: Agent is thinking ==========
                    setAgentState(DynamicIslandState.AgentThinking(
                        iteration = iterationCount,
                        message = if (iterationCount > 1) "Processing..." else "Thinking..."
                    ))

                    // Process through agent service using REASONING API (1st key)
                    val agentResponse = agentService.processUserMessage(
                        userMessage = currentMessage,
                        attachments = currentAttachments,
                        chatHistory = chatManager.chatMessages.value,
                        allNotes = aiAccessibleNotes,
                        allCategories = categories.value,
                        mode = AgentMode.REASONING  // Use first API key for reasoning
                    )

                    val response = agentResponse.message
                    val parsedAction = agentResponse.parsedAction

                    // Sanitize response
                    val sanitizedNoteIds = PrivacyGuard.sanitizeReferencedNoteIds(
                        response.referencedNoteIds,
                        notes.value
                    )
                    val sanitizedContent = PrivacyGuard.sanitizeResponseText(
                        response.content,
                        privateNotes
                    )

                    val sanitizedResponse = response.copy(
                        content = sanitizedContent,
                        referencedNoteIds = sanitizedNoteIds
                    )

                    // Check if we have an action to execute
                    if (parsedAction != null) {
                        val toolName = parsedAction.javaClass.simpleName
                        val toolDisplayName = AgentState.getToolDisplayName(parsedAction)
                        Log.d(TAG, "Executing action: $toolName ($toolDisplayName)")

                        // ========== UPDATE UI: Agent is executing tool ==========
                        setAgentState(DynamicIslandState.AgentExecutingTool(
                            toolName = toolName,
                            toolDisplayName = toolDisplayName,
                            elapsedSeconds = 0
                        ))

                        // Track tool usage
                        toolsUsedInRun.add(toolDisplayName)

                        // Execute the action directly (no re-parsing!)
                        val actionResult = executeActionWithResult(parsedAction)

                        // Update the action result in the response
                        val updatedActions = listOf(
                            AgentActionResult(
                                action = parsedAction.javaClass.simpleName,
                                success = actionResult.success,
                                resultSummary = actionResult.summary,
                                affectedNoteIds = actionResult.affectedNoteIds
                            )
                        )

                        val finalResponse = sanitizedResponse.copy(executedActions = updatedActions)

                        // Add response to chat
                        chatManager.addAssistantMessage(finalResponse)
                        chatManager.markApiCallSuccessful()

                        // If action needs continuation (like web search), WAIT for result and feed back to LLM
                        if (actionResult.needsContinuation && actionResult.resultData != null) {
                            Log.d(TAG, "Action needs continuation - WAITING for tool result")
                            Log.d(TAG, "Tool $toolName returned: ${actionResult.resultData.take(200)}...")

                            // ========== UPDATE UI: Agent is waiting for result ==========
                            setAgentState(DynamicIslandState.AgentWaitingForResult(
                                toolDisplayName = toolDisplayName,
                                elapsedSeconds = 0
                            ))

                            // Collect tool result
                            toolResultsCollected.add(
                                "Tool: ${parsedAction.javaClass.simpleName}\nResult: ${actionResult.resultData}"
                            )

                            // Feed result back to LLM for next reasoning step
                            currentMessage = buildString {
                                append("TOOL EXECUTION COMPLETED.\n\n")
                                append("Original user request: $content\n\n")
                                append("Tool executed: ${parsedAction.javaClass.simpleName}\n")
                                append("Tool result:\n${actionResult.resultData}\n\n")
                                append("Based on this result, either:\n")
                                append("1. Execute another tool if needed\n")
                                append("2. Provide a final response to the user incorporating the tool results")
                            }
                            currentAttachments = emptyList()

                            // Continue the loop to let LLM process the result
                            Log.d(TAG, "Feeding tool result back to LLM for next reasoning step...")
                            continue
                        }

                        // Save and exit loop if action doesn't need continuation
                        chatManager.saveMessagePair(
                            userMessage = userMessage,
                            assistantMessage = finalResponse,
                            hasApiKeys = securePreferences.hasAnyApiKeys()
                        )

                        // ========== UPDATE UI: Agent completed ==========
                        showAgentCompleted(toolsUsedInRun.size)
                        break
                    } else {
                        // No action - just a regular response, add and exit
                        chatManager.addAssistantMessage(sanitizedResponse)
                        chatManager.markApiCallSuccessful()
                        chatManager.saveMessagePair(
                            userMessage = userMessage,
                            assistantMessage = sanitizedResponse,
                            hasApiKeys = securePreferences.hasAnyApiKeys()
                        )

                        // ========== UPDATE UI: Agent completed (no tools) ==========
                        showAgentCompleted(toolsUsedInRun.size)
                        break
                    }
                }

                if (iterationCount >= maxIterations) {
                    Log.w(TAG, "Agent loop reached max iterations")
                    // Show completed even at max iterations
                    showAgentCompleted(toolsUsedInRun.size)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing chat message: ${e.message}", e)

                // ========== UPDATE UI: Agent error ==========
                setAgentState(DynamicIslandState.AgentError("Error occurred"))
                viewModelScope.launch {
                    delay(2000) // Show error for 2 seconds
                    setAgentState(DynamicIslandState.Contracted)
                }

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
     * Result of executing an agent action
     */
    data class ActionExecutionResult(
        val success: Boolean,
        val summary: String,
        val affectedNoteIds: List<String> = emptyList(),
        val needsContinuation: Boolean = false,
        val resultData: String? = null  // For actions that return data to feed back to LLM
    )

    /**
     * Execute an agent action and return structured result.
     * This is the core action executor that handles all action types.
     */
    private suspend fun executeActionWithResult(action: AgentAction): ActionExecutionResult {
        Log.d(TAG, "Executing action with result: ${action.javaClass.simpleName}")

        return when (action) {
            is AgentAction.WebSearch -> {
                // Web search returns results that should be fed back to LLM
                val results = executeAction(action)
                if (results.isNotEmpty()) {
                    // The results contain the search data
                    ActionExecutionResult(
                        success = true,
                        summary = "Web search completed",
                        needsContinuation = true,
                        resultData = results.joinToString("\n")
                    )
                } else {
                    ActionExecutionResult(
                        success = false,
                        summary = "Web search returned no results"
                    )
                }
            }

            is AgentAction.PlayAudio -> {
                // PlayAudio triggers UI - execute and report
                val affectedIds = executeAction(action)
                ActionExecutionResult(
                    success = affectedIds.isNotEmpty(),
                    summary = if (affectedIds.isNotEmpty()) "Audio playback started" else "Audio not found",
                    affectedNoteIds = affectedIds,
                    needsContinuation = false
                )
            }

            is AgentAction.CreateNote -> {
                val affectedIds = executeAction(action)
                ActionExecutionResult(
                    success = affectedIds.isNotEmpty(),
                    summary = "Note created",
                    affectedNoteIds = affectedIds,
                    needsContinuation = false
                )
            }

            is AgentAction.SearchNotes -> {
                // Search is informational - results already in context
                executeAction(action)
                ActionExecutionResult(
                    success = true,
                    summary = "Search completed",
                    needsContinuation = false
                )
            }

            is AgentAction.DeleteNote -> {
                val affectedIds = executeAction(action)
                ActionExecutionResult(
                    success = affectedIds.isNotEmpty(),
                    summary = if (affectedIds.isNotEmpty()) "Note deleted" else "Note not found",
                    affectedNoteIds = affectedIds,
                    needsContinuation = false
                )
            }

            is AgentAction.ArchiveNote -> {
                val affectedIds = executeAction(action)
                ActionExecutionResult(
                    success = affectedIds.isNotEmpty(),
                    summary = if (affectedIds.isNotEmpty()) "Note archived" else "Note not found",
                    affectedNoteIds = affectedIds,
                    needsContinuation = false
                )
            }

            is AgentAction.UnarchiveNote -> {
                val affectedIds = executeAction(action)
                ActionExecutionResult(
                    success = affectedIds.isNotEmpty(),
                    summary = if (affectedIds.isNotEmpty()) "Note unarchived" else "Note not found",
                    affectedNoteIds = affectedIds,
                    needsContinuation = false
                )
            }

            is AgentAction.UpdateNote -> {
                val affectedIds = executeAction(action)
                ActionExecutionResult(
                    success = affectedIds.isNotEmpty(),
                    summary = if (affectedIds.isNotEmpty()) "Note updated" else "Note not found",
                    affectedNoteIds = affectedIds,
                    needsContinuation = false
                )
            }

            is AgentAction.SummarizeNote -> {
                val affectedIds = executeAction(action)
                ActionExecutionResult(
                    success = affectedIds.isNotEmpty(),
                    summary = "Note summarized",
                    affectedNoteIds = affectedIds,
                    needsContinuation = false
                )
            }

            is AgentAction.AddTodos -> {
                val affectedIds = executeAction(action)
                ActionExecutionResult(
                    success = affectedIds.isNotEmpty(),
                    summary = "Todos added",
                    affectedNoteIds = affectedIds,
                    needsContinuation = false
                )
            }

            is AgentAction.ToggleTodo -> {
                val affectedIds = executeAction(action)
                ActionExecutionResult(
                    success = affectedIds.isNotEmpty(),
                    summary = "Todo toggled",
                    affectedNoteIds = affectedIds,
                    needsContinuation = false
                )
            }

            is AgentAction.DeleteTodo -> {
                val affectedIds = executeAction(action)
                ActionExecutionResult(
                    success = affectedIds.isNotEmpty(),
                    summary = "Todo deleted",
                    affectedNoteIds = affectedIds,
                    needsContinuation = false
                )
            }

            is AgentAction.ListCategories -> {
                executeAction(action)
                ActionExecutionResult(
                    success = true,
                    summary = "Categories listed",
                    needsContinuation = false
                )
            }

            is AgentAction.AnswerQuestion -> {
                executeAction(action)
                ActionExecutionResult(
                    success = true,
                    summary = "Question answered",
                    needsContinuation = false
                )
            }

            is AgentAction.BatchActions -> {
                val affectedIds = executeAction(action)
                ActionExecutionResult(
                    success = true,
                    summary = "Batch actions completed",
                    affectedNoteIds = affectedIds,
                    needsContinuation = false
                )
            }

            is AgentAction.GetCategoryNotes -> {
                executeAction(action)
                ActionExecutionResult(
                    success = true,
                    summary = "Category notes retrieved",
                    needsContinuation = false
                )
            }

            is AgentAction.SuggestActions -> {
                executeAction(action)
                ActionExecutionResult(
                    success = true,
                    summary = "Actions suggested",
                    needsContinuation = false
                )
            }
        }
    }

    /**
     * Fetch fresh note from database and validate privacy status (BUG-022, BUG-051 fix).
     *
     * This ensures we're not using stale snapshots where privacy status may have changed
     * between when AI saw the note and when the action executes.
     *
     * @param noteId The note ID to fetch
     * @return The note if it exists and is AI-accessible, null otherwise
     */
    private suspend fun getFreshAiAccessibleNote(noteId: String): Note? {
        val freshNote = repository.getNoteById(noteId) ?: return null
        return if (PrivacyGuard.canAiProcess(freshNote)) freshNote else null
    }

    /**
     * Execute an AgentAction directly.
     * This is the core action executor - separated from executeAgentAction to allow
     * direct execution of batch sub-actions without re-parsing.
     *
     * SECURITY: Each action type has PrivacyGuard checks to prevent access to private notes.
     * Uses getFreshAiAccessibleNote() to prevent stale privacy state issues.
     */
    private suspend fun executeAction(action: AgentAction): List<String> {
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
                // BUG-022/051 FIX: Fetch fresh note to prevent stale privacy state
                // ============================================================================
                val noteToDelete = when {
                    action.noteId != null -> getFreshAiAccessibleNote(action.noteId)
                    action.description != null -> {
                        val aiAccessibleNotes = PrivacyGuard.filterForAiModification(notes.value)
                        agentService.findNoteByDescription(action.description, aiAccessibleNotes)?.let { found ->
                            // Re-validate with fresh fetch
                            getFreshAiAccessibleNote(found.id)
                        }
                    }
                    else -> null
                }

                noteToDelete?.let {
                    repository.deleteNote(it)
                    Log.d(TAG, "Deleted note: ${it.title}")
                    listOf(it.id)
                } ?: emptyList()
            }

            is AgentAction.ArchiveNote -> {
                // ============================================================================
                // SECURITY: PrivacyGuard - private notes are INVISIBLE to AI
                // BUG-022/051 FIX: Fetch fresh note to prevent stale privacy state
                // ============================================================================
                val noteToArchive = when {
                    action.noteId != null -> getFreshAiAccessibleNote(action.noteId)
                    action.description != null -> {
                        val aiAccessibleNotes = PrivacyGuard.filterForAiModification(notes.value)
                        agentService.findNoteByDescription(action.description, aiAccessibleNotes)?.let { found ->
                            getFreshAiAccessibleNote(found.id)
                        }
                    }
                    else -> null
                }

                noteToArchive?.let {
                    repository.archiveNote(it.id)
                    Log.d(TAG, "Archived note: ${it.title}")
                    listOf(it.id)
                } ?: emptyList()
            }

            is AgentAction.UnarchiveNote -> {
                // ============================================================================
                // SECURITY: PrivacyGuard - private notes are INVISIBLE to AI
                // BUG-022/051 FIX: Fetch fresh note to prevent stale privacy state
                // ============================================================================
                val noteToUnarchive = when {
                    action.noteId != null -> getFreshAiAccessibleNote(action.noteId)
                    action.description != null -> {
                        val aiAccessibleArchived = PrivacyGuard.filterForAiModification(archivedNotes.value)
                        agentService.findNoteByDescription(action.description, aiAccessibleArchived)?.let { found ->
                            getFreshAiAccessibleNote(found.id)
                        }
                    }
                    else -> null
                }

                noteToUnarchive?.let {
                    repository.unarchiveNote(it.id)
                    Log.d(TAG, "Unarchived note: ${it.title}")
                    listOf(it.id)
                } ?: emptyList()
            }

            is AgentAction.UpdateNote -> {
                // ============================================================================
                // SECURITY: PrivacyGuard - private notes are INVISIBLE to AI
                // BUG-022/051 FIX: Fetch fresh note to prevent stale privacy state
                // ============================================================================
                val noteToUpdate = getFreshAiAccessibleNote(action.noteId)
                noteToUpdate?.let { note ->
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
                // BUG-022/051 FIX: Fetch fresh note to prevent stale privacy state
                // ============================================================================
                val noteToSummarize = getFreshAiAccessibleNote(action.noteId)
                noteToSummarize?.let { note ->
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
                // BUG-022/051 FIX: Fetch fresh note to prevent stale privacy state
                // ============================================================================
                val noteToUpdate = getFreshAiAccessibleNote(action.noteId)
                noteToUpdate?.let { note ->
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
                // BUG-022/051 FIX: Fetch fresh note to prevent stale privacy state
                // ============================================================================
                val noteToUpdate = getFreshAiAccessibleNote(action.noteId)
                noteToUpdate?.let { note ->
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
                // BUG-022/051 FIX: Fetch fresh note to prevent stale privacy state
                // ============================================================================
                val noteToUpdate = getFreshAiAccessibleNote(action.noteId)
                noteToUpdate?.let { note ->
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

            is AgentAction.WebSearch -> {
                // Execute web search using Tavily API
                val apiKey = securePreferences.getTavilyApiKey()
                if (apiKey.isNullOrBlank()) {
                    Log.w(TAG, "WebSearch: No Tavily API key configured")
                    listOf("ERROR: No Tavily API key configured. Please add your API key in Settings.")
                } else {
                    try {
                        Log.d(TAG, "WebSearch: Searching for '${action.query}' (reason: ${action.reason})")
                        val searchResult = tavilySearchProvider.search(
                            apiKey = apiKey,
                            query = action.query,
                            maxResults = action.maxResults,
                            topic = action.topic
                        )
                        if (searchResult.success) {
                            Log.d(TAG, "WebSearch: Found ${searchResult.results.size} results")
                            // Format results as JSON for the LLM to parse
                            val resultsJson = buildString {
                                append("{")
                                append("\"status\":\"success\",")
                                append("\"query\":\"${action.query.replace("\"", "\\\"")}\",")
                                append("\"reason\":\"${action.reason.replace("\"", "\\\"")}\",")

                                // Include AI summary
                                searchResult.answer?.let { answer ->
                                    append("\"ai_summary\":\"${answer.replace("\"", "\\\"").replace("\n", "\\n")}\",")
                                }

                                // Include sources
                                append("\"results\":[")
                                searchResult.results.forEachIndexed { index, result ->
                                    if (index > 0) append(",")
                                    append("{")
                                    append("\"title\":\"${result.title.replace("\"", "\\\"")}\",")
                                    append("\"url\":\"${result.url}\",")
                                    append("\"snippet\":\"${result.snippet.replace("\"", "\\\"").replace("\n", " ")}\"")
                                    append("}")
                                }
                                append("],")
                                append("\"total_results\":${searchResult.results.size}")
                                append("}")
                            }
                            listOf(resultsJson)
                        } else {
                            Log.w(TAG, "WebSearch: Failed - ${searchResult.error}")
                            listOf("{\"status\":\"error\",\"error\":\"${searchResult.error ?: "Unknown error occurred"}\"}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "WebSearch error: ${e.message}", e)
                        listOf("SEARCH_ERROR: ${e.message ?: "Search failed"}")
                    }
                }
            }

            is AgentAction.PlayAudio -> {
                Log.d(TAG, "PlayAudio: query='${action.query}' noteId=${action.noteId} index=${action.attachmentIndex}")
                try {
                    // Find the note using tiered search strategy
                    val targetNote = when {
                        // 1. Direct ID match (Best)
                        action.noteId != null -> {
                            getFreshAiAccessibleNote(action.noteId)
                        }
                        
                        // 2. Search by Query
                        action.query.isNotBlank() -> {
                            val allNotes = notes.value
                            val visibleNotes = PrivacyGuard.getAiVisibleNotes(allNotes)
                            val audioNotes = visibleNotes.filter { note ->
                                note.type == NoteType.AUDIO ||
                                note.fileMimeType?.startsWith("audio/") == true ||
                                note.getAttachments().any { it.mimeType.startsWith("audio/") }
                            }

                            // Tier 2.1: Exact/Strict Contain match
                            var match = audioNotes.find { note ->
                                note.title.contains(action.query, ignoreCase = true) ||
                                note.content.contains(action.query, ignoreCase = true) ||
                                note.fileName?.contains(action.query, ignoreCase = true) == true
                            }

                            // Tier 2.2: Fuzzy Keyword match
                            if (match == null) {
                                val keywords = action.query.split(Regex("\\s+"))
                                    .filter { it.length > 2 } // specific words only
                                    .map { it.lowercase() }
                                
                                if (keywords.isNotEmpty()) {
                                    match = audioNotes.find { note ->
                                        val title = note.title.lowercase()
                                        val content = note.content.lowercase()
                                        // Match if ALL significant keywords are present in title/content
                                        keywords.all { kw -> title.contains(kw) || content.contains(kw) }
                                    }
                                }
                            }

                            // Tier 2.3: Generic "Play Music" fallback
                            // If user says "play music" or "play audio" and we found nothing specific,
                            // play the most recent audio note.
                            if (match == null) {
                                val genericTerms = setOf("music", "audio", "recording", "song", "track", "play")
                                val isGeneric = action.query.lowercase().split(Regex("\\s+"))
                                    .all { it in genericTerms || it.length < 3 }
                                
                                if (isGeneric) {
                                    Log.d(TAG, "PlayAudio: Generic query detected, playing most recent audio")
                                    match = audioNotes.maxByOrNull { it.updatedAt }
                                }
                            }
                            
                            match
                        }
                        else -> null
                    }

                    if (targetNote == null) {
                        Log.w(TAG, "PlayAudio: Note not found for query='${action.query}' noteId=${action.noteId}")
                        viewModelScope.launch(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                getApplication(),
                                "No audio found for '${action.query}'",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        emptyList()
                    } else {
                        // Get the audio URI from the note
                        val attachments = targetNote.getAttachments()
                        val audioUri = when {
                            attachments.isNotEmpty() -> {
                                val index = action.attachmentIndex.coerceIn(0, attachments.size - 1)
                                attachments[index].uri
                            }
                            targetNote.fileUri != null -> targetNote.fileUri
                            else -> null
                        }

                        if (audioUri != null) {
                            // Create AudioTrack and set pending playback
                            val track = AudioTrack(
                                uri = audioUri,
                                title = targetNote.title,
                                fileName = targetNote.fileName,
                                sourceNoteId = targetNote.id,
                                mimeType = targetNote.fileMimeType ?: "audio/*"
                            )
                            _pendingAudioPlayback.value = track
                            Log.d(TAG, "PlayAudio: Triggering playback for '${track.title}'")
                            listOf(targetNote.id)
                        } else {
                            Log.w(TAG, "PlayAudio: No audio file found in note ${targetNote.id}")
                             viewModelScope.launch(Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    getApplication(),
                                    "Note has no audio file",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            emptyList()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "PlayAudio error: ${e.message}", e)
                    emptyList()
                }
            }

            is AgentAction.BatchActions -> {
                // Execute each action in the batch directly
                // SECURITY: Each sub-action goes through executeAction which has PrivacyGuard checks
                action.actions.flatMap { subAction ->
                    Log.d(TAG, "Executing batch sub-action: ${subAction.javaClass.simpleName}")
                    executeAction(subAction)
                }
            }
        }
    }

    // ==================== Calendar Operations ====================

    /**
     * Add a new calendar event
     */
    fun addCalendarEvent(
        title: String,
        description: String? = null,
        startTime: Long,
        endTime: Long,
        isAllDay: Boolean = false,
        location: String? = null,
        isPrivate: Boolean = false
    ) {
        viewModelScope.launch {
            val event = CalendarEvent(
                title = title,
                description = description,
                startTime = startTime,
                endTime = endTime,
                isAllDay = isAllDay,
                location = location,
                isEventPrivate = isPrivate
            )
            calendarDao.insertEvent(event)
            Log.d(TAG, "Added calendar event: $title")
        }
    }

    /**
     * Update an existing calendar event
     */
    fun updateCalendarEvent(event: CalendarEvent) {
        viewModelScope.launch {
            calendarDao.updateEvent(event.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * Delete a calendar event
     */
    fun deleteCalendarEvent(eventId: String) {
        viewModelScope.launch {
            calendarDao.deleteEventById(eventId)
            Log.d(TAG, "Deleted calendar event: $eventId")
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

/**
 * Factory for CogniViewModel that provides SavedStateHandle for state preservation
 * across process death (BUG-053 fix).
 *
 * Usage in Activity:
 * ```
 * private val viewModel: CogniViewModel by viewModels {
 *     CogniViewModelFactory(application, this)
 * }
 * ```
 */
class CogniViewModelFactory(
    private val application: Application,
    owner: SavedStateRegistryOwner
) : AbstractSavedStateViewModelFactory(owner, null) {

    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(
        key: String,
        modelClass: Class<T>,
        handle: SavedStateHandle
    ): T {
        if (modelClass.isAssignableFrom(CogniViewModel::class.java)) {
            return CogniViewModel(application, handle) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
