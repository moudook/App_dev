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
    var isToolCallInProgress = false
    var totalUsage: LlmUsage? = null
    var currentSubagentId: String? = null

    private var stepIndex = 0
    private var currentThinkingStepId: String? = null
    private var currentThinkingStepStart: Long = 0L
    private val currentThinkingContent = StringBuilder()
    private val THINKING_STEP_THROTTLE_MS = 500L
    private var lastThinkingStepEmitTime = 0L

    suspend fun emitThrottledProcessing(content: String, thinking: String?) {
        val now = System.currentTimeMillis()
        val shouldEmit = (now - lastProcessingEventTime >= PROCESSING_EVENT_THROTTLE_MS) || (thinking != null)
        if (shouldEmit) {
            this.emit(AgentEvent.Processing(
                eventId = UUID.randomUUID().toString(),
                timestamp = now,
                content = content,
                thinking = thinking,
                subagentId = currentSubagentId
            ))
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
        this.emit(AgentEvent.AgentStep(
            eventId = currentThinkingStepId!!,
            timestamp = currentThinkingStepStart,
            stepIndex = stepIndex++,
            stepType = "thinking",
            stepTitle = "Thinking…",
            stepContent = "",
            stepStatus = "started",
            subagentId = currentSubagentId
        ))
    }

    private suspend fun streamThinkingContent(content: String) {
        currentThinkingContent.append(content)
        val now = System.currentTimeMillis()
        thinkingStorage.addReasoning(sessionId, content, forceNewBlock = false)

        this.emit(AgentEvent.ReasoningDelta(UUID.randomUUID().toString(), now, content))

        if (now - lastThinkingStepEmitTime < THINKING_STEP_THROTTLE_MS) return
        lastThinkingStepEmitTime = now
        currentThinkingStepId?.let { stepId ->
            this.emit(AgentEvent.AgentStep(
                eventId = stepId,
                timestamp = now,
                stepIndex = stepIndex - 1,
                stepType = "thinking",
                stepTitle = "Thinking…",
                stepContent = currentThinkingContent.toString(),
                stepStatus = "streaming",
                subagentId = currentSubagentId
            ))
        }
    }

    private suspend fun finalizeThinkingStep() {
        val stepId = currentThinkingStepId ?: return
        val duration = System.currentTimeMillis() - currentThinkingStepStart
        this.emit(AgentEvent.AgentStep(
            eventId = stepId,
            timestamp = System.currentTimeMillis(),
            stepIndex = stepIndex - 1,
            stepType = "thinking",
            stepTitle = "Thought",
            stepContent = currentThinkingContent.toString(),
            stepStatus = "completed",
            durationMs = duration,
            subagentId = currentSubagentId
        ))
        this.emit(AgentEvent.ReasoningFinished(UUID.randomUUID().toString(), System.currentTimeMillis()))
        currentThinkingStepId = null
        currentThinkingContent.clear()
    }

    suspend fun processChunk(chunk: com.example.smarty.server.llm.LlmChunk) {
        chunk.usage?.let { totalUsage = it }
        chunk.subagentId?.let { currentSubagentId = it }

        chunk.rawJson?.let { raw ->
            this.emit(AgentEvent.OpencodeRawEvent(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                data = raw,
                eventName = chunk.sseEvent,
                subagentId = currentSubagentId
            ))
            if (chunk.content == null && chunk.reasoning == null && chunk.toolCall == null && chunk.toolResult == null) return
        }

        if (!chunk.reasoning.isNullOrEmpty()) {
            if (isToolCallInProgress) finalizeCurrentTool("completed")
            if (currentThinkingStepId == null) startThinkingStep()
            streamThinkingContent(chunk.reasoning)
            emitThrottledProcessing("", thinkingStorage.getCompleteThinking(sessionId))
        }

        if (!chunk.content.isNullOrEmpty()) {
            if (isToolCallInProgress) finalizeCurrentTool("completed")
            if (currentThinkingStepId != null) finalizeThinkingStep()
            if (!hasStartedFinalAnswer) {
                hasStartedFinalAnswer = true
                this.emit(AgentEvent.FinalAnswerStarted(UUID.randomUUID().toString(), System.currentTimeMillis()))
            }
            this.emit(AgentEvent.FinalAnswerDelta(UUID.randomUUID().toString(), System.currentTimeMillis(), chunk.content))
            currentContent += chunk.content
            emitThrottledProcessing(chunk.content, thinkingStorage.getCompleteThinking(sessionId))
        }

        val toolCall = chunk.toolCall
        if (toolCall != null) {
            if (!isToolCallInProgress) {
                isToolCallInProgress = true
                if (currentThinkingStepId != null) finalizeThinkingStep()
                currentToolId = toolCall.id.ifEmpty { "tool-${UUID.randomUUID()}" }
                currentToolName = toolCall.functionName
                currentToolArgs = ""
                
                thinkingStorage.updateToolCall(sessionId, currentToolId!!, currentToolName, "started", toolCall.arguments)
                this.emit(AgentEvent.ToolCall(UUID.randomUUID().toString(), System.currentTimeMillis(), currentToolName, "Running ${currentToolName}…", "started"))
            }
            currentToolArgs += toolCall.arguments
            thinkingStorage.updateToolCall(sessionId, currentToolId!!, currentToolName, "started", currentToolArgs)
            this.emit(AgentEvent.ToolCallInput(UUID.randomUUID().toString(), System.currentTimeMillis(), currentToolId!!, toolCall.arguments))
        }

        val toolResult = chunk.toolResult
        if (toolResult != null) {
            finalizeCurrentTool("completed", toolResult.result)
            this.emit(AgentEvent.ToolCallOutput(UUID.randomUUID().toString(), System.currentTimeMillis(), currentToolId ?: "unknown", toolResult.result))
        }
    }

    private suspend fun finalizeCurrentTool(status: String, result: String? = null) {
        if (!isToolCallInProgress) return
        isToolCallInProgress = false
        val tid = currentToolId ?: "unknown"
        thinkingStorage.updateToolCall(sessionId, tid, currentToolName, status, currentToolArgs, result)
        this.emit(AgentEvent.ToolCall(UUID.randomUUID().toString(), System.currentTimeMillis(), currentToolName, "Finished ${currentToolName}", status, currentToolArgs))
        this.emit(AgentEvent.AgentStep(tid, System.currentTimeMillis(), stepIndex++, "tool_call", "Result: $currentToolName", result ?: "", "completed", currentToolName, subagentId = currentSubagentId))
        currentToolId = null
    }

    suspend fun emitFinalResponse(content: String, confidence: String, sourceType: String) {
        if (currentThinkingStepId != null) finalizeThinkingStep()
        if (isToolCallInProgress) finalizeCurrentTool("completed")
        val finalTrace = thinkingStorage.finalizeAndGetThinking(sessionId)
        this.emit(AgentEvent.Result(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            content = content,
            thinking = finalTrace,
            citations = emptyList(),
            isFinal = true,
            confidence = confidence,
            sourceType = sourceType
        ))
        this.emit(AgentEvent.FinalAnswerFinished(UUID.randomUUID().toString(), System.currentTimeMillis()))
        thinkingStorage.clear(sessionId)
    }

    suspend fun emitCustomToolStep(
        toolName: String, 
        status: String, 
        inputSummary: String = "", 
        outputSummary: String = "", 
        durationMs: Long? = null
    ) {
        val tid = "custom-${UUID.randomUUID()}"
        thinkingStorage.updateToolCall(sessionId, tid, toolName, status, inputSummary, outputSummary)
        this.emit(AgentEvent.AgentStep(tid, System.currentTimeMillis(), stepIndex++, "tool_call", toolName, if (status == "completed") outputSummary else inputSummary, status, toolName, durationMs = durationMs, subagentId = currentSubagentId))
    }

    fun extractFinalResponse(content: String) = Regex("""<final>([\s\S]*?)</final>""").find(content)?.groupValues?.getOrNull(1)?.trim() ?: content.replace(Regex("""<think>[\s\S]*?</think>"""), "").replace(Regex("""<final>|</final>"""), "").trim()
    fun extractThinking(content: String) = Regex("""<think>([\s\S]*?)</think>""").find(content)?.groupValues?.getOrNull(1)?.trim() ?: ""
    private suspend fun emit(event: AgentEvent) = eventEmitter(event)
    fun reset() { currentContent = ""; currentToolId = null; currentToolName = ""; currentToolArgs = ""; isToolCallInProgress = false; hasStartedFinalAnswer = false; stepIndex = 0; currentThinkingStepId = null; currentThinkingContent.clear() }
}
