package com.example.smarty.util

import android.util.Log
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.getTags
import com.example.smarty.util.search.SemanticSearchEngine

/**
 * Provides semantically related notes for a given note.
 * Uses the lightweight SemanticSearchEngine (no ML model required).
 * 
 * This enables the "Related Knowledge" feature in KnowledgeCardScreen,
 * helping users discover connections between their notes.
 */
object RelatedNotesProvider {
    private const val TAG = "RelatedNotesProvider"
    
    // Minimum similarity score to consider a note as "related"
    private const val MIN_RELEVANCE_SCORE = 0.35
    
    // Maximum number of related notes to return
    private const val MAX_RELATED_NOTES = 5
    
    /**
     * Result containing a related note and its relevance score.
     */
    data class RelatedNote(
        val note: Note,
        val score: Double,
        val matchReason: String  // Human-readable reason for the match
    )
    
    /**
     * Find notes that are semantically related to the given note.
     * 
     * @param targetNote The note to find related notes for
     * @param allNotes All notes to search through (should exclude private notes)
     * @param maxResults Maximum number of results to return
     * @return List of RelatedNote sorted by relevance (highest first)
     */
    fun findRelatedNotes(
        targetNote: Note,
        allNotes: List<Note>,
        maxResults: Int = MAX_RELATED_NOTES
    ): List<RelatedNote> {
        // Don't include the target note itself in results
        val candidateNotes = allNotes.filter { it.id != targetNote.id }
        
        if (candidateNotes.isEmpty()) {
            return emptyList()
        }
        
        // Build search query from target note's key information
        val searchQuery = buildSearchQuery(targetNote)
        if (searchQuery.isBlank()) {
            return emptyList()
        }
        
        Log.d(TAG, "Finding related notes for: '${targetNote.title.take(30)}' query: '${searchQuery.take(50)}'")
        
        // Use SemanticSearchEngine to find matches
        val results = SemanticSearchEngine.search(
            query = searchQuery,
            items = candidateNotes,
            textExtractor = { note -> extractSearchableText(note) },
            minScore = MIN_RELEVANCE_SCORE
        )
        
        // Convert to RelatedNote with human-readable match reasons
        return results
            .take(maxResults)
            .map { result ->
                RelatedNote(
                    note = result.item,
                    score = result.score,
                    matchReason = generateMatchReason(result.matchType, result.matchedTerms)
                )
            }
    }
    
    /**
     * Build a search query from the note's key information.
     * Prioritizes title, tags, and summary over raw content.
     */
    private fun buildSearchQuery(note: Note): String {
        val parts = mutableListOf<String>()
        
        // Title is most important
        if (note.title.isNotBlank() && note.title.length > 2) {
            parts.add(note.title)
        }
        
        // Tags are very specific signals
        note.getTags().let { tags ->
            if (tags.isNotEmpty()) {
                parts.addAll(tags.take(5))
            }
        }
        
        // Summary is AI-generated and usually captures key concepts
        note.summary?.let { summary ->
            if (summary.isNotBlank()) {
                // Take first 100 chars of summary
                parts.add(summary.take(100))
            }
        }
        
        // Category name can indicate related topics
        note.categoryName?.let { category ->
            if (category.isNotBlank()) {
                parts.add(category)
            }
        }
        
        return parts.joinToString(" ")
    }
    
    /**
     * Extract searchable text fields from a note.
     */
    private fun extractSearchableText(note: Note): List<String> {
        val texts = mutableListOf<String>()
        
        // Add title
        if (note.title.isNotBlank()) {
            texts.add(note.title)
        }
        
        // Add tags as individual searchable items
        note.getTags().forEach { tag ->
            texts.add(tag)
        }
        
        // Add summary
        note.summary?.let { summary ->
            if (summary.isNotBlank()) {
                texts.add(summary)
            }
        }
        
        // Add category
        note.categoryName?.let { category ->
            texts.add(category)
        }
        
        // Add content (limited to first 500 chars for performance)
        if (note.content.isNotBlank()) {
            texts.add(note.content.take(500))
        }
        
        return texts
    }
    
    /**
     * Generate a human-readable reason for why notes are related.
     */
    private fun generateMatchReason(
        matchType: SemanticSearchEngine.MatchType,
        matchedTerms: List<String>
    ): String {
        val termsSummary = if (matchedTerms.isNotEmpty()) {
            matchedTerms.take(3).joinToString(", ") { "'${it.take(20)}'" }
        } else {
            ""
        }
        
        return when (matchType) {
            SemanticSearchEngine.MatchType.EXACT -> "Exact match: $termsSummary"
            SemanticSearchEngine.MatchType.CONTAINS -> "Contains: $termsSummary"
            SemanticSearchEngine.MatchType.FUZZY_HIGH -> "Very similar to: $termsSummary"
            SemanticSearchEngine.MatchType.FUZZY_MEDIUM -> "Similar to: $termsSummary"
            SemanticSearchEngine.MatchType.FUZZY_LOW -> "Possibly related to: $termsSummary"
            SemanticSearchEngine.MatchType.TOKEN_MATCH -> "Shares keywords: $termsSummary"
            SemanticSearchEngine.MatchType.PHONETIC -> "Sounds like: $termsSummary"
            SemanticSearchEngine.MatchType.PARTIAL -> "Partial match"
        }
    }
    
    /**
     * Quickly check if a note might have related notes.
     * Use this for UI decisions (show/hide related section).
     */
    fun hasEnoughDataForRelatedSearch(note: Note): Boolean {
        // Need at least a title or some content to search
        return note.title.length > 3 || 
               note.getTags().isNotEmpty() || 
               !note.summary.isNullOrBlank()
    }
}
