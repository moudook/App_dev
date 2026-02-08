package com.example.smarty.viewmodel.managers

import android.util.Log
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.repository.SmartyRepository
import com.example.smarty.data.local.SearchHistoryManager
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.search.SemanticSearchEngine
import com.example.smarty.agent.WebResult
import com.example.smarty.agent.WebSearchResult
import com.example.smarty.agent.models.WebCitation
import com.example.smarty.agent.SearchCitation
import com.example.smarty.util.SemanticRecallEngine
import com.example.smarty.util.RecallContext
import com.example.smarty.util.TimeContext
import com.example.smarty.ui.components.AttachmentOption
import com.example.smarty.data.model.getAttachments
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.*

/**
 * Centralized manager for all search operations (Local).
 * Hybridizes logic for:
 * - Semantic note search (Standard & Advanced)
 * - Hybrid, Vector, and Keyword algorithms
 * - Query analysis and intent detection
 * - Semantic recall and contextual retrieval
 *
 * This manager is the "Brain" of the search system, used by UI and AI.
 */
class SearchFeatureManager(
    private val repository: SmartyRepository,
    private val allNotes: StateFlow<List<Note>>,
    private val searchHistoryManager: SearchHistoryManager,
    private val securePreferences: com.example.smarty.data.local.SecurePreferences
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

    private fun Note.toSearchResult(score: Float, highlight: String?) = SearchResultItem(this, score, highlight)

    private fun calculateTimeCutoff(timeRange: String): Long {
        val now = System.currentTimeMillis()
        val dayMillis = 24 * 60 * 60 * 1000L
        return when (timeRange.lowercase()) {
            "today" -> now - dayMillis
            "week" -> now - 7 * dayMillis
            "month" -> now - 30 * dayMillis
            else -> 0L
        }
    }

    private suspend fun performKeywordSearch(query: String, notes: List<Note>, limit: Int, minScore: Double): List<SearchResultItem> {
        return notes.filter { it.title.contains(query, ignoreCase = true) || it.content.contains(query, ignoreCase = true) }
            .take(limit)
            .map { it.toSearchResult(1.0f, "keyword_match") }
    }

    private suspend fun performVectorSearch(query: String, notes: List<Note>, limit: Int, minScore: Double): List<SearchResultItem> {
        // Placeholder for vector search, falling back to semantic
        return search(query, limit = limit).filter { it.score >= minScore }
    }

    private suspend fun performHybridSearch(query: String, notes: List<Note>, limit: Int, minScore: Double): List<SearchResultItem> {
        // Simple hybrid: semantic results for now
        return search(query, limit = limit).filter { it.score >= minScore }
    }

    private fun detectIntent(query: String): String {
        return if (query.contains("search", ignoreCase = true)) "search" else "query"
    }

    private fun calculateQueryComplexity(query: String, keywords: List<String>): Int {
        return if (keywords.size > 3) 3 else 1
    }
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
