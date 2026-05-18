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
import com.example.smarty.features.chat.domain.thinking.ParsedResponse
import com.example.smarty.core.domain.model.SearchResultItem
import com.example.smarty.core.domain.model.SearchQueryAnalysis
import com.example.smarty.core.domain.model.RecallResult
import com.example.smarty.R
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
// import io.ktor.client.plugins.contentnegotiation.ContentNegotiation // Removed - not available in minimal Ktor
import io.ktor.client.plugins.sse.SSE
import kotlin.time.Duration.Companion.seconds
// import io.ktor.serialization.kotlinx.json.json // Removed - not available in minimal Ktor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
        private const val COMMAND_LOG_BUFFER_SIZE = 20

        // Validation constants
        private const val MAX_CONTENT_LENGTH = 100_000 // 100KB max for note content
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
        val summary: String, // Safe summary: lengths, IDs, enums, booleans only
        val rejected: Boolean = false,
        val rejectionReason: String? = null,
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
    private fun validateCommand(command: AgentCommand): CommandValidationResult =
        when (command) {
            // === NOTE OPERATIONS ===
            is AgentCommand.AddNote -> {
                when {
                    command.content.isBlank() -> CommandValidationResult.Invalid("content cannot be blank", "content")
                    command.content.length > MAX_CONTENT_LENGTH ->
                        CommandValidationResult.Invalid(
                            "content exceeds max length ($MAX_CONTENT_LENGTH)",
                            "content",
                        )
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.UpdateNote -> {
                val contentVal = command.content
                val titleVal = command.title
                when {
                    command.noteId.isBlank() -> CommandValidationResult.Invalid("noteId cannot be blank", "noteId")
                    contentVal != null && contentVal.length > MAX_CONTENT_LENGTH ->
                        CommandValidationResult.Invalid(
                            "content exceeds max length",
                            "content",
                        )
                    titleVal != null && titleVal.length > MAX_TITLE_LENGTH ->
                        CommandValidationResult.Invalid(
                            "title exceeds max length",
                            "title",
                        )
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
                CommandValidationResult.Valid // No params to validate
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
                    command.packageName.contains(
                        " ",
                    ) -> CommandValidationResult.Invalid("packageName cannot contain whitespace", "packageName")
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
                CommandValidationResult.Valid // No params to validate
            }

            is AgentCommand.GetDeviceInfo -> {
                CommandValidationResult.Valid // No params to validate
            }

            is AgentCommand.GetScreenContext -> {
                CommandValidationResult.Valid // No params to validate
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
                    command.action.lowercase() !in ALLOWED_AUDIO_ACTIONS ->
                        CommandValidationResult.Invalid(
                            "action must be one of: $ALLOWED_AUDIO_ACTIONS",
                            "action",
                        )
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
                    queryVal != null && queryVal.length > MAX_QUERY_LENGTH ->
                        CommandValidationResult.Invalid(
                            "query exceeds max length",
                            "query",
                        )
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
                CommandValidationResult.Valid // Empty list is valid
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
        rejectionReason: String? = null,
    ) {
        val entry =
            CommandLogEntry(
                timestamp = System.currentTimeMillis(),
                commandType = command::class.simpleName ?: "Unknown",
                commandId = command.commandId,
                summary = summarizeCommand(command),
                rejected = rejected,
                rejectionReason = rejectionReason,
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
    private fun summarizeCommand(command: AgentCommand): String =
        when (command) {
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
            is AgentCommand.GetDeviceInfo -> "infoType=${command.infoType}"
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
    }

    private val androidLogger by lazy { AndroidLogger() }
    private val historyCompressor by lazy { com.example.smarty.core.common.util.HistoryCompressor(androidLogger) }

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
        noteOperationsManager.getAllNotes()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val archivedNotes =
        noteOperationsManager.getArchivedNotes()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val allCategories =
        noteOperationsManager.getAllCategories()
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
            HttpClient(OkHttp) {
                install(SSE) {
                    reconnectionTime = 5.seconds
                }
                engine {
                    config {
                        connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                        writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }
            }

        RemoteAgentService(
            client = client,
            eventSink = agentEventSink,
            serverUrlProvider = { securePreferences.getSmartyServerUrl() },
            deviceIdProvider = { securePreferences.getDeviceId() },
        )
    }

    // Agent Event Sink for Koog tools notifications
    private val agentEventSink =
        object : AgentEventSink {
            override fun onToolExecutionStarted(
                toolName: String,
                toolDisplayName: String,
            ) {
                // Show tool execution in UI
                val activityType =
                    when {
                        toolName.contains("search", ignoreCase = true) -> AgentActivity.Type.SEARCHING
                        toolName.contains("analyze", ignoreCase = true) -> AgentActivity.Type.ANALYZING
                        else -> AgentActivity.Type.TOOL_RUNNING
                    }
                _agentActivity.value =
                    AgentActivity(
                        type = activityType,
                        displayText = toolDisplayName,
                        toolName = toolName,
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
                val activityType =
                    when {
                        status.contains("search", ignoreCase = true) -> AgentActivity.Type.SEARCHING
                        status.contains("analyz", ignoreCase = true) -> AgentActivity.Type.ANALYZING
                        else -> AgentActivity.Type.THINKING
                    }
                _agentActivity.value =
                    AgentActivity(
                        type = activityType,
                        displayText = status,
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

            override fun onStateSync(
                syncType: String,
                data: String,
            ) {
                scope.launch {
                    try {
                        when (syncType) {
                            "note_created" -> {
                                val info = Json.decodeFromString<NoteInfo>(data)
                                val category = null // TODO: resolve categoryId to Category object
                                val note =
                                    Note(
                                        id = info.id,
                                        title = info.title,
                                        content = info.content,
                                        categoryId = info.categoryId,
                                        categoryName = null, // TODO: resolve categoryId to name
                                        type = NoteType.BRAIN_DUMP,
                                        createdAt = info.createdAt,
                                        updatedAt = info.updatedAt,
                                        isArchived = info.isArchived,
                                    )
                                repository.insertNote(note)
                            }
                            "timer_set" -> {
                                val info = Json.decodeFromString<TimerInfo>(data)
                                val timer =
                                    SmartyTimer(
                                        id = info.id,
                                        name = info.name,
                                        triggerTime = info.triggerAt,
                                        isAlarm = info.isAlarm,
                                        isActive = info.isActive,
                                        createdAt = info.createdAt,
                                        repeatDays = null, // Server doesn't support recurring yet
                                    )
                                alarmScheduler.scheduleTimer(timer)
                            }
                            "event_scheduled" -> {
                                val info = Json.decodeFromString<CalendarEventInfo>(data)
                                val event =
                                    CalendarEvent(
                                        id = info.id,
                                        title = info.title,
                                        startTime = info.startTime,
                                        endTime = info.endTime,
                                        description = info.description,
                                        reminderMinutes = info.reminderMinutes,
                                        isEventPrivate = false,
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
                        rejectionReason = validation.toLogString(),
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
                        val citations =
                            command.citations.map { proto ->
                                WebCitation(proto.title, proto.url, proto.snippet)
                            }
                        onCitationsFound(citations)
                    }

                    // === ALL OTHER COMMANDS (delegated to transport) ===
                    else -> {
                        scope.launch {
                            // Track this command as an executed action
                            val actionName = command::class.simpleName ?: "Unknown"
                            pendingActions.add(
                                AgentActionResult(
                                    action = actionName,
                                    success = true,
                                    resultSummary = getCommandSummary(command),
                                ),
                            )

                            val result = commandTransport.dispatch(command)
                            // Fire-and-forget: we don't send results back to server anymore
                            // unless specifically required by a future bidirectional tool.
                        }
                    }
                }
            }
        }

    // Client Command Executor for Koog tools actions
    private val clientCommandExecutor =
        object : ClientCommandExecutor {
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
                    contextData =
                        mapOf(
                            "active_note_id" to note.id,
                            "active_note_title" to note.title,
                            "active_note_content" to (note.content ?: ""),
                            "active_note_type" to note.type.name,
                            "current_screen" to currentScreen.value,
                        ),
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
                    unreadMemoryCount = 0, // Placeholder to fix compilation
                )
            }

            override fun addNote(
                content: String,
                category: String?,
            ) {
                scope.launch {
                    noteOperationsManager.addNote(
                        content = content,
                        type = NoteType.BRAIN_DUMP,
                        excludeFromAiChat = false,
                        initialCategory = category,
                    )
                }
            }

            override fun updateNote(
                noteId: String,
                title: String?,
                content: String?,
            ) {
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

            override suspend fun toggleSetting(
                setting: String,
                enable: Boolean,
            ) {
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
                limit: Int,
            ): List<SearchResultItem> {
                return searchFeatureManager.search(query, category, noteType, timeRange, emptySet(), limit)
            }

            override suspend fun advancedSearch(
                query: String,
                algorithm: String,
                limit: Int,
                minScore: Double,
            ): List<SearchResultItem> {
                return searchFeatureManager.advancedSearch(query, algorithm, limit, minScore)
            }

            override fun analyzeQuery(query: String): SearchQueryAnalysis {
                return searchFeatureManager.analyzeQuery(query)
            }

            override suspend fun performRecall(
                query: String,
                minScore: Double,
            ): List<RecallResult> {
                return searchFeatureManager.performRecall(query, minScore)
            }

            override fun requestAudioPlayback(track: AudioTrack) {
                audioFeatureManager.play(track)
            }

            override fun shareContent(
                text: String,
                title: String?,
            ) {
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
                isPrivate: Boolean,
            ) {
                val startMillis = calendarFeatureManager.parseDateTime(startTimeStr) ?: return
                val endMillis =
                    endTimeStr?.let { calendarFeatureManager.parseDateTime(it) }
                        ?: (startMillis + 3600000L)

                calendarFeatureManager.addCalendarEvent(
                    title = title,
                    description = description,
                    startTime = startMillis,
                    endTime = endMillis,
                    location = location,
                    isPrivate = isPrivate,
                )
            }

            override fun deleteCalendarEvent(eventId: String) {
                calendarFeatureManager.deleteCalendarEvent(eventId)
            }

            override suspend fun scheduleEvent(
                title: String,
                startTime: Long,
                endTime: Long,
                description: String?,
            ) {
                calendarFeatureManager.addCalendarEvent(
                    title = title,
                    description = description,
                    startTime = startTime,
                    endTime = endTime,
                    location = null,
                    isPrivate = false,
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

            override fun setTimer(
                name: String,
                timeStr: String,
                isAlarm: Boolean,
            ) {
                val triggerTime = calendarFeatureManager.parseDateTime(timeStr) ?: return
                calendarFeatureManager.setTimer(name, triggerTime, isAlarm)
            }

            override fun cancelTimer(timerId: String) {
                calendarFeatureManager.cancelTimer(timerId)
            }

            override fun addTodoToNote(
                noteId: String,
                text: String,
            ) {
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

            override fun bulkMoveToCategory(
                noteIds: List<String>,
                categoryName: String,
            ) {
                noteOperationsManager.bulkMoveToCategory(noteIds, categoryName)
            }

            override suspend fun storeContext(
                content: String,
                type: String,
            ) {
                // Implementation for storing context
                Log.d(TAG, "Storing context: type=$type")
            }

            override suspend fun updateContext(
                id: String,
                content: String,
                type: String,
            ) {
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
    private val pendingInlineImages = CopyOnWriteArrayList<InlineChatImage>()
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
        dispatchQuery(content, attachments)
    }

    /**
     * Direct image generation via Krea API.
     * Adds user message, shows generating state, calls server, posts result.
     */
    fun generateImageDirect(
        prompt: String,
        aspectRatio: String = "1:1",
    ) {
        if (prompt.isBlank()) return

        scope.launch {
            try {
                chatManager.setProcessing(true)
                chatManager.ensureSession()

                // Add user message
                val userMessage = chatManager.addUserMessage("🎨 Generate image: $prompt")

                // Show streaming placeholder with tool call card
                val streamingMessageId = java.util.UUID.randomUUID().toString()
                chatManager.addSmartyMessage(
                    ChatMessage(
                        id = streamingMessageId,
                        role = ChatRole.SMARTY,
                        content = "",
                        timestamp = System.currentTimeMillis(),
                        isStreaming = true,
                        toolCalls = listOf(
                            com.example.smarty.core.domain.model.AgentToolCallEntry(
                                toolName = "generate_image",
                                status = "started",
                                displayName = "Direct Request",
                                inputSummary = prompt,
                            ),
                        ),
                    ),
                )

                // Show activity indicator
                _agentActivity.value =
                    AgentActivity(
                        type = AgentActivity.Type.TOOL_RUNNING,
                        displayText = "Generating image...",
                        toolName = "generate_image",
                    )

                // Call server
                val result = remoteAgentService.generateImageDirect(prompt, aspectRatio)

                _agentActivity.value = null

                if (result != null && result.success) {
                    // Success — replace with completed tool call carrying the image URL
                    val smartyMessage =
                        ChatMessage(
                            id = streamingMessageId,
                            role = ChatRole.SMARTY,
                            content = "",
                            timestamp = System.currentTimeMillis(),
                            toolCalls = listOf(
                                com.example.smarty.core.domain.model.AgentToolCallEntry(
                                    toolName = "generate_image",
                                    status = "completed",
                                    displayName = "Direct Request",
                                    inputSummary = prompt,
                                    outputSummary = result.url,
                                ),
                            ),
                        )
                    chatManager.replaceMessage(streamingMessageId, smartyMessage)
                    chatManager.markApiCallSuccessful()
                    // Persist with non-blank content so ChatRepository.saveMessage doesn't skip it
                    chatManager.saveMessagePair(
                        userMessage = userMessage,
                        smartyMessage = smartyMessage.copy(content = "Generated image for: $prompt"),
                    )
                } else {
                    val errorMsg = result?.error ?: result?.message ?: "Please try again."
                    val smartyMessage =
                        ChatMessage(
                            id = streamingMessageId,
                            role = ChatRole.SMARTY,
                            content = "❌ Image generation failed: $errorMsg",
                            timestamp = System.currentTimeMillis(),
                            isError = true,
                        )
                    chatManager.replaceMessage(streamingMessageId, smartyMessage)
                    chatManager.markApiCallSuccessful()
                    chatManager.saveMessagePair(
                        userMessage = userMessage,
                        smartyMessage = smartyMessage,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Image generation error: ${e.message}", e)
                _agentActivity.value = null

                val errorMessage =
                    ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        role = ChatRole.SMARTY,
                        content = "❌ Image generation failed: ${e.message}",
                        timestamp = System.currentTimeMillis(),
                    )
                chatManager.addSmartyMessage(errorMessage)
                chatManager.markApiCallSuccessful()
                chatManager.saveMessagePair(
                    userMessage = chatManager.chatMessages.value.lastOrNull { it.isUser } ?: return@launch,
                    smartyMessage = errorMessage,
                )
            } finally {
                chatManager.setProcessing(false)
            }
        }
    }

    fun stopGeneration() {
        Log.d(TAG, "Stopping generation...")
        currentStreamingJob?.cancel()
        currentStreamingJob = null
        chatManager.setProcessing(false)
        _agentActivity.value = null
    }

    fun dispatchQuery(
        content: String,
        attachments: List<Attachment> = emptyList(),
    ) {
        if (content.isBlank() && attachments.isEmpty()) return

        // Critical Fix: Clear pending text immediately to prevent stuck input
        // This ensures the UI doesn't re-populate the field if the user navigates away and back
        _pendingChatText.value = ""

        // Cancel any existing streaming job before starting new one
        currentStreamingJob?.cancel()

        currentStreamingJob =
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
                            val smartyMessage =
                                ChatMessage(id = java.util.UUID.randomUUID().toString(), role = ChatRole.SMARTY, content = commandResult.response, timestamp = System.currentTimeMillis())
                            chatManager.addSmartyMessage(smartyMessage)
                            chatManager.saveMessagePair(
                                userMessage = userMessage,
                                smartyMessage = smartyMessage,
                            )
                            return@launch
                        }
                        is CommandResult.NavigateTo -> {
                            chatManager.markApiCallSuccessful()
                            navigateTo(commandResult.route)
                            val response = application.getString(R.string.navigating_success, commandResult.route)
                            val smartyMessage =
                                ChatMessage(id = java.util.UUID.randomUUID().toString(), role = ChatRole.SMARTY, content = response, timestamp = System.currentTimeMillis())
                            chatManager.addSmartyMessage(smartyMessage)
                            chatManager.saveMessagePair(
                                userMessage = userMessage,
                                smartyMessage = smartyMessage,
                            )
                            return@launch
                        }
                        is CommandResult.HandledAndPassToLLM -> {
                            val localMessage =
                                ChatMessage(id = java.util.UUID.randomUUID().toString(), role = ChatRole.SMARTY, content = commandResult.response, timestamp = System.currentTimeMillis())
                            chatManager.addSmartyMessage(localMessage)
                        }
                        is CommandResult.SavePageRequest -> {
                            systemFeatureManager.captureScreen()
                            chatManager.markApiCallSuccessful()
                            val response = application.getString(R.string.capturing_screenshot)
                            val smartyMessage =
                                ChatMessage(
                                    id = java.util.UUID.randomUUID().toString(),
                                    role = ChatRole.SMARTY,
                                    content = response,
                                    timestamp = System.currentTimeMillis(),
                                )
                            chatManager.addSmartyMessage(smartyMessage)
                            chatManager.saveMessagePair(
                                userMessage = userMessage,
                                smartyMessage = smartyMessage,
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

    private val noteTagRegex = "<note_([a-zA-Z0-9-]+)>".toRegex()
    private val eventTagRegex = "<event_([a-zA-Z0-9-]+)>".toRegex()

    private suspend fun extractAndStripInlineTags(
        builder: StringBuilder,
        messageId: String,
    ) {
        val content = builder.toString()
        var newContent = content
        var hasChanges = false

        noteTagRegex.findAll(content).forEach { matchResult ->
            val noteId = matchResult.groupValues[1]
            newContent = newContent.replace(matchResult.value, "")
            hasChanges = true

            // Asynchronously fetch and map note to UI reference
            scope.launch {
                val dbNote = repository.getNoteById(noteId)
                if (dbNote != null) {
                    val noteRef =
                        com.example.smarty.core.domain.model.NoteReference(
                            noteId = dbNote.id,
                            title = dbNote.title,
                            snippet = dbNote.summary ?: dbNote.content.take(100),
                            category = dbNote.categoryName,
                        )
                    chatManager.updateMessageNoteReferences(messageId, noteRef)
                }
            }
        }

        eventTagRegex.findAll(content).forEach { matchResult ->
            val eventId = matchResult.groupValues[1]
            newContent = newContent.replace(matchResult.value, "")
            hasChanges = true

            // Asynchronously fetch and map event to UI reference
            scope.launch {
                val dbEvent = repository.getCalendarEventById(eventId)
                if (dbEvent != null) {
                    val eventRef =
                        com.example.smarty.core.domain.model.EventReference(
                            eventId = dbEvent.id,
                            title = dbEvent.title,
                            timeSnippet = "Planned Event", // Simplified for now
                            description = dbEvent.description,
                        )
                    chatManager.updateMessageEventReferences(messageId, eventRef)
                }
            }
        }

        if (hasChanges) {
            builder.clear()
            builder.append(newContent)
        }
    }

    private suspend fun processRemoteQuery(
        content: String,
        userMessage: ChatMessage,
    ) {
        // Clear previous state
        pendingCitations.clear()
        pendingInlineImages.clear()
        pendingActions.clear()
        pendingToolCalls.clear() // Fix #10: Clear tool calls for new message
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
            val streamingMessage =
                ChatMessage(
                    id = streamingMessageId,
                    role = ChatRole.SMARTY,
                    content = "",
                    timestamp = System.currentTimeMillis(),
                    isStreaming = true,
                )
            chatManager.addSmartyMessage(streamingMessage)

            // Collect chunks from the remote stream and update UI live
            val responseBuilder = StringBuilder()
            val thinkingBuilder = StringBuilder()
            var capturedConfidence: String? = null // Fix #3: Capture confidence from Result events
            var capturedSourceType: String? = null // Fix #3: Capture sourceType from Result events
            val sessionId = currentSessionId.value
            val personality = securePreferences.getPersonality()
            remoteAgentService.sendQuery(
                query = content,
                sessionId = sessionId,
                personality = personality,
            )
                .collect { event ->
                    when (event) {
                        is AgentEvent.Processing -> {
                            responseBuilder.append(event.content)
                            extractAndStripInlineTags(responseBuilder, streamingMessageId)
                            // Handle thinking from server - replace, not append (server sends full accumulated thinking)
                            event.thinking?.let { thinking ->
                                thinkingBuilder.clear()
                                val cleanThinking =
                                    if (thinking.startsWith(
                                            "SMARTY_TRACE_V2:",
                                        )
                                    ) {
                                        thinking.removePrefix("SMARTY_TRACE_V2:").trim()
                                    } else {
                                        thinking
                                    }
                                thinkingBuilder.append(cleanThinking)
                            }
                            chatManager.updateMessageWithThinking(
                                streamingMessageId,
                                responseBuilder.toString(),
                                thinkingBuilder.toString().ifEmpty { null },
                            )
                        }
                        is AgentEvent.Result -> {
                            // Result contains the COMPLETE final response — use it directly
                            // This prevents duplication from accumulated Processing chunks
                            val finalContent = if (event.content.isNotEmpty()) event.content else responseBuilder.toString()
                            extractAndStripInlineTags(StringBuilder(finalContent), streamingMessageId)
                            event.thinking?.let { thinking ->
                                // Final thinking - replace to ensure clean content
                                thinkingBuilder.clear()
                                val cleanThinking =
                                    if (thinking.startsWith(
                                            "SMARTY_TRACE_V2:",
                                        )
                                    ) {
                                        thinking.removePrefix("SMARTY_TRACE_V2:").trim()
                                    } else {
                                        thinking
                                    }
                                thinkingBuilder.append(cleanThinking)
                            }
                            // Capture citations from Result event (primary source for web search results)
                            if (event.citations.isNotEmpty()) {
                                event.citations.forEach { citation ->
                                    if (pendingCitations.none { it.url == citation.url }) {
                                        pendingCitations.add(WebCitation(citation.title, citation.url, citation.snippet))
                                    }
                                }
                                chatManager.updateMessageCitations(
                                    streamingMessageId,
                                    pendingCitations.map { Citation(title = it.title, url = it.url, snippet = it.snippet) },
                                )
                            }
                            // Fix #3: Capture confidence from Result event directly
                            capturedConfidence = event.confidence ?: capturedConfidence
                            capturedSourceType = event.sourceType ?: capturedSourceType
                            chatManager.updateMessageWithThinking(
                                streamingMessageId,
                                finalContent,
                                thinkingBuilder.toString().ifEmpty { null },
                                capturedConfidence,
                                capturedSourceType,
                            )
                        }
                        is AgentEvent.Error -> {
                            responseBuilder.append("\n[Error: ${event.message}]")
                            chatManager.updateMessageById(streamingMessageId, responseBuilder.toString())
                        }
                        is AgentEvent.ToolCall -> {
                            // Also add to pending actions so it appears inside the thinking block immediately
                            val actionResult =
                                com.example.smarty.core.domain.model.AgentActionResult(
                                    action = event.displayName,
                                    success = event.status == "completed" || event.status == "started",
                                    resultSummary = "Server action ${event.status}",
                                )
                            pendingActions.removeAll { it.action == event.displayName }
                            pendingActions.add(actionResult)
                            chatManager.updateSmartyMessageActions(streamingMessageId, pendingActions.toList())

                            // Also add to pendingToolCalls for ThinkingSection display — include all detail fields
                            val toolCallEntry =
                                com.example.smarty.core.domain.model.AgentToolCallEntry(
                                    toolName = event.toolName,
                                    displayName = event.displayName,
                                    status = event.status,
                                    inputSummary = event.inputSummary,
                                    outputSummary = event.outputSummary,
                                    searchQueries = event.searchQueries.map {
                                        com.example.smarty.core.domain.model.SearchQueryEntry(
                                            query = it.query,
                                            result = it.result,
                                        )
                                    },
                                )
                            pendingToolCalls.removeAll { it.toolName == event.toolName }
                            pendingToolCalls.add(toolCallEntry)
                        }
                        is AgentEvent.Command -> {
                            // Commands handled by eventSink
                        }
                        is AgentEvent.StateSync -> {
                            // State sync handled by eventSink
                        }
                        is AgentEvent.ToolBlocked -> {
                            // Tool blocked - append message to response so AI knows to try different approach
                            responseBuilder.append("\n[System: ${event.reason}]")
                            chatManager.updateMessageById(streamingMessageId, responseBuilder.toString())
                        }
                        is AgentEvent.Question -> {
                            // Create clarification request and add to message
                            val clarification =
                                com.example.smarty.core.domain.model.ClarificationRequest(
                                    question = event.question,
                                    options = event.options ?: emptyList(),
                                    allowCustomInput = event.allowCustom ?: true,
                                )
                            chatManager.updateMessageClarification(streamingMessageId, clarification)
                        }
                        is AgentEvent.NoteBlock -> {
                            // Create note reference and add to message
                            val noteRef =
                                com.example.smarty.core.domain.model.NoteReference(
                                    noteId = event.noteId,
                                    title = event.title,
                                    snippet = event.snippet,
                                    category = event.category,
                                )
                            chatManager.updateMessageNoteReferences(streamingMessageId, noteRef)
                        }
                    }
                }

            val fullResponse = responseBuilder.toString()
            val fullThinking = thinkingBuilder.toString()

            // Debug logging for thinking section verification
            Log.d(
                "ChatFeatureManager",
                "saveMessage: fullThinking length=${fullThinking.length}, hasToolCalls=${fullThinking.contains("[Action:")}",
            )
            if (fullThinking.isNotEmpty()) {
                Log.d("ChatFeatureManager", "saveMessage: fullThinking preview=${fullThinking.take(300)}")
            }

            // Handle success - replace streaming message with final message
            chatManager.markApiCallSuccessful()

            // Use thinking from server events if available, otherwise parse from content
            val parsedResponse =
                if (fullThinking.isNotEmpty()) {
                    var cleanAnswer = fullResponse
                    if (cleanAnswer.startsWith(fullThinking)) {
                        cleanAnswer = cleanAnswer.substring(fullThinking.length).trim()
                    } else if (cleanAnswer.contains(fullThinking)) {
                        cleanAnswer = cleanAnswer.replace(fullThinking, "").trim()
                    }
                    ParsedResponse(fullThinking.trim(), cleanAnswer)
                } else {
                    ThinkingParser.parse(fullResponse)
                }

            // Debug logging for parsed thinking
            Log.d("ChatFeatureManager", "saveMessage: parsedResponse.thinking length=${parsedResponse.thinking?.length}")
            if (parsedResponse.thinking != null) {
                Log.d("ChatFeatureManager", "saveMessage: parsedResponse.thinking preview=${parsedResponse.thinking.take(300)}")
            }

            // Retrieve streaming-accumulated fields before replacing the message
            val streamingMsg = chatManager.chatMessages.value.find { it.id == streamingMessageId }
            val smartyMessage =
                ChatMessage(
                    id = streamingMessageId,
                    role = ChatRole.SMARTY,
                    content = parsedResponse.answer.ifEmpty { "[No response received. Please try again.]" },
                    thinking = parsedResponse.thinking,
                    timestamp = System.currentTimeMillis(),
                    executedActions = pendingActions.toList(),
                    toolCalls = pendingToolCalls.toList(), // Fix #10: Tools not visible - now populated
                    citations = pendingCitations.map { Citation(title = it.title, url = it.url, snippet = it.snippet) },
                    inlineImages = pendingInlineImages.toList(),
                    isStreaming = false,
                    // Fix #3: Use captured confidence from Result event (not from streamingMsg which may be stale)
                    confidence = capturedConfidence ?: streamingMsg?.confidence,
                    sourceType = capturedSourceType ?: streamingMsg?.sourceType,
                    // Feature 2: carry over interactive clarification request
                    clarificationRequest = streamingMsg?.clarificationRequest,
                    // Feature 5: carry over note reference cards
                    noteReferences = streamingMsg?.noteReferences ?: emptyList(),
                )

            // Update the message to its final state (no longer streaming)
            chatManager.replaceMessage(streamingMessageId, smartyMessage)

            chatManager.saveMessagePair(userMessage, smartyMessage)

            if (settingsFeatureManager.isSoundEnabled()) {
                completionSoundManager.playAgentCompletionSound(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Remote query execution failed", e)
            // Update the streaming message to show the error
            chatManager.updateMessageById(
                streamingMessageId,
                application.getString(R.string.error_prefix, e.message ?: "Connection error"),
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
        pendingInlineImages.addAll(
            images.map {
                InlineChatImage(uri = it.uri, fileName = it.fileName, noteTitle = it.noteTitle)
            },
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
                    parts.subList(1, parts.size).map {
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
