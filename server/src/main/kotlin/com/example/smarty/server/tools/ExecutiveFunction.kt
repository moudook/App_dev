package com.example.smarty.server.tools

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*
import kotlin.random.Random

@Serializable
data class Decision(
    val id: String,
    val question: String,
    val options: List<String>,
    val criteria: Map<String, Int>,
    val scores: Map<String, Map<String, Int>>,
    val decision: String?,
    val reasoning: String?,
    val confidence: Double,
    val createdAt: Long,
    val decidedAt: Long?,
    val decisionQuality: Double = 0.5,
    val outcome: String? = null,
    val satisfaction: Double? = null,
    val alternativeRevisits: Int = 0
)

@Serializable
data class Plan(
    val id: String,
    val name: String,
    val objective: String,
    val steps: List<PlanStep>,
    val status: String,
    val createdAt: Long,
    val estimatedDuration: String?,
    val dependencies: Map<String, List<String>>,
    val riskLevel: String,
    val successProbability: Double = 0.5,
    val resourceRequirements: Map<String, Double> = emptyMap(),
    val criticalPath: List<String> = emptyList(),
    val parallelizationOptions: List<List<String>> = emptyList(),
    val contingencyPlans: List<ContingencyPlan> = emptyList()
)

@Serializable
data class PlanStep(
    val id: String,
    val description: String,
    val tool: String?,
    val args: Map<String, String>?,
    val estimatedTime: String?,
    val dependencies: List<String>,
    val status: String,
    val result: String?,
    val executionTime: Long? = null,
    val retryCount: Int = 0,
    val energyCost: Double = 0.1,
    val complexity: Int = 1
)

@Serializable
data class ContingencyPlan(
    val triggerCondition: String,
    val alternativeSteps: List<PlanStep>,
    val recoveryStrategy: String,
    val activationProbability: Double
)

@Serializable
data class CognitiveState(
    val focus: String?,
    val energy: Double,
    val confidence: Double,
    val uncertainty: List<String>,
    val activeQuestions: List<String>,
    val lastUpdated: Long,
    val cognitiveLoad: Double = 0.5,
    val workingMemoryUsage: Double = 0.3,
    val attentionFragmentation: Double = 0.0,
    val taskSwitchingOverhead: Double = 0.0
)

@Serializable
data class DecisionContext(
    val decisionId: String,
    val environmentalFactors: Map<String, Double>,
    val temporalPressure: Double,
    val stakes: Double,
    val reversibility: Double,
    val availableInformation: Double,
    val alternativesConsidered: Int,
    val processingDepth: String,
    val biasIndicators: List<String>
)

@Serializable
data class ExecutionProfile(
    val planId: String,
    val startTime: Long,
    val endTime: Long?,
    val stepDurations: Map<String, Long>,
    val totalEnergy: Double,
    val efficiency: Double,
    val bottlenecks: List<String>,
    val parallelizationGains: Double,
    val rollbackCount: Int,
    val adaptations: List<String>
)

@Serializable
data class HeuristicScore(
    val heuristic: String,
    val score: Double,
    val weight: Double,
    val reasoning: String
)

@Serializable
data class MetacognitiveAssessment(
    val decisionQuality: Double,
    val biasDetected: List<String>,
    val confidenceCalibration: Double,
    val processingEfficiency: Double,
    val improvementAreas: List<String>,
    val learnedPatterns: Map<String, Double>
)

@Serializable
data class PlanAnalytics(
    val planId: String,
    val successProbability: Double,
    val riskFactors: List<String>,
    val resourceOptimization: List<String>,
    val parallelizationScore: Double,
    val criticalPathDuration: Long,
    val estimatedVariance: Double,
    val monteCarloResults: Map<String, Double>
)

class ExecutiveFunction {
    private val logger = LoggerFactory.getLogger(ExecutiveFunction::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val decisions = ConcurrentHashMap<String, Decision>()
    private val plans = ConcurrentHashMap<String, Plan>()
    private var cognitiveState = CognitiveState(
        focus = null,
        energy = 1.0,
        confidence = 0.5,
        uncertainty = emptyList(),
        activeQuestions = emptyList(),
        lastUpdated = System.currentTimeMillis()
    )
    
    private val decisionHistory = mutableListOf<Decision>()
    private val executionProfiles = ConcurrentHashMap<String, ExecutionProfile>()
    private val heuristics = ConcurrentHashMap<String, MutableList<HeuristicScore>>()
    private val decisionPatterns = ConcurrentHashMap<String, Double>()
    
    private val decisionCounter = AtomicLong(0)
    private val planCounter = AtomicLong(0)
    
    private val decisionWeights = mapOf(
        "feasibility" to 1.0,
        "impact" to 1.2,
        "effort" to 0.8,
        "reversibility" to 0.6,
        "time_sensitivity" to 0.9,
        "risk_tolerance" to 0.7,
        "alignment" to 1.1,
        "innovation" to 0.5,
        "stakeholder_satisfaction" to 1.0,
        "sustainability" to 0.8
    )
    
    private val biasWeights = mapOf(
        "confirmation_bias" to 0.15,
        "availability_heuristic" to 0.12,
        "anchoring" to 0.10,
        "status_quo" to 0.08,
        "sunk_cost" to 0.10,
        "optimism_bias" to 0.12,
        "hindsight_bias" to 0.08
    )
    
    companion object {
        private const val MAX_DECISIONS = 200
        private const val MAX_PLANS = 100
        private const val MONTE_CARLO_ITERATIONS = 1000
    }
    
    fun makeDecision(
        question: String,
        options: List<String>,
        criteria: Map<String, Int> = mapOf("feasibility" to 5, "impact" to 5, "effort" to 5),
        scores: Map<String, Map<String, Int>> = emptyMap(),
        context: Map<String, Double> = emptyMap()
    ): Decision {
        val decisionId = "dec_${System.currentTimeMillis()}_${decisionCounter.incrementAndGet()}"
        
        val biasAssessment = assessCognitiveBiases(question, options)
        
        val adjustedCriteria = criteria.mapValues { (key, value) ->
            val biasPenalty = biasAssessment.biasIndicators.sumOf { bias ->
                (biasWeights[bias] ?: 0.0) * value
            }
            (value * (1.0 - biasPenalty)).toInt().coerceIn(1, 10)
        }
        
        val weightedScores = if (scores.isEmpty()) {
            generateHeuristicScores(options, adjustedCriteria, context)
        } else {
            scores
        }
        
        val totalScores = weightedScores.mapValues { (_, criteriaScores) ->
            criteriaScores.entries.sumOf { (criterion, score) ->
                val weight = decisionWeights[criterion] ?: 1.0
                score * weight * (adjustedCriteria[criterion] ?: 1)
            }
        }
        
        val bestOption = totalScores.maxByOrNull { it.value }?.key ?: options.first()
        val maxScore = totalScores.values.maxOrNull() ?: 0
        val avgScore = totalScores.values.average()
        val stdDev = calculateStdDev(totalScores.values.toList())
        
        val confidence = calculateConfidence(maxScore, avgScore, stdDev, options.size)
        
        val reasoning = generateDecisionReasoning(
            question, options, criteria, weightedScores, bestOption, 
            maxScore, avgScore, biasAssessment
        )
        
        val decision = Decision(
            id = decisionId,
            question = question,
            options = options,
            criteria = adjustedCriteria,
            scores = weightedScores,
            decision = bestOption,
            reasoning = reasoning,
            confidence = confidence,
            createdAt = System.currentTimeMillis(),
            decidedAt = System.currentTimeMillis(),
            decisionQuality = confidence * (1.0 - biasAssessment.biasDetected.size * 0.1)
        )
        
        decisions[decisionId] = decision
        updateDecisionHistory(decision)
        
        recordHeuristicsUsed(decisionId, weightedScores)
        
        logger.info("Decision made: $question -> $bestOption (confidence: ${(confidence * 100).toInt()}%)")
        
        return decision
    }
    
    private fun generateHeuristicScores(
        options: List<String>,
        criteria: Map<String, Int>,
        context: Map<String, Double>
    ): Map<String, Map<String, Int>> {
        val temporalPressure = context["temporal_pressure"] ?: 0.5
        val complexity = context["complexity"] ?: 0.5
        
        return options.associateWith { option ->
            criteria.mapValues { (criterion, weight) ->
                val baseScore = 5 + Random.nextInt(4)
                val pressureModifier = if (temporalPressure > 0.7) -1 else 0
                val complexityModifier = if (complexity > 0.6) -Random.nextInt(2) else 0
                
                (baseScore + pressureModifier + complexityModifier).coerceIn(1, 10)
            }
        }
    }
    
    private fun calculateConfidence(maxScore: Double, avgScore: Double, stdDev: Double, optionCount: Int): Double {
        val margin = maxScore - avgScore
        val normalizedMargin = (margin / (avgScore + 0.001)).coerceIn(0.0, 1.0)
        val certainty = 1.0 - (stdDev / (avgScore + 0.001)).coerceIn(0.0, 1.0)
        val optionFactor = 1.0 - (optionCount - 1) * 0.05
        
        return (normalizedMargin * 0.4 + certainty * 0.4 + optionFactor * 0.2).coerceIn(0.1, 0.95)
    }
    
    private fun calculateStdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }
    
    private fun assessCognitiveBiases(question: String, options: List<String>): MetacognitiveAssessment {
        val biasIndicators = mutableListOf<String>()
        
        if (question.contains("always", "never", "definitely")) {
            biasIndicators.add("absolutism")
        }
        
        if (options.size == 2 && options.first().length > options.last().length * 2) {
            biasIndicators.add("anchoring")
        }
        
        if (options.any { it.contains("same", "continue", "keep") }) {
            biasIndicators.add("status_quo")
        }
        
        val confidenceCalibration = 1.0 - biasIndicators.size * 0.1
        
        return MetacognitiveAssessment(
            decisionQuality = 0.5,
            biasDetected = biasIndicators,
            confidenceCalibration = confidenceCalibration,
            processingEfficiency = 0.7,
            improvementAreas = emptyList(),
            learnedPatterns = emptyMap()
        )
    }
    
    private fun generateDecisionReasoning(
        question: String,
        options: List<String>,
        criteria: Map<String, Int>,
        scores: Map<String, Map<String, Int>>,
        bestOption: String,
        maxScore: Double,
        avgScore: Double,
        biasAssessment: MetacognitiveAssessment
    ): String {
        return buildString {
            appendLine("Analyzed ${options.size} options across ${criteria.size} criteria.")
            appendLine("Top option '$bestOption' scored ${maxScore.toInt()} (avg: ${"%.1f".format(avgScore)})")
            
            if (biasAssessment.biasDetected.isNotEmpty()) {
                appendLine("\nPotential biases detected: ${biasAssessment.biasDetected.joinToString(", ")}")
            }
            
            appendLine("\nKey differentiating factors:")
            val bestCriteria = scores[bestOption] ?: emptyMap()
            criteria.entries.sortedByDescending { it.value }.take(3).forEach { (criterion, weight) ->
                val score = bestCriteria[criterion] ?: 0
                appendLine("  - $criterion: $score (weight: $weight)")
            }
            
            appendLine("\nAlternative options considered:")
            scores.keys.filter { it != bestOption }.take(2).forEach { alt ->
                val altScore = scores[alt]?.values?.sum() ?: 0
                val gap = maxScore - altScore
                appendLine("  - $alt: ${altScore.toInt()} (gap: ${gap.toInt()})")
            }
        }
    }
    
    private fun updateDecisionHistory(decision: Decision) {
        decisionHistory.add(decision)
        if (decisionHistory.size > MAX_DECISIONS) {
            decisionHistory.removeAt(0)
        }
        
        val patternKey = extractDecisionPattern(decision.question)
        decisionPatterns[patternKey] = (decisionPatterns[patternKey] ?: 0.0) + 1.0
    }
    
    private fun extractDecisionPattern(question: String): String {
        return when {
            question.contains("should", "should I") -> "should_pattern"
            question.contains("which", "what") -> "selection_pattern"
            question.contains("how") -> "method_pattern"
            question.contains("when") -> "timing_pattern"
            else -> "general_pattern"
        }
    }
    
    private fun recordHeuristicsUsed(decisionId: String, scores: Map<String, Map<String, Int>>) {
        val heuristicList = mutableListOf<HeuristicScore>()
        
        scores.forEach { (option, criteriaScores) ->
            criteriaScores.forEach { (criterion, score) ->
                heuristicList.add(HeuristicScore(
                    heuristic = criterion,
                    score = score / 10.0,
                    weight = decisionWeights[criterion] ?: 1.0,
                    reasoning = "Evaluated $option on $criterion"
                ))
            }
        }
        
        heuristics[decisionId] = heuristicList
    }
    
    fun createPlan(
        name: String,
        objective: String,
        steps: List<PlanStep>,
        estimatedDuration: String? = null,
        riskLevel: String = "medium"
    ): String {
        val planId = "plan_${planCounter.incrementAndGet()}_${System.currentTimeMillis()}"
        
        val dependencies = mutableMapOf<String, List<String>>()
        steps.forEach { step ->
            if (step.dependencies.isNotEmpty()) {
                dependencies[step.id] = step.dependencies
            }
        }
        
        val criticalPath = findCriticalPath(steps, dependencies)
        val parallelOptions = findParallelizationOpportunities(steps, dependencies)
        val successProbability = calculateSuccessProbability(steps, riskLevel)
        val resourceRequirements = calculateResourceRequirements(steps)
        
        val contingencyPlans = generateContingencyPlans(steps, dependencies)
        
        val plan = Plan(
            id = planId,
            name = name,
            objective = objective,
            steps = steps,
            status = "ready",
            createdAt = System.currentTimeMillis(),
            estimatedDuration = estimatedDuration,
            dependencies = dependencies,
            riskLevel = riskLevel,
            successProbability = successProbability,
            resourceRequirements = resourceRequirements,
            criticalPath = criticalPath,
            parallelizationOptions = parallelOptions,
            contingencyPlans = contingencyPlans
        )
        
        plans[planId] = plan
        logger.info("Created plan: $name with ${steps.size} steps (success probability: ${(successProbability * 100).toInt()}%)")
        
        return planId
    }
    
    private fun findCriticalPath(steps: List<PlanStep>, dependencies: Map<String, List<String>>): List<String> {
        if (steps.isEmpty()) return emptyList()
        
        val stepMap = steps.associateBy { it.id }
        val inDegree = steps.associate { it.id to it.dependencies.size }
        val visited = mutableSetOf<String>()
        
        val criticalPath = mutableListOf<String>()
        var current = inDegree.filter { it.value == 0 }.minByOrNull { 
            stepMap[it.key]?.complexity ?: 0 
        }?.key
        
        while (current != null && current !in visited) {
            visited.add(current)
            criticalPath.add(current)
            
            current = stepMap[current]?.dependencies?.firstOrNull { dep ->
                stepMap[dep]?.status == "completed"
            }
        }
        
        return criticalPath
    }
    
    private fun findParallelizationOpportunities(
        steps: List<PlanStep>, 
        dependencies: Map<String, List<String>>
    ): List<List<String>> {
        val parallelGroups = mutableListOf<List<String>>()
        val completed = mutableSetOf<String>()
        val remaining = steps.map { it.id }.toMutableSet()
        
        while (remaining.isNotEmpty()) {
            val currentGroup = remaining.filter { stepId ->
                val step = steps.find { it.id == stepId } ?: return@filter false
                step.dependencies.all { dep -> dep in completed }
            }
            
            if (currentGroup.isEmpty()) break
            
            parallelGroups.add(currentGroup)
            currentGroup.forEach { completed.add(it); remaining.remove(it) }
        }
        
        return parallelGroups.filter { it.size > 1 }
    }
    
    private fun calculateSuccessProbability(steps: List<PlanStep>, riskLevel: String): Double {
        val baseProbability = when (riskLevel) {
            "low" -> 0.95
            "medium" -> 0.8
            "high" -> 0.6
            "critical" -> 0.4
            else -> 0.7
        }
        
        val complexityFactor = steps.map { it.complexity }.average()
        val dependencyFactor = steps.map { it.dependencies.size }.average().let { 
            1.0 - (it * 0.05)
        }
        
        val energyFactor = steps.map { it.energyCost }.sum().let { 
            1.0 - (it * 0.3).coerceAtMost(0.4)
        }
        
        return (baseProbability * 0.4 + complexityFactor / 10.0 * 0.3 + 
                dependencyFactor * 0.2 + energyFactor * 0.1).coerceIn(0.1, 0.99)
    }
    
    private fun calculateResourceRequirements(steps: List<PlanStep>): Map<String, Double> {
        return mapOf(
            "total_energy" to steps.sumOf { it.energyCost },
            "max_concurrent" to steps.map { it.energyCost }.maxOrNull() ?: 0.0,
            "avg_complexity" to steps.map { it.complexity }.average(),
            "dependency_complexity" to steps.map { it.dependencies.size }.average()
        )
    }
    
    private fun generateContingencyPlans(
        steps: List<PlanStep>,
        dependencies: Map<String, List<String>>
    ): List<ContingencyPlan> {
        val contingencyPlans = mutableListOf<ContingencyPlan>()
        
        steps.filter { it.complexity > 3 || it.dependencies.size > 2 }.forEach { step ->
            contingencyPlans.add(ContingencyPlan(
                triggerCondition = "step_${step.id}_fails",
                alternativeSteps = listOf(step.copy(
                    id = "${step.id}_alt",
                    description = "${step.description} (fallback)",
                    complexity = maxOf(1, step.complexity - 1)
                )),
                recoveryStrategy = "Simplify and retry with reduced complexity",
                activationProbability = 0.3
            ))
        }
        
        return contingencyPlans
    }
    
    fun getPlanAnalytics(planId: String): PlanAnalytics? {
        val plan = plans[planId] ?: return null
        
        val riskFactors = mutableListOf<String>()
        
        if (plan.riskLevel == "high" || plan.riskLevel == "critical") {
            riskFactors.add("High risk level: ${plan.riskLevel}")
        }
        
        if (plan.successProbability < 0.6) {
            riskFactors.add("Low success probability: ${(plan.successProbability * 100).toInt()}%")
        }
        
        if (plan.criticalPath.size > plan.steps.size / 2) {
            riskFactors.add("Long critical path may cause delays")
        }
        
        val dependencyComplexity = plan.dependencies.values.flatten().size
        if (dependencyComplexity > plan.steps.size) {
            riskFactors.add("High dependency complexity")
        }
        
        val resourceOptimization = mutableListOf<String>()
        if (plan.parallelizationOptions.isNotEmpty()) {
            val parallelGain = plan.parallelizationOptions.size * 0.1
            resourceOptimization.add("Parallel execution possible: ${plan.parallelizationOptions.size} groups")
        }
        
        if (plan.resourceRequirements["total_energy"] ?: 0.0 > 1.0) {
            resourceOptimization.add("High energy requirement: consider optimization")
        }
        
        val monteCarloResults = runMonteCarloSimulation(plan)
        
        return PlanAnalytics(
            planId = planId,
            successProbability = plan.successProbability,
            riskFactors = riskFactors,
            resourceOptimization = resourceOptimization,
            parallelizationScore = plan.parallelizationOptions.size.toDouble() / maxOf(1, plan.steps.size),
            criticalPathDuration = estimateCriticalPathDuration(plan),
            estimatedVariance = monteCarloResults["variance"] ?: 0.0,
            monteCarloResults = monteCarloResults
        )
    }
    
    private fun runMonteCarloSimulation(plan: Plan): Map<String, Double> {
        var successCount = 0
        val durations = mutableListOf<Long>()
        
        repeat(MONTE_CARLO_ITERATIONS) {
            var totalDuration = 0L
            var failed = false
            
            plan.steps.forEach { step ->
                val stepDuration = (step.complexity * 1000 * (0.8 + Random.nextDouble() * 0.4)).toLong()
                totalDuration += stepDuration
                
                val failureChance = 1.0 - plan.successProbability
                if (Random.nextDouble() < failureChance * 0.1) {
                    failed = true
                }
            }
            
            if (!failed) successCount++
            durations.add(totalDuration)
        }
            
        val avgDuration = durations.average()
        val variance = durations.map { (it - avgDuration).pow(2) }.average()
        
        return mapOf(
            "success_rate" to successCount.toDouble() / MONTE_CARLO_ITERATIONS,
            "avg_duration" to avgDuration,
            "min_duration" to durations.minOrNull()?.toDouble() ?: 0.0,
            "max_duration" to durations.maxOrNull()?.toDouble() ?: 0.0,
            "variance" to variance,
            "std_dev" to sqrt(variance)
        )
    }
    
    private fun estimateCriticalPathDuration(plan: Plan): Long {
        val stepMap = plan.steps.associateBy { it.id }
        
        var totalDuration = 0L
        plan.criticalPath.forEach { stepId ->
            val step = stepMap[stepId]
            if (step != null) {
                val baseDuration = step.complexity * 1000L
                totalDuration += baseDuration
            }
        }
        
        return totalDuration
    }
    
    fun quickPlan(
        objective: String,
        tools: List<String>
    ): String {
        val steps = tools.mapIndexed { index, tool ->
            PlanStep(
                id = "step_$index",
                description = "Execute $tool",
                tool = tool,
                args = emptyMap(),
                estimatedTime = null,
                dependencies = if (index > 0) listOf("step_${index - 1}") else emptyList(),
                status = "pending",
                result = null,
                energyCost = 0.1 + (tool.length * 0.01),
                complexity = 1
            )
        }
        
        return createPlan(
            name = objective.take(30),
            objective = objective,
            steps = steps
        )
    }
    
    fun adaptivePlan(
        objective: String,
        constraints: List<String>,
        availableTools: List<String>
    ): String {
        val steps = mutableListOf<PlanStep>()
        
        val complexity = when {
            constraints.contains("high_stakes") -> 3
            constraints.contains("complex") -> 3
            constraints.contains("urgent") -> 2
            else -> 2
        }
        
        steps.add(PlanStep(
            id = "step_0",
            description = "Analyze objective and constraints",
            tool = null,
            args = null,
            estimatedTime = "30s",
            dependencies = emptyList(),
            status = "pending",
            result = null,
            energyCost = 0.15,
            complexity = complexity
        ))
        
        steps.add(PlanStep(
            id = "step_1",
            description = "Gather relevant information",
            tool = if (availableTools.contains("search_web")) "search_web" else null,
            args = mapOf("query" to objective),
            estimatedTime = "1m",
            dependencies = listOf("step_0"),
            status = "pending",
            result = null,
            energyCost = 0.2,
            complexity = complexity
        ))
        
        steps.add(PlanStep(
            id = "step_2",
            description = "Process and analyze gathered information",
            tool = if (availableTools.contains("analyze_data")) "analyze_data" else null,
            args = mapOf("data" to "\${prev}", "analysis" to "summary"),
            estimatedTime = "30s",
            dependencies = listOf("step_1"),
            status = "pending",
            result = null,
            energyCost = 0.25,
            complexity = complexity + 1
        ))
        
        steps.add(PlanStep(
            id = "step_3",
            description = "Execute primary action",
            tool = availableTools.firstOrNull { it !in listOf("search_web", "analyze_data") },
            args = mapOf("input" to "\${prev}"),
            estimatedTime = "1m",
            dependencies = listOf("step_2"),
            status = "pending",
            result = null,
            energyCost = 0.3,
            complexity = complexity + 1
        ))
        
        steps.add(PlanStep(
            id = "step_4",
            description = "Verify and report results",
            tool = null,
            args = null,
            estimatedTime = "30s",
            dependencies = listOf("step_3"),
            status = "pending",
            result = null,
            energyCost = 0.1,
            complexity = complexity - 1
        ))
        
        val riskLevel = when {
            constraints.contains("high_stakes") -> "high"
            constraints.contains("critical") -> "critical"
            constraints.contains("urgent") -> "medium"
            else -> "medium"
        }
        
        return createPlan(
            name = "Adaptive: ${objective.take(20)}",
            objective = objective,
            steps = steps,
            riskLevel = riskLevel
        )
    }
    
    fun getPlan(planId: String): Plan? = plans[planId]
    
    fun updatePlanStep(planId: String, stepId: String, status: String, result: String?): Boolean {
        val plan = plans[planId] ?: return false
        
        val updatedSteps = plan.steps.map { step ->
            if (step.id == stepId) {
                val executionTime = if (status == "completed") {
                    (step.complexity * 1000L * (0.8 + Random.nextDouble() * 0.4)).toLong()
                } else null
                
                step.copy(status = status, result = result, executionTime = executionTime)
            } else {
                step
            }
        }
        
        val allComplete = updatedSteps.all { it.status == "completed" }
        val anyFailed = updatedSteps.any { it.status == "failed" }
        
        val newStatus = when {
            anyFailed -> "failed"
            allComplete -> "completed"
            else -> "in_progress"
        }
        
        val updatedPlan = plan.copy(steps = updatedSteps, status = newStatus)
        plans[planId] = updatedPlan
        
        if (newStatus == "completed") {
            recordExecutionProfile(updatedPlan)
        }
        
        return true
    }
    
    private fun recordExecutionProfile(plan: Plan) {
        val profile = ExecutionProfile(
            planId = plan.id,
            startTime = plan.createdAt,
            endTime = System.currentTimeMillis(),
            stepDurations = plan.steps.mapNotNull { step ->
                step.executionTime?.let { step.id to it }
            }.toMap(),
            totalEnergy = plan.steps.sumOf { it.energyCost },
            efficiency = calculatePlanEfficiency(plan),
            bottlenecks = identifyBottlenecks(plan),
            parallelizationGains = calculateParallelizationGains(plan),
            rollbackCount = plan.steps.count { it.status == "failed" && it.retryCount > 0 },
            adaptations = emptyList()
        )
        
        executionProfiles[plan.id] = profile
    }
    
    private fun calculatePlanEfficiency(plan: Plan): Double {
        val actualDuration = plan.steps.mapNotNull { it.executionTime }.sum()
        val parallelizedDuration = plan.parallelizationOptions.maxOfOrNull { it.size }?.let {
            actualDuration / it.toDouble()
        } ?: actualDuration.toDouble()
        
        val maxPossible = plan.steps.size * 1000L
        return (parallelizedDuration / maxPossible).coerceIn(0.0, 1.0)
    }
    
    private fun identifyBottlenecks(plan: Plan): List<String> {
        val bottlenecks = mutableListOf<String>()
        
        val stepDurations = plan.steps.filter { it.executionTime != null }
            .associate { it.id to it.executionTime!! }
        
        if (stepDurations.isEmpty()) return bottlenecks
        
        val avgDuration = stepDurations.values.average()
        val threshold = avgDuration * 1.5
        
        stepDurations.filter { it.value > threshold }.forEach { (stepId, duration) ->
            val step = plan.steps.find { it.id == stepId }
            bottlenecks.add("${step?.description ?: stepId} (${duration}ms)")
        }
        
        if (plan.criticalPath.size > plan.steps.size / 2) {
            bottlenecks.add("Long critical path (${plan.criticalPath.size} steps)")
        }
        
        return bottlenecks
    }
    
    private fun calculateParallelizationGains(plan: Plan): Double {
        if (plan.parallelizationOptions.isEmpty()) return 0.0
        
        val sequentialTime = plan.steps.sumOf { it.complexity * 1000L }
        val parallelTime = plan.parallelizationOptions.sumOf { group ->
            group.maxOfOrNull { stepId ->
                plan.steps.find { it.id == stepId }?.complexity ?: 0
            }?.times(1000L) ?: 0L
        }
        
        return if (sequentialTime > 0) {
            ((sequentialTime - parallelTime).toDouble() / sequentialTime).coerceIn(0.0, 1.0)
        } else 0.0
    }
    
    fun getNextStep(planId: String): PlanStep? {
        val plan = plans[planId] ?: return null
        
        return plan.steps.firstOrNull { step ->
            step.status == "pending" && 
            step.dependencies.all { depId ->
                plan.steps.find { it.id == depId }?.status == "completed"
            }
        }
    }
    
    fun setFocus(focus: String?) {
        val previousFocus = cognitiveState.focus
        cognitiveState = cognitiveState.copy(
            focus = focus,
            lastUpdated = System.currentTimeMillis(),
            attentionFragmentation = if (previousFocus != null && previousFocus != focus) {
                cognitiveState.attentionFragmentation + 0.1
            } else cognitiveState.attentionFragmentation
        )
        
        updateCognitiveLoad()
        
        logger.info("Focus set to: $focus")
    }
    
    private fun updateCognitiveLoad() {
        val focusFactor = if (cognitiveState.focus != null) 0.8 else 1.2
        val questionFactor = 1.0 + cognitiveState.activeQuestions.size * 0.1
        val uncertaintyFactor = 1.0 + cognitiveState.uncertainty.size * 0.05
        
        cognitiveState = cognitiveState.copy(
            cognitiveLoad = (focusFactor * questionFactor * uncertaintyFactor / 2.0).coerceIn(0.0, 1.0),
            workingMemoryUsage = (cognitiveState.activeQuestions.size * 0.15 + 
                                  cognitiveState.uncertainty.size * 0.1).coerceIn(0.0, 1.0)
        )
    }
    
    fun updateEnergy(energy: Double) {
        val taskSwitchingCost = cognitiveState.taskSwitchingOverhead * 0.1
        cognitiveState = cognitiveState.copy(
            energy = (energy - taskSwitchingCost).coerceIn(0.0, 1.0),
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    fun addUncertainty(uncertainty: String) {
        cognitiveState = cognitiveState.copy(
            uncertainty = (cognitiveState.uncertainty + uncertainty).distinct().take(10),
            lastUpdated = System.currentTimeMillis()
        )
        updateCognitiveLoad()
    }
    
    fun resolveUncertainty(uncertainty: String) {
        cognitiveState = cognitiveState.copy(
            uncertainty = cognitiveState.uncertainty - uncertainty,
            lastUpdated = System.currentTimeMillis()
        )
        updateCognitiveLoad()
    }
    
    fun addQuestion(question: String) {
        cognitiveState = cognitiveState.copy(
            activeQuestions = (cognitiveState.activeQuestions + question).distinct().take(5),
            lastUpdated = System.currentTimeMillis()
        )
        updateCognitiveLoad()
    }
    
    fun getCognitiveState(): CognitiveState = cognitiveState
    
    fun prioritize(options: List<String>): List<String> {
        val patternScores = options.associateWith { option ->
            var score = 0.0
            
            decisions.values.takeLast(20).forEach { decision ->
                if (decision.question.contains(option, ignoreCase = true) || 
                    decision.decision == option) {
                    score += decision.decisionQuality
                }
            }
            
            score += (decisionPatterns[extractDecisionPattern(option)] ?: 0.0) * 0.1
            
            score
        }
        
        return options.sortedByDescending { patternScores[it] ?: 0.0 }
    }
    
    fun shouldContinue(): Boolean {
        return cognitiveState.energy > 0.3 && 
               cognitiveState.uncertainty.size < 5 &&
               cognitiveState.confidence > 0.3 &&
               cognitiveState.cognitiveLoad < 0.8
    }
    
    fun getDecisionQuality(decisionId: String): Double? {
        return decisions[decisionId]?.decisionQuality
    }
    
    fun recordDecisionOutcome(decisionId: String, outcome: String, satisfaction: Double): Boolean {
        val decision = decisions[decisionId] ?: return false
        
        val updated = decision.copy(
            outcome = outcome,
            satisfaction = satisfaction
        )
        
        decisions[decisionId] = updated
        
        val patternKey = extractDecisionPattern(decision.question)
        val patternSuccess = if (outcome == "success") 1.0 else -0.5
        decisionPatterns[patternKey] = (decisionPatterns[patternKey] ?: 0.0) + patternSuccess
        
        return true
    }
    
    fun getHeuristicsUsed(decisionId: String): List<HeuristicScore> {
        return heuristics[decisionId]?.toList() ?: emptyList()
    }
    
    fun getExecutionProfile(planId: String): ExecutionProfile? {
        return executionProfiles[planId]
    }
    
    fun formatDecision(decision: Decision): String {
        return buildString {
            appendLine("[Decision] ${decision.question}")
            appendLine("-".repeat(40))
            appendLine("Options: ${decision.options.joinToString(", ")}")
            appendLine("Decision: ${decision.decision}")
            appendLine("Confidence: ${(decision.confidence * 100).toInt()}%")
            appendLine("Quality: ${(decision.decisionQuality * 100).toInt()}%")
            
            if (decision.outcome != null) {
                appendLine("Outcome: ${decision.outcome}")
                appendLine("Satisfaction: ${(decision.satisfaction ?: 0.0 * 100).toInt()}%")
            }
            
            appendLine("\n[Reasoning]")
            appendLine(decision.reasoning)
        }
    }
    
    fun formatPlan(plan: Plan): String {
        val analytics = getPlanAnalytics(plan.id)
        
        return buildString {
            appendLine("[Plan] ${plan.name}")
            appendLine("ID: ${plan.id}")
            appendLine("Objective: ${plan.objective}")
            appendLine("Status: ${plan.status} | Risk: ${plan.riskLevel}")
            appendLine("Success Probability: ${(plan.successProbability * 100).toInt()}%")
            
            if (analytics != null) {
                appendLine("Estimated Duration: ${analytics.criticalPathDuration}ms")
                appendLine("Parallelization Score: ${(analytics.parallelizationScore * 100).toInt()}%")
                
                if (analytics.riskFactors.isNotEmpty()) {
                    appendLine("\n[Risk Factors]")
                    analytics.riskFactors.forEach { appendLine("  ! $it") }
                }
                
                if (analytics.resourceOptimization.isNotEmpty()) {
                    appendLine("\n[Optimizations]")
                    analytics.resourceOptimization.forEach { appendLine("  * $it") }
                }
            }
            
            if (plan.estimatedDuration != null) {
                appendLine("Estimated: ${plan.estimatedDuration}")
            }
            
            appendLine("\n[Steps]")
            plan.steps.forEach { step ->
                val statusIcon = when (step.status) {
                    "completed" -> "[DONE]"
                    "in_progress" -> "[RUNNING]"
                    "failed" -> "[FAILED]"
                    else -> "[PENDING]"
                }
                appendLine("  ${step.id}: $statusIcon ${step.description}")
                if (step.tool != null) appendLine("      Tool: ${step.tool}")
                if (step.executionTime != null) appendLine("      Time: ${step.executionTime}ms")
                if (step.result != null) appendLine("      Result: ${step.result?.take(50)}...")
            }
            
            if (plan.parallelizationOptions.isNotEmpty()) {
                appendLine("\n[Parallelization Opportunities]")
                plan.parallelizationOptions.forEachIndexed { index, group ->
                    appendLine("  Group ${index + 1}: ${group.joinToString(", ")}")
                }
            }
            
            if (plan.contingencyPlans.isNotEmpty()) {
                appendLine("\n[Contingency Plans]")
                plan.contingencyPlans.take(3).forEach { plan ->
                    appendLine("  - ${plan.triggerCondition}: ${plan.recoveryStrategy}")
                }
            }
        }
    }
    
    fun formatCognitiveState(): String {
        return buildString {
            appendLine("[Cognitive State]")
            appendLine("-".repeat(30))
            appendLine("Focus: ${cognitiveState.focus ?: "none"}")
            appendLine("Energy: ${(cognitiveState.energy * 100).toInt()}%")
            appendLine("Confidence: ${(cognitiveState.confidence * 100).toInt()}%")
            appendLine("Cognitive Load: ${(cognitiveState.cognitiveLoad * 100).toInt()}%")
            appendLine("Working Memory: ${(cognitiveState.workingMemoryUsage * 100).toInt()}%")
            appendLine("Attention Fragmentation: ${(cognitiveState.attentionFragmentation * 100).toInt()}%")
            
            if (cognitiveState.uncertainty.isNotEmpty()) {
                appendLine("\nUncertainties:")
                cognitiveState.uncertainty.forEach { appendLine("  ? $it") }
            }
            
            if (cognitiveState.activeQuestions.isNotEmpty()) {
                appendLine("\nActive Questions:")
                cognitiveState.activeQuestions.forEach { appendLine("  Q: $it") }
            }
        }
    }
    
    fun formatDecisionAnalytics(): String {
        return buildString {
            appendLine("[Decision Analytics]")
            appendLine("=".repeat(40))
            appendLine("Total decisions: ${decisions.size}")
            appendLine("Total plans: ${plans.size}")
            appendLine("Execution profiles: ${executionProfiles.size}")
            
            val recentDecisions = decisionHistory.takeLast(10)
            if (recentDecisions.isNotEmpty()) {
                val avgQuality = recentDecisions.map { it.decisionQuality }.average()
                appendLine("Recent avg quality: ${(avgQuality * 100).toInt()}%")
            }
            
            appendLine("\n[Decision Patterns]")
            decisionPatterns.entries.sortedByDescending { it.value }.take(5).forEach { (pattern, count) ->
                appendLine("  $pattern: ${count.toInt()} occurrences")
            }
            
            val completedPlans = executionProfiles.values.filter { it.endTime != null }
            if (completedPlans.isNotEmpty()) {
                appendLine("\n[Execution Efficiency]")
                val avgEfficiency = completedPlans.map { it.efficiency }.average()
                appendLine("  Average efficiency: ${(avgEfficiency * 100).toInt()}%")
                
                val avgParallelization = completedPlans.map { it.parallelizationGains }.average()
                appendLine("  Average parallelization: ${(avgParallelization * 100).toInt()}%")
            }
        }
    }
}
