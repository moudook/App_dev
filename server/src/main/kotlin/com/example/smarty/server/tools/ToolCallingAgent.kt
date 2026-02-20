package com.example.smarty.server.tools

import com.example.smarty.server.llm.LlmProvider
import com.example.smarty.server.llm.ToolDefinition
import com.example.smarty.server.llm.ToolCall
import com.example.smarty.server.llm.ToolResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class ToolCallingAgent(
    private val llmProvider: LlmProvider,
    private val toolRegistry: ToolRegistry
) {
    private val logger = LoggerFactory.getLogger(ToolCallingAgent::class.java)
    private val executionMutex = Mutex()
    
    private val executionHistory = ConcurrentLinkedQueue<ToolExecutionRecord>()
    private val activeExecutions = ConcurrentHashMap<String, ToolExecution>()
    
    data class ToolExecution(
        val id: String = UUID.randomUUID().toString(),
        val toolName: String,
        val parameters: Map<String, Any>,
        val startedAt: Long = System.currentTimeMillis(),
        var completedAt: Long? = null,
        var result: Any? = null,
        var error: String? = null
    )
    
    data class ToolExecutionRecord(
        val id: String,
        val toolName: String,
        val parameters: Map<String, Any>,
        val startedAt: Long,
        val completedAt: Long,
        val success: Boolean,
        val durationMs: Long,
        val result: String?,
        val error: String?
    )
    
    suspend fun executeTool(
        toolName: String,
        parameters: Map<String, Any>,
        callerContext: String? = null
    ): ToolCallResponse {
        return executionMutex.withLock {
            val executionId = UUID.randomUUID().toString()
            val execution = ToolExecution(
                id = executionId,
                toolName = toolName,
                parameters = parameters
            )
            
            activeExecutions[executionId] = execution
            
            logger.info("[$executionId] Executing tool: $toolName")
            
            try {
                val tool = toolRegistry.getTool(toolName)
                if (tool == null) {
                    val error = "Tool not found: $toolName"
                    execution.error = error
                    activeExecutions.remove(executionId)
                    
                    return@withLock ToolCallResponse(
                        success = false,
                        toolName = toolName,
                        result = null,
                        error = error,
                        executionId = executionId
                    )
                }
                
                val result = toolRegistry.execute(toolName, parameters)
                
                execution.completedAt = System.currentTimeMillis()
                execution.result = result
                
                val record = ToolExecutionRecord(
                    id = executionId,
                    toolName = toolName,
                    parameters = parameters,
                    startedAt = execution.startedAt,
                    completedAt = execution.completedAt!!,
                    success = true,
                    durationMs = execution.completedAt!! - execution.startedAt,
                    result = result?.toString()?.take(1000),
                    error = null
                )
                executionHistory.offer(record)
                
                activeExecutions.remove(executionId)
                
                logger.info("[$executionId] Tool executed successfully in ${record.durationMs}ms")
                
                ToolCallResponse(
                    success = true,
                    toolName = toolName,
                    result = result,
                    error = null,
                    executionId = executionId
                )
                
            } catch (e: Exception) {
                execution.completedAt = System.currentTimeMillis()
                execution.error = e.message
                
                val record = ToolExecutionRecord(
                    id = executionId,
                    toolName = toolName,
                    parameters = parameters,
                    startedAt = execution.startedAt,
                    completedAt = execution.completedAt!!,
                    success = false,
                    durationMs = execution.completedAt!! - execution.startedAt,
                    result = null,
                    error = e.message
                )
                executionHistory.offer(record)
                
                activeExecutions.remove(executionId)
                
                logger.error("[$executionId] Tool execution failed: ${e.message}")
                
                ToolCallResponse(
                    success = false,
                    toolName = toolName,
                    result = null,
                    error = e.message ?: "Unknown error",
                    executionId = executionId
                )
            }
        }
    }
    
    suspend fun executeMultiple(
        requests: List<ToolExecutionRequest>
    ): List<ToolCallResponse> {
        return requests.map { request ->
            executeTool(request.toolName, request.parameters, request.context)
        }
    }
    
    fun getAvailableTools(): List<String> {
        return toolRegistry.listTools()
    }
    
    fun getToolSchema(toolName: String): ToolDefinition? {
        return toolRegistry.getToolDefinition(toolName)
    }
    
    fun getExecutionHistory(limit: Int = 50): List<ToolExecutionRecord> {
        return executionHistory.toList().takeLast(limit)
    }
    
    fun getActiveExecutions(): List<ToolExecution> {
        return activeExecutions.values.toList()
    }
    
    fun getStatistics(): String {
        val recent = executionHistory.toList().takeLast(100)
        val successCount = recent.count { it.success }
        val totalCount = recent.size
        
        val avgDuration = if (recent.isNotEmpty()) {
            recent.map { it.durationMs }.average()
        } else 0.0
        
        val toolUsage = recent.groupBy { it.toolName }
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }
        
        return buildString {
            appendLine("[ToolCallingAgent Statistics]")
            appendLine("=".repeat(50))
            appendLine()
            appendLine("Recent Executions (last 100):")
            appendLine("  Success: $successCount / $totalCount (${if (totalCount > 0) successCount * 100 / totalCount else 0}%)")
            appendLine("  Average Duration: ${"%.2f".format(avgDuration)}ms")
            appendLine()
            appendLine("Tool Usage:")
            toolUsage.take(10).forEach { (tool, count) ->
                appendLine("  $tool: $count calls")
            }
            appendLine()
            appendLine("Active Executions: ${activeExecutions.size}")
        }
    }
    
    fun clearHistory() {
        executionHistory.clear()
    }
}

data class ToolCallResponse(
    val success: Boolean,
    val toolName: String,
    val result: Any?,
    val error: String?,
    val executionId: String
)

data class ToolExecutionRequest(
    val toolName: String,
    val parameters: Map<String, Any>,
    val context: String? = null
)

class ToolRegistry(
    private val toolImplementations: Map<String, ToolImplementation>
) {
    private val logger = LoggerFactory.getLogger(ToolRegistry::class.java)
    
    private val toolDefinitions = ConcurrentHashMap<String, ToolDefinition>()
    private val executionCount = ConcurrentHashMap<String, Int>()
    private val errorCount = ConcurrentHashMap<String, Int>()
    
    interface ToolImplementation {
        suspend fun execute(params: Map<String, Any>): Any?
        fun getDefinition(): ToolDefinition
    }
    
    fun registerTool(impl: ToolImplementation) {
        val definition = impl.getDefinition()
        toolDefinitions[definition.name] = definition
        logger.info("Registered tool: ${definition.name}")
    }
    
    fun getTool(name: String): ToolDefinition? {
        return toolDefinitions[name]
    }
    
    fun getToolDefinition(name: String): ToolDefinition? {
        return toolDefinitions[name]
    }
    
    fun listTools(): List<String> {
        return toolDefinitions.keys.toList()
    }
    
    fun listToolDefinitions(): List<ToolDefinition> {
        return toolDefinitions.values.toList()
    }
    
    suspend fun execute(name: String, params: Map<String, Any>): Any? {
        val impl = toolImplementations[name]
        
        if (impl == null) {
            errorCount[name] = (errorCount[name] ?: 0) + 1
            throw IllegalArgumentException("Tool not found: $name")
        }
        
        return try {
            val result = impl.execute(params)
            executionCount[name] = (executionCount[name] ?: 0) + 1
            result
        } catch (e: Exception) {
            errorCount[name] = (errorCount[name] ?: 0) + 1
            throw e
        }
    }
    
    fun getToolStats(): Map<String, ToolStats> {
        return toolDefinitions.keys.associateWith { name ->
            ToolStats(
                executions = executionCount[name] ?: 0,
                errors = errorCount[name] ?: 0
            )
        }
    }
    
    data class ToolStats(
        val executions: Int,
        val errors: Int
    )
}

class ToolCallingAgentFactory(
    private val llmProvider: LlmProvider
) {
    private val logger = LoggerFactory.getLogger(ToolCallingAgentFactory::class.java)
    
    private var instance: ToolCallingAgent? = null
    private var registry: ToolRegistry? = null
    
    fun createAgent(
        toolImplementations: Map<String, ToolRegistry.ToolImplementation>
    ): ToolCallingAgent {
        registry = ToolRegistry(toolImplementations)
        instance = ToolCallingAgent(llmProvider, registry!!)
        
        logger.info("ToolCallingAgent created with ${toolImplementations.size} tools")
        return instance!!
    }
    
    fun getInstance(): ToolCallingAgent? = instance
    
    fun getRegistry(): ToolRegistry? = registry
}

class MainAgent(
    private val toolCallingAgent: ToolCallingAgent,
    private val llmProvider: LlmProvider
) {
    private val logger = LoggerFactory.getLogger(MainAgent::class.java)
    
    private val conversationHistory = mutableListOf<AgentMessage>()
    private val systemPrompt = buildSystemPrompt()
    
    data class AgentMessage(
        val role: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private fun buildSystemPrompt(): String {
        return buildString {
            appendLine("You are an advanced AI assistant with access to a Tool Calling Agent.")
            appendLine()
            appendLine("## Your Role")
            appendLine("You focus on UNDERSTANDING, REASONING, and THINKING.")
            appendLine("You do NOT execute tools directly. Instead, you delegate tool execution to the Tool Calling Agent.")
            appendLine()
            appendLine("## How to Use Tools")
            appendLine("When you need to execute a tool, respond with:")
            appendLine("""
                TOOL_CALL: <tool_name>
                PARAMETERS: <json_parameters>
                CONTEXT: <optional_context_about_why_you_need_this>
            """.trimIndent())
            appendLine()
            appendLine("The Tool Calling Agent will execute the tool and return the result to you.")
            appendLine()
            appendLine("## Available Actions")
            appendLine("- If you need information: Use find_note, web_search, or other retrieval tools")
            appendLine("- If you need to save something: Use save_note or remember_fact")
            appendLine("- If you need to perform an action: Use the appropriate tool via Tool Calling Agent")
            appendLine("- If you're unsure what tool to use: Ask the Tool Calling Agent for available tools")
            appendLine()
            appendLine("## Guidelines")
            appendLine("1. Focus on reasoning and understanding the user's request")
            appendLine("2. Delegate tool execution - don't try to execute tools yourself")
            appendLine("3. Synthesize results returned by the Tool Calling Agent")
            appendLine("4. Provide clear, helpful responses to the user")
            appendLine()
        }
    }
    
    suspend fun processMessage(userMessage: String): String {
        conversationHistory.add(AgentMessage("user", userMessage))
        
        val messages = mutableListOf(
            AgentMessage("system", systemPrompt)
        )
(conversationHistory.takeLast(10        messages.addAll))
        
        val response = llmProvider.chat(messages.map { it.toLlmMessage() })
        
        val toolCallResult = parseAndExecuteToolCall(response)
        
        if (toolCallResult != null) {
            conversationHistory.add(AgentMessage("assistant", response))
            conversationHistory.add(AgentMessage("tool_result", toolCallResult))
            
            val finalResponse = llmProvider.chat(
                messages.map { it.toLlmMessage() } + 
                listOf(AgentMessage("assistant", response).toLlmMessage()) +
                listOf(AgentMessage("tool", toolCallResult).toLlmMessage())
            )
            
            conversationHistory.add(AgentMessage("assistant", finalResponse))
            return finalResponse
        }
        
        conversationHistory.add(AgentMessage("assistant", response))
        return response
    }
    
    private suspend fun parseAndExecuteToolCall(response: String): String? {
        if (!response.contains("TOOL_CALL:")) return null
        
        val toolNameMatch = Regex("TOOL_CALL:\\s*(\\w+)").find(response)
        val paramsMatch = Regex("PARAMETERS:\\s*(\\{[^}]+\\})").find(response)
        val contextMatch = Regex("CONTEXT:\\s*(.+)").find(response)
        
        if (toolNameMatch == null) return null
        
        val toolName = toolNameMatch.groupValues[1]
        val params = parseParameters(paramsMatch?.groupValues?.get(1) ?: "{}")
        val context = contextMatch?.groupValues?.get(1)
        
        val result = toolCallingAgent.executeTool(toolName, params, context)
        
        return if (result.success) {
            "Tool '$toolName' executed successfully: ${result.result}"
        } else {
            "Tool '$toolName' failed: ${result.error}"
        }
    }
    
    private fun parseParameters(jsonStr: String): Map<String, Any> {
        return try {
            val cleanStr = jsonStr.replace("'", "\"")
            kotlinx.serialization.json.Json.parseToJsonElement(cleanStr)
                .jsonObject
                .mapValues { it.value.toKotlinValue() }
        } catch (e: Exception) {
            emptyMap()
        }
    }
    
    private fun AgentMessage.toLlmMessage() = com.example.smarty.server.llm.LlmMessage(
        role = role,
        content = content
    )
    
    private fun kotlinx.serialization.json.JsonElement.toKotlinValue(): Any {
        return when (this) {
            is kotlinx.serialization.json.JsonPrimitive -> {
                if (isString) content else content.toString().toDoubleOrNull() ?: content
            }
            is kotlinx.serialization.json.JsonObject -> map.mapValues { it.value.toKotlinValue() }
            is kotlinx.serialization.json.JsonArray -> map { it.toKotlinValue() }
            else -> ""
        }
    }
    
    fun getConversationHistory(): List<AgentMessage> {
        return conversationHistory.toList()
    }
    
    fun clearHistory() {
        conversationHistory.clear()
    }
}

class HierarchicalAgentSystem(
    private val toolCallingAgent: ToolCallingAgent,
    private val mainAgent: MainAgent
) {
    private val logger = LoggerFactory.getLogger(HierarchicalAgentSystem::class.java)
    
    private val subAgents = ConcurrentHashMap<String, SubAgent>()
    private val agentHierarchy = AgentHierarchy()
    
    data class SubAgent(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val role: String,
        val capabilities: List<String>,
        val state: AgentState = AgentState.IDLE,
        var parentId: String? = null,
        var currentTask: String? = null
    )
    
    enum class AgentState {
        IDLE, THINKING, EXECUTING_TOOL, WAITING, COMPLETED, FAILED
    }
    
    data class AgentHierarchy(
        val rootId: String = "main",
        val children: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()
    )
    
    fun createSubAgent(
        name: String,
        role: String,
        capabilities: List<String>,
        parentId: String? = null
    ): SubAgent {
        val agent = SubAgent(
            name = name,
            role = role,
            capabilities = capabilities,
            parentId = parentId
        )
        
        subAgents[agent.id] = agent
        
        if (parentId != null) {
            agentHierarchy.children.getOrPut(parentId) { mutableSetOf() }.add(agent.id)
        }
        
        logger.info("Created sub-agent: ${agent.name} (id: ${agent.id})")
        return agent
    }
    
    suspend fun delegateTask(
        agentId: String,
        task: String,
        context: Map<String, Any>? = null
    ): TaskResult {
        val agent = subAgents[agentId] ?: return TaskResult(
            success = false,
            result = null,
            error = "Agent not found: $agentId"
        )
        
        agent.state = AgentState.THINKING
        agent.currentTask = task
        
        val requiredCapabilities = determineRequiredCapabilities(task)
        
        if (!requiredCapabilities.all { agent.capabilities.contains(it) }) {
            val parentAgent = agent.parentId?.let { subAgents[it] }
            
            if (parentAgent != null) {
                agent.state = AgentState.WAITING
                
                val parentResult = delegateTask(parentAgent.id, task, context)
                
                agent.state = AgentState.COMPLETED
                return parentResult
            }
        }
        
        agent.state = AgentState.EXECUTING_TOOL
        
        val toolName = selectToolForTask(task)
        val params = buildParamsForTask(task, context)
        
        val result = toolCallingAgent.executeTool(toolName, params, "Task: $task")
        
        agent.state = if (result.success) AgentState.COMPLETED else AgentState.FAILED
        agent.currentTask = null
        
        return TaskResult(
            success = result.success,
            result = result.result,
            error = result.error
        )
    }
    
    private fun determineRequiredCapabilities(task: String): List<String> {
        val taskLower = task.lowercase()
        val capabilities = mutableListOf<String>()
        
        when {
            taskLower.contains("search") || taskLower.contains("find") -> 
                capabilities.add("search")
            taskLower.contains("save") || taskLower.contains("write") -> 
                capabilities.add("storage")
            taskLower.contains("calculate") || taskLower.contains("compute") -> 
                capabilities.add("computation")
            taskLower.contains("analyze") || taskLower.contains("research") -> 
                capabilities.add("analysis")
            taskLower.contains("create") || taskLower.contains("generate") -> 
                capabilities.add("creation")
        }
        
        return capabilities
    }
    
    private fun selectToolForTask(task: String): String {
        val taskLower = task.lowercase()
        
        return when {
            taskLower.contains("search") -> "web_search"
            taskLower.contains("note") -> "save_note"
            taskLower.contains("remember") -> "remember_fact"
            taskLower.contains("event") || taskLower.contains("calendar") -> "add_event"
            taskLower.contains("timer") || taskLower.contains("remind") -> "set_timer"
            else -> "analyze_text"
        }
    }
    
    private fun buildParamsForTask(task: String, context: Map<String, Any>?): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        
        context?.forEach { (key, value) ->
            params[key] = value
        }
        
        if (task.contains("search", ignoreCase = true)) {
            params["query"] = extractQuery(task)
        }
        
        return params
    }
    
    private fun extractQuery(task: String): String {
        val removeWords = listOf("search", "find", "look", "up", "for", "the", "a", "an")
        return task.split(" ")
            .filter { !removeWords.contains(it.lowercase()) }
            .joinToString(" ")
            .trim()
    }
    
    fun getAgentStatus(agentId: String): String {
        val agent = subAgents[agentId] ?: return "Agent not found"
        
        return buildString {
            appendLine("Agent: ${agent.name}")
            appendLine("  Role: ${agent.role}")
            appendLine("  State: ${agent.state}")
            appendLine("  Capabilities: ${agent.capabilities.joinToString(", ")}")
            if (agent.currentTask != null) {
                appendLine("  Current Task: ${agent.currentTask}")
            }
            if (agent.parentId != null) {
                appendLine("  Parent: ${subAgents[agent.parentId]?.name ?: "Unknown"}")
            }
        }
    }
    
    fun getSystemStatus(): String {
        return buildString {
            appendLine("[Hierarchical Agent System Status]")
            appendLine("=".repeat(50))
            appendLine()
            appendLine("Main Agent: Active")
            appendLine("Sub Agents: ${subAgents.size}")
            appendLine()
            
            val byState = subAgents.values.groupBy { it.state }
            byState.forEach { (state, agents) ->
                appendLine("${state.name}: ${agents.size}")
            }
            
            appendLine()
            appendLine("[Agents]")
            subAgents.values.forEach { agent ->
                appendLine("  - ${agent.name} (${agent.role}): ${agent.state}")
            }
        }
    }
    
    fun terminateAgent(agentId: String): Boolean {
        val agent = subAgents.remove(agentId) ?: return false
        
        agentHierarchy.children[agent.parentId]?.remove(agentId)
        
        logger.info("Terminated agent: ${agent.name}")
        return true
    }
}

data class TaskResult(
    val success: Boolean,
    val result: Any?,
    val error: String?
)
