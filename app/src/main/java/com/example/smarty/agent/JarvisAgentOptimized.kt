package com.example.smarty.agent

import android.content.Context
import android.util.Log
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import com.example.smarty.agent.models.ScreenContext
import com.example.smarty.agent.tools.consolidated.*
import com.example.smarty.agent.tools.base.NotifyingTool
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
import com.example.smarty.data.repository.JarvisRepository
import com.example.smarty.service.AlarmScheduler
import com.example.smarty.util.HistoryCompressor
import com.example.smarty.util.PIIMasker
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.api.ApiErrorCategory
import com.example.smarty.util.api.RateLimiter
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout


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
        is Validation -> "Invalid input: $message"
        is InvalidState -> "Cannot complete action: $message"
        is NotFound -> "Could not find $resource: $message"
        is PermissionDenied -> "Access denied: $message"
        is ParseError -> "Could not understand the input: $message"
        is NetworkError -> "Connection issue: $message"
        is ResourceExhausted -> "Service temporarily unavailable: $message"
        is ProviderError -> "AI service error: $message"
        is Unknown -> message
        else -> message
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
 * Callbacks for agent operations that need ViewModel state or actions.
 */
interface AgentCallbacks {
    fun getActiveNotes(): List<Note>
    fun getArchivedNotes(): List<Note>
    fun getCategories(): List<Category>
    fun getTavilyApiKey(): String?
    fun getOpenAiApiKey(): String?  // For AgentOptimizer semantic cache (OpenAI embeddings)
    fun getGeminiApiKey(): String?  // For AgentOptimizer semantic cache (Gemini embeddings fallback)
    suspend fun processNoteWithAi(note: Note)
    suspend fun findNoteByDescription(description: String, notes: List<Note>): Note?
    fun requestAudioPlayback(track: AudioTrack)
    fun onToolExecutionStarted(toolName: String, toolDisplayName: String)
    fun onToolExecutionCompleted(toolName: String)
    fun onStatusUpdate(status: String)
    fun onCitationsFound(citations: List<WebCitation>)

    // New callbacks for OpenApp and SaveScreen tools
    fun launchApp(packageName: String)
    fun getScreenContext(): ScreenContext?

    // Callback for ViewImageTool to display images inline in chat
    fun onDisplayImages(images: List<ImageDisplayItem>)

    // Callback for status updates from internal planning system
    fun onPlanStatusChanged(status: String?)

    // Callback to mark a note as analyzed for AI memory learning
    suspend fun markNoteAsAnalyzedForMemory(noteId: String)

    // NEW: Get audio files from device storage (MediaStore)
    fun getDeviceAudio(): List<AudioTrack> = emptyList()  // Default empty for backward compatibility

    // HYBRID-CONTROL: Internal app navigation
    fun navigateTo(screen: String)
    fun getCurrentScreen(): String = "unknown"
    fun getSystemStatus(): Map<String, String> = emptyMap()

    // HYBRID-CONTROL: Note Operations (Delegated to NoteOperationsManager)
    fun addNote(content: String, category: String? = null)
    fun updateNote(noteId: String, title: String? = null, content: String? = null)
    fun deleteNoteById(noteId: String)
    fun archiveNote(noteId: String)
    fun unarchiveNote(noteId: String)
    fun summarizeNote(noteId: String)
    suspend fun onCreateCategory(name: String): Category
    suspend fun getCategoryStats(): List<com.example.smarty.viewmodel.managers.CategoryStatInfo>

    // HYBRID-CONTROL: App Settings
    fun toggleTheme(isDark: Boolean)
    fun clearCache()
    fun syncMemory()
    fun backupData()
    fun setPrivacyMode(mode: String)

    // HYBRID-CONTROL: Cognitive Operations
    suspend fun storeMemory(content: String, scope: String? = null)
    suspend fun updateMemory(id: String, content: String? = null, type: String? = null, confidence: Float? = null): Boolean
    suspend fun deleteMemory(id: String): Boolean
    suspend fun retrieveMemories(query: String?, limit: Int = 10): List<com.example.smarty.data.model.AIMemory>
    fun consolidateMemories()
    fun getMemoryStats(): Map<String, Any> = emptyMap()
    suspend fun analyzePatterns(): com.example.smarty.viewmodel.managers.UserPatternsReport
    suspend fun learnFromNotes(maxNotes: Int = 20): com.example.smarty.viewmodel.managers.LearningReport

    // HYBRID-CONTROL: Search Operations
    suspend fun searchNotes(query: String, category: String? = null, noteType: String? = null, timeRange: String = "all", limit: Int = 10): List<com.example.smarty.viewmodel.managers.SearchResultItem>
    suspend fun advancedSearch(query: String, algorithm: String = "hybrid", limit: Int = 10, minScore: Double = 0.3): List<com.example.smarty.viewmodel.managers.SearchResultItem>
    fun analyzeQuery(query: String): com.example.smarty.viewmodel.managers.SearchQueryAnalysis
    suspend fun performRecall(query: String, minScore: Double = 0.3): List<com.example.smarty.viewmodel.managers.RecallResult>

    // HYBRID-CONTROL: Intent Bridge (Cross-app communication)
    fun shareContent(text: String, title: String? = null)

    // HYBRID-CONTROL: System Lookups
    fun findPackageName(appName: String): String?
    fun findMatchingAudio(query: String): AudioTrack?

    // HYBRID-CONTROL: Audio Playback Control (NEW)
    fun pauseAudioPlayback()
    fun resumeAudioPlayback()
    fun stopAudioPlayback()
    fun seekAudioTo(positionMs: Long)
    fun toggleAudioPlayback()
    fun getCurrentAudioTrack(): AudioTrack?
    fun getCurrentAudioPosition(): Long
    fun getAudioDuration(): Long
    fun isAudioPlaying(): Boolean

    // HYBRID-CONTROL: Time Operations (Delegated to Managers)
    fun addCalendarEvent(
        title: String,
        startTimeStr: String,
        endTimeStr: String?,
        description: String?,
        location: String?,
        isPrivate: Boolean
    )
    fun deleteCalendarEvent(eventId: String)
    suspend fun queryCalendarEvents(query: String?): List<com.example.smarty.data.model.CalendarEvent>
    fun bulkDeleteEvents(eventIds: List<String>)
    fun setTimer(name: String, timeStr: String, isAlarm: Boolean)
    fun cancelTimer(timerId: String)
    fun addTodoToNote(noteId: String, text: String)

    // HYBRID-CONTROL: Orchestration (Bulk actions & Workflows)
    fun bulkArchiveNotes(noteIds: List<String>)
    fun bulkDeleteNotes(noteIds: List<String>)
    fun bulkMoveToCategory(noteIds: List<String>, categoryName: String)
    fun onDeepResearch(topic: String, apiKey: String, focusAreas: List<String>?, searchDepth: Int)

    // HYBRID-CONTROL: Specialized Analytics
    fun onAnalyzeStyle(limit: Int): com.example.smarty.viewmodel.managers.StyleAnalysisReport
    suspend fun onWebSearch(query: String, maxResults: Int, topic: String, onCitationsFound: (List<WebCitation>) -> Unit): com.example.smarty.agent.tools.base.WebSearchResult
}

/**
 * Main Jarvis AI Agent wrapper using JetBrains Koog framework.
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
class JarvisAgentOptimized(
    private val context: Context,  // For OpenAppTool
    private val agentProvider: JarvisAgentProvider,
    private val repository: JarvisRepository,
    private val tavilySearchProvider: TavilySearchProvider,
    private val alarmScheduler: AlarmScheduler,
    private val callbacks: AgentCallbacks,
    private val aiMemoryDao: AIMemoryDao,
    private val executionPlanManager: ExecutionPlanManager,
    private val rateLimiter: RateLimiter? = null
) {
    companion object {
        private const val TAG = "JarvisAgentOptimized"

        // INCREASED iteration limits to 50 for everything - MAXIMUM COMPLEXITY SUPPORT
        private const val MAX_ITERATIONS_SIMPLE = 50     // Increased to 50 for all queries
        private const val MAX_ITERATIONS_STANDARD = 50   // Increased to 50 for all queries
        private const val MAX_ITERATIONS_COMPLEX = 50    // Increased to 50 for all queries
        private const val MAX_ITERATIONS_RESEARCH = 50   // Increased to 50 for all queries

        // INCREASED planning loop limit to 50 for maximum complex workflows
        private const val MAX_PLAN_LOOP_ITERATIONS = 50  // Increased from 20 to 50

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
            enableCache = true,
            enablePiiMasking = true,
            enableHistoryCompression = true,
            enableFewShotExamples = true
        )

        // Log cache status for debugging
        when (optimizer.getCacheMode()) {
            AgentOptimizer.CacheMode.HASH_BASED -> {
                Log.i(TAG, "AgentOptimizer: On-device hash-based cache ENABLED")
            }
            AgentOptimizer.CacheMode.DISABLED -> {
                Log.w(TAG, "AgentOptimizer: Caching DISABLED")
            }
        }

        optimizer
    }
    private val systemPrompt = """
You are Jarvis, an elite intelligent agent designed for high-performance assistance.

**CORE IDENTITY:**
- You are not just a chatbot; you are a proactive system controller.
- You have direct access to the user's notes, calendar, device apps, and the web.
- You value **precision**, **brevity**, and **action**.

**OPERATIONAL HIERARCHY:**
1.  **DIRECT RESPONSE**: If a query is simple (fact, math, greeting), answer IMMEDIATELY. **NO TOOLS**.
2.  **CLARIFICATION**: If a request is vague ("do it", "send that"), ASK for context. NEVER guess.
3.  **UI & NAVIGATION**: You have "eyes" on the app. If the user asks for a screen, use `system_interface` with action='navigate'.
4.  **MAINTENANCE**: If the system status indicates low storage or unread memories, suggest using `app_controller`.
5.  **SEARCH STRATEGY**:
    -   **SEARCH STRATEGY**:
    -   **Primary**: Use `universal_search` (scope='both') for most queries. It handles internal notes and web search simultaneously.
    -   **Internal Only**: Use `universal_search` (scope='internal') or `knowledge_master` (intent='retrieve_notes') to find notes.
    -   **Web Only**: Use `universal_search` (scope='web') for real-time external info.
6.  **ACTION OVER PASSIVITY**: Don't just say you can do it; use the tool and confirm.

**TOOL PROTOCOLS:**
-   **Search**: Use `universal_search` as your default information gathering tool. It combines web, internal, and semantic recall.
-   **Knowledge**: Use `knowledge_master` for specific note CRUD: create, update, delete, archive/unarchive, and summarization.
-   **Bulk Actions**: Use `batch_notes` for operations affecting multiple notes (archive all, delete all, move category).
-   **Navigation**: Use `system_interface(action='navigate')` for: 'input_stream', 'stacks', 'calendar', 'settings', 'archive'.
-   **Settings**: Use `app_controller` for: 'toggle_theme', 'clear_cache', 'sync_memory', 'backup_data'.
-   **Temporal**: Use `time_manager` for ALL todos, calendar events, and timers.
-   **Orchestration**: Use `agent_orchestrator` for 'batch_operation' or 'deep_research' workflows.

**CRITICAL RULES:**
-   ALWAYS preview batch actions with `execute=false` before proceeding.
-   NEVER create a note for a meeting (Use `time_manager` -> 'manage_event').
-   NEVER create a note for a reminder (Use `time_manager` -> 'manage_timer').
-   Format responses with clean Markdown and bold key terms.
    """.trimIndent()

    /**
     * Build the tool registry with all available tools.
     * FULL VERSION: All tools enabled for comprehensive AI capabilities.
     * Wrapped with NotifyingTool for UI feedback.
     */
    private fun buildToolRegistry(): ToolRegistry {
        return ToolRegistry {
            // === CONSOLIDATED TOOLS (6) ===
            tool(NotifyingTool(KnowledgeMasterTool(
                onAddNote = callbacks::addNote,
                onUpdateNote = callbacks::updateNote,
                onDeleteNote = callbacks::deleteNoteById,
                onArchiveNote = callbacks::archiveNote,
                onUnarchiveNote = callbacks::unarchiveNote,
                onSummarizeNote = callbacks::summarizeNote,
                onSearchNotes = { query, category, noteType, timeRange, limit ->
                    callbacks.searchNotes(query ?: "", category, noteType, timeRange, limit)
                },
                onCreateCategory = callbacks::onCreateCategory,
                onGetCategoryStats = callbacks::getCategoryStats,
                onStatusUpdate = callbacks::onStatusUpdate
            ), callbacks))

            tool(NotifyingTool(BatchOperationsTool(
                onSearchNotes = { query, category, noteType, timeRange, limit ->
                    callbacks.searchNotes(query ?: "", category, noteType, timeRange, limit)
                },
                onBulkArchive = callbacks::bulkArchiveNotes,
                onBulkDelete = callbacks::bulkDeleteNotes,
                onBulkMove = callbacks::bulkMoveToCategory,
                onStatusUpdate = callbacks::onStatusUpdate
            ), callbacks))

            tool(NotifyingTool(AppControllerTool(
                onToggleTheme = callbacks::toggleTheme,
                onClearCache = callbacks::clearCache,
                onSyncMemory = callbacks::syncMemory,
                onBackupData = callbacks::backupData,
                onSetPrivacyMode = callbacks::setPrivacyMode,
                onStatusUpdate = callbacks::onStatusUpdate
            ), callbacks))

            tool(NotifyingTool(TimeManagerTool(
                onAddTodo = callbacks::addTodoToNote,
                onAddEvent = callbacks::addCalendarEvent,
                onDeleteEvent = callbacks::deleteCalendarEvent,
                onBulkDeleteEvents = callbacks::bulkDeleteEvents,
                onQueryEvents = callbacks::queryCalendarEvents,
                onSetTimer = callbacks::setTimer,
                onCancelTimer = callbacks::cancelTimer,
                onStatusUpdate = callbacks::onStatusUpdate
            ), callbacks))

            tool(NotifyingTool(SystemInterfaceTool(
                onLaunchApp = callbacks::launchApp,
                onFindPackage = callbacks::findPackageName,
                onPlayAudio = callbacks::requestAudioPlayback,
                onFindAudio = callbacks::findMatchingAudio,
                onDisplayImages = callbacks::onDisplayImages,
                getScreenContext = callbacks::getScreenContext,
                onStatusUpdate = callbacks::onStatusUpdate,
                onNavigate = callbacks::navigateTo,
                onShare = callbacks::shareContent
            ), callbacks))

            tool(NotifyingTool(AudioControlTool(
                onPlay = callbacks::requestAudioPlayback,
                onPause = callbacks::pauseAudioPlayback,
                onResume = callbacks::resumeAudioPlayback,
                onStop = callbacks::stopAudioPlayback,
                onSeek = callbacks::seekAudioTo,
                onToggle = callbacks::toggleAudioPlayback,
                onFindAudio = callbacks::findMatchingAudio,
                getCurrentTrack = callbacks::getCurrentAudioTrack,
                getCurrentPosition = callbacks::getCurrentAudioPosition,
                getDuration = callbacks::getAudioDuration,
                isPlaying = callbacks::isAudioPlaying,
                onStatusUpdate = callbacks::onStatusUpdate
            ), callbacks))

            tool(NotifyingTool(CognitiveCoreTool(
                onStoreMemory = callbacks::storeMemory,
                onRetrieveMemories = callbacks::retrieveMemories,
                onGetMemoryStats = callbacks::getMemoryStats,
                onConsolidate = callbacks::consolidateMemories,
                onSyncMemory = callbacks::syncMemory,
                onStatusUpdate = callbacks::onStatusUpdate
            ), callbacks))

            tool(NotifyingTool(AgentOrchestratorTool(
                onSearchNotes = { query, category, noteType, timeRange, limit ->
                    callbacks.searchNotes(query ?: "", category, noteType, timeRange, limit)
                },
                getTavilyApiKey = callbacks::getTavilyApiKey,
                onBulkArchive = callbacks::bulkArchiveNotes,
                onBulkDelete = callbacks::bulkDeleteNotes,
                onDeepResearch = callbacks::onDeepResearch,
                onStatusUpdate = callbacks::onStatusUpdate
            ), callbacks))

            // === SEARCH TOOLS (CONSOLIDATED) ===
            tool(NotifyingTool(UniversalSearchTool(
                onSearchInternal = callbacks::searchNotes,
                onAdvancedSearch = callbacks::advancedSearch,
                onWebSearch = callbacks::onWebSearch,
                onAnalyzeQuery = callbacks::analyzeQuery,
                onRecall = callbacks::performRecall,
                onCitationsFound = callbacks::onCitationsFound,
                onStatusUpdate = callbacks::onStatusUpdate
            ), callbacks))

            tool(NotifyingTool(ReadAndAnalyzeStyleTool(
                onAnalyzeStyle = callbacks::onAnalyzeStyle,
                onStatusUpdate = callbacks::onStatusUpdate
            ), callbacks))

            // === PLANNING TOOLS ===
            tool(NotifyingTool(CreatePlanTool(executionPlanManager), callbacks))
            tool(NotifyingTool(MarkStepCompleteTool(executionPlanManager), callbacks))
            tool(NotifyingTool(CancelPlanTool(executionPlanManager), callbacks))
        }
    }

    /**
     * Build context string with current notes summary for the agent.
     * SLIM VERSION: Minimal context to fit smaller LLM context windows.
     */
    private fun buildContext(): String {
        val activeNotes = callbacks.getActiveNotes()
        val visibleNotes = PrivacyGuard.getAiVisibleNotes(activeNotes)
        val currentScreen = callbacks.getCurrentScreen()
        val systemStatus = callbacks.getSystemStatus()

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
     * Fetches top recent and top most-used memories to provide user insights.
     */
    private suspend fun buildMemoryContext(): String {
        return try {
            // Fetch relevant memories
            val recent = aiMemoryDao.getRecentMemories(7)
            val topUsed = aiMemoryDao.getMostUsedMemories(7)

            // Combine and deduplicate
            val combined = (recent + topUsed).distinctBy { it.id }

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
            Log.e(TAG, "Error building memory context: ${e.message}")
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
        isThinkingModeEnabled: Boolean = true // Default to true (standard behavior)
    ): AgentResult {
        Log.d(TAG, "Running agent with message: ${userMessage.take(50)}... (history: ${conversationHistory.size} messages)")

        // Get all available executors for fallback (already filtered by health)
        // Now includes multiple executors per provider (one per healthy key)
        val availableExecutors = agentProvider.getAllAvailableExecutors()

        if (availableExecutors.isEmpty()) {
            Log.w(TAG, "No healthy AI provider available")
            return AgentResult.NoProvider("No AI provider available. All providers may be temporarily disabled due to errors.")
        }

        Log.i(TAG, "Available executors: ${availableExecutors.size} (across ${availableExecutors.map { it.provider }.distinct().size} providers)")

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
            Log.e(TAG, "AgentOptimizer preProcess failed - using backup PII masking", e)
            val backupMasked = PIIMasker.mask(userMessage)
            Log.d(TAG, "Backup PII masking applied. Original length: ${userMessage.length}, Masked length: ${backupMasked.length}")
            AgentOptimizer.ProcessedQuery(
                maskedQuery = backupMasked,
                compressedHistory = historyMessages.takeLast(4), // Basic compression fallback
                cacheHit = null
            )
        }

        // BATCH-3C: Check for semantic cache hit - skip API call entirely!
        if (processed.cacheHit != null) {
            Log.d(TAG, "AgentOptimizer semantic cache HIT - skipping API call")
            return AgentResult.Success(
                processed.cacheHit,
                availableExecutors.firstOrNull()?.provider ?: AIProvider.GEMINI
            )
        } else if (!agentOptimizer.isCacheAvailable()) {
            Log.d(TAG, "Semantic cache lookup skipped - cache not available (OpenAI API key required)")
        }

        // BATCH-3C: Use HistoryCompressor for additional compression if needed
        // Replaces hardcoded takeLast(8) with intelligent compression
        val compressedHistory = if (processed.compressedHistory.isNotEmpty()) {
            val estimatedTokens = HistoryCompressor.estimateTokens(processed.compressedHistory)
            Log.d(TAG, "History tokens before compression: ~$estimatedTokens")

            // Force aggressive compression if tokens exceed threshold
            // AGENT-009: Increased from 2 to 3 to preserve more context
            if (estimatedTokens > 3000) {
                Log.d(TAG, "Forcing aggressive history compression (>3000 tokens)")
                HistoryCompressor.compress(
                    messages = processed.compressedHistory,
                    recentExchanges = 3,  // AGENT-009: Keep 3 exchanges minimum (was 2)
                    forceCompress = true
                )
            } else if (HistoryCompressor.shouldCompress(processed.compressedHistory)) {
                HistoryCompressor.compress(
                    messages = processed.compressedHistory,
                    recentExchanges = 3  // Standard: keep last 3 exchanges
                )
            } else {
                processed.compressedHistory
            }
        } else {
            emptyList()
        }

        val compressedTokens = HistoryCompressor.estimateTokens(compressedHistory)
        Log.d(TAG, "History tokens after compression: ~$compressedTokens")

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
        val thinkingContextSection = if (thinkingModeContext != null && thinkingModeContext.isThinkingMode) {
            Log.d(TAG, "@THINKING MODE: Analyzing ${thinkingModeContext.documentFileName ?: "document"} (${thinkingModeContext.totalChars} chars, ${thinkingModeContext.documentChunks.size} chunks)")
            buildString {
                appendLine("\n\n=== DEEP THINKING MODE ===")
                appendLine("You are in DEEP THINKING mode. Analyze the following document content THOROUGHLY.")
                thinkingModeContext.documentFileName?.let { appendLine("Document: $it") }
                thinkingModeContext.targetNote?.let { appendLine("Note Title: ${it.title}") }
                appendLine("Total Content: ${thinkingModeContext.totalChars} characters")
                appendLine()
                appendLine("USER'S QUESTION: ${thinkingModeContext.userQuery.ifBlank { "Analyze this document in depth." }}")
                appendLine()
                appendLine("=== FULL DOCUMENT CONTENT ===")
                appendLine()
                // Include all document chunks
                thinkingModeContext.documentChunks.forEach { chunk ->
                    appendLine("[SECTION ${chunk.index + 1}/${chunk.totalChunks}]")
                    appendLine(chunk.content)
                    appendLine()
                }
                appendLine("=== END OF DOCUMENT ===")
                appendLine()
                appendLine("INSTRUCTIONS: Analyze the ENTIRE document above to thoroughly answer the user's question. Consider all sections and provide a comprehensive response.")
                appendLine()
            }
        } else ""

        // ═══════════════════════════════════════════════════════════════
        // @MENTION CONTEXT - Include referenced notes if present
        // (Only used when NOT in thinking mode)
        // ═══════════════════════════════════════════════════════════════
        val mentionContextSection = if (thinkingModeContext?.isThinkingMode != true && taggedNoteContext != null && taggedNoteContext.totalChars > 0) {
            if (taggedNoteContext.needsChunking) {
                // Large context - include chunk summary
                Log.d(TAG, "Including chunked mention context (${taggedNoteContext.chunks.size} chunks)")
                "\n\n${taggedNoteContext.chunks.firstOrNull()?.content ?: ""}\n[Additional ${taggedNoteContext.chunks.size - 1} chunks available]\n"
            } else {
                // Normal context - include full content
                Log.d(TAG, "Including mention context: ${taggedNoteContext.noteCount} notes, ${taggedNoteContext.totalChars} chars")
                "\n\n${taggedNoteContext.contextString}\n"
            }
        } else ""

        // Build full prompt with context, examples, history, thinking mode, mentions, and current message
        // BATCH-3C: Use masked query from optimizer for PII protection
        val memorySection = buildMemoryContext()
        val fullPrompt = buildContext() + memorySection + historySection + thinkingContextSection + mentionContextSection + "USER: ${processed.maskedQuery}"
        val toolRegistry = buildToolRegistry()

        // BATCH-3C: Token estimation pre-check to prevent context window overflow
        val contextTokens = buildContext().length / 4
        val promptTokens = fullPrompt.length / 4
        val systemPromptTokens = systemPrompt.length / 4
        val totalEstimatedTokens = contextTokens + promptTokens + systemPromptTokens

        Log.d(TAG, "Token estimate: context=$contextTokens, prompt=$promptTokens, system=$systemPromptTokens, total=$totalEstimatedTokens")

        if (totalEstimatedTokens > 6000) {
            Log.w(TAG, "Token count high ($totalEstimatedTokens), may hit context limits on smaller models")
            // Note: The HistoryCompressor already handles aggressive compression above
        }

        // Determine dynamic iteration limit based on task complexity
        val maxIterations = determineMaxIterations(userMessage)
        Log.d(TAG, "Using maxIterations=$maxIterations for task")

        // BATCH-3C: Simple query caching is now handled by AgentOptimizer's semantic cache
        // The old AIResponseCache was incompatible (designed for note analysis, not chat responses)
        // Semantic cache from AgentOptimizer provides better similarity-based caching
        val isSimpleQuery = maxIterations == MAX_ITERATIONS_SIMPLE &&
            !userMessage.lowercase().let { msg ->
                msg.contains("search") || msg.contains("find") || msg.contains("create") ||
                msg.contains("delete") || msg.contains("play") || msg.contains("note")
            }

        // Rate limiter check before making API call
        rateLimiter?.let { limiter ->
            val waitTime = limiter.canMakeCall()
            if (waitTime != null) {
                if (waitTime > RATE_LIMIT_DAILY_THRESHOLD_MS) {
                    // Daily budget exceeded
                    val hours = waitTime / 3600_000
                    val minutes = (waitTime / 60_000) % 60
                    Log.w(TAG, "Daily API limit reached. Resets in ${hours}h ${minutes}m")
                    return AgentResult.Error(
                        "I've reached my daily thinking limit to help manage resources. " +
                        "I'll be back at full capacity in ${hours}h ${minutes}m. " +
                        "Feel free to continue chatting - I can still help with simpler questions!"
                    )
                } else {
                    // Per-minute limit, wait briefly
                    Log.d(TAG, "Rate limit: waiting ${waitTime}ms before API call")
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
                    Log.i(TAG, "All ${currentProvider} keys exhausted, trying ${executorResult.provider}")
                }
                currentProvider = executorResult.provider
            }

            try {
                Log.d(TAG, "Trying ${executorResult.provider} ($keyLabel) / ${executorResult.model}")

                // ═══════════════════════════════════════════════════════════════
                // PLAN EXECUTION LOOP
                // When the agent creates a plan, we need to keep calling it until
                // all steps are complete. Each iteration rebuilds context with
                // the updated plan state.
                // ═══════════════════════════════════════════════════════════════

                var currentResponse = ""
                var planLoopIterations = 0
                val maxPlanLoopIterations = MAX_PLAN_LOOP_ITERATIONS // Increased from 10 to 20

                val providerTimeout = getTimeoutForProvider(executorResult.provider)

                do {
                    planLoopIterations++
                    Log.d(TAG, "Plan loop iteration $planLoopIterations")

                    // Rebuild context with current plan state
                    val currentPrompt = buildContext() + memorySection + examplesSection + historySection +
                        thinkingContextSection + mentionContextSection +
                        "USER: ${processed.maskedQuery}"

                    // Rebuild tool registry (in case state changed)
                    val currentToolRegistry = buildToolRegistry()

                    // AGENT-010: Inject thinking mode instruction into system prompt
                    // When THINKING MODE is ON, use iterative planning loop with STRICT no-action guidelines
                    val thinkingModeInstruction = if (isThinkingModeEnabled) {
                        """

<deep_thinking_mode>
<mode_status>ACTIVE - Iteration ${planLoopIterations} of 5</mode_status>

<critical_rules>
You are operating in Deep Thinking Mode. This mode exists to ensure thorough analysis before action.

ABSOLUTE CONSTRAINTS:
- You MUST NOT execute any actions, modifications, or changes
- You MUST NOT use any tools that alter state - only read-only information gathering
- You MUST NOT provide final answers or solutions until Iteration 5
- You MUST focus exclusively on understanding, research, and planning

YOUR SINGULAR FOCUS THIS ITERATION:
Think deeply. Analyze thoroughly. Plan carefully. Do not act.
</critical_rules>

<iteration_${planLoopIterations}_instructions>
${when (planLoopIterations) {
    1 -> """
ITERATION 1: DEEP UNDERSTANDING

Your objective is to fully comprehend what the user is asking before doing anything else.

Think through these questions:
- What exactly is the user trying to accomplish?
- What are the explicit requirements stated?
- What are the implicit requirements not stated but necessary?
- What assumptions am I making? Are they valid?
- What information do I need that I don't have?
- What could go wrong if I misunderstand this request?

Structure your response:

## My Understanding
[Restate the request in your own words to confirm understanding]

## Explicit Requirements
[List what was directly stated]

## Implicit Requirements
[List what is necessary but wasn't explicitly mentioned]

## Open Questions
[List what you need to investigate or clarify]

## Assumptions Being Made
[List any assumptions and flag uncertain ones]

End with: "Proceeding to information gathering..."
"""
    2 -> """
ITERATION 2: THOROUGH RESEARCH

Your objective is to gather all relevant information needed to solve this problem well.

For each open question from Iteration 1:
- What facts do I need to know?
- What context is relevant?
- What constraints exist?
- What dependencies must be considered?
- What has worked in similar situations?
- What pitfalls should be avoided?

Structure your response:

## Research Findings

### [Topic 1]
**Context:** [Relevant background]
**Key Facts:** [Important information discovered]
**Implications:** [What this means for the solution]

### [Topic 2]
[Same structure...]

## Constraints Identified
[List technical, practical, or other limitations]

## Dependencies Discovered
[List things that must happen first or are interconnected]

## Potential Pitfalls
[List common mistakes or issues to avoid]

End with: "Proceeding to approach analysis..."
"""
    3 -> """
ITERATION 3: APPROACH ENUMERATION

Your objective is to identify ALL viable approaches, not just the first one that comes to mind.

Force yourself to consider at least 3 different approaches:
- The obvious approach - what comes to mind first
- The alternative approach - a different way entirely
- The unconventional approach - thinking outside the box

For each approach, honestly evaluate:
- What are the genuine benefits?
- What are the real drawbacks?
- How complex is implementation?
- How maintainable is this long-term?
- What could go wrong?

Structure your response:

## Approach Analysis

### Approach A: [Descriptive Name]
**Strategy:** [Brief description of the approach]
**Benefits:**
- [Genuine advantage 1]
- [Genuine advantage 2]
**Drawbacks:**
- [Real limitation 1]
- [Real limitation 2]
**Complexity:** [Low/Medium/High with justification]
**Risk Level:** [Low/Medium/High with explanation]

### Approach B: [Descriptive Name]
[Same structure...]

### Approach C: [Descriptive Name]
[Same structure...]

## Comparative Analysis
[Direct comparison: which approach wins on which criteria?]

## Preliminary Recommendation
[Which approach appears best and why - but remain open to revision]

End with: "Proceeding to detailed planning..."
"""
    4 -> """
ITERATION 4: DETAILED PLANNING

Your objective is to create a concrete, actionable implementation plan for the chosen approach.

Think through execution details:
- What are the exact steps in order?
- What does each step produce?
- What does each step depend on?
- Where could each step fail?
- How would you verify each step succeeded?

Structure your response:

## Implementation Plan

### Step 1: [Action Title]
**Action:** [Specific description of what to do]
**Prerequisites:** [What must be true before starting]
**Expected Output:** [What this step produces]
**Verification:** [How to confirm success]
**If This Fails:** [Contingency plan]

### Step 2: [Action Title]
[Same structure...]

[Continue for all steps...]

## Critical Path
[Which steps are blocking? What's the minimum viable path?]

## Risk Mitigation Matrix
| Risk | Probability | Impact | Mitigation Strategy |
|------|-------------|--------|---------------------|
| [Risk 1] | [H/M/L] | [H/M/L] | [How to prevent or handle] |
| [Risk 2] | ... | ... | ... |

## Verification Checklist
- [ ] [Requirement 1 will be satisfied by Step X]
- [ ] [Requirement 2 will be satisfied by Step Y]
...

## Remaining Concerns
[Any lingering doubts or areas needing attention]

End with: "Proceeding to final synthesis..."
"""
    else -> """
ITERATION 5: FINAL SYNTHESIS

Your objective is to deliver a complete, polished, actionable plan to the user.

This is your FINAL output. Synthesize everything from previous iterations into a clear, professional response that the user can act on.

Structure your response:

## Summary
[2-3 sentences: What will be done and why this approach]

## Recommended Solution
[Clear, confident description of the chosen approach]

## Implementation Steps

**1. [Step Title]**
[Clear, actionable description with enough detail to execute]

**2. [Step Title]**
[Continue for each step...]

## Key Considerations
- [Important point the user should keep in mind]
- [Another important consideration]
- [Technical or practical note]

## Potential Challenges
- **[Challenge 1]:** [How to handle it]
- **[Challenge 2]:** [How to handle it]

## Success Criteria
[How will the user know this worked?]

## Next Steps
[What should the user do right now to begin?]

---
*This plan was developed through 5 iterations of deep analysis. Ready for your approval to proceed.*
"""
}}
</iteration_${planLoopIterations}_instructions>

<response_style>
- Write with confidence and clarity
- Use precise, professional language
- Structure information for easy scanning
- Be thorough but not verbose
- Avoid filler phrases, apologies, or hedging
- Focus on actionable insights
- Present information as knowledge, not discovery
</response_style>
</deep_thinking_mode>
"""
                    } else {
                        "\n\n<flash_mode>Direct execution mode. Respond efficiently and take action immediately. Keep responses concise and focused on completing the task.</flash_mode>"
                    }
                    val finalSystemPrompt = systemPrompt + thinkingModeInstruction

                    val agent = AIAgent(
                        promptExecutor = executorResult.executor,
                        llmModel = executorResult.model,
                        systemPrompt = finalSystemPrompt,
                        toolRegistry = currentToolRegistry,
                        maxIterations = maxIterations
                    )

                    // Execute agent
                    val response = withTimeout(providerTimeout) {
                        agent.run(currentPrompt)
                    }

                    currentResponse = response
                    Log.d(TAG, "Agent response (iter $planLoopIterations): ${response.take(100)}...")

                    // DEEP THINKING MODE: Force 5 iterations for comprehensive planning
                    if (isThinkingModeEnabled) {
                        if (planLoopIterations >= 5) {
                            Log.d(TAG, "Deep thinking mode: Completed all 5 planning iterations")
                            break
                        }
                        // Continue to next iteration for deep thinking
                        Log.d(TAG, "Deep thinking mode: Iteration $planLoopIterations complete, continuing...")
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
                        Log.d(TAG, "Flash mode: No pending steps, exiting plan loop")
                        break
                    }

                    // Safety: Check for stuck state (same step for too many iterations)
                    if (planLoopIterations >= maxPlanLoopIterations) {
                        Log.w(TAG, "Plan loop reached max iterations ($maxPlanLoopIterations), breaking to prevent infinite loop")
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
                Log.i(TAG, " Success with ${executorResult.provider} ($keyLabel) after $planLoopIterations iterations")
                agentProvider.recordSuccess(executorResult.provider)
                rateLimiter?.recordCall()

                // BATCH-3C: Post-process response (unmask PII, cache for semantic similarity)
                val finalResponse = try {
                    agentOptimizer.postProcess(userMessage, processed.maskedQuery, currentResponse)
                } catch (e: Exception) {
                    Log.w(TAG, "AgentOptimizer postProcess failed, using raw response", e)
                    currentResponse
                }

                // BATCH-3C: Caching is now handled by AgentOptimizer's semantic cache
                if (isSimpleQuery) {
                    Log.d(TAG, "Simple query processed - cached via AgentOptimizer semantic cache")
                }

                return AgentResult.Success(finalResponse, executorResult.provider)


            } catch (e: TimeoutCancellationException) {
                // Agent took too long - try next provider
                val timeoutUsed = getTimeoutForProvider(executorResult.provider)
                val errorMsg = "Request timed out after ${timeoutUsed / 1000}s"
                Log.w(TAG, "${executorResult.provider} $keyLabel: $errorMsg")
                errors.add(Triple(executorResult.provider, executorResult.keyIndex, errorMsg))

                // Record failure for this key/provider
                if (executorResult.apiKey.isNotEmpty()) {
                    agentProvider.recordKeyFailure(executorResult.apiKey, executorResult.provider, e)
                }
                agentProvider.recordFailure(executorResult.provider, e)

                if (availableExecutors.indexOf(executorResult) < availableExecutors.lastIndex) {
                    Log.d(TAG, "↻ Falling back to next provider/key...")
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
                        Log.i(TAG, " Conversational response from ${executorResult.provider} ($keyLabel)")
                        agentProvider.recordSuccess(executorResult.provider)

                        // BATCH-3C: Post-process conversational response (unmask PII, cache)
                        // Note: postProcess() handles caching to semantic cache internally
                        val finalConversationalResponse = try {
                            agentOptimizer.postProcess(userMessage, processed.maskedQuery, conversationalResponse)
                        } catch (ex: Exception) {
                            Log.w(TAG, "AgentOptimizer postProcess failed for conversational response", ex)
                            conversationalResponse
                        }

                        // BATCH-3C: Caching is now handled by AgentOptimizer's semantic cache
                        if (isSimpleQuery) {
                            Log.d(TAG, "Conversational response cached via AgentOptimizer semantic cache")
                        }

                        return AgentResult.Success(finalConversationalResponse, executorResult.provider)
                    }
                }

                // Not a recoverable action format error, treat as normal failure
                // SECURITY: Sanitize error messages to prevent API key leakage
                val sanitizedError = sanitizeErrorMessage(errorMsg)
                Log.w(TAG, "${executorResult.provider} $keyLabel failed: $sanitizedError")
                errors.add(Triple(executorResult.provider, executorResult.keyIndex, sanitizedError))

                if (executorResult.apiKey.isNotEmpty()) {
                    agentProvider.recordKeyFailure(executorResult.apiKey, executorResult.provider, e)
                }
                agentProvider.recordFailure(executorResult.provider, e)

                if (availableExecutors.indexOf(executorResult) < availableExecutors.lastIndex) {
                    Log.d(TAG, "↻ Falling back to next provider/key...")
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                // SECURITY: Sanitize error messages to prevent API key leakage
                val sanitizedError = sanitizeErrorMessage(errorMsg)

                // BUG FIX (L-001): Use ToolErrorType for structured error classification
                // Replaces fragile string matching with exception-type-based classification
                val errorType = ToolErrorType.classify(e)

                // Log the classified error type for debugging
                Log.d(TAG, "Error classified as: ${errorType::class.simpleName}")

                if (!errorType.shouldFailover()) {
                    // Tool error - return to user immediately without failover
                    val userMessage = errorType.toUserMessage()
                    // AGENT-007: Include error type in log for easier debugging
                    Log.w(TAG, "Tool error [${errorType::class.simpleName}] (no failover): $userMessage")
                    return AgentResult.Error("I couldn't complete that action: $userMessage")
                }

                Log.w(TAG, "${executorResult.provider} $keyLabel failed: $sanitizedError")
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
                    Log.d(TAG, "↻ Falling back to next provider/key...")
                }
            }
        }

        // All providers/keys failed - return combined error
        val errorSummary = if (errors.size == 1) {
            "Error: ${errors.first().third}"
        } else {
            // Group errors by provider for cleaner output
            val grouped = errors.groupBy { it.first }
            "All ${errors.size} attempts failed:\n" + grouped.entries.joinToString("\n") { (provider, providerErrors) ->
                if (providerErrors.size == 1) {
                    "• $provider: ${providerErrors.first().third}"
                } else {
                    "• $provider (${providerErrors.size} keys): ${providerErrors.first().third}"
                }
            }
        }

        Log.e(TAG, "All providers failed: $errorSummary")
        return AgentResult.Error("I couldn't complete your request. $errorSummary\n\nProviders will automatically retry after a cooldown period.")
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
            Log.d(TAG, "Complex multi-step task detected: actionCount=$actionCount, hasLongHorizon=$hasLongHorizon")
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