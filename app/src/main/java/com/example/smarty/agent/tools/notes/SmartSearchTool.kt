package com.example.smarty.agent.tools.notes

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.data.model.Note
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.search.SemanticSearchEngine
import com.example.smarty.util.toon.ToonManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = false }

@Serializable
data class SmartSearchArgs(
    @property:LLMDescription("Search query - can be natural language like 'notes about meetings from last week'")
    val query: String,
    @property:LLMDescription("Optional: filter by category name")
    val category: String? = null,
    @property:LLMDescription("Optional: filter by note type (BRAIN_DUMP, RESEARCH, TODO, AUDIO, IMAGE, etc)")
    val noteType: String? = null,
    @property:LLMDescription("Time filter: 'today', 'week', 'month', 'all' (default: all)")
    val timeRange: String = "all",
    @property:LLMDescription("Maximum results to return (default 10, max 25)")
    val limit: Int = 10,
    @property:LLMDescription("Search mode: 'fuzzy' (forgiving typos), 'exact' (precise match)")
    val mode: String = "fuzzy"
)

@Serializable
data class ScoredNoteInfo(
    val id: String,
    val title: String,
    val summary: String?,
    val category: String?,
    val type: String,
    val createdAt: Long,
    val relevanceScore: Float,
    val matchHighlight: String?
)

@Serializable
data class SmartSearchResult(
    val success: Boolean,
    val query: String,
    val notes: List<ScoredNoteInfo>,
    val totalMatches: Int,
    val searchMode: String,
    val timeRange: String,
    val message: String
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

/**
 * Advanced search tool with relevance scoring and smart matching.
 * Features:
 * - Fuzzy matching for typo tolerance
 * - Time-based filtering (today, week, month)
 * - Type and category filtering
 * - Relevance scoring with recency boost
 * - Match highlighting
 */
class SmartSearchTool(
    private val getActiveNotes: () -> List<Note>
) : Tool<SmartSearchArgs, SmartSearchResult>(
    argsSerializer = SmartSearchArgs.serializer(),
    resultSerializer = SmartSearchResult.serializer(),
    name = "smart_search",
    description = """
        Advanced semantic search with relevance scoring, time filtering, and match highlighting.
        Triggers: "Find notes about X from last week", "Search for Y in Work category", "Most relevant notes about Z".
        Features: Fuzzy matching, Recency boost, Category filtering.
    """.trimIndent()
) {
    override suspend fun execute(args: SmartSearchArgs): SmartSearchResult {
        val allNotes = getActiveNotes()
        val visibleNotes = PrivacyGuard.getAiVisibleNotes(allNotes)

        // Apply time filter
        val timeFilteredNotes = filterByTime(visibleNotes, args.timeRange)

        // Apply type filter
        val typeFilteredNotes = if (args.noteType.isNullOrBlank()) {
            timeFilteredNotes
        } else {
            timeFilteredNotes.filter { it.type.name.equals(args.noteType, ignoreCase = true) }
        }

        // Apply category filter
        val categoryFilteredNotes = if (args.category.isNullOrBlank()) {
            typeFilteredNotes
        } else {
            typeFilteredNotes.filter { it.categoryName?.equals(args.category, ignoreCase = true) == true }
        }

        // Score and rank notes
        val safeLimit = args.limit.coerceIn(1, 25)
        val scoredNotes = categoryFilteredNotes.map { note ->
            val (score, highlight) = calculateRelevanceScore(note, args.query, args.mode)
            ScoredNoteInfo(
                id = note.id,
                title = note.title,
                summary = note.summary?.take(150),
                category = note.categoryName,
                type = note.type.name,
                createdAt = note.createdAt,
                relevanceScore = score,
                matchHighlight = highlight
            )
        }
            .filter { it.relevanceScore > 0f }
            .sortedByDescending { it.relevanceScore }
            .take(safeLimit)

        return SmartSearchResult(
            success = true,
            query = args.query,
            notes = scoredNotes,
            totalMatches = scoredNotes.size,
            searchMode = args.mode,
            timeRange = args.timeRange,
            message = if (scoredNotes.isEmpty()) {
                "No notes found matching '${args.query}'" +
                        if (args.timeRange != "all") " in the last ${args.timeRange}" else ""
            } else {
                "Found ${scoredNotes.size} relevant notes, ranked by relevance"
            }
        )
    }

    private fun filterByTime(notes: List<Note>, timeRange: String): List<Note> {
        val now = System.currentTimeMillis()
        val cutoff = when (timeRange.lowercase()) {
            "today" -> now - 24 * 60 * 60 * 1000L
            "week" -> now - 7 * 24 * 60 * 60 * 1000L
            "month" -> now - 30 * 24 * 60 * 60 * 1000L
            else -> 0L // "all" - no filtering
        }
        return if (cutoff > 0) notes.filter { it.createdAt >= cutoff } else notes
    }

    private fun calculateRelevanceScore(note: Note, query: String, mode: String): Pair<Float, String?> {
        if (query.isBlank()) return Pair(0f, null)

        // Use SemanticSearchEngine for comprehensive matching
        val searchResults = SemanticSearchEngine.search(
            query = query,
            items = listOf(note),
            textExtractor = { n ->
                listOfNotNull(
                    n.title,
                    n.summary,
                    n.content.take(1000)  // Limit content for performance
                )
            },
            minScore = 0.15  // Low threshold to catch more matches
        )

        if (searchResults.isEmpty()) {
            // Fallback: try basic term matching
            val queryTerms = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
            var basicScore = 0f
            var matchedTerm: String? = null

            for (term in queryTerms) {
                if (note.title.contains(term, ignoreCase = true)) {
                    basicScore += 2.0f
                    matchedTerm = "Title: \"${note.title}\""
                }
                if (note.content.contains(term, ignoreCase = true)) {
                    basicScore += 1.0f
                    if (matchedTerm == null) matchedTerm = "Content match"
                }
            }
            return Pair(basicScore, matchedTerm)
        }

        val result = searchResults.first()
        val semanticScore = result.score.toFloat()

        // Build match highlight based on match type
        val matchHighlight = when (result.matchType) {
            SemanticSearchEngine.MatchType.EXACT -> "Exact match in: ${result.matchedTerms.firstOrNull() ?: note.title}"
            SemanticSearchEngine.MatchType.CONTAINS -> "Found in: ${result.matchedTerms.firstOrNull() ?: note.title}"
            SemanticSearchEngine.MatchType.FUZZY_HIGH -> "High similarity match (${String.format("%.0f", semanticScore * 100)}%)"
            SemanticSearchEngine.MatchType.FUZZY_MEDIUM -> "Similar match (${String.format("%.0f", semanticScore * 100)}%)"
            SemanticSearchEngine.MatchType.FUZZY_LOW -> "Possible match (${String.format("%.0f", semanticScore * 100)}%)"
            SemanticSearchEngine.MatchType.TOKEN_MATCH -> "Matching words: ${result.matchedTerms.take(3).joinToString(", ")}"
            SemanticSearchEngine.MatchType.PHONETIC -> "Sounds like: ${result.matchedTerms.firstOrNull() ?: query}"
            SemanticSearchEngine.MatchType.PARTIAL -> "Partial match"
        }

        // Boost for exact/contains matches
        val matchTypeBoost = when (result.matchType) {
            SemanticSearchEngine.MatchType.EXACT -> 5.0f
            SemanticSearchEngine.MatchType.CONTAINS -> 4.0f
            SemanticSearchEngine.MatchType.FUZZY_HIGH -> 3.0f
            SemanticSearchEngine.MatchType.TOKEN_MATCH -> 2.5f
            SemanticSearchEngine.MatchType.FUZZY_MEDIUM -> 2.0f
            SemanticSearchEngine.MatchType.PHONETIC -> 1.5f
            SemanticSearchEngine.MatchType.FUZZY_LOW -> 1.0f
            SemanticSearchEngine.MatchType.PARTIAL -> 0.5f
        }

        // Recency boost - more recent notes get higher scores
        val ageHours = (System.currentTimeMillis() - note.createdAt) / (1000 * 60 * 60)
        val recencyBoost = when {
            ageHours < 24 -> 0.5f      // Today
            ageHours < 168 -> 0.3f     // This week
            ageHours < 720 -> 0.1f     // This month
            else -> 0f
        }

        // Combine semantic score, match type boost, and recency
        val totalScore = (semanticScore * matchTypeBoost) + recencyBoost

        return Pair(totalScore, matchHighlight)
    }

    override fun toString(): String {
        return "SmartSearchTool - Advanced search with fuzzy matching and relevance scoring"
    }
}
