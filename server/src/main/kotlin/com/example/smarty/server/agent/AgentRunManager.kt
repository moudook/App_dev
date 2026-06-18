package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentEvent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.delay
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
    ): Boolean {
        val flow = sessionEventFlows[sessionId]
        if (flow == null) {
            logger.warn("AgentRunManager: bridge.emit failed! No WebSocket subscribed for session=$sessionId")
            return false
        }
        flow.emit(event)
        return true
    }



    fun isRunActive(sessionId: String): Boolean = activeRuns[sessionId]?.isActive == true

    /**
     * Cancel an active run for a specific session gracefully.
     */
    /**
     * Starts an agent run using the new LangChain4j AgentEngine.
     * Manages the same session lifecycle as [startRun] but uses [AgentEngine.stream]
     * instead of [ServerAgent.run].
     */
    fun startEngineRun(
        engine: com.example.smarty.server.agent2.AgentEngine,
        request: com.example.smarty.server.agent2.AgentRequest,
        messageId: String? = null,
        fcmService: com.example.smarty.server.services.FcmNotificationService? = null,
    ): Boolean {
        val sessionId = request.sessionId
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

        val flow = sessionEventFlows.getOrPut(sessionId) {
            MutableSharedFlow(replay = 10, extraBufferCapacity = 100)
        }

        val job = agentScope.launch {
            val eventEmitter: suspend (AgentEvent) -> Unit = { event -> emitEvent(sessionId, event) }

            try {
                val textBuilder = StringBuilder()

                engine.stream(request, eventEmitter)
                    .collect { token ->
                        textBuilder.append(token)
                        emitEvent(
                            sessionId,
                            AgentEvent.TextDelta(
                                eventId = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                text = token,
                            ),
                        )
                    }

                val fullText = textBuilder.toString()
                logger.info("[EngineRun] Agent run completed for session: $sessionId, response length: ${fullText.length}")

                if (fullText.isNotBlank()) {
                    val ts = System.currentTimeMillis()
                    if (!hasBridgeSentText(sessionId)) {
                        logger.info("[EngineRun] bridge did not emit text, emitting directly for session=$sessionId len=${fullText.length}")
                        emitEvent(
                            sessionId,
                            AgentEvent.TextDelta(
                                eventId = UUID.randomUUID().toString(),
                                timestamp = ts,
                                text = fullText,
                            ),
                        )
                    }
                    emitEvent(
                        sessionId,
                        AgentEvent.ResponseBlock(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = ts,
                            sessionId = sessionId,
                            messageId = messageIdMap[sessionId] ?: UUID.randomUUID().toString(),
                            content = fullText,
                        ),
                    )
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                logger.error("[EngineRun] Agent timed out for session: $sessionId")
                emitEvent(
                    sessionId,
                    AgentEvent.Error(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        message = "Agent execution timed out",
                        code = "TIMEOUT",
                    ),
                )
            } catch (e: Exception) {
                val msg = "[EngineRun] Agent execution failed for session: $sessionId: ${e.message}"
                logger.error(msg, e)
                emitEvent(
                    sessionId,
                    AgentEvent.Error(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        message = e.message ?: "Unknown error (${e.javaClass.simpleName})",
                        code = "INTERNAL_ERROR",
                    ),
                )
            } finally {
                kotlinx.coroutines.delay(1500)
                emitEvent(sessionId, AgentEvent.Done(eventId = UUID.randomUUID().toString(), timestamp = System.currentTimeMillis()))
                ActiveEventBridge.clear(request.userId)
                ApprovalRegistry.cancelApprovalsForSession(sessionId)
                ActiveSessionManager.endSession(request.userId, sessionId)
                activeRuns.remove(sessionId)
                messageIdMap.remove(sessionId)
                bridgeSentTextSessions.remove(sessionId)
                kotlinx.coroutines.delay(5000)
                sessionEventFlows.remove(sessionId)
            }
        }

        activeRuns[sessionId] = job
        return true
    }

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
