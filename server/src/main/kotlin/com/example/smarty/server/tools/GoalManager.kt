package com.example.smarty.server.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*
import kotlin.random.Random

@Serializable
data class AgentGoal(
    val id: String,
    val name: String,
    val description: String,
    val priority: Int,
    val status: String,
    val progress: Double,
    val subGoals: List<String>,
    val parentGoal: String?,
    val createdAt: Long,
    val targetDate: Long?,
    val completedAt: Long?,
    val blockers: List<String>,
    val metrics: Map<String, Double>,
    val strategies: List<String>,
    val estimatedDuration: Long = 0L,
    val actualDuration: Long = 0L,
    val confidence: Double = 1.0,
    val riskScore: Double = 0.0,
    val dependencies: List<String> = emptyList()
)

@Serializable
data class GoalProgress(
    val goalId: String,
    val progress: Double,
    val notes: String,
    val timestamp: Long
)

@Serializable
data class AgentIntent(
    val id: String,
    val intent: String,
    val priority: Int,
    val source: String,
    val relatedGoal: String?,
    val createdAt: Long
)

class GoalManager {
    private val logger = LoggerFactory.getLogger(GoalManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val goals = ConcurrentHashMap<String, AgentGoal>()
    private val progressHistory = ConcurrentHashMap<String, MutableList<GoalProgress>>()
    private val intentions = mutableListOf<AgentIntent>()
    private val intentCounter = AtomicLong(0)
    
    private val goalDecomposer = GoalDecomposer()
    private val priorityCalculator = PriorityCalculator()
    private val progressPredictor = ProgressPredictor()
    private val resourceAllocator = ResourceAllocator()
    private val criticalPathAnalyzer = CriticalPathAnalyzer()
    private val riskAssessor = RiskAssessor()
    private val monteCarloSimulator = MonteCarloSimulator()
    private val goalScheduler = GoalScheduler()
    private val dependencyGraph = GoalDependencyGraph()
    
    fun createGoal(
        name: String,
        description: String,
        priority: Int = 5,
        targetDate: Long? = null,
        parentGoal: String? = null,
        strategies: List<String> = emptyList(),
        dependencies: List<String> = emptyList()
    ): String {
        val goalId = "goal_${name.lowercase().replace(" ", "_")}_${System.currentTimeMillis()}"
        
        val estimatedDuration = estimateDuration(name, description)
        
        val goal = AgentGoal(
            id = goalId,
            name = name,
            description = description,
            priority = priority,
            status = "active",
            progress = 0.0,
            subGoals = emptyList(),
            parentGoal = parentGoal,
            createdAt = System.currentTimeMillis(),
            targetDate = targetDate,
            completedAt = null,
            blockers = emptyList(),
            metrics = emptyMap(),
            strategies = strategies,
            estimatedDuration = estimatedDuration,
            confidence = calculateInitialConfidence(priority),
            riskScore = 0.0,
            dependencies = dependencies
        )
        
        goals[goalId] = goal
        
        dependencies.forEach { depId ->
            dependencyGraph.addEdge(depId, goalId)
        }
        
        if (parentGoal != null) {
            goals[parentGoal]?.let { parent ->
                goals[parentGoal] = parent.copy(subGoals = parent.subGoals + goalId)
            }
        }
        
        val decomposed = goalDecomposer.decompose(goal)
        decomposed.forEach { subGoal ->
            goals[subGoal.id] = subGoal
        }
        
        logger.info("Created goal: $name (priority: $priority, estimated: ${estimatedDuration}ms)")
        return goalId
    }
    
    private fun estimateDuration(name: String, description: String): Long {
        val complexity = (name.length + description.length) / 50.0
        return (complexity * 3600000 * Random.nextDouble(0.5, 1.5)).toLong()
    }
    
    private fun calculateInitialConfidence(priority: Int): Double {
        return when (priority) {
            1, 2 -> 0.9
            3, 4 -> 0.75
            else -> 0.6
        }
    }
    
    fun setIntent(
        intent: String,
        priority: Int = 5,
        source: String = "self",
        relatedGoal: String? = null
    ): String {
        val intentId = "intent_${System.currentTimeMillis()}_${intentCounter.incrementAndGet()}"
        
        val agentIntent = AgentIntent(
            id = intentId,
            intent = intent,
            priority = priority,
            source = source,
            relatedGoal = relatedGoal,
            createdAt = System.currentTimeMillis()
        )
        
        intentions.add(agentIntent)
        
        if (intentions.size > 20) {
            intentions.removeAt(0)
        }
        
        logger.info("Set intent: $intent (priority: $priority)")
        return intentId
    }
    
    fun getCurrentIntents(): List<AgentIntent> {
        return intentions.sortedByDescending { it.priority }.take(5)
    }
    
    fun updateProgress(
        goalId: String,
        progress: Double,
        notes: String = ""
    ): Boolean {
        val goal = goals[goalId] ?: return false
        
        val clampedProgress = progress.coerceIn(0.0, 1.0)
        val newStatus = when {
            clampedProgress >= 1.0 -> "completed"
            clampedProgress > 0.0 -> "in_progress"
            else -> "active"
        }
        
        val completedAt = if (newStatus == "completed") System.currentTimeMillis() else null
        val actualDuration = if (newStatus == "completed") {
            System.currentTimeMillis() - goal.createdAt
        } else goal.actualDuration
        
        val confidence = progressPredictor.predictConfidence(goal, clampedProgress)
        val riskScore = riskAssessor.assessRisk(goal, clampedProgress)
        
        goals[goalId] = goal.copy(
            progress = clampedProgress,
            status = newStatus,
            completedAt = completedAt,
            actualDuration = actualDuration,
            confidence = confidence,
            riskScore = riskScore
        )
        
        progressHistory.getOrPut(goalId) { mutableListOf() }.add(
            GoalProgress(goalId, clampedProgress, notes, System.currentTimeMillis())
        )
        
        if (newStatus == "completed" && goal.parentGoal != null) {
            recalculateParentProgress(goal.parentGoal)
        }
        
        resourceAllocator.updateAllocation(goalId, clampedProgress)
        
        logger.info("Updated goal $goalId: ${(clampedProgress * 100).toInt()}% (confidence: ${"%.1f".format(confidence * 100)}%)")
        return true
    }
    
    private fun recalculateParentProgress(parentId: String) {
        val parent = goals[parentId] ?: return
        
        if (parent.subGoals.isEmpty()) return
        
        val subProgresses = parent.subGoals.mapNotNull { goals[it]?.progress }
        if (subProgresses.isEmpty()) return
        
        val avgProgress = subProgresses.average()
        updateProgress(parentId, avgProgress, "Auto-calculated from sub-goals")
    }
    
    fun addBlocker(goalId: String, blocker: String): Boolean {
        val goal = goals[goalId] ?: return false
        goals[goalId] = goal.copy(
            blockers = goal.blockers + blocker,
            status = "blocked"
        )
        
        riskAssessor.recordBlocker(goalId, blocker)
        
        logger.warn("Added blocker to $goalId: $blocker")
        return true
    }
    
    fun removeBlocker(goalId: String, blocker: String): Boolean {
        val goal = goals[goalId] ?: return false
        val newBlockers = goal.blockers - blocker
        goals[goalId] = goal.copy(
            blockers = newBlockers,
            status = if (newBlockers.isEmpty()) "active" else "blocked"
        )
        return true
    }
    
    fun setMetric(goalId: String, metricName: String, value: Double): Boolean {
        val goal = goals[goalId] ?: return false
        goals[goalId] = goal.copy(
            metrics = goal.metrics + (metricName to value)
        )
        return true
    }
    
    fun addStrategy(goalId: String, strategy: String): Boolean {
        val goal = goals[goalId] ?: return false
        goals[goalId] = goal.copy(strategies = goal.strategies + strategy)
        return true
    }
    
    fun getActiveGoals(): List<AgentGoal> {
        return goals.values
            .filter { it.status in listOf("active", "in_progress", "blocked") }
            .sortedByDescending { it.priority }
    }
    
    fun getTopPriorityGoal(): AgentGoal? {
        return goals.values
            .filter { it.status in listOf("active", "in_progress") }
            .maxByOrNull { priorityCalculator.calculateEffectivePriority(it) }
    }
    
    fun getGoal(goalId: String): AgentGoal? = goals[goalId]
    
    fun deleteGoal(goalId: String): Boolean {
        val goal = goals.remove(goalId) ?: return false
        
        goal.subGoals.forEach { subGoalId ->
            goals.remove(subGoalId)
        }
        
        if (goal.parentGoal != null) {
            goals[goal.parentGoal]?.let { parent ->
                goals[goal.parentGoal] = parent.copy(
                    subGoals = parent.subGoals - goalId
                )
            }
        }
        
        dependencyGraph.removeNode(goalId)
        
        return true
    }
    
    fun suggestNextAction(): String {
        val topGoal = getTopPriorityGoal()
        val currentIntents = getCurrentIntents()
        val criticalPath = criticalPathAnalyzer.findCriticalPath(goals.values.toList())
        
        return buildString {
            appendLine("[Suggested Next Action]")
            appendLine("=".repeat(40))
            
            if (topGoal != null) {
                appendLine("\n[Primary Goal] ${topGoal.name}")
                appendLine("Progress: ${(topGoal.progress * 100).toInt()}%")
                appendLine("Priority: ${topGoal.priority}")
                appendLine("Confidence: ${"%.1f".format(topGoal.confidence * 100)}%")
                appendLine("Risk Score: ${"%.2f".format(topGoal.riskScore)}")
                
                val predictedCompletion = progressPredictor.predictCompletion(topGoal)
                appendLine("Predicted Completion: $predictedCompletion")
                
                if (topGoal.blockers.isNotEmpty()) {
                    appendLine("\n[Blockers]")
                    topGoal.blockers.forEach { appendLine("  - $it") }
                    appendLine("\nSuggestion: Resolve blockers before proceeding")
                } else if (topGoal.strategies.isNotEmpty()) {
                    appendLine("\n[Available Strategies]")
                    topGoal.strategies.forEach { appendLine("  - $it") }
                }
            }
            
            if (criticalPath.isNotEmpty()) {
                appendLine("\n[Critical Path]")
                criticalPath.forEach { appendLine("  -> ${goals[it]?.name ?: it}") }
            }
            
            if (currentIntents.isNotEmpty()) {
                appendLine("\n[Current Intents]")
                currentIntents.take(3).forEach {
                    appendLine("  - ${it.intent} (priority: ${it.priority})")
                }
            }
            
            val simulation = monteCarloSimulator.simulate(getActiveGoals(), 1000)
            appendLine("\n[Monte Carlo Prediction]")
            appendLine("  Success Probability: ${"%.1f".format(simulation.successProbability * 100)}%")
            appendLine("  Expected Completion: ${simulation.expectedCompletionDays} days")
        }
    }
    
    fun getCriticalPath(): List<String> {
        return criticalPathAnalyzer.findCriticalPath(goals.values.toList())
    }
    
    fun getResourceAllocation(): Map<String, Double> {
        return resourceAllocator.getAllocations()
    }
    
    fun analyzeRisks(): List<RiskAnalysis> {
        return goals.values.map { goal ->
            riskAssessor.analyze(goal)
        }.sortedByDescending { it.score }
    }
    
    fun formatGoal(goal: AgentGoal, depth: Int = 0): String {
        val indent = "  ".repeat(depth)
        
        return buildString {
            appendLine("${indent}[${goal.status.uppercase()}] ${goal.name}")
            appendLine("${indent}ID: ${goal.id}")
            appendLine("${indent}Description: ${goal.description}")
            appendLine("${indent}Progress: ${(goal.progress * 100).toInt()}% | Priority: ${goal.priority}")
            appendLine("${indent}Confidence: ${"%.1f".format(goal.confidence * 100)}% | Risk: ${"%.2f".format(goal.riskScore)}")
            
            if (goal.blockers.isNotEmpty()) {
                appendLine("${indent}Blockers: ${goal.blockers.joinToString(", ")}")
            }
            
            if (goal.metrics.isNotEmpty()) {
                appendLine("${indent}Metrics: ${goal.metrics.entries.joinToString { "${it.key}=${it.value}" }}")
            }
            
            if (goal.targetDate != null) {
                val target = java.time.Instant.ofEpochMilli(goal.targetDate)
                appendLine("${indent}Target: $target")
            }
            
            if (goal.dependencies.isNotEmpty()) {
                appendLine("${indent}Dependencies: ${goal.dependencies.joinToString(", ")}")
            }
            
            goal.subGoals.forEach { subId ->
                goals[subId]?.let { subGoal ->
                    appendLine(formatGoal(subGoal, depth + 1))
                }
            }
        }
    }
    
    fun formatAllGoals(): String {
        val rootGoals = goals.values.filter { it.parentGoal == null }
        
        return buildString {
            appendLine("[Agent Goals]")
            appendLine("=".repeat(50))
            
            val active = goals.values.count { it.status == "active" || it.status == "in_progress" }
            val completed = goals.values.count { it.status == "completed" }
            val blocked = goals.values.count { it.status == "blocked" }
            
            appendLine("\nActive: $active | Completed: $completed | Blocked: $blocked")
            
            val criticalPath = criticalPathAnalyzer.findCriticalPath(goals.values.toList())
            if (criticalPath.isNotEmpty()) {
                appendLine("\nCritical Path Length: ${criticalPath.size} goals")
            }
            
            val avgProgress = if (goals.values.isNotEmpty()) {
                goals.values.map { it.progress }.average()
            } else 0.0
            appendLine("Average Progress: ${"%.1f".format(avgProgress * 100)}%")
            appendLine()
            
            rootGoals.sortedByDescending { priorityCalculator.calculateEffectivePriority(it) }.forEach { goal ->
                appendLine(formatGoal(goal))
                appendLine()
            }
        }
    }
    
    fun formatIntents(): String {
        return buildString {
            appendLine("[Current Intentions]")
            appendLine("-".repeat(30))
            
            if (intentions.isEmpty()) {
                appendLine("No active intentions.")
            } else {
                getCurrentIntents().forEach {
                    appendLine("* ${it.intent}")
                    appendLine("  Priority: ${it.priority} | Source: ${it.source}")
                    if (it.relatedGoal != null) {
                        goals[it.relatedGoal]?.let { g ->
                            appendLine("  Related: ${g.name}")
                        }
                    }
                }
            }
        }
    }
}

class GoalDecomposer {
    fun decompose(goal: AgentGoal): List<AgentGoal> {
        val subGoals = mutableListOf<AgentGoal>()
        
        val complexity = (goal.name.length + goal.description.length) / 100
        
        if (complexity > 2) {
            val parts = goal.description.split(".").filter { it.isNotBlank() }
            
            parts.forEachIndexed { index, part ->
                val subGoal = AgentGoal(
                    id = "${goal.id}_sub_$index",
                    name = "Sub-goal ${index + 1}: ${part.take(30)}",
                    description = part,
                    priority = goal.priority - 1,
                    status = "active",
                    progress = 0.0,
                    subGoals = emptyList(),
                    parentGoal = goal.id,
                    createdAt = System.currentTimeMillis(),
                    targetDate = null,
                    completedAt = null,
                    blockers = emptyList(),
                    metrics = emptyMap(),
                    strategies = emptyList(),
                    estimatedDuration = goal.estimatedDuration / parts.size,
                    confidence = goal.confidence * 0.9,
                    riskScore = goal.riskScore * 1.1,
                    dependencies = if (index > 0) listOf("${goal.id}_sub_${index - 1}") else emptyList()
                )
                subGoals.add(subGoal)
            }
        }
        
        return subGoals
    }
}

class PriorityCalculator {
    fun calculateEffectivePriority(goal: AgentGoal): Double {
        val basePriority = goal.priority.toDouble()
        
        val progressFactor = 1.0 - goal.progress
        val urgencyFactor = if (goal.targetDate != null) {
            val timeRemaining = goal.targetDate - System.currentTimeMillis()
            when {
                timeRemaining < 0 -> 2.0
                timeRemaining < 3600000 -> 1.5
                timeRemaining < 86400000 -> 1.2
                else -> 1.0
            }
        } else 1.0
        
        val riskFactor = 1.0 + goal.riskScore
        
        val blockerPenalty = if (goal.blockers.isNotEmpty()) 0.5 else 1.0
        
        return basePriority * progressFactor * urgencyFactor * riskFactor * blockerPenalty
    }
}

class ProgressPredictor {
    private val historicalData = mutableMapOf<String, MutableList<ProgressPoint>>()
    
    fun predictConfidence(goal: AgentGoal, currentProgress: Double): Double {
        val history = progressHistory[goal.id] ?: return goal.confidence
        
        if (history.size < 3) return goal.confidence
        
        val velocities = mutableListOf<Double>()
        for (i in 1 until history.size) {
            val timeDelta = history[i].timestamp - history[i - 1].timestamp
            val progressDelta = history[i].progress - history[i - 1].progress
            if (timeDelta > 0) {
                velocities.add(progressDelta / timeDelta)
            }
        }
        
        if (velocities.isEmpty()) return goal.confidence
        
        val avgVelocity = velocities.average()
        val remainingProgress = 1.0 - currentProgress
        val estimatedTimeRemaining = if (avgVelocity > 0) remainingProgress / avgVelocity else Double.MAX_VALUE
        
        val confidence = when {
            estimatedTimeRemaining < 86400000 -> 0.9
            estimatedTimeRemaining < 172800000 -> 0.75
            estimatedTimeRemaining < 604800000 -> 0.6
            else -> 0.4
        }
        
        return confidence
    }
    
    fun predictCompletion(goal: AgentGoal): String {
        if (goal.progress >= 1.0) return "Completed"
        if (goal.progress == 0.0) return "Unknown"
        
        val history = progressHistory[goal.id] ?: return "Insufficient data"
        
        val velocities = mutableListOf<Double>()
        for (i in 1 until history.size) {
            val timeDelta = (history[i].timestamp - history[i - 1].timestamp).toDouble()
            val progressDelta = history[i].progress - history[i - 1].progress
            if (timeDelta > 0) {
                velocities.add(progressDelta / timeDelta)
            }
        }
        
        if (velocities.isEmpty() || velocities.average() <= 0) return "Stalled"
        
        val avgVelocity = velocities.average()
        val remaining = 1.0 - goal.progress
        val estimatedMs = remaining / avgVelocity
        
        val days = (estimatedMs / 86400000).toInt()
        val hours = ((estimatedMs % 86400000) / 3600000).toInt()
        
        return "${days}d ${hours}h"
    }
    
    data class ProgressPoint(
        val progress: Double,
        val timestamp: Long
    )
}

class ResourceAllocator {
    private val allocations = ConcurrentHashMap<String, Double>()
    private val totalResources = 100.0
    
    fun updateAllocation(goalId: String, progress: Double) {
        val baseAllocation = totalResources / 10
        val progressBonus = progress * 10
        val remaining = totalResources - allocations.values.sum()
        
        allocations[goalId] = (baseAllocation + progressBonus).coerceAtMost(remaining)
    }
    
    fun getAllocations(): Map<String, Double> = allocations.toMap()
    
    fun getTotalAllocated(): Double = allocations.values.sum()
}

class CriticalPathAnalyzer {
    fun findCriticalPath(goals: List<AgentGoal>): List<String> {
        val graph = mutableMapOf<String, MutableList<String>>()
        
        goals.forEach { goal ->
            graph.getOrPut(goal.id) { mutableListOf() }
            goal.dependencies.forEach { dep ->
                graph.getOrPut(dep) { mutableListOf() }.add(goal.id)
            }
            goal.subGoals.forEach { subId ->
                graph.getOrPut(goal.id) { mutableListOf() }.add(subId)
                graph.getOrPut(subId) { mutableListOf() }.add(goal.id)
            }
        }
        
        val criticalPath = mutableListOf<String>()
        var current = goals.filter { it.dependencies.isEmpty() }.maxByOrNull { it.estimatedDuration }
        
        while (current != null) {
            criticalPath.add(current.id)
            
            val dependents = graph[current.id] ?: emptyList()
            current = dependents.mapNotNull { goals.find { g -> g.id == it } }
                .filter { it.status != "completed" }
                .maxByOrNull { it.estimatedDuration }
        }
        
        return criticalPath
    }
}

class RiskAssessor {
    private val riskFactors = mutableMapOf<String, MutableList<String>>()
    
    fun assessRisk(goal: AgentGoal, currentProgress: Double): Double {
        var risk = 0.0
        
        if (goal.blockers.isNotEmpty()) {
            risk += goal.blockers.size * 0.2
        }
        
        if (goal.targetDate != null) {
            val timeRemaining = goal.targetDate - System.currentTimeMillis()
            val estimatedRemaining = (1.0 - currentProgress) * goal.estimatedDuration
            if (estimatedRemaining > timeRemaining && timeRemaining > 0) {
                risk += 0.3
            }
        }
        
        if (goal.confidence < 0.5) {
            risk += (0.5 - goal.confidence) * 0.5
        }
        
        if (goal.dependencies.isNotEmpty()) {
            risk += goal.dependencies.size * 0.1
        }
        
        return risk.coerceIn(0.0, 1.0)
    }
    
    fun recordBlocker(goalId: String, blocker: String) {
        riskFactors.getOrPut(goalId) { mutableListOf() }.add(blocker)
    }
    
    fun analyze(goal: AgentGoal): RiskAnalysis {
        val factors = mutableListOf<String>()
        
        if (goal.blockers.isNotEmpty()) {
            factors.add("Has ${goal.blockers.size} blockers")
        }
        
        if (goal.targetDate != null && goal.targetDate < System.currentTimeMillis()) {
            factors.add("Past target date")
        }
        
        if (goal.confidence < 0.6) {
            factors.add("Low confidence: ${"%.0f".format(goal.confidence * 100)}%")
        }
        
        if (goal.riskScore > 0.5) {
            factors.add("High risk score: ${"%.2f".format(goal.riskScore)}")
        }
        
        return RiskAnalysis(
            goalId = goal.id,
            goalName = goal.name,
            score = goal.riskScore,
            factors = factors
        )
    }
    
    data class RiskAnalysis(
        val goalId: String,
        val goalName: String,
        val score: Double,
        val factors: List<String>
    )
}

class MonteCarloSimulator {
    fun simulate(goals: List<AgentGoal>, iterations: Int): SimulationResult {
        var successCount = 0
        val completionTimes = mutableListOf<Long>()
        
        repeat(iterations) {
            var allCompleted = true
            var totalTime = 0L
            
            goals.filter { it.status != "completed" }.forEach { goal ->
                val success = Random.nextDouble() < goal.confidence
                if (!success) {
                    allCompleted = false
                }
                
                val variance = Random.nextDouble() * 0.5 + 0.75
                totalTime += (goal.estimatedDuration * variance).toLong()
            }
            
            if (allCompleted) successCount++
            completionTimes.add(totalTime)
        }
        
        return SimulationResult(
            successProbability = successCount.toDouble() / iterations,
            expectedCompletionDays = completionTimes.average() / 86400000,
            completionVariance = completionTimes.map { (it - completionTimes.average()).let { d -> d * d } }.average()
        )
    }
    
    data class SimulationResult(
        val successProbability: Double,
        val expectedCompletionDays: Double,
        val completionVariance: Double
    )
}

class GoalScheduler {
    private val schedule = mutableMapOf<String, ScheduledSlot>()
    
    fun schedule(goalId: String, startTime: Long, duration: Long) {
        schedule[goalId] = ScheduledSlot(goalId, startTime, startTime + duration)
    }
    
    fun getSchedule(): Map<String, ScheduledSlot> = schedule.toMap()
    
    fun findSlot(duration: Long, after: Long = System.currentTimeMillis()): Long {
        val sortedSlots = schedule.values.sortedBy { it.startTime }
        var candidate = after
        
        for (slot in sortedSlots) {
            if (candidate + duration <= slot.startTime) {
                return candidate
            }
            candidate = maxOf(candidate, slot.endTime)
        }
        
        return candidate
    }
    
    data class ScheduledSlot(
        val goalId: String,
        val startTime: Long,
        val endTime: Long
    )
}

class GoalDependencyGraph {
    private val edges = mutableMapOf<String, MutableList<String>>()
    private val reverseEdges = mutableMapOf<String, MutableList<String>>()
    
    fun addEdge(from: String, to: String) {
        edges.getOrPut(from) { mutableListOf() }.add(to)
        reverseEdges.getOrPut(to) { mutableListOf() }.add(from)
    }
    
    fun removeNode(node: String) {
        edges.remove(node)
        reverseEdges.remove(node)
        edges.values.forEach { it.remove(node) }
        reverseEdges.values.forEach { it.remove(node) }
    }
    
    fun getDependencies(node: String): List<String> = reverseEdges[node] ?: emptyList()
    
    fun getDependents(node: String): List<String> = edges[node] ?: emptyList()
    
    fun topologicalSort(): List<String> {
        val result = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        val temp = mutableSetOf<String>()
        
        fun visit(node: String) {
            if (node in temp) return
            if (node in visited) return
            
            temp.add(node)
            edges[node]?.forEach { visit(it) }
            temp.remove(node)
            visited.add(node)
            result.add(node)
        }
        
        edges.keys.forEach { visit(it) }
        
        return result.reversed()
    }
}
