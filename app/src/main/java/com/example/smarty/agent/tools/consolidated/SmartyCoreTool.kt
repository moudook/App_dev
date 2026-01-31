package com.example.smarty.agent.tools.consolidated

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.data.model.AIMemory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Serializable
data class SmartyCoreArgs(
    @property:LLMDescription("The mode: 'store' (add memory), 'retrieve' (search memories), 'analyze' (stats), 'consolidate' (abstract), 'sync' (learn from notes)")
    val mode: String,
    @property:LLMDescription("Data to store, or JSON params")
    val data: String? = null,
    @property:LLMDescription("Scope/Type: 'user_preference', 'fact', 'pattern'")
    val scope: String? = null,
    @property:LLMDescription("Search query for retrieval")
    val query: String? = null
)

@Serializable
data class SmartyResult(
    val success: Boolean,
    val message: String,
    val data: String? = null
) {
    override fun toString(): String {
        return "{success:$success|message:$message|data:${data ?: "null"}}"
    }
}

/**
 * Hybridized Smarty Core Tool.
 * 100% logic-free. Delegates to MemoryFeatureManager via callbacks.
 */
class SmartyCoreTool(
    private val onStoreMemory: suspend (String, String?) -> Unit,
    private val onRetrieveMemories: suspend (String?, Int) -> List<AIMemory>,
    private val onGetMemoryStats: suspend () -> Map<String, Any>,
    private val onConsolidate: () -> Unit,
    private val onSyncMemory: () -> Unit,
    private val onStatusUpdate: (String) -> Unit
) : Tool<SmartyCoreArgs, SmartyResult>(
    argsSerializer = SmartyCoreArgs.serializer(),
    resultSerializer = SmartyResult.serializer(),
    name = "smarty_core",
    description = """
        The long-term memory and pattern recognition engine.

        MODES:
        - store: Save a fact or preference.
        - retrieve: Search/recall memories.
        - analyze: Get high-level behavioral stats.
        - consolidate: Merge fragmented memories into abstract patterns.
        - sync: Process recent notes to build memory.
    """.trimIndent()
) {
    private val smartyJson = Json { encodeDefaults = false }

    override suspend fun execute(args: SmartyCoreArgs): SmartyResult {
        return try {
            when (args.mode) {
                "store" -> {
                    val content = args.data ?: return SmartyResult(false, "error_data_required")
                    onStatusUpdate("status_learning")
                    onStoreMemory(content, args.scope)
                    SmartyResult(true, "memory_stored_success")
                }
                "retrieve" -> {
                    onStatusUpdate("status_recalling")
                    val memories = onRetrieveMemories(args.query, 10)
                    val results = memories.map { mapOf("type" to it.type.name, "content" to it.content) }
                    SmartyResult(true, "memories_found_count|${memories.size}", smartyJson.encodeToString(results))
                }
                "analyze" -> {
                    onStatusUpdate("status_analyzing_habits")
                    val stats = onGetMemoryStats()
                    SmartyResult(true, "smarty_analysis_complete", smartyJson.encodeToString(stats))
                }
                "consolidate" -> {
                    onStatusUpdate("status_consolidating")
                    onConsolidate()
                    SmartyResult(true, "consolidation_initiated_success")
                }
                "sync" -> {
                    onStatusUpdate("status_syncing_memories")
                    onSyncMemory()
                    SmartyResult(true, "sync_initiated_success")
                }
                else -> SmartyResult(false, "error_unknown_intent")
            }
        } catch (e: Exception) {
            SmartyResult(false, "error_prefix|${e.message}")
        }
    }
}
