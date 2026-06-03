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
                val partialEvent = Json.parseToJsonElement(body).jsonObject
                val partialSessionId = partialEvent["sessionID"]?.jsonPrimitive?.content
                val partialMsgId = partialEvent["messageID"]?.jsonPrimitive?.content
                if (partialSessionId != null) {
                    if (partialMsgId != null) cleanupContentState(partialSessionId, partialMsgId)
                    val prefix = "$partialSessionId:"
                    partTextLengths.keys.removeAll { it.startsWith(prefix) }
                }
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "unknown")))
            }
        }
    }

    logger.info("[KTOR] /opencode/events route registered")
}

private val logger = LoggerFactory.getLogger("com.example.smarty.server.routes.TimelineBridgeRoutes")

private val partTextLengths = ConcurrentHashMap<String, Int>()

/** Track accumulated text per (sessionID, messageID) for streaming delta -> block translation. */
private data class MessageContentState(
    val textBuilder: java.lang.StringBuffer = java.lang.StringBuffer(),
    val reasoningBuilder: java.lang.StringBuffer = java.lang.StringBuffer(),
    var lastSentReasoningLen: Int = 0,
    var lastSentResponseLen: Int = 0,
)

private val sessionContentStates = ConcurrentHashMap<String, MessageContentState>()

private fun contentStateKey(sessionId: String, msgId: String) = "$sessionId:$msgId"

private fun cleanupContentState(sessionId: String, msgId: String) {
    sessionContentStates.remove(contentStateKey(sessionId, msgId))
    // Also clean up part lengths for this session to ensure fresh deltas for next turn
    val prefix = "$sessionId:"
    partTextLengths.keys.removeAll { it.startsWith(prefix) }
}

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

    logger.debug("[TRANSLATE-IN] kind=$kind")

    when (kind) {

        // ── SESSION LIFECYCLE ──────────────────────────────────────────────

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
            val partId = event["partID"]?.jsonPrimitive?.content ?: "default-part"

            when (partType) {
                "text", "reasoning" -> {
                    if (phase == "streaming") {
                        val deltaObj = event["delta"]?.jsonObject
                        val rawText = deltaObj?.get("text")?.jsonPrimitive?.content 
                            ?: event["text"]?.jsonPrimitive?.content 
                            ?: ""
                        val rawReasoning = deltaObj?.get("reasoning")?.jsonPrimitive?.content
                            ?: event["reasoning"]?.jsonPrimitive?.content
                            ?: ""
                        
                        val key = contentStateKey(sId, msgId)
                        val state = sessionContentStates.getOrPut(key) { MessageContentState() }

                        // 1. Handle native reasoning from daemon
                        if (rawReasoning.isNotEmpty()) {
                            out += AgentEvent.ReasoningDelta(eventId = eid(), timestamp = ts, text = rawReasoning)
                            out += AgentEvent.ThinkingActive(eventId = eid(), timestamp = ts, sessionId = sId, messageId = msgId)
                        }

                        // 2. Handle text (may still contain interleaved tags from some models)
                        if (rawText.isNotEmpty()) {
                            // Calculate specific part delta for tracking
                            val partKey = "$sessionId:$partId"
                            val lastPartLen = partTextLengths.getOrDefault(partKey, 0)
                            val partDelta = if (rawText.length > lastPartLen) rawText.substring(lastPartLen) else ""
                            partTextLengths[partKey] = rawText.length

                            if (partDelta.isNotEmpty()) {
                                state.textBuilder.append(partDelta)
                                
                                // Split only the NEW text to find tags
                                val (accumulatedThinking, accumulatedResponse) = splitThinkTags(state.textBuilder.toString())
                                
                                if (accumulatedThinking.length > state.lastSentReasoningLen) {
                                    val d = accumulatedThinking.substring(state.lastSentReasoningLen)
                                    state.lastSentReasoningLen = accumulatedThinking.length
                                    out += AgentEvent.ReasoningDelta(eventId = eid(), timestamp = ts, text = d)
                                    out += AgentEvent.ThinkingActive(eventId = eid(), timestamp = ts, sessionId = sId, messageId = msgId)
                                }

                                if (accumulatedResponse.length > state.lastSentResponseLen) {
                                    val d = accumulatedResponse.substring(state.lastSentResponseLen)
                                    state.lastSentResponseLen = accumulatedResponse.length
                                    out += AgentEvent.TextDelta(eventId = eid(), timestamp = ts, text = d)
                                    out += AgentEvent.StreamingActive(eventId = eid(), timestamp = ts, sessionId = sId, messageId = msgId)
                                }
                            }
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

            var combinedText = ""
            val separateReasoning = StringBuilder()

            for (part in parts) {
                val partObj = part.jsonObject
                val partType = partObj["type"]?.jsonPrimitive?.content ?: continue

                when (partType) {
                    "reasoning" -> {
                        val r = partObj["reasoning"]?.jsonPrimitive?.content ?: ""
                        if (r.isNotBlank()) separateReasoning.append(r).append("\n")
                    }
                    "text" -> {
                        val t = partObj["content"]?.jsonPrimitive?.content
                            ?: partObj["text"]?.jsonPrimitive?.content
                            ?: ""
                        if (t.isNotBlank()) {
                            combinedText += t
                        }
                    }
                }
            }

            // Robust split of thinking tags from combined text
            val (thinkingFromText, cleanResponse) = splitThinkTags(combinedText)
            val finalReasoning = (separateReasoning.toString() + thinkingFromText).trim()

            if (finalReasoning.isNotBlank()) {
                out += AgentEvent.ReasoningBlock(
                    eventId = eid(), timestamp = ts,
                    sessionId = sId, messageId = msgId,
                    partId = "snapshot-reasoning",
                    content = finalReasoning,
                )
            }

            val finalResponse = if (cleanResponse.isNotBlank()) cleanResponse else " "
            out += AgentEvent.ResponseBlock(
                eventId = eid(), timestamp = ts,
                sessionId = sId, messageId = msgId,
                content = finalResponse,
            )
            
            // Clean up session content states since this message is now finalized.
            // NOTE: Do NOT emit Done here — message.completed fires immediately after
            // message.updated (plugin v2 behavior) and will emit Done. Double-Done
            // causes double replaceMessage() on Android → DB duplication.
            cleanupContentState(sessionId, msgId)
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

        // ── LEGACY DAEMON DELTAS (suppressed — no-op) ──
        // Raw message.part.delta is NOT forwarded because part.updated already
        // emits clean (tag-split) deltas. Passing raw deltas would double content.

        "message.part.delta" -> {
            // no-op: part.updated is the single source of truth for streaming
        }

        "message.completed" -> {
            val msgId = event["messageID"]?.jsonPrimitive?.content ?: ""
            cleanupContentState(sessionId, msgId)
            out += AgentEvent.Done(eventId = eid(), timestamp = ts)
        }

        "session.idle" -> {
            val prefix = "$sessionId:"
            partTextLengths.keys.removeAll { it.startsWith(prefix) }
            sessionContentStates.keys.removeAll { it.startsWith(prefix) }
            out += AgentEvent.Done(eventId = eid(), timestamp = ts)
        }

        "session.compacted" -> {
            val msgId = event["messageID"]?.jsonPrimitive?.content ?: ""
            cleanupContentState(sessionId, msgId)
            out += AgentEvent.CompactionMarker(
                eventId = eid(), timestamp = ts, sessionId = sessionId,
            )
        }
    }

    return out
}

private fun splitThinkTags(text: String): Pair<String, String> {
    if (text.isEmpty()) return Pair("", "")

    val normalized = text
        .replace("[think]", "<think>", ignoreCase = true)
        .replace("[/think]", "</think>", ignoreCase = true)
        .replace("<thought>", "<think>", ignoreCase = true)
        .replace("</thought>", "</think>", ignoreCase = true)
        .replace("<reasoning>", "<think>", ignoreCase = true)
        .replace("</reasoning>", "</think>", ignoreCase = true)
        .replace("<|DSML|tool_calls>", "<think>Tool logic: ", ignoreCase = true)

    var thinking = ""
    var response = ""
    var cursor = 0

    // 1. Extract explicit tags
    while (cursor < normalized.length) {
        val start = normalized.indexOf("<think>", cursor)
        if (start == -1) {
            response += normalized.substring(cursor)
            break
        }
        
        response += normalized.substring(cursor, start)
        val contentStart = start + "<think>".length
        val end = normalized.indexOf("</think>", contentStart)
        
        if (end == -1) {
            thinking += normalized.substring(contentStart)
            cursor = normalized.length
        } else {
            thinking += normalized.substring(contentStart, end) + "\n"
            cursor = end + "</think>".length
        }
    }

    // 2. Supreme Heuristic: If no tags, detect "Internal Monologue" shift.
    // Enhanced: Only split if the "reasoning" part looks like planning/analysis 
    // AND the "response" part starts with a clear conversational marker.
    if (thinking.isBlank() && response.length > 40) {
        val responseStarts = listOf(
            Regex("""\n\n(Wait,|Actually,|However,|Sure,|Okay,|Yes,|No,|So,|Hello,|Hi,)""", RegexOption.IGNORE_CASE),
            Regex("""\n\n(I will|I'll|Let me|Based on|According to)""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in responseStarts) {
            val match = pattern.find(response)
            if (match != null && match.range.first > 15) {
                val potentialThinking = response.substring(0, match.range.first).trim()
                // Monologue check: does it look like reasoning? (no direct address)
                if (!potentialThinking.contains(Regex("""\b(you|your|user)\b""", RegexOption.IGNORE_CASE))) {
                    thinking = potentialThinking
                    response = response.substring(match.range.first).trim()
                    break
                }
            }
        }
    }

    return Pair(thinking.trim(), response.trim())
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
