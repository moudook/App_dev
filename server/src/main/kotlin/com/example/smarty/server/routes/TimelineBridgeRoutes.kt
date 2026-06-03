package com.example.smarty.server.routes

import com.example.smarty.protocol.AgentEvent
import com.example.smarty.server.agent.AgentRunManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

                val resolved = com.example.smarty.server.agent.ActiveSessionManager.resolveOpencodeSessionId(sessionID)
                if (resolved != null) {
                    val (userId, chatSessionId) = resolved
                    val streamEvents = translatePluginEvent(kind, event, ts, sessionID)
                    for (streamEvent in streamEvents) {
                        AgentRunManager.emitEvent(chatSessionId, streamEvent)
                    }
                    if (streamEvents.isNotEmpty()) {
                        logger.debug("[STREAM-TRANSLATE] kind=$kind -> ${streamEvents.size} event(s) for user=$userId chat=$chatSessionId")
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

private val INTERACTIVE_TOOLS = setOf(
    "ask_user", "ask", "askuser", "confirm", "question", "clarify", "input"
)

private fun JsonElement?.str(): String? = this?.jsonPrimitive?.content

private fun JsonElement?.bool(): Boolean? = this?.jsonPrimitive?.let {
    when (it.content.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

private fun JsonElement?.int(): Int? = this?.jsonPrimitive?.let { it.content.toIntOrNull() }

private fun JsonElement?.long(): Long? = this?.jsonPrimitive?.let { it.content.toLongOrNull() }

private fun JsonElement?.double(): Double? = this?.jsonPrimitive?.let { it.content.toDoubleOrNull() }

private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

private fun translatePluginEvent(
    kind: String,
    event: JsonObject,
    ts: Long,
    sessionId: String,
): List<AgentEvent> {
    val out = mutableListOf<AgentEvent>()
    val eid = { UUID.randomUUID().toString() }

    when (kind) {

        // ── SESSION LIFECYCLE ──────────────────────────────────────────────

        "session.idle" -> {
            out += AgentEvent.Done(eventId = eid(), timestamp = ts)
        }

        "session.error" -> {
            val msg = event["error"]?.let {
                if (it is JsonObject) it["message"]?.jsonPrimitive?.content
                else it.jsonPrimitive?.content
            } ?: event["message"]?.jsonPrimitive?.content ?: "Unknown error"
            out += AgentEvent.Error(eventId = eid(), timestamp = ts, message = msg)
        }

        "session.aborted" -> {
            val reason = event["reason"]?.jsonPrimitive?.content ?: "aborted"
            out += AgentEvent.Error(eventId = eid(), timestamp = ts, message = reason)
        }

        // ── PART UPDATES — route by phase + partType ───────────────────────

        "part.updated" -> {
            val phase = event["phase"]?.jsonPrimitive?.content ?: "streaming"
            val partType = event["partType"]?.jsonPrimitive?.content ?: ""
            val sId = sessionId
            val msgId = event["messageID"]?.jsonPrimitive?.content ?: ""

            when (partType) {

                "text" -> {
                    if (phase == "streaming") {
                        val isThinkingHint = event["isThinkingHint"]?.bool() ?: false
                        if (isThinkingHint) {
                            out += AgentEvent.ThinkingActive(
                                eventId = eid(), timestamp = ts,
                                sessionId = sId, messageId = msgId,
                            )
                        } else {
                            out += AgentEvent.StreamingActive(
                                eventId = eid(), timestamp = ts,
                                sessionId = sId, messageId = msgId,
                            )
                        }
                    }
                }

                "reasoning" -> {
                    if (phase == "snapshot") {
                        val reasoning = event["reasoning"]?.jsonPrimitive?.content ?: ""
                        val durationMs = event["thinkingDurationMs"]?.jsonPrimitive?.let {
                            it.content.toLongOrNull()
                        }
                        out += AgentEvent.ReasoningBlock(
                            eventId = eid(), timestamp = ts,
                            sessionId = sId, messageId = msgId,
                            partId = event["partID"]?.jsonPrimitive?.content ?: "",
                            content = reasoning,
                            thinkingDurationMs = durationMs,
                        )
                    }
                }

                "tool" -> {
                    val toolName = event["tool"]?.jsonPrimitive?.content ?: ""
                    val callId = event["toolCallID"]?.jsonPrimitive?.content ?: ""
                    val state = event["state"]?.jsonPrimitive?.content ?: ""
                    val isMcp = event["isMcpTool"]?.jsonPrimitive?.let {
                        it.content.lowercase() in listOf("true", "yes", "1")
                    } ?: false
                    val isInteractive = INTERACTIVE_TOOLS.contains(toolName.lowercase())

                    when (state) {
                        "pending" -> {
                            out += AgentEvent.ToolStart(
                                eventId = eid(), timestamp = ts,
                                toolId = callId, name = toolName,
                                isMcpTool = isMcp, isInteractive = isInteractive,
                            )
                        }
                        "running" -> {
                            val inputSummary = buildInputSummary(toolName, event["input"]?.jsonObject)
                            out += AgentEvent.ToolStart(
                                eventId = eid(), timestamp = ts,
                                toolId = callId, name = toolName,
                                args = event["input"]?.toString(),
                                isMcpTool = isMcp, isInteractive = isInteractive,
                                inputSummary = inputSummary,
                            )
                        }
                        "complete" -> {
                            val inputSummary = buildInputSummary(toolName, event["input"]?.jsonObject)
                            val outputSummary = summarizeOutput(
                                event["output"]?.jsonPrimitive?.content, toolName
                            ) ?: ""
                            out += AgentEvent.ToolStart(
                                eventId = eid(), timestamp = ts,
                                toolId = callId, name = toolName,
                                args = event["input"]?.toString(),
                                isMcpTool = isMcp, isInteractive = isInteractive,
                                inputSummary = inputSummary,
                            )
                            out += AgentEvent.ToolEnd(
                                eventId = eid(), timestamp = ts,
                                toolId = callId,
                                result = event["output"]?.toString(),
                                isMcpTool = isMcp, isInteractive = isInteractive,
                                success = true, outputSummary = outputSummary,
                            )
                        }
                        "error" -> {
                            val errMsg = event["error"]?.jsonPrimitive?.content ?: "Tool failed"
                            out += AgentEvent.ToolStart(
                                eventId = eid(), timestamp = ts,
                                toolId = callId, name = toolName,
                                isMcpTool = isMcp, isInteractive = isInteractive,
                            )
                            out += AgentEvent.ToolEnd(
                                eventId = eid(), timestamp = ts,
                                toolId = callId,
                                error = errMsg,
                                isMcpTool = isMcp, isInteractive = isInteractive,
                                success = false, outputSummary = errMsg,
                            )
                        }
                    }
                }

                "step-start" -> {
                    val step = event["step"]?.jsonPrimitive?.let {
                        it.content.toIntOrNull()
                    } ?: 0
                    out += AgentEvent.StepStart(
                        eventId = eid(), timestamp = ts,
                        title = "Step $step", stepNumber = step, messageId = msgId,
                    )
                }

                "step-finish" -> {
                    val step = event["step"]?.jsonPrimitive?.let {
                        it.content.toIntOrNull()
                    } ?: 0
                    val cost = event["cost"]?.jsonPrimitive?.let {
                        it.content.toDoubleOrNull()
                    } ?: 0.0
                    val tokensObj = event["tokens"]?.jsonObject
                    out += AgentEvent.StepEnd(
                        eventId = eid(), timestamp = ts,
                        stepNumber = step, success = true,
                        cost = cost,
                        tokensInput = tokensObj?.get("input")?.jsonPrimitive?.let {
                            it.content.toIntOrNull()
                        } ?: 0,
                        tokensOutput = tokensObj?.get("output")?.jsonPrimitive?.let {
                            it.content.toIntOrNull()
                        } ?: 0,
                    )
                }

                "subtask" -> {
                    val agentName = event["agent"]?.jsonPrimitive?.content ?: "sub-agent"
                    val desc = event["description"]?.jsonPrimitive?.content ?: ""
                    val state = event["state"]?.jsonPrimitive?.content ?: "pending"
                    out += AgentEvent.SubAgentEvent(
                        eventId = eid(), timestamp = ts,
                        sessionId = sId, messageId = msgId,
                        agent = agentName, description = desc, state = state,
                    )
                }
            }
        }

        // ── TOOL HOOKS — full args from tool.execute.before/after ──────────

        "tool.before" -> {
            val toolName = event["tool"]?.jsonPrimitive?.content ?: ""
            val callId = event["callID"]?.jsonPrimitive?.content ?: ""
            val isMcp = event["isMcpTool"]?.jsonPrimitive?.let {
                it.content.lowercase() in listOf("true", "yes", "1")
            } ?: false
            val msgId = event["messageID"]?.jsonPrimitive?.content ?: ""
            val argsObj = event["args"]?.jsonObject
            val inputSummary = buildInputSummary(toolName, argsObj)

            out += AgentEvent.ToolStart(
                eventId = eid(), timestamp = ts,
                toolId = callId, name = toolName,
                args = argsObj?.toString(),
                isMcpTool = isMcp, isInteractive = INTERACTIVE_TOOLS.contains(toolName.lowercase()),
                inputSummary = inputSummary,
            )
        }

        "tool.after" -> {
            val toolName = event["tool"]?.jsonPrimitive?.content ?: ""
            val callId = event["callID"]?.jsonPrimitive?.content ?: ""
            val isMcp = event["isMcpTool"]?.jsonPrimitive?.let {
                it.content.lowercase() in listOf("true", "yes", "1")
            } ?: false
            val msgId = event["messageID"]?.jsonPrimitive?.content ?: ""
            val result = event["result"]?.jsonPrimitive?.content
            val error = event["error"]?.jsonPrimitive?.content

            out += AgentEvent.ToolEnd(
                eventId = eid(), timestamp = ts,
                toolId = callId,
                result = result, error = error,
                isMcpTool = isMcp, isInteractive = INTERACTIVE_TOOLS.contains(toolName.lowercase()),
                success = error == null,
                outputSummary = summarizeOutput(result, toolName) ?: error ?: "No output",
            )
        }

        // ── INTERACTIVE TOOL — ask_user card ───────────────────────────────

        "user.input.required" -> {
            val callId = event["callID"]?.jsonPrimitive?.content ?: ""
            val question = event["question"]?.jsonPrimitive?.content ?: ""
            val optionsArr = (event["options"] as? JsonArray)?.mapNotNull {
                it.jsonPrimitive?.content
            } ?: emptyList()
            val inputMode = event["inputMode"]?.jsonPrimitive?.content ?: "choice"
            val toolName = event["tool"]?.jsonPrimitive?.content ?: "ask_user"

            out += AgentEvent.ApprovalRequested(
                eventId = eid(), timestamp = ts,
                toolId = callId, toolName = toolName,
                question = question, options = optionsArr,
                inputMode = inputMode, interactive = true,
            )
        }

        "user.input.resolved" -> {
            val callId = event["callID"]?.jsonPrimitive?.content ?: ""
            val response = event["response"]?.jsonPrimitive?.content ?: ""
            val declined = event["declined"]?.jsonPrimitive?.let {
                it.content.lowercase() in listOf("true", "yes", "1")
            } ?: false

            out += AgentEvent.ApprovalResult(
                eventId = eid(), timestamp = ts,
                toolId = callId,
                granted = !declined, feedback = response,
            )
        }

        // ── FINAL SNAPSHOT — message.updated ────────────────────────────────

        "message.updated" -> {
            val sId = sessionId
            val msgId = event["messageID"]?.jsonPrimitive?.content ?: ""
            val rawParts = event["parts"]?.jsonObject?.get("parts")
                ?: event["parts"]
            val parts = rawParts as? JsonArray ?: return out

            var cleanReasoningText = ""
            var cleanResponseText = ""

            for (part in parts) {
                val partObj = part.jsonObject
                val partType = partObj["type"]?.jsonPrimitive?.content ?: continue

                when (partType) {
                    "reasoning" -> {
                        val r = partObj["reasoning"]?.jsonPrimitive?.content ?: ""
                        if (r.isNotBlank()) cleanReasoningText += r
                    }
                    "text" -> {
                        val t = partObj["content"]?.jsonPrimitive?.content
                            ?: partObj["text"]?.jsonPrimitive?.content
                            ?: ""
                        if (t.isNotBlank()) {
                            if (t.contains("<think>") || t.contains("</think>")) {
                                val (thinking, response) = splitThinkTags(t)
                                if (thinking.isNotBlank()) cleanReasoningText += thinking
                                if (response.isNotBlank()) cleanResponseText += response
                            } else {
                                cleanResponseText += t
                            }
                        }
                    }
                }
            }

            if (cleanReasoningText.isNotBlank()) {
                out += AgentEvent.ReasoningBlock(
                    eventId = eid(), timestamp = ts,
                    sessionId = sId, messageId = msgId,
                    partId = "snapshot-reasoning",
                    content = cleanReasoningText,
                )
            }

            if (cleanResponseText.isNotBlank()) {
                out += AgentEvent.ResponseBlock(
                    eventId = eid(), timestamp = ts,
                    sessionId = sId, messageId = msgId,
                    content = cleanResponseText,
                )
            }
        }

        // ── PERMISSION GATES ───────────────────────────────────────────────

        "permission.asked" -> {
            val toolName = event["tool"]?.jsonPrimitive?.content ?: ""
            val callId = event["callID"]?.jsonPrimitive?.content
                ?: event["callId"]?.jsonPrimitive?.content
                ?: UUID.randomUUID().toString()
            out += AgentEvent.ApprovalRequested(
                eventId = eid(), timestamp = ts,
                toolId = callId, toolName = toolName,
                question = "Allow $toolName to run?",
                options = listOf("Allow", "Deny"),
                inputMode = "choice", interactive = false,
            )
        }

        "permission.replied" -> {
            val granted = event["granted"]?.jsonPrimitive?.let {
                it.content.lowercase() in listOf("true", "yes", "1")
            } ?: false
            out += AgentEvent.ApprovalResult(
                eventId = eid(), timestamp = ts,
                toolId = event["tool"]?.jsonPrimitive?.content ?: "",
                granted = granted,
                feedback = if (granted) "Allowed" else "Denied",
            )
        }

        // ── BACKWARD COMPAT: legacy delta events ───────────────────────────

        "message.part.delta" -> {
            val field = event["field"]?.jsonPrimitive?.content
            val delta = event["delta"]?.jsonPrimitive?.content
            if (!delta.isNullOrEmpty()) {
                when (field) {
                    "text" -> out += AgentEvent.TextDelta(
                        eventId = eid(), timestamp = ts, text = delta,
                    )
                    "reasoning" -> out += AgentEvent.ReasoningDelta(
                        eventId = eid(), timestamp = ts, text = delta,
                    )
                }
            }
        }

        "message.completed" -> {
            out += AgentEvent.Done(eventId = eid(), timestamp = ts)
        }

        "session.compacted" -> {
            out += AgentEvent.CompactionMarker(
                eventId = eid(), timestamp = ts, sessionId = sessionId,
            )
        }
    }

    return out
}

private fun splitThinkTags(text: String): Pair<String, String> {
    val thinkPattern = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
    val thinking = thinkPattern.findAll(text).joinToString("\n") { it.groupValues[1].trim() }
    val response = thinkPattern.replace(text, "").trim()
    return Pair(thinking, response)
}

private fun buildInputSummary(toolName: String, args: JsonObject?): String {
    if (args == null) return ""
    return when (toolName.lowercase()) {
        "websearch", "web_search" ->
            args["query"]?.jsonPrimitive?.content?.let { "Search: \"$it\"" } ?: ""
        "webfetch", "web_fetch" ->
            args["url"]?.jsonPrimitive?.content?.let { "Fetch: $it" } ?: ""
        "bash" ->
            args["command"]?.jsonPrimitive?.content?.let { cmd ->
                if (cmd.length > 60) "$ ${cmd.take(57)}…" else "$ $cmd"
            } ?: ""
        "memory_save", "memory" ->
            args["content"]?.jsonPrimitive?.content?.let {
                if (it.length > 60) "Save: \"${it.take(57)}…\"" else "Save: \"$it\""
            } ?: ""
        "ask_user", "ask" ->
            args["question"]?.jsonPrimitive?.content ?: ""
        "generate_image" ->
            args["prompt"]?.jsonPrimitive?.content?.let {
                if (it.length > 60) "Image: \"${it.take(57)}…\"" else "Image: \"$it\""
            } ?: ""
        "schedule_add" ->
            args["title"]?.jsonPrimitive?.content?.let { "Event: $it" } ?: ""
        "remind_set" ->
            args["message"]?.jsonPrimitive?.content?.let { "Remind: $it" } ?: ""
        else -> {
            args.entries.firstOrNull { it.value is JsonPrimitive }
                ?.let { (k, v) -> "$k: ${v.jsonPrimitive.content.take(50)}" } ?: ""
        }
    }
}

private fun summarizeOutput(result: String?, toolName: String): String? {
    if (result == null || result == "null") return null
    val clean = result.trim()
    return when {
        clean.length <= 120 -> clean
        toolName.lowercase().contains("search") ->
            "Found ${clean.lines().count { it.isNotBlank() }} results"
        toolName.lowercase().contains("memory") -> "Saved to memory"
        toolName.lowercase().contains("schedule") -> "Event scheduled"
        toolName.lowercase().contains("remind") -> "Reminder set"
        toolName.lowercase().contains("image") ->
            if (clean.startsWith("http")) "Image generated" else clean.take(100)
        else -> "${clean.take(100)}${if (clean.length > 100) "…" else ""}"
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

    fun ingest(kind: String, sessionID: String, event: JsonObject, ts: Long) {
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
                logger.info("[TIMELINE] User input required: tool=$tool")
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
