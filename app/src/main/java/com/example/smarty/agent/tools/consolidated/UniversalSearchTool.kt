package com.example.smarty.agent.tools.consolidated

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.agent.WebCitation
import com.example.smarty.agent.tools.base.WebResult
import com.example.smarty.agent.tools.base.WebSearchResult
import com.example.smarty.data.model.getTags
import com.example.smarty.viewmodel.managers.RecallResult
import com.example.smarty.viewmodel.managers.SearchQueryAnalysis
import com.example.smarty.viewmodel.managers.SearchResultItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Serializable
data class UniversalSearchArgs(
    @property:LLMDescription("List of 3-5 distinct search queries for deep research. Use this for complex topics.")
    val queries: List<String> = emptyList(),
    @property:LLMDescription("Single search query (legacy). Use 'queries' for research.")
    val query: String? = null,
    @property:LLMDescription("Search scope: 'web', 'internal', or 'both'")
    val scope: String = "both",
    @property:LLMDescription("Maximum results per category (1-10)")
    val limit: Int = 10,
    @property:LLMDescription("Algorithm: 'hybrid', 'semantic', 'keyword', 'vector'")
    val algorithm: String = "hybrid",
    @property:LLMDescription("Minimum relevance threshold (0.0-1.0)")
    val threshold: Double = 0.3,
    @property:LLMDescription("Filter by category")
    val category: String? = null,
    @property:LLMDescription("Filter by type: 'audio', 'image', 'document', 'todo'")
    val type: String? = null,
    @property:LLMDescription("Time range: 'today', 'week', 'month', 'all'")
    val time_range: String = "all"
)

@Serializable
data class UniversalSearchResult(
    val success: Boolean,
    val message: String,
    val web_results: List<WebResult> = emptyList(),
    val internal_results: List<UniversalInternalItem> = emptyList(),
    val recalled_facts: List<UniversalRecallItem> = emptyList(),
    val analysis: UniversalQueryAnalysis? = null
)

@Serializable
data class UniversalInternalItem(
    val id: String,
    val title: String,
    val preview: String,
    val score: Double,
    val type: String,
    val category: String?,
    val tags: List<String> = emptyList()
)

@Serializable
data class UniversalRecallItem(
    val title: String,
    val content: String,
    val reason: String,
    val score: Double
)

@Serializable
data class UniversalQueryAnalysis(
    val keywords: List<String>,
    val intent: String,
    val complexity: Int
)

/**
 * Universal Search Tool.
 * Unified interface for all search operations.
 * 100% logic-free. Delegates to SearchFeatureManager.
 */
class UniversalSearchTool(
    private val onSearchInternal: suspend (String, String?, String?, String, Int) -> List<SearchResultItem>,
    private val onAdvancedSearch: suspend (String, String, Int, Double) -> List<SearchResultItem>,
    private val onWebSearch: suspend (String, Int, String, (List<WebCitation>) -> Unit) -> WebSearchResult,
    private val onParallelWebSearch: suspend (List<String>, Int, String, (List<WebCitation>) -> Unit) -> WebSearchResult,
    private val onAnalyzeQuery: (String) -> SearchQueryAnalysis,
    private val onRecall: suspend (String, Double) -> List<RecallResult>,
    private val onCitationsFound: (List<WebCitation>) -> Unit,
    private val onStatusUpdate: (String) -> Unit
) : Tool<UniversalSearchArgs, UniversalSearchResult>(
    argsSerializer = UniversalSearchArgs.serializer(),
    resultSerializer = UniversalSearchResult.serializer(),
    name = "universal_search",
    description = """
        The primary search engine. Handles web search (parallel supported), internal note search, and semantic recall.
        Use 'queries' (plural) for deep research to check multiple sources simultaneously.

        SCOPES:
        - both (default): Web + Internal.
        - internal: Only search notes and app data.
        - web: Only search the internet.
    """.trimIndent()
) {
    private val searchJson = Json { encodeDefaults = false }

    override suspend fun execute(args: UniversalSearchArgs): UniversalSearchResult {
        return try {
            val primaryQuery = args.query ?: args.queries.firstOrNull() ?: ""
            onStatusUpdate("status_analyzing_query")
            val analysis = onAnalyzeQuery(primaryQuery)

            val webResults = mutableListOf<WebResult>()
            val internalResults = mutableListOf<UniversalInternalItem>()
            val recalledFacts = mutableListOf<UniversalRecallItem>()

            // 1. Web Search
            if (args.scope == "web" || args.scope == "both") {
                onStatusUpdate("status_searching_web")

                val res = if (args.queries.isNotEmpty()) {
                    // Parallel Search (Deep Research)
                    onParallelWebSearch(args.queries, args.limit, "general", onCitationsFound)
                } else if (!args.query.isNullOrBlank()) {
                    // Single Search (Legacy)
                    onWebSearch(args.query, args.limit, "general", onCitationsFound)
                } else {
                    WebSearchResult(false, "", "No query provided")
                }

                if (res.success) {
                    res.results?.let { webResults.addAll(it) }
                }
            }

            // 2. Internal Search
            if (args.scope == "internal" || args.scope == "both") {
                if (primaryQuery.isNotBlank()) {
                    onStatusUpdate("status_searching_notes")
                    val rawInternal = if (args.algorithm == "hybrid" && args.category == null && args.type == null && args.time_range == "all") {
                        onAdvancedSearch(primaryQuery, args.algorithm, args.limit, args.threshold)
                    } else {
                        onSearchInternal(primaryQuery, args.category, args.type, args.time_range, args.limit)
                    }

                    internalResults.addAll(rawInternal.map {
                        UniversalInternalItem(
                            id = it.note.id,
                            title = it.note.title,
                            preview = it.note.content.take(200),
                            score = it.score.toDouble(),
                            type = it.note.type.name,
                            category = it.note.categoryName,
                            tags = it.note.getTags()
                        )
                    })

                    // 3. Recall
                    onStatusUpdate("status_recalling_context")
                    val recall = onRecall(primaryQuery, args.threshold)
                    recalledFacts.addAll(recall.map {
                        UniversalRecallItem(it.title, it.content, it.reason, it.score)
                    })
                }
            }

            UniversalSearchResult(
                success = true,
                message = "search_complete",
                web_results = webResults,
                internal_results = internalResults,
                recalled_facts = recalledFacts,
                analysis = UniversalQueryAnalysis(analysis.parsedKeywords, analysis.detectedIntent, analysis.complexity)
            )
        } catch (e: Exception) {
            UniversalSearchResult(false, "batch_error_failed|${e.message}")
        }
    }
}
