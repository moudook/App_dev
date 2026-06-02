package com.example.smarty.protocol

import com.example.smarty.core.domain.model.Citation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Events streamed from the Cloud Agent to the Android Client via SSE.
 *
 * These events provide real-time feedback during agent execution:
 * - Processing: Agent activity updates (renamed from Thinking)
 * - ToolCall: Tool execution status (started/completed/failed)
 * - Result: Response content (partial or final)
 * - Error: Processing errors
 *
 * SSE Format:
 * ```
 * event: processing
 * data: {"eventId":"...","timestamp":...,"content":"..."}
 * ```
 */
@Serializable
sealed class AgentEvent {
    abstract val eventId: String
    abstract val timestamp: Long
    open val subagentId: String? = null

    /**
     * Agent is processing/working.
     * Streamed as partial updates during processing.
     */
    @Serializable
    @SerialName("processing")
    data class Processing(
        override val eventId: String,
        override val timestamp: Long,
        val content: String,
        val thinking: String? = null,
        @SerialName("subagent_id") override val subagentId: String? = null,
    ) : AgentEvent()

    /**
     * Agent is executing a tool.
     * Status transitions: "started" -> "completed" or "failed"
     *
     * inputSummary  - human-readable summary of the tool input (e.g. search query).
     * outputSummary - abbreviated result (e.g. first 400 chars of web search result).
     * searchQueries - for parallel web searches: individual (query, result) pairs.
     */
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        override val eventId: String,
        override val timestamp: Long,
        @SerialName("tool_name") val toolName: String,
        @SerialName("display_name") val displayName: String,
        val status: String, // "started", "completed", "failed"
        @SerialName("input_summary") val inputSummary: String? = null,
        @SerialName("output_summary") val outputSummary: String? = null,
        @SerialName("search_queries") val searchQueries: List<SearchQueryResult> = emptyList(),
        @SerialName("subagent_id") override val subagentId: String? = null,
    ) : AgentEvent()

    /** Individual web-search query + its result (used inside a ToolCall event). */
    @Serializable
    data class SearchQueryResult(
        val query: String,
        val result: String? = null, // null when status=="started"
    )

    /**
     * Agent produced a result chunk.
     * Multiple Result events may be emitted; isFinal=true marks the last one.
     */
    @Serializable
    @SerialName("result")
    data class Result(
        override val eventId: String,
        override val timestamp: Long,
        val content: String,
        val thinking: String? = null,
        val citations: List<Citation> = emptyList(),
        @SerialName("is_final") val isFinal: Boolean = false,
        val confidence: String? = null,
        @SerialName("source_type") val sourceType: String? = null,
    ) : AgentEvent()

    /**
     * Error occurred during agent processing.
     */
    @Serializable
    @SerialName("error")
    data class Error(
        override val eventId: String,
        override val timestamp: Long,
        val message: String,
        val code: String? = null,
    ) : AgentEvent()

    /**
     * Agent requests execution of a command on the client device.
     * Wraps a polymorphic AgentCommand (e.g., AddNote, SetTimer).
     */
    @Serializable
    @SerialName("command")
    data class Command(
        override val eventId: String,
        override val timestamp: Long,
        val command: AgentCommand,
    ) : AgentEvent()

    /**
     * Server-side state change notification.
     * Sent after a tool executes on the server so the client can cache data locally.
     * syncType examples: "note_created", "note_updated", "timer_set", "event_scheduled"
     * data: JSON payload with full entity data for local caching.
     */
    @Serializable
    @SerialName("state_sync")
    data class StateSync(
        override val eventId: String,
        override val timestamp: Long,
        @SerialName("sync_type") val syncType: String,
        val data: String,
    ) : AgentEvent()

    /**
     * Tool execution was blocked (e.g., same query repeated too many times).
     * Unlike Error, this allows the agent to continue with a different approach.
     * The AI receives this message and can decide next steps.
     */
    @Serializable
    @SerialName("tool_blocked")
    data class ToolBlocked(
        override val eventId: String,
        override val timestamp: Long,
        @SerialName("tool_name") val toolName: String,
        val reason: String,
        val code: String? = null,
    ) : AgentEvent()

    /**
     * Agent asked a structured question (multiple choice or free text).
     * Client renders as interactive question cards.
     */
    @Serializable
    @SerialName("question")
    data class Question(
        override val eventId: String,
        override val timestamp: Long,
        val question: String,
        val options: List<String> = emptyList(),
        @SerialName("allow_custom") val allowCustom: Boolean = false,
    ) : AgentEvent()

    /**
     * Agent embeds a clickable note card within the response.
     * Client renders as an interactive note block; tapping opens note details.
     */
    @Serializable
    @SerialName("note_block")
    data class NoteBlock(
        override val eventId: String,
        override val timestamp: Long,
        @SerialName("note_id") val noteId: String,
        val title: String,
        val snippet: String,
        val category: String? = null,
    ) : AgentEvent()

    /**
     * Agentic step event — shows each discrete step the agent takes.
     * Enables step-by-step visualization in the UI like:
     *   [Analyzed request] → [Searched web] → [Read note] → [Final answer]
     *
     * stepType values:
     *   "thinking"     - Agent is reasoning/thinking (streaming thinking text)
     *   "tool_call"    - Agent invoked a specific tool
     *   "tool_result"  - Result of a tool call
     *   "opencode_tool" - Native OpenCode CLI tool (e.g. web_search from daemon)
     *   "checkpoint"   - Logical milestone (e.g. "Analyzed request", "Synthesizing...")
     *
     * stepStatus: "started" | "streaming" | "completed" | "failed"
     * stepIndex: monotonically increasing per conversation turn (0,1,2,...)
     */
    @Serializable
    @SerialName("agent_step")
    data class AgentStep(
        override val eventId: String,
        override val timestamp: Long,
        @SerialName("step_index") val stepIndex: Int,
        @SerialName("step_type") val stepType: String,
        @SerialName("step_title") val stepTitle: String,
        @SerialName("step_content") val stepContent: String = "",
        @SerialName("step_status") val stepStatus: String = "started",
        @SerialName("tool_name") val toolName: String? = null,
        @SerialName("duration_ms") val durationMs: Long? = null,
        @SerialName("subagent_id") override val subagentId: String? = null,
    ) : AgentEvent()

    // =========================================================================
    // NEW CANONICAL GRANULAR EVENT MODEL
    // =========================================================================

    // Session & Lifecycle
    @Serializable
    @SerialName("session_started")
    data class SessionStarted(
        override val eventId: String,
        override val timestamp: Long,
        val sessionId: String,
    ) : AgentEvent()

    @Serializable
    @SerialName("session_completed")
    data class SessionCompleted(
        override val eventId: String,
        override val timestamp: Long,
    ) : AgentEvent()

    @Serializable
    @SerialName("session_error")
    data class SessionError(
        override val eventId: String,
        override val timestamp: Long,
        val message: String,
    ) : AgentEvent()

    @Serializable
    @SerialName("session_aborted")
    data class SessionAborted(
        override val eventId: String,
        override val timestamp: Long,
        val reason: String,
    ) : AgentEvent()

    // Model Resolution
    @Serializable
    @SerialName("model_resolved")
    data class ModelResolved(
        override val eventId: String,
        override val timestamp: Long,
        val requested: String,
        val resolved: String,
        val fallback: Boolean,
    ) : AgentEvent()

    // Reasoning
    @Serializable
    @SerialName("reasoning_started")
    data class ReasoningStarted(
        override val eventId: String,
        override val timestamp: Long,
    ) : AgentEvent()

    @Serializable
    @SerialName("reasoning_delta")
    data class ReasoningDelta(
        override val eventId: String,
        override val timestamp: Long,
        val text: String,
    ) : AgentEvent()

    @Serializable
    @SerialName("reasoning_finished")
    data class ReasoningFinished(
        override val eventId: String,
        override val timestamp: Long,
    ) : AgentEvent()

    // Steps
    @Serializable
    @SerialName("step_started")
    data class StepStarted(
        override val eventId: String,
        override val timestamp: Long,
        val title: String,
    ) : AgentEvent()

    @Serializable
    @SerialName("step_finished")
    data class StepFinished(
        override val eventId: String,
        override val timestamp: Long,
        val success: Boolean,
    ) : AgentEvent()

    // Tool Call Lifecycle
    @Serializable
    @SerialName("tool_call_started")
    data class ToolCallStarted(
        override val eventId: String,
        override val timestamp: Long,
        val toolId: String,
        val name: String,
        val source: String,
        @SerialName("subagent_id") override val subagentId: String? = null,
    ) : AgentEvent()

    @Serializable
    @SerialName("tool_call_input")
    data class ToolCallInput(
        override val eventId: String,
        override val timestamp: Long,
        val toolId: String,
        val inputDelta: String,
        @SerialName("subagent_id") override val subagentId: String? = null,
    ) : AgentEvent()

    @Serializable
    @SerialName("tool_call_output")
    data class ToolCallOutput(
        override val eventId: String,
        override val timestamp: Long,
        val toolId: String,
        val output: String,
        @SerialName("subagent_id") override val subagentId: String? = null,
    ) : AgentEvent()

    @Serializable
    @SerialName("tool_call_finished")
    data class ToolCallFinished(
        override val eventId: String,
        override val timestamp: Long,
        val toolId: String,
        val durationMs: Long,
        @SerialName("subagent_id") override val subagentId: String? = null,
    ) : AgentEvent()

    @Serializable
    @SerialName("approval_requested")
    data class ApprovalRequested(
        override val eventId: String,
        override val timestamp: Long,
        @SerialName("tool_id") val toolId: String,
        @SerialName("tool_name") val toolName: String,
        @SerialName("tool_title") val toolTitle: String,
        @SerialName("tool_args") val toolArgs: String,
        /**
         * OpenCode session ID — only set for plugin-origin approvals
         * (kind=`user.input.required` from `/ws/timeline`). The Ktor MCP
         * server doesn't emit this field, so it stays null for Ktor-origin
         * approvals, which is fine because they have a different response
         * path (server-side [ApprovalRegistry] keyed by `toolId`, not by
         * `(sessionID, callID)` on disk).
         */
        @SerialName("session_id") val sessionId: String? = null,
        /**
         * `true` when the approval originates from a `user.input.required`
         * event (i.e. an interactive tool like `ask_user` that needs the
         * user's *content* answer, not just an approve/deny decision).
         * `false` for `permission.asked` events (the agent is asking
         * permission to run a tool — the user can approve or deny).
         *
         * Interactive events ALWAYS show a prompt regardless of the
         * permission policy, because the whole point of `ask_user` is to
         * gather user input. Non-interactive events are subject to the
         * [com.example.smarty.agent.permissions.ToolPermissionPolicy] —
         * a defensive short-circuit on the app and Ktor server.
         */
        @SerialName("is_interactive") val isInteractive: Boolean = false,
    ) : AgentEvent()

    @Serializable
    @SerialName("approval_granted")
    data class ApprovalGranted(
        override val eventId: String,
        override val timestamp: Long,
        @SerialName("tool_id") val toolId: String,
    ) : AgentEvent()

    @Serializable
    @SerialName("approval_denied")
    data class ApprovalDenied(
        override val eventId: String,
        override val timestamp: Long,
        @SerialName("tool_id") val toolId: String,
    ) : AgentEvent()

    // Post-approval: client tells server to resume execution
    @Serializable
    @SerialName("approval_response")
    data class ApprovalResponse(
        override val eventId: String,
        override val timestamp: Long,
        @SerialName("tool_id") val toolId: String,
        val approved: Boolean,
        val feedback: String? = null,
    ) : AgentEvent()

    // Tool execution completion
    @Serializable
    @SerialName("tool_call_completed")
    data class ToolCallCompleted(
        override val eventId: String,
        override val timestamp: Long,
        val toolId: String,
        val result: String,
        val durationMs: Long,
    ) : AgentEvent()

    // Final Answer
    @Serializable
    @SerialName("final_answer_started")
    data class FinalAnswerStarted(
        override val eventId: String,
        override val timestamp: Long,
    ) : AgentEvent()

    @Serializable
    @SerialName("final_answer_delta")
    data class FinalAnswerDelta(
        override val eventId: String,
        override val timestamp: Long,
        val text: String,
    ) : AgentEvent()

    @Serializable
    @SerialName("final_answer_finished")
    data class FinalAnswerFinished(
        override val eventId: String,
        override val timestamp: Long,
    ) : AgentEvent()

    // Reliability & Cache
    @Serializable
    @SerialName("recovery_started")
    data class RecoveryStarted(
        override val eventId: String,
        override val timestamp: Long,
        val reason: String,
    ) : AgentEvent()

    @Serializable
    @SerialName("recovery_finished")
    data class RecoveryFinished(
        override val eventId: String,
        override val timestamp: Long,
        val success: Boolean,
    ) : AgentEvent()

    @Serializable
    @SerialName("cache_hit")
    data class CacheHit(
        override val eventId: String,
        override val timestamp: Long,
    ) : AgentEvent()

    @Serializable
    @SerialName("cache_miss")
    data class CacheMiss(
        override val eventId: String,
        override val timestamp: Long,
    ) : AgentEvent()

    // Data Sync
    @Serializable
    @SerialName("db_write")
    data class DbWrite(
        override val eventId: String,
        override val timestamp: Long,
        val table: String,
    ) : AgentEvent()

    @Serializable
    @SerialName("db_read")
    data class DbRead(
        override val eventId: String,
        override val timestamp: Long,
        val table: String,
    ) : AgentEvent()

    @Serializable
    @SerialName("sync_started")
    data class SyncStarted(
        override val eventId: String,
        override val timestamp: Long,
    ) : AgentEvent()

    @Serializable
    @SerialName("sync_finished")
    data class SyncFinished(
        override val eventId: String,
        override val timestamp: Long,
    ) : AgentEvent()

    // Raw OpenCode CLI Output
    @Serializable
    @SerialName("opencode_raw")
    data class OpencodeRawEvent(
        override val eventId: String,
        override val timestamp: Long,
        val data: String,
        @SerialName("event_name") val eventName: String? = null,
        @SerialName("subagent_id") override val subagentId: String? = null,
    ) : AgentEvent()
}
