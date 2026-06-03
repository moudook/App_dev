package com.example.smarty.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class AgentEvent {
    abstract val eventId: String
    abstract val timestamp: Long

    /** Streaming text delta for the final answer. */
    @Serializable
    @SerialName("text_delta")
    data class TextDelta(
        override val eventId: String,
        override val timestamp: Long,
        val text: String,
    ) : AgentEvent()

    /** Streaming reasoning delta from the model. */
    @Serializable
    @SerialName("reasoning_delta")
    data class ReasoningDelta(
        override val eventId: String,
        override val timestamp: Long,
        val text: String,
    ) : AgentEvent()

    /** Tool execution started. */
    @Serializable
    @SerialName("tool_start")
    data class ToolStart(
        override val eventId: String,
        override val timestamp: Long,
        val toolId: String,
        val name: String,
        val args: String? = null,
    ) : AgentEvent()

    /** Tool execution completed. */
    @Serializable
    @SerialName("tool_end")
    data class ToolEnd(
        override val eventId: String,
        override val timestamp: Long,
        val toolId: String,
        val result: String? = null,
        val error: String? = null,
    ) : AgentEvent()

    /** Agent step started (e.g. "Searching web", "Analyzing results"). */
    @Serializable
    @SerialName("step_start")
    data class StepStart(
        override val eventId: String,
        override val timestamp: Long,
        val title: String,
        val stepNumber: Int = 0,
    ) : AgentEvent()

    /** Agent step finished. */
    @Serializable
    @SerialName("step_end")
    data class StepEnd(
        override val eventId: String,
        override val timestamp: Long,
        val success: Boolean = true,
        val stepNumber: Int = -1,
    ) : AgentEvent()

    /** Stream complete — terminal event. The response is done. */
    @Serializable
    @SerialName("done")
    data class Done(
        override val eventId: String,
        override val timestamp: Long,
    ) : AgentEvent()

    /** Error during processing. */
    @Serializable
    @SerialName("error")
    data class Error(
        override val eventId: String,
        override val timestamp: Long,
        val message: String,
        val code: String? = null,
    ) : AgentEvent()

    /** Tool requires user approval (permission gate or ask_user). */
    @Serializable
    @SerialName("approval_requested")
    data class ApprovalRequested(
        override val eventId: String,
        override val timestamp: Long,
        val toolId: String,
        val toolName: String,
        val question: String,
        val options: List<String> = emptyList(),
        val interactive: Boolean = false,
    ) : AgentEvent()

    /** User responded to an approval request. */
    @Serializable
    @SerialName("approval_result")
    data class ApprovalResult(
        override val eventId: String,
        override val timestamp: Long,
        val toolId: String,
        val granted: Boolean,
    ) : AgentEvent()

    /** Server-side state change notification for local caching. */
    @Serializable
    @SerialName("state_sync")
    data class StateSync(
        override val eventId: String,
        override val timestamp: Long,
        val syncType: String,
        val data: String,
    ) : AgentEvent()
}
