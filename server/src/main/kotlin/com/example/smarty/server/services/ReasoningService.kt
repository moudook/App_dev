package com.example.smarty.server.services

import com.example.smarty.server.data.ReasoningSummary
import com.example.smarty.server.data.ReasoningTrace
import com.example.smarty.server.data.ReasoningTraceRepository
import com.example.smarty.server.data.ReasoningTraceWithSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Reasoning Service
 * Business logic for reasoning traces and thinking logs
 */
class ReasoningService(
    private val repository: ReasoningTraceRepository,
) {
    private val logger = LoggerFactory.getLogger(ReasoningService::class.java)

    /**
     * Log a reasoning step
     */
    suspend fun logReasoningStep(
        sessionId: String,
        messageId: String?,
        userId: String,
        stepType: com.example.smarty.server.data.ReasoningStepType,
        title: String,
        content: String,
        confidenceScore: Double = 0.5,
        importanceScore: Double = 0.5,
        isFinal: Boolean = false,
        tokenCount: Int = 0,
        durationMs: Long = 0,
    ): String =
        withContext(Dispatchers.Default) {
            val trace =
                ReasoningTrace(
                    sessionId = sessionId,
                    messageId = messageId,
                    userId = userId,
                    stepIndex = getNextStepIndex(sessionId),
                    stepType = stepType,
                    title = title,
                    content = content,
                    confidenceScore = confidenceScore,
                    importanceScore = importanceScore,
                    isFinal = isFinal,
                    tokenCount = tokenCount,
                    durationMs = durationMs,
                )

            repository.saveTrace(trace)
        }

    /**
     * Log multiple reasoning steps (batch)
     */
    suspend fun logReasoningSteps(traces: List<ReasoningTrace>): Int =
        withContext(Dispatchers.Default) {
            repository.saveTraces(traces)
        }

    /**
     * Get reasoning timeline for UI
     */
    suspend fun getReasoningTimeline(sessionId: String): List<ReasoningTraceWithSummary> = repository.getReasoningTimeline(sessionId)

    /**
     * Get reasoning traces for a session
     */
    suspend fun getReasoningTraces(
        sessionId: String,
        messageId: String? = null,
    ): List<ReasoningTrace> = repository.getTracesForSession(sessionId, messageId)

    /**
     * Create and save reasoning summary
     */
    suspend fun createReasoningSummary(
        sessionId: String,
        messageId: String?,
        userId: String,
        oneLiner: String,
        briefSummary: String,
        detailedSummary: String,
        totalSteps: Int,
        totalDurationMs: Long,
        totalTokens: Int,
        confidenceScore: Double,
        complexityScore: Double,
        reasoningType: String,
        tags: List<String> = emptyList(),
    ): String {
        val summary =
            ReasoningSummary(
                sessionId = sessionId,
                messageId = messageId,
                userId = userId,
                oneLiner = oneLiner,
                briefSummary = briefSummary,
                detailedSummary = detailedSummary,
                totalSteps = totalSteps,
                totalDurationMs = totalDurationMs,
                totalTokens = totalTokens,
                confidenceScore = confidenceScore,
                complexityScore = complexityScore,
                reasoningType = reasoningType,
                tags = tags,
            )

        return repository.saveSummary(summary)
    }

    /**
     * Get reasoning summary
     */
    suspend fun getReasoningSummary(
        sessionId: String,
        messageId: String? = null,
    ): ReasoningSummary? = repository.getSummary(sessionId, messageId)

    /**
     * Mark a reasoning trace as revised
     */
    suspend fun markReasoningRevised(
        traceId: String,
        revisedByTraceId: String,
    ) {
        repository.markTraceAsRevised(traceId, revisedByTraceId)
    }

    /**
     * Get next step index for a session
     */
    private suspend fun getNextStepIndex(sessionId: String): Int {
        val existingTraces = repository.getTracesForSession(sessionId)
        return existingTraces.maxOfOrNull { it.stepIndex }?.plus(1) ?: 0
    }

    /**
     * Generate progressive disclosure levels for UI
     */
    fun generateProgressiveDisclosure(traces: List<ReasoningTrace>): ReasoningDisclosureLevels {
        val finalTraces = traces.filter { it.isFinal }

        // One-liner: Just the conclusion
        val oneLiner =
            finalTraces
                .filter { it.stepType == com.example.smarty.server.data.ReasoningStepType.SYNTHESIS }
                .firstOrNull()
                ?.content
                ?.take(200) ?: "Analysis complete"

        // Brief: Key steps only (3-5)
        val briefSteps =
            finalTraces
                .filter { it.importanceScore > 0.7 }
                .take(5)
                .map { "- ${it.title}" }

        // Detailed: All final steps
        val detailedSteps =
            finalTraces.map { trace ->
                "### ${trace.title}\n${trace.content}"
            }

        return ReasoningDisclosureLevels(
            oneLiner = oneLiner,
            briefSteps = briefSteps,
            detailedSteps = detailedSteps,
            totalSteps = traces.size,
            finalSteps = finalTraces.size,
            revisedSteps = traces.count { it.wasRevised },
        )
    }
}

/**
 * Reasoning Disclosure Levels (for progressive UI disclosure)
 */
data class ReasoningDisclosureLevels(
    val oneLiner: String,
    val briefSteps: List<String>,
    val detailedSteps: List<String>,
    val totalSteps: Int,
    val finalSteps: Int,
    val revisedSteps: Int,
)
