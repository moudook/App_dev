package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentEvent
import com.example.smarty.protocol.AskUserQuestion
import com.example.smarty.server.data.CalendarRepository
import com.example.smarty.server.data.ConversationSummarizer
import com.example.smarty.server.data.DatabaseFactory
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.data.TimerRepository
import com.example.smarty.server.data.ToolSessionPayload
import com.example.smarty.server.data.ToolSessionRepository
import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.LlmProvider
import io.micrometer.core.instrument.Metrics
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Server-side AI Agent with agentic tool loop.
 * Orchestrates the "Remote Brain" logic using a pluggable LLM provider.
 * Tools execute server-side; results feed back to the LLM for intelligent replies.
 * All operations are scoped by userId for multi-tenant isolation.
 *
 * REFACTOR: This file has been broken into smaller, maintainable components:
 * - ToolExecutor.kt: All tool execution logic
 * - AgentStateManager.kt: Session management, context building, cache
 * - AgentStreamProcessor.kt: Streaming, event processing, response extraction
 * - GoalMemoryManager.kt: Goal tracking and progress management
 * - AgentTracing.kt: Tracing, monitoring, and persistence
 */
class ServerAgent(
    private val llmProvider: LlmProvider,
    private val vectorStore: PostgresVectorStore,
    private val summarizer: ConversationSummarizer,
    private val noteRepository: NoteRepository?,
    private val timerRepository: TimerRepository?,
    private val calendarRepository: CalendarRepository?,
    private val eventEmitter: suspend (AgentEvent) -> Unit,
    private val userId: String = "dev-user",
    private val noteService: com.example.smarty.server.services.NoteService? = null,
    private val capabilities: com.example.smarty.protocol.DeviceCapabilities? = null,
    private val fcmService: com.example.smarty.server.services.FcmNotificationService? = null,
) {
    private val logger = LoggerFactory.getLogger(ServerAgent::class.java)

    // Extracted components
    private val stateManager = AgentStateManager(userId, llmProvider, vectorStore, summarizer)

    private val toolExecutor =
        ToolExecutor(
            userId = userId,
            llmProvider = llmProvider,
            vectorStore = vectorStore,
            noteRepository = noteRepository,
            timerRepository = timerRepository,
            calendarRepository = calendarRepository,
            eventEmitter = eventEmitter,
            noteService = noteService,
            capabilities = capabilities,
            fcmService = fcmService,
        )
    private val tracer: AgentTracer =
        CompositeTracer(
            listOf(
                PostgresTracer(userId),
                MonitoringTracer(userId),
                LoggerTracer(userId),
            ),
        )
    private val persistenceManager = AgentPersistenceManager(userId)
    private val toolExampleStore = ToolExampleStore()

    // Security limits to prevent runaway execution
    companion object {
        const val MAX_EXECUTION_TIME_MS = 120 * 60 * 1000L // 2 hours hard limit (ask_user sessions can be long)
        const val MAX_TOOL_CALLS = 100 // Allow extensive research with up to 100 tool calls
        const val MAX_ITERATIONS = 200 // Max LLM iterations for extensive research
    }

    // Pass our app's tool definitions to the LLM — these are core product features.
    // OpenCode CLI handles websearch natively; our other tools (memory, schedule, remind,
    // device, navigate, ask_user, etc.) are passed as definitions so the LLM knows how to use them.
    private val tools = AgentToolDefinitions.getAllTools()

    suspend fun run(
        query: String,
        sessionId: String = UUID.randomUUID().toString(),
        history: List<LlmMessage> = emptyList(),
        modelOverride: String? = null,
        clientTimezone: String? = null,
        clientTimeMillis: Long? = null,
        personality: String? = null,
        opencodeSessionId: String? = null,
        onOpencodeSessionCreated: suspend (String) -> Unit = {},
        variantOverride: String? = null,
        section: String? = null,
        resumeToolResult: LlmMessage? = null,
    ): String {
        if (query.length > 10000) {
            throw IllegalArgumentException("Query too long")
        }

        // No global withTimeout wrapping the entire run — the ask_user tool counts as a valid AI response
        // and its waiting time shouldn't count toward any timeout. Safety is provided by:
        //   MAX_ITERATIONS (200) + MAX_TOOL_CALLS (100) + AgentRunManager's 120min outer timeout
        return runInternal(
            query,
            sessionId,
            history,
            modelOverride,
            clientTimezone,
            clientTimeMillis,
            personality,
            opencodeSessionId,
            onOpencodeSessionCreated,
            variantOverride,
            section,
            resumeToolResult,
        )
    }

    private suspend fun runInternal(
        query: String,
        sessionId: String,
        history: List<LlmMessage>,
        modelOverride: String?,
        clientTimezone: String?,
        clientTimeMillis: Long?,
        personality: String? = null,
        opencodeSessionId: String? = null,
        onOpencodeSessionCreated: suspend (String) -> Unit = {},
        variantOverride: String? = null,
        section: String? = null,
        resumeToolResult: LlmMessage? = null,
    ): String {
        var toolCallCount = 0

        val startTime = System.currentTimeMillis()
        logger.info("Agent execution starting for query: $query (Session: $sessionId, User: $userId)")

        // Initialize Goal Memory Manager
        val goalMemoryManager = GoalMemoryManager(sessionId, query)
        goalMemoryManager.initializeWithGoal()

        // KOOG Tracking
        tracer.trace(
            AgentTraceEvent(
                sessionId = sessionId,
                stepType = AgentStepType.THOUGHT,
                content = "Starting execution",
                metadata = mapOf("query" to query),
            ),
        )

        // Session Recovery
        val checkpoint = persistenceManager.loadCheckpoint(sessionId)
        val checkpointedHistory = checkpoint?.messages ?: history
        val initialHistory =
            if (resumeToolResult != null) {
                checkpointedHistory + resumeToolResult
            } else {
                checkpointedHistory
            }

        // 1.5 Fetch Tool Examples
        val toolExamples = toolExampleStore.getRelevantExamples(query)

        // Production guard: force ask_user for explicit tool demonstration requests.
        // This mirrors LangGraph's interrupt() pattern where the framework can pause at deterministic points
        // rather than relying solely on the LLM to decide when to ask.
        val askUserDemonstrationPattern =
            Regex(
                "\\b(can you|show me|use|try|demo|demonstrate|test)\\b.*\\b(ask[_ ]user|ask user)\\b",
                RegexOption.IGNORE_CASE,
            )
        if (askUserDemonstrationPattern.containsMatchIn(query)) {
            val toolCallId = "tool-${UUID.randomUUID()}"
            val demoQuestions =
                listOf(
                    AskUserQuestion(
                        question = "What would you like the ask_user demo to ask?",
                        options = listOf("Clarification question", "Preference selection", "Approval workflow", "Other"),
                    ),
                )
            DatabaseFactory.getDataSource()?.let { dataSource ->
                val ttlMinutes = 30
                val expiresAt = Instant.now().plus(ttlMinutes.toLong(), ChronoUnit.MINUTES)
                val payload =
                    ToolSessionPayload(
                        chatSessionId = sessionId,
                        toolCallId = toolCallId,
                        userId = userId,
                        questionSummaries = demoQuestions.map { q -> q.question.take(120) },
                        expiresAt = expiresAt.toString(),
                    )
                ToolSessionRepository(dataSource).createPendingSession(payload)
            }
            logger.info("ASK_USER DEMO GUARD: Forcing ask_user tool for query: $query toolCallId=$toolCallId")
            eventEmitter(
                AgentEvent.AskUserRequest(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    toolId = toolCallId,
                    sessionId = sessionId,
                    questions = demoQuestions,
                    toolCallId = toolCallId,
                ),
            )
            return "I've opened the ask_user interface. Please answer the question to continue."
        }

        // 2. Build Messages
        val systemMessage =
            stateManager.buildSystemMessage(
                query,
                clientTimezone,
                clientTimeMillis,
                personality,
                goalMemoryManager,
                section,
            )

        val userMessage =
            if (query.isNotBlank()) {
                LlmMessage(role = LlmMessage.Role.USER, content = "<user_input>\n$query\n</user_input>")
            } else {
                null
            }

        val messages = stateManager.buildMessageList(systemMessage, initialHistory, userMessage)

        // 3. Agentic Loop
        val messagesForAgent = messages.toMutableList()

        // Check cache
        stateManager.checkCache(messagesForAgent, tools, query, modelOverride, variantOverride)?.let { cached ->
            tracer.trace(
                AgentTraceEvent(
                    sessionId = sessionId,
                    stepType = AgentStepType.FINAL,
                    content = cached,
                    metadata = mapOf("cache" to "hit"),
                ),
            )

            return cached
        }

        val streamProcessor = AgentStreamProcessor(sessionId)
        val toolCallHistory = mutableListOf<Pair<String, String>>()
        var lastFailedToolName: String? = null
        var consecutiveToolFailures = 0
        var agentIteration = 0
        var busyRetries = 0

        while (agentIteration < MAX_ITERATIONS) {
            agentIteration++

            streamProcessor.reset()

            try {
                llmProvider
                    .stream(
                        messagesForAgent,
                        tools,
                        modelOverride,
                        opencodeSessionId,
                        onOpencodeSessionCreated,
                        variantOverride,
                    ).collect { chunk ->
                        streamProcessor.processChunk(chunk)
                        if (chunk.content != null) {
                            eventEmitter(
                                AgentEvent.TextDelta(
                                    eventId = UUID.randomUUID().toString(),
                                    timestamp = System.currentTimeMillis(),
                                    text = chunk.content,
                                ),
                            )
                            // Mark that the LLM provider (not the plugin bridge) emitted text first.
                            // This prevents the plugin bridge from emitting duplicate text deltas
                            // and also prevents the AgentRunManager fallback from re-emitting the
                            // full response after agent.run() returns.
                            AgentRunManager.markBridgeSentText(sessionId)
                        }
                        if (chunk.reasoning != null) {
                            eventEmitter(
                                AgentEvent.ReasoningDelta(
                                    eventId = UUID.randomUUID().toString(),
                                    timestamp = System.currentTimeMillis(),
                                    text = chunk.reasoning,
                                ),
                            )
                        }
                    }

                // Retry on busy — the daemon may be processing another request
                if (streamProcessor.finishReason == "busy" && busyRetries < 3) {
                    busyRetries++
                    logger.warn("Daemon busy, retrying (attempt $busyRetries/3) for session $sessionId")
                    kotlinx.coroutines.delay(busyRetries * 5_000L)
                    continue
                }

                val duration = System.currentTimeMillis() - startTime
                logger.info(
                    "Agent iteration $agentIteration summary",
                    kv("duration_ms", duration),
                    kv("input_tokens", streamProcessor.totalUsage?.promptTokens ?: 0),
                    kv("output_tokens", streamProcessor.totalUsage?.completionTokens ?: 0),
                    kv("total_tokens", streamProcessor.totalUsage?.totalTokens ?: 0),
                    kv("model", llmProvider.providerName),
                )

                // Determine tool call source: native tool_use event from OpenCode
                val hasNativeToolCall = streamProcessor.isToolCallInProgress && streamProcessor.currentToolName.isNotEmpty()

                // Note: daemonQuestion interception removed. McpServer.kt handles ask_user for OpenCode daemon.

                // 4. Tool call detected — execute and loop
                if (hasNativeToolCall && llmProvider.providerName != "OpenRouter") {
                    val toolName = streamProcessor.currentToolName
                    val toolArgs = streamProcessor.currentToolArgs

                    val argsHash = toolArgs.take(100).hashCode().toString()
                    val sameCallCount = toolCallHistory.count { it.first == toolName && it.second == argsHash }

                    val shouldBlock = sameCallCount >= 3

                    if (shouldBlock) {
                        logger.warn(
                            "TOOL BLOCKED: Tool $toolName called ${sameCallCount + 1} times with same query",
                        )
                        return "I can't search for the same thing again. Let me try a different approach."
                    }

                    toolCallHistory.add(Pair(toolName, argsHash))
                    toolCallCount++

                    if (toolCallCount > MAX_TOOL_CALLS) {
                        logger.warn("Tool call limit exceeded ($MAX_TOOL_CALLS) for user: $userId")
                        goalMemoryManager.markFailed("Tool limit exceeded: $toolCallCount calls")
                        return streamProcessor.currentContent.ifEmpty { "Execution limit reached." }
                    }

                    // Finalize and clear current thinking so the next iteration's thought starts fresh in the UI
                    val finalThinkingForStep = ThinkingStorageManagerSingleton.instance.finalizeAndGetThinking(sessionId)
                    ThinkingStorageManagerSingleton.instance.clear(sessionId)

                    val toolCallId = streamProcessor.currentToolId ?: "tool-${java.util.UUID.randomUUID()}"
                    
                    // Append the assistant's tool call to history so the Zen API knows what we're responding to
                    messagesForAgent += LlmMessage(
                        role = LlmMessage.Role.ASSISTANT,
                        content = streamProcessor.currentContent,
                        toolCalls = listOf(
                            com.example.smarty.server.llm.LlmToolCall(
                                id = toolCallId,
                                functionName = toolName,
                                arguments = toolArgs
                            )
                        )
                    )

                    // ToolCall tracking — plugin bridge handles events

                    try {
                        tracer.trace(
                            AgentTraceEvent(
                                sessionId = sessionId,
                                stepType = AgentStepType.TOOL_CALL,
                                content = "Calling tool: $toolName",
                                metadata = mapOf("args" to toolArgs),
                            ),
                        )

                        val toolResult =
                            toolExecutor.executeTool(
                                name = toolName,
                                argsJson = toolArgs,
                                history = messagesForAgent,
                                clientTimezone = clientTimezone,
                                clientTimeMillis = clientTimeMillis,
                                toolCallId = toolCallId,
                                sessionId = sessionId,
                                section = section,
                            )

                        val isToolError =
                            toolResult.startsWith("Error", ignoreCase = true) ||
                                toolResult.startsWith("Search failed", ignoreCase = true) ||
                                toolResult.startsWith("All configured keys failed", ignoreCase = true) ||
                                toolResult.startsWith("Failed to", ignoreCase = true) ||
                                toolResult.contains("failed:", ignoreCase = true) ||
                                (toolResult.contains("failed", ignoreCase = true) && toolResult.contains("error", ignoreCase = true))

                        if (isToolError) {
                            if (toolResult.contains("schema") || toolResult.contains("auth") || toolResult.contains("missing")) {
                                logger.error("PERMANENT TOOL FAILURE: $toolName — $toolResult")
                                messagesForAgent +=
                                    LlmMessage(
                                        role = LlmMessage.Role.TOOL,
                                        content =
                                            "[Tool Permanent Error for $toolName]: $toolResult. " +
                                                "This error is deterministic and cannot be fixed by retrying. " +
                                                "Do NOT attempt to call this tool again with similar arguments. " +
                                                "Inform the user and stop.",
                                        name = toolName,
                                        toolCallId = toolCallId,
                                    )
                                goalMemoryManager.addError(
                                    "Tool $toolName permanent failure: ${toolResult.take(200)}",
                                )
                                persistenceManager.saveCheckpoint(
                                    sessionId,
                                    messagesForAgent,
                                    "permanent_error_$toolName",
                                )
                                continue
                            }

                            if (toolName == lastFailedToolName) {
                                consecutiveToolFailures++
                            } else {
                                lastFailedToolName = toolName
                                consecutiveToolFailures = 1
                            }
                            logger.warn(
                                "Tool returned error result: $toolName, failures: $consecutiveToolFailures",
                            )
                        } else {
                            lastFailedToolName = null
                            consecutiveToolFailures = 0
                        }

                        Metrics
                            .counter(
                                "agent.tool." + if (isToolError) "error" else "success",
                                "tool",
                                toolName,
                            ).increment()

                        messagesForAgent +=
                            LlmMessage(
                                role = LlmMessage.Role.TOOL,
                                content = "[Tool Result for $toolName]: $toolResult",
                                name = toolName,
                                toolCallId = toolCallId,
                            )

                        // §2.2 ask_user: ToolExecutor suspends the turn by returning this
                        // sentinel. The agent pauses here; the webhook will inject the user's
                        // answers into history and the client will start a new query to resume.
                        if (toolResult == "__ASK_USER_TURN_COMPLETE__") {
                            logger.info("[ServerAgent] ask_user suspended agent loop for tool=$toolName session=$sessionId")
                            persistenceManager.saveCheckpoint(sessionId, messagesForAgent, "ask_user_suspended")
                            break
                        }

                        val stepDescription = "Executed $toolName"
                        if (isToolError) {
                            goalMemoryManager.addError("Tool $toolName failed: ${toolResult.take(200)}")
                        } else {
                            goalMemoryManager.markStepCompleted(
                                description = stepDescription,
                                toolUsed = toolName,
                                result = toolResult.take(500),
                            )
                        }

                        persistenceManager.saveCheckpoint(sessionId, messagesForAgent, toolName)
                        continue
                    } catch (e: Exception) {
                        logger.error("Tool execution failed: $toolName", e)
                        Metrics.counter("agent.tool.error", "tool", toolName).increment()

                        messagesForAgent +=
                            LlmMessage(
                                role = LlmMessage.Role.TOOL,
                                content = "[Tool Error for $toolName]: ${e.message}",
                                name = toolName,
                                toolCallId = toolCallId,
                            )
                        goalMemoryManager.addError("Tool $toolName exception: ${e.message?.take(200)}")
                        persistenceManager.saveCheckpoint(sessionId, messagesForAgent, "error_$toolName")
                        continue
                    }
                } else if (streamProcessor.currentContent.isNotEmpty()) {
                    stateManager.putCache(
                        messagesForAgent,
                        tools,
                        query,
                        streamProcessor.currentContent,
                        toolCallCount > 0,
                        modelOverride,
                        variantOverride,
                    )

                    val citationCount = toolCallHistory.size
                    val confidence =
                        when {
                            citationCount >= 3 -> "verified"
                            citationCount >= 1 -> "moderate"
                            else -> "model_knowledge"
                        }
                    val sourceType =
                        when {
                            toolCallHistory.any { it.first.contains("search") || it.first.contains("tavily") } -> "web_search"
                            toolCallHistory.any { it.first.contains("memory") || it.first.contains("note") } -> "user_data"
                            else -> "model_knowledge"
                        }

                    tracer.trace(
                        AgentTraceEvent(
                            sessionId = sessionId,
                            stepType = AgentStepType.FINAL,
                            content = streamProcessor.currentContent,
                        ),
                    )
                    persistenceManager.clearCheckpoint(sessionId)

                    goalMemoryManager.markCompleted()

                    return streamProcessor.currentContent
                } else {
                    val reason = streamProcessor.finishReason
                    if (reason == "error") {
                        logger.warn("LLM stream ended with error for user: $userId")
                        return "I'm sorry, my language model encountered an error generating the response."
                    }
                    logger.warn("LLM stream completed with no content for user: $userId")
                    tracer.trace(
                        AgentTraceEvent(
                            sessionId = sessionId,
                            stepType = AgentStepType.ERROR,
                            content = "Empty response from LLM",
                        ),
                    )
                    return "I processed your request, but wasn't able to generate a text response."
                }
            } catch (e: Exception) {
                // Don't swallow CancellationException (including TimeoutCancellationException) —
                // let the outer withTimeout handle it with the correct error message
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.error("LLM stream error", e)
                val errorMsg = e.message ?: "Unknown error"

                val isCliUnavailable =
                    errorMsg.contains("OpenCode CLI", ignoreCase = true) ||
                        errorMsg.contains("exit code", ignoreCase = true) ||
                        errorMsg.contains("opencode: command not found", ignoreCase = true)

                if (isCliUnavailable) {
                    logger.warn("OpenCode CLI unavailable — rethrowing for error handling")
                    throw e
                }

                val userMsg =
                    when {
                        errorMsg.contains("Free usage exceeded", ignoreCase = true) ||
                            errorMsg.contains("free tier", ignoreCase = true) ->
                            "OpenCode free tier is exhausted. Requests will resume after the daily reset. Subscribe at https://opencode.ai/go for more."
                        errorMsg.contains("Max retries exceeded", ignoreCase = true) ||
                            errorMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                            errorMsg.contains("rate limit", ignoreCase = true) ||
                            errorMsg.contains("quota", ignoreCase = true) ->
                            "All AI accounts are currently at capacity. Try a different model or wait a moment."
                        errorMsg.contains("Socket timeout", ignoreCase = true) ||
                            errorMsg.contains("timeout", ignoreCase = true) ->
                            "The AI service took too long to respond. Please try again."
                        errorMsg.contains("Connection refused", ignoreCase = true) ||
                            errorMsg.contains("connection", ignoreCase = true) ->
                            "Cannot reach the AI service. Check if the proxy is running."
                        errorMsg.contains("context window", ignoreCase = true) ||
                            errorMsg.contains("max tokens", ignoreCase = true) ->
                            "Conversation is too long. Starting a fresh session."
                        else -> "I encountered an unexpected issue while processing your request. Please try again."
                    }
                goalMemoryManager.markFailed(errorMsg)
                return userMsg
            }
        }

        logger.warn("Agent loop reached max iterations ($MAX_ITERATIONS) for user: $userId")
        goalMemoryManager.markFailed("Max iterations reached: $MAX_ITERATIONS")
        return "I completed several actions but reached my iteration limit."
    }
}
