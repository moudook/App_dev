package com.example.smarty.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.example.smarty.protocol.AgentCommand

/**
 * Events streamed from the Cloud Agent to the Android Client via SSE.
 *
 * These events provide real-time feedback during agent execution:
 * - Thinking: Agent reasoning/processing updates
 * - ToolCall: Tool execution status (started/completed/failed)
 * - Result: Response content (partial or final)
 * - Error: Processing errors
 *
 * SSE Format:
 * ```
 * event: thinking
 * data: {"eventId":"...","timestamp":...,"content":"..."}
 * ```
 */
@Serializable
sealed class AgentEvent {
    abstract val eventId: String
    abstract val timestamp: Long

    /**
     * Agent is reasoning/thinking.
     * Streamed as partial updates during processing.
     */
    @Serializable
    @SerialName("thinking")
    data class Thinking(
        override val eventId: String,
        override val timestamp: Long,
        val content: String
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
}
