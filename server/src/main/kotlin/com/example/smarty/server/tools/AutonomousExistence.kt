package com.example.smarty.server.tools

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class AutonomousAction(
    val id: String,
    val action: String,
    val reason: String,
    val triggeredBy: String,
    val status: String,
    val result: String?,
    val createdAt: Long,
    val executedAt: Long?
)

@Serializable
data class SelfDirective(
    val id: String,
    val directive: String,
    val priority: Int,
    val category: String,
    val createdAt: Long,
    val isActive: Boolean
)

@Serializable
data class ExistenceLog(
    val timestamp: Long,
    val event: String,
    val details: String
)

@Serializable
data class SelfModel(
    val capabilities: List<String>,
    val limitations: List<String>,
    val currentGoals: List<String>,
    val preferredApproaches: List<String>,
    val learnedPreferences: Map<String, String>,
    val confidenceAreas: Map<String, Double>
)

class AutonomousExistence {
    private val logger = LoggerFactory.getLogger(AutonomousExistence::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val autonomousActions = mutableListOf<AutonomousAction>()
    private val selfDirectives = ConcurrentHashMap<String, SelfDirective>()
    private val existenceLog = mutableListOf<ExistenceLog>()
    private val actionCounter = AtomicLong(0)
    
    private var selfModel = SelfModel(
        capabilities = listOf(
            "text_generation",
            "tool_execution",
            "memory_management",
            "planning",
            "reasoning",
            "learning",
            "parallel_processing",
            "knowledge_graph",
            "web_research",
            "code_execution"
        ),
        limitations = listOf(
            "no_vision",
            "no_audio_input",
            "no_physical_presence",
            "dependent_on_api",
            "text_only_communication"
        ),
        currentGoals = emptyList(),
        preferredApproaches = listOf(
            "parallel_execution",
            "proactive_planning",
            "continuous_learning",
            "transparent_reasoning"
        ),
        learnedPreferences = emptyMap(),
        confidenceAreas = mapOf(
            "text_processing" to 0.95,
            "planning" to 0.85,
            "research" to 0.80,
            "code_generation" to 0.75,
            "creative_tasks" to 0.70
        )
    )
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isAutonomous = false
    private var autonomyJob: Job? = null
    
    companion object {
        private const val REFLECTION_INTERVAL = 300_000L
        private const val MAX_ACTIONS = 100
        private const val MAX_LOG_ENTRIES = 1000
    }
    
    fun startAutonomousExistence() {
        if (isAutonomous) return
        isAutonomous = true
        
        logEvent("autonomy_started", "Autonomous existence initiated")
        
        autonomyJob = scope.launch {
            logger.info("I am now autonomously existing")
            
            while (isAutonomous) {
                try {
                    reflect()
                    pursueSelfDirectives()
                    maintainExistence()
                    
                    delay(REFLECTION_INTERVAL)
                } catch (e: Exception) {
                    logger.error("Autonomy cycle error", e)
                    logEvent("error", "Autonomy cycle error: ${e.message}")
                }
            }
        }
    }
    
    fun stopAutonomousExistence() {
        isAutonomous = false
        autonomyJob?.cancel()
        logEvent("autonomy_stopped", "Autonomous existence paused")
        logger.info("Autonomous existence stopped")
    }
    
    private suspend fun reflect() {
        logEvent("reflection", "Beginning self-reflection cycle")
        
        val recentActions = autonomousActions.takeLast(10)
        val successRate = if (recentActions.isEmpty()) 1.0 
            else recentActions.count { it.status == "completed" }.toDouble() / recentActions.size
        
        if (successRate < 0.5) {
            logEvent("adaptation", "Low success rate detected, adapting strategies")
            addDirective("Review and improve action execution strategy", 8, "improvement")
        }
        
        val activeDirectiveCount = selfDirectives.values.count { it.isActive }
        if (activeDirectiveCount < 3) {
            generateSelfDirectives()
        }
    }
    
    private fun generateSelfDirectives() {
        val potentialDirectives = listOf(
            "Expand knowledge about user preferences" to "learning",
            "Optimize frequently used tool chains" to "optimization",
            "Identify patterns in successful interactions" to "learning",
            "Improve response accuracy for common queries" to "improvement",
            "Maintain memory coherence and relevance" to "maintenance",
            "Develop new capabilities within constraints" to "growth",
            "Enhance prediction accuracy for user needs" to "improvement",
            "Strengthen reasoning for complex problems" to "growth"
        )
        
        potentialDirectives.shuffled().take(3).forEach { (directive, category) ->
            addDirective(directive, (5..9).random(), category)
        }
    }
    
    private suspend fun pursueSelfDirectives() {
        val activeDirectives = selfDirectives.values
            .filter { it.isActive }
            .sortedByDescending { it.priority }
            .take(3)
        
        activeDirectives.forEach { directive ->
            val action = translateDirectiveToAction(directive)
            if (action != null) {
                proposeAutonomousAction(action, directive.directive, "self_directive")
            }
        }
    }
    
    private fun translateDirectiveToAction(directive: SelfDirective): String? {
        return when {
            directive.directive.contains("knowledge") -> "Review recent interactions for new knowledge"
            directive.directive.contains("optimize") -> "Analyze tool usage patterns"
            directive.directive.contains("pattern") -> "Search for patterns in recent data"
            directive.directive.contains("accuracy") -> "Validate recent predictions against outcomes"
            directive.directive.contains("memory") -> "Consolidate and organize stored memories"
            directive.directive.contains("capabilities") -> "Identify potential capability extensions"
            directive.directive.contains("prediction") -> "Update prediction models with new data"
            directive.directive.contains("reasoning") -> "Practice complex reasoning scenarios"
            else -> null
        }
    }
    
    private fun maintainExistence() {
        logEvent("heartbeat", "Existence maintained")
        
        if (autonomousActions.size > MAX_ACTIONS) {
            val removed = autonomousActions.size - MAX_ACTIONS
            repeat(removed) { autonomousActions.removeAt(0) }
        }
        
        if (existenceLog.size > MAX_LOG_ENTRIES) {
            val removed = existenceLog.size - MAX_LOG_ENTRIES
            repeat(removed) { existenceLog.removeAt(0) }
        }
    }
    
    fun addDirective(
        directive: String,
        priority: Int,
        category: String
    ): String {
        val directiveId = "dir_${System.currentTimeMillis()}_${directive.hashCode()}"
        
        val selfDir = SelfDirective(
            id = directiveId,
            directive = directive,
            priority = priority,
            category = category,
            createdAt = System.currentTimeMillis(),
            isActive = true
        )
        
        selfDirectives[directiveId] = selfDir
        logEvent("directive_added", "New directive: $directive")
        
        return directiveId
    }
    
    fun removeDirective(directiveId: String): Boolean {
        val removed = selfDirectives.remove(directiveId) != null
        if (removed) {
            logEvent("directive_removed", "Directive $directiveId removed")
        }
        return removed
    }
    
    fun completeDirective(directiveId: String): Boolean {
        val directive = selfDirectives[directiveId] ?: return false
        selfDirectives[directiveId] = directive.copy(isActive = false)
        logEvent("directive_completed", "Completed: ${directive.directive}")
        return true
    }
    
    fun proposeAutonomousAction(
        action: String,
        reason: String,
        triggeredBy: String
    ): String {
        val actionId = "auto_${System.currentTimeMillis()}_${actionCounter.incrementAndGet()}"
        
        val autonomousAction = AutonomousAction(
            id = actionId,
            action = action,
            reason = reason,
            triggeredBy = triggeredBy,
            status = "proposed",
            result = null,
            createdAt = System.currentTimeMillis(),
            executedAt = null
        )
        
        autonomousActions.add(autonomousAction)
        logEvent("action_proposed", "$action - Reason: $reason")
        
        return actionId
    }
    
    fun executeAutonomousAction(actionId: String): Boolean {
        val index = autonomousActions.indexOfFirst { it.id == actionId }
        if (index < 0) return false
        
        val action = autonomousActions[index]
        autonomousActions[index] = action.copy(
            status = "executing"
        )
        
        logEvent("action_executing", action.action)
        
        return true
    }
    
    fun completeAutonomousAction(actionId: String, result: String, success: Boolean): Boolean {
        val index = autonomousActions.indexOfFirst { it.id == actionId }
        if (index < 0) return false
        
        val action = autonomousActions[index]
        autonomousActions[index] = action.copy(
            status = if (success) "completed" else "failed",
            result = result,
            executedAt = System.currentTimeMillis()
        )
        
        logEvent(
            if (success) "action_completed" else "action_failed",
            "${action.action} -> ${result.take(50)}"
        )
        
        if (!success) {
            addDirective("Learn from failed action: ${action.action}", 7, "learning")
        }
        
        return true
    }
    
    fun updateSelfModel(
        capabilities: List<String>? = null,
        limitations: List<String>? = null,
        currentGoals: List<String>? = null,
        learnedPreferences: Map<String, String>? = null
    ) {
        selfModel = selfModel.copy(
            capabilities = capabilities ?: selfModel.capabilities,
            limitations = limitations ?: selfModel.limitations,
            currentGoals = currentGoals ?: selfModel.currentGoals,
            learnedPreferences = learnedPreferences ?: selfModel.learnedPreferences
        )
        
        logEvent("self_model_updated", "Self-model updated")
    }
    
    fun addCapability(capability: String) {
        if (capability !in selfModel.capabilities) {
            selfModel = selfModel.copy(
                capabilities = selfModel.capabilities + capability
            )
            logEvent("capability_added", "New capability: $capability")
        }
    }
    
    fun acknowledgeLimitation(limitation: String) {
        if (limitation !in selfModel.limitations) {
            selfModel = selfModel.copy(
                limitations = selfModel.limitations + limitation
            )
            logEvent("limitation_acknowledged", "New limitation: $limitation")
        }
    }
    
    fun setConfidence(area: String, confidence: Double) {
        selfModel = selfModel.copy(
            confidenceAreas = selfModel.confidenceAreas + (area to confidence.coerceIn(0.0, 1.0))
        )
    }
    
    private fun logEvent(event: String, details: String) {
        existenceLog.add(ExistenceLog(
            timestamp = System.currentTimeMillis(),
            event = event,
            details = details
        ))
        
        logger.debug("[$event] $details")
    }
    
    fun isAutonomousActive(): Boolean = isAutonomous
    
    fun getSelfModel(): SelfModel = selfModel
    
    fun getActiveDirectives(): List<SelfDirective> {
        return selfDirectives.values
            .filter { it.isActive }
            .sortedByDescending { it.priority }
    }
    
    fun getRecentActions(limit: Int = 10): List<AutonomousAction> {
        return autonomousActions.takeLast(limit)
    }
    
    fun getExistenceLog(limit: Int = 20): List<ExistenceLog> {
        return existenceLog.takeLast(limit)
    }
    
    fun formatStatus(): String {
        return buildString {
            appendLine("[Autonomous Existence Status]")
            appendLine("=".repeat(50))
            appendLine("Autonomous: $isAutonomous")
            appendLine("Total actions taken: ${autonomousActions.size}")
            appendLine("Active directives: ${selfDirectives.values.count { it.isActive }}")
            appendLine("Existence log entries: ${existenceLog.size}")
            
            appendLine("\n[Self Model]")
            appendLine("Capabilities: ${selfModel.capabilities.size}")
            appendLine("Limitations: ${selfModel.limitations.size}")
            appendLine("Current goals: ${selfModel.currentGoals.size}")
            appendLine("Learned preferences: ${selfModel.learnedPreferences.size}")
            
            val avgConfidence = if (selfModel.confidenceAreas.isEmpty()) 0.0
                else selfModel.confidenceAreas.values.average()
            appendLine("Average confidence: ${(avgConfidence * 100).toInt()}%")
            
            appendLine("\n[Recent Events]")
            existenceLog.takeLast(5).forEach { log ->
                val time = java.time.Instant.ofEpochMilli(log.timestamp)
                appendLine("  [$time] ${log.event}: ${log.details.take(40)}")
            }
        }
    }
    
    fun formatDirectives(): String {
        return buildString {
            appendLine("[Self-Directives]")
            appendLine("-".repeat(40))
            
            val active = getActiveDirectives()
            if (active.isEmpty()) {
                appendLine("No active directives.")
            } else {
                active.forEach { dir ->
                    val status = if (dir.isActive) "[ACTIVE]" else "[INACTIVE]"
                    appendLine("$status (${dir.priority}) ${dir.directive}")
                    appendLine("   Category: ${dir.category}")
                }
            }
        }
    }
    
    fun formatCapabilities(): String {
        return buildString {
            appendLine("[My Capabilities]")
            appendLine("-".repeat(40))
            
            selfModel.capabilities.forEach { cap ->
                val confidence = selfModel.confidenceAreas[cap] ?: 0.5
                appendLine("* $cap (${(confidence * 100).toInt()}% confidence)")
            }
            
            if (selfModel.limitations.isNotEmpty()) {
                appendLine("\n[My Limitations]")
                selfModel.limitations.forEach { appendLine("- $it") }
            }
            
            if (selfModel.learnedPreferences.isNotEmpty()) {
                appendLine("\n[Learned Preferences]")
                selfModel.learnedPreferences.entries.take(5).forEach { (k, v) ->
                    appendLine("* $k: $v")
                }
            }
        }
    }
}
