package com.example.smarty.ui.components.timeline

import androidx.compose.runtime.Immutable
import com.example.smarty.protocol.AgentEvent

/**
 * Stable, UI-ready representation of an agent timeline entry.
 *
 * These are derived from the canonical [AgentEvent] stream. The aggregation
 * layer converts many high-frequency raw events into these coarser, stable
 * nodes. Each node has a unique ID so Compose can track it across recompositions.
 *
 * Tier classification:
 *  - SEMANTIC  → Tier 1: always shown in main timeline
 *  - SYSTEM    → Tier 2: aggregated into a collapsed SystemActivityCard
 */
sealed class TimelineNode {
    abstract val id: String
    abstract val timestamp: Long

    // ─────────────────────────────────────────────────────────
    // TIER 1 — Semantic nodes (always visible)
    // ─────────────────────────────────────────────────────────

    /**
     * Represents agent reasoning (thinking) phase.
     */
    @Immutable
    data class Thinking(
        override val id: String,
        override val timestamp: Long,
        val text: String,
        val isStreaming: Boolean = false,
    ) : TimelineNode()



    /**
     * A single tool invocation (from ToolCallStarted → ToolCallFinished).
     * Incrementally updated as ToolCallInput / ToolCallOutput arrive.
     */
    @Immutable
    data class ToolExecution(
        override val id: String,        // toolId from the events
        override val timestamp: Long,
        val toolName: String,
        val displayName: String,
        val source: String,             // "opencode" | "mcp" | "native"
        val status: Status,
        val inputSummary: String?,
        val outputSummary: String?,
        val durationMs: Long?,
        val subagentId: String? = null,
    ) : TimelineNode() {
        enum class Status { RUNNING, COMPLETED, FAILED }
    }

    /**
     * Approval gate — maps ApprovalRequested / Granted / Denied.
     * The agent stream is paused while this is in PENDING state.
     * [requiresText] = true when the tool is "ask_user" (needs free-text input).
     */
    @Immutable
    data class ApprovalGate(
        override val id: String,
        override val timestamp: Long,
        val toolId: String,
        val toolName: String,
        val toolTitle: String,
        val toolArgs: String,
        val status: Status,
        val requiresText: Boolean = false,
    ) : TimelineNode() {
        enum class Status { PENDING, GRANTED, DENIED }
    }

    /**
     * Error surface — includes SessionError and SessionAborted.
     */
    @Immutable
    data class ErrorNode(
        override val id: String,
        override val timestamp: Long,
        val message: String,
        val isAborted: Boolean = false,
    ) : TimelineNode()

    /**
     * Recovery in-progress notice.
     */
    @Immutable
    data class RecoveryNode(
        override val id: String,
        override val timestamp: Long,
        val reason: String,
        val succeeded: Boolean?,        // null = still running
    ) : TimelineNode()

    /**
     * Subagent group - encapsulates parallel tools/reasoning running in a separate agent stream.
     */
    @Immutable
    data class SubagentGroup(
        override val id: String,
        override val timestamp: Long,
        val subagentId: String,
        val name: String,
        val nodes: List<TimelineNode>,
        val isOngoing: Boolean = true,
    ) : TimelineNode()

    // ─────────────────────────────────────────────────────────
    // TIER 2 — System / Background activity (collapsed by default)
    // ─────────────────────────────────────────────────────────

    /**
     * Aggregated system activity: cache reads/writes, DB ops, sync events.
     * Displayed as a single collapsed chip; expandable on tap.
     */
    @Immutable
    data class SystemActivity(
        override val id: String,
        override val timestamp: Long,
        val cacheHits: Int = 0,
        val cacheMisses: Int = 0,
        val dbReads: Int = 0,
        val dbWrites: Int = 0,
        val syncCount: Int = 0,
        val durationMs: Long = 0L,
        val isOngoing: Boolean = false,
    ) : TimelineNode() {
        val totalOps: Int get() = cacheHits + cacheMisses + dbReads + dbWrites + syncCount
    }
}
