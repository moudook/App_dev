package com.example.smarty.core.common.util

import android.util.Log
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.getTags
import com.example.smarty.core.common.util.search.SemanticSearchEngine
import com.example.smarty.core.domain.model.RecallResult
import java.util.*
import kotlin.math.*

/**
 * Advanced Semantic Recall Engine for retrieving contextually relevant information
 * based on semantic similarity and contextual relationships
 */
object SemanticRecallEngine {
    private const val TAG = "SemanticRecallEngine"
    
    // Cache for frequently recalled items
    private val recallCache = mutableMapOf<String, List<com.example.smarty.core.common.util.SemanticRecallEngine.InternalRecallResult>>()
    private const val CACHE_SIZE_LIMIT = 100

    /**
     * Internal result of a semantic recall operation - used within this engine
     */
    data class InternalRecallResult(
        val id: String,
        val title: String,
        val content: String,
        val dataType: DataType,
        val semanticScore: Double,           // How semantically similar to query
        val contextualRelevance: Double,     // How contextually relevant to current situation
        val temporalRelevance: Double,       // How recent/important temporally
        val combinedScore: Double,           // Combined relevance score
        val recallReason: String,            // Why this was recalled
        val tags: List<String> = emptyList(),
        val category: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val lastAccessed: Long = System.currentTimeMillis()
    )

    enum class DataType {
        NOTE, EVENT, TODO, CONVERSATION, MEMORY, ATTACHMENT
    }
    
    /**
     * Perform semantic recall based on query and context
     */
    fun semanticRecall(
        query: String,
        context: RecallContext,
        allNotes: List<Note>,
        minRelevance: Double = 0.3
    ): List<com.example.smarty.core.domain.model.RecallResult> {
        val cacheKey = "${query}_${context.sessionId}"

        // Check cache first - using internal type
        val cachedInternal = recallCache[cacheKey]
        cachedInternal?.let { cached ->
            Log.d(TAG, "Cache hit for recall: ${cached.size} items")
            val filtered = cached.filter { it.combinedScore >= minRelevance }
            // Map internal results to common RecallResult
            return filtered.map { internal ->
                com.example.smarty.core.domain.model.RecallResult(
                    id = internal.id,
                    title = internal.title,
                    content = internal.content,
                    score = internal.combinedScore,
                    reason = internal.recallReason
                )
            }
        }

        val internalResults = performRecallInternal(query, context, allNotes, minRelevance)

        // Update cache with internal type
        if (recallCache.size >= CACHE_SIZE_LIMIT) {
            // Remove oldest entries
            val oldestKey = recallCache.minByOrNull { it.value.minOfOrNull { r -> r.lastAccessed } ?: Long.MAX_VALUE }?.key
            oldestKey?.let { recallCache.remove(it) }
        }
        recallCache[cacheKey] = internalResults

        val filteredInternal = internalResults.filter { it.combinedScore >= minRelevance }
        
        // Map internal results to common RecallResult
        return filteredInternal.map { internal ->
            com.example.smarty.core.domain.model.RecallResult(
                id = internal.id,
                title = internal.title,
                content = internal.content,
                score = internal.combinedScore,
                reason = internal.recallReason
            )
        }
    }

    private fun performRecallInternal(
        query: String,
        context: RecallContext,
        allNotes: List<Note>,
        minRelevance: Double
    ): List<InternalRecallResult> {
        val queryTokens = SemanticSearchEngine.tokenize(query.lowercase())
        val results = mutableListOf<InternalRecallResult>()

        // Process notes for semantic recall
        allNotes.forEach { note ->
            val noteContent = (note.title + " " + note.content + " " + (note.categoryName ?: "")).lowercase()
            val noteTokens = SemanticSearchEngine.tokenize(noteContent)

            // Calculate semantic similarity
            val semanticScore = calculateSemanticSimilarity(query, noteContent)

            // Calculate contextual relevance based on context
            val contextualRelevance = calculateContextualRelevance(query, note, context)

            // Calculate temporal relevance
            val temporalRelevance = calculateTemporalRelevance(note.createdAt, context)

            // Combine scores
            val combinedScore = combineRelevanceScores(semanticScore, contextualRelevance, temporalRelevance)

            if (combinedScore >= minRelevance) {
                val recallReason = buildRecallReason(query, note, semanticScore, contextualRelevance, temporalRelevance)

                results.add(InternalRecallResult(
                    id = note.id,
                    title = note.title,
                    content = note.content,
                    dataType = DataType.NOTE,
                    semanticScore = semanticScore,
                    contextualRelevance = contextualRelevance,
                    temporalRelevance = temporalRelevance,
                    combinedScore = combinedScore,
                    recallReason = recallReason,
                    tags = note.getTags(),
                    category = note.categoryName,
                    createdAt = note.createdAt,
                    lastAccessed = System.currentTimeMillis()
                ))
            }
        }

        // Sort by combined score (highest first)
        return results.sortedByDescending { it.combinedScore }
    }
    
    /**
     * Calculate semantic similarity using multiple algorithms
     */
    private fun calculateSemanticSimilarity(query: String, content: String): Double {
        // Use the existing SemanticSearchEngine for base similarity
        val baseSimilarity = SemanticSearchEngine.calculateSimilarity(query, content)
        
        // Enhance with additional semantic measures
        val tokenOverlap = calculateTokenOverlap(query, content)
        val ngramSimilarity = SemanticSearchEngine.ngramSimilarity(query, content, 2)
        val jaroWinkler = SemanticSearchEngine.jaroWinklerSimilarity(query, content)
        
        // Weighted combination
        return (baseSimilarity * 0.4) + (tokenOverlap * 0.2) + (ngramSimilarity * 0.2) + (jaroWinkler * 0.2)
    }
    
    private fun calculateTokenOverlap(query: String, content: String): Double {
        val queryTokens = SemanticSearchEngine.tokenize(query.lowercase())
        val contentTokens = SemanticSearchEngine.tokenize(content.lowercase())
        
        if (queryTokens.isEmpty() || contentTokens.isEmpty()) return 0.0
        
        val matches = queryTokens.count { qt -> contentTokens.any { ct -> ct.contains(qt) || qt.contains(ct) } }
        return matches.toDouble() / max(queryTokens.size, contentTokens.size).toDouble()
    }
    
    /**
     * Calculate contextual relevance based on provided context
     */
    private fun calculateContextualRelevance(query: String, note: Note, context: RecallContext): Double {
        var relevance = 0.0
        
        // Match against current session context
        if (context.currentTopic != null && note.content.contains(context.currentTopic, ignoreCase = true)) {
            relevance += 0.3
        }
        
        // Match against user interests
        context.userInterests.forEach { interest ->
            if (note.content.contains(interest, ignoreCase = true)) {
                relevance += 0.1
            }
        }
        
        // Match against recent activities
        context.recentActivities.forEach { activity ->
            if (note.content.contains(activity, ignoreCase = true)) {
                relevance += 0.1
            }
        }
        
        // Category matching
        if (context.preferredCategories.contains(note.categoryName ?: "")) {
            relevance += 0.2
        }
        
        // Temporal context matching
        if (context.timeContext != null) {
            // Boost notes that are temporally relevant
            val noteAge = abs(System.currentTimeMillis() - note.createdAt)
            val contextTime = when (context.timeContext) {
                is TimeContext.Recent -> 7 * 24 * 60 * 60 * 1000L // 7 days
                is TimeContext.Upcoming -> 3 * 24 * 60 * 60 * 1000L // 3 days
                is TimeContext.Historical -> 30 * 24 * 60 * 60 * 1000L // 30 days
                else -> 7 * 24 * 60 * 60 * 1000L
            }
            
            if (noteAge <= contextTime) {
                relevance += 0.1
            }
        }
        
        return min(1.0, relevance)
    }
    
    /**
     * Calculate temporal relevance based on note age and context
     */
    private fun calculateTemporalRelevance(noteCreatedAt: Long, context: RecallContext): Double {
        val now = System.currentTimeMillis()
        val ageInMillis = now - noteCreatedAt
        
        // Convert to days for easier calculation
        val ageInDays = ageInMillis / (1000 * 60 * 60 * 24).toDouble()
        
        // Temporal relevance curve: recent items are more relevant, but very old items might be relevant for historical context
        val temporalScore = when {
            ageInDays <= 1 -> 1.0  // Very recent
            ageInDays <= 7 -> 0.9   // Within a week
            ageInDays <= 30 -> 0.7  // Within a month
            ageInDays <= 90 -> 0.5  // Within 3 months
            else -> 0.3  // Older (but still potentially relevant for historical context)
        }
        
        // Adjust based on time context
        return when (context.timeContext) {
            is TimeContext.Recent -> if (ageInDays <= 7) temporalScore else temporalScore * 0.5
            is TimeContext.Upcoming -> {
                // For upcoming context, look for notes that might contain planning info
                if (ageInDays <= 30) temporalScore else temporalScore * 0.3
            }
            is TimeContext.Historical -> {
                // For historical context, older notes get a boost
                if (ageInDays > 30) temporalScore * 1.2 else temporalScore
            }
            else -> temporalScore
        }.coerceIn(0.0, 1.0)
    }
    
    /**
     * Combine different relevance scores into a single score
     */
    private fun combineRelevanceScores(
        semantic: Double,
        contextual: Double,
        temporal: Double
    ): Double {
        // Weighted combination with semantic being most important
        return (semantic * 0.5) + (contextual * 0.3) + (temporal * 0.2)
    }
    
    /**
     * Build a reason string explaining why this item was recalled
     */
    private fun buildRecallReason(
        query: String,
        note: Note,
        semanticScore: Double,
        contextualRelevance: Double,
        temporalRelevance: Double
    ): String {
        val reasons = mutableListOf<String>()
        
        if (semanticScore > 0.7) reasons.add("high semantic similarity")
        else if (semanticScore > 0.5) reasons.add("moderate semantic similarity")
        else if (semanticScore > 0.3) reasons.add("low semantic similarity")
        
        if (contextualRelevance > 0.5) reasons.add("contextually relevant")
        if (temporalRelevance > 0.7) reasons.add("temporally relevant")
        
        // Check for specific matches
        if (note.title.contains(query, ignoreCase = true)) reasons.add("title match")
        if (note.content.contains(query, ignoreCase = true)) reasons.add("content match")
        if (note.categoryName?.contains(query, ignoreCase = true) == true) reasons.add("category match")
        
        return reasons.joinToString(", ").ifEmpty { "general relevance" }
    }
    
    /**
     * Clear the recall cache
     */
    fun clearCache() {
        recallCache.clear()
    }
    
    /**
     * Remove expired cache entries
     */
    fun cleanupCache() {
        val now = System.currentTimeMillis()
        val expiredKeys = recallCache.filter { 
            val oldestResult = it.value.minByOrNull { r -> r.lastAccessed }
            oldestResult != null && (now - oldestResult.lastAccessed) > 24 * 60 * 60 * 1000L // 24 hours
        }.keys
        
        expiredKeys.forEach { recallCache.remove(it) }
    }
}

/**
 * Context for semantic recall operations
 */
data class RecallContext(
    val sessionId: String = UUID.randomUUID().toString(),
    val currentTopic: String? = null,
    val userInterests: List<String> = emptyList(),
    val recentActivities: List<String> = emptyList(),
    val preferredCategories: List<String> = emptyList(),
    val timeContext: TimeContext? = null,
    val locationContext: String? = null,
    val conversationHistory: List<String> = emptyList()
)

/**
 * Time context for recall operations
 */
sealed class TimeContext {
    object Recent : TimeContext()
    object Upcoming : TimeContext()
    object Historical : TimeContext()
    data class Specific(val timeRange: Pair<Long, Long>) : TimeContext() // start and end timestamps
}
