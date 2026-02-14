package com.example.smarty.features.chat.domain

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.example.smarty.features.chat.agent.*
import com.example.smarty.features.chat.agent.models.ScreenContext
import com.example.smarty.features.chat.agent.models.WebCitation
import com.example.smarty.features.chat.agent.models.ImageDisplayItem
import com.example.smarty.features.chat.agent.transport.CommandTransport
import com.example.smarty.features.chat.agent.transport.CompositeTransport
import com.example.smarty.features.chat.agent.transport.LocalCommandTransport
import com.example.smarty.features.chat.agent.transport.ShadowRemoteTransport
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
import com.example.smarty.core.common.util.AndroidStringProvider
import com.example.smarty.core.common.util.CompletionSoundManager
import com.example.smarty.core.common.util.ContentTypeDetector
import com.example.smarty.core.common.util.FileStorageHelper
import com.example.smarty.core.common.util.PrivacyGuard
import com.example.smarty.core.common.util.mention.MentionParser
import com.example.smarty.core.common.util.mention.NoteContextBuilder
import com.example.smarty.features.audio.domain.AudioFeatureManager.AudioSearchResult
import com.example.smarty.core.domain.model.SearchResultItem
import com.example.smarty.core.domain.model.SearchQueryAnalysis
import com.example.smarty.core.domain.model.RecallResult
import com.example.smarty.R
import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.header
// import io.ktor.client.plugins.contentnegotiation.ContentNegotiation // Removed - not available in minimal Ktor
import io.ktor.client.plugins.sse.SSE
// import io.ktor.serialization.kotlinx.json.json // Removed - not available in minimal Ktor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
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
import com.example.smarty.features.chat.domain.StyleFeatureManager
import com.example.smarty.features.chat.domain.WorkflowManager
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
    private val onNavigate: (String?) -> Unit
) {
    companion object {
        private const val TAG = "ChatFeatureManager"
        private const val KEY_IS_CHAT_MODE = "isChatMode"
        private const val KEY_CURRENT_SESSION_ID = "currentSessionId"
        private const val COMMAND_LOG_BUFFER_SIZE = 20

        // Validation constants
        private const val MAX_CONTENT_LENGTH = 100_000  // 100KB max for note content
        private const val MAX_TITLE_LENGTH = 500
        private const val MAX_QUERY_LENGTH = 1_000
        private val ALLOWED_AUDIO_ACTIONS = setOf("pause", "resume", "stop", "next", "prev", "toggle")
    }

    // =========================================================================
    // TASK 5: Command Observability
    // =========================================================================

    /**
     * Log entry for emitted AgentCommand objects.
     * Contains only metadata and summaries - never full user content.
     * Task 6: Added rejected flag for validation failures.
     */
    data class CommandLogEntry(
        val timestamp: Long,
        val commandType: String,
        val commandId: String,
        val summary: String,  // Safe summary: lengths, IDs, enums, booleans only
        val rejected: Boolean = false,
        val rejectionReason: String? = null
    ) {
        fun toLogString(): String {
            val prefix = if (rejected) "REJECTED " else ""
            val suffix = if (rejected && rejectionReason != null) " | reason=$rejectionReason" else ""
            return "$prefix[$commandType] id=${commandId.take(8)} | $summary$suffix"
        }
    }

    // =========================================================================
    // TASK 6: Command Validation & Guardrails
    // =========================================================================

    /**
     * Result of command validation.
     * Commands are either Valid or Invalid with a reason.
     */
    sealed class CommandValidationResult {
        object Valid : CommandValidationResult()
        data class Invalid(val reason: String, val field: String? = null) : CommandValidationResult() {
            fun toLogString(): String = if (field != null) "$field: $reason" else reason
        }
    }

    /**
     * Validate an AgentCommand before execution.
     *
     * TOTAL FUNCTION: Every AgentCommand subtype is explicitly handled.
     * No default else branch - unknown commands are rejected.
     *
     * @param command The command to validate
     * @return Valid if command passes all checks, Invalid with reason otherwise
     */
    private fun validateCommand(command: AgentCommand): CommandValidationResult = when (command) {
            // === NOTE OPERATIONS ===
            is AgentCommand.AddNote -> {
                when {
                    command.content.isBlank() -> CommandValidationResult.Invalid("content cannot be blank", "content")
                    command.content.length > MAX_CONTENT_LENGTH -> CommandValidationResult.Invalid("content exceeds max length ($MAX_CONTENT_LENGTH)", "content")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.UpdateNote -> {
                val contentVal = command.content
                val titleVal = command.title
                when {
                    command.noteId.isBlank() -> CommandValidationResult.Invalid("noteId cannot be blank", "noteId")
                    contentVal != null && contentVal.length > MAX_CONTENT_LENGTH -> CommandValidationResult.Invalid("content exceeds max length", "content")
                    titleVal != null && titleVal.length > MAX_TITLE_LENGTH -> CommandValidationResult.Invalid("title exceeds max length", "title")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.DeleteNote -> {
                when {
                    command.noteId.isBlank() -> CommandValidationResult.Invalid("noteId cannot be blank", "noteId")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.ArchiveNote -> {
                when {
                    command.noteId.isBlank() -> CommandValidationResult.Invalid("noteId cannot be blank", "noteId")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.SearchNotes -> {
                when {
                    command.query.length > MAX_QUERY_LENGTH -> CommandValidationResult.Invalid("query exceeds max length", "query")
                    command.limit <= 0 -> CommandValidationResult.Invalid("limit must be positive", "limit")
                    command.limit > 100 -> CommandValidationResult.Invalid("limit exceeds maximum (100)", "limit")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.GetActiveNotes -> {
                CommandValidationResult.Valid  // No params to validate
            }

            // === CONTEXT / PERSONALIZATION ===
            is AgentCommand.StoreContext -> {
                when {
                    command.content.isBlank() -> CommandValidationResult.Invalid("content cannot be blank", "content")
                    command.type.isBlank() -> CommandValidationResult.Invalid("type cannot be blank", "type")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.UpdateContext -> {
                when {
                    command.id.isBlank() -> CommandValidationResult.Invalid("id cannot be blank", "id")
                    command.content.isBlank() -> CommandValidationResult.Invalid("content cannot be blank", "content")
                    command.type.isBlank() -> CommandValidationResult.Invalid("type cannot be blank", "type")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.DeleteContext -> {
                when {
                    command.id.isBlank() -> CommandValidationResult.Invalid("id cannot be blank", "id")
                    else -> CommandValidationResult.Valid
                }
            }

            // === SYSTEM & APP CONTROL ===
            is AgentCommand.LaunchApp -> {
                when {
                    command.packageName.isBlank() -> CommandValidationResult.Invalid("packageName cannot be blank", "packageName")
                    command.packageName.contains(" ") -> CommandValidationResult.Invalid("packageName cannot contain whitespace", "packageName")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.TakeScreenshot -> {
                CommandValidationResult.Valid
            }

            is AgentCommand.ToggleSetting -> {
                when {
                    command.setting.isBlank() -> CommandValidationResult.Invalid("setting cannot be blank", "setting")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.GetSystemStatus -> {
                CommandValidationResult.Valid  // No params to validate
            }

            is AgentCommand.GetScreenContext -> {
                CommandValidationResult.Valid  // No params to validate
            }

            is AgentCommand.SetTimer -> {
                when {
                    command.name.isBlank() -> CommandValidationResult.Invalid("name cannot be blank", "name")
                    command.timeStr.isBlank() -> CommandValidationResult.Invalid("timeStr cannot be blank", "timeStr")
                    else -> CommandValidationResult.Valid
                }
            }

            // === AUDIO CONTROL ===
            is AgentCommand.PlayAudio -> {
                when {
                    command.query.isBlank() -> CommandValidationResult.Invalid("query cannot be blank", "query")
                    command.query.length > MAX_QUERY_LENGTH -> CommandValidationResult.Invalid("query exceeds max length", "query")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.ControlAudio -> {
                when {
                    command.action.isBlank() -> CommandValidationResult.Invalid("action cannot be blank", "action")
                    command.action.lowercase() !in ALLOWED_AUDIO_ACTIONS -> CommandValidationResult.Invalid("action must be one of: $ALLOWED_AUDIO_ACTIONS", "action")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.SeekAudio -> {
                when {
                    command.positionMs < 0 -> CommandValidationResult.Invalid("positionMs must be non-negative", "positionMs")
                    else -> CommandValidationResult.Valid
                }
            }

            // === CALENDAR ===
            is AgentCommand.ScheduleEvent -> {
                when {
                    command.title.isBlank() -> CommandValidationResult.Invalid("title cannot be blank", "title")
                    command.startTime <= 0 -> CommandValidationResult.Invalid("startTime must be positive", "startTime")
                    command.endTime < command.startTime -> CommandValidationResult.Invalid("endTime cannot be before startTime", "endTime")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.ListEvents -> {
                when {
                    command.date <= 0 -> CommandValidationResult.Invalid("date must be positive", "date")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.DeleteEvent -> {
                when {
                    command.eventId.isBlank() -> CommandValidationResult.Invalid("eventId cannot be blank", "eventId")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.AddCalendarEvent -> {
                when {
                    command.title.isBlank() -> CommandValidationResult.Invalid("title cannot be blank", "title")
                    command.title.length > MAX_TITLE_LENGTH -> CommandValidationResult.Invalid("title exceeds max length", "title")
                    command.start.isBlank() -> CommandValidationResult.Invalid("start cannot be blank", "start")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.QueryCalendar -> {
                val queryVal = command.query
                when {
                    queryVal != null && queryVal.length > MAX_QUERY_LENGTH -> CommandValidationResult.Invalid("query exceeds max length", "query")
                    else -> CommandValidationResult.Valid
                }
            }

            // === UI NOTIFICATIONS ===
            is AgentCommand.NotifyToolStarted -> {
                when {
                    command.toolName.isBlank() -> CommandValidationResult.Invalid("toolName cannot be blank", "toolName")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.NotifyToolCompleted -> {
                when {
                    command.toolName.isBlank() -> CommandValidationResult.Invalid("toolName cannot be blank", "toolName")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.NotifyStatus -> {
                when {
                    command.status.isBlank() -> CommandValidationResult.Invalid("status cannot be blank", "status")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.NotifyCitations -> {
                CommandValidationResult.Valid  // Empty list is valid
            }

            is AgentCommand.Navigate -> {
                when {
                    command.screen.isBlank() -> CommandValidationResult.Invalid("screen cannot be blank", "screen")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.Share -> {
                when {
                    command.content.isBlank() -> CommandValidationResult.Invalid("content cannot be blank", "content")
                    else -> CommandValidationResult.Valid
                }
            }

            // NO else BRANCH - Kotlin exhaustive when ensures all subtypes handled
            // If a new AgentCommand subtype is added, this will fail to compile
        }

    // In-memory ring buffer for recent commands (debugging only, no persistence)
    private val commandHistory = ArrayDeque<CommandLogEntry>(COMMAND_LOG_BUFFER_SIZE)
    private val commandHistoryLock = Any()

    /**
     * Log a command with safe summary (no user content).
     * Task 6: Updated to support rejected commands.
     *
     * @param command The command to log
     * @param rejected Whether the command was rejected by validation
     * @param rejectionReason The reason for rejection (if rejected)
     */
    private fun logCommand(
        command: AgentCommand,
        rejected: Boolean = false,
        rejectionReason: String? = null
    ) {
        val entry = CommandLogEntry(
            timestamp = System.currentTimeMillis(),
            commandType = command::class.simpleName ?: "Unknown",
            commandId = command.commandId,
            summary = summarizeCommand(command),
            rejected = rejected,
            rejectionReason = rejectionReason
        )

        // Add to ring buffer
        synchronized(commandHistoryLock) {
            if (commandHistory.size >= COMMAND_LOG_BUFFER_SIZE) {
                commandHistory.removeFirst()
            }
            commandHistory.addLast(entry)
        }

        // Structured log output - use warning level for rejected commands
        if (rejected) {
            Log.w("AgentCommand", entry.toLogString())
        } else {
            Log.d("AgentCommand", entry.toLogString())
        }
    }

    /**
     * Generate safe summary for a command (no user-generated content).
     * Only includes: lengths, IDs, enums, booleans, counts.
     */
    private fun summarizeCommand(command: AgentCommand): String = when (command) {
        // Note operations - content lengths only
        is AgentCommand.AddNote -> "content.len=${command.content.length} | category=${command.category != null}"
        is AgentCommand.UpdateNote -> "noteId=${command.noteId} | hasTitle=${command.title != null} | hasContent=${command.content != null}"
        is AgentCommand.DeleteNote -> "noteId=${command.noteId}"
        is AgentCommand.ArchiveNote -> "noteId=${command.noteId}"
        is AgentCommand.SearchNotes -> "query.len=${command.query.length} | category=${command.category != null} | limit=${command.limit}"
        is AgentCommand.GetActiveNotes -> "(no params)"

        // Context / Personalization
        is AgentCommand.StoreContext -> "content.len=${command.content.length} | type=${command.type}"
        is AgentCommand.UpdateContext -> "id=${command.id} | content.len=${command.content.length} | type=${command.type}"
        is AgentCommand.DeleteContext -> "id=${command.id}"

        // System & app control
        is AgentCommand.LaunchApp -> "packageName.len=${command.packageName.length}"
        is AgentCommand.TakeScreenshot -> "save=${command.save}"
        is AgentCommand.ToggleSetting -> "setting=${command.setting} | enable=${command.enable}"
        is AgentCommand.GetSystemStatus -> "(no params)"
        is AgentCommand.GetScreenContext -> "(no params)"
        is AgentCommand.SetTimer -> "name.len=${command.name.length} | timeStr.len=${command.timeStr.length} | isAlarm=${command.isAlarm}"

        // Audio control
        is AgentCommand.PlayAudio -> "query.len=${command.query.length} | service=${command.service != null}"
        is AgentCommand.ControlAudio -> "action=${command.action}"
        is AgentCommand.SeekAudio -> "positionMs=${command.positionMs}"

        // Calendar
        is AgentCommand.ScheduleEvent -> "title.len=${command.title.length} | duration=${command.endTime - command.startTime}"
        is AgentCommand.ListEvents -> "date=${command.date}"
        is AgentCommand.DeleteEvent -> "eventId=${command.eventId}"
        is AgentCommand.AddCalendarEvent -> "title.len=${command.title.length} | hasEnd=${command.end != null} | hasDesc=${command.description != null}"
        is AgentCommand.QueryCalendar -> "hasQuery=${command.query != null}"

        // UI notifications
        is AgentCommand.NotifyToolStarted -> "toolName.len=${command.toolName.length}"
        is AgentCommand.NotifyToolCompleted -> "toolName.len=${command.toolName.length}"
        is AgentCommand.NotifyStatus -> "status.len=${command.status.length}"
        is AgentCommand.NotifyCitations -> "count=${command.citations.size}"

        // New commands
        is AgentCommand.Navigate -> "screen=${command.screen}"
        is AgentCommand.Share -> "content.len=${command.content.length} | hasTitle=${command.title != null}"
    }

    /**
     * Get recent command log entries for debugging.
     * Returns a copy of the buffer (thread-safe).
     */
    fun getRecentCommands(): List<CommandLogEntry> {
        synchronized(commandHistoryLock) {
            return commandHistory.toList()
        }
    }

    /**
     * Clear command history buffer.
     */
    fun clearCommandHistory() {
        synchronized(commandHistoryLock) {
            commandHistory.clear()
        }
        Log.d("AgentCommand", "Command history cleared")
    }

    private val androidLogger by lazy { AndroidLogger() }
    private val historyCompressor by lazy { com.example.smarty.core.common.util.HistoryCompressor(androidLogger) }
    private val piiMasker by lazy { com.example.smarty.core.common.util.PIIMasker(androidLogger) }

    // Reuse existing ChatManager for basic state and session management
    private val chatManager = ChatManager(
        application,
        chatRepository,
        scope,
        historyCompressor,
        piiMasker
    )

    // --- Internal Managers ---

    private val mentionManager: MentionFeatureManager by lazy {
        MentionFeatureManager(repository)
    }

    private val allNotes = noteOperationsManager.getAllNotes()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val archivedNotes = noteOperationsManager.getArchivedNotes()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val allCategories = noteOperationsManager.getAllCategories()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Local command processor for fast-path handling
    private val localCommandProcessor: LocalCommandProcessor by lazy {
        LocalCommandProcessor(
            context = application,
            getNotes = { allNotes.value },
            getActiveNoteId = { activeNoteId.value },
            systemFeatureManager = systemFeatureManager,
            getDeviceAudio = { audioFeatureManager.getAllAudioTracks() }
        )
    }

    private val noteContextBuilder: NoteContextBuilder by lazy {
        NoteContextBuilder(mentionManager)
    }

    // Task 15: Remote Agent Service (Thin Client)
    // Replaces local SmartyAgentOptimized and SmartyAgentProvider
    private val remoteAgentService: RemoteAgentService by lazy {
        // Initialize Ktor client - SSE only (JSON parsing done manually in RemoteAgentService)
        val client = HttpClient(OkHttp) {
            install(SSE)
            // Add header to bypass ngrok browser warning for public internet access
            install(DefaultRequest) {
                header("ngrok-skip-browser-warning", "true")
            }
        }

        // Connect to local server via USB Reverse Tethering or Emulator Loopback
        // For physical device: Run 'connect_via_usb.bat' (adb reverse tcp:7860 tcp:7860)
        // For emulator: adb reverse is also recommended, but 10.0.2.2 works natively
        // We use 127.0.0.1 to support both provided port forwarding is active
        RemoteAgentService(
            client = client,
            eventSink = agentEventSink,
            serverUrlProvider = { securePreferences.getSmartyServerUrl() },
            deviceIdProvider = { securePreferences.getDeviceId() }
        )
    }

    // Agent Event Sink for Koog tools notifications
    private val agentEventSink = object : AgentEventSink {
        override fun onToolExecutionStarted(toolName: String, toolDisplayName: String) {
            // Show tool execution in UI
            val activityType = when {
                toolName.contains("search", ignoreCase = true) -> AgentActivity.Type.SEARCHING
                toolName.contains("analyze", ignoreCase = true) -> AgentActivity.Type.ANALYZING
                else -> AgentActivity.Type.TOOL_RUNNING
            }
            _agentActivity.value = AgentActivity(
                type = activityType,
                displayText = toolDisplayName,
                toolName = toolName
            )
        }

        override fun onToolExecutionCompleted(toolName: String) {
            // Clear activity when tool completes
            if (_agentActivity.value?.toolName == toolName) {
                _agentActivity.value = null
            }
        }

        override fun onStatusUpdate(status: String) {
            // Show thinking/processing status
            val activityType = when {
                status.contains("search", ignoreCase = true) -> AgentActivity.Type.SEARCHING
                status.contains("analyz", ignoreCase = true) -> AgentActivity.Type.ANALYZING
                else -> AgentActivity.Type.THINKING
            }
            _agentActivity.value = AgentActivity(
                type = activityType,
                displayText = status
            )
        }

        override fun onCitationsFound(citations: List<WebCitation>) {
            this@ChatFeatureManager.onCitationsFound(citations)
        }

        override fun onDisplayImages(images: List<ImageDisplayItem>) {
            this@ChatFeatureManager.onDisplayImages(images)
        }

        override fun onPlanStatusChanged(status: String?) {
            // AI planning status is disabled to reduce visual clutter
            // _aiPlanStatus is no longer updated
        }

        override fun onStateSync(syncType: String, data: String) {
            scope.launch {
                try {
                    when (syncType) {
                        "note_created" -> {
                            val info = Json.decodeFromString<NoteInfo>(data)
                            val category = info.category?.let { repository.getOrCreateCategory(it) }
                            val note = Note(
                                id = info.id,
                                title = info.title,
                                content = info.content,
                                categoryId = category?.id,
                                categoryName = category?.name,
                                type = NoteType.BRAIN_DUMP,
                                createdAt = info.createdAt,
                                updatedAt = info.updatedAt,
                                isArchived = info.isArchived
                            )
                            repository.insertNote(note)
                        }
                        "timer_set" -> {
                            val info = Json.decodeFromString<TimerInfo>(data)
                            val timer = SmartyTimer(
                                id = info.id,
                                name = info.name,
                                triggerTime = info.triggerAt,
                                isAlarm = info.isAlarm,
                                isActive = info.isActive,
                                createdAt = info.createdAt,
                                repeatDays = null // Server doesn't support recurring yet
                            )
                            alarmScheduler.scheduleTimer(timer)
                        }
                        "event_scheduled" -> {
                            val info = Json.decodeFromString<CalendarEventInfo>(data)
                            val event = CalendarEvent(
                                id = info.id,
                                title = info.title,
                                startTime = info.startTime,
                                endTime = info.endTime,
                                description = info.description,
                                reminderMinutes = info.reminderMinutes,
                                isEventPrivate = false
                            )
                            repository.insertCalendarEvent(event)
                        }
                        else -> Log.w(TAG, "Unknown state sync type: $syncType")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle state sync: $syncType", e)
                }
            }
        }

        // 
        // TASK 7: Route commands through transport abstraction
        // Smarty notifications are handled here; action commands go through transport
        // 
        override fun emit(command: AgentCommand) {
            // Task 6: Validate command before execution
            val validation = validateCommand(command)

            if (validation is CommandValidationResult.Invalid) {
                // Log rejected command with reason (remains observable)
                logCommand(
                    command = command,
                    rejected = true,
                    rejectionReason = validation.toLogString()
                )
                // Do not execute - silent rejection, no exception, no feedback to Agent
                return
            }

            // Task 5: Log valid command with safe summaries (no user content)
            logCommand(command)

            // Task 7: Route commands through transport abstraction
            // UI notifications are handled here; action commands go through transport
            when (command) {
                // === UI NOTIFICATIONS (handled locally, not through transport) ===
                is AgentCommand.NotifyToolStarted -> {
                    onToolExecutionStarted(command.toolName, command.displayName)
                }
                is AgentCommand.NotifyToolCompleted -> {
                    onToolExecutionCompleted(command.toolName)
                }
                is AgentCommand.NotifyStatus -> {
                    onStatusUpdate(command.status)
                }
                is AgentCommand.NotifyCitations -> {
                    val citations = command.citations.map { proto ->
                        WebCitation(proto.title, proto.url, proto.snippet)
                    }
                    onCitationsFound(citations)
                }

                // === ALL OTHER COMMANDS (delegated to transport) ===
                else -> {
                        scope.launch {
                            // Track this command as an executed action
                            val actionName = command::class.simpleName ?: "Unknown"
                            pendingActions.add(AgentActionResult(
                                action = actionName,
                                success = true,
                                resultSummary = getCommandSummary(command)
                            ))

                            val result = commandTransport.dispatch(command)
                                // Fire-and-forget: we don't send results back to server anymore
                                // unless specifically required by a future bidirectional tool.

                        }
                }
            }
        }
    }

    // Client Command Executor for Koog tools actions
    private val clientCommandExecutor = object : ClientCommandExecutor {
        override fun getActiveNotes(): List<Note> {
            val rawNotes = allNotes.value
            return PrivacyGuard.getAiVisibleNotes(rawNotes)
        }
        override fun getArchivedNotes(): List<Note> = PrivacyGuard.getAiVisibleNotes(archivedNotes.value)
        override fun getCategories(): List<Category> = allCategories.value
        override fun getScreenContext(): ScreenContext? {
            val activeId = activeNoteId.value ?: return null
            val note = allNotes.value.find { it.id == activeId } ?: return null

            return ScreenContext(
                selectedText = null,
                referringApp = application.packageName,
                capturedAt = System.currentTimeMillis(),
                contextData = mapOf(
                    "active_note_id" to note.id,
                    "active_note_title" to note.title,
                    "active_note_content" to (note.content ?: ""),
                    "active_note_type" to note.type.name,
                    "current_screen" to currentScreen.value
                )
            )
        }

        override suspend fun getDeviceAudio(): List<AudioTrack> = systemFeatureManager.getDeviceAudio()

        override fun navigateTo(screen: String) {
            this@ChatFeatureManager.navigateTo(screen)
        }

        override fun getCurrentScreen(): String = currentScreen.value

        override fun getSystemStatus(): Map<String, String> {
            return systemFeatureManager.getSystemStatus(
                isDarkTheme = isDarkTheme.value,
                connectionStatus = connectionStatus.value.name,
                cacheSize = ContentTypeDetector.formatFileSize(application, cacheSizeBytes.value),
                unreadMemoryCount = 0 // Placeholder to fix compilation
            )
        }

        override fun addNote(content: String, category: String?) {
            scope.launch {
                noteOperationsManager.addNote(
                    content = content,
                    type = NoteType.BRAIN_DUMP,
                    excludeFromAiChat = false,
                    initialCategory = category
                )
            }
        }

        override fun updateNote(noteId: String, title: String?, content: String?) {
            scope.launch {
                val target = allNotes.value.find { it.id == noteId } ?: archivedNotes.value.find { it.id == noteId }
                if (target != null && target.isPrivate) {
                    Log.w(TAG, "SECURITY: Blocked Agent modify on private note: $noteId")
                    return@launch
                }
                noteOperationsManager.updateNote(noteId, title, content, allNotes.value, archivedNotes.value)
            }
        }

        override fun deleteNoteById(noteId: String) {
            scope.launch {
                val target = allNotes.value.find { it.id == noteId } ?: archivedNotes.value.find { it.id == noteId }
                if (target != null && target.isPrivate) {
                    Log.w(TAG, "SECURITY: Blocked Agent modify on private note: $noteId")
                    return@launch
                }
                noteOperationsManager.deleteNoteById(noteId, allNotes.value, archivedNotes.value)
            }
        }

        override fun archiveNote(noteId: String) {
            val target = allNotes.value.find { it.id == noteId } ?: archivedNotes.value.find { it.id == noteId }
            if (target != null && target.isPrivate) {
                Log.w(TAG, "SECURITY: Blocked Agent modify on private note: $noteId")
                return
            }
            noteOperationsManager.archiveNote(noteId)
        }

        override fun unarchiveNote(noteId: String) {
            val target = allNotes.value.find { it.id == noteId } ?: archivedNotes.value.find { it.id == noteId }
            if (target != null && target.isPrivate) {
                Log.w(TAG, "SECURITY: Blocked Agent modify on private note: $noteId")
                return
            }
            noteOperationsManager.unarchiveNote(noteId)
        }

        override fun summarizeNote(noteId: String) {
            noteOperationsManager.summarizeNote(noteId, allNotes.value, archivedNotes.value)
        }

        override suspend fun processNoteWithAi(note: Note) {
            noteOperationsManager.processNoteWithAi(note)
        }

        override suspend fun onCreateCategory(name: String): Category {
            return noteOperationsManager.getOrCreateCategory(name)
        }

        override suspend fun getCategoryStats(): List<CategoryStatInfo> {
            return noteOperationsManager.getCategoryStats(allCategories.value, allNotes.value)
        }

        override fun toggleTheme(isDark: Boolean) {
            systemFeatureManager.toggleTheme(isDark)
        }

        override suspend fun toggleSetting(setting: String, enable: Boolean) {
            systemFeatureManager.toggleSetting(setting, enable)
        }

        override suspend fun takeScreenshot(save: Boolean) {
            // Screen capture is handled by SystemFeatureManager
            systemFeatureManager.captureScreen()
        }

        override fun clearCache() {
            systemFeatureManager.clearCache()
        }

        override fun backupData() {
            systemFeatureManager.backupData()
        }

        override fun setPrivacyMode(mode: String) {
            systemFeatureManager.setPrivacyMode(mode)
        }

        override suspend fun searchNotes(
            query: String,
            category: String?,
            noteType: String?,
            timeRange: String,
            limit: Int
        ): List<SearchResultItem> {
            return searchFeatureManager.search(query, category, noteType, timeRange, emptySet(), limit)
        }

        override suspend fun advancedSearch(
            query: String,
            algorithm: String,
            limit: Int,
            minScore: Double
        ): List<SearchResultItem> {
            return searchFeatureManager.advancedSearch(query, algorithm, limit, minScore)
        }

        override fun analyzeQuery(query: String): SearchQueryAnalysis {
            return searchFeatureManager.analyzeQuery(query)
        }

        override suspend fun performRecall(query: String, minScore: Double): List<RecallResult> {
            return searchFeatureManager.performRecall(query, minScore)
        }

        override fun requestAudioPlayback(track: AudioTrack) {
            audioFeatureManager.play(track)
        }

        override fun shareContent(text: String, title: String?) {
            systemFeatureManager.shareContent(text, title)
        }

        override fun launchApp(packageName: String) {
            systemFeatureManager.launchApp(packageName)
        }

        override fun findPackageName(appName: String): String? {
            return systemFeatureManager.findPackageName(appName)
        }

        override suspend fun findMatchingAudio(query: String): AudioSearchResult {
            return audioFeatureManager.findAudioTrack(query)
        }

        override suspend fun controlAudio(action: String) {
            when (action.lowercase()) {
                "pause" -> audioFeatureManager.pause()
                "resume" -> audioFeatureManager.resume()
                "stop" -> audioFeatureManager.stop()
                "toggle" -> audioFeatureManager.togglePlayPause()
                "next" -> audioFeatureManager.next()
                "previous", "prev" -> audioFeatureManager.previous()
            }
        }

        override suspend fun seekAudio(positionMs: Long) {
            audioFeatureManager.seekTo(positionMs)
        }

        override fun playAudioList(tracks: List<AudioTrack>) {
            audioFeatureManager.playList(tracks)
        }

        override fun pauseAudioPlayback() {
            audioFeatureManager.pause()
        }

        override fun resumeAudioPlayback() {
            audioFeatureManager.resume()
        }

        override fun stopAudioPlayback() {
            audioFeatureManager.stop()
        }

        override fun seekAudioTo(positionMs: Long) {
            audioFeatureManager.seekTo(positionMs)
        }

        override fun toggleAudioPlayback() {
            audioFeatureManager.togglePlayPause()
        }

        override fun nextTrack() {
            audioFeatureManager.next()
        }

        override fun previousTrack() {
            audioFeatureManager.previous()
        }

        override fun getCurrentAudioTrack(): AudioTrack? {
            return audioFeatureManager.getCurrentTrack()
        }

        override fun getCurrentAudioPosition(): Long {
            return audioFeatureManager.getCurrentPosition()
        }

        override fun getAudioDuration(): Long {
            return audioFeatureManager.getDuration()
        }

        override fun isAudioPlaying(): Boolean {
            return audioFeatureManager.isPlaying()
        }

        override fun addCalendarEvent(
            title: String,
            startTimeStr: String,
            endTimeStr: String?,
            description: String?,
            location: String?,
            isPrivate: Boolean
        ) {
            val startMillis = calendarFeatureManager.parseDateTime(startTimeStr) ?: return
            val endMillis = endTimeStr?.let { calendarFeatureManager.parseDateTime(it) }
                ?: (startMillis + 3600000L)

            calendarFeatureManager.addCalendarEvent(
                title = title,
                description = description,
                startTime = startMillis,
                endTime = endMillis,
                location = location,
                isPrivate = isPrivate
            )
        }

        override fun deleteCalendarEvent(eventId: String) {
            calendarFeatureManager.deleteCalendarEvent(eventId)
        }

        override suspend fun scheduleEvent(title: String, startTime: Long, endTime: Long, description: String?) {
            calendarFeatureManager.addCalendarEvent(
                title = title,
                description = description,
                startTime = startTime,
                endTime = endTime,
                location = null,
                isPrivate = false
            )
        }

        override suspend fun listEvents(date: Long): List<CalendarEvent> {
            return calendarFeatureManager.getEventsForDay(date)
        }

        override suspend fun deleteEvent(eventId: String) {
            calendarFeatureManager.deleteCalendarEvent(eventId)
        }

        override suspend fun queryCalendarEvents(query: String?): List<CalendarEvent> {
            return if (query.isNullOrBlank()) {
                calendarFeatureManager.getTodayEvents()
            } else {
                calendarFeatureManager.searchEvents(query)
            }
        }

        override fun bulkDeleteEvents(eventIds: List<String>) {
            eventIds.forEach { id ->
                calendarFeatureManager.deleteCalendarEvent(id)
            }
        }

        override fun setTimer(name: String, timeStr: String, isAlarm: Boolean) {
            val triggerTime = calendarFeatureManager.parseDateTime(timeStr) ?: return
            calendarFeatureManager.setTimer(name, triggerTime, isAlarm)
        }

        override fun cancelTimer(timerId: String) {
            calendarFeatureManager.cancelTimer(timerId)
        }

        override fun addTodoToNote(noteId: String, text: String) {
            scope.launch {
                val target = allNotes.value.find { it.id == noteId } ?: archivedNotes.value.find { it.id == noteId }
                if (target != null && target.isPrivate) {
                    Log.w(TAG, "SECURITY: Blocked Agent modify on private note: $noteId")
                    return@launch
                }
                noteOperationsManager.addTodoToNote(noteId, text)
            }
        }

        override fun bulkArchiveNotes(noteIds: List<String>) {
            noteOperationsManager.bulkArchiveNotes(noteIds)
        }

        override fun bulkDeleteNotes(noteIds: List<String>) {
            noteOperationsManager.bulkDeleteNotes(noteIds, allNotes.value, archivedNotes.value)
        }

        override fun bulkMoveToCategory(noteIds: List<String>, categoryName: String) {
            noteOperationsManager.bulkMoveToCategory(noteIds, categoryName)
        }

        override suspend fun storeContext(content: String, type: String) {
            // Implementation for storing context
            Log.d(TAG, "Storing context: type=$type")
        }

        override suspend fun updateContext(id: String, content: String, type: String) {
            // Implementation for updating context
            Log.d(TAG, "Updating context: id=$id, type=$type")
        }

        override suspend fun deleteContext(id: String) {
            // Implementation for deleting context
            Log.d(TAG, "Deleting context: id=$id")
        }
    }

    // Task 7: Command transport for delivering validated commands to execution
    // Task 8: CompositeTransport with shadow mode disabled by default
    // To enable shadow mode for debugging, change shadow = null to shadow = ShadowRemoteTransport()
    private val commandTransport: CommandTransport by lazy {
        CompositeTransport(
            primary = LocalCommandTransport(clientCommandExecutor, scope),
            shadow = null  // Disabled by default; set to ShadowRemoteTransport() for debugging
        )
    }

    // Exposed flows from ChatManager
    val isChatMode: StateFlow<Boolean> = chatManager.isChatMode
    val chatMessages: StateFlow<List<ChatMessage>> = chatManager.chatMessages
    val isChatProcessing: StateFlow<Boolean> = chatManager.isChatProcessing
    val currentSessionId: StateFlow<String?> = chatManager.currentSessionId
    val chatSessions: StateFlow<List<ChatSession>> = chatManager.chatSessions

    // Mention State
    private val _mentionState = MutableStateFlow(MentionState())
    val mentionState: StateFlow<MentionState> = _mentionState.asStateFlow()
    private var chatInputCursorPosition: Int = 0

    // Pending Text
    private val _pendingChatText = MutableStateFlow<String?>(null)
    val pendingChatText: StateFlow<String?> = _pendingChatText.asStateFlow()

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
        val timestamp: Long = System.currentTimeMillis()
    ) {
        enum class Type {
            THINKING,      // General processing/thinking
            TOOL_RUNNING,  // A specific tool is executing
            SEARCHING,     // Web search in progress
            ANALYZING      // Analyzing content
        }
    }

    private val _agentActivity = MutableStateFlow<AgentActivity?>(null)
    val agentActivity: StateFlow<AgentActivity?> = _agentActivity.asStateFlow()

    // Internal state for current response
    private val pendingCitations = CopyOnWriteArrayList<WebCitation>()
    private val pendingInlineImages = CopyOnWriteArrayList<InlineChatImage>()
    private val pendingActions = CopyOnWriteArrayList<AgentActionResult>()

    init {
        chatManager.initialize()
    }

    fun toggleChatMode(fromShake: Boolean = false) {
        chatManager.toggleChatMode()
        savedStateHandle[KEY_IS_CHAT_MODE] = !isChatMode.value
    }

    /** Update the current tool name display */
    fun updateCurrentToolName(name: String?) {
        // Disabled UI indicator
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
    }

    fun clearChatHistory() {
        chatManager.clearChatHistory()
    }

    fun enterChatWithNoteReference(noteTitle: String) {
        val mentionText = if (noteTitle.contains(' ')) {
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

    fun updateMentionState(text: String, cursorPosition: Int) {
        chatInputCursorPosition = cursorPosition
        scope.launch {
            val detection = MentionParser.detectActiveMention(text, cursorPosition)
            if (detection.isTypingMention && !detection.isEmailPattern) {
                val suggestions = mentionManager.getSuggestions(detection.query)
                _mentionState.value = MentionState(
                    isActive = true,
                    query = detection.query,
                    triggerIndex = detection.triggerIndex,
                    suggestions = suggestions,
                    highlightedIndex = 0
                )
            } else {
                if (_mentionState.value.isActive) {
                    _mentionState.value = MentionState()
                }
            }
        }
    }

    fun onMentionSelected(suggestion: MentionSuggestion, currentText: String): String {
        val state = _mentionState.value
        if (!state.isActive || state.triggerIndex < 0) return currentText

        val replacement = when (suggestion) {
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
        val afterCursor = if (chatInputCursorPosition < currentText.length) {
            currentText.substring(chatInputCursorPosition)
        } else ""

        _mentionState.value = MentionState()
        return "$beforeMention$replacement $afterCursor"
    }

    fun dismissMention() {
        _mentionState.value = MentionState()
    }

    fun sendChatMessage(content: String, attachments: List<Attachment> = emptyList()) {
        dispatchQuery(content, attachments)
    }

    fun dispatchQuery(content: String, attachments: List<Attachment> = emptyList()) {
        if (content.isBlank() && attachments.isEmpty()) return

        // Critical Fix: Clear pending text immediately to prevent stuck input
        // This ensures the UI doesn't re-populate the field if the user navigates away and back
        _pendingChatText.value = ""

        scope.launch {
            var processingSet = false
            try {
                // Set processing state with error handling
                try {
                    chatManager.setProcessing(true)
                    processingSet = true
                    chatManager.resetApiCallFlag()
                    chatManager.ensureSession()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to initialize chat processing: ${e.message}")
                    // Continue anyway, but mark that we couldn't set processing state
                    processingSet = false
                }

                val userMessage = chatManager.addUserMessage(content, attachments)

                // 1. FAST-PATH: Local Command Processor
                val commandResult = localCommandProcessor.process(content)
                when (commandResult) {
                    is CommandResult.Handled -> {
                        chatManager.markApiCallSuccessful()
                        val smartyMessage = ChatMessage(id = java.util.UUID.randomUUID().toString(), role = ChatRole.SMARTY, content = commandResult.response, timestamp = System.currentTimeMillis())
                        chatManager.addSmartyMessage(smartyMessage)
                        chatManager.saveMessagePair(
                            userMessage = userMessage,
                            smartyMessage = smartyMessage
                        )
                        return@launch
                    }
                    is CommandResult.NavigateTo -> {
                        chatManager.markApiCallSuccessful()
                        navigateTo(commandResult.route)
                        val response = application.getString(R.string.navigating_success, commandResult.route)
                        val smartyMessage = ChatMessage(id = java.util.UUID.randomUUID().toString(), role = ChatRole.SMARTY, content = response, timestamp = System.currentTimeMillis())
                        chatManager.addSmartyMessage(smartyMessage)
                        chatManager.saveMessagePair(
                            userMessage = userMessage,
                            smartyMessage = smartyMessage
                        )
                        return@launch
                    }
                    is CommandResult.HandledAndPassToLLM -> {
                        val localMessage = ChatMessage(id = java.util.UUID.randomUUID().toString(), role = ChatRole.SMARTY, content = commandResult.response, timestamp = System.currentTimeMillis())
                        chatManager.addSmartyMessage(localMessage)
                    }
                    is CommandResult.SavePageRequest -> {
                        systemFeatureManager.captureScreen()
                        chatManager.markApiCallSuccessful()
                        val response = application.getString(R.string.capturing_screenshot)
                        val smartyMessage = ChatMessage(
                            id = java.util.UUID.randomUUID().toString(),
                            role = ChatRole.SMARTY,
                            content = response,
                            timestamp = System.currentTimeMillis()
                        )
                        chatManager.addSmartyMessage(smartyMessage)
                        chatManager.saveMessagePair(
                            userMessage = userMessage,
                            smartyMessage = smartyMessage
                        )
                        return@launch
                    }
                    else -> Log.d(TAG, "Falling back to REASONING-PATH")
                }

                // 2. REMOTE-PATH
                processRemoteQuery(content, userMessage)

            } catch (e: Exception) {
                Log.e(TAG, "Error in dispatcher: ${e.message}", e)
                chatManager.addSmartyMessage(ChatMessage(id = java.util.UUID.randomUUID().toString(), role = ChatRole.SMARTY, content = application.getString(R.string.error_prefix, e.message ?: application.getString(R.string.unknown_error)), timestamp = System.currentTimeMillis()))
            } finally {
                // Clear agent activity state
                _agentActivity.value = null

                // Safely reset processing state only if we successfully set it
                if (processingSet) {
                    try {
                        // Use NonCancellable to ensure processing state is always reset
                        // but wrap in try-catch to prevent crashes during cleanup
                        withContext(NonCancellable) {
                            chatManager.setProcessing(false)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to reset processing state: ${e.message}")
                        // Fallback: try direct assignment
                        try {
                            chatManager.setProcessing(false)
                        } catch (fallbackE: Exception) {
                            Log.e(TAG, "Complete failure to reset processing state: ${fallbackE.message}")
                        }
                    }
                }
            }
        }
    }

    private suspend fun processRemoteQuery(content: String, userMessage: ChatMessage) {
        // Clear previous state
        pendingCitations.clear()
        pendingInlineImages.clear()
        pendingActions.clear()
        _mentionState.value = MentionState()

        // Prepare UI
        chatManager.setProcessing(true)

        // Task 15: Thin Client Mode
        // We delegate all logic to the Remote Agent Service via SSE.
        // Local logic (history compression, context building) is handled by the server now.

        // Create a streaming placeholder message that updates live
        val streamingMessageId = java.util.UUID.randomUUID().toString()

        try {
            // Add a "thinking" placeholder message immediately
            val streamingMessage = ChatMessage(
                id = streamingMessageId,
                role = ChatRole.SMARTY,
                content = "",
                timestamp = System.currentTimeMillis(),
                isStreaming = true
            )
            chatManager.addSmartyMessage(streamingMessage)

            // Collect chunks from the remote stream and update UI live
            val responseBuilder = StringBuilder()
            val sessionId = currentSessionId.value

            remoteAgentService.sendQuery(
                query = content,
                sessionId = sessionId
            )
                .collect { chunk ->
                    responseBuilder.append(chunk)
                    // Update the streaming message content live
                    chatManager.updateMessageById(streamingMessageId, responseBuilder.toString())
                }

            val fullResponse = responseBuilder.toString()

            // Handle success - replace streaming message with final message
            chatManager.markApiCallSuccessful()

            val smartyMessage = ChatMessage(
                id = streamingMessageId,
                role = ChatRole.SMARTY,
                content = fullResponse.ifEmpty { "[No response received. Please try again.]" },
                timestamp = System.currentTimeMillis(),
                executedActions = pendingActions.toList(),
                citations = pendingCitations.map { Citation(title = it.title, url = it.url, snippet = it.snippet) },
                inlineImages = pendingInlineImages.toList(),
                isStreaming = false
            )

            // Update the message to its final state (no longer streaming)
            chatManager.updateMessageById(streamingMessageId, smartyMessage.content)

            chatManager.saveMessagePair(userMessage, smartyMessage)

            if (settingsFeatureManager.isSoundEnabled()) {
                completionSoundManager.playAgentCompletionSound(true)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Remote query execution failed", e)
            // Update the streaming message to show the error
            chatManager.updateMessageById(
                streamingMessageId,
                application.getString(R.string.error_prefix, e.message ?: "Connection error")
            )
        }
    }

    // Removed handleAgentResult as it's specific to the old SmartyAgentOptimized return type
    // Removed legacy helper methods (filterPlanningText, extractSuggestions, etc.)
    // as the server now handles response formatting.

    /**
     * Generate a brief human-readable summary for an agent command.
     */
    private fun getCommandSummary(command: AgentCommand): String {
        return when (command) {
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
            else -> command::class.simpleName ?: "Action"
        }
    }

    fun navigateTo(screen: String) {
        scope.launch {
            onNavigate(screen)
        }
    }

    fun clearNavigationRequest() {
        onNavigate(null)
    }

    fun startProactiveMonitoring(unreadCountFlow: StateFlow<Int>, cacheSizeFlow: StateFlow<Long>) {
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

    // Callbacks for Smarty
    fun onCitationsFound(citations: List<WebCitation>) {
        pendingCitations.addAll(citations)
    }

    fun onDisplayImages(images: List<ImageDisplayItem>) {
        pendingInlineImages.clear()
        pendingInlineImages.addAll(images.map {
            InlineChatImage(uri = it.uri, fileName = it.fileName, noteTitle = it.noteTitle)
        })
    }

    fun onPlanStatusChanged(status: String?) {
        // Disabled UI indicator
    }

    fun onToolExecutionStarted(toolDisplayName: String) {
        // Disabled UI indicator
    }

    fun onToolExecutionCompleted() {
        // Disabled UI indicator
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
                val args = parts.subList(1, parts.size).map {
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

