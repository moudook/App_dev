package com.example.smarty.server.tools

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

sealed class SystemEvent {
    data class AgentSpawned(val agentId: String, val role: String) : SystemEvent()
    data class AgentStateChanged(val agentId: String, val oldState: String, val newState: String) : SystemEvent()
    data class ToolAcquired(val toolName: String, val agentId: String) : SystemEvent()
    data class ToolReleased(val toolName: String, val agentId: String) : SystemEvent()
    data class MessageSent(val from: String, val to: String, val type: String) : SystemEvent()
    data class FindingShared(val from: String, val to: String?, val findingType: String) : SystemEvent()
    data class KeyAllocated(val agentId: String, val keyId: String) : SystemEvent()
    data class KeyReleased(val agentId: String, val keyId: String) : SystemEvent()
    data class Error(val component: String, val error: String, val recoverable: Boolean) : SystemEvent()
    data class ResourceExhausted(val resource: String, val agentId: String?) : SystemEvent()
    data class RecoveryInitiated(val component: String, val strategy: String) : SystemEvent()
    data class DeadlockDetected(val agents: Set<String>, val resources: Set<String>) : SystemEvent()
    data class DeadlockResolved(val agents: Set<String>) : SystemEvent()
}

class FormalAgentSystem(
    private val keyPool: ApiKeyPool,
    private val maxAgents: Int = 20,
    private val maxToolsPerAgent: Int = 5
) {
    private val logger = LoggerFactory.getLogger(FormalAgentSystem::class.java)
    
    private val systemState = AtomicReference<SystemState>(SystemState.INITIALIZING)
    private val eventLog = ConcurrentHashMap<Long, SystemEvent>()
    private val eventIdCounter = AtomicInteger(0)
    
    private val agentRegistry = ConcurrentHashMap<String, FormalAgent>()
    private val toolResources = ConcurrentHashMap<String, ToolResource>()
    private val messageBus = FormalMessageBus()
    
    private val globalMutex = Mutex()
    private val agentSemaphore = Semaphore(maxAgents)
    
    private val faultDetector = FaultDetector()
    private val deadlockPreventer = DeadlockPreventer()
    private val recoveryManager = RecoveryManager()
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val monitorJob: Job? = null
    
    init {
        initializeSystem()
    }
    
    private fun initializeSystem() {
        logger.info("Initializing formal agent system...")
        
        registerDefaultToolResources()
        
        if (systemState.compareAndSet(SystemState.INITIALIZING, SystemState.RUNNING)) {
            logEvent(SystemEvent.Error("System", "Initialization complete", true))
            logger.info("System initialized successfully")
        }
        
        startMonitoring()
    }
    
    private fun registerDefaultToolResources() {
        val criticalTools = listOf(
            "search_web", "execute_code", "fetch_url", "deep_research"
        )
        
        criticalTools.forEach { toolName ->
            toolResources[toolName] = ToolResource(
                name = toolName,
                maxConcurrent = 1,
                isCritical = true,
                deadlockRisk = toolName in listOf("execute_code")
            )
        }
        
        val nonCriticalTools = listOf(
            "save_note", "find_note", "summarize_content", "compare_options"
        )
        
        nonCriticalTools.forEach { toolName ->
            toolResources[toolName] = ToolResource(
                name = toolName,
                maxConcurrent = 3,
                isCritical = false,
                deadlockRisk = false
            )
        }
    }
    
    private fun startMonitoring() {
        scope.launch {
            while (isActive) {
                delay(5000)
                performHealthCheck()
            }
        }
    }
    
    private fun suspend fun performHealthCheck() {
        globalMutex.withLock {
            checkAgentHealth()
            checkResourceHealth()
            checkForDeadlock()
        }
    }
    
    private fun checkAgentHealth() {
        agentRegistry.values.forEach { agent ->
            when (agent.currentState) {
                FormalAgentState.BLOCKED -> {
                    val blockedDuration = System.currentTimeMillis() - agent.stateTimestamp
                    if (blockedDuration > 30000) {
                        logger.warn("Agent ${agent.id} blocked for ${blockedDuration}ms")
                        if (faultDetector.shouldRecover(agent.id, FaultType.AGENT_BLOCKED)) {
                            initiateRecovery(agent.id, RecoveryStrategy.AGENT_RESTART)
                        }
                    }
                }
                FormalAgentState.ERROR -> {
                    if (faultDetector.shouldRecover(agent.id, FaultType.AGENT_CRASHED)) {
                        initiateRecovery(agent.id, RecoveryStrategy.AGENT_RESTART)
                    }
                }
                else -> {}
            }
        }
    }
    
    private fun checkResourceHealth() {
        toolResources.values.forEach { resource ->
            if (resource.waitQueue.size > 5) {
                logEvent(SystemEvent.ResourceExhausted(resource.name, null))
            }
            
            val waitingAgents = resource.waitQueue.mapNotNull { agentRegistry[it] }
            if (waitingAgents.size >= 3) {
                deadlockPreventer.registerPotentialDeadlock(waitingAgents.map { it.id }.toSet(), resource.name)
            }
        }
    }
    
    private fun checkForDeadlock() {
        val potentialDeadlocks = deadlockPreventer.checkForDeadlock()
        potentialDeadlocks.forEach { deadlock ->
            logEvent(SystemEvent.DeadlockDetected(deadlock.agents, deadlock.resources))
            resolveDeadlock(deadlock)
        }
    }
    
    private fun resolveDeadlock(deadlock: DeadlockScenario) {
        val victimAgent = deadlock.agents.minByOrNull { agentId ->
            agentRegistry[agentId]?.priority ?: 0
        }
        
        if (victimAgent != null) {
            logEvent(SystemEvent.RecoveryInitiated(victimAgent, "PREEMPT"))
            
            val agent = agentRegistry[victimAgent]
            agent?.let {
                releaseAgentResources(it)
                it.transitionTo(FormalAgentState.TERMINATED)
            }
            
            deadlockPreventer.clearDeadlock(deadlock)
            logEvent(SystemEvent.DeadlockResolved(deadlock.agents))
        }
    }
    
    private fun releaseAgentResources(agent: FormalAgent) {
        agent.heldResources.forEach { toolName ->
            releaseTool(toolName, agent.id)
        }
        agent.heldResources.clear()
    }
    
    private suspend fun initiateRecovery(agentId: String, strategy: RecoveryStrategy) {
        logEvent(SystemEvent.RecoveryInitiated(agentId, strategy.name))
        
        when (strategy) {
            RecoveryStrategy.AGENT_RESTART -> {
                val agent = agentRegistry[agentId]
                agent?.let {
                    releaseAgentResources(it)
                    it.transitionTo(FormalAgentState.IDLE)
                }
            }
            RecoveryStrategy.KEY_ROTATION -> {
                val agent = agentRegistry[agentId]
                agent?.let {
                    keyPool.releaseAgentKey(agentId)
                    val newKey = keyPool.getKeyForAgent(agentId)
                    if (newKey != null) {
                        it.apiKey = newKey.apiKey
                        logEvent(SystemEvent.KeyAllocated(agentId, newKey.keyId))
                    }
                }
            }
            RecoveryStrategy.TOOL_PREEMPTION -> {
                val agent = agentRegistry[agentId]
                agent?.let {
                    releaseAgentResources(it)
                    it.transitionTo(FormalAgentState.IDLE)
                }
            }
            RecoveryStrategy.SYSTEM_RESET -> {
                emergencyShutdown()
                delay(1000)
                initializeSystem()
            }
        }
    }
    
    private fun emergencyShutdown() {
        logger.warn("EMERGENCY SHUTDOWN INITIATED")
        agentRegistry.values.forEach { agent ->
            releaseAgentResources(agent)
            agent.transitionTo(FormalAgentState.TERMINATED)
        }
        systemState.set(SystemState.ERROR)
    }
    
    suspend fun spawnAgent(role: String, task: String, tools: List<String>): String? {
        if (systemState.get() != SystemState.RUNNING) {
            logger.error("Cannot spawn agent - system not running")
            return null
        }
        
        if (!agentSemaphore.tryAcquire()) {
            logEvent(SystemEvent.ResourceExhausted("AGENT_SLOT", null))
            return null
        }
        
        val agentId = "formal_agent_${System.currentTimeMillis()}"
        val keyAssignment = keyPool.getKeyForAgent(agentId)
        
        if (keyAssignment == null) {
            agentSemaphore.release()
            logEvent(SystemEvent.ResourceExhausted("API_KEY", null))
            return null
        }
        
        val agent = FormalAgent(
            id = agentId,
            role = role,
            apiKey = keyAssignment.apiKey,
            keyId = keyAssignment.keyId,
            requestedTools = tools,
            maxTools = maxToolsPerAgent
        )
        
        agentRegistry[agentId] = agent
        logEvent(SystemEvent.AgentSpawned(agentId, role))
        logEvent(SystemEvent.KeyAllocated(agentId, keyAssignment.keyId))
        
        return agentId
    }
    
    suspend fun acquireTool(toolName: String, agentId: String, timeoutMs: Long = 30000): Boolean {
        val resource = toolResources[toolName] ?: return false
        val agent = agentRegistry[agentId] ?: return false
        
        val acquired = resource.acquire(agentId, timeoutMs)
        
        if (acquired) {
            agent.heldResources.add(toolName)
            logEvent(SystemEvent.ToolAcquired(toolName, agentId))
            
            deadlockPreventer.recordAcquisition(agentId, toolName)
        }
        
        return acquired
    }
    
    suspend fun releaseTool(toolName: String, agentId: String) {
        val resource = toolResources[toolName]
        val agent = agentRegistry[agentId]
        
        resource?.release(agentId)
        agent?.heldResources?.remove(toolName)
        
        logEvent(SystemEvent.ToolReleased(toolName, agentId))
        deadlockPreventer.recordRelease(agentId, toolName)
    }
    
    suspend fun sendMessage(fromAgentId: String, toAgentId: String, content: String, type: String): Boolean {
        val from = agentRegistry[fromAgentId]
        val to = agentRegistry[toAgentId]
        
        if (from == null || to == null) return false
        
        val message = FormalMessage(
            id = "msg_${System.currentTimeMillis()}",
            from = fromAgentId,
            to = toAgentId,
            content = content,
            type = type
        )
        
        messageBus.deliver(message)
        logEvent(SystemEvent.MessageSent(fromAgentId, toAgentId, type))
        
        return true
    }
    
    suspend fun broadcastFinding(fromAgentId: String, findingType: String, content: String) {
        val interestedAgents = findInterestedAgents(fromAgentId, findingType)
        
        interestedAgents.forEach { agentId ->
            sendMessage(fromAgentId, agentId, content, "INSIGHT")
        }
        
        logEvent(SystemEvent.FindingShared(fromAgentId, null, findingType))
    }
    
    private fun findInterestedAgents(fromAgentId: String, findingType: String): List<String> {
        return agentRegistry.values
            .filter { it.id != fromAgentId && it.currentState == FormalAgentState.RUNNING }
            .map { it.id }
            .take(3)
    }
    
    fun getSystemState(): SystemState = systemState.get()
    
    fun getAgentState(agentId: String): FormalAgentState? = agentRegistry[agentId]?.currentState
    
    fun getToolState(toolName: String): ToolResourceState? = toolResources[toolName]?.getState()
    
    fun getEventLog(limit: Int = 100): List<Pair<Long, SystemEvent>> {
        return eventLog.entries.sortedBy { it.key }.takeLast(limit).map { it.key to it.value }
    }
    
    private fun logEvent(event: SystemEvent) {
        val id = eventIdCounter.incrementAndGet().toLong()
        eventLog[id] = event
        
        if (eventLog.size > 10000) {
            val keysToRemove = eventLog.keys.sorted().take(5000)
            keysToRemove.forEach { eventLog.remove(it) }
        }
        
        logger.debug("Event: $event")
    }
    
    fun formatSystemStatus(): String {
        return buildString {
            appendLine("=".repeat(70))
            appendLine("FORMAL AGENT SYSTEM STATUS")
            appendLine("=".repeat(70))
            appendLine()
            appendLine("System State: ${systemState.get()}")
            appendLine("Active Agents: ${agentRegistry.count { it.value.currentState == FormalAgentState.RUNNING }}")
            appendLine("Total Agents: ${agentRegistry.size}")
            appendLine("Available Keys: ${keyPool.getAvailableKeyCount()}")
            appendLine()
            
            appendLine("Agent Registry:")
            appendLine("-".repeat(50))
            agentRegistry.values.forEach { agent ->
                appendLine("  [${agent.role}] ${agent.id}")
                appendLine("    State: ${agent.currentState}")
                appendLine("    Held Resources: ${agent.heldResources.joinToString(", ")}")
                appendLine()
            }
            
            appendLine("Tool Resources:")
            appendLine("-".repeat(50))
            toolResources.values.forEach { resource ->
                appendLine("  ${resource.name}: ${resource.getState()}")
                appendLine("    Waiting: ${resource.waitQueue.size}, Held: ${resource.heldBy ?: "none"}")
            }
            
            appendLine()
            appendLine("Recent Events:")
            appendLine("-".repeat(50))
            getEventLog(10).forEach { (_, event) ->
                appendLine("  $event")
            }
        }
    }
    
    fun shutdown() {
        logger.info("Shutting down formal agent system...")
        systemState.set(SystemState.SHUTTING_DOWN)
        
        agentRegistry.values.forEach { agent ->
            releaseAgentResources(agent)
            agent.transitionTo(FormalAgentState.TERMINATED)
        }
        
        scope.cancel()
        systemState.set(SystemState.TERMINATED)
        logger.info("System shutdown complete")
    }
}

enum class SystemState {
    INITIALIZING, RUNNING, DEGRADED, ERROR, SHUTTING_DOWN, TERMINATED
}

enum class FormalAgentState {
    IDLE, INITIALIZING, RUNNING, BLOCKED, WAITING, COMPLETED, ERROR, TERMINATED
}

data class FormalAgent(
    val id: String,
    val role: String,
    val apiKey: String,
    val keyId: String,
    val requestedTools: List<String>,
    val maxTools: Int,
    var currentState: FormalAgentState = FormalAgentState.IDLE,
    var stateTimestamp: Long = System.currentTimeMillis(),
    var heldResources: MutableSet<String> = mutableSetOf(),
    var priority: Int = 5,
    var errorCount: Int = 0,
    var lastError: String? = null
) {
    fun transitionTo(newState: FormalAgentState): Boolean {
        val validTransitions = when (currentState) {
            FormalAgentState.IDLE -> setOf(FormalAgentState.INITIALIZING, FormalAgentState.TERMINATED)
            FormalAgentState.INITIALIZING -> setOf(FormalAgentState.RUNNING, FormalAgentState.ERROR, FormalAgentState.TERMINATED)
            FormalAgentState.RUNNING -> setOf(FormalAgentState.BLOCKED, FormalAgentState.WAITING, FormalAgentState.COMPLETED, FormalAgentState.ERROR, FormalAgentState.TERMINATED)
            FormalAgentState.BLOCKED -> setOf(FormalAgentState.RUNNING, FormalAgentState.ERROR, FormalAgentState.TERMINATED)
            FormalAgentState.WAITING -> setOf(FormalAgentState.RUNNING, FormalAgentState.COMPLETED, FormalAgentState.ERROR, FormalAgentState.TERMINATED)
            FormalAgentState.COMPLETED -> setOf(FormalAgentState.IDLE, FormalAgentState.TERMINATED)
            FormalAgentState.ERROR -> setOf(FormalAgentState.IDLE, FormalAgentState.TERMINATED)
            FormalAgentState.TERMINATED -> emptySet()
        }
        
        if (newState in validTransitions) {
            currentState = newState
            stateTimestamp = System.currentTimeMillis()
            return true
        }
        return false
    }
}

data class ToolResource(
    val name: String,
    val maxConcurrent: Int,
    val isCritical: Boolean,
    val deadlockRisk: Boolean,
    var heldBy: String? = null,
    val waitQueue: MutableList<String> = mutableListOf(),
    val lock: Mutex = Mutex()
) {
    suspend fun acquire(agentId: String, timeoutMs: Long): Boolean {
        return lock.withLock {
            if (heldBy == null) {
                heldBy = agentId
                return@withLock true
            }
            
            if (agentId !in waitQueue) {
                waitQueue.add(agentId)
            }
            false
        }
    }
    
    suspend fun release(agentId: String) {
        lock.withLock {
            if (heldBy == agentId) {
                heldBy = if (waitQueue.isNotEmpty()) {
                    waitQueue.removeAt(0)
                } else null
            } else {
                waitQueue.remove(agentId)
            }
        }
    }
    
    fun getState(): ToolResourceState {
        return when {
            heldBy != null -> ToolResourceState.BUSY
            waitQueue.isNotEmpty() -> ToolResourceState.QUEUED
            else -> ToolResourceState.AVAILABLE
        }
    }
}

enum class ToolResourceState {
    AVAILABLE, BUSY, QUEUED
}

data class FormalMessage(
    val id: String,
    val from: String,
    val to: String,
    val content: String,
    val type: String,
    val timestamp: Long = System.currentTimeMillis()
)

class FormalMessageBus {
    private val logger = LoggerFactory.getLogger(FormalMessageBus::class.java)
    private val messageQueues = ConcurrentHashMap<String, Channel<FormalMessage>>()
    
    fun registerReceiver(agentId: String): Channel<FormalMessage> {
        return messageQueues.getOrPut(agentId) { Channel(Channel.UNLIMITED) }
    }
    
    fun unregisterReceiver(agentId: String) {
        messageQueues[agentId]?.close()
        messageQueues.remove(agentId)
    }
    
    suspend fun deliver(message: FormalMessage): Boolean {
        val queue = messageQueues[message.to]
        if (queue != null) {
            queue.send(message)
            return true
        }
        logger.warn("Message delivery failed - recipient ${message.to} not found")
        return false
    }
}

enum class FaultType {
    AGENT_CRASHED, AGENT_BLOCKED, TOOL_DEADLOCK, API_KEY_EXHAUSTED, MEMORY_EXHAUSTED, TIMEOUT
}

enum class RecoveryStrategy {
    AGENT_RESTART, KEY_ROTATION, TOOL_PREEMPTION, SYSTEM_RESET
}

class FaultDetector {
    private val faultCounts = ConcurrentHashMap<String, MutableMap<FaultType, Int>>()
    private val lastRecovery = ConcurrentHashMap<String, Long>()
    
    private val faultThresholds = mapOf(
        FaultType.AGENT_CRASHED to 3,
        FaultType.AGENT_BLOCKED to 2,
        FaultType.API_KEY_EXHAUSTED to 5,
        FaultType.TIMEOUT to 5
    )
    
    fun recordFault(agentId: String, faultType: FaultType) {
        val counts = faultCounts.getOrPut(agentId) { ConcurrentHashMap() }
        counts[faultType] = (counts[faultType] ?: 0) + 1
    }
    
    fun shouldRecover(agentId: String, faultType: FaultType): Boolean {
        val counts = faultCounts[agentId] ?: return false
        val threshold = faultThresholds[faultType] ?: return 10
        
        val lastRecoveryTime = lastRecovery[agentId] ?: 0
        val timeSinceLastRecovery = System.currentTimeMillis() - lastRecoveryTime
        
        return (counts[faultType] ?: 0) >= threshold && timeSinceLastRecovery > 10000
    }
    
    fun recordRecovery(agentId: String) {
        lastRecovery[agentId] = System.currentTimeMillis()
        faultCounts[agentId]?.clear()
    }
}

data class DeadlockScenario(
    val agents: Set<String>,
    val resources: Set<String>,
    val timestamp: Long = System.currentTimeMillis()
)

class DeadlockPreventer {
    private val resourceAllocationGraph = ConcurrentHashMap<String, MutableSet<String>>()
    private val waitingFor = ConcurrentHashMap<String, String>()
    private val potentialDeadlocks = mutableListOf<DeadlockScenario>()
    
    private val lock = Mutex()
    
    fun recordAcquisition(agentId: String, resource: String) {
        lock.tryRun {
            resourceAllocationGraph.getOrPut(agentId) { mutableSetOf() }.add(resource)
            waitingFor[agentId] = resource
        }
    }
    
    fun recordRelease(agentId: String, resource: String) {
        lock.tryRun {
            resourceAllocationGraph[agentId]?.remove(resource)
            waitingFor.remove(agentId)
        }
    }
    
    fun registerPotentialDeadlock(agents: Set<String>, resource: String) {
        lock.tryRun {
            val existing = potentialDeadlocks.find { it.resources.contains(resource) }
            if (existing == null) {
                potentialDeadlocks.add(DeadlockScenario(agents, setOf(resource)))
            }
        }
    }
    
    fun checkForDeadlock(): List<DeadlockScenario> {
        val deadlocks = mutableListOf<DeadlockScenario>()
        
        lock.tryRun {
            val visited = mutableSetOf<String>()
            val recursionStack = mutableSetOf<String>()
            
            resourceAllocationGraph.keys.forEach { agentId ->
                if (agentId !in visited) {
                    val cycle = detectCycle(agentId, visited, recursionStack)
                    if (cycle != null) {
                        deadlocks.add(DeadlockScenario(cycle, emptySet()))
                    }
                }
            }
        }
        
        return deadlocks
    }
    
    private fun detectCycle(agentId: String, visited: MutableSet<String>, stack: MutableSet<String>): Set<String>? {
        visited.add(agentId)
        stack.add(agentId)
        
        val holding = resourceAllocationGraph[agentId] ?: emptySet()
        val waiting = waitingFor[agentId]
        
        if (waiting != null) {
            val waitingOwner = resourceAllocationGraph.entries.find { waiting in it.value }?.key
            if (waitingOwner != null) {
                if (waitingOwner in stack) {
                    return stack.toSet() + waitingOwner
                }
                if (waitingOwner !in visited) {
                    return detectCycle(waitingOwner, visited, stack)
                }
            }
        }
        
        stack.remove(agentId)
        return null
    }
    
    fun clearDeadlock(deadlock: DeadlockScenario) {
        lock.tryRun {
            potentialDeadlocks.removeAll { it.agents == deadlock.agents }
        }
    }
    
    private fun Mutex.tryRun(block: () -> Unit) {
        try {
            runBlocking {
                withLock { block() }
            }
        } catch (e: Exception) {
            block()
        }
    }
}

class RecoveryManager {
    private val logger = LoggerFactory.getLogger(RecoveryManager::class.java)
    private val recoveryLog = ConcurrentHashMap<String, Long>()
    
    suspend fun recover(agentId: String, strategy: RecoveryStrategy): Boolean {
        logger.info("Initiating recovery for $agentId using strategy $strategy")
        recoveryLog[agentId] = System.currentTimeMillis()
        
        return when (strategy) {
            RecoveryStrategy.AGENT_RESTART -> {
                logger.info("Restarting agent $agentId")
                true
            }
            RecoveryStrategy.KEY_ROTATION -> {
                logger.info("Rotating API key for $agentId")
                true
            }
            RecoveryStrategy.TOOL_PREEMPTION -> {
                logger.info("Preempting tools for $agentId")
                true
            }
            RecoveryStrategy.SYSTEM_RESET -> {
                logger.warn("Full system reset requested for $agentId")
                true
            }
        }
    }
    
    fun getLastRecoveryTime(agentId: String): Long? = recoveryLog[agentId]
}

private fun runBlocking(block: () -> Unit) {
    kotlinx.coroutines.runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Unconfined) { block() } }
}
