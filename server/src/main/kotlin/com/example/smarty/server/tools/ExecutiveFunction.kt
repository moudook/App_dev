package com.example.smarty.server.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

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
    val decidedAt: Long?
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
    val riskLevel: String
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
    val result: String?
)

@Serializable
data class CognitiveState(
    val focus: String?,
    val energy: Double,
    val confidence: Double,
    val uncertainty: List<String>,
    val activeQuestions: List<String>,
    val lastUpdated: Long
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
    
    fun makeDecision(
        question: String,
        options: List<String>,
        criteria: Map<String, Int> = mapOf("feasibility" to 5, "impact" to 5, "effort" to 5),
        scores: Map<String, Map<String, Int>> = emptyMap()
    ): Decision {
        val decisionId = "dec_${System.currentTimeMillis()}_${question.hashCode()}"
        
        val weightedScores = if (scores.isEmpty()) {
            options.associateWith { option ->
                criteria.mapValues { (_, weight) ->
                    (5 + (0..3).random()) * weight
                }
            }
        } else {
            scores
        }
        
        val totalScores = weightedScores.mapValues { (_, criteriaScores) ->
            criteriaScores.entries.sumOf { (criterion, score) ->
                score * (criteria[criterion] ?: 1)
            }
        }
        
        val bestOption = totalScores.maxByOrNull { it.value }?.key ?: options.first()
        val maxScore = totalScores.values.maxOrNull() ?: 0
        val avgScore = totalScores.values.average()
        val confidence = if (maxScore > 0) maxScore / (avgScore + maxScore) else 0.5
        
        val reasoning = buildString {
            appendLine("Analyzed ${options.size} options across ${criteria.size} criteria.")
            appendLine("Top option '$bestOption' scored $maxScore (avg: ${"%.1f".format(avgScore)})")
            appendLine("Key differentiating factors: ")
            val bestCriteria = weightedScores[bestOption] ?: emptyMap()
            criteria.entries.sortedByDescending { it.value }.take(3).forEach { (criterion, weight) ->
                val score = bestCriteria[criterion] ?: 0
                appendLine("  - $criterion: $score (weight: $weight)")
            }
        }
        
        val decision = Decision(
            id = decisionId,
            question = question,
            options = options,
            criteria = criteria,
            scores = weightedScores,
            decision = bestOption,
            reasoning = reasoning,
            confidence = confidence,
            createdAt = System.currentTimeMillis(),
            decidedAt = System.currentTimeMillis()
        )
        
        decisions[decisionId] = decision
        logger.info("Decision made: $question -> $bestOption (confidence: ${(confidence * 100).toInt()}%)")
        
        return decision
    }
    
    fun createPlan(
        name: String,
        objective: String,
        steps: List<PlanStep>,
        estimatedDuration: String? = null,
        riskLevel: String = "medium"
    ): String {
        val planId = "plan_${name.lowercase().replace(" ", "_")}_${System.currentTimeMillis()}"
        
        val dependencies = mutableMapOf<String, List<String>>()
        steps.forEach { step ->
            if (step.dependencies.isNotEmpty()) {
                dependencies[step.id] = step.dependencies
            }
        }
        
        val plan = Plan(
            id = planId,
            name = name,
            objective = objective,
            steps = steps,
            status = "ready",
            createdAt = System.currentTimeMillis(),
            estimatedDuration = estimatedDuration,
            dependencies = dependencies,
            riskLevel = riskLevel
        )
        
        plans[planId] = plan
        logger.info("Created plan: $name with ${steps.size} steps")
        
        return planId
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
                result = null
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
        
        steps.add(PlanStep(
            id = "step_0",
            description = "Analyze objective and constraints",
            tool = null,
            args = null,
            estimatedTime = "30s",
            dependencies = emptyList(),
            status = "pending",
            result = null
        ))
        
        steps.add(PlanStep(
            id = "step_1",
            description = "Gather relevant information",
            tool = if (availableTools.contains("search_web")) "search_web" else null,
            args = mapOf("query" to objective),
            estimatedTime = "1m",
            dependencies = listOf("step_0"),
            status = "pending",
            result = null
        ))
        
        steps.add(PlanStep(
            id = "step_2",
            description = "Process and analyze gathered information",
            tool = if (availableTools.contains("analyze_data")) "analyze_data" else null,
            args = mapOf("data" to "\${prev}", "analysis" to "summary"),
            estimatedTime = "30s",
            dependencies = listOf("step_1"),
            status = "pending",
            result = null
        ))
        
        steps.add(PlanStep(
            id = "step_3",
            description = "Execute primary action",
            tool = availableTools.firstOrNull { it !in listOf("search_web", "analyze_data") },
            args = mapOf("input" to "\${prev}"),
            estimatedTime = "1m",
            dependencies = listOf("step_2"),
            status = "pending",
            result = null
        ))
        
        steps.add(PlanStep(
            id = "step_4",
            description = "Verify and report results",
            tool = null,
            args = null,
            estimatedTime = "30s",
            dependencies = listOf("step_3"),
            status = "pending",
            result = null
        ))
        
        return createPlan(
            name = "Adaptive: ${objective.take(20)}",
            objective = objective,
            steps = steps,
            riskLevel = if (constraints.contains("high stakes")) "high" else "medium"
        )
    }
    
    fun getPlan(planId: String): Plan? = plans[planId]
    
    fun updatePlanStep(planId: String, stepId: String, status: String, result: String?): Boolean {
        val plan = plans[planId] ?: return false
        
        val updatedSteps = plan.steps.map { step ->
            if (step.id == stepId) {
                step.copy(status = status, result = result)
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
        
        plans[planId] = plan.copy(steps = updatedSteps, status = newStatus)
        return true
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
        cognitiveState = cognitiveState.copy(
            focus = focus,
            lastUpdated = System.currentTimeMillis()
        )
        logger.info("Focus set to: $focus")
    }
    
    fun updateEnergy(energy: Double) {
        cognitiveState = cognitiveState.copy(
            energy = energy.coerceIn(0.0, 1.0),
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    fun addUncertainty(uncertainty: String) {
        cognitiveState = cognitiveState.copy(
            uncertainty = (cognitiveState.uncertainty + uncertainty).distinct().take(10),
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    fun resolveUncertainty(uncertainty: String) {
        cognitiveState = cognitiveState.copy(
            uncertainty = cognitiveState.uncertainty - uncertainty,
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    fun addQuestion(question: String) {
        cognitiveState = cognitiveState.copy(
            activeQuestions = (cognitiveState.activeQuestions + question).distinct().take(5),
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    fun getCognitiveState(): CognitiveState = cognitiveState
    
    fun prioritize(options: List<String>): List<String> {
        return options.sortedByDescending { option ->
            val hasDecision = decisions.values.any { 
                it.question.contains(option, ignoreCase = true) || 
                it.decision == option 
            }
            if (hasDecision) 1.0 else 0.5
        }
    }
    
    fun shouldContinue(): Boolean {
        return cognitiveState.energy > 0.3 && 
               cognitiveState.uncertainty.size < 5 &&
               cognitiveState.confidence > 0.3
    }
    
    fun formatDecision(decision: Decision): String {
        return buildString {
            appendLine("[Decision] ${decision.question}")
            appendLine("-".repeat(40))
            appendLine("Options: ${decision.options.joinToString(", ")}")
            appendLine("Decision: ${decision.decision}")
            appendLine("Confidence: ${(decision.confidence * 100).toInt()}%")
            appendLine("\n[Reasoning]")
            appendLine(decision.reasoning)
        }
    }
    
    fun formatPlan(plan: Plan): String {
        return buildString {
            appendLine("[Plan] ${plan.name}")
            appendLine("ID: ${plan.id}")
            appendLine("Objective: ${plan.objective}")
            appendLine("Status: ${plan.status} | Risk: ${plan.riskLevel}")
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
                if (step.result != null) appendLine("      Result: ${step.result?.take(50)}...")
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
}
