package com.example.smarty.server.tools

import com.example.smarty.server.llm.LlmProvider
import com.example.smarty.server.llm.ToolDefinition
import com.example.smarty.server.llm.ToolParameters
import com.example.smarty.server.llm.ToolProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class ToolExecutorAgent(
    private val llmProvider: LlmProvider? = null
) {
    private val logger = LoggerFactory.getLogger(ToolExecutorAgent::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executionMutex = Mutex()
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    
    private val toolImplementations = ConcurrentHashMap<String, ToolHandler>()
    private val router = ToolRouter()
    private val paramExtractor = ParameterExtractor()
    
    private val executionHistory = ConcurrentLinkedQueue<ExecutionEntry>()
    private val activeExecutions = ConcurrentHashMap<String, ActiveExecution>()
    private val pendingCallbacks = ConcurrentHashMap<String, CallbackInfo>()
    
    interface ToolHandler {
        suspend fun handle(params: Map<String, Any>): ToolResponse
        fun getToolDefinition(): ToolDefinition
    }
    
    data class ToolResponse(
        val success: Boolean,
        val result: String?,
        val error: String?,
        val metadata: Map<String, Any> = emptyMap()
    )
    
    data class ExecutionEntry(
        val id: String,
        val toolName: String,
        val userRequest: String,
        val params: Map<String, Any>,
        val startedAt: Long,
        val completedAt: Long?,
        val success: Boolean,
        val durationMs: Long?,
        val result: String?,
        val error: String?
    )
    
    data class ActiveExecution(
        val id: String,
        val toolName: String,
        val startTime: Long,
        var params: Map<String, Any>,
        var result: Any? = null,
        var error: String? = null
    )
    
    data class CallbackInfo(
        val callbackId: String,
        val callerId: String,
        val toolName: String,
        val createdAt: Long,
        var result: Any? = null,
        var completed: Boolean = false
    )
    
    init {
        startHealthCheck()
    }
    
    private fun startHealthCheck() {
        executor.scheduleAtFixedRate({
            val staleExecutions = activeExecutions.filter { 
                System.currentTimeMillis() - it.value.startTime > 30000 
            }
            staleExecutions.forEach { (id, exec) ->
                exec.error = "Timeout - execution exceeded 30 seconds"
                logger.warn("Execution $id timed out")
            }
        }, 30, 30, TimeUnit.SECONDS)
    }
    
    fun registerTool(handler: ToolHandler) {
        val definition = handler.getToolDefinition()
        toolImplementations[definition.name] = handler
        router.registerTool(definition)
        logger.info("Registered tool: ${definition.name}")
    }
    
    fun registerTool(
        name: String,
        description: String,
        parameters: Map<String, ToolProperty>,
        required: List<String>,
        handler: suspend (Map<String, Any>) -> ToolResponse
    ) {
        val definition = ToolDefinition(
            name = name,
            description = description,
            parameters = ToolParameters(properties = parameters, required = required)
        )
        
        val toolHandler = object : ToolHandler {
            override suspend fun handle(params: Map<String, Any>): ToolResponse = handler(params)
            override fun getToolDefinition(): ToolDefinition = definition
        }
        
        toolImplementations[name] = toolHandler
        router.registerTool(definition)
    }
    
    suspend fun execute(request: ToolRequest): ExecutionResult {
        return executionMutex.withLock {
            val executionId = UUID.randomUUID().toString()
            
            val routingDecision = router.route(request.userRequest)
            
            if (routingDecision.toolName == null || routingDecision.confidence < 0.2) {
                logger.warn("[$executionId] No tool routed for request: ${request.userRequest}")
                return@withLock ExecutionResult(
                    success = false,
                    toolName = null,
                    result = null,
                    error = "Could not find appropriate tool for: ${request.userRequest}",
                    executionId = executionId,
                    confidence = 0.0,
                    routingReasoning = routingDecision.reasoning
                )
            }
            
            val toolName = routingDecision.toolName
            val params = if (request.parameters.isNotEmpty()) {
                request.parameters
            } else {
                paramExtractor.extract(request.userRequest, toolName, router.getDefinition(toolName))
            }
            
            val activeExec = ActiveExecution(
                id = executionId,
                toolName = toolName,
                startTime = System.currentTimeMillis(),
                params = params
            )
            activeExecutions[executionId] = activeExec
            
            logger.info("[$executionId] Executing tool: $toolName")
            
            try {
                val handler = toolImplementations[toolName]
                if (handler == null) {
                    val error = "Tool handler not found: $toolName"
                    activeExec.error = error
                    activeExecutions.remove(executionId)
                    
                    return@withLock ExecutionResult(
                        success = false,
                        toolName = toolName,
                        result = null,
                        error = error,
                        executionId = executionId,
                        confidence = routingDecision.confidence,
                        routingReasoning = routingDecision.reasoning
                    )
                }
                
                val response = handler.handle(params)
                
                val duration = System.currentTimeMillis() - activeExec.startTime
                activeExec.result = response.result
                activeExec.error = response.error
                
                val entry = ExecutionEntry(
                    id = executionId,
                    toolName = toolName,
                    userRequest = request.userRequest,
                    params = params,
                    startedAt = activeExec.startTime,
                    completedAt = System.currentTimeMillis(),
                    success = response.success,
                    durationMs = duration,
                    result = response.result,
                    error = response.error
                )
                executionHistory.offer(entry)
                activeExecutions.remove(executionId)
                
                if (request.callbackId != null) {
                    completeCallback(request.callbackId, response)
                }
                
                logger.info("[$executionId] Completed $toolName in ${duration}ms")
                
                ExecutionResult(
                    success = response.success,
                    toolName = toolName,
                    result = response.result,
                    error = response.error,
                    executionId = executionId,
                    confidence = routingDecision.confidence,
                    routingReasoning = routingDecision.reasoning,
                    durationMs = duration
                )
                
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - activeExec.startTime
                activeExec.error = e.message
                
                val entry = ExecutionEntry(
                    id = executionId,
                    toolName = toolName,
                    userRequest = request.userRequest,
                    params = params,
                    startedAt = activeExec.startTime,
                    completedAt = System.currentTimeMillis(),
                    success = false,
                    durationMs = duration,
                    result = null,
                    error = e.message
                )
                executionHistory.offer(entry)
                activeExecutions.remove(executionId)
                
                logger.error("[$executionId] Error executing $toolName: ${e.message}")
                
                ExecutionResult(
                    success = false,
                    toolName = toolName,
                    result = null,
                    error = e.message ?: "Unknown error",
                    executionId = executionId,
                    confidence = routingDecision.confidence,
                    routingReasoning = routingDecision.reasoning,
                    durationMs = duration
                )
            }
        }
    }
    
    suspend fun executeMultiple(requests: List<ToolRequest>): List<ExecutionResult> {
        return requests.map { request ->
            scope.async { execute(request) }
        }.awaitAll()
    }
    
    suspend fun executeParallel(requests: List<ToolRequest>): List<ExecutionResult> {
        return scope.async {
            requests.map { request ->
                async { execute(request) }
            }.awaitAll()
        }.await()
    }
    
    private fun completeCallback(callbackId: String, response: ToolResponse) {
        val callback = pendingCallbacks[callbackId]
        if (callback != null) {
            callback.result = response.result
            callback.completed = true
        }
    }
    
    fun getToolDefinitions(): List<ToolDefinition> {
        return router.getAllDefinitions()
    }
    
    fun getAvailableTools(): List<String> {
        return toolImplementations.keys.toList()
    }
    
    fun getActiveCount(): Int = activeExecutions.size
    
    fun getHistory(limit: Int = 50): List<ExecutionEntry> {
        return executionHistory.toList().takeLast(limit)
    }
    
    fun getStatistics(): Statistics {
        val recent = executionHistory.toList().takeLast(100)
        val successCount = recent.count { it.success }
        val avgDuration = recent.filter { it.durationMs != null }
            .mapNotNull { it.durationMs }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0
        
        val toolUsage = recent.groupBy { it.toolName }
            .mapValues { it.value.size }
        
        return Statistics(
            totalExecutions = executionHistory.size.toLong(),
            recentSuccessRate = if (recent.isNotEmpty()) successCount.toDouble() / recent.size else 0.0,
            averageDurationMs = avgDuration,
            activeExecutions = activeExecutions.size,
            toolUsage = toolUsage
        )
    }
    
    fun shutdown() {
        executor.shutdown()
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

data class ToolRequest(
    val userRequest: String,
    val parameters: Map<String, Any> = emptyMap(),
    val callbackId: String? = null,
    val context: String? = null,
    val priority: Int = 0
)

data class ExecutionResult(
    val success: Boolean,
    val toolName: String?,
    val result: Any?,
    val error: String?,
    val executionId: String,
    val confidence: Double,
    val routingReasoning: String,
    val durationMs: Long? = null
)

data class Statistics(
    val totalExecutions: Long,
    val recentSuccessRate: Double,
    val averageDurationMs: Double,
    val activeExecutions: Int,
    val toolUsage: Map<String, Int>
)

class ToolRouter {
    private val tools = ConcurrentHashMap<String, ToolDefinition>()
    private val keywords = ConcurrentHashMap<String, MutableList<Pair<String, Double>>>()
    
    fun registerTool(definition: ToolDefinition) {
        tools[definition.name] = definition
        
        val toolKeywords = extractKeywords(definition)
        for ((keyword, weight) in toolKeywords) {
            keywords.getOrPut(keyword) { mutableListOf() }.add(definition.name to weight)
        }
    }
    
    private fun extractKeywords(definition: ToolDefinition): List<Pair<String, Double>> {
        val keywords = mutableListOf<Pair<String, Double>>()
        
        keywords.add(definition.name.lowercase() to 1.0)
        
        val desc = definition.description.lowercase()
        val words = desc.split(Regex("\\W+")).filter { it.length > 3 }
        
        for (word in words) {
            keywords.add(word to 0.3)
        }
        
        val actionWords = listOf(
            "search" to 0.8, "save" to 0.8, "find" to 0.8, "get" to 0.6,
            "create" to 0.7, "delete" to 0.7, "update" to 0.7, "edit" to 0.7,
            "list" to 0.6, "show" to 0.6, "display" to 0.6,
            "calculate" to 0.8, "analyze" to 0.8, "compare" to 0.7
        )
        
        for ((action, weight) in actionWords) {
            if (desc.contains(action)) {
                keywords.add(action to weight)
            }
        }
        
        return keywords.distinctBy { it.first }
    }
    
    fun route(request: String): RoutingDecision {
        val requestLower = request.lowercase()
        val requestWords = requestLower.split(Regex("\\W+")).filter { it.length > 2 }
        
        val scores = mutableMapOf<String, Double>()
        
        for ((keyword, toolWeights) in keywords) {
            for (word in requestWords) {
                if (keyword.contains(word) || word.contains(keyword)) {
                    for ((toolName, keywordWeight) in toolWeights) {
                        scores[toolName] = (scores[toolName] ?: 0.0) + keywordWeight
                    }
                }
            }
        }
        
        for (toolName in tools.keys) {
            val toolDesc = tools[toolName]?.description?.lowercase() ?: ""
            
            for (word in requestWords) {
                if (toolDesc.contains(word)) {
                    scores[toolName] = (scores[toolName] ?: 0.0) + 0.5
                }
            }
        }
        
        val sorted = scores.entries.sortedByDescending { it.value }
        val selected = sorted.firstOrNull()
        
        val maxPossibleScore = keywords.values.flatten().sumOf { it.second }
        val confidence = if (selected != null && maxPossibleScore > 0) {
            (selected.value / maxPossibleScore).coerceIn(0.0, 1.0)
        } else 0.0
        
        return RoutingDecision(
            toolName = selected?.key,
            confidence = confidence,
            alternativeTools = sorted.drop(1).take(3).map { it.key },
            reasoning = generateReasoning(request, selected, sorted)
        )
    }
    
    private fun generateReasoning(
        request: String,
        selected: Map.Entry<String, Double>?,
        all: List<Map.Entry<String, Double>>
    ): String {
        return if (selected != null) {
            "Routed '${request.take(50)}...' to '${selected.key}' (score: ${"%.2f".format(selected.value)})"
        } else {
            "No tool matched for '${request.take(50)}...'"
        }
    }
    
    fun getDefinition(name: String): ToolDefinition? = tools[name]
    
    fun getAllDefinitions(): List<ToolDefinition> = tools.values.toList()
    
    fun listTools(): List<String> = tools.keys.toList()
}

data class RoutingDecision(
    val toolName: String?,
    val confidence: Double,
    val alternativeTools: List<String>,
    val reasoning: String
)

class ParameterExtractor {
    
    fun extract(request: String, toolName: String, definition: ToolDefinition?): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        val requestLower = request.lowercase()
        
        val paramSpec = definition?.parameters
        
        when {
            toolName.contains("note", ignoreCase = true) -> {
                extractNoteParams(request, requestLower, params)
            }
            toolName.contains("search", ignoreCase = true) -> {
                params["query"] = extractQuery(request)
            }
            toolName.contains("event", ignoreCase = true) || toolName.contains("calendar", ignoreCase = true) -> {
                extractEventParams(request, requestLower, params)
            }
            toolName.contains("timer", ignoreCase = true) || toolName.contains("remind", ignoreCase = true) -> {
                extractTimerParams(request, requestLower, params)
            }
            toolName.contains("fact", ignoreCase = true) || toolName.contains("remember", ignoreCase = true) -> {
                extractFactParams(request, requestLower, params)
            }
            else -> {
                extractGenericParams(request, params, paramSpec)
            }
        }
        
        return params
    }
    
    private fun extractNoteParams(request: String, requestLower: String, params: MutableMap<String, Any>) {
        val titlePatterns = listOf(
            Regex("""title[:\s]+["']?([^"']+)["']?"""),
            Regex("""save\s+(?:a\s+)?note\s+(?:called\s+)?["']?([^"']+)["']?"""),
            Regex("""note\s+["']?([^"']+)["']?""")
        )
        
        for (pattern in titlePatterns) {
            val match = pattern.find(request)
            if (match != null) {
                params["title"] = match.groupValues[1].trim()
                break
            }
        }
        
        val contentPatterns = listOf(
            Regex("""content[:\s]+["']?([^"']+)["']?"""),
            Regex("""with\s+(?:the\s+)?(?:content\s+)?["']?([^"']+)["']?"""),
            Regex("""saying\s+["']?([^"']+)["']?"""),
            Regex("""that\s+(?:says?\s+)?["']?([^"']+)["']?\s*(?:and|$)"""),
            Regex(""":\s*["']?([^"']+)["']?\s*(?:and|$)""")
        )
        
        for (pattern in contentPatterns) {
            val match = pattern.find(request)
            if (match != null && !params.containsKey("content")) {
                params["content"] = match.groupValues[1].trim()
                break
            }
        }
        
        if (!params.containsKey("title") && !params.containsKey("content")) {
            val afterNote = requestLower.substringAfter("note").substringAfter("save").trim()
            if (afterNote.isNotEmpty()) {
                params["content"] = afterNote.take(200)
                params["title"] = "Note ${System.currentTimeMillis() % 10000}"
            }
        }
        
        if (requestLower.contains("work") || requestLower.contains("job")) {
            params["category"] = "work"
        } else if (requestLower.contains("personal") || requestLower.contains("home")) {
            params["category"] = "personal"
        } else if (requestLower.contains("idea")) {
            params["category"] = "ideas"
        }
    }
    
    private fun extractQuery(request: String): String {
        val patterns = listOf(
            Regex("""search\s+(?:for\s+)?["']?([^"']+)["']?""", RegexOption.IGNORE_CASE),
            Regex("""find\s+(?:information\s+)?(?:about\s+)?["']?([^"']+)["']?""", RegexOption.IGNORE_CASE),
            Regex("""(?:look|get)\s+up\s+["']?([^"']+)["']?""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(request)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        
        val afterSearch = request.substringAfter("search", request.substringAfter("find"))
            .trim()
        return afterSearch.ifEmpty { request }
    }
    
    private fun extractEventParams(request: String, requestLower: String, params: MutableMap<String, Any>) {
        val titlePatterns = listOf(
            Regex("""event\s+["']?([^"']+)["']?"""),
            Regex("""(?:add|create)\s+(?:an?\s+)?event\s+["']?([^"']+)["']?""")
        )
        
        for (pattern in titlePatterns) {
            val match = pattern.find(request)
            if (match != null) {
                params["title"] = match.groupValues[1].trim()
                break
            }
        }
        
        val timePatterns = listOf(
            Regex("""(?:at|on)\s+(["']?[^\s'"](?:(?!\s(?:and|for|with).).)*?["']?(?:\s+(?:am|pm))?)""", RegexOption.IGNORE_CASE),
            Regex("""when\s+["']?([^"']+)["']?""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in timePatterns) {
            val match = pattern.find(request)
            if (match != null) {
                params["when"] = match.groupValues[1].trim()
                break
            }
        }
        
        if (requestLower.contains("hour") || requestLower.contains("minute")) {
            val durationMatch = Regex("""(\d+)\s*(hour|hr|h|minute|min|m)""", RegexOption.IGNORE_CASE).find(request)
            if (durationMatch != null) {
                params["duration"] = "${durationMatch.groupValues[1]} ${durationMatch.groupValues[2]}"
            }
        }
    }
    
    private fun extractTimerParams(request: String, requestLower: String, params: MutableMap<String, Any>) {
        val durationMatch = Regex("""(\d+)\s*(hour|hr|h|minute|min|m|second|sec|s)""", RegexOption.IGNORE_CASE).find(request)
        if (durationMatch != null) {
            val value = durationMatch.groupValues[1]
            val unit = durationMatch.groupValues[2]
            params["duration"] = "$value $unit"
        }
        
        val reminderMatch = Regex("""remind\s+me\s+to\s+["']?([^"']+)["']?""", RegexOption.IGNORE_CASE).find(request)
        if (reminderMatch != null) {
            params["message"] = reminderMatch.groupValues[1].trim()
        }
    }
    
    private fun extractFactParams(request: String, requestLower: String, params: MutableMap<String, Any>) {
        val factMatch = Regex("""remember\s+(?:that\s+)?["']?([^"']+)["']?""", RegexOption.IGNORE_CASE).find(request)
        if (factMatch != null) {
            params["fact"] = factMatch.groupValues[1].trim()
        }
        
        val type = when {
            requestLower.contains("preference") || requestLower.contains("like") || requestLower.contains("dislike") -> "preference"
            requestLower.contains("episodic") || requestLower.contains("happened") || requestLower.contains("went") -> "episodic"
            else -> "factual"
        }
        params["type"] = type
    }
    
    private fun extractGenericParams(
        request: String,
        params: MutableMap<String, Any>,
        spec: ToolParameters?
    ) {
        if (spec == null) return
        
        val keyValuePatterns = listOf(
            Regex("""(\w+)[:\s]+["']?([^"'\s]+)["']?(?:\s|$)""")
        )
        
        for (pattern in keyValuePatterns) {
            val matches = pattern.findAll(request)
            for (match in matches) {
                val key = match.groupValues[1].lowercase()
                val value = match.groupValues[2]
                
                if (spec.properties.containsKey(key)) {
                    params[key] = value
                }
            }
        }
    }
}

class ReasoningAgent(
    private val llmProvider: LlmProvider,
    private val toolExecutor: ToolExecutorAgent
) {
    private val logger = LoggerFactory.getLogger(ReasoningAgent::class.java)
    
    private val systemPrompt = buildMinimalSystemPrompt()
    private val conversationContext = ConversationContext()
    
    private data class ConversationContext(
        val messages: MutableList<ContextMessage> = mutableListOf()
    )
    
    private data class ContextMessage(
        val role: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private fun buildMinimalSystemPrompt(): String {
        return buildString {
            appendLine("You are an advanced AI assistant.")
            appendLine()
            appendLine("## Your Role")
            appendLine("Think, reason, understand, and help the user.")
            appendLine("Focus on comprehension, analysis, and problem-solving.")
            appendLine()
            appendLine("## Tool Execution")
            appendLine("When you need to use a tool, say:")
            appendLine("TOOL: <what you want to do>")
            appendLine()
            appendLine("Example:")
            appendLine("  TOOL: search for the weather in Tokyo")
            appendLine("  TOOL: save a note with my WiFi password")
            appendLine("  TOOL: find my notes about the project")
            appendLine()
            appendLine("The Tool Executor Agent will handle finding and running the appropriate tool.")
            appendLine("You don't need to know which specific tool exists - just describe what you want done.")
            appendLine()
            appendLine("## Guidelines")
            appendLine("- Focus on understanding the user's intent")
            appendLine("- Reason through complex problems")
            appendLine("- Delegate tool tasks by describing desired outcomes")
            appendLine("- Synthesize results from tool executions")
            appendLine("- Provide clear, helpful responses")
        }
    }
    
    suspend fun process(userInput: String): AgentResponse {
        conversationContext.messages.add(ContextMessage("user", userInput))
        
        val messages = buildMessageList()
        
        val response = llmProvider.chat(messages)
        
        val toolExecution = extractToolExecution(response)
        
        if (toolExecution != null) {
            conversationContext.messages.add(ContextMessage("assistant", response))
            
            val result = toolExecutor.execute(
                ToolRequest(
                    userRequest = toolExecution,
                    context = userInput
                )
            )
            
            val formattedResult = formatToolResult(result)
            conversationContext.messages.add(ContextMessage("tool", formattedResult))
            
            val finalMessages = messages + listOf(
                com.example.smarty.server.llm.LlmMessage("assistant", response),
                com.example.smarty.server.llm.LlmMessage("tool", formattedResult)
            )
            
            val finalResponse = llmProvider.chat(finalMessages)
            
            conversationContext.messages.add(ContextMessage("assistant", finalResponse))
            
            return AgentResponse(
                response = finalResponse,
                toolExecuted = true,
                toolName = result.toolName,
                toolResult = result.result,
                toolError = result.error,
                executionId = result.executionId
            )
        }
        
        conversationContext.messages.add(ContextMessage("assistant", response))
        
        return AgentResponse(
            response = response,
            toolExecuted = false
        )
    }
    
    private fun buildMessageList(): List<com.example.smarty.server.llm.LlmMessage> {
        val messages = mutableListOf<com.example.smarty.server.llm.LlmMessage>()
        
        messages.add(com.example.smarty.server.llm.LlmMessage("system", systemPrompt))
        
        messages.addAll(
            conversationContext.messages.takeLast(10).map {
                com.example.smarty.server.llm.LlmMessage(it.role, it.content)
            }
        )
        
        return messages
    }
    
    private fun extractToolExecution(response: String): String? {
        val patterns = listOf(
            Regex("""TOOL:\s*(.+?)(?:\n|$)""", RegexOption.IGNORE_CASE),
            Regex("""EXECUTE_TOOL:\s*(.+?)(?:\n|$)""", RegexOption.IGNORE_CASE),
            Regex("""USE_TOOL:\s*(.+?)(?:\n|$)""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(response)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        
        return null
    }
    
    private fun formatToolResult(result: ExecutionResult): String {
        return buildString {
            appendLine("[Tool Execution]")
            appendLine("Tool: ${result.toolName ?: "none"}")
            appendLine("Execution ID: ${result.executionId}")
            appendLine("Success: ${result.success}")
            appendLine()
            if (result.success) {
                appendLine("Result:")
                appendLine(result.result?.toString() ?: "No result")
            } else {
                appendLine("Error:")
                appendLine(result.error ?: "Unknown error")
            }
            if (result.durationMs != null) {
                appendLine()
                appendLine("Duration: ${result.durationMs}ms")
            }
        }
    }
    
    fun clearContext() {
        conversationContext.messages.clear()
    }
    
    fun getContext(): List<ContextMessage> = conversationContext.messages.toList()
}

data class AgentResponse(
    val response: String,
    val toolExecuted: Boolean,
    val toolName: String? = null,
    val toolResult: Any? = null,
    val toolError: String? = null,
    val executionId: String? = null
)

class AgentSystem(
    private val reasoningAgent: ReasoningAgent,
    private val toolExecutor: ToolExecutorAgent
) {
    private val logger = LoggerFactory.getLogger(AgentSystem::class.java)
    
    suspend fun process(input: String): AgentResponse {
        return reasoningAgent.process(input)
    }
    
    fun getToolExecutor(): ToolExecutorAgent = toolExecutor
    
    fun getSystemStatus(): String {
        val stats = toolExecutor.getStatistics()
        
        return buildString {
            appendLine("==================================================")
            appendLine("Agent System Status")
            appendLine("==================================================")
            appendLine()
            appendLine("[Tool Executor]")
            appendLine("  Available Tools: ${toolExecutor.getAvailableTools().size}")
            appendLine("  Active Executions: ${stats.activeExecutions}")
            appendLine("  Total Executions: ${stats.totalExecutions}")
            appendLine("  Success Rate: ${"%.1f".format(stats.recentSuccessRate * 100)}%")
            appendLine("  Avg Duration: ${"%.0f".format(stats.averageDurationMs)}ms")
            appendLine()
            appendLine("[Tool Usage]")
            stats.toolUsage.entries.sortedByDescending { it.value }.take(10).forEach { (tool, count) ->
                appendLine("  $tool: $count")
            }
            appendLine()
            appendLine("[Reasoning Agent]")
            appendLine("  Context Messages: ${reasoningAgent.getContext().size}")
        }
    }
}
