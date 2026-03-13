package com.example.smarty.server.routes

import com.example.smarty.server.data.ReasoningStepType
import com.example.smarty.server.data.ReasoningTrace
import com.example.smarty.server.data.ReasoningSummary
import com.example.smarty.server.data.ReasoningTraceWithSummary
import com.example.smarty.server.services.ReasoningService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/**
 * Reasoning Routes
 * API endpoints for reasoning traces and thinking logs
 */
fun Route.configureReasoningRoutes(reasoningService: ReasoningService) {
    route("/api/reasoning") {
        
        // Get reasoning timeline for a session
        get("/session/{sessionId}") {
            val sessionId = call.parameters["sessionId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, "Session ID required")
                return@get
            }
            
            try {
                val timeline = reasoningService.getReasoningTimeline(sessionId)
                call.respond(ReasoningTimelineResponse(
                    sessionId = sessionId,
                    traces = timeline.map { it.toResponse() },
                    totalSteps = timeline.size,
                    finalSteps = timeline.count { it.isFinal }
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error retrieving reasoning timeline: ${e.message}")
            }
        }
        
        // Get reasoning traces (with optional message filter)
        get("/session/{sessionId}/traces") {
            val sessionId = call.parameters["sessionId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, "Session ID required")
                return@get
            }
            val messageId = call.request.queryParameters["messageId"]
            
            try {
                val traces = reasoningService.getReasoningTraces(sessionId, messageId)
                call.respond(ReasoningTracesResponse(
                    sessionId = sessionId,
                    messageId = messageId,
                    traces = traces.map { it.toResponse() }
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error retrieving reasoning traces: ${e.message}")
            }
        }
        
        // Get reasoning summary
        get("/session/{sessionId}/summary") {
            val sessionId = call.parameters["sessionId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, "Session ID required")
                return@get
            }
            val messageId = call.request.queryParameters["messageId"]
            
            try {
                val summary = reasoningService.getReasoningSummary(sessionId, messageId)
                if (summary != null) {
                    call.respond(summary.toResponse())
                } else {
                    call.respond(HttpStatusCode.NotFound, "No summary found for session")
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error retrieving reasoning summary: ${e.message}")
            }
        }
        
        // Log a reasoning step
        post("/log") {
            val request = call.receive<LogReasoningRequest>()
            
            try {
                val traceId = reasoningService.logReasoningStep(
                    sessionId = request.sessionId,
                    messageId = request.messageId,
                    userId = request.userId,
                    stepType = ReasoningStepType.valueOf(request.stepType),
                    title = request.title,
                    content = request.content,
                    confidenceScore = request.confidenceScore,
                    importanceScore = request.importanceScore,
                    isFinal = request.isFinal,
                    tokenCount = request.tokenCount,
                    durationMs = request.durationMs
                )
                
                call.respond(LogReasoningResponse(
                    success = true,
                    traceId = traceId
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error logging reasoning step: ${e.message}")
            }
        }
        
        // Log multiple reasoning steps (batch)
        post("/log/batch") {
            val request = call.receive<LogReasoningBatchRequest>()
            
            try {
                val count = reasoningService.logReasoningSteps(
                    request.traces.map { it.toDomain() }
                )
                
                call.respond(LogReasoningBatchResponse(
                    success = true,
                    loggedCount = count
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error logging reasoning steps: ${e.message}")
            }
        }
        
        // Create reasoning summary
        post("/summary") {
            val request = call.receive<CreateSummaryRequest>()
            
            try {
                val summaryId = reasoningService.createReasoningSummary(
                    sessionId = request.sessionId,
                    messageId = request.messageId,
                    userId = request.userId,
                    oneLiner = request.oneLiner,
                    briefSummary = request.briefSummary,
                    detailedSummary = request.detailedSummary,
                    totalSteps = request.totalSteps,
                    totalDurationMs = request.totalDurationMs,
                    totalTokens = request.totalTokens,
                    confidenceScore = request.confidenceScore,
                    complexityScore = request.complexityScore,
                    reasoningType = request.reasoningType,
                    tags = request.tags
                )
                
                call.respond(CreateSummaryResponse(
                    success = true,
                    summaryId = summaryId
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error creating reasoning summary: ${e.message}")
            }
        }
        
        // Mark reasoning as revised
        post("/revise") {
            val request = call.receive<ReviseReasoningRequest>()
            
            try {
                reasoningService.markReasoningRevised(
                    traceId = request.traceId,
                    revisedByTraceId = request.revisedByTraceId
                )
                
                call.respond(ReviseReasoningResponse(
                    success = true,
                    message = "Reasoning trace marked as revised"
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error marking reasoning as revised: ${e.message}")
            }
        }
        
        // Get progressive disclosure levels
        get("/session/{sessionId}/disclosure") {
            val sessionId = call.parameters["sessionId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, "Session ID required")
                return@get
            }
            
            try {
                val traces = reasoningService.getReasoningTraces(sessionId)
                val disclosure = reasoningService.generateProgressiveDisclosure(traces)
                
                call.respond(ProgressiveDisclosureResponse(
                    oneLiner = disclosure.oneLiner,
                    briefSteps = disclosure.briefSteps,
                    detailedSteps = disclosure.detailedSteps,
                    statistics = ReasoningStatistics(
                        totalSteps = disclosure.totalSteps,
                        finalSteps = disclosure.finalSteps,
                        revisedSteps = disclosure.revisedSteps
                    )
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error generating disclosure levels: ${e.message}")
            }
        }
    }
}

// ==================== REQUEST/RESPONSE DATA CLASSES ====================

@Serializable
data class LogReasoningRequest(
    val sessionId: String,
    val messageId: String?,
    val userId: String,
    val stepType: String,
    val title: String,
    val content: String,
    val confidenceScore: Double = 0.5,
    val importanceScore: Double = 0.5,
    val isFinal: Boolean = false,
    val tokenCount: Int = 0,
    val durationMs: Long = 0
)

@Serializable
data class LogReasoningResponse(
    val success: Boolean,
    val traceId: String
)

@Serializable
data class LogReasoningBatchRequest(
    val traces: List<ReasoningTraceRequest>
)

@Serializable
data class LogReasoningBatchResponse(
    val success: Boolean,
    val loggedCount: Int
)

@Serializable
data class ReasoningTraceRequest(
    val sessionId: String,
    val messageId: String?,
    val userId: String,
    val stepIndex: Int,
    val stepType: String,
    val title: String,
    val content: String,
    val confidenceScore: Double = 0.5,
    val importanceScore: Double = 0.5,
    val isFinal: Boolean = false,
    val wasRevised: Boolean = false,
    val tokenCount: Int = 0,
    val durationMs: Long = 0
)

@Serializable
data class ReasoningTraceResponse(
    val traceId: String,
    val sessionId: String,
    val messageId: String?,
    val stepIndex: Int,
    val stepType: String,
    val title: String,
    val content: String,
    val confidenceScore: Double,
    val importanceScore: Double,
    val isFinal: Boolean,
    val wasRevised: Boolean,
    val durationMs: Long,
    val createdAt: String
)

@Serializable
data class ReasoningTracesResponse(
    val sessionId: String,
    val messageId: String?,
    val traces: List<ReasoningTraceResponse>,
    val totalSteps: Int = 0
)

@Serializable
data class ReasoningTimelineResponse(
    val sessionId: String,
    val traces: List<ReasoningTraceWithSummaryResponse>,
    val totalSteps: Int,
    val finalSteps: Int
)

@Serializable
data class ReasoningTraceWithSummaryResponse(
    val traceId: String,
    val stepIndex: Int,
    val stepType: String,
    val title: String,
    val content: String,
    val confidenceScore: Double,
    val importanceScore: Double,
    val isFinal: Boolean,
    val wasRevised: Boolean,
    val durationMs: Long,
    val oneLiner: String?,
    val briefSummary: String?
)

@Serializable
data class ReasoningSummaryResponse(
    val summaryId: String,
    val sessionId: String,
    val messageId: String?,
    val oneLiner: String,
    val briefSummary: String,
    val detailedSummary: String,
    val totalSteps: Int,
    val totalDurationMs: Long,
    val totalTokens: Int,
    val confidenceScore: Double,
    val complexityScore: Double,
    val reasoningType: String,
    val tags: List<String>
)

@Serializable
data class CreateSummaryRequest(
    val sessionId: String,
    val messageId: String?,
    val userId: String,
    val oneLiner: String,
    val briefSummary: String,
    val detailedSummary: String,
    val totalSteps: Int,
    val totalDurationMs: Long,
    val totalTokens: Int,
    val confidenceScore: Double,
    val complexityScore: Double,
    val reasoningType: String,
    val tags: List<String> = emptyList()
)

@Serializable
data class CreateSummaryResponse(
    val success: Boolean,
    val summaryId: String
)

@Serializable
data class ReviseReasoningRequest(
    val traceId: String,
    val revisedByTraceId: String
)

@Serializable
data class ReviseReasoningResponse(
    val success: Boolean,
    val message: String
)

@Serializable
data class ProgressiveDisclosureResponse(
    val oneLiner: String,
    val briefSteps: List<String>,
    val detailedSteps: List<String>,
    val statistics: ReasoningStatistics
)

@Serializable
data class ReasoningStatistics(
    val totalSteps: Int,
    val finalSteps: Int,
    val revisedSteps: Int
)

// ==================== CONVERTER EXTENSIONS ====================

fun ReasoningTraceWithSummary.toResponse(): ReasoningTraceWithSummaryResponse {
    return ReasoningTraceWithSummaryResponse(
        traceId = traceId.toString(),
        stepIndex = stepIndex,
        stepType = stepType.name,
        title = title,
        content = content,
        confidenceScore = confidenceScore,
        importanceScore = importanceScore,
        isFinal = isFinal,
        wasRevised = wasRevised,
        durationMs = durationMs,
        oneLiner = oneLiner,
        briefSummary = briefSummary
    )
}

fun com.example.smarty.server.data.ReasoningTrace.toResponse(): ReasoningTraceResponse {
    return ReasoningTraceResponse(
        traceId = (traceId ?: java.util.UUID.randomUUID()).toString(),
        sessionId = sessionId,
        messageId = messageId,
        stepIndex = stepIndex,
        stepType = stepType.name,
        title = title,
        content = content,
        confidenceScore = confidenceScore,
        importanceScore = importanceScore,
        isFinal = isFinal,
        wasRevised = wasRevised,
        durationMs = durationMs,
        createdAt = "" // Would need timestamp from DB
    )
}

fun ReasoningSummaryResponse.toDomain(): com.example.smarty.server.data.ReasoningSummary {
    return com.example.smarty.server.data.ReasoningSummary(
        sessionId = sessionId,
        messageId = messageId,
        userId = "", // Would need to be passed separately
        oneLiner = oneLiner,
        briefSummary = briefSummary,
        detailedSummary = detailedSummary,
        totalSteps = totalSteps,
        totalDurationMs = totalDurationMs,
        totalTokens = totalTokens,
        confidenceScore = confidenceScore,
        complexityScore = complexityScore,
        reasoningType = reasoningType,
        tags = tags
    )
}

fun ReasoningTraceRequest.toDomain(): com.example.smarty.server.data.ReasoningTrace {
    return com.example.smarty.server.data.ReasoningTrace(
        sessionId = sessionId,
        messageId = messageId,
        userId = userId,
        stepIndex = stepIndex,
        stepType = ReasoningStepType.valueOf(stepType),
        title = title,
        content = content,
        confidenceScore = confidenceScore,
        importanceScore = importanceScore,
        isFinal = isFinal,
        wasRevised = wasRevised,
        tokenCount = tokenCount,
        durationMs = durationMs
    )
}

fun com.example.smarty.server.data.ReasoningSummary.toResponse(): ReasoningSummaryResponse {
    return ReasoningSummaryResponse(
        summaryId = (summaryId ?: java.util.UUID.randomUUID()).toString(),
        sessionId = sessionId,
        messageId = messageId,
        oneLiner = oneLiner,
        briefSummary = briefSummary,
        detailedSummary = detailedSummary,
        totalSteps = totalSteps,
        totalDurationMs = totalDurationMs,
        totalTokens = totalTokens,
        confidenceScore = confidenceScore,
        complexityScore = complexityScore,
        reasoningType = reasoningType,
        tags = tags
    )
}
