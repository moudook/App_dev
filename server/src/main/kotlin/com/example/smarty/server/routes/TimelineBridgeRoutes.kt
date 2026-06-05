package com.example.smarty.server.routes

import com.example.smarty.protocol.AgentEvent
import com.example.smarty.server.agent.AgentRunManager
import com.example.smarty.server.agent.ActiveSessionManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*

private val bridgeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

fun Application.configureTimelineBridgeRoutes() {
    val bridge = TimelineBridgeService

    routing {
        post("/opencode/events") {
            val body = call.receiveText()
            val ts = System.currentTimeMillis()

            try {
                val event = Json.parseToJsonElement(body).jsonObject
                val kind = event["type"]?.jsonPrimitive?.content 
                    ?: event["kind"]?.jsonPrimitive?.content 
                    ?: "unknown"

                logger.debug("[KTOR-RECV] kind=$kind bodyLen=${body.length}")
                
                val sessionID = event["sessionID"]?.jsonPrimitive?.content 
                    ?: event["properties"]?.jsonObject?.get("message")?.jsonObject?.get("sessionID")?.jsonPrimitive?.content
                    ?: event["message"]?.jsonObject?.get("sessionID")?.jsonPrimitive?.content
                    ?: "no-session"

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

private val partTextLengths = ConcurrentHashMap<String, Int>()

/** Cross-source tool event dedup: track seen callIds per (sessionId, lifecycle) so we never emit a duplicate. */
private val seenToolStarts = ConcurrentHashMap<String, Boolean>()
private val seenToolEnds = ConcurrentHashMap<String, Boolean>()

private fun toolStartKey(sessionId: String, callId: String) = "tool_start:$sessionId:$callId"
private fun toolEndKey(sessionId: String, callId: String) = "tool_end:$sessionId:$callId"

/** Track accumulated text per (sessionID, messageID) for streaming delta -> block translation.
 *  Note: MessageContentState is mutable but access is guarded per-key by ConcurrentHashMap.getOrPut
 *  and we only mutate after retrieval in single-threaded event handlers.
 */
private data class MessageContentState(
    val textBuilder: StringBuilder = StringBuilder(),
    val reasoningBuilder: StringBuilder = StringBuilder(),
    @Volatile var lastSentReasoningLen: Int = 0,
    @Volatile var lastSentResponseLen: Int = 0,
)

private val sessionContentStates = ConcurrentHashMap<String, MessageContentState>()

private fun contentStateKey(sessionId: String, msgId: String) = "$sessionId:$msgId"

private fun cleanupContentState(sessionId: String, msgId: String) {
    sessionContentStates.remove(contentStateKey(sessionId, msgId))
    val prefix = "$sessionId:"
    partTextLengths.keys.removeAll { it.startsWith(prefix) }
    val toolPrefix = "tool_start:$sessionId:"
    val toolEndPrefix = "tool_end:$sessionId:"
    seenToolStarts.keys.removeAll { it.startsWith(toolPrefix) }
    seenToolEnds.keys.removeAll { it.startsWith(toolEndPrefix) }
}

private val INTERACTIVE_TOOLS = setOf(
    "ask_user", "ask", "askuser", "confirm", "question", "clarify", "input"
)

private fun JsonElement?.str(): String? = this?.jsonPrimitive?.contentOrNull
private fun JsonElement?.bool(): Boolean? = this?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun translatePluginEvent(
    kind: String,
    event: JsonObject,
    ts: Long,
    pluginSessionId: String,
): List<AgentEvent> {
    val out = mutableListOf<AgentEvent>()
    val eid = { UUID.randomUUID().toString() }
    
    val payload = event["properties"]?.jsonObject ?: event
    
    val currentMsgId: String = event["messageID"]?.jsonPrimitive?.contentOrNull
        ?: runCatching { payload["info"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull }.getOrNull()
        ?: runCatching { payload["message"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull }.getOrNull()
        ?: ""

    when (kind) {
        "session.error" -> {
            val msg = event["error"]?.jsonPrimitive?.contentOrNull ?: event["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
            out += AgentEvent.Error(eventId = eid(), timestamp = ts, message = msg)
        }

        "session.aborted" -> {
            val reason = event["reason"]?.jsonPrimitive?.contentOrNull ?: "aborted"
            out += AgentEvent.SessionAborted(eventId = eid(), timestamp = ts, sessionId = pluginSessionId, reason = reason)
        }

        // ── Plugin v3 events ────────────────────────────────────────────────
        "session.created" -> { /* logged for trace; client uses Unknown fallback if needed */ }

        "session.compacted" -> {
            out += AgentEvent.CompactionMarker(eventId = eid(), timestamp = ts, sessionId = pluginSessionId)
        }

        "subagent.created" -> {
            out += AgentEvent.SubAgentCreated(
                eventId = eid(), timestamp = ts,
                sessionId = pluginSessionId,
                parentSessionId = event["parentSessionID"]?.jsonPrimitive?.contentOrNull ?: "",
                title = event["title"]?.jsonPrimitive?.contentOrNull,
            )
        }

        "subagent.idle" -> {
            out += AgentEvent.SubAgentIdle(
                eventId = eid(), timestamp = ts,
                sessionId = pluginSessionId,
                parentSessionId = event["parentSessionID"]?.jsonPrimitive?.contentOrNull ?: "",
                durationMs = event["durationMs"]?.jsonPrimitive?.longOrNull,
                totalToolCalls = event["totalToolCalls"]?.jsonPrimitive?.intOrNull,
            )
        }

        "websearch.query" -> {
            out += AgentEvent.WebSearchQuery(
                eventId = eid(), timestamp = ts,
                sessionId = pluginSessionId,
                callId = event["callID"]?.jsonPrimitive?.contentOrNull ?: "",
                query = event["query"]?.jsonPrimitive?.contentOrNull ?: "",
                numResults = event["numResults"]?.jsonPrimitive?.intOrNull ?: 5,
            )
        }

        "websearch.result" -> {
            val domains = (event["domains"] as? JsonArray)?.mapNotNull {
                it.jsonPrimitive.contentOrNull
            } ?: emptyList()
            out += AgentEvent.WebSearchResult(
                eventId = eid(), timestamp = ts,
                sessionId = pluginSessionId,
                callId = event["callID"]?.jsonPrimitive?.contentOrNull ?: "",
                domains = domains,
                resultLength = event["resultLength"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }

        "webfetch.url" -> {
            out += AgentEvent.WebFetchUrl(
                eventId = eid(), timestamp = ts,
                sessionId = pluginSessionId,
                callId = event["callID"]?.jsonPrimitive?.contentOrNull ?: "",
                url = event["url"]?.jsonPrimitive?.contentOrNull ?: "",
                domain = event["domain"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }

        "webfetch.result" -> {
            val domains = (event["domains"] as? JsonArray)?.mapNotNull {
                it.jsonPrimitive.contentOrNull
            } ?: emptyList()
            out += AgentEvent.WebFetchResult(
                eventId = eid(), timestamp = ts,
                sessionId = pluginSessionId,
                callId = event["callID"]?.jsonPrimitive?.contentOrNull ?: "",
                url = event["url"]?.jsonPrimitive?.contentOrNull ?: "",
                domains = domains,
                resultLength = event["resultLength"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }

        "tool.denied" -> {
            out += AgentEvent.ToolDenied(
                eventId = eid(), timestamp = ts,
                sessionId = pluginSessionId,
                callId = event["callID"]?.jsonPrimitive?.contentOrNull ?: "",
                tool = event["tool"]?.jsonPrimitive?.contentOrNull ?: "",
                reason = event["reason"]?.jsonPrimitive?.contentOrNull ?: "Tool disabled",
            )
        }

        "compaction.start" -> {
            out += AgentEvent.CompactionStart(
                eventId = eid(), timestamp = ts,
                sessionId = pluginSessionId,
                compactionCount = event["compactionCount"]?.jsonPrimitive?.intOrNull ?: 1,
            )
        }

        "compaction.complete" -> {
            out += AgentEvent.CompactionComplete(eventId = eid(), timestamp = ts, sessionId = pluginSessionId)
        }

        "file.edited" -> {
            out += AgentEvent.FileEdited(
                eventId = eid(), timestamp = ts,
                sessionId = pluginSessionId,
                path = event["path"]?.jsonPrimitive?.contentOrNull,
            )
        }

        "lsp.diagnostics" -> {
            val diagCount = (event["diagnostics"] as? JsonArray)?.size ?: 0
            out += AgentEvent.LspDiagnostics(
                eventId = eid(), timestamp = ts,
                sessionId = pluginSessionId,
                path = event["path"]?.jsonPrimitive?.contentOrNull,
                count = diagCount,
            )
        }

        "command.execute" -> {
            out += AgentEvent.CommandExecuted(
                eventId = eid(), timestamp = ts,
                sessionId = pluginSessionId,
                command = event["command"]?.jsonPrimitive?.contentOrNull,
                arguments = event["arguments"]?.jsonPrimitive?.contentOrNull,
            )
        }

        "plugin.dispose" -> {
            out += AgentEvent.PluginDispose(eventId = eid(), timestamp = ts)
        }

        "permission.ask" -> {
            // Informational — native permission flow, not used in our app
            logger.debug("[plugin] permission.ask session=$pluginSessionId tool=${event["tool"]?.jsonPrimitive?.contentOrNull}")
        }

        "part.updated" -> {
            val phase = event["phase"]?.jsonPrimitive?.contentOrNull ?: "streaming"
            val partType = event["partType"]?.jsonPrimitive?.contentOrNull ?: ""
            val partId = event["partID"]?.jsonPrimitive?.contentOrNull ?: "default-part"

            if (phase == "streaming" && (partType == "text" || partType == "reasoning")) {
                val rawText: String = run {
                    val fromDelta = (event["delta"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                        ?: (event["delta"] as? JsonObject)?.get("content")?.jsonPrimitive?.contentOrNull
                    val fromEvent = event["text"]?.jsonPrimitive?.contentOrNull
                        ?: event["content"]?.jsonPrimitive?.contentOrNull
                    fromDelta ?: fromEvent ?: ""
                }
                val rawReasoning: String = run {
                    val fromDelta = (event["delta"] as? JsonObject)?.get("reasoning")?.jsonPrimitive?.contentOrNull
                        ?: (event["delta"] as? JsonObject)?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                    val fromEvent = event["reasoning"]?.jsonPrimitive?.contentOrNull
                        ?: event["reasoning_content"]?.jsonPrimitive?.contentOrNull
                    val fromTextFallback = if (partType == "reasoning") event["text"]?.jsonPrimitive?.contentOrNull else null
                    fromDelta ?: fromEvent ?: fromTextFallback ?: ""
                }

                val key = contentStateKey(pluginSessionId, currentMsgId)
                val state = sessionContentStates.getOrPut(key) { MessageContentState() }
                val isThinkingHint = event["isThinkingHint"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

                if (rawReasoning.isNotEmpty()) {
                    out += AgentEvent.ReasoningDelta(eventId = eid(), timestamp = ts, text = rawReasoning)
                    out += AgentEvent.ThinkingActive(eventId = eid(), timestamp = ts, sessionId = pluginSessionId, messageId = currentMsgId)
                } else if (isThinkingHint) {
                    out += AgentEvent.ThinkingActive(eventId = eid(), timestamp = ts, sessionId = pluginSessionId, messageId = currentMsgId)
                }

                if (rawText.isNotEmpty() && rawText != rawReasoning) {
                    val partKey = "$pluginSessionId:$partId"
                    val lastPartLen = partTextLengths.getOrDefault(partKey, 0)
                    val partDelta = if (rawText.length > lastPartLen) rawText.substring(lastPartLen) else ""
                    partTextLengths[partKey] = rawText.length

                    if (partDelta.isNotEmpty()) {
                        state.textBuilder.append(partDelta)
                        val (thinking, response) = splitThinkTags(state.textBuilder.toString())

                        if (thinking.length > state.lastSentReasoningLen) {
                            val d = thinking.substring(state.lastSentReasoningLen)
                            state.lastSentReasoningLen = thinking.length
                            out += AgentEvent.ReasoningDelta(eventId = eid(), timestamp = ts, text = d)
                            out += AgentEvent.ThinkingActive(eventId = eid(), timestamp = ts, sessionId = pluginSessionId, messageId = currentMsgId)
                        }
                        if (response.length > state.lastSentResponseLen) {
                            val d = response.substring(state.lastSentResponseLen)
                            state.lastSentResponseLen = response.length
                            out += AgentEvent.TextDelta(eventId = eid(), timestamp = ts, text = d)
                            AgentRunManager.markBridgeSentText(pluginSessionId)
                            out += AgentEvent.StreamingActive(eventId = eid(), timestamp = ts, sessionId = pluginSessionId, messageId = currentMsgId)
                        }
                    }
                }
            } else if (phase == "snapshot" && partType == "reasoning") {
                val content = event["reasoning"]?.jsonPrimitive?.contentOrNull ?: ""
                out += AgentEvent.ReasoningBlock(
                    eventId = eid(), timestamp = ts,
                    sessionId = pluginSessionId, messageId = currentMsgId,
                    partId = partId, content = content,
                    thinkingDurationMs = event["thinkingDurationMs"]?.jsonPrimitive?.longOrNull
                )
            } else if (partType == "tool") {
                val toolName = event["tool"]?.jsonPrimitive?.contentOrNull ?: ""
                val callId = event["toolCallID"]?.jsonPrimitive?.contentOrNull ?: ""
                val stateVal = event["state"]?.jsonPrimitive?.contentOrNull ?: ""
                val isMcp = event["isMcpTool"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
                val isInteractive = INTERACTIVE_TOOLS.contains(toolName.lowercase())

                when (stateVal) {
                    "running" -> {
                        val startKey = toolStartKey(pluginSessionId, callId)
                        if (seenToolStarts.putIfAbsent(startKey, true) == null) {
                            out += AgentEvent.ToolStart(
                                eventId = eid(), timestamp = ts,
                                toolId = callId, name = toolName,
                                args = event["input"]?.toString(),
                                isMcpTool = isMcp, isInteractive = isInteractive,
                                inputSummary = buildInputSummary(toolName, event["input"]?.jsonObject)
                            )
                        }
                    }
                    "complete", "completed" -> {
                        val endKey = toolEndKey(pluginSessionId, callId)
                        if (seenToolEnds.putIfAbsent(endKey, true) == null) {
                            out += AgentEvent.ToolEnd(
                                eventId = eid(), timestamp = ts,
                                toolId = callId, result = event["output"]?.toString(),
                                isMcpTool = isMcp, isInteractive = isInteractive,
                                success = true, outputSummary = summarizeOutput(event["output"]?.toString(), toolName) ?: ""
                            )
                        }
                    }
                    "error" -> {
                        val endKey = toolEndKey(pluginSessionId, callId)
                        if (seenToolEnds.putIfAbsent(endKey, true) == null) {
                            out += AgentEvent.ToolEnd(
                                eventId = eid(), timestamp = ts,
                                toolId = callId, error = event["error"]?.jsonPrimitive?.contentOrNull ?: "Tool failed",
                                isMcpTool = isMcp, isInteractive = isInteractive,
                                success = false, outputSummary = event["error"]?.jsonPrimitive?.contentOrNull ?: "Error"
                            )
                        }
                    }
                }
            }
        }

        "message.part.delta" -> {
            val delta: String = run {
                val deltaEl = event["delta"]
                when (deltaEl) {
                    is kotlinx.serialization.json.JsonPrimitive -> deltaEl.contentOrNull ?: ""
                    is kotlinx.serialization.json.JsonObject -> {
                        deltaEl["text"]?.jsonPrimitive?.contentOrNull
                            ?: deltaEl["content"]?.jsonPrimitive?.contentOrNull
                            ?: deltaEl["reasoning"]?.jsonPrimitive?.contentOrNull
                            ?: deltaEl["reasoning_content"]?.jsonPrimitive?.contentOrNull
                            ?: deltaEl["delta"]?.jsonPrimitive?.contentOrNull
                            ?: ""
                    }
                    else -> ""
                }
            }
            if (delta.isNotEmpty()) {
                logger.debug("[message.part.delta] delta.length=${delta.length}, preview=${delta.take(60)}")
                val key = contentStateKey(pluginSessionId, currentMsgId)
                val state = sessionContentStates.getOrPut(key) { MessageContentState() }
                state.textBuilder.append(delta)
                val (thinking, response) = splitThinkTags(state.textBuilder.toString())

                if (thinking.length > state.lastSentReasoningLen) {
                    val d = thinking.substring(state.lastSentReasoningLen)
                    state.lastSentReasoningLen = thinking.length
                    out += AgentEvent.ReasoningDelta(eventId = eid(), timestamp = ts, text = d)
                }
                if (response.length > state.lastSentResponseLen) {
                    val d = response.substring(state.lastSentResponseLen)
                    state.lastSentResponseLen = response.length
                    out += AgentEvent.TextDelta(eventId = eid(), timestamp = ts, text = d)
                }
            }
        }

        "message.updated" -> {
            // Detect if this is the final/completed snapshot for this message.
            // OpenCode sends message.updated as snapshots; the last one has finish="stop"
            // OR info.time.completed set, OR message.summary present.
            val isFinalMessage = run {
                val topLevel = event["finish"]?.jsonPrimitive?.contentOrNull
                    ?: payload["finish"]?.jsonPrimitive?.contentOrNull
                val inInfo = (payload["info"] as? JsonObject)?.get("finish")?.jsonPrimitive?.contentOrNull
                val inMessage = (payload["message"] as? JsonObject)?.get("finish")?.jsonPrimitive?.contentOrNull
                val inInfoTime = (payload["info"] as? JsonObject)?.get("time")?.jsonObject?.get("completed")?.jsonPrimitive?.longOrNull != null
                val inMessageSummary = (payload["message"] as? JsonObject)?.get("summary") != null
                topLevel == "stop" || inInfo == "stop" || inMessage == "stop" || inInfoTime || inMessageSummary
            }

            // Extract text/reasoning from parts array (primary) or fallback fields.
            // ONLY look at assistant/text parts — skip user/system parts to avoid
            // treating conversation history as new response text.
            val snapshot = run {
                val info = payload["info"] as? JsonObject
                val msg = payload["message"] as? JsonObject
                val parts = payload["parts"] as? JsonArray 
                    ?: event["parts"] as? JsonArray
                    ?: payload["rawParts"] as? JsonArray
                var text = ""
                var reasoning = ""
                if (parts != null) {
                    for (part in parts) {
                        val p = part as? JsonObject ?: continue
                        val type = p["type"]?.jsonPrimitive?.contentOrNull ?: "text"
                        // Only process text/reasoning parts from this specific message's content
                        // Skip user parts, step-start, tool parts (they are handled by part.updated)
                        if (type == "tool" || type == "step-start" || type == "subtask") continue
                        val content = p["content"]?.jsonPrimitive?.contentOrNull
                            ?: p["text"]?.jsonPrimitive?.contentOrNull
                            ?: p["message"]?.jsonPrimitive?.contentOrNull
                            ?: ""
                        val r = p["reasoning"]?.jsonPrimitive?.contentOrNull
                            ?: p["reasoning_content"]?.jsonPrimitive?.contentOrNull
                            ?: ""
                        if (type == "reasoning" || r.isNotEmpty()) {
                            reasoning += r + "\n"
                        } else if (type == "text" || type == "assistant") {
                            text += content
                        }
                    }
                } else {
                    // Fallback: no parts array, try info or message fields
                    text = info?.get("content")?.jsonPrimitive?.contentOrNull
                        ?: msg?.get("content")?.jsonPrimitive?.contentOrNull
                        ?: msg?.get("text")?.jsonPrimitive?.contentOrNull
                        ?: ""
                    reasoning = info?.get("reasoning")?.jsonPrimitive?.contentOrNull
                        ?: info?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                        ?: msg?.get("reasoning")?.jsonPrimitive?.contentOrNull
                        ?: msg?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                        ?: ""
                }
                Pair(text.trim(), reasoning.trim())
            }

            val (snapshotText, snapshotReasoning) = snapshot
            logger.info("[message.updated] session=$pluginSessionId msgId=$currentMsgId isFinal=$isFinalMessage textLen=${snapshotText.length} reasoningLen=${snapshotReasoning.length}")

            // Skip processing if there is nothing useful in this snapshot.
            // This avoids poisoning the delta tracker with history-only snapshots.
            if (snapshotText.isEmpty() && snapshotReasoning.isEmpty() && !isFinalMessage) {
                logger.debug("[message.updated] Skipping empty non-final snapshot for session=$pluginSessionId")
            } else {
                val key = contentStateKey(pluginSessionId, currentMsgId)
                val state = sessionContentStates.getOrPut(key) { MessageContentState() }

                val (cleanThinking, cleanResponse) = splitThinkTags(snapshotText)
                val mergedReasoning = (snapshotReasoning + "\n" + cleanThinking).trim()

                val hasNewReasoning = mergedReasoning.length > state.lastSentReasoningLen
                val hasNewResponse = cleanResponse.length > state.lastSentResponseLen

                logger.debug("[message.updated] hasNewReasoning=$hasNewReasoning hasNewResponse=$hasNewResponse cleanResponseLen=${cleanResponse.length} lastSentResponseLen=${state.lastSentResponseLen}")

                if (hasNewReasoning || hasNewResponse || isFinalMessage) {
                    out += AgentEvent.StreamingActive(eventId = eid(), timestamp = ts,
                        sessionId = pluginSessionId, messageId = currentMsgId)
                }
                if (hasNewReasoning) {
                    val d = mergedReasoning.substring(state.lastSentReasoningLen)
                    state.lastSentReasoningLen = mergedReasoning.length
                    out += AgentEvent.ReasoningDelta(eventId = eid(), timestamp = ts, text = d)
                    out += AgentEvent.ThinkingActive(eventId = eid(), timestamp = ts, sessionId = pluginSessionId, messageId = currentMsgId)
                }
                if (hasNewResponse) {
                    val d = cleanResponse.substring(state.lastSentResponseLen)
                    state.lastSentResponseLen = cleanResponse.length
                    state.textBuilder.append(d)
                    out += AgentEvent.TextDelta(eventId = eid(), timestamp = ts, text = d)
                }

                if (isFinalMessage) {
                    out += AgentEvent.StepEnd(eventId = eid(), timestamp = ts, success = true, stepNumber = 999, cost = 0.0)
                    if (mergedReasoning.isNotBlank()) {
                        out += AgentEvent.ReasoningBlock(eventId = eid(), timestamp = ts,
                            sessionId = pluginSessionId, messageId = currentMsgId,
                            partId = "snapshot-reasoning", content = mergedReasoning)
                    }
                    if (cleanResponse.isNotBlank() || mergedReasoning.isNotBlank()) {
                        out += AgentEvent.ResponseBlock(eventId = eid(), timestamp = ts,
                            sessionId = pluginSessionId, messageId = currentMsgId,
                            content = if (cleanResponse.isNotBlank()) cleanResponse else " ")
                    }
                    cleanupContentState(pluginSessionId, currentMsgId)
                }
            }
        }

        "message.completed" -> {
            // Flush any accumulated content as a final ResponseBlock then clean up.
            val key = contentStateKey(pluginSessionId, currentMsgId)
            val state = sessionContentStates[key]
            if (state != null) {
                val accumulatedText = state.textBuilder.toString().trim()
                if (accumulatedText.isNotEmpty() && state.lastSentResponseLen < accumulatedText.length) {
                    val remaining = accumulatedText.substring(state.lastSentResponseLen)
                    out += AgentEvent.TextDelta(eventId = eid(), timestamp = ts, text = remaining)
                }
                if (accumulatedText.isNotEmpty()) {
                    out += AgentEvent.ResponseBlock(eventId = eid(), timestamp = ts,
                        sessionId = pluginSessionId, messageId = currentMsgId,
                        content = accumulatedText)
                }
            }
            logger.info("[message.completed] session=$pluginSessionId msgId=$currentMsgId")
            cleanupContentState(pluginSessionId, currentMsgId)
        }

        "tool.before", "tool.execute.before" -> {
            val toolName = payload["tool"]?.jsonPrimitive?.contentOrNull ?: ""
            val callId = payload["callID"]?.jsonPrimitive?.contentOrNull ?: ""
            val startKey = toolStartKey(pluginSessionId, callId)
            if (seenToolStarts.putIfAbsent(startKey, true) == null) {
                out += AgentEvent.ToolStart(eventId = eid(), timestamp = ts,
                    toolId = callId, name = toolName,
                    args = payload["args"]?.toString() ?: payload["input"]?.toString(),
                    isMcpTool = payload["isMcpTool"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
                    isInteractive = INTERACTIVE_TOOLS.contains(toolName.lowercase()),
                    inputSummary = buildInputSummary(toolName, payload["args"]?.jsonObject ?: payload["input"]?.jsonObject))
            }
        }

        "tool.after", "tool.execute.after" -> {
            val toolName = payload["tool"]?.jsonPrimitive?.contentOrNull ?: ""
            val callId = payload["callID"]?.jsonPrimitive?.contentOrNull ?: ""
            val result = payload["result"]?.toString() ?: payload["output"]?.toString()
            val error = payload["error"]?.toString()
            val endKey = toolEndKey(pluginSessionId, callId)
            if (seenToolEnds.putIfAbsent(endKey, true) == null) {
                out += AgentEvent.ToolEnd(eventId = eid(), timestamp = ts,
                    toolId = callId, result = result, error = error,
                    isMcpTool = payload["isMcpTool"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
                    isInteractive = INTERACTIVE_TOOLS.contains(toolName.lowercase()),
                    success = error == null,
                    outputSummary = summarizeOutput(error ?: result, toolName) ?: "")
            }
        }

        "permission.asked" -> {
            val toolName = event["tool"]?.jsonPrimitive?.content ?: ""
            val callId = event["callID"]?.jsonPrimitive?.content ?: "ask_${eid().substring(0, 8)}"
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

        "session.idle" -> {
            // DO NOT EMIT DONE HERE! 
            // The OpenCode daemon goes idle before ServerAgent finishes processing its response.
            // If we emit Done here, it closes the Android WebSocket connection prematurely,
            // preventing the AgentRunManager fallback from delivering the text.
            // AgentRunManager's finally block will handle emitting Done when it is actually finished.
            logger.info("[session.idle] Received from plugin but delegating Done emission to AgentRunManager")
            
            bridgeScope.launch {
                delay(5000)
                val prefix = "$pluginSessionId:"
                partTextLengths.keys.removeAll { it.startsWith(prefix) }
                sessionContentStates.keys.removeAll { it.startsWith(prefix) }
            }
        }
    }
    return out
}

private fun extractToolFromPart(sessionId: String, partObj: JsonObject, ts: Long, eid: () -> String, out: MutableList<AgentEvent>) {
    val toolName = partObj["tool"]?.jsonPrimitive?.contentOrNull ?: ""
    val callId = partObj["toolCallID"]?.jsonPrimitive?.contentOrNull ?: partObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
    val isMcp = partObj["isMcpTool"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
    val isInteractive = INTERACTIVE_TOOLS.contains(toolName.lowercase())
    val state = partObj["state"]?.jsonPrimitive?.contentOrNull ?: ""

    val toolKey = toolStartKey(sessionId, callId)
    val isFirstStart = seenToolStarts.putIfAbsent(toolKey, true) == null

    if (isFirstStart) {
        out += AgentEvent.ToolStart(
            eventId = eid(), timestamp = ts,
            toolId = callId, name = toolName,
            args = partObj["input"]?.toString(),
            isMcpTool = isMcp, isInteractive = isInteractive,
            inputSummary = buildInputSummary(toolName, partObj["input"]?.jsonObject)
        )
    }

    val outputStr = partObj["output"]?.toString()
    val errorStr = partObj["error"]?.jsonPrimitive?.contentOrNull

    val isDone = state == "complete" || state == "completed" || state == "error" || outputStr != null || errorStr != null
    val toolEndKey = toolEndKey(sessionId, callId)
    val isFirstEnd = seenToolEnds.putIfAbsent(toolEndKey, true) == null

    if (isDone && isFirstEnd) {
        out += AgentEvent.ToolEnd(
            eventId = eid(), timestamp = ts,
            toolId = callId, result = outputStr, error = errorStr,
            isMcpTool = isMcp, isInteractive = isInteractive,
            success = errorStr == null,
            outputSummary = summarizeOutput(errorStr ?: outputStr, toolName) ?: ""
        )
    }
}

private fun splitThinkTags(text: String): Pair<String, String> {
    if (text.isEmpty()) return Pair("", "")
    val normalized = text
        .replace(Regex("<final>|</final>", RegexOption.IGNORE_CASE), "")
        .replace("[think]", "<think>", ignoreCase = true)
        .replace("[/think]", "</think>", ignoreCase = true)
        .replace("<thought>", "<think>", ignoreCase = true)
        .replace("</thought>", "</think>", ignoreCase = true)
        .replace("<reasoning>", "<think>", ignoreCase = true)
        .replace("</reasoning>", "</think>", ignoreCase = true)
        .replace("<|DSML|tool_calls>", "<think>Tool logic: ", ignoreCase = true)
        .replace(Regex("<think>", RegexOption.IGNORE_CASE), "<think>")
        .replace(Regex("</think>", RegexOption.IGNORE_CASE), "</think>")

    var thinking = ""
    var response = ""
    var cursor = 0

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
            val content = normalized.substring(contentStart)
            thinking += content
            cursor = normalized.length
        } else {
            val content = normalized.substring(contentStart, end)
            thinking += content + "\n"
            cursor = end + "</think>".length
        }
    }

    if (thinking.isBlank() && response.length > 40) {
        val responseStarts = listOf(
            Regex("""\n\n(Wait,|Actually,|However,|Sure,|Okay,|Yes,|No,|So,|Hello,|Hi,)""", RegexOption.IGNORE_CASE),
            Regex("""\n\n(I will|I'll|Let me|Based on|According to)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in responseStarts) {
            val match = pattern.find(response)
            if (match != null && match.range.first > 15) {
                val potentialThinking = response.substring(0, match.range.first).trim()
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
        "websearch", "web_search" -> args["query"]?.jsonPrimitive?.contentOrNull?.let { "Search: \"$it\"" } ?: ""
        "bash" -> args["command"]?.jsonPrimitive?.contentOrNull?.let { cmd -> if (cmd.length > 60) "$ ${cmd.take(57)}…" else "$ $cmd" } ?: ""
        else -> args.entries.firstOrNull { it.value is JsonPrimitive }?.let { (k, v) -> "$k: ${v.jsonPrimitive.content.take(50)}" } ?: ""
    }
}

private fun summarizeOutput(result: String?, toolName: String): String? {
    if (result == null || result == "null") return null
    val clean = result.trim()
    if (clean.isEmpty()) return null

    return when (toolName.lowercase()) {
        "memory", "save_progress", "read_progress", "get_note_by_id" -> summarizeNoteLike(clean)
        "search_history" -> summarizeSearchHistory(clean)
        "schedule", "remind" -> summarizeSchedule(clean)
        "ask_user", "ask" -> summarizeAskUser(clean)
        else -> if (clean.length <= 100000) clean else "${clean.take(99997)}…"
    }
}

private fun summarizeNoteLike(json: String): String {
    return try {
        val obj = Json.parseToJsonElement(json).jsonObject
        val title = obj["title"]?.jsonPrimitive?.contentOrNull
        val content = obj["content"]?.jsonPrimitive?.contentOrNull
        val category = obj["category"]?.jsonPrimitive?.contentOrNull
        val tags = obj["tags"]?.toString()?.take(80)
        buildString {
            if (title != null) append("**").append(title).append("**")
            if (category != null) append(" _[").append(category).append("]_")
            append("\n")
            if (content != null) {
                val preview = content.take(400)
                append(preview)
                if (content.length > 400) append("…")
            }
            if (tags != null) append("\n🏷 ").append(tags)
        }.take(800)
    } catch (_: Exception) {
        if (json.length <= 100000) json else "${json.take(99997)}…"
    }
}

private fun summarizeSearchHistory(json: String): String {
    return try {
        val arr = Json.parseToJsonElement(json)
        val list = when (arr) {
            is JsonArray -> arr
            is JsonObject -> arr["results"] as? JsonArray ?: arr["messages"] as? JsonArray
            else -> null
        }
        if (list == null) {
            return if (json.length <= 100000) json else "${json.take(99997)}…"
        }
        val first = list.firstOrNull()?.toString()?.take(300) ?: "(no results)"
        "${list.size} result(s). First: $first…"
    } catch (_: Exception) {
        if (json.length <= 100000) json else "${json.take(99997)}…"
    }
}

private fun summarizeSchedule(json: String): String {
    return try {
        val obj = Json.parseToJsonElement(json).jsonObject
        val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: obj["summary"]?.jsonPrimitive?.contentOrNull
        val time = obj["startTime"]?.jsonPrimitive?.contentOrNull ?: obj["time"]?.jsonPrimitive?.contentOrNull
        val loc = obj["location"]?.jsonPrimitive?.contentOrNull
        buildString {
            if (title != null) append("📅 ").append(title)
            if (time != null) append(" @ ").append(time)
            if (loc != null) append(" — ").append(loc)
        }
    } catch (_: Exception) {
        if (json.length <= 100000) json else "${json.take(99997)}…"
    }
}

private fun summarizeAskUser(json: String): String {
    return try {
        val obj = Json.parseToJsonElement(json).jsonObject
        obj["answer"]?.jsonPrimitive?.contentOrNull
            ?: obj["response"]?.jsonPrimitive?.contentOrNull
            ?: obj["choice"]?.jsonPrimitive?.contentOrNull
            ?: json.take(200)
    } catch (_: Exception) {
        json.take(200)
    }
}

object TimelineBridgeService {
    data class EventSnapshot(val kind: String, val sessionID: String, val ts: Long, val raw: JsonObject)
    private val timelines = ConcurrentHashMap<String, CopyOnWriteArrayList<EventSnapshot>>()
    fun ingest(kind: String, sessionID: String, event: JsonObject, ts: Long) {
        if (sessionID == "no-session") return
        val list = timelines.getOrPut(sessionID) { CopyOnWriteArrayList() }
        if (list.size >= 10000) list.removeAt(0)
        list.add(EventSnapshot(kind, sessionID, ts, event))
    }
    fun getTimeline(sessionID: String): List<EventSnapshot> = timelines[sessionID]?.toList() ?: emptyList()
    fun getAllSessionIDs(): Set<String> = timelines.keys
}
