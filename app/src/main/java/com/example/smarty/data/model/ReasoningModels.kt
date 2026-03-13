package com.example.smarty.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Reasoning Step Types (matches server enum)
 */
enum class ReasoningStepType {
    ANALYSIS,
    PLANNING,
    HYPOTHESIS,
    RESEARCH,
    VERIFICATION,
    SYNTHESIS,
    REFLECTION,
    CORRECTION
}

/**
 * Reasoning Trace - Individual reasoning step
 */
@Serializable
data class ReasoningTrace(
    @SerialName("traceId") val traceId: String,
    @SerialName("sessionId") val sessionId: String,
    @SerialName("messageId") val messageId: String?,
    @SerialName("stepIndex") val stepIndex: Int,
    @SerialName("stepType") val stepType: String,
    @SerialName("title") val title: String,
    @SerialName("content") val content: String,
    @SerialName("confidenceScore") val confidenceScore: Double,
    @SerialName("importanceScore") val importanceScore: Double,
    @SerialName("isFinal") val isFinal: Boolean,
    @SerialName("wasRevised") val wasRevised: Boolean,
    @SerialName("durationMs") val durationMs: Long
)

/**
 * Reasoning Trace with Summary - For timeline display
 */
@Serializable
data class ReasoningTraceWithSummary(
    @SerialName("traceId") val traceId: String,
    @SerialName("stepIndex") val stepIndex: Int,
    @SerialName("stepType") val stepType: String,
    @SerialName("title") val title: String,
    @SerialName("content") val content: String,
    @SerialName("confidenceScore") val confidenceScore: Double,
    @SerialName("importanceScore") val importanceScore: Double,
    @SerialName("isFinal") val isFinal: Boolean,
    @SerialName("wasRevised") val wasRevised: Boolean,
    @SerialName("durationMs") val durationMs: Long,
    @SerialName("oneLiner") val oneLiner: String?,
    @SerialName("briefSummary") val briefSummary: String?
)

/**
 * Reasoning Statistics
 */
@Serializable
data class ReasoningStatistics(
    @SerialName("totalSteps") val totalSteps: Int,
    @SerialName("finalSteps") val finalSteps: Int,
    @SerialName("revisedSteps") val revisedSteps: Int
)

/**
 * Progressive Disclosure Response - 3-level disclosure for UI
 */
@Serializable
data class ProgressiveDisclosureResponse(
    @SerialName("oneLiner") val oneLiner: String,
    @SerialName("briefSteps") val briefSteps: List<String>,
    @SerialName("detailedSteps") val detailedSteps: List<String>,
    @SerialName("statistics") val statistics: ReasoningStatistics
)

/**
 * Reasoning Timeline Response
 */
@Serializable
data class ReasoningTimelineResponse(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("traces") val traces: List<ReasoningTraceWithSummary>,
    @SerialName("totalSteps") val totalSteps: Int,
    @SerialName("finalSteps") val finalSteps: Int
)

/**
 * Reasoning Traces Response
 */
@Serializable
data class ReasoningTracesResponse(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("messageId") val messageId: String?,
    @SerialName("traces") val traces: List<ReasoningTrace>,
    @SerialName("totalSteps") val totalSteps: Int = 0
)

/**
 * Log Reasoning Request
 */
@Serializable
data class LogReasoningRequest(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("messageId") val messageId: String?,
    @SerialName("userId") val userId: String,
    @SerialName("stepType") val stepType: String,
    @SerialName("title") val title: String,
    @SerialName("content") val content: String,
    @SerialName("confidenceScore") val confidenceScore: Double = 0.5,
    @SerialName("importanceScore") val importanceScore: Double = 0.5,
    @SerialName("isFinal") val isFinal: Boolean = false,
    @SerialName("tokenCount") val tokenCount: Int = 0,
    @SerialName("durationMs") val durationMs: Long = 0
)

/**
 * Log Reasoning Response
 */
@Serializable
data class LogReasoningResponse(
    @SerialName("success") val success: Boolean,
    @SerialName("traceId") val traceId: String
)

/**
 * Reasoning Summary Response
 */
@Serializable
data class ReasoningSummaryResponse(
    @SerialName("summaryId") val summaryId: String,
    @SerialName("sessionId") val sessionId: String,
    @SerialName("messageId") val messageId: String?,
    @SerialName("oneLiner") val oneLiner: String?,
    @SerialName("briefSummary") val briefSummary: String?,
    @SerialName("detailedSummary") val detailedSummary: String?,
    @SerialName("totalSteps") val totalSteps: Int,
    @SerialName("totalDurationMs") val totalDurationMs: Long,
    @SerialName("confidenceScore") val confidenceScore: Double
)
