package com.example.smarty.features.runtime

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarty.agent.permissions.ToolPermissionDecision
import com.example.smarty.agent.permissions.ToolPermissionPolicy
import com.example.smarty.core.common.util.HttpClientProvider
import com.example.smarty.core.common.util.buildJsonBody
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.protocol.AgentEvent
import com.example.smarty.ui.components.timeline.TimelineNode
import com.example.smarty.ui.components.timeline.TimelineNodeAggregator
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID

data class RuntimeUiState(
    val timelineNodes: List<TimelineNode> = emptyList(),
    val rawEvents: List<AgentEvent> = emptyList(),
    val isRunning: Boolean = false,
    val isLiveStreamConnected: Boolean = false,
    val error: String? = null,
)

/**
 * Drives the Agent Runtime screen — shows a live timeline of every event the
 * OpenCode CLI plugin emits, with interactive cards for tool calls, reasoning
 * traces, and approval gates (including MCP `ask_user` style user-input prompts).
 *
 * Wire model (end-to-end):
 *   OpenCode CLI
 *     → timeline-bridge.ts plugin
 *     → Ktor POST /opencode/events
 *     → Ktor TimelineBridgeService.broadcast()
 *     → Ktor WebSocket /ws/timeline
 *     → THIS ViewModel.connectLiveStream()
 *     → translate raw JSON → AgentEvent
 *     → handleIncomingEvent() → TimelineNodeAggregator
 *     → AgentRuntimeScreen (LazyColumn of TimelineNode cards)
 *
 * For the MCP `ask_user` tool conflict:
 *   When the OpenCode plugin sees a tool in its INTERACTIVE_TOOLS set (e.g. "ask_user"),
 *   it emits `user.input.required` BEFORE the user has responded. This is the
 *   "blocking" signal — the CLI session is stuck waiting.
 *   The plugin's view of the tool switches from "running" → "awaiting user input".
 *   Independently, Ktor's own `McpServer.ask_user` already emits an `ApprovalRequested`
 *   AgentEvent which the main chat surface shows. The user types a response and submits;
 *   the `sendApproval` call routes back to Ktor's `ApprovalRegistry` which unblocks the
 *   MCP tool coroutine. The tool returns, the plugin sees `tool.execute.after`, and the
 *   timeline flips the tool from "awaiting" → "done".
 */
class AgentRuntimeViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(RuntimeUiState())
    val uiState: StateFlow<RuntimeUiState> = _uiState.asStateFlow()

    private val aggregator = TimelineNodeAggregator()

    private val okHttpClient = HttpClientProvider.longRunning
    private val securePreferences = SecurePreferences.getInstance(application)

    /**
     * Defensive permission policy. Mirrors `opencode.json`'s `permission`
     * block. Normally the OpenCode CLI enforces this before any event
     * is emitted, so the policy check here is a safety net for the
     * rare case where a `permission.asked` event leaks through for an
     * already-allow/deny tool.
     */
    private val toolPermissionPolicy = ToolPermissionPolicy.SMARTY_DEFAULT

    private var webSocket: WebSocket? = null
    private val wsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var connectUrl: String = ""
    private var authToken: String? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10

    /**
     * Open a persistent WebSocket to Ktor's `/ws/timeline`. Translates raw plugin
     * events into canonical [AgentEvent]s and feeds them into [handleIncomingEvent].
     *
     * Auto-reconnects with exponential backoff up to 30s.
     */
    fun connectLiveStream() {
        if (webSocket != null) return
        viewModelScope.launch {
            val serverUrl = securePreferences.getSmartyServerUrl()
            val token =
                withContext(Dispatchers.IO) {
                    runCatching {
                        FirebaseAuth
                            .getInstance()
                            .currentUser
                            ?.getIdToken(false)
                            ?.await()
                            ?.token
                    }.getOrNull()
                }
            val wsUrl =
                serverUrl
                    .replace("https://", "wss://")
                    .replace("http://", "ws://")
                    .let { "$it/ws/timeline" }

            Log.i(TAG, "Connecting to live stream: $wsUrl (authorized via header)")
            openWebSocket(wsUrl, token)
        }
    }

    private fun openWebSocket(
        url: String,
        token: String? = null,
    ) {
        reconnectAttempts = 0 // Reset on successful connect attempt
        connectUrl = url
        authToken = token
        val request =
            Request
                .Builder()
                .url(url)
                .apply {
                    if (token != null) {
                        addHeader("Authorization", "Bearer $token")
                    }
                }.build()
        webSocket =
            okHttpClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        Log.i(TAG, "Live stream connected")
                        _uiState.update { it.copy(isLiveStreamConnected = true) }
                        startHeartbeat()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        try {
                            val json = JSONObject(text)
                            val kind = json.optString("kind")
                            val sessionID = json.optString("sessionID")
                            val event = translateToAgentEvent(kind, json)
                            if (event != null) {
                                // Defensive policy short-circuit: if a non-interactive
                                // `permission.asked` event slipped through for a tool
                                // the policy already allows/denies, auto-respond and
                                // DON'T feed it to the aggregator (so no spurious
                                // `ApprovalGate` node appears in the timeline). The
                                // CLI normally handles allow/deny before emitting,
                                // so this is a safety net.
                                if (event is AgentEvent.ApprovalRequested && !event.isInteractive) {
                                    val decision = toolPermissionPolicy.decide(event.toolName)
                                    when (decision) {
                                        ToolPermissionDecision.ALLOW -> {
                                            Log.i(
                                                TAG,
                                                ">>> POLICY_AUTO_APPROVE: tool=${event.toolName} (defensive short-circuit; CLI normally handles this)",
                                            )
                                            sendPluginAskResponse(
                                                sessionId = sessionID.ifBlank { null },
                                                callId = event.toolId,
                                                response = "auto-approved by policy",
                                            )
                                            return // don't render in timeline
                                        }
                                        ToolPermissionDecision.DENY -> {
                                            Log.i(
                                                TAG,
                                                ">>> POLICY_AUTO_DENY: tool=${event.toolName} (defensive short-circuit; CLI normally blocks this)",
                                            )
                                            sendPluginAskResponse(
                                                sessionId = sessionID.ifBlank { null },
                                                callId = event.toolId,
                                                response = "denied by policy",
                                            )
                                            return // don't render in timeline
                                        }
                                        ToolPermissionDecision.DEFAULT -> {
                                            // No policy rule — fall through to the
                                            // normal aggregator path which renders
                                            // the approval card.
                                        }
                                    }
                                }
                                handleIncomingEvent(event)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse WS message: ${e.message}")
                        }
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        Log.i(TAG, "Live stream closing: $code $reason")
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        Log.i(TAG, "Live stream closed: $code $reason")
                        _uiState.update { it.copy(isLiveStreamConnected = false) }
                        // Don't reconnect on normal close (1000) — intentional disconnect
                        if (code != 1000) {
                            scheduleReconnect(url)
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        Log.w(TAG, "Live stream failure: ${t.message}")
                        _uiState.update { it.copy(isLiveStreamConnected = false) }
                        scheduleReconnect(url)
                    }
                },
            )
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob =
            wsScope.launch {
                while (true) {
                    delay(15_000)
                    try {
                        val ok = webSocket?.send("""{"type":"ping"}""") ?: false
                        if (!ok) {
                            Log.w(TAG, "Heartbeat send failed (queue full), will reconnect")
                            break
                        }
                    } catch (e: Exception) {
                        // ignore — onFailure/onClosed will trigger reconnect
                        break
                    }
                }
            }
    }

    private fun scheduleReconnect(url: String) {
        reconnectAttempts++
        if (reconnectAttempts > maxReconnectAttempts) {
            Log.w(TAG, "Max reconnect attempts ($maxReconnectAttempts) reached, giving up")
            _uiState.update { it.copy(isLiveStreamConnected = false) }
            return
        }
        reconnectJob?.cancel()
        reconnectJob =
            wsScope.launch {
                val delayMs = (2_000L * reconnectAttempts).coerceAtMost(30_000L)
                delay(delayMs)
                // Refresh the token before reconnect in case it expired
                val freshToken =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            FirebaseAuth
                                .getInstance()
                                .currentUser
                                ?.getIdToken(false)
                                ?.await()
                                ?.token
                        }.getOrNull()
                    }
                openWebSocket(url, freshToken ?: authToken)
            }
    }

    /**
     * Translate a raw plugin event (JSON) into the canonical [AgentEvent] type
     * the existing [TimelineNodeAggregator] understands. This is the bridge
     * between the plugin's bespoke schema and the Android app's existing
     * timeline model.
     */
    private fun translateToAgentEvent(
        kind: String,
        json: JSONObject,
    ): AgentEvent? {
        val ts = System.currentTimeMillis()
        val eventId = UUID.randomUUID().toString()
        val sessionID = json.optString("sessionID")
        return when (kind) {
            "session.created" -> AgentEvent.SessionStarted(eventId, ts, sessionID)
            "session.idle" -> AgentEvent.SessionCompleted(eventId, ts)
            "session.error" -> {
                val msg = json.optString("error", "Unknown error")
                AgentEvent.SessionError(eventId, ts, msg)
            }
            "session.compacted" -> AgentEvent.RecoveryStarted(eventId, ts, "Compaction completed")
            "compaction.start" -> AgentEvent.RecoveryStarted(eventId, ts, "Context compressing")

            "permission.asked", "user.input.required" -> {
                // Normalize the tool name so the TimelineNodeAggregator detects
                // requiresText=true. The aggregator's heuristic matches "ask_user"
                // or "askuser" (case-insensitive). We map all interactive tool
                // names to "ask_user" so the UI shows a text input.
                val rawTool = json.optString("tool", "ask_user").lowercase()
                val isInteractive =
                    rawTool in
                        setOf(
                            "ask_user",
                            "askuser",
                            "ask",
                            "input",
                            "confirm",
                            "question",
                            "clarify",
                        )
                val tool = if (isInteractive) "ask_user" else rawTool
                val callId = json.optString("callID", eventId)
                val question = json.optString("question", "")
                val toolArgs =
                    if (question.isNotEmpty()) {
                        JSONObject().apply { put("question", question) }.toString()
                    } else {
                        json.optJSONObject("args")?.toString() ?: "{}"
                    }
                AgentEvent.ApprovalRequested(
                    eventId = eventId,
                    timestamp = ts,
                    toolId = callId,
                    toolName = tool,
                    toolTitle = tool.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    toolArgs = toolArgs,
                    isInteractive = isInteractive,
                )
            }

            "permission.replied" -> {
                val granted = json.optBoolean("granted", false)
                val callId = json.optString("toolId", json.optString("callID", eventId))
                if (granted) {
                    AgentEvent.ApprovalGranted(eventId, ts, callId)
                } else {
                    AgentEvent.ApprovalDenied(eventId, ts, callId)
                }
            }

            "part.updated" -> {
                val partType = json.optString("partType")
                val partID = json.optString("partID", eventId)
                val messageID = json.optString("messageID", sessionID)
                when (partType) {
                    "reasoning" -> {
                        val text = json.optString("reasoning", "")
                        AgentEvent.ReasoningDelta(eventId, ts, text)
                    }
                    "text" -> {
                        // Plain text deltas are folded into the FinalAnswer stream.
                        // We forward as a Processing event so the main chat surface
                        // can render incrementally if it chooses.
                        val text = json.optString("text", "")
                        if (text.isNotEmpty()) {
                            AgentEvent.Processing(eventId, ts, text)
                        } else {
                            null
                        }
                    }
                    "tool" -> {
                        val tool = json.optString("tool", "tool")
                        val state = json.optString("state", "running")
                        val toolCallID = json.optString("toolCallID", partID)
                        val input = json.optJSONObject("input")?.toString()
                        val output = json.optJSONObject("output")?.toString()
                        when (state) {
                            "running", "pending" ->
                                AgentEvent.ToolCallStarted(
                                    eventId = eventId,
                                    timestamp = ts,
                                    toolId = toolCallID,
                                    name = tool,
                                    source = "opencode",
                                )
                            "complete", "completed" ->
                                AgentEvent.ToolCallFinished(
                                    eventId = eventId,
                                    timestamp = ts,
                                    toolId = toolCallID,
                                    durationMs = 0L,
                                )
                            "error" ->
                                AgentEvent.ToolBlocked(
                                    eventId = eventId,
                                    timestamp = ts,
                                    toolName = tool,
                                    reason = json.optString("error", "Tool failed"),
                                )
                            else -> null
                        }
                    }
                    "step-start" -> AgentEvent.StepStarted(eventId, ts, title = "Step ${json.optInt("step")}")
                    "step-finish" -> AgentEvent.StepFinished(eventId, ts, success = true)
                    "subtask" -> {
                        val subagentId = json.optString("agent", json.optString("partID", eventId))
                        AgentEvent.AgentStep(
                            eventId = eventId,
                            timestamp = ts,
                            stepIndex = ts.toInt(),
                            stepType = "tool_call",
                            stepTitle = "Sub-agent: ${json.optString("agent")}",
                            stepContent = json.optString("description", ""),
                            stepStatus = json.optString("state", "running"),
                            toolName = "subtask",
                            subagentId = subagentId,
                        )
                    }
                    else -> null
                }
            }

            "tool.before" -> {
                val tool = json.optString("tool", "tool")
                val callId = json.optString("callID", eventId)
                AgentEvent.ToolCallStarted(
                    eventId = eventId,
                    timestamp = ts,
                    toolId = callId,
                    name = tool,
                    source = "opencode",
                )
            }

            "tool.after" -> {
                val tool = json.optString("tool", "tool")
                val callId = json.optString("callID", eventId)
                AgentEvent.ToolCallCompleted(
                    eventId = eventId,
                    timestamp = ts,
                    toolId = callId,
                    result = json.optJSONObject("result")?.toString() ?: "",
                    durationMs = 0L,
                )
            }

            "message.part.delta" -> {
                val partID = json.optString("partID")
                val field = json.optString("field", "text")
                val delta = json.optString("delta", "")
                // Without a per-part type cache we forward as Processing deltas;
                // the aggregator can refine later.
                if (delta.isNotEmpty()) {
                    AgentEvent.Processing(eventId, ts, delta)
                } else {
                    null
                }
            }

            else -> {
                // Unknown kind — ignore silently. The plugin may add new event
                // types in the future; the bridge should never crash on them.
                Log.d(TAG, "Ignoring unknown event kind: $kind")
                null
            }
        }
    }

    fun disconnectLiveStream() {
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _uiState.update { it.copy(isLiveStreamConnected = false) }
    }

    fun startRun(query: String) {
        aggregator.reset()
        _uiState.update {
            it.copy(
                isRunning = true,
                error = null,
                rawEvents = emptyList(),
                timelineNodes = emptyList(),
            )
        }
        connectLiveStream()
    }

    fun stopRun() {
        _uiState.update { it.copy(isRunning = false) }
        disconnectLiveStream()
    }

    /**
     * Process a raw AgentEvent. Updates the timeline in-place.
     */
    fun handleIncomingEvent(event: AgentEvent) {
        val updatedEvents = _uiState.value.rawEvents + event
        aggregator.process(event)
        _uiState.update { state ->
            state.copy(
                rawEvents = updatedEvents,
                timelineNodes = aggregator.nodes.toList(),
            )
        }
    }

    /**
     * Send approval/deny decision back to the server so the suspended MCP tool can resume.
     *
     * For the MCP `ask_user` / interactive tool flow:
     *   The Ktor `/api/v1/chat/events/approval` endpoint resolves the server-side
     *   ApprovalRegistry entry. The McpServer.ask_user coroutine unblocks and the
     *   tool returns. The OpenCode plugin then sees `tool.execute.after` and the
     *   live timeline flips the tool from "awaiting user input" to "done".
     */
    fun sendApproval(
        toolId: String,
        approved: Boolean,
        feedback: String? = null,
    ) {
        viewModelScope.launch {
            try {
                val serverUrl = securePreferences.getSmartyServerUrl()
                val token =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            FirebaseAuth
                                .getInstance()
                                .currentUser
                                ?.getIdToken(false)
                                ?.await()
                                ?.token
                        }.getOrNull()
                    }
                val url = "$serverUrl/api/v1/chat/events/approval"

                val jsonBody =
                    buildJsonBody(
                        "toolId" to toolId,
                        "approved" to approved,
                        "feedback" to feedback,
                    )

                withContext(Dispatchers.IO) {
                    val request =
                        Request
                            .Builder()
                            .url(url)
                            .post(jsonBody.toRequestBody(HttpClientProvider.JSON_MEDIA_TYPE))
                            .apply {
                                if (token != null) {
                                    addHeader("Authorization", "Bearer $token")
                                }
                            }.build()

                    okHttpClient.newCall(request).execute().use { response ->
                        Log.i(TAG, "Approval sent: $approved for $toolId -> ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send approval for $toolId", e)
            }
        }
    }

    /**
     * Defensive auto-response to a plugin-driven MCP `ask` tool call.
     * The Ktor `/opencode/ask-response/{sessionId}/{callId}` route writes
     * the response to
     *   `/tmp/opencode-asks/<sessionID>/<callID>.response.txt`
     * for the OpenCode plugin's MCP `ask` tool to poll and unblock.
     *
     * Used by [onMessage] when the policy check decides a non-interactive
     * `permission.asked` event should be auto-approved/denied without
     * surfacing an approval card.
     *
     * Mirrors the equivalent `RemoteAgentService.sendPluginAskResponse`
     * in the main service layer — the runtime VM uses OkHttp directly
     * because the WebSocket here is also OkHttp.
     */
    private fun sendPluginAskResponse(
        sessionId: String?,
        callId: String,
        response: String,
    ) {
        if (sessionId.isNullOrBlank()) {
            Log.w(TAG, ">>> POLICY_AUTO_RESPONSE_DROPPED: no sessionId for call=$callId")
            return
        }
        viewModelScope.launch {
            try {
                val serverUrl = securePreferences.getSmartyServerUrl()
                val token =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            FirebaseAuth
                                .getInstance()
                                .currentUser
                                ?.getIdToken(false)
                                ?.await()
                                ?.token
                        }.getOrNull()
                    }
                // Path-param safety — Ktor enforces [A-Za-z0-9_-]+.
                if (!sessionId.matches(Regex("[A-Za-z0-9_-]+")) ||
                    !callId.matches(Regex("[A-Za-z0-9_-]+"))
                ) {
                    Log.w(
                        TAG,
                        ">>> POLICY_AUTO_RESPONSE_REJECTED: sessionId/callId contains unsafe characters",
                    )
                    return@launch
                }
                val url = "$serverUrl/opencode/ask-response/$sessionId/$callId"
                val jsonBody = buildJsonBody("response" to response)
                withContext(Dispatchers.IO) {
                    val request =
                        Request
                            .Builder()
                            .url(url)
                            .post(jsonBody.toRequestBody(HttpClientProvider.JSON_MEDIA_TYPE))
                            .apply {
                                if (!token.isNullOrBlank()) {
                                    addHeader("Authorization", "Bearer $token")
                                }
                            }.build()
                    okHttpClient.newCall(request).execute().use { resp ->
                        Log.i(
                            TAG,
                            ">>> POLICY_AUTO_RESPONSE: call=$callId response.len=${response.length} -> ${resp.code}",
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, ">>> POLICY_AUTO_RESPONSE_ERROR: call=$callId", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnectLiveStream()
        wsScope.cancel()
    }

    companion object {
        private const val TAG = "AgentRuntimeVM"
    }
}
