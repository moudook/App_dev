package com.example.smarty.server.agent

import com.example.smarty.server.llm.LlmProvider
import com.example.smarty.server.llm.LlmMessage
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * RESEARCH EVALUATOR v4.0
 *
 * The "Brain" of the closed-loop agentic workflow.
 * Implements the critical evaluation step that distinguishes agentic workflows from linear search.
 */
class ResearchEvaluator(
    private val llmProvider: LlmProvider
) {
    private val logger = LoggerFactory.getLogger(ResearchEvaluator::class.java)

    companion object {
        // Decision thresholds
        private const val COMPLETENESS_THRESHOLD = 0.85      // 85% → ready to synthesize
        private const val HIGH_CONFLICT_THRESHOLD = 0.4      // 40% conflicts → verification needed
        private const val LOW_SOURCE_QUALITY = 0.5           // Average credibility < 50%
        private const val MIN_SOURCES_REQUIRED = 5           // Minimum before synthesis
        private const val MIN_TIER1_SOURCES = 2              // Minimum Tier 1 sources
        
        // RAG confidence thresholds
        private const val RAG_CONFIDENCE_THRESHOLD = 0.7     // Minimum confidence for retrieved docs
        private const val MIN_RETRIEVED_DOCS = 3             // Minimum docs before synthesis
    }

    @Serializable
    data class EvaluationResult(
        val completenessScore: Double = 0.0,          // 0-1: How complete is the answer?
        val conflictCount: Int = 0,                    // Number of source contradictions
        val conflictSeverity: ConflictSeverity = ConflictSeverity.NONE,
        val sourceQualityScore: Double = 0.0,          // 0-1: Average credibility
        val sourceDiversityScore: Double = 0.0,        // 0-1: Variety of source types
        val identifiedGaps: List<String> = emptyList(), // What's missing?
        val unansweredQuestions: List<String> = emptyList(),
        val conflictingClaims: List<ConflictingClaim> = emptyList(),
        val ragConfidenceScore: Double = 0.0,          // RAG-specific confidence
        val identifiedBiases: List<String> = emptyList(), // Simplified bias tracking
        val recommendation: String = "",                // Human-readable recommendation
        val failureReason: String? = null,              // If FAILED, why?
        val requiresHumanReview: Boolean = false,       // High-stakes judgment needed
        val timestamp: Long = System.currentTimeMillis()
    )

    @Serializable
    enum class ConflictSeverity {
        NONE,           // No contradictions
        LOW,            // Minor discrepancies
        MODERATE,       // Some contradictions that need resolution
        HIGH            // Major conflicts requiring verification
    }

    @Serializable
    enum class SourceTier {
        TIER_1_PRIMARY,      // Government, RFCs, peer-reviewed with DOI
        TIER_2_VERIFIED,     // Major vendors, academic conferences
        TIER_3_EXPERT,       // IETF lists, GitHub advisories, HackerOne
        TIER_4_GENERAL,      // News, blogs, Stack Overflow
        TIER_5_UNVERIFIED    // Anonymous forums, dark web
    }

    @Serializable
    data class ConflictingClaim(
        val claimA: String,
        val claimB: String,
        val sourceA: String,  // URL
        val sourceB: String,  // URL
        val topic: String,
        val severity: ConflictSeverity,
        val resolution: String? = null  // How was it resolved?
    )

    enum class EvaluationDecision {
        COMPLETE,      // Ready to synthesize
        INCOMPLETE,    // Need more iteration
        FAILED         // Unresolvable issues
    }

    /**
     * Research state snapshot for evaluation
     */
    data class ResearchStateSnapshot(
        val topic: String,
        val originalQuestion: String,
        val sources: List<SourceSnapshot>,
        val currentIteration: Int = 0
    )

    data class SourceSnapshot(
        val url: String,
        val title: String,
        val keyFindings: List<String>,
        val sourceTier: SourceTier,
        val credibilityScore: Double,
        val sourceType: String
    )

    /**
     * Evaluate the current research state
     */
    suspend fun evaluate(state: ResearchStateSnapshot): EvaluationResult {
        logger.info("Evaluating research state: ${state.sources.size} sources, iteration ${state.currentIteration}")
        
        // 1. Evaluate completeness against original question
        val completenessScore = evaluateCompleteness(state)
        
        // 2. Detect conflicts between sources
        val conflicts = detectConflicts(state)
        
        // 3. Evaluate source quality
        val sourceQualityScore = evaluateSourceQuality(state)
        val sourceDiversityScore = evaluateSourceDiversity(state)
        
        // 4. Identify gaps
        val gaps = identifyGaps(state)
        val unansweredQuestions = identifyUnansweredQuestions(state)
        
        // 5. Evaluate RAG confidence
        val ragConfidenceScore = evaluateRagConfidence(state)
        
        // 6. Detect cognitive biases
        val identifiedBiases = detectBiases(state)
        
        // Calculate conflict severity
        val conflictSeverity = when {
            conflicts.isEmpty() -> ConflictSeverity.NONE
            conflicts.size <= 2 -> ConflictSeverity.LOW
            conflicts.size <= 5 -> ConflictSeverity.MODERATE
            else -> ConflictSeverity.HIGH
        }
        
        // Determine if human review is required
        val requiresHumanReview = conflictSeverity == ConflictSeverity.HIGH ||
                                 sourceQualityScore < LOW_SOURCE_QUALITY ||
                                 state.sources.size < MIN_SOURCES_REQUIRED
        
        // Generate recommendation
        val recommendation = generateRecommendation(
            completenessScore = completenessScore,
            conflictSeverity = conflictSeverity,
            sourceQualityScore = sourceQualityScore,
            gaps = gaps,
            requiresHumanReview = requiresHumanReview
        )
        
        // Check for failure conditions
        val failureReason = checkFailureConditions(state, conflicts)
        
        return EvaluationResult(
            completenessScore = completenessScore,
            conflictCount = conflicts.size,
            conflictSeverity = conflictSeverity,
            sourceQualityScore = sourceQualityScore,
            sourceDiversityScore = sourceDiversityScore,
            identifiedGaps = gaps,
            unansweredQuestions = unansweredQuestions,
            conflictingClaims = conflicts,
            ragConfidenceScore = ragConfidenceScore,
            identifiedBiases = identifiedBiases,
            recommendation = recommendation,
            failureReason = failureReason,
            requiresHumanReview = requiresHumanReview
        )
    }

    /**
     * Make decision based on evaluation result
     */
    fun makeDecision(evaluation: EvaluationResult): EvaluationDecision {
        // Check for failure conditions
        if (evaluation.failureReason != null) {
            logger.warn("Evaluation FAILED: ${evaluation.failureReason}")
            return EvaluationDecision.FAILED
        }
        
        // Check if complete
        if (evaluation.completenessScore >= COMPLETENESS_THRESHOLD &&
            evaluation.conflictSeverity != ConflictSeverity.HIGH &&
            evaluation.sourceQualityScore >= LOW_SOURCE_QUALITY) {
            logger.info("Evaluation COMPLETE: Ready to synthesize (completeness=${evaluation.completenessScore})")
            return EvaluationDecision.COMPLETE
        }
        
        // Otherwise, needs more iteration
        logger.info("Evaluation INCOMPLETE: Need more research (completeness=${evaluation.completenessScore})")
        return EvaluationDecision.INCOMPLETE
    }

    /**
     * Evaluate completeness: Does this answer the original question?
     */
    private suspend fun evaluateCompleteness(state: ResearchStateSnapshot): Double {
        val messages = listOf(
            LlmMessage(LlmMessage.Role.SYSTEM, """
You are an expert evaluator assessing research completeness.

TASK: Rate how completely the gathered information answers the original question.

SCORING:
- 1.0: Fully comprehensive, all aspects covered
- 0.8-0.9: Mostly complete, minor gaps
- 0.6-0.7: Moderately complete, some gaps
- 0.4-0.5: Partially complete, significant gaps
- 0.0-0.3: Incomplete, major gaps

Be critical and rigorous.
"""),
            LlmMessage(LlmMessage.Role.USER, """
Original Question: ${state.originalQuestion}

Gathered Information:
- Total sources: ${state.sources.size}
- Source types: ${state.sources.map { it.sourceType }.toSet()}
- Key findings: ${state.sources.flatMap { it.keyFindings }.take(10)}

Rate completeness (0.0-1.0) and explain your reasoning.
""")
        )

        val response = llmProvider.generate(messages)
        return parseCompletenessScore(response.content ?: "")
    }

    /**
     * Detect conflicts between sources
     */
    private suspend fun detectConflicts(state: ResearchStateSnapshot): List<ConflictingClaim> {
        val messages = listOf(
            LlmMessage(LlmMessage.Role.SYSTEM, """
You are a fact-checker specializing in conflict detection.

TASK: Identify contradictions between different sources.

Look for:
- Conflicting facts or statistics
- Opposing expert opinions
- Inconsistent timelines or sequences
- Disagreements on causality or responsibility

For each conflict, note the severity and which sources are involved.
"""),
            LlmMessage(LlmMessage.Role.USER, """
Research Topic: ${state.topic}

Sources to analyze:
${state.sources.take(20).joinToString("\n\n") { source ->
    "Source: ${source.title}\nURL: ${source.url}\nKey Findings: ${source.keyFindings.joinToString("; ")}"
}}

Identify all conflicts and contradictions.
""")
        )

        val response = llmProvider.generate(messages)
        return parseConflicts(response.content ?: "")
    }

    /**
     * Evaluate source quality (average credibility)
     */
    private fun evaluateSourceQuality(state: ResearchStateSnapshot): Double {
        val sources = state.sources
        if (sources.isEmpty()) return 0.0

        val tier1Count = sources.count { it.sourceTier == SourceTier.TIER_1_PRIMARY }
        val tier2Count = sources.count { it.sourceTier == SourceTier.TIER_2_VERIFIED }
        val tier3Count = sources.count { it.sourceTier == SourceTier.TIER_3_EXPERT }
        
        // Weighted scoring
        val weightedScore = (tier1Count * 1.0 + tier2Count * 0.8 + tier3Count * 0.6) / sources.size
        
        // Also consider average credibility score
        val avgCredibility = sources.map { it.credibilityScore }.average()
        
        // Combine both metrics
        return (weightedScore + avgCredibility) / 2.0
    }

    /**
     * Evaluate source diversity
     */
    private fun evaluateSourceDiversity(state: ResearchStateSnapshot): Double {
        val sources = state.sources
        if (sources.isEmpty()) return 0.0

        val sourceTypes = sources.map { it.sourceType }.toSet().size
        val uniqueDomains = sources.map { extractDomain(it.url) }.toSet().size

        // Normalize to 0-1 (assuming 8 source types)
        val typeDiversity = (sourceTypes.toDouble() / 8.0).coerceAtMost(1.0)
        val domainDiversity = (uniqueDomains.toDouble() / sources.size).coerceAtMost(1.0)

        return (typeDiversity + domainDiversity) / 2.0
    }

    /**
     * Identify knowledge gaps
     */
    private suspend fun identifyGaps(state: ResearchStateSnapshot): List<String> {
        val messages = listOf(
            LlmMessage(LlmMessage.Role.SYSTEM, """
You are a research analyst specializing in gap analysis.

TASK: Identify what information is MISSING from the current research.

Consider:
- Aspects of the original question not addressed
- Missing perspectives or viewpoints
- Lack of specific data types (statistics, expert opinions, case studies)
- Temporal gaps (outdated information, missing recent developments)
- Geographic or demographic gaps

Be specific about what's missing.
"""),
            LlmMessage(LlmMessage.Role.USER, """
Original Question: ${state.originalQuestion}

Current Research:
- Sources collected: ${state.sources.size}

Identify specific knowledge gaps.
""")
        )

        val response = llmProvider.generate(messages)
        return parseGaps(response.content ?: "")
    }

    /**
     * Identify unanswered questions
     */
    private suspend fun identifyUnansweredQuestions(state: ResearchStateSnapshot): List<String> {
        val messages = listOf(
            LlmMessage(LlmMessage.Role.SYSTEM, """
You are a research coordinator tracking open questions.

TASK: List specific questions that remain UNANSWERED by the current research.

Format each question clearly and actionably.
"""),
            LlmMessage(LlmMessage.Role.USER, """
Original Question: ${state.originalQuestion}

Current Findings:
${state.sources.take(10).joinToString("\n") { "- ${it.title}: ${it.keyFindings.take(2)}" }}

What questions remain unanswered?
""")
        )

        val response = llmProvider.generate(messages)
        return parseUnansweredQuestions(response.content ?: "")
    }

    /**
     * Evaluate RAG confidence (retrieval-augmented generation confidence)
     */
    private suspend fun evaluateRagConfidence(state: ResearchStateSnapshot): Double {
        val messages = listOf(
            LlmMessage(LlmMessage.Role.SYSTEM, """
You are evaluating the confidence level for retrieval-augmented generation.

TASK: Assess how confident you are in the retrieved information.

Consider:
- Are sources primary or secondary?
- Is information consistent across sources?
- Are claims specific and verifiable?
- Is there sufficient evidence for each major claim?

Rate confidence 0.0-1.0.
"""),
            LlmMessage(LlmMessage.Role.USER, """
Research Topic: ${state.topic}

Retrieved Information:
- Total sources: ${state.sources.size}
- Tier 1 sources: ${state.sources.count { it.sourceTier == SourceTier.TIER_1_PRIMARY }}
- Tier 2 sources: ${state.sources.count { it.sourceTier == SourceTier.TIER_2_VERIFIED }}

Rate RAG confidence (0.0-1.0).
""")
        )

        val response = llmProvider.generate(messages)
        return parseConfidenceScore(response.content ?: "")
    }

    /**
     * Detect cognitive biases in the research
     */
    private fun detectBiases(state: ResearchStateSnapshot): List<String> {
        val biases = mutableListOf<String>()
        val sources = state.sources

        // Confirmation bias: All sources have very high credibility (might indicate cherry-picking)
        if (sources.isNotEmpty() && sources.all { it.credibilityScore > 0.8 }) {
            biases.add("CONFIRMATION_BIAS")
        }

        // Anchoring bias: First source was low tier
        val firstSource = sources.firstOrNull()
        if (firstSource?.sourceTier in listOf(SourceTier.TIER_4_GENERAL, SourceTier.TIER_5_UNVERIFIED)) {
            biases.add("ANCHORING")
        }

        // Recency bias: Would need timestamp analysis

        return biases
    }

    /**
     * Generate human-readable recommendation
     */
    private fun generateRecommendation(
        completenessScore: Double,
        conflictSeverity: ConflictSeverity,
        sourceQualityScore: Double,
        gaps: List<String>,
        requiresHumanReview: Boolean
    ): String {
        return buildString {
            when {
                completenessScore >= COMPLETENESS_THRESHOLD && conflictSeverity != ConflictSeverity.HIGH -> {
                    append("Research is sufficiently complete. Proceed to synthesis.")
                }
                conflictSeverity == ConflictSeverity.HIGH -> {
                    append("HIGH CONFLICTS DETECTED. ")
                    append("Recommend verification phase or human review. ")
                }
                sourceQualityScore < LOW_SOURCE_QUALITY -> {
                    append("SOURCE QUALITY TOO LOW. ")
                    append("Search for more authoritative sources (Tier 1-2). ")
                }
                gaps.isNotEmpty() -> {
                    append("Knowledge gaps identified: ${gaps.take(3).joinToString(", ")}. ")
                    append("Recommend targeted searches to address gaps. ")
                }
                else -> {
                    append("Continue research iteration. ")
                }
            }
            
            if (requiresHumanReview) {
                append(" Human review recommended.")
            }
        }
    }

    /**
     * Check for failure conditions
     */
    private fun checkFailureConditions(
        state: ResearchStateSnapshot,
        conflicts: List<ConflictingClaim>
    ): String? {
        // Max iterations exceeded
        if (state.currentIteration >= 20) {
            return "Max iterations (20) exceeded"
        }

        // Unresolvable conflicts
        if (conflicts.size > 10) {
            return "Too many conflicting claims (${conflicts.size}) - unresolvable"
        }

        // No sources found
        if (state.sources.isEmpty() && state.currentIteration > 3) {
            return "No information sources found after ${state.currentIteration} iterations"
        }

        return null
    }

    // Parsing helpers (to be implemented with LLM structured output)
    private fun parseCompletenessScore(content: String): Double {
        // Extract number from LLM response
        val regex = Regex("""(0\.\d+|1\.0|0|1)""")
        return regex.find(content)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.5
    }

    private fun parseConflicts(content: String): List<ConflictingClaim> {
        // TODO: Parse structured JSON from LLM
        return emptyList()
    }

    private fun parseGaps(content: String): List<String> {
        // Parse bullet points or numbered list
        return content.split("\n")
            .filter { it.trim().startsWith("-") || it.trim().matches(Regex("""^\d+\. """)) }
            .map { it.trim().removePrefix("-").removePrefix("•").trim() }
            .filter { it.isNotBlank() }
    }

    private fun parseUnansweredQuestions(content: String): List<String> {
        // Parse questions (lines ending with ?)
        return content.split("\n")
            .map { it.trim() }
            .filter { it.endsWith("?") }
            .filter { it.isNotBlank() }
    }

    private fun parseConfidenceScore(content: String): Double {
        val regex = Regex("""(0\.\d+|1\.0|0|1)""")
        return regex.find(content)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.5
    }

    private fun extractDomain(url: String): String {
        return try {
            url.split("://").getOrElse(1) { url }.split("/").first()
        } catch (e: Exception) {
            url
        }
    }
}
