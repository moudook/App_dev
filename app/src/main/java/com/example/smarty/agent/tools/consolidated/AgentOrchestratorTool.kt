package com.example.smarty.agent.tools.consolidated

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.data.model.Note
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AgentOrchestratorArgs(
    @property:LLMDescription("The workflow: 'batch_operation' (bulk actions), 'deep_research' (comprehensive study)")
    val workflow: String,
    @property:LLMDescription("Parameters for the workflow")
    val parameters: OrchestratorParameters
)

@Serializable
data class OrchestratorParameters(
    // Batch params
    val target_type: String? = null, // notes, todos
    val criteria: String? = null, // e.g., "keyword:meeting", "contains_x"
    val actions: List<String>? = null, // archive, delete

    // Research params
    val research_query: String? = null,
    val focus_areas: List<String>? = null,
    val search_depth: Int = 3
)

@Serializable
data class OrchestratorResult(
    val success: Boolean,
    val message: String,
    val data: String? = null
) {
    override fun toString(): String {
        return "{success:$success|message:$message|data:${data ?: "null"}}"
    }
}

/**
 * Hybridized Orchestrator Tool.
 * 100% logic-free. Delegates all execution to centralized managers via callbacks.
 */
class AgentOrchestratorTool(
    private val onSearchNotes: suspend (String?, String?, String?, String, Int) -> List<com.example.smarty.viewmodel.managers.SearchResultItem>,
    private val getTavilyApiKey: () -> String?,
    private val onBulkArchive: (List<String>) -> Unit,
    private val onBulkDelete: (List<String>) -> Unit,
    private val onDeepResearch: (String, String, List<String>?, Int) -> Unit,
    private val onStatusUpdate: (String) -> Unit
) : Tool<AgentOrchestratorArgs, OrchestratorResult>(
    argsSerializer = AgentOrchestratorArgs.serializer(),
    resultSerializer = OrchestratorResult.serializer(),
    name = "agent_orchestrator",
    description = """
        Handles complex, multi-step workflows.

        WORKFLOWS:
        - batch_operation: Perform actions on multiple items. usage: workflow="batch_operation", parameters={target_type="notes", criteria="keyword:meeting", actions=["archive"]}
        - deep_research: Conduct multi-step web research and save report. usage: workflow="deep_research", parameters={research_query="Quantum Computing", focus_areas=["History", "Future"], search_depth=3}
    """.trimIndent()
) {
    override suspend fun execute(args: AgentOrchestratorArgs): OrchestratorResult {
        return try {
            when (args.workflow) {
                "batch_operation" -> {
                    onStatusUpdate("Running batch action...")
                    executeBatchOperation(args.parameters)
                }
                "deep_research" -> {
                    onStatusUpdate("Initiating research...")
                    executeDeepResearch(args.parameters)
                }
                else -> OrchestratorResult(false, "Unknown workflow: ${args.workflow}")
            }
        } catch (e: Exception) {
            OrchestratorResult(false, "Error: ${e.message}")
        }
    }

    private suspend fun executeBatchOperation(params: OrchestratorParameters): OrchestratorResult {
        val targetType = params.target_type ?: return OrchestratorResult(false, "target_type required")
        val actions = params.actions ?: return OrchestratorResult(false, "actions required")
        val criteria = params.criteria

        if (targetType != "notes") {
            return OrchestratorResult(false, "Only 'notes' target_type supported currently")
        }

        // Use central search manager for filtering logic
        val query = if (criteria?.startsWith("keyword:") == true) {
            criteria.removePrefix("keyword:").trim()
        } else criteria

        val results = onSearchNotes(query, null, null, "all", 50)

        if (results.isEmpty()) {
            return OrchestratorResult(true, "No items found matching criteria.")
        }

        val noteIds = results.map { it.note.id }

        // HYBRIDIZED: Delegate to Manager-bound callbacks
        actions.forEach { action ->
            when (action.lowercase()) {
                "archive" -> onBulkArchive(noteIds)
                "delete" -> onBulkDelete(noteIds)
            }
        }

        return OrchestratorResult(true, "Batch operation initiated for ${results.size} items.")
    }

    private fun executeDeepResearch(params: OrchestratorParameters): OrchestratorResult {
        val topic = params.research_query ?: return OrchestratorResult(false, "research_query required")
        val apiKey = getTavilyApiKey() ?: return OrchestratorResult(false, "Web search not configured")

        // HYBRIDIZED: Delegate to WorkflowManager via callback
        onDeepResearch(topic, apiKey, params.focus_areas, params.search_depth)

        return OrchestratorResult(true, "Deep research initiated for topic: $topic. I will notify you when the report is ready.")
    }
}
