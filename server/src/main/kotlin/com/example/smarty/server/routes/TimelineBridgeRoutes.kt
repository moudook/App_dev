package com.example.smarty.server.routes

import com.example.smarty.agent.permissions.ToolPermissionDecision
import com.example.smarty.protocol.AgentEvent
import com.example.smarty.server.agent.ActiveSessionManager
import com.example.smarty.server.agent.permissionRepository
import com.example.smarty.server.agent.toolPermissionEnforcer
import com.example.smarty.server.plugins.verifyFirebaseToken
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TimelineBridgeRoutes
 *
 * Receives real-time telemetry events from the OpenCode plugin (timeline-bridge.ts)
 * running inside the OpenCode daemon process and broadcasts them to connected
 * Android clients over WebSocket.
 *
 * End-to-end flow:
 *   OpenCode CLI
 *     ↓ (POST /opencode/events)
 *   Ktor TimelineBridgeService
 *     ↓ (WebSocket /ws/timeline)
 *   Android RemoteAgentService → handleEvent() → TimelineNodeAggregator
 *
 * The MCP `ask` tool conflict:
 *   When the MCP `ask` tool blocks waiting for user input, the plugin emits
 *   `user.input.required` → this service translates it to `AgentEvent.ApprovalRequested`
 *   with `requiresText=true` → Android shows ApprovalGateCard → user types
 *   response → Android calls `sendApproval(toolId, approved, feedback)` →
 *   POST /api/v1/chat/events/approval → unblocks the tool.
 *   The MCP `ask` tool MUST be implemented to use ctx.client.session.permission.ask()
 *   (or write to /tmp/opencode-asks/<session>/<callID>.json and poll for the response file).
 */
fun Application.configureTimelineBridgeRoutes() {
    val bridge = TimelineBridgeService
    // Defensive: short-circuit `permission.asked` events for tools the
    // policy explicitly allows/denies. The OpenCode CLI normally
    // handles these internally before any event is emitted, so reaching
    // this point means a leak (plugin drift, CLI misconfiguration, or
    // a manual `opencode` run without the policy file). When the policy
    // matches, Ktor writes a synthetic response to the
    // `/tmp/opencode-asks/<sessionID>/<callID>.response.txt` file the
    // MCP `ask` tool polls, and DROPS the broadcast so the Android app
    // never even sees the spurious `permission.asked` event.
    //
    // We use the static policy (sync `decide()`) here because the
    // OpenCode plugin's `sessionID` doesn't map 1:1 to a Ktor userId
    // — the plugin runs inside the CLI process and broadcasts to a
    // single global WebSocket. Per-user overrides are applied at the
    // request handler level (see `ToolExecutor.requiresApproval`)
    // where the authenticated user is known.
    val enforcer = toolPermissionEnforcer
    val permissionRepo = permissionRepository

    routing {
        // Plugin → Ktor: ingest raw timeline events
        post("/opencode/events") {
            val body = call.receiveText()
            val ts = System.currentTimeMillis()

            try {
                val event = Json.parseToJsonElement(body).jsonObject
                val kind = event["kind"]?.jsonPrimitive?.content ?: "unknown"
                val sessionID = event["sessionID"]?.jsonPrimitive?.content ?: "no-session"

                // ── Policy filter for `permission.asked` events ──
                // The plugin emits this when the OpenCode CLI needs
                // user input. The CLI's `opencode.json` already
                // auto-runs `allow` tools and blocks `deny` tools
                // before emitting, so a leaked `permission.asked`
                // is normally for a `default` tool (which we DO
                // forward so the Android app can show the
                // approval card).
                if (kind == "permission.asked") {
                    val rawTool = event["tool"]?.jsonPrimitive?.content
                    if (rawTool != null) {
                        val decision = enforcer.decide(rawTool)
                        if (decision != ToolPermissionDecision.DEFAULT) {
                            val callId = event["callID"]?.jsonPrimitive?.content
                            if (!callId.isNullOrBlank() && sessionID != "no-session") {
                                val synthetic = enforcer.syntheticResponse(rawTool)
                                val dir = Paths.get("/tmp/opencode-asks", sessionID)
                                Files.createDirectories(dir)
                                dir
                                    .resolve("$callId.response.txt")
                                    .toFile()
                                    .writeText(synthetic, Charsets.UTF_8)
                                logger.info(
                                    "[KTOR-POLICY] auto-${decision.name.lowercase()} tool=$rawTool session=$sessionID call=$callId — synthetic response written, broadcast DROPPED",
                                )
                                // Audit log: best-effort. We don't have
                                // a userId here (the plugin's sessionID
                                // doesn't map to a Ktor user), so we
                                // log with `actor=ktor_enforcer` and
                                // userId=null. The user can still be
                                // attributed via the sessionID in
                                // `permission_audit_log.session_id`.
                                permissionRepo.logDecision(
                                    userId = sessionID, // placeholder; the
                                    // sessionId column carries the real value
                                    sessionId = sessionID,
                                    toolName = rawTool,
                                    decision =
                                        if (decision == ToolPermissionDecision.ALLOW) {
                                            "AUTO_APPROVED"
                                        } else {
                                            "AUTO_DENIED"
                                        },
                                    actor = "ktor_enforcer",
                                    callId = callId,
                                    metadata =
                                        mapOf(
                                            "source" to "static_policy",
                                            "session_id" to sessionID,
                                        ),
                                )
                                bridge.ingest(kind, sessionID, event, ts)
                                call.respond(
                                    HttpStatusCode.OK,
                                    mapOf(
                                        "ok" to true,
                                        "policy" to "auto-${decision.name.lowercase()}",
                                    ),
                                )
                                return@post
                            }
                        }
                    }
                }

                bridge.ingest(kind, sessionID, event, ts)

                // ── Live streaming: translate plugin events into AgentEvents and
                // push them into the per-session flow so the Android app can render
                // every step in real time. WebSocket is the only transport — no
                // polling, no SSE fallback. MCP tool calls are deliberately skipped
                // per product requirement (they have their own approval flow).
                val resolved =
                    ActiveSessionManager.resolveOpencodeSessionId(sessionID)
                if (resolved != null) {
                    val (userId, chatSessionId) = resolved
                    val streamEvents = translatePluginEvent(kind, event, ts, sessionID)
                    for (streamEvent in streamEvents) {
                        com.example.smarty.server.agent.AgentRunManager
                            .emitEvent(chatSessionId, streamEvent)
                    }
                    if (streamEvents.isNotEmpty()) {
                        logger.debug(
                            "[STREAM-TRANSLATE] kind=$kind -> ${streamEvents.size} event(s) for user=$userId chat=$chatSessionId",
                        )
                    } else if (kind.startsWith("message.") || kind.startsWith("tool.") || kind.startsWith("part.")) {
                        logger.warn(
                            "[STREAM-TRANSLATE-EMPTY] kind=$kind session=$sessionID — translatePluginEvent returned no events for user=$userId chat=$chatSessionId",
                        )
                    }
                } else if (kind.startsWith("message.") || kind.startsWith("tool.") || kind.startsWith("part.")) {
                    logger.warn(
                        "[STREAM-RESOLVE-NULL] kind=$kind session=$sessionID — no mapping in ActiveSessionManager, events dropped",
                    )
                }

                // Broadcast the raw event to all connected WebSocket clients
                val wrapped =
                    buildJsonObject {
                        put("kind", JsonPrimitive(kind))
                        put("sessionID", JsonPrimitive(sessionID))
                        put("ts", JsonPrimitive(ts))
                        // Forward every other field
                        for ((k, v) in event) {
                            if (k != "kind" && k != "sessionID" && k != "ts") {
                                put(k, v)
                            }
                        }
                    }
                bridge.broadcast(wrapped)

                call.respond(HttpStatusCode.OK, mapOf("ok" to true))
            } catch (e: Exception) {
                val preview = body.substring(0, minOf(body.length, 200))
                logger.error("[KTOR-RECV-ERROR] error=${e.message} body=$preview")
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "unknown")))
            }
        }

        // WebSocket: Android clients subscribe to live timeline events.
        // Sends events as raw JSON frames; Android feeds them into handleEvent().
        webSocket("/ws/timeline") {
            val token = call.request.headers[io.ktor.http.HttpHeaders.Authorization]?.removePrefix("Bearer ")
            val user = verifyFirebaseToken(token ?: "", null)
            if (user == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
                return@webSocket
            }
            val userId = user.userId
            logger.info("[WS-TIMELINE] Client connected: user=$userId")
            val sender: suspend (String) -> Unit = { payload ->
                send(Frame.Text(payload))
            }
            bridge.addClient(userId, sender)
            try {
                // Keep the connection alive — incoming frames are ignored.
                // We could implement commands from client (e.g., abort session) here later.
                for (frame in incoming) {
                    if (frame is Frame.Close) break
                }
            } catch (e: Exception) {
                logger.debug("[WS-TIMELINE] Incoming error: ${e.message}")
            } finally {
                bridge.removeClient(sender)
                logger.info("[WS-TIMELINE] Client disconnected")
            }
        }

        // MCP `ask` tool delivery endpoint.
        // The Android app posts the user response here. We write the response to
        //   /tmp/opencode-asks/<sessionID>/<callID>.response.txt
        // so the MCP `ask` tool (which polls this file) can return the response.
        // SECURITY: Requires valid Firebase token. The response file path uses
        // a subdirectory derived from the user's identity to prevent cross-user writes.
        post("/opencode/ask-response/{sessionId}/{callId}") {
            val authHeader = call.request.headers[io.ktor.http.HttpHeaders.Authorization]
            val token = authHeader?.removePrefix("Bearer ")
            val user = verifyFirebaseToken(token ?: "", null)
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                return@post
            }
            val sessionId = call.parameters["sessionId"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
            val callId = call.parameters["callId"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
            if (sessionId == null || callId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid sessionId or callId"))
                return@post
            }
            val body = call.receiveText()
            val response =
                try {
                    Json
                        .parseToJsonElement(body)
                        .jsonObject["response"]
                        ?.jsonPrimitive
                        ?.content ?: ""
                } catch (e: Exception) {
                    ""
                }
            if (response.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing response field"))
                return@post
            }
            try {
                val dir = Paths.get("/tmp/opencode-asks", sessionId)
                Files.createDirectories(dir)
                val file = dir.resolve("$callId.response.txt")
                file.toFile().writeText(response, Charsets.UTF_8)
                logger.info("[ASK-RESPONSE] Wrote response for session=$sessionId call=$callId (${response.length} chars)")
                call.respond(HttpStatusCode.OK, mapOf("ok" to true))
            } catch (e: Exception) {
                logger.error("[ASK-RESPONSE] Failed to write response file: ${e.message}")
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "unknown")))
            }
        }
    }

    logger.info("[KTOR] /opencode/events, /ws/timeline, /opencode/ask-response routes registered")
}

private val logger = LoggerFactory.getLogger("TimelineBridgeRoutes")

/**
 * MCP tool names are prefixed with `mcp` by the OpenCode CLI (e.g.
 * `mcp__smarty_ask_user`). The product requirement is to skip streaming
 * for MCP tool calls — they have their own approval / response flow.
 */
private fun isMcpToolName(name: String?): Boolean = !name.isNullOrBlank() && name.startsWith("mcp")

/**
 * Per-session state for computing deltas from snapshot events.
 * The OpenCode daemon v1.15.13 sends `message.updated` snapshot events
 * (full accumulated text) rather than `message.part.delta` deltas.
 * We track the previous text/reasoning per session and emit only the delta.
 */
private data class SessionContentState(
    var text: String = "",
    var reasoning: String = "",
)

private val sessionContentStates = ConcurrentHashMap<String, SessionContentState>()

/**
 * Translate a single plugin event payload into zero or more [AgentEvent]s
 * for live streaming to the Android app. Returns an empty list when the
 * event is not streamable (e.g. unknown part type, MCP tool, empty delta).
 *
 * Coverage:
 *   - message.part.delta field=text      -> FinalAnswerDelta
 *   - message.part.delta field=reasoning -> ReasoningDelta
 *   - message.updated (snapshot)         -> FinalAnswerDelta / ReasoningDelta (delta computed)
 *   - tool.before (non-MCP)              -> ToolCallStarted + ToolCallInput
 *   - tool.after  (non-MCP)              -> ToolCallOutput + ToolCallFinished
 *   - part.updated partType=step-start   -> StepStarted
 *   - part.updated partType=step-finish  -> StepFinished
 *   - part.updated partType=subtask      -> StepStarted (sub-agent)
 */
private fun translatePluginEvent(
    kind: String,
    event: JsonObject,
    ts: Long,
    sessionId: String,
): List<AgentEvent> {
    val out = mutableListOf<AgentEvent>()

    when (kind) {
        "message.part.delta" -> {
            val field = event["field"]?.jsonPrimitive?.content
            val delta = event["delta"]?.jsonPrimitive?.content
            if (delta.isNullOrEmpty()) return emptyList()
            when (field) {
                "text" ->
                    out +=
                        AgentEvent.FinalAnswerDelta(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = ts,
                            text = delta,
                        )
                "reasoning" ->
                    out +=
                        AgentEvent.ReasoningDelta(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = ts,
                            text = delta,
                        )
            }
        }

        "message.updated" -> {
            // The daemon sends snapshot events with the full accumulated text.
            // Extract text/reasoning from the message parts array, compute deltas
            // from the previous state, and emit only the new content.
            val state = sessionContentStates.getOrPut(sessionId) { SessionContentState() }
            val messageObj = event["message"]?.jsonObject ?: event
            val parts = messageObj["parts"]
            if (parts != null) {
                val partsArray = parts.toString().let { raw ->
                    try {
                        Json.parseToJsonElement(raw).jsonObject
                    } catch (_: Exception) {
                        null
                    }
                }
                // Try array-style parts: {"0": {"type":"text","text":"..."}, "1": {...}}
                // or list-style: [{"type":"text","text":"..."}]
                if (partsArray != null) {
                    for ((_, partRaw) in partsArray) {
                        val part = partRaw?.jsonObject ?: continue
                        val partType = part["type"]?.jsonPrimitive?.content
                        when (partType) {
                            "text" -> {
                                val fullText = part["text"]?.jsonPrimitive?.content ?: ""
                                if (fullText.length > state.text.length) {
                                    val delta = fullText.substring(state.text.length)
                                    state.text = fullText
                                    if (delta.isNotEmpty()) {
                                        out +=
                                            AgentEvent.FinalAnswerDelta(
                                                eventId = UUID.randomUUID().toString(),
                                                timestamp = ts,
                                                text = delta,
                                            )
                                    }
                                }
                            }
                            "reasoning" -> {
                                val fullReasoning = part["reasoning"]?.jsonPrimitive?.content ?: ""
                                if (fullReasoning.length > state.reasoning.length) {
                                    val delta = fullReasoning.substring(state.reasoning.length)
                                    state.reasoning = fullReasoning
                                    if (delta.isNotEmpty()) {
                                        out +=
                                            AgentEvent.ReasoningDelta(
                                                eventId = UUID.randomUUID().toString(),
                                                timestamp = ts,
                                                text = delta,
                                            )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Also handle flat structure: event has direct "text" or "reasoning" fields
            if (out.isEmpty()) {
                val fullText = event["text"]?.jsonPrimitive?.content
                if (fullText != null && fullText.length > state.text.length) {
                    val delta = fullText.substring(state.text.length)
                    state.text = fullText
                    if (delta.isNotEmpty()) {
                        out +=
                            AgentEvent.FinalAnswerDelta(
                                eventId = UUID.randomUUID().toString(),
                                timestamp = ts,
                                text = delta,
                            )
                    }
                }
                val fullReasoning = event["reasoning"]?.jsonPrimitive?.content
                if (fullReasoning != null && fullReasoning.length > state.reasoning.length) {
                    val delta = fullReasoning.substring(state.reasoning.length)
                    state.reasoning = fullReasoning
                    if (delta.isNotEmpty()) {
                        out +=
                            AgentEvent.ReasoningDelta(
                                eventId = UUID.randomUUID().toString(),
                                timestamp = ts,
                                text = delta,
                            )
                    }
                }
            }
        }

        "tool.before" -> {
            val tool = event["tool"]?.jsonPrimitive?.content
            val callId = event["callID"]?.jsonPrimitive?.content
            if (isMcpToolName(tool)) return emptyList()
            if (tool.isNullOrBlank() || callId.isNullOrBlank()) return emptyList()
            val args = event["args"]
            val argsStr = if (args != null && args != JsonPrimitive(null)) args.toString() else ""
            out +=
                AgentEvent.ToolCallStarted(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = ts,
                    toolId = callId,
                    name = tool,
                    source = "opencode",
                )
            if (argsStr.isNotEmpty()) {
                out +=
                    AgentEvent.ToolCallInput(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = ts,
                        toolId = callId,
                        inputDelta = argsStr,
                    )
            }
        }

        "tool.after" -> {
            val tool = event["tool"]?.jsonPrimitive?.content
            val callId = event["callID"]?.jsonPrimitive?.content
            if (isMcpToolName(tool)) return emptyList()
            if (callId.isNullOrBlank()) return emptyList()
            val result = event["result"]
            val error = event["error"]
            val outputStr =
                when {
                    error != null && error != JsonPrimitive(null) -> "Error: $error"
                    result != null && result != JsonPrimitive(null) -> result.toString()
                    else -> ""
                }
            if (outputStr.isNotEmpty()) {
                out +=
                    AgentEvent.ToolCallOutput(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = ts,
                        toolId = callId,
                        output = outputStr,
                    )
            }
            out +=
                AgentEvent.ToolCallFinished(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = ts,
                    toolId = callId,
                    durationMs = 0L,
                )
        }

        "part.updated" -> {
            val partType = event["partType"]?.jsonPrimitive?.content ?: return emptyList()
            when (partType) {
                "step-start" -> {
                    val step = event["step"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    out +=
                        AgentEvent.StepStarted(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = ts,
                            title = "Step $step",
                        )
                }
                "step-finish" -> {
                    out +=
                        AgentEvent.StepFinished(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = ts,
                            success = true,
                        )
                }
                "subtask" -> {
                    val desc = event["description"]?.jsonPrimitive?.content
                    val agent = event["agent"]?.jsonPrimitive?.content
                    val title = desc ?: agent?.let { "Sub-agent: $it" } ?: "Sub-agent"
                    out +=
                        AgentEvent.StepStarted(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = ts,
                            title = title,
                        )
                }
                "text" -> {
                    // part.updated snapshot with full accumulated text — compute delta
                    val state = sessionContentStates.getOrPut(sessionId) { SessionContentState() }
                    val fullText = event["text"]?.jsonPrimitive?.content ?: ""
                    if (fullText.length > state.text.length) {
                        val delta = fullText.substring(state.text.length)
                        state.text = fullText
                        if (delta.isNotEmpty()) {
                            out +=
                                AgentEvent.FinalAnswerDelta(
                                    eventId = UUID.randomUUID().toString(),
                                    timestamp = ts,
                                    text = delta,
                                )
                        }
                    }
                }
                "reasoning" -> {
                    // part.updated snapshot with full accumulated reasoning — compute delta
                    val state = sessionContentStates.getOrPut(sessionId) { SessionContentState() }
                    val fullReasoning = event["reasoning"]?.jsonPrimitive?.content ?: ""
                    if (fullReasoning.length > state.reasoning.length) {
                        val delta = fullReasoning.substring(state.reasoning.length)
                        state.reasoning = fullReasoning
                        if (delta.isNotEmpty()) {
                            out +=
                                AgentEvent.ReasoningDelta(
                                    eventId = UUID.randomUUID().toString(),
                                    timestamp = ts,
                                    text = delta,
                                )
                        }
                    }
                }
            }
        }
    }

    return out
}

/**
 * In-memory timeline storage with WebSocket broadcast.
 *
 * Phase 1 (storage): keyed by sessionID, append-only list of events.
 * Phase 2 (broadcast): SharedFlow that pushes events to all WS subscribers.
 */
object TimelineBridgeService {
    data class EventSnapshot(
        val kind: String,
        val sessionID: String,
        val ts: Long,
        val raw: JsonObject,
    )

    private val timelines = ConcurrentHashMap<String, CopyOnWriteArrayList<EventSnapshot>>()
    private const val MAX_EVENTS_PER_SESSION = 10_000
    private const val EVICTION_BATCH = 1_000

    // Broadcast channel — drop oldest if consumer is slow to prevent blocking all clients
    private val _events =
        MutableSharedFlow<JsonObject>(
            replay = 0,
            extraBufferCapacity = 1024,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    val events: SharedFlow<JsonObject> = _events.asSharedFlow()

    // Connected WebSocket clients — (userId, sender) pairs for per-user broadcast
    private val senders = CopyOnWriteArrayList<Pair<String, suspend (String) -> Unit>>()

    val totalEvents: Long
        get() = timelines.values.sumOf { it.size.toLong() }

    val totalSessions: Int
        get() = timelines.size

    fun ingest(
        kind: String,
        sessionID: String,
        event: JsonObject,
        ts: Long,
    ) {
        if (sessionID == "no-session") return

        val list = timelines.getOrPut(sessionID) { CopyOnWriteArrayList() }
        if (list.size >= MAX_EVENTS_PER_SESSION) {
            // Evict oldest events to prevent unbounded memory growth
            val toRemove = list.subList(0, minOf(EVICTION_BATCH, list.size))
            toRemove.clear()
        }
        list.add(EventSnapshot(kind, sessionID, ts, event))

        // Log important events with extra detail
        when (kind) {
            "session.created" -> logger.info("[TIMELINE] New session: $sessionID")
            "session.idle" -> logger.info("[TIMELINE] Session done: $sessionID | events=${list.size}")
            "user.input.required" -> {
                val tool = event["tool"]?.jsonPrimitive?.content ?: "?"
                val question = (event["question"]?.jsonPrimitive?.content ?: "").take(80)
                logger.info("[TIMELINE] User input required: tool=$tool (question truncated)")
            }
            "part.updated" -> {
                val partType = event["partType"]?.jsonPrimitive?.content ?: "unknown"
                when (partType) {
                    "reasoning" -> {
                        val reasoning = (event["reasoning"]?.jsonPrimitive?.content ?: "").take(80)
                        logger.info("[TIMELINE] Reasoning: ${reasoning.take(80)}")
                    }
                    "text" -> {
                        val text = (event["text"]?.jsonPrimitive?.content ?: "").take(80)
                        logger.info("[TIMELINE] Text: ${text.take(80)}")
                    }
                    "tool" -> {
                        val tool = event["tool"]?.jsonPrimitive?.content ?: "?"
                        val state = event["state"]?.jsonPrimitive?.content ?: "?"
                        logger.info("[TIMELINE] Tool [$tool] -> $state")
                    }
                    "subtask" -> {
                        val agent = event["agent"]?.jsonPrimitive?.content ?: "?"
                        val state = event["state"]?.jsonPrimitive?.content ?: "?"
                        logger.info("[TIMELINE] Sub-agent [$agent] -> $state")
                    }
                    "step-finish" -> {
                        val cost = event["cost"]?.jsonPrimitive?.content ?: "?"
                        logger.info("[TIMELINE] Step finished, cost=\$$cost")
                    }
                }
            }
            "tool.before" -> {
                val tool = event["tool"]?.jsonPrimitive?.content ?: "?"
                logger.info("[TIMELINE] Tool call: $tool")
            }
            "tool.after" -> {
                val tool = event["tool"]?.jsonPrimitive?.content ?: "?"
                logger.info("[TIMELINE] Tool result: $tool")
            }
        }
    }

    /** Push an event to all WebSocket subscribers for the given userId. */
    suspend fun broadcast(
        event: JsonObject,
        userId: String? = null,
    ) {
        val payload = Json.encodeToString(JsonObject.serializer(), event)
        val dead = mutableListOf<Pair<String, suspend (String) -> Unit>>()
        for ((uid, sender) in senders) {
            if (userId != null && uid != userId) continue
            try {
                sender(payload)
            } catch (e: ClosedSendChannelException) {
                dead.add(uid to sender)
            } catch (e: Exception) {
                dead.add(uid to sender)
            }
        }
        for (d in dead) {
            senders.remove(d)
        }
    }

    /** Add a WebSocket client scoped to a userId. */
    fun addClient(
        userId: String,
        sender: suspend (String) -> Unit,
    ) {
        senders.add(userId to sender)
        logger.info("[TIMELINE] Client added for user=$userId. Total clients: ${senders.size}")
    }

    /** Remove a WebSocket client. */
    fun removeClient(sender: suspend (String) -> Unit) {
        senders.removeAll { (_, s) -> s == sender }
        logger.info("[TIMELINE] Client removed. Total clients: ${senders.size}")
    }

    fun getTimeline(sessionID: String): List<EventSnapshot> = timelines[sessionID]?.toList() ?: emptyList()

    fun getAllSessionIDs(): Set<String> = timelines.keys
}
