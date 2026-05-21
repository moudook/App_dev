package com.example.smarty.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Normalizes all agent lifecycle events for the timeline UI and persistence layer.
 */
@Serializable
sealed class TimelineEvent {
    abstract val traceId: String
    abstract val timestamp: Long
    abstract val eventId: String

    // === Session Lifecycle ===
    @Serializable
    data class SessionStarted(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val sessionId: String
    ) : TimelineEvent()

    @Serializable
    data class ModelResolved(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val requested: String,
        val resolved: String,
        val fallbackReason: String? = null
    ) : TimelineEvent()

    // === Reasoning & Steps ===
    @Serializable
    data class ReasoningStarted(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String
    ) : TimelineEvent()

    @Serializable
    data class ReasoningDelta(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val text: String
    ) : TimelineEvent()

    @Serializable
    data class ReasoningFinished(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val fullText: String
    ) : TimelineEvent()

    @Serializable
    data class StepStarted(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val stepType: String, // e.g. "thinking", "tool_call", "web_search"
        val title: String
    ) : TimelineEvent()

    @Serializable
    data class StepFinished(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val durationMs: Long
    ) : TimelineEvent()

    // === Tools ===
    @Serializable
    data class ToolCallStarted(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val toolId: String,
        val name: String,
        val source: String // "opencode", "mcp", "custom"
    ) : TimelineEvent()

    @Serializable
    data class ToolCallInput(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val toolId: String,
        val inputDelta: String
    ) : TimelineEvent()

    @Serializable
    data class ToolCallOutput(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val toolId: String,
        val output: String
    ) : TimelineEvent()

    @Serializable
    data class ToolCallFinished(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val toolId: String,
        val durationMs: Long,
        val status: String // "completed", "failed", "aborted"
    ) : TimelineEvent()

    // === Approvals ===
    @Serializable
    data class ApprovalRequested(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val toolId: String,
        val toolName: String,
        val parameters: String
    ) : TimelineEvent()

    @Serializable
    data class ApprovalGranted(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val toolId: String
    ) : TimelineEvent()

    @Serializable
    data class ApprovalDenied(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val toolId: String,
        val reason: String?
    ) : TimelineEvent()

    // === Final Answer ===
    @Serializable
    data class FinalAnswerStarted(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String
    ) : TimelineEvent()

    @Serializable
    data class FinalAnswerDelta(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val text: String
    ) : TimelineEvent()

    @Serializable
    data class FinalAnswerFinished(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val fullText: String
    ) : TimelineEvent()

    // === End Lifecycle & Errors ===
    @Serializable
    data class SessionCompleted(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String
    ) : TimelineEvent()

    @Serializable
    data class SessionError(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val errorType: String,
        val message: String
    ) : TimelineEvent()

    @Serializable
    data class SessionAborted(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val reason: String
    ) : TimelineEvent()

    // === Recovery & System ===
    @Serializable
    data class RecoveryStarted(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val reason: String
    ) : TimelineEvent()

    @Serializable
    data class RecoveryFinished(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val success: Boolean
    ) : TimelineEvent()

    @Serializable
    data class CacheHit(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String
    ) : TimelineEvent()

    @Serializable
    data class CacheMiss(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String
    ) : TimelineEvent()

    @Serializable
    data class DbWrite(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val table: String
    ) : TimelineEvent()

    @Serializable
    data class DbRead(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String,
        val table: String
    ) : TimelineEvent()

    @Serializable
    data class SyncStarted(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String
    ) : TimelineEvent()

    @Serializable
    data class SyncFinished(
        override val traceId: String,
        override val timestamp: Long,
        override val eventId: String
    ) : TimelineEvent()
}
