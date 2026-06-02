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
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

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
                            val event = Json.decodeFromString<AgentEvent>(text)
                            if (event is AgentEvent.ApprovalRequested && !event.interactive) {
                                val decision = toolPermissionPolicy.decide(event.toolName)
                                when (decision) {
                                    ToolPermissionDecision.ALLOW -> {
                                        Log.i(TAG, ">>> POLICY_AUTO_APPROVE: tool=${event.toolName}")
                                        sendPluginAskResponse(
                                            sessionId = null,
                                            callId = event.toolId,
                                            response = "auto-approved by policy",
                                        )
                                        return
                                    }
                                    ToolPermissionDecision.DENY -> {
                                        Log.i(TAG, ">>> POLICY_AUTO_DENY: tool=${event.toolName}")
                                        sendPluginAskResponse(
                                            sessionId = null,
                                            callId = event.toolId,
                                            response = "denied by policy",
                                        )
                                        return
                                    }
                                    ToolPermissionDecision.DEFAULT -> { }
                                }
                            }
                            handleIncomingEvent(event)
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

    // translateToAgentEvent removed — client now receives canonical AgentEvent
    // JSON directly from the server (11-type protocol).

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
