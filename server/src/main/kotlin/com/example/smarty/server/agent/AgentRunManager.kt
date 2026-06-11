package com.example.smarty.server.agent

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

    // Map chatSessionId -> Android-provided messageId so timeline bridge events
    // can carry the correct ID that Android filters on.
    private val messageIdMap = ConcurrentHashMap<String, String>()

    // Track sessions where the plugin bridge has successfully emitted text content.
    // If the bridge emits text, we skip the AgentRunManager fallback delivery.
    private val bridgeSentTextSessions = ConcurrentHashMap<String, Boolean>()

    fun getEventFlow(sessionId: String): SharedFlow<AgentEvent> =
        sessionEventFlows
            .getOrPut(sessionId) {
                MutableSharedFlow(
                    replay = 10,
                    extraBufferCapacity = 100,
                )
            }.asSharedFlow()

    fun setMessageId(
        sessionId: String,
        messageId: String,
    ) {
        if (messageId.isBlank()) return
        messageIdMap[sessionId] = messageId
    }

    fun getMessageId(sessionId: String): String? = messageIdMap[sessionId]

    fun clearMessageId(sessionId: String) {
        messageIdMap.remove(sessionId)
    }

    /** Called by the TimelineBridgeRoutes when it successfully emits text content for a session. */
    fun markBridgeSentText(sessionId: String) {
        bridgeSentTextSessions[sessionId] = true
    }

    fun hasBridgeSentText(sessionId: String): Boolean = bridgeSentTextSessions.containsKey(sessionId)

    suspend fun emitEvent(
        sessionId: String,
        event: AgentEvent,
    ) {
        sessionEventFlows[sessionId]?.emit(event)
    }

    /**
     * Starts an agent run in the background. If a run is already active for this session, it ignores or cancels based on logic.
     * @return true if the run was started, false if it was skipped (e.g. pending approval)
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
        section: String? = null,
        fcmService: com.example.smarty.server.services.FcmNotificationService? = null,
    ): Boolean {
        val existingJob = activeRuns[sessionId]
        if (existingJob?.isActive == true) {
            if (ApprovalRegistry.hasPendingForSession(sessionId)) {
                logger.warn("Agent run already active for session: $sessionId with pending approval. Ignoring new query.")
                return false
            }
            logger.warn("Agent run already active for session: $sessionId. Cancelling existing and starting new.")
            existingJob.cancel()
            activeRuns.remove(sessionId)
        }

        if (!messageId.isNullOrBlank()) {
            messageIdMap[sessionId] = messageId
        }

        val flow =
            sessionEventFlows.getOrPut(sessionId) {
                MutableSharedFlow(
                    replay = 10,
                    extraBufferCapacity = 100,
                )
            }

        if (opencodeSessionId != null) {
            ActiveSessionManager.registerOpencodeSessionId(opencodeSessionId, userId, sessionId)
        }

        val job =
            agentScope.launch {
                val eventEmitter: suspend (AgentEvent) -> Unit = { event -> emitEvent(sessionId, event) }

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
                        fcmService = fcmService,
                    )

                try {
                    // Match ServerAgent's hard limit — the agent itself manages timeouts per-iteration.
                    // Enforce 10-minute maximum runtime per agent session.
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
                                    // CRITICAL: Register the NEW opencode session ID immediately so that
                                    // plugin bridge events (arriving at /opencode/events) can be routed
                                    // to the correct SharedFlow. Without this, ALL plugin events are dropped.
                                    ActiveSessionManager.registerOpencodeSessionId(newOpencodeSessionId, userId, sessionId)
                                    if (chatRepository != null) {
                                        try {
                                            chatRepository.updateOpencodeSessionId(userId, sessionId, newOpencodeSessionId)
                                        } catch (e: Exception) {
                                            logger.error("Failed to update opencodeSessionId", e)
                                        }
                                    }
                                },
                                variantOverride = variantOverride,
                                section = section,
                            )
                        }

                    logger.info("Agent run completed for session: $sessionId, response length: ${assistantResponse.length}")

                    // GUARANTEED DELIVERY: emit the final response text directly via the SharedFlow.
                    // The plugin bridge's message.updated path may fail to parse text when the daemon
                    // returns a JSON body (not SSE) and the plugin's final message.updated snapshot
                    // does not include the full parts array. This is the authoritative fallback.
                    if (assistantResponse.isNotBlank()) {
                        val ts = System.currentTimeMillis()
                        val eid = UUID.randomUUID().toString()
                        // Only emit if bridge hasn't already sent the text (bridge sets sentTextForSession flag)
                        if (!hasBridgeSentText(sessionId)) {
                            logger.info(
                                "AgentRunManager: bridge did not emit text, emitting directly for session=$sessionId len=${assistantResponse.length}",
                            )
                            emitEvent(
                                sessionId,
                                AgentEvent.TextDelta(
                                    eventId = UUID.randomUUID().toString(),
                                    timestamp = ts,
                                    text = assistantResponse,
                                ),
                            )
                            emitEvent(
                                sessionId,
                                AgentEvent.ResponseBlock(
                                    eventId = UUID.randomUUID().toString(),
                                    timestamp = ts,
                                    sessionId = sessionId,
                                    messageId = messageIdMap[sessionId] ?: eid,
                                    content = assistantResponse,
                                ),
                            )
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    val msg = "Agent execution timed out after ${ServerAgent.MAX_EXECUTION_TIME_MS / 60000} minutes for session: $sessionId"
                    logger.error(msg, e)
                    emitEvent(
                        sessionId,
                        AgentEvent.Error(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            message = msg,
                            code = "TIMEOUT",
                        ),
                    )
                } catch (e: Exception) {
                    val msg = "Agent execution failed for session: $sessionId: ${e.message}"
                    logger.error(msg, e)
                    emitEvent(
                        sessionId,
                        AgentEvent.Error(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            message = msg,
                            code = "INTERNAL_ERROR",
                        ),
                    )
                } finally {
                    // Critical Fix: Small delay before terminal Done to allow late-arriving
                    // bridge snapshots (ResponseBlock) to reach the SharedFlow.
                    kotlinx.coroutines.delay(1500)
                    emitEvent(sessionId, AgentEvent.Done(eventId = UUID.randomUUID().toString(), timestamp = System.currentTimeMillis()))
                    ActiveEventBridge.clear(userId)

                    ApprovalRegistry.cancelApprovalsForSession(sessionId)
                    ThinkingStorageManagerSingleton.instance.clear(sessionId)
                    ActiveSessionManager.endSession(userId, sessionId)
                    activeRuns.remove(sessionId)
                    messageIdMap.remove(sessionId)
                    bridgeSentTextSessions.remove(sessionId)
                    // Clean up the SharedFlow to prevent memory leak
                    // Delay removal so late subscribers can still receive final events
                    kotlinx.coroutines.delay(5000)
                    sessionEventFlows.remove(sessionId)
                }
            }

        activeRuns[sessionId] = job
        return true
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
