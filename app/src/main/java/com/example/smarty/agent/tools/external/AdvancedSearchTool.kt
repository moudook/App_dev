package com.example.smarty.agent.tools.external

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.util.Log
import com.example.smarty.agent.tools.base.WebResult
import com.example.smarty.agent.SearchCitation
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.getTags
import com.example.smarty.data.remote.providers.TavilySearchProvider
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.SemanticRecallEngine
import com.example.smarty.util.RecallContext
import com.example.smarty.util.TimeContext
import com.example.smarty.util.search.SemanticSearchEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlin.math.*

/**
 * Advanced Search Tool - The most powerful search tool with multiple algorithms and semantic recall
 */
@Serializable
data class AdvancedSearchArgs(
    @property:LLMDescription("The main search query or multiple related queries")
    val query: String,
    @property:LLMDescription("Optional related queries for comprehensive search")
    val relatedQueries: List<String> = emptyList(),
    @property:LLMDescription("Search scope: 'web', 'internal', or 'both'")
    val searchScope: SearchScope = SearchScope.BOTH,
    @property:LLMDescription("Data types to search in internal data: notes, categories, todos, etc.")
    val internalDataTypes: List<InternalDataType> = listOf(InternalDataType.NOTES),
    @property:LLMDescription("Maximum number of results to return")
    val maxResults: Int = 10,
    @property:LLMDescription("Minimum relevance score threshold (0.0-1.0)")
    val minRelevance: Double = 0.3,
    @property:LLMDescription("Search algorithm to use: 'hybrid', 'semantic', 'keyword', or 'vector'")
    val algorithm: SearchAlgorithm = SearchAlgorithm.HYBRID
)

@Serializable
enum class SearchScope {
    WEB,        // External web search only
    INTERNAL,   // Internal app data only
    BOTH        // Both web and internal
}

@Serializable
enum class InternalDataType {
    NOTES,
    CATEGORIES,
    TODOS,
    EVENTS,
    ATTACHMENTS,
    ALL
}

@Serializable
enum class SearchAlgorithm {
    HYBRID,      // Combined keyword + semantic + vector
    SEMANTIC,    // Semantic similarity using multiple algorithms
    KEYWORD,     // Traditional keyword matching
    VECTOR       // Vector embedding similarity (simulated)
}

@Serializable
data class AdvancedSearchResult(
    val success: Boolean,
    val webResults: List<WebResult> = emptyList(),
    val internalResults: List<InternalSearchResult> = emptyList(),
    val semanticRecall: List<SemanticRecallResult> = emptyList(),
    val queryAnalysis: QueryAnalysis,
    val overallSummary: String,
    val confidenceScore: Double,
    val error: String? = null
)

@Serializable
data class InternalSearchResult(
    val id: String,
    val title: String,
    val contentPreview: String,
    val dataType: InternalDataType,
    val relevanceScore: Double,
    val matchType: String,
    val sourceInfo: String? = null,
    val tags: List<String> = emptyList(),
    val category: String? = null,
    val createdAt: Long? = null
)

@Serializable
data class SemanticRecallResult(
    val id: String,
    val title: String,
    val content: String,
    val dataType: InternalDataType,
    val semanticScore: Double,
    val temporalRelevance: Double, // How recent the information is
    val contextualRelevance: Double, // How contextually relevant to current query
    val recallReason: String // Why this was recalled
)

@Serializable
data class QueryAnalysis(
    val originalQuery: String,
    val parsedKeywords: List<String>,
    val detectedIntent: String,
    val queryComplexity: Int, // 1-5 scale
    val suggestedSearchStrategy: String,
    val relatedConcepts: List<String>
)

/**
 * Advanced Search Tool with multiple algorithms and semantic recall capabilities
 */
class AdvancedSearchTool(
    private val tavilySearchProvider: TavilySearchProvider,
    private val getApiKey: () -> String?,
    private val getActiveNotes: () -> List<Note>,
    private val getCategories: () -> List<String>,
    private val getEvents: () -> List<String>,
    private val onCitationsFound: ((List<com.example.smarty.agent.SearchCitation>) -> Unit)? = null
) : Tool<AdvancedSearchArgs, AdvancedSearchResult>(
    argsSerializer = AdvancedSearchArgs.serializer(),
    resultSerializer = AdvancedSearchResult.serializer(),
    name = "advanced_search",
    description = """The most powerful search tool. Use for ALL search needs. 
                   |Combines web search, internal data search, semantic recall, and multiple algorithms.
                   |Handles complex queries with multiple related searches in one call.""".trimMargin()
) {
    companion object {
        private const val TAG = "AdvancedSearchTool"
        
        // Algorithm weights for hybrid search
        private const val KEYWORD_WEIGHT = 0.3
        private const val SEMANTIC_WEIGHT = 0.5
        private const val TEMPORAL_WEIGHT = 0.2
        
        // Minimum relevance thresholds
        private const val MIN_RELEVANCE_SCORE = 0.2
        private const val HIGH_RELEVANCE_THRESHOLD = 0.8
        private const val MEDIUM_RELEVANCE_THRESHOLD = 0.6
    }

    override suspend fun execute(args: AdvancedSearchArgs): AdvancedSearchResult {
        return try {
            Log.d(TAG, "Executing advanced search: '${args.query}', scope: ${args.searchScope}, algorithm: ${args.algorithm}")
            
            // Analyze the query
            val queryAnalysis = analyzeQuery(args.query)
            
            // Execute searches based on scope
            val webResults = if (args.searchScope == SearchScope.WEB || args.searchScope == SearchScope.BOTH) {
                performWebSearch(args)
            } else {
                emptyList()
            }
            
            val internalResults = if (args.searchScope == SearchScope.INTERNAL || args.searchScope == SearchScope.BOTH) {
                performInternalSearch(args)
            } else {
                emptyList()
            }
            
            // Perform semantic recall
            val semanticRecall = performSemanticRecall(args.query, args.minRelevance)
            
            // Calculate overall confidence
            val confidenceScore = calculateConfidenceScore(webResults, internalResults, semanticRecall)
            
            // Generate summary
            val overallSummary = generateSummary(webResults, internalResults, semanticRecall, args)
            
            AdvancedSearchResult(
                success = true,
                webResults = webResults,
                internalResults = internalResults,
                semanticRecall = semanticRecall,
                queryAnalysis = queryAnalysis,
                overallSummary = overallSummary,
                confidenceScore = confidenceScore
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Advanced search failed: ${e.message}", e)
            AdvancedSearchResult(
                success = false,
                queryAnalysis = QueryAnalysis(
                    originalQuery = args.query,
                    parsedKeywords = emptyList(),
                    detectedIntent = "error",
                    queryComplexity = 1,
                    suggestedSearchStrategy = "retry",
                    relatedConcepts = emptyList()
                ),
                overallSummary = "Search failed: ${e.message}",
                confidenceScore = 0.0,
                error = e.message
            )
        }
    }
    
    private fun analyzeQuery(query: String): QueryAnalysis {
        val keywords = extractKeywords(query)
        val intent = detectIntent(query)
        val complexity = calculateQueryComplexity(query, keywords)
        val strategy = suggestSearchStrategy(query, complexity)
        val relatedConcepts = generateRelatedConcepts(query, keywords)
        
        return QueryAnalysis(
            originalQuery = query,
            parsedKeywords = keywords,
            detectedIntent = intent,
            queryComplexity = complexity,
            suggestedSearchStrategy = strategy,
            relatedConcepts = relatedConcepts
        )
    }
    
    private fun extractKeywords(query: String): List<String> {
        // Use SemanticSearchEngine's tokenization
        return SemanticSearchEngine.tokenize(query)
            .filter { it.length > 2 }
            .distinct()
    }
    
    private fun detectIntent(query: String): String {
        val lowerQuery = query.lowercase()
        return when {
            lowerQuery.contains("weather") -> "weather_info"
            lowerQuery.contains("news") -> "news_info"
            lowerQuery.contains("recipe") -> "recipe_search"
            lowerQuery.contains("how to") || lowerQuery.contains("how do") -> "instruction"
            lowerQuery.contains("what is") || lowerQuery.contains("define") -> "definition"
            lowerQuery.contains("when") -> "temporal_info"
            lowerQuery.contains("where") -> "location_info"
            lowerQuery.contains("compare") || lowerQuery.contains("vs") -> "comparison"
            else -> "general_info"
        }
    }
    
    private fun calculateQueryComplexity(query: String, keywords: List<String>): Int {
        var complexity = 1
        
        // Length-based complexity
        if (query.length > 50) complexity++
        if (query.length > 100) complexity++
        
        // Keyword count
        if (keywords.size > 5) complexity++
        if (keywords.size > 10) complexity++
        
        // Multiple sentence indicators
        if (query.contains("?") || query.contains("!")) complexity++
        if (query.contains(" and ") || query.contains(" or ")) complexity++
        
        return min(5, max(1, complexity))
    }
    
    private fun suggestSearchStrategy(query: String, complexity: Int): String {
        return when (complexity) {
            1 -> "simple_keyword"
            2 -> "keyword_plus"
            3 -> "semantic_enhanced"
            4 -> "hybrid_advanced"
            5 -> "comprehensive_multi"
            else -> "keyword_plus"
        }
    }
    
    private fun generateRelatedConcepts(query: String, keywords: List<String>): List<String> {
        val concepts = mutableSetOf<String>()
        concepts.addAll(keywords.take(5)) // Primary keywords
        
        // Add related concepts based on common patterns
        val lowerQuery = query.lowercase()
        if (lowerQuery.contains("project")) concepts.add("task")
        if (lowerQuery.contains("meeting")) concepts.add("schedule")
        if (lowerQuery.contains("recipe")) concepts.add("cooking")
        if (lowerQuery.contains("health")) concepts.add("wellness")
        
        return concepts.toList()
    }
    
    private suspend fun performWebSearch(args: AdvancedSearchArgs): List<WebResult> {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "No Tavily API key configured for web search")
            return emptyList()
        }
        
        return try {
            // Combine main query with related queries if provided
            val allQueries = if (args.relatedQueries.isNotEmpty()) {
                listOf(args.query) + args.relatedQueries
            } else {
                listOf(args.query)
            }
            
            // Execute all queries and aggregate results
            coroutineScope {
                allQueries.map { query ->
                    async {
                        try {
                            val searchResult = tavilySearchProvider.search(
                                apiKey = apiKey,
                                query = query,
                                maxResults = args.maxResults,
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
                                    val citations = results.map { com.example.smarty.agent.SearchCitation(it.title, it.url, it.snippet) }
                                    onCitationsFound?.invoke(citations)
                                }
                                
                                results
                            } else {
                                emptyList()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Web search failed for query '$query': ${e.message}", e)
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }.take(args.maxResults)
            
        } catch (e: Exception) {
            Log.e(TAG, "Web search execution failed: ${e.message}", e)
            emptyList()
        }
    }
    
    private suspend fun performInternalSearch(args: AdvancedSearchArgs): List<InternalSearchResult> {
        val allNotes = PrivacyGuard.getAiVisibleNotes(getActiveNotes())
        
        return when (args.algorithm) {
            SearchAlgorithm.HYBRID -> performHybridSearch(args.query, allNotes, args)
            SearchAlgorithm.SEMANTIC -> performSemanticSearch(args.query, allNotes, args)
            SearchAlgorithm.KEYWORD -> performKeywordSearch(args.query, allNotes, args)
            SearchAlgorithm.VECTOR -> performVectorSearch(args.query, allNotes, args)
        }
    }
    
    private fun performHybridSearch(query: String, notes: List<Note>, args: AdvancedSearchArgs): List<InternalSearchResult> {
        // Combine multiple search approaches with weighted scoring
        val keywordResults = performKeywordSearch(query, notes, args.copy(algorithm = SearchAlgorithm.KEYWORD))
        val semanticResults = performSemanticSearch(query, notes, args.copy(algorithm = SearchAlgorithm.SEMANTIC))
        
        // Merge and re-rank results using hybrid scoring
        val resultMap = mutableMapOf<String, InternalSearchResult>()
        
        // Add keyword results with keyword weight
        keywordResults.forEach { result ->
            val id = result.id
            val weightedScore = result.relevanceScore * KEYWORD_WEIGHT
            resultMap[id] = result.copy(relevanceScore = weightedScore)
        }
        
        // Add semantic results with semantic weight, updating scores if already exists
        semanticResults.forEach { result ->
            val id = result.id
            val semanticScore = result.relevanceScore * SEMANTIC_WEIGHT
            val existing = resultMap[id]
            if (existing != null) {
                // Combine scores
                val combinedScore = min(1.0, existing.relevanceScore + semanticScore)
                resultMap[id] = existing.copy(relevanceScore = combinedScore)
            } else {
                resultMap[id] = result.copy(relevanceScore = semanticScore)
            }
        }
        
        // Apply temporal relevance (more recent = higher score)
        val now = System.currentTimeMillis()
        val temporalResults = resultMap.values.map { result ->
            val temporalBoost = if (result.createdAt != null) {
                val ageInDays = (now - result.createdAt) / (1000 * 60 * 60 * 24).toDouble()
                // More recent items get higher temporal relevance
                val temporalRelevance = max(0.1, 1.0 - (ageInDays / 30.0)) // Normalize over 30 days
                temporalRelevance * TEMPORAL_WEIGHT
            } else {
                0.0
            }
            
            result.copy(relevanceScore = min(1.0, result.relevanceScore + temporalBoost))
        }
        
        return temporalResults
            .filter { it.relevanceScore >= args.minRelevance }
            .sortedByDescending { it.relevanceScore }
            .take(args.maxResults)
    }
    
    private fun performSemanticSearch(query: String, notes: List<Note>, args: AdvancedSearchArgs): List<InternalSearchResult> {
        // Use SemanticSearchEngine for semantic matching
        val searchResults = SemanticSearchEngine.search(
            query = query,
            items = notes,
            textExtractor = { note ->
                val texts = mutableListOf<String>()
                if (note.title.isNotBlank()) texts.add(note.title)
                if (note.content.isNotBlank()) texts.add(note.content)
                if (!note.categoryName.isNullOrBlank()) texts.add(note.categoryName)
                val tags = note.getTags()
                if (tags.isNotEmpty()) texts.add(tags.joinToString(" "))
                if (!note.summary.isNullOrBlank()) texts.add(note.summary)
                texts
            },
            minScore = args.minRelevance
        )
        
        return searchResults.map { result ->
            val preview = buildString {
                append(result.item.title)
                if (result.item.content.length > 100) {
                    append(": ").append(result.item.content.substring(0, 100)).append("...")
                } else if (result.item.content.isNotBlank()) {
                    append(": ").append(result.item.content)
                }
            }
            
            InternalSearchResult(
                id = result.item.id,
                title = result.item.title,
                contentPreview = preview,
                dataType = InternalDataType.NOTES,
                relevanceScore = result.score,
                matchType = when (result.matchType) {
                    SemanticSearchEngine.MatchType.EXACT -> "exact"
                    SemanticSearchEngine.MatchType.CONTAINS -> "contains"
                    SemanticSearchEngine.MatchType.FUZZY_HIGH -> "fuzzy_high"
                    SemanticSearchEngine.MatchType.FUZZY_MEDIUM -> "fuzzy_medium"
                    SemanticSearchEngine.MatchType.FUZZY_LOW -> "fuzzy_low"
                    SemanticSearchEngine.MatchType.TOKEN_MATCH -> "token_match"
                    SemanticSearchEngine.MatchType.PHONETIC -> "phonetic"
                    SemanticSearchEngine.MatchType.PARTIAL -> "partial"
                },
                sourceInfo = "Semantic match using ${result.matchType.name}",
                tags = result.item.getTags(),
                category = result.item.categoryName,
                createdAt = result.item.createdAt
            )
        }.take(args.maxResults)
    }
    
    private fun performKeywordSearch(query: String, notes: List<Note>, args: AdvancedSearchArgs): List<InternalSearchResult> {
        val queryLower = query.lowercase()
        val queryTokens = SemanticSearchEngine.tokenize(queryLower)
        
        val results = notes.mapNotNull { note ->
            var score = 0.0
            val matchedTerms = mutableListOf<String>()
            
            // Title matching
            if (note.title.lowercase().contains(queryLower)) {
                score += 0.4
                matchedTerms.add("title match")
            } else {
                // Token matching in title
                val titleTokens = SemanticSearchEngine.tokenize(note.title.lowercase())
                val titleMatches = queryTokens.count { qt -> titleTokens.any { tt -> tt.contains(qt) || qt.contains(tt) } }
                if (titleMatches > 0) {
                    score += (titleMatches.toDouble() / queryTokens.size) * 0.3
                    matchedTerms.add("$titleMatches title tokens matched")
                }
            }
            
            // Content matching
            if (note.content.lowercase().contains(queryLower)) {
                score += 0.3
                matchedTerms.add("content match")
            } else {
                // Token matching in content
                val contentTokens = SemanticSearchEngine.tokenize(note.content.lowercase())
                val contentMatches = queryTokens.count { qt -> contentTokens.any { ct -> ct.contains(qt) || qt.contains(ct) } }
                if (contentMatches > 0) {
                    score += (contentMatches.toDouble() / queryTokens.size) * 0.2
                    matchedTerms.add("$contentMatches content tokens matched")
                }
            }
            
            // Category matching
            if (!note.categoryName.isNullOrBlank() && note.categoryName.lowercase().contains(queryLower)) {
                score += 0.1
                matchedTerms.add("category match")
            }
            
            if (score >= args.minRelevance) {
                val preview = buildString {
                    append(note.title)
                    if (note.content.length > 100) {
                        append(": ").append(note.content.substring(0, 100)).append("...")
                    } else if (note.content.isNotBlank()) {
                        append(": ").append(note.content)
                    }
                }
                
                InternalSearchResult(
                    id = note.id,
                    title = note.title,
                    contentPreview = preview,
                    dataType = InternalDataType.NOTES,
                    relevanceScore = min(1.0, score),
                    matchType = "keyword",
                    sourceInfo = matchedTerms.joinToString(", "),
                    tags = note.getTags(),
                    category = note.categoryName,
                    createdAt = note.createdAt
                )
            } else {
                null
            }
        }
        
        return results.sortedByDescending { it.relevanceScore }.take(args.maxResults)
    }
    
    private fun performVectorSearch(query: String, notes: List<Note>, args: AdvancedSearchArgs): List<InternalSearchResult> {
        // Enhanced vector search using TF-IDF approach with term weighting
        val queryTerms = SemanticSearchEngine.tokenize(query.lowercase())
        val allTerms = mutableSetOf<String>()

        // Collect all unique terms from query and notes
        allTerms.addAll(queryTerms)
        notes.forEach { note ->
            val content = (note.title + " " + note.content + " " + (note.categoryName ?: "")).lowercase()
            allTerms.addAll(SemanticSearchEngine.tokenize(content))
        }

        // Calculate IDF values for all terms
        val idfValues = calculateIDF(notes, allTerms)

        // Create query vector with TF-IDF values
        val queryVector = createTfIdfVector(query.lowercase(), allTerms, idfValues)

        val results = notes.mapNotNull { note ->
            val content = (note.title + " " + note.content + " " + (note.categoryName ?: "")).lowercase()
            val noteVector = createTfIdfVector(content, allTerms, idfValues)

            val similarity = cosineSimilarity(queryVector, noteVector)

            if (similarity >= args.minRelevance) {
                val preview = buildString {
                    append(note.title)
                    if (note.content.length > 100) {
                        append(": ").append(note.content.substring(0, 100)).append("...")
                    } else if (note.content.isNotBlank()) {
                        append(": ").append(note.content)
                    }
                }

                InternalSearchResult(
                    id = note.id,
                    title = note.title,
                    contentPreview = preview,
                    dataType = InternalDataType.NOTES,
                    relevanceScore = similarity,
                    matchType = "vector_similarity",
                    sourceInfo = "Cosine similarity: ${String.format("%.3f", similarity)}",
                    tags = note.getTags(),
                    category = note.categoryName,
                    createdAt = note.createdAt
                )
            } else {
                null
            }
        }

        return results.sortedByDescending { it.relevanceScore }.take(args.maxResults)
    }

    private fun calculateIDF(notes: List<Note>, terms: Set<String>): Map<String, Double> {
        val idfMap = mutableMapOf<String, Double>()

        terms.forEach { term ->
            val docsContainingTerm = notes.count { note ->
                val content = (note.title + " " + note.content + " " + (note.categoryName ?: "")).lowercase()
                content.contains(term, ignoreCase = true)
            }

            // IDF = log(total documents / documents containing term)
            val idf = if (docsContainingTerm > 0) {
                ln(notes.size.toDouble() / docsContainingTerm)
            } else {
                ln(notes.size.toDouble() + 1) // Smoothing factor
            }

            idfMap[term] = idf
        }

        return idfMap
    }

    private fun createTfIdfVector(text: String, allTerms: Set<String>, idfValues: Map<String, Double>): Map<String, Double> {
        val tokens = SemanticSearchEngine.tokenize(text)
        val termFreq = mutableMapOf<String, Double>()

        // Calculate term frequencies
        tokens.forEach { token ->
            termFreq[token] = (termFreq[token] ?: 0.0) + 1.0
        }

        // Normalize term frequencies (TF)
        val maxFreq = termFreq.values.maxOrNull() ?: 1.0
        val normalizedTf = termFreq.mapValues { (_, freq) -> freq / maxFreq }

        // Calculate TF-IDF values
        val tfIdfVector = mutableMapOf<String, Double>()
        allTerms.forEach { term ->
            val tf = normalizedTf[term] ?: 0.0
            val idf = idfValues[term] ?: 0.0
            tfIdfVector[term] = tf * idf
        }

        return tfIdfVector
    }

    private fun cosineSimilarity(vec1: Map<String, Double>, vec2: Map<String, Double>): Double {
        val commonTerms = vec1.keys.intersect(vec2.keys)

        var dotProduct = 0.0
        var magnitude1 = 0.0
        var magnitude2 = 0.0

        commonTerms.forEach { term ->
            val val1 = vec1[term] ?: 0.0
            val val2 = vec2[term] ?: 0.0

            dotProduct += val1 * val2
            magnitude1 += val1 * val1
            magnitude2 += val2 * val2
        }

        if (magnitude1 == 0.0 || magnitude2 == 0.0) return 0.0

        return dotProduct / (sqrt(magnitude1) * sqrt(magnitude2))
    }
    
    private fun performSemanticRecall(query: String, minRelevance: Double): List<SemanticRecallResult> {
        // Create a recall context based on current session
        val context = RecallContext(
            currentTopic = query,
            userInterests = extractKeywords(query),
            recentActivities = listOf(query), // Current query as recent activity
            preferredCategories = emptyList(), // Could be populated from user preferences
            timeContext = TimeContext.Recent // Focus on recent information
        )

        // Get all notes for recall processing
        val allNotes = PrivacyGuard.getAiVisibleNotes(getActiveNotes())

        // Use the SemanticRecallEngine to perform recall
        val recallResults = SemanticRecallEngine.semanticRecall(
            query = query,
            context = context,
            allNotes = allNotes,
            minRelevance = minRelevance
        )

        // Convert to SemanticRecallResult format
        return recallResults.map { result ->
            SemanticRecallResult(
                id = result.id,
                title = result.title,
                content = result.content,
                dataType = when (result.dataType) {
                    SemanticRecallEngine.DataType.NOTE -> InternalDataType.NOTES
                    SemanticRecallEngine.DataType.EVENT -> InternalDataType.EVENTS
                    SemanticRecallEngine.DataType.TODO -> InternalDataType.TODOS
                    SemanticRecallEngine.DataType.CONVERSATION -> InternalDataType.ALL
                    SemanticRecallEngine.DataType.MEMORY -> InternalDataType.ALL
                    SemanticRecallEngine.DataType.ATTACHMENT -> InternalDataType.ATTACHMENTS
                },
                semanticScore = result.semanticScore,
                temporalRelevance = result.temporalRelevance,
                contextualRelevance = result.contextualRelevance,
                recallReason = result.recallReason
            )
        }
    }
    
    private fun calculateConfidenceScore(
        webResults: List<WebResult>,
        internalResults: List<InternalSearchResult>,
        semanticRecall: List<SemanticRecallResult>
    ): Double {
        var score = 0.0
        
        // Web results contribute to confidence
        if (webResults.isNotEmpty()) {
            score += 0.3
        }
        
        // Internal results contribute based on relevance scores
        if (internalResults.isNotEmpty()) {
            val avgRelevance = internalResults.sumOf { it.relevanceScore } / internalResults.size
            score += avgRelevance * 0.5
        }
        
        // Semantic recall adds to confidence
        if (semanticRecall.isNotEmpty()) {
            score += 0.2
        }
        
        return min(1.0, score)
    }
    
    private fun generateSummary(
        webResults: List<WebResult>,
        internalResults: List<InternalSearchResult>,
        semanticRecall: List<SemanticRecallResult>,
        args: AdvancedSearchArgs
    ): String {
        return buildString {
            append("Advanced search completed. ")
            append("Found ${webResults.size} web results and ${internalResults.size} internal results. ")
            
            if (semanticRecall.isNotEmpty()) {
                append("${semanticRecall.size} semantic recall items identified. ")
            }
            
            if (webResults.isNotEmpty()) {
                append("Web search successful. ")
            }
            if (internalResults.isNotEmpty()) {
                append("Internal search completed with ${internalResults.size} relevant items. ")
            }
            
            append("Used ${args.algorithm} algorithm with ${args.searchScope} scope.")
        }
    }
}

