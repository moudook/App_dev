package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentEvent
import com.example.smarty.server.llm.LlmUsage
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
    private val PROCESSING_EVENT_THROTTLE_MS = 300L

    var currentContent = ""
    var currentToolId: String? = null
    var currentToolName = ""
    var currentToolArgs = ""
    var currentToolStepIndex = -1
    var isToolCallInProgress = false
    var totalUsage: LlmUsage? = null
    var currentSubagentId: String? = null

    private var stepIndex = 0
    private var currentThinkingStepId: String? = null
    private var currentThinkingStepStart: Long = 0L
    private val currentThinkingContent = StringBuilder()
    private val THINKING_STEP_THROTTLE_MS = 250L
    private var lastThinkingStepEmitTime = 0L
    private var lastReasoningDeltaEmitTime = 0L
    private val reasoningDeltaBuffer = java.lang.StringBuilder()

    private var lastFinalAnswerDeltaEmitTime = 0L
    private val finalAnswerDeltaBuffer = java.lang.StringBuilder()
    private val FINAL_ANSWER_THROTTLE_MS = 150L

    // Regex for pseudo-narration (e.g. "[tool_call: web_search]")
    private val pseudoNarrationRegex = Regex("""\[(?:tool_call|subtask|patch|file):.*?\]""")

    suspend fun emitThrottledProcessing(
        content: String,
        thinking: String?,
    ) {
        val now = System.currentTimeMillis()
        val shouldEmit = (now - lastProcessingEventTime >= PROCESSING_EVENT_THROTTLE_MS) || (thinking != null)
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

        this.emit(AgentEvent.ReasoningStarted(UUID.randomUUID().toString(), currentThinkingStepStart))
        this.emit(
            AgentEvent.AgentStep(
                eventId = currentThinkingStepId!!,
                timestamp = currentThinkingStepStart,
                stepIndex = stepIndex++,
                stepType = "thinking",
                stepTitle = "Thinking…",
                stepContent = "",
                stepStatus = "started",
                subagentId = currentSubagentId,
            ),
        )
    }

    private suspend fun streamThinkingContent(content: String) {
        currentThinkingContent.append(content)
        val now = System.currentTimeMillis()
        thinkingStorage.addReasoning(sessionId, content, forceNewBlock = false)

        reasoningDeltaBuffer.append(content)
        if (now - lastReasoningDeltaEmitTime >= THINKING_STEP_THROTTLE_MS) {
            this.emit(AgentEvent.ReasoningDelta(UUID.randomUUID().toString(), now, reasoningDeltaBuffer.toString()))
            reasoningDeltaBuffer.clear()
            lastReasoningDeltaEmitTime = now
        }

        if (now - lastThinkingStepEmitTime < THINKING_STEP_THROTTLE_MS) return
        lastThinkingStepEmitTime = now
        currentThinkingStepId?.let { stepId ->
            this.emit(
                AgentEvent.AgentStep(
                    eventId = stepId,
                    timestamp = now,
                    stepIndex = stepIndex - 1,
                    stepType = "thinking",
                    stepTitle = "Thinking…",
                    stepContent = currentThinkingContent.toString(),
                    stepStatus = "streaming",
                    subagentId = currentSubagentId,
                ),
            )
        }
    }

    private suspend fun finalizeThinkingStep() {
        val stepId = currentThinkingStepId ?: return
        val duration = System.currentTimeMillis() - currentThinkingStepStart
        this.emit(
            AgentEvent.AgentStep(
                eventId = stepId,
                timestamp = System.currentTimeMillis(),
                stepIndex = stepIndex - 1,
                stepType = "thinking",
                stepTitle = "Thought",
                stepContent = currentThinkingContent.toString(),
                stepStatus = "completed",
                durationMs = duration,
                subagentId = currentSubagentId,
            ),
        )
        this.emit(AgentEvent.ReasoningFinished(UUID.randomUUID().toString(), System.currentTimeMillis()))
        currentThinkingStepId = null
        currentThinkingContent.clear()
        
        if (reasoningDeltaBuffer.isNotEmpty()) {
            this.emit(AgentEvent.ReasoningDelta(UUID.randomUUID().toString(), System.currentTimeMillis(), reasoningDeltaBuffer.toString()))
            reasoningDeltaBuffer.clear()
        }
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
            // Pseudo-narration filtering
            val filteredContent = chunk.content.replace(pseudoNarrationRegex, "").trim()
            if (filteredContent.isNotEmpty()) {
                if (isToolCallInProgress) finalizeCurrentTool("completed")
                if (currentThinkingStepId != null) finalizeThinkingStep()
                if (!hasStartedFinalAnswer) {
                    hasStartedFinalAnswer = true
                    this.emit(AgentEvent.FinalAnswerStarted(UUID.randomUUID().toString(), System.currentTimeMillis()))
                }
                
                finalAnswerDeltaBuffer.append(filteredContent)
                val now = System.currentTimeMillis()
                if (now - lastFinalAnswerDeltaEmitTime >= FINAL_ANSWER_THROTTLE_MS) {
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
            if (!isToolCallInProgress || currentToolName != toolCall.functionName) {
                if (isToolCallInProgress) finalizeCurrentTool("completed")
                isToolCallInProgress = true
                if (currentThinkingStepId != null) finalizeThinkingStep()
                currentToolId = toolCall.id.ifEmpty { "tool-${UUID.randomUUID()}" }
                currentToolName = toolCall.functionName
                currentToolArgs = ""
                currentToolStepIndex = stepIndex++

                thinkingStorage.updateToolCall(sessionId, currentToolId!!, currentToolName, "started", toolCall.arguments)
                this.emit(
                    AgentEvent.ToolCallStarted(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        toolId = currentToolId!!,
                        name = currentToolName,
                        source = "opencode",
                        subagentId = currentSubagentId
                    ),
                )
                this.emit(
                    AgentEvent.AgentStep(
                        eventId = currentToolId!!,
                        timestamp = System.currentTimeMillis(),
                        stepIndex = currentToolStepIndex,
                        stepType = "tool_call",
                        stepTitle = "Calling: $currentToolName",
                        stepContent = "",
                        stepStatus = "started",
                        toolName = currentToolName,
                        subagentId = currentSubagentId
                    ),
                )
            }
            
            // Only append delta if there is one
            if (toolCall.arguments.isNotEmpty()) {
                currentToolArgs += toolCall.arguments
                thinkingStorage.updateToolCall(sessionId, currentToolId!!, currentToolName, "started", currentToolArgs)
                this.emit(
                    AgentEvent.ToolCallInput(UUID.randomUUID().toString(), System.currentTimeMillis(), currentToolId!!, toolCall.arguments, subagentId = currentSubagentId),
                )
                this.emit(
                    AgentEvent.AgentStep(
                        eventId = currentToolId!!,
                        timestamp = System.currentTimeMillis(),
                        stepIndex = currentToolStepIndex,
                        stepType = "tool_call",
                        stepTitle = "Calling: $currentToolName",
                        stepContent = currentToolArgs,
                        stepStatus = "streaming",
                        toolName = currentToolName,
                        subagentId = currentSubagentId
                    ),
                )
            }

            // Explicit transitions from OpenCode daemon
            if (status == "completed" || status == "error") {
                finalizeCurrentTool(status)
            }
        }

        val toolResult = chunk.toolResult
        if (toolResult != null) {
            finalizeCurrentTool("completed", toolResult.result)
            this.emit(
                AgentEvent.ToolCallOutput(
                    UUID.randomUUID().toString(),
                    System.currentTimeMillis(),
                    currentToolId ?: "unknown",
                    toolResult.result,
                ),
            )
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
        this.emit(
            AgentEvent.ToolCallFinished(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                toolId = tid,
                durationMs = 0L,
                subagentId = currentSubagentId
            ),
        )
        val finalStatus = if (status == "error") "failed" else "completed"
        this.emit(
            AgentEvent.AgentStep(tid, System.currentTimeMillis(), currentToolStepIndex, "tool_call", "Result: $currentToolName", result ?: "", finalStatus, currentToolName, subagentId = currentSubagentId),
        )
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
            this.emit(AgentEvent.FinalAnswerDelta(UUID.randomUUID().toString(), System.currentTimeMillis(), finalAnswerDeltaBuffer.toString()))
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
        thinkingStorage.clear(sessionId)
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
        this.emit(
            AgentEvent.AgentStep(tid, System.currentTimeMillis(), stepIndex++, "tool_call", toolName, if (status == "completed") outputSummary else inputSummary, status, toolName, durationMs = durationMs, subagentId = currentSubagentId),
        )
    }

    fun extractFinalResponse(content: String) =
        Regex("""<final>([\s\S]*?)</final>""").find(content)?.groupValues?.getOrNull(1)?.trim() ?: content.replace(Regex("""<think>[\s\S]*?</think>"""), "").replace(Regex("""<final>|</final>"""), "").trim()

    fun extractThinking(content: String) = Regex("""<think>([\s\S]*?)</think>""").find(content)?.groupValues?.getOrNull(1)?.trim() ?: ""

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
    }
}
