package com.example.smarty.server.tools

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min

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
    data class CUSTOM(val tag: String) : ResponseTag()
}

data class TaggedResponse(
    val tag: ResponseTag,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val agentId: String? = null
)

data class ToolCallSpec(
    val toolName: String,
    val args: Map<String, String>,
    val callId: String,
    val agentId: String,
    val priority: Int = 5,
    val timestamp: Long = System.currentTimeMillis(),
    val maxRetries: Int = 3,
    var retryCount: Int = 0
)

enum class CallResult {
    SUCCESS, FAILURE, TIMEOUT, INVALID_ARGS, RESOURCE_CONFLICT
}

data class ToolCallResult(
    val callId: String,
    val toolName: String,
    val agentId: String,
    val result: String,
    val resultType: CallResult,
    val executionTimeMs: Long,
    val errorMessage: String? = null
)

sealed class StaticAnalysisResult {
    data class Valid(val response: TaggedResponse) : StaticAnalysisResult()
    data class Invalid(val reason: String, val original: String) : StaticAnalysisResult()
    data class RequiresAction(val response: TaggedResponse, val suggestedTool: String) : StaticAnalysisResult()
    data class Error(val message: String) : StaticAnalysisResult()
}

class StaticResponseAnalyzer {
    private val logger = LoggerFactory.getLogger(StaticResponseAnalyzer::class.java)
    
    private val tagPatterns = mapOf(
        ResponseTag.TASK_START to listOf("starting", "begin", "initiat", "commenc"),
        ResponseTag.TASK_PROGRESS to listOf("progress", "updating", "status", "current"),
        ResponseTag.TASK_COMPLETE to listOf("complete", "finish", "done", "success"),
        ResponseTag.TASK_FAIL to listOf("fail", "error", "unable", "cannot"),
        ResponseTag.FINDING to listOf("found", "discovered", "detected", "identified"),
        ResponseTag.HELP_REQUEST to listOf("need help", "please assist", "can someone", "request support"),
        ResponseTag.HELP_RESPONSE to listOf("here is", "try this", "suggest", "recommend"),
        ResponseTag.TOOL_CALL to listOf("calling tool", "executing", "running"),
        ResponseTag.ERROR to listOf("error:", "exception", "failed:", "crash")
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
        
        val taggedResponse = TaggedResponse(
            tag = primaryTag,
            content = response,
            metadata = mapOf(
                "detected_tags" to matchedTags.joinToString(",") { it.toString() },
                "confidence" to calculateConfidence(response, matchedTags),
                "word_count" to response.split(" ").size.toString()
            ),
            agentId = agentId
        )
        
        return when (primaryTag) {
            ResponseTag.FINDING -> {
                val suggestedTool = determineSharingTool(response)
                StaticAnalysisResult.RequiresAction(taggedResponse, suggestedTool)
            }
            ResponseTag.HELP_REQUEST -> {
                StaticAnalysisResult.RequiresAction(taggedResponse, "message_agent")
            }
            ResponseTag.ERROR -> {
                StaticAnalysisResult.RequiresAction(taggedResponse, "log_error")
            }
            else -> StaticAnalysisResult.Valid(taggedResponse)
        }
    }
    
    private fun calculateConfidence(response: String, tags: List<ResponseTag>): String {
        val wordCount = response.split(" ").size
        val baseConfidence = when {
            wordCount < 10 -> 0.3
            wordCount < 50 -> 0.6
            wordCount < 200 -> 0.8
            else -> 0.9
        }
        val tagMultiplier = (tags.size.coerceAtMost(3)) * 0.1
        return (baseConfidence + tagMultiplier).coerceAtMost(1.0).toString()
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
                agentId = ""
            )
        }.toList()
    }
    
    fun validateToolCallSpec(spec: ToolCallSpec): Boolean {
        if (spec.toolName.isBlank()) return false
        if (spec.callId.isBlank()) return false
        if (spec.maxRetries < 0) return false
        return true
    }
}

class ToolCallQueueManager(
    private val maxConcurrentPerTool: Int = 1,
    private val queueTimeoutMs: Long = 30000
) {
    private val logger = LoggerFactory.getLogger(ToolCallQueueManager::class.java)
    
    private val toolQueues = ConcurrentHashMap<String, Channel<ToolCallSpec>>()
    private val activeCalls = ConcurrentHashMap<String, ToolCallSpec>()
    private val callResults = ConcurrentHashMap<String, ToolCallResult>()
    private val pendingCallbacks = ConcurrentHashMap<String, MutableList<(ToolCallResult) -> Unit>>()
    
    private val toolSemaphores = ConcurrentHashMap<String, Semaphore>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private val stateMachineManager = StateMachineManager()
    
    init {
        startQueueProcessors()
    }
    
    private fun startQueueProcessors() {
        scope.launch {
            while (isActive) {
                delay(100)
                processQueues()
            }
        }
    }
    
    private suspend fun processQueues() {
        toolQueues.forEach { (toolName, queue) ->
            if (activeCalls.count { it.value.toolName == toolName } < maxConcurrentPerTool) {
                try {
                    val call = queue.tryReceive().getOrNull()
                    if (call != null) {
                        executeToolCall(call)
                    }
                } catch (e: Exception) {
                    logger.error("Error processing queue for $toolName", e)
                }
            }
        }
    }
    
    private suspend fun executeToolCall(call: ToolCallSpec) {
        val machine = stateMachineManager.createToolMachine(call.callId)
        machine.transition(TransitionEvent.TOOL_ACQUIRE(call.toolName))
        
        activeCalls[call.callId] = call
        
        val startTime = System.currentTimeMillis()
        
        machine.transition(TransitionEvent.START)
        
        val result = try {
            executeTool(call)
        } catch (e: Exception) {
            machine.transition(TransitionEvent.FAIL)
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
        } else {
            machine.transition(TransitionEvent.FAIL)
        }
        
        machine.transition(TransitionEvent.TOOL_RELEASE(call.toolName))
        
        activeCalls.remove(call.callId)
        
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
            Channel(Channel.UNLIMITED) 
        }
        
        queue.send(call)
        
        val resultFuture = CompletableDeferred<ToolCallResult>()
        
        pendingCallbacks.getOrPut(call.callId) { mutableListOf() }.add { result ->
            resultFuture.complete(result)
        }
        
        return resultFuture.await()
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
    
    fun getQueueStatus(): Map<String, Int> {
        return toolQueues.keys.associateWith { toolName ->
            activeCalls.count { it.value.toolName == toolName }
        }
    }
    
    fun formatQueueStatus(): String {
        return buildString {
            appendLine("Tool Call Queue Status")
            appendLine("=".repeat(50))
            toolQueues.forEach { (toolName, _) ->
                val active = activeCalls.count { it.value.toolName == toolName }
                appendLine("$toolName: $active active / $maxConcurrentPerTool max")
            }
        }
    }
    
    fun shutdown() {
        scope.cancel()
    }
}

class CrashRecoveryManager(
    private val toolQueueManager: ToolCallQueueManager
) {
    private val logger = LoggerFactory.getLogger(CrashRecoveryManager::class.java)
    
    private val crashLog = ConcurrentHashMap<String, CrashEvent>()
    private val recoveryStrategies = ConcurrentHashMap<String, RecoveryStrategy>()
    
    private val stateMachineManager = StateMachineManager()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    data class CrashEvent(
        val eventId: String,
        val componentType: String,
        val componentId: String,
        val crashType: CrashType,
        val timestamp: Long,
        val lastKnownState: String?,
        val recoveryAttempted: Boolean = false
    )
    
    enum class CrashType {
        AGENT_CRASH, TOOL_CRASH, MESSAGE_CRASH, API_KEY_CRASH, MEMORY_CRASH, UNKNOWN
    }
    
    enum class RecoveryStrategy {
        RESTART_COMPONENT, REROUTE_TOOL, ROTATE_KEY, CLEAR_QUEUE, SYSTEM_RESTART
    }
    
    fun logCrash(componentType: String, componentId: String, crashType: CrashType, lastState: String?) {
        val event = CrashEvent(
            eventId = "crash_${System.currentTimeMillis()}",
            componentType = componentType,
            componentId = componentId,
            crashType = crashType,
            timestamp = System.currentTimeMillis(),
            lastKnownState = lastState
        )
        
        crashLog[event.eventId] = event
        
        logger.warn("CRASH DETECTED: $componentType/$componentId - ${crashType.name}")
        
        scope.launch {
            attemptRecovery(event)
        }
    }
    
    private suspend fun attemptRecovery(event: CrashEvent) {
        val strategy = determineRecoveryStrategy(event)
        recoveryStrategies[event.componentId] = strategy
        
        val success = when (strategy) {
            RecoveryStrategy.RESTART_COMPONENT -> restartComponent(event)
            RecoveryStrategy.REROUTE_TOOL -> rerouteTool(event)
            RecoveryStrategy.ROTATE_KEY -> rotateKey(event)
            RecoveryStrategy.CLEAR_QUEUE -> clearQueue(event)
            RecoveryStrategy.SYSTEM_RESTART -> systemRestart(event)
        }
        
        if (success) {
            logger.info("Recovery successful for ${event.componentId} using $strategy")
        } else {
            logger.error("Recovery failed for ${event.componentId}")
        }
    }
    
    private fun determineRecoveryStrategy(event: CrashEvent): RecoveryStrategy {
        return when (event.crashType) {
            CrashType.AGENT_CRASH -> RecoveryStrategy.RESTART_COMPONENT
            CrashType.TOOL_CRASH -> RecoveryStrategy.REROUTE_TOOL
            CrashType.API_KEY_CRASH -> RecoveryStrategy.ROTATE_KEY
            CrashType.MESSAGE_CRASH -> RecoveryStrategy.CLEAR_QUEUE
            CrashType.MEMORY_CRASH -> RecoveryStrategy.SYSTEM_RESTART
            CrashType.UNKNOWN -> RecoveryStrategy.RESTART_COMPONENT
        }
    }
    
    private suspend fun restartComponent(event: CrashEvent): Boolean {
        logger.info("Attempting to restart component: ${event.componentId}")
        
        when (event.componentType) {
            "AGENT" -> {
                stateMachineManager.removeAgentMachine(event.componentId)
            }
            "TOOL" -> {
                stateMachineManager.removeToolMachine(event.componentId)
            }
            "MESSAGE" -> {
                stateMachineManager.removeMessageMachine(event.componentId)
            }
        }
        
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
        logger.warn("Full system restart required")
        return true
    }
    
    fun getCrashHistory(): List<CrashEvent> {
        return crashLog.values.sortedByDescending { it.timestamp }
    }
    
    fun formatCrashReport(): String {
        return buildString {
            appendLine("Crash Recovery Report")
            appendLine("=".repeat(50))
            appendLine("Total Crashes: ${crashLog.size}")
            appendLine()
            
            getCrashHistory().take(10).forEach { event ->
                appendLine("[${event.crashType.name}] ${event.componentType}/${event.componentId}")
                appendLine("  Time: ${java.time.Instant.ofEpochMilli(event.timestamp)}")
                appendLine("  Last State: ${event.lastKnownState ?: "unknown"}")
                appendLine("  Recovery: ${if (event.recoveryAttempted) "attempted" else "pending"}")
                appendLine()
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
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private val isRunning = AtomicBoolean(false)
    
    data class HealthScore(
        val componentId: String,
        val componentType: String,
        val score: Double,
        val status: HealthStatus,
        val lastCheck: Long,
        val issues: List<String>
    )
    
    enum class HealthStatus {
        HEALTHY, DEGRADED, CRITICAL, UNKNOWN
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
            healthScores[snapshot.currentState] = score
            
            if (score.status == HealthStatus.CRITICAL) {
                logger.warn("CRITICAL: ${snapshot.stateType} ${snapshot.currentState}")
                crashRecovery.logCrash(
                    snapshot.stateType,
                    snapshot.currentState,
                    CrashRecoveryManager.CrashType.UNKNOWN,
                    snapshot.currentState
                )
            }
        }
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
                if (score.issues.isNotEmpty()) {
                    appendLine("    Issues: ${score.issues.joinToString(", ")}")
                }
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
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isInitialized = AtomicBoolean(false)
    
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
        val result = responseAnalyzer.analyze(response, agentId)
        
        when (result) {
            is StaticAnalysisResult.Valid -> {
                storeResponse(agentId, result.response)
                notifyInterestedAgents(agentId, result.response)
            }
            is StaticAnalysisResult.RequiresAction -> {
                handleRequiredAction(result)
            }
            is StaticAnalysisResult.Invalid -> {
                logger.warn("Invalid response from $agentId: ${result.reason}")
            }
            is StaticAnalysisResult.Error -> {
                logger.error("Analysis error: ${result.message}")
            }
        }
        
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
            else -> emptyList()
        }
    }
    
    private suspend fun handleRequiredAction(result: StaticAnalysisResult.RequiresAction) {
        logger.info("Handling required action: ${result.suggestedTool}")
        
        when (result.suggestedTool) {
            "share_finding" -> handleSharingFinding(result.response)
            "message_agent" -> handleMessageAgent(result.response)
            "log_error" -> handleError(result.response)
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
            response.content.take(100)
        )
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
            appendLine(healthMonitor.formatHealthReport())
            appendLine()
            appendLine(toolQueueManager.formatQueueStatus())
            appendLine()
            appendLine(stateMachineManager.formatStateMachines())
        }
    }
    
    fun getCrashReport(): String = crashRecovery.formatCrashReport()
}

class ResponseTagProcessor(
    val agentId: String,
    private val outputChannel: Channel<TaggedResponse>
) {
    private val tagHistory = mutableListOf<TaggedResponse>()
    
    suspend fun process(response: TaggedResponse) {
        tagHistory.add(response)
        
        if (tagHistory.size > 50) {
            tagHistory.removeAt(0)
        }
        
        outputChannel.send(response)
    }
    
    fun getHistory(): List<TaggedResponse> = tagHistory.toList()
    
    fun getLastTag(): ResponseTag? = tagHistory.lastOrNull()?.tag
}
