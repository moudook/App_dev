package com.example.smarty.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One question in an ask_user multi-question schema.
 */
@Serializable
data class AskUserQuestion(
    val question: String,
    val options: List<String> = emptyList(),
    @SerialName("allow_custom") val allowCustom: Boolean = false,
    @SerialName("input_mode") val inputMode: String = "choice", // "choice" | "text"
)

@Serializable
sealed class AgentEvent {
    abstract val eventId: String
    abstract val timestamp: Long

    // ── Skeleton events (during streaming — drive spinners only)

    @Serializable
    @SerialName("thinking_active")
    data class ThinkingActive(
        override val eventId: String,
        override val timestamp: Long,
        val sessionId: String,
        val messageId: String,
    ) : AgentEvent()

    @Serializable
    @SerialName("streaming_active")
    data class StreamingActive(
        override val eventId: String,
        override val timestamp: Long,
        val sessionId: String,
        val messageId: String,
    ) : AgentEvent()

    // ── Content blocks (from clean snapshot — drive real UI)

    @Serializable
    @SerialName("reasoning_block")
    data class ReasoningBlock(
        override val eventId: String,
        override val timestamp: Long,
        val sessionId: String,
        val messageId: String,
        val partId: String,
        val content: String,
        val thinkingDurationMs: Long? = null,
    ) : AgentEvent()

    @Serializable
    @SerialName("response_block")
    data class ResponseBlock(
        override val eventId: String,
        override val timestamp: Long,
        val sessionId: String,
        val messageId: String,
        val content: String,
    ) : AgentEvent()

    // ── Streaming deltas (deprecated — kept for backward compatibility)

    @Serializable
    @SerialName("text_delta")
    data class TextDelta(
        override val eventId: String,
        override val timestamp: Long,
        val text: String,
    ) : AgentEvent()

    @Serializable
    @SerialName("reasoning_delta")
    data class ReasoningDelta(
        override val eventId: String,
        override val timestamp: Long,
        val text: String,
    ) : AgentEvent()

    // ── Tool events (real-time, clean)

    @Serializable
    @SerialName("tool_start")
    data class ToolStart(
        override val eventId: String,
        override val timestamp: Long,
        val toolId: String,
        val name: String,
        val args: String? = null,
        val isMcpTool: Boolean = false,
        val isInteractive: Boolean = false,
        val inputSummary: String = "",
    ) : AgentEvent()

    @Serializable
    @SerialName("tool_end")
    data class ToolEnd(
        override val eventId: String,
        override val timestamp: Long,
        val toolId: String,
        val result: String? = null,
        val error: String? = null,
        val isMcpTool: Boolean = false,
        val isInteractive: Boolean = false,
        val success: Boolean = true,
        val outputSummary: String = "",
    ) : AgentEvent()

    // ── Step markers

    @Serializable
    @SerialName("step_start")
    data class StepStart(
        override val eventId: String,
        override val timestamp: Long,
        val title: String,
        val stepNumber: Int = 0,
        val messageId: String = "",
    ) : AgentEvent()

    @Serializable
    @SerialName("step_end")
    data class StepEnd(
        override val eventId: String,
        override val timestamp: Long,
        val success: Boolean = true,
        val stepNumber: Int = -1,
        val cost: Double = 0.0,
        val tokensInput: Int = 0,
        val tokensOutput: Int = 0,
    ) : AgentEvent()

    // ── Sub-agent

    @Serializable
    @SerialName("sub_agent")
    data class SubAgentEvent(
        override val eventId: String,
        override val timestamp: Long,
        val sessionId: String,
        val messageId: String,
        val agent: String,
        val description: String,
        val state: String,
    ) : AgentEvent()

    // ── Interactive tool (ask_user)

    @Serializable
    @SerialName("approval_requested")
    data class ApprovalRequested(
        override val eventId: String,
        override val timestamp: Long,
        val toolId: String,
        val toolName: String,
        val question: String,
        val options: List<String> = emptyList(),
        val inputMode: String = "choice",
        val interactive: Boolean = false,
    ) : AgentEvent()

    @Serializable
    @SerialName("approval_result")
    data class ApprovalResult(
        override val eventId: String,
        override val timestamp: Long,
        val toolId: String,
        val granted: Boolean,
        val feedback: String = "",
    ) : AgentEvent()

    /**
     * Server -> Client: ask_user hands-free interactive loop (§2.2, §6.1).
     * Client renders the question UI, activates mic, and POSTs answers to
     * /webhook/ask_user_response. The server SSE turn ends after this event.
     */
    @Serializable
    @SerialName("ask_user_request")
    data class AskUserRequest(
        override val eventId: String,
        override val timestamp: Long,
        val toolId: String,
        val sessionId: String,
        val questions: List<AskUserQuestion>,
        @SerialName("tool_call_id") val toolCallId: String,
        val ttlMinutes: Int = 30,
    ) : AgentEvent()

    /**
     * Server -> Client: launch an OS intent / internal screen (§3.2).
     * Client must ACK within 15s via POST /webhook/launch_result.
     */
    @Serializable
    @SerialName("launch_ui_request")
    data class LaunchUiRequest(
        override val eventId: String,
        override val timestamp: Long,
        val commandId: String,
        val sessionId: String,
        val intent: String, // e.g. "spotify", "home", "calendar"
        val payload: String? = null, // Optional deep-link data
    ) : AgentEvent()

    /**
     * Server -> Client: trigger Android Share Sheet (§3.1).
     */
    @Serializable
    @SerialName("share_content_request")
    data class ShareContentRequest(
        override val eventId: String,
        override val timestamp: Long,
        val commandId: String,
        val sessionId: String,
        val content: String,
        val title: String? = null,
        val mimeType: String = "text/plain",
    ) : AgentEvent()

    /**
     * Server -> Client: an image generation is complete (§3.1, generate_image tool).
     */
    @Serializable
    @SerialName("image_ready")
    data class ImageReady(
        override val eventId: String,
        override val timestamp: Long,
        val imageId: String,
        val url: String,
        val prompt: String? = null,
        val messageId: String? = null,
    ) : AgentEvent()

    /**
     * Server -> Client: a background note-processing task has completed (§5.2).
     */
    @Serializable
    @SerialName("note_processed")
    data class NoteProcessed(
        override val eventId: String,
        override val timestamp: Long,
        val noteId: String,
        val success: Boolean = true,
        val errorMessage: String? = null,
    ) : AgentEvent()

    /**
     * Server -> Client: dispositional memory was updated by update_user_profile (§5.3).
     */
    @Serializable
    @SerialName("memory_updated")
    data class MemoryUpdated(
        override val eventId: String,
        override val timestamp: Long,
        val profileField: String? = null,
    ) : AgentEvent()

    // ── Terminal

    @Serializable
    @SerialName("done")
    data class Done(
        override val eventId: String,
        override val timestamp: Long,
    ) : AgentEvent()

    @Serializable
    @SerialName("error")
    data class Error(
        override val eventId: String,
        override val timestamp: Long,
        val message: String,
        val code: String? = null,
    ) : AgentEvent()

    // ── Misc

    @Serializable
    @SerialName("state_sync")
    data class StateSync(
        override val eventId: String,
        override val timestamp: Long,
        val syncType: String,
        val data: String,
    ) : AgentEvent()

    @Serializable
    @SerialName("compaction_marker")
    data class CompactionMarker(
        override val eventId: String,
        override val timestamp: Long,
        val sessionId: String,
    ) : AgentEvent()

    @Serializable
    @SerialName("device_command")
    data class DeviceCommand(
        override val eventId: String,
        override val timestamp: Long,
        val sessionId: String,
        val commandId: String,
        val action: String,
        val setting: String? = null,
        val on: Boolean? = null,
        val app: String? = null,
        val actionType: String? = null,
        val info: String? = null,
    ) : AgentEvent()


}
