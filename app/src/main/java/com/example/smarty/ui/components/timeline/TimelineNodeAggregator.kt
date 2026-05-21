package com.example.smarty.ui.components.timeline

import com.example.smarty.protocol.AgentEvent

/**
 * Converts a raw, append-only list of [AgentEvent]s into a stable, ordered
 * list of [TimelineNode]s suitable for direct Compose rendering.
 *
 * Design invariants:
 *  - Nodes are appended; existing nodes are mutated in-place (via index) not
 *    replaced, so Compose [LazyColumn] stable keys remain valid.
 *  - Tier-2 system activity is collapsed into a single [TimelineNode.SystemActivity]
 *    node that is updated incrementally.
 *  - Reasoning deltas are merged into the single open [TimelineNode.Thinking] node.
 *  - Tool events are grouped by toolId so concurrent tools form independent nodes.
 *
 * Usage:
 * ```kotlin
 * val nodes = remember { mutableStateListOf<TimelineNode>() }
 * val aggregator = remember { TimelineNodeAggregator(nodes) }
 * LaunchedEffect(events) { aggregator.processAll(events) }
 * ```
 */
class TimelineNodeAggregator {

    // Ordered list of nodes — mutated incrementally
    private val _nodes = mutableListOf<TimelineNode>()
    val nodes: List<TimelineNode> get() = _nodes

    // Index maps for O(1) lookup
    private var thinkingNodeIndex: Int = -1
    private val toolNodeIndexByToolId = mutableMapOf<String, Int>()
    private val approvalNodeIndexByToolId = mutableMapOf<String, Int>()
    private var systemActivityIndex: Int = -1
    private var recoveryNodeIndex: Int = -1

    // Tracking state for system activity
    private var sysActivityId: String = "sys_activity"
    private var sysActivityStart: Long = 0L

    /**
     * Process a new batch of events (append-only). Only processes events
     * beyond the last processed index.
     */
    fun processAll(events: List<AgentEvent>, fromIndex: Int = 0): List<TimelineNode> {
        for (i in fromIndex until events.size) {
            process(events[i])
        }
        return _nodes
    }

    /**
     * Process a single [AgentEvent] and mutate the node list accordingly.
     */
    fun process(event: AgentEvent) {
        when (event) {
            // ── Reasoning ─────────────────────────────────────────────────────
            is AgentEvent.ReasoningStarted -> {
                val node = TimelineNode.Thinking(
                    id = "thinking_${event.eventId}",
                    timestamp = event.timestamp,
                    text = "",
                    isStreaming = true,
                )
                thinkingNodeIndex = _nodes.size
                _nodes.add(node)
            }

            is AgentEvent.ReasoningDelta -> {
                updateThinkingNode { existing ->
                    existing.copy(text = existing.text + event.text, isStreaming = true)
                }
            }

            is AgentEvent.ReasoningFinished -> {
                updateThinkingNode { existing ->
                    existing.copy(isStreaming = false)
                }
                thinkingNodeIndex = -1
            }

            // Legacy: Processing event maps to a thinking update
            is AgentEvent.Processing -> {
                val content = event.content
                if (content.isBlank()) return
                if (thinkingNodeIndex < 0) {
                    val node = TimelineNode.Thinking(
                        id = "thinking_processing_${event.eventId}",
                        timestamp = event.timestamp,
                        text = content,
                        isStreaming = true,
                    )
                    thinkingNodeIndex = _nodes.size
                    _nodes.add(node)
                } else {
                    updateThinkingNode { it.copy(text = it.text + content, isStreaming = true) }
                }
            }

            // ── Tool Lifecycle ────────────────────────────────────────────────
            is AgentEvent.ToolCallStarted -> {
                val node = TimelineNode.ToolExecution(
                    id = event.toolId,
                    timestamp = event.timestamp,
                    toolName = event.name,
                    displayName = formatToolDisplayName(event.name),
                    source = event.source,
                    status = TimelineNode.ToolExecution.Status.RUNNING,
                    inputSummary = null,
                    outputSummary = null,
                    durationMs = null,
                )
                toolNodeIndexByToolId[event.toolId] = _nodes.size
                _nodes.add(node)
            }

            is AgentEvent.ToolCallInput -> {
                updateToolNode(event.toolId) { existing ->
                    val appended = if (existing.inputSummary.isNullOrBlank()) {
                        event.inputDelta
                    } else {
                        existing.inputSummary + event.inputDelta
                    }
                    existing.copy(inputSummary = appended.take(800))
                }
            }

            is AgentEvent.ToolCallOutput -> {
                updateToolNode(event.toolId) { existing ->
                    val appended = if (existing.outputSummary.isNullOrBlank()) {
                        event.output
                    } else {
                        existing.outputSummary + "\n" + event.output
                    }
                    existing.copy(outputSummary = appended.take(1200))
                }
            }

            is AgentEvent.ToolCallFinished -> {
                updateToolNode(event.toolId) { existing ->
                    existing.copy(
                        status = TimelineNode.ToolExecution.Status.COMPLETED,
                        durationMs = event.durationMs,
                    )
                }
            }

            // Legacy ToolCall SSE event
            is AgentEvent.ToolCall -> {
                val toolId = "${event.toolName}_${event.timestamp}"
                when (event.status) {
                    "started" -> {
                        if (!toolNodeIndexByToolId.containsKey(toolId)) {
                            val node = TimelineNode.ToolExecution(
                                id = toolId,
                                timestamp = event.timestamp,
                                toolName = event.toolName,
                                displayName = event.displayName,
                                source = "opencode",
                                status = TimelineNode.ToolExecution.Status.RUNNING,
                                inputSummary = event.inputSummary,
                                outputSummary = null,
                                durationMs = null,
                            )
                            toolNodeIndexByToolId[toolId] = _nodes.size
                            _nodes.add(node)
                        }
                    }
                    "completed" -> {
                        updateToolNode(toolId) { it.copy(status = TimelineNode.ToolExecution.Status.COMPLETED, outputSummary = event.outputSummary) }
                    }
                    "failed" -> {
                        updateToolNode(toolId) { it.copy(status = TimelineNode.ToolExecution.Status.FAILED) }
                    }
                }
            }

            // Legacy AgentStep events
            is AgentEvent.AgentStep -> {
                val stepId = "step_${event.stepIndex}"
                when (event.stepType) {
                    "thinking" -> {
                        if (thinkingNodeIndex < 0) {
                            val node = TimelineNode.Thinking(
                                id = "thinking_step_${event.stepIndex}",
                                timestamp = event.timestamp,
                                text = event.stepContent,
                                isStreaming = event.stepStatus == "streaming",
                            )
                            thinkingNodeIndex = _nodes.size
                            _nodes.add(node)
                        } else {
                            updateThinkingNode { it.copy(text = event.stepContent, isStreaming = event.stepStatus == "streaming") }
                        }
                    }
                    "tool_call", "opencode_tool", "tool_result" -> {
                        val existing = toolNodeIndexByToolId[stepId]
                        if (existing == null) {
                            val node = TimelineNode.ToolExecution(
                                id = stepId,
                                timestamp = event.timestamp,
                                toolName = event.toolName ?: event.stepTitle,
                                displayName = event.stepTitle,
                                source = if (event.stepType == "opencode_tool") "opencode" else "mcp",
                                status = when (event.stepStatus) {
                                    "completed" -> TimelineNode.ToolExecution.Status.COMPLETED
                                    "failed" -> TimelineNode.ToolExecution.Status.FAILED
                                    else -> TimelineNode.ToolExecution.Status.RUNNING
                                },
                                inputSummary = if (event.stepType != "tool_result") event.stepContent.take(400) else null,
                                outputSummary = if (event.stepType == "tool_result") event.stepContent.take(800) else null,
                                durationMs = event.durationMs,
                            )
                            toolNodeIndexByToolId[stepId] = _nodes.size
                            _nodes.add(node)
                        } else {
                            _nodes[existing] = (_nodes[existing] as? TimelineNode.ToolExecution)?.copy(
                                status = when (event.stepStatus) {
                                    "completed" -> TimelineNode.ToolExecution.Status.COMPLETED
                                    "failed" -> TimelineNode.ToolExecution.Status.FAILED
                                    else -> TimelineNode.ToolExecution.Status.RUNNING
                                },
                                outputSummary = if (event.stepType == "tool_result") event.stepContent.take(800) else null,
                                durationMs = event.durationMs,
                            ) ?: _nodes[existing]
                        }
                    }
                }
            }

            // ── Approvals ─────────────────────────────────────────────────────
            is AgentEvent.ApprovalRequested -> {
                val node = TimelineNode.ApprovalGate(
                    id = "approval_${event.toolId}",
                    timestamp = event.timestamp,
                    toolId = event.toolId,
                    status = TimelineNode.ApprovalGate.Status.PENDING,
                )
                approvalNodeIndexByToolId[event.toolId] = _nodes.size
                _nodes.add(node)
            }

            is AgentEvent.ApprovalGranted -> {
                updateApprovalNode(event.toolId) { it.copy(status = TimelineNode.ApprovalGate.Status.GRANTED) }
            }

            is AgentEvent.ApprovalDenied -> {
                updateApprovalNode(event.toolId) { it.copy(status = TimelineNode.ApprovalGate.Status.DENIED) }
            }

            // ── Errors ─────────────────────────────────────────────────────────
            is AgentEvent.SessionError -> {
                _nodes.add(
                    TimelineNode.ErrorNode(
                        id = "error_${event.eventId}",
                        timestamp = event.timestamp,
                        message = event.message,
                    )
                )
            }

            is AgentEvent.SessionAborted -> {
                _nodes.add(
                    TimelineNode.ErrorNode(
                        id = "aborted_${event.eventId}",
                        timestamp = event.timestamp,
                        message = event.reason,
                        isAborted = true,
                    )
                )
            }

            // Legacy Error event
            is AgentEvent.Error -> {
                _nodes.add(
                    TimelineNode.ErrorNode(
                        id = "error_${event.eventId}",
                        timestamp = event.timestamp,
                        message = event.message,
                    )
                )
            }

            // ── Recovery ──────────────────────────────────────────────────────
            is AgentEvent.RecoveryStarted -> {
                val node = TimelineNode.RecoveryNode(
                    id = "recovery_${event.eventId}",
                    timestamp = event.timestamp,
                    reason = event.reason,
                    succeeded = null,
                )
                recoveryNodeIndex = _nodes.size
                _nodes.add(node)
            }

            is AgentEvent.RecoveryFinished -> {
                if (recoveryNodeIndex >= 0 && recoveryNodeIndex < _nodes.size) {
                    val existing = _nodes[recoveryNodeIndex] as? TimelineNode.RecoveryNode
                    if (existing != null) {
                        _nodes[recoveryNodeIndex] = existing.copy(succeeded = event.success)
                    }
                }
                recoveryNodeIndex = -1
            }

            // ── Tier-2 System Activity ─────────────────────────────────────────
            is AgentEvent.CacheHit -> incrementSystemActivity(cacheHits = 1, ts = event.timestamp)
            is AgentEvent.CacheMiss -> incrementSystemActivity(cacheMisses = 1, ts = event.timestamp)
            is AgentEvent.DbRead -> incrementSystemActivity(dbReads = 1, ts = event.timestamp)
            is AgentEvent.DbWrite -> incrementSystemActivity(dbWrites = 1, ts = event.timestamp)
            is AgentEvent.SyncStarted -> {
                if (sysActivityStart == 0L) sysActivityStart = event.timestamp
                incrementSystemActivity(syncCount = 1, ts = event.timestamp, isOngoing = true)
            }
            is AgentEvent.SyncFinished -> {
                val duration = if (sysActivityStart > 0L) event.timestamp - sysActivityStart else 0L
                updateSystemActivity { it.copy(durationMs = it.durationMs + duration, isOngoing = false) }
                sysActivityStart = 0L
            }

            // Ignored events (no UI representation needed at this level)
            is AgentEvent.SessionStarted,
            is AgentEvent.SessionCompleted,
            is AgentEvent.ModelResolved,
            is AgentEvent.StepStarted,
            is AgentEvent.StepFinished,
            is AgentEvent.FinalAnswerStarted,
            is AgentEvent.FinalAnswerDelta,
            is AgentEvent.FinalAnswerFinished,
            is AgentEvent.Result,
            is AgentEvent.Command,
            is AgentEvent.StateSync,
            is AgentEvent.ToolBlocked,
            is AgentEvent.Question,
            is AgentEvent.NoteBlock -> { /* No direct UI node */ }

            else -> { /* Unknown event types — safe to ignore */ }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun updateThinkingNode(update: (TimelineNode.Thinking) -> TimelineNode.Thinking) {
        if (thinkingNodeIndex >= 0 && thinkingNodeIndex < _nodes.size) {
            (_nodes[thinkingNodeIndex] as? TimelineNode.Thinking)?.let {
                _nodes[thinkingNodeIndex] = update(it)
            }
        }
    }

    private fun updateToolNode(toolId: String, update: (TimelineNode.ToolExecution) -> TimelineNode.ToolExecution) {
        val idx = toolNodeIndexByToolId[toolId] ?: return
        (_nodes[idx] as? TimelineNode.ToolExecution)?.let {
            _nodes[idx] = update(it)
        }
    }

    private fun updateApprovalNode(toolId: String, update: (TimelineNode.ApprovalGate) -> TimelineNode.ApprovalGate) {
        val idx = approvalNodeIndexByToolId[toolId] ?: return
        (_nodes[idx] as? TimelineNode.ApprovalGate)?.let {
            _nodes[idx] = update(it)
        }
    }

    private fun incrementSystemActivity(
        cacheHits: Int = 0,
        cacheMisses: Int = 0,
        dbReads: Int = 0,
        dbWrites: Int = 0,
        syncCount: Int = 0,
        ts: Long,
        isOngoing: Boolean = false,
    ) {
        if (systemActivityIndex < 0 || systemActivityIndex >= _nodes.size) {
            val node = TimelineNode.SystemActivity(
                id = sysActivityId,
                timestamp = ts,
                cacheHits = cacheHits,
                cacheMisses = cacheMisses,
                dbReads = dbReads,
                dbWrites = dbWrites,
                syncCount = syncCount,
                isOngoing = isOngoing,
            )
            systemActivityIndex = _nodes.size
            _nodes.add(node)
        } else {
            updateSystemActivity { existing ->
                existing.copy(
                    cacheHits = existing.cacheHits + cacheHits,
                    cacheMisses = existing.cacheMisses + cacheMisses,
                    dbReads = existing.dbReads + dbReads,
                    dbWrites = existing.dbWrites + dbWrites,
                    syncCount = existing.syncCount + syncCount,
                    isOngoing = isOngoing,
                )
            }
        }
    }

    private fun updateSystemActivity(update: (TimelineNode.SystemActivity) -> TimelineNode.SystemActivity) {
        val idx = systemActivityIndex
        if (idx >= 0 && idx < _nodes.size) {
            (_nodes[idx] as? TimelineNode.SystemActivity)?.let {
                _nodes[idx] = update(it)
            }
        }
    }

    /** Reset the aggregator for a new conversation turn. */
    fun reset() {
        _nodes.clear()
        thinkingNodeIndex = -1
        toolNodeIndexByToolId.clear()
        approvalNodeIndexByToolId.clear()
        systemActivityIndex = -1
        recoveryNodeIndex = -1
        sysActivityStart = 0L
    }

    companion object {
        /** Converts a snake_case tool name into a friendly display name. */
        fun formatToolDisplayName(name: String): String {
            return name
                .replace("_", " ")
                .split(" ")
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                .take(40)
        }
    }
}
