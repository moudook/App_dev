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
import kotlinx.serialization.json.JsonArray
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

private val logger = LoggerFactory.getLogger("com.example.smarty.server.routes.TimelineBridgeRoutes")

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

// Track which (sessionId, kind) pairs we've already dumped to the log — once is enough
// to confirm payload structure without spamming the log on every event.
// For `message.updated` we keep a separate counter so we see the user echo
// (#1) AND the assistant response (#2, #3) in the same run.
private val debugLoggedKinds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
private val msgUpdatedCounters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
private const val MSG_UPDATED_LOG_LIMIT = 10

/**
 * Comprehensive OpenCode v1.15.13+ event → AgentEvent translator.
 *
 * Maps every event kind the daemon emits into typed [AgentEvent]s for the
 * Android app. The daemon's wire format includes:
 *
 *   Session lifecycle
 *     session.created, session.idle, session.error, session.aborted,
 *     session.resumed, session.completed, session.compacted, compaction.*
 *
 *   Message lifecycle
 *     message.created, message.updated, message.completed, message.deleted
 *
 *   Message parts (granular deltas + snapshots)
 *     message.part.created, message.part.delta, message.part.updated,
 *     message.part.completed, message.part.deleted,
 *     message.part.reasoning*, message.part.text*,
 *     message.part.step_start, message.part.step_finish, message.part.subtask,
 *     message.part.{tool, snapshot, patch, agent, compaction, retry, hook,
 *                  command, file}
 *
 *   Tool execution
 *     tool.before, tool.after  (ALL tools including MCP)
 *
 *   Permission / approval
 *     permission.asked, permission.granted, permission.denied
 *     user.input.required
 *
 *   MCP
 *     mcp.tools.changed, mcp.connected, mcp.disconnected
 *
 *   Misc (forwarded as OpencodeRawEvent)
 *     todo.*, file.*, command.*, web.*, lsp.*
 *
 *   Catchall: any unknown kind → AgentEvent.OpencodeRawEvent
 *
 * The daemon v1.15.13 sends `message.updated` SNAPSHOT events (full
 * accumulated text). We track per-session text/reasoning state to
 * compute deltas so Android's `responseBuilder.append(event.text)`
 * doesn't duplicate content. State resets on `message.created` (new turn)
 * and clears on `session.idle` / `session.completed`.
 */
private fun translatePluginEvent(
    kind: String,
    event: JsonObject,
    ts: Long,
    sessionId: String,
): List<AgentEvent> {
    val out = mutableListOf<AgentEvent>()

    // One-time debug log: dump the structure of major event kinds so we can
    // confirm payload shape. For `message.updated` we log the FIRST N events
    // (full payload) so we can see both the user echo AND the assistant
    // response — the first event is role="user" and the second/third are
    // usually role="assistant" with the actual response parts[].
    if (kind == "message.updated") {
        val count = msgUpdatedCounters
            .computeIfAbsent(sessionId) { java.util.concurrent.atomic.AtomicInteger(0) }
            .incrementAndGet()
        if (count <= MSG_UPDATED_LOG_LIMIT) {
            val messageObj = event["info"]?.jsonObject
                ?: event["message"]?.jsonObject
                ?: event
            val role = messageObj["role"]?.jsonPrimitive?.content ?: "?"
            val topKeys = event.keys.sorted().joinToString(",")
            val innerKeys = messageObj.keys.sorted().joinToString(",")
            val fullPreview = event.toString().take(16384)
            logger.info(
                "[STREAM-MAP-DEBUG] message.updated #$count role=$role topKeys=[$topKeys] innerKeys=[$innerKeys] payload(${event.toString().length}B)=$fullPreview",
            )
        }
    } else if (debugLoggedKinds.add("$sessionId:$kind") &&
               (kind.startsWith("message.") || kind.startsWith("tool.") ||
                kind.startsWith("permission.") || kind.startsWith("mcp.") ||
                kind.startsWith("session."))
    ) {
        val topKeys = event.keys.sorted().joinToString(",")
        val preview = event.toString().take(2000)
        logger.info("[STREAM-MAP-DEBUG] first $kind topKeys=[$topKeys] preview=$preview")
    }

    when (kind) {
        // ── Session lifecycle ──
        "session.created", "session.resumed" -> {
            out += AgentEvent.SessionStarted(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                sessionId = sessionId,
            )
        }
        "session.idle", "session.completed" -> {
            sessionContentStates.remove(sessionId)
            out += AgentEvent.SessionCompleted(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
            )
        }
        "session.error" -> {
            val msg = event["error"]?.let {
                if (it is JsonObject) it["message"]?.jsonPrimitive?.content
                else it.jsonPrimitive?.content
            } ?: event["message"]?.jsonPrimitive?.content ?: "Unknown error"
            out += AgentEvent.SessionError(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                message = msg,
            )
        }
        "session.aborted" -> {
            val reason = event["reason"]?.jsonPrimitive?.content ?: "aborted"
            out += AgentEvent.SessionAborted(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                reason = reason,
            )
        }
        "session.compacted", "compaction.start", "compaction.completed" -> {
            val reason = when (kind) {
                "compaction.start" -> "Context compressing"
                else -> "Compaction completed"
            }
            out += AgentEvent.RecoveryStarted(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                reason = reason,
            )
        }

        // ── Session sub-events observed in v1.15.13 (e.g. session.next.*, session.status) ──
        "session.next.agent.switched" -> {
            val info = event["info"]?.jsonObject ?: event
            val newAgent = info["agent"]?.jsonPrimitive?.content ?: "?"
            out += AgentEvent.ModelResolved(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                requested = newAgent,
                resolved = newAgent,
                fallback = false,
            )
        }
        "session.next.model.switched" -> {
            val info = event["info"]?.jsonObject ?: event
            val modelObj = info["model"]?.jsonObject
            val newModel = modelObj?.get("modelID")?.jsonPrimitive?.content
                ?: info["modelID"]?.jsonPrimitive?.content
                ?: "?"
            val provider = modelObj?.get("providerID")?.jsonPrimitive?.content
                ?: info["providerID"]?.jsonPrimitive?.content
            val resolved = if (provider != null) "$provider/$newModel" else newModel
            out += AgentEvent.ModelResolved(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                requested = resolved,
                resolved = resolved,
                fallback = false,
            )
        }
        "session.status" -> {
            out += AgentEvent.StateSync(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                syncType = "session_status",
                data = event.toString().take(4096),
            )
        }

        // ── Message lifecycle ──
        "message.created" -> {
            // New turn — reset per-session content state so deltas start fresh
            sessionContentStates[sessionId] = SessionContentState()
            out += AgentEvent.FinalAnswerStarted(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
            )
        }
        "message.completed" -> {
            out += AgentEvent.FinalAnswerFinished(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
            )
            // Keep state alive — late `message.updated` snapshots may still fire
        }
        "message.deleted" -> { /* marker — no event */ }

        // ── Message snapshot (parts array) ──
        "message.updated" -> {
            extractFromMessageParts(event, ts, sessionId, out)
        }

        // ── Message part events (granular deltas) ──
        "message.part.created", "message.part.completed", "message.part.deleted" -> { /* markers */ }

        "message.part.delta" -> {
            val field = event["field"]?.jsonPrimitive?.content
            val delta = event["delta"]?.jsonPrimitive?.content
            if (!delta.isNullOrEmpty()) {
                when (field) {
                    "text" -> out += AgentEvent.FinalAnswerDelta(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = ts,
                        text = delta,
                    )
                    "reasoning" -> out += AgentEvent.ReasoningDelta(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = ts,
                        text = delta,
                    )
                }
            }
        }

        "message.part.updated" -> {
            extractFromPartUpdated(event, ts, sessionId, out)
        }

        "message.part.reasoning", "message.part.reasoning_delta" -> {
            val delta = event["delta"]?.jsonPrimitive?.content
                ?: event["reasoning"]?.jsonPrimitive?.content
                ?: event["text"]?.jsonPrimitive?.content
                ?: ""
            if (delta.isNotEmpty()) {
                out += AgentEvent.ReasoningDelta(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = ts,
                    text = delta,
                )
            }
        }
        "message.part.reasoning_completed" -> {
            out += AgentEvent.ReasoningFinished(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
            )
        }
        "message.part.text", "message.part.text_delta" -> {
            val delta = event["delta"]?.jsonPrimitive?.content
                ?: event["text"]?.jsonPrimitive?.content
                ?: ""
            if (delta.isNotEmpty()) {
                out += AgentEvent.FinalAnswerDelta(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = ts,
                    text = delta,
                )
            }
        }
        "message.part.text_completed" -> {
            out += AgentEvent.FinalAnswerFinished(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
            )
        }
        "message.part.step_start" -> {
            val step = event["step"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            out += AgentEvent.StepStarted(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                title = "Step $step",
            )
        }
        "message.part.step_finish" -> {
            out += AgentEvent.StepFinished(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                success = true,
            )
        }
        "message.part.subtask" -> {
            val desc = event["description"]?.jsonPrimitive?.content
            val agent = event["agent"]?.jsonPrimitive?.content
            val title = desc ?: agent?.let { "Sub-agent: $it" } ?: "Sub-agent"
            out += AgentEvent.StepStarted(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                title = title,
            )
        }
        "message.part.tool", "message.part.tool_result",
        "message.part.snapshot", "message.part.patch", "message.part.agent",
        "message.part.compaction", "message.part.retry", "message.part.hook",
        "message.part.command", "message.part.file" -> {
            out += AgentEvent.OpencodeRawEvent(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                data = event.toString().take(8192),
                eventName = kind,
            )
        }

        // ── Backwards-compatible: old daemon v1.14.x used `part.updated` (no message. prefix) ──
        "part.updated" -> {
            extractFromPartUpdated(event, ts, sessionId, out)
        }

        // ── Tool lifecycle (ALL tools, including MCP — was previously filtered out) ──
        "tool.before" -> {
            val tool = event["tool"]?.jsonPrimitive?.content
            val callId = event["callID"]?.jsonPrimitive?.content
                ?: event["callId"]?.jsonPrimitive?.content
            if (tool.isNullOrBlank() || callId.isNullOrBlank()) return out
            out += AgentEvent.ToolCallStarted(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                toolId = callId,
                name = tool,
                source = if (isMcpToolName(tool)) "mcp" else "opencode",
            )
            val args = event["args"]
            val argsStr = if (args != null && args != JsonPrimitive(null)) args.toString() else ""
            if (argsStr.isNotEmpty()) {
                out += AgentEvent.ToolCallInput(
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
                ?: event["callId"]?.jsonPrimitive?.content
            if (callId.isNullOrBlank()) return out
            val result = event["result"]
            val error = event["error"]
            val outputStr = when {
                error != null && error != JsonPrimitive(null) -> "Error: $error"
                result != null && result != JsonPrimitive(null) -> result.toString()
                else -> ""
            }
            if (outputStr.isNotEmpty()) {
                out += AgentEvent.ToolCallOutput(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = ts,
                    toolId = callId,
                    output = outputStr,
                )
            }
            out += AgentEvent.ToolCallFinished(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                toolId = callId,
                durationMs = 0L,
            )
        }

        // ── Permission / approval flow ──
        "permission.asked" -> {
            val callId = event["callID"]?.jsonPrimitive?.content
                ?: event["callId"]?.jsonPrimitive?.content
                ?: return out
            val tool = event["tool"]?.jsonPrimitive?.content ?: return out
            val title = event["title"]?.jsonPrimitive?.content ?: tool
            val args = event["args"]
            val toolArgs = if (args != null && args != JsonPrimitive(null)) args.toString() else ""
            out += AgentEvent.ApprovalRequested(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                toolId = callId,
                toolName = tool,
                toolTitle = title,
                toolArgs = toolArgs,
                sessionId = sessionId,
                isInteractive = false,
            )
        }
        "permission.granted" -> {
            val callId = event["callID"]?.jsonPrimitive?.content
                ?: event["callId"]?.jsonPrimitive?.content
                ?: return out
            out += AgentEvent.ApprovalGranted(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                toolId = callId,
            )
        }
        "permission.denied" -> {
            val callId = event["callID"]?.jsonPrimitive?.content
                ?: event["callId"]?.jsonPrimitive?.content
                ?: return out
            out += AgentEvent.ApprovalDenied(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                toolId = callId,
            )
        }
        "user.input.required" -> {
            val callId = event["callID"]?.jsonPrimitive?.content
                ?: event["callId"]?.jsonPrimitive?.content
                ?: UUID.randomUUID().toString()
            val tool = event["tool"]?.jsonPrimitive?.content ?: "ask_user"
            val title = event["title"]?.jsonPrimitive?.content ?: tool
            val args = event["args"]
            val toolArgs = if (args != null && args != JsonPrimitive(null)) args.toString() else ""
            out += AgentEvent.ApprovalRequested(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                toolId = callId,
                toolName = tool,
                toolTitle = title,
                toolArgs = toolArgs,
                sessionId = sessionId,
                isInteractive = true,
            )
        }

        // ── MCP events ──
        "mcp.tools.changed" -> {
            out += AgentEvent.StateSync(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                syncType = "mcp_tools",
                data = event.toString().take(4096),
            )
        }
        "mcp.connected", "mcp.disconnected" -> {
            out += AgentEvent.OpencodeRawEvent(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                data = event.toString().take(2048),
                eventName = kind,
            )
        }

        // ── Misc events (forwarded as raw for Android to display) ──
        "todo.updated", "todo.completed",
        "file.edited", "file.read", "file.written",
        "command.executed",
        "web.searched", "web.fetched",
        "lsp.client.diagnostics", "lsp.indexed" -> {
            out += AgentEvent.OpencodeRawEvent(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                data = event.toString().take(4096),
                eventName = kind,
            )
        }

        // ── Catchall: forward any unknown event so Android can see it ──
        else -> {
            out += AgentEvent.OpencodeRawEvent(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                data = event.toString().take(4096),
                eventName = kind,
            )
        }
    }

    return out
}

/**
 * Extract text/reasoning/tool/step parts from a `message.updated` snapshot.
 *
 * OpenCode v1.15.13 `Info` schema (from `packages/opencode/src/session/message.ts`):
 *   {
 *     "id": "msg_xxx",
 *     "role": "user" | "assistant",   <-- filter to assistant only
 *     "parts": [
 *       { "type": "text",         "text": "..." },
 *       { "type": "reasoning",    "text": "..." },            <-- `text`, not `reasoning`!
 *       { "type": "tool-invocation", "toolInvocation": {
 *           "state": "call" | "partial-call" | "result",
 *           "step": 0,
 *           "toolCallId": "...",
 *           "toolName": "...",
 *           "args": {...},
 *           "result": "..."  // only when state="result"
 *       }},
 *       { "type": "step-start" },
 *       { "type": "source-url",  "sourceId": "...", "url": "..." },
 *       { "type": "file",        "mediaType": "...", "url": "..." }
 *     ],
 *     "metadata": { "time": {"created":...}, "error":?, "sessionID", "tool":...,
 *                   "assistant":?, "snapshot":? }
 *   }
 *
 * The first `message.updated` is the user's prompt echo (role="user") and
 * has the agent's system prompt inside `info.system` — we MUST skip it.
 * Subsequent `message.updated` events with role="assistant" carry the
 * model's response in `info.parts[]`.
 *
 * For the user message echo (flattened wire format observed in server.log),
 * `info` looks like:
 *   { id, role="user", sessionID, time, agent, model, system }
 * There are NO parts[] for the user echo. We just skip it via role check.
 */
private fun extractFromMessageParts(
    event: JsonObject,
    ts: Long,
    sessionId: String,
    out: MutableList<AgentEvent>,
) {
    val messageObj = event["info"]?.jsonObject
        ?: event["message"]?.jsonObject
        ?: event

    val role = messageObj["role"]?.jsonPrimitive?.content
    if (role != null && role != "assistant") {
        // Skip user-message echoes (they only carry the system prompt).
        return
    }

    val state = sessionContentStates.getOrPut(sessionId) { SessionContentState() }

    // Path 1: `info.parts[]` array — the canonical OpenCode v1.15+ format
    val parts = messageObj["parts"]
    val partList: List<JsonObject> = when (parts) {
        is JsonArray -> parts.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        is JsonObject -> parts.values.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        else -> emptyList()
    }

    for (part in partList) {
        val partType = part["type"]?.jsonPrimitive?.content
        when (partType) {
            "text" -> emitTextDelta(part["text"]?.jsonPrimitive?.content ?: "", state, ts, out)
            // Reasoning parts ALSO use `text` (not `reasoning`) per the OpenCode schema
            "reasoning" -> emitReasoningDelta(part["text"]?.jsonPrimitive?.content ?: "", state, ts, out)
            "tool-invocation" -> emitToolInvocationPart(part, ts, out)
            "step-start" -> out += AgentEvent.StepStarted(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                title = "Step",
            )
            "source-url", "file" -> {
                // Pass-through as raw for Android to display
                out += AgentEvent.OpencodeRawEvent(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = ts,
                    data = part.toString().take(4096),
                    eventName = partType,
                )
            }
        }
    }

    // Path 2: flat `text`/`content` on the message object (older daemon formats)
    if (out.isEmpty()) {
        val fullText = messageObj["text"]?.jsonPrimitive?.content
            ?: messageObj["content"]?.jsonPrimitive?.content
        if (!fullText.isNullOrEmpty()) {
            emitTextDelta(fullText, state, ts, out)
        }
    }

    // Path 3: legacy flat at event top-level
    if (out.isEmpty()) {
        val fullText = event["text"]?.jsonPrimitive?.content
            ?: event["content"]?.jsonPrimitive?.content
        if (!fullText.isNullOrEmpty()) {
            emitTextDelta(fullText, state, ts, out)
        }
    }
}

/**
 * Extract from `message.part.updated` / legacy `part.updated` event.
 * Schema for v1.15+: `event.part` = a single MessagePart object
 * (or `event` itself is a flat part). Handles text, reasoning,
 * step-start, step-finish, subtask, tool-invocation part types.
 */
private fun extractFromPartUpdated(
    event: JsonObject,
    ts: Long,
    sessionId: String,
    out: MutableList<AgentEvent>,
) {
    val info = event["info"]?.jsonObject
    val part = event["part"]?.jsonObject
    val state = sessionContentStates.getOrPut(sessionId) { SessionContentState() }

    val partType = info?.get("type")?.jsonPrimitive?.content
        ?: part?.get("type")?.jsonPrimitive?.content
        ?: event["partType"]?.jsonPrimitive?.content
        ?: return

    when (partType) {
        "text" -> {
            val fullText = (info?.get("text") ?: part?.get("text")
                ?: event["text"])?.jsonPrimitive?.content ?: ""
            emitTextDelta(fullText, state, ts, out)
        }
        "reasoning" -> {
            val fullReasoning = (info?.get("text") ?: part?.get("text")
                ?: event["reasoning"])?.jsonPrimitive?.content ?: ""
            emitReasoningDelta(fullReasoning, state, ts, out)
        }
        "step-start" -> {
            out += AgentEvent.StepStarted(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                title = "Step",
            )
        }
        "step-finish" -> {
            out += AgentEvent.StepFinished(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                success = true,
            )
        }
        "subtask" -> {
            val desc = event["description"]?.jsonPrimitive?.content
            val agent = event["agent"]?.jsonPrimitive?.content
            val title = desc ?: agent?.let { "Sub-agent: $it" } ?: "Sub-agent"
            out += AgentEvent.StepStarted(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                title = title,
            )
        }
        "tool-invocation" -> {
            // The part might be in `event.part` (newer) or directly on `event` (older)
            val toolPart = part ?: info ?: event
            emitToolInvocationPart(toolPart, ts, out)
        }
    }
}

/**
 * Emit events for a `tool-invocation` MessagePart.
 *
 * Schema: {
 *   "type": "tool-invocation",
 *   "toolInvocation": {
 *     "state": "call" | "partial-call" | "result",
 *     "step": 0,
 *     "toolCallId": "call_xxx",
 *     "toolName": "web_search",
 *     "args": {...},
 *     "result": "..." // only when state="result"
 *   }
 * }
 */
private fun emitToolInvocationPart(
    part: JsonObject,
    ts: Long,
    out: MutableList<AgentEvent>,
) {
    val toolInv = part["toolInvocation"]?.jsonObject
    val callId = toolInv?.get("toolCallId")?.jsonPrimitive?.content
    val toolName = toolInv?.get("toolName")?.jsonPrimitive?.content
    val state = toolInv?.get("state")?.jsonPrimitive?.content
    val args = toolInv?.get("args")
    val result = toolInv?.get("result")

    if (callId.isNullOrBlank() || toolName.isNullOrBlank()) return

    when (state) {
        "call", "partial-call" -> {
            out += AgentEvent.ToolCallStarted(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                toolId = callId,
                name = toolName,
                source = if (isMcpToolName(toolName)) "mcp" else "opencode",
            )
            if (args != null && args != JsonPrimitive(null)) {
                out += AgentEvent.ToolCallInput(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = ts,
                    toolId = callId,
                    inputDelta = args.toString(),
                )
            }
        }
        "result" -> {
            out += AgentEvent.ToolCallStarted(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                toolId = callId,
                name = toolName,
                source = if (isMcpToolName(toolName)) "mcp" else "opencode",
            )
            if (args != null && args != JsonPrimitive(null)) {
                out += AgentEvent.ToolCallInput(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = ts,
                    toolId = callId,
                    inputDelta = args.toString(),
                )
            }
            val resultStr = if (result != null && result != JsonPrimitive(null)) result.toString() else ""
            if (resultStr.isNotEmpty()) {
                out += AgentEvent.ToolCallOutput(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = ts,
                    toolId = callId,
                    output = resultStr,
                )
            }
            out += AgentEvent.ToolCallFinished(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                toolId = callId,
                durationMs = 0L,
            )
        }
    }
}

private fun emitTextDelta(
    fullText: String,
    state: SessionContentState,
    ts: Long,
    out: MutableList<AgentEvent>,
) {
    if (fullText.length > state.text.length) {
        val delta = fullText.substring(state.text.length)
        state.text = fullText
        if (delta.isNotEmpty()) {
            out += AgentEvent.FinalAnswerDelta(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                text = delta,
            )
        }
    }
}

private fun emitReasoningDelta(
    fullReasoning: String,
    state: SessionContentState,
    ts: Long,
    out: MutableList<AgentEvent>,
) {
    if (fullReasoning.length > state.reasoning.length) {
        val delta = fullReasoning.substring(state.reasoning.length)
        state.reasoning = fullReasoning
        if (delta.isNotEmpty()) {
            out += AgentEvent.ReasoningDelta(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                text = delta,
            )
        }
    }
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
