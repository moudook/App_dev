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
import com.example.smarty.data.model.getAllAttachmentUris
import com.example.smarty.data.model.getAttachments
import com.example.smarty.data.model.getTodos
import com.example.smarty.data.model.withAttachments
import com.example.smarty.data.model.withTodos
import com.example.smarty.data.remote.AIService
import com.example.smarty.agent.AgentCallbacks
import com.example.smarty.agent.AgentResult
import com.example.smarty.agent.CogniAgent
import com.example.smarty.agent.CogniAgentProvider
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
import com.example.smarty.util.api.RateLimiter
import com.example.smarty.util.api.GroqKeyManager
import com.example.smarty.util.api.KeyUsageStats
import com.example.smarty.service.AlarmScheduler
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

    // Repository needs to be initialized before agent
    private val database = CogniDatabase.getDatabase(application)
    private val repository = CogniRepository(
        database.noteDao(),
        database.categoryDao(),
        database.calendarDao()
    )

    // Web search provider for agent actions
    private val tavilySearchProvider: TavilySearchProvider by lazy {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        TavilySearchProvider(httpClient, Gson())
    }

    // Alarm scheduler for timer/alarm tools
    private val alarmScheduler = AlarmScheduler.getInstance(application)

    // Rate limiter for API call management (30 calls/min, 14.4k/day)
    private val rateLimiter = RateLimiter.getInstance(application)

    // GROQ Key Manager for per-key usage tracking
    private val groqKeyManager = GroqKeyManager.getInstance(application)

    // Koog-based AI Agent (GROQ-only with multi-key rotation)
    private val agentProvider = CogniAgentProvider(securePreferences, groqKeyManager)
    private val cogniAgent: CogniAgent by lazy {
        CogniAgent(
            agentProvider = agentProvider,
            repository = repository,
            tavilySearchProvider = tavilySearchProvider,
            alarmScheduler = alarmScheduler,
            callbacks = agentCallbacks,
            rateLimiter = rateLimiter  // API budget management
        )
    }

    // GROQ key usage stats exposed for UI
    val groqKeyUsageStats: StateFlow<List<KeyUsageStats>> = groqKeyManager.usageStats

    // Agent callbacks for Koog tools that need ViewModel state
    // SECURITY: Pre-filter notes at callback level for defense-in-depth
    private val agentCallbacks = object : AgentCallbacks {
        override fun getActiveNotes(): List<Note> = PrivacyGuard.getAiVisibleNotes(notes.value)
        override fun getArchivedNotes(): List<Note> = PrivacyGuard.getAiVisibleNotes(archivedNotes.value)
        override fun getCategories(): List<Category> = categories.value
        override fun getTavilyApiKey(): String? = securePreferences.getTavilyApiKey()

        override suspend fun processNoteWithAi(note: Note) {
            simulateAiProcessing(note)
        }

        override suspend fun findNoteByDescription(description: String, notes: List<Note>): Note? {
            // Simple fuzzy matching
            return notes.find { note ->
                note.title.contains(description, ignoreCase = true) ||
                note.content.contains(description, ignoreCase = true)
            }
        }

        override fun requestAudioPlayback(track: AudioTrack) {
            // Directly set the track for playback - it now has a valid URI from the note attachment
            _pendingAudioPlayback.value = track
        }

        override fun onToolExecutionStarted(toolName: String, toolDisplayName: String) {
            agentStateManager.showExecutingTool(toolName, toolDisplayName)
        }

        override fun onToolExecutionCompleted(toolName: String) {
            // State manager handles this via the agent loop
        }
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

    // Agent State Manager - handles DynamicIsland state for AI agent operations
    private val agentStateManager = com.example.smarty.viewmodel.managers.AgentStateManager(viewModelScope)

    // Note Operations Manager - handles note CRUD operations
    private val noteOperationsManager = com.example.smarty.viewmodel.managers.NoteOperationsManager(
        repository = repository,
        aiService = aiService,
        context = application,
        scope = viewModelScope
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

    // Shake-triggered mode switch (for glow animation feedback)
    private val _wasShakeTriggered = MutableStateFlow(false)
    val wasShakeTriggered: StateFlow<Boolean> = _wasShakeTriggered.asStateFlow()

    // Shared flow for speech results to be consumed by screens
    private val _speechResults = kotlinx.coroutines.flow.MutableSharedFlow<String>()
    val speechResults = _speechResults.asSharedFlow()

    fun onSpeechResult(text: String) {
        viewModelScope.launch {
            _speechResults.emit(text)
        }
    }

    // ==================== AGENT STATE (delegated to AgentStateManager) ====================

    val agentState: StateFlow<DynamicIslandState> = agentStateManager.agentState

    // Delegate agent state methods to manager
    private fun setAgentState(state: DynamicIslandState) = agentStateManager.setState(state)
    private fun showAgentCompleted(toolsUsed: Int) = agentStateManager.showCompleted()

    // Expose secure preferences state for UI
    val geminiKeys: StateFlow<List<String>> = securePreferences.geminiKeys
    val huggingFaceKeys: StateFlow<List<String>> = securePreferences.huggingFaceKeys
    val providerConfigs: StateFlow<Map<AIProvider, AIProviderConfig>> = securePreferences.providerConfigs
    val providerPriorityOrder: StateFlow<List<AIProvider>> = securePreferences.providerPriorityOrder
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

        // Sync GROQ keys with manager for usage tracking on startup
        viewModelScope.launch {
            agentProvider.syncGroqKeys()
        }

        // Initialize chat manager (loads sessions and cleans up empty ones)
        chatManager.initialize()

        // Set up NoteOperationsManager callback for AI processing
        noteOperationsManager.setAiProcessingCallback(object : com.example.smarty.viewmodel.managers.NoteOperationsManager.AiProcessingCallback {
            override suspend fun onProcessingComplete(note: Note) {
                Log.d(TAG, "Note processing complete: ${note.title}")
            }
            override suspend fun onProcessingError(note: Note, error: String) {
                Log.e(TAG, "Note processing error for ${note.title}: $error")
            }
        })

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

    /**
     * Update todos for a note - with callback for completion.
     * BUG FIX: Use fresh note from database, not stale StateFlow cache.
     */
    fun updateNoteTodos(noteId: String, todos: List<TodoItem>, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            try {
                noteOperationMutex.withLock {
                    // BUG FIX: Get fresh note from database, not stale StateFlow
                    val note = repository.getNoteById(noteId)
                    note?.let {
                        val updatedNote = it.withTodos(todos)
                        repository.updateNote(updatedNote)
                        Log.d(TAG, "Todos saved for note $noteId: ${todos.size} items")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating note todos: ${e.message}", e)
            } finally {
                // Always call completion callback on main thread
                onComplete?.invoke()
            }
        }
    }

    /**
     * Edit a note's title and content.
     * Called when user edits a note from the detail view.
     */
    fun editNote(noteId: String, newTitle: String, newContent: String) {
        viewModelScope.launch {
            try {
                noteOperationMutex.withLock {
                    val note = repository.getNoteById(noteId)
                    note?.let {
                        val updatedNote = it.copy(
                            title = newTitle,
                            content = newContent,
                            updatedAt = System.currentTimeMillis()
                        )
                        repository.updateNote(updatedNote)
                        // Update selected note if this is the currently viewed note
                        if (_selectedNote.value?.id == noteId) {
                            _selectedNote.value = updatedNote
                        }
                        Log.d(TAG, "Note edited: $noteId")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error editing note: ${e.message}", e)
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

        // Build attachment metadata for AI (file names and types only, no content)
        val attachmentMetadata = note.getAttachments().map { attachment ->
            com.example.smarty.data.model.AttachmentMetadata.fromNoteAttachment(attachment)
        }.takeIf { it.isNotEmpty() }

        // Use real AI service with fallback for regular content
        val aiResponse = aiService.analyzeContent(note.content, attachmentMetadata)

        val categoryName = aiResponse.category
        val summary = aiResponse.summary
        val whySaved = aiResponse.whySaved
        val newTitle = aiResponse.title
        val tags = aiResponse.tags

        val category = repository.getOrCreateCategory(categoryName)

        // Convert tags list to JSON for storage
        val tagsJson = if (tags.isNotEmpty()) {
            com.google.gson.Gson().toJson(tags)
        } else null

        val updatedNote = note.copy(
            title = if (newTitle.isNotBlank()) newTitle else note.title,
            summary = summary,
            whySaved = whySaved,
            categoryId = category.id,
            categoryName = category.name,
            tagsJson = tagsJson,
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
        // Sync GROQ keys with manager for usage tracking
        if (provider == AIProvider.GROQ) {
            viewModelScope.launch { agentProvider.syncGroqKeys() }
        }
    }

    fun removeApiKey(provider: AIProvider, apiKey: String) {
        securePreferences.removeProviderKey(provider, apiKey)
        // Sync GROQ keys with manager for usage tracking
        if (provider == AIProvider.GROQ) {
            viewModelScope.launch { agentProvider.syncGroqKeys() }
        }
    }

    fun updateApiKey(provider: AIProvider, oldKey: String, newKey: String) {
        securePreferences.updateProviderKey(provider, oldKey, newKey)
        // Sync GROQ keys with manager for usage tracking
        if (provider == AIProvider.GROQ) {
            viewModelScope.launch { agentProvider.syncGroqKeys() }
        }
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

    // Rate Limit Stats (exposed for UI monitoring)
    fun getRateLimitStats() = rateLimiter.getUsageStats()

    // Tavily Web Search API Management
    private val _tavilyApiKey = MutableStateFlow(securePreferences.getTavilyApiKey())
    val tavilyApiKey: StateFlow<String?> = _tavilyApiKey.asStateFlow()

    fun setTavilyApiKey(key: String?) {
        securePreferences.setTavilyApiKey(key)
        _tavilyApiKey.value = key
    }

    // Shake Sensitivity Management
    private val _shakeSensitivity = MutableStateFlow(securePreferences.getShakeSensitivity())
    val shakeSensitivity: StateFlow<Float> = _shakeSensitivity.asStateFlow()

    fun setShakeSensitivity(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        securePreferences.setShakeSensitivity(clamped)
        _shakeSensitivity.value = clamped
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
        shakeDetector = ShakeDetector(
            context = context,
            onShakeDetected = { handleShake() },
            getThreshold = { securePreferences.getShakeThreshold() }
        )
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
                toggleChatMode(fromShake = true)
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
     * @param fromShake Whether this toggle was triggered by a shake gesture
     */
    fun toggleChatMode(fromShake: Boolean = false) {
        // Track if this was shake-triggered for glow animation
        if (fromShake) {
            _wasShakeTriggered.value = true
            viewModelScope.launch {
                kotlinx.coroutines.delay(500) // Reset after animation
                _wasShakeTriggered.value = false
            }
        }

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
     * Send a message in chat mode using the Koog-based AI agent.
     *
     * ARCHITECTURE: Uses JetBrains Koog framework for agent orchestration:
     * - Koog handles the agent loop, tool execution, and multi-step reasoning
     * - PrivacyGuard is enforced at the tool level via CogniToolBase
     * - DynamicIsland shows agent state during execution
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
                // Initialize agent run tracking
                agentStateManager.startAgentRun()

                // Show thinking state
                setAgentState(DynamicIslandState.AgentThinking(
                    iteration = 1,
                    message = "Thinking..."
                ))

                // Build conversation history for agent memory
                val conversationHistory = chatManager.chatMessages.value
                    .filter { it.role != ChatRole.SYSTEM } // Exclude system messages
                    .map { msg ->
                        val role = when (msg.role) {
                            ChatRole.USER -> "User"
                            ChatRole.ASSISTANT -> "Assistant"
                            else -> "System"
                        }
                        Pair(role, msg.content)
                    }

                // Run the Koog agent with conversation history
                val result = cogniAgent.run(content, conversationHistory)

                when (result) {
                    is AgentResult.Success -> {
                        Log.d(TAG, "Agent completed successfully via ${result.provider}")

                        // Detect if this was an audio-related query
                        val isAudioQuery = content.lowercase().let {
                            it.contains("play") || it.contains("music") || it.contains("audio") ||
                            it.contains("song") || it.contains("podcast") || it.contains("listen")
                        }

                        // Create assistant message from agent response
                        val assistantMessage = ChatMessage(
                            role = ChatRole.ASSISTANT,
                            content = result.response,
                            isAudioRelated = isAudioQuery
                        )

                        chatManager.addAssistantMessage(assistantMessage)
                        chatManager.markApiCallSuccessful()
                        chatManager.saveMessagePair(
                            userMessage = userMessage,
                            assistantMessage = assistantMessage,
                            hasApiKeys = securePreferences.hasAnyApiKeys()
                        )

                        // Show completed state
                        agentStateManager.showCompleted()
                    }

                    is AgentResult.Error -> {
                        Log.e(TAG, "Agent error: ${result.message}")

                        setAgentState(DynamicIslandState.AgentError(result.message))
                        delay(2000)
                        setAgentState(DynamicIslandState.Contracted)

                        val errorMessage = ChatMessage(
                            role = ChatRole.ASSISTANT,
                            content = result.message
                        )
                        chatManager.addAssistantMessage(errorMessage)
                    }

                    is AgentResult.NoProvider -> {
                        Log.w(TAG, "No provider configured: ${result.message}")

                        val noKeyMessage = ChatMessage(
                            role = ChatRole.ASSISTANT,
                            content = "Please configure an AI provider API key in Settings to use chat."
                        )
                        chatManager.addAssistantMessage(noKeyMessage)
                        setAgentState(DynamicIslandState.Contracted)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing chat message: ${e.message}", e)

                setAgentState(DynamicIslandState.AgentError("Error occurred"))
                delay(2000)
                setAgentState(DynamicIslandState.Contracted)

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
        color: Int? = null,
        reminderMinutes: Int? = null,
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
                color = color,
                reminderMinutes = reminderMinutes,
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
            try {
                calendarDao.updateEvent(event)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating calendar event", e)
            }
        }
    }

    fun deleteCalendarEvent(eventId: String) {
        viewModelScope.launch {
            try {
                calendarDao.deleteEventById(eventId)
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting calendar event", e)
            }
        }
    }

    // ==================== Dynamic Model Management ====================

    fun getAvailableModels(provider: AIProvider): List<Pair<String, String>> {
        return securePreferences.getAvailableModels(provider)
    }

    fun refreshGroqModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val apiKey = securePreferences.getProviderKeys(AIProvider.GROQ).firstOrNull()
            if (apiKey.isNullOrBlank()) return@launch

            try {
                val request = okhttp3.Request.Builder()
                    .url("https://api.groq.com/openai/v1/models")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()

                OkHttpClient().newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = org.json.JSONObject(body)
                        val data = json.getJSONArray("data")
                        val models = mutableListOf<Pair<String, String>>()

                        for (i in 0 until data.length()) {
                            val item = data.getJSONObject(i)
                            val id = item.getString("id")
                            // Basic formatting for display name
                            val name = when {
                                id.contains("llama-4-scout") -> "Llama 4 Scout 17B (Dynamic)"
                                id.contains("llama-4") -> "Llama 4 (Dynamic)"
                                id.contains("llama-3.3") -> "Llama 3.3 (Dynamic)"
                                id.contains("llama-3.1") -> "Llama 3.1 (Dynamic)"
                                id.contains("mixtral") -> "Mixtral (Dynamic)"
                                id.contains("gemma") -> "Gemma (Dynamic)"
                                else -> id
                            }
                            models.add(id to name)
                        }
                        
                        // Sort by name for better UX
                        models.sortBy { it.second }

                        if (models.isNotEmpty()) {
                            securePreferences.setDynamicModels(AIProvider.GROQ, models)
                            // Force refresh of provider configs flow
                            securePreferences.setProviderEnabled(AIProvider.GROQ, securePreferences.isProviderEnabled(AIProvider.GROQ))
                        }
                    } else {
                        Log.e(TAG, "Groq models fetch failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching Groq models", e)
            }
        }
    }
    // ==================== Resource Optimization ====================

    // Track if resource-intensive operations are paused
    private var isResourceOptimized = false

    /**
     * Pause resource-intensive operations when app goes to background (onPause).
     * This reduces battery consumption while preserving essential functionality.
     */
    fun pauseResourceIntensiveOperations() {
        if (isResourceOptimized) return
        isResourceOptimized = true
        Log.d(TAG, "Pausing resource-intensive operations")
        // Image loading is handled by Coil which auto-pauses when lifecycle not active
        // Database access remains active for scheduled backups
        // Audio service continues if playing (handled by AudioPlayerService)
    }

    /**
     * Resume resource-intensive operations when app returns to foreground (onResume).
     */
    fun resumeResourceIntensiveOperations() {
        if (!isResourceOptimized) return
        isResourceOptimized = false
        Log.d(TAG, "Resuming resource-intensive operations")
        // Refresh cache size on resume
        refreshCacheSize()
    }

    /**
     * Minimize background resources when app goes to background (onStop).
     * Trims memory caches while preserving database and scheduled operations.
     */
    fun minimizeBackgroundResources() {
        Log.d(TAG, "Minimizing background resources")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Trim image caches to reduce memory footprint
                cacheManager.trimToSize(cacheManager.getCacheSize() / 2)
                Log.d(TAG, "Trimmed cache to 50% of current size")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to trim cache: ${e.message}")
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
