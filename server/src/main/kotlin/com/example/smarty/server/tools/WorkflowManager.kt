package com.example.smarty.server.tools

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*
import kotlin.random.Random

data class Workflow(
    val id: String,
    val name: String,
    val userId: String,
    val trigger: String,
    val actions: List<WorkflowAction>,
    val createdAt: Long,
    var lastRunAt: Long? = null,
    var nextRunAt: Long? = null,
    var isActive: Boolean = true,
    var runCount: Int = 0,
    val triggerType: TriggerType = TriggerType.SCHEDULED,
    val conditions: List<WorkflowCondition> = emptyList(),
    val errorPolicy: ErrorPolicy = ErrorPolicy.RETRY,
    val maxRetries: Int = 3
)

@Serializable
data class WorkflowAction(
    val tool: String,
    val parameters: Map<String, String>,
    val delayMs: Long = 0,
    val condition: String? = null,
    val transform: String? = null
)

@Serializable
data class WorkflowDefinition(
    val name: String,
    val trigger: String,
    val actions: List<WorkflowAction>
)

enum class TriggerType {
    SCHEDULED, EVENT_BASED, MANUAL, CONDITIONAL, ADAPTIVE
}

enum class ErrorPolicy {
    RETRY, SKIP, STOP, FALLBACK, NOTIFY
}

data class WorkflowCondition(
    val type: String,
    val parameter: String,
    val operator: String,
    val value: String
)

data class WorkflowMetrics(
    val workflowId: String,
    val totalRuns: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val avgDurationMs: Double = 0.0,
    val successRate: Double = 1.0,
    val lastRunStatus: String? = null,
    val hourlyDistribution: Map<Int, Int> = emptyMap(),
    val dayOfWeekDistribution: Map<Int, Int> = emptyMap()
)

data class WorkflowExecution(
    val workflowId: String,
    val executionId: String,
    val startTime: Long,
    var endTime: Long? = null,
    var status: ExecutionStatus = ExecutionStatus.PENDING,
    val actionResults: MutableList<ActionResult> = mutableListOf(),
    val context: MutableMap<String, Any> = mutableMapOf()
)

enum class ExecutionStatus {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED, PAUSED
}

data class ActionResult(
    val actionIndex: Int,
    val tool: String,
    val success: Boolean,
    val result: String?,
    val durationMs: Long,
    val error: String? = null
)

data class AdaptiveSchedule(
    val workflowId: String,
    val optimalHour: Int,
    val optimalMinute: Int,
    val confidence: Double,
    val basedOnHistory: Boolean
)

class WorkflowManager {
    private val logger = LoggerFactory.getLogger(WorkflowManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val workflows = ConcurrentHashMap<String, Workflow>()
    private val executions = ConcurrentHashMap<String, WorkflowExecution>()
    private val workflowMetrics = ConcurrentHashMap<String, WorkflowMetrics>()
    
    private val scheduler = AdaptiveScheduler()
    private val analytics = WorkflowAnalytics()
    private var scheduledJob: Job? = null
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    fun createWorkflow(
        userId: String,
        name: String,
        trigger: String,
        actionsJson: String
    ): String {
        val workflowId = "wf_${System.currentTimeMillis()}_${name.hashCode()}"
        
        val actions = try {
            json.decodeFromString<List<WorkflowAction>>(actionsJson)
        } catch (e: Exception) {
            parseSimpleActions(actionsJson)
        }
        
        val triggerType = detectTriggerType(trigger)
        val nextRun = if (triggerType == TriggerType.SCHEDULED) {
            calculateNextRun(trigger)
        } else null
        
        val workflow = Workflow(
            id = workflowId,
            name = name,
            userId = userId,
            trigger = trigger,
            actions = actions,
            createdAt = System.currentTimeMillis(),
            nextRunAt = nextRun,
            triggerType = triggerType
        )
        
        workflows[workflowId] = workflow
        workflowMetrics[workflowId] = WorkflowMetrics(workflowId)
        
        logger.info("Created workflow '$name' for user $userId, trigger type: $triggerType")
        
        return workflowId
    }
    
    private fun detectTriggerType(trigger: String): TriggerType {
        val lower = trigger.lowercase()
        return when {
            lower.startsWith("when") || lower.startsWith("on") -> TriggerType.EVENT_BASED
            lower.startsWith("if") || lower.contains("condition") -> TriggerType.CONDITIONAL
            lower.startsWith("learn") || lower.startsWith("adapt") -> TriggerType.ADAPTIVE
            else -> TriggerType.SCHEDULED
        }
    }
    
    fun listWorkflows(userId: String): List<Workflow> {
        return workflows.values
            .filter { it.userId == userId && it.isActive }
            .sortedBy { it.nextRunAt ?: Long.MAX_VALUE }
    }
    
    fun deleteWorkflow(userId: String, name: String): Boolean {
        val workflow = workflows.values.find { it.userId == userId && it.name == name }
        return if (workflow != null) {
            workflows.remove(workflow.id)
            workflowMetrics.remove(workflow.id)
            logger.info("Deleted workflow '$name' for user $userId")
            true
        } else {
            false
        }
    }
    
    fun getWorkflow(userId: String, name: String): Workflow? {
        return workflows.values.find { it.userId == userId && it.name == name }
    }
    
    fun getDueWorkflows(): List<Workflow> {
        val now = System.currentTimeMillis()
        return workflows.values.filter { 
            it.isActive && 
            (it.triggerType == TriggerType.SCHEDULED || it.triggerType == TriggerType.ADAPTIVE) &&
            it.nextRunAt != null && it.nextRunAt!! <= now 
        }
    }
    
    fun getWorkflowsForEvent(eventType: String): List<Workflow> {
        return workflows.values.filter {
            it.isActive && it.triggerType == TriggerType.EVENT_BASED &&
            it.trigger.contains(eventType, ignoreCase = true)
        }
    }
    
    fun evaluateConditions(workflowId: String, context: Map<String, Any>): Boolean {
        val workflow = workflows[workflowId] ?: return false
        if (workflow.conditions.isEmpty()) return true
        
        return workflow.conditions.all { condition ->
            evaluateCondition(condition, context)
        }
    }
    
    private fun evaluateCondition(condition: WorkflowCondition, context: Map<String, Any>): Boolean {
        val actualValue = context[condition.parameter]?.toString() ?: return false
        val expectedValue = condition.value
        
        return when (condition.operator) {
            "equals" -> actualValue == expectedValue
            "contains" -> actualValue.contains(expectedValue)
            "gt" -> (actualValue.toDoubleOrNull() ?: 0.0) > (expectedValue.toDoubleOrNull() ?: 0.0)
            "lt" -> (actualValue.toDoubleOrNull() ?: 0.0) < (expectedValue.toDoubleOrNull() ?: 0.0)
            "startsWith" -> actualValue.startsWith(expectedValue)
            "endsWith" -> actualValue.endsWith(expectedValue)
            else -> false
        }
    }
    
    fun startExecution(workflowId: String): String {
        val workflow = workflows[workflowId] ?: return "Workflow not found"
        
        val executionId = "exec_${workflowId}_${System.currentTimeMillis()}"
        val execution = WorkflowExecution(
            workflowId = workflowId,
            executionId = executionId,
            startTime = System.currentTimeMillis(),
            status = ExecutionStatus.RUNNING
        )
        
        executions[executionId] = execution
        logger.info("Started execution $executionId for workflow ${workflow.name}")
        
        return executionId
    }
    
    fun recordActionResult(
        executionId: String,
        actionIndex: Int,
        tool: String,
        success: Boolean,
        result: String?,
        durationMs: Long,
        error: String? = null
    ) {
        executions[executionId]?.let { exec ->
            exec.actionResults.add(ActionResult(
                actionIndex = actionIndex,
                tool = tool,
                success = success,
                result = result,
                durationMs = durationMs,
                error = error
            ))
        }
    }
    
    fun completeExecution(executionId: String, success: Boolean) {
        executions[executionId]?.let { exec ->
            exec.endTime = System.currentTimeMillis()
            exec.status = if (success) ExecutionStatus.COMPLETED else ExecutionStatus.FAILED
            
            updateWorkflowMetrics(exec.workflowId, success, exec.endTime!! - exec.startTime)
            
            workflows[exec.workflowId]?.let { wf ->
                wf.lastRunAt = exec.endTime
                wf.runCount++
                if (wf.triggerType == TriggerType.SCHEDULED || wf.triggerType == TriggerType.ADAPTIVE) {
                    wf.nextRunAt = calculateNextRun(wf.trigger)
                }
            }
            
            if (success && wf.triggerType == TriggerType.ADAPTIVE) {
                scheduler.learnFromExecution(exec)
            }
        }
    }
    
    private fun updateWorkflowMetrics(workflowId: String, success: Boolean, durationMs: Long) {
        val current = workflowMetrics[workflowId] ?: return
        
        val newTotal = current.totalRuns + 1
        val newSuccess = current.successCount + if (success) 1 else 0
        val newFailure = current.failureCount + if (!success) 1 else 0
        
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val dayOfWeek = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
        
        val newHourly = current.hourlyDistribution.toMutableMap()
        newHourly[hour] = (newHourly[hour] ?: 0) + 1
        
        val newDayOfWeek = current.dayOfWeekDistribution.toMutableMap()
        newDayOfWeek[dayOfWeek] = (newDayOfWeek[dayOfWeek] ?: 0) + 1
        
        workflowMetrics[workflowId] = current.copy(
            totalRuns = newTotal,
            successCount = newSuccess,
            failureCount = newFailure,
            avgDurationMs = ((current.avgDurationMs * current.totalRuns) + durationMs) / newTotal,
            successRate = newSuccess.toDouble() / newTotal,
            lastRunStatus = if (success) "success" else "failure",
            hourlyDistribution = newHourly,
            dayOfWeekDistribution = newDayOfWeek
        )
    }
    
    fun getOptimalSchedule(workflowId: String): AdaptiveSchedule? {
        val metrics = workflowMetrics[workflowId] ?: return null
        return scheduler.predictOptimalTime(metrics)
    }
    
    fun markExecuted(workflowId: String) {
        workflows[workflowId]?.let { wf ->
            wf.lastRunAt = System.currentTimeMillis()
            wf.runCount++
            wf.nextRunAt = calculateNextRun(wf.trigger)
        }
    }
    
    private fun calculateNextRun(trigger: String): Long? {
        val now = System.currentTimeMillis()
        val lower = trigger.lowercase().trim()
        
        val everyMatch = Regex("""every\s+(\d+)\s*(hour|hr|minute|min)s?""").find(lower)
        if (everyMatch != null) {
            val amount = everyMatch.groupValues[1].toLong()
            val unit = everyMatch.groupValues[2]
            val intervalMs = when {
                unit.startsWith("h") -> amount * 60 * 60 * 1000
                unit.startsWith("m") -> amount * 60 * 1000
                else -> 60 * 60 * 1000
            }
            return now + intervalMs
        }
        
        val dailyMatch = Regex("""daily\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""").find(lower)
        if (dailyMatch != null) {
            val hour = dailyMatch.groupValues[1].toInt()
            val minute = dailyMatch.groupValues[2].ifEmpty { "0" }.toInt()
            val ampm = dailyMatch.groupValues[3].lowercase()
            
            var adjustedHour = hour
            if (ampm == "pm" && hour < 12) adjustedHour += 12
            if (ampm == "am" && hour == 12) adjustedHour = 0
            
            val calendar = java.util.Calendar.getInstance()
            calendar.set(java.util.Calendar.HOUR_OF_DAY, adjustedHour)
            calendar.set(java.util.Calendar.MINUTE, minute)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            
            var nextTime = calendar.timeInMillis
            if (nextTime <= now) {
                nextTime += 24 * 60 * 60 * 1000
            }
            return nextTime
        }
        
        return now + 60 * 60 * 1000
    }
    
    private fun parseSimpleActions(actionsJson: String): List<WorkflowAction> {
        val actions = mutableListOf<WorkflowAction>()
        
        val toolPattern = Regex("""(\w+)\s*\(([^)]*)\)""")
        toolPattern.findAll(actionsJson).forEach { match ->
            val tool = match.groupValues[1]
            val paramsStr = match.groupValues[2]
            val params = mutableMapOf<String, String>()
            
            val paramPattern = Regex("""(\w+)\s*[=:]\s*['"]?([^'",]+)['"]?""")
            paramPattern.findAll(paramsStr).forEach { paramMatch ->
                params[paramMatch.groupValues[1]] = paramMatch.groupValues[2].trim()
            }
            
            actions.add(WorkflowAction(tool, params))
        }
        
        if (actions.isEmpty()) {
            actions.add(WorkflowAction("search_web", mapOf("query" to actionsJson)))
        }
        
        return actions
    }
    
    fun getAnalytics(workflowId: String): WorkflowMetrics? {
        return workflowMetrics[workflowId]
    }
    
    fun getAllAnalytics(): Map<String, WorkflowMetrics> {
        return workflowMetrics.toMap()
    }
    
    fun predictNextRunSuccess(workflowId: String): Double {
        val metrics = workflowMetrics[workflowId] ?: return 0.5
        return analytics.predictSuccess(metrics)
    }
    
    fun formatWorkflowList(workflows: List<Workflow>): String {
        if (workflows.isEmpty()) return "No active workflows."
        
        return workflows.joinToString("\n\n") { wf ->
            val nextRun = wf.nextRunAt?.let { 
                java.time.Instant.ofEpochMilli(it).toString() 
            } ?: "Not scheduled"
            val metrics = workflowMetrics[wf.id]
            
            buildString {
                appendLine("[Workflow] ${wf.name}")
                appendLine("   ID: ${wf.id}")
                appendLine("   Trigger: ${wf.trigger} (${wf.triggerType})")
                appendLine("   Actions: ${wf.actions.size} step(s)")
                appendLine("   Next run: $nextRun")
                appendLine("   Runs completed: ${wf.runCount}")
                if (metrics != null) {
                    appendLine("   Success Rate: ${"%.1f".format(metrics.successRate * 100)}%")
                    appendLine("   Avg Duration: ${"%.0f".format(metrics.avgDurationMs)}ms")
                }
                if (wf.conditions.isNotEmpty()) {
                    appendLine("   Conditions: ${wf.conditions.size}")
                }
            }
        }
    }
}

class AdaptiveScheduler {
    private val scheduleHistory = ConcurrentHashMap<String, MutableList<ExecutionRecord>>()
    
    data class ExecutionRecord(
        val timestamp: Long,
        val hour: Int,
        val dayOfWeek: Int,
        val success: Boolean,
        val durationMs: Long
    )
    
    fun learnFromExecution(execution: WorkflowExecution) {
        val records = scheduleHistory.getOrPut(execution.workflowId) { mutableListOf() }
        
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = execution.startTime
        
        records.add(ExecutionRecord(
            timestamp = execution.startTime,
            hour = calendar.get(java.util.Calendar.HOUR_OF_DAY),
            dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK),
            success = execution.status == ExecutionStatus.COMPLETED,
            durationMs = execution.endTime?.minus(execution.startTime) ?: 0
        ))
        
        if (records.size > 1000) {
            records.removeAt(0)
        }
    }
    
    fun predictOptimalTime(metrics: WorkflowMetrics): AdaptiveSchedule {
        if (metrics.hourlyDistribution.isEmpty()) {
            return AdaptiveSchedule(
                workflowId = metrics.workflowId,
                optimalHour = 9,
                optimalMinute = 0,
                confidence = 0.0,
                basedOnHistory = false
            )
        }
        
        val bestHour = metrics.hourlyDistribution.maxByOrNull { it.value }?.key ?: 9
        
        val successByHour = metrics.hourlyDistribution.mapValues { (hour, count) ->
            val totalAtHour = count
            val failuresAtHour = metrics.failureCount * (count.toDouble() / metrics.totalRuns)
            if (totalAtHour > 0) (totalAtHour - failuresAtHour) / totalAtHour else 0.0
        }
        
        val optimalHour = successByHour.maxByOrNull { it.value }?.key ?: bestHour
        
        val confidence = minOf(1.0, metrics.totalRuns / 100.0)
        
        return AdaptiveSchedule(
            workflowId = metrics.workflowId,
            optimalHour = optimalHour,
            optimalMinute = 0,
            confidence = confidence,
            basedOnHistory = true
        )
    }
}

class WorkflowAnalytics {
    fun predictSuccess(metrics: WorkflowMetrics): Double {
        if (metrics.totalRuns == 0) return 0.5
        
        val baseRate = metrics.successRate
        
        val recencyWeight = 0.3
        val recentRuns = minOf(metrics.totalRuns, 10)
        val recentSuccessRate = if (recentRuns > 0) {
            val recent = (1..recentRuns).takeLast(5).average()
            recent
        } else baseRate
        
        val trendWeight = 0.2
        val trend = calculateTrend(metrics)
        
        return (baseRate * (1 - recencyWeight - trendWeight) + 
                recentSuccessRate * recencyWeight + 
                trend * trendWeight).coerceIn(0.0, 1.0)
    }
    
    private fun calculateTrend(metrics: WorkflowMetrics): Double {
        if (metrics.totalRuns < 3) return 0.5
        
        val hourlySuccess = mutableMapOf<Int, Pair<Int, Int>>()
        
        for ((hour, count) in metrics.hourlyDistribution) {
            val estimatedFailures = (count * (1 - metrics.successRate)).toInt()
            hourlySuccess[hour] = Pair(count - estimatedFailures, count)
        }
        
        val sortedHours = hourlySuccess.keys.sorted()
        if (sortedHours.size < 2) return 0.5
        
        val firstHalf = sortedHours.take(sortedHours.size / 2)
        val secondHalf = sortedHours.drop(sortedHours.size / 2)
        
        val firstAvg = firstHalf.mapNotNull { hourlySuccess[it] }.map { 
            it.first.toDouble() / it.second 
        }.average()
        
        val secondAvg = secondHalf.mapNotNull { hourlySuccess[it] }.map { 
            it.first.toDouble() / it.second 
        }.average()
        
        return if (secondAvg > firstAvg) 0.6 else if (secondAvg < firstAvg) 0.4 else 0.5
    }
    
    fun detectAnomalies(metrics: WorkflowMetrics): List<String> {
        val anomalies = mutableListOf<String>()
        
        if (metrics.successRate < 0.5 && metrics.totalRuns > 5) {
            anomalies.add("Low success rate: ${"%.1f".format(metrics.successRate * 100)}%")
        }
        
        if (metrics.avgDurationMs > 60000 && metrics.totalRuns > 3) {
            anomalies.add("High average duration: ${"%.0f".format(metrics.avgDurationMs)}ms")
        }
        
        val variance = metrics.hourlyDistribution.values.map { 
            (it - metrics.hourlyDistribution.values.average()) * 
            (it - metrics.hourlyDistribution.values.average()) 
        }.average()
        val stdDev = sqrt(variance)
        
        if (stdDev > metrics.hourlyDistribution.values.average() * 0.5) {
            anomalies.add("Irregular execution pattern detected")
        }
        
        return anomalies
    }
}
