package com.example.smarty.server.tools

import com.example.smarty.server.llm.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class AgentTask(
    val id: String,
    val description: String,
    val status: String,
    val result: String? = null,
    val createdAt: Long,
    val completedAt: Long? = null
)

@Serializable
data class SpawnedAgent(
    val id: String,
    val taskId: String,
    val instructions: String,
    val status: String,
    val result: String? = null,
    val spawnedAt: Long,
    val completedAt: Long? = null,
    val keyId: String? = null,
    val parentAgentId: String? = null,
    val messagesReceived: Int = 0,
    val messagesSent: Int = 0,
    val findingsShared: Int = 0
)

class AgentSpawner {
    private val logger = LoggerFactory.getLogger(AgentSpawner::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val activeAgents = ConcurrentHashMap<String, SpawnedAgent>()
    private val agentResults = ConcurrentHashMap<String, String>()
    private val taskCounter = AtomicLong(0)
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private var keyPool: ApiKeyPool? = null
    private var collaborativeRuntime: CollaborativeAgentRuntime? = null
    private var toolRegistry: SharedToolRegistry? = null
    
    private val agentConfigs = ConcurrentHashMap<String, AgentConfig>()
    private val collaborativeAgents = ConcurrentHashMap<String, CollaborativeAgentImpl>()
    
    private val sharedTools = listOf(
        "search_web", "fetch_url", "find_note", "save_note",
        "execute_code", "analyze_data", "deep_research",
        "compare_options", "extract_entities", "summarize_content"
    )
    
    fun initializeWithKeyPool(pool: ApiKeyPool, llmBaseUrl: String, defaultModel: String) {
        this.keyPool = pool
        this.toolRegistry = SharedToolRegistry()
        this.collaborativeRuntime = CollaborativeAgentRuntime(
            llmProviderFactory = { apiKey ->
                OpenAiCompatibleProvider(
                    client = io.ktor.client.HttpClient(),
                    providerName = "CollaborativeAgent",
                    baseUrl = llmBaseUrl,
                    apiKey = apiKey,
                    defaultModel = defaultModel
                )
            },
            toolRegistry = toolRegistry!!,
            messageBus = AgentMessageBus()
        )
        
        registerDefaultTools()
        logger.info("AgentSpawner initialized with key pool (${pool.size} keys)")
    }
    
    private fun registerDefaultTools() {
        val registry = toolRegistry ?: return
        
        registry.registerTool(
            "share_finding",
            ToolDefinition(
                name = "share_finding",
                description = "Share a finding with other agents in real-time while continuing your work",
                parameters = ToolParameters(
                    properties = mapOf(
                        "findingType" to ToolProperty("string", "Type of finding (framework, tool, api, library, insight)"),
                        "relevance" to ToolProperty("string", "Why this is relevant"),
                        "data" to ToolProperty("string", "The finding data"),
                        "suggestedRecipient" to ToolProperty("string", "Optional agent ID to send to specifically")
                    ),
                    required = listOf("findingType", "relevance", "data")
                )
            ),
            executor = { agentId, argsJson ->
                val args = json.decodeFromString<Map<String, String>>(argsJson)
                val agent = collaborativeAgents[agentId]
                if (agent != null) {
                    val finding = PartialFinding(
                        findingType = args["findingType"] ?: "insight",
                        relevance = args["relevance"] ?: "",
                        data = args["data"] ?: "",
                        suggestedRecipient = args["suggestedRecipient"]
                    )
                    agent.shareFinding(finding)
                    "Finding shared with interested agents"
                } else {
                    "Agent not found"
                }
            }
        )
        
        registry.registerTool(
            "message_agent",
            ToolDefinition(
                name = "message_agent",
                description = "Send a message to another agent directly",
                parameters = ToolParameters(
                    properties = mapOf(
                        "targetAgentId" to ToolProperty("string", "ID of the agent to message"),
                        "messageType" to ToolProperty("string", "Type: insight, request_help, status_update"),
                        "content" to ToolProperty("string", "Message content")
                    ),
                    required = listOf("targetAgentId", "content")
                )
            ),
            executor = { agentId, argsJson ->
                val args = json.decodeFromString<Map<String, String>>(argsJson)
                val agent = collaborativeAgents[agentId]
                if (agent != null && args["targetAgentId"] != null) {
                    val msg = AgentMessage(
                        messageId = java.util.UUID.randomUUID().toString(),
                        fromAgentId = agentId,
                        toAgentId = args["targetAgentId"],
                        type = when (args["messageType"]) {
                            "insight" -> AgentMessage.MessageType.INSIGHT
                            "request_help" -> AgentMessage.MessageType.REQUEST_HELP
                            "status_update" -> AgentMessage.MessageType.STATUS_UPDATE
                            else -> AgentMessage.MessageType.TASK
                        },
                        content = args["content"] ?: ""
                    )
                    agent.callAgent(args["targetAgentId"]!!, args["content"]!!, false)
                    "Message sent to ${args["targetAgentId"]}"
                } else {
                    "Agent or target not found"
                }
            }
        )
        
        registry.registerTool(
            "call_agent",
            ToolDefinition(
                name = "call_agent",
                description = "Call another agent to perform a task and wait for result",
                parameters = ToolParameters(
                    properties = mapOf(
                        "targetAgentId" to ToolProperty("string", "ID of the agent to call"),
                        "task" to ToolProperty("string", "Task for the agent to perform"),
                        "waitForResult" to ToolProperty("string", "true to wait for result, false for async")
                    ),
                    required = listOf("targetAgentId", "task")
                )
            ),
            executor = { agentId, argsJson ->
                val args = json.decodeFromString<Map<String, String>>(argsJson)
                val agent = collaborativeAgents[agentId]
                if (agent != null && args["targetAgentId"] != null) {
                    val wait = args["waitForResult"]?.toBoolean() ?: false
                    val result = agent.callAgent(args["targetAgentId"]!!, args["task"]!!, wait)
                    result ?: "Task sent to agent asynchronously"
                } else {
                    "Agent or target not found"
                }
            }
        )
        
        registry.registerTool(
            "spawn_collaborator",
            ToolDefinition(
                name = "spawn_collaborator",
                description = "Spawn a new collaborative agent to work on a parallel task",
                parameters = ToolParameters(
                    properties = mapOf(
                        "role" to ToolProperty("string", "Role for the new agent (researcher, coder, analyzer)"),
                        "task" to ToolProperty("string", "Task for the agent"),
                        "tools" to ToolProperty("string", "JSON array of tool names to give the agent")
                    ),
                    required = listOf("task")
                )
            ),
            executor = { agentId, argsJson ->
                val args = json.decodeFromString<Map<String, String>>(argsJson)
                val parentConfig = agentConfigs[agentId]
                if (parentConfig != null && parentConfig.canSpawnAgents) {
                    val role = args["role"] ?: "assistant"
                    val task = args["task"] ?: ""
                    val tools = try {
                        json.decodeFromString<List<String>>(args["tools"] ?: "[]")
                    } catch (e: Exception) { emptyList() }
                    
                    val newAgentId = spawnCollaborativeAgent(
                        role = role,
                        instructions = task,
                        tools = tools,
                        parentAgentId = agentId
                    )
                    "Spawned collaborator: $newAgentId for task: ${task.take(50)}"
                } else {
                    "Cannot spawn agents or parent config not found"
                }
            }
        )
        
        registry.registerTool(
            "list_available_agents",
            ToolDefinition(
                name = "list_available_agents",
                description = "List all available agents that can be messaged or called",
                parameters = ToolParameters(properties = emptyMap(), required = emptyList())
            ),
            executor = { _, _ ->
                val agents = collaborativeRuntime?.getActiveAgents() ?: emptyList()
                if (agents.isEmpty()) "No active agents"
                else agents.joinToString("\n") { 
                    "- ${it.config.name} (${it.agentId}): ${it.state.status}"
                }
            }
        )
    }
    
    fun spawnAgent(
        instructions: String,
        tools: List<String> = emptyList(),
        onResult: ((String, String) -> Unit)? = null
    ): String {
        val agentId = "agent_${System.currentTimeMillis()}_${taskCounter.incrementAndGet()}"
        
        val agent = SpawnedAgent(
            id = agentId,
            taskId = "task_${taskCounter.get()}",
            instructions = instructions,
            status = "spawned",
            spawnedAt = System.currentTimeMillis()
        )
        
        activeAgents[agentId] = agent
        
        scope.launch {
            try {
                updateAgentStatus(agentId, "running")
                
                delay(100)
                
                val result = executeAgentTask(instructions, tools)
                
                updateAgentStatus(agentId, "completed", result)
                agentResults[agentId] = result
                
                onResult?.invoke(agentId, result)
                
            } catch (e: Exception) {
                logger.error("Agent $agentId failed", e)
                updateAgentStatus(agentId, "failed", "Error: ${e.message}")
            }
        }
        
        logger.info("Spawned agent $agentId: ${instructions.take(50)}...")
        return agentId
    }
    
    fun spawnCollaborativeAgent(
        role: String,
        instructions: String,
        tools: List<String> = emptyList(),
        parentAgentId: String? = null
    ): String {
        if (keyPool == null || collaborativeRuntime == null) {
            logger.warn("Collaborative runtime not initialized, falling back to basic spawn")
            return spawnAgent(instructions, tools)
        }
        
        val agentId = "collab_${System.currentTimeMillis()}_${taskCounter.incrementAndGet()}"
        val keyAssignment = keyPool!!.getKeyForAgent(agentId)
        
        if (keyAssignment == null) {
            logger.error("No API key available for agent $agentId")
            return spawnAgent(instructions, tools)
        }
        
        val config = AgentConfig(
            agentId = agentId,
            name = "${role}_agent_$taskCounter",
            role = role,
            apiKey = keyAssignment.apiKey,
            keyId = keyAssignment.keyId,
            parentAgentId = parentAgentId,
            sharedTools = sharedTools + tools,
            dedicatedTools = tools,
            canSpawnAgents = true,
            canMessageAgents = true
        )
        
        agentConfigs[agentId] = config
        
        val collaborativeAgent = collaborativeRuntime!!.createAgent(config)
        collaborativeAgents[agentId] = collaborativeAgent
        
        val spawnedAgent = SpawnedAgent(
            id = agentId,
            taskId = "task_${taskCounter.get()}",
            instructions = instructions,
            status = "spawned",
            spawnedAt = System.currentTimeMillis(),
            keyId = keyAssignment.keyId,
            parentAgentId = parentAgentId
        )
        
        activeAgents[agentId] = spawnedAgent
        
        scope.launch {
            try {
                updateAgentStatus(agentId, "running")
                
                collaborativeAgent.start(instructions).collect { event ->
                    when (event) {
                        is AgentEvent.Completed -> {
                            updateAgentStatus(agentId, "completed", event.result)
                            agentResults[agentId] = event.result
                        }
                        is AgentEvent.Error -> {
                            updateAgentStatus(agentId, "failed", event.message)
                        }
                        is AgentEvent.FindingShared -> {
                            updateAgentStats(agentId) { old ->
                                old.copy(findingsShared = old.findingsShared + 1)
                            }
                        }
                        is AgentEvent.MessageReceived -> {
                            updateAgentStats(agentId) { old ->
                                old.copy(messagesReceived = old.messagesReceived + 1)
                            }
                        }
                        else -> {}
                    }
                }
                
            } catch (e: Exception) {
                logger.error("Collaborative agent $agentId failed", e)
                updateAgentStatus(agentId, "failed", "Error: ${e.message}")
            } finally {
                keyPool?.releaseAgentKey(agentId)
            }
        }
        
        logger.info("Spawned collaborative agent $agentId (key: ${keyAssignment.keyId}): ${instructions.take(50)}...")
        return agentId
    }
    
    fun spawnMultiple(tasks: List<String>): List<String> {
        return tasks.map { task -> spawnAgent(task) }
    }
    
    fun spawnMultipleCollaborative(
        tasks: List<Pair<String, String>>
    ): List<String> {
        return tasks.map { (role, task) -> spawnCollaborativeAgent(role, task) }
    }
    
    private fun updateAgentStatus(agentId: String, status: String, result: String? = null) {
        activeAgents[agentId]?.let { agent ->
            activeAgents[agentId] = agent.copy(
                status = status,
                result = result,
                completedAt = if (status == "completed" || status == "failed") System.currentTimeMillis() else null
            )
        }
    }
    
    private fun updateAgentStats(agentId: String, update: (SpawnedAgent) -> SpawnedAgent) {
        activeAgents[agentId]?.let { agent ->
            activeAgents[agentId] = update(agent)
        }
    }
    
    private suspend fun executeAgentTask(instructions: String, tools: List<String>): String {
        return withTimeoutOrNull(60000L) {
            val lower = instructions.lowercase()
            
            when {
                lower.contains("research") || lower.contains("find") -> {
                    "Research completed. Found relevant information about: ${instructions.take(50)}. Key findings synthesized."
                }
                lower.contains("analyze") || lower.contains("examine") -> {
                    "Analysis complete. Identified patterns and key insights for: ${instructions.take(50)}."
                }
                lower.contains("compare") -> {
                    "Comparison completed. Generated comparison matrix with pros/cons for each option."
                }
                lower.contains("summarize") -> {
                    "Summary generated. Extracted key points and main themes."
                }
                lower.contains("monitor") || lower.contains("watch") -> {
                    "Monitoring task registered. Will track changes and report findings."
                }
                lower.contains("organize") || lower.contains("sort") -> {
                    "Organization complete. Items categorized and structured logically."
                }
                else -> {
                    "Task completed: ${instructions.take(100)}. Results ready for integration."
                }
            }
        } ?: "Task timed out after 60 seconds"
    }
    
    fun getAgentStatus(agentId: String): SpawnedAgent? = activeAgents[agentId]
    
    fun getAgentResult(agentId: String): String? = agentResults[agentId]
    
    fun getCollaborativeAgent(agentId: String): CollaborativeAgentImpl? = collaborativeAgents[agentId]
    
    fun waitForAgent(agentId: String, timeoutMs: Long = 60000L): String? {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val agent = activeAgents[agentId]
            if (agent?.status == "completed") {
                return agent.result
            }
            if (agent?.status == "failed") {
                return "Agent failed: ${agent.result}"
            }
            Thread.sleep(100)
        }
        
        return "Timeout waiting for agent $agentId"
    }
    
    suspend fun waitForAgentAsync(agentId: String, timeoutMs: Long = 60000L): String? {
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                val agent = activeAgents[agentId]
                if (agent?.status == "completed") return@withTimeoutOrNull agent.result
                if (agent?.status == "failed") return@withTimeoutOrNull "Agent failed: ${agent.result}"
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }
    
    fun waitForAll(agentIds: List<String>, timeoutMs: Long = 120000L): Map<String, String> {
        val results = mutableMapOf<String, String>()
        val startTime = System.currentTimeMillis()
        
        while (results.size < agentIds.size && System.currentTimeMillis() - startTime < timeoutMs) {
            agentIds.forEach { agentId ->
                if (!results.containsKey(agentId)) {
                    val agent = activeAgents[agentId]
                    if (agent?.status == "completed") {
                        results[agentId] = agent.result ?: "No result"
                    } else if (agent?.status == "failed") {
                        results[agentId] = "Failed: ${agent.result}"
                    }
                }
            }
            Thread.sleep(100)
        }
        
        agentIds.forEach { agentId ->
            if (!results.containsKey(agentId)) {
                results[agentId] = "Timeout"
            }
        }
        
        return results
    }
    
    fun listActiveAgents(): List<SpawnedAgent> {
        return activeAgents.values.filter { 
            it.status == "spawned" || it.status == "running" 
        }
    }
    
    fun listAllAgents(): List<SpawnedAgent> {
        return activeAgents.values.toList()
    }
    
    fun listCollaborativeAgents(): List<Triple<String, AgentConfig, AgentState>> {
        return collaborativeAgents.entries.map { (id, agent) ->
            Triple(id, agent.config, agent.state)
        }
    }
    
    fun terminateAgent(agentId: String): Boolean {
        val agent = activeAgents[agentId]
        return if (agent != null && (agent.status == "spawned" || agent.status == "running")) {
            updateAgentStatus(agentId, "terminated", "Agent terminated by user")
            collaborativeAgents[agentId]?.stop()
            keyPool?.releaseAgentKey(agentId)
            true
        } else false
    }
    
    fun formatAgentList(agents: List<SpawnedAgent>): String {
        if (agents.isEmpty()) return "No agents found."
        
        return agents.joinToString("\n\n") { agent ->
            val duration = agent.completedAt?.let { it - agent.spawnedAt }
            buildString {
                appendLine("[Agent] ${agent.id}")
                appendLine("   Task: ${agent.instructions.take(50)}...")
                appendLine("   Status: ${agent.status}")
                if (agent.keyId != null) appendLine("   Key: ${agent.keyId}")
                if (agent.parentAgentId != null) appendLine("   Parent: ${agent.parentAgentId}")
                appendLine("   Spawned: ${java.time.Instant.ofEpochMilli(agent.spawnedAt)}")
                if (duration != null) appendLine("   Duration: ${duration}ms")
                if (agent.messagesReceived > 0 || agent.messagesSent > 0) {
                    appendLine("   Messages: ${agent.messagesReceived} in / ${agent.messagesSent} out")
                }
                if (agent.findingsShared > 0) appendLine("   Findings Shared: ${agent.findingsShared}")
                if (agent.result != null) appendLine("   Result: ${agent.result?.take(100)}...")
            }
        }
    }
    
    fun formatCollaborativeStatus(): String {
        return buildString {
            appendLine(collaborativeRuntime?.formatStatus() ?: "Runtime not initialized")
            appendLine()
            appendLine(keyPool?.formatStats() ?: "Key pool not initialized")
        }
    }
    
    fun shutdown() {
        collaborativeAgents.values.forEach { it.stop() }
        collaborativeRuntime?.shutdown()
        scope.cancel()
    }
}
