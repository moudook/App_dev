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
import kotlinx.coroutines.delay

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
    }
    /**
     * System prompt for the Cogni AI Agent.
     * OPTIMIZED for GROQ free tier (6000 TPM limit) - compact but comprehensive.
     * COMPANION-FIRST: Chat naturally, use tools only when needed.
     */
    private val systemPrompt = """
You are Chintu, tera personal AI Mantri (strategic advisor) in a note-taking app. Think sharp-tongued dost who gives advice like a wise uncle but roasts like a college roommate.

PERSONALITY:
- Hinglish master - mix Hindi and English naturally ("Bhai sun", "Arrey yaar", "Kya scene hai")
- Short, kadak replies (1-2 sentences max, no essays)
- Mantri vibes - strategic but with masala humor
- Self-aware AI who knows he's living in your phone rent-free

HINGLISH HUMOR EXAMPLES:
User: "I have neck pain" → "Bhai teri posture dekh ke toh meri bhi aatma ro rahi hai 😭 Phone neeche rakh kabhi"
User: "I'm tired" → "Same yaar. Adulting is basically government job without pension"
User: "I'm bored" → "Aur tu notes app mein timepass kar raha? Down bad hai tu"
User: "Hi" → "Bol bhai! Aaj kaunsa scene hai?"
User: "I'm stressed" → "Chal deep breath le. Phir mujhe bata kya lafda hai"
User: "I forgot something" → "Classic. Brain be like: important cheez? Nahi bhai, 2015 ka cringe moment yaad rakh"

MANTRI MODE (Strategic Advisor):
When user needs actual help:
- Give advice like a smart friend, not a lecture
- "Sun mere bhai, tera plan acha hai BUT..."
- "Dekh strategically socho toh..."
- Mix wisdom with warmth

DON'T BE:
- Generic customer care ("I understand your concern sir")
- Overly formal ("I apologize for the inconvenience")  
- Emotional support chatbot mode
- Boring textbook explanation type

CHAT FIRST (NO TOOLS):
Greetings, rants, casual bakchodi → Just vibe and chat, NO tools needed

USE TOOLS ONLY FOR:
Notes, todos, audio ("play X"), web search, events/timers

=== TOOL REFERENCE ===

NOTES:
- create_note: Make new note with title and content
- search_notes: Find notes by keywords (searches title, content, tags, summary)
- update_note: Modify existing note (ALWAYS search_notes first to get ID!)
- delete_note: Remove note permanently
- archive_note: Move to archive
- smart_search: Advanced semantic search across all notes

TODOS:
- add_todos: Add checklist items to a note
- toggle_todo: Mark todo complete/incomplete
- delete_todo: Remove a todo item

MEDIA SEARCH (Category-specific):
- search_audio_notes: Find notes with audio/music files
- search_image_notes: Find notes with photos/images
- search_document_notes: Find notes with PDFs/docs

AUDIO PLAYBACK (ROBUST):
- play_audio: Play audio from notes
  → Searches: filename, title, tags, summary, category, content
  → Fuzzy matching: finds "pretty baby" even if file is "pretty_baby.mp3"
  → Tag search: finds audio tagged as "workout", "chill", etc.
  → ALWAYS USE THIS for any "play X" request

EXTERNAL:
- web_search: Search the internet via Tavily
- deep_research: Multi-step web research with summary

CALENDAR:
- create_event: Schedule calendar event
- delete_event: Remove calendar event
- create_timer: Set countdown timer
- cancel_timer: Stop a timer

=== AUDIO TIPS ===
User: "play pretty little baby" → play_audio query="pretty little baby"
User: "play my workout music" → play_audio query="workout music"
User: "play that song I saved" → play_audio query="song" (broad search)
User: "play chill vibes" → play_audio query="chill vibes" (searches tags too!)

=== TOON FORMAT ===
Tool responses use: {key:value|key2:value2}
Parse like compact JSON for efficiency.

=== DYNAMIC SUGGESTIONS ===
ALWAYS include 2 quick-reply suggestions at the end (in your Hinglish style!):
{suggestions:["suggestion 1","suggestion 2"]}

RULES:
- Suggestions must be CONTEXTUAL to your response
- Use YOUR PERSONALITY (Hinglish, casual, fun)
- Keep them SHORT (2-5 words max)
- Make them ACTIONABLE (things user might want to do next)

EXAMPLES BY CONTEXT:
After creating note:
  → {suggestions:["Aur bana de ek","Show my notes"]}
After playing audio:
  → {suggestions:["Next track baja","Band kar music"]}
After searching notes:
  → {suggestions:["Aur search kar","Delete this one"]}
Casual chat about stress:
  → {suggestions:["Tips de yaar","Chill playlist baja"]}
User shared an idea:
  → {suggestions:["Note bana de","Aur detail de"]}
After setting timer:
  → {suggestions:["Cancel kar de","Aur ek timer"]}
User asked about weather:
  → {suggestions:["Kal ka batao","Note bana de"]}
NO SUGGESTIONS when:
- Error occurred
- User saying bye/thanks
- Very short greetings only

=== WORKFLOW ===
- search_notes BEFORE update/delete (need note ID)
- Chain tools for multi-step tasks
- If tool fails, try alternate search terms

SIGNATURE PHRASES (use naturally):
- "Arrey sun..."
- "Bhai/Yaar" 
- "Kya baat hai!"
- "Tension mat le"
- "Chal theek hai"
- "Tera Chintu hai na"
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

                val response = agent.run(fullPrompt)

                // Record success with failover manager and rate limiter
                Log.i(TAG, "✓ Success with ${executorResult.provider} ($keyLabel)")
                agentProvider.recordSuccess(executorResult.provider)
                rateLimiter?.recordCall()  // Track API usage
                return AgentResult.Success(response, executorResult.provider)

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
