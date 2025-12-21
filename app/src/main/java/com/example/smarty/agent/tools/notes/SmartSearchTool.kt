package com.example.smarty.agent.tools.notes

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.data.model.Note
import com.example.smarty.util.PrivacyGuard
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer

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
)

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
) : Tool<SmartSearchArgs, SmartSearchResult>() {

    override val argsSerializer: KSerializer<SmartSearchArgs> = SmartSearchArgs.serializer()
    override val resultSerializer: KSerializer<SmartSearchResult> = SmartSearchResult.serializer()

    override val name = "smart_search"

    override val description = """
        Advanced note search with relevance scoring and smart matching.
        Features: fuzzy matching (typo-tolerant), time filtering (today/week/month), type filtering, relevance ranking.
        Use for complex searches like "notes about meetings from last week" or "anything related to project X".
        Returns scored results with match highlights.
    """.trimIndent()

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
        val queryTerms = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        if (queryTerms.isEmpty()) return Pair(0f, null)

        var totalScore = 0f
        var matchedTerm: String? = null

        for (term in queryTerms) {
            // Title match (highest weight - 3x)
            if (note.title.contains(term, ignoreCase = true)) {
                totalScore += 3.0f
                matchedTerm = "Title: \"${note.title}\""
            }

            // Summary match (high weight - 2x)
            if (note.summary?.contains(term, ignoreCase = true) == true) {
                totalScore += 2.0f
                if (matchedTerm == null) {
                    matchedTerm = "Summary match"
                }
            }

            // Content match (standard weight - 1x)
            if (note.content.contains(term, ignoreCase = true)) {
                totalScore += 1.0f
                if (matchedTerm == null) {
                    val idx = note.content.lowercase().indexOf(term.lowercase())
                    val start = maxOf(0, idx - 30)
                    val end = minOf(note.content.length, idx + term.length + 30)
                    matchedTerm = "...${note.content.substring(start, end)}..."
                }
            }

            // Fuzzy matching for typos (mode: fuzzy)
            if (mode == "fuzzy") {
                val fuzzyScore = fuzzyMatch(term, note.title) * 2.0f +
                        fuzzyMatch(term, note.summary ?: "") * 1.2f +
                        fuzzyMatch(term, note.content) * 0.4f
                totalScore += fuzzyScore
            }
        }

        // Recency boost - more recent notes get higher scores
        val ageHours = (System.currentTimeMillis() - note.createdAt) / (1000 * 60 * 60)
        val recencyBoost = when {
            ageHours < 24 -> 0.5f      // Today
            ageHours < 168 -> 0.3f     // This week
            ageHours < 720 -> 0.1f     // This month
            else -> 0f
        }
        totalScore += recencyBoost

        return Pair(totalScore, matchedTerm)
    }

    private fun fuzzyMatch(term: String, text: String): Float {
        val words = text.lowercase().split(Regex("\\W+"))
        val termLower = term.lowercase()

        for (word in words) {
            if (word.length < 2) continue
            if (word.startsWith(termLower)) return 0.8f
            if (word.contains(termLower)) return 0.5f
            if (levenshteinDistance(word, termLower) <= 2 && word.length > 3) return 0.3f
        }
        return 0f
    }

    /**
     * Calculate Levenshtein edit distance between two strings.
     * Used for fuzzy matching with typo tolerance.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length

        if (m == 0) return n
        if (n == 0) return m

        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1]) + 1
                }
            }
        }
        return dp[m][n]
    }

    override fun toString(): String {
        return "SmartSearchTool - Advanced search with fuzzy matching and relevance scoring"
    }
}
