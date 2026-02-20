package com.example.smarty.server.tools

import com.example.smarty.server.llm.ToolDefinition
import com.example.smarty.server.llm.ToolParameters
import com.example.smarty.server.llm.ToolProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RobustToolExecutor(
    private val maxRetries: Int = 3,
    private val timeoutMs: Long = 30000
) {
    private val logger = LoggerFactory.getLogger(RobustToolExecutor::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executionMutex = Mutex()
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(4)
    
    private val toolRegistry = ConcurrentHashMap<String, ToolHandler>()
    private val router = RequestRouter()
    private val paramExtractor = ParameterExtractor()
    
    private val pendingRequests = ConcurrentHashMap<String, ToolRequest>()
    private val completedRequests = ConcurrentHashMap<String, ToolResult>()
    private val requestCallbacks = ConcurrentHashMap<String, CallbackInfo>()
    private val retryQueue = ConcurrentLinkedQueue<RetryableRequest>()
    
    private val activeExecutions = ConcurrentHashMap<String, ActiveExecution>()
    private val executionHistory = ConcurrentLinkedQueue<ExecutionEntry>()
    
    private val errorRegistry = ErrorRegistry()
    
    enum class ErrorCode(val code: Int, val message: String, val retryable: Boolean) {
        SUCCESS(0, "Success", false),
        TOOL_NOT_FOUND(1001, "Tool not found", false),
        PARAMETER_MISSING(1002, "Required parameter missing", false),
        PARAMETER_INVALID(1003, "Invalid parameter format", false),
        EXECUTION_TIMEOUT(2001, "Tool execution timed out", true),
        EXECUTION_FAILED(2002, "Tool execution failed", true),
        NETWORK_ERROR(3001, "Network error", true),
        RATE_LIMITED(3002, "Rate limit exceeded", true),
        AUTH_FAILED(4001, "Authentication failed", false),
        PERMISSION_DENIED(4002, "Permission denied", false),
        INTERNAL_ERROR(5001, "Internal server error", true),
        UNKNOWN_ERROR(9999, "Unknown error", false)
    }
    
    data class ToolHandler(
        val name: String,
        val definition: ToolDefinition,
        val handler: suspend (Map<String, Any>) -> ToolResponse
    )
    
    data class ToolResponse(
        val success: Boolean,
        val result: String?,
        val error: String?,
        val errorCode: ErrorCode = ErrorCode.SUCCESS,
        val metadata: Map<String, Any> = emptyMap()
    )
    
    data class ToolRequest(
        val id: String = UUID.randomUUID().toString(),
        val callerId: String,
        val toolName: String?,
        val userRequest: String,
        val parameters: Map<String, Any> = emptyMap(),
        val priority: Int = 0,
        val callbackUrl: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        var attempts: Int = 0
    )
    
    data class ToolResult(
        val requestId: String,
        val callerId: String,
        val success: Boolean,
        val toolName: String?,
        val result: String?,
        val error: String?,
        val errorCode: ErrorCode,
        val attempt: Int,
        val durationMs: Long,
        val completedAt: Long = System.currentTimeMillis()
    )
    
    data class CallbackInfo(
        val callbackId: String,
        val callerId: String,
        val requestId: String,
        val toolName: String,
        val createdAt: Long
    )
    
    data class ActiveExecution(
        val executionId: String,
        val requestId: String,
        val callerId: String,
        val toolName: String,
        val startTime: Long,
        var result: ToolResult? = null
    )
    
    data class ExecutionEntry(
        val executionId: String,
        val requestId: String,
        val callerId: String,
        val toolName: String,
        val startedAt: Long,
        val completedAt: Long,
        val success: Boolean,
        val durationMs: Long,
        val attempts: Int,
        val errorCode: ErrorCode
    )
    
    data class RetryableRequest(
        val request: ToolRequest,
        val lastError: ErrorCode,
        val retryAt: Long,
        val attemptNumber: Int
    )
    
    data class CallbackInfoData(
        val callerId: String,
        val requestId: String,
        val callback: (ToolResult) -> Unit
    )
    
    init {
        startHealthMonitor()
        startRetryProcessor()
    }
    
    private fun startHealthMonitor() {
        executor.scheduleAtFixedRate({
            checkStaleExecutions()
            cleanupCompletedRequests()
        }, 15, 15, TimeUnit.SECONDS
        )
    }
    
    private fun startRetryProcessor() {
        scope.launch {
            while (true) {
                processRetryQueue()
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    
    private fun checkStaleExecutions() {
        val now = System.currentTimeMillis()
        val stale = activeExecutions.filter { (now - it.value.startTime) > timeoutMs }
        
        stale.forEach { (execId, exec) ->
            logger.warn("Execution $execId timed out for caller ${exec.callerId}")
            exec.result = ToolResult(
                requestId = exec.requestId,
                callerId = exec.callerId,
                success = false,
                toolName = exec.toolName,
                result = null,
                error = "Execution timed out after ${timeoutMs}ms",
                errorCode = ErrorCode.EXECUTION_TIMEOUT,
                attempt = 1,
                durationMs = now - exec.startTime
            )
            activeExecutions.remove(execId)
        }
    }
    
    private fun cleanupCompletedRequests() {
        val cutoff = System.currentTimeMillis() - (60 * 60 * 1000)
        completedRequests.entries.removeIf { it.value.completedAt < cutoff }
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
        
        toolRegistry[name] = ToolHandler(name, definition, handler)
        router.register(definition)
        
        logger.info("Registered tool: $name")
    }
    
    suspend fun execute(request: ToolRequest): ToolResult {
        return executionMutex.withLock {
            val resolvedRequest = resolveTool(request)
            
            if (resolvedRequest.toolName == null) {
                return@withLock createErrorResult(
                    request,
                    ErrorCode.TOOL_NOT_FOUND,
                    "Could not find appropriate tool for: ${request.userRequest}"
                )
            }
            
            executeWithRetry(resolvedRequest)
        }
    }
    
    suspend fun executeAsync(request: ToolRequest): String {
        val requestId = request.id
        pendingRequests[requestId] = request
        
        scope.launch {
            val result = execute(request)
            completedRequests[requestId] = result
            
            val callback = requestCallbacks[requestId]
            if (callback != null) {
                try {
                    callback.callback(result)
                } catch (e: Exception) {
                    logger.error("Callback failed for $requestId: ${e.message}")
                }
            }
            
            pendingRequests.remove(requestId)
        }
        
        return requestId
    }
    
    suspend fun registerCallback(
        requestId: String,
        callerId: String,
        callback: (ToolResult) -> Unit
    ) {
        val request = pendingRequests[requestId]
        if (request != null) {
            requestCallbacks[requestId] = CallbackInfo(
                callbackId = UUID.randomUUID().toString(),
                callerId = callerId,
                requestId = requestId,
                toolName = request.toolName ?: "unknown",
                createdAt = System.currentTimeMillis()
            )
        }
    }
    
    private suspend fun resolveTool(request: ToolRequest): ToolRequest {
        if (request.toolName != null && toolRegistry.containsKey(request.toolName)) {
            return request
        }
        
        val routing = router.route(request.userRequest)
        
        val toolName = routing.toolName
        val params = if (request.parameters.isNotEmpty()) {
            request.parameters
        } else if (toolName != null) {
            paramExtractor.extract(request.userRequest, toolName, router.getDefinition(toolName))
        } else {
            emptyMap()
        }
        
        return request.copy(
            toolName = toolName,
            parameters = params
        )
    }
    
    private suspend fun executeWithRetry(request: ToolRequest): ToolResult {
        var lastError: ErrorCode = ErrorCode.SUCCESS
        var lastErrorMessage: String? = null
        
        for (attempt in 1..maxRetries) {
            val result = executeSingleAttempt(request.copy(attempts = attempt))
            
            if (result.success) {
                recordExecution(request, result, attempt)
                return result
            }
            
            lastError = result.errorCode
            lastErrorMessage = result.error
            
            if (!result.errorCode.retryable) {
                logger.info("Non-retryable error for ${request.toolName}: ${result.errorCode}")
                break
            }
            
            if (attempt < maxRetries) {
                val delay = (attempt * 1000L).coerceAtMost(5000)
                logger.info("Retrying ${request.toolName} (attempt $attempt/$maxRetries) after ${delay}ms")
                kotlinx.coroutines.delay(delay)
            }
        }
        
        val finalResult = if (lastError.retryable) {
            ToolResult(
                requestId = request.id,
                callerId = request.callerId,
                success = false,
                toolName = request.toolName,
                result = null,
                error = "Tool call has failed after $maxRetries attempts. Please refer another way to get/use information.",
                errorCode = lastError,
                attempt = maxRetries,
                durationMs = 0
            )
        } else {
            createErrorResult(request, lastError, lastErrorMessage ?: lastError.message)
        }
        
        recordExecution(request, finalResult, maxRetries)
        return finalResult
    }
    
    private suspend fun executeSingleAttempt(request: ToolRequest): ToolResult {
        val toolName = request.toolName ?: return createErrorResult(request, ErrorCode.TOOL_NOT_FOUND, "No tool specified")
        
        val handler = toolRegistry[toolName] ?: return createErrorResult(request, ErrorCode.TOOL_NOT_FOUND, "Tool not found: $toolName")
        
        val requiredParams = handler.definition.parameters?.required ?: emptyList()
        val missingParams = requiredParams.filter { !request.parameters.containsKey(it) }
        
        if (missingParams.isNotEmpty()) {
            return createErrorResult(
                request,
                ErrorCode.PARAMETER_MISSING,
                "Missing required parameters: ${missingParams.joinToString(", ")}"
            )
        }
        
        val executionId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()
        
        activeExecutions[executionId] = ActiveExecution(
            executionId = executionId,
            requestId = request.id,
            callerId = request.callerId,
            toolName = toolName,
            startTime = startTime
        )
        
        return try {
            val response = withTimeout(timeoutMs) {
                handler.handler(request.parameters)
            }
            
            val duration = System.currentTimeMillis() - startTime
            activeExecutions.remove(executionId)
            
            ToolResult(
                requestId = request.id,
                callerId = request.callerId,
                success = response.success,
                toolName = toolName,
                result = response.result,
                error = response.error,
                errorCode = if (response.success) ErrorCode.SUCCESS else response.errorCode,
                attempt = request.attempts,
                durationMs = duration
            )
            
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            activeExecutions.remove(executionId)
            logger.error("Timeout executing $toolName for caller ${request.callerId}")
            
            ToolResult(
                requestId = request.id,
                callerId = request.callerId,
                success = false,
                toolName = toolName,
                result = null,
                error = "Tool execution timed out after ${timeoutMs}ms",
                errorCode = ErrorCode.EXECUTION_TIMEOUT,
                attempt = request.attempts,
                durationMs = timeoutMs
            )
            
        } catch (e: Exception) {
            activeExecutions.remove(executionId)
            logger.error("Error executing $toolName: ${e.message}")
            
            val errorCode = when {
                e.message?.contains("network", ignoreCase = true) == true -> ErrorCode.NETWORK_ERROR
                e.message?.contains("auth", ignoreCase = true) == true -> ErrorCode.AUTH_FAILED
                else -> ErrorCode.EXECUTION_FAILED
            }
            
            ToolResult(
                requestId = request.id,
                callerId = request.callerId,
                success = false,
                toolName = toolName,
                result = null,
                error = e.message ?: "Execution failed",
                errorCode = errorCode,
                attempt = request.attempts,
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }
    
    private fun createErrorResult(request: ToolRequest, errorCode: ErrorCode, errorMessage: String?): ToolResult {
        return ToolResult(
            requestId = request.id,
            callerId = request.callerId,
            success = false,
            toolName = request.toolName,
            result = null,
            error = errorMessage ?: errorCode.message,
            errorCode = errorCode,
            attempt = request.attempts,
            durationMs = 0
        )
    }
    
    private fun recordExecution(request: ToolRequest, result: ToolResult, attempts: Int) {
        val entry = ExecutionEntry(
            executionId = UUID.randomUUID().toString(),
            requestId = request.id,
            callerId = request.callerId,
            toolName = request.toolName ?: "unknown",
            startedAt = request.createdAt,
            completedAt = System.currentTimeMillis(),
            success = result.success,
            durationMs = result.durationMs,
            attempts = attempts,
            errorCode = result.errorCode
        )
        executionHistory.offer(entry)
        errorRegistry.record(result.errorCode)
    }
    
    private suspend fun processRetryQueue() {
        val now = System.currentTimeMillis()
        val toRetry = mutableListOf<RetryableRequest>()
        
        while (retryQueue.isNotEmpty()) {
            val item = retryQueue.peek()
            if (item != null && item.retryAt <= now) {
                retryQueue.poll()
                toRetry.add(item)
            } else {
                break
            }
        }
        
        for (item in toRetry) {
            val result = executeWithRetry(item.request)
            
            val callback = requestCallbacks[item.request.id]
            callback?.let {
                try {
                    it.callback(result)
                } catch (e: Exception) {
                    logger.error("Retry callback failed: ${e.message}")
                }
            }
        }
    }
    
    suspend fun executeMultiple(requests: List<ToolRequest>): List<ToolResult> {
        return requests.map { request ->
            scope.async { execute(request) }
        }.awaitAll()
    }
    
    suspend fun executeParallel(requests: List<ToolRequest>): List<ToolResult> {
        return scope.async {
            requests.parMap { request ->
                scope.async { execute(request) }
            }.awaitAll()
        }.await()
    }
    
    private fun <T, R> Iterable<T>.parMap(f: suspend (T) -> R): List<R> = runBlocking {
        map { async { f(it) } }.awaitAll()
    }
    
    fun getToolDefinitions(): List<ToolDefinition> {
        return toolRegistry.values.map { it.definition }
    }
    
    fun getAvailableTools(): List<String> {
        return toolRegistry.keys.toList()
    }
    
    fun getStatus(callerId: String? = null): ExecutorStatus {
        val recentHistory = executionHistory.toList().takeLast(100)
        
        val filteredHistory = if (callerId != null) {
            recentHistory.filter { it.callerId == callerId }
        } else {
            recentHistory
        }
        
        val successCount = filteredHistory.count { it.success }
        val totalCount = filteredHistory.size
        
        val byCaller = filteredHistory.groupBy { it.callerId }
            .mapValues { it.value.size }
        
        val byTool = filteredHistory.groupBy { it.toolName }
            .mapValues { it.value.size }
        
        val errorBreakdown = errorRegistry.getErrorCounts()
        
        return ExecutorStatus(
            totalTools = toolRegistry.size,
            activeExecutions = activeExecutions.size,
            pendingRequests = pendingRequests.size,
            totalExecutions = executionHistory.size.toLong(),
            successRate = if (totalCount > 0) successCount.toDouble() / totalCount else 0.0,
            byCaller = byCaller,
            byTool = byTool,
            errorBreakdown = errorBreakdown
        )
    }
    
    fun shutdown() {
        executor.shutdown()
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

data class ExecutorStatus(
    val totalTools: Int,
    val activeExecutions: Int,
    val pendingRequests: Int,
    val totalExecutions: Long,
    val successRate: Double,
    val byCaller: Map<String, Int>,
    val byTool: Map<String, Int>,
    val errorBreakdown: Map<String, Int>
)

class ErrorRegistry {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    
    fun record(error: RobustToolExecutor.ErrorCode) {
        counts.getOrPut(error.name) { AtomicInteger(0) }.incrementAndGet()
    }
    
    fun getErrorCounts(): Map<String, Int> {
        return counts.mapValues { it.value.get() }
    }
    
    fun reset() {
        counts.clear()
    }
}

class RequestRouter {
    private val tools = ConcurrentHashMap<String, ToolDefinition>()
    private val keywords = ConcurrentHashMap<String, MutableList<Pair<String, Double>>>()
    
    fun register(definition: ToolDefinition) {
        tools[definition.name] = definition
        
        val kw = extractKeywords(definition)
        for ((word, weight) in kw) {
            keywords.getOrPut(word) { mutableListOf() }.add(definition.name to weight)
        }
    }
    
    private fun extractKeywords(def: ToolDefinition): List<Pair<String, Double>> {
        val kw = mutableListOf<Pair<String, Double>>()
        
        kw.add(def.name.lowercase() to 1.0)
        
        val desc = def.description.lowercase()
        
        val actionWords = mapOf(
            "search" to 0.9, "find" to 0.8, "get" to 0.7,
            "save" to 0.9, "store" to 0.8, "remember" to 0.8,
            "create" to 0.8, "add" to 0.7, "delete" to 0.8, "remove" to 0.8,
            "update" to 0.8, "edit" to 0.8, "modify" to 0.7,
            "list" to 0.6, "show" to 0.6, "display" to 0.6,
            "calculate" to 0.9, "compute" to 0.9, "analyze" to 0.8,
            "compare" to 0.8, "convert" to 0.9, "transform" to 0.8
        )
        
        for ((word, weight) in actionWords) {
            if (desc.contains(word)) {
                kw.add(word to weight)
            }
        }
        
        return kw.distinctBy { it.first }
    }
    
    fun route(request: String): RoutingResult {
        val words = request.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        
        val scores = mutableMapOf<String, Double>()
        
        for (word in words) {
            val matching = keywords.filter { it.key.contains(word) || word.contains(it.key) }
            for ((keyword, toolWeights) in matching) {
                for ((tool, weight) in toolWeights) {
                    scores[tool] = (scores[tool] ?: 0.0) + weight
                }
            }
        }
        
        for ((tool, def) in tools) {
            val desc = def.description.lowercase()
            for (word in words) {
                if (desc.contains(word)) {
                    scores[tool] = (scores[tool] ?: 0.0) + 0.4
                }
            }
        }
        
        val sorted = scores.entries.sortedByDescending { it.value }
        val selected = sorted.firstOrNull()
        
        return RoutingResult(
            toolName = selected?.key,
            confidence = selected?.value?.coerceIn(0.0, 1.0) ?: 0.0,
            alternatives = sorted.drop(1).take(3).map { it.key }
        )
    }
    
    fun getDefinition(name: String): ToolDefinition? = tools[name]
}

data class RoutingResult(
    val toolName: String?,
    val confidence: Double,
    val alternatives: List<String>
)

class ParameterExtractor {
    
    fun extract(request: String, toolName: String, definition: ToolDefinition?): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        val reqLower = request.lowercase()
        
        when {
            toolName.contains("note") -> extractNoteParams(request, reqLower, params)
            toolName.contains("search") -> params["query"] = extractQuery(request)
            toolName.contains("event") || toolName.contains("calendar") -> extractEventParams(request, reqLower, params)
            toolName.contains("timer") || toolName.contains("remind") -> extractTimerParams(request, reqLower, params)
            toolName.contains("fact") || toolName.contains("remember") -> extractFactParams(request, reqLower, params)
        }
        
        return params
    }
    
    private fun extractNoteParams(request: String, reqLower: String, params: MutableMap<String, Any>) {
        val titleMatch = Regex("""title[:\s]+["']?([^"']+)["']?""", RegexOption.IGNORE_CASE).find(request)
        titleMatch?.groupValues?.get(1)?.trim()?.let { params["title"] = it }
        
        val contentMatch = Regex("""content[:\s]+["']?([^"']+)["']?""", RegexOption.IGNORE_CASE).find(request)
        contentMatch?.groupValues?.get(1)?.trim()?.let { params["content"] = it }
        
        if (!params.containsKey("content")) {
            val afterNote = request.substringAfter("note").substringAfter("save").trim()
            if (afterNote.isNotEmpty()) {
                params["content"] = afterNote.take(200)
                params["title"] = "Note ${System.currentTimeMillis() % 10000}"
            }
        }
        
        val category = when {
            reqLower.contains("work") || reqLower.contains("job") -> "work"
            reqLower.contains("personal") || reqLower.contains("home") -> "personal"
            reqLower.contains("idea") -> "ideas"
            else -> null
        }
        category?.let { params["category"] = it }
    }
    
    private fun extractQuery(request: String): String {
        val patterns = listOf(
            Regex("""search\s+(?:for\s+)?["']?([^"']+)["']?""", RegexOption.IGNORE_CASE),
            Regex("""find\s+(?:information\s+)?(?:about\s+)?["']?([^"']+)["']?""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(request)
            if (match != null) return match.groupValues[1].trim()
        }
        
        return request.substringAfter("search", request.substringAfter("find")).trim().ifEmpty { request }
    }
    
    private fun extractEventParams(request: String, reqLower: String, params: MutableMap<String, Any>) {
        val titleMatch = Regex("""event\s+["']?([^"']+)["']?""", RegexOption.IGNORE_CASE).find(request)
        titleMatch?.groupValues?.get(1)?.trim()?.let { params["title"] = it }
        
        val timeMatch = Regex("""(?:at|on)\s+["']?([^"']+)["']?""", RegexOption.IGNORE_CASE).find(request)
        timeMatch?.groupValues?.get(1)?.trim()?.let { params["when"] = it }
        
        val durationMatch = Regex("""(\d+)\s*(hour|hr|minute|min)""", RegexOption.IGNORE_CASE).find(request)
        durationMatch?.let { params["duration"] = "${it.groupValues[1]} ${it.groupValues[2]}" }
    }
    
    private fun extractTimerParams(request: String, reqLower: String, params: MutableMap<String, Any>) {
        val durationMatch = Regex("""(\d+)\s*(hour|hr|minute|min|second|sec)""", RegexOption.IGNORE_CASE).find(request)
        durationMatch?.let { params["duration"] = "${it.groupValues[1]} ${it.groupValues[2]}" }
        
        val msgMatch = Regex("""remind\s+me\s+to\s+["']?([^"']+)["']?""", RegexOption.IGNORE_CASE).find(request)
        msgMatch?.groupValues?.get(1)?.trim()?.let { params["message"] = it }
    }
    
    private fun extractFactParams(request: String, reqLower: String, params: MutableMap<String, Any>) {
        val factMatch = Regex("""remember\s+(?:that\s+)?["']?([^"']+)["']?""", RegexOption.IGNORE_CASE).find(request)
        factMatch?.groupValues?.get(1)?.trim()?.let { params["fact"] = it }
        
        val type = when {
            reqLower.contains("preference") || reqLower.contains("like") -> "preference"
            reqLower.contains("episodic") || reqLower.contains("happened") -> "episodic"
            else -> "factual"
        }
        params["type"] = type
    }
}

class MultiAgentToolClient(
    private val executor: RobustToolExecutor
) {
    private val logger = LoggerFactory.getLogger(MultiAgentToolClient::class.java)
    
    data class AgentContext(
        val agentId: String,
        val name: String,
        val createdAt: Long = System.currentTimeMillis(),
        var lastUsed: Long = System.currentTimeMillis()
    )
    
    private val registeredAgents = ConcurrentHashMap<String, AgentContext>()
    private val agentResults = ConcurrentHashMap<String, ConcurrentHashMap<String, RobustToolExecutor.ToolResult>>()
    
    fun registerAgent(agentId: String, agentName: String): Boolean {
        if (registeredAgents.containsKey(agentId)) {
            return false
        }
        
        registeredAgents[agentId] = AgentContext(agentId, agentName)
        agentResults[agentId] = ConcurrentHashMap()
        
        logger.info("Registered agent: $agentId ($agentName)")
        return true
    }
    
    fun unregisterAgent(agentId: String): Boolean {
        val removed = registeredAgents.remove(agentId) != null
        agentResults.remove(agentId)
        
        if (removed) {
            logger.info("Unregistered agent: $agentId")
        }
        return removed
    }
    
    suspend fun callTool(
        agentId: String,
        userRequest: String,
        parameters: Map<String, Any> = emptyMap(),
        toolName: String? = null
    ): RobustToolExecutor.ToolResult {
        val agent = registeredAgents[agentId]
        if (agent == null) {
            return createErrorResult(agentId, "Agent not registered")
        }
        
        agent.lastUsed = System.currentTimeMillis()
        
        val request = RobustToolExecutor.ToolRequest(
            callerId = agentId,
            toolName = toolName,
            userRequest = userRequest,
            parameters = parameters
        )
        
        val result = executor.execute(request)
        
        agentResults[agentId]?.set(result.requestId, result)
        
        return result
    }
    
    suspend fun callToolAsync(
        agentId: String,
        userRequest: String,
        parameters: Map<String, Any> = emptyMap(),
        toolName: String? = null,
        onResult: (RobustToolExecutor.ToolResult) -> Unit
    ): String {
        val agent = registeredAgents[agentId]
        if (agent == null) {
            onResult(createErrorResult(agentId, "Agent not registered"))
            return ""
        }
        
        agent.lastUsed = System.currentTimeMillis()
        
        val request = RobustToolExecutor.ToolRequest(
            callerId = agentId,
            toolName = toolName,
            userRequest = userRequest,
            parameters = parameters
        )
        
        val requestId = executor.executeAsync(request)
        
        scope.launch {
            executor.registerCallback(requestId, agentId) { result ->
                agentResults[agentId]?.set(result.requestId, result)
                onResult(result)
            }
        }
        
        return requestId
    }
    
    fun getResult(agentId: String, requestId: String): RobustToolExecutor.ToolResult? {
        return agentResults[agentId]?.get(requestId)
    }
    
    fun getAgentResults(agentId: String): List<RobustToolExecutor.ToolResult> {
        return agentResults[agentId]?.values?.toList() ?: emptyList()
    }
    
    fun getRegisteredAgents(): List<AgentContext> {
        return registeredAgents.values.toList()
    }
    
    fun getAgentStatus(agentId: String): String {
        val agent = registeredAgents[agentId]
        if (agent == null) return "Agent not found: $agentId"
        
        val results = agentResults[agentId]?.values ?: emptyList()
        val successCount = results.count { it.success }
        
        return buildString {
            appendLine("Agent: ${agent.name} (${agent.agentId})")
            appendLine("  Registered: ${java.time.Instant.ofEpochMilli(agent.createdAt)}")
            appendLine("  Last Used: ${java.time.Instant.ofEpochMilli(agent.lastUsed)}")
            appendLine("  Total Requests: ${results.size}")
            appendLine("  Success Rate: ${if (results.isNotEmpty()) successCount * 100 / results.size else 0}%")
        }
    }
    
    private fun createErrorResult(agentId: String, error: String): RobustToolExecutor.ToolResult {
        return RobustToolExecutor.ToolResult(
            requestId = "",
            callerId = agentId,
            success = false,
            toolName = null,
            result = null,
            error = error,
            errorCode = RobustToolExecutor.ErrorCode.UNKNOWN_ERROR,
            attempt = 1,
            durationMs = 0
        )
    }
    
    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
