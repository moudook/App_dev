package com.example.smarty.data.remote

import android.util.Base64
import android.util.Log
import com.example.smarty.BuildConfig
import com.example.smarty.features.chat.agent.AgentEventSink
import com.example.smarty.protocol.AgentEvent
import com.example.smarty.protocol.ClientEvent
import com.example.smarty.ui.components.ConnectionStatus
import com.google.firebase.auth.FirebaseAuth
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Safe `content` accessors on [JsonPrimitive] — return null on null primitives
 * or non-string values instead of throwing, so the timeline translator can
 * tolerate missing/optional plugin event fields without crashing the flow.
 */
private val JsonPrimitive.contentOrNullSafe: String?
    get() = runCatching { content }.getOrNull()
private val JsonPrimitive.booleanOrNullSafe: Boolean?
    get() = runCatching { content.toBooleanStrictOrNull() }.getOrNull()
private val JsonPrimitive.longOrNullSafe: Long?
    get() = runCatching { content.toLongOrNull() }.getOrNull()
private val JsonPrimitive.intOrNullSafe: Int?
    get() = runCatching { content.toIntOrNull() }.getOrNull()

/**
 * Client-side service that connects to the Cloud Agent's SSE stream.
 * Acts as the bridge between the Android app and the "Remote Brain".
 *
 * Handles Firebase authentication automatically for all requests.
 */
class RemoteAgentService(
    private val client: HttpClient,
    private val eventSink: AgentEventSink,
    private val serverUrlProvider: () -> String,
    private val deviceIdProvider: () -> String,
) {
    // Secondary constructor for fixed URL (Legacy/Test)
    constructor(client: HttpClient, eventSink: AgentEventSink, serverUrl: String) :
        this(client, eventSink, { serverUrl }, { "smarty-test-device" })

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

    /**
     * Decode an SSE event into the correct AgentEvent subclass.
     * The server sends the type in the SSE `event:` field, NOT in the JSON data.
     *
     * Self-healing: If decoding fails for any reason (malformed JSON, oversized payload,
     * unknown type), we return a synthetic Error event instead of crashing the stream.
     */
    private fun decodeAgentEvent(
        eventType: String,
        data: String,
    ): AgentEvent {
        return try {
            when (eventType) {
                "processing" -> json.decodeFromString<AgentEvent.Processing>(data)
                "tool_call" -> json.decodeFromString<AgentEvent.ToolCall>(data)
                "result" -> json.decodeFromString<AgentEvent.Result>(data)
                "error" -> json.decodeFromString<AgentEvent.Error>(data)
                "command" -> json.decodeFromString<AgentEvent.Command>(data)
                "state_sync" -> json.decodeFromString<AgentEvent.StateSync>(data)
                "tool_blocked" -> json.decodeFromString<AgentEvent.ToolBlocked>(data)
                "question" -> json.decodeFromString<AgentEvent.Question>(data)
                "note_block" -> json.decodeFromString<AgentEvent.NoteBlock>(data)
                "agent_step" -> json.decodeFromString<AgentEvent.AgentStep>(data)
                "opencode_raw" -> json.decodeFromString<AgentEvent.OpencodeRawEvent>(data)
                "approval_requested" -> json.decodeFromString<AgentEvent.ApprovalRequested>(data)
                "approval_granted" -> json.decodeFromString<AgentEvent.ApprovalGranted>(data)
                "approval_denied" -> json.decodeFromString<AgentEvent.ApprovalDenied>(data)
                else -> {
                    // Inject type discriminator if missing for the new canonical events
                    val jsonStr =
                        if (!data.contains("\"type\"")) {
                            data.trim().removeSuffix("}") + ",\"type\":\"$eventType\"}"
                        } else {
                            data
                        }
                    try {
                        json.decodeFromString<AgentEvent>(jsonStr)
                    } catch (e: Exception) {
                        Log.w(TAG, "Unknown SSE event type: '$eventType', falling back to Processing")
                        json.decodeFromString<AgentEvent.Processing>(data)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode '$eventType' event (data length: ${data.length}): ${e.message}")
            AgentEvent.Error(
                eventId = java.util.UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                message = "[Decode Error: $eventType] ${e.message?.take(200)}",
                code = "DECODE_ERROR",
            )
        }
    }

    private val _connectionState = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionState: StateFlow<ConnectionStatus> = _connectionState.asStateFlow()

    /**
     * Get Firebase ID token for authentication.
     * Returns null if user is not signed in.
     */
    private suspend fun getFirebaseToken(): String? {
        return try {
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null) {
                val tokenResult = user.getIdToken(false).await()
                tokenResult.token
            } else {
                Log.w(TAG, "No Firebase user signed in")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Firebase token: ${e.message}")
            null
        }
    }

    /**
     * Get a unique device identifier for security handshake.
     */
    private fun getDeviceId(): String {
        return try {
            deviceIdProvider()
        } catch (e: Exception) {
            "smarty-unknown"
        }
    }

    // Custom exception to exit the flow gracefully
    private class EndStreamException : Exception()

    /**
     * Send a query to the remote agent and process the event stream.
     * Returns a Flow of partial/final results (content chunks) to be displayed in the UI.
     *
     * Side effects (Commands, UI status updates) are dispatched to [eventSink].
     */
    fun sendQuery(
        query: String,
        provider: String? = null,
        providerUrl: String? = null,
        model: String? = null,
        variant: String? = null,
        sessionId: String? = null,
        personality: String? = null,
        messageId: String? = null,
    ): Flow<AgentEvent> =
        flow {
            val baseUrl = serverUrlProvider().replace("http://", "ws://").replace("https://", "wss://")
            val token = getFirebaseToken()

            val timezone = java.util.TimeZone.getDefault().id
            val clientTime = System.currentTimeMillis()

            val url =
                buildString {
                    append("$baseUrl/chat/ws")
                    append("?token=${token ?: ""}")
                    if (sessionId != null) append("&sessionId=${sessionId.encodeURLParameter()}")
                }

            Log.i(TAG, ">>> SEND_QUERY: query=${query.take(100)}, sessionId=$sessionId, model=$model, messageId=$messageId")
            Log.i(TAG, ">>> SEND_QUERY: url=$url")
            _connectionState.value = ConnectionStatus.CONNECTING

            try {
                client.webSocket(
                    urlString = url,
                    request = {
                        if (token != null) {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                        header("X-Smarty-Version", BuildConfig.VERSION_NAME)
                        header("X-Smarty-Device-Id", getDeviceId())
                    },
                ) {
                    _connectionState.value = ConnectionStatus.CONNECTED
                    Log.i(TAG, ">>> WS_CONNECTED: WebSocket connected successfully")
                    
                    // Send the query request frame to start the run
                    val requestObj = ChatQueryRequest(
                        query = query,
                        sessionId = sessionId,
                        provider = provider,
                        providerUrl = providerUrl,
                        model = model,
                        variant = variant,
                        timezone = timezone,
                        clientTime = clientTime,
                        personality = personality,
                        messageId = messageId,
                    )
                    
                    val requestJson = json.encodeToString(ChatQueryRequest.serializer(), requestObj)
                    send(Frame.Text(requestJson))
                    Log.i(TAG, ">>> WS_SENT: ChatQueryRequest sent (length=${requestJson.length})")

                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val data = frame.readText()
                                if (data.isBlank()) {
                                    Log.d(TAG, ">>> WS_RECEIVED: blank frame, skipping")
                                    continue
                                }
                                try {
                                    Log.d(TAG, ">>> WS_RECEIVED: length=${data.length}, preview=${data.take(200)}")
                        val jsonElement = json.parseToJsonElement(data).jsonObject
                                    val eventType = jsonElement["type"]?.jsonPrimitive?.content ?: "processing"
                                    
                                    // Skip server keepalive pings
                                    if (eventType == "ping") continue
                                    
                                    Log.d(TAG, ">>> WS_DECODE: eventType=$eventType")
                                    val agentEvent = decodeAgentEvent(eventType, data)
                                    Log.d(TAG, ">>> WS_DECODED: ${agentEvent::class.simpleName}")
                                    val shouldStop = handleEvent(agentEvent, this@flow)
                                    if (shouldStop) {
                                        Log.i(TAG, ">>> WS_STOP: handleEvent returned true, stopping stream")
                                        throw EndStreamException()
                                    }
                                } catch (e: Exception) {
                                    if (e is EndStreamException) throw e
                                    Log.e(TAG, "Failed to process WS event (length: ${data.length}): ${e.message}", e)
                                }
                            }
                        }
                    } catch (e: EndStreamException) {
                        Log.i(TAG, ">>> WS_COMPLETE: Stream completed normally")
                    }
                }
                if (_connectionState.value != ConnectionStatus.OFFLINE) {
                    _connectionState.value = ConnectionStatus.DISCONNECTED
                    Log.i(TAG, ">>> WS_DISCONNECTED: WebSocket closed normally")
                }
            } catch (e: Exception) {
                Log.e(TAG, "WS connection failed: ${e.message}", e)
                _connectionState.value = ConnectionStatus.OFFLINE
                emit(
                    AgentEvent.Error(
                        eventId = java.util.UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        message = "[Connection Error: ${e.message}]",
                        code = "CONNECTION_ERROR",
                    ),
                )
            }
        }

    /**
     * Send a client event (e.g., tool result, app state) back to the remote agent.
     */
    suspend fun sendEvent(
        sessionId: String,
        event: ClientEvent,
    ) {
        try {
            Log.d(TAG, "Sending client event: $event")
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            val response =
                client.post("$baseUrl/chat/events") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    parameter("sessionId", sessionId)
                    contentType(ContentType.Application.Json)
                    setBody(event)
                }
            Log.d(TAG, "Event sent successfully: ${response.status}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send client event", e)
        }
    }

    /**
     * Open the live timeline WebSocket to Ktor's `/ws/timeline` endpoint.
     * The Ktor server is forwarding every event the OpenCode plugin emits
     * (tool calls, reasoning deltas, sub-agents, MCP `ask_user` gates, etc.).
     *
     * The raw plugin JSON is translated into canonical [AgentEvent] types and
     * emitted into a [Flow] that callers can collect. The same translation
     * logic is shared with [AgentRuntimeViewModel] — but here the events flow
     * into the MAIN chat surface, so reasoning, sub-agents, web search results,
     * and approval gates appear inline in the conversation.
     *
     * Auto-reconnects with a 2-second backoff on disconnect. Callers should
     * cancel the collecting [Job] to stop the stream.
     *
     * End-to-end wire model:
     *   OpenCode CLI → plugin → Ktor /opencode/events → Ktor /ws/timeline → this Flow
     */
    fun observeTimelineEvents(): Flow<AgentEvent> =
        flow {
            val baseUrl = serverUrlProvider().replace("http://", "ws://").replace("https://", "wss://")
            val token = getFirebaseToken()
            val url =
                buildString {
                    append("$baseUrl/ws/timeline")
                    if (!token.isNullOrBlank()) {
                        append("?token=$token")
                    }
                }

            Log.i(TAG, ">>> TIMELINE_WS: connecting to $url")
            _connectionState.value = ConnectionStatus.CONNECTING

            // Translation function — converts a raw plugin JSON frame to an
            // AgentEvent. Lives inside the flow so the json instance is captured
            // by closure without leaking `this`.
            val translate = translateTimelineEvent

            try {
                client.webSocket(urlString = url) {
                    _connectionState.value = ConnectionStatus.CONNECTED
                    Log.i(TAG, ">>> TIMELINE_WS: connected")

                    // Heartbeat — Ktor's WS expects a periodic ping or it
                    // tears the connection after ~30s idle.
                    val heartbeatJob =
                        launch {
                            while (isActive) {
                                kotlinx.coroutines.delay(15_000L)
                                try {
                                    send(Frame.Text("""{"type":"ping"}"""))
                                } catch (e: Exception) {
                                    break
                                }
                            }
                        }

                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val text = frame.readText()
                            if (text.isBlank()) continue
                            try {
                                val event = translate(text)
                                if (event != null) {
                                    emit(event)
                                }
                            } catch (e: kotlinx.serialization.SerializationException) {
                                Log.w(TAG, "Timeline event decode failed: ${e.message}")
                            } catch (e: Exception) {
                                Log.w(TAG, "Timeline event parse failed: ${e.message}")
                            }
                        }
                    } finally {
                        heartbeatJob.cancel()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Timeline WS failed: ${e.message}", e)
                _connectionState.value = ConnectionStatus.OFFLINE
            }
        }

    /**
     * Translate a raw plugin JSON frame (as sent over the Ktor `/ws/timeline`
     * WebSocket) into a canonical [AgentEvent] that the rest of the app
     * understands.
     *
     * Plugin event format (from timeline-bridge.ts):
     *   { "kind": "user.input.required", "sessionID": "...", "tool": "ask_user",
     *     "callID": "...", "question": "...", "ts": 12345 }
     *   { "kind": "part.updated", "partType": "tool", "tool": "websearch",
     *     "state": "complete", "toolCallID": "...", "input": {...}, "output": [...] }
     *   { "kind": "part.updated", "partType": "reasoning", "reasoning": "..." }
     *   { "kind": "part.updated", "partType": "text", "text": "..." }
     *   { "kind": "part.updated", "partType": "subtask", "agent": "researcher",
     *     "description": "...", "state": "running" }
     *   { "kind": "tool.before", "tool": "bash", "callID": "...", "args": {...} }
     *   { "kind": "tool.after",  "tool": "bash", "callID": "...", "result": ... }
     *   { "kind": "permission.asked", "tool": "bash", "args": {...} }
     *   { "kind": "permission.replied", "tool": "bash", "granted": true }
     *   { "kind": "session.created" | "session.idle" | "session.error" | "session.compacted" }
     *   { "kind": "compaction.start" }
     */
    private val translateTimelineEvent: (String) -> AgentEvent? = translate@{ text ->
        try {
            val obj = json.parseToJsonElement(text).jsonObject
            val kind = obj["kind"]?.jsonPrimitive?.contentOrNullSafe ?: return@translate null
            val sessionID = obj["sessionID"]?.jsonPrimitive?.contentOrNullSafe
            val ts = obj["ts"]?.jsonPrimitive?.longOrNullSafe ?: System.currentTimeMillis()
            val eventId = java.util.UUID.randomUUID().toString()

            when (kind) {
                "session.created" -> AgentEvent.SessionStarted(eventId, ts, sessionID ?: "unknown")
                "session.idle" -> AgentEvent.SessionCompleted(eventId, ts)
                "session.error" -> AgentEvent.SessionError(
                    eventId = eventId,
                    timestamp = ts,
                    message = obj["error"]?.jsonPrimitive?.contentOrNullSafe ?: "Unknown error",
                )
                "session.compacted", "compaction.start" -> AgentEvent.RecoveryStarted(
                    eventId = eventId,
                    timestamp = ts,
                    reason = "Context compressed",
                )

                "permission.asked", "user.input.required" -> {
                    val rawTool = (obj["tool"]?.jsonPrimitive?.contentOrNullSafe ?: "ask_user").lowercase()
                    val tool = when (rawTool) {
                        "ask_user", "askuser", "ask", "input", "confirm", "question", "clarify" -> "ask_user"
                        else -> rawTool
                    }
                    val callId = obj["callID"]?.jsonPrimitive?.contentOrNullSafe ?: eventId
                    val question = obj["question"]?.jsonPrimitive?.contentOrNullSafe.orEmpty()
                    val optionsArr = obj["options"] as? kotlinx.serialization.json.JsonArray
                    val optionsStrings = optionsArr?.mapNotNull { el ->
                        (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafe
                    }.orEmpty()
                    val isInteractive = tool == "ask_user"
                    val toolArgs = when {
                        isInteractive && question.isNotEmpty() -> {
                            // Emit a `questions` array (matching the Ktor MCP
                            // ask_user schema) so `AssistOverlayScreen` can
                            // parse it into `ClarificationRequest` with
                            // option chips. Falls back to a single
                            // `question` field if `questions` parsing fails.
                            buildString {
                                append("{\"questions\":[{")
                                append("\"question\":")
                                append(json.encodeToString(JsonPrimitive(question)))
                                append(",\"allow_custom\":true")
                                if (optionsStrings.isNotEmpty()) {
                                    append(",\"options\":")
                                    append(
                                        json.encodeToString(
                                            ListSerializer(String.serializer()),
                                            optionsStrings,
                                        ),
                                    )
                                }
                                append("}]}")
                            }
                        }
                        isInteractive -> obj["args"]?.toString() ?: "{}"
                        else -> obj["args"]?.toString() ?: "{}"
                    }
                    AgentEvent.ApprovalRequested(
                        eventId = eventId,
                        timestamp = ts,
                        toolId = callId,
                        toolName = tool,
                        toolTitle = tool.replace('_', ' ').replaceFirstChar { it.uppercase() },
                        toolArgs = toolArgs,
                        sessionId = sessionID,
                        isInteractive = isInteractive,
                    )
                }

                "permission.replied" -> {
                    val granted = obj["granted"]?.jsonPrimitive?.booleanOrNullSafe ?: false
                    val callId = obj["toolId"]?.jsonPrimitive?.contentOrNullSafe
                        ?: obj["callID"]?.jsonPrimitive?.contentOrNullSafe
                        ?: eventId
                    if (granted) AgentEvent.ApprovalGranted(eventId, ts, callId)
                    else AgentEvent.ApprovalDenied(eventId, ts, callId)
                }

                "part.updated" -> {
                    val partType = obj["partType"]?.jsonPrimitive?.contentOrNullSafe
                    val partID = obj["partID"]?.jsonPrimitive?.contentOrNullSafe ?: eventId
                    val messageID = obj["messageID"]?.jsonPrimitive?.contentOrNullSafe ?: sessionID ?: eventId
                    when (partType) {
                        "reasoning" -> {
                            val text = obj["reasoning"]?.jsonPrimitive?.contentOrNullSafe.orEmpty()
                            if (text.isNotEmpty()) {
                                AgentEvent.ReasoningDelta(eventId, ts, text)
                            } else {
                                AgentEvent.ReasoningStarted(eventId, ts)
                            }
                        }
                        "text" -> {
                            val text = obj["text"]?.jsonPrimitive?.contentOrNullSafe.orEmpty()
                            if (text.isNotEmpty()) AgentEvent.Processing(eventId, ts, text) else null
                        }
                        "tool" -> {
                            val tool = obj["tool"]?.jsonPrimitive?.contentOrNullSafe ?: "tool"
                            val state = obj["state"]?.jsonPrimitive?.contentOrNullSafe ?: "running"
                            val toolCallID = obj["toolCallID"]?.jsonPrimitive?.contentOrNullSafe ?: partID
                            val inputJson = obj["input"]?.toString()
                            val outputJson = obj["output"]?.toString()
                            when (state) {
                                "running", "pending" -> AgentEvent.ToolCallStarted(
                                    eventId = eventId,
                                    timestamp = ts,
                                    toolId = toolCallID,
                                    name = tool,
                                    source = "opencode",
                                )
                                "complete", "completed" -> {
                                    // Also fire ToolCallOutput if we have result data
                                    val result = if (outputJson != null) {
                                        AgentEvent.ToolCallOutput(eventId, ts, toolCallID, outputJson)
                                    } else null
                                    val finished = AgentEvent.ToolCallFinished(
                                        eventId = eventId,
                                        timestamp = ts,
                                        toolId = toolCallID,
                                        durationMs = 0L,
                                    )
                                    // Return the first event; ChatFeatureManager will
                                    // accumulate both via the rawEvents list.
                                    result ?: finished
                                }
                                "error" -> AgentEvent.ToolBlocked(
                                    eventId = eventId,
                                    timestamp = ts,
                                    toolName = tool,
                                    reason = obj["error"]?.jsonPrimitive?.contentOrNullSafe ?: "Tool failed",
                                )
                                else -> null
                            }
                        }
                        "step-start" -> AgentEvent.StepStarted(
                            eventId = eventId,
                            timestamp = ts,
                            title = "Step ${obj["step"]?.jsonPrimitive?.intOrNullSafe ?: ""}",
                        )
                        "step-finish" -> AgentEvent.StepFinished(eventId, ts, success = true)
                        "subtask" -> {
                            val agent = obj["agent"]?.jsonPrimitive?.contentOrNullSafe ?: "subagent"
                            val desc = obj["description"]?.jsonPrimitive?.contentOrNullSafe.orEmpty()
                            val stt = obj["state"]?.jsonPrimitive?.contentOrNullSafe ?: "running"
                            AgentEvent.AgentStep(
                                eventId = eventId,
                                timestamp = ts,
                                stepIndex = ts.toInt(),
                                stepType = "tool_call",
                                stepTitle = "Sub-agent: $agent",
                                stepContent = desc,
                                stepStatus = stt,
                                toolName = "subtask",
                                subagentId = agent,
                            )
                        }
                        "compaction" -> AgentEvent.RecoveryStarted(eventId, ts, "Context compressed")
                        "file" -> null // Files are visible in the chat as attachments; no timeline node
                        else -> null
                    }
                }

                "tool.before" -> {
                    val tool = obj["tool"]?.jsonPrimitive?.contentOrNullSafe ?: "tool"
                    val callId = obj["callID"]?.jsonPrimitive?.contentOrNullSafe ?: eventId
                    val args = obj["args"]?.toString().orEmpty()
                    AgentEvent.ToolCallStarted(
                        eventId = eventId,
                        timestamp = ts,
                        toolId = callId,
                        name = tool,
                        source = "opencode",
                    )
                }

                "tool.after" -> {
                    val tool = obj["tool"]?.jsonPrimitive?.contentOrNullSafe ?: "tool"
                    val callId = obj["callID"]?.jsonPrimitive?.contentOrNullSafe ?: eventId
                    val result = obj["result"]?.toString().orEmpty()
                    AgentEvent.ToolCallCompleted(
                        eventId = eventId,
                        timestamp = ts,
                        toolId = callId,
                        result = result,
                        durationMs = 0L,
                    )
                }

                "message.part.delta" -> {
                    val delta = obj["delta"]?.jsonPrimitive?.contentOrNullSafe.orEmpty()
                    if (delta.isNotEmpty()) {
                        AgentEvent.Processing(eventId, ts, delta)
                    } else {
                        null
                    }
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "translateTimelineEvent failed: ${e.message}")
            null
        }
    }

    /**
     * Send the user's approval/denial decision back to the paused server agent.
     * The server resumes the TLSM stream and continues tool execution (or aborts it).
     *
     * @param toolId  The unique approval gate ID the server sent in ApprovalRequested
     * @param approved true = Approve,  false = Deny
     * @param feedback free-text rationale sent to the agent as tool output
     */
    suspend fun sendApproval(
        toolId: String,
        approved: Boolean,
        feedback: String? = null,
    ) {
        Log.i(TAG, ">>> SEND_APPROVAL: toolId=$toolId, approved=$approved, feedback=${feedback?.take(100)}")
        try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            val response =
                client.post("$baseUrl/api/v1/chat/events/approval") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(ApprovalRequest(toolId, approved, feedback))
                }

            if (response.status.isSuccess()) {
                Log.i(TAG, ">>> SEND_APPROVAL_OK: $approved for $toolId (${response.status})")
            } else {
                Log.e(TAG, ">>> SEND_APPROVAL_FAILED: ${response.status}")
            }
        } catch (e: Exception) {
            Log.e(TAG, ">>> SEND_APPROVAL_ERROR: $toolId", e)
        }
    }

    /**
     * Deliver the user's response to a plugin-driven MCP `ask` tool call.
     *
     * When the OpenCode CLI plugin emits `user.input.required` over
     * `/ws/timeline` (origin = `ApprovalSource.Plugin`), the corresponding
     * tool is blocked in the plugin and is polling
     *   `/tmp/opencode-asks/<sessionID>/<callID>.response.txt`
     * on disk. The Ktor `/opencode/ask-response/{sessionId}/{callId}` route
     * writes the response to that file and unblocks the tool.
     *
     * Path param characters are restricted to the same `[A-Za-z0-9_-]+` set
     * enforced server-side, so any rogue values are rejected with a clear
     * 400 instead of smuggling through URL slashes.
     *
     * @param sessionId OpenCode session ID — required so the file is written
     *                  to the right per-session directory.
     * @param callId    Plugin `callID` from the `user.input.required` event.
     * @param response  The user's text reply.
     * @return `true` if Ktor accepted the response (200 OK), `false` on any
     *         transport or HTTP error.
     */
    suspend fun sendPluginAskResponse(
        sessionId: String,
        callId: String,
        response: String,
    ): Boolean {
        Log.i(
            TAG,
            ">>> SEND_PLUGIN_ASK_RESPONSE: session=$sessionId call=$callId response.len=${response.length}",
        )
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()
            val safePathRegex = Regex("[A-Za-z0-9_-]+")
            if (!sessionId.matches(safePathRegex) || !callId.matches(safePathRegex)) {
                Log.e(
                    TAG,
                    ">>> SEND_PLUGIN_ASK_RESPONSE_REJECTED: sessionId/callId contains unsafe characters",
                )
                return false
            }

            val httpResponse =
                client.post("$baseUrl/opencode/ask-response/$sessionId/$callId") {
                    if (!token.isNullOrBlank()) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(buildString {
                        append("{\"response\":")
                        append(json.encodeToString(JsonPrimitive(response)))
                        append("}")
                    })
                }

            if (httpResponse.status.isSuccess()) {
                Log.i(
                    TAG,
                    ">>> SEND_PLUGIN_ASK_RESPONSE_OK: session=$sessionId call=$callId (${httpResponse.status})",
                )
                true
            } else {
                Log.e(
                    TAG,
                    ">>> SEND_PLUGIN_ASK_RESPONSE_FAILED: session=$sessionId call=$callId status=${httpResponse.status}",
                )
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, ">>> SEND_PLUGIN_ASK_RESPONSE_ERROR: session=$sessionId call=$callId", e)
            false
        }
    }

    /**
     * Delete a chat session from the server (Supabase sync).
     */
    suspend fun deleteChatSession(sessionId: String): Boolean {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return false

            val response =
                client.delete("$baseUrl/api/v1/chat/sessions/$sessionId") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            response.status.isSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete remote chat session", e)
            false
        }
    }

    /**
     * Delete a message and all messages after it.
     * Used for Edit & Resend feature.
     */
    suspend fun deleteMessageAndAfter(messageId: String): Int {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return 0

            val response =
                client.delete("$baseUrl/chat/messages/$messageId/and-after") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                try {
                    val jsonMap = com.google.gson.JsonParser.parseString(body).asJsonObject
                    jsonMap.get("deletedCount")?.asInt ?: 0
                } catch (e: Exception) {
                    0
                }
            } else {
                0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete messages", e)
            0
        }
    }

    /**
     * Fetch available OpenCode models from the server, including variant info.
     * Returns list of ModelInfo with id, label, and available variants.
     * Falls back to cached models if server call fails.
     */
    suspend fun getOpencodeModels(refresh: Boolean = false): List<ModelInfo> {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            Log.d(TAG, "Fetching opencode models: refresh=$refresh, url=$baseUrl/api/v1/opencode/models")

            val response =
                client.get("$baseUrl/api/v1/opencode/models") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    parameter("refresh", refresh)
                }

            Log.d(TAG, "Models API response status: ${response.status}")

            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                Log.d(TAG, "Models API response body (first 500 chars): ${body.take(500)}")

                val jsonObject = com.google.gson.JsonParser.parseString(body).asJsonObject

                // Check if models array exists
                if (!jsonObject.has("models")) {
                    Log.e(TAG, "Response missing 'models' field: ${body.take(200)}")
                    return emptyList()
                }

                val modelsArray = jsonObject.getAsJsonArray("models")
                Log.d(TAG, "Models array size: ${modelsArray.size()}")

                val resultList = mutableListOf<ModelInfo>()
                for (i in 0 until modelsArray.size()) {
                    val element = modelsArray.get(i)
                    if (element.isJsonObject) {
                        val mObj = element.asJsonObject
                        val id = mObj.get("id")?.asString
                        val label = mObj.get("label")?.asString

                        if (id != null && label != null) {
                            val variants = mutableListOf<String>()
                            val variantsObj = mObj.getAsJsonObject("variants")
                            if (variantsObj != null) {
                                variants.addAll(variantsObj.keySet())
                            }
                            resultList.add(ModelInfo(id = id, label = label, variants = variants))
                            Log.d(TAG, "  Model[$i]: $id -> $label, variants=$variants")
                        } else {
                            Log.w(TAG, "  Model[$i] missing id or label: $element")
                        }
                    } else {
                        Log.w(TAG, "  Model[$i] is not a JSON object: $element")
                    }
                }

                Log.d(TAG, "Successfully parsed ${resultList.size} models from server")
                resultList
            } else {
                Log.e(TAG, "Failed to fetch opencode models: ${response.status}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching opencode models: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Legacy variant — returns plain (modelId, label) pairs for backward compat.
     */
    suspend fun getOpencodeModelPairs(refresh: Boolean = false): List<Pair<String, String>> {
        return getOpencodeModels(refresh).map { it.id to it.label }
    }

    /**
     * Analyze content on the server.
     */
    suspend fun analyzeContent(
        content: String,
        attachments: List<AttachmentInfo>? = null,
    ): AIResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            val response =
                client.post("$baseUrl/analyze/content") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(ContentAnalysisRequest(content, attachments))
                }

            if (response.status.isSuccess()) {
                response.body<AIResponse>()
            } else {
                Log.e(TAG, "Content analysis failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Content analysis error: ${e.message}", e)
            null
        }
    }

    /**
     * Analyze a document on the server.
     */
    suspend fun analyzeDocument(
        text: String,
        fileName: String? = null,
        userContext: String? = null,
    ): DocumentAnalysisResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            val response =
                client.post("$baseUrl/analyze/document") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(DocumentAnalysisRequest(text, fileName, userContext))
                }

            if (response.status.isSuccess()) {
                response.body<DocumentAnalysisResponse>()
            } else {
                Log.e(TAG, "Document analysis failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Document analysis error: ${e.message}", e)
            null
        }
    }

    /**
     * Process an image with OCR on the server.
     */
    suspend fun processImage(
        imageBytes: ByteArray,
        mimeType: String,
        analysisType: String = "ocr",
    ): ImageProcessingResult? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

            val response =
                client.post("$baseUrl/process/image") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(ImageProcessingRequest(base64Image, mimeType, analysisType))
                }

            if (response.status.isSuccess()) {
                response.body<ImageProcessingResult>()
            } else {
                Log.e(TAG, "Image processing failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image processing error: ${e.message}", e)
            null
        }
    }

    /**
     * Process a PDF on the server.
     */
    suspend fun processPdf(
        pdfBytes: ByteArray,
        fileName: String? = null,
        useOcr: Boolean = true,
    ): PdfProcessingResult? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()
            val base64Pdf = Base64.encodeToString(pdfBytes, Base64.NO_WRAP)

            val response =
                client.post("$baseUrl/process/pdf") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(PdfProcessingRequest(base64Pdf, fileName, useOcr))
                }

            if (response.status.isSuccess()) {
                response.body<PdfProcessingResult>()
            } else {
                Log.e(TAG, "PDF processing failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "PDF processing error: ${e.message}", e)
            null
        }
    }

    /**
     * Upload a file using the direct-to-Drive Signed URL approach.
     */
    suspend fun uploadFile(
        fileBytes: ByteArray,
        fileName: String,
        contentType: String,
        analysisType: String = "content",
    ): String? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            // 1. Get Signed URL from Ktor
            val urlResponse = client.post("$baseUrl/files/upload-url") {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(mapOf("fileName" to fileName, "mimeType" to contentType))
            }

            if (!urlResponse.status.isSuccess()) {
                Log.e(TAG, "Failed to get upload URL: ${urlResponse.status}")
                return null
            }

            val uploadUrl = try {
                val jsonBody = com.google.gson.JsonParser.parseString(urlResponse.bodyAsText()).asJsonObject
                jsonBody.get("uploadUrl")?.asString
            } catch (e: Exception) { null }

            if (uploadUrl == null) {
                Log.e(TAG, "Missing uploadUrl in response")
                return null
            }

            // 2. Upload directly to Google Drive via PUT
            val uploadResponse = client.put(uploadUrl) {
                // Do NOT send Firebase token here, this goes directly to Google APIs
                setBody(fileBytes)
            }

            if (uploadResponse.status.isSuccess()) {
                // Google Drive returns the File resource metadata
                try {
                    val fileMeta = com.google.gson.JsonParser.parseString(uploadResponse.bodyAsText()).asJsonObject
                    val fileId = fileMeta.get("id")?.asString
                    return fileId ?: uploadUrl // fallback to URL if ID parsing fails
                } catch (e: Exception) {
                    return uploadUrl
                }
            } else {
                Log.e(TAG, "Direct to Drive upload failed: ${uploadResponse.status}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "File upload error: ${e.message}", e)
            return null
        }
    }

    /**
     * Download a file using the direct-to-Drive Signed URL approach.
     */
    suspend fun downloadFile(fileId: String): ByteArray? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            // 1. Get Signed Download URL from Ktor
            val urlResponse = client.get("$baseUrl/files/download-url/$fileId") {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            }

            if (!urlResponse.status.isSuccess()) {
                Log.e(TAG, "Failed to get download URL: ${urlResponse.status}")
                return null
            }

            val downloadUrl = try {
                val jsonBody = com.google.gson.JsonParser.parseString(urlResponse.bodyAsText()).asJsonObject
                jsonBody.get("downloadUrl")?.asString
            } catch (e: Exception) { null }

            if (downloadUrl == null) {
                Log.e(TAG, "Missing downloadUrl in response")
                return null
            }

            // 2. Download directly from Google Drive
            val downloadResponse = client.get(downloadUrl)
            
            if (downloadResponse.status.isSuccess()) {
                downloadResponse.readRawBytes()
            } else {
                Log.e(TAG, "Direct from Drive download failed: ${downloadResponse.status}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "File download error: ${e.message}", e)
            return null
        }
    }

    /**
     * Test connection to the server.
     */
    suspend fun testConnection(): Boolean {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            val response =
                client.get("$baseUrl/health") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                }

            val success = response.status.isSuccess()
            Log.i(TAG, "Connection test: ${if (success) "SUCCESS" else "FAILED"}")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed: ${e.message}")
            false
        }
    }

    /**
     * Send a query with optional file context (for large payloads like PDF text).
     * Uses POST /chat/query endpoint which can handle larger bodies.
     *
     * @param query The user's query
     * @param sessionId The chat session ID (optional, server will create one if not provided)
     * @param fileContext Extracted text from uploaded files (e.g., PDF text, OCR text)
     * @param attachments List of attachment metadata
     * @param provider AI provider strategy
     * @param model Specific model to use
     *
     * @return Flow of response content chunks
     */
    fun sendQueryWithContext(
        query: String,
        sessionId: String? = null,
        fileContext: String? = null,
        attachments: List<ChatAttachment>? = null,
        provider: String? = null,
        model: String? = null,
        variant: String? = null,
        personality: String? = null, // Fix: Add personality parameter
    ): Flow<String> =
        flow {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            Log.d(TAG, "Sending query with context: hasFileContext=${!fileContext.isNullOrBlank()}, attachments=${attachments?.size ?: 0}")
            _connectionState.value = ConnectionStatus.CONNECTING

            try {
                val timezone = java.util.TimeZone.getDefault().id
                val clientTime = System.currentTimeMillis()

                val request =
                    ChatQueryRequest(
                        query = query,
                        sessionId = sessionId,
                        provider = provider,
                        model = model,
                        variant = variant,
                        fileContext = fileContext,
                        attachments =
                            attachments?.map {
                                ChatQueryAttachment(type = it.type, name = it.name, mimeType = it.mimeType)
                            },
                        timezone = timezone,
                        clientTime = clientTime,
                        personality = personality, // Fix: Include personality in POST request
                    )

                val response =
                    client.post("$baseUrl/chat/query") {
                        if (token != null) {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                        header("X-Smarty-Version", BuildConfig.VERSION_NAME)
                        header("X-Smarty-Device-Id", getDeviceId())
                        contentType(ContentType.Application.Json)
                        setBody(request)
                    }

                _connectionState.value = ConnectionStatus.CONNECTED

                if (response.status.isSuccess()) {
                    val result = response.body<ChatQueryResponse>()

                    result.events.forEach { eventJson ->
                        try {
                            val event = json.decodeFromString<AgentEvent>(eventJson)
                            val shouldStop = handleStringEvent(event, this@flow)
                            if (shouldStop) {
                                // Do nothing for non flow-collecting stream
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse event: $eventJson", e)
                        }
                    }

                    if (result.response.isNotBlank()) {
                        emit(result.response)
                    }
                } else {
                    val errorBody = response.bodyAsText()
                    Log.e(TAG, "Chat query failed: ${response.status} - $errorBody")
                    emit("\n[Error: ${response.status}]")
                }
                if (_connectionState.value != ConnectionStatus.OFFLINE) {
                    _connectionState.value = ConnectionStatus.DISCONNECTED
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chat query error: ${e.message}", e)
                _connectionState.value = ConnectionStatus.OFFLINE
                emit("\n[Connection Error: ${e.message}]")
            }
        }

    /**
     * Generate a daily briefing.
     */
    suspend fun generateBriefing(prompt: String): String? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            val response =
                client.post("$baseUrl/briefing/generate") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(BriefingRequest(prompt))
                }

            if (response.status.isSuccess()) {
                val result = response.body<BriefingResponse>()
                result.briefing
            } else {
                Log.e(TAG, "Briefing generation failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Briefing generation error: ${e.message}", e)
            null
        }
    }

    /**
     * Workflow B: Direct Image Generation.
     * Bypasses the Agent and generates an image directly on the server.
     */
    suspend fun generateImageDirect(
        prompt: String,
        aspectRatio: String = "1:1",
    ): DirectImageGenerationResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            val response =
                client.post("$baseUrl/api/v1/image/direct") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(DirectImageGenerationRequest(prompt, aspectRatio))
                }

            // Both success (200) and error (500/503) responses use the same schema
            val body = response.body<DirectImageGenerationResponse>()
            body
        } catch (e: Exception) {
            Log.e(TAG, "Direct image generation error: ${e.message}", e)
            null
        }
    }

    /**
     * Get current authenticated user info.
     */
    fun getCurrentUser(): UserInfo? {
        val user = FirebaseAuth.getInstance().currentUser ?: return null
        return UserInfo(
            userId = user.uid,
            email = user.email,
            displayName = user.displayName,
        )
    }

    /**
     * Handles events streaming from the SSE endpoint and emits raw string chunks
     */
    private suspend fun handleStringEvent(
        event: AgentEvent,
        flowCollector: kotlinx.coroutines.flow.FlowCollector<String>,
    ): Boolean {
        return when (event) {
            is AgentEvent.Processing -> {
                if (!event.content.isNullOrEmpty()) {
                    flowCollector.emit(event.content)
                }
                false
            }
            is AgentEvent.ToolCall -> {
                eventSink.onToolExecutionStarted(event.toolName, event.displayName)
                if (event.status == "completed") {
                    eventSink.onToolExecutionCompleted(event.toolName)
                }
                false
            }
            is AgentEvent.Command -> {
                Log.d(TAG, "Received remote command: ${event.command}")
                eventSink.emit(event.command)
                false
            }
            is AgentEvent.Result -> {
                if (event.content.isNotEmpty()) {
                    flowCollector.emit(event.content)
                }
                event.isFinal
            }
            is AgentEvent.Error -> {
                Log.e(TAG, "Remote Agent Error: ${event.message}")
                flowCollector.emit("\n[Error: ${event.message}]")
                true // Stop stream on error
            }
            is AgentEvent.StateSync -> {
                Log.d(TAG, "Received state sync: ${event.syncType}")
                eventSink.onStateSync(event.syncType, event.data)
                false
            }
            is AgentEvent.ToolBlocked -> {
                Log.w(TAG, "Tool blocked: ${event.toolName} - ${event.reason}")
                // Don't stop stream, just log and continue
                false
            }
            is AgentEvent.Question -> {
                Log.d(TAG, "Received question: ${event.question}")
                // Question events from POST endpoint - notify via eventSink if possible
                // Note: Full Question handling requires architectural changes to pass structured events
                false
            }
            is AgentEvent.NoteBlock -> {
                Log.d(TAG, "Received note block: ${event.noteId} - ${event.title}")
                // NoteBlock events from POST endpoint - notify via eventSink if possible
                // Note: Full NoteBlock handling requires architectural changes to pass structured events
                false
            }
            is AgentEvent.AgentStep -> {
                Log.d(TAG, "Agent step: ${event.stepType} - ${event.stepTitle} (${event.stepStatus})")
                false
            }
            else -> false // Handle all other canonical events seamlessly
        }
    }

    /**
     * Handles events streaming from the SSE endpoint and emits whole AgentEvents
     */
    private suspend fun handleEvent(
        event: AgentEvent,
        flowCollector: kotlinx.coroutines.flow.FlowCollector<AgentEvent>,
    ): Boolean {
        Log.d(TAG, ">>> HANDLE_EVENT: ${event::class.simpleName}")
        return when (event) {
            is AgentEvent.Processing -> {
                flowCollector.emit(event)
                false
            }
            is AgentEvent.ToolCall -> {
                eventSink.onToolExecutionStarted(event.toolName, event.displayName)
                if (event.status == "completed") {
                    eventSink.onToolExecutionCompleted(event.toolName)
                }
                // Emit the ToolCall event into the flow so AssistViewModel.processRemoteQuery()
                // can accumulate it in pendingToolCalls and display the result (including image URLs).
                flowCollector.emit(event)
                false
            }
            is AgentEvent.Command -> {
                Log.d(TAG, "Received remote command: ${event.command}")
                eventSink.emit(event.command)
                false
            }
            is AgentEvent.Result -> {
                flowCollector.emit(event)
                event.isFinal
            }
            is AgentEvent.Error -> {
                Log.e(TAG, "Remote Agent Error: ${event.message}")
                flowCollector.emit(event)
                true // Stop stream on error
            }
            is AgentEvent.StateSync -> {
                Log.d(TAG, "Received state sync: ${event.syncType}")
                eventSink.onStateSync(event.syncType, event.data)
                false
            }
            is AgentEvent.ToolBlocked -> {
                Log.w(TAG, "Tool blocked: ${event.toolName} - ${event.reason}")
                flowCollector.emit(event)
                false
            }
            is AgentEvent.Question -> {
                Log.d(TAG, "Received question: ${event.question}")
                flowCollector.emit(event)
                false
            }
            is AgentEvent.NoteBlock -> {
                Log.d(TAG, "Received note block: ${event.noteId} - ${event.title}")
                flowCollector.emit(event)
                false
            }
            is AgentEvent.AgentStep -> {
                Log.d(TAG, "Agent step: ${event.stepType} - ${event.stepTitle} (${event.stepStatus})")
                flowCollector.emit(event)
                false
            }
            is AgentEvent.ApprovalRequested -> {
                Log.d(TAG, "Received approval_requested for tool: ${event.toolName}")
                flowCollector.emit(event)
                false
            }
            else -> {
                flowCollector.emit(event)
                false
            }
        }
    }

    /**
     * Perform a security/capability handshake with the remote agent.
     */
    suspend fun performHandshake(request: com.example.smarty.protocol.HandshakeRequest): com.example.smarty.protocol.HandshakeResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            Log.d(TAG, "Initiating handshake with Cloud Agent: $baseUrl")

            val response =
                client.post("$baseUrl/api/v1/session/init") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    // Add security handshake headers
                    header("X-Smarty-Version", BuildConfig.VERSION_NAME)
                    header("X-Smarty-Device-Id", getDeviceId())
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            if (response.status.isSuccess()) {
                val handshakeResponse = response.body<com.example.smarty.protocol.HandshakeResponse>()
                Log.i(TAG, "Handshake successful. Session ID: ${handshakeResponse.sessionId}")
                handshakeResponse
            } else {
                Log.e(TAG, "Handshake failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Handshake error: ${e.message}", e)
            null
        }
    }

    /**
     * Interrupt ongoing agent execution for a session.
     * Used to stop deep research or long-running tasks.
     */
    suspend fun interruptSession(sessionId: String): InterruptResponse {
        val baseUrl = serverUrlProvider()
        val token = getFirebaseToken()

        return try {
            val response =
                client.post("$baseUrl/chat/interrupt") {
                    contentType(ContentType.Application.Json)
                    bearerAuth(token ?: "")
                    setBody(InterruptRequest(sessionId))
                }
            if (response.status.isSuccess()) {
                response.body<InterruptResponse>()
            } else {
                InterruptResponse(success = false, message = "Interrupt failed: ${response.status}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Interrupt error: ${e.message}")
            InterruptResponse(success = false, message = e.message ?: "Unknown error")
        }
    }

    /**
     * Push local changes to the server (Optimized Sync).
     */
    suspend fun pushSync(request: com.example.smarty.protocol.SyncPushRequest): com.example.smarty.protocol.SyncPushResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response =
                client.post("$baseUrl/api/v1/sync/push") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            if (response.status.isSuccess()) {
                response.body<com.example.smarty.protocol.SyncPushResponse>()
            } else {
                Log.e(TAG, "Sync push failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync push error: ${e.message}", e)
            null
        }
    }

    @Serializable
    data class ApprovalRequest(
        val toolId: String,
        val approved: Boolean,
        val feedback: String? = null,
    )

    /**
     * Fetch the effective tool permission decisions for the current user.
     * Returns null on auth failure or network error. The settings UI
     * falls back to SMARTY_DEFAULT (hard-coded in common) if the
     * call fails.
     */
    suspend fun getToolPermissions(): ToolPermissionsListResponse? {
        val baseUrl = serverUrlProvider()
        val token = getFirebaseToken() ?: return null
        return try {
            val response =
                client.get("$baseUrl/api/v1/permissions/tools") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            if (response.status.isSuccess()) {
                response.body<ToolPermissionsListResponse>()
            } else {
                Log.e(TAG, "getToolPermissions failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getToolPermissions error: ${e.message}", e)
            null
        }
    }

    /**
     * Upsert a single tool's override. Pass `decision = "INHERIT"`
     * to delete the override (fall back to SMARTY_DEFAULT).
     * Returns the response on success, null on failure.
     */
    suspend fun setToolPermission(
        toolName: String,
        decision: String,
        reason: String? = null,
    ): ToolPermissionUpsertResponse? {
        val baseUrl = serverUrlProvider()
        val token = getFirebaseToken() ?: return null
        return try {
            val response =
                client.put("$baseUrl/api/v1/permissions/tools/$toolName") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(ToolPermissionUpsertRequest(decision = decision, reason = reason))
                }
            if (response.status.isSuccess()) {
                response.body<ToolPermissionUpsertResponse>()
            } else {
                Log.e(TAG, "setToolPermission($toolName, $decision) failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "setToolPermission error: ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "RemoteAgentService"
    }
}

@Serializable
data class ToolPermissionDto(
    val toolName: String,
    val decision: String,
    val isOverridden: Boolean = false,
    val overrideSource: String? = null,
    val overrideUpdatedAt: String? = null,
    val overrideExpiresAt: String? = null,
)

@Serializable
data class ToolPermissionsListResponse(
    val tools: List<ToolPermissionDto> = emptyList(),
    val defaultPolicy: Map<String, String> = emptyMap(),
)

@Serializable
data class ToolPermissionUpsertRequest(
    val decision: String,
    val reason: String? = null,
    val expiresAt: String? = null,
)

@Serializable
data class ToolPermissionUpsertResponse(
    val toolName: String,
    val effectiveDecision: String,
    val persisted: Boolean,
)

// Request/Response DTOs

@Serializable
data class BriefingRequest(
    val prompt: String,
)

@Serializable
data class BriefingResponse(
    val briefing: String,
    val success: Boolean = true,
)

@Serializable
data class AttachmentInfo(
    val fileName: String,
    val fileType: String,
)

@Serializable
data class ContentAnalysisRequest(
    val content: String,
    val attachments: List<AttachmentInfo>? = null,
)

@Serializable
data class DocumentAnalysisRequest(
    val text: String,
    val fileName: String? = null,
    val userContext: String? = null,
)

@Serializable
data class ImageProcessingRequest(
    val base64Image: String,
    val mimeType: String? = null,
    val analysisType: String? = "ocr",
)

@Serializable
data class ImageProcessingResult(
    val text: String,
    val contentType: String,
    val success: Boolean = true,
    val error: String? = null,
)

@Serializable
data class PdfProcessingRequest(
    val base64Pdf: String,
    val fileName: String? = null,
    val useOcr: Boolean? = true,
)

@Serializable
data class PdfProcessingResult(
    val text: String,
    val pageCount: Int,
    val hasImages: Boolean,
    val success: Boolean = true,
    val error: String? = null,
)

data class UserInfo(
    val userId: String,
    val email: String?,
    val displayName: String?,
)

// Chat with context DTOs

/**
 * Attachment info for chat messages.
 */
data class ChatAttachment(
    val type: String, // "image", "pdf", "document"
    val name: String,
    val mimeType: String? = null,
    val extractedText: String? = null, // Text extracted from the attachment
)

@Serializable
data class ChatQueryAttachment(
    val type: String,
    val name: String,
    val mimeType: String? = null,
)

/**
 * Model info returned from the server, including available variants.
 */
data class ModelInfo(
    val id: String,
    val label: String,
    val variants: List<String> = emptyList(),
)

@Serializable
data class ChatQueryRequest(
    val query: String,
    val sessionId: String? = null,
    val provider: String? = null,
    val providerUrl: String? = null,
    val model: String? = null,
    val variant: String? = null,
    val token: String? = null,
    val fileContext: String? = null,
    val attachments: List<ChatQueryAttachment>? = null,
    val timezone: String? = null,
    val clientTime: Long? = null,
    val personality: String? = null,
    val messageId: String? = null,
)

@Serializable
data class ChatQueryResponse(
    val sessionId: String? = null,
    val response: String = "",
    val events: List<String> = emptyList(),
)

@Serializable
data class DirectImageGenerationRequest(
    val prompt: String,
    val aspectRatio: String = "1:1",
)

@Serializable
data class DirectImageGenerationResponse(
    val type: String = "",
    val url: String = "",
    val source: String = "",
    val prompt: String = "",
    val jobId: String = "",
    val error: String? = null,
    val message: String? = null,
) {
    val success: Boolean get() = type == "image" && url.isNotBlank()
}

@Serializable
data class InterruptRequest(
    val sessionId: String,
)

@Serializable
data class InterruptResponse(
    val success: Boolean,
    val message: String,
)
