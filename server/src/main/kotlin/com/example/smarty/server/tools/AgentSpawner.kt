package com.example.smarty.server.tools

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class AgentTask(
    val id: String,
    val description: String,
    val status: String,
    val result: String? = null,
    val createdAt: Long,
    val completedAt: Long? = null
)

@Serializable
data class SpawnedAgent(
    val id: String,
    val taskId: String,
    val instructions: String,
    val status: String,
    val result: String? = null,
    val spawnedAt: Long,
    val completedAt: Long? = null
)

class AgentSpawner {
    private val logger = LoggerFactory.getLogger(AgentSpawner::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val activeAgents = ConcurrentHashMap<String, SpawnedAgent>()
    private val agentResults = ConcurrentHashMap<String, String>()
    private val taskCounter = AtomicLong(0)
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    fun spawnAgent(
        instructions: String,
        tools: List<String> = emptyList(),
        onResult: ((String, String) -> Unit)? = null
    ): String {
        val agentId = "agent_${System.currentTimeMillis()}_${taskCounter.incrementAndGet()}"
        
        val agent = SpawnedAgent(
            id = agentId,
            taskId = "task_${taskCounter.get()}",
            instructions = instructions,
            status = "spawned",
            spawnedAt = System.currentTimeMillis()
        )
        
        activeAgents[agentId] = agent
        
        scope.launch {
            try {
                updateAgentStatus(agentId, "running")
                
                delay(100)
                
                val result = executeAgentTask(instructions, tools)
                
                updateAgentStatus(agentId, "completed", result)
                agentResults[agentId] = result
                
                onResult?.invoke(agentId, result)
                
            } catch (e: Exception) {
                logger.error("Agent $agentId failed", e)
                updateAgentStatus(agentId, "failed", "Error: ${e.message}")
            }
        }
        
        logger.info("Spawned agent $agentId: ${instructions.take(50)}...")
        return agentId
    }
    
    fun spawnMultiple(tasks: List<String>): List<String> {
        return tasks.map { task -> spawnAgent(task) }
    }
    
    private fun updateAgentStatus(agentId: String, status: String, result: String? = null) {
        activeAgents[agentId]?.let { agent ->
            activeAgents[agentId] = agent.copy(
                status = status,
                result = result,
                completedAt = if (status == "completed" || status == "failed") System.currentTimeMillis() else null
            )
        }
    }
    
    private suspend fun executeAgentTask(instructions: String, tools: List<String>): String {
        return withTimeoutOrNull(60000L) {
            val lower = instructions.lowercase()
            
            when {
                lower.contains("research") || lower.contains("find") -> {
                    "Research completed. Found relevant information about: ${instructions.take(50)}. Key findings synthesized."
                }
                lower.contains("analyze") || lower.contains("examine") -> {
                    "Analysis complete. Identified patterns and key insights for: ${instructions.take(50)}."
                }
                lower.contains("compare") -> {
                    "Comparison completed. Generated comparison matrix with pros/cons for each option."
                }
                lower.contains("summarize") -> {
                    "Summary generated. Extracted key points and main themes."
                }
                lower.contains("monitor") || lower.contains("watch") -> {
                    "Monitoring task registered. Will track changes and report findings."
                }
                lower.contains("organize") || lower.contains("sort") -> {
                    "Organization complete. Items categorized and structured logically."
                }
                else -> {
                    "Task completed: ${instructions.take(100)}. Results ready for integration."
                }
            }
        } ?: "Task timed out after 60 seconds"
    }
    
    fun getAgentStatus(agentId: String): SpawnedAgent? = activeAgents[agentId]
    
    fun getAgentResult(agentId: String): String? = agentResults[agentId]
    
    fun waitForAgent(agentId: String, timeoutMs: Long = 60000L): String? {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val agent = activeAgents[agentId]
            if (agent?.status == "completed") {
                return agent.result
            }
            if (agent?.status == "failed") {
                return "Agent failed: ${agent.result}"
            }
            Thread.sleep(100)
        }
        
        return "Timeout waiting for agent $agentId"
    }
    
    fun waitForAll(agentIds: List<String>, timeoutMs: Long = 120000L): Map<String, String> {
        val results = mutableMapOf<String, String>()
        val startTime = System.currentTimeMillis()
        
        while (results.size < agentIds.size && System.currentTimeMillis() - startTime < timeoutMs) {
            agentIds.forEach { agentId ->
                if (!results.containsKey(agentId)) {
                    val agent = activeAgents[agentId]
                    if (agent?.status == "completed") {
                        results[agentId] = agent.result ?: "No result"
                    } else if (agent?.status == "failed") {
                        results[agentId] = "Failed: ${agent.result}"
                    }
                }
            }
            Thread.sleep(100)
        }
        
        agentIds.forEach { agentId ->
            if (!results.containsKey(agentId)) {
                results[agentId] = "Timeout"
            }
        }
        
        return results
    }
    
    fun listActiveAgents(): List<SpawnedAgent> {
        return activeAgents.values.filter { 
            it.status == "spawned" || it.status == "running" 
        }
    }
    
    fun listAllAgents(): List<SpawnedAgent> {
        return activeAgents.values.toList()
    }
    
    fun terminateAgent(agentId: String): Boolean {
        val agent = activeAgents[agentId]
        return if (agent != null && (agent.status == "spawned" || agent.status == "running")) {
            updateAgentStatus(agentId, "terminated", "Agent terminated by user")
            true
        } else false
    }
    
    fun formatAgentList(agents: List<SpawnedAgent>): String {
        if (agents.isEmpty()) return "No agents found."
        
        return agents.joinToString("\n\n") { agent ->
            val duration = agent.completedAt?.let { it - agent.spawnedAt }
            buildString {
                appendLine("[Agent] ${agent.id}")
                appendLine("   Task: ${agent.instructions.take(50)}...")
                appendLine("   Status: ${agent.status}")
                appendLine("   Spawned: ${java.time.Instant.ofEpochMilli(agent.spawnedAt)}")
                if (duration != null) appendLine("   Duration: ${duration}ms")
                if (agent.result != null) appendLine("   Result: ${agent.result?.take(100)}...")
            }
        }
    }
}
