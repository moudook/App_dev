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
    }

    /**
     * Process a single LLM stream chunk.
     */
    suspend fun processChunk(chunk: com.example.smarty.server.llm.LlmChunk) {
        chunk.usage?.let { totalUsage = it }

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
            cleanContent = stripToolCallXml(cleanContent)

            if (thinkingPart.isNotEmpty()) {
                thinkingStorage.addReasoning(sessionId, thinkingPart)
                streamThinkingContent(thinkingPart) // Feed into AgentStep
                reasoningUpdated = true
            }
            if (cleanContent.isNotEmpty()) {
                // Finalize any open thinking step when real content arrives
                if (currentThinkingStepId != null) finalizeThinkingStep()
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

        // Try all formats in priority order
        val candidates = listOf(
            TOOL_CALL_XML_REGEX,       // <tool_call_json>```json ... ```</tool_call_json>
            TOOL_CALL_JSON_BARE_REGEX, // <tool_call_json>{...}</tool_call_json>
            TOOL_CALL_BARE_REGEX,      // <tool_call>{...}</tool_call>
        )

        for (regex in candidates) {
            val match = regex.find(raw) ?: continue
            val jsonStr = match.groupValues[1].trim()
            logger.info("Found tool call (pattern=${regex.pattern.take(30)}): {}", jsonStr.take(200))

            return try {
                val obj = Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(jsonStr)
                    .let { it as? JsonObject }
                    ?: throw IllegalArgumentException("Not a JSON object")

                parsedToolName = obj["name"]?.let {
                    (it as? JsonPrimitive)?.contentOrNull
                } ?: throw IllegalArgumentException("Missing 'name' field")

                parsedToolArgs = obj["arguments"]?.toString()
                    ?: obj["parameters"]?.toString()
                    ?: "{}"
                parsedToolCallFound = true

                // Strip ALL tool call XML from currentContent so it's not shown to the user
                var cleaned = currentContent
                for (p in ALL_TOOL_CALL_PATTERNS) cleaned = p.replace(cleaned, "")
                currentContent = cleaned.trim()

                logger.info("Parsed tool call: {} with args: {}", parsedToolName, parsedToolArgs.take(200))
                true
            } catch (e: Exception) {
                logger.warn("Failed to parse tool call JSON ({} chars): {}", jsonStr.length, e.message)
                parsedToolCallFound = false
                continue
            }
        }

        logger.debug("No tool call found in accumulated content ({} chars)", raw.length)
        parsedToolCallFound = false
        return false
    }

    /**
     * Strip tool call XML from content so it's not shown to the user during streaming.
     */
    private fun stripToolCallXml(text: String): String {
        var result = text
        for (p in ALL_TOOL_CALL_PATTERNS) result = p.replace(result, "")
        return result
    }


    fun extractFinalResponse(content: String): String {
        val finalMatch = Regex("""<final>([\s\S]*?)</final>""").find(content)
        return finalMatch?.groupValues?.getOrNull(1)?.trim()
            ?: run {
                var cleaned = content
                    .replace(Regex("""<think>[\s\S]*?</think>"""), "")
                    .replace(Regex("""<final>|</final>"""), "")
                for (p in ALL_TOOL_CALL_PATTERNS) cleaned = p.replace(cleaned, "")
                cleaned.trim()
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
        rawContentBuffer.clear()
        parsedToolName = ""
        parsedToolArgs = ""
        parsedToolCallFound = false
        // Reset agentic step tracking
        stepIndex = 0
        currentThinkingStepId = null
        currentThinkingStepStart = 0L
        currentThinkingContent.clear()
        lastOpenCodeToolStepId = null
        lastOpenCodeToolStart = 0L
        lastThinkingStepEmitTime = 0L
    }

    companion object {
        // Format 1 (preferred): <tool_call_json>```json...```</tool_call_json>
        private val TOOL_CALL_XML_REGEX = Regex("""<tool_call_json>\s*```(?:json)?\s*\n?([\s\S]+?)\n?```[\s\S]*?</tool_call_json>""")

        // Format 2: <tool_call_json>{...}</tool_call_json> (no code block)
        private val TOOL_CALL_JSON_BARE_REGEX = Regex("""<tool_call_json>\s*([\s\S]*?)\s*</tool_call_json>""")

        // Format 3: <tool_call>{...}</tool_call> (opencode.json legacy format)
        private val TOOL_CALL_BARE_REGEX = Regex("""<tool_call>\s*([\s\S]*?)\s*</tool_call>""")

        // All strip patterns — used to clean content before display
        private val ALL_TOOL_CALL_PATTERNS = listOf(TOOL_CALL_XML_REGEX, TOOL_CALL_JSON_BARE_REGEX, TOOL_CALL_BARE_REGEX)
    }
}
