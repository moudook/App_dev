package com.example.smarty.util

import android.util.Log
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.util.search.SemanticSearchEngine
import java.util.*
import kotlin.math.*

/**
 * Adaptive Semantic Search Engine that evolves based on database content
 * Automatically adjusts search algorithms and weights based on content characteristics
 */
class AdaptiveSemanticSearchEngine {
    companion object {
        private const val TAG = "AdaptiveSemanticSearch"
        
        // Minimum number of items needed to adapt search strategy
        private const val MIN_ITEMS_FOR_ADAPTATION = 10
        
        // Thresholds for different content characteristics
        private const val HIGH_KEYWORD_DENSITY_THRESHOLD = 0.15  // 15% of content as keywords
        private const val LOW_KEYWORD_DENSITY_THRESHOLD = 0.05  // 5% of content as keywords
        private const val HIGH_SEMANTIC_CLUSTERING_THRESHOLD = 0.7  // High similarity within clusters
    }
    
    /**
     * Search configuration that adapts based on database content
     */
    data class SearchConfiguration(
        val keywordWeight: Double = 0.3,
        val semanticWeight: Double = 0.5,
        val vectorWeight: Double = 0.2,
        val minRelevanceScore: Double = 0.3,
        val algorithmSelection: AlgorithmSelection = AlgorithmSelection.HYBRID,
        val contentCharacteristics: ContentCharacteristics = ContentCharacteristics()
    )
    
    /**
     * Algorithm selection strategy based on content
     */
    enum class AlgorithmSelection {
        KEYWORD_HEAVY,    // When content has many distinct keywords
        SEMANTIC_HEAVY,   // When content is conceptually dense
        VECTOR_HEAVY,     // When content is document-heavy
        HYBRID            // Balanced approach
    }
    
    /**
     * Characterizes the content in the database
     */
    data class ContentCharacteristics(
        val avgContentLength: Double = 0.0,
        val keywordDensity: Double = 0.0,
        val semanticClustering: Double = 0.0,  // How similar content is
        val contentDiversity: Double = 0.0,    // How diverse content is
        val temporalDistribution: Double = 0.0 // How recent content is
    )
    
    /**
     * Result of an adaptive search operation
     */
    data class AdaptiveSearchResult<T>(
        val item: T,
        val id: String,
        val title: String,
        val contentPreview: String,
        val relevanceScore: Double,
        val matchType: String,
        val dataType: String,
        val contentCharacteristics: ContentCharacteristics
    )
    
    /**
     * Analyzes database content to determine optimal search configuration
     */
    fun analyzeDatabaseContent(notes: List<Note>): SearchConfiguration {
        if (notes.isEmpty()) {
            return SearchConfiguration()
        }
        
        val characteristics = analyzeContentCharacteristics(notes)
        
        // Determine algorithm selection based on content characteristics
        val algorithmSelection = when {
            characteristics.keywordDensity > HIGH_KEYWORD_DENSITY_THRESHOLD -> AlgorithmSelection.KEYWORD_HEAVY
            characteristics.semanticClustering > HIGH_SEMANTIC_CLUSTERING_THRESHOLD -> AlgorithmSelection.SEMANTIC_HEAVY
            characteristics.avgContentLength > 1000 -> AlgorithmSelection.VECTOR_HEAVY  // Long documents
            else -> AlgorithmSelection.HYBRID
        }
        
        // Adjust weights based on content characteristics
        val (keywordWeight, semanticWeight, vectorWeight) = when (algorithmSelection) {
            AlgorithmSelection.KEYWORD_HEAVY -> Triple(0.5, 0.3, 0.2)
            AlgorithmSelection.SEMANTIC_HEAVY -> Triple(0.2, 0.6, 0.2)
            AlgorithmSelection.VECTOR_HEAVY -> Triple(0.2, 0.3, 0.5)
            AlgorithmSelection.HYBRID -> Triple(0.3, 0.5, 0.2)
        }
        
        // Adjust minimum relevance based on content diversity
        val minRelevance = when {
            characteristics.contentDiversity > 0.8 -> 0.2  // High diversity = lower threshold
            characteristics.contentDiversity < 0.3 -> 0.4  // Low diversity = higher threshold
            else -> 0.3
        }
        
        return SearchConfiguration(
            keywordWeight = keywordWeight,
            semanticWeight = semanticWeight,
            vectorWeight = vectorWeight,
            minRelevanceScore = minRelevance,
            algorithmSelection = algorithmSelection,
            contentCharacteristics = characteristics
        )
    }
    
    private fun analyzeContentCharacteristics(notes: List<Note>): ContentCharacteristics {
        if (notes.isEmpty()) {
            return ContentCharacteristics()
        }
        
        // Calculate average content length
        val avgContentLength = notes.map { 
            (it.title.length + it.content.length).toDouble() 
        }.average()
        
        // Calculate keyword density (ratio of unique words to total words)
        var totalWords = 0
        var uniqueWords = mutableSetOf<String>()
        
        notes.forEach { note ->
            val content = (note.title + " " + note.content).lowercase()
            val tokens = SemanticSearchEngine.tokenize(content)
            totalWords += tokens.size
            uniqueWords.addAll(tokens)
        }
        
        val keywordDensity = if (totalWords > 0) uniqueWords.size.toDouble() / totalWords else 0.0
        
        // Calculate semantic clustering (average similarity between items)
        var totalSimilarity = 0.0
        var comparisonCount = 0
        
        for (i in notes.indices) {
            for (j in i + 1 until notes.size) {
                val similarity = SemanticSearchEngine.calculateSimilarity(
                    notes[i].title + " " + notes[i].content,
                    notes[j].title + " " + notes[j].content
                )
                totalSimilarity += similarity
                comparisonCount++
            }
        }
        
        val semanticClustering = if (comparisonCount > 0) totalSimilarity / comparisonCount else 0.0
        
        // Calculate content diversity (inverse of clustering)
        val contentDiversity = 1.0 - semanticClustering
        
        // Calculate temporal distribution (how recent the content is)
        val now = System.currentTimeMillis()
        val avgAge = notes.map { now - it.createdAt }.average()
        val temporalDistribution = if (avgAge > 0) {
            // Normalize: more recent content = higher value
            1.0 / (1.0 + (avgAge / (30 * 24 * 60 * 60 * 1000.0))) // Normalize over 30 days
        } else 1.0
        
        return ContentCharacteristics(
            avgContentLength = avgContentLength,
            keywordDensity = keywordDensity,
            semanticClustering = semanticClustering,
            contentDiversity = contentDiversity,
            temporalDistribution = temporalDistribution
        )
    }
    
    /**
     * Perform adaptive search based on database content analysis
     */
    fun <T> adaptiveSearch(
        query: String,
        items: List<T>,
        idExtractor: (T) -> String,
        titleExtractor: (T) -> String,
        contentExtractor: (T) -> String,
        dataType: String = "unknown"
    ): List<AdaptiveSearchResult<T>> {
        if (items.isEmpty()) {
            return emptyList()
        }
        
        // Analyze the content to get adaptive configuration
        val notesForAnalysis = items.map { item ->
            Note(
                id = idExtractor(item),
                title = titleExtractor(item),
                content = contentExtractor(item),
                type = NoteType.BRAIN_DUMP, // Default type for analysis
                createdAt = System.currentTimeMillis()
            )
        }
        
        val config = analyzeDatabaseContent(notesForAnalysis)
        
        // Perform search based on the adaptive configuration
        return when (config.algorithmSelection) {
            AlgorithmSelection.KEYWORD_HEAVY -> keywordHeavySearch(query, items, idExtractor, titleExtractor, contentExtractor, dataType, config)
            AlgorithmSelection.SEMANTIC_HEAVY -> semanticHeavySearch(query, items, idExtractor, titleExtractor, contentExtractor, dataType, config)
            AlgorithmSelection.VECTOR_HEAVY -> vectorHeavySearch(query, items, idExtractor, titleExtractor, contentExtractor, dataType, config)
            AlgorithmSelection.HYBRID -> hybridSearch(query, items, idExtractor, titleExtractor, contentExtractor, dataType, config)
        }
    }
    
    private fun <T> keywordHeavySearch(
        query: String,
        items: List<T>,
        idExtractor: (T) -> String,
        titleExtractor: (T) -> String,
        contentExtractor: (T) -> String,
        dataType: String,
        config: SearchConfiguration
    ): List<AdaptiveSearchResult<T>> {
        val queryTokens = SemanticSearchEngine.tokenize(query.lowercase())
        val results = mutableListOf<AdaptiveSearchResult<T>>()
        
        items.forEach { item ->
            val title = titleExtractor(item)
            val content = contentExtractor(item)
            val id = idExtractor(item)
            
            var score = 0.0
            var matchType = "keyword"
            
            // Title matching
            if (title.lowercase().contains(query.lowercase())) {
                score += 0.4
                matchType = "title_match"
            } else {
                val titleTokens = SemanticSearchEngine.tokenize(title.lowercase())
                val titleMatches = queryTokens.count { qt -> 
                    titleTokens.any { tt -> tt.contains(qt) || qt.contains(tt) } 
                }
                if (titleMatches > 0) {
                    score += (titleMatches.toDouble() / max(1.0, queryTokens.size.toDouble())) * 0.3
                }
            }
            
            // Content matching
            if (content.lowercase().contains(query.lowercase())) {
                score += 0.3
                matchType = if (matchType == "keyword") "content_match" else "${matchType}_and_content"
            } else {
                val contentTokens = SemanticSearchEngine.tokenize(content.lowercase())
                val contentMatches = queryTokens.count { qt -> 
                    contentTokens.any { ct -> ct.contains(qt) || qt.contains(ct) } 
                }
                if (contentMatches > 0) {
                    score += (contentMatches.toDouble() / max(1.0, queryTokens.size.toDouble())) * 0.2
                }
            }
            
            if (score >= config.minRelevanceScore) {
                results.add(AdaptiveSearchResult(
                    item = item,
                    id = id,
                    title = title,
                    contentPreview = if (content.length > 100) content.substring(0, 100) + "..." else content,
                    relevanceScore = min(1.0, score),
                    matchType = matchType,
                    dataType = dataType,
                    contentCharacteristics = config.contentCharacteristics
                ))
            }
        }
        
        return results.sortedByDescending { it.relevanceScore }
    }
    
    private fun <T> semanticHeavySearch(
        query: String,
        items: List<T>,
        idExtractor: (T) -> String,
        titleExtractor: (T) -> String,
        contentExtractor: (T) -> String,
        dataType: String,
        config: SearchConfiguration
    ): List<AdaptiveSearchResult<T>> {
        val results = mutableListOf<AdaptiveSearchResult<T>>()
        
        items.forEach { item ->
            val title = titleExtractor(item)
            val content = contentExtractor(item)
            val id = idExtractor(item)
            
            // Use SemanticSearchEngine for semantic matching
            val semanticScore = SemanticSearchEngine.calculateSimilarity(query, title + " " + content)
            
            if (semanticScore >= config.minRelevanceScore) {
                val matchType = when {
                    semanticScore > 0.8 -> "high_semantic_match"
                    semanticScore > 0.6 -> "medium_semantic_match"
                    semanticScore > 0.4 -> "low_semantic_match"
                    else -> "minimal_semantic_match"
                }
                
                results.add(AdaptiveSearchResult(
                    item = item,
                    id = id,
                    title = title,
                    contentPreview = if (content.length > 100) content.substring(0, 100) + "..." else content,
                    relevanceScore = semanticScore,
                    matchType = matchType,
                    dataType = dataType,
                    contentCharacteristics = config.contentCharacteristics
                ))
            }
        }
        
        return results.sortedByDescending { it.relevanceScore }
    }
    
    private fun <T> vectorHeavySearch(
        query: String,
        items: List<T>,
        idExtractor: (T) -> String,
        titleExtractor: (T) -> String,
        contentExtractor: (T) -> String,
        dataType: String,
        config: SearchConfiguration
    ): List<AdaptiveSearchResult<T>> {
        // Create TF-IDF vectors for vector-heavy search
        val allTexts = items.map { titleExtractor(it) + " " + contentExtractor(it) }
        val queryText = query
        
        // Combine query with all texts to build vocabulary
        val allTextsCombined = listOf(queryText) + allTexts
        val vocabulary = buildVocabulary(allTextsCombined)
        
        if (vocabulary.isEmpty()) {
            return emptyList()
        }
        
        // Calculate IDF values
        val idfValues = calculateIDF(allTexts, vocabulary)
        
        // Create query vector
        val queryVector = createTfIdfVector(queryText, vocabulary, idfValues)
        
        val results = items.mapIndexed { index, item ->
            val text = allTexts[index]
            val id = idExtractor(item)
            val title = titleExtractor(item)
            
            // Create document vector
            val docVector = createTfIdfVector(text, vocabulary, idfValues)
            
            // Calculate cosine similarity
            val similarity = cosineSimilarity(queryVector, docVector)
            
            if (similarity >= config.minRelevanceScore) {
                AdaptiveSearchResult(
                    item = item,
                    id = id,
                    title = title,
                    contentPreview = if (text.length > 100) text.substring(0, 100) + "..." else text,
                    relevanceScore = similarity,
                    matchType = "vector_similarity",
                    dataType = dataType,
                    contentCharacteristics = config.contentCharacteristics
                )
            } else {
                null
            }
        }.filterNotNull()
        
        return results.sortedByDescending { it.relevanceScore }
    }
    
    private fun <T> hybridSearch(
        query: String,
        items: List<T>,
        idExtractor: (T) -> String,
        titleExtractor: (T) -> String,
        contentExtractor: (T) -> String,
        dataType: String,
        config: SearchConfiguration
    ): List<AdaptiveSearchResult<T>> {
        val keywordResults = keywordHeavySearch(query, items, idExtractor, titleExtractor, contentExtractor, dataType, config.copy(algorithmSelection = AlgorithmSelection.KEYWORD_HEAVY))
        val semanticResults = semanticHeavySearch(query, items, idExtractor, titleExtractor, contentExtractor, dataType, config.copy(algorithmSelection = AlgorithmSelection.SEMANTIC_HEAVY))
        val vectorResults = vectorHeavySearch(query, items, idExtractor, titleExtractor, contentExtractor, dataType, config.copy(algorithmSelection = AlgorithmSelection.VECTOR_HEAVY))
        
        // Combine results using the configured weights
        val resultMap = mutableMapOf<String, Triple<Double, AdaptiveSearchResult<T>, Int>>() // score, result, count
        
        // Add keyword results
        keywordResults.forEach { result ->
            val current = resultMap[result.id] ?: Triple(0.0, result, 0)
            val newScore = current.first + (result.relevanceScore * config.keywordWeight)
            val newCount = current.third + 1
            resultMap[result.id] = Triple(newScore, result, newCount)
        }
        
        // Add semantic results
        semanticResults.forEach { result ->
            val current = resultMap[result.id] ?: Triple(0.0, result, 0)
            val newScore = current.first + (result.relevanceScore * config.semanticWeight)
            val newCount = current.third + 1
            resultMap[result.id] = Triple(newScore, result, newCount)
        }
        
        // Add vector results
        vectorResults.forEach { result ->
            val current = resultMap[result.id] ?: Triple(0.0, result, 0)
            val newScore = current.first + (result.relevanceScore * config.vectorWeight)
            val newCount = current.third + 1
            resultMap[result.id] = Triple(newScore, result, newCount)
        }
        
        // Create final results with combined scores
        val finalResults = resultMap.values.map { (score, result, _) ->
            result.copy(relevanceScore = min(1.0, score))
        }
        
        return finalResults.filter { it.relevanceScore >= config.minRelevanceScore }
            .sortedByDescending { it.relevanceScore }
    }
    
    private fun buildVocabulary(texts: List<String>): Set<String> {
        val vocabulary = mutableSetOf<String>()
        texts.forEach { text ->
            val tokens = SemanticSearchEngine.tokenize(text.lowercase())
            vocabulary.addAll(tokens)
        }
        return vocabulary
    }
    
    private fun calculateIDF(texts: List<String>, vocabulary: Set<String>): Map<String, Double> {
        val idfMap = mutableMapOf<String, Double>()
        
        vocabulary.forEach { term ->
            val docsContainingTerm = texts.count { text ->
                val tokens = SemanticSearchEngine.tokenize(text.lowercase())
                tokens.contains(term)
            }
            
            val idf = if (docsContainingTerm > 0) {
                ln(texts.size.toDouble() / docsContainingTerm)
            } else {
                ln(texts.size.toDouble() + 1) // Smoothing factor
            }
            
            idfMap[term] = idf
        }
        
        return idfMap
    }
    
    private fun createTfIdfVector(text: String, vocabulary: Set<String>, idfValues: Map<String, Double>): Map<String, Double> {
        val tokens = SemanticSearchEngine.tokenize(text.lowercase())
        val termFreq = mutableMapOf<String, Double>()
        
        // Calculate term frequencies
        tokens.forEach { token ->
            if (vocabulary.contains(token)) {
                termFreq[token] = (termFreq[token] ?: 0.0) + 1.0
            }
        }
        
        // Normalize term frequencies (TF)
        val maxFreq = termFreq.values.maxOrNull() ?: 1.0
        val normalizedTf = termFreq.mapValues { (_, freq) -> freq / maxFreq }
        
        // Calculate TF-IDF values
        val tfIdfVector = mutableMapOf<String, Double>()
        vocabulary.forEach { term ->
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
}