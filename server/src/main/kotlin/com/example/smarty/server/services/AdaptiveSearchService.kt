package com.example.smarty.server.services

import com.example.smarty.protocol.NoteInfo
import kotlin.math.*

/**
 * Server-side Adaptive Search Service.
 * Ported from Android's AdaptiveSemanticSearchEngine.
 */
class AdaptiveSearchService {
    data class SearchConfiguration(
        val keywordWeight: Double = 0.3,
        val semanticWeight: Double = 0.5,
        val vectorWeight: Double = 0.2,
        val minRelevanceScore: Double = 0.3,
    )

    fun search(
        query: String,
        notes: List<NoteInfo>,
        limit: Int = 20,
    ): List<NoteInfo> {
        if (notes.isEmpty() || query.isBlank()) return notes.take(limit)

        // For server-side simplicity, we use a hybrid approach directly
        val config = SearchConfiguration()

        val results =
            notes.map { note ->
                val keywordScore = calculateKeywordScore(query, note)
                val semanticScore = SemanticSearchEngine.calculateSimilarity(query, "${note.title} ${note.content}")

                val combinedScore = (keywordScore * config.keywordWeight) + (semanticScore * config.semanticWeight)

                note to combinedScore
            }
                .filter { it.second >= config.minRelevanceScore }
                .sortedByDescending { it.second }
                .map { it.first }

        return results.take(limit)
    }

    private fun calculateKeywordScore(
        query: String,
        note: String,
    ): Double {
        val queryTokens = SemanticSearchEngine.tokenize(query)
        val textTokens = SemanticSearchEngine.tokenize(note)
        if (queryTokens.isEmpty()) return 0.0

        val matches = queryTokens.count { qt -> textTokens.any { tt -> tt.contains(qt) || qt.contains(tt) } }
        return matches.toDouble() / queryTokens.size
    }

    private fun calculateKeywordScore(
        query: String,
        note: NoteInfo,
    ): Double {
        val titleScore = calculateKeywordScore(query, note.title) * 1.5 // Title weight
        val contentScore = calculateKeywordScore(query, note.content)
        return min(1.0, (titleScore + contentScore) / 2.0)
    }
}
