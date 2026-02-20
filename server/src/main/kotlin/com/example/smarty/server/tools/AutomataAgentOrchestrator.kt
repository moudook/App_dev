package com.example.smarty.server.tools

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

sealed class AutomatonState {
    abstract val name: String
    abstract val validTransitions: Set<String>
}

object AutomatonTheory {
    
    interface FiniteAutomaton<S : AutomatonState> {
        val currentState: S
        val stateHistory: List<S>
        
        fun transition(to: String): Boolean
        fun isValidTransition(to: String): Boolean
        fun reset()
    }
    
    abstract class DeterministicFiniteAutomaton<S : AutomatonState>(
        initialState: S,
        private val transitionFunction: (S, String) -> S?
    ) : FiniteAutomaton<S> {
        
        private val _currentState = AtomicReference(initialState)
        private val _stateHistory = mutableListOf<S>()
        
        override val currentState: S get() = _currentState.get()
        override val stateHistory: List<S> get() = _stateHistory.toList()
        
        @Synchronized
        override fun transition(to: String): Boolean {
            val current = _currentState.get()
            if (!isValidTransition(to)) {
                return false
            }
            
            val nextState = transitionFunction(current, to) ?: return false
            _stateHistory.add(current)
            _currentState.set(nextState)
            return true
        }
        
        override fun isValidTransition(to: String): Boolean {
            return to in _currentState.get().validTransitions
        }
        
        @Synchronized
        override fun reset() {
            _stateHistory.clear()
        }
    }
    
    class AgentStateAutomaton : DeterministicFiniteAutomaton<AgentAutomatonState>(
        initialState = AgentAutomatonState.IDLE,
        transitionFunction = { current, input ->
            when {
                current == AgentAutomatonState.IDLE && input == "INITIALIZE" -> AgentAutomatonState.INITIALIZING
                current == AgentAutomatonState.INITIALIZING && input == "READY" -> AgentAutomatonState.READY
                current == AgentAutomatonState.READY && input == "START" -> AgentAutomatonState.RUNNING
                current == AgentAutomatonState.RUNNING && input == "WAIT" -> AgentAutomatonState.WAITING
                current == AgentAutomatonState.WAITING && input == "RESUME" -> AgentAutomatonState.RUNNING
                current == AgentAutomatonState.RUNNING && input == "COMPLETE" -> AgentAutomatonState.COMPLETED
                current == AgentAutomatonState.RUNNING && input == "FAIL" -> AgentAutomatonState.FAILED
                current == AgentAutomatonState.WAITING && input == "COMPLETE" -> AgentAutomatonState.COMPLETED
                current == AgentAutomatonState.WAITING && input == "FAIL" -> AgentAutomatonState.FAILED
                current == AgentAutomatonState.FAILED && input == "RESET" -> AgentAutomatonState.IDLE
                current == AgentAutomatonState.COMPLETED && input == "RESET" -> AgentAutomatonState.IDLE
                else -> null
            }
        }
    )
    
    class ToolAccessAutomaton : DeterministicFiniteAutomaton<ToolAutomatonState>(
        initialState = ToolAutomatonState.FREE,
        transitionFunction = { current, input ->
            when {
                current == ToolAutomatonState.FREE && input == "ACQUIRE" -> ToolAutomatonState.LOCKED
                current == ToolAutomatonState.LOCKED && input == "EXECUTE" -> ToolAutomatonState.EXECUTING
                current == ToolAutomatonState.EXECUTING && input == "COMPLETE" -> ToolAutomatonState.RELEASING
                current == ToolAutomatonState.EXECUTING && input == "ERROR" -> ToolAutomatonState.RELEASING
                current == ToolAutomatonState.RELEASING && input == "RELEASE" -> ToolAutomatonState.FREE
                else -> null
            }
        }
    )
    
    class AgentLifecycleAutomaton : DeterministicFiniteAutomaton<LifecycleState>(
        initialState = LifecycleState.CREATED,
        transitionFunction = { current, input ->
            when {
                current == LifecycleState.CREATED && input == "SPAWN" -> LifecycleState.SPAWNED
                current == LifecycleState.SPAWNED && input == "ASSIGN_KEY" -> LifecycleState.KEY_ASSIGNED
                current == LifecycleState.KEY_ASSIGNED && input == "ASSIGN_TASK" -> LifecycleState.TASK_ASSIGNED
                current == LifecycleState.TASK_ASSIGNED && input == "START" -> LifecycleState.ACTIVE
                current == LifecycleState.ACTIVE && input == "PAUSE" -> LifecycleState.PAUSED
                current == LifecycleState.PAUSED && input == "RESUME" -> LifecycleState.ACTIVE
                current == LifecycleState.ACTIVE && input == "FINISH" -> LifecycleState.FINISHED
                current == LifecycleState.ACTIVE && input == "ERROR" -> LifecycleState.ERROR
                current == LifecycleState.ERROR && input == "RETRY" -> LifecycleState.ACTIVE
                current == LifecycleState.FINISHED && input == "CLEANUP" -> LifecycleState.TERMINATED
                current == LifecycleState.TERMINATED && input == "RECYCLE" -> LifecycleState.CREATED
                else -> null
            }
        }
    )
}

sealed class AgentAutomatonState(
    override val name: String,
    override val validTransitions: Set<String>
) : AutomatonState() {
    object IDLE : AgentAutomatonState("IDLE", setOf("INITIALIZE"))
    object INITIALIZING : AgentAutomatonState("INITIALIZING", setOf("READY"))
    object READY : AgentAutomatonState("READY", setOf("START"))
    object RUNNING : AgentAutomatonState("RUNNING", setOf("WAIT", "COMPLETE", "FAIL"))
    object WAITING : AgentAutomatonState("WAITING", setOf("RESUME", "COMPLETE", "FAIL"))
    object COMPLETED : AgentAutomatonState("COMPLETED", setOf("RESET"))
    object FAILED : AgentAutomatonState("FAILED", setOf("RESET"))
    
    override fun toString(): String = "AgentState($name)"
}

sealed class ToolAutomatonState(
    override val name: String,
    override val validTransitions: Set<String>
) : AutomatonState() {
    object FREE : ToolAutomatonState("FREE", setOf("ACQUIRE"))
    object LOCKED : ToolAutomatonState("LOCKED", setOf("EXECUTE"))
    object EXECUTING : ToolAutomatonState("EXECUTING", setOf("COMPLETE", "ERROR"))
    object RELEASING : ToolAutomatonState("RELEASING", setOf("RELEASE"))
    
    override fun toString(): String = "ToolState($name)"
}

sealed class LifecycleState(
    override val name: String,
    override val validTransitions: Set<String>
) : AutomatonState() {
    object CREATED : LifecycleState("CREATED", setOf("SPAWN"))
    object SPAWNED : LifecycleState("SPAWNED", setOf("ASSIGN_KEY"))
    object KEY_ASSIGNED : LifecycleState("KEY_ASSIGNED", setOf("ASSIGN_TASK"))
    object TASK_ASSIGNED : LifecycleState("TASK_ASSIGNED", setOf("START"))
    object ACTIVE : LifecycleState("ACTIVE", setOf("PAUSE", "FINISH", "ERROR"))
    object PAUSED : LifecycleState("PAUSED", setOf("RESUME"))
    object FINISHED : LifecycleState("FINISHED", setOf("CLEANUP"))
    object ERROR : LifecycleState("ERROR", setOf("RETRY", "CLEANUP"))
    object TERMINATED : LifecycleState("TERMINATED", setOf("RECYCLE"))
    
    override fun toString(): String = "Lifecycle($name)"
}

data class ToolRequest(
    val requestId: String,
    val toolName: String,
    val agentId: String,
    val argsJson: String,
    val timestamp: Long = System.currentTimeMillis(),
    val deferred: CompletableDeferred<ToolResult>
)

data class ToolResult(
    val requestId: String,
    val toolName: String,
    val agentId: String,
    val result: String,
    val success: Boolean,
    val executionTimeMs: Long,
    val error: String? = null
)

data class AgentContext(
    val agentId: String,
    val name: String,
    val role: String,
    val currentTask: String?,
    val currentState: AgentAutomatonState,
    val lifecycleState: LifecycleState,
    val progress: Double,
    val activeTools: Set<String>,
    val lastActivity: Long,
    val findings: List<PartialFinding>,
    val dependencies: Set<String>,
    val dependents: Set<String>
)

class SharedAgentContext {
    private val logger = LoggerFactory.getLogger(SharedAgentContext::class.java)
    private val contexts = ConcurrentHashMap<String, AgentContext>()
    private val contextMutex = Mutex()
    
    suspend fun registerAgent(
        agentId: String,
        name: String,
        role: String
    ) = contextMutex.withLock {
        contexts[agentId] = AgentContext(
            agentId = agentId,
            name = name,
            role = role,
            currentTask = null,
            currentState = AgentAutomatonState.IDLE,
            lifecycleState = LifecycleState.CREATED,
            progress = 0.0,
            activeTools = emptySet(),
            lastActivity = System.currentTimeMillis(),
            findings = emptyList(),
            dependencies = emptySet(),
            dependents = emptySet()
        )
        logger.debug("Registered agent context: $agentId")
    }
    
    suspend fun updateAgentState(
        agentId: String,
        state: AgentAutomatonState
    ) = contextMutex.withLock {
        contexts[agentId]?.let { existing ->
            contexts[agentId] = existing.copy(
                currentState = state,
                lastActivity = System.currentTimeMillis()
            )
        }
    }
    
    suspend fun updateAgentTask(
        agentId: String,
        task: String,
        progress: Double
    ) = contextMutex.withLock {
        contexts[agentId]?.let { existing ->
            contexts[agentId] = existing.copy(
                currentTask = task,
                progress = progress,
                lastActivity = System.currentTimeMillis()
            )
        }
    }
    
    suspend fun addActiveTool(
        agentId: String,
        toolName: String
    ) = contextMutex.withLock {
        contexts[agentId]?.let { existing ->
            contexts[agentId] = existing.copy(
                activeTools = existing.activeTools + toolName,
                lastActivity = System.currentTimeMillis()
            )
        }
    }
    
    suspend fun removeActiveTool(
        agentId: String,
        toolName: String
    ) = contextMutex.withLock {
        contexts[agentId]?.let { existing ->
            contexts[agentId] = existing.copy(
                activeTools = existing.activeTools - toolName,
                lastActivity = System.currentTimeMillis()
            )
        }
    }
    
    suspend fun addFinding(
        agentId: String,
        finding: PartialFinding
    ) = contextMutex.withLock {
        contexts[agentId]?.let { existing ->
            contexts[agentId] = existing.copy(
                findings = existing.findings + finding,
                lastActivity = System.currentTimeMillis()
            )
        }
    }
    
    suspend fun addDependency(
        agentId: String,
        dependsOn: String
    ) = contextMutex.withLock {
        contexts[agentId]?.let { existing ->
            contexts[agentId] = existing.copy(
                dependencies = existing.dependencies + dependsOn
            )
        }
        contexts[dependsOn]?.let { existing ->
            contexts[dependsOn] = existing.copy(
                dependents = existing.dependents + agentId
            )
        }
    }
    
    fun getAgentContext(agentId: String): AgentContext? = contexts[agentId]
    
    fun getAllContexts(): Map<String, AgentContext> = contexts.toMap()
    
    fun getActiveContexts(): List<AgentContext> = contexts.values.filter {
        it.currentState == AgentAutomatonState.RUNNING || 
        it.currentState == AgentAutomatonState.WAITING
    }
    
    fun getCollaborationContextFor(agentId: String): String {
        val others = contexts.values.filter { it.agentId != agentId }
        if (others.isEmpty()) return "No other active agents."
        
        return buildString {
            appendLine("Current collaborative context:")
            appendLine("-".repeat(40))
            others.forEach { ctx ->
                appendLine("[${ctx.name}] ${ctx.agentId}")
                appendLine("  State: ${ctx.currentState.name}")
                appendLine("  Task: ${ctx.currentTask ?: "idle"}")
                appendLine("  Progress: ${(ctx.progress * 100).toInt()}%")
                if (ctx.activeTools.isNotEmpty()) {
                    appendLine("  Using tools: ${ctx.activeTools.joinToString(", ")}")
                }
                if (ctx.findings.isNotEmpty()) {
                    appendLine("  Recent finding: ${ctx.findings.last().findingType}")
                }
                appendLine()
            }
        }
    }
    
    suspend fun unregisterAgent(agentId: String) = contextMutex.withLock {
        contexts.remove(agentId)
    }
}

class ToolExecutionQueue(
    private val toolName: String,
    private val maxConcurrent: Int = 1,
    private val executor: suspend (String, String) -> String
) {
    private val logger = LoggerFactory.getLogger(ToolExecutionQueue::class.java)
    private val automaton = AutomatonTheory.ToolAccessAutomaton()
    private val semaphore = Semaphore(maxConcurrent)
    private val requestQueue = Channel<ToolRequest>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private var queueProcessor: Job? = null
    
    fun start() {
        queueProcessor = scope.launch {
            while (isActive) {
                val request = requestQueue.receive()
                processRequest(request)
            }
        }
        logger.info("Tool execution queue started for: $toolName")
    }
    
    fun stop() {
        queueProcessor?.cancel()
        logger.info("Tool execution queue stopped for: $toolName")
    }
    
    suspend fun enqueue(request: ToolRequest): ToolResult {
        requestQueue.send(request)
        return request.deferred.await()
    }
    
    private suspend fun processRequest(request: ToolRequest) {
        semaphore.acquire()
        
        try {
            if (!automaton.transition("ACQUIRE")) {
                request.deferred.complete(ToolResult(
                    requestId = request.requestId,
                    toolName = toolName,
                    agentId = request.agentId,
                    result = "Failed to acquire tool lock",
                    success = false,
                    executionTimeMs = 0,
                    error = "State transition failed"
                ))
                semaphore.release()
                return
            }
            
            automaton.transition("EXECUTE")
            val startTime = System.currentTimeMillis()
            
            val result = try {
                executor(request.agentId, request.argsJson)
            } catch (e: Exception) {
                automaton.transition("ERROR")
                logger.error("Tool $toolName execution failed for agent ${request.agentId}", e)
                "Error: ${e.message}"
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            
            automaton.transition("COMPLETE")
            automaton.transition("RELEASE")
            
            request.deferred.complete(ToolResult(
                requestId = request.requestId,
                toolName = toolName,
                agentId = request.agentId,
                result = result,
                success = !result.startsWith("Error"),
                executionTimeMs = executionTime
            ))
            
        } finally {
            semaphore.release()
        }
    }
    
    fun getState(): ToolAutomatonState = automaton.currentState
}

class ToolExecutionManager(
    private val sharedContext: SharedAgentContext
) {
    private val logger = LoggerFactory.getLogger(ToolExecutionManager::class.java)
    private val queues = ConcurrentHashMap<String, ToolExecutionQueue>()
    private val toolDefinitions = ConcurrentHashMap<String, suspend (String, String) -> String>()
    
    fun registerTool(
        toolName: String,
        maxConcurrent: Int,
        executor: suspend (String, String) -> String
    ) {
        val queue = ToolExecutionQueue(toolName, maxConcurrent, executor)
        queue.start()
        queues[toolName] = queue
        toolDefinitions[toolName] = executor
        logger.info("Registered tool with execution queue: $toolName (max concurrent: $maxConcurrent)")
    }
    
    suspend fun executeTool(
        toolName: String,
        agentId: String,
        argsJson: String
    ): ToolResult {
        val queue = queues[toolName]
        if (queue == null) {
            return ToolResult(
                requestId = java.util.UUID.randomUUID().toString(),
                toolName = toolName,
                agentId = agentId,
                result = "Tool not found: $toolName",
                success = false,
                executionTimeMs = 0,
                error = "Unknown tool"
            )
        }
        
        sharedContext.addActiveTool(agentId, toolName)
        
        val request = ToolRequest(
            requestId = java.util.UUID.randomUUID().toString(),
            toolName = toolName,
            agentId = agentId,
            argsJson = argsJson,
            deferred = CompletableDeferred()
        )
        
        logger.debug("Enqueued tool request: ${request.requestId} for agent $agentId")
        
        val result = queue.enqueue(request)
        
        sharedContext.removeActiveTool(agentId, toolName)
        
        return result
    }
    
    fun getToolStates(): Map<String, ToolAutomatonState> = 
        queues.mapValues { it.value.getState() }
    
    fun shutdown() {
        queues.values.forEach { it.stop() }
        queues.clear()
    }
    
    fun formatStatus(): String {
        return buildString {
            appendLine("Tool Execution Manager Status")
            appendLine("=".repeat(50))
            queues.forEach { (name, queue) ->
                appendLine("$name: ${queue.getState().name}")
            }
        }
    }
}

class CollaborativeAgentOrchestrator(
    private val keyPool: ApiKeyPool,
    private val sharedContext: SharedAgentContext,
    private val toolManager: ToolExecutionManager
) {
    private val logger = LoggerFactory.getLogger(CollaborativeAgentOrchestrator::class.java)
    private val agents = ConcurrentHashMap<String, OrchestratedAgent>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    data class OrchestratedAgent(
        val agentId: String,
        val config: AgentConfig,
        val stateAutomaton: AutomatonTheory.AgentStateAutomaton,
        val lifecycleAutomaton: AutomatonTheory.AgentLifecycleAutomaton
    )
    
    suspend fun createAgent(config: AgentConfig): OrchestratedAgent {
        val stateAutomaton = AutomatonTheory.AgentStateAutomaton()
        val lifecycleAutomaton = AutomatonTheory.AgentLifecycleAutomaton()
        
        stateAutomaton.transition("INITIALIZE")
        stateAutomaton.transition("READY")
        
        lifecycleAutomaton.transition("SPAWN")
        lifecycleAutomaton.transition("ASSIGN_KEY")
        lifecycleAutomaton.transition("ASSIGN_TASK")
        
        sharedContext.registerAgent(config.agentId, config.name, config.role)
        
        val agent = OrchestratedAgent(
            agentId = config.agentId,
            config = config,
            stateAutomaton = stateAutomaton,
            lifecycleAutomaton = lifecycleAutomaton
        )
        
        agents[config.agentId] = agent
        logger.info("Created orchestrated agent: ${config.agentId}")
        return agent
    }
    
    suspend fun startAgent(agentId: String, task: String): Boolean {
        val agent = agents[agentId] ?: return false
        
        if (!agent.stateAutomaton.transition("START")) {
            logger.warn("Cannot start agent $agentId - invalid state transition")
            return false
        }
        
        if (!agent.lifecycleAutomaton.transition("START")) {
            logger.warn("Cannot start agent $agentId - invalid lifecycle transition")
            return false
        }
        
        sharedContext.updateAgentTask(agentId, task, 0.0)
        sharedContext.updateAgentState(agentId, AgentAutomatonState.RUNNING)
        
        logger.info("Started agent $agentId with task: ${task.take(50)}...")
        return true
    }
    
    suspend fun pauseAgent(agentId: String): Boolean {
        val agent = agents[agentId] ?: return false
        
        if (!agent.stateAutomaton.transition("WAIT")) {
            return false
        }
        
        sharedContext.updateAgentState(agentId, AgentAutomatonState.WAITING)
        return true
    }
    
    suspend fun resumeAgent(agentId: String): Boolean {
        val agent = agents[agentId] ?: return false
        
        if (!agent.stateAutomaton.transition("RESUME")) {
            return false
        }
        
        sharedContext.updateAgentState(agentId, AgentAutomatonState.RUNNING)
        return true
    }
    
    suspend fun completeAgent(agentId: String, result: String): Boolean {
        val agent = agents[agentId] ?: return false
        
        if (!agent.stateAutomaton.transition("COMPLETE")) {
            return false
        }
        
        agent.lifecycleAutomaton.transition("FINISH")
        agent.lifecycleAutomaton.transition("CLEANUP")
        
        sharedContext.updateAgentState(agentId, AgentAutomatonState.COMPLETED)
        sharedContext.updateAgentTask(agentId, "Completed: $result", 1.0)
        
        keyPool.releaseAgentKey(agentId)
        return true
    }
    
    suspend fun failAgent(agentId: String, error: String): Boolean {
        val agent = agents[agentId] ?: return false
        
        if (!agent.stateAutomaton.transition("FAIL")) {
            agent.stateAutomaton.transition("COMPLETE")
        }
        
        agent.lifecycleAutomaton.transition("ERROR")
        agent.lifecycleAutomaton.transition("CLEANUP")
        
        sharedContext.updateAgentState(agentId, AgentAutomatonState.FAILED)
        
        keyPool.releaseAgentKey(agentId)
        return true
    }
    
    fun getAgentState(agentId: String): AgentAutomatonState? = 
        agents[agentId]?.stateAutomaton?.currentState
    
    fun getAgentLifecycle(agentId: String): LifecycleState? =
        agents[agentId]?.lifecycleAutomaton?.currentState
    
    fun getCollaborationContext(agentId: String): String =
        sharedContext.getCollaborationContextFor(agentId)
    
    fun formatOrchestrationStatus(): String {
        return buildString {
            appendLine("=".repeat(60))
            appendLine("COLLABORATIVE AGENT ORCHESTRATOR")
            appendLine("=".repeat(60))
            appendLine()
            appendLine("Key Pool: ${keyPool.size} keys, ${keyPool.getAvailableKeyCount()} available")
            appendLine("Active Agents: ${agents.size}")
            appendLine()
            appendLine(sharedContext.getCollaborationContextFor("system"))
            appendLine()
            appendLine(toolManager.formatStatus())
        }
    }
    
    fun shutdown() {
        agents.values.forEach { agent ->
            agent.stateAutomaton.transition("COMPLETE")
            agent.lifecycleAutomaton.transition("CLEANUP")
            keyPool.releaseAgentKey(agent.agentId)
        }
        toolManager.shutdown()
        scope.cancel()
    }
}
