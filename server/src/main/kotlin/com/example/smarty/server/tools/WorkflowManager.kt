package com.example.smarty.server.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

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
    var runCount: Int = 0
)

@Serializable
data class WorkflowAction(
    val tool: String,
    val parameters: Map<String, String>
)

@Serializable
data class WorkflowDefinition(
    val name: String,
    val trigger: String,
    val actions: List<WorkflowAction>
)

class WorkflowManager {
    private val logger = LoggerFactory.getLogger(WorkflowManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val workflows = ConcurrentHashMap<String, Workflow>()
    
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
        
        val nextRun = calculateNextRun(trigger)
        
        val workflow = Workflow(
            id = workflowId,
            name = name,
            userId = userId,
            trigger = trigger,
            actions = actions,
            createdAt = System.currentTimeMillis(),
            nextRunAt = nextRun
        )
        
        workflows[workflowId] = workflow
        logger.info("Created workflow '$name' for user $userId, next run: $nextRun")
        
        return workflowId
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
            it.isActive && it.nextRunAt != null && it.nextRunAt!! <= now 
        }
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
    
    fun formatWorkflowList(workflows: List<Workflow>): String {
        if (workflows.isEmpty()) return "No active workflows."
        
        return workflows.joinToString("\n\n") { wf ->
            val nextRun = wf.nextRunAt?.let { 
                java.time.Instant.ofEpochMilli(it).toString() 
            } ?: "Not scheduled"
            buildString {
                appendLine("[Workflow] ${wf.name}")
                appendLine("   Trigger: ${wf.trigger}")
                appendLine("   Actions: ${wf.actions.size} step(s)")
                appendLine("   Next run: $nextRun")
                appendLine("   Runs completed: ${wf.runCount}")
            }
        }
    }
}
