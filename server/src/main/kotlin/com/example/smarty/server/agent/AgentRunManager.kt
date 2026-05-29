package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentCommand
import com.example.smarty.protocol.AgentEvent
import com.example.smarty.server.data.CalendarRepository
import com.example.smarty.server.data.ChatRepository
import com.example.smarty.server.data.ConversationSummarizer
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.data.TimerRepository
import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.LlmProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages decoupled Agent runs so they survive WebSocket disconnections.
 */
object AgentRunManager {
    private val logger = LoggerFactory.getLogger(AgentRunManager::class.java)

    // Scope for running agents independently of client HTTP/WebSocket connections
    private val agentScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val activeRuns = ConcurrentHashMap<String, Job>()

    // Store SharedFlow per session to broadcast events
    private val sessionEventFlows = ConcurrentHashMap<String, MutableSharedFlow<AgentEvent>>()

    fun getEventFlow(sessionId: String): SharedFlow<AgentEvent> =
        sessionEventFlows
            .getOrPut(sessionId) {
                MutableSharedFlow(extraBufferCapacity = 200)
            }.asSharedFlow()

    suspend fun emitEvent(
        sessionId: String,
        event: AgentEvent,
    ) {
        sessionEventFlows[sessionId]?.emit(event)
    }

    /**
     * Starts an agent run in the background. If a run is already active for this session, it ignores or cancels based on logic.
     */
    fun startRun(
        userId: String,
        sessionId: String,
        query: String,
        llmProvider: LlmProvider,
        vectorStore: PostgresVectorStore,
        summarizer: ConversationSummarizer,
        noteRepository: NoteRepository?,
        timerRepository: TimerRepository?,
        calendarRepository: CalendarRepository?,
        noteService: com.example.smarty.server.services.NoteService?,
        chatRepository: ChatRepository?,
        modelOverride: String?,
        clientTimezone: String?,
        clientTimeMillis: Long?,
        personality: String?,
        history: List<LlmMessage>,
        opencodeSessionId: String?,
        messageId: String? = null,
        variantOverride: String? = null,
    ) {
        val existingJob = activeRuns[sessionId]
        if (existingJob?.isActive == true) {
            logger.warn("Agent run already active for session: $sessionId. Ignoring new request.")
            return
        }

        val flow =
            sessionEventFlows.getOrPut(sessionId) {
                MutableSharedFlow(extraBufferCapacity = 200)
            }

        val job =
            agentScope.launch {
                val collectedAgentSteps = mutableMapOf<Int, com.example.smarty.protocol.AgentEvent.AgentStep>()
                val collectedCitations = mutableListOf<com.example.smarty.protocol.ProtocolWebCitation>()
                val collectedAgentEvents = mutableListOf<AgentEvent>()

                val eventEmitter: suspend (AgentEvent) -> Unit = { event ->
                    flow.emit(event)
                    ActiveEventBridge.emit(userId, event) // Support legacy SSE active bridge if still needed

                    collectedAgentEvents.add(event)
                    if (event is AgentEvent.AgentStep) {
                        collectedAgentSteps[event.stepIndex] = event
                    }
                    if (event is AgentEvent.Command) {
                        val cmd = event.command
                        if (cmd is AgentCommand.NotifyCitations) {
                            collectedCitations.addAll(cmd.citations)
                        }
                    }

                    // Progressive save for active WebSocket stream (so crashes don't lose data)
                    if (chatRepository != null &&
                        (event is AgentEvent.Processing || event is AgentEvent.ToolCall || event is AgentEvent.AgentStep)
                    ) {
                        val currentThinking = ThinkingStorageManagerSingleton.instance.getCurrentThinking(sessionId)
                        val currentToolCalls =
                            if (currentThinking.contains(
                                    "SMARTY_TRACE_V2",
                                )
                            ) {
                                currentThinking.substringAfter("SMARTY_TRACE_V2:")
                            } else {
                                null
                            }

                        val currentAgentEventsJson =
                            if (collectedAgentSteps.isNotEmpty() || event !is AgentEvent.Processing) {
                                val filteredEvents =
                                    collectedAgentEvents.filter {
                                        it !is AgentEvent.Processing &&
                                            it !is AgentEvent.OpencodeRawEvent
                                    }
                                if (filteredEvents.isNotEmpty()) {
                                    kotlinx.serialization.json.Json
                                        .encodeToString(filteredEvents)
                                } else {
                                    null
                                }
                            } else {
                                null
                            }

                        val currentAgentStepsJson =
                            if (collectedAgentSteps.isNotEmpty()) {
                                val entries =
                                    collectedAgentSteps.values.sortedBy { it.stepIndex }.map { step ->
                                        com.example.smarty.core.domain.model.AgentStepEntry(
                                            stepType = step.stepType,
                                            stepTitle = step.stepTitle,
                                            stepContent = step.stepContent,
                                            stepStatus = step.stepStatus,
                                            stepIndex = step.stepIndex,
                                            toolName = step.toolName,
                                            durationMs = step.durationMs,
                                        )
                                    }
                                kotlinx.serialization.json.Json
                                    .encodeToString(entries)
                            } else {
                                null
                            }

                        if (currentThinking.isNotBlank() || currentAgentStepsJson != null || currentAgentEventsJson != null) {
                            chatRepository.updateMessageThinking(
                                userId = userId,
                                sessionId = sessionId,
                                thinking = currentThinking,
                                toolCalls = currentToolCalls,
                                agentStepsJson = currentAgentStepsJson,
                                agentEventsJson = currentAgentEventsJson,
                            )
                        }
                    }
                }

                val agent =
                    ServerAgent(
                        llmProvider = llmProvider,
                        vectorStore = vectorStore,
                        summarizer = summarizer,
                        noteRepository = noteRepository,
                        timerRepository = timerRepository,
                        calendarRepository = calendarRepository,
                        eventEmitter = eventEmitter,
                        userId = userId,
                        noteService = noteService,
                        capabilities = DeviceRegistry.getCapabilities(userId),
                    )

                try {
                    // Pre-emit processing
                    eventEmitter(
                        AgentEvent.Processing(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            content = "",
                            thinking = "Initializing task...",
                        ),
                    )

                    // Match ServerAgent's hard limit — the agent itself manages timeouts per-iteration.
                    // The previous 2-minute limit killed legitimate deep research tasks.
                    val assistantResponse =
                        kotlinx.coroutines.withTimeout(com.example.smarty.server.agent.ServerAgent.MAX_EXECUTION_TIME_MS) {
                            agent.run(
                                query = query,
                                sessionId = sessionId,
                                history = history,
                                modelOverride = modelOverride,
                                clientTimezone = clientTimezone,
                                clientTimeMillis = clientTimeMillis,
                                personality = personality,
                                opencodeSessionId = opencodeSessionId,
                                onOpencodeSessionCreated = { newOpencodeSessionId ->
                                    if (chatRepository != null) {
                                        try {
                                            chatRepository.updateOpencodeSessionId(userId, sessionId, newOpencodeSessionId)
                                        } catch (e: Exception) {
                                            logger.error("Failed to update opencodeSessionId", e)
                                        }
                                    }
                                },
                                variantOverride = variantOverride,
                            )
                        }

                    // The repository saving happens in the AgentRunManager now (moved from ChatRoutes)
                    if (chatRepository != null && assistantResponse.isNotEmpty()) {
                        val thinkingTrace =
                            ThinkingStorageManagerSingleton.instance
                                .finalizeAndGetThinking(sessionId)
                                .ifBlank { null }

                        val citationsJson =
                            if (collectedCitations.isNotEmpty()) {
                                kotlinx.serialization.json.Json.encodeToString(
                                    collectedCitations,
                                )
                            } else {
                                "[]"
                            }
                        val agentStepsJson =
                            if (collectedAgentSteps.isNotEmpty()) {
                                val entries =
                                    collectedAgentSteps.values.sortedBy { it.stepIndex }.map { step ->
                                        com.example.smarty.core.domain.model.AgentStepEntry(
                                            stepType = step.stepType,
                                            stepTitle = step.stepTitle,
                                            stepContent = step.stepContent,
                                            stepStatus = step.stepStatus,
                                            stepIndex = step.stepIndex,
                                            toolName = step.toolName,
                                            durationMs = step.durationMs,
                                        )
                                    }
                                kotlinx.serialization.json.Json
                                    .encodeToString(entries)
                            } else {
                                null
                            }

                        val finalAgentEventsJson =
                            if (collectedAgentEvents.isNotEmpty()) {
                                val filteredEvents =
                                    collectedAgentEvents.filter {
                                        it !is AgentEvent.Processing &&
                                            it !is AgentEvent.OpencodeRawEvent
                                    }
                                if (filteredEvents.isNotEmpty()) {
                                    kotlinx.serialization.json.Json
                                        .encodeToString(filteredEvents)
                                } else {
                                    null
                                }
                            } else {
                                null
                            }

                        if (messageId != null) {
                            chatRepository.saveMessageWithId(
                                userId = userId,
                                sessionId = sessionId,
                                messageId = messageId,
                                role = LlmMessage.Role.ASSISTANT.name,
                                content = assistantResponse,
                                thinking = thinkingTrace,
                                toolCalls = citationsJson,
                                agentStepsJson = agentStepsJson,
                                agentEventsJson = finalAgentEventsJson,
                            )
                        } else {
                            chatRepository.saveMessage(
                                userId = userId,
                                sessionId = sessionId,
                                role = LlmMessage.Role.ASSISTANT.name,
                                content = assistantResponse,
                                thinking = thinkingTrace,
                                toolCalls = citationsJson,
                                agentStepsJson = agentStepsJson,
                                agentEventsJson = finalAgentEventsJson,
                            )
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    logger.error(
                        "Agent execution timed out after ${ServerAgent.MAX_EXECUTION_TIME_MS / 60000} minutes for session: $sessionId",
                        e,
                    )
                    try {
                        eventEmitter(
                            AgentEvent.Error(
                                eventId = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                message = "Agent execution timed out. The task took too long to complete.",
                                code = "AGENT_TIMEOUT",
                            ),
                        )
                    } catch (emitError: Exception) {
                        logger.warn("Failed to emit timeout error event: ${emitError.message}")
                    }
                } catch (e: Exception) {
                    logger.error("Agent execution failed in background job for session: $sessionId", e)
                    try {
                        eventEmitter(
                            AgentEvent.Error(
                                eventId = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                message = "An error occurred during processing: ${e.message ?: "Unknown error"}",
                                code = "AGENT_BACKGROUND_ERROR",
                            ),
                        )
                    } catch (emitError: Exception) {
                        logger.warn("Failed to emit error event: ${emitError.message}")
                    }
                } finally {
                    ActiveEventBridge.clear(userId)
                    ApprovalRegistry.cancelApprovalsForSession(sessionId)
                    ThinkingStorageManagerSingleton.instance.clear(sessionId)
                    ActiveSessionManager.endSession(userId, sessionId)
                    activeRuns.remove(sessionId)
                    // Clean up the SharedFlow to prevent memory leak
                    // Delay removal so late subscribers can still receive final events
                    kotlinx.coroutines.delay(5000)
                    sessionEventFlows.remove(sessionId)
                }
            }

        activeRuns[sessionId] = job
    }

    fun isRunActive(sessionId: String): Boolean = activeRuns[sessionId]?.isActive == true

    /**
     * Cancel an active run for a specific session gracefully.
     */
    fun cancelRun(sessionId: String): Boolean {
        val job = activeRuns[sessionId]
        return if (job != null && job.isActive) {
            job.cancel(kotlinx.coroutines.CancellationException("User requested interruption"))
            true
        } else {
            false
        }
    }

    /**
     * Cancel all active runs and clean up resources. Called during server shutdown.
     */
    fun shutdown() {
        logger.info("Shutting down AgentRunManager - cancelling ${activeRuns.size} active runs")
        activeRuns.values.forEach { it.cancel() }
        activeRuns.clear()
        sessionEventFlows.clear()
        agentScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
