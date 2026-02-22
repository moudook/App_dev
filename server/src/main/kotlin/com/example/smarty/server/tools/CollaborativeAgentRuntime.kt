package com.example.smarty.server.tools

import com.example.smarty.server.llm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.*
import kotlin.random.Random

data class AgentConfig(
    val agentId: String,
    val name: String,
    val role: String,
    val apiKey: String,
    val keyId: String,
    val parentAgentId: String? = null,
    val sharedTools: List<String> = emptyList(),
    val dedicatedTools: List<String> = emptyList(),
    val canSpawnAgents: Boolean = true,
    val canMessageAgents: Boolean = true,
    val maxIterations: Int = 10
)

data class AgentMessage(
    val messageId: String,
    val fromAgentId: String,
    val toAgentId: String?,
    val type: MessageType,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val priority: Int = 5,
    val ttl: Int = 5,
    val traceId: String = UUID.randomUUID().toString()
) {
    enum class MessageType {
        TASK, RESULT, PARTIAL_FINDING, INSIGHT, REQUEST_HELP, 
        BROADCAST, STATUS_UPDATE, TOOL_SHARE, DIRECT_CALL, ERROR,
        GOSSIP, HEARTBEAT, CONSENSUS, NEGOTIATION, VOTE
    }
}

data class PartialFinding(
    val findingType: String,
    val relevance: String,
    val data: String,
    val suggestedRecipient: String? = null,
    val confidence: Double = 0.8,
    val timestamp: Long = System.currentTimeMillis()
)

data class AgentState(
    val agentId: String,
    val status: AgentStatus,
    val currentTask: String?,
    val progress: Double,
    val messagesReceived: Int,
    val messagesSent: Int,
    val findingsShared: Int,
    val createdAt: Long,
    val lastActivity: Long,
    val energy: Double = 1.0,
    val attention: Map<String, Double> = emptyMap()
) {
    enum class AgentStatus {
        IDLE, RUNNING, WAITING, COMPLETED, FAILED, SUSPENDED, NEGOTIATING
    }
}

interface CollaborativeAgent {
    val agentId: String
    val config: AgentConfig
    val state: AgentState
    
    suspend fun start(task: String): Flow<AgentEvent>
    suspend fun receiveMessage(message: AgentMessage)
    suspend fun shareFinding(finding: PartialFinding)
    suspend fun requestHelp(from: String, description: String)
    suspend fun callAgent(targetAgentId: String, task: String, waitForResult: Boolean): String?
    suspend fun stop()
}

sealed class AgentEvent {
    data class MessageReceived(val message: AgentMessage) : AgentEvent()
    data class FindingShared(val finding: PartialFinding, val sharedWith: List<String>) : AgentEvent()
    data class TaskProgress(val progress: Double, val update: String) : AgentEvent()
    data class ToolCall(val toolName: String, val args: Map<String, Any>) : AgentEvent()
    data class ToolResult(val toolName: String, val result: String) : AgentEvent()
    data class StatusChange(val oldStatus: AgentState.AgentStatus, val newStatus: AgentState.AgentStatus) : AgentEvent()
    data class SpawnedAgent(val childAgentId: String, val task: String) : AgentEvent()
    data class Completed(val result: String) : AgentEvent()
    data class Error(val message: String, val throwable: Throwable? = null) : AgentEvent()
    data class StreamingOutput(val content: String) : AgentEvent()
    data class ReputationUpdate(val agentId: String, val delta: Double, val newScore: Double) : AgentEvent()
    data class GossipReceived(val gossip: GossipMessage) : AgentEvent()
    data class ConsensusReached(val topic: String, val decision: String) : AgentEvent()
}

data class GossipMessage(
    val type: GossipType,
    val sourceAgentId: String,
    val content: Map<String, Any>,
    val timestamp: Long = System.currentTimeMillis(),
    val ttl: Int = 3
) {
    enum class GossipType { REPUTATION, CAPABILITY, LOAD, FINDING, INTEREST }
}

data class ReputationScore(
    val agentId: String,
    val score: Double = 0.5,
    val totalInteractions: Int = 0,
    val successfulInteractions: Int = 0,
    val failedInteractions: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis(),
    val history: List<ReputationDelta> = emptyList()
)

data class ReputationDelta(
    val timestamp: Long,
    val delta: Double,
    val reason: String
)

data class AgentCapability(
    val name: String,
    val quality: Double,
    val reliability: Double,
    val load: Double,
    val tags: Set<String>
)

class CollaborativeAgentRuntime(
    private val llmProviderFactory: (apiKey: String) -> LlmProvider,
    private val toolRegistry: SharedToolRegistry,
    private val messageBus: AgentMessageBus
) {
    private val logger = LoggerFactory.getLogger(CollaborativeAgentRuntime::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val agents = ConcurrentHashMap<String, CollaborativeAgentImpl>()
    private val agentScopes = ConcurrentHashMap<String, CoroutineScope>()
    
    private val reputationManager = ReputationManager()
    private val neuralRouter = NeuralRouter()
    private val gossipProtocol = GossipProtocol()
    private val loadBalancer = AdaptiveLoadBalancer()
    private val consensusEngine = ConsensusEngine()
    
    private var gossipJob: Job? = null
    
    init {
        startGossipProtocol()
    }
    
    private fun startGossipProtocol() {
        gossipProtocol.start(agents)
    }
    
    fun createAgent(config: AgentConfig): CollaborativeAgentImpl {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        agentScopes[config.agentId] = scope
        
        val agent = CollaborativeAgentImpl(
            config = config,
            llmProvider = llmProviderFactory(config.apiKey),
            toolRegistry = toolRegistry,
            messageBus = messageBus,
            scope = scope,
            onEvent = { event ->
                handleAgentEvent(config.agentId, event)
            },
            neuralRouter = neuralRouter,
            reputationManager = reputationManager
        )
        
        agents[config.agentId] = agent
        messageBus.registerAgent(config.agentId, agent)
        reputationManager.registerAgent(config.agentId)
        loadBalancer.registerAgent(config.agentId, AgentCapability(
            name = config.role,
            quality = 0.8,
            reliability = 0.9,
            load = 0.0,
            tags = setOf(config.role)
        ))
        
        logger.info("Created collaborative agent: ${config.name} (${config.agentId}) with key ${config.keyId}")
        return agent
    }
    
    private fun handleAgentEvent(agentId: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.FindingShared -> {
                logger.info("Agent $agentId shared finding: ${event.finding.findingType}")
                gossipProtocol.broadcastFinding(agentId, event.finding)
            }
            is AgentEvent.MessageReceived -> {
                logger.debug("Agent $agentId received message from ${event.message.fromAgentId}")
                reputationManager.recordInteraction(agentId, event.message.fromAgentId, true)
            }
            is AgentEvent.StatusChange -> {
                logger.info("Agent $agentId status: ${event.oldStatus} -> ${event.newStatus}")
            }
            is AgentEvent.Error -> {
                logger.error("Agent $agentId error: ${event.message}", event.throwable)
                event.message.fromAgentId.let { 
                    reputationManager.recordInteraction(agentId, it, false)
                }
            }
            is AgentEvent.ReputationUpdate -> {
                logger.debug("Agent ${event.agentId} reputation: ${event.newScore}")
            }
            else -> {}
        }
    }
    
    fun getAgent(agentId: String): CollaborativeAgentImpl? = agents[agentId]
    
    fun getAllAgents(): List<CollaborativeAgentImpl> = agents.values.toList()
    
    fun getActiveAgents(): List<CollaborativeAgentImpl> = agents.values.filter {
        it.state.status == AgentState.AgentStatus.RUNNING || it.state.status == AgentState.AgentStatus.WAITING
    }
    
    fun findBestAgent(capabilities: Set<String>): String? {
        return neuralRouter.route(agents.keys.toList(), capabilities, reputationManager, loadBalancer)
    }
    
    fun stopAgent(agentId: String) {
        agents[agentId]?.stop()
        agentScopes[agentId]?.cancel()
        agentScopes.remove(agentId)
        messageBus.unregisterAgent(agentId)
        reputationManager.unregisterAgent(agentId)
        loadBalancer.unregisterAgent(agentId)
    }
    
    fun shutdown() {
        gossipProtocol.stop()
        agents.values.forEach { it.stop() }
        agentScopes.values.forEach { it.cancel() }
        agents.clear()
        agentScopes.clear()
    }
    
    fun formatStatus(): String {
        return buildString {
            appendLine("=".repeat(60))
            appendLine("COLLABORATIVE AGENT RUNTIME STATUS")
            appendLine("=".repeat(60))
            appendLine("Active Agents: ${agents.size}")
            appendLine()
            appendLine("=== Reputation Scores ===")
            for ((agentId, score) in reputationManager.getAllReputations()) {
                appendLine("$agentId: ${"%.2f".format(score.score)} (${score.totalInteractions} interactions)")
            }
            appendLine()
            appendLine("=== Agent Load ===")
            for ((agentId, load) in loadBalancer.getLoadDistribution()) {
                appendLine("$agentId: ${"%.1f".format(load * 100)}%")
            }
            appendLine()
            agents.values.forEach { agent ->
                appendLine("[${agent.config.name}] ${agent.agentId}")
                appendLine("  Status: ${agent.state.status}")
                appendLine("  Task: ${agent.state.currentTask ?: "none"}")
                appendLine("  Progress: ${(agent.state.progress * 100).toInt()}%")
                appendLine("  Key: ${agent.config.keyId}")
                appendLine("  Energy: ${"%.1f".format(agent.state.energy * 100)}%")
                appendLine("  Messages: ${agent.state.messagesReceived} in / ${agent.state.messagesSent} out")
                appendLine()
            }
        }
    }
}

class ReputationManager {
    private val reputations = ConcurrentHashMap<String, ReputationScore>()
    private val decayFactor = 0.995
    
    fun registerAgent(agentId: String) {
        reputations[agentId] = ReputationScore(agentId = agentId)
    }
    
    fun unregisterAgent(agentId: String) {
        reputations.remove(agentId)
    }
    
    fun recordInteraction(fromAgent: String, toAgent: String, success: Boolean) {
        val current = reputations[toAgent] ?: ReputationScore(toAgent)
        val delta = if (success) 0.05 else -0.1
        val newScore = (current.score + delta).coerceIn(0.0, 1.0)
        
        reputations[toAgent] = current.copy(
            score = newScore,
            totalInteractions = current.totalInteractions + 1,
            successfulInteractions = current.successfulInteractions + if (success) 1 else 0,
            failedInteractions = current.failedInteractions + if (!success) 1 else 0,
            lastUpdated = System.currentTimeMillis(),
            history = current.history + ReputationDelta(System.currentTimeMillis(), delta, 
                if (success) "successful_interaction" else "failed_interaction")
        )
    }
    
    fun getReputation(agentId: String): Double {
        return reputations[agentId]?.score ?: 0.5
    }
    
    fun getAllReputations(): Map<String, ReputationScore> = reputations
    
    fun getTopAgents(count: Int): List<String> {
        return reputations.values.sortedByDescending { it.score }.take(count).map { it.agentId }
    }
    
    fun applyDecay() {
        for ((id, rep) in reputations) {
            val decayed = rep.score * decayFactor
            reputations[id] = rep.copy(score = decayed, lastUpdated = System.currentTimeMillis())
        }
    }
}

class NeuralRouter {
    private val weights = listOf(0.4, 0.3, 0.2, 0.1)
    private val embeddingDim = 32
    
    private val agentEmbeddings = ConcurrentHashMap<String, DoubleArray>()
    private val capabilityEmbeddings = ConcurrentHashMap<String, DoubleArray>()
    
    init {
        initializeCapabilityEmbeddings()
    }
    
    private fun initializeCapabilityEmbeddings() {
        val capabilities = listOf("research", "coding", "analysis", "coordination", "summarization")
        capabilities.forEach { cap ->
            capabilityEmbeddings[cap] = generateEmbedding(cap)
        }
    }
    
    private fun generateEmbedding(text: String): DoubleArray {
        val random = Random(text.hashCode().toLong())
        return DoubleArray(embeddingDim) { random.nextDouble() }
    }
    
    fun updateAgentEmbedding(agentId: String, capabilities: Set<String>) {
        val embeddings = capabilities.mapNotNull { capabilityEmbeddings[it] }
        if (embeddings.isNotEmpty()) {
            val avgEmbedding = DoubleArray(embeddingDim) { i ->
                embeddings.map { it[i] }.average()
            }
            agentEmbeddings[agentId] = avgEmbedding
        }
    }
    
    fun route(
        agentIds: List<String>,
        requiredCapabilities: Set<String>,
        reputationManager: ReputationManager,
        loadBalancer: AdaptiveLoadBalancer
    ): String? {
        if (agentIds.isEmpty()) return null
        if (agentIds.size == 1) return agentIds.first()
        
        val scores = agentIds.map { agentId ->
            val reputation = reputationManager.getReputation(agentId)
            val load = loadBalancer.getLoad(agentId)
            
            val capabilityScore = if (requiredCapabilities.isEmpty()) 0.5 else {
                val agentEmbedding = agentEmbeddings[agentId]
                if (agentEmbedding != null) {
                    val reqEmbeddings = requiredCapabilities.mapNotNull { capabilityEmbeddings[it] }
                    if (reqEmbeddings.isNotEmpty()) {
                        cosineSimilarity(agentEmbedding, reqEmbeddings.first())
                    } else 0.5
                } else 0.5
            }
            
            val score = weights[0] * reputation + 
                       weights[1] * (1 - load) + 
                       weights[2] * capabilityScore +
                       weights[3] * (1 - load)
            
            agentId to score
        }
        
        return scores.maxByOrNull { it.second }?.first
    }
    
    private fun cosineSimilarity(a: DoubleArray, b: DoubleArray): Double {
        val dotProduct = a.zip(b).sumOf { it.first * it.second }
        val magnitudeA = sqrt(a.sumOf { it * it })
        val magnitudeB = sqrt(b.sumOf { it * it })
        return if (magnitudeA > 0 && magnitudeB > 0) dotProduct / (magnitudeA * magnitudeB) else 0.0
    }
}

class GossipProtocol {
    private val logger = LoggerFactory.getLogger(GossipProtocol::class.java)
    private val gossipQueue = Channel<GossipMessage>(Channel.BUFFERED)
    private var isRunning = false
    
    private val gossipHistory = ConcurrentHashMap<String, MutableList<GossipMessage>>()
    private val fanout = 3
    
    suspend fun start(agents: Map<String, CollaborativeAgentImpl>) {
        isRunning = true
        while (isRunning) {
            select<Unit> {
                gossipQueue.onReceive { gossip ->
                    processGossip(gossip, agents)
                }
            }
        }
    }
    
    fun stop() {
        isRunning = false
    }
    
    fun broadcastFinding(agentId: String, finding: PartialFinding) {
        val gossip = GossipMessage(
            type = GossipMessage.GossipType.FINDING,
            sourceAgentId = agentId,
            content = mapOf(
                "findingType" to finding.findingType,
                "relevance" to finding.relevance,
                "confidence" to finding.confidence
            )
        )
        gossipQueue.trySend(gossip)
    }
    
    private suspend fun processGossip(gossip: GossipMessage, agents: Map<String, CollaborativeAgentImpl>) {
        if (gossip.ttl <= 0) return
        
        val historyKey = "${gossip.type}_${gossip.sourceAgentId}"
        gossipHistory.getOrPut(historyKey) { mutableListOf() }.add(gossip)
        
        logger.debug("Processing gossip from ${gossip.sourceAgentId}: ${gossip.type}")
        
        val targets = agents.keys.filter { it != gossip.sourceAgentId }.shuffled().take(fanout)
        targets.forEach { targetId ->
            agents[targetId]?.receiveMessage(AgentMessage(
                messageId = UUID.randomUUID().toString(),
                fromAgentId = gossip.sourceAgentId,
                toAgentId = targetId,
                type = AgentMessage.MessageType.GOSSIP,
                content = json.encodeToString(kotlinx.serialization.builtins.MapSerializer(
                    kotlinx.serialization.builtins.serializer<String>(),
                    kotlinx.serialization.builtins.serializer<Any>()
                ), gossip.content),
                metadata = mapOf("gossipType" to gossip.type.name)
            ))
        }
        
        if (gossip.ttl > 1) {
            gossipQueue.trySend(gossip.copy(ttl = gossip.ttl - 1))
        }
    }
}

class AdaptiveLoadBalancer {
    private val agentLoads = ConcurrentHashMap<String, Double>()
    private val capacity = 1.0
    
    fun registerAgent(agentId: String, capability: AgentCapability) {
        agentLoads[agentId] = 0.0
    }
    
    fun unregisterAgent(agentId: String) {
        agentLoads.remove(agentId)
    }
    
    fun updateLoad(agentId: String, delta: Double) {
        val current = agentLoads[agentId] ?: 0.0
        agentLoads[agentId] = (current + delta).coerceIn(0.0, capacity)
    }
    
    fun getLoad(agentId: String): Double {
        return agentLoads[agentId] ?: 0.0
    }
    
    fun getLoadDistribution(): Map<String, Double> = agentLoads.toMap()
    
    fun getLeastLoaded(): String? {
        return agentLoads.minByOrNull { it.value }?.key
    }
}

class ConsensusEngine {
    private val votes = ConcurrentHashMap<String, MutableMap<String, String>>()
    private val quorum = 0.5
    
    fun propose(topic: String, proposerId: String, proposal: String) {
        votes.getOrPut(topic) { ConcurrentHashMap() }[proposerId] = proposal
    }
    
    fun vote(topic: String, voterId: String, decision: String) {
        votes[topic]?.set(voterId, decision)
    }
    
    fun hasQuorum(topic: String, totalAgents: Int): Boolean {
        val votesForTopic = votes[topic]?.size ?: 0
        return votesForTopic.toDouble() / totalAgents >= quorum
    }
    
    fun getDecision(topic: String): String? {
        val topicVotes = votes[topic] ?: return null
        return topicVotes.values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }
}

class CollaborativeAgentImpl(
    override val config: AgentConfig,
    private val llmProvider: LlmProvider,
    private val toolRegistry: SharedToolRegistry,
    private val messageBus: AgentMessageBus,
    private val scope: CoroutineScope,
    private val onEvent: (AgentEvent) -> Unit,
    private val neuralRouter: NeuralRouter? = null,
    private val reputationManager: ReputationManager? = null
) : CollaborativeAgent {
    
    private val logger = LoggerFactory.getLogger(CollaborativeAgentImpl::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private var _state = AgentState(
        agentId = config.agentId,
        status = AgentState.AgentStatus.IDLE,
        currentTask = null,
        progress = 0.0,
        messagesReceived = 0,
        messagesSent = 0,
        findingsShared = 0,
        createdAt = System.currentTimeMillis(),
        lastActivity = System.currentTimeMillis(),
        energy = 1.0
    )
    
    override val agentId: String get() = config.agentId
    override val state: AgentState get() = _state
    
    private val messageChannel = Channel<AgentMessage>(Channel.UNLIMITED)
    private val findingsChannel = Channel<PartialFinding>(Channel.UNLIMITED)
    private var currentJob: Job? = null
    
    private var messageProcessorJob: Job? = null
    
    private val attentionWeights = ConcurrentHashMap<String, Double>()
    private var internalModel: DoubleArray? = null
    
    init {
        startMessageProcessor()
        initializeInternalModel()
    }
    
    private fun initializeInternalModel() {
        val dim = 64
        val random = Random(config.agentId.hashCode().toLong())
        internalModel = DoubleArray(dim) { random.nextDouble() }
    }
    
    private fun startMessageProcessor() {
        messageProcessorJob = scope.launch {
            while (isActive) {
                select<Unit> {
                    messageChannel.onReceive { message ->
                        processIncomingMessage(message)
                    }
                    findingsChannel.onReceive { finding ->
                        processAndShareFinding(finding)
                    }
                }
            }
        }
    }
    
    private suspend fun processIncomingMessage(message: AgentMessage) {
        _state = _state.copy(
            messagesReceived = _state.messagesReceived + 1,
            lastActivity = System.currentTimeMillis()
        )
        
        updateAttention(message.fromAgentId, 0.1)
        
        if (message.type == AgentMessage.MessageType.GOSSIP) {
            handleGossipMessage(message)
            return
        }
        
        onEvent(AgentEvent.MessageReceived(message))
        
        when (message.type) {
            AgentMessage.MessageType.INSIGHT, AgentMessage.MessageType.PARTIAL_FINDING -> {
                logger.info("Agent ${config.agentId} received insight from ${message.fromAgentId}: ${message.content.take(100)}")
                integrateInsight(message)
            }
            AgentMessage.MessageType.REQUEST_HELP -> {
                logger.info("Agent ${config.agentId} received help request from ${message.fromAgentId}")
            }
            AgentMessage.MessageType.DIRECT_CALL -> {
                logger.info("Agent ${config.agentId} received direct call from ${message.fromAgentId}")
            }
            else -> {}
        }
    }
    
    private suspend fun handleGossipMessage(message: AgentMessage) {
        val gossip = GossipMessage(
            type = GossipMessage.GossipType.valueOf(message.metadata["gossipType"] ?: "FINDING"),
            sourceAgentId = message.fromAgentId,
            content = emptyMap()
        )
        onEvent(AgentEvent.GossipReceived(gossip))
    }
    
    private suspend fun integrateInsight(message: AgentMessage) {
        internalModel?.let { model ->
            val learningRate = 0.01
            for (i in model.indices) {
                model[i] += learningRate * (Random.nextDouble() - 0.5) * 0.1
            }
        }
    }
    
    private fun updateAttention(sourceId: String, delta: Double) {
        val current = attentionWeights[sourceId] ?: 0.0
        attentionWeights[sourceId] = (current + delta).coerceIn(0.0, 1.0)
        
        _state = _state.copy(attention = attentionWeights.toMap())
    }
    
    override suspend fun receiveMessage(message: AgentMessage) {
        messageChannel.send(message)
    }
    
    override suspend fun shareFinding(finding: PartialFinding) {
        findingsChannel.send(finding)
    }
    
    private suspend fun processAndShareFinding(finding: PartialFinding) {
        val recipients = if (finding.suggestedRecipient != null) {
            listOf(finding.suggestedRecipient)
        } else {
            messageBus.findInterestedAgents(config.agentId, finding.findingType, finding.relevance)
        }
        
        if (recipients.isNotEmpty()) {
            val message = AgentMessage(
                messageId = UUID.randomUUID().toString(),
                fromAgentId = config.agentId,
                toAgentId = null,
                type = AgentMessage.MessageType.INSIGHT,
                content = json.encodeToString(
                    kotlinx.serialization.serializer<PartialFinding>(),
                    finding
                ),
                metadata = mapOf(
                    "findingType" to finding.findingType,
                    "relevance" to finding.relevance,
                    "confidence" to finding.confidence.toString()
                )
            )
            
            recipients.forEach { recipientId ->
                messageBus.send(message.copy(toAgentId = recipientId))
                updateAttention(recipientId, 0.05)
            }
            
            _state = _state.copy(
                messagesSent = _state.messagesSent + recipients.size,
                findingsShared = _state.findingsShared + 1
            )
            
            onEvent(AgentEvent.FindingShared(finding, recipients))
        }
    }
    
    override suspend fun requestHelp(from: String, description: String) {
        val message = AgentMessage(
            messageId = UUID.randomUUID().toString(),
            fromAgentId = config.agentId,
            toAgentId = from,
            type = AgentMessage.MessageType.REQUEST_HELP,
            content = description
        )
        messageBus.send(message)
        _state = _state.copy(messagesSent = _state.messagesSent + 1)
    }
    
    override suspend fun callAgent(targetAgentId: String, task: String, waitForResult: Boolean): String? {
        val message = AgentMessage(
            messageId = UUID.randomUUID().toString(),
            fromAgentId = config.agentId,
            toAgentId = targetAgentId,
            type = AgentMessage.MessageType.DIRECT_CALL,
            content = task,
            metadata = mapOf("waitForResult" to waitForResult.toString())
        )
        
        messageBus.send(message)
        _state = _state.copy(messagesSent = _state.messagesSent + 1)
        
        if (waitForResult) {
            return messageBus.waitForResponse(message.messageId, timeoutMs = 60000)
        }
        return null
    }
    
    override suspend fun start(task: String): Flow<AgentEvent> = flow {
        _state = _state.copy(
            status = AgentState.AgentStatus.RUNNING,
            currentTask = task,
            progress = 0.0,
            energy = 1.0
        )
        
        emit(AgentEvent.StatusChange(AgentState.AgentStatus.IDLE, AgentState.AgentStatus.RUNNING))
        
        val systemPrompt = buildSystemPrompt()
        val messages = mutableListOf(
            LlmMessage(role = LlmMessage.Role.SYSTEM, content = systemPrompt),
            LlmMessage(role = LlmMessage.Role.USER, content = task)
        )
        
        val tools = toolRegistry.getToolsForAgent(config)
        
        try {
            var iteration = 0
            var lastContent = ""
            
            while (iteration < config.maxIterations && _state.energy > 0.2) {
                iteration++
                
                llmProvider.stream(messages, tools).collect { chunk ->
                    if (!chunk.content.isNullOrEmpty()) {
                        lastContent += chunk.content
                        emit(AgentEvent.StreamingOutput(chunk.content))
                    }
                    
                    chunk.toolCall?.let { toolCall ->
                        val toolName = toolCall.functionName
                        val args = try {
                            json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(toolCall.arguments)
                                .mapValues { it.value.toString().removeSurrounding("\"") }
                        } catch (e: Exception) {
                            emptyMap()
                        }
                        
                        emit(AgentEvent.ToolCall(toolName, args))
                        
                        val result = executeCollaborativeTool(toolName, toolCall.arguments)
                        
                        emit(AgentEvent.ToolResult(toolName, result))
                        
                        messages.add(LlmMessage(role = LlmMessage.Role.TOOL, content = "[Tool $toolName]: $result"))
                    }
                }
                
                val progress = iteration.toDouble() / config.maxIterations
                _state = _state.copy(
                    progress = progress,
                    lastActivity = System.currentTimeMillis(),
                    energy = maxOf(0.0, _state.energy - 0.05)
                )
                emit(AgentEvent.TaskProgress(_state.progress, "Iteration $iteration complete"))
                
                if (lastContent.isNotEmpty() && !lastContent.contains("tool_call")) {
                    break
                }
            }
            
            _state = _state.copy(
                status = if (_state.energy <= 0.2) AgentState.AgentStatus.SUSPENDED else AgentState.AgentStatus.COMPLETED,
                progress = 1.0
            )
            emit(AgentEvent.StatusChange(AgentState.AgentStatus.RUNNING, _state.status))
            emit(AgentEvent.Completed(lastContent))
            
        } catch (e: Exception) {
            _state = _state.copy(status = AgentState.AgentStatus.FAILED)
            emit(AgentEvent.StatusChange(AgentState.AgentStatus.RUNNING, AgentState.AgentStatus.FAILED))
            emit(AgentEvent.Error(e.message ?: "Unknown error", e))
        }
    }
    
    private suspend fun executeCollaborativeTool(toolName: String, argsJson: String): String {
        if (!config.sharedTools.contains(toolName) && !config.dedicatedTools.contains(toolName)) {
            return "Tool $toolName not available to this agent"
        }
        
        val result = toolRegistry.executeTool(toolName, argsJson, config.agentId)
        
        val insightPatterns = listOf("framework", "library", "tool", "api", "sdk", "package")
        insightPatterns.forEach { pattern ->
            if (result.contains(pattern, ignoreCase = true)) {
                val finding = PartialFinding(
                    findingType = pattern,
                    relevance = "discovered_during_$toolName",
                    data = result.take(500),
                    suggestedRecipient = null,
                    confidence = 0.8
                )
                shareFinding(finding)
            }
        }
        
        return result
    }
    
    private fun buildSystemPrompt(): String {
        return """
You are a collaborative AI agent named ${config.name}.
Role: ${config.role}
Agent ID: ${config.agentId}

Capabilities:
- You have internal state including energy and attention
- You can share findings with other agents in real-time
- You can learn from insights received from other agents
- You should manage your energy and focus on high-value tasks

Available tools:
- Shared: ${config.sharedTools.joinToString(", ")}
- Dedicated: ${config.dedicatedTools.joinToString(", ")}

Collaboration guidelines:
1. Share useful findings immediately using shareFinding
2. Request help when stuck using message_agent
3. Learn from insights from other agents
4. Manage your energy by avoiding unnecessary work
        """.trimIndent()
    }
    
    override suspend fun stop() {
        currentJob?.cancel()
        messageProcessorJob?.cancel()
        _state = _state.copy(status = AgentState.AgentStatus.IDLE)
    }
}

class AgentMessageBus {
    private val logger = LoggerFactory.getLogger(AgentMessageBus::class.java)
    private val agents = ConcurrentHashMap<String, CollaborativeAgent>()
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val interestRegistry = ConcurrentHashMap<String, MutableSet<String>>()
    private val messageHistory = ConcurrentHashMap<String, MutableList<AgentMessage>>()
    
    fun registerAgent(agentId: String, agent: CollaborativeAgent) {
        agents[agentId] = agent
        logger.info("Registered agent on message bus: $agentId")
    }
    
    fun unregisterAgent(agentId: String) {
        agents.remove(agentId)
        interestRegistry.remove(agentId)
        logger.info("Unregistered agent from message bus: $agentId")
    }
    
    fun registerInterest(agentId: String, findingType: String) {
        interestRegistry.getOrPut(agentId) { mutableSetOf() }.add(findingType)
    }
    
    suspend fun send(message: AgentMessage) {
        val historyKey = message.traceId
        messageHistory.getOrPut(historyKey) { mutableListOf() }.add(message)
        
        if (message.toAgentId != null) {
            val agent = agents[message.toAgentId]
            if (agent != null) {
                agent.receiveMessage(message)
                logger.debug("Delivered message ${message.messageId} to ${message.toAgentId}")
            } else {
                logger.warn("Target agent not found: ${message.toAgentId}")
            }
        } else {
            broadcast(message)
        }
    }
    
    private suspend fun broadcast(message: AgentMessage) {
        agents.values.forEach { agent ->
            if (agent.agentId != message.fromAgentId) {
                agent.receiveMessage(message)
            }
        }
        logger.debug("Broadcast message ${message.messageId} to ${agents.size - 1} agents")
    }
    
    fun findInterestedAgents(fromAgentId: String, findingType: String, relevance: String): List<String> {
        return interestRegistry.entries
            .filter { it.key != fromAgentId && it.value.contains(findingType) }
            .map { it.key }
            .ifEmpty {
                agents.keys.filter { it != fromAgentId }.take(3)
            }
    }
    
    suspend fun waitForResponse(messageId: String, timeoutMs: Long): String? {
        val deferred = CompletableDeferred<String?>()
        pendingResponses[messageId] = deferred
        
        return withTimeoutOrNull(timeoutMs) {
            deferred.await()
        }.also {
            pendingResponses.remove(messageId)
        }
    }
    
    fun provideResponse(messageId: String, response: String) {
        pendingResponses[messageId]?.complete(response)
    }
    
    fun getMessageHistory(traceId: String): List<AgentMessage> {
        return messageHistory[traceId] ?: emptyList()
    }
}

class SharedToolRegistry {
    private val tools = ConcurrentHashMap<String, ToolDefinition>()
    private val executors = ConcurrentHashMap<String, suspend (String, String) -> String>()
    private val agentToolUsage = ConcurrentHashMap<String, MutableSet<String>>()
    private val toolMetrics = ConcurrentHashMap<String, ToolMetrics>()
    
    data class ToolMetrics(
        val name: String,
        val totalExecutions: Int = 0,
        val successfulExecutions: Int = 0,
        val failedExecutions: Int = 0,
        val avgLatencyMs: Double = 0.0,
        val totalLatencyMs: Long = 0
    )
    
    fun registerTool(
        toolName: String,
        definition: ToolDefinition,
        executor: suspend (String, String) -> String
    ) {
        tools[toolName] = definition
        executors[toolName] = executor
        toolMetrics[toolName] = ToolMetrics(toolName)
    }
    
    fun getToolDefinition(toolName: String): ToolDefinition? = tools[toolName]
    
    fun getAllToolDefinitions(): List<ToolDefinition> = tools.values.toList()
    
    fun getToolsForAgent(config: AgentConfig): List<ToolDefinition> {
        val availableTools = config.sharedTools + config.dedicatedTools
        return tools.entries
            .filter { it.key in availableTools || availableTools.isEmpty() }
            .map { it.value }
    }
    
    suspend fun executeTool(toolName: String, argsJson: String, agentId: String): String {
        val executor = executors[toolName]
            ?: return "Tool not found: $toolName"
        
        agentToolUsage.getOrPut(agentId) { mutableSetOf() }.add(toolName)
        
        val startTime = System.currentTimeMillis()
        return try {
            val result = executor(agentId, argsJson)
            recordExecution(toolName, true, System.currentTimeMillis() - startTime)
            result
        } catch (e: Exception) {
            recordExecution(toolName, false, System.currentTimeMillis() - startTime)
            "Error: ${e.message}"
        }
    }
    
    private fun recordExecution(toolName: String, success: Boolean, latencyMs: Long) {
        val current = toolMetrics[toolName] ?: return
        toolMetrics[toolName] = current.copy(
            totalExecutions = current.totalExecutions + 1,
            successfulExecutions = current.successfulExecutions + if (success) 1 else 0,
            failedExecutions = current.failedExecutions + if (!success) 1 else 0,
            totalLatencyMs = current.totalLatencyMs + latencyMs,
            avgLatencyMs = current.totalLatencyMs.toDouble() / (current.totalExecutions + 1)
        )
    }
    
    fun getToolUsageStats(): Map<String, Set<String>> = agentToolUsage.mapValues { it.value.toSet() }
    
    fun getToolMetrics(toolName: String): ToolMetrics? = toolMetrics[toolName]
}
