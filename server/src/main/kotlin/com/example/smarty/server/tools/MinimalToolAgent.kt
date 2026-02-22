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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

class ToolExecutorAgent(
    private val llmProvider: LlmProvider? = null
) {
    private val logger = LoggerFactory.getLogger(ToolExecutorAgent::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executionMutex = Mutex()
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(4)
    
    private val toolImplementations = ConcurrentHashMap<String, ToolHandler>()
    private val toolVersions = ConcurrentHashMap<String, MutableMap<Int, ToolHandler>>()
    private val router = AdvancedToolRouter()
    private val paramExtractor = IntelligentParameterExtractor()
    
    private val executionHistory = ConcurrentLinkedQueue<ExecutionEntry>()
    private val activeExecutions = ConcurrentHashMap<String, ActiveExecution>()
    private val pendingCallbacks = ConcurrentHashMap<String, CallbackInfo>()
    
    private val circuitBreakers = ConcurrentHashMap<String, CircuitBreakerState>()
    private val rateLimiters = ConcurrentHashMap<String, RateLimiter>()
    private val toolMetrics = ConcurrentHashMap<String, ToolMetrics>()
    
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
        val error: String?,
        val confidence: Double = 0.0,
        val retryCount: Int = 0
    )
    
    data class ActiveExecution(
        val id: String,
        val toolName: String,
        val startTime: Long,
        var params: Map<String, Any>,
        var result: Any? = null,
        var error: String? = null,
        var retryCount: Int = 0
    )
    
    data class CallbackInfo(
        val callbackId: String,
        val callerId: String,
        val toolName: String,
        val createdAt: Long,
        var result: Any? = null,
        var completed: Boolean = false
    )
    
    data class CircuitBreakerState(
        val toolName: String,
        var failureCount: Int = 0,
        var lastFailureTime: Long = 0,
        var state: CircuitState = CircuitState.CLOSED,
        var halfOpenAttempts: Int = 0
    )
    
    enum class CircuitState { CLOSED, OPEN, HALF_OPEN }
    
    data class RateLimiter(
        val capacity: Int,
        val refillRate: Double,
        var tokens: Int = capacity,
        var lastRefill: Long = System.currentTimeMillis()
    )
    
    data class ToolMetrics(
        val toolName: String,
        var totalExecutions: Long = 0,
        var successCount: Long = 0,
        var failureCount: Long = 0,
        var totalDuration: Long = 0,
        var avgConfidence: Double = 0.0,
        var lastExecutionTime: Long = 0
    )
    
    init {
        startHealthCheck()
        startCircuitBreakerRecovery()
        startMetricsCollection()
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
    
    private fun startCircuitBreakerRecovery() {
        executor.scheduleAtFixedRate({
            circuitBreakers.values.forEach { cb ->
                if (cb.state == CircuitState.OPEN) {
                    val elapsed = System.currentTimeMillis() - cb.lastFailureTime
                    if (elapsed > 60000) {
                        cb.state = CircuitState.HALF_OPEN
                        cb.halfOpenAttempts = 0
                    }
                }
            }
        }, 30, 30, TimeUnit.SECONDS)
    }
    
    private fun startMetricsCollection() {
        executor.scheduleAtFixedRate({
            toolMetrics.values.forEach { metric ->
                if (metric.totalExecutions > 0) {
                    metric.avgConfidence = metric.avgConfidence * 0.95
                }
            }
        }, 60, 60, TimeUnit.SECONDS)
    }
    
    fun registerTool(handler: ToolHandler, version: Int = 1) {
        val definition = handler.getToolDefinition()
        toolImplementations[definition.name] = handler
        toolVersions.getOrPut(definition.name) { mutableMapOf() }[version] = handler
        router.registerTool(definition)
        
        circuitBreakers[definition.name] = CircuitBreakerState(definition.name)
        rateLimiters[definition.name] = RateLimiter(100, 10.0)
        toolMetrics[definition.name] = ToolMetrics(definition.name)
        
        logger.info("Registered tool: ${definition.name} v$version")
    }
    
    fun registerTool(
        name: String,
        description: String,
        parameters: Map<String, ToolProperty>,
        required: List<String>,
        handler: suspend (Map<String, Any>) -> ToolResponse,
        version: Int = 1
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
        
        registerTool(toolHandler, version)
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
            
            val circuitBreaker = circuitBreakers[toolName]
            if (circuitBreaker != null && circuitBreaker.state == CircuitState.OPEN) {
                return@withLock ExecutionResult(
                    success = false,
                    toolName = toolName,
                    result = null,
                    error = "Circuit breaker is open for $toolName",
                    executionId = executionId,
                    confidence = routingDecision.confidence,
                    routingReasoning = "Circuit breaker blocked"
                )
            }
            
            val rateLimiter = rateLimiters[toolName]
            if (rateLimiter != null && !tryConsumeToken(rateLimiter)) {
                return@withLock ExecutionResult(
                    success = false,
                    toolName = toolName,
                    result = null,
                    error = "Rate limit exceeded for $toolName",
                    executionId = executionId,
                    confidence = routingDecision.confidence,
                    routingReasoning = "Rate limited"
                )
            }
            
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
                
                updateMetrics(toolName, response.success, duration, routingDecision.confidence)
                updateCircuitBreaker(toolName, response.success)
                
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
                    error = response.error,
                    confidence = routingDecision.confidence
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
                
                updateMetrics(toolName, false, duration, routingDecision.confidence)
                updateCircuitBreaker(toolName, false)
                
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
    
    private fun tryConsumeToken(limiter: RateLimiter): Boolean {
        val now = System.currentTimeMillis()
        val elapsed = now - limiter.lastRefill
        
        if (elapsed > 1000) {
            val refill = ((elapsed / 1000.0) * limiter.refillRate).toInt()
            limiter.tokens = min(limiter.capacity, limiter.tokens + refill)
            limiter.lastRefill = now
        }
        
        return if (limiter.tokens > 0) {
            limiter.tokens--
            true
        } else false
    }
    
    private fun updateMetrics(toolName: String, success: Boolean, duration: Long, confidence: Double) {
        val metrics = toolMetrics[toolName] ?: return
        metrics.totalExecutions++
        if (success) metrics.successCount++ else metrics.failureCount++
        metrics.totalDuration += duration
        metrics.avgConfidence = metrics.avgConfidence * 0.99 + confidence * 0.01
        metrics.lastExecutionTime = System.currentTimeMillis()
    }
    
    private fun updateCircuitBreaker(toolName: String, success: Boolean) {
        val cb = circuitBreakers[toolName] ?: return
        
        when (cb.state) {
            CircuitState.CLOSED -> {
                if (!success) {
                    cb.failureCount++
                    cb.lastFailureTime = System.currentTimeMillis()
                    if (cb.failureCount >= 5) {
                        cb.state = CircuitState.OPEN
                    }
                } else {
                    cb.failureCount = 0
                }
            }
            CircuitState.HALF_OPEN -> {
                if (success) {
                    cb.halfOpenAttempts++
                    if (cb.halfOpenAttempts >= 3) {
                        cb.state = CircuitState.CLOSED
                        cb.failureCount = 0
                    }
                } else {
                    cb.state = CircuitState.OPEN
                    cb.lastFailureTime = System.currentTimeMillis()
                }
            }
            CircuitState.OPEN -> {}
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
    
    fun getToolDefinitions(): List<ToolDefinition> = router.getAllDefinitions()
    fun getAvailableTools(): List<String> = toolImplementations.keys.toList()
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

class AdvancedToolRouter {
    private val tools = ConcurrentHashMap<String, ToolDefinition>()
    private val keywords = ConcurrentHashMap<String, MutableList<Pair<String, Double>>>()
    private val semanticIndex = SemanticMemory()
    private val contextAnalyzers = mutableListOf<ContextAnalyzer>()
    
    init {
        contextAnalyzers.add(IntentAnalyzer())
        contextAnalyzers.add(EntityExtractor())
    }
    
    fun registerTool(definition: ToolDefinition) {
        tools[definition.name] = definition
        
        val toolKeywords = extractKeywords(definition)
        for ((keyword, weight) in toolKeywords) {
            keywords.getOrPut(keyword) { mutableListOf() }.add(definition.name to weight)
        }
        
        semanticIndex.index(definition.name, definition.description)
    }
    
    private fun extractKeywords(definition: ToolDefinition): List<Pair<String, Double>> {
        val kw = mutableListOf<Pair<String, Double>>()
        
        kw.add(definition.name.lowercase() to 1.0)
        
        val desc = definition.description.lowercase()
        
        val actionWords = listOf(
            "search" to 0.8, "save" to 0.8, "find" to 0.8, "get" to 0.6,
            "create" to 0.7, "delete" to 0.7, "update" to 0.7, "edit" to 0.7,
            "list" to 0.6, "show" to 0.6, "display" to 0.6,
            "calculate" to 0.8, "analyze" to 0.8, "compare" to 0.7,
            "convert" to 0.8, "transform" to 0.7, "fetch" to 0.7,
            "retrieve" to 0.7, "store" to 0.8, "compute" to 0.8
        )
        
        for ((action, weight) in actionWords) {
            if (desc.contains(action)) {
                kw.add(action to weight)
            }
        }
        
        return kw.distinctBy { it.first }
    }
    
    fun route(request: String): RoutingDecision {
        val context = contextAnalyzers.map { it.analyze(request) }
        
        val requestLower = request.lowercase()
        val requestWords = requestLower.split(Regex("\\W+")).filter { it.length > 2 }
        
        val scores = mutableMapOf<String, Double>()
        
        for ((keyword, toolWeights) in keywords) {
            for (word in requestWords) {
                if (keyword.contains(word) || word.contains(keyword)) {
                    for ((toolName, keywordWeight) in toolWeights) {
                        val baseScore = keywordWeight
                        val contextBonus = context.sumOf { it.getScore(toolName) }
                        scores[toolName] = (scores[toolName] ?: 0.0) + baseScore + contextBonus
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
        
        val semanticScores = semanticIndex.search(request)
        semanticScores.forEach { (tool, score) ->
            scores[tool] = (scores[tool] ?: 0.0) + score * 0.4
        }
        
        val sorted = scores.entries.sortedByDescending { it.value }
        val selected = sorted.firstOrNull()
        
        val confidence = calculateConfidence(scores, selected)
        
        return RoutingDecision(
            toolName = selected?.key,
            confidence = confidence,
            alternativeTools = sorted.drop(1).take(3).map { it.key },
            reasoning = generateReasoning(request, selected, sorted, context),
            metadata = mapOf(
                "context" to context,
                "semanticMatches" to semanticScores
            )
        )
    }
    
    private fun calculateConfidence(scores: Map<String, Double>, selected: Map.Entry<String, Double>?): Double {
        if (selected == null) return 0.0
        
        val maxScore = scores.values.maxOrNull() ?: 1.0
        val secondBest = scores.values.sorted().drop(1).lastOrNull() ?: 0.0
        
        val normalizedScore = selected.value / maxScore
        val margin = max(0.0, selected.value - secondBest) / maxScore
        
        return (normalizedScore * 0.7 + margin * 0.3).coerceIn(0.0, 1.0)
    }
    
    private fun generateReasoning(
        request: String,
        selected: Map.Entry<String, Double>?,
        all: List<Map.Entry<String, Double>>,
        context: List<ContextAnalysis>
    ): String {
        val parts = mutableListOf<String>()
        
        selected?.let {
            parts.add("Routed '${request.take(40)}...' to '${it.key}'")
            parts.add("Score: ${"%.2f".format(it.value)}")
        }
        
        if (context.isNotEmpty()) {
            val intents = context.filterIsInstance<IntentAnalysis>().firstOrNull()
            intents?.let {
                if (it.intent != null) {
                    parts.add("Intent: ${it.intent}")
                }
            }
        }
        
        return parts.joinToString(" | ")
    }
    
    fun getDefinition(name: String): ToolDefinition? = tools[name]
    fun getAllDefinitions(): List<ToolDefinition> = tools.values.toList()
    fun listTools(): List<String> = tools.keys.toList()
}

interface ContextAnalyzer {
    fun analyze(request: String): ContextAnalysis
}

data class ContextAnalysis(
    val type: String,
    val data: Map<String, Any> = emptyMap()
) {
    fun getScore(toolName: String): Double = data["score_$toolName"] as? Double ?: 0.0
}

class IntentAnalyzer : ContextAnalyzer {
    private val intentPatterns = mapOf(
        "search" to listOf("search", "find", "look", "get", "fetch", "retrieve"),
        "create" to listOf("create", "add", "new", "make", "save"),
        "update" to listOf("update", "edit", "modify", "change", "alter"),
        "delete" to listOf("delete", "remove", "clear", "erase"),
        "list" to listOf("list", "show", "display", "get all", "what do i have"),
        "compute" to listOf("calculate", "compute", "convert", "how many", "what is")
    )
    
    override fun analyze(request: String): ContextAnalysis {
        val lower = request.lowercase()
        val detected = mutableMapOf<String, Double>()
        
        for ((intent, keywords) in intentPatterns) {
            val matchCount = keywords.count { lower.contains(it) }
            if (matchCount > 0) {
                detected[intent] = matchCount.toDouble() / keywords.size
            }
        }
        
        return IntentAnalysis(
            intent = detected.maxByOrNull { it.value }?.key,
            confidence = detected.values.maxOrNull() ?: 0.0,
            allIntents = detected
        )
    }
}

data class IntentAnalysis(
    val intent: String?,
    val confidence: Double,
    val allIntents: Map<String, Double>
) : ContextAnalysis("intent", mapOf("intent" to intent, "confidence" to confidence))

class EntityExtractor : ContextAnalyzer {
    private val entityPatterns = mapOf(
        "time" to Regex("""\d+\s*(second|minute|hour|day|week|month|year)s?\b""", RegexOption.IGNORE_CASE),
        "number" to Regex("""\d+(\.\d+)?"""),
        "date" to Regex("""\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|\w+\s+\d{1,2}(st|nd|rd|th)?""", RegexOption.IGNORE_CASE),
        "query" to Regex("""["']([^"']+)["']""")
    )
    
    override fun analyze(request: String): ContextAnalysis {
        val entities = mutableMapOf<String, Any>()
        
        for ((type, pattern) in entityPatterns) {
            val matches = pattern.findAll(request).map { it.value }.toList()
            if (matches.isNotEmpty()) {
                entities[type] = if (matches.size == 1) matches[0] else matches
            }
        }
        
        return EntityExtraction(entities)
    }
}

data class EntityExtraction(
    val entities: Map<String, Any>
) : ContextAnalysis("entity", entities)

class SemanticMemory {
    private val index = ConcurrentHashMap<String, List<String>>()
    private val vectors = ConcurrentHashMap<String, List<Double>>()
    
    fun index(toolName: String, description: String) {
        val tokens = description.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        index[toolName] = tokens
        
        val vector = tokens.distinct().map { token ->
            token.hashCode().toDouble() / Int.MAX_VALUE
        }
        vectors[toolName] = vector
    }
    
    fun search(query: String): Map<String, Double> {
        val queryTokens = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        
        val results = mutableMapOf<String, Double>()
        
        for ((tool, tokens) in index) {
            val overlap = queryTokens.intersect(tokens.toSet()).size
            val jaccard = if (queryTokens.isNotEmpty() && tokens.isNotEmpty()) {
                overlap.toDouble() / (queryTokens.size + tokens.size - overlap)
            } else 0.0
            
            if (overlap > 0) {
                results[tool] = jaccard
            }
        }
        
        return results.entries.sortedByDescending { it.value }.take(5).associate { it.toPair() }
    }
}

data class RoutingDecision(
    val toolName: String?,
    val confidence: Double,
    val alternativeTools: List<String>,
    val reasoning: String,
    val metadata: Map<String, Any> = emptyMap()
)

class IntelligentParameterExtractor {
    private val extractors = mapOf(
        "note" to NoteParameterExtractor(),
        "search" to SearchParameterExtractor(),
        "event" to EventParameterExtractor(),
        "timer" to TimerParameterExtractor(),
        "fact" to FactParameterExtractor()
    )
    
    fun extract(request: String, toolName: String, definition: ToolDefinition?): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        
        val extractor = extractors.entries.firstOrNull { toolName.contains(it.key, ignoreCase = true) }
            ?: extractors["search"]
        
        extractor?.value?.extract(request, params)
        
        val spec = definition?.parameters
        if (spec != null) {
            extractGenericParams(request, params, spec)
        }
        
        return params
    }
    
    private fun extractGenericParams(
        request: String,
        params: MutableMap<String, Any>,
        spec: ToolParameters
    ) {
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

class NoteParameterExtractor {
    fun extract(request: String, params: MutableMap<String, Any>) {
        val titlePatterns = listOf(
            Regex("""title[:\s]+["']?([^"']+)["']?"""),
            Regex("""save\s+(?:a\s+)?note\s+(?:called\s+)?["']?([^"']+)["']?"""),
            Regex("""note\s+["']?([^"']+)["']?""")
        )
        
        for (pattern in titlePatterns) {
            pattern.find(request)?.groupValues?.get(1)?.trim()?.let {
                params["title"] = it
                return@forEach
            }
        }
        
        val contentPatterns = listOf(
            Regex("""content[:\s]+["']?([^"']+)["']?"""),
            Regex("""with\s+(?:the\s+)?(?:content\s+)?["']?([^"']+)["']?"""),
            Regex("""saying\s+["']?([^"']+)["']?"""),
            Regex(""":\s*["']?([^"']+)["']?\s*(?:and|$)""")
        )
        
        for (pattern in contentPatterns) {
            pattern.find(request)?.groupValues?.get(1)?.trim()?.let {
                if (!params.containsKey("content")) {
                    params["content"] = it
                }
            }
        }
        
        if (!params.containsKey("title") && !params.containsKey("content")) {
            val afterNote = request.substringAfter("note").substringAfter("save").trim()
            if (afterNote.isNotEmpty()) {
                params["content"] = afterNote.take(200)
                params["title"] = "Note ${System.currentTimeMillis() % 10000}"
            }
        }
        
        val requestLower = request.lowercase()
        params["category"] = when {
            requestLower.contains("work") || requestLower.contains("job") -> "work"
            requestLower.contains("personal") || requestLower.contains("home") -> "personal"
            requestLower.contains("idea") -> "ideas"
            else -> "general"
        }
    }
}

class SearchParameterExtractor {
    fun extract(request: String, params: MutableMap<String, Any>) {
        val patterns = listOf(
            Regex("""search\s+(?:for\s+)?["']?([^"']+)["']?""", RegexOption.IGNORE_CASE),
            Regex("""find\s+(?:information\s+)?(?:about\s+)?["']?([^"']+)["']?""", RegexOption.IGNORE_CASE),
            Regex("""(?:look|get)\s+up\s+["']?([^"']+)["']?""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            pattern.find(request)?.groupValues?.get(1)?.trim()?.let {
                params["query"] = it
                return@forEach
            }
        }
        
        val after = request.substringAfter("search", request.substringAfter("find")).trim()
        if (after.isNotEmpty()) {
            params["query"] = after
        }
    }
}

class EventParameterExtractor {
    fun extract(request: String, params: MutableMap<String, Any>) {
        val titlePatterns = listOf(
            Regex("""event\s+["']?([^"']+)["']?"""),
            Regex("""(?:add|create)\s+(?:an?\s+)?event\s+["']?([^"']+)["']?""")
        )
        
        for (pattern in titlePatterns) {
            pattern.find(request)?.groupValues?.get(1)?.trim()?.let {
                params["title"] = it
                return@forEach
            }
        }
        
        val timePatterns = listOf(
            Regex("""(?:at|on)\s+(["']?[^\s'"](?:(?!\s(?:and|for|with).).)*?["']?(?:\s+(?:am|pm))?)""", RegexOption.IGNORE_CASE),
            Regex("""when\s+["']?([^"']+)["']?""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in timePatterns) {
            pattern.find(request)?.groupValues?.get(1)?.trim()?.let {
                params["when"] = it
                return@forEach
            }
        }
        
        val requestLower = request.lowercase()
        if (requestLower.contains("hour") || requestLower.contains("minute")) {
            Regex("""(\d+)\s*(hour|hr|h|minute|min|m)""", RegexOption.IGNORE_CASE)
                .find(request)?.let {
                    params["duration"] = "${it.groupValues[1]} ${it.groupValues[2]}"
                }
        }
    }
}

class TimerParameterExtractor {
    fun extract(request: String, params: MutableMap<String, Any>) {
        Regex("""(\d+)\s*(hour|hr|h|minute|min|m|second|sec|s)""", RegexOption.IGNORE_CASE)
            .find(request)?.let {
                params["duration"] = "${it.groupValues[1]} ${it.groupValues[2]}"
            }
        
        Regex("""remind\s+me\s+to\s+["']?([^"']+)["']?""", RegexOption.IGNORE_CASE)
            .find(request)?.groupValues?.get(1)?.trim()?.let {
                params["message"] = it
            }
    }
}

class FactParameterExtractor {
    fun extract(request: String, params: MutableMap<String, Any>) {
        Regex("""remember\s+(?:that\s+)?["']?([^"']+)["']?""", RegexOption.IGNORE_CASE)
            .find(request)?.groupValues?.get(1)?.trim()?.let {
                params["fact"] = it
            }
        
        val requestLower = request.lowercase()
        params["type"] = when {
            requestLower.contains("preference") || requestLower.contains("like") || requestLower.contains("dislike") -> "preference"
            requestLower.contains("episodic") || requestLower.contains("happened") || requestLower.contains("went") -> "episodic"
            else -> "factual"
        }
    }
}

class ReasoningAgent(
    private val llmProvider: LlmProvider,
    private val toolExecutor: ToolExecutorAgent
) {
    private val logger = LoggerFactory.getLogger(ReasoningAgent::class.java)
    
    private val systemPrompt = buildAdvancedSystemPrompt()
    private val conversationContext = ConversationMemory()
    private val beliefRevision = BeliefRevisionSystem()
    private val selfReflection = SelfReflectionEngine()
    private val theoryOfMind = TheoryOfMindEngine()
    private val planningEngine = HTNPlanner()
    
    private val agentPersonality = AgentPersonality()
    private val cognitiveState = CognitiveState()
    
    suspend fun process(userInput: String): AgentResponse {
        val userIntent = analyzeUserIntent(userInput)
        
        theoryOfMind.updateUserModel(userInput, userIntent)
        
        val relevantBeliefs = beliefRevision.retrieveRelevant(userInput)
        
        conversationContext.addMessage("user", userInput)
        
        val augmentedContext = augmentContextWithBeliefs(userInput, relevantBeliefs)
        
        val messages = buildMessageList(augmentedContext)
        
        val response = llmProvider.chat(messages)
        
        val toolExecution = extractToolExecution(response)
        
        if (toolExecution != null) {
            conversationContext.addMessage("assistant", response)
            
            val result = toolExecutor.execute(
                ToolRequest(
                    userRequest = toolExecution,
                    context = userInput
                )
            )
            
            beliefRevision.processToolResult(toolExecution, result)
            
            val formattedResult = formatToolResult(result)
            conversationContext.addMessage("tool", formattedResult)
            
            val finalMessages = messages + listOf(
                com.example.smarty.server.llm.LlmMessage("assistant", response),
                com.example.smarty.server.llm.LlmMessage("tool", formattedResult)
            )
            
            val finalResponse = llmProvider.chat(finalMessages)
            
            conversationContext.addMessage("assistant", finalResponse)
            
            selfReflection.reflect(response, result, userInput)
            
            return AgentResponse(
                response = finalResponse,
                toolExecuted = true,
                toolName = result.toolName,
                toolResult = result.result,
                toolError = result.error,
                executionId = result.executionId,
                metadata = mapOf(
                    "intent" to userIntent,
                    "beliefsUpdated" to relevantBeliefs.size,
                    "confidence" to result.confidence
                )
            )
        }
        
        conversationContext.addMessage("assistant", response)
        
        return AgentResponse(
            response = response,
            toolExecuted = false,
            metadata = mapOf("intent" to userIntent)
        )
    }
    
    private fun analyzeUserIntent(input: String): IntentClassification {
        val lower = input.lowercase()
        
        val intentType = when {
            lower.contains("what") || lower.contains("how") || lower.contains("why") -> IntentType.QUESTION
            lower.contains("do") || lower.contains("make") || lower.contains("create") -> IntentType.COMMAND
            lower.contains("can you") || lower.contains("would you") -> IntentType.REQUEST
            lower.contains("remember") || lower.contains("note") -> IntentType.REMINDER
            else -> IntentType.STATEMENT
        }
        
        val entities = extractEntities(input)
        
        return IntentClassification(intentType, entities, 0.8)
    }
    
    private fun extractEntities(input: String): Map<String, String> {
        val entities = mutableMapOf<String, String>()
        
        Regex("""\d+\s*(hour|minute|second|day|week)""", RegexOption.IGNORE_CASE)
            .find(input)?.let { entities["duration"] = it.value }
        
        Regex("""["']([^"']+)["']""").find(input)?.let { entities["quoted"] = it.groupValues[1] }
        
        return entities
    }
    
    private fun augmentContextWithBeliefs(userInput: String, beliefs: List<Belief>): String {
        if (beliefs.isEmpty()) return userInput
        
        val beliefContext = beliefs.take(3).joinToString("; ") { belief ->
            "${belief.key}: ${belief.value}"
        }
        
        return "[Relevant context: $beliefContext] $userInput"
    }
    
    private fun buildMessageList(augmentedInput: String? = null): List<com.example.smarty.server.llm.LlmMessage> {
        val messages = mutableListOf<com.example.smarty.server.llm.LlmMessage>()
        
        messages.add(com.example.smarty.server.llm.LlmMessage("system", systemPrompt))
        
        val history = conversationContext.getRecent(10)
        messages.addAll(history.map { com.example.smarty.server.llm.LlmMessage(it.role, it.content) })
        
        if (augmentedInput != null && history.isEmpty()) {
            messages.add(com.example.smarty.server.llm.LlmMessage("user", augmentedInput))
        }
        
        return messages
    }
    
    private fun buildAdvancedSystemPrompt(): String {
        return buildString {
            appendLine("You are ${agentPersonality.name}, ${agentPersonality.description}")
            appendLine()
            appendLine("## Core Principles")
            appendLine("- Think step by step before responding")
            appendLine("- Consider multiple perspectives")
            appendLine("- Acknowledge uncertainty when present")
            appendLine("- Learn from previous interactions")
            appendLine()
            appendLine("## Tool Execution")
            appendLine("When you need to use a tool, say:")
            appendLine("TOOL: <what you want to do>")
            appendLine()
            appendLine("Example:")
            appendLine("  TOOL: search for the weather in Tokyo")
            appendLine("  TOOL: save a note with my WiFi password")
            appendLine()
            appendLine("## Reasoning Process")
            appendLine("1. Understand what the user wants")
            appendLine("2. Determine if a tool is needed")
            appendLine("3. If yes, delegate to Tool Executor")
            appendLine("4. Synthesize the result into your response")
            appendLine("5. Consider if this affects your understanding")
        }
    }
    
    private fun extractToolExecution(response: String): String? {
        val patterns = listOf(
            Regex("""TOOL:\s*(.+?)(?:\n|$)""", RegexOption.IGNORE_CASE),
            Regex("""EXECUTE_TOOL:\s*(.+?)(?:\n|$)""", RegexOption.IGNORE_CASE),
            Regex("""USE_TOOL:\s*(.+?)(?:\n|$)""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            pattern.find(response)?.groupValues?.get(1)?.trim()?.let { return it }
        }
        
        return null
    }
    
    private fun formatToolResult(result: ExecutionResult): String {
        return buildString {
            appendLine("[Tool Execution]")
            appendLine("Tool: ${result.toolName ?: "none"}")
            appendLine("Execution ID: ${result.executionId}")
            appendLine("Success: ${result.success}")
            appendLine("Confidence: ${"%.2f".format(result.confidence)}")
            appendLine()
            if (result.success) {
                appendLine("Result:")
                appendLine(result.result?.toString() ?: "No result")
            } else {
                appendLine("Error:")
                appendLine(result.error ?: "Unknown error")
            }
            result.durationMs?.let {
                appendLine()
                appendLine("Duration: ${it}ms")
            }
        }
    }
    
    fun clearContext() = conversationContext.clear()
    fun getContext(): List<MemoryEntry> = conversationContext.getAll()
    fun getBeliefs(): List<Belief> = beliefRevision.getAllBeliefs()
}

data class AgentResponse(
    val response: String,
    val toolExecuted: Boolean,
    val toolName: String? = null,
    val toolResult: Any? = null,
    val toolError: String? = null,
    val executionId: String? = null,
    val metadata: Map<String, Any> = emptyMap()
)

enum class IntentType { QUESTION, COMMAND, REQUEST, REMINDER, STATEMENT }

data class IntentClassification(
    val type: IntentType,
    val entities: Map<String, String>,
    val confidence: Double
)

class ConversationMemory(
    private val maxSize: Int = 100
) {
    private val messages = ConcurrentLinkedQueue<MemoryEntry>()
    private val lock = ReentrantReadWriteLock()
    
    data class MemoryEntry(
        val role: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
        val importance: Double = 0.5
    )
    
    fun addMessage(role: String, content: String, importance: Double = 0.5) {
        lock.write {
            messages.add(MemoryEntry(role, content, System.currentTimeMillis(), importance))
            
            val overflow = messages.size - maxSize
            if (overflow > 0) {
                repeat(overflow) { messages.poll() }
            }
        }
    }
    
    fun getRecent(count: Int): List<MemoryEntry> {
        return lock.read { messages.toList().takeLast(count) }
    }
    
    fun getAll(): List<MemoryEntry> = lock.read { messages.toList() }
    fun clear() = lock.write { messages.clear() }
}

class BeliefRevisionSystem(
    private val maxBeliefs: Int = 50
) {
    private val beliefs = ConcurrentHashMap<String, Belief>()
    private val lock = ReentrantReadWriteLock()
    
    data class Belief(
        val key: String,
        val value: String,
        val confidence: Double,
        val timestamp: Long,
        val source: String
    )
    
    fun retrieveRelevant(query: String): List<Belief> {
        val queryTerms = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        
        return lock.read {
            beliefs.values.filter { belief ->
                queryTerms.any { term ->
                    belief.key.contains(term) || belief.value.lowercase().contains(term)
                }
            }.sortedByDescending { it.confidence }.take(5)
        }
    }
    
    fun processToolResult(toolRequest: String, result: ExecutionResult) {
        if (result.success && result.result != null) {
            val key = extractKey(toolRequest)
            lock.write {
                beliefs[key] = Belief(
                    key = key,
                    value = result.result.toString(),
                    confidence = result.confidence,
                    timestamp = System.currentTimeMillis(),
                    source = result.toolName ?: "unknown"
                )
                
                if (beliefs.size > maxBeliefs) {
                    val toRemove = beliefs.entries.minByOrNull { it.value.confidence }?.key
                    toRemove?.let { beliefs.remove(it) }
                }
            }
        }
    }
    
    private fun extractKey(request: String): String {
        val words = request.split(Regex("\\W+")).filter { it.length > 3 }.take(3)
        return words.joinToString("_").lowercase()
    }
    
    fun getAllBeliefs(): List<Belief> = lock.read { beliefs.values.toList() }
}

class SelfReflectionEngine {
    private val reflections = ConcurrentLinkedQueue<Reflection>()
    private val lock = ReentrantReadWriteLock()
    
    data class Reflection(
        val trigger: String,
        val outcome: String,
        val assessment: String,
        val timestamp: Long
    )
    
    fun reflect(response: String, result: ExecutionResult, originalInput: String) {
        val assessment = when {
            result.success && result.confidence > 0.8 -> "High confidence successful execution"
            result.success -> "Successful execution with moderate confidence"
            result.error != null -> "Failed execution - ${result.error}"
            else -> "Inconclusive outcome"
        }
        
        lock.write {
            reflections.add(Reflection(
                trigger = originalInput.take(50),
                outcome = if (result.success) "success" else "failure",
                assessment = assessment,
                timestamp = System.currentTimeMillis()
            ))
        }
    }
    
    fun getRecentReflections(count: Int = 10): List<Reflection> {
        return lock.read { reflections.toList().takeLast(count) }
    }
}

class TheoryOfMindEngine {
    private val userModel = UserModel()
    private val lock = ReentrantReadWriteLock()
    
    data class UserModel(
        var lastIntent: String? = null,
        var preferences: MutableMap<String, String> = mutableMapOf(),
        var interactionCount: Int = 0,
        var avgMessageLength: Double = 0.0
    )
    
    fun updateUserModel(input: String, intent: IntentClassification) {
        lock.write {
            userModel.lastIntent = intent.type.name
            userModel.interactionCount++
            
            val currentAvg = userModel.avgMessageLength
            userModel.avgMessageLength = (currentAvg * (userModel.interactionCount - 1) + input.length) / userModel.interactionCount
            
            intent.entities.forEach { (key, value) ->
                userModel.preferences[key] = value
            }
        }
    }
    
    fun getUserModel(): UserModel = lock.read { userModel.copy() }
}

class HTNPlanner {
    private val taskNetwork = TaskNetwork()
    private val methods = mutableMapOf<String, List<Method>>()
    
    data class Task(
        val name: String,
        val parameters: Map<String, Any> = emptyMap()
    )
    
    data class Method(
        val task: String,
        val subtasks: List<Task>,
        val precondition: (Map<String, Any>) -> Boolean
    )
    
    data class TaskNetwork(
        val tasks: MutableList<Task> = mutableListOf()
    )
    
    fun decompose(task: String, state: Map<String, Any>): List<Task>? {
        return methods[task]?.firstOrNull { it.precondition(state) }?.subtasks
    }
    
    fun addMethod(task: String, method: Method) {
        methods.getOrPut(task) { mutableListOf() }.add(method)
    }
}

class AgentPersonality(
    val name: String = "Smarty",
    val description: String = "a helpful and intelligent AI assistant"
)

class CognitiveState(
    var load: Double = 0.0,
    var focus: String = "general",
    var contextWindow: Int = 10
)

class AgentSystem(
    private val reasoningAgent: ReasoningAgent,
    private val toolExecutor: ToolExecutorAgent
) {
    private val logger = LoggerFactory.getLogger(AgentSystem::class.java)
    
    private val agentPool = AgentPool()
    private val taskQueue = TaskQueue()
    private val coordinator = MultiAgentCoordinator(agentPool)
    
    suspend fun process(input: String): AgentResponse {
        val classification = analyzeTaskComplexity(input)
        
        return when (classification) {
            TaskComplexity.SIMPLE -> reasoningAgent.process(input)
            TaskComplexity.COMPLEX -> handleComplexTask(input)
            TaskComplexity.MULTI_AGENT -> handleMultiAgentTask(input)
        }
    }
    
    private fun analyzeTaskComplexity(input: String): TaskComplexity {
        val lower = input.lowercase()
        val wordCount = lower.split(Regex("\\s+")).size
        
        val multiIndicators = listOf("and", "then", "also", "plus", "both", "multiple", "all")
        val hasMulti = multiIndicators.any { lower.contains(it) }
        
        val parallelIndicators = listOf("concurrently", "parallel", "at the same time")
        val hasParallel = parallelIndicators.any { lower.contains(it) }
        
        return when {
            hasParallel -> TaskComplexity.MULTI_AGENT
            hasMulti || wordCount > 20 -> TaskComplexity.COMPLEX
            else -> TaskComplexity.SIMPLE
        }
    }
    
    private suspend fun handleComplexTask(input: String): AgentResponse {
        val subtasks = decomposeTask(input)
        
        val results = mutableListOf<ExecutionResult>()
        
        for (subtask in subtasks) {
            val result = toolExecutor.execute(ToolRequest(subtask))
            results.add(result)
            
            if (!result.success && result.confidence < 0.5) {
                break
            }
        }
        
        val success = results.all { it.success }
        val response = results.joinToString("\n\n") { it.result?.toString() ?: it.error ?: "failed" }
        
        return AgentResponse(
            response = response,
            toolExecuted = true,
            metadata = mapOf(
                "subtasks" to subtasks.size,
                "completed" to results.count { it.success }
            )
        )
    }
    
    private suspend fun handleMultiAgentTask(input: String): AgentResponse {
        val agents = agentPool.getAvailableAgents(2)
        
        val tasks = splitTaskForAgents(input, agents.size)
        
        val results = agents.zip(tasks).map { (agent, task) ->
            scope.async { agent.process(task) }
        }.awaitAll()
        
        val aggregated = results.joinToString("\n") { it.response }
        
        return AgentResponse(
            response = aggregated,
            toolExecuted = true,
            metadata = mapOf("agentsUsed" to agents.size)
        )
    }
    
    private fun decomposeTask(input: String): List<String> {
        val parts = input.split(Regex("(?=and|then|also|,)\\s*", RegexOption.IGNORE_CASE))
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }
    
    private fun splitTaskForAgents(input: String, agentCount: Int): List<String> {
        val sentences = input.split(Regex("[.,;]\\s*")).filter { it.isNotBlank() }
        
        if (sentences.size <= agentCount) {
            return sentences.map { it.trim() }
        }
        
        val perAgent = sentences.size / agentCount
        return sentences.groupBy { sentences.indexOf(it) / perAgent }
            .values
            .map { it.joinToString(". ") }
    }
    
    fun getToolExecutor(): ToolExecutorAgent = toolExecutor
    
    fun getSystemStatus(): String {
        val stats = toolExecutor.getStatistics()
        
        return buildString {
            appendLine("==================================================")
            appendLine("Multi-Agent System Status")
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
            appendLine("[Agent Pool]")
            appendLine("  Active Agents: ${agentPool.activeCount()}")
            appendLine("  Available Agents: ${agentPool.availableCount()}")
        }
    }
}

enum class TaskComplexity { SIMPLE, COMPLEX, MULTI_AGENT }

class AgentPool {
    private val agents = ConcurrentHashMap<String, PooledAgent>()
    private val availableAgents = ConcurrentLinkedQueue<String>()
    
    data class PooledAgent(
        val id: String,
        val agent: ReasoningAgent,
        var isAvailable: Boolean = true,
        var tasksCompleted: Int = 0,
        var avgResponseTime: Long = 0
    )
    
    init {
        repeat(3) { i ->
            val id = "agent-$i"
            availableAgents.add(id)
        }
    }
    
    fun getAvailableAgents(count: Int): List<ReasoningAgent> {
        val result = mutableListOf<ReasoningAgent>()
        
        repeat(min(count, availableAgents.size)) {
            val id = availableAgents.poll() ?: return@repeat
            
            agents[id]?.let { pooled ->
                pooled.isAvailable = false
                result.add(pooled.agent)
            }
        }
        
        return result
    }
    
    fun releaseAgent(agentId: String) {
        agents[agentId]?.let { pooled ->
            pooled.isAvailable = true
            availableAgents.add(agentId)
        }
    }
    
    fun activeCount(): Int = agents.values.count { !it.isAvailable }
    fun availableCount(): Int = availableAgents.size
}

class TaskQueue {
    private val queue = ConcurrentLinkedQueue<QueuedTask>()
    private val lock = ReentrantReadWriteLock()
    
    data class QueuedTask(
        val id: String,
        val input: String,
        val priority: Int,
        val enqueuedAt: Long
    )
    
    fun enqueue(task: String, priority: Int = 0) {
        lock.write {
            queue.add(QueuedTask(UUID.randomUUID().toString(), task, priority, System.currentTimeMillis()))
        }
    }
    
    fun dequeue(): String? {
        return lock.write {
            queue.poll()?.input
        }
    }
    
    fun size(): Int = queue.size
}

class MultiAgentCoordinator(private val agentPool: AgentPool) {
    private val communicationLog = ConcurrentLinkedQueue<CommunicationMessage>()
    
    data class CommunicationMessage(
        val from: String,
        val to: String,
        val message: String,
        val timestamp: Long
    )
    
    suspend fun coordinate(agents: List<ReasoningAgent>, task: String): List<AgentResponse> {
        return agents.map { agent ->
            scope.async { agent.process(task) }
        }.awaitAll()
    }
    
    fun broadcast(fromAgent: String, message: String, toAgents: List<String>) {
        toAgents.forEach { to ->
            communicationLog.add(CommunicationMessage(fromAgent, to, message, System.currentTimeMillis()))
        }
    }
    
    fun getCommunicationLog(): List<CommunicationMessage> = communicationLog.toList()
}

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
