package com.example.smarty.server.tools

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.PriorityBlockingQueue
import kotlin.math.*
import kotlin.random.Random

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
    
    class NeuralAutomaton(
        private val inputDim: Int = 32,
        private val hiddenDim: Int = 64,
        private val outputDim: Int = 16
    ) {
        private val weights1 = Array(hiddenDim) { DoubleArray(inputDim) { Random.nextDouble(-0.5, 0.5) } }
        private val weights2 = Array(outputDim) { DoubleArray(hiddenDim) { Random.nextDouble(-0.5, 0.5) } }
        private val bias1 = DoubleArray(hiddenDim) { Random.nextDouble(-0.1, 0.1) }
        private val bias2 = DoubleArray(outputDim) { Random.nextDouble(-0.1, 0.1) }
        
        fun forward(input: DoubleArray): DoubleArray {
            val hidden = DoubleArray(hiddenDim)
            for (i in 0 until hiddenDim) {
                var sum = bias1[i]
                for (j in input.indices) {
                    sum += weights1[i][j] * input[j]
                }
                hidden[i] = tanh(sum)
            }
            
            val output = DoubleArray(outputDim)
            for (i in 0 until outputDim) {
                var sum = bias2[i]
                for (j in 0 until hiddenDim) {
                    sum += weights2[i][j] * hidden[j]
                }
                output[i] = sigmoid(sum)
            }
            
            return output
        }
        
        private fun sigmoid(x: Double) = 1.0 / (1.0 + exp(-x))
        
        fun predictNextState(currentState: DoubleArray, action: DoubleArray): DoubleArray {
            val input = DoubleArray(inputDim)
            for (i in currentState.indices.coerceAtMost(input.size - 1)) {
                input[i] = currentState[i]
            }
            for (i in action.indices.coerceAtMost(input.size - currentState.size - 1)) {
                input[currentState.indices.size + i] = action[i]
            }
            return forward(input)
        }
        
        fun train(input: DoubleArray, target: DoubleArray, learningRate: Double = 0.01) {
            val output = forward(input)
            val outputError = DoubleArray(outputDim)
            
            for (i in 0 until outputDim) {
                outputError[i] = (target[i] - output[i]) * output[i] * (1 - output[i])
            }
            
            for (i in 0 until outputDim) {
                for (j in 0 until hiddenDim) {
                    weights2[i][j] += learningRate * outputError[i] * forward(input)[j]
                }
            }
        }
    }
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
    val deferred: CompletableDeferred<ToolResult>,
    val priority: Int = 5,
    val deadline: Long = System.currentTimeMillis() + 30000
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
    val dependents: Set<String>,
    val stateEmbedding: DoubleArray = DoubleArray(32),
    val attentionWeights: Map<String, Double> = emptyMap()
)

class SharedAgentContext {
    private val logger = LoggerFactory.getLogger(SharedAgentContext::class.java)
    private val contexts = ConcurrentHashMap<String, AgentContext>()
    private val contextMutex = Mutex()
    
    private val neuralPredictor = AutomatonTheory.NeuralAutomaton()
    private val attentionMechanism = AttentionMechanism()
    private val stateEmbedder = StateEmbedder()
    private val relationshipGraph = AgentRelationshipGraph()
    
    suspend fun registerAgent(
        agentId: String,
        name: String,
        role: String
    ) = contextMutex.withLock {
        val embedding = stateEmbedder.embed("$name:$role")
        
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
            dependents = emptySet(),
            stateEmbedding = embedding
        )
        
        relationshipGraph.addNode(agentId, role)
        logger.debug("Registered agent context: $agentId")
    }
    
    suspend fun updateAgentState(
        agentId: String,
        state: AgentAutomatonState
    ) = contextMutex.withLock {
        contexts[agentId]?.let { existing ->
            val embedding = stateEmbedder.embed("$existing.name:${state.name}")
            val attention = attentionMechanism.computeAttention(embedding, getAllEmbeddings())
            
            contexts[agentId] = existing.copy(
                currentState = state,
                lastActivity = System.currentTimeMillis(),
                stateEmbedding = embedding,
                attentionWeights = attention
            )
            
            relationshipGraph.updateState(agentId, state.name)
        }
    }
    
    suspend fun updateAgentTask(
        agentId: String,
        task: String,
        progress: Double
    ) = contextMutex.withLock {
        contexts[agentId]?.let { existing ->
            val embedding = stateEmbedder.embed(task)
            
            contexts[agentId] = existing.copy(
                currentTask = task,
                progress = progress,
                lastActivity = System.currentTimeMillis(),
                stateEmbedding = embedding
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
        
        relationshipGraph.addEdge(agentId, toolName, "uses")
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
        
        relationshipGraph.addEdge(agentId, dependsOn, "depends_on")
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
    
    fun predictNextState(agentId: String, action: String): String? {
        val context = contexts[agentId] ?: return null
        val actionEmbedding = stateEmbedder.embed(action)
        val prediction = neuralPredictor.predictNextState(context.stateEmbedding, actionEmbedding)
        return stateEmbedder.decode(prediction)
    }
    
    fun getInfluentialAgents(agentId: String, topK: Int = 3): List<String> {
        return attentionMechanism.getTopAttention(agentId, contexts.values.toList(), topK)
    }
    
    fun getAgentGraph(): AgentRelationshipGraph = relationshipGraph
    
    private fun getAllEmbeddings(): List<Pair<String, DoubleArray>> {
        return contexts.values.map { it.agentId to it.stateEmbedding }.toList()
    }
    
    suspend fun unregisterAgent(agentId: String) = contextMutex.withLock {
        contexts.remove(agentId)
        relationshipGraph.removeNode(agentId)
    }
}

class StateEmbedder(
    private val embeddingDim: Int = 32
) {
    private val vocabulary = ConcurrentHashMap<String, Int>()
    private val embeddings = ConcurrentHashMap<String, DoubleArray>()
    
    init {
        initializeDefaultEmbeddings()
    }
    
    private fun initializeDefaultEmbeddings() {
        val defaultTerms = listOf("IDLE", "RUNNING", "WAITING", "COMPLETED", "FAILED", 
            "agent", "task", "tool", "result", "error", "progress", "waiting")
        defaultTerms.forEachIndexed { index, term ->
            val embedding = DoubleArray(embeddingDim) { i ->
                sin((index + 1) * (i + 1) * 0.1)
            }
            embeddings[term] = normalize(embedding)
            vocabulary[term] = index
        }
    }
    
    fun embed(text: String): DoubleArray {
        val words = text.lowercase().split(" ")
        val embedding = DoubleArray(embeddingDim)
        
        words.forEach { word ->
            val existing = embeddings[word]
            if (existing != null) {
                for (i in embedding.indices) {
                    embedding[i] += existing[i]
                }
            } else {
                val hash = word.hashCode()
                val newEmbedding = DoubleArray(embeddingDim) { i ->
                    sin((hash + i) * 0.1)
                }
                for (i in embedding.indices) {
                    embedding[i] += newEmbedding[i]
                }
            }
        }
        
        return normalize(embedding)
    }
    
    private fun normalize(vector: DoubleArray): DoubleArray {
        val norm = sqrt(vector.sumOf { it * it })
        return if (norm > 0) {
            DoubleArray(vector.size) { vector[it] / norm }
        } else vector
    }
    
    fun decode(embedding: DoubleArray): String {
        var maxSimilarity = -1.0
        var closest = "UNKNOWN"
        
        embeddings.forEach { (term, existing) ->
            val similarity = cosineSimilarity(embedding, existing)
            if (similarity > maxSimilarity) {
                maxSimilarity = similarity
                closest = term
            }
        }
        
        return closest
    }
    
    private fun cosineSimilarity(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size)
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA > 0 && normB > 0) dotProduct / (sqrt(normA) * sqrt(normB)) else 0.0
    }
}

class AttentionMechanism(
    private val attentionDim: Int = 16
) {
    private val attentionWeights = ConcurrentHashMap<String, MutableMap<String, Double>>()
    
    fun computeAttention(query: DoubleArray, keys: List<Pair<String, DoubleArray>>): Map<String, Double> {
        val scores = mutableMapOf<String, Double>()
        
        keys.forEach { (id, key) ->
            val score = dotProduct(query, key)
            scores[id] = softmax(score)
        }
        
        return scores
    }
    
    fun getTopAttention(sourceId: String, allContexts: List<AgentContext>, topK: Int): List<String> {
        val weights = attentionWeights[sourceId] ?: return emptyList()
        return weights.entries.sortedByDescending { it.value }.take(topK).map { it.key }
    }
    
    private fun dotProduct(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size)
        return a.indices.sumOf { a[it] * b[it] }
    }
    
    private fun softmax(x: Double): Double {
        return exp(x) / exp(x)
    }
}

class AgentRelationshipGraph {
    private val nodes = ConcurrentHashMap<String, GraphNode>()
    private val edges = ConcurrentHashMap<String, MutableList<GraphEdge>>()
    
    fun addNode(agentId: String, role: String) {
        nodes[agentId] = GraphNode(agentId, role, System.currentTimeMillis())
        edges.getOrPut(agentId) { mutableListOf() }
    }
    
    fun removeNode(agentId: String) {
        nodes.remove(agentId)
        edges.remove(agentId)
        edges.values.forEach { list ->
            list.removeAll { it.target == agentId }
        }
    }
    
    fun addEdge(from: String, to: String, type: String) {
        edges.getOrPut(from) { mutableListOf() }
            .add(GraphEdge(from, to, type, System.currentTimeMillis()))
    }
    
    fun updateState(agentId: String, state: String) {
        nodes[agentId]?.state = state
    }
    
    fun getNeighbors(agentId: String): List<String> {
        return edges[agentId]?.map { it.target } ?: emptyList()
    }
    
    fun getPageRank(iterations: Int = 20): Map<String, Double> {
        val ranks = nodes.keys.associateWith { 1.0 }.toMutableMap()
        
        repeat(iterations) {
            val newRanks = mutableMapOf<String, Double>()
            nodes.keys.forEach { node ->
                var rank = 0.0
                edges.forEach { (_, edges) ->
                    edges.filter { it.target == node }.forEach { edge ->
                        rank += (ranks[edge.source] ?: 1.0) / (edges.size.coerceAtLeast(1))
                    }
                }
                newRanks[node] = 0.15 + 0.85 * rank
            }
            ranks.putAll(newRanks)
        }
        
        return ranks
    }
    
    data class GraphNode(
        val id: String,
        val role: String,
        val createdAt: Long,
        var state: String = "IDLE"
    )
    
    data class GraphEdge(
        val source: String,
        val target: String,
        val type: String,
        val timestamp: Long
    )
}

class ToolExecutionQueue(
    private val toolName: String,
    private val maxConcurrent: Int = 1,
    private val executor: suspend (String, String) -> String
) {
    private val logger = LoggerFactory.getLogger(ToolExecutionQueue::class.java)
    private val automaton = AutomatonTheory.ToolAccessAutomaton()
    private val semaphore = Semaphore(maxConcurrent)
    private val requestQueue = PriorityBlockingQueue<ToolRequest>(100) { a, b ->
        b.priority.compareTo(a.priority)
    }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private var queueProcessor: Job? = null
    
    private val executionMetrics = ExecutionMetrics()
    private val adaptiveScheduler = AdaptiveScheduler()
    private val circuitBreaker = CircuitBreaker()
    
    fun start() {
        queueProcessor = scope.launch {
            while (isActive) {
                val request = requestQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (request != null && !circuitBreaker.isOpen) {
                    processRequest(request)
                }
            }
        }
        logger.info("Tool execution queue started for: $toolName")
    }
    
    fun stop() {
        queueProcessor?.cancel()
        logger.info("Tool execution queue stopped for: $toolName")
    }
    
    suspend fun enqueue(request: ToolRequest): ToolResult {
        requestQueue.put(request)
        return request.deferred.await()
    }
    
    private suspend fun processRequest(request: ToolRequest) {
        if (request.deadline < System.currentTimeMillis()) {
            request.deferred.complete(ToolResult(
                requestId = request.requestId,
                toolName = toolName,
                agentId = request.agentId,
                result = "Request timed out",
                success = false,
                executionTimeMs = System.currentTimeMillis() - request.timestamp,
                error = "Deadline exceeded"
            ))
            return
        }
        
        semaphore.acquire()
        circuitBreaker.recordAttempt()
        
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
                executionMetrics.recordStart(request.requestId)
                executor(request.agentId, request.argsJson)
            } catch (e: Exception) {
                automaton.transition("ERROR")
                circuitBreaker.recordFailure()
                logger.error("Tool $toolName execution failed for agent ${request.agentId}", e)
                "Error: ${e.message}"
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            
            automaton.transition("COMPLETE")
            automaton.transition("RELEASE")
            
            executionMetrics.recordComplete(request.requestId, executionTime, result.startsWith("Error"))
            circuitBreaker.recordSuccess()
            
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
    
    fun getMetrics(): ExecutionMetrics = executionMetrics
    
    fun getCircuitBreakerState(): String = circuitBreaker.state.name
    
    class ExecutionMetrics {
        private val executions = ConcurrentLinkedQueue<ExecutionRecord>()
        private var totalExecutions = 0L
        private var totalFailures = 0L
        private var totalDuration = 0L
        
        fun recordStart(requestId: String) {
            executions.add(ExecutionRecord(requestId, System.currentTimeMillis(), null, null))
        }
        
        fun recordComplete(requestId: String, duration: Long, failed: Boolean) {
            totalExecutions++
            totalDuration += duration
            if (failed) totalFailures++
            
            executions.add(ExecutionRecord(requestId, System.currentTimeMillis(), duration, failed))
            if (executions.size > 1000) executions.remove()
        }
        
        fun getSuccessRate(): Double = 
            if (totalExecutions > 0) (totalExecutions - totalFailures).toDouble() / totalExecutions else 1.0
        
        fun getAverageDuration(): Double = 
            if (totalExecutions > 0) totalDuration.toDouble() / totalExecutions else 0.0
        
        data class ExecutionRecord(
            val requestId: String,
            val startTime: Long,
            val duration: Long?,
            val failed: Boolean?
        )
    }
    
    class CircuitBreaker(
        private val failureThreshold: Int = 5,
        private val timeout: Long = 30000
    ) {
        private var failures = 0
        private var lastFailureTime = 0L
        var state: CircuitState = CircuitState.CLOSED
            private set
        
        fun recordSuccess() {
            failures = 0
            state = CircuitState.CLOSED
        }
        
        fun recordFailure() {
            failures++
            lastFailureTime = System.currentTimeMillis()
            if (failures >= failureThreshold) {
                state = CircuitState.OPEN
            }
        }
        
        fun recordAttempt() {
            if (state == CircuitState.OPEN && 
                System.currentTimeMillis() - lastFailureTime > timeout) {
                state = CircuitState.HALF_OPEN
            }
        }
        
        fun isOpen: Boolean = state == CircuitState.OPEN
    }
    
    enum class CircuitState {
        CLOSED, OPEN, HALF_OPEN
    }
    
    class AdaptiveScheduler {
        private var currentLoad = 0.0
        private var historicalLatency = mutableListOf<Long>()
        
        fun calculatePriority(request: ToolRequest): Int {
            val age = System.currentTimeMillis() - request.timestamp
            val deadlineImportance = if (request.deadline < System.currentTimeMillis() + 5000) 10 else 0
            
            return request.priority + (age / 10000).toInt() + deadlineImportance
        }
        
        fun updateLoad(latency: Long) {
            historicalLatency.add(latency)
            if (historicalLatency.size > 100) historicalLatency.removeAt(0)
            currentLoad = historicalLatency.average()
        }
        
        fun shouldThrottle(): Boolean = currentLoad > 1000
    }
}

class ToolExecutionManager(
    private val sharedContext: SharedAgentContext
) {
    private val logger = LoggerFactory.getLogger(ToolExecutionManager::class.java)
    private val queues = ConcurrentHashMap<String, ToolExecutionQueue>()
    private val toolDefinitions = ConcurrentHashMap<String, suspend (String, String) -> String>()
    
    private val toolRouter = IntelligentToolRouter()
    private val toolMonitor = ToolMonitor()
    private val toolCache = ToolResultCache()
    
    fun registerTool(
        toolName: String,
        maxConcurrent: Int,
        executor: suspend (String, String) -> String
    ) {
        val queue = ToolExecutionQueue(toolName, maxConcurrent, executor)
        queue.start()
        queues[toolName] = queue
        toolDefinitions[toolName] = executor
        toolRouter.registerTool(toolName)
        logger.info("Registered tool with execution queue: $toolName (max concurrent: $maxConcurrent)")
    }
    
    suspend fun executeTool(
        toolName: String,
        agentId: String,
        argsJson: String
    ): ToolResult {
        val cached = toolCache.get(toolName, argsJson)
        if (cached != null) {
            logger.debug("Cache hit for $toolName")
            return cached
        }
        
        val selectedTool = toolRouter.selectTool(toolName, agentId, argsJson)
        
        val queue = queues[selectedTool]
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
        
        sharedContext.addActiveTool(agentId, selectedTool)
        
        val request = ToolRequest(
            requestId = java.util.UUID.randomUUID().toString(),
            toolName = selectedTool,
            agentId = agentId,
            argsJson = argsJson,
            deferred = CompletableDeferred()
        )
        
        logger.debug("Enqueued tool request: ${request.requestId} for agent $agentId")
        
        val result = queue.enqueue(request)
        
        sharedContext.removeActiveTool(agentId, selectedTool)
        
        toolMonitor.recordExecution(selectedTool, result.executionTimeMs, result.success)
        toolRouter.updateMetrics(selectedTool, result.success, result.executionTimeMs)
        
        if (result.success) {
            toolCache.put(toolName, argsJson, result)
        }
        
        return result
    }
    
    fun getToolStates(): Map<String, ToolAutomatonState> = 
        queues.mapValues { it.value.getState() }
    
    fun getAllMetrics(): Map<String, ToolExecutionQueue.ExecutionMetrics> =
        queues.mapValues { it.value.getMetrics() }
    
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
                appendLine("  Circuit Breaker: ${queue.getCircuitBreakerState()}")
                val metrics = queue.getMetrics()
                appendLine("  Success Rate: ${"%.2f".format(metrics.getSuccessRate() * 100)}%")
                appendLine("  Avg Duration: ${"%.2f".format(metrics.getAverageDuration())}ms")
            }
        }
    }
    
    class IntelligentToolRouter {
        private val toolMetrics = ConcurrentHashMap<String, ToolMetrics>()
        private val toolAlternatives = ConcurrentHashMap<String, MutableList<String>>()
        
        fun registerTool(toolName: String) {
            toolMetrics[toolName] = ToolMetrics(toolName)
        }
        
        fun registerAlternative(toolName: String, alternative: String) {
            toolAlternatives.getOrPut(toolName) { mutableListOf() }.add(alternative)
        }
        
        fun selectTool(toolName: String, agentId: String, args: String): String {
            val metrics = toolMetrics[toolName] ?: return toolName
            
            if (metrics.successRate < 0.5) {
                val alternatives = toolAlternatives[toolName]
                if (!alternatives.isNullOrEmpty()) {
                    val best = alternatives.maxByOrNull { alt ->
                        toolMetrics[alt]?.successRate ?: 0.0
                    }
                    if (best != null && (toolMetrics[best]?.successRate ?: 0.0) > 0.7) {
                        return best
                    }
                }
            }
            
            return toolName
        }
        
        fun updateMetrics(toolName: String, success: Boolean, latency: Long) {
            toolMetrics[toolName]?.record(success, latency)
        }
        
        data class ToolMetrics(
            val toolName: String,
            var totalCalls: Int = 0,
            var successfulCalls: Int = 0,
            var totalLatency: Long = 0
        ) {
            val successRate: Double
                get() = if (totalCalls > 0) successfulCalls.toDouble() / totalCalls else 1.0
            
            val averageLatency: Double
                get() = if (totalCalls > 0) totalLatency.toDouble() / totalCalls else 0.0
            
            fun record(success: Boolean, latency: Long) {
                totalCalls++
                if (success) successfulCalls++
                totalLatency += latency
            }
        }
    }
    
    class ToolMonitor {
        private val alerts = ConcurrentLinkedQueue<Alert>()
        
        fun recordExecution(toolName: String, latency: Long, success: Boolean) {
            if (latency > 5000) {
                alerts.add(Alert(toolName, "HIGH_LATENCY", latency, System.currentTimeMillis()))
            }
            if (!success) {
                alerts.add(Alert(toolName, "FAILURE", latency, System.currentTimeMillis()))
            }
        }
        
        fun getAlerts(): List<Alert> = alerts.toList()
        
        data class Alert(
            val toolName: String,
            val type: String,
            val value: Long,
            val timestamp: Long
        )
    }
    
    class ToolResultCache(
        private val maxSize: Int = 100,
        private val ttl: Long = 60000
    ) {
        private val cache = ConcurrentHashMap<String, CachedResult>()
        
        fun get(toolName: String, args: String): ToolResult? {
            val key = "$toolName:$args".hashCode().toString()
            val cached = cache[key] ?: return null
            
            return if (System.currentTimeMillis() - cached.timestamp < ttl) {
                cached.result
            } else {
                cache.remove(key)
                null
            }
        }
        
        fun put(toolName: String, args: String, result: ToolResult) {
            val key = "$toolName:$args".hashCode().toString()
            cache[key] = CachedResult(result, System.currentTimeMillis())
            
            if (cache.size > maxSize) {
                val oldest = cache.entries.minByOrNull { it.value.timestamp }
                oldest?.let { cache.remove(it.key) }
            }
        }
        
        data class CachedResult(
            val result: ToolResult,
            val timestamp: Long
        )
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
    
    private val reinforcementLearner = ReinforcementLearner()
    private val taskScheduler = PriorityTaskScheduler()
    private val chaosEngine = ChaosEngineeringEngine()
    private val consensusProtocol = ConsensusProtocol()
    
    data class OrchestratedAgent(
        val agentId: String,
        val config: AgentConfig,
        val stateAutomaton: AutomatonTheory.AgentStateAutomaton,
        val lifecycleAutomaton: AutomatonTheory.AgentLifecycleAutomaton,
        val qTable: MutableMap<String, Double> = mutableMapOf()
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
        
        initializeQTable(agent)
        
        logger.info("Created orchestrated agent: ${config.agentId}")
        return agent
    }
    
    private fun initializeQTable(agent: OrchestratedAgent) {
        val actions = listOf("START", "PAUSE", "RESUME", "COMPLETE", "FAIL")
        val states = listOf("IDLE", "INITIALIZING", "READY", "RUNNING", "WAITING", "COMPLETED", "FAILED")
        
        states.forEach { state ->
            actions.forEach { action ->
                agent.qTable["$state:$action"] = 0.0
            }
        }
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
        
        taskScheduler.scheduleTask(agentId, task)
        
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
        
        reinforcementLearner.updateQValue(agent, "COMPLETE", 1.0)
        
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
        
        reinforcementLearner.updateQValue(agent, "FAIL", -1.0)
        
        return true
    }
    
    suspend fun executeAction(agentId: String, action: String): Double {
        val agent = agents[agentId] ?: return 0.0
        val state = agent.stateAutomaton.currentState.name
        
        val qValue = agent.qTable["$state:$action"] ?: 0.0
        
        return qValue
    }
    
    fun getBestAction(agentId: String): String? {
        val agent = agents[agentId] ?: return null
        val state = agent.stateAutomaton.currentState.name
        
        return agent.qTable.entries
            .filter { it.key.startsWith("$state:") }
            .maxByOrNull { it.value }
            ?.key?.substringAfter(":")
    }
    
    fun getAgentState(agentId: String): AgentAutomatonState? = 
        agents[agentId]?.stateAutomaton?.currentState
    
    fun getAgentLifecycle(agentId: String): LifecycleState? =
        agents[agentId]?.lifecycleAutomaton?.currentState
    
    fun getCollaborationContext(agentId: String): String =
        sharedContext.getCollaborationContextFor(agentId)
    
    fun getAgentGraph(): AgentRelationshipGraph = sharedContext.getAgentGraph()
    
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
            appendLine()
            appendLine("Agent Relationships (PageRank):")
            val pagerank = getAgentGraph().getPageRank()
            pagerank.entries.sortedByDescending { it.value }.take(5).forEach { (id, rank) ->
                appendLine("  $id: ${"%.4f".format(rank)}")
            }
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
    
    class ReinforcementLearner(
        private val learningRate: Double = 0.1,
        private val discountFactor: Double = 0.9,
        private val explorationRate: Double = 0.1
    ) {
        fun updateQValue(agent: OrchestratedAgent, action: String, reward: Double) {
            val state = agent.stateAutomaton.currentState.name
            val key = "$state:$action"
            
            val currentQ = agent.qTable[key] ?: 0.0
            val maxNextQ = agent.qTable.entries
                .filter { it.key.startsWith("${agent.stateAutomaton.currentState.name}:") }
                .maxByOrNull { it.value }?.value ?: 0.0
            
            val newQ = currentQ + learningRate * (reward + discountFactor * maxNextQ - currentQ)
            agent.qTable[key] = newQ
        }
        
        fun shouldExplore(): Boolean = Random.nextDouble() < explorationRate
    }
    
    class PriorityTaskScheduler {
        private val taskQueue = PriorityBlockingQueue<ScheduledTask>(100) { a, b ->
            b.priority.compareTo(a.priority)
        }
        
        fun scheduleTask(agentId: String, task: String, priority: Int = 5) {
            taskQueue.add(ScheduledTask(agentId, task, priority, System.currentTimeMillis()))
        }
        
        fun getNextTask(): ScheduledTask? = taskQueue.poll()
        
        data class ScheduledTask(
            val agentId: String,
            val task: String,
            val priority: Int,
            val scheduledAt: Long
        )
    }
    
    class ChaosEngineeringEngine {
        private val failureScenarios = listOf(
            FailureScenario("network_latency", 0.1, { Random.nextLong(1000, 5000) }),
            FailureScenario("resource_exhaustion", 0.05, { throw OutOfMemoryError("Chaos injection") }),
            FailureScenario("random_crash", 0.02, { throw RuntimeException("Chaos injection") })
        )
        
        fun injectFailure(): Boolean {
            return failureScenarios.any { scenario ->
                if (Random.nextDouble() < scenario.probability) {
                    scenario.effect()
                    true
                } else false
            }
        }
        
        data class FailureScenario(
            val name: String,
            val probability: Double,
            val effect: () -> Unit
        )
    }
    
    class ConsensusProtocol {
        private val votes = ConcurrentHashMap<String, MutableMap<String, Boolean>>()
        
        fun propose(agentId: String, proposal: String): Boolean {
            votes.getOrPut(proposal) { mutableMapOf() }[agentId] = true
            
            val allVotes = votes[proposal] ?: return false
            return allVotes.values.count { it } > allVotes.size / 2
        }
        
        fun vote(agentId: String, proposal: String, approval: Boolean) {
            votes.getOrPut(proposal) { mutableMapOf() }[agentId] = approval
        }
        
        fun getConsensus(proposal: String): Boolean? {
            val allVotes = votes[proposal] ?: return null
            val approved = allVotes.values.count { it }
            return when {
                approved > allVotes.size / 2 -> true
                approved < allVotes.size / 2 -> false
                else -> null
            }
        }
    }
}
