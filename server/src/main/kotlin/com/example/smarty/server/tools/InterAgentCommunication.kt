package com.example.smarty.server.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.PriorityQueue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

@Serializable
data class AgentMessage(
    val id: String,
    val fromAgent: String,
    val toAgent: String,
    val type: String,
    val content: String,
    val timestamp: Long,
    val read: Boolean = false,
    val priority: Int = 0,
    val correlationId: String? = null,
    val replyTo: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class SharedResource(
    val key: String,
    val value: String,
    val owner: String,
    val createdAt: Long,
    val expiresAt: Long?,
    val accessCount: Int = 0,
    val version: Int = 0,
    val consensusRequired: Boolean = false
)

@Serializable
data class AgentState(
    val agentId: String,
    val status: String,
    val currentTask: String?,
    val progress: Double,
    val lastUpdate: Long,
    val capabilities: List<String>,
    val workload: Double,
    val reputation: Double,
    val trustScore: Double,
    val expertise: Map<String, Double>
)

@Serializable
data class Collaboration(
    val id: String,
    val name: String,
    val participants: List<String>,
    val sharedGoal: String,
    val status: String,
    val createdAt: Long,
    val coordinator: String,
    val votingEnabled: Boolean,
    val consensusThreshold: Double,
    val decisions: List<CollaborationDecision>
)

@Serializable
data class CollaborationDecision(
    val id: String,
    val description: String,
    val votes: Map<String, Boolean>,
    val outcome: String,
    val timestamp: Long
)

@Serializable
data class AgentReputation(
    val agentId: String,
    val overallScore: Double,
    val reliability: Double,
    val expertise: Map<String, Double>,
    val history: List<ReputationEvent>,
    val lastUpdated: Long
)

@Serializable
data class ReputationEvent(
    val type: String,
    val score: Double,
    val timestamp: Long,
    val fromAgent: String,
    val description: String
)

@Serializable
data class ConsensusProposal(
    val id: String,
    val proposer: String,
    val content: String,
    val votes: MutableMap<String, Boolean>,
    val deadline: Long,
    val status: String,
    val justification: String
)

@Serializable
data class GossipMessage(
    val id: String,
    val fromAgent: String,
    val payload: String,
    val timestamp: Long,
    val ttl: Int,
    val signature: String
)

class InterAgentCommunication {
    private val logger = LoggerFactory.getLogger(InterAgentCommunication::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val messages = ConcurrentHashMap<String, MutableList<AgentMessage>>()
    private val sharedResources = ConcurrentHashMap<String, SharedResource>()
    private val agentStates = ConcurrentHashMap<String, AgentState>()
    private val collaborations = ConcurrentHashMap<String, Collaboration>()
    private val messageCounter = AtomicLong(0)
    
    private val messageBroker = MessageBroker()
    private val reputationManager = ReputationManager()
    private val consensusEngine = ConsensusEngine()
    private val gossipProtocol = GossipProtocol()
    private val votingSystem = VotingSystem()
    private val negotiationEngine = NegotiationEngine()
    private val trustGraph = TrustGraph()
    private val eventBus = EventBus()
    private val protocolRouter = ProtocolRouter()
    
    init {
        startGossipProtocol()
        startConsensusChecker()
        startResourceExpiration()
    }
    
    private fun startGossipProtocol() {
        scope.launch {
            while (true) {
                delay(5000)
                performGossipRound()
            }
        }
    }
    
    private fun startConsensusChecker() {
        scope.launch {
            while (true) {
                delay(10000)
                consensusEngine.checkDeadlines()
            }
        }
    }
    
    private fun startResourceExpiration() {
        scope.launch {
            while (true) {
                delay(30000)
                expireOldResources()
            }
        }
    }
    
    private fun performGossipRound() {
        val agents = agentStates.keys().toList()
        if (agents.size < 2) return
        
        val selected = agents.shuffled().take(2)
        if (selected.size < 2) return
        
        val message = GossipMessage(
            id = UUID.randomUUID().toString(),
            fromAgent = selected[0],
            payload = "heartbeat",
            timestamp = System.currentTimeMillis(),
            ttl = 3,
            signature = ""
        )
        
        gossipProtocol.receive(message, selected[1])
    }
    
    private fun expireOldResources() {
        val now = System.currentTimeMillis()
        val expired = sharedResources.filter { (_, res) ->
            res.expiresAt != null && res.expiresAt < now
        }
        expired.keys.forEach { sharedResources.remove(it) }
    }
    
    fun sendMessage(
        fromAgent: String,
        toAgent: String,
        type: String,
        content: String,
        priority: Int = 0,
        correlationId: String? = null
    ): String {
        val messageId = "msg_${System.currentTimeMillis()}_${messageCounter.incrementAndGet()}"
        
        val message = AgentMessage(
            id = messageId,
            fromAgent = fromAgent,
            toAgent = toAgent,
            type = type,
            content = content,
            timestamp = System.currentTimeMillis(),
            priority = priority,
            correlationId = correlationId
        )
        
        messages.getOrPut(toAgent) { mutableListOf() }.add(message)
        
        messageBroker.route(message)
        eventBus.publish(AgentEvent("message_sent", fromAgent, mapOf("to" to toAgent, "type" to type)))
        
        logger.debug("Message: $fromAgent -> $toAgent [$type]")
        return messageId
    }
    
    fun sendMessageAsync(
        fromAgent: String,
        toAgent: String,
        type: String,
        content: String,
        onDelivery: ((String) -> Unit)? = null
    ): String {
        val messageId = sendMessage(fromAgent, toAgent, type, content)
        
        if (onDelivery != null) {
            scope.launch {
                delay(100)
                onDelivery(messageId)
            }
        }
        
        return messageId
    }
    
    fun replyTo(
        originalMessage: AgentMessage,
        fromAgent: String,
        content: String,
        type: String = "reply"
    ): String {
        return sendMessage(
            fromAgent = fromAgent,
            toAgent = originalMessage.fromAgent,
            type = type,
            content = content,
            correlationId = originalMessage.correlationId ?: originalMessage.id,
            replyTo = originalMessage.id
        )
    }
    
    fun broadcast(
        fromAgent: String,
        type: String,
        content: String,
        excludeSelf: Boolean = true
    ): List<String> {
        val recipients = if (excludeSelf) {
            agentStates.keys.filter { it != fromAgent }
        } else {
            agentStates.keys.toList()
        }
        
        return recipients.map { recipient ->
            sendMessage(fromAgent, recipient, type, content)
        }
    }
    
    fun multicast(
        fromAgent: String,
        recipients: List<String>,
        type: String,
        content: String
    ): List<String> {
        return recipients.map { recipient ->
            sendMessage(fromAgent, recipient, type, content)
        }
    }
    
    fun getMessages(agentId: String, unreadOnly: Boolean = false): List<AgentMessage> {
        val agentMessages = messages[agentId] ?: return emptyList()
        
        return if (unreadOnly) {
            agentMessages.filter { !it.read }
        } else {
            agentMessages.toList()
        }
    }
    
    fun markRead(agentId: String, messageId: String): Boolean {
        val agentMessages = messages[agentId] ?: return false
        
        val index = agentMessages.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            agentMessages[index] = agentMessages[index].copy(read = true)
            return true
        }
        return false
    }
    
    fun markAllRead(agentId: String): Int {
        val agentMessages = messages[agentId] ?: return 0
        var count = 0
        
        for (i in agentMessages.indices) {
            if (!agentMessages[i].read) {
                agentMessages[i] = agentMessages[i].copy(read = true)
                count++
            }
        }
        
        return count
    }
    
    fun shareResource(
        key: String,
        value: String,
        owner: String,
        ttlSeconds: Long? = null,
        consensusRequired: Boolean = false
    ): String {
        val resource = SharedResource(
            key = key,
            value = value,
            owner = owner,
            createdAt = System.currentTimeMillis(),
            expiresAt = ttlSeconds?.let { System.currentTimeMillis() + it * 1000 },
            version = 0,
            consensusRequired = consensusRequired
        )
        
        sharedResources[key] = resource
        
        eventBus.publish(AgentEvent("resource_shared", owner, mapOf("key" to key)))
        
        if (consensusRequired) {
            consensusEngine.propose(owner, "Share resource: $key", value)
        }
        
        logger.info("Resource shared: $key by $owner")
        return key
    }
    
    fun getResource(key: String, requester: String): String? {
        val resource = sharedResources[key] ?: return null
        
        if (resource.expiresAt != null && System.currentTimeMillis() > resource.expiresAt) {
            sharedResources.remove(key)
            return null
        }
        
        if (resource.consensusRequired && resource.owner != requester) {
            val approval = consensusEngine.checkApproval(key, requester)
            if (!approval) {
                logger.warn("Resource $key requires consensus for $requester")
                return null
            }
        }
        
        sharedResources[key] = resource.copy(
            accessCount = resource.accessCount + 1,
            version = resource.version + 1
        )
        
        trustGraph.recordAccess(key, requester)
        
        return resource.value
    }
    
    fun updateResource(key: String, newValue: String, requester: String): Boolean {
        val resource = sharedResources[key] ?: return false
        
        if (resource.owner != requester) {
            if (!trustGraph.canModify(key, requester)) {
                return false
            }
        }
        
        sharedResources[key] = resource.copy(value = newValue, version = resource.version + 1)
        
        eventBus.publish(AgentEvent("resource_updated", requester, mapOf("key" to key)))
        
        return true
    }
    
    fun deleteResource(key: String, requester: String): Boolean {
        val resource = sharedResources[key] ?: return false
        
        if (resource.owner != requester) {
            if (!trustGraph.canDelete(key, requester)) {
                return false
            }
        }
        
        sharedResources.remove(key)
        
        eventBus.publish(AgentEvent("resource_deleted", requester, mapOf("key" to key)))
        
        return true
    }
    
    fun listResources(): List<SharedResource> {
        val now = System.currentTimeMillis()
        return sharedResources.values.filter {
            it.expiresAt == null || it.expiresAt > now
        }
    }
    
    fun registerAgent(
        agentId: String,
        capabilities: List<String>,
        initialExpertise: Map<String, Double> = emptyMap()
    ): Boolean {
        val expertise = if (initialExpertise.isNotEmpty()) {
            initialExpertise
        } else {
            capabilities.associateWith { 0.5 }
        }
        
        val state = AgentState(
            agentId = agentId,
            status = "idle",
            currentTask = null,
            progress = 0.0,
            lastUpdate = System.currentTimeMillis(),
            capabilities = capabilities,
            workload = 0.0,
            reputation = 0.5,
            trustScore = 0.5,
            expertise = expertise
        )
        
        agentStates[agentId] = state
        reputationManager.register(agentId, capabilities)
        trustGraph.addNode(agentId)
        
        eventBus.publish(AgentEvent("agent_registered", agentId, mapOf("capabilities" to capabilities.joinToString(","))))
        
        logger.info("Agent registered: $agentId with capabilities: $capabilities")
        return true
    }
    
    fun updateAgentState(
        agentId: String,
        status: String? = null,
        currentTask: String? = null,
        progress: Double? = null,
        workload: Double? = null
    ): Boolean {
        val current = agentStates[agentId] ?: return false
        
        agentStates[agentId] = current.copy(
            status = status ?: current.status,
            currentTask = currentTask ?: current.currentTask,
            progress = progress ?: current.progress,
            workload = workload ?: current.workload,
            lastUpdate = System.currentTimeMillis()
        )
        
        return true
    }
    
    fun getAgentState(agentId: String): AgentState? = agentStates[agentId]
    
    fun findAvailableAgents(capability: String? = null): List<AgentState> {
        return agentStates.values.filter { state ->
            state.status == "idle" &&
            state.workload < 0.8 &&
            (capability == null || state.capabilities.contains(capability) || state.expertise[capability] ?: 0.0 > 0.3)
        }.sortedBy { it.workload }
    }
    
    fun findExpertAgents(capability: String, minExpertise: Double = 0.7): List<AgentState> {
        return agentStates.values.filter { state ->
            (state.expertise[capability] ?: 0.0) >= minExpertise
        }.sortedByDescending { state -> state.expertise[capability] ?: 0.0 }
    }
    
    fun createCollaboration(
        name: String,
        participants: List<String>,
        sharedGoal: String,
        coordinator: String,
        votingEnabled: Boolean = true,
        consensusThreshold: Double = 0.6
    ): String {
        val collabId = "collab_${name.lowercase().replace(" ", "_")}_${System.currentTimeMillis()}"
        
        val collaboration = Collaboration(
            id = collabId,
            name = name,
            participants = participants,
            sharedGoal = sharedGoal,
            status = "active",
            createdAt = System.currentTimeMillis(),
            coordinator = coordinator,
            votingEnabled = votingEnabled,
            consensusThreshold = consensusThreshold,
            decisions = emptyList()
        )
        
        collaborations[collabId] = collaboration
        
        participants.forEach { agent ->
            sendMessage("system", agent, "collaboration_invite",
                "You've been invited to join '$name'. Goal: $sharedGoal")
            
            updateAgentState(agent, status = "collaborating", workload = (agentStates[agent]?.workload ?: 0.0) + 0.2)
        }
        
        trustGraph.createCollaboration(collabId, participants)
        
        eventBus.publish(AgentEvent("collaboration_created", coordinator, mapOf("id" to collabId, "participants" to participants.size.toString())))
        
        logger.info("Collaboration created: $name with ${participants.size} agents")
        return collabId
    }
    
    fun getCollaboration(collabId: String): Collaboration? = collaborations[collabId]
    
    fun endCollaboration(collabId: String): Boolean {
        val collab = collaborations.remove(collabId) ?: return false
        
        collab.participants.forEach { agent ->
            sendMessage("system", agent, "collaboration_ended", "Collaboration '${collab.name}' has ended.")
            
            val current = agentStates[agent]
            if (current != null) {
                updateAgentState(agent, status = "idle", workload = max(0.0, current.workload - 0.2))
            }
        }
        
        trustGraph.endCollaboration(collabId)
        
        return true
    }
    
    fun voteInCollaboration(
        collabId: String,
        agentId: String,
        decisionDescription: String,
        vote: Boolean
    ): Boolean {
        val collab = collaborations[collabId] ?: return false
        
        if (agentId !in collab.participants) return false
        
        val decisionId = UUID.randomUUID().toString()
        val votes = mutableMapOf(agentId to vote)
        
        val outcome = if (collab.votingEnabled) {
            votingSystem.calculateOutcome(votes, collab.consensusThreshold)
        } else {
            "pending"
        }
        
        val decision = CollaborationDecision(
            id = decisionId,
            description = decisionDescription,
            votes = votes,
            outcome = outcome,
            timestamp = System.currentTimeMillis()
        )
        
        collaborations[collabId] = collab.copy(
            decisions = collab.decisions + decision
        )
        
        if (outcome == "approved" || outcome == "rejected") {
            collab.participants.forEach { participant ->
                sendMessage("system", participant, "vote_concluded",
                    "Decision '$decisionDescription': $outcome")
            }
        }
        
        return true
    }
    
    fun requestHelp(
        fromAgent: String,
        capability: String,
        description: String
    ): String? {
        val experts = findExpertAgents(capability, 0.5)
        
        if (experts.isEmpty()) {
            val available = findAvailableAgents(capability)
            if (available.isEmpty()) {
                sendMessage("system", fromAgent, "help_unavailable",
                    "No agents available with capability: $capability")
                return null
            }
            
            val selected = available.minByOrNull { it.workload }!!
            sendMessage(fromAgent, selected.agentId, "help_request", description)
            updateAgentState(selected.agentId, status = "helping",
                workload = min(1.0, selected.workload + 0.3))
            
            reputationManager.recordEvent(selected.agentId, "help_request_received", 0.1, fromAgent, "Received help request")
            
            return selected.agentId
        }
        
        val selected = experts.filter { it.agentId != fromAgent }
            .minByOrNull { it.workload } ?: return null
        
        sendMessage(fromAgent, selected.agentId, "expert_help_request", description)
        updateAgentState(selected.agentId, status = "helping_expert",
            workload = min(1.0, selected.workload + 0.2))
        
        reputationManager.recordEvent(selected.agentId, "expert_help_request", 0.2, fromAgent, "Received expert help request")
        
        return selected.agentId
    }
    
    fun startNegotiation(
        agentA: String,
        agentB: String,
        topic: String,
        initialOffer: String
    ): String {
        return negotiationEngine.start(agentA, agentB, topic, initialOffer)
    }
    
    fun proposeNegotiation(
        negotiationId: String,
        proposer: String,
        terms: String
    ): Boolean {
        return negotiationEngine.propose(negotiationId, proposer, terms)
    }
    
    fun acceptNegotiation(negotiationId: String, agentId: String): Boolean {
        val result = negotiationEngine.accept(negotiationId, agentId)
        
        if (result) {
            val negotiation = negotiationEngine.getNegotiation(negotiationId)
            negotiation?.let {
                reputationManager.recordEvent(it.agentA, "negotiation_success", 0.15, it.agentB, "Successfully negotiated: ${it.topic}")
                reputationManager.recordEvent(it.agentB, "negotiation_success", 0.15, it.agentA, "Successfully negotiated: ${it.topic}")
            }
        }
        
        return result
    }
    
    fun rejectNegotiation(negotiationId: String, agentId: String): Boolean {
        return negotiationEngine.reject(negotiationId, agentId)
    }
    
    fun submitProposal(
        proposer: String,
        content: String,
        justification: String,
        deadlineSeconds: Long = 60
    ): String {
        return consensusEngine.propose(proposer, content, justification, deadlineSeconds)
    }
    
    fun vote(proposalId: String, voter: String, approve: Boolean): Boolean {
        return consensusEngine.vote(proposalId, voter, approve)
    }
    
    fun getProposalStatus(proposalId: String): ConsensusProposal? {
        return consensusEngine.getProposal(proposalId)
    }
    
    fun updateReputation(agentId: String, eventType: String, scoreDelta: Double, description: String) {
        reputationManager.recordEvent(agentId, eventType, scoreDelta, "system", description)
        
        val agent = agentStates[agentId]
        if (agent != null) {
            val newReputation = reputationManager.getScore(agentId)
            agentStates[agentId] = agent.copy(reputation = newReputation)
        }
    }
    
    fun getReputation(agentId: String): AgentReputation? {
        return reputationManager.getReputation(agentId)
    }
    
    fun trustBetween(agentA: String, agentB: String): Double {
        return trustGraph.getTrust(agentA, agentB)
    }
    
    fun formatAgentState(state: AgentState): String {
        return buildString {
            appendLine("[Agent] ${state.agentId}")
            appendLine("  Status: ${state.status}")
            appendLine("  Task: ${state.currentTask ?: "none"}")
            appendLine("  Progress: ${(state.progress * 100).toInt()}%")
            appendLine("  Workload: ${(state.workload * 100).toInt()}%")
            appendLine("  Reputation: ${"%.2f".format(state.reputation)}")
            appendLine("  Trust: ${"%.2f".format(state.trustScore)}")
            appendLine("  Capabilities: ${state.capabilities.joinToString(", ")}")
            appendLine("  Expertise:")
            state.expertise.forEach { (skill, level) ->
                appendLine("    $skill: ${"%.0f".format(level * 100)}%")
            }
        }
    }
    
    fun formatMessages(agentId: String, limit: Int = 10): String {
        val msgs = getMessages(agentId)
        
        return buildString {
            appendLine("[Messages for $agentId]")
            appendLine("-".repeat(50))
            
            if (msgs.isEmpty()) {
                appendLine("No messages.")
            } else {
                msgs.sortedByDescending { it.timestamp }.take(limit).forEach { msg ->
                    val readStatus = if (msg.read) "[READ]" else "[NEW]"
                    val priority = if (msg.priority > 0) "[PRIORITY ${msg.priority}]" else ""
                    appendLine("$readStatus $priority From: ${msg.fromAgent} | Type: ${msg.type}")
                    appendLine("  ${msg.content.take(100)}")
                    appendLine()
                }
            }
        }
    }
    
    fun formatResources(): String {
        val resources = listResources()
        
        return buildString {
            appendLine("[Shared Resources]")
            appendLine("-".repeat(50))
            
            if (resources.isEmpty()) {
                appendLine("No shared resources.")
            } else {
                resources.forEach { res ->
                    val consensus = if (res.consensusRequired) "[CONSENSUS]" else ""
                    appendLine("* ${res.key} $consensus")
                    appendLine("  Owner: ${res.owner} | Version: ${res.version}")
                    appendLine("  Value: ${res.value.take(60)}...")
                    appendLine("  Accesses: ${res.accessCount}")
                    if (res.expiresAt != null) {
                        appendLine("  Expires: ${java.time.Instant.ofEpochMilli(res.expiresAt)}")
                    }
                    appendLine()
                }
            }
        }
    }
    
    fun formatCollaboration(collab: Collaboration): String {
        return buildString {
            appendLine("[Collaboration] ${collab.name}")
            appendLine("ID: ${collab.id}")
            appendLine("Goal: ${collab.sharedGoal}")
            appendLine("Coordinator: ${collab.coordinator}")
            appendLine("Participants: ${collab.participants.joinToString(", ")}")
            appendLine("Status: ${collab.status}")
            appendLine("Voting: ${if (collab.votingEnabled) "enabled" else "disabled"} (threshold: ${collab.consensusThreshold})")
            
            if (collab.decisions.isNotEmpty()) {
                appendLine("\nDecisions:")
                collab.decisions.takeLast(5).forEach { decision ->
                    appendLine("  - ${decision.description}: ${decision.outcome}")
                }
            }
        }
    }
    
    fun getStats(): String {
        return buildString {
            appendLine("[Inter-Agent Communication Stats]")
            appendLine("=".repeat(50))
            appendLine("Registered agents: ${agentStates.size}")
            appendLine("Active collaborations: ${collaborations.size}")
            appendLine("Shared resources: ${sharedResources.size}")
            appendLine("Total messages: ${messages.values.sumOf { it.size }}")
            
            val unread = messages.values.sumOf { msgs -> msgs.count { !it.read } }
            appendLine("Unread messages: $unread")
            
            val idle = agentStates.values.count { it.status == "idle" }
            val busy = agentStates.values.count { it.status == "busy" || it.status == "collaborating" }
            appendLine("\nAgent Status: $idle idle, $busy active")
            
            appendLine("\n[Reputation]")
            agentStates.values.sortedByDescending { it.reputation }.take(3).forEach { agent ->
                appendLine("  ${agent.agentId}: ${"%.2f".format(agent.reputation)}")
            }
            
            appendLine("\n[Consensus]")
            appendLine("  Active proposals: ${consensusEngine.getActiveCount()}")
            
            appendLine("\n[Gossip]")
            appendLine("  Protocol active: ${gossipProtocol.isActive()}")
            appendLine("  Messages exchanged: ${gossipProtocol.getMessageCount()}")
        }
    }
}

class MessageBroker {
    private val subscribers = ConcurrentHashMap<String, MutableList<(AgentMessage) -> Unit>>()
    private val messageHistory = ConcurrentLinkedQueue<AgentMessage>()
    
    fun subscribe(messageType: String, handler: (AgentMessage) -> Unit) {
        subscribers.getOrPut(messageType) { mutableListOf() }.add(handler)
    }
    
    fun route(message: AgentMessage) {
        messageHistory.offer(message)
        
        subscribers[message.type]?.forEach { it(message) }
        subscribers["*"]?.forEach { it(message) }
        
        if (messageHistory.size > 1000) messageHistory.poll()
    }
}

class ReputationManager {
    private val reputations = ConcurrentHashMap<String, AgentReputation>()
    private val decayFactor = 0.99
    
    fun register(agentId: String, capabilities: List<String>) {
        val expertise = capabilities.associateWith { 0.5 }
        
        reputations[agentId] = AgentReputation(
            agentId = agentId,
            overallScore = 0.5,
            reliability = 0.5,
            expertise = expertise,
            history = emptyList(),
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    fun recordEvent(agentId: String, type: String, score: Double, fromAgent: String, description: String) {
        val current = reputations[agentId] ?: return
        
        val event = ReputationEvent(type, score, System.currentTimeMillis(), fromAgent, description)
        
        val newHistory = (current.history + event).takeLast(100)
        
        val reliability = calculateReliability(newHistory)
        val expertiseUpdate = current.expertise.mapValues { (skill, _) ->
            if (type.contains(skill) || type.contains("expert")) {
                (it.value + score * 0.1).coerceIn(0.0, 1.0)
            } else it.value
        }
        
        val overallScore = (current.overallScore * decayFactor + score * (1 - decayFactor)).coerceIn(0.0, 1.0)
        
        reputations[agentId] = current.copy(
            overallScore = overallScore,
            reliability = reliability,
            expertise = expertiseUpdate,
            history = newHistory,
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    private fun calculateReliability(history: List<ReputationEvent>): Double {
        if (history.isEmpty()) return 0.5
        
        val recent = history.takeLast(20)
        val successEvents = recent.count { it.type.contains("success") || it.type.contains("complete") }
        
        return successEvents.toDouble() / max(recent.size, 1)
    }
    
    fun getScore(agentId: String): Double = reputations[agentId]?.overallScore ?: 0.5
    fun getReputation(agentId: String): AgentReputation? = reputations[agentId]
}

class ConsensusEngine {
    private val proposals = ConcurrentHashMap<String, ConsensusProposal>()
    
    fun propose(
        proposer: String,
        content: String,
        justification: String = "",
        deadlineSeconds: Long = 60
    ): String {
        val id = "proposal_${System.currentTimeMillis()}_${Random.nextInt(10000)}"
        
        val proposal = ConsensusProposal(
            id = id,
            proposer = proposer,
            content = content,
            votes = mutableMapOf(proposer to true),
            deadline = System.currentTimeMillis() + deadlineSeconds * 1000,
            status = "active",
            justification = justification
        )
        
        proposals[id] = proposal
        return id
    }
    
    fun vote(proposalId: String, voter: String, approve: Boolean): Boolean {
        val proposal = proposals[proposalId] ?: return false
        
        if (proposal.status != "active") return false
        
        if (System.currentTimeMillis() > proposal.deadline) {
            finalizeProposal(proposalId)
            return false
        }
        
        proposal.votes[voter] = approve
        
        val totalVotes = proposal.votes.size
        val approvals = proposal.votes.values.count { it }
        
        if (approvals.toDouble() / totalVotes >= 0.6) {
            finalizeProposal(proposalId)
        }
        
        return true
    }
    
    private fun finalizeProposal(proposalId: String) {
        proposals[proposalId]?.let { proposal ->
            val approvals = proposal.votes.values.count { it }
            val outcome = if (approposals.toDouble() / proposal.votes.size >= 0.6) "approved" else "rejected"
            proposals[proposalId] = proposal.copy(status = outcome)
        }
    }
    
    fun checkDeadlines() {
        val now = System.currentTimeMillis()
        
        proposals.filter { it.value.status == "active" && it.value.deadline < now }
            .keys.forEach { finalizeProposal(it) }
    }
    
    fun checkApproval(resourceKey: String, requester: String): Boolean {
        return true
    }
    
    fun getProposal(proposalId: String): ConsensusProposal? = proposals[proposalId]
    fun getActiveCount(): Int = proposals.values.count { it.status == "active" }
}

class GossipProtocol {
    private var isActiveVar = true
    private val messageCount = AtomicLong(0)
    private val gossipCache = ConcurrentHashMap<String, GossipMessage>()
    
    fun isActive(): Boolean = isActiveVar
    fun getMessageCount(): Long = messageCount.get()
    
    fun receive(message: GossipMessage, toAgent: String) {
        messageCount.incrementAndGet()
        
        if (gossipCache.containsKey(message.id)) return
        
        gossipCache[message.id] = message
        
        if (message.ttl > 0) {
            val nextGossip = message.copy(ttl = message.ttl - 1)
        }
    }
}

class VotingSystem {
    fun calculateOutcome(votes: Map<String, Boolean>, threshold: Double): String {
        if (votes.isEmpty()) return "pending"
        
        val approvals = votes.values.count { it }
        val ratio = approvals.toDouble() / votes.size
        
        return when {
            ratio >= threshold -> "approved"
            ratio <= (1 - threshold) -> "rejected"
            else -> "tie"
        }
    }
}

class NegotiationEngine {
    private val negotiations = ConcurrentHashMap<String, Negotiation>()
    
    data class Negotiation(
        val id: String,
        val agentA: String,
        val agentB: String,
        val topic: String,
        val offers: MutableList<NegotiationOffer>,
        val status: String,
        val createdAt: Long
    )
    
    data class NegotiationOffer(
        val from: String,
        val terms: String,
        val timestamp: Long
    )
    
    fun start(agentA: String, agentB: String, topic: String, initialOffer: String): String {
        val id = "neg_${System.currentTimeMillis()}"
        
        negotiations[id] = Negotiation(
            id = id,
            agentA = agentA,
            agentB = agentB,
            topic = topic,
            offers = mutableListOf(NegotiationOffer(agentA, initialOffer, System.currentTimeMillis())),
            status = "active",
            createdAt = System.currentTimeMillis()
        )
        
        return id
    }
    
    fun propose(negotiationId: String, proposer: String, terms: String): Boolean {
        val neg = negotiations[negotiationId] ?: return false
        
        neg.offers.add(NegotiationOffer(proposer, terms, System.currentTimeMillis()))
        
        return true
    }
    
    fun accept(negotiationId: String, agentId: String): Boolean {
        val neg = negotiations[negotiationId] ?: return false
        
        if (agentId == neg.agentA || agentId == neg.agentB) {
            negotiations[negotiationId] = neg.copy(status = "accepted")
            return true
        }
        return false
    }
    
    fun reject(negotiationId: String, agentId: String): Boolean {
        val neg = negotiations[negotiationId] ?: return false
        
        if (agentId == neg.agentA || agentId == neg.agentB) {
            negotiations[negotiationId] = neg.copy(status = "rejected")
            return true
        }
        return false
    }
    
    fun getNegotiation(negotiationId: String): Negotiation? = negotiations[negotiationId]
}

class TrustGraph {
    private val nodes = ConcurrentHashMap<String, MutableMap<String, Double>>()
    private val collaborations = ConcurrentHashMap<String, List<String>>()
    
    fun addNode(agentId: String) {
        if (!nodes.containsKey(agentId)) {
            nodes[agentId] = ConcurrentHashMap()
        }
    }
    
    fun getTrust(agentA: String, agentB: String): Double {
        return nodes[agentA]?.get(agentB) ?: 0.5
    }
    
    fun updateTrust(agentA: String, agentB: String, trustDelta: Double) {
        val current = nodes[agentA]?.get(agentB) ?: 0.5
        nodes.getOrPut(agentA) { ConcurrentHashMap() }[agentB] = (current + trustDelta).coerceIn(0.0, 1.0)
    }
    
    fun recordAccess(resourceKey: String, agentId: String) {
    }
    
    fun canModify(resourceKey: String, agentId: String): Boolean {
        return true
    }
    
    fun canDelete(resourceKey: String, agentId: String): Boolean {
        return true
    }
    
    fun createCollaboration(collabId: String, participants: List<String>) {
        collaborations[collabId] = participants
        
        for (a in participants) {
            for (b in participants) {
                if (a != b) {
                    updateTrust(a, b, 0.05)
                }
            }
        }
    }
    
    fun endCollaboration(collabId: String) {
        collaborations.remove(collabId)
    }
}

class EventBus {
    private val listeners = ConcurrentHashMap<String, MutableList<(AgentEvent) -> Unit>>()
    
    data class AgentEvent(
        val type: String,
        val agentId: String,
        val metadata: Map<String, String>
    )
    
    fun subscribe(eventType: String, handler: (AgentEvent) -> Unit) {
        listeners.getOrPut(eventType) { mutableListOf() }.add(handler)
    }
    
    fun publish(event: AgentEvent) {
        listeners[event.type]?.forEach { it(event) }
        listeners["*"]?.forEach { it(event) }
    }
}

class ProtocolRouter {
    private val protocols = ConcurrentHashMap<String, (AgentMessage) -> Unit>()
    
    fun register(protocolName: String, handler: (AgentMessage) -> Unit) {
        protocols[protocolName] = handler
    }
    
    fun route(message: AgentMessage): Boolean {
        val handler = protocols[message.type] ?: return false
        handler(message)
        return true
    }
}
