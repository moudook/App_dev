package com.example.smarty.server.routes

import com.example.smarty.protocol.AgentEvent
import com.example.smarty.server.agent.ActiveSessionManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

fun Application.configureTimelineBridgeRoutes() {
    val bridge = TimelineBridgeService

    routing {
        post("/opencode/events") {
            val body = call.receiveText()
            val ts = System.currentTimeMillis()

            try {
                val event = Json.parseToJsonElement(body).jsonObject
                val kind = event["kind"]?.jsonPrimitive?.content ?: "unknown"
                val sessionID = event["sessionID"]?.jsonPrimitive?.content ?: "no-session"

                bridge.ingest(kind, sessionID, event, ts)

                val resolved = ActiveSessionManager.resolveOpencodeSessionId(sessionID)
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
                    }
                }

                call.respond(HttpStatusCode.OK, mapOf("ok" to true))
            } catch (e: Exception) {
                val preview = body.substring(0, minOf(body.length, 200))
                logger.error("[KTOR-RECV-ERROR] error=${e.message} body=$preview")
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "unknown")))
            }
        }
    }

    logger.info("[KTOR] /opencode/events route registered")
}

private val logger = LoggerFactory.getLogger("com.example.smarty.server.routes.TimelineBridgeRoutes")

private fun isMcpToolName(name: String?): Boolean = !name.isNullOrBlank() && name.startsWith("mcp")

private data class SessionContentState(
    var text: String = "",
    var reasoning: String = "",
)

private val sessionContentStates = ConcurrentHashMap<String, SessionContentState>()

private val debugLoggedKinds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
private val msgUpdatedCounters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
private const val MSG_UPDATED_LOG_LIMIT = 10

private fun translatePluginEvent(
    kind: String,
    event: JsonObject,
    ts: Long,
    sessionId: String,
): List<AgentEvent> {
    val out = mutableListOf<AgentEvent>()

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
        "session.idle", "session.completed" -> {
            sessionContentStates.remove(sessionId)
            out += AgentEvent.Done(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
            )
        }

        "session.error" -> {
            val msg = event["error"]?.let {
                if (it is JsonObject) it["message"]?.jsonPrimitive?.content
                else it.jsonPrimitive?.content
            } ?: event["message"]?.jsonPrimitive?.content ?: "Unknown error"
            out += AgentEvent.Error(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                message = msg,
            )
        }

        "session.aborted" -> {
            val reason = event["reason"]?.jsonPrimitive?.content ?: "aborted"
            out += AgentEvent.Error(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                message = reason,
            )
        }

        "session.status" -> {
            val status = when (val s = event["status"]) {
                is JsonPrimitive -> s.content
                is JsonObject -> s["type"]?.jsonPrimitive?.content
                else -> null
            } ?: event["info"]?.jsonObject?.get("status")?.jsonPrimitive?.content
            if (status == "error" || status == "failed") {
                val msg = event["error"]?.let {
                    if (it is JsonObject) it["message"]?.jsonPrimitive?.content
                    else it.jsonPrimitive?.content
                } ?: event["message"]?.jsonPrimitive?.content ?: status ?: "Unknown error"
                out += AgentEvent.Error(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = ts,
                    message = msg,
                )
            }
        }

        "message.completed" -> {
            out += AgentEvent.Done(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
            )
        }

        "message.updated" -> {
            extractFromMessageParts(event, ts, sessionId, out)
        }

        "message.part.delta" -> {
            val field = event["field"]?.jsonPrimitive?.content
            val delta = event["delta"]?.jsonPrimitive?.content
            if (!delta.isNullOrEmpty()) {
                when (field) {
                    "text" -> out += AgentEvent.TextDelta(
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

        "part.updated" -> {
            handlePartUpdated(event, ts, sessionId, out)
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

        "message.part.text", "message.part.text_delta" -> {
            val delta = event["delta"]?.jsonPrimitive?.content
                ?: event["text"]?.jsonPrimitive?.content
                ?: ""
            if (delta.isNotEmpty()) {
                out += AgentEvent.TextDelta(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = ts,
                    text = delta,
                )
            }
        }

        "message.part.step_start" -> {
            val step = event["step"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            out += AgentEvent.StepStart(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                title = "Step $step",
            )
        }

        "message.part.step_finish" -> {
            val step = event["step"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            out += AgentEvent.StepEnd(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                stepNumber = step,
                success = true,
            )
        }

        "message.part.subtask" -> {
            val desc = event["description"]?.jsonPrimitive?.content
            val agent = event["agent"]?.jsonPrimitive?.content
            val title = desc ?: agent?.let { "Sub-agent: $it" } ?: "Sub-agent"
            out += AgentEvent.StepStart(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                title = title,
            )
        }

        "tool.before" -> {
            val tool = event["tool"]?.jsonPrimitive?.content
            val callId = event["callID"]?.jsonPrimitive?.content
                ?: event["callId"]?.jsonPrimitive?.content
            if (tool.isNullOrBlank() || callId.isNullOrBlank()) return out
            val args = event["args"]
            val argsStr = if (args != null && args != JsonPrimitive(null)) args.toString() else null
            out += AgentEvent.ToolStart(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                toolId = callId,
                name = tool,
                args = argsStr,
            )
        }

        "tool.after" -> {
            val callId = event["callID"]?.jsonPrimitive?.content
                ?: event["callId"]?.jsonPrimitive?.content
            if (callId.isNullOrBlank()) return out
            val result = event["result"]
            val error = event["error"]
            val resultStr = when {
                error != null && error != JsonPrimitive(null) -> null
                result != null && result != JsonPrimitive(null) -> result.toString()
                else -> null
            }
            val errorStr = when {
                error != null && error != JsonPrimitive(null) -> error.toString()
                else -> null
            }
            out += AgentEvent.ToolEnd(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                toolId = callId,
                result = resultStr,
                error = errorStr,
            )
        }

        "permission.asked" -> {
            val callId = event["callID"]?.jsonPrimitive?.content
                ?: event["callId"]?.jsonPrimitive?.content
                ?: return out
            val tool = event["tool"]?.jsonPrimitive?.content ?: return out
            val question = event["title"]?.jsonPrimitive?.content ?: tool
            out += AgentEvent.ApprovalRequested(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                toolId = callId,
                toolName = tool,
                question = question,
                interactive = false,
            )
        }

        "permission.granted" -> {
            val callId = event["callID"]?.jsonPrimitive?.content
                ?: event["callId"]?.jsonPrimitive?.content
                ?: return out
            out += AgentEvent.ApprovalResult(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                toolId = callId,
                granted = true,
            )
        }

        "permission.denied" -> {
            val callId = event["callID"]?.jsonPrimitive?.content
                ?: event["callId"]?.jsonPrimitive?.content
                ?: return out
            out += AgentEvent.ApprovalResult(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                toolId = callId,
                granted = false,
            )
        }

        "user.input.required" -> {
            val callId = event["callID"]?.jsonPrimitive?.content
                ?: event["callId"]?.jsonPrimitive?.content
                ?: UUID.randomUUID().toString()
            val tool = event["tool"]?.jsonPrimitive?.content ?: "ask_user"
            val question = event["question"]?.jsonPrimitive?.content
                ?: event["title"]?.jsonPrimitive?.content
                ?: tool
            val options = (event["options"] as? JsonArray)?.mapNotNull { it.jsonPrimitive?.content }
                ?: emptyList()
            out += AgentEvent.ApprovalRequested(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                toolId = callId,
                toolName = tool,
                question = question,
                options = options,
                interactive = true,
            )
        }

        "mcp.tools.changed" -> {
            out += AgentEvent.StateSync(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                syncType = "mcp_tools",
                data = event.toString().take(4096),
            )
        }
    }

    return out
}

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
        return
    }

    val state = sessionContentStates.getOrPut(sessionId) { SessionContentState() }

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
            "reasoning" -> emitReasoningDelta(part["reasoning"]?.jsonPrimitive?.content ?: "", state, ts, out)
            "tool-invocation" -> emitToolInvocationPart(part, ts, out)
            "step-start" -> out += AgentEvent.StepStart(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                title = "Step",
            )
        }
    }

    if (out.isEmpty()) {
        val fullText = messageObj["text"]?.jsonPrimitive?.content
            ?: messageObj["content"]?.jsonPrimitive?.content
        if (!fullText.isNullOrEmpty()) {
            emitTextDelta(fullText, state, ts, out)
        }
    }

    if (out.isEmpty()) {
        val fullText = event["text"]?.jsonPrimitive?.content
            ?: event["content"]?.jsonPrimitive?.content
        if (!fullText.isNullOrEmpty()) {
            emitTextDelta(fullText, state, ts, out)
        }
    }
}

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

    val argsStr = if (args != null && args != JsonPrimitive(null)) args.toString() else null

    when (state) {
        "call", "partial-call" -> {
            out += AgentEvent.ToolStart(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                toolId = callId,
                name = toolName,
                args = argsStr,
            )
        }
        "result" -> {
            out += AgentEvent.ToolStart(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                toolId = callId,
                name = toolName,
                args = argsStr,
            )
            val resultStr = if (result != null && result != JsonPrimitive(null)) result.toString() else null
            out += AgentEvent.ToolEnd(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                toolId = callId,
                result = resultStr,
            )
        }
    }
}

private fun handlePartUpdated(
    event: JsonObject,
    ts: Long,
    sessionId: String,
    out: MutableList<AgentEvent>,
) {
    val partType = event["partType"]?.jsonPrimitive?.content ?: return
    val state = sessionContentStates.getOrPut(sessionId) { SessionContentState() }

    when (partType) {
        "text" -> {
            val text = event["text"]?.jsonPrimitive?.content ?: return
            emitTextDelta(text, state, ts, out)
        }
        "reasoning" -> {
            val reasoning = event["reasoning"]?.jsonPrimitive?.content ?: return
            emitReasoningDelta(reasoning, state, ts, out)
        }
        "tool" -> {
            val tool = event["tool"]?.jsonPrimitive?.content ?: return
            val callId = event["toolCallID"]?.jsonPrimitive?.content ?: return
            val toolState = event["state"]?.jsonPrimitive?.content ?: "unknown"
            val argsStr = event["input"]?.toString() ?: event["raw"]?.toString()
            val resultStr = event["output"]?.toString()
            val errorStr = event["error"]?.toString()

            when (toolState) {
                "running", "pending" -> {
                    out += AgentEvent.ToolStart(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = ts,
                        toolId = callId,
                        name = tool,
                        args = argsStr,
                    )
                }
                "complete" -> {
                    out += AgentEvent.ToolStart(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = ts,
                        toolId = callId,
                        name = tool,
                        args = argsStr,
                    )
                    out += AgentEvent.ToolEnd(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = ts,
                        toolId = callId,
                        result = resultStr,
                        error = errorStr,
                    )
                }
                "error" -> {
                    out += AgentEvent.ToolStart(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = ts,
                        toolId = callId,
                        name = tool,
                        args = argsStr,
                    )
                    out += AgentEvent.ToolEnd(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = ts,
                        toolId = callId,
                        result = null,
                        error = errorStr,
                    )
                }
            }
        }
        "step-start" -> {
            val step = event["step"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            out += AgentEvent.StepStart(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                title = "Step $step",
                stepNumber = step,
            )
        }
        "step-finish" -> {
            out += AgentEvent.StepEnd(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                success = true,
            )
        }
        "subtask" -> {
            val desc = event["description"]?.jsonPrimitive?.content
            val agent = event["agent"]?.jsonPrimitive?.content
            val title = desc ?: agent?.let { "Sub-agent: $it" } ?: "Sub-agent"
            out += AgentEvent.StepStart(
                eventId = UUID.randomUUID().toString(),
                timestamp = ts,
                title = title,
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
            out += AgentEvent.TextDelta(
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
            val toRemove = list.subList(0, minOf(EVICTION_BATCH, list.size))
            toRemove.clear()
        }
        list.add(EventSnapshot(kind, sessionID, ts, event))

        when (kind) {
            "session.created" -> logger.info("[TIMELINE] New session: $sessionID")
            "session.idle" -> logger.info("[TIMELINE] Session done: $sessionID | events=${list.size}")
            "user.input.required" -> {
                val tool = event["tool"]?.jsonPrimitive?.content ?: "?"
                val question = (event["question"]?.jsonPrimitive?.content ?: "").take(80)
                logger.info("[TIMELINE] User input required: tool=$tool (question truncated)")
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

    fun getTimeline(sessionID: String): List<EventSnapshot> = timelines[sessionID]?.toList() ?: emptyList()

    fun getAllSessionIDs(): Set<String> = timelines.keys
}
