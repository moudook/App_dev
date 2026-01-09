package com.example.smarty.agent.tools.external

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.util.Log
import com.example.smarty.agent.tools.base.WebResult
import com.example.smarty.data.model.*
import com.example.smarty.data.model.getTags
import com.example.smarty.data.remote.providers.TavilySearchProvider
import com.example.smarty.util.AdaptiveSemanticSearchEngine
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.search.SemanticSearchEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import com.example.smarty.agent.SearchCitation
import com.example.smarty.data.model.CalendarEvent

/**
 * Universal Search Tool that searches across all data types and returns IDs
 * Automatically adapts based on database content
 */
@Serializable
data class UniversalSearchArgs(
    @property:LLMDescription("The search query to find across all data types")
    val query: String,
    @property:LLMDescription("Maximum number of results to return")
    val maxResults: Int = 10,
    @property:LLMDescription("Minimum relevance score threshold (0.0-1.0)")
    val minRelevance: Double = 0.3,
    @property:LLMDescription("Which data types to search: 'notes', 'categories', 'todos', 'events', 'all'")
    val searchTypes: List<UniversalSearchType> = listOf(UniversalSearchType.ALL),
    @property:LLMDescription("Whether to include web search in addition to internal data")
    val includeWeb: Boolean = true,
    @property:LLMDescription("Whether to include archived items in search")
    val includeArchived: Boolean = false
)

@Serializable
enum class UniversalSearchType {
    NOTES,
    CATEGORIES,
    TODOS,
    EVENTS,
    ATTACHMENTS,
    ALL
}

@Serializable
data class UniversalSearchResult(
    val success: Boolean,
    val results: List<UniversalSearchItem>,
    val webResults: List<WebResult>,
    val searchStats: SearchStatistics,
    val overallSummary: String,
    val confidenceScore: Double,
    val error: String? = null
)

@Serializable
data class UniversalSearchItem(
    val id: String,
    val title: String,
    val contentPreview: String,
    val dataType: String,
    val relevanceScore: Double,
    val matchType: String,
    val sourceInfo: String? = null,
    val tags: List<String> = emptyList(),
    val category: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

@Serializable
data class SearchStatistics(
    val totalItemsSearched: Int,
    val totalResults: Int,
    val searchTypes: List<String>,
    val avgRelevance: Double,
    val searchTimeMs: Long
)

/**
 * Universal Search Tool that searches across all data types and returns IDs
 * Automatically adapts based on database content
 */
class UniversalSearchTool(
    private val tavilySearchProvider: TavilySearchProvider,
    private val getApiKey: () -> String?,
    private val getActiveNotes: () -> List<Note>,
    private val getArchivedNotes: () -> List<Note>,
    private val getCategories: () -> List<Category>,
    private val getEvents: () -> List<CalendarEvent>,
    private val getTodos: () -> List<TodoItem>,
    private val onCitationsFound: ((List<SearchCitation>) -> Unit)? = null
) : Tool<UniversalSearchArgs, UniversalSearchResult>(
    argsSerializer = UniversalSearchArgs.serializer(),
    resultSerializer = UniversalSearchResult.serializer(),
    name = "universal_search",
    description = """The most comprehensive search tool. Searches across ALL data types (notes, categories, events, todos) and returns item IDs. 
                   |Automatically adapts search strategy based on database content. Use for ANY search request.""".trimMargin()
) {
    companion object {
        private const val TAG = "UniversalSearchTool"
    }

    override suspend fun execute(args: UniversalSearchArgs): UniversalSearchResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            Log.d(TAG, "Executing universal search: '${args.query}', types: ${args.searchTypes}")
            
            // Get all data based on search types and includeArchived flag
            val allData = collectAllData(args)
            
            // Use AdaptiveSemanticSearchEngine to analyze and search
            val adaptiveEngine = AdaptiveSemanticSearchEngine()
            
            // Perform adaptive search across all collected data
            val searchResults = performAdaptiveSearch(args.query, allData, adaptiveEngine, args)
            
            // Perform web search if requested
            val webResults = if (args.includeWeb) {
                performWebSearch(args.query)
            } else {
                emptyList()
            }
            
            // Calculate statistics
            val searchStats = calculateSearchStatistics(allData, searchResults, System.currentTimeMillis() - startTime)
            
            // Calculate confidence score
            val confidenceScore = calculateConfidenceScore(searchResults, webResults)
            
            // Generate summary
            val overallSummary = generateSummary(searchResults, webResults, searchStats)
            
            UniversalSearchResult(
                success = true,
                results = searchResults,
                webResults = webResults,
                searchStats = searchStats,
                overallSummary = overallSummary,
                confidenceScore = confidenceScore
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Universal search failed: ${e.message}", e)
            UniversalSearchResult(
                success = false,
                results = emptyList(),
                webResults = emptyList(),
                searchStats = SearchStatistics(0, 0, emptyList(), 0.0, System.currentTimeMillis() - startTime),
                overallSummary = "Search failed: ${e.message}",
                confidenceScore = 0.0,
                error = e.message
            )
        }
    }
    
    private fun collectAllData(args: UniversalSearchArgs): List<SearchableItem> {
        val allItems = mutableListOf<SearchableItem>()
        
        val shouldSearchNotes = args.searchTypes.contains(UniversalSearchType.ALL) || args.searchTypes.contains(UniversalSearchType.NOTES)
        val shouldSearchCategories = args.searchTypes.contains(UniversalSearchType.ALL) || args.searchTypes.contains(UniversalSearchType.CATEGORIES)
        val shouldSearchTodos = args.searchTypes.contains(UniversalSearchType.ALL) || args.searchTypes.contains(UniversalSearchType.TODOS)
        val shouldSearchEvents = args.searchTypes.contains(UniversalSearchType.ALL) || args.searchTypes.contains(UniversalSearchType.EVENTS)
        
        if (shouldSearchNotes) {
            val notes = if (args.includeArchived) {
                PrivacyGuard.getAiVisibleNotes(getActiveNotes() + getArchivedNotes())
            } else {
                PrivacyGuard.getAiVisibleNotes(getActiveNotes())
            }
            
            allItems.addAll(notes.map { note ->
                SearchableItem(
                    id = note.id,
                    title = note.title,
                    content = note.content,
                    dataType = "note",
                    tags = note.getTags(),
                    category = note.categoryName,
                    createdAt = note.createdAt,
                    updatedAt = note.updatedAt
                )
            })
        }
        
        if (shouldSearchCategories) {
            val categories = getCategories()
            allItems.addAll(categories.map { category ->
                SearchableItem(
                    id = category.id,
                    title = category.name,
                    content = category.description ?: "",
                    dataType = "category",
                    tags = emptyList(),
                    category = null,
                    createdAt = null,
                    updatedAt = null
                )
            })
        }
        
        if (shouldSearchEvents) {
            val events = getEvents()
            allItems.addAll(events.map { event ->
                SearchableItem(
                    id = event.id,
                    title = event.title,
                    content = event.description ?: "",
                    dataType = "event",
                    tags = emptyList(), // CalendarEvent doesn't have tags property
                    category = event.location, // Use location as category if available
                    createdAt = event.createdAt,
                    updatedAt = event.updatedAt
                )
            })
        }
        
        if (shouldSearchTodos) {
            // Assuming we get todos from notes that have todo items
            val allNotes = if (args.includeArchived) {
                PrivacyGuard.getAiVisibleNotes(getActiveNotes() + getArchivedNotes())
            } else {
                PrivacyGuard.getAiVisibleNotes(getActiveNotes())
            }
            
            allNotes.forEach { note ->
                val todos = note.getTodos()
                todos.forEach { todo ->
                    allItems.add(SearchableItem(
                        id = "${note.id}_${todo.id}", // Composite ID
                        title = todo.text,
                        content = "Todo in note: ${note.title}. ${note.content}",
                        dataType = "todo",
                        tags = note.getTags(),
                        category = note.categoryName,
                        createdAt = note.createdAt,
                        updatedAt = note.updatedAt
                    ))
                }
            }
        }
        
        return allItems
    }
    
    private fun performAdaptiveSearch(
        query: String,
        allData: List<SearchableItem>,
        adaptiveEngine: AdaptiveSemanticSearchEngine,
        args: UniversalSearchArgs
    ): List<UniversalSearchItem> {
        // Use the adaptive engine to perform search
        val adaptiveResults = adaptiveEngine.adaptiveSearch(
            query = query,
            items = allData,
            idExtractor = { it.id },
            titleExtractor = { it.title },
            contentExtractor = { it.fullText() },
            dataType = "universal"
        )
        
        // Convert to UniversalSearchItem format
        return adaptiveResults
            .filter { it.relevanceScore >= args.minRelevance }
            .map { result ->
                UniversalSearchItem(
                    id = result.id,
                    title = result.title,
                    contentPreview = result.contentPreview,
                    dataType = result.dataType,
                    relevanceScore = result.relevanceScore,
                    matchType = result.matchType,
                    sourceInfo = "Adaptive search result",
                    tags = result.item.tags,
                    category = result.item.category,
                    createdAt = result.item.createdAt,
                    updatedAt = result.item.updatedAt
                )
            }
            .sortedByDescending { it.relevanceScore }
            .take(args.maxResults)
    }
    
    private suspend fun performWebSearch(query: String): List<WebResult> {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "No Tavily API key configured for web search")
            return emptyList()
        }
        
        return try {
            val searchResult = tavilySearchProvider.search(
                apiKey = apiKey,
                query = query,
                maxResults = 5, // Limit web results to avoid overwhelming
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
                
                // Report citations
                if (results.isNotEmpty()) {
                    val citations = results.map { SearchCitation(it.title, it.url, it.snippet) }
                    onCitationsFound?.invoke(citations)
                }
                
                results
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Web search failed: ${e.message}", e)
            emptyList()
        }
    }
    
    private fun calculateSearchStatistics(
        allData: List<SearchableItem>,
        results: List<UniversalSearchItem>,
        searchTimeMs: Long
    ): SearchStatistics {
        val searchTypes = results.map { it.dataType }.distinct()
        val avgRelevance = if (results.isNotEmpty()) {
            results.sumOf { it.relevanceScore } / results.size
        } else 0.0
        
        return SearchStatistics(
            totalItemsSearched = allData.size,
            totalResults = results.size,
            searchTypes = searchTypes,
            avgRelevance = avgRelevance,
            searchTimeMs = searchTimeMs
        )
    }
    
    private fun calculateConfidenceScore(results: List<UniversalSearchItem>, webResults: List<WebResult>): Double {
        var score = 0.0
        
        if (results.isNotEmpty()) {
            // Higher average relevance = higher confidence
            val avgRelevance = results.sumOf { it.relevanceScore } / results.size
            score += avgRelevance * 0.7
        }
        
        if (webResults.isNotEmpty()) {
            score += 0.3
        }
        
        return minOf(1.0, score)
    }
    
    private fun generateSummary(results: List<UniversalSearchItem>, webResults: List<WebResult>, stats: SearchStatistics): String {
        return buildString {
            append("Universal search completed in ${stats.searchTimeMs}ms. ")
            append("Searched ${stats.totalItemsSearched} items and found ${results.size} internal results")
            
            if (webResults.isNotEmpty()) {
                append(" and ${webResults.size} web results.")
            } else {
                append(".")
            }
            
            if (results.isNotEmpty()) {
                val avgRelevance = String.format("%.2f", stats.avgRelevance)
                append(" Average relevance: $avgRelevance.")
            }
            
            if (stats.searchTypes.isNotEmpty()) {
                append(" Data types searched: ${stats.searchTypes.joinToString(", ")}.")
            }
        }
    }
}

/**
 * Internal data class for searchable items
 */
private data class SearchableItem(
    val id: String,
    val title: String,
    val content: String,
    val dataType: String,
    val tags: List<String>,
    val category: String?,
    val createdAt: Long?,
    val updatedAt: Long?
) {
    fun fullText(): String {
        return "$title $content ${category ?: ""} ${tags.joinToString(" ")}"
    }
}

