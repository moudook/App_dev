package com.example.smarty.server.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class ServerHealthMetrics(
    val uptimeMs: Long,
    val memoryUsed: Long,
    val memoryTotal: Long,
    val memoryPercent: Double,
    val threadCount: Int,
    val activeConnections: Int,
    val toolCallsTotal: Long,
    val toolCallsPerMinute: Double,
    val errorRate: Double,
    val lastError: String?,
    val lastErrorTime: Long?,
    val gcCount: Int,
    val heapUsedAfterGC: Long,
    val healthScore: Double
)

@Serializable
data class ProcessSnapshot(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val metrics: ServerHealthMetrics,
    val activeAgents: Int,
    val pendingToolCalls: Int,
    val messageQueueSize: Int,
    val state: String
)

@Serializable
data class RecoveryCheckpoint(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val activeAgents: Map<String, AgentState>,
    val pendingMessages: List<String>,
    val lastProcessedIndex: Long,
    val metadata: Map<String, String>
)

@Serializable
data class AgentState(
    val agentId: String,
    val state: String,
    val lastTool: String?,
    val currentTask: String?,
    val messageCount: Int,
    val lastActivity: Long,
    val healthStatus: String
)

class ServerResilienceManager(
    private val checkpointDir: String = "./data/resilience"
) {
    private val logger = LoggerFactory.getLogger(ServerResilienceManager::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    private val isRunning = AtomicBoolean(true)
    private val startTime = AtomicLong(System.currentTimeMillis())
    
    private val snapshots = ConcurrentLinkedQueue<ProcessSnapshot>()
    private val checkpoints = ConcurrentLinkedQueue<RecoveryCheckpoint>()
    private val errorLog = ConcurrentLinkedQueue<ErrorRecord>()
    
    private val toolCallCount = AtomicLong(0)
    private val errorCount = AtomicInteger(0)
    private val successCount = AtomicInteger(0)
    
    private val memoryThreshold = 0.85
    private val cpuThreshold = 0.80
    
    init {
        File(checkpointDir).mkdirs()
        startHealthMonitoring()
    }
    
    private fun startHealthMonitoring() {
        executor.scheduleAtFixedRate({
            if (isRunning.get()) {
                takeSnapshot()
                checkHealth()
            }
        }, 30, 30, TimeUnit.SECONDS)
        
        executor.scheduleAtFixedRate({
            if (isRunning.get()) {
                cleanupOldData()
            }
        }, 5, 5, TimeUnit.MINUTES)
    }
    
    private fun takeSnapshot() {
        try {
            val runtime = Runtime.getRuntime()
            val memoryUsed = runtime.totalMemory() - runtime.freeMemory()
            val memoryTotal = runtime.totalMemory()
            
            val gcCount = runtime.totalMemory().toInt()
            val heapUsed = runtime.totalMemory() - runtime.freeMemory()
            
            val toolCallsTotal = toolCallCount.get()
            val toolCallsPerMinute = toolCallsTotal.toDouble() / 
                ((System.currentTimeMillis() - startTime.get()) / 60000.0).coerceAtLeast(1.0)
            
            val errorRate = if (toolCallsTotal > 0) {
                errorCount.get().toDouble() / toolCallsTotal
            } else 0.0
            
            val healthScore = calculateHealthScore(memoryUsed, memoryTotal, errorRate)
            
            val metrics = ServerHealthMetrics(
                uptimeMs = System.currentTimeMillis() - startTime.get(),
                memoryUsed = memoryUsed,
                memoryTotal = memoryTotal,
                memoryPercent = memoryUsed.toDouble() / memoryTotal,
                threadCount = Thread.activeCount(),
                activeConnections = 0,
                toolCallsTotal = toolCallsTotal,
                toolCallsPerMinute = toolCallsPerMinute,
                errorRate = errorRate,
                lastError = errorLog.peek()?.message,
                lastErrorTime = errorLog.peek()?.timestamp,
                gcCount = gcCount,
                heapUsedAfterGC = heapUsed,
                healthScore = healthScore
            )
            
            val snapshot = ProcessSnapshot(
                metrics = metrics,
                activeAgents = 0,
                pendingToolCalls = 0,
                messageQueueSize = 0,
                state = "healthy"
            )
            
            snapshots.offer(snapshot)
            
            if (snapshots.size > 100) {
                snapshots.poll()
            }
            
        } catch (e: Exception) {
            logger.error("Failed to take snapshot: ${e.message}")
        }
    }
    
    private fun calculateHealthScore(memoryUsed: Long, memoryTotal: Long, errorRate: Double): Double {
        var score = 1.0
        
        val memoryPercent = memoryUsed.toDouble() / memoryTotal
        score -= when {
            memoryPercent > 0.9 -> 0.5
            memoryPercent > memoryThreshold -> 0.3
            memoryPercent > 0.7 -> 0.1
            else -> 0.0
        }
        
        score -= errorRate * 2.0
        
        return score.coerceIn(0.0, 1.0)
    }
    
    private fun checkHealth() {
        val runtime = Runtime.getRuntime()
        val memoryUsed = runtime.totalMemory() - runtime.freeMemory()
        val memoryTotal = runtime.totalMemory()
        val memoryPercent = memoryUsed.toDouble() / memoryTotal
        
        if (memoryPercent > memoryThreshold) {
            logger.warn("Memory threshold exceeded: ${"%.1f".format(memoryPercent * 100)}%")
            triggerPreventiveMeasures()
        }
        
        val lastError = errorLog.peek()
        if (lastError != null && System.currentTimeMillis() - lastError.timestamp < 60000) {
            if (errorCount.get() > 10) {
                logger.error("High error rate detected: ${errorCount.get()} errors in last minute")
            }
        }
    }
    
    private fun triggerPreventiveMeasures() {
        try {
            System.gc()
            logger.info("Triggered preventive garbage collection")
            
            val runtime = Runtime.getRuntime()
            runtime.runFinalization()
            logger.info("Triggered finalization")
            
        } catch (e: Exception) {
            logger.error("Preventive measures failed: ${e.message}")
        }
    }
    
    private fun cleanupOldData() {
        val cutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        
        while (snapshots.isNotEmpty() && (snapshots.peek()?.timestamp ?: 0) < cutoff) {
            snapshots.poll()
        }
        
        while (checkpoints.isNotEmpty() && (checkpoints.peek()?.timestamp ?: 0) < cutoff) {
            checkpoints.poll()
        }
        
        while (errorLog.size > 1000) {
            errorLog.poll()
        }
    }
    
    fun recordToolCall(toolName: String, success: Boolean) {
        toolCallCount.incrementAndGet()
        if (success) {
            successCount.incrementAndGet()
        } else {
            errorCount.incrementAndGet()
        }
    }
    
    fun recordError(error: String, context: String? = null) {
        errorCount.incrementAndGet()
        errorLog.offer(
            ErrorRecord(
                message = error,
                context = context,
                timestamp = System.currentTimeMillis()
            )
        )
        
        if (errorLog.size > 1000) {
            errorLog.poll()
        }
    }
    
    fun createCheckpoint(
        activeAgents: Map<String, AgentState>,
        lastProcessedIndex: Long,
        metadata: Map<String, String> = emptyMap()
    ): RecoveryCheckpoint {
        val checkpoint = RecoveryCheckpoint(
            activeAgents = activeAgents,
            pendingMessages = emptyList(),
            lastProcessedIndex = lastProcessedIndex,
            metadata = metadata
        )
        
        checkpoints.offer(checkpoint)
        
        saveCheckpointToFile(checkpoint)
        
        return checkpoint
    }
    
    private fun saveCheckpointToFile(checkpoint: RecoveryCheckpoint) {
        try {
            val file = File(checkpointDir, "checkpoint_${checkpoint.id}.json")
            file.writeText(json.encodeToString(RecoveryCheckpoint.serializer(), checkpoint))
            logger.info("Saved checkpoint: ${checkpoint.id}")
        } catch (e: Exception) {
            logger.error("Failed to save checkpoint: ${e.message}")
        }
    }
    
    fun loadLatestCheckpoint(): RecoveryCheckpoint? {
        return try {
            val files = File(checkpointDir).listFiles { f -> f.name.startsWith("checkpoint_") && f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?: return null
            
            files.firstOrNull()?.let { file ->
                json.decodeFromString(RecoveryCheckpoint.serializer(), file.readText())
            }
        } catch (e: Exception) {
            logger.error("Failed to load checkpoint: ${e.message}")
            null
        }
    }
    
    fun getLatestSnapshot(): ProcessSnapshot? = snapshots.lastOrNull()
    
    fun getHealthReport(): String {
        val snapshot = snapshots.lastOrNull()
        
        return buildString {
            appendLine("[Server Health Report]")
            appendLine("=".repeat(50))
            appendLine()
            
            if (snapshot != null) {
                val m = snapshot.metrics
                appendLine("Uptime: ${formatUptime(m.uptimeMs)}")
                appendLine()
                appendLine("[Memory]")
                appendLine("  Used: ${formatBytes(m.memoryUsed)} / ${formatBytes(m.memoryTotal)}")
                appendLine("  Usage: ${"%.1f".format(m.memoryPercent * 100)}%")
                appendLine("  Health Score: ${"%.1f".format(m.healthScore * 100)}%")
                appendLine()
                appendLine("[Activity]")
                appendLine("  Tool Calls: ${m.toolCallsTotal}")
                appendLine("  Rate: ${"%.1f".format(m.toolCallsPerMinute)}/min")
                appendLine("  Error Rate: ${"%.2f".format(m.errorRate * 100)}%")
                appendLine()
                appendLine("[Threads]")
                appendLine("  Active: ${m.threadCount}")
                appendLine()
                
                if (m.lastError != null) {
                    appendLine("[Last Error]")
                    appendLine("  ${m.lastError}")
                    appendLine("  At: ${java.time.Instant.ofEpochMilli(m.lastErrorTime ?: 0)}")
                }
            } else {
                appendLine("No snapshots available")
            }
            
            appendLine()
            appendLine("[Checkpoints]")
            appendLine("  Stored: ${checkpoints.size}")
            appendLine("  Pending cleanup: ${errorLog.size} errors")
        }
    }
    
    private fun formatUptime(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        
        return when {
            days > 0 -> "${days}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes > 1_000_000_000 -> "${"%.1f".format(bytes / 1_000_000_000.0)} GB"
            bytes > 1_000_000 -> "${"%.1f".format(bytes / 1_000_000.0)} MB"
            bytes > 1_000 -> "${"%.1f".format(bytes / 1_000.0)} KB"
            else -> "$bytes B"
        }
    }
    
    fun shutdown() {
        isRunning.set(false)
        executor.shutdown()
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
    
    fun isHealthy(): Boolean {
        val snapshot = snapshots.lastOrNull() ?: return true
        return snapshot.metrics.healthScore > 0.5
    }
}

data class ErrorRecord(
    val message: String,
    val context: String?,
    val timestamp: Long
)

class CrashRecoveryHandler(
    private val resilienceManager: ServerResilienceManager
) {
    private val logger = LoggerFactory.getLogger(CrashRecoveryHandler::class.java)
    
    private val recoveryStrategies = listOf(
        RecoveryStrategy("memory_cleanup", 0.8) { performMemoryCleanup() },
        RecoveryStrategy("gc", 0.6) { triggerGarbageCollection() },
        RecoveryStrategy("checkpoint_restore", 0.4) { restoreFromCheckpoint() },
        RecoveryStrategy("graceful_degradation", 0.2) { enableGracefulDegradation() }
    )
    
    data class RecoveryStrategy(
        val name: String,
        val priority: Double,
        val action: () -> Boolean
    )
    
    fun handlePotentialCrash(): RecoveryAction {
        val snapshot = resilienceManager.getLatestSnapshot()
        
        if (snapshot == null) {
            return RecoveryAction(
                actionTaken = "no_data",
                success = true,
                message = "No snapshot data available, assuming fresh start"
            )
        }
        
        val healthScore = snapshot.metrics.healthScore
        
        return when {
            healthScore < 0.3 -> {
                logger.error("Critical health score: ${healthScore}, attempting recovery")
                attemptRecovery(healthScore)
            }
            healthScore < 0.5 -> {
                logger.warn("Low health score: ${healthScore}, taking preventive measures")
                takePreventiveMeasures()
            }
            else -> {
                RecoveryAction(
                    actionTaken = "none",
                    success = true,
                    message = "System healthy, no action needed"
                )
            }
        }
    }
    
    private fun attemptRecovery(healthScore: Double): RecoveryAction {
        for (strategy in recoveryStrategies.sortedByDescending { it.priority }) {
            try {
                logger.info("Attempting recovery strategy: ${strategy.name}")
                if (strategy.action()) {
                    logger.info("Recovery strategy ${strategy.name} succeeded")
                    
                    val newSnapshot = resilienceManager.getLatestSnapshot()
                    val newScore = newSnapshot?.metrics?.healthScore ?: 0.0
                    
                    if (newScore > healthScore) {
                        return RecoveryAction(
                            actionTaken = strategy.name,
                            success = true,
                            message = "Recovered. Health improved from ${"%.1f".format(healthScore * 100)}% to ${"%.1f".format(newScore * 100)}%"
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Recovery strategy ${strategy.name} failed: ${e.message}")
            }
        }
        
        return RecoveryAction(
            actionTaken = "failed",
            success = false,
            message = "All recovery strategies failed. Consider manual intervention."
        )
    }
    
    private fun takePreventiveMeasures(): RecoveryAction {
        try {
            System.gc()
            
            val runtime = Runtime.getRuntime()
            runtime.runFinalization()
            
            return RecoveryAction(
                actionTaken = "preventive",
                success = true,
                message = "Preventive measures taken successfully"
            )
        } catch (e: Exception) {
            return RecoveryAction(
                actionTaken = "preventive_failed",
                success = false,
                message = "Preventive measures failed: ${e.message}"
            )
        }
    }
    
    private fun performMemoryCleanup(): Boolean {
        return try {
            System.gc()
            Thread.sleep(100)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun triggerGarbageCollection(): Boolean {
        return try {
            System.gc()
            System.runFinalization()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun restoreFromCheckpoint(): Boolean {
        return try {
            val checkpoint = resilienceManager.loadLatestCheckpoint()
            checkpoint != null
        } catch (e: Exception) {
            logger.error("Checkpoint restore failed: ${e.message}")
            false
        }
    }
    
    private fun enableGracefulDegradation(): Boolean {
        logger.info("Enabling graceful degradation mode")
        return true
    }
    
    fun prepareForShutdown() {
        logger.info("Preparing for shutdown - creating final checkpoint")
        resilienceManager.createCheckpoint(
            activeAgents = emptyMap(),
            lastProcessedIndex = 0,
            metadata = mapOf("reason" to "shutdown", "timestamp" to System.currentTimeMillis().toString())
        )
    }
}

data class RecoveryAction(
    val actionTaken: String,
    val success: Boolean,
    val message: String
)

class ResourceAllocator(
    private val resilienceManager: ServerResilienceManager
) {
    private val logger = LoggerFactory.getLogger(ResourceAllocator::class.java)
    
    private val resourcePools = ConcurrentHashMap<String, ResourcePool>()
    private val allocations = ConcurrentHashMap<String, ResourceAllocation>()
    
    data class ResourcePool(
        val name: String,
        val maxResources: Int,
        val allocatedCount: AtomicInteger = AtomicInteger(0),
        val waitQueue: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
    )
    
    data class ResourceAllocation(
        val id: String = UUID.randomUUID().toString(),
        val poolName: String,
        val resourceId: String,
        val allocatedAt: Long = System.currentTimeMillis(),
        val expiresAt: Long? = null
    )
    
    fun createPool(name: String, maxResources: Int) {
        resourcePools[name] = ResourcePool(name, maxResources)
        logger.info("Created resource pool: $name (max: $maxResources)")
    }
    
    fun allocate(poolName: String, timeoutMs: Long = 5000): ResourceAllocation? {
        val pool = resourcePools[poolName] ?: run {
            logger.warn("Pool not found: $poolName")
            return null
        }
        
        if (pool.allocatedCount.get() < pool.maxResources) {
            val allocation = ResourceAllocation(
                poolName = poolName,
                resourceId = UUID.randomUUID().toString()
            )
            pool.allocatedCount.incrementAndGet()
            allocations[allocation.id] = allocation
            
            resilienceManager.recordToolCall("resource_allocate", true)
            return allocation
        }
        
        val waitId = UUID.randomUUID().toString()
        pool.waitQueue.offer(waitId)
        
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (pool.allocatedCount.get() < pool.maxResources) {
                pool.waitQueue.remove(waitId)
                
                val allocation = ResourceAllocation(
                    poolName = poolName,
                    resourceId = UUID.randomUUID().toString()
                )
                pool.allocatedCount.incrementAndGet()
                allocations[allocation.id] = allocation
                
                return allocation
            }
            Thread.sleep(50)
        }
        
        pool.waitQueue.remove(waitId)
        resilienceManager.recordToolCall("resource_allocate", false)
        logger.warn("Resource allocation timeout for pool: $poolName")
        return null
    }
    
    fun release(allocationId: String): Boolean {
        val allocation = allocations.remove(allocationId) ?: return false
        
        val pool = resourcePools[allocation.poolName] ?: return false
        pool.allocatedCount.decrementAndGet()
        
        logger.debug("Released resource: ${allocation.resourceId} to pool: ${allocation.poolName}")
        return true
    }
    
    fun getPoolStatus(poolName: String): String {
        val pool = resourcePools[poolName] ?: return "Pool not found: $poolName"
        
        return buildString {
            appendLine("Pool: $poolName")
            appendLine("  Allocated: ${pool.allocatedCount.get()} / ${pool.maxResources}")
            appendLine("  Wait Queue: ${pool.waitQueue.size}")
            appendLine("  Available: ${pool.maxResources - pool.allocatedCount.get()}")
        }
    }
    
    fun getAllPoolStatus(): String {
        return buildString {
            appendLine("[Resource Pool Status]")
            appendLine("-".repeat(40))
            
            for ((name, pool) in resourcePools) {
                appendLine("Pool: $name")
                appendLine("  Allocated: ${pool.allocatedCount.get()} / ${pool.maxResources}")
                appendLine("  Wait Queue: ${pool.waitQueue.size}")
                appendLine()
            }
        }
    }
}

class ConnectionPool(
    private val resilienceManager: ServerResilienceManager
) {
    private val logger = LoggerFactory.getLogger(ConnectionPool::class.java)
    
    private val connections = ConcurrentHashMap<String, Connection>()
    private val availableConnections = ConcurrentLinkedQueue<String>()
    private val maxConnections: Int
    
    private val connectionTimeout: Long = 30000
    private val idleTimeout: Long = 60000
    
    init {
        maxConnections = (Runtime.getRuntime().maxMemory() / (50 * 1024 * 1024)).toInt().coerceIn(10, 100)
        logger.info("Connection pool initialized with max: $maxConnections")
    }
    
    data class Connection(
        val id: String = UUID.randomUUID().toString(),
        val createdAt: Long = System.currentTimeMillis(),
        var lastUsed: Long = System.currentTimeMillis(),
        var inUse: Boolean = false,
        val metadata: MutableMap<String, String> = mutableMapOf()
    )
    
    fun acquire(): Connection? {
        val availableId = availableConnections.poll()
        
        if (availableId != null) {
            val conn = connections[availableId]
            if (conn != null && !isConnectionStale(conn)) {
                conn.inUse = true
                conn.lastUsed = System.currentTimeMillis()
                return conn
            } else {
                connections.remove(availableId)
            }
        }
        
        if (connections.size < maxConnections) {
            val conn = Connection()
            connections[conn.id] = conn
            conn.inUse = true
            resilienceManager.recordToolCall("connection_acquire", true)
            return conn
        }
        
        resilienceManager.recordToolCall("connection_acquire", false)
        logger.warn("Connection pool exhausted")
        return null
    }
    
    fun release(connection: Connection) {
        connection.inUse = false
        connection.lastUsed = System.currentTimeMillis()
        
        if (!isConnectionStale(connection)) {
            availableConnections.offer(connection.id)
        } else {
            connections.remove(connection.id)
            logger.debug("Removed stale connection: ${connection.id}")
        }
    }
    
    private fun isConnectionStale(conn: Connection): Boolean {
        val idleTime = System.currentTimeMillis() - conn.lastUsed
        return idleTime > idleTimeout
    }
    
    fun cleanup() {
        val staleIds = connections.filter { isConnectionStale(it.value) && !it.value.inUse }.keys
        
        staleIds.forEach { id ->
            connections.remove(id)
            availableConnections.remove(id)
        }
        
        if (staleIds.isNotEmpty()) {
            logger.info("Cleaned up ${staleIds.size} stale connections")
        }
    }
    
    fun getStats(): String {
        val inUse = connections.values.count { it.inUse }
        val available = availableConnections.size
        val stale = connections.values.count { isConnectionStale(it) && !it.inUse }
        
        return buildString {
            appendLine("[Connection Pool Stats]")
            appendLine("-".repeat(40))
            appendLine("Max: $maxConnections")
            appendLine("In Use: $inUse")
            appendLine("Available: $available")
            appendLine("Stale: $stale")
            appendLine("Total: ${connections.size}")
        }
    }
}
