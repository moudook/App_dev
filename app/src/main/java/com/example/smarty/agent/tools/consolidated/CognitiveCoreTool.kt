package com.example.smarty.agent.tools.consolidated

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.data.model.AIMemory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Serializable
data class CognitiveCoreArgs(
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
data class CognitiveResult(
    val success: Boolean,
    val message: String,
    val data: String? = null
) {
    override fun toString(): String {
        return "{success:$success|message:$message|data:${data ?: "null"}}"
    }
}

/**
 * Hybridized Cognitive Core Tool.
 * 100% logic-free. Delegates to MemoryFeatureManager via callbacks.
 */
class CognitiveCoreTool(
    private val onStoreMemory: suspend (String, String?) -> Unit,
    private val onRetrieveMemories: suspend (String?, Int) -> List<AIMemory>,
    private val onGetMemoryStats: suspend () -> Map<String, Any>,
    private val onConsolidate: () -> Unit,
    private val onSyncMemory: () -> Unit,
    private val onStatusUpdate: (String) -> Unit
) : Tool<CognitiveCoreArgs, CognitiveResult>(
    argsSerializer = CognitiveCoreArgs.serializer(),
    resultSerializer = CognitiveResult.serializer(),
    name = "cognitive_core",
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
    private val cognitiveJson = Json { encodeDefaults = false }

    override suspend fun execute(args: CognitiveCoreArgs): CognitiveResult {
        return try {
            when (args.mode) {
                "store" -> {
                    val content = args.data ?: return CognitiveResult(false, "Data required")
                    onStatusUpdate("Learning...")
                    onStoreMemory(content, args.scope)
                    CognitiveResult(true, "Memory stored")
                }
                "retrieve" -> {
                    onStatusUpdate("Recalling...")
                    val memories = onRetrieveMemories(args.query, 10)
                    val results = memories.map { mapOf("type" to it.type.name, "content" to it.content) }
                    CognitiveResult(true, "Found ${memories.size} memories", cognitiveJson.encodeToString(results))
                }
                "analyze" -> {
                    onStatusUpdate("Analyzing habits...")
                    val stats = onGetMemoryStats()
                    CognitiveResult(true, "Cognitive analysis complete", cognitiveJson.encodeToString(stats))
                }
                "consolidate" -> {
                    onStatusUpdate("Consolidating...")
                    onConsolidate()
                    CognitiveResult(true, "Consolidation initiated")
                }
                "sync" -> {
                    onStatusUpdate("Syncing memories...")
                    onSyncMemory()
                    CognitiveResult(true, "Note synchronization initiated")
                }
                else -> CognitiveResult(false, "Unknown mode: ${args.mode}")
            }
        } catch (e: Exception) {
            CognitiveResult(false, "Error: ${e.message}")
        }
    }
}
