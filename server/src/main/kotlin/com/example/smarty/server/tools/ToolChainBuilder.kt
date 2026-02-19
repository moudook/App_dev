package com.example.smarty.server.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ToolChain(
    val id: String,
    val name: String,
    val description: String,
    val steps: List<ChainStep>,
    val createdAt: Long,
    val lastExecuted: Long? = null,
    val executionCount: Int = 0,
    val successRate: Double = 1.0
)

@Serializable
data class ChainStep(
    val toolName: String,
    val argsTemplate: Map<String, String>,
    val condition: String? = null,
    val onError: String = "stop",
    val transform: String? = null
)

@Serializable
data class ChainExecution(
    val chainId: String,
    val executionId: String,
    val inputs: Map<String, String>,
    val results: MutableList<StepResult> = mutableListOf(),
    val status: String = "running",
    val startTime: Long,
    val endTime: Long? = null
)

@Serializable
data class StepResult(
    val stepIndex: Int,
    val toolName: String,
    val args: Map<String, String>,
    val result: String?,
    val success: Boolean,
    val durationMs: Long
)

class ToolChainBuilder {
    private val logger = LoggerFactory.getLogger(ToolChainBuilder::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val chains = ConcurrentHashMap<String, ToolChain>()
    private val executions = ConcurrentHashMap<String, ChainExecution>()
    
    fun createChain(
        name: String,
        description: String,
        steps: List<ChainStep>
    ): String {
        val chainId = "chain_${name.lowercase().replace(" ", "_")}_${System.currentTimeMillis()}"
        
        val chain = ToolChain(
            id = chainId,
            name = name,
            description = description,
            steps = steps,
            createdAt = System.currentTimeMillis()
        )
        
        chains[chainId] = chain
        logger.info("Created tool chain: $name with ${steps.size} steps")
        
        return chainId
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
    
    fun deleteChain(chainId: String): Boolean = chains.remove(chainId) != null
    
    fun startExecution(
        chainId: String,
        inputs: Map<String, String>
    ): String {
        val chain = chains[chainId] ?: return "Chain not found: $chainId"
        
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
            chains[exec.chainId]?.let { chain ->
                val newCount = chain.executionCount + 1
                val newRate = if (success) {
                    (chain.successRate * chain.executionCount + 1.0) / newCount
                } else {
                    (chain.successRate * chain.executionCount) / newCount
                }
                
                chains[chain.id] = chain.copy(
                    lastExecuted = System.currentTimeMillis(),
                    executionCount = newCount,
                    successRate = newRate
                )
            }
        }
    }
    
    fun getExecution(executionId: String): ChainExecution? = executions[executionId]
    
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
    
    fun formatChain(chain: ToolChain): String {
        return buildString {
            appendLine("[Tool Chain] ${chain.name}")
            appendLine("ID: ${chain.id}")
            appendLine("Description: ${chain.description}")
            appendLine("Executions: ${chain.executionCount}")
            appendLine("Success Rate: ${(chain.successRate * 100).toInt()}%")
            appendLine("\n[Steps]")
            chain.steps.forEachIndexed { i, step ->
                appendLine("  ${i + 1}. ${step.toolName}")
                appendLine("     Args: ${step.argsTemplate}")
                if (step.condition != null) appendLine("     Condition: ${step.condition}")
            }
        }
    }
    
    fun formatExecution(exec: ChainExecution): String {
        return buildString {
            appendLine("[Execution] ${exec.executionId}")
            appendLine("Chain: ${exec.chainId}")
            appendLine("Status: ${exec.status}")
            appendLine("Inputs: ${exec.inputs}")
            appendLine("\n[Results]")
            exec.results.forEach { r ->
                val status = if (r.success) "[OK]" else "[FAIL]"
                appendLine("  ${r.stepIndex + 1}. ${r.toolName} $status (${r.durationMs}ms)")
                if (r.result != null) {
                    appendLine("     Result: ${r.result?.take(100)}...")
                }
            }
        }
    }
}
