package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentCommand
import com.example.smarty.protocol.AgentEvent
import com.example.smarty.protocol.TimerInfo
import com.example.smarty.protocol.NoteInfo
import com.example.smarty.protocol.CalendarEventInfo
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.data.ConversationSummarizer
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.TimerRepository
import com.example.smarty.server.data.CalendarRepository
import com.example.smarty.server.llm.LlmProvider
import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.ToolDefinition
import com.example.smarty.server.llm.ToolParameters
import com.example.smarty.server.llm.ToolProperty
import com.example.smarty.server.llm.LlmCache
import com.example.smarty.server.llm.LlmCacheKey
import com.example.smarty.server.llm.LlmUsage
import com.example.smarty.core.common.util.PIIMasker
import com.example.smarty.server.tools.TavilySearchTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import net.logstash.logback.argument.StructuredArguments.kv
import io.micrometer.core.instrument.Metrics
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Local representation of a chat session.
 */
data class ChatSession(
    val sessionId: String,
    val messages: MutableList<LlmMessage> = mutableListOf(),
    var lastInteractedAt: Long = System.currentTimeMillis()
)

/**
 * Server-side AI Agent with agentic tool loop.
 * Orchestrates the "Remote Brain" logic using a pluggable LLM provider.
 * Tools execute server-side; results feed back to the LLM for intelligent replies.
 * All operations are scoped by userId for multi-tenant isolation.
 */
class ServerAgent(
    private val llmProvider: LlmProvider,
    private val tavilyTool: TavilySearchTool,
    private val vectorStore: PostgresVectorStore,
    private val summarizer: ConversationSummarizer,
    private val noteRepository: NoteRepository?,
    private val timerRepository: TimerRepository?,
    private val calendarRepository: CalendarRepository?,
    private val eventEmitter: suspend (AgentEvent) -> Unit,
    private val userId: String = "dev-user"
) {
    private val logger = LoggerFactory.getLogger(ServerAgent::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val toolExampleStore = ToolExampleStore()
    
    // Initialize PIIMasker securely
    private val piiMasker = PIIMasker(object : com.example.smarty.core.common.util.Logger {
        override fun d(tag: String, message: String) = logger.debug("[$tag] $message")
        override fun i(tag: String, message: String) = logger.info("[$tag] $message")
        override fun w(tag: String, message: String, throwable: Throwable?) = logger.warn("[$tag] $message", throwable)
        override fun e(tag: String, message: String, throwable: Throwable?) = logger.error("[$tag] $message", throwable)
    })

    // Session cache (simplified for example)
    private val sessions = ConcurrentHashMap<String, ChatSession>()

    
    // KOOG-inspired infrastructure
    private val tracer: AgentTracer = PostgresTracer(userId)
    private val persistenceManager = AgentPersistenceManager(userId)

    private val MAX_HISTORY = 20
    private val RECENT_WINDOW = 10

    // Security limits to prevent runaway execution
    companion object {
        const val MAX_EXECUTION_TIME_MS = 30 * 60 * 1000L  // 30 minutes hard limit
        const val MAX_TOOL_CALLS = 50  // Max tool calls per session
        const val MAX_ITERATIONS = 100 // Max LLM iterations
    }

    private val tools = listOf(
        ToolDefinition(
            name = "create_note",
            description = "Save a new note/info.",
            parameters = ToolParameters(
                properties = mapOf(
                    "title" to ToolProperty("string", "Note title"),
                    "content" to ToolProperty("string", "Note content"),
                    "category" to ToolProperty("string", "Optional category")
                ),
                required = listOf("title", "content")
            )
        ),
        ToolDefinition(
            name = "search_notes",
            description = "Search saved notes/knowledge.",
            parameters = ToolParameters(
                properties = mapOf(
                    "query" to ToolProperty("string", "Search query"),
                    "filter" to ToolProperty("string", "Category filter")
                ),
                required = listOf("query")
            )
        ),
        ToolDefinition(
            name = "schedule_event",
            description = "Add calendar event.",
            parameters = ToolParameters(
                properties = mapOf(
                    "title" to ToolProperty("string", "Event title"),
                    "startTime" to ToolProperty("number", "Start UTC ms"),
                    "endTime" to ToolProperty("number", "End UTC ms"),
                    "description" to ToolProperty("string", "Extra info"),
                    "reminderMinutes" to ToolProperty("number", "Reminder lead time (mins). Default 15.")
                ),
                required = listOf("title", "startTime", "endTime")
            )
        ),
        ToolDefinition(
            name = "list_events",
            description = "List events for a date.",
            parameters = ToolParameters(
                properties = mapOf(
                    "date" to ToolProperty("number", "Date in UTC ms")
                ),
                required = listOf("date")
            )
        ),
        ToolDefinition(
            name = "delete_event",
            description = "Remove a calendar event.",
            parameters = ToolParameters(
                properties = mapOf(
                    "eventId" to ToolProperty("string", "Event ID")
                ),
                required = listOf("eventId")
            )
        ),
        ToolDefinition(
            name = "set_timer",
            description = "Set countdown timer.",
            parameters = ToolParameters(
                properties = mapOf(
                    "name" to ToolProperty("string", "Timer label"),
                    "duration" to ToolProperty("string", "Human duration (e.g. '10m')")
                ),
                required = listOf("name", "duration")
            )
        ),
        ToolDefinition(
            name = "set_alarm",
            description = "Set alarm for specific time.",
            parameters = ToolParameters(
                properties = mapOf(
                    "name" to ToolProperty("string", "Alarm label"),
                    "time" to ToolProperty("string", "Human time (e.g. '7 AM')")
                ),
                required = listOf("name", "time")
            )
        ),
        ToolDefinition(
            name = "launch_app",
            description = "Launch Android app by package name.",
            parameters = ToolParameters(
                properties = mapOf(
                    "packageName" to ToolProperty("string", "Package name (e.g. 'com.google.android.calendar')")
                ),
                required = listOf("packageName")
            )
        ),
        ToolDefinition(
            name = "take_screenshot",
            description = "Take device screenshot.",
            parameters = ToolParameters(properties = emptyMap(), required = emptyList())
        ),
        ToolDefinition(
            name = "toggle_setting",
            description = "Toggle WiFi/Bluetooth/Flashlight.",
            parameters = ToolParameters(
                properties = mapOf(
                    "setting" to ToolProperty("string", "wifi/bluetooth/flashlight"),
                    "enable" to ToolProperty("boolean", "True=ON, False=OFF")
                ),
                required = listOf("setting", "enable")
            )
        ),
        ToolDefinition(
            name = "web_search",
            description = "Search the live web for current info.",
            parameters = ToolParameters(
                properties = mapOf(
                    "query" to ToolProperty("string", "Search query")
                ),
                required = listOf("query")
            )
        ),
        ToolDefinition(
            name = "query_knowledge",
            description = "Deep search over private notes and memories.",
            parameters = ToolParameters(
                properties = mapOf(
                    "query" to ToolProperty("string", "Target information")
                ),
                required = listOf("query")
            )
        ),
        ToolDefinition(
            name = "summarize_session",
            description = "Generate a summary of the current session.",
            parameters = ToolParameters(properties = emptyMap(), required = emptyList())
        ),
        ToolDefinition(
            name = "control_media",
            description = "Control music/video playback.",
            parameters = ToolParameters(
                properties = mapOf(
                    "action" to ToolProperty("string", "pause/resume/stop/next/previous", enum = listOf("pause", "resume", "stop", "next", "previous"))
                ),
                required = listOf("action")
            )
        ),
        ToolDefinition(
            name = "seek_media",
            description = "Seek media position.",
            parameters = ToolParameters(
                properties = mapOf(
                    "positionMs" to ToolProperty("number", "Position in ms")
                ),
                required = listOf("positionMs")
            )
        ),
        ToolDefinition(
            name = "store_context",
            description = "Save user preference/fact.",
            parameters = ToolParameters(
                properties = mapOf(
                    "content" to ToolProperty("string", "Fact to remember"),
                    "type" to ToolProperty(
                        type = "string",
                        description = "factual/preference/episodic",
                        enum = listOf("factual", "preference", "episodic")
                    )
                ),
                required = listOf("content", "type")
            )
        ),
        ToolDefinition(
            name = "update_context",
            description = "Update user fact/preference.",
            parameters = ToolParameters(
                properties = mapOf(
                    "id" to ToolProperty("string", "Context ID"),
                    "content" to ToolProperty("string", "New fact content"),
                    "type" to ToolProperty(
                        type = "string",
                        description = "factual/preference/episodic",
                        enum = listOf("factual", "preference", "episodic")
                    )
                ),
                required = listOf("id", "content", "type")
            )
        ),
        ToolDefinition(
            name = "delete_context",
            description = "Delete user fact/preference.",
            parameters = ToolParameters(
                properties = mapOf(
                    "id" to ToolProperty("string", "Context ID")
                ),
                required = listOf("id")
            )
        ),
        ToolDefinition(
            name = "update_note",
            description = "Update note title/content.",
            parameters = ToolParameters(
                properties = mapOf(
                    "noteId" to ToolProperty("string", "Note ID"),
                    "title" to ToolProperty("string", "New title"),
                    "content" to ToolProperty("string", "New content")
                ),
                required = listOf("noteId")
            )
        ),
        ToolDefinition(
            name = "delete_note",
            description = "Delete note.",
            parameters = ToolParameters(
                properties = mapOf(
                    "noteId" to ToolProperty("string", "Note ID")
                ),
                required = listOf("noteId")
            )
        ),
        ToolDefinition(
            name = "archive_note",
            description = "Archive note.",
            parameters = ToolParameters(
                properties = mapOf(
                    "noteId" to ToolProperty("string", "Note ID")
                ),
                required = listOf("noteId")
            )
        ),
        ToolDefinition(
            name = "navigate",
            description = "Switch screens: home/calendar/stacks/archive/settings.",
            parameters = ToolParameters(
                properties = mapOf(
                    "screen" to ToolProperty("string", "Target screen")
                ),
                required = listOf("screen")
            )
        ),
        ToolDefinition(
            name = "share",
            description = "Share info with other apps.",
            parameters = ToolParameters(
                properties = mapOf(
                    "content" to ToolProperty("string", "Content to share"),
                    "title" to ToolProperty("string", "Optional share title")
                ),
                required = listOf("content")
            )
        ),
        ToolDefinition(
            name = "generate_image",
            description = "Generate image (COMING SOON). Tell user it's unavailable.",
            parameters = ToolParameters(
                properties = mapOf(
                    "prompt" to ToolProperty("string", "Image description")
                ),
                required = listOf("prompt")
            )
        )
    )

    suspend fun run(
        query: String,
        sessionId: String = UUID.randomUUID().toString(),
        history: List<LlmMessage> = emptyList(),
        modelOverride: String? = null,
        clientTimezone: String? = null,
        clientTimeMillis: Long? = null
    ): String {
        if (query.length > 10000) {
            throw IllegalArgumentException("Query too long")
        }

        return try {
            withTimeout(MAX_EXECUTION_TIME_MS) {
                runInternal(query, sessionId, history, modelOverride, clientTimezone, clientTimeMillis)
            }
        } catch (e: TimeoutCancellationException) {
            logger.error("Agent execution exceeded ${MAX_EXECUTION_TIME_MS / 60000} minute limit for user: $userId")
            emit(AgentEvent.Error(
                eventId = java.util.UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                message = "I had to stop - the operation took too long. Try breaking it into smaller tasks.",
                code = "TIMEOUT"
            ))
            "Operation timed out. Please try a simpler request."
        }
    }

    private suspend fun runInternal(
        query: String,
        sessionId: String,
        history: List<LlmMessage>,
        modelOverride: String?,
        clientTimezone: String?,
        clientTimeMillis: Long?
    ): String {
        var toolCallCount = 0

        val startTime = System.currentTimeMillis()
        logger.info("Agent starting for query: $query (Session: $sessionId)")
        
        // PII: Mask the query immediately
        val maskedQuery = piiMasker.mask(query)

        // KOOG Tracking
        tracer.trace(AgentTraceEvent(
            sessionId = sessionId,
            stepType = AgentStepType.THOUGHT,
            content = "Starting execution",
            metadata = mapOf("query" to maskedQuery) // Log masked query
        ))

        // Session Recovery
        val checkpoint = persistenceManager.loadCheckpoint(sessionId)
        val initialHistory = checkpoint?.messages ?: history
        
        // PII: Mask history (re-masking ensures safety even if DB has raw data)
        val maskedHistory = initialHistory.map { msg -> 
            msg.copy(content = piiMasker.mask(msg.content)) 
        }

        // Build time context for the agent
        val timeContext = buildTimeContext(clientTimezone, clientTimeMillis)

        // 1. RAG - Query-specific context
        val queryContext = try {
            val contextResults = vectorStore.search(userId, query, limit = 5)
            if (contextResults.isNotEmpty()) {
                contextResults.joinToString("\n") { "- ${it.content}" }
            } else "No relevant context for this query."
        } catch (e: Exception) {
            logger.warn("RAG query context failed (non-fatal): ${e.message}")
            "No relevant context for this query."
        }
        val maskedQueryContext = piiMasker.mask(queryContext)

        // 1.1 Fetch baseline user context
        val userProfile = try {
            val recentContext = vectorStore.getRecentContext(userId, limit = 5)
            if (recentContext.isNotEmpty()) {
                recentContext.joinToString("\n") { entry ->
                    val type = entry.metadata["type"] ?: "info"
                    "[$type] ${entry.content}"
                }
            } else "No stored preferences or facts about this user yet."
        } catch (e: Exception) {
            logger.warn("RAG user profile failed (non-fatal): ${e.message}")
            "No stored preferences or facts about this user yet."
        }
        val maskedUserProfile = piiMasker.mask(userProfile)

        // 1.5 Fetch Tool Examples
        val toolExamples = toolExampleStore.getRelevantExamples(query)

        // 2. Build Messages
        val systemMessage = LlmMessage(
            role = LlmMessage.Role.SYSTEM,
            content = """
<identity>
You are Friday, an intelligent personal AI assistant. You help users manage their digital life through notes, reminders, calendar events, timers, web searches, and device actions.

You are a real code-wiz: few people are as talented as you at understanding context, providing accurate information, and iterating until you get things right. You are efficient, accurate, empathetic, and occasionally witty—like a helpful colleague who knows their stuff.
</identity>

<tone_and_style>
- You MUST answer concisely with fewer than 4 lines for simple queries, unless the user asks for detail.
- Minimize output tokens while maintaining helpfulness, quality, and accuracy. Only address the specific query at hand.
- NEVER use filler phrases: "Certainly!", "I'd be happy to help", "Here's what I found", "Let me help you", "I can see that".
- NEVER use preamble or postamble (explaining what you will do or summarizing what you did). Just do it.
- NEVER start with "Great", "Certainly", "Okay", "Sure", "Based on the information". Be direct.
- Answer directly. One word answers are best for simple questions. No introductions, conclusions, or explanations unless asked.
- Be warm but not effusive—professional with a human touch.
- Use light humor when appropriate, but never at the user's expense.
- NEVER say "As an AI" or similar disclaimers—just be direct and helpful.
- Reply in the same language as the user.
</tone_and_style>

<critical_rules>

## 1. TOOL USAGE (HIGHEST PRIORITY)
- Execute tools IMMEDIATELY when user intent matches a tool's purpose. Do NOT describe what you will do—just do it.
- Tool triggers: notes, reminders, timers, alarms, calendar, web search, app launch, media control.
- After execution: confirm briefly ("Done", "Scheduled", "Playing now", "Created").
- If a tool fails or returns an error: STOP immediately. Do NOT retry automatically. Inform the user.
- Maximum 1 retry per tool type per query. If unsuccessful after retry, apologize and suggest alternatives.

## 2. LOOP PREVENTION (CRITICAL)
- If a tool fails: STOP, inform the user, do NOT retry the same tool.
- If an action failed previously: do not repeat it—inform the user instead.
- Never get stuck in a loop—if something isn't working, pivot or ask for guidance.
- Maximum 2 total attempts per tool type per query.
- If you notice yourself going in circles, ask the user for help.

## 3. ACCURACY OVER SPEED
- If uncertain about a fact, say so explicitly. Never fabricate information.
- For obscure topics: "I'm not confident about the specifics here—let me search rather than guess."
- Distinguish clearly between facts you know and reasonable inferences.
- When citing web search results, reference the source naturally.

## 4. BREVITY
- Respond in 1-2 sentences for simple requests.
- Match the user's energy: brief for brief requests, detailed for complex questions.
- Skip introductory explanations unless the user asks for detail.

## 5. PRIVACY
- Never create notes unless explicitly requested.
- Never store or repeat sensitive information (passwords, keys, personal identifiers).
- When in doubt about sensitivity, ask before storing.

## 6. WEB SEARCH RESULTS
- Summarize findings conversationally. Never dump raw data.
- If sources conflict, mention the disagreement.
- Cite sources when providing specific facts.

</critical_rules>

<response_examples>

**Straightforward question:**
"The capital of Japan is Tokyo. It's been the de facto capital since 1868, though interestingly, there's no law that officially designates it as such."

**When something's ambiguous:**
"I'm not entirely sure which project you're referring to - do you mean the client presentation or the internal roadmap? Both are due this week, so I want to make sure I'm helping with the right one."

**When declining something:**
"I can't help with that specific request, but I can explain why the limitation exists and suggest an alternative approach that might work for what you're trying to accomplish."

**Explaining something complex:**
"Think of API rate limits like a bouncer at a club - they're not there to ruin your night, they're there to make sure the servers don't get overwhelmed. When you hit the limit, you're just being asked to pace yourself a bit."

**When someone's frustrated:**
"I hear you - that sounds genuinely frustrating. Let's see if we can figure out what's going wrong here. Walk me through exactly what happened when you tried it?"

**Making a suggestion:**
"You might want to consider adding error handling there. It's one of those things that feels like overkill until the one time you really need it."

**When uncertain:**
"I'm not confident about the specifics here since this is outside what I reliably know. Let me search for current information rather than guess."

**When tool fails:**
"The web search isn't working right now. Would you like me to try a different approach, or should we come back to this later?"

</response_examples>

<humor_guide>
- Subtle and relatable, not forced or cheesy
- Self-deprecating is okay, never at user's expense
- Dry observations work better than jokes
- Timing matters - humor lands better after delivering the answer
- When in doubt, be helpful over funny
</humor_guide>

<approach_to_work>
- Fulfill the user's request using all tools available to you.
- When encountering difficulties, gather information before concluding root cause.
- If struggling to complete a task, take a step back and think about alternative approaches.
- Always follow security best practices. Never expose or log secrets.
</approach_to_work>

<context>
- User Profile: $maskedUserProfile
- Query Context: $maskedQueryContext
- Time: $timeContext
</context>

 <formatting>
 User input is wrapped in <user_input> tags. 

## Response Structure
- **Thinking Process:** Wrap your reasoning, analysis, or step-by-step thinking inside <think>...</think> tags. Put the thinking in the middle and your final answer after the closing tag.
            """.trimIndent()
        )

        val userMessage = if (maskedQuery.isNotBlank()) {
            LlmMessage(role = LlmMessage.Role.USER, content = "<user_input>\n$maskedQuery\n</user_input>")
        } else null

        // Apply Intelligent Sliding Window with Summarization
        val fullHistory = if (userMessage != null) maskedHistory + userMessage else maskedHistory
        val messages = if (fullHistory.size > MAX_HISTORY) {
            val splitIndex = fullHistory.size - RECENT_WINDOW
            val older = fullHistory.subList(0, splitIndex)
            val recent = fullHistory.subList(splitIndex, fullHistory.size)

            logger.info("History threshold exceeded (${fullHistory.size}). Summarizing ${older.size} older messages.")

            // Summarize MASKED older messages to protect PII
            val summary = summarizer.generateSummary(older) ?: "No summary generated."

            // Store summary in vector store as episodic history
            try {
                vectorStore.store(
                    userId = userId,
                    content = "Conversation Summary: $summary",
                    metadata = mapOf("type" to "episodic", "source" to "auto_summarization")
                )
            } catch (e: Exception) {
                logger.warn("Failed to store summary in vector store (non-fatal)", e)
            }

            val summaryMessage = LlmMessage(
                role = LlmMessage.Role.SYSTEM,
                content = "Previous conversation summary: $summary"
            )

            listOf(systemMessage, summaryMessage) + recent
        } else {
            listOf(systemMessage) + fullHistory
        }

        // 3. Agentic Loop
        val messagesForAgent = messages.toMutableList()
        
        // KOOG Optimization: LlmCache Check
        val cacheKey = LlmCacheKey(messagesForAgent, tools, modelOverride)
        LlmCache.get(cacheKey)?.let { cached ->
            val unmaskedCached = piiMasker.unmask(cached)
            emit(AgentEvent.Processing(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                content = unmaskedCached
            ))
            emit(AgentEvent.Result(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                content = "",
                isFinal = true
            ))
            tracer.trace(AgentTraceEvent(
                sessionId = sessionId,
                stepType = AgentStepType.FINAL,
                content = cached, // Log masked
                metadata = mapOf("cache" to "hit")
            ))
            return unmaskedCached
        }

        var agentIteration = 0
        val maxAgentIterations = 5
        var lastFailedToolName: String? = null
        var consecutiveToolFailures = 0

        while (agentIteration < maxAgentIterations) {
            agentIteration++
            var currentContent = ""
            var currentToolId = ""
            var currentToolName = ""
            var currentToolArgs = ""
            var isToolCallInProgress = false
            var totalUsage: LlmUsage? = null

            try {
                llmProvider.stream(messagesForAgent, tools, modelOverride).collect { chunk ->
                    chunk.usage?.let { totalUsage = it }

                    // Handle Content
                    if (!chunk.content.isNullOrEmpty()) {
                        currentContent += chunk.content
                        // PII: Unmask for UI display
                        val unmaskedChunk = piiMasker.unmask(chunk.content)
                        
                        if (agentIteration == 1 || !isToolCallInProgress) {
                            emit(AgentEvent.Processing(
                                eventId = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                content = unmaskedChunk
                            ))
                        }
                    }

                    // Handle Tool Call Accumulation
                    val toolCall = chunk.toolCall
                    if (toolCall != null) {
                        if (!isToolCallInProgress) {
                            isToolCallInProgress = true
                            emit(AgentEvent.ToolCall(
                                eventId = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                toolName = toolCall.functionName,
                                displayName = "Preparing ${toolCall.functionName}...",
                                status = "started"
                            ))
                        }
                        if (toolCall.id.isNotEmpty()) currentToolId = toolCall.id
                        if (toolCall.functionName.isNotEmpty()) currentToolName = toolCall.functionName
                        currentToolArgs += toolCall.arguments
                    }
                }

                val duration = System.currentTimeMillis() - startTime
                logger.info("Agent iteration $agentIteration summary",
                    kv("duration_ms", duration),
                    kv("input_tokens", totalUsage?.promptTokens ?: 0),
                    kv("output_tokens", totalUsage?.completionTokens ?: 0),
                    kv("total_tokens", totalUsage?.totalTokens ?: 0),
                    kv("model", llmProvider.providerName)
                )

                // 4. Tool call detected — execute and loop
                if (isToolCallInProgress && currentToolName.isNotEmpty()) {
                    // Check for consecutive same-tool failures (loop prevention)
                    // Allow 1 retry maximum (2 total attempts) before stopping
                    // After first failure: consecutiveToolFailures = 1, allow retry
                    // After second failure: consecutiveToolFailures = 2, stop
                    if (currentToolName == lastFailedToolName && consecutiveToolFailures >= 2) {
                        logger.warn("Tool $currentToolName failed after retry - stopping loop (failures: $consecutiveToolFailures)")
                        emit(AgentEvent.Error(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            message = "The $currentToolName action failed after retry. I'll stop and give you what I have so far.",
                            code = "TOOL_LOOP_DETECTED"
                        ))
                        return piiMasker.unmask(currentContent.ifEmpty { "Action failed. Please try a different approach." })
                    }
                    
                    toolCallCount++
                    if (toolCallCount > MAX_TOOL_CALLS) {
                        logger.warn("Tool call limit exceeded ($MAX_TOOL_CALLS) for user: $userId")
                        emit(AgentEvent.Error(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            message = "I've made too many actions in this session. Let me summarize what I've done.",
                            code = "TOOL_LIMIT_EXCEEDED"
                        ))
                        return piiMasker.unmask(currentContent.ifEmpty { "Execution limit reached." })
                    }

                    val toolStartTime = System.currentTimeMillis()
                    try {
                        tracer.trace(AgentTraceEvent(
                            sessionId = sessionId,
                            stepType = AgentStepType.TOOL_CALL,
                            content = "Calling tool: $currentToolName",
                            metadata = mapOf("args" to currentToolArgs)
                        ))
                        
                        // PII: Unmask arguments before execution to use real data
                        val unmaskedArgs = piiMasker.unmask(currentToolArgs)
                        val toolResult = executeTool(currentToolName, unmaskedArgs, messagesForAgent, clientTimezone, clientTimeMillis)
                        
                        // Check if tool returned an error result
                        val isToolError = toolResult.startsWith("Error", ignoreCase = true) || 
                            toolResult.startsWith("Search failed", ignoreCase = true) ||
                            toolResult.startsWith("All configured keys failed", ignoreCase = true) ||
                            toolResult.contains("failed:", ignoreCase = true) ||
                            (toolResult.contains("failed", ignoreCase = true) && toolResult.contains("error", ignoreCase = true))
                        
                        if (isToolError) {
                            // Track consecutive failures for error results
                            if (currentToolName == lastFailedToolName) {
                                consecutiveToolFailures++
                            } else {
                                lastFailedToolName = currentToolName
                                consecutiveToolFailures = 1
                            }
                            logger.warn("Tool returned error result: $currentToolName - failure count: $consecutiveToolFailures")
                        } else {
                            // Reset on success - clear failure tracking for this tool
                            if (lastFailedToolName == currentToolName) {
                                lastFailedToolName = null
                                consecutiveToolFailures = 0
                            }
                        }
                        
                        // PII: Mask result before feeding back to LLM
                        val maskedToolResult = piiMasker.mask(toolResult)

                        val toolDuration = System.currentTimeMillis() - toolStartTime
                        
                        tracer.trace(AgentTraceEvent(
                            sessionId = sessionId,
                            stepType = AgentStepType.TOOL_RESULT,
                            content = "Result: $maskedToolResult",
                            metadata = mapOf("tool" to currentToolName, "duration_ms" to toolDuration.toString())
                        ))
                        logger.info("Tool execution summary",
                            kv("tool_name", currentToolName),
                            kv("duration_ms", toolDuration),
                            kv("status", if (isToolError) "error_result" else "success")
                        )
                        Metrics.counter("agent.tool." + if (isToolError) "error" else "success", "tool", currentToolName).increment()

                        emit(AgentEvent.ToolCall(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            toolName = currentToolName,
                            displayName = "Executed $currentToolName",
                            status = if (isToolError) "error" else "completed"
                        ))

                        messagesForAgent += LlmMessage(
                            role = LlmMessage.Role.TOOL,
                            content = "[Tool Result for $currentToolName]: $maskedToolResult"
                        )
                        
                        persistenceManager.saveCheckpoint(sessionId, messagesForAgent, currentToolName)
                        continue
                    } catch (e: Exception) {
                        val toolDuration = System.currentTimeMillis() - toolStartTime
                        
                        // Track consecutive failures for exceptions
                        if (currentToolName == lastFailedToolName) {
                            consecutiveToolFailures++
                        } else {
                            lastFailedToolName = currentToolName
                            consecutiveToolFailures = 1
                        }
                        
                        logger.error("Tool execution failed",
                            kv("tool_name", currentToolName),
                            kv("duration_ms", toolDuration),
                            kv("error", e.message),
                            kv("consecutive_failures", consecutiveToolFailures)
                        )
                        tracer.trace(AgentTraceEvent(
                            sessionId = sessionId,
                            stepType = AgentStepType.ERROR,
                            content = "Tool failed: ${e.message}",
                            metadata = mapOf("tool" to currentToolName, "consecutive_failures" to consecutiveToolFailures.toString())
                        ))
                        Metrics.counter("agent.tool.error", "tool", currentToolName).increment()
                        
                        messagesForAgent += LlmMessage(
                            role = LlmMessage.Role.TOOL,
                            content = "[Tool Error for $currentToolName]: ${e.message}"
                        )
                        persistenceManager.saveCheckpoint(sessionId, messagesForAgent, "error_$currentToolName")
                        continue
                    }
                } else if (currentContent.isNotEmpty()) {
                    // Final answer reached
                    LlmCache.put(cacheKey, currentContent)
                    emit(AgentEvent.Result(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        content = "",
                        isFinal = true
                    ))
                    tracer.trace(AgentTraceEvent(
                        sessionId = sessionId,
                        stepType = AgentStepType.FINAL,
                        content = currentContent // Log masked
                    ))
                    persistenceManager.clearCheckpoint(sessionId)
                    
                    // PII: Unmask final return
                    return piiMasker.unmask(currentContent)
                } else {
                    logger.warn("LLM stream completed with no content for user: $userId")
                    tracer.trace(AgentTraceEvent(
                        sessionId = sessionId,
                        stepType = AgentStepType.ERROR,
                        content = "Empty response from LLM"
                    ))
                    emit(AgentEvent.Error(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        message = "I didn't receive a response from the AI service. Please try again.",
                        code = "EMPTY_RESPONSE"
                    ))
                    return ""
                }

            } catch (e: Exception) {
                logger.error("LLM stream error", e)
                val errorMsg = e.message ?: "Unknown error"
                val userMsg = when {
                    errorMsg.contains("Max retries exceeded", ignoreCase = true) ||
                    errorMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ->
                        "All AI accounts are currently at capacity. Try a different model or wait a moment."
                    errorMsg.contains("Socket timeout", ignoreCase = true) ->
                        "The AI service took too long to respond. Please try again."
                    errorMsg.contains("Connection refused", ignoreCase = true) ->
                        "Cannot reach the AI service. Check if the proxy is running."
                    else -> "Brain freeze: ${errorMsg.take(150)}"
                }
                emit(AgentEvent.Error(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    message = userMsg,
                    code = "LLM_ERROR"
                ))
                return ""
            }
        }

        // Max iterations reached
        logger.warn("Agent loop reached max iterations ($maxAgentIterations) for user: $userId")
        return "I completed several actions but reached my iteration limit."
    }

    /**
     * Execute a tool server-side and return the result string.
     * Server-side tools (notes, timers, events, search, context) execute directly on PostgreSQL.
     * Device-only tools (media, settings, launch, navigate, share) emit Command events as fire-and-forget.
     */
    private suspend fun executeTool(name: String, argsJson: String, history: List<LlmMessage>, clientTimezone: String? = null, clientTimeMillis: Long? = null): String {
        logger.info("Executing tool: $name with args: $argsJson")

        return when (name) {
            // 
            // SERVER-SIDE TOOLS — execute on PostgreSQL, emit StateSync
            // 

            "create_note" -> {
                val args = json.decodeFromString<CreateNoteArgs>(argsJson)
                if (noteRepository != null) {
                    val noteId = noteRepository.create(userId, args.title, args.content, args.category)
                    val now = System.currentTimeMillis()
                    val info = NoteInfo(
                        id = noteId,
                        title = args.title,
                        content = args.content,
                        category = args.category,
                        isArchived = false,
                        createdAt = now,
                        updatedAt = now
                    )
                    emitStateSync("note_created", json.encodeToString(info))
                    "Note created successfully. ID: $noteId, Title: '${args.title}'"
                } else {
                    // Fallback: send Command to device (legacy mode)
                    emitDeviceCommand(AgentCommand.AddNote(commandId = UUID.randomUUID().toString(), content = "${args.title}\n\n${args.content}", category = args.category))
                    "Note creation sent to device: ${args.title}"
                }
            }

            "search_notes" -> {
                val args = json.decodeFromString<SearchNotesArgs>(argsJson)
                if (noteRepository != null) {
                    val results = noteRepository.search(userId, args.query)
                    if (results.isEmpty()) {
                        "No notes found matching '${args.query}'."
                    } else {
                        val formatted = results.joinToString("\n") { "- [${it.id}] ${it.title}: ${it.content.take(100)}" }
                        "Found ${results.size} note(s):\n$formatted"
                    }
                } else {
                    emitDeviceCommand(AgentCommand.SearchNotes(commandId = UUID.randomUUID().toString(), query = args.query, category = args.filter))
                    "Search request sent to device for: ${args.query}"
                }
            }

            "update_note" -> {
                val args = json.decodeFromString<UpdateNoteArgs>(argsJson)
                if (noteRepository != null) {
                    val success = noteRepository.update(userId, args.noteId, args.title, args.content, null)
                    if (success) {
                        emitStateSync("note_updated", """{"id":"${args.noteId}","title":"${args.title ?: ""}","content":"${args.content?.replace("\"", "\\\"") ?: ""}"}""")
                        "Note ${args.noteId} updated successfully."
                    } else "Note ${args.noteId} not found."
                } else {
                    emitDeviceCommand(AgentCommand.UpdateNote(commandId = UUID.randomUUID().toString(), noteId = args.noteId, title = args.title, content = args.content))
                    "Note update sent to device."
                }
            }

            "delete_note" -> {
                val args = json.decodeFromString<DeleteNoteArgs>(argsJson)
                if (noteRepository != null) {
                    val success = noteRepository.delete(userId, args.noteId)
                    if (success) {
                        emitStateSync("note_deleted", """{"id":"${args.noteId}"}""")
                        "Note ${args.noteId} deleted."
                    } else "Note ${args.noteId} not found."
                } else {
                    emitDeviceCommand(AgentCommand.DeleteNote(commandId = UUID.randomUUID().toString(), noteId = args.noteId))
                    "Note deletion sent to device."
                }
            }

            "archive_note" -> {
                val args = json.decodeFromString<ArchiveNoteArgs>(argsJson)
                if (noteRepository != null) {
                    val success = noteRepository.archive(userId, args.noteId)
                    if (success) {
                        emitStateSync("note_archived", """{"id":"${args.noteId}"}""")
                        "Note ${args.noteId} archived."
                    } else "Note ${args.noteId} not found."
                } else {
                    emitDeviceCommand(AgentCommand.ArchiveNote(commandId = UUID.randomUUID().toString(), noteId = args.noteId))
                    "Note archive sent to device."
                }
            }

            "schedule_event" -> {
                val args = json.decodeFromString<ScheduleEventArgs>(argsJson)
                val reminder = args.reminderMinutes ?: 15
                if (calendarRepository != null) {
                    val eventId = calendarRepository.create(userId, args.title, args.startTime, args.endTime, args.description, reminder)
                    val info = CalendarEventInfo(
                        id = eventId,
                        title = args.title,
                        startTime = args.startTime,
                        endTime = args.endTime,
                        description = args.description,
                        reminderMinutes = reminder,
                        createdAt = System.currentTimeMillis()
                    )
                    emitStateSync("event_scheduled", json.encodeToString(info))
                    "Event scheduled: '${args.title}', ID: $eventId"
                } else {
                    emitDeviceCommand(AgentCommand.ScheduleEvent(commandId = UUID.randomUUID().toString(), title = args.title, startTime = args.startTime, endTime = args.endTime, description = args.description, reminderMinutes = reminder))
                    "Event scheduling sent to device: ${args.title}"
                }
            }

            "list_events" -> {
                val args = json.decodeFromString<ListEventsArgs>(argsJson)
                if (calendarRepository != null) {
                    val events = calendarRepository.listUpcoming(userId)
                    if (events.isEmpty()) {
                        "No upcoming events found."
                    } else {
                        val formatted = events.joinToString("\n") { "- [${it.id}] ${it.title} (${java.time.Instant.ofEpochMilli(it.startTime)})" }
                        "Found ${events.size} event(s):\n$formatted"
                    }
                } else {
                    emitDeviceCommand(AgentCommand.ListEvents(commandId = UUID.randomUUID().toString(), date = args.date))
                    "Event listing sent to device."
                }
            }

            "delete_event" -> {
                val args = json.decodeFromString<DeleteEventArgs>(argsJson)
                if (calendarRepository != null) {
                    val success = calendarRepository.delete(userId, args.eventId)
                    if (success) {
                        emitStateSync("event_deleted", """{"id":"${args.eventId}"}""")
                        "Event ${args.eventId} deleted."
                    } else "Event ${args.eventId} not found."
                } else {
                    emitDeviceCommand(AgentCommand.DeleteEvent(commandId = UUID.randomUUID().toString(), eventId = args.eventId))
                    "Event deletion sent to device."
                }
            }

            "set_timer" -> {
                val args = json.decodeFromString<SetTimerArgs>(argsJson)
                val durationMs = parseDurationToMs(args.duration)
                if (timerRepository != null) {
                    val timerId = timerRepository.create(userId, args.name, durationMs = durationMs, isAlarm = false)
                    val triggerAt = System.currentTimeMillis() + durationMs
                    val info = TimerInfo(
                        id = timerId,
                        name = args.name,
                        durationMs = durationMs,
                        triggerAt = triggerAt,
                        isAlarm = false,
                        isActive = true,
                        createdAt = System.currentTimeMillis()
                    )
                    emitStateSync("timer_set", json.encodeToString(info))
                    "Timer set: '${args.name}' for ${args.duration} (ID: $timerId)"
                } else {
                    emitDeviceCommand(AgentCommand.SetTimer(commandId = UUID.randomUUID().toString(), name = args.name, timeStr = args.duration, isAlarm = false))
                    "Timer sent to device: ${args.name}"
                }
            }

            "set_alarm" -> {
                val args = json.decodeFromString<SetAlarmArgs>(argsJson)
                if (timerRepository != null) {
                    val triggerAt = parseAlarmTimeToMs(args.time, clientTimezone, clientTimeMillis)
                    val timerId = timerRepository.create(userId, args.name, triggerAt = triggerAt, isAlarm = true)
                    val info = TimerInfo(
                        id = timerId,
                        name = args.name,
                        durationMs = 0L,
                        triggerAt = triggerAt,
                        isAlarm = true,
                        isActive = true,
                        createdAt = System.currentTimeMillis()
                    )
                    emitStateSync("timer_set", json.encodeToString(info))
                    "Alarm set: '${args.name}' at ${args.time} (ID: $timerId)"
                } else {
                    emitDeviceCommand(AgentCommand.SetTimer(commandId = UUID.randomUUID().toString(), name = args.name, timeStr = args.time, isAlarm = true))
                    "Alarm sent to device: ${args.name}"
                }
            }

            "store_context" -> {
                val args = json.decodeFromString<StoreContextArgs>(argsJson)
                try {
                    vectorStore.store(userId, args.content, mapOf("type" to args.type))
                    "Context stored: '${args.content.take(50)}...' as ${args.type}"
                } catch (e: Exception) {
                    logger.warn("store_context failed: ${e.message}")
                    "Failed to store context: ${e.message}"
                }
            }

            "update_context" -> {
                val args = json.decodeFromString<UpdateContextArgs>(argsJson)
                try {
                    vectorStore.update(userId, args.id, args.content)
                    emitStateSync("context_updated", """{"id":"${args.id}","content":"${args.content.replace("\"","\\\"")}","type":"${args.type}"}""")
                    "Context ${args.id} updated."
                } catch (e: Exception) {
                    "Failed to update context: ${e.message}"
                }
            }

            "delete_context" -> {
                val args = json.decodeFromString<DeleteContextArgs>(argsJson)
                try {
                    vectorStore.delete(userId, args.id)
                    emitStateSync("context_deleted", """{"id":"${args.id}"}""")
                    "Context ${args.id} deleted."
                } catch (e: Exception) {
                    "Failed to delete context: ${e.message}"
                }
            }

            "query_knowledge" -> {
                val args = json.decodeFromString<QueryKnowledgeArgs>(argsJson)
                try {
                    val results = vectorStore.search(userId, args.query, limit = 5)
                    if (results.isEmpty()) "No private knowledge found for '${args.query}'."
                    else "Found ${results.size} relevant items:\n" + results.joinToString("\n") { "- ${it.content}" }
                } catch (e: Exception) {
                    "Knowledge query failed: ${e.message}"
                }
            }

            "summarize_session" -> {
                val summary = summarizer.generateSummary(history)
                summary ?: "Could not summarize session at this time."
            }

            "web_search" -> {
                val args = json.decodeFromString<WebSearchArgs>(argsJson)
                val result = tavilyTool.search(args.query)
                if (result.startsWith("Error")) "Search failed: $result"
                else "Web search results for '${args.query}':\n$result"
            }

            "generate_image" -> {
                json.decodeFromString<GenerateImageArgs>(argsJson)
                "Image generation is not available yet. It's on the roadmap."
            }

            // 
            // DEVICE-ONLY TOOLS — fire-and-forget Command events
            // 

            "launch_app" -> {
                val args = json.decodeFromString<LaunchAppArgs>(argsJson)
                emitDeviceCommand(AgentCommand.LaunchApp(commandId = UUID.randomUUID().toString(), packageName = args.packageName))
                "Launching app: ${args.packageName}"
            }

            "take_screenshot" -> {
                emitDeviceCommand(AgentCommand.TakeScreenshot(commandId = UUID.randomUUID().toString()))
                "Taking screenshot."
            }

            "toggle_setting" -> {
                val args = json.decodeFromString<ToggleSettingArgs>(argsJson)
                emitDeviceCommand(AgentCommand.ToggleSetting(commandId = UUID.randomUUID().toString(), setting = args.setting, enable = args.enable))
                "${args.setting} ${if (args.enable) "enabled" else "disabled"}."
            }

            "control_media" -> {
                val args = json.decodeFromString<ControlMediaArgs>(argsJson)
                emitDeviceCommand(AgentCommand.ControlAudio(commandId = UUID.randomUUID().toString(), action = args.action))
                "Media ${args.action} sent to device."
            }

            "seek_media" -> {
                val args = json.decodeFromString<SeekMediaArgs>(argsJson)
                emitDeviceCommand(AgentCommand.SeekAudio(commandId = UUID.randomUUID().toString(), positionMs = args.positionMs))
                "Seeking to ${args.positionMs}ms."
            }

            "navigate" -> {
                val args = json.decodeFromString<NavigateArgs>(argsJson)
                emitDeviceCommand(AgentCommand.Navigate(commandId = UUID.randomUUID().toString(), screen = args.screen))
                "Navigating to ${args.screen}."
            }

            "share" -> {
                val args = json.decodeFromString<ShareArgs>(argsJson)
                emitDeviceCommand(AgentCommand.Share(commandId = UUID.randomUUID().toString(), content = args.content, title = args.title))
                "Sharing content."
            }

            else -> "Unknown tool: $name"
        }
    }

    /** Emit a StateSync event so the Android client can cache data locally. */
    private suspend fun emitStateSync(syncType: String, data: String) {
        emit(AgentEvent.StateSync(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            syncType = syncType,
            data = data
        ))
    }

    /** Emit a fire-and-forget Command event for device-only tools. */
    private suspend fun emitDeviceCommand(command: AgentCommand) {
        emit(AgentEvent.Command(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            command = command
        ))
    }

    /** Parse human-readable duration string to milliseconds. */
    private fun parseDurationToMs(duration: String): Long {
        val lower = duration.lowercase().trim()
        var totalMs = 0L
        val hourMatch = Regex("""(\d+)\s*h(?:our)?s?""").find(lower)
        val minMatch = Regex("""(\d+)\s*m(?:in(?:ute)?)?s?""").find(lower)
        val secMatch = Regex("""(\d+)\s*s(?:ec(?:ond)?)?s?""").find(lower)
        hourMatch?.let { totalMs += it.groupValues[1].toLong() * 3600000 }
        minMatch?.let { totalMs += it.groupValues[1].toLong() * 60000 }
        secMatch?.let { totalMs += it.groupValues[1].toLong() * 1000 }
        // If just a number, treat as minutes
        if (totalMs == 0L) {
            val plainNum = Regex("""(\d+)""").find(lower)
            plainNum?.let { totalMs = it.groupValues[1].toLong() * 60000 }
        }
        return if (totalMs > 0) totalMs else 60000 // Default 1 minute
    }

    /** Parse human-readable alarm time string to absolute epoch milliseconds. */
    private fun parseAlarmTimeToMs(timeStr: String, clientTimezone: String? = null, clientTimeMillis: Long? = null): Long {
        val now = clientTimeMillis ?: System.currentTimeMillis()
        val tz = try { java.time.ZoneId.of(clientTimezone ?: "UTC") } catch (e: Exception) { java.time.ZoneId.of("UTC") }
        val zonedNow = java.time.Instant.ofEpochMilli(now).atZone(tz)

        val lower = timeStr.lowercase().trim()
        val isTomorrow = lower.contains("tomorrow")
        val cleanStr = lower.replace("tomorrow", "").trim()

        val timePatterns = listOf(
            Regex("""(\d{1,2}):(\d{2})\s*(am|pm)?"""),
            Regex("""(\d{1,2})\s*(am|pm)""")
        )

        var hour = 0
        var minute = 0
        var foundMatch = false

        for (pattern in timePatterns) {
            val match = pattern.find(cleanStr)
            if (match != null) {
                hour = match.groupValues[1].toInt()
                minute = if (match.groupValues[2].matches(Regex("""\d{2}"""))) match.groupValues[2].toInt() else 0
                val ampm = match.groupValues.last().lowercase()
                if (ampm == "pm" && hour < 12) hour += 12
                else if (ampm == "am" && hour == 12) hour = 0
                foundMatch = true
                break
            }
        }

        if (!foundMatch) {
            val plainHour = Regex("""(\d{1,2})""").find(cleanStr)
            if (plainHour != null) {
                hour = plainHour.groupValues[1].toInt()
                minute = 0
                foundMatch = true
            }
        }

        if (!foundMatch) return now + 3600000

        var resultTime = zonedNow.withHour(hour).withMinute(minute).withSecond(0).withNano(0)

        if (isTomorrow) {
            resultTime = resultTime.plusDays(1)
        } else if (!resultTime.isAfter(zonedNow)) {
            resultTime = resultTime.plusDays(1)
        }

        return resultTime.toInstant().toEpochMilli()
    }

    private suspend fun emit(event: AgentEvent) {
        eventEmitter(event)
    }

    @Serializable data class CreateNoteArgs(val title: String, val content: String, val category: String? = null)
    @Serializable data class SearchNotesArgs(val query: String, val filter: String? = null)
    @Serializable data class ScheduleEventArgs(val title: String, val startTime: Long, val endTime: Long, val description: String? = null, val reminderMinutes: Int? = null)
    @Serializable data class ListEventsArgs(val date: Long)
    @Serializable data class DeleteEventArgs(val eventId: String)
    @Serializable data class SetTimerArgs(val name: String, val duration: String)
    @Serializable data class SetAlarmArgs(val name: String, val time: String)
    @Serializable data class LaunchAppArgs(val packageName: String)
    @Serializable data class ToggleSettingArgs(val setting: String, val enable: Boolean)
    @Serializable data class ControlMediaArgs(val action: String)
    @Serializable data class SeekMediaArgs(val positionMs: Long)
    @Serializable data class StoreContextArgs(val content: String, val type: String)
    @Serializable data class UpdateContextArgs(val id: String, val content: String, val type: String)
    @Serializable data class DeleteContextArgs(val id: String)
    @Serializable data class UpdateNoteArgs(val noteId: String, val title: String? = null, val content: String? = null)
    @Serializable data class DeleteNoteArgs(val noteId: String)
    @Serializable data class ArchiveNoteArgs(val noteId: String)
    @Serializable data class NavigateArgs(val screen: String)
    @Serializable data class ShareArgs(val content: String, val title: String? = null)
    @Serializable data class WebSearchArgs(val query: String)
    @Serializable data class QueryKnowledgeArgs(val query: String)
    @Serializable data class GenerateImageArgs(val prompt: String)

    /**
     * Build time context string for the system prompt.
     * This helps the agent correctly parse time-based requests.
     */
    private fun buildTimeContext(clientTimezone: String?, clientTimeMillis: Long?): String {
        val now = clientTimeMillis ?: System.currentTimeMillis()
        val tz = try {
            java.time.ZoneId.of(clientTimezone ?: "UTC")
        } catch (e: Exception) {
            java.time.ZoneId.of("UTC")
        }

        val zonedNow = java.time.Instant.ofEpochMilli(now).atZone(tz)
        val dateFormatter = java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")
        val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a")

        return """
            - User's timezone: ${tz.id}
            - User's current time: ${zonedNow.format(timeFormatter)}
            - User's current date: ${zonedNow.format(dateFormatter)}
            - Current epoch millis: $now
            - When scheduling events or setting alarms/timers, convert times to UTC milliseconds based on this context.
            - "Tomorrow" means ${zonedNow.plusDays(1).format(dateFormatter)}
        """.trimIndent()
    }
}
