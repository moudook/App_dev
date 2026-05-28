package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentEvent
import com.example.smarty.server.llm.LlmUsage
import com.example.smarty.server.llm.PendingQuestion
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * AgentStreamProcessor — Handles SSE streaming and state management.
 * REVISION V6: Fixed parameter names for ServerAgent compatibility.
 */
class AgentStreamProcessor(
    private val sessionId: String,
    private val eventEmitter: suspend (AgentEvent) -> Unit,
) {
    private val logger = LoggerFactory.getLogger(AgentStreamProcessor::class.java)
    private val thinkingStorage = ThinkingStorageManagerSingleton.instance

    private var hasStartedFinalAnswer = false
    private var lastProcessingEventTime = 0L
    private val processingEventThrottleMs = 50L

    var currentContent = ""
    var currentToolId: String? = null
    var currentToolName = ""
    var currentToolArgs = ""
    var currentToolStepIndex = -1
    var isToolCallInProgress = false
    var totalUsage: LlmUsage? = null
    var currentSubagentId: String? = null

    var pendingAskUserQuestion: PendingQuestion? = null
        private set

    private var stepIndex = 0
    private var currentThinkingStepId: String? = null
    private var currentThinkingStepStart: Long = 0L
    private val currentThinkingContent = StringBuilder()
    private val thinkingStepThrottleMs = 50L
    private var lastThinkingStepEmitTime = 0L
    private var lastReasoningDeltaEmitTime = 0L
    private val reasoningDeltaBuffer = java.lang.StringBuilder()

    private var lastFinalAnswerDeltaEmitTime = 0L
    private val finalAnswerDeltaBuffer = java.lang.StringBuilder()
    private val finalAnswerThrottleMs = 50L

    // Regex for pseudo-narration (e.g. "[tool_call: web_search]")
    private val pseudoNarrationRegex = Regex("""\[(?:tool_call|subtask|patch|file):.*?\]""")

    suspend fun emitThrottledProcessing(
        content: String,
        thinking: String?,
    ) {
        val now = System.currentTimeMillis()
        val shouldEmit = (now - lastProcessingEventTime >= processingEventThrottleMs) || (thinking != null)
        if (shouldEmit) {
            this.emit(
                AgentEvent.Processing(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = now,
                    content = content,
                    thinking = thinking,
                    subagentId = currentSubagentId,
                ),
            )
            lastProcessingEventTime = now
        }
    }

    private suspend fun startThinkingStep() {
        if (currentThinkingStepId != null) return
        currentThinkingStepId = UUID.randomUUID().toString()
        currentThinkingStepStart = System.currentTimeMillis()
        currentThinkingContent.clear()
        thinkingStorage.addReasoning(sessionId, "", forceNewBlock = true)
    }

    private suspend fun streamThinkingContent(content: String) {
        currentThinkingContent.append(content)
        thinkingStorage.addReasoning(sessionId, content, forceNewBlock = false)
    }

    private suspend fun finalizeThinkingStep() {
        if (currentThinkingStepId == null) return
        currentThinkingStepId = null
        currentThinkingContent.clear()
        reasoningDeltaBuffer.clear()
    }

    suspend fun processChunk(chunk: com.example.smarty.server.llm.LlmChunk) {
        chunk.usage?.let { totalUsage = it }
        chunk.subagentId?.let { currentSubagentId = it }

        chunk.rawJson?.let { raw ->
            this.emit(
                AgentEvent.OpencodeRawEvent(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    data = raw,
                    eventName = chunk.sseEvent,
                    subagentId = currentSubagentId,
                ),
            )
            if (chunk.content == null && chunk.reasoning == null && chunk.toolCall == null && chunk.toolResult == null) return
        }

        if (!chunk.reasoning.isNullOrEmpty()) {
            if (isToolCallInProgress) finalizeCurrentTool("completed")
            if (currentThinkingStepId == null) startThinkingStep()
            streamThinkingContent(chunk.reasoning)
            emitThrottledProcessing("", thinkingStorage.getCompleteThinking(sessionId))
        }

        if (!chunk.content.isNullOrEmpty()) {
            // Strip think tags
            val strippedContent = chunk.content.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
            val filteredContent = strippedContent.replace(pseudoNarrationRegex, "").trim()
            if (filteredContent.isNotEmpty()) {
                if (isToolCallInProgress) finalizeCurrentTool("completed")
                if (currentThinkingStepId != null) finalizeThinkingStep()
                if (!hasStartedFinalAnswer) {
                    hasStartedFinalAnswer = true
                    this.emit(AgentEvent.FinalAnswerStarted(UUID.randomUUID().toString(), System.currentTimeMillis()))
                }

                finalAnswerDeltaBuffer.append(filteredContent)
                val now = System.currentTimeMillis()
                if (now - lastFinalAnswerDeltaEmitTime >= finalAnswerThrottleMs) {
                    this.emit(AgentEvent.FinalAnswerDelta(UUID.randomUUID().toString(), now, finalAnswerDeltaBuffer.toString()))
                    finalAnswerDeltaBuffer.clear()
                    lastFinalAnswerDeltaEmitTime = now
                }

                currentContent += filteredContent
                emitThrottledProcessing(chunk.content, thinkingStorage.getCompleteThinking(sessionId))
            }
        }

        val toolCall = chunk.toolCall
        if (toolCall != null) {
            val status = toolCall.status ?: "running"

            // Capture ask_user question before the tool_result clears isToolCallInProgress
            chunk.question?.let { pendingAskUserQuestion = it }
            if (!isToolCallInProgress || currentToolName != toolCall.functionName) {
                if (isToolCallInProgress) finalizeCurrentTool("completed")
                isToolCallInProgress = true
                if (currentThinkingStepId != null) finalizeThinkingStep()
                currentToolId = toolCall.id.ifEmpty { "tool-${UUID.randomUUID()}" }
                currentToolName = toolCall.functionName
                currentToolArgs = ""
                currentToolStepIndex = stepIndex++

                thinkingStorage.updateToolCall(sessionId, currentToolId!!, currentToolName, "started", toolCall.arguments)
            }

            // Only append delta if there is one
            if (toolCall.arguments.isNotEmpty()) {
                currentToolArgs += toolCall.arguments
                thinkingStorage.updateToolCall(sessionId, currentToolId!!, currentToolName, "started", currentToolArgs)
            }

            // Explicit transitions from OpenCode daemon
            if (status == "completed" || status == "error") {
                finalizeCurrentTool(status)
            }
        }

        val toolResult = chunk.toolResult
        if (toolResult != null) {
            finalizeCurrentTool("completed", toolResult.result)
        }
    }

    private suspend fun finalizeCurrentTool(
        status: String,
        result: String? = null,
    ) {
        if (!isToolCallInProgress) return
        isToolCallInProgress = false
        val tid = currentToolId ?: "unknown"
        thinkingStorage.updateToolCall(sessionId, tid, currentToolName, status, currentToolArgs, result)
        currentToolId = null
    }

    suspend fun emitFinalResponse(
        content: String,
        confidence: String,
        sourceType: String,
    ) {
        if (currentThinkingStepId != null) finalizeThinkingStep()
        if (isToolCallInProgress) finalizeCurrentTool("completed")

        if (finalAnswerDeltaBuffer.isNotEmpty()) {
            this.emit(
                AgentEvent.FinalAnswerDelta(UUID.randomUUID().toString(), System.currentTimeMillis(), finalAnswerDeltaBuffer.toString()),
            )
            finalAnswerDeltaBuffer.clear()
        }

        val finalTrace = thinkingStorage.finalizeAndGetThinking(sessionId)
        this.emit(
            AgentEvent.Result(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                content = content,
                thinking = finalTrace,
                citations = emptyList(),
                isFinal = true,
                confidence = confidence,
                sourceType = sourceType,
            ),
        )
        this.emit(AgentEvent.FinalAnswerFinished(UUID.randomUUID().toString(), System.currentTimeMillis()))
        // thinkingStorage.clear(sessionId) is now handled by the caller (ChatRoutes) after saving the message
    }

    suspend fun emitCustomToolStep(
        toolName: String,
        status: String,
        inputSummary: String = "",
        outputSummary: String = "",
        durationMs: Long? = null,
    ) {
        val tid = "custom-${UUID.randomUUID()}"
        thinkingStorage.updateToolCall(sessionId, tid, toolName, status, inputSummary, outputSummary)
    }

    private suspend fun emit(event: AgentEvent) = eventEmitter(event)

    fun reset() {
        currentContent = ""
        currentToolId = null
        currentToolName = ""
        currentToolArgs = ""
        isToolCallInProgress = false
        hasStartedFinalAnswer = false
        stepIndex = 0
        currentThinkingStepId = null
        currentThinkingContent.clear()
        pendingAskUserQuestion = null
    }
}
