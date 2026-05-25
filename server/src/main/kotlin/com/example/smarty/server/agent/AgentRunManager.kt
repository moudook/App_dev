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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    fun getEventFlow(sessionId: String): SharedFlow<AgentEvent> {
        return sessionEventFlows.getOrPut(sessionId) {
            MutableSharedFlow(replay = 100, extraBufferCapacity = 200)
        }.asSharedFlow()
    }
    
    suspend fun emitEvent(sessionId: String, event: AgentEvent) {
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
        opencodeSessionId: String?
    ) {
        val existingJob = activeRuns[sessionId]
        if (existingJob?.isActive == true) {
            logger.warn("Agent run already active for session: $sessionId. Ignoring new request.")
            return
        }

        val flow = sessionEventFlows.getOrPut(sessionId) {
            MutableSharedFlow(replay = 100, extraBufferCapacity = 200)
        }

        val job = agentScope.launch {
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
                if (event is AgentEvent.Command && event.command is AgentCommand.NotifyCitations) {
                    collectedCitations.addAll(event.command.citations)
                }
                
                // Progressive save for active WebSocket stream (so crashes don't lose data)
                if (chatRepository != null && (event is AgentEvent.Processing || event is AgentEvent.ToolCall || event is AgentEvent.AgentStep)) {
                    val currentThinking = ThinkingStorageManagerSingleton.instance.getCurrentThinking(sessionId)
                    val currentToolCalls = if (currentThinking.contains("SMARTY_TRACE_V2")) currentThinking.substringAfter("SMARTY_TRACE_V2:") else null
                    
                    val currentAgentEventsJson = if (collectedAgentSteps.isNotEmpty() || event !is AgentEvent.Processing) {
                        val filteredEvents = collectedAgentEvents.filter { it !is AgentEvent.Processing && it !is AgentEvent.OpencodeRawEvent }
                        if (filteredEvents.isNotEmpty()) kotlinx.serialization.json.Json.encodeToString(filteredEvents) else null
                    } else null
                    
                    val currentAgentStepsJson = if (collectedAgentSteps.isNotEmpty()) {
                        val entries = collectedAgentSteps.values.sortedBy { it.stepIndex }.map { step ->
                            com.example.smarty.core.domain.model.AgentStepEntry(
                                stepType = step.stepType, stepTitle = step.stepTitle, stepContent = step.stepContent,
                                stepStatus = step.stepStatus, stepIndex = step.stepIndex, toolName = step.toolName, durationMs = step.durationMs
                            )
                        }
                        kotlinx.serialization.json.Json.encodeToString(entries)
                    } else null
                    
                    if (currentThinking.isNotBlank() || currentAgentStepsJson != null || currentAgentEventsJson != null) {
                        chatRepository.updateMessageThinking(
                            userId = userId, sessionId = sessionId,
                            thinking = currentThinking, toolCalls = currentToolCalls,
                            agentStepsJson = currentAgentStepsJson, agentEventsJson = currentAgentEventsJson
                        )
                    }
                }
            }

            val agent = ServerAgent(
                llmProvider = llmProvider,
                vectorStore = vectorStore,
                summarizer = summarizer,
                noteRepository = noteRepository,
                timerRepository = timerRepository,
                calendarRepository = calendarRepository,
                eventEmitter = eventEmitter,
                userId = userId,
                noteService = noteService,
                capabilities = DeviceRegistry.getCapabilities(userId)
            )

            try {
                // Pre-emit processing
                eventEmitter(
                    AgentEvent.Processing(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        content = "",
                        thinking = "Initializing task..."
                    )
                )

                // Aggressive Supervision: Enforce a 2-minute hard limit on agent runs to mitigate cross-spawn hangs.
                val assistantResponse = kotlinx.coroutines.withTimeout(120_000L) {
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
                        }
                    )
                }

                // The repository saving happens in the AgentRunManager now (moved from ChatRoutes)
                if (chatRepository != null && assistantResponse.isNotEmpty()) {
                    val thinkingTrace = ThinkingStorageManagerSingleton.instance
                        .finalizeAndGetThinking(sessionId)
                        .ifBlank { null }
                    
                    val citationsJson = if (collectedCitations.isNotEmpty()) kotlinx.serialization.json.Json.encodeToString(collectedCitations) else "[]"
                    val agentStepsJson = if (collectedAgentSteps.isNotEmpty()) {
                        val entries = collectedAgentSteps.values.sortedBy { it.stepIndex }.map { step ->
                            com.example.smarty.core.domain.model.AgentStepEntry(
                                stepType = step.stepType, stepTitle = step.stepTitle, stepContent = step.stepContent,
                                stepStatus = step.stepStatus, stepIndex = step.stepIndex, toolName = step.toolName, durationMs = step.durationMs
                            )
                        }
                        kotlinx.serialization.json.Json.encodeToString(entries)
                    } else null

                    val finalAgentEventsJson = if (collectedAgentEvents.isNotEmpty()) {
                        val filteredEvents = collectedAgentEvents.filter { it !is AgentEvent.Processing && it !is AgentEvent.OpencodeRawEvent }
                        if (filteredEvents.isNotEmpty()) kotlinx.serialization.json.Json.encodeToString(filteredEvents) else null
                    } else null

                    chatRepository.saveMessage(
                        userId = userId,
                        sessionId = sessionId,
                        role = LlmMessage.Role.ASSISTANT.name,
                        content = assistantResponse,
                        thinking = thinkingTrace,
                        toolCalls = citationsJson,
                        agentStepsJson = agentStepsJson,
                        agentEventsJson = finalAgentEventsJson
                    )
                }

            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                logger.error("Agent execution timed out after 2 minutes. Aggressively killing OpenCode daemon to clear stuck cross-spawn instances.", e)
                OpencodeDaemonManager.stopMonitoring()
                OpencodeDaemonManager.startMonitoring() // Restarts the daemon
                try {
                    eventEmitter(
                        AgentEvent.Error(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            message = "Agent execution timed out (hung process detected).",
                            code = "AGENT_TIMEOUT"
                        )
                    )
                } catch (emitError: Exception) {
                    // Ignore
                }
            } catch (e: Exception) {
                logger.error("Agent execution failed in background job", e)
                try {
                    eventEmitter(
                        AgentEvent.Error(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            message = "An error occurred during background processing.",
                            code = "AGENT_BACKGROUND_ERROR"
                        )
                    )
                } catch (emitError: Exception) {
                    // Ignore
                }
            } finally {
                ActiveEventBridge.clear(userId)
                ApprovalRegistry.cancelApprovalsForSession(sessionId)
                ThinkingStorageManagerSingleton.instance.clear(sessionId)
                ActiveSessionManager.endSession(userId, sessionId)
                activeRuns.remove(sessionId)
            }
        }
        
        activeRuns[sessionId] = job
    }

    fun isRunActive(sessionId: String): Boolean {
        return activeRuns[sessionId]?.isActive == true
    }
}
