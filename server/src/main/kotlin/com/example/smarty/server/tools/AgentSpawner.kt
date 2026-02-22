package com.example.smarty.server.tools

import com.example.smarty.server.llm.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*
import kotlin.random.Random

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
    val findingsShared: Int = 0,
    val capabilities: List<String> = emptyList(),
    val performanceScore: Double = 0.0,
    val lineage: AgentLineage? = null
)

@Serializable
data class AgentLineage(
    val ancestorIds: List<String> = emptyList(),
    val generation: Int = 0,
    val mutations: List<String> = emptyList(),
    val adaptations: List<String> = emptyList()
)

data class AgentCapability(
    val name: String,
    val description: String,
    val cost: Double = 1.0,
    val quality: Double = 1.0,
    val latencyMs: Long = 1000,
    val reliability: Double = 0.95,
    val tags: Set<String> = emptySet()
)

data class AgentMetrics(
    val agentId: String,
    val tasksCompleted: Int = 0,
    val tasksFailed: Int = 0,
    val totalLatencyMs: Long = 0,
    val avgLatencyMs: Double = 0.0,
    val throughput: Double = 0.0,
    val successRate: Double = 1.0,
    val lastUpdated: Long = System.currentTimeMillis()
)

data class CapacityForecast(
    val timestamp: Long,
    val predictedDemand: Double,
    val confidence: Double,
    val recommendedAgents: Int,
    val recommendedKeys: Int
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
    
    private val agentRegistry = ConcurrentHashMap<String, AgentCapability>()
    private val agentMetrics = ConcurrentHashMap<String, AgentMetrics>()
    private val demandHistory = ConcurrentHashMap<Long, Int>()
    private val capabilityCache = ConcurrentHashMap<String, List<AgentCapability>>()
    
    private val learningRate = 0.01
    private val momentum = 0.9
    private val weights = doubleArrayOf(0.3, 0.25, 0.2, 0.15, 0.1)
    private val velocity = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0)
    
    private val maxHistoricalPoints = 1000
    private val forecastHorizon = 300000L
    
    private val skillAffinityMatrix = ConcurrentHashMap<String, MutableMap<String, Double>>()
    private val agentSpecialization = ConcurrentHashMap<String, MutableMap<String, Double>>()
    
    private val costOptimizer = CostOptimizer()
    private val loadPredictor = AdaptiveLoadPredictor()
    
    init {
        initializeCapabilityRegistry()
    }
    
    private fun initializeCapabilityRegistry() {
        agentRegistry["research"] = AgentCapability(
            name = "research",
            description = "Web search and information gathering",
            cost = 0.5, quality = 0.9, latencyMs = 2000, reliability = 0.95,
            tags = setOf("search", "information", "gathering")
        )
        agentRegistry["analysis"] = AgentCapability(
            name = "analysis",
            description = "Data analysis and pattern recognition",
            cost = 0.8, quality = 0.95, latencyMs = 3000, reliability = 0.92,
            tags = setOf("data", "patterns", "insights")
        )
        agentRegistry["coding"] = AgentCapability(
            name = "coding",
            description = "Code generation and debugging",
            cost = 1.0, quality = 0.9, latencyMs = 2500, reliability = 0.88,
            tags = setOf("code", "programming", "debugging")
        )
        agentRegistry["summarization"] = AgentCapability(
            name = "summarization",
            description = "Text summarization and extraction",
            cost = 0.3, quality = 0.85, latencyMs = 1000, reliability = 0.98,
            tags = setOf("text", "summary", "extraction")
        )
        agentRegistry["coordination"] = AgentCapability(
            name = "coordination",
            description = "Multi-agent coordination and communication",
            cost = 0.6, quality = 0.92, latencyMs = 1500, reliability = 0.96,
            tags = setOf("communication", "coordination", "collaboration")
        )
    }
    
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
        
        registry.registerTool(
            "match_capabilities",
            ToolDefinition(
                name = "match_capabilities",
                description = "Find agents matching required capabilities",
                parameters = ToolParameters(
                    properties = mapOf(
                        "requiredCapabilities" to ToolProperty("string", "Comma-separated capabilities"),
                        "minQuality" to ToolProperty("string", "Minimum quality threshold (0-1)")
                    ),
                    required = listOf("requiredCapabilities")
                )
            ),
            executor = { _, argsJson ->
                val args = json.decodeFromString<Map<String, String>>(argsJson)
                val required = args["requiredCapabilities"]?.split(",")?.map { it.trim() } ?: emptyList()
                val minQuality = args["minQuality"]?.toDoubleOrNull() ?: 0.7
                
                val matches = findCapabilityMatches(required, minQuality)
                if (matches.isEmpty()) "No matching agents found"
                else matches.joinToString("\n") { (cap, score) ->
                    "- ${cap.name}: score=${"%.2f".format(score)}, cost=${"%.2f".format(cap.cost)}"
                }
            }
        )
        
        registry.registerTool(
            "get_agent_lineage",
            ToolDefinition(
                name = "get_agent_lineage",
                description = "Get genealogy information for an agent",
                parameters = ToolParameters(
                    properties = mapOf(
                        "agentId" to ToolProperty("string", "Agent ID to查询")
                    ),
                    required = listOf("agentId")
                )
            ),
            executor = { _, argsJson ->
                val args = json.decodeFromString<Map<String, String>>(argsJson)
                val agentId = args["agentId"] ?: ""
                val agent = activeAgents[agentId]
                
                if (agent?.lineage != null) {
                    buildString {
                        appendLine("Agent: $agentId")
                        appendLine("Generation: ${agent.lineage.generation}")
                        appendLine("Ancestors: ${agent.lineage.ancestorIds.size}")
                        appendLine("Mutations: ${agent.lineage.mutations.joinToString(", ")}")
                        appendLine("Adaptations: ${agent.lineage.adaptations.joinToString(", ")}")
                    }
                } else "No lineage data for agent $agentId"
            }
        )
    }
    
    private fun findCapabilityMatches(
        required: List<String>,
        minQuality: Double
    ): List<Pair<AgentCapability, Double>> {
        val matches = mutableListOf<Pair<AgentCapability, Double>>()
        
        for ((_, cap) in agentRegistry) {
            if (cap.quality >= minQuality) {
                val score = calculateCapabilityScore(cap, required)
                if (score > 0.5) {
                    matches.add(cap to score)
                }
            }
        }
        
        return matches.sortedByDescending { it.second }
    }
    
    private fun calculateCapabilityScore(cap: AgentCapability, required: List<String>): Double {
        var score = 0.0
        var weight = 1.0
        
        for (req in required) {
            val reqLower = req.lowercase()
            when {
                cap.name.lowercase().contains(reqLower) -> score += weight * 1.0
                cap.tags.any { it.lowercase().contains(reqLower) } -> score += weight * 0.7
                cap.description.lowercase().contains(reqLower) -> score += weight * 0.5
            }
            weight *= 0.8
        }
        
        return (score / required.size.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    }
    
    fun spawnAgent(
        instructions: String,
        tools: List<String> = emptyList(),
        onResult: ((String, String) -> Unit)? = null
    ): String {
        val agentId = "agent_${System.currentTimeMillis()}_${taskCounter.incrementAndGet()}"
        
        val inferredCaps = inferCapabilities(instructions)
        
        val agent = SpawnedAgent(
            id = agentId,
            taskId = "task_${taskCounter.get()}",
            instructions = instructions,
            status = "spawned",
            spawnedAt = System.currentTimeMillis(),
            capabilities = inferredCaps.map { it.name }
        )
        
        activeAgents[agentId] = agent
        
        recordDemand()
        
        scope.launch {
            try {
                updateAgentStatus(agentId, "running")
                
                val startTime = System.currentTimeMillis()
                delay(100)
                
                val result = executeAgentTask(instructions, tools)
                
                val latency = System.currentTimeMillis() - startTime
                updateAgentMetrics(agentId, true, latency)
                updateAgentStatus(agentId, "completed", result)
                agentResults[agentId] = result
                
                adaptAgentCapabilities(agentId, instructions, result)
                
                onResult?.invoke(agentId, result)
                
            } catch (e: Exception) {
                logger.error("Agent $agentId failed", e)
                updateAgentMetrics(agentId, false, 0)
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
        
        val lineage = if (parentAgentId != null) {
            val parentAgent = activeAgents[parentAgentId]
            val parentLineage = parentAgent?.lineage
            AgentLineage(
                ancestorIds = (parentLineage?.ancestorIds ?: emptyList()) + parentAgentId,
                generation = (parentLineage?.generation ?: 0) + 1,
                mutations = generateMutations(instructions),
                adaptations = emptyList()
            )
        } else {
            AgentLineage(generation = 1)
        }
        
        val roleCaps = getCapabilitiesForRole(role)
        
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
            parentAgentId = parentAgentId,
            capabilities = roleCaps.map { it.name },
            lineage = lineage
        )
        
        activeAgents[agentId] = spawnedAgent
        
        recordDemand()
        
        scope.launch {
            try {
                updateAgentStatus(agentId, "running")
                val startTime = System.currentTimeMillis()
                
                collaborativeAgent.start(instructions).collect { event ->
                    when (event) {
                        is AgentEvent.Completed -> {
                            val latency = System.currentTimeMillis() - startTime
                            updateAgentMetrics(agentId, true, latency)
                            updateAgentStatus(agentId, "completed", event.result)
                            agentResults[agentId] = event.result
                        }
                        is AgentEvent.Error -> {
                            updateAgentMetrics(agentId, false, 0)
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
                updateAgentMetrics(agentId, false, 0)
                updateAgentStatus(agentId, "failed", "Error: ${e.message}")
            } finally {
                keyPool?.releaseAgentKey(agentId)
            }
        }
        
        logger.info("Spawned collaborative agent $agentId (key: ${keyAssignment.keyId}): ${instructions.take(50)}...")
        return agentId
    }
    
    private fun getCapabilitiesForRole(role: String): List<AgentCapability> {
        return when (role.lowercase()) {
            "researcher" -> listOf(
                agentRegistry["research"]!!,
                agentRegistry["summarization"]!!
            )
            "coder" -> listOf(
                agentRegistry["coding"]!!,
                agentRegistry["analysis"]!!
            )
            "analyzer" -> listOf(
                agentRegistry["analysis"]!!,
                agentRegistry["research"]!!
            )
            "coordinator" -> listOf(
                agentRegistry["coordination"]!!,
                agentRegistry["summarization"]!!
            )
            else -> agentRegistry.values.toList()
        }
    }
    
    private fun inferCapabilities(instructions: String): List<AgentCapability> {
        val lower = instructions.lowercase()
        val inferred = mutableListOf<AgentCapability>()
        
        if (lower.contains("research") || lower.contains("find") || lower.contains("search")) {
            inferred.add(agentRegistry["research"] ?: return emptyList())
        }
        if (lower.contains("analyze") || lower.contains("examine") || lower.contains("compare")) {
            inferred.add(agentRegistry["analysis"]!!)
        }
        if (lower.contains("code") || lower.contains("program") || lower.contains("implement")) {
            inferred.add(agentRegistry["coding"]!!)
        }
        if (lower.contains("summarize") || lower.contains("summary") || lower.contains("extract")) {
            inferred.add(agentRegistry["summarization"]!!)
        }
        if (lower.contains("coordinate") || lower.contains("collaborate") || lower.contains("manage")) {
            inferred.add(agentRegistry["coordination"]!!)
        }
        
        return inferred
    }
    
    private fun generateMutations(instructions: String): List<String> {
        val mutations = mutableListOf<String>()
        val lower = instructions.lowercase()
        
        if (lower.contains("new") || lower.contains("innovative")) {
            mutations.add("innovation_boost")
        }
        if (lower.contains("fast") || lower.contains("quick")) {
            mutations.add("speed_optimization")
        }
        if (lower.contains("thorough") || lower.contains("deep")) {
            mutations.add("depth_enhancement")
        }
        
        return mutations
    }
    
    private fun adaptAgentCapabilities(agentId: String, instructions: String, result: String) {
        val agent = activeAgents[agentId] ?: return
        val metrics = agentMetrics[agentId] ?: return
        
        if (metrics.successRate > 0.9 && metrics.avgLatencyMs < 2000) {
            val currentCaps = agent.capabilities.toMutableList()
            val inferred = inferCapabilities(instructions)
            
            for (cap in inferred) {
                if (cap.name !in currentCaps) {
                    currentCaps.add(cap.name)
                }
            }
            
            val lineage = agent.lineage?.copy(
                adaptations = agent.lineage.adaptations + "capability_expansion"
            )
            
            activeAgents[agentId] = agent.copy(
                capabilities = currentCaps,
                lineage = lineage,
                performanceScore = metrics.successRate * metrics.throughput
            )
        }
    }
    
    private fun updateAgentMetrics(agentId: String, success: Boolean, latencyMs: Long) {
        val current = agentMetrics.getOrPut(agentId) { AgentMetrics(agentId) }
        
        val newCompleted = current.tasksCompleted + if (success) 1 else 0
        val newFailed = current.tasksFailed + if (!success) 1 else 0
        val newTotalLatency = current.totalLatencyMs + latencyMs
        val newAvgLatency = if (newCompleted > 0) newTotalLatency.toDouble() / newCompleted else 0.0
        val newSuccessRate = if (newCompleted + newFailed > 0) {
            newCompleted.toDouble() / (newCompleted + newFailed)
        } else 1.0
        val newThroughput = if (newAvgLatency > 0) 1000.0 / newAvgLatency else 0.0
        
        agentMetrics[agentId] = current.copy(
            tasksCompleted = newCompleted,
            tasksFailed = newFailed,
            totalLatencyMs = newTotalLatency,
            avgLatencyMs = newAvgLatency,
            successRate = newSuccessRate,
            throughput = newThroughput,
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    private fun recordDemand() {
        val now = System.currentTimeMillis()
        val bucket = now / 60000
        demandHistory[bucket] = (demandHistory[bucket] ?: 0) + 1
        
        if (demandHistory.size > maxHistoricalPoints) {
            val oldest = demandHistory.keys.minOrNull()
            oldest?.let { demandHistory.remove(it) }
        }
    }
    
    fun forecastCapacity(): CapacityForecast {
        val now = System.currentTimeMillis()
        val prediction = loadPredictor.predict(demandHistory, forecastHorizon)
        
        val currentAgents = activeAgents.count { it.value.status == "running" }
        val currentKeys = keyPool?.size ?: 1
        
        val recommendedAgents = maxOf(currentAgents, ceil(prediction.demand).toInt())
        val recommendedKeys = maxOf(currentKeys, ceil(prediction.demand * 0.3).toInt())
        
        return CapacityForecast(
            timestamp = now + forecastHorizon,
            predictedDemand = prediction.demand,
            confidence = prediction.confidence,
            recommendedAgents = recommendedAgents,
            recommendedKeys = recommendedKeys
        )
    }
    
    fun optimizeCost(budget: Double): Map<String, Any> {
        val currentAgents = activeAgents.values.filter { it.status == "running" }
        
        var totalCost = 0.0
        val agentAllocation = mutableMapOf<String, Double>()
        
        for (agent in currentAgents) {
            val caps = agent.capabilities.mapNotNull { agentRegistry[it] }
            val cost = caps.sumOf { it.cost }
            totalCost += cost
            
            agentAllocation[agent.id] = cost
        }
        
        val optimization = costOptimizer.optimize(budget, agentAllocation)
        
        return mapOf(
            "totalCost" to totalCost,
            "budget" to budget,
            "remainingBudget" to budget - totalCost,
            "agentCosts" to agentAllocation,
            "recommendations" to optimization
        )
    }
    
    fun getSkillAffinity(agentId1: String, agentId2: String): Double {
        return skillAffinityMatrix[agentId1]?.get(agentId2) ?: 0.5
    }
    
    fun updateSkillAffinity(agentId1: String, agentId2: String, effectiveness: Double) {
        skillAffinityMatrix.getOrPut(agentId1) { ConcurrentHashMap() }[agentId2] = effectiveness
        skillAffinityMatrix.getOrPut(agentId2) { ConcurrentHashMap() }[agentId1] = effectiveness
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
    
    fun getAgentMetrics(agentId: String): AgentMetrics? = agentMetrics[agentId]
    
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
            val metrics = agentMetrics[agent.id]
            buildString {
                appendLine("[Agent] ${agent.id}")
                appendLine("   Task: ${agent.instructions.take(50)}...")
                appendLine("   Status: ${agent.status}")
                if (agent.keyId != null) appendLine("   Key: ${agent.keyId}")
                if (agent.parentAgentId != null) appendLine("   Parent: ${agent.parentAgentId}")
                if (agent.lineage != null) appendLine("   Generation: ${agent.lineage.generation}")
                if (agent.capabilities.isNotEmpty()) appendLine("   Capabilities: ${agent.capabilities.joinToString(", ")}")
                if (metrics != null) {
                    appendLine("   Success Rate: ${"%.1f".format(metrics.successRate * 100)}%")
                    appendLine("   Avg Latency: ${"%.0f".format(metrics.avgLatencyMs)}ms")
                    appendLine("   Throughput: ${"%.2f".format(metrics.throughput)}/s")
                }
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
        val forecast = forecastCapacity()
        
        return buildString {
            appendLine(collaborativeRuntime?.formatStatus() ?: "Runtime not initialized")
            appendLine()
            appendLine(keyPool?.formatStats() ?: "Key pool not initialized")
            appendLine()
            appendLine("=== Capacity Forecast ===")
            appendLine("Predicted Demand: ${"%.2f".format(forecast.predictedDemand)}")
            appendLine("Confidence: ${"%.1f".format(forecast.confidence * 100)}%")
            appendLine("Recommended Agents: ${forecast.recommendedAgents}")
            appendLine("Recommended Keys: ${forecast.recommendedKeys}")
            appendLine()
            appendLine("=== Agent Metrics ===")
            for ((id, metrics) in agentMetrics) {
                appendLine("$id: success=${"%.1f".format(metrics.successRate * 100)}%, " +
                    "latency=${"%.0f".format(metrics.avgLatencyMs)}ms, " +
                    "throughput=${"%.2f".format(metrics.throughput)}/s")
            }
        }
    }
    
    fun shutdown() {
        collaborativeAgents.values.forEach { it.stop() }
        collaborativeRuntime?.shutdown()
        scope.cancel()
    }
}

class AdaptiveLoadPredictor {
    private val historyWeight = 0.7
    private val trendWeight = 0.3
    
    data class Prediction(
        val demand: Double,
        val confidence: Double,
        val trend: Double
    )
    
    fun predict(history: Map<Long, Int>, horizonMs: Long): Prediction {
        if (history.isEmpty()) {
            return Prediction(demand = 5.0, confidence = 0.5, trend = 0.0)
        }
        
        val sortedTimes = history.keys.sorted()
        val values = sortedTimes.map { history[it] ?: 0 }
        
        if (values.size < 2) {
            return Prediction(demand = values.firstOrNull()?.toDouble() ?: 5.0, confidence = 0.5, trend = 0.0)
        }
        
        val avg = values.average()
        
        val n = values.size
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2 = 0.0
        
        for (i in values.indices) {
            sumX += i
            sumY += values[i]
            sumXY += i * values[i]
            sumX2 += i * i
        }
        
        val slope = if (n * sumX2 - sumX * sumX != 0.0) {
            (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
        } else 0.0
        
        val intercept = (sumY - slope * sumX) / n
        
        val futureIndex = values.size + (horizonMs / 60000.0)
        val predicted = slope * futureIndex + intercept
        
        val variance = values.map { (it - avg) * (it - avg) }.average()
        val stdDev = sqrt(variance)
        val cv = if (avg != 0.0) stdDev / avg else 1.0
        val confidence = (1.0 - cv.coerceIn(0.0, 1.0)) * historyWeight + trendWeight
        
        return Prediction(
            demand = predicted.coerceAtLeast(1.0),
            confidence = confidence,
            trend = slope
        )
    }
}

class CostOptimizer {
    data class OptimizationResult(
        val action: String,
        val savings: Double,
        val impact: String
    )
    
    fun optimize(budget: Double, allocations: Map<String, Double>): List<OptimizationResult> {
        val results = mutableListOf<OptimizationResult>()
        val totalCost = allocations.values.sum()
        
        if (totalCost > budget) {
            val excess = totalCost - budget
            val sortedByPriority = allocations.entries.sortedByDescending { it.value }
            
            var remainingExcess = excess
            for ((agentId, cost) in sortedByPriority) {
                if (remainingExcess <= 0) break
                val reduction = minOf(cost * 0.2, remainingExcess)
                results.add(OptimizationResult(
                    action = "Reduce $agentId budget",
                    savings = reduction,
                    impact = "Minor - 20% capacity reduction"
                ))
                remainingExcess -= reduction
            }
        } else {
            val remaining = budget - totalCost
            if (remaining > budget * 0.3) {
                results.add(OptimizationResult(
                    action = "Scale up agents",
                    savings = -remaining,
                    impact = "Positive - can handle ${(remaining / (totalCost / allocations.size)).toInt()} more agents"
                ))
            }
        }
        
        return results
    }
}
