package com.example.smarty.server.tools

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.*
import kotlin.random.Random

sealed class ResponseTag {
    object TASK_START : ResponseTag()
    object TASK_PROGRESS : ResponseTag()
    object TASK_COMPLETE : ResponseTag()
    object TASK_FAIL : ResponseTag()
    object FINDING : ResponseTag()
    object HELP_REQUEST : ResponseTag()
    object HELP_RESPONSE : ResponseTag()
    object TOOL_CALL : ResponseTag()
    object TOOL_RESULT : ResponseTag()
    object ERROR : ResponseTag()
    object WARNING : ResponseTag()
    object INFO : ResponseTag()
    object CRITICAL : ResponseTag()
    object RECOVERY : ResponseTag()
    data class CUSTOM(val tag: String) : ResponseTag()
}

@Serializable
data class TaggedResponse(
    val tag: ResponseTag,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val agentId: String? = null,
    val priority: Int = 5,
    val sentiment: Double = 0.0,
    val confidence: Double = 0.0,
    val urgency: Double = 0.0
)

@Serializable
data class ToolCallSpec(
    val toolName: String,
    val args: Map<String, String>,
    val callId: String,
    val agentId: String,
    val priority: Int = 5,
    val timestamp: Long = System.currentTimeMillis(),
    val maxRetries: Int = 3,
    var retryCount: Int = 0,
    val deadline: Long? = null,
    val dependencies: List<String> = emptyList(),
    val resourceRequirements: Map<String, Double> = emptyMap()
)

enum class CallResult {
    SUCCESS, FAILURE, TIMEOUT, INVALID_ARGS, RESOURCE_CONFLICT, DEADLOCK, CANCELLED
}

@Serializable
data class ToolCallResult(
    val callId: String,
    val toolName: String,
    val agentId: String,
    val result: String,
    val resultType: CallResult,
    val executionTimeMs: Long,
    val errorMessage: String? = null,
    val retryAttempt: Int = 0,
    val resourceUsage: Map<String, Double> = emptyMap()
)

sealed class StaticAnalysisResult {
    data class Valid(val response: TaggedResponse) : StaticAnalysisResult()
    data class Invalid(val reason: String, val original: String) : StaticAnalysisResult()
    data class RequiresAction(val response: TaggedResponse, val suggestedTool: String, val urgency: Double) : StaticAnalysisResult()
    data class Error(val message: String) : StaticAnalysisResult()
    data class PriorityEscalation(val response: TaggedResponse, val newPriority: Int) : StaticAnalysisResult()
}

@Serializable
data class AnalysisPattern(
    val name: String,
    val regex: String,
    val weight: Double,
    val category: String
)

@Serializable
data class ResponseClassification(
    val primaryTag: ResponseTag,
    val secondaryTags: List<ResponseTag>,
    val confidence: Double,
    val sentiment: Double,
    val urgency: Double,
    val entities: List<String>,
    val intent: String,
    val suggestedActions: List<String>
)

@Serializable
data class QueueMetrics(
    val toolName: String,
    val queueSize: Int,
    val activeCalls: Int,
    val avgWaitTime: Double,
    val avgExecutionTime: Double,
    val throughput: Double,
    val utilization: Double
)

@Serializable
data class RecoveryMetrics(
    val totalCrashes: Long,
    val recoverySuccessRate: Double,
    val avgRecoveryTime: Double,
    val crashesByType: Map<String, Long>,
    val recoveryStrategies: Map<String, Int>
)

class StaticResponseAnalyzer {
    private val logger = LoggerFactory.getLogger(StaticResponseAnalyzer::class.java)
    
    private val tagPatterns = mapOf(
        ResponseTag.TASK_START to listOf("starting", "begin", "initiat", "commenc", "launching"),
        ResponseTag.TASK_PROGRESS to listOf("progress", "updating", "status", "current", "working on"),
        ResponseTag.TASK_COMPLETE to listOf("complete", "finish", "done", "success", "finished"),
        ResponseTag.TASK_FAIL to listOf("fail", "error", "unable", "cannot", "unsuccessful"),
        ResponseTag.FINDING to listOf("found", "discovered", "detected", "identified", "located"),
        ResponseTag.HELP_REQUEST to listOf("need help", "please assist", "can someone", "request support", "stuck"),
        ResponseTag.HELP_RESPONSE to listOf("here is", "try this", "suggest", "recommend", "solution"),
        ResponseTag.TOOL_CALL to listOf("calling tool", "executing", "running", "invoking"),
        ResponseTag.ERROR to listOf("error:", "exception", "failed:", "crash", "fatal"),
        ResponseTag.WARNING to listOf("warning:", "caution", "alert", "potential issue"),
        ResponseTag.CRITICAL to listOf("urgent", "critical", "emergency", "immediate"),
        ResponseTag.RECOVERY to listOf("recovering", "restarting", "reconnecting", "resuming")
    )
    
    private val patterns = mutableListOf(
        AnalysisPattern("error_pattern", "error|fail|exception", 0.9, "error"),
        AnalysisPattern("success_pattern", "success|complete|done", 0.85, "success"),
        AnalysisPattern("progress_pattern", "progress|updating|working", 0.7, "progress"),
        AnalysisPattern("urgent_pattern", "urgent|critical|immediately|emergency", 0.95, "urgent"),
        AnalysisPattern("help_pattern", "help|assist|support|please", 0.8, "help")
    )
    
    fun analyze(response: String, agentId: String? = null): StaticAnalysisResult {
        val lowerResponse = response.lowercase()
        
        val matchedTags = tagPatterns.entries
            .filter { (_, patterns) -> patterns.any { pattern -> lowerResponse.contains(pattern) } }
            .map { it.key }
        
        if (matchedTags.isEmpty()) {
            return StaticAnalysisResult.Invalid(
                reason = "No recognizable tags found in response",
                original = response.take(100)
            )
        }
        
        val primaryTag = matchedTags.first()
        val sentiment = calculateSentiment(response)
        val urgency = calculateUrgency(response, primaryTag)
        val confidence = calculateConfidence(response, matchedTags)
        val entities = extractEntities(response)
        val intent = classifyIntent(response)
        
        val suggestedActions = determineSuggestedActions(primaryTag, response)
        
        val taggedResponse = TaggedResponse(
            tag = primaryTag,
            content = response,
            metadata = mapOf(
                "detected_tags" to matchedTags.joinToString(",") { it.toString() },
                "confidence" to confidence.toString(),
                "word_count" to response.split(" ").size.toString(),
                "sentiment" to sentiment.toString(),
                "urgency" to urgency.toString(),
                "intent" to intent,
                "entities" to entities.joinToString(",")
            ),
            agentId = agentId,
            priority = calculatePriority(primaryTag, urgency),
            sentiment = sentiment,
            confidence = confidence,
            urgency = urgency
        )
        
        if (urgency > 0.8) {
            return StaticAnalysisResult.PriorityEscalation(taggedResponse, calculatePriority(primaryTag, urgency))
        }
        
        return when (primaryTag) {
            ResponseTag.FINDING -> {
                val suggestedTool = determineSharingTool(response)
                StaticAnalysisResult.RequiresAction(taggedResponse, suggestedTool, urgency)
            }
            ResponseTag.HELP_REQUEST -> {
                StaticAnalysisResult.RequiresAction(taggedResponse, "message_agent", urgency)
            }
            ResponseTag.ERROR, ResponseTag.CRITICAL -> {
                StaticAnalysisResult.RequiresAction(taggedResponse, "log_error", urgency)
            }
            else -> StaticAnalysisResult.Valid(taggedResponse)
        }
    }
    
    private fun calculateSentiment(response: String): Double {
        val positiveWords = listOf("good", "great", "excellent", "amazing", "wonderful", "fantastic", "love", "best", "perfect", "helpful", "success", "successfully")
        val negativeWords = listOf("bad", "terrible", "awful", "horrible", "worst", "hate", "poor", "fail", "error", "bug", "issue", "problem", "failed", "failure")
        
        val words = response.lowercase().split(Regex("\\W+"))
        val positiveCount = words.count { it in positiveWords }
        val negativeCount = words.count { it in negativeWords }
        
        val total = positiveCount + negativeCount
        if (total == 0) return 0.5
        
        return ((positiveCount - negativeCount).toDouble() / total + 1.0) / 2.0
    }
    
    private fun calculateUrgency(response: String, tag: ResponseTag): Double {
        var urgency = when (tag) {
            ResponseTag.CRITICAL -> 0.9
            ResponseTag.ERROR -> 0.7
            ResponseTag.TASK_FAIL -> 0.6
            ResponseTag.HELP_REQUEST -> 0.4
            ResponseTag.TASK_PROGRESS -> 0.3
            else -> 0.1
        }
        
        val urgentIndicators = listOf("urgent", "immediately", "asap", "critical", "emergency", "deadline")
        val urgencyWords = response.lowercase().split(" ")
        
        if (urgentIndicators.any { it in urgencyWords }) {
            urgency = minOf(1.0, urgency + 0.3)
        }
        
        return urgency
    }
    
    private fun calculateConfidence(response: String, tags: List<ResponseTag>): Double {
        val wordCount = response.split(" ").size
        val baseConfidence = when {
            wordCount < 10 -> 0.3
            wordCount < 50 -> 0.6
            wordCount < 200 -> 0.8
            else -> 0.9
        }
        val tagMultiplier = (tags.size.coerceAtMost(3)) * 0.1
        return (baseConfidence + tagMultiplier).coerceAtMost(1.0)
    }
    
    private fun extractEntities(response: String): List<String> {
        val capitalized = Regex("""\b[A-Z][a-z]+(?:\s+[A-Z][a-z]+)*\b""").findAll(response)
            .map { it.value }
            .filter { it.length > 3 }
            .distinct()
            .take(10)
            .toList()
        return capitalized
    }
    
    private fun classifyIntent(response: String): String {
        val lower = response.lowercase()
        return when {
            lower.contains("what") || lower.contains("how") || lower.contains("why") -> "informational"
            lower.contains("create") || lower.contains("make") || lower.contains("build") -> "creation"
            lower.contains("fix") || lower.contains("solve") || lower.contains("resolve") -> "problem_solving"
            lower.contains("find") || lower.contains("search") || lower.contains("look for") -> "search"
            lower.contains("tell") || lower.contains("explain") || lower.contains("describe") -> "communication"
            else -> "general"
        }
    }
    
    private fun determineSuggestedActions(tag: ResponseTag, response: String): List<String> {
        return when (tag) {
            ResponseTag.FINDING -> listOf("share_finding", "analyze_result", "store_knowledge")
            ResponseTag.HELP_REQUEST -> listOf("message_agent", "delegate_task", "escalate")
            ResponseTag.ERROR -> listOf("log_error", "retry", "rollback", "notify")
            ResponseTag.CRITICAL -> listOf("emergency_protocol", "notify_all", "log_error", "create_incident")
            ResponseTag.TASK_COMPLETE -> listOf("store_result", "notify_dependent", "cleanup")
            else -> emptyList()
        }
    }
    
    private fun calculatePriority(tag: ResponseTag, urgency: Double): Int {
        val basePriority = when (tag) {
            ResponseTag.CRITICAL -> 10
            ResponseTag.ERROR -> 8
            ResponseTag.TASK_FAIL -> 7
            ResponseTag.HELP_REQUEST -> 6
            ResponseTag.FINDING -> 5
            ResponseTag.TASK_PROGRESS -> 4
            ResponseTag.TASK_COMPLETE -> 3
            else -> 5
        }
        
        val urgencyBoost = (urgency * 3).toInt()
        return (basePriority + urgencyBoost).coerceAtMost(10)
    }
        
        val urgencyBoost = (urgency * 3).toInt()
        return (basePriority + urgencyIn(1,Boost).coerce 10)
    }
    
    private fun determineSharingTool(response: String): String {
        val lower = response.lowercase()
        return when {
            lower.contains("search") || lower.contains("web") -> "parallel_search"
            lower.contains("code") || lower.contains("execute") -> "execute_code"
            lower.contains("data") || lower.contains("analyze") -> "analyze_data"
            lower.contains("url") || lower.contains("fetch") -> "fetch_url"
            else -> "share_finding"
        }
    }
    
    fun extractToolCalls(response: String): List<ToolCallSpec> {
        val toolCallPattern = Regex("""tool[_\s]call[:\s]+(\w+)[\s\(]+([^)]+)\)?""", RegexOption.IGNORE_CASE)
        val matches = toolCallPattern.findAll(response)
        
        return matches.map { match ->
            val toolName = match.groupValues[1]
            val argsStr = match.groupValues[2]
            
            val args = argsStr.split(",").associate { arg ->
                val parts = arg.split(":")
                if (parts.size == 2) {
                    parts[0].trim() to parts[1].trim().removeSurrounding("\"", "\"")
                } else {
                    "arg" to arg.trim()
                }
            }
            
            ToolCallSpec(
                toolName = toolName,
                args = args,
                callId = "call_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}",
                agentId = "",
                priority = 5
            )
        }.toList()
    }
    
    fun validateToolCallSpec(spec: ToolCallSpec): Boolean {
        if (spec.toolName.isBlank()) return false
        if (spec.callId.isBlank()) return false
        if (spec.maxRetries < 0) return false
        return true
    }
    
    fun classifyResponse(response: String, agentId: String? = null): ResponseClassification {
        val result = analyze(response, agentId)
        
        val primaryTag = when (result) {
            is StaticAnalysisResult.Valid -> result.response.tag
            is StaticAnalysisResult.RequiresAction -> result.response.tag
            is StaticAnalysisResult.PriorityEscalation -> result.response.tag
            else -> ResponseTag.INFO
        }
        
        return ResponseClassification(
            primaryTag = primaryTag,
            secondaryTags = emptyList(),
            confidence = result.let {
                when (it) {
                    is StaticAnalysisResult.Valid -> it.response.confidence
                    is StaticAnalysisResult.RequiresAction -> it.response.confidence
                    is StaticAnalysisResult.PriorityEscalation -> it.response.confidence
                    else -> 0.5
                }
            },
            sentiment = result.let {
                when (it) {
                    is StaticAnalysisResult.Valid -> it.response.sentiment
                    is StaticAnalysisResult.RequiresAction -> it.response.sentiment
                    is StaticAnalysisResult.PriorityEscalation -> it.response.sentiment
                    else -> 0.5
                }
            },
            urgency = result.let {
                when (it) {
                    is StaticAnalysisResult.Valid -> it.response.urgency
                    is StaticAnalysisResult.RequiresAction -> it.urgency
                    is StaticAnalysisResult.PriorityEscalation -> it.response.urgency
                    else -> 0.0
                }
            },
            entities = extractEntities(response),
            intent = classifyIntent(response),
            suggestedActions = determineSuggestedActions(primaryTag, response)
        )
    }
}

class ToolCallQueueManager(
    private val maxConcurrentPerTool: Int = 1,
    private val queueTimeoutMs: Long = 30000
) {
    private val logger = LoggerFactory.getLogger(ToolCallQueueManager::class.java)
    
    private val toolQueues = ConcurrentHashMap<String, PriorityChannel<ToolCallSpec>>()
    private val activeCalls = ConcurrentHashMap<String, ToolCallSpec>()
    private val callResults = ConcurrentHashMap<String, ToolCallResult>()
    private val pendingCallbacks = ConcurrentHashMap<String, MutableList<(ToolCallResult) -> Unit>>()
    private val callMetrics = ConcurrentHashMap<String, QueueMetrics>()
    
    private val toolSemaphores = ConcurrentHashMap<String, Semaphore>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private val stateMachineManager = StateMachineManager()
    private val priorityInheritance = ConcurrentHashMap<String, Int>()
    private val resourceTracking = ConcurrentHashMap<String, MutableMap<String, Double>>()
    
    private val totalEnqueued = AtomicLong(0)
    private val totalCompleted = AtomicLong(0)
    private val totalFailed = AtomicLong(0)
    
    init {
        startQueueProcessors()
        startMetricsCollection()
    }
    
    private fun startQueueProcessors() {
        scope.launch {
            while (isActive) {
                delay(50)
                processQueues()
            }
        }
    }
    
    private fun startMetricsCollection() {
        scope.launch {
            while (isActive) {
                delay(5000)
                updateMetrics()
            }
        }
    }
    
    private fun updateMetrics() {
        toolQueues.forEach { (toolName, queue) ->
            val activeCount = activeCalls.count { it.value.toolName == toolName }
            val queueSize = queue.size()
            
            val existingMetrics = callMetrics[toolName]
            val newMetrics = QueueMetrics(
                toolName = toolName,
                queueSize = queueSize,
                activeCalls = activeCount,
                avgWaitTime = existingMetrics?.avgWaitTime ?: 0.0,
                avgExecutionTime = existingMetrics?.avgExecutionTime ?: 0.0,
                throughput = calculateThroughput(toolName),
                utilization = activeCount.toDouble() / maxConcurrentPerTool
            )
            callMetrics[toolName] = newMetrics
        }
    }
    
    private fun calculateThroughput(toolName: String): Double {
        return totalCompleted.get().toDouble() / 60.0
    }
    
    private suspend fun processQueues() {
        toolQueues.forEach { (toolName, queue) ->
            if (activeCalls.count { it.value.toolName == toolName } < maxConcurrentPerTool) {
                try {
                    val call = queue.tryReceive().getOrNull()
                    if (call != null) {
                        if (checkDeadlock(call)) {
                            logger.warn("Potential deadlock detected for call ${call.callId}")
                            handleDeadlock(call)
                        } else {
                            executeToolCall(call)
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Error processing queue for $toolName", e)
                }
            }
        }
    }
    
    private fun checkDeadlock(call: ToolCallSpec): Boolean {
        val dependencies = call.dependencies
        if (dependencies.isEmpty()) return false
        
        val dependencyCalls = dependencies.mapNotNull { activeCalls[it] }
        if (dependencyCalls.isEmpty()) return false
        
        val now = System.currentTimeMillis()
        val timeoutThreshold = queueTimeoutMs
        
        return dependencyCalls.any { dep ->
            val waitingTime = now - (dep.timestamp)
            waitingTime > timeoutThreshold
        }
    }
    
    private suspend fun handleDeadlock(call: ToolCallSpec) {
        val result = ToolCallResult(
            callId = call.callId,
            toolName = call.toolName,
            agentId = call.agentId,
            result = "",
            resultType = CallResult.DEADLOCK,
            executionTimeMs = System.currentTimeMillis() - call.timestamp,
            errorMessage = "Deadlock detected: circular dependency"
        )
        
        callResults[call.callId] = result
        totalFailed.incrementAndGet()
        notifyCallbacks(call.callId, result)
        
        logger.warn("Deadlock resolved for call ${call.callId}")
    }
    
    private suspend fun executeToolCall(call: ToolCallSpec) {
        val inheritedPriority = priorityInheritance[call.agentId] ?: call.priority
        val effectivePriority = maxOf(call.priority, inheritedPriority)
        
        val machine = stateMachineManager.createToolMachine(call.callId)
        machine.transition(TransitionEvent.TOOL_ACQUIRE(call.toolName))
        
        activeCalls[call.callId] = call.copy(priority = effectivePriority)
        resourceTracking[call.callId] = mutableMapOf("cpu" to 0.0, "memory" to 0.0)
        
        val startTime = System.currentTimeMillis()
        
        machine.transition(TransitionEvent.START)
        
        val result = try {
            executeTool(call)
        } catch (e: Exception) {
            machine.transition(TransitionEvent.FAIL)
            totalFailed.incrementAndGet()
            ToolCallResult(
                callId = call.callId,
                toolName = call.toolName,
                agentId = call.agentId,
                result = "",
                resultType = CallResult.FAILURE,
                executionTimeMs = System.currentTimeMillis() - startTime,
                errorMessage = e.message
            )
        }
        
        val executionTime = System.currentTimeMillis() - startTime
        val finalResult = result.copy(executionTimeMs = executionTime)
        
        callResults[call.callId] = finalResult
        
        if (finalResult.resultType == CallResult.SUCCESS) {
            machine.transition(TransitionEvent.COMPLETE)
            totalCompleted.incrementAndGet()
        } else {
            machine.transition(TransitionEvent.FAIL)
            totalFailed.incrementAndGet()
        }
        
        machine.transition(TransitionEvent.TOOL_RELEASE(call.toolName))
        
        activeCalls.remove(call.callId)
        resourceTracking.remove(call.callId)
        
        notifyCallbacks(call.callId, finalResult)
        
        logger.debug("Tool call ${call.callId} completed with ${finalResult.resultType}")
    }
    
    private suspend fun executeTool(call: ToolCallSpec): ToolCallResult {
        delay(100)
        
        return ToolCallResult(
            callId = call.callId,
            toolName = call.toolName,
            agentId = call.agentId,
            result = "Tool executed: ${call.toolName}",
            resultType = CallResult.SUCCESS,
            executionTimeMs = 0
        )
    }
    
    suspend fun enqueueCall(call: ToolCallSpec): ToolCallResult {
        val queue = toolQueues.getOrPut(call.toolName) { 
            PriorityChannel(Channel.UNLIMITED) 
        }
        
        queue.send(call)
        totalEnqueued.incrementAndGet()
        
        val resultFuture = CompletableDeferred<ToolCallResult>()
        
        pendingCallbacks.getOrPut(call.callId) { mutableListOf() }.add { result ->
            resultFuture.complete(result)
        }
        
        return withTimeoutOrNull(queueTimeoutMs) { resultFuture.await() } ?: 
            ToolCallResult(call.callId, call.toolName, call.agentId, "", CallResult.TIMEOUT, queueTimeoutMs, "Queue timeout")
    }
    
    fun enqueueCallWithPriority(call: ToolCallSpec): ToolCallResult {
        val queue = toolQueues.getOrPut(call.toolName) { 
            PriorityChannel(Channel.UNLIMITED) 
        }
        
        queue.send(call)
        totalEnqueued.incrementAndGet()
        
        return ToolCallResult(call.callId, call.toolName, call.agentId, "", CallResult.SUCCESS, 0)
    }
    
    private fun notifyCallbacks(callId: String, result: ToolCallResult) {
        pendingCallbacks[callId]?.forEach { callback ->
            try {
                callback(result)
            } catch (e: Exception) {
                logger.error("Error in callback for $callId", e)
            }
        }
        pendingCallbacks.remove(callId)
    }
    
    fun getCallStatus(callId: String): ToolCallResult? = callResults[callId]
    
    fun getQueueStatus(): Map<String, QueueMetrics> = callMetrics.toMap()
    
    fun setPriorityInheritance(agentId: String, priority: Int) {
        priorityInheritance[agentId] = priority
    }
    
    fun formatQueueStatus(): String {
        return buildString {
            appendLine("Tool Call Queue Status")
            appendLine("=".repeat(50))
            appendLine("Total Enqueued: ${totalEnqueued.get()}")
            appendLine("Total Completed: ${totalCompleted.get()}")
            appendLine("Total Failed: ${totalFailed.get()}")
            appendLine()
            
            callMetrics.forEach { (toolName, metrics) ->
                appendLine("$toolName:")
                appendLine("  Queue: ${metrics.queueSize}, Active: ${metrics.activeCalls}/${maxConcurrentPerTool}")
                appendLine("  Utilization: ${(metrics.utilization * 100).toInt()}%, Throughput: ${"%.1f".format(metrics.throughput)}/min")
            }
        }
    }
    
    fun shutdown() {
        scope.cancel()
    }
}

class PriorityChannel<T>(capacity: Int) {
    private val queue = java.util.concurrent.PriorityBlockingQueue<Pair<Int, T>>()
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    
    suspend fun send(element: T) {
        val priority = (element as? ToolCallSpec)?.priority ?: 5
        queue.put(Pair(priority, element))
    }
    
    fun tryReceive(): java.util.concurrent.CompletableFuture<T?> {
        return java.util.concurrent.CompletableFuture.supplyAsync {
            queue.poll()?.let { it.second }
        }
    }
    
    fun size(): Int = queue.size
}

class CrashRecoveryManager(
    private val toolQueueManager: ToolCallQueueManager
) {
    private val logger = LoggerFactory.getLogger(CrashRecoveryManager::class.java)
    
    private val crashLog = ConcurrentHashMap<String, CrashEvent>()
    private val recoveryStrategies = ConcurrentHashMap<String, RecoveryStrategy>()
    private val recoveryMetrics = ConcurrentHashMap<String, RecoveryMetrics>()
    
    private val stateMachineManager = StateMachineManager()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private val crashPrediction = mutableListOf<PredictionEvent>()
    private val totalRecoveries = AtomicLong(0)
    private val successfulRecoveries = AtomicLong(0)
    private val totalRecoveryTime = AtomicLong(0)
    
    data class CrashEvent(
        val eventId: String,
        val componentType: String,
        val componentId: String,
        val crashType: CrashType,
        val timestamp: Long,
        val lastKnownState: String?,
        val recoveryAttempted: Boolean = false,
        val severity: Double = 0.5,
        val rootCause: String? = null
    )
    
    enum class CrashType {
        AGENT_CRASH, TOOL_CRASH, MESSAGE_CRASH, API_KEY_CRASH, MEMORY_CRASH, NETWORK_CRASH, UNKNOWN
    }
    
    enum class RecoveryStrategy {
        RESTART_COMPONENT, REROUTE_TOOL, ROTATE_KEY, CLEAR_QUEUE, SYSTEM_RESTART, SCALE_UP, FALLBACK
    }
    
    data class PredictionEvent(
        val componentId: String,
        val predictedFailure: Double,
        val indicators: List<String>,
        val recommendedAction: String,
        val predictedAt: Long
    )
    
    init {
        startPredictionMonitor()
    }
    
    private fun startPredictionMonitor() {
        scope.launch {
            while (isActive) {
                analyzeCrashPatterns()
                delay(30000)
            }
        }
    }
    
    private fun analyzeCrashPatterns() {
        val recentCrashes = crashLog.values
            .filter { System.currentTimeMillis() - it.timestamp < 300000 }
            .groupBy { it.componentType }
        
        recentCrashes.forEach { (componentType, crashes) ->
            if (crashes.size >= 3) {
                val indicators = detectFailureIndicators(crashes)
                crashPrediction.add(PredictionEvent(
                    componentId = componentType,
                    predictedFailure = 0.7,
                    indicators = indicators,
                    recommendedAction = determinePreventiveAction(componentType),
                    predictedAt = System.currentTimeMillis()
                ))
            }
        }
    }
    
    private fun detectFailureIndicators(crashes: List<CrashEvent>): List<String> {
        val indicators = mutableListOf<String>()
        
        val recentCrashes = crashes.sortedByDescending { it.timestamp }
        if (recentCrashes.size >= 3) {
            val timeDiffs = recentCrashes.zipWithNext().map { (a, b) -> a.timestamp - b.timestamp }
            val avgInterval = timeDiffs.average()
            if (avgInterval < 60000) {
                indicators.add("High frequency crashes (< 1 min interval)")
            }
        }
        
        crashes.groupBy { it.crashType }.maxByOrNull { it.value.size }?.let { (type, _) ->
            indicators.add("Dominant crash type: $type")
        }
        
        return indicators
    }
    
    private fun determinePreventiveAction(componentType: String): String {
        return when (componentType) {
            "AGENT" -> "Consider scaling up agent pool or restarting idle agents"
            "TOOL" -> "Check tool resource usage and potential bottlenecks"
            "MEMORY" -> "Trigger garbage collection and memory cleanup"
            "NETWORK" -> "Switch to backup network or increase timeout"
            else -> "Monitor closely for additional failures"
        }
    }
    
    fun logCrash(
        componentType: String, 
        componentId: String, 
        crashType: CrashType, 
        lastState: String?,
        severity: Double = 0.5
    ) {
        val event = CrashEvent(
            eventId = "crash_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}",
            componentType = componentType,
            componentId = componentId,
            crashType = crashType,
            timestamp = System.currentTimeMillis(),
            lastKnownState = lastState,
            severity = severity,
            rootCause = analyzeRootCause(componentType, crashType)
        )
        
        crashLog[event.eventId] = event
        
        logger.warn("CRASH DETECTED: $componentType/$componentId - ${crashType.name} [severity: ${(severity * 100).toInt()}%]")
        
        scope.launch {
            attemptRecovery(event)
        }
    }
    
    private fun analyzeRootCause(componentType: String, crashType: CrashType): String {
        return when {
            crashType == CrashType.MEMORY_CRASH -> "Memory exhaustion or leak detected"
            crashType == CrashType.NETWORK_CRASH -> "Network connectivity issues"
            crashType == CrashType.API_KEY_CRASH -> "API key invalid or rate limited"
            else -> "Unknown cause - requires investigation"
        }
    }
    
    private suspend fun attemptRecovery(event: CrashEvent) {
        val startTime = System.currentTimeMillis()
        
        val strategy = determineRecoveryStrategy(event)
        recoveryStrategies[event.componentId] = strategy
        
        val success = when (strategy) {
            RecoveryStrategy.RESTART_COMPONENT -> restartComponent(event)
            RecoveryStrategy.REROUTE_TOOL -> rerouteTool(event)
            RecoveryStrategy.ROTATE_KEY -> rotateKey(event)
            RecoveryStrategy.CLEAR_QUEUE -> clearQueue(event)
            RecoveryStrategy.SYSTEM_RESTART -> systemRestart(event)
            RecoveryStrategy.SCALE_UP -> scaleUpComponent(event)
            RecoveryStrategy.FALLBACK -> fallbackComponent(event)
        }
        
        val recoveryTime = System.currentTimeMillis() - startTime
        totalRecoveries.incrementAndGet()
        
        if (success) {
            successfulRecoveries.incrementAndGet()
            logger.info("Recovery successful for ${event.componentId} using $strategy in ${recoveryTime}ms")
        } else {
            logger.error("Recovery failed for ${event.componentId} using $strategy after ${recoveryTime}ms")
        }
        
        totalRecoveryTime.addAndGet(recoveryTime)
    }
    
    private fun determineRecoveryStrategy(event: CrashEvent): RecoveryStrategy {
        return when {
            event.severity > 0.8 -> RecoveryStrategy.SYSTEM_RESTART
            event.crashType == CrashType.AGENT_CRASH -> RecoveryStrategy.RESTART_COMPONENT
            event.crashType == CrashType.TOOL_CRASH -> RecoveryStrategy.REROUTE_TOOL
            event.crashType == CrashType.API_KEY_CRASH -> RecoveryStrategy.ROTATE_KEY
            event.crashType == CrashType.MESSAGE_CRASH -> RecoveryStrategy.CLEAR_QUEUE
            event.crashType == CrashType.MEMORY_CRASH -> RecoveryStrategy.SCALE_UP
            else -> RecoveryStrategy.FALLBACK
        }
    }
    
    private suspend fun restartComponent(event: CrashEvent): Boolean {
        logger.info("Attempting to restart component: ${event.componentId}")
        
        when (event.componentType) {
            "AGENT" -> stateMachineManager.removeAgentMachine(event.componentId)
            "TOOL" -> stateMachineManager.removeToolMachine(event.componentId)
            "MESSAGE" -> stateMachineManager.removeMessageMachine(event.componentId)
        }
        
        delay(1000)
        return true
    }
    
    private suspend fun rerouteTool(event: CrashEvent): Boolean {
        logger.info("Rerouting tool calls from: ${event.componentId}")
        return true
    }
    
    private suspend fun rotateKey(event: CrashEvent): Boolean {
        logger.info("Rotating API key for: ${event.componentId}")
        return true
    }
    
    private suspend fun clearQueue(event: CrashEvent): Boolean {
        logger.info("Clearing queue for: ${event.componentId}")
        return true
    }
    
    private suspend fun systemRestart(event: CrashEvent): Boolean {
        logger.warn("Full system restart required for: ${event.componentId}")
        delay(2000)
        return true
    }
    
    private suspend fun scaleUpComponent(event: CrashEvent): Boolean {
        logger.info("Scaling up component: ${event.componentId}")
        return true
    }
    
    private suspend fun fallbackComponent(event: CrashEvent): Boolean {
        logger.info("Using fallback for: ${event.componentId}")
        return true
    }
    
    fun getCrashHistory(): List<CrashEvent> {
        return crashLog.values.sortedByDescending { it.timestamp }
    }
    
    fun getPredictions(): List<PredictionEvent> = crashPrediction.toList()
    
    fun getRecoveryMetrics(): RecoveryMetrics {
        val crashesByType = crashLog.values.groupBy { it.crashType.name }.mapValues { it.value.size.toLong() }
        val strategiesByType = recoveryStrategies.values.groupBy { it.name }.mapValues { it.value.size }
        
        return RecoveryMetrics(
            totalCrashes = crashLog.size.toLong(),
            recoverySuccessRate = if (totalRecoveries.get() > 0) 
                successfulRecoveries.get().toDouble() / totalRecoveries.get() else 0.0,
            avgRecoveryTime = if (totalRecoveries.get() > 0) 
                totalRecoveryTime.get().toDouble() / totalRecoveries.get() else 0.0,
            crashesByType = crashesByType,
            recoveryStrategies = strategiesByType
        )
    }
    
    fun formatCrashReport(): String {
        val metrics = getRecoveryMetrics()
        
        return buildString {
            appendLine("Crash Recovery Report")
            appendLine("=".repeat(50))
            appendLine("Total Crashes: ${crashLog.size}")
            appendLine("Recovery Success Rate: ${"%.1f".format(metrics.recoverySuccessRate * 100)}%")
            appendLine("Avg Recovery Time: ${"%.0f".format(metrics.avgRecoveryTime)}ms")
            appendLine()
            
            appendLine("[Crashes by Type]")
            metrics.crashesByType.forEach { (type, count) ->
                appendLine("  $type: $count")
            }
            
            appendLine()
            appendLine("[Recent Crashes]")
            getCrashHistory().take(5).forEach { event ->
                appendLine("[${event.crashType.name}] ${event.componentType}/${event.componentId}")
                appendLine("  Time: ${java.time.Instant.ofEpochMilli(event.timestamp)}")
                appendLine("  Severity: ${(event.severity * 100).toInt()}%")
                if (event.rootCause != null) {
                    appendLine("  Root cause: ${event.rootCause}")
                }
            }
            
            if (crashPrediction.isNotEmpty()) {
                appendLine()
                appendLine("[Predictions]")
                crashPrediction.take(3).forEach { pred ->
                    appendLine("  ${pred.componentId}: ${(pred.predictedFailure * 100).toInt()}% failure risk")
                    appendLine("    Action: ${pred.recommendedAction}")
                }
            }
        }
    }
}

class HealthMonitor(
    private val stateMachineManager: StateMachineManager,
    private val crashRecovery: CrashRecoveryManager
) {
    private val logger = LoggerFactory.getLogger(HealthMonitor::class.java)
    
    private val healthScores = ConcurrentHashMap<String, HealthScore>()
    private val healthHistory = mutableListOf<HealthSnapshot>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private val isRunning = AtomicBoolean(false)
    private val alertThresholds = ConcurrentHashMap<String, Double>()
    
    data class HealthScore(
        val componentId: String,
        val componentType: String,
        val score: Double,
        val status: HealthStatus,
        val lastCheck: Long,
        val issues: List<String>,
        val trends: List<Double> = emptyList(),
        val predictedScore: Double? = null
    )
    
    data class HealthSnapshot(
        val timestamp: Long,
        val overallScore: Double,
        val componentScores: Map<String, Double>,
        val alerts: List<String>
    )
    
    enum class HealthStatus {
        HEALTHY, DEGRADED, CRITICAL, UNKNOWN
    }
    
    init {
        alertThresholds["critical"] = 0.3
        alertThresholds["degraded"] = 0.6
    }
    
    fun start() {
        isRunning.set(true)
        scope.launch {
            while (isRunning.get()) {
                performHealthCheck()
                delay(10000)
            }
        }
        logger.info("Health monitor started")
    }
    
    fun stop() {
        isRunning.set(false)
        scope.cancel()
        logger.info("Health monitor stopped")
    }
    
    private suspend fun performHealthCheck() {
        val snapshots = stateMachineManager.getAllSnapshots()
        
        snapshots.forEach { snapshot ->
            val score = calculateHealthScore(snapshot)
            val trend = calculateTrend(snapshot.currentState)
            val predicted = predictHealth(snapshot.currentState)
            
            val finalScore = score.copy(
                trends = trend,
                predictedScore = predicted
            )
            
            healthScores[snapshot.currentState] = finalScore
            
            checkAlerts(finalScore)
            
            if (score.status == HealthStatus.CRITICAL) {
                logger.warn("CRITICAL: ${snapshot.stateType} ${snapshot.currentState}")
                crashRecovery.logCrash(
                    snapshot.stateType,
                    snapshot.currentState,
                    CrashRecoveryManager.CrashType.UNKNOWN,
                    snapshot.currentState,
                    severity = 1.0 - score.score
                )
            }
        }
        
        recordSnapshot()
    }
    
    private fun calculateTrend(componentId: String): List<Double> {
        val recent = healthHistory.takeLast(10).mapNotNull { snapshot ->
            snapshot.componentScores[componentId]
        }
        return recent
    }
    
    private fun predictHealth(componentId: String): Double? {
        val trends = calculateTrend(componentId)
        if (trends.size < 5) return null
        
        val n = trends.size
        val xSum = (0 until n).sum()
        val ySum = trends.sum()
        val xySum = trends.indices.sumOf { it * trends[it] }
        val xxSum = (0 until n).sumOf { it * it }
        
        val slope = (n * xySum - xSum * ySum) / (n * xxSum - xSum * xSum)
        val intercept = (ySum - slope * xSum) / n
        
        return (intercept + slope * (n + 1)).coerceIn(0.0, 1.0)
    }
    
    private fun calculateHealthScore(snapshot: StateMachineSnapshot): HealthScore {
        val baseScore = when {
            snapshot.isError -> 0.0
            snapshot.isFinal -> 1.0
            else -> 0.8
        }
        
        val status = when {
            baseScore >= 0.9 -> HealthStatus.HEALTHY
            baseScore >= 0.6 -> HealthStatus.DEGRADED
            baseScore > 0 -> HealthStatus.CRITICAL
            else -> HealthStatus.UNKNOWN
        }
        
        val issues = mutableListOf<String>()
        if (snapshot.isError) issues.add("In error state")
        if (!snapshot.isValid) issues.add("Invalid state transition")
        
        return HealthScore(
            componentId = snapshot.currentState,
            componentType = snapshot.stateType,
            score = baseScore,
            status = status,
            lastCheck = System.currentTimeMillis(),
            issues = issues
        )
    }
    
    private fun checkAlerts(score: HealthScore) {
        val threshold = when (score.status) {
            HealthStatus.CRITICAL -> alertThresholds["critical"] ?: 0.3
            HealthStatus.DEGRADED -> alertThresholds["degraded"] ?: 0.6
            else -> 0.0
        }
        
        if (score.score < threshold) {
            logger.warn("ALERT: ${score.componentType}/${score.componentId} score ${(score.score * 100).toInt()}%")
        }
    }
    
    private fun recordSnapshot() {
        val snapshot = HealthSnapshot(
            timestamp = System.currentTimeMillis(),
            overallScore = calculateOverallHealthScore(),
            componentScores = healthScores.mapValues { it.value.score },
            alerts = healthScores.values.filter { it.status == HealthStatus.CRITICAL }.map { "${it.componentId}: ${it.status}" }
        )
        
        healthHistory.add(snapshot)
        if (healthHistory.size > 100) healthHistory.removeAt(0)
    }
    
    private fun calculateOverallHealthScore(): Double {
        if (healthScores.isEmpty()) return 0.5
        return healthScores.values.map { it.score }.average()
    }
    
    fun getOverallHealth(): HealthStatus {
        val scores = healthScores.values
        if (scores.isEmpty()) return HealthStatus.UNKNOWN
        
        val critical = scores.count { it.status == HealthStatus.CRITICAL }
        val degraded = scores.count { it.status == HealthStatus.DEGRADED }
        
        return when {
            critical > 0 -> HealthStatus.CRITICAL
            degraded > scores.size / 2 -> HealthStatus.DEGRADED
            scores.all { it.status == HealthStatus.HEALTHY } -> HealthStatus.HEALTHY
            else -> HealthStatus.DEGRADED
        }
    }
    
    fun getHealthTrends(): Map<String, List<Double>> {
        return healthScores.keys.associateWith { calculateTrend(it) }
    }
    
    fun formatHealthReport(): String {
        return buildString {
            appendLine("=".repeat(60))
            appendLine("SYSTEM HEALTH REPORT")
            appendLine("=".repeat(60))
            appendLine()
            appendLine("Overall Status: ${getOverallHealth()}")
            appendLine("Components Monitored: ${healthScores.size}")
            appendLine()
            
            healthScores.values.forEach { score ->
                val indicator = when (score.status) {
                    HealthStatus.HEALTHY -> "[OK]"
                    HealthStatus.DEGRADED -> "[WARN]"
                    HealthStatus.CRITICAL -> "[CRITICAL]"
                    HealthStatus.UNKNOWN -> "[?]"
                }
                appendLine("$indicator ${score.componentType}: ${score.componentId}")
                appendLine("    Score: ${(score.score * 100).toInt()}%")
                if (score.predictedScore != null) {
                    appendLine("    Predicted: ${(score.predictedScore * 100).toInt()}%")
                }
                if (score.issues.isNotEmpty()) {
                    appendLine("    Issues: ${score.issues.joinToString(", ")}")
                }
            }
            
            if (healthHistory.size >= 2) {
                val trend = if (healthHistory.last().overallScore > healthHistory.first().overallScore) "improving" else "declining"
                appendLine()
                appendLine("Trend: $trend over last ${healthHistory.size} snapshots")
            }
        }
    }
}

class StaticControlLayer(
    private val keyPool: ApiKeyPool
) {
    private val logger = LoggerFactory.getLogger(StaticControlLayer::class.java)
    
    private val responseAnalyzer = StaticResponseAnalyzer()
    private val toolQueueManager = ToolCallQueueManager()
    private val crashRecovery = CrashRecoveryManager(toolQueueManager)
    private val stateMachineManager = StateMachineManager()
    private val healthMonitor = HealthMonitor(stateMachineManager, crashRecovery)
    
    private val agentProcessors = ConcurrentHashMap<String, ResponseTagProcessor>()
    private val pendingResponses = ConcurrentHashMap<String, MutableList<TaggedResponse>>()
    private val responseCache = ConcurrentHashMap<String, CachedResponse>()
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isInitialized = AtomicBoolean(false)
    
    private val rateLimiters = ConcurrentHashMap<String, RateLimiter>()
    private val totalProcessed = AtomicLong(0)
    
    data class CachedResponse(
        val response: TaggedResponse,
        val cachedAt: Long,
        val expiresAt: Long
    )
    
    class RateLimiter(
        val maxRequests: Int,
        val windowMs: Long
    ) {
        private val requests = mutableListOf<Long>()
        
        fun allow(): Boolean {
            val now = System.currentTimeMillis()
            requests.removeAll { now - it > windowMs }
            
            if (requests.size >= maxRequests) return false
            
            requests.add(now)
            return true
        }
        
        fun getRemaining(): Int = maxRequests - requests.size
    }
    
    fun initialize() {
        if (!isInitialized.compareAndSet(false, true)) {
            logger.warn("Static control layer already initialized")
            return
        }
        
        logger.info("Initializing static control layer...")
        
        healthMonitor.start()
        
        logger.info("Static control layer initialized successfully")
    }
    
    fun shutdown() {
        healthMonitor.stop()
        toolQueueManager.shutdown()
        scope.cancel()
        isInitialized.set(false)
        logger.info("Static control layer shutdown complete")
    }
    
    suspend fun processAgentResponse(response: String, agentId: String): StaticAnalysisResult {
        val rateLimiter = rateLimiters.getOrPut(agentId) { RateLimiter(100, 60000) }
        
        if (!rateLimiter.allow()) {
            return StaticAnalysisResult.Invalid("Rate limit exceeded for $agentId", response.take(50))
        }
        
        val cacheKey = "${agentId}_${response.hashCode()}"
        responseCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() < cached.expiresAt) {
                totalProcessed.incrementAndGet()
                return StaticAnalysisResult.Valid(cached.response)
            }
        }
        
        val result = responseAnalyzer.analyze(response, agentId)
        
        when (result) {
            is StaticAnalysisResult.Valid -> {
                storeResponse(agentId, result.response)
                notifyInterestedAgents(agentId, result.response)
                
                responseCache[cacheKey] = CachedResponse(
                    result.response,
                    System.currentTimeMillis(),
                    System.currentTimeMillis() + 300000
                )
            }
            is StaticAnalysisResult.RequiresAction -> {
                handleRequiredAction(result)
            }
            is StaticAnalysisResult.PriorityEscalation -> {
                logger.warn("Priority escalation for ${agentId}: new priority ${result.newPriority}")
                toolQueueManager.setPriorityInheritance(agentId, result.newPriority)
            }
            is StaticAnalysisResult.Invalid -> {
                logger.warn("Invalid response from $agentId: ${result.reason}")
            }
            is StaticAnalysisResult.Error -> {
                logger.error("Analysis error: ${result.message}")
            }
        }
        
        totalProcessed.incrementAndGet()
        return result
    }
    
    private fun storeResponse(agentId: String, response: TaggedResponse) {
        pendingResponses.getOrPut(agentId) { mutableListOf() }.add(response)
        
        if ((pendingResponses[agentId]?.size ?: 0) > 100) {
            pendingResponses[agentId]?.removeAt(0)
        }
    }
    
    private suspend fun notifyInterestedAgents(fromAgentId: String, response: TaggedResponse) {
        val interestedAgents = findInterestedAgents(fromAgentId, response.tag)
        
        interestedAgents.forEach { agentId ->
            logger.debug("Notifying $agentId about ${response.tag} from $fromAgentId")
        }
    }
    
    private fun findInterestedAgents(fromAgentId: String, tag: ResponseTag): List<String> {
        return when (tag) {
            ResponseTag.FINDING -> listOf("analyzer", "coder")
            ResponseTag.HELP_REQUEST -> listOf("coordinator")
            ResponseTag.ERROR, ResponseTag.CRITICAL -> listOf("monitor", "coordinator")
            else -> emptyList()
        }
    }
    
    private suspend fun handleRequiredAction(result: StaticAnalysisResult.RequiresAction) {
        logger.info("Handling required action: ${result.suggestedTool} (urgency: ${"%.1f".format(result.urgency * 100)}%)")
        
        when (result.suggestedTool) {
            "share_finding" -> handleSharingFinding(result.response)
            "message_agent" -> handleMessageAgent(result.response)
            "log_error" -> handleError(result.response)
            "emergency_protocol" -> handleCritical(result.response)
            else -> {}
        }
    }
    
    private suspend fun handleSharingFinding(response: TaggedResponse) {
        logger.debug("Sharing finding: ${response.content.take(50)}")
    }
    
    private suspend fun handleMessageAgent(response: TaggedResponse) {
        logger.debug("Messaging agent for help")
    }
    
    private suspend fun handleError(response: TaggedResponse) {
        crashRecovery.logCrash(
            "AGENT",
            response.agentId ?: "unknown",
            CrashRecoveryManager.CrashType.AGENT_CRASH,
            response.content.take(100),
            severity = response.urgency
        )
    }
    
    private suspend fun handleCritical(response: TaggedResponse) {
        crashRecovery.logCrash(
            "AGENT",
            response.agentId ?: "unknown",
            CrashRecoveryManager.CrashType.UNKNOWN,
            response.content.take(100),
            severity = 1.0
        )
        logger.error("CRITICAL: ${response.content.take(100)}")
    }
    
    suspend fun enqueueToolCall(call: ToolCallSpec): ToolCallResult {
        return toolQueueManager.enqueueCall(call)
    }
    
    fun getSystemStatus(): String {
        return buildString {
            appendLine("=".repeat(70))
            appendLine("STATIC CONTROL LAYER STATUS")
            appendLine("=".repeat(70))
            appendLine()
            appendLine("Total Processed: ${totalProcessed.get()}")
            appendLine()
            appendLine(healthMonitor.formatHealthReport())
            appendLine()
            appendLine(toolQueueManager.formatQueueStatus())
            appendLine()
            appendLine(stateMachineManager.formatStateMachines())
            appendLine()
            appendLine(crashRecovery.formatCrashReport())
        }
    }
    
    fun getCrashReport(): String = crashRecovery.formatCrashReport()
    
    fun getRateLimitStatus(): Map<String, Int> {
        return rateLimiters.mapValues { it.value.getRemaining() }
    }
}

class ResponseTagProcessor(
    val agentId: String,
    private val outputChannel: Channel<TaggedResponse>
) {
    private val tagHistory = mutableListOf<TaggedResponse>()
    private val tagCounts = ConcurrentHashMap<String, Int>()
    
    suspend fun process(response: TaggedResponse) {
        tagHistory.add(response)
        
        val tagName = response.tag.toString()
        tagCounts[tagName] = (tagCounts[tagName] ?: 0) + 1
        
        if (tagHistory.size > 50) {
            tagHistory.removeAt(0)
        }
        
        outputChannel.send(response)
    }
    
    fun getHistory(): List<TaggedResponse> = tagHistory.toList()
    
    fun getLastTag(): ResponseTag? = tagHistory.lastOrNull()?.tag
    
    fun getTagDistribution(): Map<String, Int> = tagCounts.toMap()
    
    fun getMostCommonTag(): String? {
        return tagCounts.maxByOrNull { it.value }?.key
    }
}
