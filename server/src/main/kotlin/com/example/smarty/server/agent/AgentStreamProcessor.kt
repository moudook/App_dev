package com.example.smarty.server.agent

import com.example.smarty.server.llm.LlmUsage
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * AgentStreamProcessor — Handles SSE streaming and state management.
 * Accumulates text, detects tool calls, and returns results.
 * No event emission — plugin bridge handles all events.
 *
 * Implements §2.5 Normalization Middleware:
 * - Strips markdown fences (```json) from LLM tool arguments before parsing
 * - Uses ToolCallAccumulator (keyed by integer index) for multi-frame JSON safety
 */
class AgentStreamProcessor(
    private val sessionId: String,
) {
    private val logger = LoggerFactory.getLogger(AgentStreamProcessor::class.java)
    private val thinkingStorage = ThinkingStorageManagerSingleton.instance

    var currentContent = ""
    var currentToolId: String? = null
    var currentToolName = ""
    var currentToolArgs = ""
    var currentToolStepIndex = -1
    var isToolCallInProgress = false
    var totalUsage: LlmUsage? = null
    var currentSubagentId: String? = null
    var finishReason: String? = null

    private var stepIndex = 0
    private var currentThinkingStepId: String? = null
    private val currentThinkingContent = StringBuilder()

    // ToolCallAccumulator map: keyed by integer index (per §6 spec — NOT string id)
    private val accumulators = ConcurrentHashMap<Int, ToolCallAccumulator>()

    // Regex for pseudo-narration (e.g. "[tool_call: web_search]")
    private val pseudoNarrationRegex = Regex("""\\[(?:tool_call|subtask|patch|file):.*?\\]""")

    // Normalization Middleware §2.5: strip markdown code fences from tool args
    private val markdownFenceRegex = Regex("""^```(?:json|javascript|js|python)?\s*\n?|```\s*$""", RegexOption.MULTILINE)

    /**
     * Strips markdown code fences that some models (LLaMA3, DeepSeek) inject
     * around tool call JSON arguments. Also trims stray whitespace/control chars.
     */
    private fun normalizeToolArgs(raw: String): String = raw.replace(markdownFenceRegex, "").trim()

    private suspend fun startThinkingStep() {
        if (currentThinkingStepId != null) return
        currentThinkingStepId = UUID.randomUUID().toString()
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
    }

    suspend fun processChunk(chunk: com.example.smarty.server.llm.LlmChunk) {
        chunk.usage?.let { totalUsage = it }
        chunk.subagentId?.let { currentSubagentId = it }

        // Track finish reason (error/busy/done) for upstream retry decisions
        chunk.finishReason?.let { finishReason = it }

        if (!chunk.reasoning.isNullOrEmpty()) {
            if (isToolCallInProgress) finalizeCurrentTool("completed")
            if (currentThinkingStepId == null) startThinkingStep()
            streamThinkingContent(chunk.reasoning)
        }

        if (!chunk.content.isNullOrEmpty()) {
            // Strip think tags
            val strippedContent = chunk.content.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
            val filteredContent = strippedContent.replace(pseudoNarrationRegex, "").trim()
            if (filteredContent.isNotEmpty()) {
                if (isToolCallInProgress) finalizeCurrentTool("completed")
                if (currentThinkingStepId != null) finalizeThinkingStep()

                currentContent += filteredContent
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
            }

            // Only append delta if there is one
            if (toolCall.arguments.isNotEmpty()) {
                // Normalization Middleware §2.5: strip markdown fences before accumulating
                val normalizedFragment = normalizeToolArgs(toolCall.arguments)
                currentToolArgs += normalizedFragment
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

    fun reset() {
        currentContent = ""
        currentToolId = null
        currentToolName = ""
        currentToolArgs = ""
        isToolCallInProgress = false
        stepIndex = 0
        currentThinkingStepId = null
        currentThinkingContent.clear()
        finishReason = null
        accumulators.clear()
    }
}
