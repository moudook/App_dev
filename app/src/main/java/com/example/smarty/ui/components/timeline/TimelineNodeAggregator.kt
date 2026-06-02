package com.example.smarty.ui.components.timeline

import com.example.smarty.protocol.AgentEvent

class TimelineNodeAggregator {
    private val _nodes = mutableListOf<TimelineNode>()
    val nodes: List<TimelineNode> get() = _nodes

    private val toolNodeIndexByToolId = mutableMapOf<String, Int>()
    private val approvalNodeIndexByToolId = mutableMapOf<String, Int>()

    private var lastProcessedIndex: Int = 0
    private var lastApprovalTimestamp: Long = 0L

    fun processAll(events: List<AgentEvent>): List<TimelineNode> {
        val start = lastProcessedIndex
        lastProcessedIndex = events.size
        for (i in start until events.size) {
            process(events[i])
        }
        return _nodes.toList()
    }

    fun process(event: AgentEvent) {
        when (event) {
            is AgentEvent.ToolStart -> {
                val node = TimelineNode.ToolExecution(
                    id = event.toolId,
                    timestamp = event.timestamp,
                    toolName = event.name,
                    displayName = event.name.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    source = "mcp",
                    status = TimelineNode.ToolExecution.Status.RUNNING,
                    inputSummary = event.args ?: "",
                    outputSummary = null,
                    durationMs = null,
                )
                toolNodeIndexByToolId[event.toolId] = _nodes.size
                _nodes.add(node)
            }

            is AgentEvent.ToolEnd -> {
                val idx = toolNodeIndexByToolId[event.toolId]
                if (idx != null && idx < _nodes.size) {
                    val old = _nodes[idx] as? TimelineNode.ToolExecution
                    if (old != null) {
                        _nodes[idx] = old.copy(
                            status = if (event.error != null) TimelineNode.ToolExecution.Status.FAILED
                                     else TimelineNode.ToolExecution.Status.COMPLETED,
                            outputSummary = event.result?.take(400) ?: event.error?.take(200) ?: "",
                        )
                    }
                }
            }

            is AgentEvent.ApprovalRequested -> {
                val now = System.currentTimeMillis()
                if (now - lastApprovalTimestamp > 5000 ||
                    _nodes.none { it is TimelineNode.ApprovalGate }
                ) {
                    val node = TimelineNode.ApprovalGate(
                        id = "approval_${event.toolId}",
                        timestamp = event.timestamp,
                        toolId = event.toolId,
                        toolName = event.toolName,
                        toolTitle = event.toolName.replace('_', ' ').replaceFirstChar { it.uppercase() },
                        toolArgs = event.question,
                        status = TimelineNode.ApprovalGate.Status.PENDING,
                        requiresText = event.interactive || event.toolName.equals("ask_user", ignoreCase = true),
                    )
                    approvalNodeIndexByToolId[event.toolId] = _nodes.size
                    _nodes.add(node)
                    lastApprovalTimestamp = now
                }
            }

            is AgentEvent.ApprovalResult -> {
                val idx = approvalNodeIndexByToolId[event.toolId]
                if (idx != null && idx < _nodes.size) {
                    val old = _nodes[idx] as? TimelineNode.ApprovalGate
                    if (old != null) {
                        _nodes[idx] = old.copy(
                            status = if (event.granted) TimelineNode.ApprovalGate.Status.GRANTED
                                     else TimelineNode.ApprovalGate.Status.DENIED,
                        )
                    }
                }
            }

            is AgentEvent.Error -> {
                _nodes.add(TimelineNode.ErrorNode(
                    id = "error_${event.eventId}",
                    timestamp = event.timestamp,
                    message = event.message,
                ))
            }

            is AgentEvent.StepStart -> {
                val node = TimelineNode.ToolExecution(
                    id = event.eventId,
                    timestamp = event.timestamp,
                    toolName = "step",
                    displayName = event.title,
                    source = "mcp",
                    status = TimelineNode.ToolExecution.Status.RUNNING,
                    inputSummary = null,
                    outputSummary = null,
                    durationMs = null,
                )
                toolNodeIndexByToolId[event.eventId] = _nodes.size
                _nodes.add(node)
            }

            is AgentEvent.StepEnd -> {
                val idx = toolNodeIndexByToolId[event.eventId]
                if (idx != null && idx < _nodes.size) {
                    val old = _nodes[idx] as? TimelineNode.ToolExecution
                    if (old != null) {
                        _nodes[idx] = old.copy(
                            status = if (event.success) TimelineNode.ToolExecution.Status.COMPLETED
                                     else TimelineNode.ToolExecution.Status.FAILED,
                        )
                    }
                }
            }

            else -> { /* TextDelta, ReasoningDelta, Done, StateSync — no timeline node */ }
        }
    }

    fun reset() {
        _nodes.clear()
        toolNodeIndexByToolId.clear()
        approvalNodeIndexByToolId.clear()
        lastProcessedIndex = 0
        lastApprovalTimestamp = 0L
    }
}
