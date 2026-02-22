package com.example.smarty.server.tools

import com.example.smarty.server.llm.ToolDefinition
import com.example.smarty.server.llm.ToolParameters
import com.example.smarty.server.llm.ToolProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class RobustToolExecutor(
    private val maxRetries: Int = 3,
    private val timeoutMs: Long = 30000
) {
    private val logger = LoggerFactory.getLogger(RobustToolExecutor::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executionMutex = Mutex()
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(8)
    
    private val toolRegistry = ConcurrentHashMap<String, ToolHandler>()
    private val toolVersionRegistry = ConcurrentHashMap<String, MutableMap<Int, ToolHandler>>()
    private val router = RequestRouter()
    private val paramExtractor = ParameterExtractor()
    
    private val pendingRequests = ConcurrentHashMap<String, ToolRequest>()
    private val completedRequests = ConcurrentHashMap<String, ToolResult>()
    private val requestCallbacks = ConcurrentHashMap<String, CallbackInfo>()
    private val retryQueue = ConcurrentLinkedQueue<RetryableRequest>()
    
    private val activeExecutions = ConcurrentHashMap<String, ActiveExecution>()
    private val executionHistory = ConcurrentLinkedQueue<ExecutionEntry>()
    
    private val errorRegistry = ErrorRegistry()
    private val circuitBreakerRegistry = ConcurrentHashMap<String, CircuitBreaker>()
    private val rateLimiterRegistry = ConcurrentHashMap<String, TokenBucketRateLimiter>()
    private val agentRateLimiters = ConcurrentHashMap<String, TokenBucketRateLimiter>()
    private val cacheManager = ToolResultCache()
    private val dependencyGraph = ToolDependencyGraph()
    private val pipelineExecutor = PipelineExecutor(this)
    private val deadlockDetector = DeadlockDetector()
    private val backpressureController = BackpressureController()
    private val tracingManager = TracingManager()
    private val metricsCollector = MetricsCollector()
    private val timeoutOptimizer = TimeoutOptimizer()
    private val securityValidator = SecurityValidator()
    private val loadBalancer = ToolLoadBalancer()
    private val hotReloadManager = HotReloadManager()
    
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
        CIRCUIT_OPEN(5002, "Circuit breaker is open", false),
        BACKPRESSURE(5003, "System under high load", true),
        CIRCULAR_DEPENDENCY(5004, "Circular dependency detected", false),
        SECURITY_VIOLATION(5005, "Security validation failed", false),
        UNKNOWN_ERROR(9999, "Unknown error", false)
    }
    
    data class ToolHandler(
        val name: String,
        val definition: ToolDefinition,
        val handler: suspend (Map<String, Any>) -> ToolResponse,
        val version: Int = 1,
        val dependencies: List<String> = emptyList(),
        val resourceCost: Int = 1,
        val retryPolicy: RetryPolicy = RetryPolicy.Default
    )
    
    data class RetryPolicy(
        val maxAttempts: Int,
        val baseDelayMs: Long,
        val maxDelayMs: Long,
        val exponentialBase: Double,
        val jitterFactor: Double
    ) {
        companion object {
            val Default = RetryPolicy(3, 1000, 10000, 2.0, 0.3)
            val Aggressive = RetryPolicy(5, 500, 5000, 1.5, 0.2)
            val Conservative = RetryPolicy(2, 2000, 20000, 2.5, 0.4)
        }
    }
    
    data class ToolResponse(
        val success: Boolean,
        val result: String?,
        val error: String?,
        val errorCode: ErrorCode = ErrorCode.SUCCESS,
        val metadata: Map<String, Any> = emptyMap(),
        val cacheHit: Boolean = false,
        val traceId: String? = null
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
        var attempts: Int = 0,
        val traceId: String = UUID.randomUUID().toString(),
        val spanId: String = UUID.randomUUID().toString().take(8),
        val parentSpanId: String? = null,
        val pipelineId: String? = null,
        val authToken: String? = null
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
        val completedAt: Long = System.currentTimeMillis(),
        val traceId: String? = null,
        val spanId: String? = null,
        val metadata: Map<String, Any> = emptyMap()
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
        var result: ToolResult? = null,
        val spanId: String,
        val resourceCost: Int
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
        val errorCode: ErrorCode,
        val traceId: String? = null,
        val cacheHit: Boolean = false
    )
    
    data class RetryableRequest(
        val request: ToolRequest,
        val lastError: ErrorCode,
        val retryAt: Long,
        val attemptNumber: Int
    )
    
    init {
        startHealthMonitor()
        startRetryProcessor()
        startMetricsCollection()
        startCircuitBreakerRecovery()
        startDeadlockDetection()
        startCacheCleanup()
        startTimeoutOptimization()
        logger.info("RobustToolExecutor initialized with full feature set")
    }
    
    private fun startHealthMonitor() {
        executor.scheduleAtFixedRate({
            checkStaleExecutions()
            cleanupCompletedRequests()
            updateMetrics()
            checkBackpressure()
        }, 15, 15, TimeUnit.SECONDS
        )
    }
    
    private fun startRetryProcessor() {
        scope.launch {
            while (true) {
                processRetryQueue()
                delay(1000)
            }
        }
    }
    
    private fun startMetricsCollection() {
        executor.scheduleAtFixedRate({
            metricsCollector.record(
                activeExecutions = activeExecutions.size,
                pendingRequests = pendingRequests.size,
                totalTools = toolRegistry.size,
                cacheHitRate = cacheManager.getHitRate(),
                circuitBreakerStates = circuitBreakerRegistry.mapValues { it.value.state.name }
            )
        }, 30, 30, TimeUnit.SECONDS
        )
    }
    
    private fun startCircuitBreakerRecovery() {
        executor.scheduleAtFixedRate({
            circuitBreakerRegistry.values.forEach { it.tryRecovery() }
        }, 60, 60, TimeUnit.SECONDS
        )
    }
    
    private fun startDeadlockDetection() {
        executor.scheduleAtFixedRate({
            val cycles = deadlockDetector.detectCycles(dependencyGraph)
            if (cycles.isNotEmpty()) {
                logger.error("Circular dependencies detected: $cycles")
            }
        }, 30, 30, TimeUnit.SECONDS
        )
    }
    
    private fun startCacheCleanup() {
        executor.scheduleAtFixedRate({
            cacheManager.cleanup()
        }, 60, 60, TimeUnit.SECONDS
        )
    }
    
    private fun startTimeoutOptimization() {
        executor.scheduleAtFixedRate({
            timeoutOptimizer.updateFromHistory(executionHistory.toList())
        }, 120, 120, TimeUnit.SECONDS
        )
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
                durationMs = now - exec.startTime,
                traceId = exec.spanId
            )
            activeExecutions.remove(execId)
            circuitBreakerRegistry[exec.toolName]?.recordFailure()
        }
    }
    
    private fun cleanupCompletedRequests() {
        val cutoff = System.currentTimeMillis() - (60 * 60 * 1000)
        completedRequests.entries.removeIf { it.value.completedAt < cutoff }
    }
    
    private fun updateMetrics() {
        val status = getStatus()
        metricsCollector.recordSuccessRate(status.successRate)
    }
    
    private fun checkBackpressure() {
        if (!backpressureController.shouldAccept()) {
            logger.warn("Backpressure active - rejecting new requests")
        }
    }
    
    fun registerTool(
        name: String,
        description: String,
        parameters: Map<String, ToolProperty>,
        required: List<String>,
        handler: suspend (Map<String, Any>) -> ToolResponse,
        version: Int = 1,
        dependencies: List<String> = emptyList(),
        resourceCost: Int = 1,
        retryPolicy: RetryPolicy = RetryPolicy.Default
    ) {
        val definition = ToolDefinition(
            name = name,
            description = description,
            parameters = ToolParameters(properties = parameters, required = required)
        )
        
        val toolHandler = ToolHandler(name, definition, handler, version, dependencies, resourceCost, retryPolicy)
        toolRegistry[name] = toolHandler
        
        toolVersionRegistry.getOrPut(name) { ConcurrentHashMap() }[version] = toolHandler
        
        dependencyGraph.addTool(name, dependencies)
        
        circuitBreakerRegistry[name] = CircuitBreaker(
            failureThreshold = 5,
            recoveryTimeoutMs = 60000,
            halfOpenAttempts = 3
        )
        
        rateLimiterRegistry[name] = TokenBucketRateLimiter(
            capacity = 100,
            refillRate = 10.0
        )
        
        router.register(definition)
        hotReloadManager.register(name, handler)
        
        logger.info("Registered tool: $name v$version with dependencies: $dependencies")
    }
    
    suspend fun execute(request: ToolRequest): ToolResult {
        val traceId = request.traceId
        tracingManager.startSpan(traceId, request.spanId, request.parentSpanId)
        
        val securityCheck = securityValidator.validate(request)
        if (!securityCheck.valid) {
            tracingManager.endSpan(traceId, false)
            return createErrorResult(
                request,
                ErrorCode.SECURITY_VIOLATION,
                securityCheck.reason ?: "Security validation failed"
            )
        }
        
        if (!backpressureController.shouldAccept()) {
            tracingManager.endSpan(traceId, false)
            return createErrorResult(
                request,
                ErrorCode.BACKPRESSURE,
                "System is under high load. Please try again later."
            )
        }
        
        val agentLimiter = agentRateLimiters.getOrPut(request.callerId) {
            TokenBucketRateLimiter(capacity = 50, refillRate = 5.0)
        }
        
        if (!agentLimiter.tryConsume()) {
            tracingManager.endSpan(traceId, false)
            return createErrorResult(
                request,
                ErrorCode.RATE_LIMITED,
                "Agent ${request.callerId} rate limit exceeded"
            )
        }
        
        return executionMutex.withLock {
            val resolvedRequest = resolveTool(request)
            
            if (resolvedRequest.toolName == null) {
                tracingManager.endSpan(traceId, false)
                return@withLock createErrorResult(
                    request,
                    ErrorCode.TOOL_NOT_FOUND,
                    "Could not find appropriate tool for: ${request.userRequest}"
                )
            }
            
            val toolLimiter = rateLimiterRegistry[resolvedRequest.toolName]
            if (toolLimiter != null && !toolLimiter.tryConsume()) {
                tracingManager.endSpan(traceId, false)
                return@withLock createErrorResult(
                    request,
                    ErrorCode.RATE_LIMITED,
                    "Tool ${resolvedRequest.toolName} rate limit exceeded"
                )
            }
            
            val circuitBreaker = circuitBreakerRegistry[resolvedRequest.toolName]
            if (circuitBreaker != null && !circuitBreaker.canExecute()) {
                tracingManager.endSpan(traceId, false)
                return@withLock createErrorResult(
                    request,
                    ErrorCode.CIRCUIT_OPEN,
                    "Circuit breaker is open for ${resolvedRequest.toolName}"
                )
            }
            
            if (deadlockDetector.wouldCauseDeadlock(request.callerId, resolvedRequest.toolName ?: "", dependencyGraph)) {
                tracingManager.endSpan(traceId, false)
                return@withLock createErrorResult(
                    request,
                    ErrorCode.CIRCULAR_DEPENDENCY,
                    "This operation would cause a circular dependency"
                )
            }
            
            val cachedResult = cacheManager.get(resolvedRequest)
            if (cachedResult != null) {
                tracingManager.endSpan(traceId, true)
                metricsCollector.recordCacheHit()
                return@withLock cachedResult.copy(cacheHit = true, traceId = traceId)
            }
            
            val handler = toolRegistry[resolvedRequest.toolName]
            val toolTimeout = timeoutOptimizer.getOptimizedTimeout(resolvedRequest.toolName ?: "")
            
            val result = executeWithRetry(resolvedRequest, toolTimeout, handler?.retryPolicy)
            
            if (result.success && result.errorCode == ErrorCode.SUCCESS) {
                cacheManager.put(resolvedRequest, result)
                circuitBreaker?.recordSuccess()
            } else {
                circuitBreaker?.recordFailure()
            }
            
            tracingManager.endSpan(traceId, result.success)
            result.copy(traceId = traceId)
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
    
    suspend fun executePipeline(pipeline: ToolPipeline): PipelineResult {
        return pipelineExecutor.execute(pipeline)
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
    
    private suspend fun executeWithRetry(
        request: ToolRequest,
        timeout: Long,
        retryPolicy: RetryPolicy?
    ): ToolResult {
        val policy = retryPolicy ?: RetryPolicy.Default
        var lastError: ErrorCode = ErrorCode.SUCCESS
        var lastErrorMessage: String? = null
        
        for (attempt in 1..policy.maxAttempts) {
            val result = executeSingleAttempt(request.copy(attempts = attempt), timeout)
            
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
            
            if (attempt < policy.maxAttempts) {
                val delay = calculateRetryDelay(attempt, policy)
                logger.info("Retrying ${request.toolName} (attempt $attempt/${policy.maxAttempts}) after ${delay}ms")
                delay(delay)
            }
        }
        
        val finalResult = if (lastError.retryable) {
            ToolResult(
                requestId = request.id,
                callerId = request.callerId,
                success = false,
                toolName = request.toolName,
                result = null,
                error = "Tool call has failed after ${policy.maxAttempts} attempts. Please refer another way to get/use information.",
                errorCode = lastError,
                attempt = policy.maxAttempts,
                durationMs = 0,
                traceId = request.traceId
            )
        } else {
            createErrorResult(request, lastError, lastErrorMessage ?: lastError.message)
        }
        
        recordExecution(request, finalResult, policy.maxAttempts)
        return finalResult
    }
    
    private fun calculateRetryDelay(attempt: Int, policy: RetryPolicy): Long {
        val exponentialDelay = policy.baseDelayMs * policy.exponentialBase.pow(attempt - 1)
        val cappedDelay = min(exponentialDelay, policy.maxDelayMs)
        val jitter = cappedDelay * policy.jitterFactor * (SecureRandom().nextDouble() - 0.5)
        return max(100, (cappedDelay + jitter).toLong())
    }
    
    private suspend fun executeSingleAttempt(request: ToolRequest, timeout: Long): ToolResult {
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
        
        for (dep in handler.dependencies) {
            if (!toolRegistry.containsKey(dep)) {
                return createErrorResult(
                    request,
                    ErrorCode.TOOL_NOT_FOUND,
                    "Required dependency not found: $dep"
                )
            }
        }
        
        val executionId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()
        
        activeExecutions[executionId] = ActiveExecution(
            executionId = executionId,
            requestId = request.id,
            callerId = request.callerId,
            toolName = toolName,
            startTime = startTime,
            spanId = request.spanId,
            resourceCost = handler.resourceCost
        )
        
        return try {
            backpressureController.reserveResources(handler.resourceCost)
            
            val response = withTimeout(timeout) {
                handler.handler(request.parameters)
            }
            
            backpressureController.releaseResources(handler.resourceCost)
            
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
                durationMs = duration,
                traceId = request.traceId,
                spanId = request.spanId
            )
            
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            backpressureController.releaseResources(handler.resourceCost)
            activeExecutions.remove(executionId)
            logger.error("Timeout executing $toolName for caller ${request.callerId}")
            
            ToolResult(
                requestId = request.id,
                callerId = request.callerId,
                success = false,
                toolName = toolName,
                result = null,
                error = "Tool execution timed out after ${timeout}ms",
                errorCode = ErrorCode.EXECUTION_TIMEOUT,
                attempt = request.attempts,
                durationMs = timeout,
                traceId = request.traceId,
                spanId = request.spanId
            )
            
        } catch (e: Exception) {
            backpressureController.releaseResources(handler.resourceCost)
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
                durationMs = System.currentTimeMillis() - startTime,
                traceId = request.traceId,
                spanId = request.spanId
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
            durationMs = 0,
            traceId = request.traceId,
            spanId = request.spanId
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
            errorCode = result.errorCode,
            traceId = result.traceId,
            cacheHit = result.cacheHit
        )
        executionHistory.offer(entry)
        errorRegistry.record(result.errorCode)
        timeoutOptimizer.recordExecution(request.toolName ?: "", result.durationMs, result.success)
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
            val result = executeWithRetry(item.request, timeoutMs, null)
            
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
            errorBreakdown = errorBreakdown,
            circuitBreakerStates = circuitBreakerRegistry.mapValues { it.value.state.name },
            cacheStats = cacheManager.getStats(),
            backpressureActive = !backpressureController.shouldAccept(),
            currentResourceUsage = backpressureController.currentUsage
        )
    }
    
    fun getMetrics(): Map<String, Any> {
        return metricsCollector.getAllMetrics()
    }
    
    fun getTrace(traceId: String): TraceInfo? {
        return tracingManager.getTrace(traceId)
    }
    
    fun invalidateCache(toolName: String? = null, pattern: String? = null) {
        if (toolName != null) {
            cacheManager.invalidateByTool(toolName)
        } else if (pattern != null) {
            cacheManager.invalidateByPattern(pattern)
        } else {
            cacheManager.clear()
        }
    }
    
    fun reloadTool(name: String, newHandler: suspend (Map<String, Any>) -> ToolResponse) {
        hotReloadManager.reload(name, newHandler)
        val existing = toolRegistry[name]
        if (existing != null) {
            toolRegistry[name] = existing.copy(handler = newHandler)
            logger.info("Hot reloaded tool: $name")
        }
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
    val errorBreakdown: Map<String, Int>,
    val circuitBreakerStates: Map<String, String> = emptyMap(),
    val cacheStats: CacheStats = CacheStats(0, 0, 0.0),
    val backpressureActive: Boolean = false,
    val currentResourceUsage: Int = 0
)

data class CacheStats(
    val entries: Int,
    val hits: Int,
    val hitRate: Double
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

class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val recoveryTimeoutMs: Long = 60000,
    private val halfOpenAttempts: Int = 3
) {
    private val failureCount = AtomicInteger(0)
    private val successCount = AtomicInteger(0)
    private val lastFailureTime = AtomicLong(0)
    
    enum class State { CLOSED, OPEN, HALF_OPEN }
    
    @Volatile
    var state: State = State.CLOSED
        private set
    
    fun canExecute(): Boolean {
        return when (state) {
            State.CLOSED -> true
            State.OPEN -> {
                if (System.currentTimeMillis() - lastFailureTime.get() > recoveryTimeoutMs) {
                    state = State.HALF_OPEN
                    successCount.set(0)
                    true
                } else {
                    false
                }
            }
            State.HALF_OPEN -> true
        }
    }
    
    fun recordSuccess() {
        when (state) {
            State.CLOSED -> failureCount.set(0)
            State.HALF_OPEN -> {
                successCount.incrementAndGet()
                if (successCount.get() >= halfOpenAttempts) {
                    state = State.CLOSED
                    failureCount.set(0)
                }
            }
            State.OPEN -> {}
        }
    }
    
    fun recordFailure() {
        failureCount.incrementAndGet()
        lastFailureTime.set(System.currentTimeMillis())
        
        when (state) {
            State.CLOSED -> {
                if (failureCount.get() >= failureThreshold) {
                    state = State.OPEN
                }
            }
            State.HALF_OPEN -> {
                state = State.OPEN
            }
            State.OPEN -> {}
        }
    }
    
    fun tryRecovery() {
        if (state == State.OPEN && System.currentTimeMillis() - lastFailureTime.get() > recoveryTimeoutMs) {
            state = State.HALF_OPEN
            successCount.set(0)
        }
    }
}

class TokenBucketRateLimiter(
    private val capacity: Int,
    private val refillRate: Double
) {
    private val tokens = AtomicInteger(capacity)
    private val lastRefill = AtomicLong(System.currentTimeMillis())
    private val lock = ReentrantReadWriteLock()
    
    fun tryConsume(): Boolean {
        refill()
        
        return lock.read {
            if (tokens.get() > 0) {
                tokens.decrementAndGet()
                true
            } else {
                false
            }
        }
    }
    
    private fun refill() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefill.get()
        
        if (elapsed > 100) {
            lock.write {
                val newTokens = ((elapsed / 1000.0) * refillRate).toInt()
                tokens.set(min(capacity, tokens.get() + newTokens))
                lastRefill.set(now)
            }
        }
    }
    
    fun getAvailableTokens(): Int = tokens.get()
}

class ToolResultCache(
    private val maxEntries: Int = 1000,
    private val defaultTtlMs: Long = 300000
) {
    private data class CacheEntry(
        val result: RobustToolExecutor.ToolResult,
        val expiresAt: Long,
        val accessCount: AtomicInteger = AtomicInteger(0)
    )
    
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val hits = AtomicInteger(0)
    private val misses = AtomicInteger(0)
    private val lock = ReentrantReadWriteLock()
    
    fun get(request: RobustToolExecutor.ToolRequest): RobustToolExecutor.ToolResult? {
        val key = generateCacheKey(request)
        
        return lock.read {
            cache[key]?.let { entry ->
                if (System.currentTimeMillis() < entry.expiresAt) {
                    entry.accessCount.incrementAndGet()
                    hits.incrementAndGet()
                    entry.result
                } else {
                    lock.write { cache.remove(key) }
                    misses.incrementAndGet()
                    null
                }
            } ?: run {
                misses.incrementAndGet()
                null
            }
        }
    }
    
    fun put(request: RobustToolExecutor.ToolRequest, result: RobustToolExecutor.ToolResult) {
        if (!result.success) return
        
        val key = generateCacheKey(request)
        val ttl = result.metadata["cacheTtl"] as? Long ?: defaultTtlMs
        
        lock.write {
            if (cache.size >= maxEntries) {
                evictLeastUsed()
            }
            cache[key] = CacheEntry(
                result = result,
                expiresAt = System.currentTimeMillis() + ttl
            )
        }
    }
    
    private fun generateCacheKey(request: RobustToolExecutor.ToolRequest): String {
        val data = "${request.toolName}:${request.parameters.toSortedMap()}"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    private fun evictLeastUsed() {
        val leastUsed = cache.minByOrNull { it.value.accessCount.get() }
        leastUsed?.let { cache.remove(it.key) }
    }
    
    fun cleanup() {
        val now = System.currentTimeMillis()
        lock.write {
            cache.entries.removeIf { it.value.expiresAt < now }
        }
    }
    
    fun getHitRate(): Double {
        val total = hits.get() + misses.get()
        return if (total > 0) hits.get().toDouble() / total else 0.0
    }
    
    fun getStats(): CacheStats {
        return CacheStats(cache.size, hits.get(), getHitRate())
    }
    
    fun invalidateByTool(toolName: String) {
        lock.write {
            cache.keys.removeIf { it.startsWith("$toolName:") }
        }
    }
    
    fun invalidateByPattern(pattern: String) {
        lock.write {
            cache.keys.removeIf { it.contains(pattern) }
        }
    }
    
    fun clear() {
        lock.write { cache.clear() }
    }
}

class ToolDependencyGraph {
    private val adjList = ConcurrentHashMap<String, MutableList<String>>()
    private val reverseAdjList = ConcurrentHashMap<String, MutableList<String>>()
    private val lock = ReentrantReadWriteLock()
    
    fun addTool(tool: String, dependencies: List<String>) {
        lock.write {
            adjList.getOrPut(tool) { mutableListOf() }.clear()
            adjList[tool]?.addAll(dependencies)
            
            dependencies.forEach { dep ->
                reverseAdjList.getOrPut(dep) { mutableListOf() }.add(tool)
            }
        }
    }
    
    fun getDependencies(tool: String): List<String> {
        return lock.read { adjList[tool]?.toList() ?: emptyList() }
    }
    
    fun getDependents(tool: String): List<String> {
        return lock.read { reverseAdjList[tool]?.toList() ?: emptyList() }
    }
    
    fun getTopologicalOrder(): List<String>? {
        return lock.read {
            val inDegree = adjList.keys.associateWith { 0 }.toMutableMap()
            adjList.forEach { (_, deps) ->
                deps.forEach { dep ->
                    inDegree[dep] = (inDegree[dep] ?: 0) + 1
                }
            }
            
            val queue = ArrayDeque(inDegree.filter { it.value == 0 }.keys)
            val result = mutableListOf<String>()
            
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                result.add(node)
                
                adjList[node]?.forEach { neighbor ->
                    inDegree[neighbor] = (inDegree[neighbor] ?: 1) - 1
                    if (inDegree[neighbor] == 0) {
                        queue.add(neighbor)
                    }
                }
            }
            
            if (result.size != adjList.size) null else result
        }
    }
}

class PipelineExecutor(private val executor: RobustToolExecutor) {
    
    data class ToolPipeline(
        val id: String = UUID.randomUUID().toString(),
        val steps: List<PipelineStep>,
        val onError: PipelineErrorStrategy = PipelineErrorStrategy.STOP
    )
    
    data class PipelineStep(
        val toolName: String,
        val parameters: Map<String, Any> = emptyMap(),
        val inputMapping: Map<String, String> = emptyMap(),
        val outputKey: String? = null
    )
    
    enum class PipelineErrorStrategy { STOP, SKIP, RETRY }
    
    data class PipelineResult(
        val pipelineId: String,
        val success: Boolean,
        val outputs: Map<String, Any>,
        val errors: List<String>,
        val durationMs: Long
    )
    
    suspend fun execute(pipeline: ToolPipeline): PipelineResult {
        val startTime = System.currentTimeMillis()
        val outputs = mutableMapOf<String, Any>()
        val errors = mutableListOf<String>()
        
        for ((index, step) in pipeline.steps.withIndex()) {
            val params = resolveParameters(step, outputs)
            
            val request = RobustToolExecutor.ToolRequest(
                callerId = "pipeline-${pipeline.id}",
                toolName = step.toolName,
                userRequest = "",
                parameters = params,
                pipelineId = pipeline.id
            )
            
            val result = executor.execute(request)
            
            if (result.success) {
                step.outputKey?.let { key ->
                    outputs[key] = result.result ?: ""
                }
            } else {
                errors.add("Step $index (${step.toolName}): ${result.error}")
                
                when (pipeline.onError) {
                    PipelineErrorStrategy.STOP -> break
                    PipelineErrorStrategy.SKIP -> continue
                    PipelineErrorStrategy.RETRY -> {
                        val retryResult = executor.execute(request.copy(attempts = 2))
                        if (retryResult.success) {
                            step.outputKey?.let { key ->
                                outputs[key] = retryResult.result ?: ""
                            }
                        } else {
                            errors.add("Retry failed for step $index: ${retryResult.error}")
                        }
                    }
                }
            }
        }
        
        return PipelineResult(
            pipelineId = pipeline.id,
            success = errors.isEmpty(),
            outputs = outputs,
            errors = errors,
            durationMs = System.currentTimeMillis() - startTime
        )
    }
    
    private fun resolveParameters(step: PipelineStep, context: Map<String, Any>): Map<String, Any> {
        val resolved = mutableMapOf<String, Any>()
        
        step.parameters.forEach { (key, value) ->
            resolved[key] = resolveValue(value, context)
        }
        
        step.inputMapping.forEach { (target, source) ->
            context[source]?.let { resolved[target] = it }
        }
        
        return resolved
    }
    
    private fun resolveValue(value: Any, context: Map<String, Any>): Any {
        return when (value) {
            is String -> {
                if (value.startsWith("$") && value.length > 1) {
                    context[value.substring(1)] ?: value
                } else {
                    value
                }
            }
            is Map<*, *> -> value.mapValues { resolveValue(it.value ?: "", context) }
            is List<*> -> value.map { resolveValue(it ?: "", context) }
            else -> value
        }
    }
}

class DeadlockDetector {
    private val lock = ReentrantReadWriteLock()
    private val agentToolLocks = ConcurrentHashMap<String, MutableSet<String>>()
    
    fun wouldCauseDeadlock(agentId: String, toolName: String, graph: ToolDependencyGraph): Boolean {
        val agentLocks = lock.read { agentToolLocks[agentId]?.toSet() ?: emptySet() }
        
        if (toolName in agentLocks) return false
        
        val toolDeps = graph.getDependencies(toolName)
        
        for (dep in toolDeps) {
            if (dep in agentLocks) return true
            if (wouldCauseDeadlock(agentId, dep, graph)) return true
        }
        
        return false
    }
    
    fun acquireLock(agentId: String, toolName: String) {
        lock.write {
            agentToolLocks.getOrPut(agentId) { mutableSetOf() }.add(toolName)
        }
    }
    
    fun releaseLock(agentId: String, toolName: String) {
        lock.write {
            agentToolLocks[agentId]?.remove(toolName)
        }
    }
    
    fun detectCycles(graph: ToolDependencyGraph): List<List<String>> {
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        val cycles = mutableListOf<List<String>>()
        
        fun dfs(node: String, path: List<String>): Boolean {
            if (node in recursionStack) {
                val cycleStart = path.indexOf(node)
                if (cycleStart >= 0) {
                    cycles.add(path.subList(cycleStart, path.size) + node)
                }
                return true
            }
            
            if (node in visited) return false
            
            visited.add(node)
            recursionStack.add(node)
            
            for (dep in graph.getDependencies(node)) {
                if (dfs(dep, path + node)) return true
            }
            
            recursionStack.remove(node)
            return false
        }
        
        graph.getTopologicalOrder()?.forEach { node ->
            if (node !in visited) {
                dfs(node, emptyList())
            }
        }
        
        return cycles
    }
}

class BackpressureController(
    private val maxConcurrentResourceCost: Int = 100,
    private val threshold: Double = 0.8
) {
    private val currentUsage = AtomicInteger(0)
    private val lock = ReentrantReadWriteLock()
    private val requestQueue = ConcurrentLinkedQueue<QueuedRequest>()
    
    data class QueuedRequest(
        val request: RobustToolExecutor.ToolRequest,
        val enqueuedAt: Long,
        val priority: Int
    )
    
    fun shouldAccept(): Boolean {
        val usage = currentUsage.get()
        return usage < (maxConcurrentResourceCost * threshold)
    }
    
    fun reserveResources(cost: Int): Boolean {
        var retries = 0
        while (retries < 10) {
            val current = currentUsage.get()
            val newValue = current + cost
            
            if (newValue > maxConcurrentResourceCost) {
                Thread.sleep(50)
                retries++
            } else {
                return true
            }
        }
        return false
    }
    
    fun releaseResources(cost: Int) {
        currentUsage.addAndGet(-cost)
    }
    
    fun enqueueRequest(request: RobustToolExecutor.ToolRequest): Boolean {
        if (shouldAccept()) return true
        
        requestQueue.add(QueuedRequest(request, System.currentTimeMillis(), request.priority))
        
        return false
    }
    
    fun dequeueRequest(): RobustToolExecutor.ToolRequest? {
        val sorted = requestQueue.sortedByDescending { it.priority }
        return sorted.firstOrNull()?.let { requestQueue.remove(it)?.request }
    }
}

class TracingManager {
    private val traces = ConcurrentHashMap<String, TraceInfo>()
    private val lock = ReentrantReadWriteLock()
    
    data class TraceInfo(
        val traceId: String,
        val startTime: Long,
        var endTime: Long? = null,
        var spans: MutableList<SpanInfo> = mutableListOf(),
        var success: Boolean = true
    )
    
    data class SpanInfo(
        val spanId: String,
        val parentSpanId: String?,
        val operation: String,
        val startTime: Long,
        var endTime: Long? = null,
        var success: Boolean = true,
        val tags: MutableMap<String, String> = mutableMapOf()
    )
    
    fun startSpan(traceId: String, spanId: String, parentSpanId: String?): TraceInfo {
        return lock.write {
            traces.getOrPut(traceId) {
                TraceInfo(traceId, System.currentTimeMillis())
            }.let { trace ->
                trace.spans.add(SpanInfo(spanId, parentSpanId, "execute", System.currentTimeMillis()))
                trace
            }
        }
    }
    
    fun endSpan(traceId: String, success: Boolean) {
        lock.write {
            traces[traceId]?.let { trace ->
                trace.spans.lastOrNull()?.let { span ->
                    span.endTime = System.currentTimeMillis()
                    span.success = success
                }
                if (trace.endTime == null) {
                    trace.endTime = System.currentTimeMillis()
                    trace.success = success
                }
            }
        }
    }
    
    fun addTag(traceId: String, key: String, value: String) {
        lock.write {
            traces[traceId]?.spans?.lastOrNull()?.tags?.put(key, value)
        }
    }
    
    fun getTrace(traceId: String): TraceInfo? {
        return lock.read { traces[traceId]?.copy() }
    }
    
    fun cleanup() {
        val cutoff = System.currentTimeMillis() - (60 * 60 * 1000)
        lock.write {
            traces.entries.removeIf { it.value.endTime != null && it.value.endTime < cutoff }
        }
    }
}

class MetricsCollector {
    private val metrics = ConcurrentHashMap<String, MetricValue>()
    private val history = ConcurrentLinkedQueue<MetricSnapshot>()
    
    sealed class MetricValue {
        data class Counter(val value: AtomicLong = AtomicLong(0)) : MetricValue()
        data class Gauge(val value: AtomicLong = AtomicLong(0)) : MetricValue()
        data class Histogram(val values: MutableList<Long> = mutableListOf()) : MetricValue()
    }
    
    data class MetricSnapshot(
        val timestamp: Long,
        val activeExecutions: Int,
        val pendingRequests: Int,
        val totalTools: Int,
        val cacheHitRate: Double,
        val successRate: Double,
        val circuitBreakerStates: Map<String, String>
    )
    
    init {
        metrics["active_executions"] = MetricValue.Gauge()
        metrics["pending_requests"] = MetricValue.Gauge()
        metrics["total_tools"] = MetricValue.Gauge()
        metrics["cache_hits"] = MetricValue.Counter()
        metrics["cache_misses"] = MetricValue.Counter()
        metrics["success_count"] = MetricValue.Counter()
        metrics["failure_count"] = MetricValue.Counter()
        metrics["execution_times"] = MetricValue.Histogram()
    }
    
    fun record(vararg values: Pair<String, Any>) {
        values.forEach { (key, value) ->
            when (val metric = metrics[key]) {
                is MetricValue.Gauge -> {
                    (value as? Number)?.let { metric.value.set(it.toLong()) }
                }
                is MetricValue.Counter -> {
                    (value as? Number)?.let { metric.value.addAndGet(it.toLong()) }
                }
                is MetricValue.Histogram -> {
                    (value as? Long)?.let { 
                        synchronized(metric.values) { 
                            metric.values.add(it)
                            if (metric.values.size > 1000) metric.values.removeAt(0)
                        }
                    }
                }
                null -> {}
            }
        }
    }
    
    fun recordCacheHit() {
        (metrics["cache_hits"] as? MetricValue.Counter)?.value?.incrementAndGet()
    }
    
    fun recordSuccessRate(rate: Double) {
        (metrics["success_rate"] as? MetricValue.Gauge)?.value?.set((rate * 100).toLong())
    }
    
    fun getAllMetrics(): Map<String, Any> {
        return metrics.mapValues { (_, value) ->
            when (value) {
                is MetricValue.Counter -> value.value.get()
                is MetricValue.Gauge -> value.value.get()
                is MetricValue.Histogram -> {
                    synchronized(value.values) {
                        if (value.values.isEmpty()) emptyMap<String, Any>()
                        else mapOf(
                            "count" to value.values.size,
                            "min" to value.values.minOrNull() ?: 0,
                            "max" to value.values.maxOrNull() ?: 0,
                            "avg" to value.values.average(),
                            "p50" to percentile(50),
                            "p95" to percentile(95),
                            "p99" to percentile(99)
                        )
                    }
                }
            }
        }
    }
    
    private fun percentile(p: Int): Long {
        val hist = (metrics["execution_times"] as? MetricValue.Histogram)?.values ?: return 0
        return synchronized(hist) {
            if (hist.isEmpty()) 0L
            else {
                val sorted = hist.sorted()
                val index = (p * sorted.size / 100)
                sorted[index]
            }
        }
    }
    
    fun snapshot(
        activeExecutions: Int,
        pendingRequests: Int,
        totalTools: Int,
        cacheHitRate: Double,
        circuitBreakerStates: Map<String, String>
    ) {
        history.offer(MetricSnapshot(
            timestamp = System.currentTimeMillis(),
            activeExecutions = activeExecutions,
            pendingRequests = pendingRequests,
            totalTools = totalTools,
            cacheHitRate = cacheHitRate,
            successRate = 0.0,
            circuitBreakerStates = circuitBreakerStates
        ))
        
        if (history.size > 1000) {
            val removed = history.poll()
        }
    }
}

class TimeoutOptimizer {
    private val toolStats = ConcurrentHashMap<String, ToolTimeoutStats>()
    private val lock = ReentrantReadWriteLock()
    
    data class ToolTimeoutStats(
        val toolName: String,
        val samples: MutableList<Long> = mutableListOf(),
        val failures: MutableList<Long> = mutableListOf(),
        var optimizedTimeout: Long = 30000,
        var p95: Long = 30000,
        var failureRate: Double = 0.0
    )
    
    fun updateFromHistory(history: List<ExecutionEntry>) {
        val byTool = history.groupBy { it.toolName }
        
        byTool.forEach { (tool, entries) ->
            lock.write {
                val stats = toolStats.getOrPut(tool) { ToolTimeoutStats(tool) }
                
                entries.forEach { entry ->
                    if (entry.success) {
                        synchronized(stats.samples) { stats.samples.add(entry.durationMs) }
                    } else {
                        synchronized(stats.failures) { stats.failures.add(entry.durationMs) }
                    }
                }
                
                updateStats(stats)
            }
        }
    }
    
    private fun updateStats(stats: ToolTimeoutStats) {
        val samples = synchronized(stats.samples) { stats.samples.toList() }
        val failures = synchronized(stats.failures) { stats.failures.toList() }
        
        if (samples.isNotEmpty()) {
            stats.p95 = calculatePercentile(samples, 95)
            stats.optimizedTimeout = (stats.p95 * 1.2).toLong().coerceAtLeast(5000).coerceAtMost(120000)
        }
        
        val total = samples.size + failures.size
        stats.failureRate = if (total > 0) failures.size.toDouble() / total else 0.0
        
        if (stats.failureRate > 0.5) {
            stats.optimizedTimeout = (stats.optimizedTimeout * 0.8).toLong().coerceAtLeast(5000)
        }
    }
    
    private fun calculatePercentile(list: List<Long>, percentile: Int): Long {
        if (list.isEmpty()) return 30000
        val sorted = list.sorted()
        val index = (percentile * sorted.size / 100) - 1
        return sorted[index.coerceIn(0, sorted.lastIndex)]
    }
    
    fun getOptimizedTimeout(toolName: String): Long {
        return lock.read {
            toolStats[toolName]?.optimizedTimeout ?: 30000
        }
    }
    
    fun recordExecution(toolName: String, durationMs: Long, success: Boolean) {
        lock.write {
            val stats = toolStats.getOrPut(toolName) { ToolTimeoutStats(toolName) }
            
            if (success) {
                synchronized(stats.samples) {
                    stats.samples.add(durationMs)
                    if (stats.samples.size > 100) stats.samples.removeAt(0)
                }
            } else {
                synchronized(stats.failures) {
                    stats.failures.add(durationMs)
                    if (stats.failures.size > 100) stats.failures.removeAt(0)
                }
            }
            
            updateStats(stats)
        }
    }
}

class SecurityValidator {
    private val blockedPatterns = listOf(
        "exec", "eval", "system", "runtime", "process", 
        "shutdown", "exit", "kill", "terminate"
    )
    
    private val parameterValidators = mapOf(
        "command" to { v: Any -> validateCommand(v) },
        "script" to { v: Any -> validateScript(v) },
        "path" to { v: Any -> validatePath(v) }
    )
    
    data class ValidationResult(val valid: Boolean, val reason: String? = null)
    
    fun validate(request: RobustToolExecutor.ToolRequest): ValidationResult {
        if (request.userRequest.isBlank() && request.parameters.isEmpty()) {
            return ValidationResult(false, "Empty request")
        }
        
        if (containsBlockedContent(request.userRequest)) {
            return ValidationResult(false, "Request contains blocked content")
        }
        
        for ((key, value) in request.parameters) {
            parameterValidators[key]?.let { validator ->
                val result = validator(value)
                if (!result.valid) return result
            }
            
            if (value is String && containsBlockedContent(value)) {
                return ValidationResult(false, "Parameter '$key' contains blocked content")
            }
        }
        
        return ValidationResult(true)
    }
    
    private fun containsBlockedContent(input: String): Boolean {
        val lower = input.lowercase()
        return blockedPatterns.any { lower.contains(it) }
    }
    
    private fun validateCommand(value: Any): ValidationResult {
        val str = value.toString()
        if (str.contains(";") || str.contains("|") || str.contains("&") || str.contains("$")) {
            return ValidationResult(false, "Command contains forbidden characters")
        }
        return ValidationResult(true)
    }
    
    private fun validateScript(value: Any): ValidationResult {
        val str = value.toString().lowercase()
        if (str.contains("eval") || str.contains("exec") || str.contains("import")) {
            return ValidationResult(false, "Script contains forbidden operations")
        }
        return ValidationResult(true)
    }
    
    private fun validatePath(value: Any): ValidationResult {
        val str = value.toString()
        if (str.contains("..") || str.startsWith("/etc") || str.startsWith("/proc")) {
            return ValidationResult(false, "Path contains forbidden traversal")
        }
        return ValidationResult(true)
    }
}

class ToolLoadBalancer {
    private val instanceHealth = ConcurrentHashMap<String, InstanceHealth>()
    private val lock = ReentrantReadWriteLock()
    
    data class InstanceHealth(
        val instanceId: String,
        var successRate: Double = 1.0,
        var avgLatency: Long = 0,
        var currentLoad: Int = 0,
        var isHealthy: Boolean = true
    )
    
    fun selectInstance(toolName: String, instances: List<String>): String? {
        return lock.read {
            instances.filter { instanceHealth[it]?.isHealthy ?: true }
                .minByOrNull { instanceHealth[it]?.currentLoad ?: 0 }
        }
    }
    
    fun recordSuccess(instanceId: String, latencyMs: Long) {
        lock.write {
            val health = instanceHealth.getOrPut(instanceId) { InstanceHealth(instanceId) }
            health.currentLoad = max(0, health.currentLoad - 1)
            health.successRate = min(1.0, health.successRate + 0.01)
            health.avgLatency = ((health.avgLatency * 0.9) + (latencyMs * 0.1)).toLong()
        }
    }
    
    fun recordFailure(instanceId: String) {
        lock.write {
            val health = instanceHealth.getOrPut(instanceId) { InstanceHealth(instanceId) }
            health.currentLoad = max(0, health.currentLoad - 1)
            health.successRate = max(0.0, health.successRate - 0.1)
            
            if (health.successRate < 0.5) {
                health.isHealthy = false
            }
        }
    }
    
    fun markHealthy(instanceId: String) {
        lock.write {
            instanceHealth[instanceId]?.isHealthy = true
        }
    }
}

class HotReloadManager {
    private val handlers = ConcurrentHashMap<String, suspend (Map<String, Any>) -> RobustToolExecutor.ToolResponse>()
    private val reloadListeners = ConcurrentHashMap<String, MutableList<ReloadListener>>()
    
    interface ReloadListener {
        fun onReload(toolName: String)
    }
    
    fun register(toolName: String, handler: suspend (Map<String, Any>) -> RobustToolExecutor.ToolResponse) {
        handlers[toolName] = handler
    }
    
    fun reload(toolName: String, newHandler: suspend (Map<String, Any>) -> RobustToolExecutor.ToolResponse) {
        handlers[toolName] = newHandler
        reloadListeners[toolName]?.forEach { it.onReload(toolName) }
    }
    
    fun getHandler(toolName: String): suspend (Map<String, Any>) -> RobustToolExecutor.ToolResponse? {
        return handlers[toolName]
    }
    
    fun addListener(toolName: String, listener: ReloadListener) {
        reloadListeners.getOrPut(toolName) { mutableListOf() }.add(listener)
    }
}

class RequestRouter {
    private val tools = ConcurrentHashMap<String, ToolDefinition>()
    private val keywords = ConcurrentHashMap<String, MutableList<Pair<String, Double>>>()
    private val semanticIndex = SemanticIndex()
    
    fun register(definition: ToolDefinition) {
        tools[definition.name] = definition
        
        val kw = extractKeywords(definition)
        for ((word, weight) in kw) {
            keywords.getOrPut(word) { mutableListOf() }.add(definition.name to weight)
        }
        
        semanticIndex.addTool(definition.name, definition.description)
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
        
        val semanticScores = semanticIndex.search(request)
        semanticScores.forEach { (tool, score) ->
            scores[tool] = (scores[tool] ?: 0.0) + score * 0.5
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

class SemanticIndex {
    private val toolEmbeddings = ConcurrentHashMap<String, List<Double>>()
    private val lock = ReentrantReadWriteLock()
    
    fun addTool(name: String, description: String) {
        lock.write {
            toolEmbeddings[name] = simpleEmbed(description)
        }
    }
    
    fun search(query: String): Map<String, Double> {
        val queryEmbed = simpleEmbed(query)
        
        return lock.read {
            toolEmbeddings.mapValues { (_, embed) ->
                cosineSimilarity(queryEmbed, embed)
            }.filter { it.value > 0.3 }
                .toList()
                .sortedByDescending { it.second }
                .take(5)
                .associate { it }
        }
    }
    
    private fun simpleEmbed(text: String): List<Double> {
        val words = text.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        val vocab = words.distinct().take(100)
        return vocab.map { word ->
            if (text.contains(word)) 1.0 else 0.0
        }
    }
    
    private fun cosineSimilarity(a: List<Double>, b: List<Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        
        val len = min(a.size, b.size)
        val aSub = a.take(len)
        val bSub = b.take(len)
        
        val dot = aSub.zip(bSub).sumOf { it.first * it.second }
        val magA = sqrt(aSub.sumOf { it * it })
        val magB = sqrt(bSub.sumOf { it * it })
        
        return if (magA > 0 && magB > 0) dot / (magA * magB) else 0.0
    }
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
