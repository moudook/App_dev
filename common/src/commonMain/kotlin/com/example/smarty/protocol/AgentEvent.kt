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
    ) : AgentEvent()
}
