package com.example.smarty.agent

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
import com.example.smarty.agent.tools.external.PlayAudioTool
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
        private const val MAX_ITERATIONS_SIMPLE = 15     // Simple queries, greetings
        private const val MAX_ITERATIONS_STANDARD = 25   // Normal multi-step tasks
        private const val MAX_ITERATIONS_COMPLEX = 35    // Long-horizon multi-step tasks
        private const val MAX_ITERATIONS_RESEARCH = 40   // Complex research workflows

        // Rate limit constants
        private const val RATE_LIMIT_DAILY_THRESHOLD_MS = 30_000L  // If wait > 30s, it's daily limit

        // Agent execution timeout (2 minutes max per request)
        private const val AGENT_TIMEOUT_MS = 120_000L

        /**
         * SECURITY: Sanitize error messages to prevent API key leakage in chat UI.
         * Removes any patterns that might contain API keys from various providers.
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
            tool(WebSearchTool(tavilySearchProvider, callbacks::getTavilyApiKey))
            tool(PlayAudioTool(
                getActiveNotes = callbacks::getActiveNotes,
                onPlayAudio = callbacks::requestAudioPlayback
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

        // Build context with conversation history (compact for GROQ 6000 TPM limit)
        // Use symbols instead of role names to prevent LLM from mimicking "USER:" format
        val historySection = if (conversationHistory.isNotEmpty()) {
            val recentHistory = conversationHistory.takeLast(8) // Last 8 exchanges
            "\nPREVIOUS:\n" + recentHistory.joinToString("\n") { (role, content) ->
                val prefix = if (role.equals("user", ignoreCase = true)) ">" else "<"
                "$prefix ${content.take(150)}"  // > for user, < for assistant
            } + "\n"
        } else ""

        // NEW-016: Get relevant tool examples for few-shot learning
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
        val fullPrompt = buildContext() + examplesSection + historySection + "USER: $userMessage"
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

                // BUG FIX (L-001): Distinguish tool errors from provider errors
                // Tool errors should NOT trigger provider failover (wastes API calls)
                val isToolError = e is IllegalArgumentException ||
                                  e is IllegalStateException ||
                                  e is NumberFormatException ||
                                  errorMsg.contains("Invalid", ignoreCase = true) ||
                                  errorMsg.contains("cannot be", ignoreCase = true) ||
                                  errorMsg.contains("parse", ignoreCase = true)

                if (isToolError) {
                    // Tool error - return to user immediately without failover
                    Log.w(TAG, "Tool error (no failover): $sanitizedError")
                    return AgentResult.Error("I couldn't complete that action: $sanitizedError")
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
}
