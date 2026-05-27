package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentEvent
import com.example.smarty.server.data.CalendarRepository
import com.example.smarty.server.data.ConversationSummarizer
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.data.TimerRepository
import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.LlmProvider
import io.micrometer.core.instrument.Metrics
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory
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
) {
    private val logger = LoggerFactory.getLogger(ServerAgent::class.java)

    // Extracted components
    private val stateManager = AgentStateManager(userId, llmProvider, vectorStore, summarizer)

    // Permission Engine: resume callback fed to ToolExecutor.
    // Populated by ChatRoutes for each SSE stream; calls resume the waiting
    // continuation when the user approves or denies via POST /chat/events/approval.
    private var resumeApprovalHandler: (suspend (com.example.smarty.protocol.AgentEvent.ApprovalResponse) -> Unit)? = null

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
        const val MAX_EXECUTION_TIME_MS = 30 * 60 * 1000L // 30 minutes hard limit
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
    ): String {
        if (query.length > 10000) {
            throw IllegalArgumentException("Query too long")
        }

        return try {
            withTimeout(MAX_EXECUTION_TIME_MS) {
                runInternal(
                    query,
                    sessionId,
                    history,
                    modelOverride,
                    clientTimezone,
                    clientTimeMillis,
                    personality,
                    opencodeSessionId,
                    onOpencodeSessionCreated,
                )
            }
        } catch (e: TimeoutCancellationException) {
            logger.error("Agent execution exceeded ${MAX_EXECUTION_TIME_MS / 60000} minute limit for user: $userId")
            emit(
                AgentEvent.Error(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    message = "I had to stop - the operation took too long. Try breaking it into smaller tasks.",
                    code = "TIMEOUT",
                ),
            )
            "Operation timed out. Please try a simpler request."
        }
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
        val initialHistory = checkpoint?.messages ?: history

        // 1.5 Fetch Tool Examples
        val toolExamples = toolExampleStore.getRelevantExamples(query)

        // 2. Build Messages
        val systemMessage = stateManager.buildSystemMessage(query, clientTimezone, clientTimeMillis, personality, goalMemoryManager)

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
        stateManager.checkCache(messagesForAgent, tools, query, modelOverride)?.let { cached ->
            val streamProcessor = AgentStreamProcessor(sessionId, eventEmitter)

            emit(
                AgentEvent.Processing(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    content = cached,
                    thinking = null,
                ),
            )
            emit(
                AgentEvent.Result(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    content = cached,
                    isFinal = true,
                ),
            )

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

        val streamProcessor = AgentStreamProcessor(sessionId, eventEmitter)
        val toolCallHistory = mutableListOf<Pair<String, String>>()
        var lastFailedToolName: String? = null
        var consecutiveToolFailures = 0
        var awaitingUserResponse = false
        var agentIteration = 0

        while (agentIteration < MAX_ITERATIONS) {
            if (awaitingUserResponse) break
            agentIteration++

            streamProcessor.reset()

            try {
                llmProvider.stream(messagesForAgent, tools, modelOverride, opencodeSessionId, onOpencodeSessionCreated).collect { chunk ->
                    streamProcessor.processChunk(chunk)
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

                // 4. Tool call detected — execute and loop
                if (hasNativeToolCall) {
                    val toolName = streamProcessor.currentToolName
                    val toolArgs = streamProcessor.currentToolArgs

                    val argsHash = toolArgs.take(100).hashCode().toString()
                    val sameCallCount = toolCallHistory.count { it.first == toolName && it.second == argsHash }

                    val isResearchTool =
                        toolName.lowercase().let {
                            it.contains("search") ||
                                it.contains("web") ||
                                it.contains("tavily") ||
                                it.contains("fetch") ||
                                it.contains("scrape") ||
                                it.contains("browser")
                        }

                    val shouldBlock = !isResearchTool && sameCallCount >= 3

                    if (shouldBlock) {
                        logger.warn(
                            "TOOL BLOCKED: Tool $toolName called ${sameCallCount + 1} times with same query",
                        )
                        emit(
                            AgentEvent.ToolBlocked(
                                eventId = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                toolName = toolName,
                                reason = "Same query repeated ${sameCallCount + 1} times. Try a different approach.",
                                code = "TOOL_BLOCKED_SAME_QUERY",
                            ),
                        )
                        return "I can't search for the same thing again. Let me try a different approach."
                    }

                    toolCallHistory.add(Pair(toolName, argsHash))
                    toolCallCount++

                    if (toolCallCount > MAX_TOOL_CALLS) {
                        logger.warn("Tool call limit exceeded ($MAX_TOOL_CALLS) for user: $userId")
                        emit(
                            AgentEvent.Error(
                                eventId = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                message = "I've made too many actions in this session. Let me summarize what I've done.",
                                code = "TOOL_LIMIT_EXCEEDED",
                            ),
                        )
                        goalMemoryManager.markFailed("Tool limit exceeded: $toolCallCount calls")
                        return streamProcessor.currentContent.ifEmpty { "Execution limit reached." }
                    }

                    // Finalize and clear current thinking so the next iteration's thought starts fresh in the UI
                    val finalThinkingForStep = ThinkingStorageManagerSingleton.instance.finalizeAndGetThinking(sessionId)
                    if (finalThinkingForStep.isNotEmpty()) {
                        emit(
                            AgentEvent.Processing(
                                eventId = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                content = "",
                                thinking = finalThinkingForStep,
                            ),
                        )
                    }
                    ThinkingStorageManagerSingleton.instance.clear(sessionId)

                    // Emit ToolCall SSE event so the client shows "Executing tool..."
                    emit(
                        AgentEvent.ToolCall(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            toolName = toolName,
                            displayName = "Executing $toolName...",
                            status = "started",
                        ),
                    )
                    streamProcessor.emitCustomToolStep(toolName, "started", inputSummary = toolArgs)

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
                            )

                        if ((toolName == "ask_user" || toolName == "askuser") && toolResult == "__WAITING_FOR_USER_RESPONSE__") {
                            val finalThinking = ThinkingStorageManagerSingleton.instance.finalizeAndGetThinking(sessionId)
                            emit(
                                AgentEvent.Result(
                                    eventId = UUID.randomUUID().toString(),
                                    timestamp = System.currentTimeMillis(),
                                    content = "I'm waiting for your response to the question above.",
                                    thinking = finalThinking,
                                    isFinal = true,
                                ),
                            )
                            ThinkingStorageManagerSingleton.instance.clear(sessionId)
                            awaitingUserResponse = true
                            continue
                        }

                        val stepDescription = "Executed $toolName"
                        if (isToolError) {
                            goalMemoryManager.addError("Tool $toolName failed: ${toolResult.take(200)}")
                            streamProcessor.emitCustomToolStep(toolName, "failed", outputSummary = toolResult)
                        } else {
                            goalMemoryManager.markStepCompleted(
                                description = stepDescription,
                                toolUsed = toolName,
                                result = toolResult.take(500),
                            )
                            streamProcessor.emitCustomToolStep(toolName, "completed", outputSummary = toolResult)
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
                            )
                        goalMemoryManager.addError("Tool $toolName exception: ${e.message?.take(200)}")
                        persistenceManager.saveCheckpoint(sessionId, messagesForAgent, "error_$toolName")
                        continue
                    }
                } else if (streamProcessor.currentContent.isNotEmpty()) {
                    stateManager.putCache(messagesForAgent, tools, query, streamProcessor.currentContent, toolCallCount > 0, modelOverride)

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

                    streamProcessor.emitFinalResponse(streamProcessor.currentContent, confidence, sourceType)

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
                    logger.warn("LLM stream completed with no content for user: $userId")
                    tracer.trace(
                        AgentTraceEvent(
                            sessionId = sessionId,
                            stepType = AgentStepType.ERROR,
                            content = "Empty response from LLM",
                        ),
                    )
                    emit(
                        AgentEvent.Error(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            message = "I didn't receive a response from the AI service. Please try again.",
                            code = "EMPTY_RESPONSE",
                        ),
                    )
                    return ""
                }
            } catch (e: Exception) {
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
                emit(
                    AgentEvent.Error(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        message = userMsg,
                        code = "LLM_ERROR",
                    ),
                )

                goalMemoryManager.markFailed(errorMsg)
                return ""
            }
        }

        logger.warn("Agent loop reached max iterations ($MAX_ITERATIONS) for user: $userId")
        goalMemoryManager.markFailed("Max iterations reached: $MAX_ITERATIONS")
        return "I completed several actions but reached my iteration limit."
    }

    private suspend fun emit(event: AgentEvent) {
        eventEmitter(event)
    }
}
