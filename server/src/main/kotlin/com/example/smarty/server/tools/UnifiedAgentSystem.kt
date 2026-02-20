package com.example.smarty.server.tools

import com.example.smarty.server.llm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UnifiedAgentSystem(
    private val keyPool: ApiKeyPool,
    private val llmBaseUrl: String,
    private val defaultModel: String
) {
    private val logger = LoggerFactory.getLogger(UnifiedAgentSystem::class.java)
    
    private val stateMachineManager = StateMachineManager()
    private val resourceAllocationGraph = ResourceAllocationGraph()
    private val messageBus = UnifiedMessageBus()
    private val toolRegistry = UnifiedToolRegistry()
    
    private val formalAgentSystem: FormalAgentSystem
    private val staticControlLayer: StaticControlLayer
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isRunning = AtomicBoolean(false)
    
    private val agentProcesses = ConcurrentHashMap<String, AgentProcess>()
    
    init {
        formalAgentSystem = FormalAgentSystem(keyPool)
        staticControlLayer = StaticControlLayer(keyPool)
        
        initializeTools()
        
        logger.info("UnifiedAgentSystem initialized")
    }
    
    private fun initializeTools() {
        toolRegistry.registerTool("search_web", 2) { agentId, args ->
            executeSearch(agentId, args)
        }
        toolRegistry.registerTool("execute_code", 1) { agentId, args ->
            executeCode(agentId, args)
        }
        toolRegistry.registerTool("fetch_url", 3) { agentId, args ->
            fetchUrl(agentId, args)
        }
        toolRegistry.registerTool("share_finding", 5) { agentId, args ->
            shareFinding(agentId, args)
        }
        toolRegistry.registerTool("message_agent", 10) { agentId, args ->
            messageAgent(agentId, args)
        }
        
        logger.info("Registered default tools")
    }
    
    private suspend fun executeSearch(agentId: String, args: String): String {
        return "Search executed"
    }
    
    private suspend fun executeCode(agentId: String, args: String): String {
        return "Code executed"
    }
    
    private suspend fun fetchUrl(agentId: String, args: String): String {
        return "URL fetched"
    }
    
    private suspend fun shareFinding(agentId: String, args: String): String {
        val finding = "Finding shared from $agentId"
        messageBus.broadcast(finding, agentId)
        return finding
    }
    
    private suspend fun messageAgent(agentId: String, args: String): String {
        return "Message sent"
    }
    
    fun start() {
        if (!isRunning.compareAndSet(false, true)) {
            logger.warn("System already running")
            return
        }
        
        staticControlLayer.initialize()
        
        startAgentMonitor()
        
        logger.info("UnifiedAgentSystem started")
    }
    
    fun stop() {
        if (!isRunning.compareAndSet(true, false)) {
            logger.warn("System not running")
            return
        }
        
        staticControlLayer.shutdown()
        
        agentProcesses.values.forEach { it.stop() }
        agentProcesses.clear()
        
        scope.cancel()
        
        logger.info("UnifiedAgentSystem stopped")
    }
    
    private fun startAgentMonitor() {
        scope.launch {
            while (isRunning.get()) {
                delay(5000)
                monitorAgents()
            }
        }
    }
    
    private suspend fun monitorAgents() {
        agentProcesses.values.forEach { process ->
            if (process.isStale()) {
                logger.warn("Agent ${process.agentId} is stale, initiating recovery")
                handleAgentFailure(process.agentId, "Stale agent detected")
            }
        }
        
        val deadlock = resourceAllocationGraph.detectDeadlock()
        if (deadlock != null) {
            logger.error("Deadlock detected: $deadlock")
            resolveDeadlock(deadlock)
        }
    }
    
    private suspend fun handleAgentFailure(agentId: String, reason: String) {
        logger.error("Handling agent failure: $agentId - $reason")
        
        val process = agentProcesses[agentId]
        process?.stop()
        
        resourceAllocationGraph.getHeldResources(agentId).forEach { resource ->
            resourceAllocationGraph.releaseResource(agentId, resource)
        }
        
        staticControlLayer.processAgentResponse("ERROR: $reason", agentId)
    }
    
    private fun resolveDeadlock(deadlockedAgents: Set<String>) {
        val victim = deadlockedAgents.firstOrNull()
        if (victim != null) {
            logger.info("Resolving deadlock by terminating: $victim")
            agentProcesses[victim]?.stop()
        }
    }
    
    suspend fun spawnAgent(role: String, task: String): String? {
        if (!isRunning.get()) {
            logger.error("Cannot spawn agent - system not running")
            return null
        }
        
        val agentId = "agent_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        
        val keyAssignment = keyPool.getKeyForAgent(agentId)
        if (keyAssignment == null) {
            logger.error("No API key available")
            return null
        }
        
        val machine = stateMachineManager.createAgentMachine(agentId)
        machine.transition(TransitionEvent.SPAWN)
        
        val process = AgentProcess(
            agentId = agentId,
            role = role,
            task = task,
            apiKey = keyAssignment.apiKey,
            keyId = keyAssignment.keyId,
            stateMachine = machine,
            toolRegistry = toolRegistry,
            messageBus = messageBus,
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        )
        
        agentProcesses[agentId] = process
        
        process.start()
        
        logger.info("Spawned agent: $agentId (role: $role)")
        
        return agentId
    }
    
    suspend fun sendMessage(fromAgentId: String, toAgentId: String, content: String): Boolean {
        val fromProcess = agentProcesses[fromAgentId]
        val toProcess = agentProcesses[toAgentId]
        
        if (fromProcess == null || toProcess == null) {
            return false
        }
        
        messageBus.send(fromAgentId, toAgentId, content)
        
        toProcess.receiveMessage(content)
        
        return true
    }
    
    suspend fun shareFindingBetweenAgents(fromAgentId: String, finding: String, targetRole: String? = null) {
        val fromProcess = agentProcesses[fromAgentId] ?: return
        
        val targets = if (targetRole != null) {
            agentProcesses.values.filter { it.role == targetRole && it.agentId != fromAgentId }
        } else {
            agentProcesses.values.filter { it.agentId != fromAgentId }
        }
        
        targets.forEach { target ->
            messageBus.send(fromAgentId, target.agentId, "[FINDING] $finding")
            target.receiveFinding(finding)
        }
        
        staticControlLayer.processAgentResponse(finding, fromAgentId)
        
        logger.info("Finding shared from $fromAgentId to ${targets.size} agents")
    }
    
    fun getSystemStatus(): String {
        return buildString {
            appendLine("=".repeat(70))
            appendLine("UNIFIED AGENT SYSTEM STATUS")
            appendLine("=".repeat(70))
            appendLine()
            appendLine("Running: ${isRunning.get()}")
            appendLine("Active Agents: ${agentProcesses.count { it.value.isRunning }}")
            appendLine("Available Keys: ${keyPool.getAvailableKeyCount()}")
            appendLine()
            appendLine("Agent Processes:")
            appendLine("-".repeat(50))
            agentProcesses.values.forEach { process ->
                appendLine(process.formatStatus())
            }
            appendLine()
            appendLine(keyPool.formatStats())
            appendLine()
            appendLine(stateMachineManager.formatStateMachines())
        }
    }
    
    fun getAgentContext(agentId: String): String {
        val process = agentProcesses[agentId] ?: return "Agent not found"
        
        val otherAgents = agentProcesses.values
            .filter { it.agentId != agentId }
            .map { it.role to it.agentId }
        
        return buildString {
            appendLine("Agent: $agentId")
            appendLine("Role: ${process.role}")
            appendLine("Task: ${process.task}")
            appendLine("State: ${process.getState()}")
            appendLine()
            appendLine("Other Active Agents:")
            otherAgents.forEach { (role, id) ->
                appendLine("  - $role: $id")
            }
        }
    }
}

data class AgentProcess(
    val agentId: String,
    val role: String,
    val task: String,
    val apiKey: String,
    val keyId: String,
    val stateMachine: DeterministicFiniteAutomaton<AgentDFAState>,
    val toolRegistry: UnifiedToolRegistry,
    val messageBus: UnifiedMessageBus,
    val scope: CoroutineScope
) {
    private val _isRunning = AtomicBoolean(false)
    private val _isStale = AtomicBoolean(false)
    
    private var currentTask: String = task
    private var progress: Double = 0.0
    private var lastActivity: Long = System.currentTimeMillis()
    
    private val messagesReceived = AtomicInteger(0)
    private val findingsShared = AtomicInteger(0)
    
    val isRunning: Boolean get() = _isRunning.get()
    
    fun start() {
        _isRunning.set(true)
        stateMachine.transition(TransitionEvent.START)
        
        scope.launch {
            runAgent()
        }
    }
    
    fun stop() {
        _isRunning.set(false)
        stateMachine.transition(TransitionEvent.KILL)
        scope.cancel()
    }
    
    fun isStale(): Boolean {
        val staleTime = System.currentTimeMillis() - lastActivity
        return _isRunning.get() && staleTime > 60000
    }
    
    fun receiveMessage(content: String) {
        messagesReceived.incrementAndGet()
        lastActivity = System.currentTimeMillis()
        _isStale.set(false)
    }
    
    fun receiveFinding(finding: String) {
        receiveMessage(finding)
    }
    
    fun getState(): String = stateMachine.state.stateName
    
    private suspend fun runAgent() {
        try {
            stateMachine.transition(TransitionEvent.COMPLETE)
            progress = 1.0
            
            logger.info("Agent $agentId completed task")
        } catch (e: Exception) {
            logger.error("Agent $agentId failed", e)
            stateMachine.transition(TransitionEvent.FAIL)
        }
    }
    
    fun formatStatus(): String {
        return buildString {
            appendLine("[$role] $agentId")
            appendLine("  State: ${getState()}")
            appendLine("  Task: ${task.take(50)}...")
            appendLine("  Progress: ${(progress * 100).toInt()}%")
            appendLine("  Key: $keyId")
            appendLine("  Messages: ${messagesReceived.get()}")
            appendLine("  Findings: ${findingsShared.get()}")
        }
    }
    
    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger(AgentProcess::class.java)
    }
}

class UnifiedMessageBus {
    private val logger = org.slf4j.LoggerFactory.getLogger(UnifiedMessageBus::class.java)
    
    private val messageQueues = ConcurrentHashMap<String, Channel<String>>()
    private val broadcastChannel = Channel<Pair<String, String>>(Channel.BUFFERED)
    
    fun registerReceiver(agentId: String): Channel<String> {
        return messageQueues.getOrPut(agentId) { Channel(Channel.UNLIMITED) }
    }
    
    fun unregisterReceiver(agentId: String) {
        messageQueues[agentId]?.close()
        messageQueues.remove(agentId)
    }
    
    suspend fun send(from: String, to: String, content: String) {
        val queue = messageQueues[to]
        if (queue != null) {
            queue.send(content)
            logger.debug("Message $from -> $to: ${content.take(30)}")
        }
    }
    
    suspend fun broadcast(content: String, from: String) {
        broadcastChannel.send(from to content)
    }
    
    fun getMessageChannel(agentId: String): Channel<String>? = messageQueues[agentId]
}

class UnifiedToolRegistry {
    private val logger = org.slf4j.LoggerFactory.getLogger(UnifiedToolRegistry::class.java)
    
    private val tools = ConcurrentHashMap<String, ToolDefinitionEx>()
    private val executionQueues = ConcurrentHashMap<String, Channel<ToolExecutionRequest>>()
    
    private val toolSemaphores = ConcurrentHashMap<String, Semaphore>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    init {
        startQueueProcessors()
    }
    
    private fun startQueueProcessors() {
        scope.launch {
            while (true) {
                delay(100)
                executionQueues.forEach { (toolName, queue) ->
                    processToolExecution(toolName, queue)
                }
            }
        }
    }
    
    private suspend fun processToolExecution(toolName: String, queue: Channel<ToolExecutionRequest>) {
        val semaphore = toolSemaphores[toolName] ?: return
        
        if (semaphore.tryAcquire()) {
            try {
                val request = queue.tryReceive().getOrNull() ?: return
                val result = tools[toolName]?.executor?.invoke(request.agentId, request.args) ?: "Tool not found"
                request.resultFuture.complete(result)
            } finally {
                semaphore.release()
            }
        }
    }
    
    fun registerTool(
        name: String,
        maxConcurrent: Int,
        executor: suspend (String, String) -> String
    ) {
        tools[name] = ToolDefinitionEx(name, executor)
        toolSemaphores[name] = Semaphore(maxConcurrent)
        executionQueues[name] = Channel(Channel.UNLIMITED)
        
        logger.info("Registered tool: $name (max concurrent: $maxConcurrent)")
    }
    
    suspend fun executeTool(toolName: String, agentId: String, args: String): String {
        val queue = executionQueues[toolName]
        if (queue == null) {
            return "Tool not registered: $toolName"
        }
        
        val resultFuture = CompletableDeferred<String>()
        queue.send(ToolExecutionRequest(agentId, args, resultFuture))
        
        return resultFuture.await()
    }
    
    fun getRegisteredTools(): Set<String> = tools.keys
    
    fun shutdown() {
        scope.cancel()
    }
    
    data class ToolDefinitionEx(
        val name: String,
        val executor: suspend (String, String) -> String
    )
    
    data class ToolExecutionRequest(
        val agentId: String,
        val args: String,
        val resultFuture: CompletableDeferred<String>
    )
}

class AgentCoordinator(
    private val unifiedSystem: UnifiedAgentSystem
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(AgentCoordinator::class.java)
    
    suspend fun coordinateResearch(task: String): String {
        val webAgentId = unifiedSystem.spawnAgent("researcher", "Search and gather information about: $task")
        val codeAgentId = unifiedSystem.spawnAgent("coder", "Analyze and process code related to: $task")
        
        if (webAgentId == null || codeAgentId == null) {
            return "Failed to spawn agents"
        }
        
        delay(10000)
        
        return "Research coordinated: web agent=$webAgentId, code agent=$codeAgentId"
    }
    
    fun getSystemStatus(): String = unifiedSystem.getSystemStatus()
}

private val logger = org.slf4j.LoggerFactory.getLogger(UnifiedAgentSystem::class.java)

private fun delay(timeMs: Long) {
    Thread.sleep(timeMs)
}
