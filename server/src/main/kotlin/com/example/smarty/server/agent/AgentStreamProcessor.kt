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
    private var hasStartedFinalAnswer = false

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

    // === Agentic Step Tracking ===
    // Each reasoning phase and tool call becomes a discrete step shown in the UI.
    private var stepIndex = 0
    private var currentThinkingStepId: String? = null
    private var currentThinkingStepStart: Long = 0L
    private val currentThinkingContent = StringBuilder()
    private var lastOpenCodeToolStepId: String? = null
    private var lastOpenCodeToolStart: Long = 0L
    private val THINKING_STEP_THROTTLE_MS = 500L
    private var lastThinkingStepEmitTime = 0L

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

    // ===================================================================
    // Agentic Step Helpers — emit AgentStep events for each discrete phase
    // ===================================================================

    private suspend fun startThinkingStep() {
        if (currentThinkingStepId != null) return
        currentThinkingStepId = UUID.randomUUID().toString()
        currentThinkingStepStart = System.currentTimeMillis()
        currentThinkingContent.clear()
        this.emit(
            AgentEvent.ReasoningStarted(
                eventId = UUID.randomUUID().toString(),
                timestamp = currentThinkingStepStart
            )
        )
        this.emit(
            AgentEvent.AgentStep(
                eventId = currentThinkingStepId!!,
                timestamp = currentThinkingStepStart,
                stepIndex = stepIndex++,
                stepType = "thinking",
                stepTitle = "Thinking\u2026",
                stepContent = "",
                stepStatus = "started",
            )
        )
    }

    private suspend fun streamThinkingContent(content: String) {
        currentThinkingContent.append(content)
        val now = System.currentTimeMillis()
        
        this.emit(
            AgentEvent.ReasoningDelta(
                eventId = UUID.randomUUID().toString(),
                timestamp = now,
                text = content
            )
        )
        
        if (now - lastThinkingStepEmitTime < THINKING_STEP_THROTTLE_MS) return
        lastThinkingStepEmitTime = now
        currentThinkingStepId?.let { stepId ->
            this.emit(
                AgentEvent.AgentStep(
                    eventId = stepId,
                    timestamp = now,
                    stepIndex = stepIndex - 1,
                    stepType = "thinking",
                    stepTitle = "Thinking\u2026",
                    stepContent = currentThinkingContent.toString(),
                    stepStatus = "streaming",
                )
            )
        }
    }

    private suspend fun finalizeThinkingStep() {
        val stepId = currentThinkingStepId ?: return
        val duration = System.currentTimeMillis() - currentThinkingStepStart
        val seconds = duration / 1000
        this.emit(
            AgentEvent.AgentStep(
                eventId = stepId,
                timestamp = System.currentTimeMillis(),
                stepIndex = stepIndex - 1,
                stepType = "thinking",
                stepTitle = if (seconds > 0) "Thought for ${seconds}s" else "Thought",
                stepContent = currentThinkingContent.toString(),
                stepStatus = "completed",
                durationMs = duration,
            )
        )
        this.emit(
            AgentEvent.ReasoningFinished(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis()
            )
        )
        currentThinkingStepId = null
        currentThinkingContent.clear()
    }

    private suspend fun emitOpenCodeToolStep(toolName: String, status: String, content: String = "") {
        val displayName = when (toolName.lowercase()) {
            "web_search", "websearch" -> "Searching the web"
            "read_file" -> "Reading file"
            "bash" -> "Running command"
            "write_file" -> "Writing file"
            else -> toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
        val stepId = lastOpenCodeToolStepId ?: UUID.randomUUID().toString().also {
            lastOpenCodeToolStepId = it
            lastOpenCodeToolStart = System.currentTimeMillis()
        }
        val duration = if (status == "completed" || status == "failed")
            System.currentTimeMillis() - lastOpenCodeToolStart else null
        this.emit(
            AgentEvent.AgentStep(
                eventId = stepId,
                timestamp = System.currentTimeMillis(),
                stepIndex = if (status == "started") stepIndex++ else stepIndex - 1,
                stepType = "opencode_tool",
                stepTitle = displayName,
                stepContent = content,
                stepStatus = status,
                toolName = toolName,
                durationMs = duration,
            )
        )
        
        if (status == "started") {
            this.emit(AgentEvent.ToolCallStarted(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                toolId = stepId,
                name = toolName,
                source = "opencode"
            ))
        } else if (status == "completed" || status == "failed") {
            this.emit(AgentEvent.ToolCallFinished(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                toolId = stepId,
                durationMs = duration ?: 0L
            ))
        }
        
        if (status == "completed" || status == "failed") lastOpenCodeToolStepId = null
    }

    /** Called from the main agent loop when a Smarty custom tool is dispatched. */
    suspend fun emitCustomToolStep(
        toolName: String,
        status: String,
        inputSummary: String = "",
        outputSummary: String = "",
        durationMs: Long? = null,
    ) {
        val displayName = when (toolName.lowercase()) {
            "read_notes", "search_notes" -> "Reading notes"
            "create_note", "add_note" -> "Creating note"
            "read_calendar", "get_events" -> "Checking calendar"
            "add_event", "create_event" -> "Adding calendar event"
            "web_search" -> "Searching the web"
            else -> toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
        val stepId = UUID.randomUUID().toString()
        this.emit(
            AgentEvent.AgentStep(
                eventId = stepId,
                timestamp = System.currentTimeMillis(),
                stepIndex = if (status == "started") stepIndex++ else stepIndex - 1,
                stepType = "tool_call",
                stepTitle = displayName,
                stepContent = if (status == "completed") outputSummary.take(300)
                              else inputSummary.take(300),
                stepStatus = status,
                toolName = toolName,
                durationMs = durationMs,
            )
        )
        
        if (status == "started") {
            this.emit(AgentEvent.ToolCallStarted(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                toolId = stepId,
                name = toolName,
                source = "custom"
            ))
        } else if (status == "completed" || status == "failed") {
            this.emit(AgentEvent.ToolCallFinished(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                toolId = stepId,
                durationMs = durationMs ?: 0L
            ))
        }
    }

    /**
     * Process a single LLM stream chunk.
     */
    suspend fun processChunk(chunk: com.example.smarty.server.llm.LlmChunk) {
        chunk.usage?.let { totalUsage = it }

        // If a tool was in progress, but the daemon has started streaming text or reasoning again,
        // it means the daemon-native tool execution has completed and the model has resumed!
        if (isToolCallInProgress && chunk.toolCall == null && (!chunk.content.isNullOrEmpty() || !chunk.reasoning.isNullOrEmpty())) {
            isToolCallInProgress = false
            emitOpenCodeToolStep(currentToolName, "completed", currentToolArgs)
            this.emit(
                AgentEvent.ToolCall(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    toolName = currentToolName,
                    displayName = "Finished ${currentToolName.replace('_', ' ')}",
                    status = "completed",
                    inputSummary = currentToolArgs,
                )
            )
        }

        var reasoningUpdated = false
        if (!chunk.reasoning.isNullOrEmpty()) {
            // Start agentic thinking step on first reasoning token
            if (currentThinkingStepId == null) startThinkingStep()
            streamThinkingContent(chunk.reasoning)

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
                    if (currentThinkingStepId == null) startThinkingStep()
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


            if (thinkingPart.isNotEmpty()) {
                thinkingStorage.addReasoning(sessionId, thinkingPart)
                streamThinkingContent(thinkingPart) // Feed into AgentStep
                reasoningUpdated = true
            }
            if (cleanContent.isNotEmpty()) {
                // Finalize any open thinking step when real content arrives
                if (currentThinkingStepId != null) finalizeThinkingStep()
                
                if (!hasStartedFinalAnswer) {
                    hasStartedFinalAnswer = true
                    this.emit(AgentEvent.FinalAnswerStarted(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis()
                    ))
                }
                
                this.emit(AgentEvent.FinalAnswerDelta(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    text = cleanContent
                ))
                
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

        // Handle native OpenCode tool_use events (e.g., websearch from daemon)
        val toolCall = chunk.toolCall
        if (toolCall != null) {
            if (!isToolCallInProgress) {
                isToolCallInProgress = true
                // Finalize any open thinking step before starting a tool step
                if (currentThinkingStepId != null) finalizeThinkingStep()
                emitOpenCodeToolStep(toolCall.functionName, "started")
                this.emit(
                    AgentEvent.ToolCall(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        toolName = toolCall.functionName,
                        displayName = "Running ${toolCall.functionName.replace('_', ' ')}…",
                        status = "started",
                    ),
                )
            }
            if (toolCall.id.isNotEmpty()) currentToolId = toolCall.id
            if (toolCall.functionName.isNotEmpty()) currentToolName = toolCall.functionName
            currentToolArgs += toolCall.arguments
            
            if (toolCall.arguments.isNotEmpty()) {
                this.emit(
                    AgentEvent.ToolCallInput(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        toolId = lastOpenCodeToolStepId ?: currentToolId,
                        inputDelta = toolCall.arguments
                    )
                )
            }
        }

        // Handle native OpenCode tool_result events
        val toolResult = chunk.toolResult
        if (toolResult != null) {
            // Automatically complete the tool if it was in progress
            if (isToolCallInProgress) {
                isToolCallInProgress = false
                emitOpenCodeToolStep(currentToolName, "completed", currentToolArgs)
                this.emit(
                    AgentEvent.ToolCall(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        toolName = currentToolName,
                        displayName = "Finished ${currentToolName.replace('_', ' ')}",
                        status = "completed",
                    ),
                )
            }
            
            // Emit the tool result itself as a discrete agent step so the UI can display the output
            val stepId = UUID.randomUUID().toString()
            this.emit(
                AgentEvent.AgentStep(
                    eventId = stepId,
                    timestamp = System.currentTimeMillis(),
                    stepIndex = stepIndex++,
                    stepType = "tool_result",
                    stepTitle = "Result from ${toolResult.functionName.replace('_', ' ')}",
                    stepContent = toolResult.result,
                    stepStatus = "completed",
                    toolName = toolResult.functionName,
                    durationMs = 0
                )
            )
            
            this.emit(
                AgentEvent.ToolCallOutput(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    toolId = lastOpenCodeToolStepId ?: currentToolId,
                    output = toolResult.result
                )
            )
        }
    }

    /**
     * Emit the final response to the client.
     * Finalizes any open thinking step before emitting the result.
     */
    suspend fun emitFinalResponse(
        content: String,
        confidence: String,
        sourceType: String,
    ) {
        // Finalize any still-open thinking step
        if (currentThinkingStepId != null) finalizeThinkingStep()

        // Finalize any still-open daemon tool call
        if (isToolCallInProgress) {
            isToolCallInProgress = false
            emitOpenCodeToolStep(currentToolName, "completed", currentToolArgs)
            this.emit(
                AgentEvent.ToolCall(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    toolName = currentToolName,
                    displayName = "Finished ${currentToolName.replace('_', ' ')}",
                    status = "completed",
                    inputSummary = currentToolArgs,
                )
            )
        }

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

        this.emit(
            AgentEvent.FinalAnswerFinished(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis()
            )
        )

        thinkingStorage.clear(sessionId)
    }

    fun extractFinalResponse(content: String): String {
        val finalMatch = Regex("""<final>([\s\S]*?)</final>""").find(content)
        return finalMatch?.groupValues?.getOrNull(1)?.trim()
            ?: run {
                content
                    .replace(Regex("""<think>[\s\S]*?</think>"""), "")
                    .replace(Regex("""<final>|</final>"""), "")
                    .trim()
            }
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
        hasStartedFinalAnswer = false
        // Reset agentic step tracking
        stepIndex = 0
        currentThinkingStepId = null
        currentThinkingStepStart = 0L
        currentThinkingContent.clear()
        lastOpenCodeToolStepId = null
        lastOpenCodeToolStart = 0L
        lastThinkingStepEmitTime = 0L
    }
}
