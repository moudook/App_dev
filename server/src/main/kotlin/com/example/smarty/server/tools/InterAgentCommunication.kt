package com.example.smarty.server.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class AgentMessage(
    val id: String,
    val fromAgent: String,
    val toAgent: String,
    val type: String,
    val content: String,
    val timestamp: Long,
    val read: Boolean = false
)

@Serializable
data class SharedResource(
    val key: String,
    val value: String,
    val owner: String,
    val createdAt: Long,
    val expiresAt: Long?,
    val accessCount: Int = 0
)

@Serializable
data class AgentState(
    val agentId: String,
    val status: String,
    val currentTask: String?,
    val progress: Double,
    val lastUpdate: Long,
    val capabilities: List<String>,
    val workload: Double
)

@Serializable
data class Collaboration(
    val id: String,
    val name: String,
    val participants: List<String>,
    val sharedGoal: String,
    val status: String,
    val createdAt: Long,
    val coordinator: String
)

class InterAgentCommunication {
    private val logger = LoggerFactory.getLogger(InterAgentCommunication::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val messages = ConcurrentHashMap<String, MutableList<AgentMessage>>()
    private val sharedResources = ConcurrentHashMap<String, SharedResource>()
    private val agentStates = ConcurrentHashMap<String, AgentState>()
    private val collaborations = ConcurrentHashMap<String, Collaboration>()
    private val messageCounter = AtomicLong(0)
    
    fun sendMessage(
        fromAgent: String,
        toAgent: String,
        type: String,
        content: String
    ): String {
        val messageId = "msg_${System.currentTimeMillis()}_${messageCounter.incrementAndGet()}"
        
        val message = AgentMessage(
            id = messageId,
            fromAgent = fromAgent,
            toAgent = toAgent,
            type = type,
            content = content,
            timestamp = System.currentTimeMillis()
        )
        
        messages.getOrPut(toAgent) { mutableListOf() }.add(message)
        
        logger.info("Message: $fromAgent -> $toAgent [$type]")
        return messageId
    }
    
    fun broadcast(
        fromAgent: String,
        type: String,
        content: String
    ): List<String> {
        val recipients = agentStates.keys.filter { it != fromAgent }
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
    
    fun shareResource(
        key: String,
        value: String,
        owner: String,
        ttlSeconds: Long? = null
    ): String {
        val resource = SharedResource(
            key = key,
            value = value,
            owner = owner,
            createdAt = System.currentTimeMillis(),
            expiresAt = ttlSeconds?.let { System.currentTimeMillis() + it * 1000 }
        )
        
        sharedResources[key] = resource
        logger.info("Resource shared: $key by $owner")
        
        return key
    }
    
    fun getResource(key: String, requester: String): String? {
        val resource = sharedResources[key] ?: return null
        
        if (resource.expiresAt != null && System.currentTimeMillis() > resource.expiresAt) {
            sharedResources.remove(key)
            return null
        }
        
        sharedResources[key] = resource.copy(accessCount = resource.accessCount + 1)
        
        return resource.value
    }
    
    fun updateResource(key: String, newValue: String, requester: String): Boolean {
        val resource = sharedResources[key] ?: return false
        
        if (resource.owner != requester) return false
        
        sharedResources[key] = resource.copy(value = newValue)
        return true
    }
    
    fun deleteResource(key: String, requester: String): Boolean {
        val resource = sharedResources[key] ?: return false
        
        if (resource.owner != requester) return false
        
        sharedResources.remove(key)
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
        capabilities: List<String>
    ): Boolean {
        val state = AgentState(
            agentId = agentId,
            status = "idle",
            currentTask = null,
            progress = 0.0,
            lastUpdate = System.currentTimeMillis(),
            capabilities = capabilities,
            workload = 0.0
        )
        
        agentStates[agentId] = state
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
            (capability == null || capability in state.capabilities)
        }
    }
    
    fun createCollaboration(
        name: String,
        participants: List<String>,
        sharedGoal: String,
        coordinator: String
    ): String {
        val collabId = "collab_${name.lowercase().replace(" ", "_")}_${System.currentTimeMillis()}"
        
        val collaboration = Collaboration(
            id = collabId,
            name = name,
            participants = participants,
            sharedGoal = sharedGoal,
            status = "active",
            createdAt = System.currentTimeMillis(),
            coordinator = coordinator
        )
        
        collaborations[collabId] = collaboration
        
        participants.forEach { agent ->
            sendMessage("system", agent, "collaboration_invite", 
                "You've been invited to join '$name'. Goal: $sharedGoal")
        }
        
        logger.info("Collaboration created: $name with ${participants.size} agents")
        return collabId
    }
    
    fun getCollaboration(collabId: String): Collaboration? = collaborations[collabId]
    
    fun endCollaboration(collabId: String): Boolean {
        val collab = collaborations.remove(collabId) ?: return false
        
        collab.participants.forEach { agent ->
            sendMessage("system", agent, "collaboration_ended", 
                "Collaboration '${collab.name}' has ended.")
        }
        
        return true
    }
    
    fun requestHelp(
        fromAgent: String,
        capability: String,
        description: String
    ): String? {
        val available = findAvailableAgents(capability)
        
        if (available.isEmpty()) {
            sendMessage("system", fromAgent, "help_unavailable", 
                "No agents available with capability: $capability")
            return null
        }
        
        val selected = available.minByOrNull { it.workload }!!
        
        sendMessage(fromAgent, selected.agentId, "help_request", description)
        updateAgentState(selected.agentId, status = "helping", workload = selected.workload + 0.3)
        
        return selected.agentId
    }
    
    fun negotiate(
        agentA: String,
        agentB: String,
        topic: String,
        proposedTerms: String
    ): String {
        val negotiationId = "neg_${System.currentTimeMillis()}"
        
        sendMessage(agentA, agentB, "negotiation_proposal", 
            "Topic: $topic\nProposed: $proposedTerms")
        
        return negotiationId
    }
    
    fun formatAgentState(state: AgentState): String {
        return buildString {
            appendLine("[Agent] ${state.agentId}")
            appendLine("  Status: ${state.status}")
            appendLine("  Task: ${state.currentTask ?: "none"}")
            appendLine("  Progress: ${(state.progress * 100).toInt()}%")
            appendLine("  Workload: ${(state.workload * 100).toInt()}%")
            appendLine("  Capabilities: ${state.capabilities.joinToString(", ")}")
        }
    }
    
    fun formatMessages(agentId: String): String {
        val msgs = getMessages(agentId)
        
        return buildString {
            appendLine("[Messages for $agentId]")
            appendLine("-".repeat(40))
            
            if (msgs.isEmpty()) {
                appendLine("No messages.")
            } else {
                msgs.takeLast(10).forEach { msg ->
                    val readStatus = if (msg.read) "[READ]" else "[NEW]"
                    appendLine("$readStatus From: ${msg.fromAgent}")
                    appendLine("  Type: ${msg.type}")
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
            appendLine("-".repeat(40))
            
            if (resources.isEmpty()) {
                appendLine("No shared resources.")
            } else {
                resources.forEach { res ->
                    appendLine("* ${res.key}")
                    appendLine("  Owner: ${res.owner}")
                    appendLine("  Value: ${res.value.take(50)}...")
                    appendLine("  Accesses: ${res.accessCount}")
                    if (res.expiresAt != null) {
                        appendLine("  Expires: ${java.time.Instant.ofEpochMilli(res.expiresAt)}")
                    }
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
        }
    }
    
    fun getStats(): String {
        return buildString {
            appendLine("[Inter-Agent Communication Stats]")
            appendLine("=".repeat(40))
            appendLine("Registered agents: ${agentStates.size}")
            appendLine("Active collaborations: ${collaborations.size}")
            appendLine("Shared resources: ${sharedResources.size}")
            appendLine("Total messages: ${messages.values.sumOf { it.size }}")
            
            val idle = agentStates.values.count { it.status == "idle" }
            val busy = agentStates.values.count { it.status == "busy" }
            appendLine("\nAgent Status: $idle idle, $busy busy")
        }
    }
}
