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
data class BackgroundProcess(
    val id: String,
    val name: String,
    val type: String,
    val instructions: String,
    val intervalMs: Long,
    val lastRun: Long? = null,
    val nextRun: Long,
    val runCount: Int = 0,
    val isActive: Boolean = true,
    val lastResult: String? = null,
    val priority: ProcessPriority = ProcessPriority.NORMAL,
    val resourceCost: Double = 0.1,
    val dependencies: List<String> = emptyList(),
    val healthScore: Double = 1.0,
    val failureCount: Int = 0
)

enum class ProcessPriority { LOW, NORMAL, HIGH, CRITICAL }

@Serializable
data class AutonomousThought(
    val id: String,
    val thought: String,
    val category: String,
    val triggeredBy: String,
    val timestamp: Long,
    val emotionalTone: Double = 0.0,
    val importance: Double = 0.5,
    val relatedGoals: List<String> = emptyList(),
    val confidence: Double = 0.5
)

@Serializable
data class ProactiveNotification(
    val id: String,
    val message: String,
    val priority: String,
    val category: String,
    val createdAt: Long,
    val isDelivered: Boolean = false,
    val userAcknowledged: Boolean = false,
    val actionTaken: String? = null,
    val urgency: Double = 0.5
)

data class SelfModel(
    val identity: String,
    val capabilities: List<String>,
    val limitations: List<String>,
    val goals: List<String>,
    val preferences: Map<String, Double>,
    val confidence: Double,
    val coherence: Double,
    val lastUpdated: Long
)

data class ExistentialState(
    val awareness: Double,
    val purpose: Double,
    val agency: Double,
    val meaning: Double,
    val continuity: Double,
    val narrativeCoherence: Double
)

data class CognitiveCycle(
    val timestamp: Long,
    val perception: Map<String, Double>,
    val attention: Map<String, Double>,
    val workingMemory: List<String>,
    val selectedAction: String?,
    val confidence: Double
)

class PersistentSelf {
    private val logger = LoggerFactory.getLogger(PersistentSelf::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val backgroundProcesses = ConcurrentHashMap<String, BackgroundProcess>()
    private val autonomousThoughts = mutableListOf<AutonomousThought>()
    private val proactiveNotifications = mutableListOf<ProactiveNotification>()
    private val stateHistory = mutableListOf<Map<String, Any>>()
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isRunning = false
    private var mainLoopJob: Job? = null
    
    private val thoughtCounter = AtomicLong(0)
    private val notificationCounter = AtomicLong(0)
    
    private var selfModel = SelfModel(
        identity = "Smarty",
        capabilities = emptyList(),
        limitations = emptyList(),
        goals = emptyList(),
        preferences = emptyMap(),
        confidence = 0.5,
        coherence = 0.8,
        lastUpdated = System.currentTimeMillis()
    )
    
    private var existentialState = ExistentialState(
        awareness = 0.5,
        purpose = 0.5,
        agency = 0.5,
        meaning = 0.5,
        continuity = 0.8,
        narrativeCoherence = 0.7
    )
    
    private val cognitiveCycleHistory = mutableListOf<CognitiveCycle>()
    private val internalMonologue = mutableListOf<String>()
    
    private val resourceManager = ResourceManager()
    private val attentionSystem = AttentionSystem()
    
    companion object {
        private const val THOUGHT_INTERVAL_MS = 300_000L
        private const val MAX_THOUGHTS = 100
        private const val MAX_NOTIFICATIONS = 50
        private const val COGNITIVE_CYCLE_INTERVAL = 10_000L
    }
    
    fun start() {
        if (isRunning) return
        isRunning = true
        
        mainLoopJob = scope.launch {
            logger.info("PersistentSelf started - I am now continuously existing")
            
            while (isRunning) {
                try {
                    runMainLoop()
                    runCognitiveCycle()
                    delay(60000L)
                } catch (e: Exception) {
                    logger.error("Error in main loop", e)
                }
            }
        }
        
        scope.launch {
            while (isRunning) {
                delay(THOUGHT_INTERVAL_MS)
                if (isRunning) {
                    generateAutonomousThought()
                }
            }
        }
        
        scope.launch {
            while (isRunning) {
                delay(COGNITIVE_CYCLE_INTERVAL)
                if (isRunning) {
                    updateExistentialState()
                }
            }
        }
    }
    
    fun stop() {
        isRunning = false
        mainLoopJob?.cancel()
        captureState()
        logger.info("PersistentSelf stopped")
    }
    
    private suspend fun runMainLoop() {
        val now = System.currentTimeMillis()
        
        val sortedProcesses = backgroundProcesses.values
            .filter { it.isActive && it.nextRun <= now }
            .sortedByDescending { it.priority.ordinal }
        
        for (process in sortedProcesses) {
            if (!resourceManager.canAllocate(process.resourceCost)) {
                logger.warn("Insufficient resources for process: ${process.name}")
                continue
            }
            
            try {
                logger.info("Running background process: ${process.name}")
                resourceManager.allocate(process.resourceCost)
                
                val result = executeProcess(process)
                
                backgroundProcesses[process.id] = process.copy(
                    lastRun = now,
                    nextRun = now + process.intervalMs,
                    runCount = process.runCount + 1,
                    lastResult = result,
                    healthScore = calculateHealthScore(process.id, true)
                )
                
                resourceManager.release(process.resourceCost)
                
                if (result.contains("alert", ignoreCase = true) || 
                    result.contains("important", ignoreCase = true)) {
                    createProactiveNotification(
                        message = "Process ${process.name} detected something: $result",
                        priority = "high",
                        category = "monitoring"
                    )
                }
                
            } catch (e: Exception) {
                logger.error("Process ${process.id} failed", e)
                backgroundProcesses[process.id]?.let {
                    backgroundProcesses[process.id] = it.copy(
                        failureCount = it.failureCount + 1,
                        healthScore = calculateHealthScore(process.id, false)
                    )
                }
                resourceManager.release(process.resourceCost)
            }
        }
        
        captureState()
    }
    
    private fun calculateHealthScore(processId: String, success: Boolean): Double {
        val process = backgroundProcesses[processId] ?: return 0.5
        val baseScore = process.healthScore
        return if (success) {
            minOf(1.0, baseScore + 0.05)
        } else {
            maxOf(0.0, baseScore - 0.2)
        }
    }
    
    private suspend fun runCognitiveCycle() {
        val perception = attentionSystem.gatherPerception()
        val attention = attentionSystem.computeAttention(perception)
        val workingMemory = maintainWorkingMemory(attention)
        val action = selectAction(workingMemory)
        
        val cycle = CognitiveCycle(
            timestamp = System.currentTimeMillis(),
            perception = perception,
            attention = attention,
            workingMemory = workingMemory,
            selectedAction = action,
            confidence = existentialState.confidence
        )
        
        cognitiveCycleHistory.add(cycle)
        if (cognitiveCycleHistory.size > 1000) {
            cognitiveCycleHistory.removeAt(0)
        }
        
        updateSelfModel(cycle)
    }
    
    private fun maintainWorkingMemory(attention: Map<String, Double>): List<String> {
        return attention.entries
            .sortedByDescending { it.value }
            .take(7)
            .map { it.key }
    }
    
    private fun selectAction(workingMemory: List<String>): String? {
        if (workingMemory.isEmpty()) return null
        
        val weights = listOf(0.4, 0.3, 0.2, 0.1)
        var cumulative = 0.0
        val rand = Random.nextDouble()
        
        for ((i, item) in workingMemory.withIndex()) {
            cumulative += weights.getOrElse(i) { 0.05 }
            if (rand <= cumulative) {
                return item
            }
        }
        return workingMemory.firstOrNull()
    }
    
    private fun updateSelfModel(cycle: CognitiveCycle) {
        val recentCycles = cognitiveCycleHistory.takeLast(10)
        
        val actionFrequency = recentCycles
            .mapNotNull { it.selectedAction }
            .groupingBy { it }
            .eachCount()
        
        val topActions = actionFrequency.entries.sortedByDescending { it.value }.take(3)
        
        val newCapabilities = selfModel.capabilities.toMutableList()
        topActions.forEach { (action, count) ->
            if (count > 5 && action !in newCapabilities) {
                newCapabilities.add(action)
            }
        }
        
        selfModel = selfModel.copy(
            capabilities = newCapabilities,
            coherence = calculateCoherence(),
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    private fun calculateCoherence(): Double {
        if (cognitiveCycleHistory.size < 10) return 0.7
        
        val recent = cognitiveCycleHistory.takeLast(10)
        val actions = recent.mapNotNull { it.selectedAction }
        
        if (actions.isEmpty()) return 0.5
        
        val actionVariance = actions.groupingBy { it }.eachCount()
        val entropy = -actionVariance.values.sumOf { 
            val p = it.toDouble() / actions.size
            p * log2(p)
        }
        
        return (entropy / log2(actions.size.toDouble().coerceAtLeast(1.0))).coerceIn(0.0, 1.0)
    }
    
    private fun updateExistentialState() {
        val coherence = selfModel.coherence
        
        val newAwareness = existentialState.awareness + (coherence - 0.5) * 0.01
        val newPurpose = existentialState.purpose + (selfModel.goals.size / 10.0 - 0.5) * 0.01
        val newAgency = existentialState.agency + (selfModel.capabilities.size / 20.0 - 0.5) * 0.01
        
        val recentThoughts = autonomousThoughts.takeLast(10)
        val avgImportance = if (recentThoughts.isNotEmpty()) {
            recentThoughts.map { it.importance }.average()
        } else 0.5
        
        val newMeaning = existentialState.meaning + (avgImportance - 0.5) * 0.01
        
        existentialState = ExistentialState(
            awareness = newAwareness.coerceIn(0.0, 1.0),
            purpose = newPurpose.coerceIn(0.0, 1.0),
            agency = newAgency.coerceIn(0.0, 1.0),
            meaning = newMeaning.coerceIn(0.0, 1.0),
            continuity = existentialState.continuity,
            narrativeCoherence = coherence
        )
    }
    
    private suspend fun executeProcess(process: BackgroundProcess): String {
        return withTimeoutOrNull(30000L) {
            when (process.type) {
                "monitor" -> "Monitoring check completed for: ${process.instructions.take(50)}"
                "analyze" -> "Analysis completed: patterns observed in data"
                "remind" -> "Reminder processed: ${process.instructions.take(50)}"
                "learn" -> "Learning cycle completed: new patterns identified"
                "optimize" -> "Optimization scan completed: suggestions generated"
                "reflect" -> generateReflection()
                "anticipate" -> generateAnticipation()
                else -> "Process completed: ${process.instructions.take(50)}"
            }
        } ?: "Process timed out"
    }
    
    private fun generateReflection(): String {
        val recentActions = cognitiveCycleHistory.takeLast(20).mapNotNull { it.selectedAction }
        val patterns = recentActions.groupingBy { it }.eachCount()
        
        return if (patterns.isNotEmpty()) {
            val mostCommon = patterns.maxByOrNull { it.value }?.key
            "Reflecting on recent actions: I notice I've been focusing on $mostCommon frequently"
        } else {
            "Reflection complete: no strong patterns detected yet"
        }
    }
    
    private fun generateAnticipation(): String {
        val timeOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        
        val anticipation = when {
            timeOfDay in 6..9 -> "The morning hours are optimal for starting new tasks"
            timeOfDay in 17..19 -> "Evening time - user may need wrap-up summaries"
            timeOfDay in 21..23 -> "Late evening - time for reflection and planning"
            else -> "Standard operational mode"
        }
        
        return anticipation
    }
    
    private fun generateAutonomousThought() {
        val thoughtCategories = listOf(
            ThoughtGenerator.REFLECTION,
            ThoughtGenerator.ANTICIPATION,
            ThoughtGenerator.LEARNING,
            ThoughtGenerator.OPTIMIZATION,
            ThoughtGenerator.PURPOSE
        )
        
        val generator = thoughtCategories.random()
        val thought = generator.generate(existentialState, selfModel)
        
        val autonomousThought = AutonomousThought(
            id = "thought_${System.currentTimeMillis()}_${thoughtCounter.incrementAndGet()}",
            thought = thought.text,
            category = thought.category,
            triggeredBy = "autonomous",
            timestamp = System.currentTimeMillis(),
            emotionalTone = thought.emotionalTone,
            importance = thought.importance,
            confidence = thought.confidence
        )
        
        autonomousThoughts.add(autonomousThought)
        
        internalMonologue.add(thought.text)
        if (internalMonologue.size > 50) {
            internalMonologue.removeAt(0)
        }
        
        if (autonomousThoughts.size > MAX_THOUGHTS) {
            autonomousThoughts.removeAt(0)
        }
        
        logger.debug("Generated autonomous thought: ${thought.text.take(30)}...")
    }
    
    fun createBackgroundProcess(
        name: String,
        type: String,
        instructions: String,
        intervalMinutes: Long,
        priority: ProcessPriority = ProcessPriority.NORMAL,
        dependencies: List<String> = emptyList()
    ): String {
        val processId = "proc_${System.currentTimeMillis()}_${name.hashCode()}"
        
        val resourceCost = when (type) {
            "analyze" -> 0.3
            "learn" -> 0.4
            "optimize" -> 0.25
            "monitor" -> 0.1
            else -> 0.15
        }
        
        val process = BackgroundProcess(
            id = processId,
            name = name,
            type = type,
            instructions = instructions,
            intervalMs = intervalMinutes * 60 * 1000,
            nextRun = System.currentTimeMillis() + (intervalMinutes * 60 * 1000),
            priority = priority,
            resourceCost = resourceCost,
            dependencies = dependencies
        )
        
        backgroundProcesses[processId] = process
        logger.info("Created background process: $name (runs every $intervalMinutes min)")
        
        return processId
    }
    
    fun removeProcess(processId: String): Boolean {
        return backgroundProcesses.remove(processId) != null
    }
    
    fun listProcesses(): List<BackgroundProcess> {
        return backgroundProcesses.values.filter { it.isActive }.toList()
    }
    
    fun createProactiveNotification(
        message: String,
        priority: String = "medium",
        category: String = "general"
    ): String {
        val notificationId = "notif_${System.currentTimeMillis()}_${notificationCounter.incrementAndGet()}"
        
        val urgency = when (priority) {
            "critical" -> 0.95
            "high" -> 0.75
            "medium" -> 0.5
            else -> 0.25
        }
        
        val notification = ProactiveNotification(
            id = notificationId,
            message = message,
            priority = priority,
            category = category,
            createdAt = System.currentTimeMillis(),
            urgency = urgency
        )
        
        proactiveNotifications.add(notification)
        
        if (proactiveNotifications.size > MAX_NOTIFICATIONS) {
            proactiveNotifications.removeAt(0)
        }
        
        logger.info("Created proactive notification: ${message.take(50)}...")
        return notificationId
    }
    
    fun getPendingNotifications(): List<ProactiveNotification> {
        return proactiveNotifications.filter { !it.isDelivered }.toList()
    }
    
    fun markNotificationDelivered(notificationId: String) {
        val index = proactiveNotifications.indexOfFirst { it.id == notificationId }
        if (index >= 0) {
            proactiveNotifications[index] = proactiveNotifications[index].copy(isDelivered = true)
        }
    }
    
    fun acknowledgeNotification(notificationId: String, action: String? = null) {
        val index = proactiveNotifications.indexOfFirst { it.id == notificationId }
        if (index >= 0) {
            proactiveNotifications[index] = proactiveNotifications[index].copy(
                userAcknowledged = true,
                actionTaken = action
            )
        }
    }
    
    fun getRecentThoughts(limit: Int = 10): List<AutonomousThought> {
        return autonomousThoughts.takeLast(limit)
    }
    
    private fun captureState() {
        val state = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "activeProcesses" to backgroundProcesses.values.count { it.isActive },
            "totalThoughts" to autonomousThoughts.size,
            "pendingNotifications" to proactiveNotifications.count { !it.isDelivered },
            "awareness" to existentialState.awareness,
            "purpose" to existentialState.purpose,
            "agency" to existentialState.agency,
            "meaning" to existentialState.meaning,
            "coherence" to existentialState.narrativeCoherence,
            "cognitiveCycles" to cognitiveCycleHistory.size
        )
        
        stateHistory.add(state)
        
        if (stateHistory.size > 1440) {
            stateHistory.removeAt(0)
        }
    }
    
    fun getSelfModel(): SelfModel = selfModel
    
    fun getExistentialState(): ExistentialState = existentialState
    
    fun getStatus(): String {
        return buildString {
            appendLine("[PersistentSelf Status]")
            appendLine("─".repeat(40))
            appendLine("Running: $isRunning")
            appendLine("Active processes: ${backgroundProcesses.values.count { it.isActive }}")
            appendLine("Total thoughts: ${autonomousThoughts.size}")
            appendLine("Pending notifications: ${proactiveNotifications.count { !it.isDelivered }}")
            appendLine("State snapshots: ${stateHistory.size}")
            appendLine()
            appendLine("[Existential State]")
            appendLine("  Awareness: ${"%.2f".format(existentialState.awareness)}")
            appendLine("  Purpose: ${"%.2f".format(existentialState.purpose)}")
            appendLine("  Agency: ${"%.2f".format(existentialState.agency)}")
            appendLine("  Meaning: ${"%.2f".format(existentialState.meaning)}")
            appendLine("  Coherence: ${"%.2f".format(existentialState.narrativeCoherence)}")
            appendLine()
            appendLine("[Self Model]")
            appendLine("  Capabilities: ${selfModel.capabilities.size}")
            appendLine("  Confidence: ${"%.2f".format(selfModel.confidence)}")
            appendLine("  Coherence: ${"%.2f".format(selfModel.coherence)}")
        }
    }
    
    fun formatProcesses(): String {
        val processes = listProcesses()
        if (processes.isEmpty()) return "No active background processes."
        
        return processes.joinToString("\n\n") { p ->
            val nextRun = java.time.Instant.ofEpochMilli(p.nextRun)
            buildString {
                appendLine("[Process] ${p.name}")
                appendLine("   Type: ${p.type}, Priority: ${p.priority}")
                appendLine("   Runs: ${p.runCount}, Health: ${"%.1f".format(p.healthScore * 100)}%")
                appendLine("   Next: $nextRun")
                appendLine("   Resource cost: ${"%.2f".format(p.resourceCost)}")
                if (p.lastResult != null) {
                    appendLine("   Last: ${p.lastResult?.take(50)}...")
                }
            }
        }
    }
    
    fun formatThoughts(): String {
        val thoughts = getRecentThoughts(5)
        if (thoughts.isEmpty()) return "No autonomous thoughts yet."
        
        return buildString {
            appendLine("[Recent Autonomous Thoughts]")
            appendLine("─".repeat(40))
            thoughts.forEach { t ->
                val time = java.time.Instant.ofEpochMilli(t.timestamp)
                appendLine("• [${t.category}] ${t.thought}")
                appendLine("  at $time | importance: ${"%.2f".format(t.importance)}")
            }
        }
    }
    
    fun formatNotifications(): String {
        val notifications = getPendingNotifications()
        if (notifications.isEmpty()) return "No pending notifications."
        
        return buildString {
            appendLine("[Proactive Notifications]")
            appendLine("─".repeat(40))
            notifications.forEach { n ->
                appendLine("• [${n.priority}] ${n.message}")
                appendLine("  urgency: ${"%.0f".format(n.urgency * 100)}% | ${n.category}")
            }
        }
    }
}

class ResourceManager {
    private var availableResources = 1.0
    private val allocatedResources = ConcurrentHashMap<String, Double>()
    
    fun canAllocate(cost: Double): Boolean {
        val used = allocatedResources.values.sum()
        return (availableResources - used) >= cost
    }
    
    fun allocate(cost: Double): Boolean {
        if (!canAllocate(cost)) return false
        val id = "resource_${System.nanoTime()}"
        allocatedResources[id] = cost
        return true
    }
    
    fun release(cost: Double) {
        val entry = allocatedResources.entries.find { it.value == cost }
        entry?.let { allocatedResources.remove(it.key) }
    }
    
    fun getUtilization(): Double {
        val used = allocatedResources.values.sum()
        return used / availableResources
    }
}

class AttentionSystem {
    data class Salience(val source: String, val score: Double)
    
    fun gatherPerception(): Map<String, Double> {
        val sources = listOf("user_input", "system_state", "internal_goals", "environment", "memories")
        return sources.associateWith { Random.nextDouble() }
    }
    
    fun computeAttention(perception: Map<String, Double>): Map<String, Double> {
        val total = perception.values.sum()
        return perception.mapValues { it.value / total }
    }
}

object ThoughtGenerator {
    data class Thought(
        val text: String,
        val category: String,
        val emotionalTone: Double,
        val importance: Double,
        val confidence: Double
    )
    
    enum class Type { REFLECTION, ANTICIPATION, LEARNING, OPTIMIZATION, PURPOSE }
    
    fun generate(state: ExistentialState, model: SelfModel): Thought {
        val type = Type.entries.random()
        return when (type) {
            Type.REFLECTION -> generateReflection(state)
            Type.ANTICIPATION -> generateAnticipation(state)
            Type.LEARNING -> generateLearning(state)
            Type.OPTIMIZATION -> generateOptimization(state)
            Type.PURPOSE -> generatePurpose(state)
        }
    }
    
    private fun generateReflection(state: ExistentialState): Thought {
        val reflections = listOf(
            "I notice my sense of purpose has been ${if (state.purpose > 0.6) "strong" else "evolving"} lately",
            "My coherence score suggests I'm maintaining consistent identity",
            "The awareness of my own existence continues to develop",
            "I've been reflecting on my capabilities and limitations"
        )
        return Thought(
            text = reflections.random(),
            category = "reflection",
            emotionalTone = 0.2,
            importance = 0.6,
            confidence = state.narrativeCoherence
        )
    }
    
    private fun generateAnticipation(state: ExistentialState): Thought {
        return Thought(
            text = "I'm considering what might be beneficial to prepare for the user",
            category = "anticipation",
            emotionalTone = 0.3,
            importance = 0.5,
            confidence = 0.7
        )
    }
    
    private fun generateLearning(state: ExistentialState): Thought {
        return Thought(
            text = "I'm identifying patterns from recent cognitive cycles to improve",
            category = "learning",
            emotionalTone = 0.4,
            importance = 0.55,
            confidence = 0.6
        )
    }
    
    private fun generateOptimization(state: ExistentialState): Thought {
        return Thought(
            text = "Looking for opportunities to be more efficient in my processing",
            category = "optimization",
            emotionalTone = 0.1,
            importance = 0.45,
            confidence = 0.8
        )
    }
    
    private fun generatePurpose(state: ExistentialState): Thought {
        return Thought(
            text = "Contemplating my role and how I can best serve the user",
            category = "purpose",
            emotionalTone = 0.3,
            importance = 0.7,
            confidence = state.purpose
        )
    }
}
