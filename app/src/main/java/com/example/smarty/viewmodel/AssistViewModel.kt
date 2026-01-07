package com.example.smarty.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.smarty.agent.AgentCallbacks
import com.example.smarty.agent.AgentResult
import com.example.smarty.agent.CogniAgent
import com.example.smarty.agent.CogniAgentProvider
import com.example.smarty.agent.ImageDisplayItem
import com.example.smarty.agent.WebCitation
import com.example.smarty.data.model.InlineChatImage
import com.example.smarty.data.local.CogniDatabase
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.model.Attachment
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatRole
import com.example.smarty.data.model.Citation
import com.example.smarty.data.model.Note
import com.example.smarty.data.remote.providers.TavilySearchProvider
import com.example.smarty.data.repository.ChatRepository
import com.example.smarty.data.repository.CogniRepository
import com.example.smarty.service.AlarmScheduler
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.api.GroqKeyManager
import com.google.gson.Gson
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * ViewModel for AssistActivity - Handles assistant overlay functionality
 *
 * Responsibilities:
 * - Chat message state management
 * - AI agent communication
 * - Voice input state
 * - Saving conversations to main app's chat history
 */
class AssistViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AssistViewModel"
    }

    // Lazy initialization
    private val securePreferences: SecurePreferences by lazy {
        SecurePreferences.getInstance(application)
    }
    private val database: CogniDatabase by lazy {
        CogniDatabase.getDatabase(application)
    }
    private val repository: CogniRepository by lazy {
        CogniRepository(
            database.noteDao(),
            database.categoryDao(),
            database.calendarDao(),
            database.noteVersionDao()
        )
    }
    private val chatRepository: ChatRepository by lazy {
        ChatRepository(database.chatDao())
    }
    private val groqKeyManager: GroqKeyManager by lazy {
        GroqKeyManager.getInstance(application)
    }
    // Use shared HttpClientProvider to avoid connection pool duplication
    private val httpClient: OkHttpClient by lazy {
        com.example.smarty.util.HttpClientProvider.default
    }
    private val tavilySearchProvider: TavilySearchProvider by lazy {
        TavilySearchProvider(httpClient, Gson())
    }
    private val alarmScheduler: AlarmScheduler by lazy {
        AlarmScheduler.getInstance(application)
    }
    private val agentProvider: CogniAgentProvider by lazy {
        CogniAgentProvider(securePreferences, groqKeyManager)
    }

    // Notes state for agent callbacks (cached on first access)
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    private val _categories = MutableStateFlow<List<Category>>(emptyList())

    // Temporary storage for citations during agent execution (thread-safe)
    private val pendingCitations = java.util.concurrent.CopyOnWriteArrayList<WebCitation>()

    // Temporary storage for inline images during agent execution (thread-safe)
    private val pendingInlineImages = java.util.concurrent.CopyOnWriteArrayList<InlineChatImage>()

    // Agent callbacks for Koog tools that need ViewModel state
    private val agentCallbacks = object : AgentCallbacks {
        override fun getActiveNotes(): List<Note> = PrivacyGuard.getAiVisibleNotes(_notes.value)
        override fun getArchivedNotes(): List<Note> = emptyList()  // Simplified for assist mode
        override fun getCategories(): List<Category> = _categories.value
        override fun getTavilyApiKey(): String? = securePreferences.getTavilyApiKey()
        // BATCH-3C: OpenAI API key for AgentOptimizer semantic cache (embeddings)
        override fun getOpenAiApiKey(): String? = securePreferences.getProviderKeys(com.example.smarty.data.local.AIProvider.OPENAI).firstOrNull()
        // Gemini API key for AgentOptimizer semantic cache fallback
        override fun getGeminiApiKey(): String? = securePreferences.getProviderKeys(com.example.smarty.data.local.AIProvider.GEMINI).firstOrNull()

        override suspend fun processNoteWithAi(note: Note) {
            // No-op for assist mode
        }

        override suspend fun markNoteAsAnalyzedForMemory(noteId: String) {
            // No-op for assist mode: memory analysis is handled by main app
        }

        override suspend fun findNoteByDescription(description: String, notes: List<Note>): Note? {
            return notes.find { note ->
                note.title.contains(description, ignoreCase = true) ||
                        note.content.contains(description, ignoreCase = true)
            }
        }

        override fun requestAudioPlayback(track: AudioTrack) {
            Log.d(TAG, "Audio playback requested: ${track.title}")
            // Audio playback not supported in assist mode yet
        }

        override fun onToolExecutionStarted(toolName: String, toolDisplayName: String) {
            // Update UI to show current tool operation
            _toolStatus.value = "$toolDisplayName..."
            Log.d(TAG, "Tool started: $toolName ($toolDisplayName)")
        }

        override fun onToolExecutionCompleted(toolName: String) {
            // Clear status after tool completes
            _toolStatus.value = null
            Log.d(TAG, "Tool completed: $toolName")
        }

        override fun onCitationsFound(citations: List<WebCitation>) {
            pendingCitations.addAll(citations)
            Log.d(TAG, "Citations found: ${citations.size} sources")
        }

        override fun launchApp(packageName: String) {
            // Launch app via package manager
            try {
                val intent = application.packageManager.getLaunchIntentForPackage(packageName)
                intent?.let {
                    it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    application.startActivity(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch app: $packageName", e)
            }
        }

        override fun getScreenContext(): com.example.smarty.agent.tools.external.ScreenContext? {
            // Return cached screen context if available
            return null  // AssistViewModel doesn't track screen context
        }

        override fun onDisplayImages(images: List<ImageDisplayItem>) {
            // Store images for the current chat response
            pendingInlineImages.clear()
            pendingInlineImages.addAll(images.map {
                InlineChatImage(uri = it.uri, fileName = it.fileName, noteTitle = it.noteTitle)
            })
            Log.d(TAG, "Images found: ${images.size} images to display inline")
        }

        override fun onPlanStatusChanged(status: String?) {
            // Plan progress not displayed in assist mode
        }
    }

    private val cogniAgent: CogniAgent by lazy {
        CogniAgent(
            context = application,
            agentProvider = agentProvider,
            repository = repository,
            tavilySearchProvider = tavilySearchProvider,
            alarmScheduler = alarmScheduler,
            callbacks = agentCallbacks,
            aiMemoryDao = database.aiMemoryDao()  // For memory management tool
        )
    }

    // State
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    // Assist context (selected text from triggering app)
    private val _assistContext = MutableStateFlow<AssistContext?>(null)
    val assistContext: StateFlow<AssistContext?> = _assistContext.asStateFlow()

    // Tool execution status for UI feedback
    private val _toolStatus = MutableStateFlow<String?>(null)
    val toolStatus: StateFlow<String?> = _toolStatus.asStateFlow()

    /**
     * Data class for assist context information
     */
    data class AssistContext(
        val selectedText: String?,
        val referringPackage: String
    )

    // Track session for saving
    private var sessionId: String? = null

    // Mutex for thread-safe message operations
    private val messageMutex = Mutex()

    // Theme state
    val isDarkTheme: StateFlow<Boolean> = securePreferences.isDarkTheme

    init {
        // Create a new session for this assistant interaction
        viewModelScope.launch {
            val session = chatRepository.createNewSession()
            sessionId = session.id
            Log.d(TAG, "Created assistant session: ${session.id}")

            // Load notes for agent context
            loadNotesForContext()
        }
    }

    /**
     * Load notes and categories for agent context
     */
    private suspend fun loadNotesForContext() {
        try {
            _notes.value = repository.getAllNotes().first()
            _categories.value = repository.getAllCategories().first()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading notes: ${e.message}", e)
        }
    }

    /**
     * Send a message to the AI agent
     */
    fun sendMessage(content: String, attachments: List<Attachment> = emptyList()) {
        if (content.isBlank() && attachments.isEmpty()) return

        viewModelScope.launch {
            try {
                _isProcessing.value = true
                pendingCitations.clear()

                // Add user message
                val userMessage = ChatMessage(
                    role = ChatRole.USER,
                    content = content,
                    attachments = attachments
                )
                messageMutex.withLock {
                    _messages.value = _messages.value + userMessage
                }

                // Build conversation history for context
                val conversationHistory = _messages.value
                    .filter { it.role != ChatRole.SYSTEM }
                    .map { msg ->
                        val role = when (msg.role) {
                            ChatRole.USER -> "USER"
                            ChatRole.ASSISTANT -> "ASSISTANT"
                            ChatRole.SYSTEM -> "SYSTEM"
                        }
                        role to msg.content
                    }
                    .takeLast(10) // Keep last 10 messages for context

                // Get AI response using the run method
                val result = cogniAgent.run(
                    userMessage = content,
                    conversationHistory = conversationHistory
                )

                // Get inline images and clear pending
                val inlineImages = pendingInlineImages.toList()
                pendingInlineImages.clear()

                // Create assistant message from result
                val assistantMessage = when (result) {
                    is AgentResult.Success -> ChatMessage(
                        role = ChatRole.ASSISTANT,
                        content = result.response,
                        citations = pendingCitations.map { citation ->
                            Citation(
                                title = citation.title,
                                url = citation.url,
                                snippet = citation.snippet
                            )
                        },
                        inlineImages = inlineImages
                    )
                    is AgentResult.Error -> ChatMessage(
                        role = ChatRole.ASSISTANT,
                        content = result.message,
                        isError = true
                    )
                    is AgentResult.NoProvider -> ChatMessage(
                        role = ChatRole.ASSISTANT,
                        content = result.message,
                        isError = true
                    )
                }

                // Add assistant message
                messageMutex.withLock {
                    _messages.value = _messages.value + assistantMessage
                }

                // Save message pair to persistent storage (non-cancellable to prevent data loss)
                sessionId?.let { id ->
                    withContext(NonCancellable) {
                        chatRepository.saveMessagePair(
                            sessionId = id,
                            userMessage = userMessage,
                            assistantMessage = assistantMessage,
                            shouldSave = true
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error sending message: ${e.message}", e)
                // Add error message
                val errorMessage = ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "Sorry, something went wrong. Please try again.",
                    isError = true
                )
                messageMutex.withLock {
                    _messages.value = _messages.value + errorMessage
                }
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /**
     * Set listening state (for voice input)
     */
    fun setListening(listening: Boolean) {
        _isListening.value = listening
    }

    /**
     * Stop listening
     */
    fun stopListening() {
        _isListening.value = false
    }

    /**
     * Save conversation and prepare to close
     */
    fun saveAndClose() {
        viewModelScope.launch {
            try {
                sessionId?.let { id ->
                    // Use NonCancellable to ensure session is finalized even if scope is cancelled
                    withContext(NonCancellable) {
                        // Only finalize if we have messages to save
                        val messageCount = _messages.value.size
                        if (messageCount > 0) {
                            Log.d(TAG, "Keeping assistant session: $id with $messageCount messages")
                        } else {
                            // Finalize empty sessions (will be cleaned up)
                            chatRepository.finalizeSession(id)
                            Log.d(TAG, "Finalized empty assistant session: $id")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving session: ${e.message}", e)
            }
        }
    }

    /**
     * Add a single message to the conversation (UI-005 fix)
     * Used by AssistActivity when processing input directly
     */
    fun addMessage(message: ChatMessage) {
        viewModelScope.launch {
            messageMutex.withLock {
                _messages.value = _messages.value + message
            }
            // Persist to database if we have a session (non-cancellable to prevent data loss)
            if (message.role == ChatRole.ASSISTANT) {
                val userMsg = _messages.value.lastOrNull { it.role == ChatRole.USER }
                if (userMsg != null) {
                    sessionId?.let { id ->
                        withContext(NonCancellable) {
                            chatRepository.saveMessagePair(
                                sessionId = id,
                                userMessage = userMsg,
                                assistantMessage = message,
                                shouldSave = true
                            )
                            Log.d(TAG, "Message pair saved to session: $id")
                        }
                    }
                }
            }
        }
    }

    /**
     * Clear all messages (start fresh conversation)
     */
    fun clearMessages() {
        viewModelScope.launch {
            messageMutex.withLock {
                _messages.value = emptyList()
            }
            pendingCitations.clear()
            // Create a new session
            val session = chatRepository.createNewSession()
            sessionId = session.id
            Log.d(TAG, "Created new assistant session: ${session.id}")
        }
    }

    /**
     * Set the assist context from the triggering app
     */
    fun setAssistContext(selectedText: String?, referringPackage: String) {
        _assistContext.value = AssistContext(
            selectedText = selectedText,
            referringPackage = referringPackage
        )
        if (!selectedText.isNullOrBlank()) {
            Log.d(TAG, "Assist context set: text from $referringPackage")
        }
    }

    /**
     * Set the current tool execution status for UI feedback
     */
    fun setToolStatus(status: String?) {
        _toolStatus.value = status
    }

    /**
     * Add citations from web search to pending list
     */
    fun addCitations(citations: List<WebCitation>) {
        pendingCitations.addAll(citations)
        Log.d(TAG, "Added ${citations.size} citations to pending list")
    }

    /**
     * Clear pending citations (call before new request)
     */
    fun clearPendingCitations() {
        pendingCitations.clear()
    }

    /**
     * Cleanup resources when ViewModel is destroyed
     */
    override fun onCleared() {
        super.onCleared()

        // Cancel all coroutines in viewModelScope
        viewModelScope.cancel()

        // Note: httpClient is now a shared singleton from HttpClientProvider
        // Do NOT shutdown here as other components may still be using it

        // Clear pending state
        pendingCitations.clear()
        _messages.value = emptyList()
        _isProcessing.value = false
        _isListening.value = false
        _assistContext.value = null
        _toolStatus.value = null
        _notes.value = emptyList()
        _categories.value = emptyList()

        Log.d(TAG, "AssistViewModel cleared")
    }
}

/**
 * Factory for AssistViewModel
 */
class AssistViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AssistViewModel::class.java)) {
            return AssistViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
