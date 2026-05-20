package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentEvent
import com.example.smarty.server.llm.LlmUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Extracted streaming and event processing logic from ServerAgent.kt
 * Handles SSE streaming, thinking state management, event throttling, and response extraction
 */
class AgentStreamProcessor(
    private val sessionId: String,
    private val eventEmitter: suspend (AgentEvent) -> Unit,
) {
    private val logger = LoggerFactory.getLogger(AgentStreamProcessor::class.java)

    // Thinking storage manager
    private val thinkingStorage = ThinkingStorageManagerSingleton.instance

    // State machine for tag detection
    private var inThinkingState = false
    private var inFinalState = false

    // Event throttling
    private var lastProcessingEventTime = 0L
    private val PROCESSING_EVENT_THROTTLE_MS = 300L

    // Accumulated state
    var currentContent = ""
    var currentToolId = ""
    var currentToolName = ""
    var currentToolArgs = ""
    var isToolCallInProgress = false
    var totalUsage: LlmUsage? = null

    // Tool call XML parsing — accumulated raw text for post-stream parsing
    private val rawContentBuffer = StringBuilder()

    // Parsed tool call from XML (set by finalizeAndExtractToolCalls)
    var parsedToolName: String = ""
        private set
    var parsedToolArgs: String = ""
        private set
    var parsedToolCallFound: Boolean = false
        private set

    /**
     * Throttled emit for Processing events.
     */
    suspend fun emitThrottledProcessing(
        content: String,
        thinking: String?,
    ) {
        val now = System.currentTimeMillis()
        val shouldEmit =
            (now - lastProcessingEventTime >= PROCESSING_EVENT_THROTTLE_MS) ||
                (thinking != null && thinking.isNotEmpty())
        if (shouldEmit) {
            this.emit(
                AgentEvent.Processing(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = now,
                    content = content,
                    thinking = thinking,
                ),
            )
            lastProcessingEventTime = now
        }
    }

    /**
     * Process a single LLM stream chunk.
     */
    suspend fun processChunk(chunk: com.example.smarty.server.llm.LlmChunk) {
        chunk.usage?.let { totalUsage = it }

        var reasoningUpdated = false
        if (!chunk.reasoning.isNullOrEmpty()) {
            thinkingStorage.addReasoning(sessionId, chunk.reasoning)
            reasoningUpdated = true

            if (!isToolCallInProgress) {
                val currentThinking = thinkingStorage.getCompleteThinking(sessionId)
                emitThrottledProcessing("", currentThinking)
            }
        }

        if (!chunk.content.isNullOrEmpty()) {
            val newContent = chunk.content

            // Buffer raw content for post-stream tool call XML parsing
            rawContentBuffer.append(newContent)

            val hadThinkStart = newContent.contains("<think>") || newContent.contains("<thought>")
            val hadThinkEnd = newContent.contains("</think>") || newContent.contains("</thought>")
            val hadFinalOpen = newContent.contains("<final>")
            val hadFinalClose = newContent.contains("</final>")

            var cleanContent = ""
            var thinkingPart = ""

            when {
                hadThinkStart -> {
                    inThinkingState = true
                    inFinalState = false
                    val parts = newContent.split(Regex("<(?:think|thought)>"), limit = 2)
                    cleanContent = parts.getOrElse(0) { "" }
                    val afterOpen = parts.getOrElse(1) { "" }
                    if (hadThinkEnd || hadFinalClose) {
                        val endParts = afterOpen.split(Regex("</(?:think|thought|final)>|<final>"), limit = 2)
                        thinkingPart = endParts.getOrElse(0) { "" }
                        cleanContent += endParts.getOrElse(1) { "" }
                        inThinkingState = false
                        inFinalState = true
                    } else {
                        thinkingPart = afterOpen
                    }
                }
                inThinkingState && (hadThinkEnd || hadFinalClose) -> {
                    val endParts = newContent.split(Regex("</(?:think|thought|final)>|<final>"), limit = 2)
                    thinkingPart = endParts.getOrElse(0) { "" }
                    cleanContent = endParts.getOrElse(1) { "" }
                    inThinkingState = false
                    inFinalState = true
                }
                inThinkingState -> {
                    thinkingPart = newContent
                }
                else -> {
                    cleanContent =
                        newContent
                            .replace(Regex("<(?:think|thought|final)>"), "")
                            .replace(Regex("</(?:think|thought|final)>"), "")
                    if (hadFinalOpen) inFinalState = true
                }
            }

            // Strip thinking/final tags
            cleanContent =
                cleanContent
                    .replace(Regex("<(?:think|thought|final)>"), "")
                    .replace(Regex("</(?:think|thought|final)>"), "")
            thinkingPart =
                thinkingPart
                    .replace(Regex("<(?:think|thought|final)>"), "")
                    .replace(Regex("</(?:think|thought|final)>"), "")

            // Strip tool call XML from user-visible content
            cleanContent = stripToolCallXml(cleanContent)

            if (thinkingPart.isNotEmpty()) {
                thinkingStorage.addReasoning(sessionId, thinkingPart)
                reasoningUpdated = true
            }
            if (cleanContent.isNotEmpty()) {
                currentContent += cleanContent
            }

            if (!isToolCallInProgress) {
                val thinkingToSend =
                    if (reasoningUpdated) {
                        thinkingStorage.getCompleteThinking(sessionId)
                    } else {
                        null
                    }

                emitThrottledProcessing(cleanContent, thinkingToSend)
            }
        }

        // Handle native OpenCode tool_use events (e.g., websearch results)
        val toolCall = chunk.toolCall
        if (toolCall != null) {
            if (!isToolCallInProgress) {
                isToolCallInProgress = true
                this.emit(
                    AgentEvent.ToolCall(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        toolName = toolCall.functionName,
                        displayName = "Preparing ${toolCall.functionName}...",
                        status = "started",
                    ),
                )
            }
            if (toolCall.id.isNotEmpty()) currentToolId = toolCall.id
            if (toolCall.functionName.isNotEmpty()) currentToolName = toolCall.functionName
            currentToolArgs += toolCall.arguments
        }
    }

    /**
     * Emit the final response to the client.
     */
    suspend fun emitFinalResponse(
        content: String,
        confidence: String,
        sourceType: String,
    ) {
        val finalThinking = thinkingStorage.finalizeAndGetThinking(sessionId)

        if (finalThinking.isNotEmpty()) {
            this.emit(
                AgentEvent.Processing(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    content = "",
                    thinking = finalThinking,
                ),
            )
        }

        this.emit(
            AgentEvent.Result(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                content = "",
                thinking = finalThinking,
                confidence = confidence,
                sourceType = sourceType,
                isFinal = true,
            ),
        )

        thinkingStorage.clear(sessionId)
    }

    /**
     * After the LLM stream completes, scan the accumulated raw content for tool call XML.
     */
    fun finalizeAndExtractToolCalls(): Boolean {
        val raw = rawContentBuffer.toString()
        val match = TOOL_CALL_XML_REGEX.find(raw)
        if (match == null) {
            logger.debug("No tool call XML found in accumulated content ({} chars)", raw.length)
            parsedToolCallFound = false
            return false
        }

        val jsonStr = match.groupValues[1].trim()
        logger.info("Found tool call XML: {}", jsonStr.take(200))

        return try {
            val obj = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(jsonStr)
                .let { it as? JsonObject }
                ?: throw IllegalArgumentException("Not a JSON object")

            parsedToolName = obj["name"]?.let {
                (it as? JsonPrimitive)?.content
            } ?: throw IllegalArgumentException("Missing 'name' field")

            parsedToolArgs = obj["arguments"]?.toString() ?: "{}"
            parsedToolCallFound = true

            // Strip ALL tool call XML from currentContent so it's not shown to the user
            currentContent = TOOL_CALL_XML_REGEX.replace(currentContent, "").trim()

            logger.info("Parsed tool call: {} with args: {}", parsedToolName, parsedToolArgs.take(200))
            true
        } catch (e: Exception) {
            logger.warn("Failed to parse tool call JSON: {} — error: {}", jsonStr.take(200), e.message)
            parsedToolCallFound = false
            false
        }
    }

    /**
     * Strip tool call XML from content so it's not shown to the user during streaming.
     */
    private fun stripToolCallXml(text: String): String {
        return TOOL_CALL_XML_REGEX.replace(text, "")
    }

    fun extractFinalResponse(content: String): String {
        val finalMatch = Regex("""<final>([\s\S]*?)</final>""").find(content)
        return finalMatch?.groupValues?.getOrNull(1)?.trim()
            ?: content
                .replace(Regex("""<think>[\s\S]*?</think>"""), "")
                .replace(Regex("""<final>|</final>"""), "")
                .replace(TOOL_CALL_XML_REGEX, "")
                .trim()
    }

    fun extractThinking(content: String): String {
        val thinkMatch = Regex("""<think>([\s\S]*?)</think>""").find(content)
        return thinkMatch?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    private suspend fun emit(event: AgentEvent) {
        eventEmitter(event)
    }

    fun reset() {
        currentContent = ""
        currentToolId = ""
        currentToolName = ""
        currentToolArgs = ""
        isToolCallInProgress = false
        totalUsage = null
        inThinkingState = false
        inFinalState = false
        rawContentBuffer.clear()
        parsedToolName = ""
        parsedToolArgs = ""
        parsedToolCallFound = false
    }

    companion object {
        private val TOOL_CALL_XML_REGEX = Regex("""<tool_call_json>\s*```.*?\n([\s\S]+?)\n```[\s\S]*?</tool_call_json>""")
    }
}
