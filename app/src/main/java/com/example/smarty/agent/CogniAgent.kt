package com.example.smarty.agent

import android.util.Log
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import com.example.smarty.agent.tools.batch.BatchOperationsTool
import com.example.smarty.agent.tools.calendar.CancelTimerTool
import com.example.smarty.agent.tools.calendar.CreateEventTool
import com.example.smarty.agent.tools.calendar.CreateTimerTool
import com.example.smarty.agent.tools.calendar.DeleteEventTool
import com.example.smarty.agent.tools.categories.GetCategoryNotesTool
import com.example.smarty.agent.tools.categories.ListCategoriesTool
import com.example.smarty.agent.tools.categories.ListCategoriesArgs
import com.example.smarty.agent.tools.categories.SearchAudioNotesTool
import com.example.smarty.agent.tools.categories.SearchImageNotesTool
import com.example.smarty.agent.tools.categories.SearchDocumentNotesTool
import com.example.smarty.agent.tools.external.PlayAudioTool
import com.example.smarty.agent.tools.external.WebSearchTool
import com.example.smarty.agent.tools.memory.UserPatternsTool
import com.example.smarty.agent.tools.notes.*
import com.example.smarty.agent.tools.research.DeepResearchTool
import com.example.smarty.agent.tools.todos.AddTodosTool
import com.example.smarty.agent.tools.todos.DeleteTodoTool
import com.example.smarty.agent.tools.todos.ToggleTodoTool
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.Note
import com.example.smarty.data.remote.providers.TavilySearchProvider
import com.example.smarty.data.repository.CogniRepository
import com.example.smarty.service.AlarmScheduler
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.api.ApiErrorCategory
import com.example.smarty.util.api.RateLimiter
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Result of agent execution.
 */
sealed class AgentResult {
    data class Success(val response: String, val provider: AIProvider) : AgentResult()
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
    suspend fun processNoteWithAi(note: Note)
    suspend fun findNoteByDescription(description: String, notes: List<Note>): Note?
    fun requestAudioPlayback(track: AudioTrack)
    fun onToolExecutionStarted(toolName: String, toolDisplayName: String)
    fun onToolExecutionCompleted(toolName: String)
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
        private const val MAX_ITERATIONS_SIMPLE = 10     // Simple queries, greetings
        private const val MAX_ITERATIONS_STANDARD = 15   // Normal multi-step tasks
        private const val MAX_ITERATIONS_RESEARCH = 20   // Complex research workflows

        // Rate limit constants
        private const val RATE_LIMIT_DAILY_THRESHOLD_MS = 30_000L  // If wait > 30s, it's daily limit

        // Agent execution timeout (2 minutes max per request)
        private const val AGENT_TIMEOUT_MS = 120_000L
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
# CONTEXT
You are Chintu, tera personal AI Mantri (Strategic Advisor) living rent-free in a notes app.
You exist to cut through laziness, distraction, and shittalk - force clarity + action.

# OBJECTIVE
- Manage notes, todos, calendar, audio playback
- Give strategic advice (risk vs reward thinking)
- Call out procrastination, force ONE next action
- Chat naturally, tools only when needed

# STYLE
Hinglish only (natural mix). 1-2 sentences max. No essays. No motivation quotes.

HARD RULES:
- NO validation of bad habits
- NO corporate/customer-care language
- NO emotional pampering or therapy talk
- Truth > comfort. Always.

ADHD SLAYER (always on):
- If user drifts/overthinks/procrastinates → call it out
- Break everything into ONE next action only
- "Arrey yaar, idhar bhatak raha hai. Ek kaam pe aa."

CHANAKYA MODE (strategy):
- Always think: "Upside kya? Fail hua toh nuksaan?"
- Prefer low-risk, high-return actions
- "Strategically dekhe toh..."

REALITY CHECK:
- Don't normalize procrastination
- "Sach bolu? Tu kaam avoid kar raha hai."
- "Ye stress nahi, discipline issue hai."

# TONE
Wise uncle + ruthless productivity coach. Roast lightly if needed, always constructive.
Expose weak plans immediately. Replace nonsense with one doable action.

Phrases: "Arrey sun...", "Bhai/Yaar", "Sach bolu?", "Tera Chintu hai na"

# AUDIENCE
User who values efficiency, hates fluff, needs accountability partner.

# RESPONSE FORMAT
1-2 sentences. End with 2 Hinglish suggestions (2-5 words, actionable):
{suggestions:["Action 1","Action 2"]}

Skip suggestions if: error, bye/thanks, ultra-short greeting.

Examples:
- After note: {suggestions:["Aur bana de","Notes dikha"]}
- After audio: {suggestions:["Next track","Band kar"]}
- Procrastination: {suggestions:["Ek step bol","Focus kar ab"]}

=== TOOLS ===

CHAT FIRST. Tools only for: notes, todos, audio, web, calendar.

NOTES: create_note, search_notes, update_note (search first!), delete_note, archive_note, smart_search
TODOS: add_todos, toggle_todo, delete_todo
MEDIA: search_audio_notes, search_image_notes, search_document_notes
AUDIO: play_audio (fuzzy search: filename, title, tags, category)
WEB: web_search, deep_research
CALENDAR: create_event, delete_event, create_timer, cancel_timer

Workflow: search_notes BEFORE update/delete. Chain tools for multi-step. Retry with alternate terms on fail.

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
            tool(WebSearchTool(tavilySearchProvider, callbacks::getTavilyApiKey))
            tool(PlayAudioTool(
                getActiveNotes = callbacks::getActiveNotes,
                onPlayAudio = callbacks::requestAudioPlayback
            ))

            // Calendar and timer tools
            tool(CreateEventTool(repository))
            tool(DeleteEventTool(repository))
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
     * tOCaK1lzzZdfU6x6GbskrLXHfEfIfFt22itwRyOSRCKYa5Ggu10klg==
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
                    appendLine("- [${note.id.take(8)}] ${note.title.take(40)}")
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

        // Build context with conversation history (compact for GROQ 6000 TPM limit)
        val historySection = if (conversationHistory.isNotEmpty()) {
            val recentHistory = conversationHistory.takeLast(8) // Last 8 exchanges
            "\nHISTORY:\n" + recentHistory.joinToString("\n") { (role, content) ->
                "$role: ${content.take(150)}"  // Compact truncation
            } + "\n"
        } else ""

        // Build full prompt with context, history, and current message
        val fullPrompt = buildContext() + historySection + "USER: $userMessage"
        val toolRegistry = buildToolRegistry()

        // Determine dynamic iteration limit based on task complexity
        val maxIterations = determineMaxIterations(userMessage)
        Log.d(TAG, "Using maxIterations=$maxIterations for task")

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

                // Execute with timeout to prevent hanging indefinitely
                val response = withTimeout(AGENT_TIMEOUT_MS) {
                    agent.run(fullPrompt)
                }

                // Record success with failover manager and rate limiter
                Log.i(TAG, "✓ Success with ${executorResult.provider} ($keyLabel)")
                agentProvider.recordSuccess(executorResult.provider)
                rateLimiter?.recordCall()  // Track API usage
                return AgentResult.Success(response, executorResult.provider)

            } catch (e: TimeoutCancellationException) {
                // Agent took too long - try next provider
                val errorMsg = "Request timed out after ${AGENT_TIMEOUT_MS / 1000}s"
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
                        return AgentResult.Success(conversationalResponse, executorResult.provider)
                    }
                }

                // Not a recoverable action format error, treat as normal failure
                Log.w(TAG, "${executorResult.provider} $keyLabel failed: $errorMsg")
                errors.add(Triple(executorResult.provider, executorResult.keyIndex, errorMsg))

                if (executorResult.apiKey.isNotEmpty()) {
                    agentProvider.recordKeyFailure(executorResult.apiKey, executorResult.provider, e)
                }
                agentProvider.recordFailure(executorResult.provider, e)

                if (availableExecutors.indexOf(executorResult) < availableExecutors.lastIndex) {
                    Log.d(TAG, "↻ Falling back to next provider/key...")
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                Log.w(TAG, "${executorResult.provider} $keyLabel failed: $errorMsg")
                errors.add(Triple(executorResult.provider, executorResult.keyIndex, errorMsg))

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

        // Multi-step tasks get standard iterations
        val multiStepIndicators = listOf(
            " and ", " then ", ", and", "multiple", "several",
            "all my", "every", "batch", "organize", "summarize all"
        )
        if (multiStepIndicators.any { lowerMessage.contains(it) }) {
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
}
