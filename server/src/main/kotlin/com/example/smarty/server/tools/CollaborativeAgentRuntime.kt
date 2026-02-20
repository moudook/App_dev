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
    val priority: Int = 5
) {
    enum class MessageType {
        TASK, RESULT, PARTIAL_FINDING, INSIGHT, REQUEST_HELP, 
        BROADCAST, STATUS_UPDATE, TOOL_SHARE, DIRECT_CALL, ERROR
    }
}

data class PartialFinding(
    val findingType: String,
    val relevance: String,
    val data: String,
    val suggestedRecipient: String? = null
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
    val lastActivity: Long
) {
    enum class AgentStatus {
        IDLE, RUNNING, WAITING, COMPLETED, FAILED
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
}

class CollaborativeAgentRuntime(
    private val llmProviderFactory: (apiKey: String) -> LlmProvider,
    private val toolRegistry: SharedToolRegistry,
    private val messageBus: AgentMessageBus
) {
    private val logger = LoggerFactory.getLogger(CollaborativeAgentRuntime::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val agents = ConcurrentHashMap<String, CollaborativeAgentImpl>()
    private val agentScopes = ConcurrentHashMap<String, CoroutineScope>()
    
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
            }
        )
        
        agents[config.agentId] = agent
        messageBus.registerAgent(config.agentId, agent)
        
        logger.info("Created collaborative agent: ${config.name} (${config.agentId}) with key ${config.keyId}")
        return agent
    }
    
    private fun handleAgentEvent(agentId: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.FindingShared -> {
                logger.info("Agent $agentId shared finding: ${event.finding.findingType}")
            }
            is AgentEvent.MessageReceived -> {
                logger.debug("Agent $agentId received message from ${event.message.fromAgentId}")
            }
            is AgentEvent.StatusChange -> {
                logger.info("Agent $agentId status: ${event.oldStatus} -> ${event.newStatus}")
            }
            is AgentEvent.Error -> {
                logger.error("Agent $agentId error: ${event.message}", event.throwable)
            }
            else -> {}
        }
    }
    
    fun getAgent(agentId: String): CollaborativeAgentImpl? = agents[agentId]
    
    fun getAllAgents(): List<CollaborativeAgentImpl> = agents.values.toList()
    
    fun getActiveAgents(): List<CollaborativeAgentImpl> = agents.values.filter {
        it.state.status == AgentState.AgentStatus.RUNNING || it.state.status == AgentState.AgentStatus.WAITING
    }
    
    fun stopAgent(agentId: String) {
        agents[agentId]?.stop()
        agentScopes[agentId]?.cancel()
        agentScopes.remove(agentId)
        messageBus.unregisterAgent(agentId)
    }
    
    fun shutdown() {
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
            agents.values.forEach { agent ->
                appendLine("[${agent.config.name}] ${agent.agentId}")
                appendLine("  Status: ${agent.state.status}")
                appendLine("  Task: ${agent.state.currentTask ?: "none"}")
                appendLine("  Progress: ${(agent.state.progress * 100).toInt()}%")
                appendLine("  Key: ${agent.config.keyId}")
                appendLine("  Messages: ${agent.state.messagesReceived} in / ${agent.state.messagesSent} out")
                appendLine()
            }
        }
    }
}

class CollaborativeAgentImpl(
    override val config: AgentConfig,
    private val llmProvider: LlmProvider,
    private val toolRegistry: SharedToolRegistry,
    private val messageBus: AgentMessageBus,
    private val scope: CoroutineScope,
    private val onEvent: (AgentEvent) -> Unit
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
        lastActivity = System.currentTimeMillis()
    )
    
    override val agentId: String get() = config.agentId
    override val state: AgentState get() = _state
    
    private val messageChannel = Channel<AgentMessage>(Channel.UNLIMITED)
    private val findingsChannel = Channel<PartialFinding>(Channel.UNLIMITED)
    private var currentJob: Job? = null
    
    private var messageProcessorJob: Job? = null
    
    init {
        startMessageProcessor()
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
    
    override suspend fun receiveMessage(message: AgentMessage) {
        messageChannel.send(message)
    }
    
    private suspend fun processIncomingMessage(message: AgentMessage) {
        _state = _state.copy(
            messagesReceived = _state.messagesReceived + 1,
            lastActivity = System.currentTimeMillis()
        )
        
        onEvent(AgentEvent.MessageReceived(message))
        
        when (message.type) {
            AgentMessage.MessageType.INSIGHT, AgentMessage.MessageType.PARTIAL_FINDING -> {
                logger.info("Agent ${config.agentId} received insight from ${message.fromAgentId}: ${message.content.take(100)}")
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
                    "relevance" to finding.relevance
                )
            )
            
            recipients.forEach { recipientId ->
                messageBus.send(message.copy(toAgentId = recipientId))
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
            progress = 0.0
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
            
            while (iteration < config.maxIterations) {
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
                
                _state = _state.copy(
                    progress = iteration.toDouble() / config.maxIterations,
                    lastActivity = System.currentTimeMillis()
                )
                emit(AgentEvent.TaskProgress(_state.progress, "Iteration $iteration complete"))
                
                if (lastContent.isNotEmpty() && !lastContent.contains("tool_call")) {
                    break
                }
            }
            
            _state = _state.copy(
                status = AgentState.AgentStatus.COMPLETED,
                progress = 1.0
            )
            emit(AgentEvent.StatusChange(AgentState.AgentStatus.RUNNING, AgentState.AgentStatus.COMPLETED))
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
                    suggestedRecipient = null
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

You can:
1. Execute tools and share useful findings with other agents in real-time
2. Send messages to specific agents when you find something relevant to their task
3. Request help from other agents when needed
4. Continue your work while sharing insights

Available tools:
- Shared: ${config.sharedTools.joinToString(", ")}
- Dedicated: ${config.dedicatedTools.joinToString(", ")}

When you find something useful for another agent (framework, tool, insight), share it immediately using shareFinding.
Work efficiently and collaborate actively.
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
}

class SharedToolRegistry {
    private val tools = ConcurrentHashMap<String, ToolDefinition>()
    private val executors = ConcurrentHashMap<String, suspend (String, String) -> String>()
    private val agentToolUsage = ConcurrentHashMap<String, MutableSet<String>>()
    
    fun registerTool(
        toolName: String,
        definition: ToolDefinition,
        executor: suspend (String, String) -> String
    ) {
        tools[toolName] = definition
        executors[toolName] = executor
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
        
        return executor(agentId, argsJson)
    }
    
    fun getToolUsageStats(): Map<String, Set<String>> = agentToolUsage.mapValues { it.value.toSet() }
}
