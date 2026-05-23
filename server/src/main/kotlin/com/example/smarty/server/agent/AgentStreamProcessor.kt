package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentEvent
import com.example.smarty.server.llm.LlmUsage
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * AgentStreamProcessor — Handles SSE streaming, thinking state management, 
 * and persistent trace building (SMARTY_TRACE_V2).
 */
class AgentStreamProcessor(
    private val sessionId: String,
    private val eventEmitter: suspend (AgentEvent) -> Unit,
) {
    private val logger = LoggerFactory.getLogger(AgentStreamProcessor::class.java)

    // Thinking storage manager (singleton)
    private val thinkingStorage = ThinkingStorageManagerSingleton.instance

    // State machine for thinking vs final answer
    private var inThinkingState = false
    private var hasStartedFinalAnswer = false

    // Throttling
    private var lastProcessingEventTime = 0L
    private val PROCESSING_EVENT_THROTTLE_MS = 300L

    // Accumulated state for native tools
    var currentContent = ""
    var currentToolId: String? = null
    var currentToolName = ""
    var currentToolArgs = ""
    var isToolCallInProgress = false
    var totalUsage: LlmUsage? = null
    var currentSubagentId: String? = null

    // Agentic Step Tracking (UI only)
    private var stepIndex = 0
    private var currentThinkingStepId: String? = null
    private var currentThinkingStepStart: Long = 0L
    private val currentThinkingContent = StringBuilder()
    private val THINKING_STEP_THROTTLE_MS = 500L
    private var lastThinkingStepEmitTime = 0L

    /** Emit throttled processing events (incremental trace) */
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
        
        // Add to persistent trace — force new block to prevent combining with previous thought
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
        
        // Add to persistent trace
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

    /** Process a single LLM stream chunk from the provider. */
    suspend fun processChunk(chunk: com.example.smarty.server.llm.LlmChunk) {
        chunk.usage?.let { totalUsage = it }
        chunk.subagentId?.let { currentSubagentId = it }

        // Emit raw daemon event for live app introspection
        chunk.rawJson?.let { raw ->
            this.emit(AgentEvent.OpencodeRawEvent(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                data = raw,
                eventName = chunk.sseEvent,
                subagentId = currentSubagentId
            ))
            
            // If this was a structural event with no semantic data, stop here.
            if (chunk.content == null && chunk.reasoning == null && chunk.toolCall == null && chunk.toolResult == null) return
        }

        // 1. REASONING (Thinking)
        if (!chunk.reasoning.isNullOrEmpty()) {
            if (isToolCallInProgress) finalizeCurrentTool("completed")
            if (currentThinkingStepId == null) startThinkingStep()
            streamThinkingContent(chunk.reasoning)
            
            val trace = thinkingStorage.getCompleteThinking(sessionId)
            emitThrottledProcessing("", trace)
        }

        // 2. CONTENT (Final Answer)
        if (!chunk.content.isNullOrEmpty()) {
            if (isToolCallInProgress) finalizeCurrentTool("completed")
            if (currentThinkingStepId != null) finalizeThinkingStep()
            
            if (!hasStartedFinalAnswer) {
                hasStartedFinalAnswer = true
                this.emit(AgentEvent.FinalAnswerStarted(UUID.randomUUID().toString(), System.currentTimeMillis()))
            }
            this.emit(AgentEvent.FinalAnswerDelta(UUID.randomUUID().toString(), System.currentTimeMillis(), chunk.content))
            currentContent += chunk.content
            
            val trace = thinkingStorage.getCompleteThinking(sessionId)
            emitThrottledProcessing(chunk.content, trace)
        }

        // 3. TOOL CALL (Native)
        val toolCall = chunk.toolCall
        if (toolCall != null) {
            if (!isToolCallInProgress) {
                isToolCallInProgress = true
                if (currentThinkingStepId != null) finalizeThinkingStep()
                
                // Use deterministic ID for the tool block
                currentToolId = toolCall.id.ifEmpty { "tool-${UUID.randomUUID()}" }
                currentToolName = toolCall.functionName
                currentToolArgs = ""
                
                thinkingStorage.updateToolCall(
                    sessionId = sessionId,
                    toolCallId = currentToolId!!,
                    toolName = currentToolName,
                    status = "started",
                    inputSummary = toolCall.arguments
                )
                
                this.emit(AgentEvent.ToolCall(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    toolName = currentToolName,
                    displayName = "Running ${currentToolName.replace('_', ' ')}...",
                    status = "started"
                ))
            }
            
            currentToolArgs += toolCall.arguments
            
            // Live update the persistent trace with arguments as they stream
            thinkingStorage.updateToolCall(
                sessionId = sessionId,
                toolCallId = currentToolId!!,
                toolName = currentToolName,
                status = "started",
                inputSummary = currentToolArgs
            )

            this.emit(AgentEvent.ToolCallInput(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                toolId = currentToolId!!,
                inputDelta = toolCall.arguments
            ))
        }

        // 4. TOOL RESULT (Native)
        val toolResult = chunk.toolResult
        if (toolResult != null) {
            finalizeCurrentTool("completed", toolResult.result)
            
            this.emit(AgentEvent.ToolCallOutput(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                toolId = currentToolId ?: "unknown",
                output = toolResult.result
            ))
        }
    }

    private suspend fun finalizeCurrentTool(status: String, result: String? = null) {
        if (!isToolCallInProgress) return
        isToolCallInProgress = false
        
        val tid = currentToolId ?: "unknown"
        
        thinkingStorage.updateToolCall(
            sessionId = sessionId,
            toolCallId = tid,
            toolName = currentToolName,
            status = status,
            inputSummary = currentToolArgs,
            outputSummary = result
        )

        this.emit(AgentEvent.ToolCall(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            toolName = currentToolName,
            displayName = "Finished ${currentToolName.replace('_', ' ')}",
            status = status,
            inputSummary = currentToolArgs
        ))
        
        // Emit discrete step for UI
        this.emit(AgentEvent.AgentStep(
            eventId = tid,
            timestamp = System.currentTimeMillis(),
            stepIndex = stepIndex++,
            stepType = "tool_call",
            stepTitle = "Result: ${currentToolName.replace('_', ' ')}",
            stepContent = result ?: "",
            stepStatus = "completed",
            toolName = currentToolName,
            subagentId = currentSubagentId
        ))
        
        currentToolId = null
    }

    suspend fun emitFinalResponse(content: String, confidence: String, sourceType: String) {
        if (currentThinkingStepId != null) finalizeThinkingStep()
        finalizeCurrentTool("completed")

        val finalTrace = thinkingStorage.finalizeAndGetThinking(sessionId)
        this.emit(AgentEvent.Result(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            content = content,
            thinking = finalTrace,
            confidence = confidence,
            sourceType = sourceType,
            isFinal = true
        ))
        this.emit(AgentEvent.FinalAnswerFinished(UUID.randomUUID().toString(), System.currentTimeMillis()))
        thinkingStorage.clear(sessionId)
    }

    /** Restore for ServerAgent compatibility */
    suspend fun emitCustomToolStep(
        toolName: String,
        status: String,
        inputSummary: String = "",
        outputSummary: String = "",
        durationMs: Long? = null,
    ) {
        val tid = "custom-${UUID.randomUUID()}"
        thinkingStorage.updateToolCall(
            sessionId = sessionId,
            toolCallId = tid,
            toolName = toolName,
            status = status,
            inputSummary = inputSummary,
            outputSummary = outputSummary
        )

        this.emit(AgentEvent.AgentStep(
            eventId = tid,
            timestamp = System.currentTimeMillis(),
            stepIndex = stepIndex++,
            stepType = "tool_call",
            stepTitle = toolName,
            stepContent = if (status == "completed") outputSummary else inputSummary,
            stepStatus = status,
            toolName = toolName,
            durationMs = durationMs,
            subagentId = currentSubagentId
        ))
    }

    fun extractFinalResponse(content: String): String {
        val finalMatch = Regex("""<final>([\s\S]*?)</final>""").find(content)
        return finalMatch?.groupValues?.getOrNull(1)?.trim()
            ?: content.replace(Regex("""<think>[\s\S]*?</think>"""), "").replace(Regex("""<final>|</final>"""), "").trim()
    }

    fun extractThinking(content: String): String {
        val thinkMatch = Regex("""<think>([\s\S]*?)</think>""").find(content)
        return thinkMatch?.groupValues?.getOrNull(1)?.trim() ?: ""
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
    }
}
