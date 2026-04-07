package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentEvent
import com.example.smarty.server.llm.LlmUsage
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

    /**
     * Throttled emit for Processing events.
     * Only emits if at least [PROCESSING_EVENT_THROTTLE_MS] has passed since last emit,
     * or if this is a significant update (thinking just updated).
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
            emit(
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
     * Process a single LLM stream chunk
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

            cleanContent =
                cleanContent
                    .replace(Regex("<(?:think|thought|final)>"), "")
                    .replace(Regex("</(?:think|thought|final)>"), "")
            thinkingPart =
                thinkingPart
                    .replace(Regex("<(?:think|thought|final)>"), "")
                    .replace(Regex("</(?:think|thought|final)>"), "")

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

        val toolCall = chunk.toolCall
        if (toolCall != null) {
            if (!isToolCallInProgress) {
                isToolCallInProgress = true
                emit(
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

    suspend fun emitFinalResponse(
        content: String,
        confidence: String,
        sourceType: String,
    ) {
        val finalThinking = thinkingStorage.finalizeAndGetThinking(sessionId)

        if (finalThinking.isNotEmpty()) {
            emit(
                AgentEvent.Processing(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    content = "",
                    thinking = finalThinking,
                ),
            )
        }

        val finalAnswer = extractFinalResponse(content)

        emit(
            AgentEvent.Result(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                content = finalAnswer,
                thinking = finalThinking,
                confidence = confidence,
                sourceType = sourceType,
                isFinal = true,
            ),
        )

        thinkingStorage.clear(sessionId)
    }

    fun extractFinalResponse(content: String): String {
        val finalMatch = Regex("""<final>([\s\S]*?)</final>""").find(content)
        return finalMatch?.groupValues?.getOrNull(1)?.trim()
            ?: content
                .replace(Regex("""<think>[\s\S]*?</think>"""), "")
                .replace(Regex("""<final>|</final>"""), "")
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
    }
}
