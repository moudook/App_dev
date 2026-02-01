package com.example.smarty.viewmodel.managers

import android.util.Log
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.repository.SmartyRepository
import com.example.smarty.data.local.SearchHistoryManager
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.search.SemanticSearchEngine
import com.example.smarty.data.remote.providers.TavilySearchProvider
import com.example.smarty.agent.tools.base.WebResult
import com.example.smarty.agent.tools.base.WebSearchResult
import com.example.smarty.agent.WebCitation
import com.example.smarty.agent.SearchCitation
import com.example.smarty.util.SemanticRecallEngine
import com.example.smarty.util.RecallContext
import com.example.smarty.util.TimeContext
import com.example.smarty.ui.components.AttachmentOption
import com.example.smarty.data.model.getAttachments
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.*

/**
 * Centralized manager for all search operations (Local & Web).
 * Hybridizes logic for:
 * - Semantic note search (Standard & Advanced)
 * - Hybrid, Vector, and Keyword algorithms
 * - Query analysis and intent detection
 * - Web search integration (Tavily) with multi-query support
 * - Semantic recall and contextual retrieval
 *
 * This manager is the "Brain" of the search system, used by UI and AI.
 */
class SearchFeatureManager(
    private val repository: SmartyRepository,
    private val allNotes: StateFlow<List<Note>>,
    private val searchHistoryManager: SearchHistoryManager,
    private val securePreferences: com.example.smarty.data.local.SecurePreferences,
    private val tavilySearchProvider: TavilySearchProvider? = null
) {
    /**
     * Recent search history as a reactive flow.
     */
    val recentSearches: StateFlow<List<String>> = searchHistoryManager.recentSearches

    /**
     * Add a query to the search history.
     */
    fun addSearchHistory(query: String) {
        searchHistoryManager.addSearch(query)
    }

    /**
     * Clear all search history.
     */
    fun clearSearchHistory() {
        searchHistoryManager.clearHistory()
    }

    /**
     * Get suggestions from history based on partial query.
     */
    fun getHistorySuggestions(query: String): List<String> {
        return searchHistoryManager.getFilteredSuggestions(query)
    }

    companion object {
        private const val TAG = "SearchFeatureManager"
        private const val TAVILY_DAILY_LIMIT = 1000
        private var dailyCallCount = 0
        private var lastResetDay = 0L

        // Weights for hybrid search
        private const val KEYWORD_WEIGHT = 0.3
        private const val SEMANTIC_WEIGHT = 0.5
        private const val TEMPORAL_WEIGHT = 0.2
    }

    /**
     * Get a reactive flow of notes filtered by category.
     */
    fun getNotesByCategory(categoryId: String): kotlinx.coroutines.flow.Flow<List<Note>> =
        repository.getNotesByCategory(categoryId)

    /**
     * Get a reactive flow of all notes.
     */
    fun getAllNotesFlow(): kotlinx.coroutines.flow.Flow<List<Note>> =
        repository.getAllNotes()

    /**
     * Perform a reactive database search.
     */
    fun searchNotesFlow(query: String): kotlinx.coroutines.flow.Flow<List<Note>> =
        repository.searchNotes(query, emptyList())

    /**
     * Standard smart search or filtered retrieval across all visible notes.
     */
    suspend fun search(
        query: String?,
        category: String? = null,
        noteType: String? = null,
        timeRange: String = "all",
        filters: Set<AttachmentOption> = emptySet(),
        limit: Int = 10
    ): List<SearchResultItem> {
        val rawNotes = allNotes.value
        val visibleNotes = PrivacyGuard.getAiVisibleNotes(rawNotes)

        // 1. Apply Hard Filters
        var filtered = visibleNotes
        if (!category.isNullOrBlank()) {
            filtered = filtered.filter { it.categoryName?.equals(category, ignoreCase = true) == true }
        }
        if (!noteType.isNullOrBlank()) {
            filtered = filtered.filter { it.type.name.equals(noteType, ignoreCase = true) }
        }

        // Apply Attachment/Mime filters
        if (filters.isNotEmpty()) {
            filtered = filtered.filter { note ->
                filters.all { filter -> noteMatchesFilter(note, filter) }
            }
        }

        val cutoff = calculateTimeCutoff(timeRange)
        if (cutoff > 0) {
            filtered = filtered.filter { it.createdAt >= cutoff }
        }

        // 2. Perform Semantic/Fuzzy Search if query is present
        if (query.isNullOrBlank()) {
            return filtered.sortedByDescending { it.createdAt }
                .take(limit)
                .map { it.toSearchResult(1.0f, "search_highlight_recent|${it.type.name.lowercase()}") }
        }

        val results = SemanticSearchEngine.search(
            query = query,
            items = filtered,
            textExtractor = { listOfNotNull(it.title, it.whySaved, it.summary, it.content.take(1000)) }
        )

        return results.take(limit).map { result ->
            SearchResultItem(
                note = result.item,
                score = result.score.toFloat(),
                highlight = "search_highlight_matched|${result.matchType.toString().lowercase()}"
            )
        }
    }

    /**
     * Check if a note contains content matching the specific filter.
     * Checks both primary note type and all attachments.
     */
    fun noteMatchesFilter(note: Note, filter: AttachmentOption): Boolean {
        // 1. Check primary type
        if (typeMatchesFilter(note.type, filter)) return true

        // 2. Check source URL for Link/Website
        if (filter == AttachmentOption.LINK && (note.sourceUrl != null || note.type == NoteType.WEBSITE || note.type == NoteType.YOUTUBE)) return true

        // 3. Check all attachments
        val attachments = note.getAttachments()
        return attachments.any { attachment ->
             mimeTypeMatchesFilter(attachment.mimeType, filter)
        }
    }

    private fun typeMatchesFilter(type: NoteType, filter: AttachmentOption): Boolean {
        return when (filter) {
            AttachmentOption.IMAGE -> type == NoteType.IMAGE || type == NoteType.INSTAGRAM
            AttachmentOption.VIDEO -> type == NoteType.VIDEO || type == NoteType.YOUTUBE
            AttachmentOption.AUDIO -> type == NoteType.AUDIO
            AttachmentOption.DOCUMENT -> type == NoteType.DOCUMENT || type == NoteType.SPREADSHEET || type == NoteType.PRESENTATION
            AttachmentOption.FILE -> type == NoteType.FILE || type == NoteType.ARCHIVE || type == NoteType.APK || type == NoteType.CODE
            AttachmentOption.LINK -> type == NoteType.WEBSITE || type == NoteType.TWITTER
        }
    }

    private fun mimeTypeMatchesFilter(mimeType: String, filter: AttachmentOption): Boolean {
        return when (filter) {
            AttachmentOption.IMAGE -> mimeType.startsWith("image/")
            AttachmentOption.VIDEO -> mimeType.startsWith("video/")
            AttachmentOption.AUDIO -> mimeType.startsWith("audio/")
            AttachmentOption.DOCUMENT -> mimeType.contains("pdf") || mimeType.contains("word") || mimeType.contains("excel") || mimeType.contains("powerpoint") || mimeType.contains("text/")
            AttachmentOption.FILE -> true
            AttachmentOption.LINK -> false
        }
    }

    /**
     * Advanced hybrid search using multiple algorithms.
     */
    suspend fun advancedSearch(
        query: String,
        algorithm: String = "hybrid",
        limit: Int = 10,
        minScore: Double = 0.3
    ): List<SearchResultItem> {
        val allNotes = PrivacyGuard.getAiVisibleNotes(this.allNotes.value)

        return when (algorithm.lowercase()) {
            "keyword" -> performKeywordSearch(query, allNotes, limit, minScore)
            "vector" -> performVectorSearch(query, allNotes, limit, minScore)
            "semantic" -> {
                val results = search(query, limit = limit)
                results.filter { it.score >= minScore }
            }
            else -> performHybridSearch(query, allNotes, limit, minScore)
        }
    }

    /**
     * Analyze a search query to extract intent and keywords.
     */
    fun analyzeQuery(query: String): SearchQueryAnalysis {
        val keywords = SemanticSearchEngine.tokenize(query).filter { it.length > 2 }.distinct()
        val intent = detectIntent(query)
        val complexity = calculateQueryComplexity(query, keywords)

        return SearchQueryAnalysis(
            originalQuery = query,
            parsedKeywords = keywords,
            detectedIntent = intent,
            complexity = complexity,
            suggestedStrategy = when (complexity) {
                1 -> "simple"
                2, 3 -> "semantic"
                else -> "hybrid"
            }
        )
    }

    /**
     * Perform contextual semantic recall.
     */
    suspend fun performRecall(query: String, minScore: Double = 0.3): List<RecallResult> {
        val context = RecallContext(
            currentTopic = query,
            userInterests = SemanticSearchEngine.tokenize(query),
            recentActivities = listOf(query),
            preferredCategories = emptyList(),
            timeContext = TimeContext.Recent
        )

        val visibleNotes = PrivacyGuard.getAiVisibleNotes(allNotes.value)
        val recallResults = SemanticRecallEngine.semanticRecall(
            query = query,
            context = context,
            allNotes = visibleNotes,
            minRelevance = minScore
        )

        return recallResults.map { result ->
            RecallResult(
                id = result.id,
                title = result.title,
                content = result.content,
                score = result.semanticScore,
                reason = result.recallReason
            )
        }
    }

    /**
     * Perform parallel external web searches for multiple queries.
     * Uses available API keys in rotation to maximize throughput.
     * Handles rate limits (429) by automatically retrying with a different key.
     */
    suspend fun performParallelWebSearch(
        queries: List<String>,
        maxResultsPerQuery: Int = 4,
        topic: String = "general",
        onCitationsFound: (List<WebCitation>) -> Unit = {}
    ): WebSearchResult = coroutineScope {
        if (tavilySearchProvider == null) {
            return@coroutineScope WebSearchResult(success = false, query = queries.joinToString(", "), reason = "Search engine not initialized")
        }

        if (queries.isEmpty()) {
            return@coroutineScope WebSearchResult(success = false, query = "", reason = "No queries provided")
        }

        // Execute searches in parallel
        val deferredResults = queries.map { query ->
            async {
                // Get a key for this specific request (auto-rotates)
                var currentKey = securePreferences.getTavilyApiKey()

                if (currentKey == null) {
                    return@async WebSearchResult(success = false, query = query, reason = "No API key available")
                }

                if (!checkRateLimit()) {
                    return@async WebSearchResult(success = false, query = query, reason = "Global daily limit reached")
                }

                try {
                    // First attempt
                    var result = tavilySearchProvider.search(currentKey!!, query, maxResultsPerQuery, topic)

                    // Automatic Failover for Rate Limits (429) or Auth Errors (401)
                    if (!result.success && (result.error?.contains("429") == true || result.error?.contains("401") == true)) {
                        Log.w(TAG, "Search failed for '$query' with key ending in ...${currentKey!!.takeLast(4)} (Error: ${result.error}). Retrying with next key...")

                        // Rotate to next key
                        // Calling getTavilyApiKey() again will return the next key in rotation
                        val nextKey = securePreferences.getTavilyApiKey()

                        if (nextKey != null && nextKey != currentKey) {
                            currentKey = nextKey
                            Log.d(TAG, "Retrying '$query' with new key ending in ...${currentKey!!.takeLast(4)}")
                            result = tavilySearchProvider.search(currentKey!!, query, maxResultsPerQuery, topic)
                        } else {
                            Log.e(TAG, "No alternative keys available for retry.")
                        }
                    }

                    if (result.success) {
                        val webResults = result.results.map {
                            // Limit content to first 300 words to prevent context overflow
                            val truncatedSnippet = truncateContent(it.snippet, 300)
                            WebResult(title = it.title, url = it.url, snippet = truncatedSnippet)
                        }

                        // Don't trigger callback here to avoid UI race conditions,
                        // we'll aggregate citations at the end

                        WebSearchResult(
                            success = true,
                            query = query,
                            reason = "Success",
                            aiSummary = result.answer, // Note: Individual summaries might be null if using 'raw' mode
                            results = webResults,
                            totalResults = webResults.size
                        )
                    } else {
                        WebSearchResult(success = false, query = query, reason = result.error ?: "Unknown error")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in parallel search for '$query'", e)
                    WebSearchResult(success = false, query = query, reason = "Exception: ${e.message}")
                }
            }
        }

        // Wait for all to complete
        val results = deferredResults.awaitAll()

        // Aggregate results
        val allWebResults = results.flatMap { it.results ?: emptyList() }
        val failedQueries = results.filter { !it.success }.map { it.query }
        val successfulQueries = results.filter { it.success }.map { it.query }

        // Notify citations once with all results
        if (allWebResults.isNotEmpty()) {
            onCitationsFound(allWebResults.map { WebCitation(it.title, it.url, it.snippet) })
        }

        // Synthesize synthesis (if multiple results, we leave AI summary null so the Agent generates its own from the snippets)
        // If single query success, preserve its AI summary if available
        val aggregatedSummary = if (results.size == 1) results.first().aiSummary else null

        if (allWebResults.isNotEmpty()) {
            WebSearchResult(
                success = true,
                query = queries.joinToString(" | "),
                reason = "Parallel search complete. Success: ${successfulQueries.size}, Failed: ${failedQueries.size}",
                aiSummary = aggregatedSummary,
                results = allWebResults,
                totalResults = allWebResults.size
            )
        } else {
            WebSearchResult(
                success = false,
                query = queries.joinToString(" | "),
                reason = "All searches failed. Errors: ${results.joinToString { it.reason ?: "unknown" }}",
                results = emptyList()
            )
        }
    }

    /**
     * Perform an external web search.
     */
    suspend fun performWebSearch(
        query: String,
        apiKey: String,
        maxResults: Int = 5,
        topic: String = "general",
        onCitationsFound: (List<WebCitation>) -> Unit = {}
    ): WebSearchResult {
        if (tavilySearchProvider == null) {
            return WebSearchResult(success = false, query = query, reason = "Search engine not initialized")
        }

        if (!checkRateLimit()) {
            return WebSearchResult(success = false, query = query, reason = "Search rate limit reached. Please try again tomorrow.")
        }

        return try {
            val result = tavilySearchProvider.search(apiKey, query, maxResults, topic)
            if (result.success) {
                val webResults = result.results.map {
                    // Limit content to first 300 words
                    val truncatedSnippet = truncateContent(it.snippet, 300)
                    WebResult(title = it.title, url = it.url, snippet = truncatedSnippet)
                }

                if (webResults.isNotEmpty()) {
                    onCitationsFound(webResults.map { WebCitation(it.title, it.url, it.snippet) })
                }

                WebSearchResult(
                    success = true,
                    query = query,
                    reason = "Search completed successfully",
                    aiSummary = result.answer,
                    results = webResults,
                    totalResults = webResults.size
                )
            } else {
                WebSearchResult(success = false, query = query, reason = result.error ?: "Search failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Web search error", e)
            WebSearchResult(success = false, query = query, reason = "Error: ${e.message}")
        }
    }

    private fun truncateContent(content: String?, wordCount: Int): String {
        if (content.isNullOrBlank()) return ""
        val words = content.trim().split("\\s+".toRegex())
        return if (words.size <= wordCount) {
            content
        } else {
            words.take(wordCount).joinToString(" ") + "..."
        }
    }

    // === Private Implementation Details (Migrated from AdvancedSearchTool) ===

    private fun performHybridSearch(query: String, notes: List<Note>, limit: Int, minScore: Double): List<SearchResultItem> {
        val keywordResults = performKeywordSearch(query, notes, limit * 2, 0.1)
        val semanticResults = SemanticSearchEngine.search(query, notes, { listOfNotNull(it.title, it.whySaved, it.content) }, minScore)

        val resultMap = mutableMapOf<String, SearchResultItem>()

        keywordResults.forEach {
            resultMap[it.note.id] = it.copy(score = it.score * KEYWORD_WEIGHT.toFloat())
        }

        semanticResults.forEach { result ->
            val existing = resultMap[result.item.id]
            val weightedSemantic = result.score.toFloat() * SEMANTIC_WEIGHT.toFloat()
            if (existing != null) {
                resultMap[result.item.id] = existing.copy(score = min(1.0f, existing.score + weightedSemantic))
            } else {
                resultMap[result.item.id] = SearchResultItem(result.item, weightedSemantic, "hybrid match")
            }
        }

        return resultMap.values
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun performKeywordSearch(query: String, notes: List<Note>, limit: Int, minScore: Double): List<SearchResultItem> {
        val queryLower = query.lowercase()
        return notes.mapNotNull { note ->
            var score = 0.0
            if (note.title.lowercase().contains(queryLower)) score += 0.6
            if (note.whySaved?.lowercase()?.contains(queryLower) == true) score += 0.5
            if (note.content.lowercase().contains(queryLower)) score += 0.4

            if (score >= minScore) {
                SearchResultItem(note, score.toFloat(), "keyword match")
            } else null
        }.sortedByDescending { it.score }.take(limit)
    }

    private fun performVectorSearch(query: String, notes: List<Note>, limit: Int, minScore: Double): List<SearchResultItem> {
        // TF-IDF simulated vector search
        val queryTerms = SemanticSearchEngine.tokenize(query.lowercase()).toSet()
        return notes.mapNotNull { note ->
            val noteTerms = SemanticSearchEngine.tokenize((note.title + " " + note.content).lowercase()).toSet()
            val intersection = queryTerms.intersect(noteTerms)
            val score = intersection.size.toDouble() / queryTerms.size.toDouble()

            if (score >= minScore) {
                SearchResultItem(note, score.toFloat(), "vector match")
            } else null
        }.sortedByDescending { it.score }.take(limit)
    }

    private fun detectIntent(query: String): String {
        val lower = query.lowercase()
        return when {
            lower.contains("recipe") -> "recipe"
            lower.contains("meeting") || lower.contains("schedule") -> "meeting"
            lower.contains("how to") -> "instructional"
            else -> "general"
        }
    }

    private fun calculateQueryComplexity(query: String, keywords: List<String>): Int {
        var complexity = 1
        if (query.length > 50) complexity++
        if (keywords.size > 5) complexity++
        if (query.contains("?") && query.contains("and")) complexity++
        return min(5, complexity)
    }

    private fun calculateTimeCutoff(timeRange: String): Long {
        val now = System.currentTimeMillis()
        return when (timeRange.lowercase()) {
            "today" -> now - 24 * 60 * 60 * 1000L
            "week" -> now - 7 * 24 * 60 * 60 * 1000L
            "month" -> now - 30 * 24 * 60 * 60 * 1000L
            else -> 0L
        }
    }

    private fun checkRateLimit(): Boolean {
        val today = System.currentTimeMillis() / (24 * 60 * 60 * 1000)
        if (today != lastResetDay) {
            dailyCallCount = 0
            lastResetDay = today
        }
        if (dailyCallCount >= TAVILY_DAILY_LIMIT) return false
        dailyCallCount++
        return true
    }

    private fun Note.toSearchResult(score: Float, highlight: String?) = SearchResultItem(this, score, highlight)
}

data class SearchResultItem(
    val note: Note,
    val score: Float,
    val highlight: String?
)

data class SearchQueryAnalysis(
    val originalQuery: String,
    val parsedKeywords: List<String>,
    val detectedIntent: String,
    val complexity: Int,
    val suggestedStrategy: String
)

data class RecallResult(
    val id: String,
    val title: String,
    val content: String,
    val score: Double,
    val reason: String
)
