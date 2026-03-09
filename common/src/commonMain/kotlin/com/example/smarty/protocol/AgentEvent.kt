package com.example.smarty.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.example.smarty.protocol.AgentCommand
import com.example.smarty.core.domain.model.Citation

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
        val thinking: String? = null
    ) : AgentEvent()

    /**
     * Agent is executing a tool.
     * Status transitions: "started" -> "completed" or "failed"
     */
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        override val eventId: String,
        override val timestamp: Long,
        @SerialName("tool_name") val toolName: String,
        @SerialName("display_name") val displayName: String,
        val status: String  // "started", "completed", "failed"
    ) : AgentEvent()

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
        @SerialName("is_final") val isFinal: Boolean = false
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
        val code: String? = null
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
        val command: AgentCommand
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
        val data: String
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
        val code: String? = null
    ) : AgentEvent()
}
