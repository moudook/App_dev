package com.example.smarty.viewmodel.managers

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.example.smarty.agent.*
import com.example.smarty.agent.models.ScreenContext
import com.example.smarty.agent.transport.CommandTransport
import com.example.smarty.agent.transport.CompositeTransport
import com.example.smarty.agent.transport.LocalCommandTransport
import com.example.smarty.agent.transport.ShadowRemoteTransport
import com.example.smarty.protocol.AgentCommand
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.SmartyDatabase
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.model.*
import com.example.smarty.data.remote.RemoteAgentService
import com.example.smarty.data.remote.providers.TavilySearchProvider
import com.example.smarty.data.repository.ChatRepository
import com.example.smarty.service.AlarmScheduler
import com.example.smarty.service.CommandResult
import com.example.smarty.service.LocalCommandProcessor
import com.example.smarty.ui.components.ConnectionStatus
import com.example.smarty.util.AndroidLogger
import com.example.smarty.util.AndroidStringProvider
import com.example.smarty.util.CompletionSoundManager
import com.example.smarty.util.ContentTypeDetector
import com.example.smarty.util.FileStorageHelper
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.ThinkingParser
import com.example.smarty.util.api.GroqKeyManager
import com.example.smarty.util.api.RateLimiter
import com.example.smarty.util.mention.MentionParser
import com.example.smarty.util.mention.NoteContextBuilder
import com.example.smarty.util.mention.ThinkingModeProcessor
import com.example.smarty.viewmodel.managers.AudioFeatureManager.AudioSearchResult
import com.example.smarty.R
import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
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
import com.example.smarty.data.repository.SmartyRepository

class ChatFeatureManager(
    private val application: Application,
    private val scope: CoroutineScope,
    private val chatRepository: ChatRepository,
    private val repository: SmartyRepository,
    private val database: SmartyDatabase,
    private val securePreferences: SecurePreferences,
    private val groqKeyManager: GroqKeyManager,
    private val tavilySearchProvider: TavilySearchProvider,
    private val settingsFeatureManager: SettingsFeatureManager,
    private val noteOperationsManager: NoteOperationsManager,
    private val systemFeatureManager: SystemFeatureManager,
    private val completionSoundManager: CompletionSoundManager,
    private val alarmScheduler: AlarmScheduler,
    private val executionPlanManager: ExecutionPlanManager,
    private val rateLimiter: RateLimiter,
    private val memoryFeatureManager: MemoryFeatureManager,
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
    private val unreadForMemoryCount: StateFlow<Int>
) {
    companion object {
        private const val TAG = "ChatFeatureManager"
        private const val KEY_IS_CHAT_MODE = "isChatMode"
        private const val KEY_CURRENT_SESSION_ID = "currentSessionId"
        private const val COMMAND_LOG_BUFFER_SIZE = 20
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

    // Validation constants
    private companion object ValidationLimits {
        const val MAX_CONTENT_LENGTH = 100_000  // 100KB max for note content
        const val MAX_TITLE_LENGTH = 500
        const val MAX_QUERY_LENGTH = 1_000
        val ALLOWED_AUDIO_ACTIONS = setOf("pause", "resume", "stop", "next", "prev", "toggle")
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
    private fun validateCommand(command: AgentCommand): CommandValidationResult {
        return when (command) {
            // === NOTE OPERATIONS ===
            is AgentCommand.AddNote -> {
                when {
                    command.content.isBlank() -> CommandValidationResult.Invalid("content cannot be blank", "content")
                    command.content.length > MAX_CONTENT_LENGTH -> CommandValidationResult.Invalid("content exceeds max length ($MAX_CONTENT_LENGTH)", "content")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.UpdateNote -> {
                when {
                    command.noteId.isBlank() -> CommandValidationResult.Invalid("noteId cannot be blank", "noteId")
                    command.content != null && command.content.length > MAX_CONTENT_LENGTH -> CommandValidationResult.Invalid("content exceeds max length", "content")
                    command.title != null && command.title.length > MAX_TITLE_LENGTH -> CommandValidationResult.Invalid("title exceeds max length", "title")
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

            // === SYSTEM & APP CONTROL ===
            is AgentCommand.LaunchApp -> {
                when {
                    command.packageName.isBlank() -> CommandValidationResult.Invalid("packageName cannot be blank", "packageName")
                    command.packageName.contains(" ") -> CommandValidationResult.Invalid("packageName cannot contain whitespace", "packageName")
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

            // === CALENDAR ===
            is AgentCommand.AddCalendarEvent -> {
                when {
                    command.title.isBlank() -> CommandValidationResult.Invalid("title cannot be blank", "title")
                    command.title.length > MAX_TITLE_LENGTH -> CommandValidationResult.Invalid("title exceeds max length", "title")
                    command.start.isBlank() -> CommandValidationResult.Invalid("start cannot be blank", "start")
                    else -> CommandValidationResult.Valid
                }
            }

            is AgentCommand.QueryCalendar -> {
                when {
                    command.query != null && command.query.length > MAX_QUERY_LENGTH -> CommandValidationResult.Invalid("query exceeds max length", "query")
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

            // NO else BRANCH - Kotlin exhaustive when ensures all subtypes handled
            // If a new AgentCommand subtype is added, this will fail to compile
        }
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

        // System & app control
        is AgentCommand.LaunchApp -> "packageName.len=${command.packageName.length}"
        is AgentCommand.GetSystemStatus -> "(no params)"
        is AgentCommand.GetScreenContext -> "(no params)"
        is AgentCommand.SetTimer -> "name.len=${command.name.length} | timeStr.len=${command.timeStr.length} | isAlarm=${command.isAlarm}"

        // Audio control
        is AgentCommand.PlayAudio -> "query.len=${command.query.length} | service=${command.service != null}"
        is AgentCommand.ControlAudio -> "action=${command.action}"

        // Calendar
        is AgentCommand.AddCalendarEvent -> "title.len=${command.title.length} | hasEnd=${command.end != null} | hasDesc=${command.description != null}"
        is AgentCommand.QueryCalendar -> "hasQuery=${command.query != null}"

        // UI notifications
        is AgentCommand.NotifyToolStarted -> "toolName.len=${command.toolName.length}"
        is AgentCommand.NotifyToolCompleted -> "toolName.len=${command.toolName.length}"
        is AgentCommand.NotifyStatus -> "status.len=${command.status.length}"
        is AgentCommand.NotifyCitations -> "count=${command.citations.size}"
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
    private val historyCompressor by lazy { com.example.smarty.util.HistoryCompressor(androidLogger) }
    private val piiMasker by lazy { com.example.smarty.util.PIIMasker(androidLogger) }

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

    private val thinkingModeProcessor: ThinkingModeProcessor by lazy {
        ThinkingModeProcessor(application)
    }

    private val noteContextBuilder: NoteContextBuilder by lazy {
        NoteContextBuilder(mentionManager)
    }

    // Task 15: Remote Agent Service (Thin Client)
    // Replaces local SmartyAgentOptimized and SmartyAgentProvider
    private val remoteAgentService: RemoteAgentService by lazy {
        // Initialize Ktor client
        val client = HttpClient(OkHttp) {
            install(SSE)
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
        }

        // Connect to local server (reverse forwarded port)
        // Ensure you run: adb reverse tcp:7860 tcp:7860
        RemoteAgentService(client, agentEventSink, serverUrl = "http://10.0.2.2:7860")
    }

    // Agent Event Sink for Koog tools notifications
    private val agentEventSink = object : AgentEventSink {
        override fun onToolExecutionStarted(toolName: String, toolDisplayName: String) {
            val finalName = resolveResourceString(toolDisplayName) ?: toolDisplayName
            this@ChatFeatureManager.onToolExecutionStarted(finalName)
        }

        override fun onToolExecutionCompleted(toolName: String) {
            this@ChatFeatureManager.onToolExecutionCompleted()
        }

        override fun onStatusUpdate(status: String) {
            val finalStatus = resolveResourceString(status) ?: status
            this@ChatFeatureManager.onToolExecutionStarted(finalStatus)
        }

        override fun onCitationsFound(citations: List<WebCitation>) {
            this@ChatFeatureManager.onCitationsFound(citations)
        }

        override fun onDisplayImages(images: List<ImageDisplayItem>) {
            this@ChatFeatureManager.onDisplayImages(images)
        }

        override fun onPlanStatusChanged(status: String?) {
            this@ChatFeatureManager.onPlanStatusChanged(status)
        }

        /**
         * Command emission handler - routes AgentCommand to ClientCommandExecutor.
         *
         * This is the bridge between Agent decisions and Android execution.
         * The Agent emits commands, this method executes them on the device.
         *
         * Command → Executor Mapping:
         * - AddNote → clientCommandExecutor.addNote()
         * - UpdateNote → clientCommandExecutor.updateNote()
         * - DeleteNote → clientCommandExecutor.deleteNoteById()
         * - ArchiveNote → clientCommandExecutor.archiveNote()
         * - LaunchApp → clientCommandExecutor.launchApp()
         * - PlayAudio → clientCommandExecutor.requestAudioPlayback()
         * - ControlAudio → clientCommandExecutor.pauseAudioPlayback() / resumeAudioPlayback() / etc.
         * - SetTimer → clientCommandExecutor.setTimer()
         * - AddCalendarEvent → clientCommandExecutor.addCalendarEvent()
         * - NotifyToolStarted → onToolExecutionStarted()
         * - NotifyStatus → onStatusUpdate()
         */
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
                    commandTransport.dispatch(command)
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
        override fun getTavilyApiKey(): String? = settingsFeatureManager.getTavilyApiKeySync()
        override fun getOpenAiApiKey(): String? = settingsFeatureManager.getProviderKeys(AIProvider.OPENAI).firstOrNull()
        override fun getGeminiApiKey(): String? = settingsFeatureManager.getProviderKeys(AIProvider.GEMINI).firstOrNull()

        override suspend fun processNoteWithAi(note: Note) {
            noteOperationsManager.processNoteWithAi(note)
        }

        override suspend fun findNoteByDescription(description: String, notes: List<Note>): Note? {
            return noteOperationsManager.findNoteByDescription(description, notes)
        }

        override fun requestAudioPlayback(track: AudioTrack) {
            systemFeatureManager.playAudio(track)
        }

        override fun launchApp(packageName: String) {
            systemFeatureManager.launchApp(packageName)
        }

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

        override suspend fun markNoteAsAnalyzedForMemory(noteId: String) {
            noteOperationsManager.markNoteAsAnalyzedForMemory(noteId)
        }

        override fun getDeviceAudio(): List<AudioTrack> = systemFeatureManager.getDeviceAudio()

        override fun navigateTo(screen: String) {
            this@ChatFeatureManager.navigateTo(screen)
        }

        override fun getCurrentScreen(): String = currentScreen.value

        override fun getSystemStatus(): Map<String, String> {
            return systemFeatureManager.getSystemStatus(
                isDarkTheme = isDarkTheme.value,
                connectionStatus = connectionStatus.value.name,
                cacheSize = ContentTypeDetector.formatFileSize(application, cacheSizeBytes.value),
                unreadMemoryCount = unreadForMemoryCount.value
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
                noteOperationsManager.updateNote(noteId, title, content, allNotes.value, archivedNotes.value)
            }
        }

        override fun deleteNoteById(noteId: String) {
            scope.launch {
                noteOperationsManager.deleteNoteById(noteId, allNotes.value, archivedNotes.value)
            }
        }

        override fun archiveNote(noteId: String) {
            noteOperationsManager.archiveNote(noteId)
        }

        override fun unarchiveNote(noteId: String) {
            noteOperationsManager.unarchiveNote(noteId)
        }

        override fun summarizeNote(noteId: String) {
            noteOperationsManager.summarizeNote(noteId, allNotes.value, archivedNotes.value)
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

        override fun clearCache() {
            systemFeatureManager.clearCache()
        }

        override fun syncMemory() {
            memoryFeatureManager.syncFromNotes()
        }

        override suspend fun storeMemory(content: String, scope: String?) {
            memoryFeatureManager.storeMemory(content, scope)
        }

        override suspend fun updateMemory(id: String, content: String?, type: String?, confidence: Float?): Boolean {
            return memoryFeatureManager.updateMemory(id, content, type, confidence)
        }

        override suspend fun deleteMemory(id: String): Boolean {
            return memoryFeatureManager.deleteMemory(id)
        }

        override suspend fun retrieveMemories(query: String?, limit: Int): List<AIMemory> {
            return memoryFeatureManager.retrieveMemories(query, limit)
        }

        override suspend fun analyzePatterns(): UserPatternsReport {
            return memoryFeatureManager.analyzePatterns(allNotes.value, allCategories.value)
        }

        override suspend fun learnFromNotes(maxNotes: Int): LearningReport {
            return memoryFeatureManager.learnFromNotes(allNotes.value, maxNotes)
        }

        override fun backupData() {
            systemFeatureManager.backupData()
        }

        override fun setPrivacyMode(mode: String) {
            systemFeatureManager.setPrivacyMode(mode)
        }

        override fun consolidateMemories() {
            memoryFeatureManager.consolidateMemories()
        }

        override suspend fun getMemoryStats(): Map<String, Any> {
            return memoryFeatureManager.getMemoryStats()
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

        override fun shareContent(text: String, title: String?) {
            systemFeatureManager.shareContent(text, title)
        }

        override fun findPackageName(appName: String): String? {
            return systemFeatureManager.findPackageName(appName)
        }

        override fun findMatchingAudio(query: String): AudioSearchResult {
            return audioFeatureManager.findAudioTrack(query)
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

        override fun onDeepResearch(topic: String, apiKey: String, focusAreas: List<String>?, searchDepth: Int) {
            workflowManager.performDeepResearch(topic, apiKey, focusAreas, searchDepth)
        }

        override fun onAnalyzeStyle(limit: Int): StyleAnalysisReport {
            return styleFeatureManager.analyzeStyle(allNotes.value, limit)
        }

        override suspend fun onWebSearch(
            query: String,
            maxResults: Int,
            topic: String,
            onCitationsFound: (List<WebCitation>) -> Unit
        ): com.example.smarty.agent.tools.base.WebSearchResult {
            val apiKey = settingsFeatureManager.getTavilyApiKeySync() ?: return com.example.smarty.agent.tools.base.WebSearchResult(
                success = false,
                query = query,
                reason = "Web search not configured"
            )
            return searchFeatureManager.performWebSearch(query, apiKey, maxResults, topic, onCitationsFound)
        }

        override suspend fun onParallelWebSearch(
            queries: List<String>,
            maxResults: Int,
            topic: String,
            onCitationsFound: (List<WebCitation>) -> Unit
        ): com.example.smarty.agent.tools.base.WebSearchResult {
            return searchFeatureManager.performParallelWebSearch(queries, maxResults, topic, onCitationsFound)
        }
    }

    // Task 7: Command transport for delivering validated commands to execution
    // Task 8: CompositeTransport with shadow mode disabled by default
    // To enable shadow mode for debugging, change shadow = null to shadow = ShadowRemoteTransport()
    private val commandTransport: CommandTransport by lazy {
        CompositeTransport(
            primary = LocalCommandTransport(clientCommandExecutor),
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

    // Thinking Mode (default: false = Flash mode)
    private val _isThinkingModeEnabled = MutableStateFlow(false)
    val isThinkingModeEnabled: StateFlow<Boolean> = _isThinkingModeEnabled.asStateFlow()

    // UI Status
    private val _aiPlanStatus = MutableStateFlow<String?>(null)
    val aiPlanStatus: StateFlow<String?> = _aiPlanStatus.asStateFlow()

    private val _currentToolName = MutableStateFlow<String?>(null)
    val currentToolName: StateFlow<String?> = _currentToolName.asStateFlow()

    // Navigation
    private val _navigationRequest = MutableStateFlow<String?>(null)
    val navigationRequest: StateFlow<String?> = _navigationRequest.asStateFlow()

    // Proactive Suggestions
    private val _proactiveSuggestion = MutableStateFlow<String?>(null)
    val proactiveSuggestion: StateFlow<String?> = _proactiveSuggestion.asStateFlow()

    // Internal state for current response
    private val pendingCitations = CopyOnWriteArrayList<WebCitation>()
    private val pendingInlineImages = CopyOnWriteArrayList<InlineChatImage>()

    init {
        chatManager.initialize()
    }

    fun toggleChatMode(fromShake: Boolean = false) {
        chatManager.toggleChatMode()
        savedStateHandle[KEY_IS_CHAT_MODE] = !isChatMode.value
    }

    /** Update the current tool name display */
    fun updateCurrentToolName(name: String?) {
        _currentToolName.value = name
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

    fun toggleThinkingMode() {
        _isThinkingModeEnabled.value = !_isThinkingModeEnabled.value
    }

    fun syncGroqKeys() {
        // Keys managed on server or via GroqKeyManager directly if needed locally
        // scope.launch { agentProvider.syncGroqKeys() }
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
                        val assistantMessage = ChatMessage(role = ChatRole.ASSISTANT, content = commandResult.response)
                        chatManager.addAssistantMessage(assistantMessage)
                        chatManager.saveMessagePair(
                            userMessage = userMessage,
                            assistantMessage = assistantMessage,
                            hasApiKeys = settingsFeatureManager.hasAnyApiKeys()
                        )
                        return@launch
                    }
                    is CommandResult.HandledAndPassToLLM -> {
                        val localMessage = ChatMessage(role = ChatRole.ASSISTANT, content = commandResult.response)
                        chatManager.addAssistantMessage(localMessage)
                    }
                    else -> Log.d(TAG, "Falling back to REASONING-PATH")
                }

                // 2. REASONING-PATH
                processReasoningPath(content, userMessage)

            } catch (e: Exception) {
                Log.e(TAG, "Error in dispatcher: ${e.message}", e)
                chatManager.addAssistantMessage(ChatMessage(role = ChatRole.ASSISTANT, content = application.getString(R.string.error_prefix, e.message ?: application.getString(R.string.unknown_error))))
            } finally {
                // Safely reset processing state only if we successfully set it
                if (processingSet) {
                    try {
                        // Use NonCancellable to ensure processing state is always reset
                        // but wrap in try-catch to prevent crashes during cleanup
                        withContext(NonCancellable) {
                            chatManager.setProcessing(false)
                            _currentToolName.value = null
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to reset processing state: ${e.message}")
                        // Fallback: try direct assignment
                        try {
                            chatManager.setProcessing(false)
                            _currentToolName.value = null
                        } catch (fallbackE: Exception) {
                            Log.e(TAG, "Complete failure to reset processing state: ${fallbackE.message}")
                        }
                    }
                }
            }
        }
    }

    private suspend fun processReasoningPath(content: String, userMessage: ChatMessage) {
        // Clear previous state
        pendingCitations.clear()
        pendingInlineImages.clear()
        _mentionState.value = MentionState()

        // Prepare UI for thinking
        chatManager.setProcessing(true)

        // Task 15: Thin Client Mode
        // We delegate all reasoning to the Remote Agent Service via SSE.
        // Local logic (history compression, context building) is handled by the server now.

        try {
            // Collect chunks from the remote stream
            val responseBuilder = StringBuilder()

            remoteAgentService.sendQuery(content)
                .collect { chunk ->
                    responseBuilder.append(chunk)
                    // Optional: Stream partial response to UI if supported by chatManager
                }

            val fullResponse = responseBuilder.toString()

            // Handle success
            chatManager.markApiCallSuccessful()

            val assistantMessage = ChatMessage(
                role = ChatRole.ASSISTANT,
                content = fullResponse,
                citations = pendingCitations.toList(),
                inlineImages = pendingInlineImages.toList()
            )

            chatManager.addAssistantMessage(assistantMessage)
            chatManager.saveMessagePair(userMessage, assistantMessage, true)

            if (settingsFeatureManager.isSoundEnabled()) {
                completionSoundManager.playAgentCompletionSound(true)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Remote agent execution failed", e)
            chatManager.addAssistantMessage(ChatMessage(
                role = ChatRole.ASSISTANT,
                content = application.getString(R.string.error_prefix, e.message ?: "Connection error"),
                isError = true
            ))
        }
    }

    // Removed handleAgentResult as it's specific to the old SmartyAgentOptimized return type
    // Removed legacy helper methods (filterPlanningText, extractSuggestions, etc.)
    // as the server now handles response formatting.

    fun navigateTo(screen: String) {
        scope.launch {
            _navigationRequest.value = screen
        }
    }

    fun clearNavigationRequest() {
        _navigationRequest.value = null
    }

    fun startProactiveMonitoring(unreadCountFlow: StateFlow<Int>, cacheSizeFlow: StateFlow<Long>, memoryCountFlow: StateFlow<Int>) {
        scope.launch {
            while (true) {
                val unreadCount = unreadCountFlow.value
                val cacheSize = cacheSizeFlow.value
                val memoryCount = memoryCountFlow.value

                val suggestion = when {
                    unreadCount > 15 -> application.getString(R.string.unread_notes_suggestion, unreadCount)
                    cacheSize > 500 * 1024 * 1024 -> application.getString(R.string.large_cache_suggestion)
                    memoryCount > 100 -> application.getString(R.string.detailed_memory_suggestion)
                    else -> null
                }

                if (_proactiveSuggestion.value != suggestion) {
                    _proactiveSuggestion.value = suggestion
                }
                delay(300_000)
            }
        }
    }

    fun acceptSuggestion() {
        val suggestion = _proactiveSuggestion.value ?: return
        _proactiveSuggestion.value = null
        dispatchQuery(suggestion)
    }

    fun dismissSuggestion() {
        _proactiveSuggestion.value = null
    }

    // Callbacks for SmartyAgent
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
        _aiPlanStatus.value = status
    }

    fun onToolExecutionStarted(toolDisplayName: String) {
        _currentToolName.value = toolDisplayName
    }

    fun onToolExecutionCompleted() {
        _currentToolName.value = null
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
