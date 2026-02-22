package com.example.smarty.server.tools

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*
import kotlin.random.Random

@Serializable
data class ToolChain(
    val id: String,
    val name: String,
    val description: String,
    val steps: List<ChainStep>,
    val createdAt: Long,
    val lastExecuted: Long? = null,
    val executionCount: Int = 0,
    val successRate: Double = 1.0,
    val avgDurationMs: Long = 0,
    val optimizationLevel: Int = 1,
    val parallelizable: Boolean = false
)

@Serializable
data class ChainStep(
    val toolName: String,
    val argsTemplate: Map<String, String>,
    val condition: String? = null,
    val onError: String = "stop",
    val transform: String? = null,
    val retryCount: Int = 0,
    val timeoutMs: Long = 30000,
    val dependencies: List<String> = emptyList()
)

@Serializable
data class ChainExecution(
    val chainId: String,
    val executionId: String,
    val inputs: Map<String, String>,
    val results: MutableList<StepResult> = mutableListOf(),
    val status: String = "running",
    val startTime: Long,
    val endTime: Long? = null,
    val parallelResults: Map<String, String> = emptyMap()
)

@Serializable
data class StepResult(
    val stepIndex: Int,
    val toolName: String,
    val args: Map<String, String>,
    val result: String?,
    val success: Boolean,
    val durationMs: Long,
    val retryAttempt: Int = 0,
    val error: String? = null
)

data class ChainMetrics(
    val chainId: String,
    val totalExecutions: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val avgDurationMs: Double = 0.0,
    val minDurationMs: Long = Long.MAX_VALUE,
    val maxDurationMs: Long = 0,
    val stepMetrics: Map<Int, StepMetrics> = emptyMap()
)

data class StepMetrics(
    val stepIndex: Int,
    val toolName: String,
    val executionCount: Int = 0,
    val successCount: Int = 0,
    val avgDurationMs: Double = 0.0,
    val failurePatterns: Map<String, Int> = emptyMap()
)

data class OptimizationSuggestion(
    val type: String,
    val description: String,
    val estimatedImprovement: Double,
    val steps: List<Int>
)

class ToolChainBuilder {
    private val logger = LoggerFactory.getLogger(ToolChainBuilder::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val chains = ConcurrentHashMap<String, ToolChain>()
    private val executions = ConcurrentHashMap<String, ChainExecution>()
    private val chainMetrics = ConcurrentHashMap<String, ChainMetrics>()
    private val executionCache = ConcurrentHashMap<String, CachedResult>()
    
    private val dagAnalyzer = DAGAnalyzer()
    private val optimizer = ChainOptimizer()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    data class CachedResult(
        val key: String,
        val result: String,
        val timestamp: Long,
        val ttlMs: Long
    )
    
    fun createChain(
        name: String,
        description: String,
        steps: List<ChainStep>
    ): String {
        val chainId = "chain_${name.lowercase().replace(" ", "_")}_${System.currentTimeMillis()}"
        
        val parallelizable = dagAnalyzer.canExecuteInParallel(steps)
        val optimizedSteps = optimizer.optimize(steps)
        
        val chain = ToolChain(
            id = chainId,
            name = name,
            description = description,
            steps = optimizedSteps,
            createdAt = System.currentTimeMillis(),
            parallelizable = parallelizable,
            optimizationLevel = calculateOptimizationLevel(optimizedSteps)
        )
        
        chains[chainId] = chain
        chainMetrics[chainId] = ChainMetrics(chainId = chainId)
        
        logger.info("Created tool chain: $name with ${steps.size} steps (parallelizable: $parallelizable)")
        
        return chainId
    }
    
    private fun calculateOptimizationLevel(steps: List<ChainStep>): Int {
        val hasConditions = steps.any { it.condition != null }
        val hasTransforms = steps.any { it.transform != null }
        val hasRetries = steps.any { it.retryCount > 0 }
        
        return when {
            hasConditions && hasTransforms -> 3
            hasConditions || hasTransforms -> 2
            hasRetries -> 1
            else -> 0
        }
    }
    
    fun quickChain(
        name: String,
        toolSequence: List<String>
    ): String {
        val steps = toolSequence.mapIndexed { index, tool ->
            ChainStep(
                toolName = tool,
                argsTemplate = mapOf("input" to "\${prev}"),
                condition = null,
                onError = "continue"
            )
        }
        
        return createChain(name, "Quick chain: ${toolSequence.joinToString(" -> ")}", steps)
    }
    
    fun parallelChain(
        name: String,
        tools: List<String>,
        mergeStrategy: String = "collect"
    ): String {
        val steps = tools.map { tool ->
            ChainStep(
                toolName = tool,
                argsTemplate = mapOf("query" to "\${input}"),
                condition = "parallel",
                onError = "continue"
            )
        }
        
        return createChain(name, "Parallel execution with $mergeStrategy merge", steps)
    }
    
    fun conditionalChain(
        name: String,
        conditionTool: String,
        trueBranch: List<String>,
        falseBranch: List<String>
    ): String {
        val steps = mutableListOf<ChainStep>()
        
        steps.add(ChainStep(
            toolName = conditionTool,
            argsTemplate = mapOf("query" to "\${input}"),
            condition = "decision"
        ))
        
        trueBranch.forEach { tool ->
            steps.add(ChainStep(
                toolName = tool,
                argsTemplate = mapOf("input" to "\${prev}"),
                condition = "if_true"
            ))
        }
        
        falseBranch.forEach { tool ->
            steps.add(ChainStep(
                toolName = tool,
                argsTemplate = mapOf("input" to "\${prev}"),
                condition = "if_false"
            ))
        }
        
        return createChain(name, "Conditional chain with branches", steps)
    }
    
    fun getChain(chainId: String): ToolChain? = chains[chainId]
    
    fun listChains(): List<ToolChain> = chains.values.toList()
    
    fun deleteChain(chainId: String): Boolean {
        chainMetrics.remove(chainId)
        return chains.remove(chainId) != null
    }
    
    fun startExecution(
        chainId: String,
        inputs: Map<String, String>
    ): String {
        val chain = chains[chainId] ?: return "Chain not found: $chainId"
        
        val cacheKey = generateCacheKey(chainId, inputs)
        executionCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < cached.ttlMs) {
                logger.info("Returning cached result for chain $chainId")
                return "cached_${System.currentTimeMillis()}"
            }
        }
        
        val executionId = "exec_${chainId}_${System.currentTimeMillis()}"
        
        val execution = ChainExecution(
            chainId = chainId,
            executionId = executionId,
            inputs = inputs,
            startTime = System.currentTimeMillis()
        )
        
        executions[executionId] = execution
        logger.info("Started execution: $executionId for chain: ${chain.name}")
        
        return executionId
    }
    
    private fun generateCacheKey(chainId: String, inputs: Map<String, String>): String {
        return "$chainId:${inputs.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }}"
    }
    
    fun addStepResult(
        executionId: String,
        stepResult: StepResult
    ) {
        executions[executionId]?.let { exec ->
            exec.results.add(stepResult)
        }
    }
    
    fun completeExecution(executionId: String, success: Boolean) {
        executions[executionId]?.let { exec ->
            val endTime = System.currentTimeMillis()
            val duration = endTime - exec.startTime
            
            chains[exec.chainId]?.let { chain ->
                val newCount = chain.executionCount + 1
                val oldTotal = chain.avgDurationMs * chain.executionCount
                val newAvg = (oldTotal + duration) / newCount
                
                val newRate = if (success) {
                    (chain.successRate * chain.executionCount + 1.0) / newCount
                } else {
                    (chain.successRate * chain.executionCount) / newCount
                }
                
                chains[exec.chainId] = chain.copy(
                    lastExecuted = endTime,
                    executionCount = newCount,
                    successRate = newRate,
                    avgDurationMs = newAvg
                )
                
                updateMetrics(exec.chainId, success, duration, exec.results)
            }
            
            executions[executionId] = exec.copy(
                status = if (success) "completed" else "failed",
                endTime = endTime
            )
        }
    }
    
    private fun updateMetrics(chainId: String, success: Boolean, duration: Long, results: List<StepResult>) {
        val current = chainMetrics[chainId] ?: return
        
        val newStepMetrics = current.stepMetrics.toMutableMap()
        results.forEach { result ->
            val stepMetrics = newStepMetrics.getOrPut(result.stepIndex) {
                StepMetrics(result.stepIndex, result.toolName)
            }
            
            newStepMetrics[result.stepIndex] = stepMetrics.copy(
                executionCount = stepMetrics.executionCount + 1,
                successCount = stepMetrics.successCount + if (result.success) 1 else 0,
                avgDurationMs = ((stepMetrics.avgDurationMs * stepMetrics.executionCount) + result.durationMs) / (stepMetrics.executionCount + 1),
                failurePatterns = result.error?.let { error ->
                    val patterns = stepMetrics.failurePatterns.toMutableMap()
                    patterns[error] = (patterns[error] ?: 0) + 1
                    patterns
                } ?: stepMetrics.failurePatterns
            )
        }
        
        chainMetrics[chainId] = current.copy(
            totalExecutions = current.totalExecutions + 1,
            successCount = current.successCount + if (success) 1 else 0,
            failureCount = current.failureCount + if (!success) 1 else 0,
            avgDurationMs = ((current.avgDurationMs * current.totalExecutions) + duration) / (current.totalExecutions + 1),
            minDurationMs = minOf(current.minDurationMs, duration),
            maxDurationMs = maxOf(current.maxDurationMs, duration),
            stepMetrics = newStepMetrics
        )
    }
    
    fun getExecution(executionId: String): ChainExecution? = executions[executionId]
    
    fun getMetrics(chainId: String): ChainMetrics? = chainMetrics[chainId]
    
    fun analyzeChain(chainId: String): List<OptimizationSuggestion> {
        val chain = chains[chainId] ?: return emptyList()
        return optimizer.analyze(chain.steps, chainMetrics[chainId])
    }
    
    fun optimizeChain(chainId: String): Boolean {
        val chain = chains[chainId] ?: return false
        val suggestions = analyzeChain(chainId)
        
        if (suggestions.isEmpty()) return false
        
        val optimizedSteps = optimizer.applyOptimizations(chain.steps, suggestions)
        chains[chainId] = chain.copy(
            steps = optimizedSteps,
            optimizationLevel = calculateOptimizationLevel(optimizedSteps)
        )
        
        return true
    }
    
    fun resolveArgs(
        template: Map<String, String>,
        inputs: Map<String, String>,
        previousResult: String?
    ): Map<String, String> {
        return template.mapValues { (_, value) ->
            var resolved = value
            
            inputs.forEach { (key, input) ->
                resolved = resolved.replace("\${$key}", input)
            }
            
            if (previousResult != null) {
                resolved = resolved.replace("\${prev}", previousResult)
                resolved = resolved.replace("\${previous}", previousResult)
            }
            
            resolved.replace("\${timestamp}", System.currentTimeMillis().toString())
                .replace("\${date}", java.time.LocalDate.now().toString())
        }
    }
    
    fun executeParallel(
        executionId: String,
        stepIndices: List<Int>,
        executor: suspend (Int, ChainStep) -> String
    ): Map<Int, String> = runBlocking {
        val results = mutableMapOf<Int, String>()
        
        coroutineScope {
            stepIndices.map { index ->
                async {
                    val chain = executions[executionId]?.let { chains[it.chainId] }
                    val step = chain?.steps?.getOrNull(index)
                    if (step != null) {
                        val result = executor(index, step)
                        index to result
                    } else {
                        index to "Step not found"
                    }
                }
            }.awaitAll().forEach { (idx, res) ->
                results[idx] = res
            }
        }
        
        results
    }
    
    fun getExecutionGraph(chainId: String): Map<String, Any> {
        val chain = chains[chainId] ?: return emptyMap()
        return dagAnalyzer.buildDAG(chain.steps)
    }
    
    fun findCriticalPath(chainId: String): List<Int> {
        val chain = chains[chainId] ?: return emptyList()
        return dagAnalyzer.findCriticalPath(chain.steps)
    }
    
    fun formatChain(chain: ToolChain): String {
        val metrics = chainMetrics[chain.id]
        
        return buildString {
            appendLine("[Tool Chain] ${chain.name}")
            appendLine("ID: ${chain.id}")
            appendLine("Description: ${chain.description}")
            appendLine("Parallelizable: ${chain.parallelizable}")
            appendLine("Optimization Level: ${chain.optimizationLevel}")
            appendLine("Executions: ${chain.executionCount}")
            appendLine("Success Rate: ${(chain.successRate * 100).toInt()}%")
            appendLine("Avg Duration: ${chain.avgDurationMs}ms")
            if (metrics != null) {
                appendLine("Min Duration: ${metrics.minDurationMs}ms")
                appendLine("Max Duration: ${metrics.maxDurationMs}ms")
            }
            appendLine("\n[Steps]")
            chain.steps.forEachIndexed { i, step ->
                appendLine("  ${i + 1}. ${step.toolName}")
                appendLine("     Args: ${step.argsTemplate}")
                if (step.condition != null) appendLine("     Condition: ${step.condition}")
                if (step.retryCount > 0) appendLine("     Retries: ${step.retryCount}")
                if (step.timeoutMs != 30000L) appendLine("     Timeout: ${step.timeoutMs}ms")
                if (step.dependencies.isNotEmpty()) appendLine("     Dependencies: ${step.dependencies}")
            }
        }
    }
    
    fun formatExecution(exec: ChainExecution): String {
        return buildString {
            appendLine("[Execution] ${exec.executionId}")
            appendLine("Chain: ${exec.chainId}")
            appendLine("Status: ${exec.status}")
            appendLine("Inputs: ${exec.inputs}")
            if (exec.parallelResults.isNotEmpty()) {
                appendLine("\n[Parallel Results]")
                exec.parallelResults.forEach { (idx, result) ->
                    appendLine("  Step $idx: ${result.take(50)}...")
                }
            }
            appendLine("\n[Results]")
            exec.results.forEach { r ->
                val status = if (r.success) "[OK]" else "[FAIL]"
                appendLine("  ${r.stepIndex + 1}. ${r.toolName} $status (${r.durationMs}ms)")
                if (r.retryAttempt > 0) appendLine("     Retries: ${r.retryAttempt}")
                if (r.result != null) {
                    appendLine("     Result: ${r.result?.take(100)}...")
                }
                if (r.error != null) {
                    appendLine("     Error: $r.error")
                }
            }
            val duration = exec.endTime?.let { it - exec.startTime }
            if (duration != null) appendLine("\nTotal Duration: ${duration}ms")
        }
    }
}

class DAGAnalyzer {
    fun canExecuteInParallel(steps: List<ChainStep>): Boolean {
        for (step in steps) {
            if (step.condition == "decision" || step.condition?.startsWith("if_") == true) {
                return false
            }
            if (step.dependencies.isNotEmpty()) {
                return false
            }
        }
        
        val dependencyGraph = buildDependencyGraph(steps)
        return !hasCycles(dependencyGraph)
    }
    
    private fun buildDependencyGraph(steps: List<ChainStep>): Map<Int, List<Int>> {
        val graph = mutableMapOf<Int, MutableList<Int>>()
        
        for ((index, step) in steps.withIndex()) {
            graph[index] = mutableListOf()
            
            if (step.condition?.startsWith("if_") == true) {
                for (i in 0 until index) {
                    graph[index]?.add(i)
                }
            }
            
            step.dependencies.forEach { depName ->
                val depIndex = steps.indexOfFirst { it.toolName == depName }
                if (depIndex >= 0 && depIndex < index) {
                    graph[index]?.add(depIndex)
                }
            }
        }
        
        return graph
    }
    
    private fun hasCycles(graph: Map<Int, List<Int>>): Boolean {
        val visited = mutableSetOf<Int>()
        val recursionStack = mutableSetOf<Int>()
        
        fun dfs(node: Int): Boolean {
            visited.add(node)
            recursionStack.add(node)
            
            for (dep in graph[node] ?: emptyList()) {
                if (dep !in visited) {
                    if (dfs(dep)) return true
                } else if (dep in recursionStack) {
                    return true
                }
            }
            
            recursionStack.remove(node)
            return false
        }
        
        return graph.keys.any { dfs(it) }
    }
    
    fun findCriticalPath(steps: List<ChainStep>): List<Int> {
        if (steps.isEmpty()) return emptyList()
        if (steps.size == 1) return listOf(0)
        
        val estimatedDurations = steps.map { estimateDuration(it) }.toMutableList()
        
        val n = steps.size
        val earliestStart = LongArray(n) { 0 }
        val earliestFinish = LongArray(n) { 0 }
        
        for (i in 0 until n) {
            var maxPrevFinish = 0L
            for (j in 0 until i) {
                if (steps[i].dependencies.contains(steps[j].toolName) || 
                    steps[i].condition?.startsWith("if_") == true) {
                    maxPrevFinish = maxOf(maxPrevFinish, earliestFinish[j])
                }
            }
            earliestStart[i] = maxPrevFinish
            earliestFinish[i] = earliestStart[i] + estimatedDurations[i]
        }
        
        val criticalPath = mutableListOf<Int>()
        var current = earliestFinish.indices.maxByOrNull { earliestFinish[it] } ?: return emptyList()
        
        while (current >= 0) {
            criticalPath.add(0, current)
            
            var prev = -1
            var maxFinish = 0L
            for (j in 0 until current) {
                if (earliestFinish[j] <= earliestStart[current] && earliestFinish[j] >= maxFinish) {
                    maxFinish = earliestFinish[j]
                    prev = j
                }
            }
            current = prev
        }
        
        return criticalPath
    }
    
    private fun estimateDuration(step: ChainStep): Long {
        return step.timeoutMs / 2
    }
    
    fun buildDAG(steps: List<ChainStep>): Map<String, Any> {
        val nodes = steps.mapIndexed { index, step ->
            mapOf(
                "id" to index,
                "tool" to step.toolName,
                "dependencies" to step.dependencies,
                "condition" to step.condition
            )
        }
        
        val edges = mutableListOf<Map<String, Int>>()
        for ((index, step) in steps.withIndex()) {
            if (step.condition?.startsWith("if_") == true) {
                for (i in 0 until index) {
                    edges.add(mapOf("from" to i, "to" to index))
                }
            }
            step.dependencies.forEach { depName ->
                val depIndex = steps.indexOfFirst { it.toolName == depName }
                if (depIndex >= 0) {
                    edges.add(mapOf("from" to depIndex, "to" to index))
                }
            }
        }
        
        return mapOf("nodes" to nodes, "edges" to edges)
    }
}

class ChainOptimizer {
    fun optimize(steps: List<ChainStep>): List<ChainStep> {
        var optimized = steps.toMutableList()
        
        optimized = mergeSimilarSteps(optimized)
        optimized = reorderForCacheEfficiency(optimized)
        optimized = addCachingHints(optimized)
        
        return optimized
    }
    
    private fun mergeSimilarSteps(steps: MutableList<ChainStep>): MutableList<ChainStep> {
        if (steps.size < 2) return steps
        
        val merged = mutableListOf<ChainStep>()
        var i = 0
        
        while (i < steps.size) {
            val current = steps[i]
            var j = i + 1
            var canMerge = false
            
            while (j < steps.size && j - i < 3) {
                if (canMergeSteps(current, steps[j])) {
                    canMerge = true
                    break
                }
                j++
            }
            
            if (canMerge) {
                merged.add(current.copy(
                    onError = "continue"
                ))
                i = j
            } else {
                merged.add(current)
                i++
            }
        }
        
        return merged
    }
    
    private fun canMergeSteps(step1: ChainStep, step2: ChainStep): Boolean {
        return step1.toolName == step2.toolName ||
               (step1.condition == null && step2.condition == null &&
                step1.onError == "continue" && step2.onError == "continue")
    }
    
    private fun reorderForCacheEfficiency(steps: MutableList<ChainStep>): MutableList<ChainStep> {
        val staticTools = setOf("search_web", "fetch_url", "get_weather")
        val dynamicTools = setOf("analyze_data", "execute_code", "transform_data")
        
        val static = steps.filter { it.toolName in staticTools }
        val dynamic = steps.filter { it.toolName in dynamicTools }
        val others = steps.filter { it.toolName !in staticTools && it.toolName !in dynamicTools }
        
        return (static + others + dynamic).toMutableList()
    }
    
    private fun addCachingHints(steps: MutableList<ChainStep>): MutableList<ChainStep> {
        return steps.mapIndexed { index, step ->
            if (index > 0 && step.toolName in setOf("fetch_url", "search_web")) {
                step.copy(
                    argsTemplate = step.argsTemplate + ("_cache" to "true")
                )
            } else step
        }
    }
    
    fun analyze(steps: List<ChainStep>, metrics: ChainMetrics?): List<OptimizationSuggestion> {
        val suggestions = mutableListOf<OptimizationSuggestion>()
        
        if (metrics != null) {
            val slowSteps = metrics.stepMetrics.filter { it.value.avgDurationMs > 5000 }
            if (slowSteps.isNotEmpty()) {
                suggestions.add(OptimizationSuggestion(
                    type = "parallelize",
                    description = "Parallelize ${slowSteps.size} slow steps",
                    estimatedImprovement = 0.3,
                    steps = slowSteps.keys.toList()
                ))
            }
            
            val failingSteps = metrics.stepMetrics.filter { 
                it.value.executionCount > 3 && 
                it.value.successCount.toDouble() / it.value.executionCount < 0.7
            }
            if (failingSteps.isNotEmpty()) {
                suggestions.add(OptimizationSuggestion(
                    type = "add_retry",
                    description = "Add retry logic to ${failingSteps.size} unreliable steps",
                    estimatedImprovement = 0.2,
                    steps = failingSteps.keys.toList()
                ))
            }
        }
        
        if (steps.any { it.condition?.startsWith("if_") == true }) {
            suggestions.add(OptimizationSuggestion(
                type = "simplify_conditionals",
                description = "Simplify conditional logic",
                estimatedImprovement = 0.15,
                steps = steps.indices.filter { steps[it].condition?.startsWith("if_") == true }
            ))
        }
        
        return suggestions
    }
    
    fun applyOptimizations(steps: List<ChainStep>, suggestions: List<OptimizationSuggestion>): List<ChainStep> {
        var optimized = steps.toMutableList()
        
        suggestions.forEach { suggestion ->
            when (suggestion.type) {
                "add_retry" -> {
                    suggestion.steps.forEach { index ->
                        if (index < optimized.size) {
                            optimized[index] = optimized[index].copy(retryCount = 3)
                        }
                    }
                }
                "parallelize" -> {
                    val parallelizable = suggestion.steps.mapNotNull { idx ->
                        if (idx < optimized.size) optimized[idx] else null
                    }
                    if (parallelizable.isNotEmpty()) {
                        suggestion.steps.forEach { index ->
                            if (index < optimized.size) {
                                optimized[index] = optimized[index].copy(
                                    condition = "parallel",
                                    onError = "continue"
                                )
                            }
                        }
                    }
                }
            }
        }
        
        return optimized
    }
}
