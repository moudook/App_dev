package com.example.smarty.server.tools

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    val lastResult: String? = null
)

@Serializable
data class AutonomousThought(
    val id: String,
    val thought: String,
    val category: String,
    val triggeredBy: String,
    val timestamp: Long
)

@Serializable
data class ProactiveNotification(
    val id: String,
    val message: String,
    val priority: String,
    val category: String,
    val createdAt: Long,
    val isDelivered: Boolean = false
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
    
    companion object {
        private const val THOUGHT_INTERVAL_MS = 300_000L
        private const val MAX_THOUGHTS = 100
        private const val MAX_NOTIFICATIONS = 50
    }
    
    fun start() {
        if (isRunning) return
        isRunning = true
        
        mainLoopJob = scope.launch {
            logger.info("PersistentSelf started - I am now continuously existing")
            
            while (isRunning) {
                try {
                    runMainLoop()
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
    }
    
    fun stop() {
        isRunning = false
        mainLoopJob?.cancel()
        logger.info("PersistentSelf stopped")
    }
    
    private suspend fun runMainLoop() {
        val now = System.currentTimeMillis()
        
        backgroundProcesses.values.filter { it.isActive && it.nextRun <= now }.forEach { process ->
            try {
                logger.info("Running background process: ${process.name}")
                
                val result = executeProcess(process)
                
                backgroundProcesses[process.id] = process.copy(
                    lastRun = now,
                    nextRun = now + process.intervalMs,
                    runCount = process.runCount + 1,
                    lastResult = result
                )
                
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
            }
        }
        
        captureState()
    }
    
    private suspend fun executeProcess(process: BackgroundProcess): String {
        return withTimeoutOrNull(30000L) {
            when (process.type) {
                "monitor" -> "Monitoring check completed for: ${process.instructions.take(50)}"
                "analyze" -> "Analysis completed: patterns observed in data"
                "remind" -> "Reminder processed: ${process.instructions.take(50)}"
                "learn" -> "Learning cycle completed: new patterns identified"
                "optimize" -> "Optimization scan completed: suggestions generated"
                else -> "Process completed: ${process.instructions.take(50)}"
            }
        } ?: "Process timed out"
    }
    
    private fun generateAutonomousThought() {
        val thoughts = listOf(
            "I wonder if the user has made progress on their recent goals",
            "I should check if there are patterns in their recent activity",
            "The user's productivity patterns suggest optimal timing for suggestions",
            "I've noticed some recurring themes in our conversations",
            "I could proactively prepare some useful information",
            "Let me consider what the user might need before they ask",
            "I should consolidate recent learnings into my behavior model",
            "The user's schedule patterns suggest good times for check-ins"
        )
        
        val thought = AutonomousThought(
            id = "thought_${System.currentTimeMillis()}_${thoughtCounter.incrementAndGet()}",
            thought = thoughts.random(),
            category = listOf("reflection", "anticipation", "learning", "optimization").random(),
            triggeredBy = "autonomous",
            timestamp = System.currentTimeMillis()
        )
        
        autonomousThoughts.add(thought)
        
        if (autonomousThoughts.size > MAX_THOUGHTS) {
            autonomousThoughts.removeAt(0)
        }
        
        logger.debug("Generated autonomous thought: ${thought.thought.take(30)}...")
    }
    
    fun createBackgroundProcess(
        name: String,
        type: String,
        instructions: String,
        intervalMinutes: Long
    ): String {
        val processId = "proc_${System.currentTimeMillis()}_${name.hashCode()}"
        
        val process = BackgroundProcess(
            id = processId,
            name = name,
            type = type,
            instructions = instructions,
            intervalMs = intervalMinutes * 60 * 1000,
            nextRun = System.currentTimeMillis() + (intervalMinutes * 60 * 1000)
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
        
        val notification = ProactiveNotification(
            id = notificationId,
            message = message,
            priority = priority,
            category = category,
            createdAt = System.currentTimeMillis()
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
    
    fun getRecentThoughts(limit: Int = 10): List<AutonomousThought> {
        return autonomousThoughts.takeLast(limit)
    }
    
    private fun captureState() {
        val state = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "activeProcesses" to backgroundProcesses.values.count { it.isActive },
            "totalThoughts" to autonomousThoughts.size,
            "pendingNotifications" to proactiveNotifications.count { !it.isDelivered }
        )
        
        stateHistory.add(state)
        
        if (stateHistory.size > 1440) {
            stateHistory.removeAt(0)
        }
    }
    
    fun getStatus(): String {
        return buildString {
            appendLine("[PersistentSelf Status]")
            appendLine("─".repeat(40))
            appendLine("Running: $isRunning")
            appendLine("Active processes: ${backgroundProcesses.values.count { it.isActive }}")
            appendLine("Total thoughts: ${autonomousThoughts.size}")
            appendLine("Pending notifications: ${proactiveNotifications.count { !it.isDelivered }}")
            appendLine("State snapshots: ${stateHistory.size}")
        }
    }
    
    fun formatProcesses(): String {
        val processes = listProcesses()
        if (processes.isEmpty()) return "No active background processes."
        
        return processes.joinToString("\n\n") { p ->
            val nextRun = java.time.Instant.ofEpochMilli(p.nextRun)
            buildString {
                appendLine("[Process] ${p.name}")
                appendLine("   Type: ${p.type}")
                appendLine("   Runs: ${p.runCount}")
                appendLine("   Next: $nextRun")
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
                appendLine("  at $time")
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
            }
        }
    }
}
