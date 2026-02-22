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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*
import kotlin.random.Random

class ToolCallingAgent(
    private val llmProvider: LlmProvider,
    private val toolRegistry: ToolRegistry
) {
    private val logger = LoggerFactory.getLogger(ToolCallingAgent::class.java)
    private val executionMutex = Mutex()
    
    private val executionHistory = ConcurrentLinkedQueue<ToolExecutionRecord>()
    private val activeExecutions = ConcurrentHashMap<String, ToolExecution>()
    
    private val adaptiveToolSelector = AdaptiveToolSelector()
    private val toolRecommendationEngine = ToolRecommendationEngine()
    private val executionPlanner = ExecutionPlanner()
    private val predictiveCache = PredictiveCache()
    private val retryStrategyManager = RetryStrategyManager()
    private val toolCompositionEngine = ToolCompositionEngine()
    private val semanticMatcher = SemanticToolMatcher()
    private val toolVersionManager = ToolVersionManager()
    private val parameterInferencer = ParameterInferencer()
    
    data class ToolExecution(
        val id: String = UUID.randomUUID().toString(),
        val toolName: String,
        val parameters: Map<String, Any>,
        val startedAt: Long = System.currentTimeMillis(),
        var completedAt: Long? = null,
        var result: Any? = null,
        var error: String? = null,
        var retryCount: Int = 0,
        var executionPath: List<String> = emptyList()
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
        val error: String?,
        val retryCount: Int = 0,
        val confidence: Double = 0.0,
        val executionPath: List<String> = emptyList()
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
            
            val retryStrategy = retryStrategyManager.getStrategy(toolName)
            var lastError: String? = null
            var success = false
            
            for (attempt in 0..retryStrategy.maxRetries) {
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
                    
                    val enrichedParams = parameterInferencer.infer(
                        toolName, parameters, callerContext, executionHistory.toList()
                    )
                    
                    val result = toolRegistry.execute(toolName, enrichedParams)
                    
                    execution.completedAt = System.currentTimeMillis()
                    execution.result = result
                    success = true
                    
                    predictiveCache.record(toolName, enrichedParams, result)
                    
                    val confidence = adaptiveToolSelector.calculateConfidence(
                        toolName, enrichedParams, result
                    )
                    
                    val record = ToolExecutionRecord(
                        id = executionId,
                        toolName = toolName,
                        parameters = enrichedParams,
                        startedAt = execution.startedAt,
                        completedAt = execution.completedAt!!,
                        success = true,
                        durationMs = execution.completedAt!! - execution.startedAt,
                        result = result?.toString()?.take(1000),
                        error = null,
                        retryCount = attempt,
                        confidence = confidence,
                        executionPath = execution.executionPath
                    )
                    executionHistory.offer(record)
                    adaptiveToolSelector.recordSuccess(toolName, confidence)
                    
                    activeExecutions.remove(executionId)
                    
                    logger.info("[$executionId] Tool executed successfully in ${record.durationMs}ms (attempt $attempt)")
                    
                    toolRecommendationEngine.recordExecution(toolName, enrichedParams, success)
                    
                    return@withLock ToolCallResponse(
                        success = true,
                        toolName = toolName,
                        result = result,
                        error = null,
                        executionId = executionId
                    )
                    
                } catch (e: Exception) {
                    lastError = e.message
                    execution.retryCount = attempt + 1
                    
                    if (attempt < retryStrategy.maxRetries) {
                        val delay = retryStrategy.calculateDelay(attempt)
                        logger.warn("[$executionId] Attempt ${attempt + 1} failed, retrying in ${delay}ms: ${e.message}")
                        Thread.sleep(delay)
                    }
                }
            }
            
            execution.completedAt = System.currentTimeMillis()
            execution.error = lastError
            
            val record = ToolExecutionRecord(
                id = executionId,
                toolName = toolName,
                parameters = parameters,
                startedAt = execution.startedAt,
                completedAt = execution.completedAt!!,
                success = false,
                durationMs = execution.completedAt!! - execution.startedAt,
                result = null,
                error = lastError,
                retryCount = execution.retryCount,
                confidence = 0.0,
                executionPath = execution.executionPath
            )
            executionHistory.offer(record)
            adaptiveToolSelector.recordFailure(toolName)
            
            activeExecutions.remove(executionId)
            
            logger.error("[$executionId] Tool execution failed after ${retryStrategy.maxRetries + 1} attempts: $lastError")
            
            toolRecommendationEngine.recordExecution(toolName, parameters, false)
            
            ToolCallResponse(
                success = false,
                toolName = toolName,
                result = null,
                error = lastError ?: "Unknown error",
                executionId = executionId
            )
        }
    }
    
    suspend fun executeWithPlanning(
        task: String,
        context: Map<String, Any>? = null
    ): ToolCallResponse {
        val plan = executionPlanner.createPlan(task, toolRegistry.listTools())
        
        if (plan.steps.isEmpty()) {
            return ToolCallResponse(
                success = false,
                toolName = "",
                result = null,
                error = "No suitable tools found for task",
                executionId = UUID.randomUUID().toString()
            )
        }
        
        val composedResult = toolCompositionEngine.executePlan(plan) { toolName, params ->
            executeTool(toolName, params, "Planned execution for: $task")
        }
        
        return composedResult
    }
    
    suspend fun recommendTools(task: String, context: Map<String, Any>? = null): List<ToolRecommendation> {
        return toolRecommendationEngine.recommend(task, context, toolRegistry.listTools())
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
            appendLine()
            appendLine("Adaptive Selector:")
            appendLine(adaptiveToolSelector.getStats())
            appendLine()
            appendLine("Predictive Cache:")
            appendLine(predictiveCache.getStats())
        }
    }
    
    fun clearHistory() {
        executionHistory.clear()
    }
}

class AdaptiveToolSelector {
    private val toolScores = ConcurrentHashMap<String, ToolScore>()
    private val recentResults = ConcurrentHashMap<String, MutableList<Double>>()
    private val confidenceModel = ConfidenceModel()
    
    fun calculateConfidence(toolName: String, params: Map<String, Any>, result: Any?): Double {
        val score = toolScores[toolName] ?: ToolScore(toolName)
        
        val paramComplexity = params.size.toDouble()
        val resultQuality = evaluateResultQuality(result)
        
        val confidence = (score.successRate * 0.5 + 
                        score.averageRating * 0.3 + 
                        resultQuality * 0.2) * 
                        (1.0 - minOf(paramComplexity / 10.0, 0.3))
        
        return confidence.coerceIn(0.0, 1.0)
    }
    
    private fun evaluateResultQuality(result: Any?): Double {
        if (result == null) return 0.0
        return when (result) {
            is String -> if (result.isNotEmpty() && !result.startsWith("Error")) 1.0 else 0.5
            is Map<*, *> -> if (result.isNotEmpty()) 1.0 else 0.5
            is Collection<*> -> if (result.isNotEmpty()) 1.0 else 0.5
            else -> 0.8
        }
    }
    
    fun recordSuccess(toolName: String, confidence: Double) {
        val score = toolScores.getOrPut(toolName) { ToolScore(toolName) }
        score.successCount.incrementAndGet()
        score.totalCount.incrementAndGet()
        score.averageRating = score.averageRating * 0.9 + confidence * 0.1
        score.lastSuccessTime = System.currentTimeMillis()
        
        recentResults.getOrPut(toolName) { mutableListOf() }.add(confidence)
        if (recentResults[toolName]?.size ?: 0 > 100) {
            recentResults[toolName]?.removeAt(0)
        }
    }
    
    fun recordFailure(toolName: String) {
        val score = toolScores.getOrPut(toolName) { ToolScore(toolName) }
        score.totalCount.incrementAndGet()
        score.lastFailureTime = System.currentTimeMillis()
        
        recentResults.getOrPut(toolName) { mutableListOf() }.add(0.0)
    }
    
    fun selectBestTool(task: String, availableTools: List<String>): String? {
        return availableTools.maxByOrNull { tool ->
            val score = toolScores[tool] ?: ToolScore(tool)
            score.successRate * score.averageRating * recencyFactor(score.lastSuccessTime)
        }
    }
    
    private fun recencyFactor(lastSuccess: Long): Double {
        val elapsed = System.currentTimeMillis() - lastSuccess
        return exp(-elapsed / (24 * 60 * 60 * 1000.0))
    }
    
    fun getStats(): String {
        return buildString {
            toolScores.entries.sortedByDescending { it.value.successRate }.take(5).forEach { (tool, score) ->
                appendLine("  $tool: ${"%.1f".format(score.successRate * 100)}% success, ${"%.2f".format(score.averageRating)} rating")
            }
        }
    }
    
    data class ToolScore(
        val toolName: String,
        var successCount: AtomicInteger = AtomicInteger(0),
        var totalCount: AtomicInteger = AtomicInteger(0),
        var averageRating: Double = 0.5,
        var lastSuccessTime: Long = 0L,
        var lastFailureTime: Long = 0L
    ) {
        val successRate: Double
            get() = if (totalCount.get() > 0) successCount.get().toDouble() / totalCount.get() else 0.5
    }
    
    class ConfidenceModel {
        private val weights = mapOf(
            "success_rate" to 0.4,
            "recency" to 0.3,
            "complexity" to 0.2,
            "user_feedback" to 0.1
        )
        
        fun predict(toolName: String, context: Map<String, Any>): Double {
            return Random.nextDouble(0.6, 0.95)
        }
    }
}

class ToolRecommendationEngine {
    private val executionPatterns = ConcurrentHashMap<String, MutableList<ExecutionPattern>>()
    private val taskEmbeddings = ConcurrentHashMap<String, DoubleArray>()
    private val toolCapabilities = ConcurrentHashMap<String, Set<String>>()
    
    init {
        initializeDefaultPatterns()
    }
    
    private fun initializeDefaultPatterns() {
        registerPattern("search", listOf("web_search", "find_note"))
        registerPattern("save", listOf("save_note", "remember_fact"))
        registerPattern("compute", listOf("calculate", "analyze_text"))
    }
    
    private fun registerPattern(task: String, tools: List<String>) {
        executionPatterns[task] = tools.map { tool ->
            ExecutionPattern(task, tool, 1.0)
        }.toMutableList()
    }
    
    fun recommend(task: String, context: Map<String, Any>?, availableTools: List<String>): List<ToolRecommendation> {
        val taskLower = task.lowercase()
        val recommendations = mutableListOf<ToolRecommendation>()
        
        val matchingPatterns = executionPatterns.filterKeys { taskLower.contains(it) }
        
        matchingPatterns.forEach { (_, patterns) ->
            patterns.forEach { pattern ->
                if (availableTools.contains(pattern.toolName)) {
                    val score = pattern.successRate * recencyWeight(pattern)
                    recommendations.add(ToolRecommendation(
                        toolName = pattern.toolName,
                        score = score,
                        reason = "Pattern match: $task",
                        confidence = score
                    ))
                }
            }
        }
        
        availableTools.forEach { tool ->
            if (recommendations.none { it.toolName == tool }) {
                val semanticScore = calculateSemanticSimilarity(task, tool)
                if (semanticScore > 0.3) {
                    recommendations.add(ToolRecommendation(
                        toolName = tool,
                        score = semanticScore,
                        reason = "Semantic match",
                        confidence = semanticScore
                    ))
                }
            }
        }
        
        return recommendations.sortedByDescending { it.score }.take(5)
    }
    
    private fun calculateSemanticSimilarity(task: String, tool: String): Double {
        val taskWords = task.lowercase().split(" ").toSet()
        val toolWords = tool.lowercase().split("_").toSet()
        
        val intersection = taskWords.intersect(toolWords).size
        val union = taskWords.union(toolWords).size
        
        return if (union > 0) intersection.toDouble() / union else 0.0
    }
    
    private fun recencyWeight(pattern: ExecutionPattern): Double {
        return 1.0
    }
    
    fun recordExecution(toolName: String, params: Map<String, Any>, success: Boolean) {
        val task = extractTaskFromParams(params)
        val patterns = executionPatterns.getOrPut(task) { mutableListOf() }
        
        val existing = patterns.find { it.toolName == toolName }
        if (existing != null) {
            existing.successRate = if (success) {
                existing.successRate * 0.95 + 0.05
            } else {
                existing.successRate * 0.95
            }
        } else {
            patterns.add(ExecutionPattern(task, toolName, if (success) 1.0 else 0.0))
        }
    }
    
    private fun extractTaskFromParams(params: Map<String, Any>): String {
        return params["task"]?.toString() ?: params["query"]?.toString() ?: "unknown"
    }
    
    data class ExecutionPattern(
        val task: String,
        val toolName: String,
        var successRate: Double
    )
    
    data class ToolRecommendation(
        val toolName: String,
        val score: Double,
        val reason: String,
        val confidence: Double
    )
}

class ExecutionPlanner {
    private val toolDependencies = ConcurrentHashMap<String, MutableSet<String>>()
    private val executionHistory = ConcurrentLinkedQueue<ExecutionPlan>()
    
    fun createPlan(task: String, availableTools: List<String>): ExecutionPlan {
        val steps = mutableListOf<ExecutionStep>()
        val taskLower = task.lowercase()
        
        when {
            taskLower.contains("search") && taskLower.contains("analyze") -> {
                steps.add(ExecutionStep(0, "web_search", mapOf("query" to extractQuery(task)), emptyList()))
                steps.add(ExecutionStep(1, "analyze_text", mapOf("input" to "{{previous}}"), listOf(0)))
            }
            taskLower.contains("find") && taskLower.contains("save") -> {
                steps.add(ExecutionStep(0, "find_note", mapOf("query" to extractQuery(task)), emptyList()))
                steps.add(ExecutionStep(1, "save_note", mapOf("content" to "{{previous}}"), listOf(0)))
            }
            taskLower.contains("fetch") && taskLower.contains("process") -> {
                steps.add(ExecutionStep(0, "fetch_url", mapOf("url" to extractUrl(task)), emptyList()))
                steps.add(ExecutionStep(1, "analyze_text", mapOf("input" to "{{previous}}"), listOf(0)))
            }
            else -> {
                val tool = availableTools.firstOrNull { taskLower.contains(it.lowercase()) }
                if (tool != null) {
                    steps.add(ExecutionStep(0, tool, mapOf("task" to task), emptyList()))
                }
            }
        }
        
        val plan = ExecutionPlan(
            id = UUID.randomUUID().toString(),
            task = task,
            steps = steps,
            estimatedDuration = steps.sumOf { 1000L }
        )
        
        executionHistory.offer(plan)
        return plan
    }
    
    private fun extractQuery(task: String): String {
        val removeWords = listOf("search", "find", "look", "up", "for", "the", "a", "an", "analyze")
        return task.split(" ")
            .filter { !removeWords.contains(it.lowercase()) }
            .joinToString(" ")
            .trim()
    }
    
    private fun extractUrl(task: String): String {
        val urlPattern = Regex("https?://[\\w./-]+")
        return urlPattern.find(task)?.value ?: ""
    }
    
    data class ExecutionPlan(
        val id: String,
        val task: String,
        val steps: List<ExecutionStep>,
        val estimatedDuration: Long
    )
    
    data class ExecutionStep(
        val order: Int,
        val toolName: String,
        val parameters: Map<String, Any>,
        val dependencies: List<Int>
    )
}

class PredictiveCache {
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val accessCount = AtomicLong(0)
    private val hitCount = AtomicLong(0)
    private val evictionPolicy = EvictionPolicy.LRU
    
    private val maxCacheSize = 1000
    private val defaultTtl = 5 * 60 * 1000L
    
    fun record(toolName: String, params: Map<String, Any>, result: Any?) {
        val key = generateCacheKey(toolName, params)
        
        cache[key] = CacheEntry(
            toolName = toolName,
            params = params,
            result = result,
            createdAt = System.currentTimeMillis(),
            lastAccessed = System.currentTimeMillis(),
            accessCount = AtomicInteger(1)
        )
        
        if (cache.size > maxCacheSize) {
            evict()
        }
    }
    
    fun get(toolName: String, params: Map<String, Any>): Any? {
        val key = generateCacheKey(toolName, params)
        val entry = cache[key] ?: return null
        
        if (System.currentTimeMillis() - entry.createdAt > defaultTtl) {
            cache.remove(key)
            return null
        }
        
        entry.lastAccessed = System.currentTimeMillis()
        entry.accessCount.incrementAndGet()
        
        accessCount.incrementAndGet()
        hitCount.incrementAndGet()
        
        return entry.result
    }
    
    private fun generateCacheKey(toolName: String, params: Map<String, Any>): String {
        val paramStr = params.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        return "$toolName:$paramStr".hashCode().toString()
    }
    
    private fun evict() {
        val toEvict = cache.entries
            .sortedBy { it.value.accessCount.get() }
            .take(cache.size - maxCacheSize + 100)
            .map { it.key }
            .toList()
        
        toEvict.forEach { cache.remove(it) }
    }
    
    fun getStats(): String {
        return buildString {
            appendLine("  Entries: ${cache.size}")
            appendLine("  Hit Rate: ${if (accessCount.get() > 0) "%.2f".format(hitCount.get().toDouble() / accessCount.get() * 100) else 0.0}%")
            appendLine("  TTL: ${defaultTtl / 1000}s")
        }
    }
    
    data class CacheEntry(
        val toolName: String,
        val params: Map<String, Any>,
        val result: Any?,
        val createdAt: Long,
        var lastAccessed: Long,
        val accessCount: AtomicInteger
    )
    
    enum class EvictionPolicy {
        LRU, LFU, FIFO
    }
}

class RetryStrategyManager {
    private val strategies = ConcurrentHashMap<String, RetryStrategy>()
    
    init {
        strategies["default"] = ExponentialBackoffStrategy(3, 1000L, 2.0)
        strategies["network"] = ExponentialBackoffStrategy(5, 500L, 1.5)
        strategies["critical"] = FixedDelayStrategy(10, 100L)
    }
    
    fun getStrategy(toolName: String): RetryStrategy {
        return when {
            toolName.contains("network") || toolName.contains("fetch") -> 
                strategies["network"] ?: strategies["default"]!!
            toolName.contains("critical") || toolName.contains("transaction") -> 
                strategies["critical"] ?: strategies["default"]!!
            else -> strategies["default"]!!
        }
    }
    
    interface RetryStrategy {
        val maxRetries: Int
        fun calculateDelay(attempt: Int): Long
    }
    
    class ExponentialBackoffStrategy(
        override val maxRetries: Int,
        private val baseDelay: Long,
        private val multiplier: Double
    ) : RetryStrategy {
        override fun calculateDelay(attempt: Int): Long {
            return (baseDelay * multiplier.pow(attempt)).toLong().coerceAtMost(30000L)
        }
    }
    
    class FixedDelayStrategy(
        override val maxRetries: Int,
        private val delay: Long
    ) : RetryStrategy {
        override fun calculateDelay(attempt: Int): Long = delay
    }
}

class ToolCompositionEngine {
    private val compositionRules = ConcurrentHashMap<String, CompositionRule>()
    
    fun executePlan(
        plan: ExecutionPlanner.ExecutionPlan,
        executor: suspend (String, Map<String, Any>) -> ToolCallResponse
    ): ToolCallResponse {
        val results = mutableMapOf<Int, Any?>()
        
        for (step in plan.steps.sortedBy { it.order }) {
            val params = resolveDependencies(step, results)
            val response = executor(step.toolName, params)
            
            results[step.order] = response.result
            
            if (!response.success && step.dependencies.isNotEmpty()) {
                return ToolCallResponse(
                    success = false,
                    toolName = step.toolName,
                    result = null,
                    error = "Step ${step.order} failed: ${response.error}",
                    executionId = response.executionId
                )
            }
        }
        
        val finalResult = results[plan.steps.maxOf { it.order }]
        
        return ToolCallResponse(
            success = true,
            toolName = "composite",
            result = finalResult,
            error = null,
            executionId = UUID.randomUUID().toString()
        )
    }
    
    private fun resolveDependencies(step: ExecutionPlanner.ExecutionStep, results: Map<Int, Any?>): Map<String, Any> {
        val resolved = step.parameters.mapValues { (_, value) ->
            if (value is String && value == "{{previous}}") {
                results[step.dependencies.lastOrNull()]
            } else {
                value
            }
        }.filterValues { it != null } as Map<String, Any>
        
        return resolved
    }
    
    data class CompositionRule(
        val name: String,
        val inputTool: String,
        val outputTool: String,
        val transformer: (Any?) -> Any?
    )
}

class SemanticToolMatcher {
    private val toolEmbeddings = ConcurrentHashMap<String, DoubleArray>()
    private val vocabulary = ConcurrentHashMap<String, Int>()
    
    init {
        initializeEmbeddings()
    }
    
    private fun initializeEmbeddings() {
        val tools = listOf("web_search", "save_note", "find_note", "calculate", "analyze_text", "fetch_url")
        tools.forEachIndexed { index, tool ->
            toolEmbeddings[tool] = DoubleArray(50) { i ->
                sin((index + 1) * (i + 1) * 0.1)
            }
        }
    }
    
    fun match(task: String, tools: List<String>): List<Pair<String, Double>> {
        val taskEmbedding = embed(task)
        
        return tools.mapNotNull { tool ->
            val toolEmbedding = toolEmbeddings[tool] ?: return@mapNotNull null
            val similarity = cosineSimilarity(taskEmbedding, toolEmbedding)
            tool to similarity
        }.sortedByDescending { it.second }
    }
    
    private fun embed(text: String): DoubleArray {
        val words = text.lowercase().split(" ")
        val embedding = DoubleArray(50)
        
        words.forEach { word ->
            val hash = word.hashCode()
            for (i in embedding.indices) {
                embedding[i] += sin((hash + i) * 0.1)
            }
        }
        
        val norm = sqrt(embedding.sumOf { it * it })
        if (norm > 0) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }
        
        return embedding
    }
    
    private fun cosineSimilarity(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size)
        
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        
        return if (normA > 0 && normB > 0) dotProduct / (sqrt(normA) * sqrt(normB)) else 0.0
    }
}

class ToolVersionManager {
    private val toolVersions = ConcurrentHashMap<String, MutableList<ToolVersion>>()
    private val activeVersion = ConcurrentHashMap<String, String>()
    
    fun registerVersion(toolName: String, version: String, implementation: (Map<String, Any>) -> Any?) {
        val versions = toolVersions.getOrPut(toolName) { mutableListOf() }
        versions.add(ToolVersion(version, System.currentTimeMillis(), implementation))
        
        if (!activeVersion.containsKey(toolName)) {
            activeVersion[toolName] = version
        }
    }
    
    fun getVersion(toolName: String): String {
        return activeVersion[toolName] ?: "1.0.0"
    }
    
    fun switchVersion(toolName: String, version: String): Boolean {
        val versions = toolVersions[toolName] ?: return false
        return if (versions.any { it.version == version }) {
            activeVersion[toolName] = version
            true
        } else false
    }
    
    data class ToolVersion(
        val version: String,
        val releasedAt: Long,
        val implementation: (Map<String, Any>) -> Any?
    )
}

class ParameterInferencer {
    private val inferenceRules = ConcurrentHashMap<String, (Map<String, Any>?, List<*>) -> Map<String, Any>>()
    
    init {
        initializeRules()
    }
    
    private fun initializeRules() {
        inferenceRules["web_search"] = { params, _ ->
            params?.toMutableMap() ?: mutableMapOf()
        }
        
        inferenceRules["save_note"] = { params, _ ->
            val enriched = params?.toMutableMap() ?: mutableMapOf()
            if (!enriched.containsKey("timestamp")) {
                enriched["timestamp"] = System.currentTimeMillis()
            }
            enriched
        }
    }
    
    fun infer(toolName: String, params: Map<String, Any>, context: String?, history: List<*>): Map<String, Any> {
        val rule = inferenceRules[toolName]
        return rule?.invoke(params, history) ?: params
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
    private val toolImplementations: Map<String, ToolRegistry.ToolImplementation>
) {
    private val logger = LoggerFactory.getLogger(ToolRegistry::class.java)
    
    private val toolDefinitions = ConcurrentHashMap<String, ToolDefinition>()
    private val executionCount = ConcurrentHashMap<String, Int>()
    private val errorCount = ConcurrentHashMap<String, Int>()
    private val executionSemaphores = ConcurrentHashMap<String, java.util.concurrent.Semaphore>()
    
    interface ToolImplementation {
        suspend fun execute(params: Map<String, Any>): Any?
        fun getDefinition(): ToolDefinition
    }
    
    fun registerTool(impl: ToolImplementation) {
        val definition = impl.getDefinition()
        toolDefinitions[definition.name] = definition
        executionSemaphores[definition.name] = java.util.concurrent.Semaphore(definition.maxConcurrent)
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
        val semaphore = executionSemaphores[name]
        
        if (impl == null) {
            errorCount[name] = (errorCount[name] ?: 0) + 1
            throw IllegalArgumentException("Tool not found: $name")
        }
        
        semaphore?.acquire()
        
        return try {
            val result = impl.execute(params)
            executionCount[name] = (executionCount[name] ?: 0) + 1
            result
        } catch (e: Exception) {
            errorCount[name] = (errorCount[name] ?: 0) + 1
            throw e
        } finally {
            semaphore?.release()
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
    private val contextTracker = ContextTracker()
    private val intentClassifier = IntentClassifier()
    
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
            appendLine("- If you're unsure what to use: Use the recommendation engine")
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
        
        val intent = intentClassifier.classify(userMessage)
        contextTracker.updateContext(userMessage, intent)
        
        val messages = mutableListOf(
            AgentMessage("system", systemPrompt)
        )
        messages.addAll(conversationHistory.takeLast(10))
        
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
    
    class ContextTracker {
        private val contextHistory = ConcurrentLinkedQueue<ContextEntry>()
        
        fun updateContext(message: String, intent: String) {
            contextHistory.add(ContextEntry(message, intent, System.currentTimeMillis()))
        }
        
        fun getRecentContext(): List<ContextEntry> {
            return contextHistory.toList().takeLast(5)
        }
        
        data class ContextEntry(
            val message: String,
            val intent: String,
            val timestamp: Long
        )
    }
    
    class IntentClassifier {
        private val intentPatterns = mapOf(
            "search" to listOf("find", "search", "look", "locate"),
            "save" to listOf("save", "store", "remember", "write"),
            "compute" to listOf("calculate", "compute", "sum", "add"),
            "analyze" to listOf("analyze", "examine", "review", "check")
        )
        
        fun classify(message: String): String {
            val words = message.lowercase()
            return intentPatterns.entries.find { (_, patterns) ->
                patterns.any { words.contains(it) }
            }?.key ?: "general"
        }
    }
}

class HierarchicalAgentSystem(
    private val toolCallingAgent: ToolCallingAgent,
    private val mainAgent: MainAgent
) {
    private val logger = LoggerFactory.getLogger(HierarchicalAgentSystem::class.java)
    
    private val subAgents = ConcurrentHashMap<String, SubAgent>()
    private val agentHierarchy = AgentHierarchy()
    private val taskQueue = PriorityBlockingQueue<QueuedTask>()
    private val agentScheduler = AgentScheduler()
    
    data class SubAgent(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val role: String,
        val capabilities: List<String>,
        val state: AgentState = AgentState.IDLE,
        var parentId: String? = null,
        var currentTask: String? = null,
        val skillLevel: Double = 1.0,
        val performanceHistory: MutableList<Double> = mutableListOf()
    )
    
    enum class AgentState {
        IDLE, THINKING, EXECUTING_TOOL, WAITING, COMPLETED, FAILED, SUSPENDED
    }
    
    data class AgentHierarchy(
        val rootId: String = "main",
        val children: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()
    )
    
    data class QueuedTask(
        val task: String,
        val priority: Int,
        val submittedAt: Long,
        val context: Map<String, Any>?
    ) : Comparable<QueuedTask> {
        override fun compareTo(other: QueuedTask): Int = other.priority.compareTo(priority)
    }
    
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
    
    fun queueTask(task: String, priority: Int = 5, context: Map<String, Any>? = null) {
        taskQueue.add(QueuedTask(task, priority, System.currentTimeMillis(), context))
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
        
        val startTime = System.currentTimeMillis()
        val result = toolCallingAgent.executeTool(toolName, params, "Task: $task")
        val duration = System.currentTimeMillis() - startTime
        
        val performance = if (result.success) 1.0 - (duration / 10000.0).coerceAtMost(1.0) else 0.0
        agent.performanceHistory.add(performance)
        if (agent.performanceHistory.size > 100) {
            agent.performanceHistory.removeAt(0)
        }
        
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
        
        val avgPerformance = if (agent.performanceHistory.isNotEmpty()) {
            agent.performanceHistory.average()
        } else 0.0
        
        return buildString {
            appendLine("Agent: ${agent.name}")
            appendLine("  Role: ${agent.role}")
            appendLine("  State: ${agent.state}")
            appendLine("  Capabilities: ${agent.capabilities.joinToString(", ")}")
            appendLine("  Skill Level: ${"%.2f".format(agent.skillLevel)}")
            appendLine("  Performance: ${"%.2f".format(avgPerformance * 100)}%")
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
            appendLine("Queued Tasks: ${taskQueue.size}")
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
    
    class AgentScheduler {
        private val schedule = ConcurrentHashMap<String, Long>()
        
        fun scheduleAgent(agentId: String, delayMs: Long) {
            schedule[agentId] = System.currentTimeMillis() + delayMs
        }
        
        fun shouldRun(agentId: String): Boolean {
            val scheduledTime = schedule[agentId] ?: return true
            return System.currentTimeMillis() >= scheduledTime
        }
    }
}

data class TaskResult(
    val success: Boolean,
    val result: Any?,
    val error: String?
)
