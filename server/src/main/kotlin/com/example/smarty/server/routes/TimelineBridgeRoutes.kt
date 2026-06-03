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

private val partTextLengths = ConcurrentHashMap<String, Int>()

/** Track accumulated text per (sessionID, messageID) for streaming delta -> block translation. */
private data class MessageContentState(
    val textBuilder: StringBuilder = StringBuilder(),
    val reasoningBuilder: StringBuilder = StringBuilder(),
    var lastSentReasoningLen: Int = 0,
    var lastSentResponseLen: Int = 0,
)

private val sessionContentStates = ConcurrentHashMap<String, MessageContentState>()

private fun contentStateKey(sessionId: String, msgId: String) = "$sessionId:$msgId"

private fun cleanupContentState(sessionId: String, msgId: String) {
    sessionContentStates.remove(contentStateKey(sessionId, msgId))
    val prefix = "$sessionId:"
    partTextLengths.keys.removeAll { it.startsWith(prefix) }
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
    
    val currentMsgId: String = event["messageID"]?.jsonPrimitive?.contentOrNull ?: ""

    when (kind) {
        "session.error" -> {
            val msg = event["error"]?.jsonPrimitive?.contentOrNull ?: event["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
            out += AgentEvent.Error(eventId = eid(), timestamp = ts, message = msg)
        }

        "session.aborted" -> {
            val reason = event["reason"]?.jsonPrimitive?.contentOrNull ?: "aborted"
            out += AgentEvent.Error(eventId = eid(), timestamp = ts, message = reason)
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
                val state = event["state"]?.jsonPrimitive?.contentOrNull ?: ""
                val isMcp = event["isMcpTool"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
                val isInteractive = INTERACTIVE_TOOLS.contains(toolName.lowercase())

                when (state) {
                    "running" -> {
                        out += AgentEvent.ToolStart(
                            eventId = eid(), timestamp = ts,
                            toolId = callId, name = toolName,
                            args = event["input"]?.toString(),
                            isMcpTool = isMcp, isInteractive = isInteractive,
                            inputSummary = buildInputSummary(toolName, event["input"]?.jsonObject)
                        )
                    }
                    "complete", "completed" -> {
                        out += AgentEvent.ToolEnd(
                            eventId = eid(), timestamp = ts,
                            toolId = callId, result = event["output"]?.toString(),
                            isMcpTool = isMcp, isInteractive = isInteractive,
                            success = true, outputSummary = summarizeOutput(event["output"]?.toString(), toolName) ?: ""
                        )
                    }
                    "error" -> {
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

        "message.part.delta" -> {
            val delta = event["delta"]?.jsonPrimitive?.contentOrNull ?: ""
            if (delta.isNotEmpty()) {
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
            val parts = run {
                val p = event["parts"]
                val mParts = (event["message"] as? JsonObject)?.get("parts")
                val iParts = (event["info"] as? JsonObject)?.get("parts")
                when {
                    p is JsonArray -> p
                    p is JsonObject && p["parts"] is JsonArray -> p["parts"] as JsonArray
                    p is JsonObject -> JsonArray(p.values.toList())
                    mParts is JsonArray -> mParts
                    iParts is JsonArray -> iParts
                    else -> null
                }
            }

            val isFinalMessage = event["info"]?.jsonObject?.get("finish")?.jsonPrimitive?.contentOrNull == "stop"

            var combinedText = ""
            val separateReasoning = StringBuilder()
            var toolFound = false

            if (parts == null) {
                logger.warn("[TIMELINE] message.updated: No parts in session=$pluginSessionId msg=$currentMsgId. RAW EVENT: $event")
                val msgContent = (event["message"] as? JsonObject)?.get("content")?.jsonPrimitive?.contentOrNull
                val infoContent = (event["info"] as? JsonObject)?.get("content")?.jsonPrimitive?.contentOrNull
                combinedText = msgContent ?: infoContent ?: ""
                
                if (combinedText.isEmpty()) {
                    return out
                }
            } else {
                var currentPartIndex = 0
                for (part in parts) {
                    currentPartIndex++
                    val partObj = part as? JsonObject ?: continue
                    val partType = partObj["type"]?.jsonPrimitive?.contentOrNull ?: "text"
                    val deltaObj = partObj["delta"] as? JsonObject
                    val textContent = deltaObj?.get("text")?.jsonPrimitive?.contentOrNull
                        ?: partObj["content"]?.jsonPrimitive?.contentOrNull
                        ?: partObj["text"]?.jsonPrimitive?.contentOrNull
                        ?: partObj["message"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                    val reasoningContent = deltaObj?.get("reasoning")?.jsonPrimitive?.contentOrNull
                        ?: partObj["reasoning"]?.jsonPrimitive?.contentOrNull
                        ?: partObj["reasoning_content"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                    when {
                        partType == "reasoning" || reasoningContent.isNotEmpty() -> {
                            val r = if (reasoningContent.isNotEmpty()) reasoningContent else textContent
                            if (r.isNotBlank()) {
                                separateReasoning.append(r).append("\n")
                                val partKey = "$pluginSessionId:msg_$currentMsgId:part_$currentPartIndex"
                                val lastLen = partTextLengths.getOrDefault(partKey, 0)
                                if (lastLen == 0) {
                                    out += AgentEvent.StepStart(eventId = eid(), timestamp = ts, title = "Thinking", messageId = currentMsgId)
                                }
                                val delta = if (r.length > lastLen) r.substring(lastLen) else ""
                                partTextLengths[partKey] = r.length
                                if (delta.isNotEmpty()) {
                                    out += AgentEvent.ReasoningDelta(eventId = eid(), timestamp = ts, text = delta)
                                }
                                // If there are more parts after this one, it means this reasoning step is finished.
                                if (currentPartIndex < parts.size) {
                                    val finishedKey = partKey + "_finished"
                                    if (partTextLengths.getOrDefault(finishedKey, 0) == 0) {
                                        out += AgentEvent.StepEnd(eventId = eid(), timestamp = ts, success = true, stepNumber = currentPartIndex, cost = 0.0)
                                        partTextLengths[finishedKey] = 1
                                    }
                                }
                            }
                        }
                        partType == "tool" -> { extractToolFromPart(partObj, ts, eid, out); toolFound = true }
                        partType == "step-start" -> {
                            out += AgentEvent.StepStart(eventId = eid(), timestamp = ts,
                                title = partObj["title"]?.jsonPrimitive?.contentOrNull ?: "Step ${partObj["step"]?.jsonPrimitive?.intOrNull ?: 0}",
                                stepNumber = partObj["step"]?.jsonPrimitive?.intOrNull ?: 0, messageId = currentMsgId)
                        }
                        partType == "step-finish" -> {
                            out += AgentEvent.StepEnd(eventId = eid(), timestamp = ts,
                                success = partObj["success"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true,
                                stepNumber = partObj["step"]?.jsonPrimitive?.intOrNull ?: -1,
                                cost = partObj["cost"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
                        }
                        else -> { if (textContent.isNotBlank()) combinedText += textContent }
                    }
                }
            }

            // Delta computation via accumulated state (skeleton-stream pattern)
            val key = contentStateKey(pluginSessionId, currentMsgId)
            val state = sessionContentStates.getOrPut(key) { MessageContentState() }

            val (thinkingFromText, cleanResponse) = splitThinkTags(combinedText)
            val fullReasoning = separateReasoning.toString()
            val mergedReasoning = (fullReasoning + "\n" + thinkingFromText).trim()

            val hasNewReasoningFromText = thinkingFromText.length > state.lastSentReasoningLen
            val hasNewResponse = cleanResponse.length > state.lastSentResponseLen

            if (hasNewReasoningFromText || hasNewResponse || toolFound) {
                out += AgentEvent.StreamingActive(eventId = eid(), timestamp = ts,
                    sessionId = pluginSessionId, messageId = currentMsgId)
                if (hasNewReasoningFromText) {
                    out += AgentEvent.ThinkingActive(eventId = eid(), timestamp = ts,
                        sessionId = pluginSessionId, messageId = currentMsgId)
                }
            }

            if (hasNewResponse) {
                val delta = cleanResponse.substring(state.lastSentResponseLen)
                state.textBuilder.append(delta)
                state.lastSentResponseLen = cleanResponse.length
                out += AgentEvent.TextDelta(eventId = eid(), timestamp = ts, text = delta)
            }
            if (hasNewReasoningFromText) {
                val delta = thinkingFromText.substring(state.lastSentReasoningLen)
                state.reasoningBuilder.append(delta)
                state.lastSentReasoningLen = thinkingFromText.length
                out += AgentEvent.ReasoningDelta(eventId = eid(), timestamp = ts, text = delta)
            }

            // Emit final blocks only on the snapshot with info.finish == "stop"
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

        "message.completed" -> {
            cleanupContentState(pluginSessionId, currentMsgId)
        }

        "tool.before" -> {
            val toolName = event["tool"]?.jsonPrimitive?.contentOrNull ?: ""
            val callId = event["callID"]?.jsonPrimitive?.contentOrNull ?: ""
            out += AgentEvent.ToolStart(eventId = eid(), timestamp = ts,
                toolId = callId, name = toolName,
                args = event["args"]?.toString(),
                isMcpTool = event["isMcpTool"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
                isInteractive = INTERACTIVE_TOOLS.contains(toolName.lowercase()),
                inputSummary = buildInputSummary(toolName, event["args"]?.jsonObject))
        }

        "tool.after" -> {
            val toolName = event["tool"]?.jsonPrimitive?.contentOrNull ?: ""
            val callId = event["callID"]?.jsonPrimitive?.contentOrNull ?: ""
            val result = event["result"]?.toString()
            val error = event["error"]?.toString()
            out += AgentEvent.ToolEnd(eventId = eid(), timestamp = ts,
                toolId = callId, result = result, error = error,
                isMcpTool = event["isMcpTool"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
                isInteractive = INTERACTIVE_TOOLS.contains(toolName.lowercase()),
                success = error == null,
                outputSummary = summarizeOutput(error ?: result, toolName) ?: "")
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
            bridgeScope.launch {
                delay(3000) 
                val prefix = "$pluginSessionId:"
                partTextLengths.keys.removeAll { it.startsWith(prefix) }
                sessionContentStates.keys.removeAll { it.startsWith(prefix) }
            }
        }
    }
    return out
}

private fun extractToolFromPart(partObj: JsonObject, ts: Long, eid: () -> String, out: MutableList<AgentEvent>) {
    val toolName = partObj["tool"]?.jsonPrimitive?.contentOrNull ?: ""
    val callId = partObj["toolCallID"]?.jsonPrimitive?.contentOrNull ?: partObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
    val isMcp = partObj["isMcpTool"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
    val isInteractive = INTERACTIVE_TOOLS.contains(toolName.lowercase())
    val state = partObj["state"]?.jsonPrimitive?.contentOrNull ?: ""

    out += AgentEvent.ToolStart(
        eventId = eid(), timestamp = ts,
        toolId = callId, name = toolName,
        args = partObj["input"]?.toString(),
        isMcpTool = isMcp, isInteractive = isInteractive,
        inputSummary = buildInputSummary(toolName, partObj["input"]?.jsonObject)
    )
    
    val outputStr = partObj["output"]?.toString()
    val errorStr = partObj["error"]?.jsonPrimitive?.contentOrNull
    
    val isDone = state == "complete" || state == "completed" || state == "error" || outputStr != null || errorStr != null
    if (isDone) {
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
            val quote = content.trim().replace("\n", "\n> ")
            if (quote.isNotEmpty()) response += "\n\n> **Thinking...**\n> $quote"
            cursor = normalized.length
        } else {
            val content = normalized.substring(contentStart, end)
            thinking += content + "\n"
            val quote = content.trim().replace("\n", "\n> ")
            if (quote.isNotEmpty()) response += "\n\n> **Thought:**\n> $quote\n\n"
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
    return if (clean.length <= 100000) clean else "${clean.take(99997)}…"
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
