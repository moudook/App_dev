package com.example.smarty.agent.tools.external

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.util.Log
import com.example.smarty.agent.tools.base.WebResult
import com.example.smarty.agent.tools.base.WebSearchResult
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.getTags
import com.example.smarty.data.remote.providers.TavilySearchProvider
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.search.SemanticSearchEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

@Serializable
data class MultiQuerySearchArgs(
    @property:LLMDescription("List of search queries to execute simultaneously")
    val queries: List<SearchQuery>,
    @property:LLMDescription("Whether to search externally (web) or internally (app data) or both")
    val searchType: SearchType = SearchType.BOTH,
    @property:LLMDescription("Maximum number of results per query (1-10)")
    val maxResultsPerQuery: Int = 5
)

@Serializable
data class SearchQuery(
    @property:LLMDescription("The search query string")
    val query: String,
    @property:LLMDescription("Purpose of this specific query in the context of the overall task")
    val purpose: String? = null
)

@Serializable
enum class SearchType {
    EXTERNAL,    // Web search only
    INTERNAL,    // App data search only  
    BOTH         // Both external and internal
}

@Serializable
data class MultiQuerySearchResult(
    val success: Boolean,
    val externalResults: List<ExternalSearchResult> = emptyList(),
    val internalResults: List<MultiQueryInternalResult> = emptyList(),
    val queryBreakdown: Map<String, SearchResultSummary> = emptyMap(),
    val overallSummary: String,
    val error: String? = null
)

@Serializable
data class ExternalSearchResult(
    val query: String,
    val results: List<WebResult>,
    val aiSummary: String? = null
)

@Serializable 
data class MultiQueryInternalResult(
    val query: String,
    val results: List<AppDataResult>,
    val dataType: AppDataType
)

@Serializable
enum class AppDataType {
    NOTES,
    CATEGORIES, 
    TODOS,
    EVENTS,
    ATTACHMENTS
}

@Serializable
data class AppDataResult(
    val id: String,
    val title: String,
    val contentPreview: String,
    val dataType: AppDataType,
    val relevanceScore: Double, // 0.0 to 1.0
    val sourceInfo: String? = null
)

@Serializable
data class SearchResultSummary(
    val query: String,
    val totalResults: Int,
    val resultTypes: List<String>,
    val topResults: List<String>
)

/**
 * Enhanced search tool that supports multiple queries simultaneously and both external and internal search.
 * Combines web search (Tavily) with semantic search of internal app data.
 */
class MultiQuerySearchTool(
    private val tavilySearchProvider: TavilySearchProvider,
    private val getApiKey: () -> String?,
    private val getActiveNotes: () -> List<Note>,
    private val onCitationsFound: ((List<com.example.smarty.agent.SearchCitation>) -> Unit)? = null
) : Tool<MultiQuerySearchArgs, MultiQuerySearchResult>(
    argsSerializer = MultiQuerySearchArgs.serializer(),
    resultSerializer = MultiQuerySearchResult.serializer(),
    name = "multi_query_search",
    description = """Use this tool when you need to search for multiple pieces of information simultaneously. 
                   |Can search both web and internal app data. Use when user asks for comprehensive information gathering.""".trimMargin()
) {
    companion object {
        private const val TAG = "MultiQuerySearchTool"
        
        // Maximum number of concurrent queries to prevent overwhelming the system
        private const val MAX_CONCURRENT_QUERIES = 5
        
        // Minimum relevance score for internal search results
        private const val MIN_INTERNAL_RELEVANCE = 0.30
    }

    override suspend fun execute(args: MultiQuerySearchArgs): MultiQuerySearchResult {
        return try {
            Log.d(TAG, "Executing multi-query search with ${args.queries.size} queries, searchType: ${args.searchType}")
            
            // Validate inputs
            if (args.queries.isEmpty()) {
                return MultiQuerySearchResult(
                    success = false,
                    overallSummary = "No queries provided",
                    error = "Empty queries list"
                )
            }
            
            if (args.queries.size > MAX_CONCURRENT_QUERIES) {
                Log.w(TAG, "Query count (${args.queries.size}) exceeds max (${MAX_CONCURRENT_QUERIES}), limiting to max")
            }
            
            val limitedQueries = args.queries.take(MAX_CONCURRENT_QUERIES)
            
            // Execute searches based on search type
            val externalResults = if (args.searchType == SearchType.EXTERNAL || args.searchType == SearchType.BOTH) {
                performExternalSearch(limitedQueries, args.maxResultsPerQuery)
            } else {
                emptyList()
            }
            
            val internalResults = if (args.searchType == SearchType.INTERNAL || args.searchType == SearchType.BOTH) {
                performInternalSearch(limitedQueries, args.maxResultsPerQuery)
            } else {
                emptyList()
            }
            
            // Create query breakdown map
            val queryBreakdown = createQueryBreakdown(limitedQueries, externalResults, internalResults, args.maxResultsPerQuery)
            
            // Generate overall summary
            val overallSummary = generateOverallSummary(externalResults, internalResults, limitedQueries)
            
            MultiQuerySearchResult(
                success = true,
                externalResults = externalResults,
                internalResults = internalResults,
                queryBreakdown = queryBreakdown,
                overallSummary = overallSummary
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Multi-query search failed: ${e.message}", e)
            MultiQuerySearchResult(
                success = false,
                overallSummary = "Search failed: ${e.message}",
                error = e.message
            )
        }
    }
    
    private suspend fun performExternalSearch(queries: List<SearchQuery>, maxResults: Int): List<ExternalSearchResult> {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "No Tavily API key configured for external search")
            return emptyList()
        }
        
        return coroutineScope {
            queries.map { query ->
                async {
                    try {
                        Log.d(TAG, "Executing external search for: '${query.query}'")
                        
                        val searchResult = tavilySearchProvider.search(
                            apiKey = apiKey,
                            query = query.query,
                            maxResults = maxResults,
                            topic = "general"
                        )
                        
                        if (searchResult.success) {
                            val results = searchResult.results.map { result ->
                                WebResult(
                                    title = result.title,
                                    url = result.url,
                                    snippet = result.snippet
                                )
                            }
                            
                            // Report citations to the callback
                            if (results.isNotEmpty()) {
                                val citations = results.map { com.example.smarty.agent.SearchCitation(it.title, it.url, it.snippet) }
                                onCitationsFound?.invoke(citations)
                            }
                            
                            ExternalSearchResult(
                                query = query.query,
                                results = results,
                                aiSummary = searchResult.answer
                            )
                        } else {
                            ExternalSearchResult(
                                query = query.query,
                                results = emptyList(),
                                aiSummary = null
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "External search failed for query '${query.query}': ${e.message}", e)
                        ExternalSearchResult(
                            query = query.query,
                            results = emptyList(),
                            aiSummary = null
                        )
                    }
                }
            }.awaitAll()
        }
    }
    
    private suspend fun performInternalSearch(queries: List<SearchQuery>, maxResults: Int): List<MultiQueryInternalResult> {
        return coroutineScope {
            val allNotes = PrivacyGuard.getAiVisibleNotes(getActiveNotes())
            
            queries.flatMap { query ->
                // Search across different data types
                val noteResults = searchNotes(query.query, allNotes, maxResults)
                
                // Could extend to search other data types (categories, todos, events, etc.)
                listOf(
                    MultiQueryInternalResult(query.query, noteResults, AppDataType.NOTES)
                )
            }
        }
    }
    
    private fun searchNotes(query: String, notes: List<Note>, maxResults: Int): List<AppDataResult> {
        // Use SemanticSearchEngine for semantic search across notes
        val searchResults = SemanticSearchEngine.search(
            query = query,
            items = notes,
            textExtractor = { note ->
                // Extract searchable text from note
                val searchableTexts = mutableListOf<String>()
                
                // Title
                if (note.title.isNotBlank()) {
                    searchableTexts.add(note.title)
                }
                
                // Content
                if (note.content.isNotBlank()) {
                    searchableTexts.add(note.content)
                }
                
                // Category name
                if (!note.categoryName.isNullOrBlank()) {
                    searchableTexts.add(note.categoryName)
                }
                
                // Tags (if any)
                if (note.getTags().isNotEmpty()) {
                    searchableTexts.add(note.getTags().joinToString(" "))
                }
                
                // Summary (if any)
                if (!note.summary.isNullOrBlank()) {
                    searchableTexts.add(note.summary)
                }
                
                searchableTexts
            },
            minScore = MIN_INTERNAL_RELEVANCE
        )
        
        // Convert to AppDataResult format
        return searchResults
            .take(maxResults)
            .map { result ->
                val preview = buildString {
                    append(result.item.title)
                    if (result.item.content.length > 100) {
                        append(": ").append(result.item.content.substring(0, 100)).append("...")
                    } else if (result.item.content.isNotBlank()) {
                        append(": ").append(result.item.content)
                    }
                }
                
                AppDataResult(
                    id = result.item.id,
                    title = result.item.title,
                    contentPreview = preview,
                    dataType = AppDataType.NOTES,
                    relevanceScore = result.score,
                    sourceInfo = when (result.matchType) {
                        SemanticSearchEngine.MatchType.EXACT -> "Exact match"
                        SemanticSearchEngine.MatchType.CONTAINS -> "Contains query"
                        SemanticSearchEngine.MatchType.FUZZY_HIGH -> "High similarity"
                        SemanticSearchEngine.MatchType.FUZZY_MEDIUM -> "Medium similarity"
                        SemanticSearchEngine.MatchType.FUZZY_LOW -> "Low similarity"
                        SemanticSearchEngine.MatchType.TOKEN_MATCH -> "Token match"
                        SemanticSearchEngine.MatchType.PHONETIC -> "Phonetic match"
                        SemanticSearchEngine.MatchType.PARTIAL -> "Partial match"
                    }
                )
            }
    }
    
    private fun createQueryBreakdown(
        queries: List<SearchQuery>,
        externalResults: List<ExternalSearchResult>,
        internalResults: List<MultiQueryInternalResult>,
        maxResults: Int
    ): Map<String, SearchResultSummary> {
        return queries.associate { query ->
            val queryText = query.query
            
            // Find corresponding external results
            val extResults = externalResults.filter { it.query == queryText }
            val intResults = internalResults.filter { it.query == queryText }
            
            val totalResults = extResults.sumOf { it.results.size.toInt() } + intResults.sumOf { it.results.size.toInt() }
            
            val resultTypes = mutableSetOf<String>()
            if (extResults.isNotEmpty()) resultTypes.add("web")
            if (intResults.isNotEmpty()) resultTypes.add("internal")
            
            val topResults = (extResults.flatMap { it.results }.take(3).map { it.title } +
                             intResults.flatMap { it.results }.take(3).map { it.title }).take(3)
            
            queryText to SearchResultSummary(
                query = queryText,
                totalResults = totalResults,
                resultTypes = resultTypes.toList(),
                topResults = topResults
            )
        }
    }
    
    private fun generateOverallSummary(
        externalResults: List<ExternalSearchResult>,
        internalResults: List<MultiQueryInternalResult>,
        queries: List<SearchQuery>
    ): String {
        val extCount = externalResults.sumOf { it.results.size.toInt() }
        val intCount = internalResults.sumOf { it.results.size.toInt() }

        val queryCount = queries.size
        
        return buildString {
            append("Completed multi-query search with $queryCount queries. ")
            append("Found $extCount web results and $intCount internal results. ")
            
            if (externalResults.isNotEmpty()) {
                append("Web search completed. ")
            }
            if (internalResults.isNotEmpty()) {
                append("Internal search completed. ")
            }
            
            append("See detailed results below.")
        }
    }
}

