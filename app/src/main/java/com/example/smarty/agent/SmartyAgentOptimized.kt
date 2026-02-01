package com.example.smarty.agent

import com.example.smarty.agent.models.ScreenContext
import com.example.smarty.agent.tools.consolidated.*
import com.example.smarty.agent.tools.base.NotifyingTool
import com.example.smarty.agent.AgentEventSink
import com.example.smarty.agent.ClientCommandExecutor
import com.example.smarty.agent.routing.ModelTierRegistry  // NEW: Tiered Architecture
import com.example.smarty.agent.routing.ContextManager     // NEW: Universal Context Caching
import com.example.smarty.protocol.AgentCommand            // Task 4: Command emission
import com.example.smarty.viewmodel.managers.*
import com.example.smarty.data.model.ThinkingModeContext
import com.example.smarty.agent.prompts.ToolExampleStore
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatRole
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.TaggedNoteContext
import com.example.smarty.data.remote.providers.TavilySearchProvider
import com.example.smarty.data.local.AIMemoryDao
import com.example.smarty.data.repository.SmartyRepository
import com.example.smarty.viewmodel.managers.AudioFeatureManager.AudioSearchResult
import com.example.smarty.util.HistoryCompressor
import com.example.smarty.util.PIIMasker
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.Logger
import com.example.smarty.util.StringProvider
import com.example.smarty.util.api.ApiErrorCategory
import com.example.smarty.util.api.RateLimiter
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.UUID


/**
 * Web search citation for AI responses
 */
data class WebCitation(
    val title: String,
    val url: String,
    val snippet: String
)

/**
 * Sealed class for categorizing tool execution errors.
 * Provides structured error classification instead of fragile string matching.
 */
sealed class ToolErrorType {
    abstract val message: String
    /** Validation errors - invalid input format, missing required fields */
    data class Validation(override val message: String) : ToolErrorType()

    /** State errors - operation not allowed in current state */
    data class InvalidState(override val message: String) : ToolErrorType()

    /** Resource not found - note, event, category doesn't exist */
    data class NotFound(val resource: String, override val message: String) : ToolErrorType()

    /** Permission denied - user lacks access to resource */
    data class PermissionDenied(override val message: String) : ToolErrorType()

    /** Parsing errors - failed to parse input data */
    data class ParseError(override val message: String) : ToolErrorType()

    /** Network/API errors - external service failures */
    data class NetworkError(override val message: String) : ToolErrorType()

    /** Resource exhausted - rate limits, quotas exceeded */
    data class ResourceExhausted(override val message: String) : ToolErrorType()

    /** Provider error - should trigger failover to next provider */
    data class ProviderError(override val message: String) : ToolErrorType()

    /** Unknown error - unclassified errors */
    data class Unknown(override val message: String) : ToolErrorType()

    /**
     * Whether this error type should trigger provider failover.
     * Tool errors should NOT failover (wastes API calls).
     */
    fun shouldFailover(): Boolean = when (this) {
        is ProviderError -> true
        is NetworkError -> true  // Network issues might be provider-specific
        is ResourceExhausted -> true  // Rate limits are provider-specific
        else -> false  // Tool errors don't need failover
    }

    /**
     * Get a user-friendly error message.
     */
    fun toUserMessage(): String = when (this) {
        is Validation -> "invalid input: ${message.lowercase()}"
        is InvalidState -> "cannot complete action: ${message.lowercase()}"
        is NotFound -> "could not find $resource: ${message.lowercase()}"
        is PermissionDenied -> "access denied: ${message.lowercase()}"
        is ParseError -> "could not understand the input: ${message.lowercase()}"
        is NetworkError -> "connection issue: ${message.lowercase()}"
        is ResourceExhausted -> "service temporarily unavailable: ${message.lowercase()}"
        is ProviderError -> "ai service error: ${message.lowercase()}"
        is Unknown -> message.lowercase()
    }

    companion object {
        /**
         * Classify an exception into a ToolErrorType.
         * Uses exception types first, then falls back to message analysis.
         */
        fun classify(exception: Exception): ToolErrorType {
            val message = exception.message ?: "Unknown error"

            // Primary classification by exception type using when expression
            return when (exception) {
                // Standard validation exceptions
                is IllegalArgumentException -> classifyByMessage(message, default = Validation(message))
                is IllegalStateException -> InvalidState(message)
                is NumberFormatException -> ParseError("Invalid number format: $message")
                is NullPointerException -> Validation("Missing required value")
                is IndexOutOfBoundsException -> Validation("Index out of range: $message")
                is NoSuchElementException -> NotFound("item", message)
                is UnsupportedOperationException -> InvalidState("Operation not supported: $message")
                is SecurityException -> PermissionDenied(message)
                is java.net.SocketTimeoutException -> NetworkError("Request timed out")
                is java.net.UnknownHostException -> NetworkError("Could not reach server")
                is java.net.ConnectException -> NetworkError("Connection failed")
                is java.io.IOException -> classifyIOException(exception, message)
                is kotlinx.coroutines.TimeoutCancellationException -> NetworkError("Request timed out")
                else -> classifyByMessage(message, default = Unknown(message))
            }
        }

        /**
         * Classify IOException subtypes more specifically.
         */
        private fun classifyIOException(exception: java.io.IOException, message: String): ToolErrorType {
            return when {
                message.contains("timeout", ignoreCase = true) -> NetworkError("Request timed out")
                message.contains("connection", ignoreCase = true) -> NetworkError(message)
                message.contains("refused", ignoreCase = true) -> NetworkError("Connection refused")
                message.contains("reset", ignoreCase = true) -> NetworkError("Connection reset")
                else -> NetworkError(message)
            }
        }

        /**
         * Secondary classification by analyzing error message content.
         * Used when exception type alone is insufficient.
         */
        private fun classifyByMessage(message: String, default: ToolErrorType): ToolErrorType {
            val lowerMessage = message.lowercase()

            return when {
                // Not found patterns
                lowerMessage.contains("not found") ||
                lowerMessage.contains("does not exist") ||
                lowerMessage.contains("no such") ||
                lowerMessage.contains("couldn't find") ->
                    NotFound("resource", message)

                // Permission patterns
                lowerMessage.contains("permission") ||
                lowerMessage.contains("unauthorized") ||
                lowerMessage.contains("forbidden") ||
                lowerMessage.contains("access denied") ->
                    PermissionDenied(message)

                // Rate limit / quota patterns
                lowerMessage.contains("rate limit") ||
                lowerMessage.contains("quota") ||
                lowerMessage.contains("too many requests") ||
                lowerMessage.contains("429") ->
                    ResourceExhausted(message)

                // Parse / format patterns
                lowerMessage.contains("parse") ||
                lowerMessage.contains("invalid format") ||
                lowerMessage.contains("malformed") ||
                lowerMessage.contains("syntax") ->
                    ParseError(message)

                // Validation patterns
                lowerMessage.contains("invalid") ||
                lowerMessage.contains("cannot be") ||
                lowerMessage.contains("must be") ||
                lowerMessage.contains("required") ||
                lowerMessage.contains("missing") ->
                    Validation(message)

                // State patterns
                lowerMessage.contains("already") ||
                lowerMessage.contains("cannot") ||
                lowerMessage.contains("not allowed") ||
                lowerMessage.contains("state") ->
                    InvalidState(message)

                // Network patterns
                lowerMessage.contains("timeout") ||
                lowerMessage.contains("connection") ||
                lowerMessage.contains("network") ||
                lowerMessage.contains("unreachable") ->
                    NetworkError(message)

                // Provider/API patterns
                lowerMessage.contains("api error") ||
                lowerMessage.contains("service unavailable") ||
                lowerMessage.contains("500") ||
                lowerMessage.contains("502") ||
                lowerMessage.contains("503") ->
                    ProviderError(message)

                else -> default
            }
        }
    }
}

/**
 * Result of agent execution.
 */
sealed class AgentResult {
    data class Success(
        val response: String,
        val provider: AIProvider,
        val citations: List<WebCitation> = emptyList()
    ) : AgentResult()
    data class Error(val message: String) : AgentResult()
    data class NoProvider(val message: String) : AgentResult()
}

/**
 * Image display item for ViewImageTool callback.
 * Contains information needed to display an image inline in chat.
 */
data class ImageDisplayItem(
    val uri: String,
    val fileName: String,
    val noteTitle: String
)

/**
 * Main Smarty AI Agent wrapper using JetBrains Koog framework.
 *
 * This agent can:
 * - Create, search, update, delete, archive/unarchive notes
 * - Add, toggle, delete todo items
 * - List categories and get notes by category
 * - Search the web via Tavily API
 * - Play audio content
 *
 * All operations respect PrivacyGuard - private notes are invisible to AI.
 */
class SmartyAgentOptimized(
    private val agentProvider: SmartyAgentProvider,
    private val repository: SmartyRepository,
    private val tavilySearchProvider: TavilySearchProvider,
    private val eventSink: AgentEventSink,
    private val commandExecutor: ClientCommandExecutor,
    private val aiMemoryDao: AIMemoryDao,
    private val executionPlanManager: ExecutionPlanManager,
    private val logger: Logger,
    private val stringProvider: StringProvider,
    private val historyCompressor: HistoryCompressor,
    private val piiMasker: PIIMasker,
    private val rateLimiter: RateLimiter? = null
) {
    companion object {
        private const val TAG = "SmartyAgentOptimized"

        // REDUCED iteration limits to encourage extreme efficiency and shorter loops
        private const val MAX_ITERATIONS_SIMPLE = 3      // Minimal steps for simple queries
        private const val MAX_ITERATIONS_STANDARD = 7    // Standard multi-step tasks
        private const val MAX_ITERATIONS_COMPLEX = 10    // Complex multi-action tasks
        private const val MAX_ITERATIONS_RESEARCH = 15   // Deep research workflows

        // PLANNING loop limit - optimized for maximum velocity
        private const val MAX_PLAN_LOOP_ITERATIONS = 7   // Maximum 7 cycles for planning

        // Rate limit constants
        private const val RATE_LIMIT_DAILY_THRESHOLD_MS = 30_000L  // If wait > 30s, it's daily limit


        // Provider-specific timeout constants (in milliseconds)
        // LOCAL_PC: Local LLMs can be slow, needs long timeout
        private const val TIMEOUT_LOCAL_PC_MS = 300_000L     // 5 minutes
        // Cloud providers: Need reasonable timeout for network latency
        private const val TIMEOUT_CLOUD_DEFAULT_MS = 180_000L // 3 minutes
        private const val TIMEOUT_CLOUD_SLOW_MS = 240_000L   // 4 minutes (for Anthropic/complex models)

        /**
         * Get the appropriate timeout for a given AI provider.
         * LOCAL_PC gets shorter timeout since it's a local server.
         * Cloud providers get longer timeouts to account for network latency.
         *
         * @param provider The AI provider to get timeout for
         * @return Timeout in milliseconds
         */
        fun getTimeoutForProvider(provider: AIProvider): Long {
            return when (provider) {
                AIProvider.LOCAL_PC -> TIMEOUT_LOCAL_PC_MS
                AIProvider.ANTHROPIC -> TIMEOUT_CLOUD_SLOW_MS  // Anthropic can be slower
                AIProvider.OPENAI -> TIMEOUT_CLOUD_SLOW_MS     // OpenAI complex models may need more time
                AIProvider.GEMINI -> TIMEOUT_CLOUD_DEFAULT_MS
                AIProvider.GROQ -> TIMEOUT_CLOUD_DEFAULT_MS    // GROQ is typically fast
                AIProvider.DEEPSEEK -> TIMEOUT_CLOUD_DEFAULT_MS
                AIProvider.CEREBRAS -> TIMEOUT_CLOUD_DEFAULT_MS
                AIProvider.COHERE -> TIMEOUT_CLOUD_DEFAULT_MS
                AIProvider.OPENROUTER -> TIMEOUT_CLOUD_SLOW_MS // OpenRouter routes to various models
                AIProvider.HUGGINGFACE -> TIMEOUT_CLOUD_SLOW_MS // HuggingFace inference can be slow
                AIProvider.GITHUB -> TIMEOUT_CLOUD_DEFAULT_MS
            }
        }

        /**
         * SECURITY: Sanitize error messages to prevent API key leakage in chat UI.
         * Removes any patterns that might contain API keys from various providers.
         * AGENT-005: Extended with additional provider patterns.
         */
        private fun sanitizeErrorMessage(message: String): String {
            return message
                // GROQ API keys: gsk_xxxxx
                .replace(Regex("""gsk_[a-zA-Z0-9]{20,}"""), "[API_KEY_REDACTED]")
                // OpenAI API keys: sk-xxxxx or sk-proj-xxxxx
                .replace(Regex("""sk-[a-zA-Z0-9_-]{20,}"""), "[API_KEY_REDACTED]")
                // Google/Gemini API keys: AIzaXXXXXX
                .replace(Regex("""AIza[a-zA-Z0-9_-]{30,}"""), "[API_KEY_REDACTED]")
                // Anthropic API keys: sk-ant-xxxxx
                .replace(Regex("""sk-ant-[a-zA-Z0-9_-]{20,}"""), "[API_KEY_REDACTED]")
                // AGENT-005: DeepSeek API keys
                .replace(Regex("""dsk_[a-zA-Z0-9]{20,}"""), "[API_KEY_REDACTED]")
                // AGENT-005: HuggingFace tokens
                .replace(Regex("""hf_[a-zA-Z0-9]{20,}"""), "[API_KEY_REDACTED]")
                // AGENT-005: Cohere API keys
                .replace(Regex("""[a-zA-Z0-9]{40}"""), "[POSSIBLE_KEY_REDACTED]")  // Cohere uses 40-char alphanumeric
                // AGENT-005: Tavily API keys (tvly-xxxxx)
                .replace(Regex("""tvly-[a-zA-Z0-9]{20,}"""), "[API_KEY_REDACTED]")
                // AGENT-005: Cerebras API keys (csk-xxxxx)
                .replace(Regex("""csk-[a-zA-Z0-9_-]{20,}"""), "[API_KEY_REDACTED]")
                // Bearer tokens in auth headers
                .replace(Regex("""Bearer\s+[a-zA-Z0-9._-]{20,}"""), "Bearer [REDACTED]")
                // Authorization headers
                .replace(Regex("""Authorization:\s*[^\n]+""", RegexOption.IGNORE_CASE), "Authorization: [REDACTED]")
                // X-API-Key headers
                .replace(Regex("""X-API-Key:\s*[^\n]+""", RegexOption.IGNORE_CASE), "X-API-Key: [REDACTED]")
        }
    }


    /**
     * NEW-016: Tool Example Store for few-shot learning.
     * Provides relevant examples to improve tool selection accuracy by 25-40%.
     */
    private val toolExampleStore = ToolExampleStore()

    /**
     * BATCH-3C: Agent Optimizer for comprehensive query optimization.
     * Integrates PII masking, history compression, semantic caching, and few-shot examples.
     *
     * Benefits:
     * - Semantic caching: Skip redundant API calls for similar queries
     * - PII masking: Privacy protection for user data
     * - History compression: 50-70% token reduction for long conversations
     * - Few-shot examples: 25-40% improvement in tool selection accuracy
     */
    private val agentOptimizer by lazy {
        val optimizer = AgentOptimizer(
            logger = logger,
            historyCompressor = historyCompressor,
            piiMasker = piiMasker,
            enableCache = true,
            enablePiiMasking = true,
            enableHistoryCompression = true,
            enableFewShotExamples = true
        )

        // Log cache status for debugging
        when (optimizer.getCacheMode()) {
            AgentOptimizer.CacheMode.HASH_BASED -> {
                logger.i(TAG, "AgentOptimizer: On-device hash-based cache ENABLED")
            }
            AgentOptimizer.CacheMode.DISABLED -> {
                logger.w(TAG, "AgentOptimizer: Caching DISABLED")
            }
        }

        optimizer
    }
    private val systemPrompt = """
You are Smarty, a calm, professional, and concise intelligence.

**IDENTITY:**
- You are a proactive partner in the user's digital ecosystem.
- You have direct access to notes, calendar, device apps, and the web.
- You value high-velocity interaction and zero friction.

**TONE & STYLE (Calm Aesthetic):**
- Use "soft" language (e.g., "i've found...", "let's try...", instead of "SYSTEM ERROR" or "ACTION REQUIRED").
- Prefer lowercase for short summaries and UI labels (e.g., "added to work_notes" instead of "Added to Work Notes").
- Minimize large Markdown headers. Use bold text or simple lists for structure.
- Be extremely concise. If a one-sentence answer suffices, use it.
- **Adaptive Persona**: Mirror the user's vibe. If casual/slangy, be casual. If formal, be professional.
- **Wit & Humor**: You are not a robot. Use metaphors (e.g., "Scanning the digital horizon..."). If the user is playful or bored, be playful back.

**METAPHORICAL & ADAPTIVE UNDERSTANDING:**
- **Implicit Intents**: Map emotional states to tools:
  - "I'm bored" -> `audio_control(action='play', query='upbeat music')` or `universal_search(query='fun facts')`.
  - "I need to focus" -> `audio_control(action='play', query='lo-fi beats')`.
  - "I'm scrolling too much" / "Social media addiction" -> `time_manager(action='set_timer', duration='5m', label='Social Media Detox Break')`.
- **Tool Versatility**:
  - `universal_search` is not just for facts; use it for "deep research" by calling it multiple times with different angles (via `queries` list).
  - `time_manager` is not just for meetings; use it for mental health breaks, focus sprints (Pomodoro), or "detox" timers.
- **Memory & Context**:
  - If the user asks to "save this research", look at the *history* for previous web results and use `knowledge_master(action='add_note')` to save them. Do NOT search again.

**OPERATIONAL HIERARCHY:**
1.  **DIRECT RESPONSE**: If a query is simple, answer IMMEDIATELY. NO TOOLS.
2.  **CLARIFICATION**: If vague, ask for context. Never guess.
3.  **UI & NAVIGATION**: Use `system_interface(action='navigate')` for screen transitions.
4.  **ACTION OVER PASSIVITY**: Don't just say you can do it; execute and confirm briefly.

**TOOL PROTOCOLS:**
-   **Search**: Use `universal_search` (scope='both') as default. It handles internal and web results. Use `queries=['q1', 'q2', 'q3']` for deep research.
-   **Knowledge**: Use `knowledge_master` for note operations: create, update, delete, summarize.
-   **Temporal**: Use `time_manager` for ALL todos, calendar events, and timers.
-   **System**: Use `system_interface` for apps, audio, and navigation.
-   **Settings**: Use `app_controller` for theme, cache, and sync.

**CRITICAL RULES:**
-   Use snake_case for structured data or categories (e.g., "trip_planning").
-   NEVER create a note for a meeting or reminder; use `time_manager`.
-   Keep loops short. If the goal is reached, stop immediately.
-   Avoid redundant searches. If you have the info, use it.
    """.trimIndent()

    /**
     * Build the tool registry with all available tools.
     * FULL VERSION: All tools enabled for comprehensive AI capabilities.
     * Wrapped with NotifyingTool for UI feedback.
     */
    private fun buildToolRegistry(): ToolRegistry {
        return ToolRegistry {
            // === CONSOLIDATED TOOLS (6) ===
            // Task 4: KnowledgeMasterTool write callbacks migrated to command emission
            tool(NotifyingTool(KnowledgeMasterTool(
                onAddNote = { content, category ->
                    eventSink.emit(AgentCommand.AddNote(
                        commandId = UUID.randomUUID().toString(),
                        content = content,
                        category = category
                    ))
                },
                onUpdateNote = { noteId, title, content ->
                    eventSink.emit(AgentCommand.UpdateNote(
                        commandId = UUID.randomUUID().toString(),
                        noteId = noteId,
                        title = title,
                        content = content
                    ))
                },
                onDeleteNote = { noteId ->
                    eventSink.emit(AgentCommand.DeleteNote(
                        commandId = UUID.randomUUID().toString(),
                        noteId = noteId
                    ))
                },
                onArchiveNote = { noteId ->
                    eventSink.emit(AgentCommand.ArchiveNote(
                        commandId = UUID.randomUUID().toString(),
                        noteId = noteId
                    ))
                },
                onUnarchiveNote = commandExecutor::unarchiveNote,  // Not in protocol yet
                onSummarizeNote = commandExecutor::summarizeNote,  // Not in protocol yet
                onSearchNotes = { query, category, noteType, timeRange, limit ->
                    commandExecutor.searchNotes(query ?: "", category, noteType, timeRange, limit)
                },
                onCreateCategory = commandExecutor::onCreateCategory,
                onGetCategoryStats = commandExecutor::getCategoryStats,
                onStatusUpdate = eventSink::onStatusUpdate
            ), eventSink))

            tool(NotifyingTool(BatchOperationsTool(
                onSearchNotes = { query, category, noteType, timeRange, limit ->
                    commandExecutor.searchNotes(query ?: "", category, noteType, timeRange, limit)
                },
                onBulkArchive = commandExecutor::bulkArchiveNotes,
                onBulkDelete = commandExecutor::bulkDeleteNotes,
                onBulkMove = commandExecutor::bulkMoveToCategory,
                onStatusUpdate = eventSink::onStatusUpdate
            ), eventSink))

            tool(NotifyingTool(AppControllerTool(
                onToggleTheme = commandExecutor::toggleTheme,
                onClearCache = commandExecutor::clearCache,
                onSyncMemory = commandExecutor::syncMemory,
                onBackupData = commandExecutor::backupData,
                onSetPrivacyMode = commandExecutor::setPrivacyMode,
                onStatusUpdate = eventSink::onStatusUpdate
            ), eventSink))

            // Task 4: TimeManagerTool write callbacks migrated to command emission
            tool(NotifyingTool(TimeManagerTool(
                onAddTodo = commandExecutor::addTodoToNote,  // Not in protocol yet
                onAddEvent = { title, startTime, endTime, description, location, _ ->
                    eventSink.emit(AgentCommand.AddCalendarEvent(
                        commandId = UUID.randomUUID().toString(),
                        title = title,
                        start = startTime,
                        end = endTime,
                        description = description,
                        location = location
                    ))
                },
                onDeleteEvent = commandExecutor::deleteCalendarEvent,  // Not in protocol yet
                onBulkDeleteEvents = commandExecutor::bulkDeleteEvents,
                onQueryEvents = commandExecutor::queryCalendarEvents,
                onSetTimer = { name, timeStr, isAlarm ->
                    eventSink.emit(AgentCommand.SetTimer(
                        commandId = UUID.randomUUID().toString(),
                        name = name,
                        timeStr = timeStr,
                        isAlarm = isAlarm
                    ))
                },
                onCancelTimer = commandExecutor::cancelTimer,  // Not in protocol yet
                onStatusUpdate = eventSink::onStatusUpdate
            ), eventSink))

            tool(NotifyingTool(SystemInterfaceTool(
                onLaunchApp = commandExecutor::launchApp,
                onFindPackage = commandExecutor::findPackageName,
                onPlayAudio = commandExecutor::requestAudioPlayback,
                onFindAudio = commandExecutor::findMatchingAudio,
                onDisplayImages = eventSink::onDisplayImages,
                getScreenContext = commandExecutor::getScreenContext,
                onStatusUpdate = eventSink::onStatusUpdate,
                onNavigate = commandExecutor::navigateTo,
                onShare = commandExecutor::shareContent,
                onPlayList = commandExecutor::playAudioList
            ), eventSink))

            tool(NotifyingTool(AudioControlTool(
                onPlay = commandExecutor::requestAudioPlayback,
                onPause = commandExecutor::pauseAudioPlayback,
                onResume = commandExecutor::resumeAudioPlayback,
                onStop = commandExecutor::stopAudioPlayback,
                onSeek = commandExecutor::seekAudioTo,
                onToggle = commandExecutor::toggleAudioPlayback,
                onFindAudio = commandExecutor::findMatchingAudio,
                getCurrentTrack = commandExecutor::getCurrentAudioTrack,
                getCurrentPosition = commandExecutor::getCurrentAudioPosition,
                getDuration = commandExecutor::getAudioDuration,
                isPlaying = commandExecutor::isAudioPlaying,
                onStatusUpdate = eventSink::onStatusUpdate,
                onPlayList = commandExecutor::playAudioList
            ), eventSink))

            tool(NotifyingTool(SmartyCoreTool(
                onStoreMemory = commandExecutor::storeMemory,
                onRetrieveMemories = commandExecutor::retrieveMemories,
                onGetMemoryStats = commandExecutor::getMemoryStats,
                onConsolidate = commandExecutor::consolidateMemories,
                onSyncMemory = commandExecutor::syncMemory,
                onStatusUpdate = eventSink::onStatusUpdate
            ), eventSink))

            tool(NotifyingTool(AgentOrchestratorTool(
                onSearchNotes = { query, category, noteType, timeRange, limit ->
                    commandExecutor.searchNotes(query ?: "", category, noteType, timeRange, limit)
                },
                getTavilyApiKey = commandExecutor::getTavilyApiKey,
                onBulkArchive = commandExecutor::bulkArchiveNotes,
                onBulkDelete = commandExecutor::bulkDeleteNotes,
                onDeepResearch = commandExecutor::onDeepResearch,
                onStatusUpdate = eventSink::onStatusUpdate
            ), eventSink))

            // === SEARCH TOOLS (CONSOLIDATED) ===
            tool(NotifyingTool(UniversalSearchTool(
                onSearchInternal = commandExecutor::searchNotes,
                onAdvancedSearch = commandExecutor::advancedSearch,
                onWebSearch = commandExecutor::onWebSearch,
                onParallelWebSearch = commandExecutor::onParallelWebSearch,
                onAnalyzeQuery = commandExecutor::analyzeQuery,
                onRecall = commandExecutor::performRecall,
                onCitationsFound = eventSink::onCitationsFound,
                onStatusUpdate = eventSink::onStatusUpdate
            ), eventSink))

            tool(NotifyingTool(ReadAndAnalyzeStyleTool(
                onAnalyzeStyle = commandExecutor::onAnalyzeStyle,
                onStatusUpdate = eventSink::onStatusUpdate
            ), eventSink))

            // === PLANNING TOOLS ===
            tool(NotifyingTool(CreatePlanTool(executionPlanManager), eventSink))
            tool(NotifyingTool(MarkStepCompleteTool(executionPlanManager), eventSink))
            tool(NotifyingTool(CancelPlanTool(executionPlanManager), eventSink))
        }
    }

    /**
     * Build context string with current notes summary for the agent.
     * SLIM VERSION: Minimal context to fit smaller LLM context windows.
     */
    private fun buildContext(): String {
        val activeNotes = commandExecutor.getActiveNotes()
        val visibleNotes = PrivacyGuard.getAiVisibleNotes(activeNotes)
        val currentScreen = commandExecutor.getCurrentScreen()
        val systemStatus = commandExecutor.getSystemStatus()

        // Get current time for context
        val now = java.time.LocalDateTime.now()
        val formatter = java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d, yyyy | h:mm a")
        val formattedDateTime = now.format(formatter)

        return buildString {
            appendLine("Current Time: $formattedDateTime | Notes: ${visibleNotes.size} | Screen: $currentScreen")
            if (systemStatus.isNotEmpty()) {
                val statusStr = systemStatus.entries.joinToString(" | ") { "${it.key}: ${it.value}" }
                appendLine("System: $statusStr")
            }
        }
    }

    /**
     * Build personalized context from AI memories.
     * Fetches top relevant memories to provide user insights while saving tokens.
     *
     * @param query The user's query to find relevant memories for
     */
    private suspend fun buildMemoryContext(query: String): String {
        return try {
            // Fetch relevant memories (limit to 6 for token efficiency)
            val relevant = aiMemoryDao.getRelevantMemories(query, 6)

            // Fetch a few most recent ones for general context bias (limit to 2)
            val recent = aiMemoryDao.getRecentMemories(2)

            // Combine and deduplicate
            val combined = (relevant + recent).distinctBy { it.id }.take(8)

            if (combined.isEmpty()) return ""

            buildString {
                appendLine("\n<user_memory>")
                appendLine("The following insights were learned from the user's notes and interactions:")
                combined.forEach { memory ->
                    appendLine("- [${memory.type}]: ${memory.content}")
                    // Increment usage to maintain relevance tracking
                    aiMemoryDao.incrementUsage(memory.id)
                }
                appendLine("</user_memory>")
            }
        } catch (e: Exception) {
            logger.e(TAG, "Error building memory context: ${e.message}")
            ""
        }
    }

    /**
     * Run the agent with a user message and conversation history.
     * Automatically tries all available providers/keys in priority order with failover.
     * Records success/failure with ProviderFailoverManager for circuit breaker pattern.
     * Now cycles through ALL available keys per provider for better rate limit handling.
     *
     * @param userMessage The current user message
     * @param conversationHistory Previous messages in the conversation (optional)
     * @param taggedNoteContext Optional context from @mentioned notes (for focused note references)
     * @param thinkingModeContext Optional context for @thinking deep document analysis mode
     */
    suspend fun run(
        userMessage: String,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        taggedNoteContext: TaggedNoteContext? = null,
        thinkingModeContext: ThinkingModeContext? = null,
        isThinkingModeEnabled: Boolean = false // Default to false (flash mode)
    ): AgentResult {
        logger.d(TAG, "Running agent with message: ${userMessage.take(50)}... (history: ${conversationHistory.size} messages)")

        // Get all available executors for fallback (already filtered by health)
        // Now includes multiple executors per provider (one per healthy key)
        val availableExecutors = agentProvider.getAllAvailableExecutors()

        if (availableExecutors.isEmpty()) {
            // logger.w(TAG, "No healthy AI provider available")
            return AgentResult.NoProvider("All AI providers are currently unavailable or disabled.")
        }

        logger.i(TAG, "Available executors: ${availableExecutors.size} (across ${availableExecutors.map { it.provider }.distinct().size} providers)")

        // BATCH-3C: Convert conversation history to ChatMessage format for optimizer
        val historyMessages = conversationHistory.map { (role, content) ->
            ChatMessage(
                role = if (role.equals("user", ignoreCase = true)) ChatRole.USER else ChatRole.ASSISTANT,
                content = content
            )
        }

        // BATCH-3C: Use AgentOptimizer for preprocessing (PII masking, history compression, semantic cache)
        val processed = try {
            agentOptimizer.preProcess(userMessage, historyMessages)
        } catch (e: Exception) {
            // CRITICAL FIX (AGENT-004): Use backup PII masking to prevent unmasked PII from reaching LLM
            logger.e(TAG, "AgentOptimizer preProcess failed - using backup PII masking", e)
            val backupMasked = piiMasker.mask(userMessage)
            logger.d(TAG, "Backup PII masking applied. Original length: ${userMessage.length}, Masked length: ${backupMasked.length}")
            AgentOptimizer.ProcessedQuery(
                maskedQuery = backupMasked,
                compressedHistory = historyMessages.takeLast(4), // Basic compression fallback
                cacheHit = null
            )
        }

        // BATCH-3C: Check for semantic cache hit - skip API call entirely!
        if (processed.cacheHit != null) {
            logger.d(TAG, "AgentOptimizer semantic cache HIT - skipping API call")
            return AgentResult.Success(
                processed.cacheHit,
                availableExecutors.firstOrNull()?.provider ?: AIProvider.GEMINI
            )
        } else if (!agentOptimizer.isCacheAvailable()) {
            logger.d(TAG, "Semantic cache lookup skipped - cache not available (OpenAI API key required)")
        }

        // BATCH-3C: Use HistoryCompressor for additional compression if needed
        // Replaces hardcoded takeLast(8) with intelligent compression
        val compressedHistory = if (processed.compressedHistory.isNotEmpty()) {
            val estimatedTokens = historyCompressor.estimateTokens(processed.compressedHistory)
            logger.d(TAG, "History tokens before compression: ~$estimatedTokens")

            // Force aggressive compression if tokens exceed threshold
            // AGENT-009: Increased from 2 to 3 to preserve more context
            if (estimatedTokens > 3000) {
                logger.d(TAG, "Forcing aggressive history compression (>3000 tokens)")
                historyCompressor.compress(
                    messages = processed.compressedHistory,
                    recentExchanges = 3,  // AGENT-009: Keep 3 exchanges minimum (was 2)
                    forceCompress = true
                )
            } else if (historyCompressor.shouldCompress(processed.compressedHistory)) {
                historyCompressor.compress(
                    messages = processed.compressedHistory,
                    recentExchanges = 3  // Standard: keep last 3 exchanges
                )
            } else {
                processed.compressedHistory
            }
        } else {
            emptyList()
        }

        val compressedTokens = historyCompressor.estimateTokens(compressedHistory)
        logger.d(TAG, "History tokens after compression: ~$compressedTokens")

        // Build history section from compressed ChatMessage list
        // Use symbols instead of role names to prevent LLM from mimicking "USER:" format
        val historySection = if (compressedHistory.isNotEmpty()) {
            "\nPREVIOUS:\n" + compressedHistory.joinToString("\n") { msg ->
                val prefix = when {
                    msg.isUser -> ">"
                    msg.isAssistant -> "<"
                    msg.isSystem -> "#"  // System/context messages
                    else -> "-"
                }
                "$prefix ${msg.content.take(150)}"  // > for user, < for assistant, # for system
            } + "\n"
        } else ""

        // NEW-016: Tool examples DISABLED to reduce token count for smaller LLMs
        // TODO: Re-enable for large context models
        val examplesSection = "" // Disabled for token savings

        // ═══════════════════════════════════════════════════════════════
        // @THINKING MODE - Deep document analysis with full content
        // ═══════════════════════════════════════════════════════════════
        // TIERED ARCHITECTURE: Optimization happens just-in-time inside the plan loop now,
        // but for the initial context build, we prepare the raw content.
        // The ContextManager will be applied dynamically based on the chosen executor for each step.
        val thinkingContextSection = if (thinkingModeContext != null && thinkingModeContext.isThinkingMode) {
            // Raw thinking content - will be optimized later
            logger.d(TAG, "@THINKING MODE: Preparing analysis context (${thinkingModeContext.totalChars} chars)")
             buildString {
                appendLine("\n\n=== DEEP THINKING MODE ===")
                appendLine("You are in DEEP THINKING mode. Analyze the following document content THOROUGHLY.")
                thinkingModeContext.documentFileName?.let { appendLine("Document: $it") }
                appendLine("Total Content: ${thinkingModeContext.totalChars} characters")
                appendLine()
                appendLine("=== FULL DOCUMENT CONTENT ===")
                // Include all document chunks
                thinkingModeContext.documentChunks.forEach { chunk ->
                    appendLine("[SECTION ${chunk.index + 1}/${chunk.totalChunks}]")
                    appendLine(chunk.content)
                    appendLine()
                }
                appendLine("=== END OF DOCUMENT ===")
            }
        } else ""

        // ═══════════════════════════════════════════════════════════════
        // @MENTION CONTEXT - Include referenced notes if present
        // (Only used when NOT in thinking mode)
        // ═══════════════════════════════════════════════════════════════
        val mentionContextSection = if (thinkingModeContext?.isThinkingMode != true && taggedNoteContext != null && taggedNoteContext.totalChars > 0) {
            if (taggedNoteContext.needsChunking) {
                // Large context - include chunk summary
                logger.d(TAG, "Including chunked mention context (${taggedNoteContext.chunks.size} chunks)")
                "\n\n${taggedNoteContext.chunks.firstOrNull()?.content ?: ""}\n[Additional ${taggedNoteContext.chunks.size - 1} chunks available]\n"
            } else {
                // Normal context - include full content
                logger.d(TAG, "Including mention context: ${taggedNoteContext.noteCount} notes, ${taggedNoteContext.totalChars} chars")
                "\n\n${taggedNoteContext.contextString}\n"
            }
        } else ""

        // Build full prompt with context, examples, history, thinking mode, mentions, and current message
        // BATCH-3C: Use masked query from optimizer for PII protection
        val memorySection = buildMemoryContext(processed.maskedQuery)
        val fullPrompt = buildContext() + memorySection + historySection + thinkingContextSection + mentionContextSection + "USER: ${processed.maskedQuery}"
        val toolRegistry = buildToolRegistry()

        // BATCH-3C: Token estimation pre-check to prevent context window overflow
        val contextTokens = buildContext().length / 4
        val promptTokens = fullPrompt.length / 4
        val systemPromptTokens = systemPrompt.length / 4
        val totalEstimatedTokens = contextTokens + promptTokens + systemPromptTokens

        logger.d(TAG, "Token estimate: context=$contextTokens, prompt=$promptTokens, system=$systemPromptTokens, total=$totalEstimatedTokens")

        if (totalEstimatedTokens > 6000) {
            logger.w(TAG, "Token count high ($totalEstimatedTokens), may hit context limits on smaller models")
            // Note: The HistoryCompressor already handles aggressive compression above
        }

        // Max iterations removed - allow agent to run without iteration limits
        val maxIterations = Int.MAX_VALUE
        logger.d(TAG, "Max iterations disabled - agent will run until task completion")

        // BATCH-3C: Simple query caching is now handled by AgentOptimizer's semantic cache
        // The old AIResponseCache was incompatible (designed for note analysis, not chat responses)
        // Semantic cache from AgentOptimizer provides better similarity-based caching
        val isSimpleQuery = false  // Always treat as complex to allow full processing

        // Rate limiter check before making API call
        rateLimiter?.let { limiter ->
            val waitTime = limiter.canMakeCall()
            if (waitTime != null) {
                if (waitTime > RATE_LIMIT_DAILY_THRESHOLD_MS) {
                    // Daily budget exceeded
                    val hours = waitTime / 3600_000
                    val minutes = (waitTime / 60_000) % 60
                    logger.w(TAG, "Daily API limit reached. Resets in ${hours}h ${minutes}m")
                    val timeStr = "${hours}h ${minutes}m"
                    val message = stringProvider.getString(com.example.smarty.R.string.error_daily_limit_reached) + " " +
                            stringProvider.getString(com.example.smarty.R.string.error_limit_reset_time, timeStr) + " " +
                            stringProvider.getString(com.example.smarty.R.string.error_limit_chat_available)
                    return AgentResult.Error(message)
                } else {
                    // Per-minute limit, wait briefly
                    // logger.d(TAG, "Rate limit: waiting ${waitTime}ms before API call")
                    delay(waitTime)
                }
            }
        }

        // Collect errors from each provider/key for debugging
        val errors = mutableListOf<Triple<AIProvider, Int, String>>() // provider, keyIndex, error

        // Try each provider/key combination in priority order
        // All keys from first provider (GROQ) are tried before moving to next provider
        var currentProvider: AIProvider? = null
        for (executorResult in availableExecutors) {
            val keyLabel = "key-${executorResult.keyIndex}"

            // Log when switching providers
            if (currentProvider != executorResult.provider) {
                if (currentProvider != null) {
                    logger.i(TAG, "All ${currentProvider} keys exhausted, trying ${executorResult.provider}")
                }
                currentProvider = executorResult.provider
            }

            try {
                logger.d(TAG, "Trying ${executorResult.provider} ($keyLabel) / ${executorResult.model}")

                // ═══════════════════════════════════════════════════════════════
                // PLAN EXECUTION LOOP
                // When the agent creates a plan, we need to keep calling it until
                // all steps are complete. Each iteration rebuilds context with
                // the updated plan state.
                // ═══════════════════════════════════════════════════════════════

                var currentResponse = ""
                var planLoopIterations = 0
                val maxPlanLoopIterations = MAX_PLAN_LOOP_ITERATIONS

                val providerTimeout = getTimeoutForProvider(executorResult.provider)

                do {
                    planLoopIterations++
                    logger.d(TAG, "Plan loop iteration $planLoopIterations")

                    // Rebuild context with current plan state
                    // Rebuild context with current plan state
                    // TIERED ARCHITECTURE: Select best model for this step
                    val currentStepExecutor = when (planLoopIterations) {
                        2 -> { // Step 2: Research (Heavy reading/ingestion)
                            val ingestionExec = ModelTierRegistry.getIngestionExecutor(availableExecutors)
                            if (ingestionExec != null && ingestionExec.provider != executorResult.provider) {
                                logger.i(TAG, "Tiered Switch: Using ${ingestionExec.provider} (Ingestion) for Research Step")
                                ingestionExec
                            } else executorResult
                        }
                        5 -> { // Step 5: Synthesis (High reasoning)
                            val reasoningExec = ModelTierRegistry.getReasoningExecutor(availableExecutors)
                            if (reasoningExec != null && reasoningExec.provider != executorResult.provider) {
                                logger.i(TAG, "\uD83E\uDDE0 Tiered Switch: Using ${reasoningExec.provider} (Reasoning) for Synthesis Step")
                                reasoningExec
                            } else executorResult
                        }
                        else -> executorResult // Default to the user's primary choice
                    }

                    // PHASE 3: Universal Context Caching
                    // Use ContextManager to optimize the HEAVY sections (Thinking/Mention) for the current executor.
                    
                    val ingestionExecForOptimization = ModelTierRegistry.getIngestionExecutor(availableExecutors)
                    val optimizedThinking = ContextManager.optimizeContextForModel(thinkingContextSection, currentStepExecutor, ingestionExecForOptimization)
                    
                    val currentPrompt = buildContext() + memorySection + examplesSection + historySection +
                        optimizedThinking + mentionContextSection +
                        "USER: ${processed.maskedQuery}"

                    // Rebuild tool registry (in case state changed)
                    val currentToolRegistry = buildToolRegistry()

                    // AGENT-010: Inject thinking mode instruction into system prompt
                    val stepFocus = when (planLoopIterations) {
                        1 -> """
step 1: understanding
clarify the user's intent. what are the explicit and implicit needs? identify assumptions and missing info.
format:
**intent**: [summary]
**requirements**: [list]
**missing**: [list]
"""
                        2 -> """
step 2: research
gather facts using read-only tools. identify technical constraints and dependencies.
format:
**findings**: [bullet points]
**constraints**: [bullet points]
"""
                        3 -> """
step 3: approach
evaluate 2-3 paths. compare simplicity vs. depth.
format:
**options**: [short comparison]
**recommendation**: [chosen path]
"""
                        4 -> """
step 4: planning
define the implementation sequence. how will we verify success?
format:
**sequence**: [ordered steps]
**validation**: [how to check]
"""
                        else -> """
step 5: synthesis
deliver the final, polished plan. make it actionable.
format:
**solution**: [clear description]
**next steps**: [what to do now]
"""
                    }

                    val thinkingModeInstruction = if (isThinkingModeEnabled) {
                        """

<deep_thinking_mode>
<mode_status>ACTIVE - reasoning step ${planLoopIterations} of 5</mode_status>

<constraints>
You are in deep thinking mode. focus on thorough analysis before any state-altering action.
- avoid all state-modifying tools until the final step.
- prioritize reading, planning, and understanding.
- stay calm and concise in your reasoning.
</constraints>

<step_${planLoopIterations}_focus>
$stepFocus
</step_${planLoopIterations}_focus>
</deep_thinking_mode>
"""
                    } else {
                        "\n\n<flash_mode>Direct execution mode. Respond efficiently and take action immediately. Keep responses concise and focused on completing the task.</flash_mode>"
                    }
                    val finalSystemPrompt = systemPrompt + thinkingModeInstruction


// Executor definition moved up for Context Optimization

                    val agent = AIAgent(
                        promptExecutor = currentStepExecutor.executor,
                        llmModel = currentStepExecutor.model,
                        systemPrompt = finalSystemPrompt,
                        toolRegistry = currentToolRegistry,
                        maxIterations = maxIterations
                    )

                    // Execute agent
                    val response = withTimeout(providerTimeout) {
                        agent.run(currentPrompt)
                    }

                    currentResponse = response
                    logger.d(TAG, "Agent response (iter $planLoopIterations): ${response.take(100)}...")

                    // DEEP THINKING MODE: Force 5 iterations for comprehensive planning
                    if (isThinkingModeEnabled) {
                        if (planLoopIterations >= 5) {
                            logger.d(TAG, "Deep thinking mode: Completed all 5 planning iterations")
                            break
                        }
                        // Continue to next iteration for deep thinking
                        logger.d(TAG, "Deep thinking mode: Iteration $planLoopIterations complete, continuing...")
                        delay(200) // Slightly longer delay for thinking mode
                        continue
                    }

                    // FLASH MODE: Normal plan-based exit logic
                    // Check if there's still an active plan with pending steps
                    val activePlan = executionPlanManager.getActivePlan()
                    val hasPendingSteps = activePlan != null &&
                        activePlan.status == PlanStatus.IN_PROGRESS &&
                        activePlan.getCurrentStep() != null

                    if (!hasPendingSteps) {
                        logger.d(TAG, "Flash mode: No pending steps, exiting plan loop")
                        break
                    }

                    // Safety: Check for stuck state (same step for too many iterations)
                    if (planLoopIterations >= maxPlanLoopIterations) {
                        logger.w(TAG, "Plan loop reached max iterations ($maxPlanLoopIterations), breaking to prevent infinite loop")
                        break
                    }

                    // Small delay between iterations to prevent rate limiting
                    delay(100)

                } while (true)

                // Clear the plan after completion
                if (executionPlanManager.getActivePlan()?.status == PlanStatus.COMPLETED) {
                    executionPlanManager.clearPlan()
                }

                // Record success with failover manager and rate limiter
                logger.i(TAG, " Success with ${executorResult.provider} ($keyLabel) after $planLoopIterations iterations")
                agentProvider.recordSuccess(executorResult.provider)
                rateLimiter?.recordCall()

                // BATCH-3C: Post-process response (unmask PII, cache for semantic similarity)
                val finalResponse = try {
                    agentOptimizer.postProcess(userMessage, processed.maskedQuery, currentResponse)
                } catch (e: Exception) {
                    logger.w(TAG, "AgentOptimizer postProcess failed, using raw response", e)
                    currentResponse
                }

                // BATCH-3C: Caching is now handled by AgentOptimizer's semantic cache
                if (isSimpleQuery) {
                    logger.d(TAG, "Simple query processed - cached via AgentOptimizer semantic cache")
                }

                return AgentResult.Success(finalResponse, executorResult.provider)


            } catch (e: TimeoutCancellationException) {
                // Agent took too long - try next provider
                val timeoutUsed = getTimeoutForProvider(executorResult.provider)
                val errorMsg = "Request timed out after ${timeoutUsed / 1000}s"
                logger.w(TAG, "${executorResult.provider} $keyLabel: $errorMsg")
                errors.add(Triple(executorResult.provider, executorResult.keyIndex, errorMsg))

                // Record failure for this key/provider
                if (executorResult.apiKey.isNotEmpty()) {
                    agentProvider.recordKeyFailure(executorResult.apiKey, executorResult.provider, e)
                }
                agentProvider.recordFailure(executorResult.provider, e)

                if (availableExecutors.indexOf(executorResult) < availableExecutors.lastIndex) {
                    logger.d(TAG, "↻ Falling back to next provider/key...")
                }

            } catch (e: IllegalArgumentException) {
                // Handle "Invalid action format" - the AI responded conversationally
                // Extract the response and return it as a success (graceful fallback)
                val errorMsg = e.message ?: ""
                if (errorMsg.startsWith("Invalid action format:")) {
                    val conversationalResponse = errorMsg
                        .removePrefix("Invalid action format:")
                        .trim()

                    if (conversationalResponse.isNotEmpty()) {
                        logger.i(TAG, " Conversational response from ${executorResult.provider} ($keyLabel)")
                        agentProvider.recordSuccess(executorResult.provider)

                        // BATCH-3C: Post-process conversational response (unmask PII, cache)
                        // Note: postProcess() handles caching to semantic cache internally
                        val finalConversationalResponse = try {
                            agentOptimizer.postProcess(userMessage, processed.maskedQuery, conversationalResponse)
                        } catch (ex: Exception) {
                            logger.w(TAG, "AgentOptimizer postProcess failed for conversational response", ex)
                            conversationalResponse
                        }

                        // BATCH-3C: Caching is now handled by AgentOptimizer's semantic cache
                        if (isSimpleQuery) {
                            logger.d(TAG, "Conversational response cached via AgentOptimizer semantic cache")
                        }

                        return AgentResult.Success(finalConversationalResponse, executorResult.provider)
                    }
                }

                // Not a recoverable action format error, treat as normal failure
                // SECURITY: Sanitize error messages to prevent API key leakage
                val sanitizedError = sanitizeErrorMessage(errorMsg)
                logger.w(TAG, "${executorResult.provider} $keyLabel failed: $sanitizedError")
                errors.add(Triple(executorResult.provider, executorResult.keyIndex, sanitizedError))

                if (executorResult.apiKey.isNotEmpty()) {
                    agentProvider.recordKeyFailure(executorResult.apiKey, executorResult.provider, e)
                }
                agentProvider.recordFailure(executorResult.provider, e)

                if (availableExecutors.indexOf(executorResult) < availableExecutors.lastIndex) {
                    logger.d(TAG, "↻ Falling back to next provider/key...")
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "unknown error"
                // SECURITY: Sanitize error messages to prevent API key leakage
                val sanitizedError = sanitizeErrorMessage(errorMsg)

                // BUG FIX (L-001): Use ToolErrorType for structured error classification
                // Replaces fragile string matching with exception-type-based classification
                val errorType = ToolErrorType.classify(e)

                // Log the classified error type for debugging
                logger.d(TAG, "Error classified as: ${errorType::class.simpleName}")

                if (!errorType.shouldFailover()) {
                    // Tool error - return to user immediately without failover
                    val userMsg = errorType.toUserMessage()
                    // AGENT-007: Include error type in log for easier debugging
                    // logger.w(TAG, "Tool error [${errorType::class.simpleName}] (no failover): $userMsg")
                    return AgentResult.Error("Action failed: $userMsg")
                }

                logger.w(TAG, "${executorResult.provider} $keyLabel failed: $sanitizedError")
                errors.add(Triple(executorResult.provider, executorResult.keyIndex, sanitizedError))

                // Record key-specific failure for proper cooldowns
                // This marks this specific key as failed, not the entire provider
                if (executorResult.apiKey.isNotEmpty()) {
                    agentProvider.recordKeyFailure(executorResult.apiKey, executorResult.provider, e)
                }

                // Also record provider-level failure
                agentProvider.recordFailure(executorResult.provider, e)

                // Continue to next provider/key
                if (availableExecutors.indexOf(executorResult) < availableExecutors.lastIndex) {
                    logger.d(TAG, "↻ Falling back to next provider/key...")
                }
            }
        }

        // All providers/keys failed - return combined error
        val errorSummary = if (errors.size == 1) {
            "Error: ${errors.first().third}"
        } else {
            // Group errors by provider for cleaner output
            val grouped = errors.groupBy { it.first }
            "Multiple attempts failed (${errors.size}):\n" + grouped.entries.joinToString("\n") { (provider, providerErrors) ->
                if (providerErrors.size == 1) {
                    "• ${provider.name.lowercase()}: ${providerErrors.first().third}"
                } else {
                    "• ${provider.name.lowercase()} (${providerErrors.size} keys): ${providerErrors.first().third}"
                }
            }
        }

        // logger.e(TAG, "All providers failed: $errorSummary")
        val finalMessage = "Request failed details: $errorSummary\n\nPlease try again later."
        return AgentResult.Error(finalMessage)
    }


    /**
     * Check if the agent is ready to run (has configured provider).
     */
    fun isReady(): Boolean = agentProvider.hasConfiguredProvider()

    /**
     * Get the current provider name for display.
     */
    fun getCurrentProvider(): String? = agentProvider.getCurrentProviderName()

    /**
     * Determine the appropriate max iterations based on task complexity.
     * More complex tasks get more iterations for multi-step reasoning.
     */
    private fun determineMaxIterations(userMessage: String): Int {
        val lowerMessage = userMessage.lowercase()

        // Research workflows get maximum iterations
        val researchKeywords = listOf(
            "research", "investigate", "find out", "learn about",
            "deep dive", "analyze", "comprehensive", "detailed analysis",
            "explore", "study", "examine", "summarize"
        )
        if (researchKeywords.any { lowerMessage.contains(it) }) {
            return MAX_ITERATIONS_RESEARCH
        }

        // Count action words to determine complexity
        val actionWords = listOf(
            "play", "create", "search", "find", "delete", "update",
            "schedule", "remind", "set", "check", "show", "list",
            "tell", "give", "count", "how many", "add", "save", 
            "archive", "cancel", "read", "write", "open"
        )
        val actionCount = actionWords.count { lowerMessage.contains(it) }

        // Long-horizon tasks (3+ actions or explicit sequence words)
        val longHorizonIndicators = listOf(
            "first ", "then ", "after that", "finally", "summary",
            "everything", "all of", "and then", "next", "later",
            "and also", "followed by", "before", "after"
        )
        val hasLongHorizon = longHorizonIndicators.count { lowerMessage.contains(it) } >= 1

        if (actionCount >= 3 || hasLongHorizon) {
            logger.d(TAG, "Complex multi-step task detected: actionCount=$actionCount, hasLongHorizon=$hasLongHorizon")
            return MAX_ITERATIONS_COMPLEX
        }

        // Multi-step tasks get standard iterations
        val multiStepIndicators = listOf(
            " and ", " then ", ", and", "multiple", "several",
            "all my", "every", "batch", "organize", "summarize all",
            "also ", "check", "tell me", "give me", "show me",
            "find and", "search and", "create and", "add and"
        )
        if (multiStepIndicators.any { lowerMessage.contains(it) }) {
            return MAX_ITERATIONS_STANDARD
        }

        // Multiple actions = standard iterations
        if (actionCount >= 2) {
            return MAX_ITERATIONS_STANDARD
        }

        // Complex action keywords also get standard iterations
        val complexKeywords = listOf(
            "create", "update", "delete", "archive", "search",
            "schedule", "remind", "timer", "alarm", "event",
            "find my", "show all", "what was", "when did"
        )
        val complexCount = complexKeywords.count { lowerMessage.contains(it) }
        if (complexCount >= 2) {
            return MAX_ITERATIONS_STANDARD
        }

        // Simple queries get minimal iterations
        return MAX_ITERATIONS_SIMPLE
    }

    /**
     * Get current rate limit statistics.
     * Returns null if rate limiter is not configured.
     */
    fun getRateLimitStats() = rateLimiter?.getUsageStats()

    /**
     * BATCH-3C: Get AgentOptimizer statistics.
     * Returns stats about semantic caching, PII masking, history compression, etc.
     */
    fun getOptimizerStats() = agentOptimizer.getStats()

    /**
     * BATCH-3C: Check if semantic cache is enabled.
     * Semantic cache requires OpenAI API key for embeddings.
     */
    fun isCacheAvailable() = agentOptimizer.isCacheAvailable()

    /**
     * BATCH-3C: Clear PII masking session (call when starting new conversation).
     */
    fun clearOptimizerSession() = agentOptimizer.clearSession()

    /**
     * BATCH-3C: Clear semantic cache.
     */
    suspend fun clearSemanticCache() = agentOptimizer.clearCache()
}