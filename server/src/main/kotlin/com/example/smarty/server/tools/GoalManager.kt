package com.example.smarty.server.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    val strategies: List<String>
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
    
    fun createGoal(
        name: String,
        description: String,
        priority: Int = 5,
        targetDate: Long? = null,
        parentGoal: String? = null,
        strategies: List<String> = emptyList()
    ): String {
        val goalId = "goal_${name.lowercase().replace(" ", "_")}_${System.currentTimeMillis()}"
        
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
            strategies = strategies
        )
        
        goals[goalId] = goal
        
        if (parentGoal != null) {
            goals[parentGoal]?.let { parent ->
                goals[parentGoal] = parent.copy(subGoals = parent.subGoals + goalId)
            }
        }
        
        logger.info("Created goal: $name (priority: $priority)")
        return goalId
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
        
        goals[goalId] = goal.copy(
            progress = clampedProgress,
            status = newStatus,
            completedAt = completedAt
        )
        
        progressHistory.getOrPut(goalId) { mutableListOf() }.add(
            GoalProgress(goalId, clampedProgress, notes, System.currentTimeMillis())
        )
        
        if (newStatus == "completed" && goal.parentGoal != null) {
            recalculateParentProgress(goal.parentGoal)
        }
        
        logger.info("Updated goal $goalId: ${(clampedProgress * 100).toInt()}%")
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
            .maxByOrNull { it.priority }
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
        
        return true
    }
    
    fun suggestNextAction(): String {
        val topGoal = getTopPriorityGoal()
        val currentIntents = getCurrentIntents()
        
        return buildString {
            appendLine("[Suggested Next Action]")
            appendLine("=".repeat(40))
            
            if (topGoal != null) {
                appendLine("\n[Primary Goal] ${topGoal.name}")
                appendLine("Progress: ${(topGoal.progress * 100).toInt()}%")
                appendLine("Priority: ${topGoal.priority}")
                
                if (topGoal.blockers.isNotEmpty()) {
                    appendLine("\n[Blockers]")
                    topGoal.blockers.forEach { appendLine("  - $it") }
                    appendLine("\nSuggestion: Resolve blockers before proceeding")
                } else if (topGoal.strategies.isNotEmpty()) {
                    appendLine("\n[Available Strategies]")
                    topGoal.strategies.forEach { appendLine("  - $it") }
                }
            }
            
            if (currentIntents.isNotEmpty()) {
                appendLine("\n[Current Intents]")
                currentIntents.take(3).forEach {
                    appendLine("  - ${it.intent} (priority: ${it.priority})")
                }
            }
        }
    }
    
    fun formatGoal(goal: AgentGoal, depth: Int = 0): String {
        val indent = "  ".repeat(depth)
        
        return buildString {
            appendLine("${indent}[${goal.status.uppercase()}] ${goal.name}")
            appendLine("${indent}ID: ${goal.id}")
            appendLine("${indent}Description: ${goal.description}")
            appendLine("${indent}Progress: ${(goal.progress * 100).toInt()}% | Priority: ${goal.priority}")
            
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
            appendLine()
            
            rootGoals.sortedByDescending { it.priority }.forEach { goal ->
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
