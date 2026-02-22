package com.example.smarty.server.tools

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest
import kotlin.math.*
import kotlin.random.Random

@Serializable
data class FetchMetrics(
    val totalFetches: Long,
    val successRate: Double,
    val avgFetchTime: Double,
    val cacheHitRate: Double,
    val domainStats: Map<String, DomainStats>
)

@Serializable
data class DomainStats(
    val fetchCount: Long,
    val avgResponseTime: Double,
    val successCount: Long,
    val failureCount: Long,
    val lastFetched: Long,
    val reputationScore: Double
)

@Serializable
data class ContentAnalysis(
    val language: String,
    val readabilityScore: Double,
    val sentiment: String,
    val sentimentScore: Double,
    val entities: List<String>,
    val topics: List<String>,
    val contentType: String,
    val freshness: String,
    val wordCount: Int,
    val complexity: Int
)

@Serializable
data class URLReputation(
    val domain: String,
    val safetyScore: Double,
    val category: String,
    val trustLevel: String,
    val knownPhishing: Boolean,
    val lastVerified: Long
)

@Serializable
data class CachedContent(
    val url: String,
    val content: String,
    val format: String,
    val cachedAt: Long,
    val expiresAt: Long,
    val contentHash: String,
    val accessCount: Int,
    val analysis: ContentAnalysis?
)

class WebFetchTool(private val client: HttpClient) {
    private val logger = LoggerFactory.getLogger(WebFetchTool::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val contentCache = ConcurrentHashMap<String, CachedContent>()
    private val domainStats = ConcurrentHashMap<String, DomainStats>()
    private val fetchHistory = ConcurrentHashMap<String, MutableList<Long>>()
    
    private val totalFetches = AtomicLong(0)
    private val successfulFetches = AtomicLong(0)
    private val totalFetchTime = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    
    private val trustedDomains = setOf(
        "github.com", "stackoverflow.com", "wikipedia.org", "medium.com",
        "dev.to", "docs.python.org", "kotlinlang.org", "developer.android.com",
        "microsoft.com", "apple.com", "google.com", "wikipedia.org"
    )
    
    private val suspiciousPatterns = listOf(
        "login", "signin", "verify", "account", "password", "banking",
        "secure", "update", "confirm", "suspended"
    )
    
    companion object {
        private const val MAX_CONTENT_LENGTH = 100_000
        private const val TIMEOUT_MS = 30_000L
        private const val CACHE_TTL_MS = 3600000L
        private const val MAX_CACHE_SIZE = 100
    }
    
    suspend fun fetch(
        url: String,
        format: String = "readable",
        useCache: Boolean = true,
        analyze: Boolean = false
    ): String {
        return try {
            if (!validateURL(url)) {
                return "Error: Invalid or unsafe URL"
            }
            
            val cacheKey = generateCacheKey(url, format)
            
            if (useCache) {
                val cached = contentCache[cacheKey]
                if (cached != null && !isCacheExpired(cached)) {
                    cacheHits.incrementAndGet()
                    logger.info("Returning cached content for $url")
                    return cached.content
                }
            }
            
            updateDomainStats(url, "fetching")
            
            val response: HttpResponse = client.get(url) {
                timeout {
                    requestTimeoutMillis = TIMEOUT_MS
                    connectTimeoutMillis = 10_000
                }
                headers {
                    append("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    append("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    append("Accept-Language", "en-US,en;q=0.5")
                    append("Accept-Encoding", "gzip, deflate")
                }
            }
            
            val startTime = System.currentTimeMillis()
            
            if (!response.status.isSuccess()) {
                updateDomainStats(url, "failure", TIMEOUT_MS)
                return "Error: HTTP ${response.status.value} - ${response.status.description}"
            }
            
            val contentType = response.headers["Content-Type"] ?: ""
            val body = response.bodyAsText()
            val fetchTime = System.currentTimeMillis() - startTime
            
            val processedContent = when (format) {
                "raw" -> body
                "readable" -> extractReadableContent(body, contentType, url)
                "markdown" -> htmlToMarkdown(extractReadableContent(body, contentType, url), url)
                "analyzed" -> {
                    val analysis = analyzeContent(body, contentType, url)
                    processAnalyzedContent(body, contentType, url, analysis)
                }
                else -> extractReadableContent(body, contentType, url)
            }
            
            val contentHash = generateContentHash(processedContent)
            
            if (useCache) {
                val analysis = if (analyze) analyzeContent(body, contentType, url) else null
                contentCache[cacheKey] = CachedContent(
                    url = url,
                    content = processedContent,
                    format = format,
                    cachedAt = System.currentTimeMillis(),
                    expiresAt = System.currentTimeMillis() + CACHE_TTL_MS,
                    contentHash = contentHash,
                    accessCount = 1,
                    analysis = analysis
                )
                pruneCache()
            }
            
            updateDomainStats(url, "success", fetchTime)
            recordFetch(url, fetchTime)
            
            totalFetches.incrementAndGet()
            successfulFetches.incrementAndGet()
            totalFetchTime.addAndGet(fetchTime)
            
            processedContent
        } catch (e: Exception) {
            logger.error("Fetch failed for $url", e)
            totalFetches.incrementAndGet()
            "Error fetching URL: ${e.message}"
        }
    }
    
    private fun validateURL(url: String): Boolean {
        return try {
            val parsed = URL(url)
            val host = parsed.host.lowercase()
            
            if (host in trustedDomains) return true
            
            val domainParts = host.split(".")
            if (domainParts.size < 2) return false
            
            suspiciousPatterns.forEach { pattern ->
                if (host.contains(pattern) && host.count { it == '.' } > 2) {
                    logger.warn("Suspicious URL pattern detected: $url")
                }
            }
            
            val ipPattern = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
            if (ipPattern.matches(host)) {
                logger.warn("Direct IP address URL detected: $url")
                return false
            }
            
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun analyzeContent(html: String, contentType: String, url: String): ContentAnalysis {
        val text = if (contentType.contains("text/html", ignoreCase = true)) {
            extractReadableContent(html, contentType, url)
        } else html
        
        val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val sentences = text.split(Regex("[.!?]+")).filter { it.isNotBlank() }.size
        val avgWordsPerSentence = if (sentences > 0) wordCount.toDouble() / sentences else wordCount.toDouble()
        
        val readabilityScore = calculateReadabilityScore(avgWordsPerSentence, wordCount)
        
        val sentiment = detectSentiment(text)
        
        val entities = extractEntities(text)
        
        val topics = extractTopics(text)
        
        val language = detectLanguage(text)
        
        val complexity = when {
            avgWordsPerSentence > 25 -> 3
            avgWordsPerSentence > 15 -> 2
            else -> 1
        }
        
        return ContentAnalysis(
            language = language,
            readabilityScore = readabilityScore,
            sentiment = sentiment.first,
            sentimentScore = sentiment.second,
            entities = entities,
            topics = topics,
            contentType = contentType.split(";").firstOrNull()?.trim() ?: "unknown",
            freshness = "unknown",
            wordCount = wordCount,
            complexity = complexity
        )
    }
    
    private fun calculateReadabilityScore(avgWordsPerSentence: Double, wordCount: Int): Double {
        val score = when {
            avgWordsPerSentence < 10 && wordCount > 100 -> 0.9
            avgWordsPerSentence < 15 && wordCount > 50 -> 0.75
            avgWordsPerSentence < 20 -> 0.6
            avgWordsPerSentence < 30 -> 0.4
            else -> 0.2
        }
        return score
    }
    
    private fun detectSentiment(text: String): Pair<String, Double> {
        val positiveWords = listOf("good", "great", "excellent", "amazing", "wonderful", "fantastic", "love", "best", "perfect", "helpful")
        val negativeWords = listOf("bad", "terrible", "awful", "horrible", "worst", "hate", "poor", "fail", "error", "bug", "issue", "problem")
        
        val words = text.lowercase().split(Regex("\\W+"))
        val positiveCount = words.count { it in positiveWords }
        val negativeCount = words.count { it in negativeWords }
        
        val total = positiveCount + negativeCount
        if (total == 0) return "neutral" to 0.5
        
        val score = (positiveCount - negativeCount).toDouble() / total
        
        return when {
            score > 0.3 -> "positive" to (0.5 + score * 0.5)
            score < -0.3 -> "negative" to (0.5 + score.abs() * 0.5)
            else -> "neutral" to 0.5
        }
    }
    
    private fun extractEntities(text: String): List<String> {
        val capitalized = Regex("""\b[A-Z][a-z]+(?:\s+[A-Z][a-z]+)*\b""").findAll(text)
            .map { it.value }
            .filter { it.length > 3 }
            .distinct()
            .take(10)
            .toList()
        return capitalized
    }
    
    private fun extractTopics(text: String): List<String> {
        val topicKeywords = mapOf(
            "technology" to listOf("software", "code", "programming", "developer", "api", "database"),
            "business" to listOf("market", "company", "revenue", "customer", "sales", "growth"),
            "science" to listOf("research", "study", "data", "experiment", "theory", "discovery"),
            "health" to listOf("doctor", "patient", "treatment", "symptom", "disease", "medicine"),
            "education" to listOf("student", "teacher", "school", "learning", "course", "education")
        )
        
        val words = text.lowercase().split(Regex("\\W+"))
        
        return topicKeywords.mapNotNull { (topic, keywords) ->
            val matches = keywords.count { it in words }
            if (matches >= 2) topic else null
        }.take(3)
    }
    
    private fun detectLanguage(text: String): String {
        val commonEnglishWords = setOf("the", "is", "at", "which", "on", "and", "a", "an", "in", "to", "of", "for", "with", "that", "this")
        val words = text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }
        val englishCount = words.count { it in commonEnglishWords }
        val ratio = if (words.isNotEmpty()) englishCount.toDouble() / words.size else 0.0
        
        return if (ratio > 0.05) "en" else "unknown"
    }
    
    private fun processAnalyzedContent(html: String, contentType: String, url: String, analysis: ContentAnalysis): String {
        val readable = extractReadableContent(html, contentType, url)
        
        return buildString {
            appendLine("## Content Analysis")
            appendLine("---")
            appendLine("**Source:** $url")
            appendLine("**Language:** ${analysis.language}")
            appendLine("**Word Count:** ${analysis.wordCount}")
            appendLine("**Readability:** ${(analysis.readabilityScore * 100).toInt()}%")
            appendLine("**Sentiment:** ${analysis.sentiment} (${(analysis.sentimentScore * 100).toInt()}%)")
            appendLine("**Complexity:** ${analysis.complexity}/3")
            
            if (analysis.topics.isNotEmpty()) {
                appendLine("**Topics:** ${analysis.topics.joinToString(", ")}")
            }
            
            if (analysis.entities.isNotEmpty()) {
                appendLine("**Entities:** ${analysis.entities.take(5).joinToString(", ")}")
            }
            
            appendLine("\n---")
            appendLine("\n## Content")
            appendLine(readable)
        }
    }
    
    private fun extractReadableContent(html: String, contentType: String, url: String): String {
        if (!contentType.contains("text/html", ignoreCase = true)) {
            return html.take(MAX_CONTENT_LENGTH)
        }
        
        var content = html
        
        content = removeScriptAndStyle(content)
        content = extractMainContent(content)
        content = cleanHtml(content)
        content = normalizeWhitespace(content)
        
        return buildString {
            appendLine("--- Source: $url ---")
            appendLine(content.take(MAX_CONTENT_LENGTH))
            if (content.length > MAX_CONTENT_LENGTH) {
                appendLine("\n[...content truncated...]")
            }
        }
    }
    
    private fun removeScriptAndStyle(html: String): String {
        var result = html
        result = Regex("""<script[^>]*>.*?</script>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).replace(result, "")
        result = Regex("""<style[^>]*>.*?</style>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).replace(result, "")
        result = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL).replace(result, "")
        result = Regex("""<nav[^>]*>.*?</nav>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).replace(result, "")
        result = Regex("""<footer[^>]*>.*?</footer>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).replace(result, "")
        result = Regex("""<header[^>]*>.*?</header>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).replace(result, "")
        result = Regex("""<aside[^>]*>.*?</aside>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).replace(result, "")
        return result
    }
    
    private fun extractMainContent(html: String): String {
        val articleMatch = Regex("""<article[^>]*>(.*?)</article>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).find(html)
        if (articleMatch != null) return articleMatch.groupValues[1]
        
        val mainMatch = Regex("""<main[^>]*>(.*?)</main>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).find(html)
        if (mainMatch != null) return mainMatch.groupValues[1]
        
        val contentDiv = Regex("""<div[^>]*(?:class|id)=["'](?:content|article|post|entry|body|main)["'][^>]*>(.*?)</div>""", 
            RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).find(html)
        if (contentDiv != null) return contentDiv.groupValues[1]
        
        val bodyMatch = Regex("""<body[^>]*>(.*?)</body>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).find(html)
        return bodyMatch?.groupValues?.get(1) ?: html
    }
    
    private fun cleanHtml(html: String): String {
        var text = html
        
        text = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(text, "\n")
        text = Regex("""</p>""", RegexOption.IGNORE_CASE).replace(text, "\n\n")
        text = Regex("""</div>""", RegexOption.IGNORE_CASE).replace(text, "\n")
        text = Regex("""</li>""", RegexOption.IGNORE_CASE).replace(text, "\n")
        text = Regex("""<li[^>]*>""", RegexOption.IGNORE_CASE).replace(text, "- ")
        text = Regex("""</h[1-6]>""", RegexOption.IGNORE_CASE).replace(text, "\n\n")
        text = Regex("""<h[1-6][^>]*>""", RegexOption.IGNORE_CASE).replace(text, "\n## ")
        
        text = Regex("""<a[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE)
            .replace(text) { matchResult ->
                val href = matchResult.groupValues[1]
                val linkText = matchResult.groupValues[2].replace(Regex("<[^>]+>"), "")
                if (linkText.isNotBlank() && href.isNotBlank()) "[$linkText]($href)" else linkText
            }
        
        text = Regex("""<[^>]+>""").replace(text, "")
        
        text = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        
        return text
    }
    
    private fun normalizeWhitespace(text: String): String {
        return text
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .lines()
            .map { it.trim() }
            .joinToString("\n")
            .trim()
    }
    
    private fun htmlToMarkdown(text: String, url: String): String {
        return "## Content from: $url\n\n$text"
    }
    
    suspend fun extractLinks(url: String): String {
        return try {
            val response = client.get(url) {
                timeout {
                    requestTimeoutMillis = TIMEOUT_MS
                }
                headers {
                    append("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                }
            }
            
            if (!response.status.isSuccess()) {
                return "Error: HTTP ${response.status.value}"
            }
            
            val body = response.bodyAsText()
            val links = mutableMapOf<String, String>()
            
            Regex("""<a[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE)
                .findAll(body)
                .forEach { match ->
                    val href = match.groupValues[1]
                    val text = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                    if (href.startsWith("http") && text.isNotBlank()) {
                        links[href] = text.take(100)
                    }
                }
            
            if (links.isEmpty()) "No links found."
            else {
                "Found ${links.size} links:\n" + links.entries.take(30).joinToString("\n") { "- [${it.value}](${it.key})" }
            }
        } catch (e: Exception) {
            "Error extracting links: ${e.message}"
        }
    }
    
    fun getURLReputation(url: String): URLReputation {
        return try {
            val parsed = URL(url)
            val domain = parsed.host
            val isTrusted = domain in trustedDomains
            
            URLReputation(
                domain = domain,
                safetyScore = if (isTrusted) 0.95 else 0.7,
                category = categorizeDomain(domain),
                trustLevel = if (isTrusted) "high" else "medium",
                knownPhishing = false,
                lastVerified = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            URLReputation(
                domain = "unknown",
                safetyScore = 0.0,
                category = "unknown",
                trustLevel = "unknown",
                knownPhishing = false,
                lastVerified = System.currentTimeMillis()
            )
        }
    }
    
    private fun categorizeDomain(domain: String): String {
        val categories = mapOf(
            "news" to listOf("news", "times", "post", "herald", "chronicle"),
            "education" to listOf("edu", "university", "college", "school", "academy"),
            "tech" to listOf("dev", "tech", "code", "programming", "developer", "docs"),
            "social" to listOf("facebook", "twitter", "instagram", "linkedin", "reddit"),
            "shopping" to listOf("shop", "store", "buy", "amazon", "ebay")
        )
        
        categories.forEach { (category, keywords) ->
            if (keywords.any { domain.contains(it) }) return category
        }
        
        return "general"
    }
    
    private fun generateCacheKey(url: String, format: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest("$url:$format".toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun generateContentHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun isCacheExpired(cached: CachedContent): Boolean {
        return System.currentTimeMillis() > cached.expiresAt
    }
    
    private fun pruneCache() {
        if (contentCache.size > MAX_CACHE_SIZE) {
            val sorted = contentCache.values.sortedBy { it.cachedAt }
            repeat(contentCache.size - MAX_CACHE_SIZE + 10) { index ->
                sorted.getOrNull(index)?.let { contentCache.remove(it.url) }
            }
        }
    }
    
    private fun updateDomainStats(url: String, status: String, responseTime: Long = 0) {
        try {
            val domain = URL(url).host
            val stats = domainStats.getOrPut(domain) {
                DomainStats(0, 0.0, 0, 0, 0, 0.5)
            }
            
            val newStats = when (status) {
                "success" -> stats.copy(
                    fetchCount = stats.fetchCount + 1,
                    successCount = stats.successCount + 1,
                    avgResponseTime = (stats.avgResponseTime * stats.fetchCount + responseTime) / (stats.fetchCount + 1),
                    lastFetched = System.currentTimeMillis(),
                    reputationScore = minOf(1.0, stats.reputationScore + 0.01)
                )
                "failure" -> stats.copy(
                    fetchCount = stats.fetchCount + 1,
                    failureCount = stats.failureCount + 1,
                    reputationScore = maxOf(0.1, stats.reputationScore - 0.05)
                )
                else -> stats
            }
            
            domainStats[domain] = newStats
        } catch (e: Exception) {
            logger.debug("Failed to update domain stats: ${e.message}")
        }
    }
    
    private fun recordFetch(url: String, time: Long) {
        try {
            val domain = URL(url).host
            val history = fetchHistory.getOrPut(domain) { mutableListOf() }
            history.add(time)
            if (history.size > 100) history.removeAt(0)
        } catch (e: Exception) {
            logger.debug("Failed to record fetch: ${e.message}")
        }
    }
    
    fun getMetrics(): FetchMetrics {
        val total = totalFetches.get()
        val success = successfulFetches.get()
        val cacheHit = cacheHits.get()
        
        return FetchMetrics(
            totalFetches = total,
            successRate = if (total > 0) success.toDouble() / total else 0.0,
            avgFetchTime = if (total > 0) totalFetchTime.get().toDouble() / total else 0.0,
            cacheHitRate = if (total > 0) cacheHit.toDouble() / total else 0.0,
            domainStats = domainStats.mapValues { (_, stats) ->
                DomainStats(
                    fetchCount = stats.fetchCount,
                    avgResponseTime = stats.avgResponseTime,
                    successCount = stats.successCount,
                    failureCount = stats.failureCount,
                    lastFetched = stats.lastFetched,
                    reputationScore = stats.reputationScore
                )
            }
        )
    }
    
    fun clearCache() {
        contentCache.clear()
        logger.info("Content cache cleared")
    }
    
    fun formatMetrics(): String {
        val metrics = getMetrics()
        
        return buildString {
            appendLine("[Web Fetch Metrics]")
            appendLine("=".repeat(40))
            appendLine("Total Fetches: ${metrics.totalFetches}")
            appendLine("Success Rate: ${"%.1f".format(metrics.successRate * 100)}%")
            appendLine("Avg Fetch Time: ${"%.0f".format(metrics.avgFetchTime)}ms")
            appendLine("Cache Hit Rate: ${"%.1f".format(metrics.cacheHitRate * 100)}%")
            appendLine("\n[Domain Statistics]")
            metrics.domainStats.entries.sortedByDescending { it.value.fetchCount }.take(5).forEach { (domain, stats) ->
                appendLine("  $domain: ${stats.fetchCount} fetches, ${"%.0f".format(stats.reputationScore * 100)}% trust")
            }
        }
    }
}
