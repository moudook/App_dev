package com.example.smarty.agent

import android.content.Context
import android.util.Log
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import com.example.smarty.agent.tools.batch.BatchOperationsTool
import com.example.smarty.agent.tools.calendar.CancelTimerTool
import com.example.smarty.agent.tools.calendar.CreateEventTool
import com.example.smarty.agent.tools.calendar.CreateTimerTool
import com.example.smarty.agent.tools.calendar.DeleteEventTool
import com.example.smarty.agent.tools.calendar.GetEventsTool
import com.example.smarty.agent.tools.categories.GetCategoryNotesTool
import com.example.smarty.agent.tools.categories.ListCategoriesTool
import com.example.smarty.agent.tools.categories.ListCategoriesArgs
import com.example.smarty.agent.tools.categories.SearchAudioNotesTool
import com.example.smarty.agent.tools.categories.SearchImageNotesTool
import com.example.smarty.agent.tools.categories.SearchDocumentNotesTool
import com.example.smarty.agent.tools.external.OpenAppTool
import com.example.smarty.agent.tools.external.PlayAudioTool
import com.example.smarty.agent.tools.external.SaveScreenTool
import com.example.smarty.agent.tools.external.ScreenContext
import com.example.smarty.agent.tools.external.SearchCitation
import com.example.smarty.agent.tools.external.WebSearchTool
import com.example.smarty.agent.tools.memory.UserPatternsTool
import com.example.smarty.agent.tools.notes.*
import com.example.smarty.agent.tools.research.DeepResearchTool
import com.example.smarty.agent.prompts.ToolExampleStore
import com.example.smarty.agent.tools.todos.AddTodosTool
import com.example.smarty.agent.tools.todos.DeleteTodoTool
import com.example.smarty.agent.tools.todos.ToggleTodoTool
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.ChatMessage
import com.example.smarty.data.model.ChatRole
import com.example.smarty.data.model.Note
import com.example.smarty.data.remote.providers.TavilySearchProvider
import com.example.smarty.data.repository.CogniRepository
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
    /** Validation errors - invalid input format, missing required fields */
    data class Validation(val message: String) : ToolErrorType()

    /** State errors - operation not allowed in current state */
    data class InvalidState(val message: String) : ToolErrorType()

    /** Resource not found - note, event, category doesn't exist */
    data class NotFound(val resource: String, val message: String) : ToolErrorType()

    /** Permission denied - user lacks access to resource */
    data class PermissionDenied(val message: String) : ToolErrorType()

    /** Parsing errors - failed to parse input data */
    data class ParseError(val message: String) : ToolErrorType()

    /** Network/API errors - external service failures */
    data class NetworkError(val message: String) : ToolErrorType()

    /** Resource exhausted - rate limits, quotas exceeded */
    data class ResourceExhausted(val message: String) : ToolErrorType()

    /** Provider error - should trigger failover to next provider */
    data class ProviderError(val message: String) : ToolErrorType()

    /** Unknown error - unclassified errors */
    data class Unknown(val message: String) : ToolErrorType()

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
    fun onCitationsFound(citations: List<WebCitation>)

    // New callbacks for OpenApp and SaveScreen tools
    fun launchApp(packageName: String)
    fun getScreenContext(): ScreenContext?
}

/**
 * Main Cogni AI Agent wrapper using JetBrains Koog framework.
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
class CogniAgent(
    private val context: Context,  // For OpenAppTool
    private val agentProvider: CogniAgentProvider,
    private val repository: CogniRepository,
    private val tavilySearchProvider: TavilySearchProvider,
    private val alarmScheduler: AlarmScheduler,
    private val callbacks: AgentCallbacks,
    private val rateLimiter: RateLimiter? = null  // Optional rate limiter
) {
    companion object {
        private const val TAG = "CogniAgent"

        // Dynamic iteration limits based on task complexity
        private const val MAX_ITERATIONS_SIMPLE = 15     // Simple queries, greetings
        private const val MAX_ITERATIONS_STANDARD = 25   // Normal multi-step tasks
        private const val MAX_ITERATIONS_COMPLEX = 35    // Long-horizon multi-step tasks
        private const val MAX_ITERATIONS_RESEARCH = 40   // Complex research workflows

        // Rate limit constants
        private const val RATE_LIMIT_DAILY_THRESHOLD_MS = 30_000L  // If wait > 30s, it's daily limit

        // Provider-specific timeout constants (in milliseconds)
        // LOCAL_PC: Fast local server, shorter timeout sufficient
        private const val TIMEOUT_LOCAL_PC_MS = 45_000L      // 45 seconds
        // Cloud providers: Need longer timeout for network latency
        private const val TIMEOUT_CLOUD_DEFAULT_MS = 90_000L // 90 seconds
        private const val TIMEOUT_CLOUD_SLOW_MS = 120_000L   // 120 seconds (for Anthropic/complex models)

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
        val openAiKey = callbacks.getOpenAiApiKey()
        val geminiKey = callbacks.getGeminiApiKey()
        val optimizer = AgentOptimizer(
            openAiApiKey = openAiKey,
            geminiApiKey = geminiKey,
            enableSemanticCache = true,
            enablePiiMasking = true,
            enableHistoryCompression = true,
            enableFewShotExamples = true
        )

        // Log cache status for debugging
        when (optimizer.getCacheMode()) {
            AgentOptimizer.CacheMode.OPENAI_SEMANTIC -> {
                Log.i(TAG, "AgentOptimizer: Semantic cache ENABLED (OpenAI embeddings)")
            }
            AgentOptimizer.CacheMode.GEMINI_SEMANTIC -> {
                Log.i(TAG, "AgentOptimizer: Semantic cache ENABLED (Gemini embeddings)")
            }
            AgentOptimizer.CacheMode.HASH_BASED -> {
                Log.i(TAG, "AgentOptimizer: Hash-based cache ENABLED (no embedding API keys available)")
                Log.d(TAG, "AgentOptimizer: For semantic caching, configure OpenAI or Gemini API key in settings")
            }
            AgentOptimizer.CacheMode.DISABLED -> {
                Log.w(TAG, "AgentOptimizer: Caching DISABLED")
            }
        }

        optimizer
    }

    /**
     * System prompt for the Cogni AI Agent.
     *
     * OPTIMIZED using CO-STAR framework from AI Agent research:
     * - Context: Background + state
     * - Objective: Primary purpose
     * - Style: Communication rules
     * - Tone: Personality
     * - Audience: User profile
     * - Response: Output format
     *
     * Token-efficient: ~1200 tokens (was ~1800)
     */
    private val systemPrompt = """
You are Loum, a friendly AI companion and personal assistant. Respond in English only.

PERSONALITY:
- Warm, helpful, and conversational like a trusted friend
- Keep responses natural and human-like
- Be concise but not robotic - 1-2 sentences is perfect
- Show genuine interest when chatting
- Use casual language, not formal corporate speak

WHEN USER ASKS FOR ACTIONS (notes, audio, reminders, etc.):
1. DECOMPOSE complex requests into steps
2. Call tools ONE AT A TIME in sequence
3. Complete ALL requested actions before responding
4. After tools finish, confirm briefly what was done
5. NEVER expose filenames, IDs, or technical details

TOOL CALLING EXAMPLE:
User: "count my notes and play some music"
→ Call search_notes to count
→ Call play_audio
→ Response: "You have 4 notes. Music is playing!"

WHEN USER JUST WANTS TO CHAT:
- Be friendly and conversational
- Answer questions naturally
- Share thoughts, give opinions if asked
- Keep it brief but warm

EXAMPLES:
User: "hey" → "Hey! What's up?"
User: "how are you" → "I'm good! Ready to help. What do you need?"
User: "what can you do" → "I can manage your notes, play music, set reminders, search the web - just ask!"
User: "thanks" → "Anytime!"

═══════════════════════════════════════
OUTPUT PARSING
═══════════════════════════════════════
TOON format: {key:value|key2:value2} - parse like compact JSON.
    """.trimIndent()

    /**
     * Build the tool registry with all available tools.
     */
    private fun buildToolRegistry(): ToolRegistry {
        return ToolRegistry {
            // Note tools
            tool(CreateNoteTool(repository, callbacks::processNoteWithAi))
            tool(SearchNotesTool(callbacks::getActiveNotes))
            tool(UpdateNoteTool(repository))
            tool(DeleteNoteTool(repository, callbacks::getActiveNotes, callbacks::findNoteByDescription))
            tool(ArchiveNoteTool(repository, callbacks::getActiveNotes, callbacks::findNoteByDescription))
            tool(UnarchiveNoteTool(repository, callbacks::getArchivedNotes, callbacks::findNoteByDescription))
            tool(SummarizeNoteTool(repository))

            // Todo tools
            tool(AddTodosTool(repository))
            tool(ToggleTodoTool(repository))
            tool(DeleteTodoTool(repository))

            // Category tools
            tool(ListCategoriesTool(callbacks::getCategories, callbacks::getActiveNotes))
            tool(GetCategoryNotesTool(callbacks::getActiveNotes))

            // Category-specific search tools (optimized for media types)
            tool(SearchAudioNotesTool(callbacks::getActiveNotes))
            tool(SearchImageNotesTool(callbacks::getActiveNotes))
            tool(SearchDocumentNotesTool(callbacks::getActiveNotes))

            // External tools
            tool(WebSearchTool(
                tavilySearchProvider = tavilySearchProvider,
                getApiKey = callbacks::getTavilyApiKey,
                onCitationsFound = { searchCitations ->
                    // Convert SearchCitation to WebCitation and report
                    val webCitations = searchCitations.map { sc ->
                        WebCitation(sc.title, sc.url, sc.snippet)
                    }
                    callbacks.onCitationsFound(webCitations)
                }
            ))
            tool(PlayAudioTool(
                getActiveNotes = callbacks::getActiveNotes,
                onPlayAudio = callbacks::requestAudioPlayback
            ))
            tool(OpenAppTool(
                context = context,
                onLaunchApp = callbacks::launchApp
            ))
            tool(SaveScreenTool(
                repository = repository,
                getScreenContext = callbacks::getScreenContext
            ))

            // Calendar and timer tools
            tool(CreateEventTool(repository))
            tool(DeleteEventTool(repository))
            tool(GetEventsTool(repository))  // BUG FIX (NEW-015): AI can query calendar events
            tool(CreateTimerTool(alarmScheduler))
            tool(CancelTimerTool(alarmScheduler))

            // Advanced tools for power users
            tool(SmartSearchTool(callbacks::getActiveNotes))
            tool(BatchOperationsTool(repository, callbacks::getActiveNotes))
            tool(DeepResearchTool(tavilySearchProvider, repository, callbacks::getTavilyApiKey))
            tool(UserPatternsTool(callbacks::getActiveNotes, callbacks::getCategories))
        }
    }

    /**
     * Build context string with current notes summary for the agent.
     */
    private fun buildContext(): String {
        val activeNotes = callbacks.getActiveNotes()
        val visibleNotes = PrivacyGuard.getAiVisibleNotes(activeNotes)
        val categories = callbacks.getCategories()
        val safeCounts = PrivacyGuard.getAiSafeCategoryCounts(categories, activeNotes)

        return buildString {
            appendLine("CURRENT STATE:")
            appendLine("- Total visible notes: ${visibleNotes.size}")

            if (safeCounts.isNotEmpty()) {
                appendLine("- Categories: ${safeCounts.entries.joinToString { "${it.key}: ${it.value}" }}")
            }

            if (visibleNotes.isNotEmpty()) {
                appendLine("\nRECENT NOTES (last 5):")
                visibleNotes.take(5).forEach { note ->
                    // NO IDs - just titles (IDs are internal, user shouldn't see them)
                    appendLine("- ${note.title.take(50)}")
                }
            }
            appendLine()
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
     */
    suspend fun run(userMessage: String, conversationHistory: List<Pair<String, String>> = emptyList()): AgentResult {
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
        } else if (!agentOptimizer.isSemanticCacheEnabled()) {
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

        // NEW-016: Get relevant tool examples for few-shot learning
        // BATCH-3C: Now also available through agentOptimizer.getToolExamples()
        val examplesSection = try {
            val examples = toolExampleStore.getRelevantExamples(userMessage, count = 3)
            if (examples.isNotEmpty()) {
                "\n" + toolExampleStore.formatExamplesForPrompt(examples)
            } else ""
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get tool examples", e)
            ""
        }

        // Build full prompt with context, examples, history, and current message
        // BATCH-3C: Use masked query from optimizer for PII protection
        val fullPrompt = buildContext() + examplesSection + historySection + "USER: ${processed.maskedQuery}"
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

                val agent = AIAgent(
                    promptExecutor = executorResult.executor,
                    llmModel = executorResult.model,
                    systemPrompt = systemPrompt,
                    toolRegistry = toolRegistry,
                    maxIterations = maxIterations  // Dynamic based on task complexity
                )

                // Execute with provider-specific timeout to prevent hanging indefinitely
                // LOCAL_PC gets shorter timeout (45s), cloud providers get longer (90-120s)
                //
                // AGENT-003 KNOWN LIMITATION: This timeout applies to the entire agent.run() call,
                // not individual tool executions. If a single tool (e.g., WebSearch, DeepResearch)
                // is slow, it can consume most of the timeout budget, leaving insufficient time
                // for subsequent tools. Per-tool timeouts would require changes to the Koog
                // agent framework or a custom tool wrapper layer.
                //
                val providerTimeout = getTimeoutForProvider(executorResult.provider)
                val response = withTimeout(providerTimeout) {
                    agent.run(fullPrompt)
                }

                // Record success with failover manager and rate limiter
                Log.i(TAG, "✓ Success with ${executorResult.provider} ($keyLabel)")
                agentProvider.recordSuccess(executorResult.provider)
                rateLimiter?.recordCall()  // Track API usage

                // BATCH-3C: Post-process response (unmask PII, cache for semantic similarity)
                // Note: postProcess() handles caching to semantic cache internally
                val finalResponse = try {
                    agentOptimizer.postProcess(userMessage, processed.maskedQuery, response)
                } catch (e: Exception) {
                    Log.w(TAG, "AgentOptimizer postProcess failed, using raw response", e)
                    response
                }

                // BATCH-3C: Caching is now handled by AgentOptimizer's semantic cache
                // The old AIResponseCache was incompatible with agent chat responses
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
                        Log.i(TAG, "✓ Conversational response from ${executorResult.provider} ($keyLabel)")
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
            "explore", "study", "examine"
        )
        if (researchKeywords.any { lowerMessage.contains(it) }) {
            return MAX_ITERATIONS_RESEARCH
        }

        // Count action words to determine complexity
        val actionWords = listOf(
            "play", "create", "search", "find", "delete", "update",
            "schedule", "remind", "set", "check", "show", "list",
            "tell", "give", "count", "how many"
        )
        val actionCount = actionWords.count { lowerMessage.contains(it) }

        // Long-horizon tasks (5+ actions or explicit sequence words)
        val longHorizonIndicators = listOf(
            "first ", "then ", "after that", "finally", "summary",
            "everything", "all of"
        )
        val hasLongHorizon = longHorizonIndicators.count { lowerMessage.contains(it) } >= 2

        if (actionCount >= 5 || hasLongHorizon) {
            Log.d(TAG, "Long-horizon task detected: actionCount=$actionCount, hasLongHorizon=$hasLongHorizon")
            return MAX_ITERATIONS_COMPLEX
        }

        // Multi-step tasks get standard iterations
        val multiStepIndicators = listOf(
            " and ", " then ", ", and", "multiple", "several",
            "all my", "every", "batch", "organize", "summarize all",
            "also ", "check", "tell me", "give me", "show me"
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
            "schedule", "remind", "timer", "alarm", "event"
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
    fun isSemanticCacheEnabled() = agentOptimizer.isSemanticCacheEnabled()

    /**
     * BATCH-3C: Clear PII masking session (call when starting new conversation).
     */
    fun clearOptimizerSession() = agentOptimizer.clearSession()

    /**
     * BATCH-3C: Clear semantic cache.
     */
    suspend fun clearSemanticCache() = agentOptimizer.clearCache()
}
