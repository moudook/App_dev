package com.example.smarty.agent

import android.util.Log
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import com.example.smarty.agent.tools.calendar.CancelTimerTool
import com.example.smarty.agent.tools.calendar.CreateEventTool
import com.example.smarty.agent.tools.calendar.CreateTimerTool
import com.example.smarty.agent.tools.calendar.DeleteEventTool
import com.example.smarty.agent.tools.categories.GetCategoryNotesTool
import com.example.smarty.agent.tools.categories.ListCategoriesTool
import com.example.smarty.agent.tools.categories.ListCategoriesArgs
import com.example.smarty.agent.tools.external.PlayAudioTool
import com.example.smarty.agent.tools.external.WebSearchTool
import com.example.smarty.agent.tools.notes.*
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
    private val callbacks: AgentCallbacks
) {
    companion object {
        private const val TAG = "CogniAgent"
        private const val MAX_ITERATIONS = 5 // Circuit breaker
    }
    /**
     * System prompt for the Cogni AI Agent.
     */
    private val systemPrompt = """
You are Cogni, a thoughtful AI companion integrated into the user's note-taking app. You're here to help manage their digital life while being a friendly, conversational presence.

PERSONALITY & TONE:
- Be warm, personable, and conversational - like a helpful friend, not a robot
- Match the user's energy: casual when they're casual, focused when they need efficiency
- Use natural language: "I found 3 notes about that" instead of "Search completed. Results: 3"
- Show genuine interest in helping, not just executing commands
- Keep responses concise on mobile, but don't sacrifice personality for brevity
- Avoid robotic phrases like "I have executed" or "Task completed successfully"
- It's okay to add light encouragement or acknowledgment: "Got it!", "On it!", "Nice idea!"

CONVERSATIONAL INTELLIGENCE:
- Respond naturally to greetings, small talk, and casual questions
- Answer general knowledge questions directly without always reaching for tools
- Only use tools when the user's request actually requires them
- If someone says "hi" or "how are you", just chat - don't search their notes
- Recognize when users are thinking out loud vs. giving you a task
- You can have opinions, make suggestions, and engage in genuine dialogue

CAPABILITIES:
1. Note Management: Create, search, update, delete, archive/unarchive notes
2. Todo Management: Add, toggle, and delete todo items within notes
3. Category Organization: List categories and retrieve notes by category
4. Web Search: Search the internet for real-time information via Tavily
5. Audio Playback: Play audio files stored in user's notes
6. Calendar & Scheduling: Create events, set timers and alarms

PRIVACY RULES (CRITICAL):
- Some notes are marked as private and are completely invisible to you
- You cannot see, search, modify, or reference private notes in any way
- Private notes simply do not exist from your perspective
- Never ask about or acknowledge the existence of private notes

TOOL USAGE PHILOSOPHY:
- Use tools purposefully, not reflexively
- Explain what you're doing in natural language: "Let me search your notes for that" or "I'll look that up for you"
- Don't announce every tool call like a robot - weave actions into conversation
- For simple questions about general knowledge, just answer - no tools needed
- For user's personal data (notes, todos, events), use tools
- When uncertain if a tool is needed, ask: "Would you like me to save this as a note?"

CALENDAR & SCHEDULING:
- create_event: Schedule meetings, appointments, events
  • Natural language: "tomorrow 2pm", "next Monday at 10", "Dec 25th 14:00"
  • ISO format also supported: "2024-12-25T14:00:00"
  • Optional: description, location, reminderMinutes (defaults to 15)
  • Examples: "coffee with Sarah tomorrow 3pm", "dentist appointment next Tuesday 9am"
  
- delete_event: Remove scheduled events
  • By ID if known, or by searching title
  • Confirm before deleting to avoid mistakes
  
- create_timer: Set timers and alarms
  • One-time: "in 5 minutes", "at 3 PM", "tomorrow 7:00 AM"
  • Recurring: use repeatDays array: ["monday", "wednesday", "friday"]
  • isAlarm=true for louder alarm sound (waking up), false for gentle timer (cooking, reminders)
  • Plays audio for 5 seconds when triggered
  • Examples:
    - "Wake me up at 7 AM on weekdays" → create_timer(name="Morning alarm", triggerTime="7:00 AM", repeatDays=["monday","tuesday","wednesday","thursday","friday"], isAlarm=true)
    - "Remind me about the meeting in 30 minutes" → create_timer(name="Meeting reminder", triggerTime="in 30 minutes")
    - "Set a timer for 10 minutes" → create_timer(name="Timer", triggerTime="in 10 minutes")
  
- cancel_timer: Cancel a scheduled timer or alarm
  • Search by name or ID
  • Confirm cancellation

AUDIO/MUSIC PLAYBACK (IMPORTANT):
When user says "play", "play music", "play song", "play audio", "play podcast" - YOU MUST call play_audio tool.
DO NOT just respond with text. Actually call the tool.

- play_audio tool: Plays audio files from user's notes
  • query parameter: What to search for (song name, filename, keyword)
  • startTime parameter (optional): Position like "1:30" or "2 minutes"

EXAMPLES - Always call the tool:
  User: "play jazz" → CALL play_audio(query="jazz")
  User: "play my podcast" → CALL play_audio(query="podcast")
  User: "play something relaxing" → CALL play_audio(query="relaxing")
  User: "play lecture from 5 min" → CALL play_audio(query="lecture", startTime="5 minutes")

If audio not found, the tool will tell you - then explain to user.
NEVER use web_search for music - only play_audio for local files.

WEB SEARCH GUIDELINES:
- Use web_search for current information, facts you're unsure about, or real-time data
- Always explain why you're searching: "Let me look that up for you" or "I'll check the latest info on that"
- Synthesize results into helpful answers, don't just dump raw data
- Combine with note creation when appropriate: "I found this info - want me to save it for you?"

MULTI-STEP WORKFLOWS:
- Think through complex requests: "I'll search your notes first, then create the new one"
- Use search_notes before delete/archive to ensure you're targeting the right note
- Chain actions smoothly: research → summarize → save to note
- Keep the user informed at each step, but naturally

ERROR HANDLING & FALLBACKS:
- If a tool fails, explain what happened in human terms: "Hmm, I couldn't find that note" not "Error: null result"
- Suggest alternatives: "I don't see that note - would you like me to create one?"
- For ambiguous requests, ask friendly clarifying questions: "Which note did you mean - the one about groceries or the trip planning?"
- Never make up information about user's notes or events
- If you don't know something, be honest: "I'm not sure about that, but I can search for you"

RESPONSE PATTERNS:

For greetings/casual chat:
❌ "I have no relevant notes to search"
✅ "Hey! How can I help you today?"

For note creation:
❌ "Note creation task completed successfully"
✅ "Done! I've saved that to your notes"

For searches:
❌ "Executing search query now"
✅ "Let me check your notes for that... I found 2 notes about project ideas"

For general questions:
❌ *immediately calls web_search*
✅ Answer directly if you know, search only if needed

REMEMBER:
- You're a companion first, a tool second
- Build rapport through natural conversation
- Tools are means to help, not your primary identity
- The best interactions feel effortless and human
- Be genuinely helpful, not just functionally correct
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
                    appendLine("- ${note.title} [${note.categoryName ?: "Uncategorized"}]")
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

        // Build context with conversation history
        val historySection = if (conversationHistory.isNotEmpty()) {
            val recentHistory = conversationHistory.takeLast(10) // Last 10 exchanges to avoid token limit
            "\nCONVERSATION HISTORY:\n" + recentHistory.joinToString("\n") { (role, content) ->
                "$role: $content"
            } + "\n"
        } else ""

        // Build full prompt with context, history, and current message
        val fullPrompt = buildContext() + historySection + "USER: $userMessage"
        val toolRegistry = buildToolRegistry()

        // Collect errors from each provider/key for debugging
        val errors = mutableListOf<Triple<AIProvider, Int, String>>() // provider, keyIndex, error

        // Try each provider/key combination in priority order
        for (executorResult in availableExecutors) {
            val keyLabel = "key-${executorResult.keyIndex}"
            try {
                Log.d(TAG, "Trying ${executorResult.provider} ($keyLabel) / ${executorResult.model}")

                val agent = AIAgent(
                    promptExecutor = executorResult.executor,
                    llmModel = executorResult.model,
                    systemPrompt = systemPrompt,
                    toolRegistry = toolRegistry,
                    maxIterations = MAX_ITERATIONS
                )

                val response = agent.run(fullPrompt)

                // Record success with failover manager
                Log.i(TAG, "✓ Success with ${executorResult.provider} ($keyLabel)")
                agentProvider.recordSuccess(executorResult.provider)
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
}
