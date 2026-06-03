package com.example.smarty.features.chat.domain

import android.app.Application
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.example.smarty.features.chat.agent.*
import com.example.smarty.features.chat.agent.models.ScreenContext
import com.example.smarty.features.chat.agent.models.WebCitation
import com.example.smarty.features.chat.agent.models.ImageDisplayItem
import com.example.smarty.features.chat.agent.transport.CommandTransport
import com.example.smarty.features.chat.agent.transport.CompositeTransport
import com.example.smarty.features.chat.agent.transport.LocalCommandTransport
import com.example.smarty.protocol.AgentCommand
import com.example.smarty.protocol.*
import com.example.smarty.data.local.SmartyDatabase
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.core.domain.model.*
import com.example.smarty.data.remote.RemoteAgentService
import com.example.smarty.data.repository.ChatRepository
import com.example.smarty.service.AlarmScheduler
import com.example.smarty.service.CommandResult
import com.example.smarty.service.LocalCommandProcessor
import com.example.smarty.ui.components.ConnectionStatus
import com.example.smarty.core.common.util.AndroidLogger
import com.example.smarty.core.common.util.CompletionSoundManager
import com.example.smarty.core.common.util.ContentTypeDetector
import com.example.smarty.core.common.util.PrivacyGuard
import com.example.smarty.core.common.util.mention.MentionParser
import com.example.smarty.core.common.util.mention.NoteContextBuilder
import com.example.smarty.features.audio.domain.AudioFeatureManager.AudioSearchResult
import com.example.smarty.features.chat.domain.thinking.ThinkingParser
import com.example.smarty.core.domain.model.SearchResultItem
import com.example.smarty.core.domain.model.SearchQueryAnalysis
import com.example.smarty.core.domain.model.RecallResult
import com.example.smarty.R
// import io.ktor.client.plugins.contentnegotiation.ContentNegotiation // Removed - not available in minimal Ktor
// import io.ktor.serialization.kotlinx.json.json // Removed - not available in minimal Ktor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json

/**
 * Orchestrates the Chat feature, including AI agent interaction,
 * session management, and mention resolution.
 */
import com.example.smarty.features.settings.domain.SettingsFeatureManager
import com.example.smarty.features.notes.domain.NoteOperationsManager
import com.example.smarty.features.system.domain.SystemFeatureManager
import com.example.smarty.features.search.domain.SearchFeatureManager
import com.example.smarty.features.audio.domain.AudioFeatureManager
import com.example.smarty.features.calendar.domain.CalendarFeatureManager
import com.example.smarty.data.repository.SmartyRepository

class ChatFeatureManager(
    private val application: Application,
    private val scope: CoroutineScope,
    private val chatRepository: ChatRepository,
    private val repository: SmartyRepository,
    private val database: SmartyDatabase,
    private val securePreferences: SecurePreferences,
    private val settingsFeatureManager: SettingsFeatureManager,
    private val noteOperationsManager: NoteOperationsManager,
    private val systemFeatureManager: SystemFeatureManager,
    private val completionSoundManager: CompletionSoundManager,
    private val alarmScheduler: AlarmScheduler,
    private val searchFeatureManager: SearchFeatureManager,
    private val audioFeatureManager: AudioFeatureManager,
    private val calendarFeatureManager: CalendarFeatureManager,
    private val styleFeatureManager: StyleFeatureManager,
    private val workflowManager: WorkflowManager,
    private val savedStateHandle: SavedStateHandle,
    // External states needed for callbacks/logic
    private val currentScreen: StateFlow<String>,
    private val activeNoteId: StateFlow<String?>,
    private val isDarkTheme: StateFlow<Boolean>,
    private val connectionStatus: StateFlow<ConnectionStatus>,
    private val cacheSizeBytes: StateFlow<Long>,
    private val onNavigate: (String?) -> Unit,
) {
    companion object {
        private const val TAG = "ChatFeatureManager"
        private const val KEY_IS_CHAT_MODE = "isChatMode"
        private const val KEY_CURRENT_SESSION_ID = "currentSessionId"
    }



    private val androidLogger by lazy { AndroidLogger() }
    private val historyCompressor by lazy {
        com.example.smarty.core.common.util
            .HistoryCompressor(androidLogger)
    }

    // Reuse existing ChatManager for basic state and session management
    private val chatManager =
        ChatManager(
            application,
            chatRepository,
            scope,
            historyCompressor,
        )

    // --- Internal Managers ---

    private val mentionManager: MentionFeatureManager by lazy {
        MentionFeatureManager(repository)
    }

    private val allNotes =
        noteOperationsManager
            .getAllNotes()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val archivedNotes =
        noteOperationsManager
            .getArchivedNotes()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val allCategories =
        noteOperationsManager
            .getAllCategories()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Local command processor for fast-path handling
    private val localCommandProcessor: LocalCommandProcessor by lazy {
        LocalCommandProcessor(
            context = application,
            getNotes = { allNotes.value },
            getActiveNoteId = { activeNoteId.value },
            systemFeatureManager = systemFeatureManager,
            getDeviceAudio = { audioFeatureManager.getAllAudioTracks() },
        )
    }

    private val noteContextBuilder: NoteContextBuilder by lazy {
        NoteContextBuilder(mentionManager)
    }

    // Task 15: Remote Agent Service (Thin Client)
    // Replaces local SmartyAgentOptimized and SmartyAgentProvider
    private val remoteAgentService: RemoteAgentService by lazy {
        val client =
            com.example.smarty.di.ServiceLocator
                .provideHttpClient()

        RemoteAgentService(
            client = client,
            eventSink = agentEventSink,
            serverUrlProvider = { securePreferences.getSmartyServerUrl() },
            deviceIdProvider = { securePreferences.getDeviceId() },
        )
    }

    // Agent Event Sink for Koog tools notifications
    private val agentEventSink: AgentEventSink by lazy {
        ChatAgentEventSinkImpl(
            scope = scope,
            repository = repository,
            alarmScheduler = alarmScheduler,
            remoteAgentService = remoteAgentService,
            commandTransport = commandTransport,
            currentSessionId = currentSessionId,
            _agentActivity = _agentActivity,
            pendingActions = pendingActions,
            onCitationsFoundCallback = ::onCitationsFound,
            onDisplayImagesCallback = ::onDisplayImages,
            validateCommandCallback = { ChatCommandValidator.validateCommand(it) },
            logCommandCallback = { command, rejected, rejectionReason ->
                if (rejected) ChatCommandValidator.logCommand(command, true, rejectionReason) else ChatCommandValidator.logCommand(command)
            },
            getCommandSummaryCallback = ::getCommandSummary
        )
    }

    // Strict allowlist for settings toggles — only safe, non-privileged settings
    private val ALLOWED_SETTINGS =
        setOf(
            "wifi",
            "bluetooth",
            "flashlight",
            "auto_rotate",
            "location",
            "dnd",
            "airplane_mode",
        )

    // Client Command Executor for Koog tools actions
    private val clientCommandExecutor = ChatClientCommandExecutor(
        application = application,
        scope = scope,
        activeNoteId = activeNoteId,
        allNotes = allNotes,
        archivedNotes = archivedNotes,
        allCategories = allCategories,
        currentScreen = currentScreen,
        isDarkTheme = isDarkTheme,
        connectionStatus = connectionStatus,
        cacheSizeBytes = cacheSizeBytes,
        systemFeatureManager = systemFeatureManager,
        noteOperationsManager = noteOperationsManager,
        searchFeatureManager = searchFeatureManager,
        audioFeatureManager = audioFeatureManager,
        calendarFeatureManager = calendarFeatureManager,
        onNavigate = onNavigate,
        onShowBreathing = { _showBreathingOverlay.value = true },
        allowedSettings = ALLOWED_SETTINGS
    )

    // Task 7: Command transport for delivering validated commands to execution
    // Task 8: CompositeTransport with shadow mode disabled by default
    // To enable shadow mode for debugging, change shadow = null to shadow = ShadowRemoteTransport()
    private val commandTransport: CommandTransport by lazy {
        CompositeTransport(
            primary = LocalCommandTransport(clientCommandExecutor, scope),
            shadow = null, // Disabled by default; set to ShadowRemoteTransport() for debugging
        )
    }

// Exposed flows from ChatManager
    val isChatMode: StateFlow<Boolean> = chatManager.isChatMode
    val chatMessages: StateFlow<List<ChatMessage>> = chatManager.chatMessages
    val isChatProcessing: StateFlow<Boolean> = chatManager.isChatProcessing
    val currentSessionId: StateFlow<String?> = chatManager.currentSessionId
    val chatSessions: StateFlow<List<ChatSession>> = chatManager.chatSessions
    val failedMessages: StateFlow<List<FailedMessage>> = chatManager.failedMessages
    val pendingQueue: StateFlow<List<QueuedMessage>> = chatManager.pendingQueue

    // Mention State
    private val _mentionState = MutableStateFlow(MentionState())
    val mentionState: StateFlow<MentionState> = _mentionState.asStateFlow()
    private var chatInputCursorPosition: Int = 0

    // Pending Text
    private val _pendingChatText = MutableStateFlow<String?>(null)
    val pendingChatText: StateFlow<String?> = _pendingChatText.asStateFlow()

    // Breathing overlay state
    private val _showBreathingOverlay = MutableStateFlow(false)
    val showBreathingOverlay: StateFlow<Boolean> = _showBreathingOverlay.asStateFlow()

    // Approval state for ask_user and other tool approvals
    private val _pendingApprovalState = MutableStateFlow<com.example.smarty.features.chat.domain.state.PendingApproval?>(null)
    val pendingApprovalState: StateFlow<com.example.smarty.features.chat.domain.state.PendingApproval?> =
        _pendingApprovalState
            .asStateFlow()

    // Parsed clarification requests from ApprovalRequested events (for SmartyInputField)
    private val _pendingClarificationRequests = MutableStateFlow<List<ClarificationRequest>>(emptyList())
    val pendingClarificationRequests: StateFlow<List<ClarificationRequest>> = _pendingClarificationRequests.asStateFlow()

    private val chatQueryDispatcher: ChatQueryDispatcher by lazy {
        ChatQueryDispatcher(
            scope = scope,
            application = application,
            chatManager = chatManager,
            remoteAgentService = remoteAgentService,
            localCommandProcessor = localCommandProcessor,
            systemFeatureManager = systemFeatureManager,
            repository = repository,
            chatRepository = chatRepository,
            securePreferences = securePreferences,
            settingsFeatureManager = settingsFeatureManager,
            completionSoundManager = completionSoundManager,
            currentSessionId = currentSessionId,
            _agentActivity = _agentActivity,
            _pendingChatText = _pendingChatText,
            _mentionState = _mentionState,
            _pendingApprovalState = _pendingApprovalState,
            _pendingClarificationRequests = _pendingClarificationRequests,
            pendingActions = pendingActions,
            pendingCitations = pendingCitations,
            pendingInlineImages = pendingInlineImages,
            navigateTo = { route -> onNavigate(route) }
        )
    }

    // Navigation state delegated to SharedAppState via onNavigate callback
    // private val _navigationRequest = MutableStateFlow<String?>(null)
    // val navigationRequest: StateFlow<String?> = _navigationRequest.asStateFlow()

    // Proactive Suggestions
    private val _proactiveSuggestion = MutableStateFlow<String?>(null)
    val proactiveSuggestion: StateFlow<String?> = _proactiveSuggestion.asStateFlow()

    // Agent Activity State (Thinking/Tool Execution)

    /**
     * Represents the current activity of the AI agent.
     * Used to show real-time feedback in the chat UI.
     */
    data class AgentActivity(
        val type: Type,
        val displayText: String,
        val toolName: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
    ) {
        enum class Type {
            THINKING, // General processing/thinking
            TOOL_RUNNING, // A specific tool is executing
            SEARCHING, // Web search in progress
            ANALYZING, // Analyzing content
        }
    }

    private val _agentActivity = MutableStateFlow<AgentActivity?>(null)
    val agentActivity: StateFlow<AgentActivity?> = _agentActivity.asStateFlow()

    // Internal state for current response
    private val pendingCitations = CopyOnWriteArrayList<WebCitation>()
    private val pendingInlineImages = CopyOnWriteArrayList<ImageDisplayItem>()
    private val pendingActions = CopyOnWriteArrayList<AgentActionResult>()
    private val pendingToolCalls = CopyOnWriteArrayList<com.example.smarty.core.domain.model.AgentToolCallEntry>()

    // Current streaming job for cancellation
    private var currentStreamingJob: Job? = null

    init {
        chatManager.initialize()
    }

    fun toggleChatMode(fromShake: Boolean = false) {
        chatManager.toggleChatMode()
        savedStateHandle[KEY_IS_CHAT_MODE] = !isChatMode.value
    }

    /** Update the current tool name display */

    /** Update the current tool name display */
    fun updateCurrentToolName(name: String?) {
        if (name != null) {
            _agentActivity.value =
                AgentActivity(
                    type = AgentActivity.Type.TOOL_RUNNING,
                    displayText = "Using $name...",
                    toolName = name,
                )
        } else {
            _agentActivity.value = null
        }
    }

    fun enterChatMode() {
        scope.launch {
            chatManager.enterChatMode()
            savedStateHandle[KEY_IS_CHAT_MODE] = true
        }
    }

    fun exitChatMode() {
        chatManager.exitChatMode()
        savedStateHandle[KEY_IS_CHAT_MODE] = false
    }

    fun createNewChatSession() {
        chatManager.createNewChatSession()
        scope.launch {
            chatManager.currentSessionId.collect { id ->
                if (id != null) {
                    savedStateHandle[KEY_CURRENT_SESSION_ID] = id
                }
            }
        }
    }

    fun switchToChatSession(sessionId: String) {
        chatManager.switchToChatSession(sessionId)
        savedStateHandle[KEY_CURRENT_SESSION_ID] = sessionId
    }

    fun deleteChatSession(sessionId: String) {
        chatManager.deleteChatSession(sessionId)
        scope.launch {
            remoteAgentService.deleteChatSession(sessionId)
        }
    }

    fun clearChatHistory() {
        chatManager.clearChatHistory()
    }

    fun enterChatWithNoteReference(noteTitle: String) {
        val mentionText =
            if (noteTitle.contains(' ')) {
                "@\"$noteTitle\" "
            } else {
                "@${noteTitle.replace(' ', '_')} "
            }
        _pendingChatText.value = mentionText
        enterChatMode()
    }

    fun clearPendingChatText() {
        _pendingChatText.value = null
    }

    fun updateMentionState(
        text: String,
        cursorPosition: Int,
    ) {
        chatInputCursorPosition = cursorPosition
        scope.launch {
            val detection = MentionParser.detectActiveMention(text, cursorPosition)
            if (detection.isTypingMention && !detection.isEmailPattern) {
                val suggestions = mentionManager.getSuggestions(detection.query)
                _mentionState.value =
                    MentionState(
                        isActive = true,
                        query = detection.query,
                        triggerIndex = detection.triggerIndex,
                        suggestions = suggestions,
                        highlightedIndex = 0,
                    )
            } else {
                if (_mentionState.value.isActive) {
                    _mentionState.value = MentionState()
                }
            }
        }
    }

    fun onMentionSelected(
        suggestion: MentionSuggestion,
        currentText: String,
    ): String {
        val state = _mentionState.value
        if (!state.isActive || state.triggerIndex < 0) return currentText

        val replacement =
            when (suggestion) {
                is MentionSuggestion.NoteSuggestion -> {
                    val title = suggestion.note.title
                    if (title.contains(' ')) "@\"$title\"" else "@${title.replace(' ', '_')}"
                }
                is MentionSuggestion.TypeFilter -> "@${suggestion.keyword}"
                is MentionSuggestion.CategorySuggestion -> {
                    val name = suggestion.category.name
                    if (name.contains(' ')) "@\"$name\"" else "@${name.replace(' ', '_')}"
                }
                is MentionSuggestion.SpecialFilter -> "@${suggestion.filterName}"
                is MentionSuggestion.CommandSuggestion -> "@${suggestion.commandName}"
            }

        val beforeMention = currentText.substring(0, state.triggerIndex)
        val afterCursor =
            if (chatInputCursorPosition < currentText.length) {
                currentText.substring(chatInputCursorPosition)
            } else {
                ""
            }

        _mentionState.value = MentionState()
        return "$beforeMention$replacement $afterCursor"
    }

    fun dismissMention() {
        _mentionState.value = MentionState()
    }

    fun sendChatMessage(
        content: String,
        attachments: List<Attachment> = emptyList(),
    ) {
        chatQueryDispatcher.dispatchQuery(content, attachments)
    }

    fun callApproval(
        toolId: String,
        approved: Boolean,
        feedback: String? = null,
    ) {
        chatQueryDispatcher.callApproval(toolId, approved, feedback)
    }

    fun generateImageDirect(
        prompt: String,
        aspectRatio: String = "1:1",
    ) {
        chatQueryDispatcher.generateImageDirect(prompt, aspectRatio)
    }

    fun stopGeneration() {
        chatQueryDispatcher.stopGeneration()
    }

    // Removed handleAgentResult as it's specific to the old SmartyAgentOptimized return type
    // Removed legacy helper methods (filterPlanningText, extractSuggestions, etc.)
    // as the server now handles response formatting.

    /**
     * Generate a brief human-readable summary for an agent command.
     */
    private fun getCommandSummary(command: AgentCommand): String =
        when (command) {
            is AgentCommand.AddNote -> "Created note"
            is AgentCommand.SearchNotes -> "Searched notes for \"${command.query}\""
            is AgentCommand.UpdateNote -> "Updated note"
            is AgentCommand.DeleteNote -> "Deleted note"
            is AgentCommand.ArchiveNote -> "Archived note"
            is AgentCommand.ScheduleEvent -> "Scheduled: ${command.title}"
            is AgentCommand.ListEvents -> "Listed calendar events"
            is AgentCommand.DeleteEvent -> "Deleted event"
            is AgentCommand.SetTimer -> if (command.isAlarm) "Set alarm: ${command.name}" else "Set timer: ${command.name}"
            is AgentCommand.LaunchApp -> "Launched app"
            is AgentCommand.TakeScreenshot -> "Took screenshot"
            is AgentCommand.ToggleSetting -> "${command.setting} ${if (command.enable) "on" else "off"}"
            is AgentCommand.PlayAudio -> "Playing: ${command.query}"
            is AgentCommand.ControlAudio -> "Media: ${command.action}"
            is AgentCommand.SeekAudio -> "Seeked media"
            is AgentCommand.StoreContext -> "Saved to memory"
            is AgentCommand.UpdateContext -> "Updated memory"
            is AgentCommand.DeleteContext -> "Removed from memory"
            is AgentCommand.Navigate -> "Navigated to ${command.screen}"
            is AgentCommand.Share -> "Shared content"
            is AgentCommand.ShowBreathing -> "Guided breathing"
            else -> command::class.simpleName ?: "Action"
        }

    fun navigateTo(screen: String) {
        scope.launch {
            onNavigate(screen)
        }
    }

    fun clearNavigationRequest() {
        onNavigate(null)
    }

    fun startProactiveMonitoring(
        unreadCountFlow: StateFlow<Int>,
        cacheSizeFlow: StateFlow<Long>,
    ) {
        // Proactive monitoring and cache suggestions are disabled for cleaner UI
    }

    fun acceptSuggestion() {
        val suggestion = _proactiveSuggestion.value ?: return
        _proactiveSuggestion.value = null
        dispatchQuery(suggestion)
    }

    fun dismissSuggestion() {
        _proactiveSuggestion.value = null
    }

    fun retryFailedMessage(failedMessage: FailedMessage) {
        chatManager.removeFailedMessage(failedMessage)
        dispatchQuery(failedMessage.originalContent, failedMessage.attachments)
    }

    suspend fun deleteMessage(messageId: String): Boolean {
        Log.d(TAG, "deleteMessage: Passing request to ChatManager for messageId: $messageId")
        return try {
            val success = chatManager.deleteMessage(messageId)
            Log.d(TAG, "deleteMessage: ChatManager returned $success for messageId: $messageId")
            success
        } catch (e: Exception) {
            Log.e(TAG, "deleteMessage: Exception thrown by ChatManager for messageId: $messageId - ${e.message}", e)
            false
        }
    }

    fun regenerateResponse(messageId: String) {
        val messages = chatMessages.value
        val messageIndex = messages.indexOfFirst { it.id == messageId }
        if (messageIndex < 0) return

        val smartyMessage = messages[messageIndex]
        if (smartyMessage.role != ChatRole.SMARTY) return

        val userMessageIndex = messageIndex - 1
        if (userMessageIndex < 0) return

        val userMessage = messages[userMessageIndex]
        if (userMessage.role != ChatRole.USER) return

        scope.launch {
            chatManager.deleteMessage(messageId)
        }

        dispatchQuery(userMessage.content, userMessage.attachments)
    }

    fun saveDraft(text: String) {
        chatManager.saveDraft(text)
    }

    /**
     * Submit user's answer to an interactive question.
     */
    fun submitClarification(
        messageId: String,
        response: String,
    ) {
        if (response.isBlank()) return

        // Get the original question for context
        val messages = chatMessages.value
        val msg = messages.find { it.id == messageId }
        val originalQuestion = msg?.clarificationRequest?.question

        // Remove the clarification UI from the message
        if (msg != null) {
            val updatedMsg = msg.copy(clarificationRequest = null)
            scope.launch {
                chatManager.replaceMessage(messageId, updatedMsg)
            }
        }

        // Send the clarification response back to the agent with context
        // Prefix the response so the AI knows this is a clarification answer
        val contextMessage =
            if (originalQuestion != null) {
                "[User's response to clarification question \"$originalQuestion\"]: $response"
            } else {
                "[Clarification response]: $response"
            }
        sendChatMessage(contextMessage, emptyList())
    }

    fun getDraft(): String? = chatManager.getDraft()

    fun clearDraft() {
        chatManager.clearDraft()
    }

    // Callbacks for Smarty
    fun onCitationsFound(citations: List<WebCitation>) {
        pendingCitations.addAll(citations)
    }

    fun onDisplayImages(images: List<ImageDisplayItem>) {
        pendingInlineImages.clear()
        pendingInlineImages.addAll(images)
    }

    fun dispatchQuery(
        query: String,
        attachments: List<Attachment> = emptyList()
    ) {
        chatQueryDispatcher.dispatchQuery(
            content = query,
            attachments = attachments
        )
    }

    fun onPlanStatusChanged(status: String?) {
        if (status != null) {
            _agentActivity.value =
                AgentActivity(
                    type = AgentActivity.Type.THINKING,
                    displayText = status,
                )
        }
    }

    fun onToolExecutionStarted(toolDisplayName: String) {
        _agentActivity.value =
            AgentActivity(
                type = AgentActivity.Type.TOOL_RUNNING,
                displayText = "Using $toolDisplayName...",
                toolName = toolDisplayName,
            )
    }

    fun onToolExecutionCompleted() {
        _agentActivity.value = null
    }

    fun dismissBreathing() {
        _showBreathingOverlay.value = false
    }

    /**
     * Resolves a string that might be a resource key with parameters (e.g., "key|param1|param2")
     */
    private fun resolveResourceString(input: String?): String? {
        if (input == null) return null

        val parts = input.split("|")
        val key = parts[0]
        val resId = application.resources.getIdentifier(key, "string", application.packageName)

        return if (resId != 0) {
            if (parts.size > 1) {
                // Try to parse numeric arguments if possible
                val args =
                    parts
                        .subList(1, parts.size)
                        .map {
                            it.toIntOrNull() ?: it
                        }.toTypedArray<Any>()

                try {
                    application.getString(resId, *args)
                } catch (e: Exception) {
                    // Fallback to raw key if formatting fails
                    input
                }
            } else {
                application.getString(resId)
            }
        } else {
            // Not a resource key, return as is
            input
        }
    }
}
