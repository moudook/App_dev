package com.example.smarty.server.tools

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*
import kotlin.random.Random

@Serializable
data class SearchMetrics(
    val totalSearches: Long,
    val successRate: Double,
    val avgResponseTime: Double,
    val cacheHitRate: Double,
    val apiKeyStats: Map<String, KeyStats>,
    val queryAnalytics: QueryAnalytics
)

@Serializable
data class KeyStats(
    val keyIndex: Int,
    val usageCount: Long,
    val successCount: Long,
    val failureCount: Long,
    val avgResponseTime: Double,
    val rateLimitHits: Int,
    val lastUsed: Long,
    val healthScore: Double
)

@Serializable
data class QueryAnalytics(
    val totalQueries: Long,
    val uniqueQueries: Long,
    val avgResultsPerQuery: Double,
    val topQueries: List<QueryFrequency>,
    val queryComplexity: Map<String, Int>
)

@Serializable
data class QueryFrequency(
    val query: String,
    val frequency: Int,
    val avgResults: Double,
    val lastSearched: Long
)

@Serializable
data class SearchCache(
    val queryHash: String,
    val query: String,
    val results: List<TavilyResult>,
    val cachedAt: Long,
    val expiresAt: Long,
    val accessCount: Int,
    val relevanceScore: Double
)

@Serializable
data class SearchContext(
    val query: String,
    val userIntent: String,
    val complexity: Int,
    val domain: String?,
    val timeSensitivity: Double,
    val resultDiversity: Double
)

@Serializable
data class ResultRanking(
    val originalScore: Double,
    val recencyBoost: Double,
    val relevanceBoost: Double,
    val diversityBoost: Double,
    val finalScore: Double
)

@Serializable
data class QueryExpansion(
    val originalQuery: String,
    val expandedTerms: List<String>,
    val relatedConcepts: List<String>,
    val alternativeQueries: List<String>,
    val confidence: Double
)

class TavilySearchTool {
    private val logger = LoggerFactory.getLogger(TavilySearchTool::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val apiKeys: List<String> = System.getenv("TAVILY_API_KEY")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?: emptyList()
    
    private val keyStats = ConcurrentHashMap<Int, KeyStats>()
    private val currentIndex = AtomicInteger(0)
    private val searchCache = ConcurrentHashMap<String, SearchCache>()
    private val queryHistory = ConcurrentHashMap<String, MutableList<Long>>()
    private val queryFrequencies = ConcurrentHashMap<String, QueryFrequency>()
    
    private val totalSearches = AtomicLong(0)
    private val successfulSearches = AtomicLong(0)
    private val totalResponseTime = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    companion object {
        private const val CACHE_TTL_MS = 1800000L
        private const val MAX_CACHE_SIZE = 100
        private const val MAX_RESULTS = 10
        private const val RATE_LIMIT_DELAY_MS = 5000
    }
    
    init {
        initializeKeyStats()
    }
    
    private fun initializeKeyStats() {
        apiKeys.forEachIndexed { index, _ ->
            keyStats[index] = KeyStats(
                keyIndex = index,
                usageCount = 0,
                successCount = 0,
                failureCount = 0,
                avgResponseTime = 0.0,
                rateLimitHits = 0,
                lastUsed = 0,
                healthScore = 1.0
            )
        }
    }
    
    suspend fun search(
        query: String,
        useCache: Boolean = true,
        expandQuery: Boolean = true,
        maxResults: Int = MAX_RESULTS
    ): String {
        if (apiKeys.isEmpty()) {
            return "Error: Web search is not configured (missing TAVILY_API_KEY)."
        }
        
        val context = analyzeQueryContext(query)
        
        if (useCache) {
            val cacheKey = generateCacheKey(query)
            val cached = searchCache[cacheKey]
            if (cached != null && !isCacheExpired(cached)) {
                cacheHits.incrementAndGet()
                logger.info("Returning cached results for query: $query")
                return formatResults(cached.results, useCache = true)
            }
        }
        
        val expandedQuery = if (expandQuery) expandQueryWithAI(query) else QueryExpansion(
            originalQuery = query,
            expandedTerms = listOf(query),
            relatedConcepts = emptyList(),
            alternativeQueries = emptyList(),
            confidence = 1.0
        )
        
        val searchQuery = expandedQuery.expandedTerms.first()
        
        val result = performSearchWithRetry(searchQuery, maxResults)
        
        if (result.first != null) {
            val rankedResults = rankResults(result.first!!, context)
            
            if (useCache) {
                val cacheKey = generateCacheKey(query)
                searchCache[cacheKey] = SearchCache(
                    queryHash = cacheKey,
                    query = query,
                    results = rankedResults,
                    cachedAt = System.currentTimeMillis(),
                    expiresAt = System.currentTimeMillis() + CACHE_TTL_MS,
                    accessCount = 1,
                    relevanceScore = rankedResults.firstOrNull()?.score ?: 0.0
                )
                pruneCache()
            }
            
            updateQueryAnalytics(query, rankedResults.size)
            
            return formatResults(rankedResults, useCache = false)
        }
        
        return "Error performing web search: ${result.second}"
    }
    
    private fun analyzeQueryContext(query: String): SearchContext {
        val intentPatterns = mapOf(
            "informational" to listOf("what", "how", "why", "when", "where", "who", "define", "explain"),
            "transactional" to listOf("buy", "download", "get", "purchase", "order", "subscribe"),
            "navigational" to listOf("site", "website", "page", "login", "sign in"),
            "comparative" to listOf("compare", "versus", "vs", "difference", "better", "best", "alternative")
        )
        
        var userIntent = "informational"
        intentPatterns.forEach { (intent, patterns) ->
            if (patterns.any { query.contains(it, ignoreCase = true) }) {
                userIntent = intent
            }
        }
        
        val complexity = when {
            query.split(" ").size > 5 -> 3
            query.split(" ").size > 3 -> 2
            else -> 1
        }
        
        val timeSensitivity = when {
            query.contains("latest", "recent", "new", "current", ignoreCase = true) -> 0.9
            query.contains("history", "old", "past", ignoreCase = true) -> 0.3
            else -> 0.5
        }
        
        val domainPatterns = mapOf(
            "programming" to listOf("code", "programming", "developer", "api", "sdk"),
            "science" to listOf("research", "study", "scientific", "experiment"),
            "business" to listOf("market", "company", "business", "revenue"),
            "health" to listOf("health", "medical", "doctor", "disease", "treatment")
        )
        
        var domain: String? = null
        domainPatterns.forEach { (d, patterns) ->
            if (patterns.any { query.contains(it, ignoreCase = true) }) {
                domain = d
            }
        }
        
        return SearchContext(
            query = query,
            userIntent = userIntent,
            complexity = complexity,
            domain = domain,
            timeSensitivity = timeSensitivity,
            resultDiversity = 0.5
        )
    }
    
    private fun expandQueryWithAI(query: String): QueryExpansion {
        val tokens = query.lowercase().split(" ")
        
        val synonyms = mapOf(
            "find" to listOf("search", "locate", "discover"),
            "get" to listOf("obtain", "acquire", "retrieve"),
            "make" to listOf("create", "build", "develop"),
            "learn" to listOf("understand", "study", "explore"),
            "fix" to listOf("solve", "resolve", "repair", "debug")
        )
        
        val expandedTerms = tokens.toMutableList()
        synonyms.forEach { (word, syns) ->
            if (word in tokens) {
                expandedTerms.addAll(syns)
            }
        }
        
        val relatedConcepts = detectRelatedConcepts(query)
        
        val alternativeQueries = generateAlternativeQueries(query)
        
        val confidence = minOf(0.5 + relatedConcepts.size * 0.1 + alternativeQueries.size * 0.1, 0.95)
        
        return QueryExpansion(
            originalQuery = query,
            expandedTerms = expandedTerms.distinct(),
            relatedConcepts = relatedConcepts,
            alternativeQueries = alternativeQueries,
            confidence = confidence
        )
    }
    
    private fun detectRelatedConcepts(query: String): List<String> {
        val conceptMap = mapOf(
            "web development" to listOf("html", "css", "javascript", "frontend", "backend", "fullstack"),
            "machine learning" to listOf("ai", "deep learning", "neural network", "data science"),
            "mobile development" to listOf("ios", "android", "react native", "flutter", "app"),
            "cloud computing" to listOf("aws", "azure", "gcp", "serverless", "docker", "kubernetes")
        )
        
        return conceptMap.entries
            .filter { (key, _) -> query.contains(key, ignoreCase = true) }
            .flatMap { it.value }
            .take(5)
    }
    
    private fun generateAlternativeQueries(original: String): List<String> {
        val alternatives = mutableListOf<String>()
        
        val tokens = original.split(" ")
        if (tokens.size > 2) {
            alternatives.add(tokens.drop(1).joinToString(" "))
            alternatives.add(tokens.dropLast(1).joinToString(" "))
        }
        
        if ("how to" in original.lowercase()) {
            alternatives.add(original.replace("how to", "guide for"))
        }
        
        if ("best" in original.lowercase()) {
            alternatives.add(original.replace("best", "top"))
        }
        
        return alternatives
    }
    
    private fun rankResults(results: List<TavilyResult>, context: SearchContext): List<TavilyResult> {
        return results.map { result ->
            val recencyBoost = calculateRecencyBoost(result.url)
            val relevanceBoost = calculateRelevanceBoost(result, context)
            val diversityBoost = calculateDiversityBoost(result, results)
            
            val finalScore = (result.score * 0.5 + recencyBoost * 0.2 + 
                            relevanceBoost * 0.2 + diversityBoost * 0.1)
            
            result.copy(score = finalScore)
        }.sortedByDescending { it.score }
    }
    
    private fun calculateRecencyBoost(url: String): Double {
        val recentIndicators = listOf("2024", "2025", "2026", "latest", "new", "recent", "update")
        val hasRecent = recentIndicators.any { url.contains(it, ignoreCase = true) }
        return if (hasRecent) 0.8 else 0.3
    }
    
    private fun calculateRelevanceBoost(result: TavilyResult, context: SearchContext): Double {
        var boost = 0.5
        
        if (context.domain != null) {
            val hasDomain = result.url.contains(context.domain, ignoreCase = true) ||
                           result.title.contains(context.domain, ignoreCase = true)
            if (hasDomain) boost += 0.3
        }
        
        if (context.userIntent == "informational") {
            val hasHow = result.title.contains("how", ignoreCase = true) ||
                        result.content.contains("how to", ignoreCase = true)
            if (hasHow) boost += 0.2
        }
        
        return boost.coerceIn(0.0, 1.0)
    }
    
    private fun calculateDiversityBoost(result: TavilyResult, allResults: List<TavilyResult>): Double {
        try {
            val urlDomain = java.net.URL(result.url).host
            val sameDomain = allResults.count { 
                try {
                    java.net.URL(it.url).host == urlDomain
                } catch (e: Exception) {
                    false
                }
            }
            
            return if (sameDomain == 1) 0.8 else 0.3
        } catch (e: Exception) {
            return 0.5
        }
    }
    
    private suspend fun performSearchWithRetry(
        query: String,
        maxResults: Int
    ): Pair<List<TavilyResult>?, String> {
        if (apiKeys.isEmpty()) {
            return null to "No API keys configured"
        }
        
        val maxAttempts = apiKeys.size
        var lastErrorMessage = ""
        
        for (attempt in 0 until maxAttempts) {
            val keyIndex = Math.abs(currentIndex.getAndIncrement() % apiKeys.size)
            val apiKey = apiKeys[keyIndex]
            
            logger.info("Performing Tavily search using key at index $keyIndex (attempt ${attempt + 1}/$maxAttempts)")
            
            try {
                val startTime = System.currentTimeMillis()
                
                val response = client.post("https://api.tavily.com/search") {
                    contentType(ContentType.Application.Json)
                    setBody(TavilyRequest(
                        apiKey = apiKey,
                        query = query,
                        searchDepth = "basic",
                        maxResults = maxResults
                    ))
                }
                
                val responseTime = System.currentTimeMillis() - startTime
                
                updateKeyStats(keyIndex, responseTime, response.status)
                
                if (response.status.isSuccess()) {
                    val tavilyResponse: TavilyResponse = response.body()
                    totalSearches.incrementAndGet()
                    successfulSearches.incrementAndGet()
                    totalResponseTime.addAndGet(responseTime)
                    
                    return Pair(tavilyResponse.results, "")
                } else if (response.status == HttpStatusCode.TooManyRequests) {
                    lastErrorMessage = "Rate limited on key $keyIndex"
                    logger.warn("$lastErrorMessage. Trying next key...")
                    keyStats[keyIndex]?.let { stats ->
                        keyStats[keyIndex] = stats.copy(rateLimitHits = stats.rateLimitHits + 1)
                    }
                    
                    delay(RATE_LIMIT_DELAY_MS.toLong())
                    continue
                } else if (response.status == HttpStatusCode.Unauthorized) {
                    lastErrorMessage = "Invalid API key at index $keyIndex"
                    logger.warn("$lastErrorMessage. Trying next key...")
                    keyStats[keyIndex]?.let { stats ->
                        keyStats[keyIndex] = stats.copy(
                            healthScore = 0.0,
                            failureCount = stats.failureCount + 1
                        )
                    }
                    continue
                } else {
                    val errorBody = response.status.description
                    return null to "HTTP error: $errorBody"
                }
            } catch (e: Exception) {
                logger.error("Tavily search failed for key at index $keyIndex", e)
                lastErrorMessage = e.message ?: "Unknown error"
                
                keyStats[keyIndex]?.let { stats ->
                    keyStats[keyIndex] = stats.copy(
                        failureCount = stats.failureCount + 1,
                        healthScore = maxOf(0.0, stats.healthScore - 0.1)
                    )
                }
                
                if (attempt == maxAttempts - 1) break
            }
        }
        
        totalSearches.incrementAndGet()
        return null to "All API keys failed. Last error: $lastErrorMessage"
    }
    
    private fun updateKeyStats(keyIndex: Int, responseTime: Long, status: HttpStatusCode) {
        val stats = keyStats[keyIndex] ?: return
        
        val newUsageCount = stats.usageCount + 1
        val newSuccessCount = if (status.isSuccess()) stats.successCount + 1 else stats.successCount
        val newFailureCount = if (!status.isSuccess()) stats.failureCount + 1 else stats.failureCount
        val newAvgTime = (stats.avgResponseTime * stats.usageCount + responseTime) / newUsageCount
        
        val healthScore = if (newUsageCount > 10) {
            (newSuccessCount.toDouble() / newUsageCount) * 
            (1.0 - stats.rateLimitHits * 0.1).coerceAtLeast(0.1)
        } else 1.0
        
        keyStats[keyIndex] = stats.copy(
            usageCount = newUsageCount,
            successCount = newSuccessCount,
            failureCount = newFailureCount,
            avgResponseTime = newAvgTime,
            lastUsed = System.currentTimeMillis(),
            healthScore = healthScore
        )
    }
    
    private fun updateQueryAnalytics(query: String, resultCount: Int) {
        val normalizedQuery = query.lowercase().trim()
        
        queryHistory.getOrPut(normalizedQuery) { mutableListOf() }.add(System.currentTimeMillis())
        
        val freq = queryFrequencies.getOrPut(normalizedQuery) {
            QueryFrequency(normalizedQuery, 0, 0.0, System.currentTimeMillis())
        }
        
        queryFrequencies[normalizedQuery] = freq.copy(
            frequency = freq.frequency + 1,
            avgResults = (freq.avgResults * freq.frequency + resultCount) / (freq.frequency + 1),
            lastSearched = System.currentTimeMillis()
        )
    }
    
    private fun generateCacheKey(query: String): String {
        return query.lowercase().trim().hashCode().toString()
    }
    
    private fun isCacheExpired(cached: SearchCache): Boolean {
        return System.currentTimeMillis() > cached.expiresAt
    }
    
    private fun pruneCache() {
        if (searchCache.size > MAX_CACHE_SIZE) {
            val sorted = searchCache.values.sortedBy { it.cachedAt }
            repeat(searchCache.size - MAX_CACHE_SIZE + 10) { index ->
                sorted.getOrNull(index)?.let { searchCache.remove(it.queryHash) }
            }
        }
    }
    
    private fun formatResults(results: List<TavilyResult>, useCache: Boolean): String {
        if (results.isEmpty()) return "No results found."
        
        val prefix = if (useCache) "[Cached] " else ""
        
        return buildString {
            appendLine("${prefix}### Search Results (${results.size} found)")
            results.forEachIndexed { index, result ->
                appendLine("${index + 1}. **[${result.title}](${result.url})**")
                appendLine("   ${result.content.take(200)}...")
                appendLine("   Score: ${"%.2f".format(result.score)}")
                appendLine()
            }
        }
    }
    
    fun getMetrics(): SearchMetrics {
        val total = totalSearches.get()
        val success = successfulSearches.get()
        val cacheHit = cacheHits.get()
        
        val queryAnalytics = QueryAnalytics(
            totalQueries = total,
            uniqueQueries = queryFrequencies.size.toLong(),
            avgResultsPerQuery = if (total > 0) {
                queryFrequencies.values.map { it.avgResults }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
            } else 0.0,
            topQueries = queryFrequencies.values.sortedByDescending { it.frequency }.take(10),
            queryComplexity = emptyMap()
        )
        
        return SearchMetrics(
            totalSearches = total,
            successRate = if (total > 0) success.toDouble() / total else 0.0,
            avgResponseTime = if (total > 0) totalResponseTime.get().toDouble() / total else 0.0,
            cacheHitRate = if (total > 0) cacheHit.toDouble() / total else 0.0,
            apiKeyStats = keyStats.mapValues { it.value },
            queryAnalytics = queryAnalytics
        )
    }
    
    fun getBestKey(): Int? {
        return keyStats.entries
            .filter { it.value.healthScore > 0.5 }
            .minByOrNull { it.value.avgResponseTime }
            ?.key
    }
    
    fun clearCache() {
        searchCache.clear()
        logger.info("Search cache cleared")
    }
    
    fun formatMetrics(): String {
        val metrics = getMetrics()
        
        return buildString {
            appendLine("[Tavily Search Metrics]")
            appendLine("=".repeat(40))
            appendLine("Total Searches: ${metrics.totalSearches}")
            appendLine("Success Rate: ${"%.1f".format(metrics.successRate * 100)}%")
            appendLine("Avg Response Time: ${"%.0f".format(metrics.avgResponseTime)}ms")
            appendLine("Cache Hit Rate: ${"%.1f".format(metrics.cacheHitRate * 100)}%")
            
            appendLine("\n[API Key Statistics]")
            metrics.apiKeyStats.values.sortedBy { it.keyIndex }.forEach { stats ->
                appendLine("  Key ${stats.keyIndex}: ${stats.usageCount} uses, ${"%.1f".format(stats.healthScore * 100)}% health")
                appendLine("    Success: ${stats.successCount}, Failures: ${stats.failureCount}, Rate limits: ${stats.rateLimitHits}")
            }
            
            appendLine("\n[Top Queries]")
            metrics.queryAnalytics.topQueries.take(5).forEach { query ->
                appendLine("  \"${query.query}\": ${query.frequency}x (avg ${"%.1f".format(query.avgResults)} results)")
            }
        }
    }
}

@Serializable
data class TavilyRequest(
    @SerialName("api_key") val apiKey: String,
    val query: String,
    @SerialName("search_depth") val searchDepth: String = "basic",
    @SerialName("max_results") val maxResults: Int = 5
)

@Serializable
data class TavilyResponse(
    val results: List<TavilyResult>
)

@Serializable
data class TavilyResult(
    val title: String,
    val url: String,
    val content: String,
    val score: Double
)
