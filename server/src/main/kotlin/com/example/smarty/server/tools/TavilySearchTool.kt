package com.example.smarty.server.tools

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Server-side tool for Web Search using Tavily API.
 * Returns clean Markdown/JSON results with robust API key rotation support.
 * 
 * Features:
 * - Multiple API keys support (comma-separated in TAVILY_API_KEY env)
 * - Parallel search with distributed key usage
 * - Automatic key rotation on rate limiting
 * - Graceful degradation when keys are exhausted
 */
class TavilySearchTool {
    private val logger = LoggerFactory.getLogger(TavilySearchTool::class.java)

    // Support multiple API keys separated by comma
    private val apiKeys: List<String> = System.getenv("TAVILY_API_KEY")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?: emptyList()

    // Atomic counter for round-robin key selection
    private val keyIndex = AtomicInteger(0)

    // Track failed keys to avoid them temporarily
    private val failedKeys = mutableMapOf<String, Long>()
    private val keyFailureTimeout = 60_000L // 1 minute cooldown for failed keys

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
        engine {
            config {
                connectTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    init {
        logger.info("TavilySearchTool initialized with ${apiKeys.size} API key(s)")
        if (apiKeys.isEmpty()) {
            logger.warn("No TAVILY_API_KEY configured - web search will not work")
        }
    }

    /**
     * Perform a search and return a formatted markdown string.
     * Automatically detects single or multiple queries.
     * 
     * FORMAT FOR MULTIPLE QUERIES:
     * - Send queries separated by newlines with "SEARCH:" prefix
     * - Example:
     *   SEARCH: query 1
     *   SEARCH: query 2
     *   SEARCH: query 3
     */
    suspend fun search(query: String): String {
        val queries = parseMultiQuery(query)
        
        return if (queries.size > 1) {
            searchParallel(queries)
        } else {
            searchSingle(queries.firstOrNull() ?: query)
        }
    }

    /**
     * Parse multi-query format from agent.
     * Detects "SEARCH: query" lines and extracts all queries.
     */
    private fun parseMultiQuery(input: String): List<String> {
        val queries = mutableListOf<String>()
        val lines = input.split("\n")
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("SEARCH:", ignoreCase = true)) {
                val query = trimmed.substringAfter("SEARCH:", "").trim()
                if (query.isNotEmpty()) {
                    queries.add(query)
                }
            }
        }
        
        return if (queries.isEmpty()) listOf(input) else queries
    }

    /**
     * Get next available API key with round-robin distribution.
     * Skips keys that are currently in failure cooldown.
     */
    @Synchronized
    private fun getNextApiKey(): String? {
        if (apiKeys.isEmpty()) return null
        
        val now = System.currentTimeMillis()
        
        // Clean up expired failed keys
        failedKeys.entries.removeIf { (_, timestamp) -> now - timestamp > keyFailureTimeout }
        
        // Try each key once
        for (i in 0 until apiKeys.size) {
            val index = keyIndex.getAndIncrement()
            val key = apiKeys[index % apiKeys.size]
            
            // Skip keys in failure state
            if (!failedKeys.containsKey(key)) {
                return key
            }
        }
        
        // All keys failed, return first one anyway (for fallback)
        return apiKeys.firstOrNull()
    }

    /**
     * Mark a key as failed (rate limited or error)
     */
    @Synchronized
    private fun markKeyFailed(apiKey: String) {
        failedKeys[apiKey] = System.currentTimeMillis()
        logger.warn("API key starting with ${apiKey.take(8)}... marked as failed, will retry in ${keyFailureTimeout/1000}s")
    }

    /**
     * Perform a single search with automatic key rotation.
     */
    private suspend fun searchSingle(query: String): String {
        val apiKey = getNextApiKey() ?: return "Error: Web search is not configured (missing TAVILY_API_KEY)."

        val maxAttempts = max(apiKeys.size, 1)
        var lastErrorMessage = ""
        var usedKeys = mutableSetOf<String>()

        for (attempt in 0 until maxAttempts) {
            val currentKey = getNextApiKey() ?: break
            usedKeys.add(currentKey)

            logger.info("Tavily search attempt ${attempt + 1}/$maxAttempts for query: ${query.take(50)}...")

            try {
                val response = client.post("https://api.tavily.com/search") {
                    contentType(ContentType.Application.Json)
                    setBody(TavilyRequest(
                        apiKey = currentKey,
                        query = query,
                        searchDepth = "advanced",
                        maxResults = 10,
                        includeRawContent = true
                    ))
                }

                when {
                    response.status.isSuccess() -> {
                        val tavilyResponse: TavilyResponse = response.body()
                        return formatResults(tavilyResponse.results)
                    }
                    response.status == HttpStatusCode.TooManyRequests -> {
                        lastErrorMessage = "Rate limited (429)"
                        markKeyFailed(currentKey)
                        logger.warn("Rate limited on key ${currentKey.take(8)}..., trying next key")
                    }
                    response.status == HttpStatusCode.Unauthorized -> {
                        lastErrorMessage = "Invalid API key (401)"
                        markKeyFailed(currentKey)
                        logger.warn("Invalid API key ${currentKey.take(8)}..., trying next key")
                    }
                    else -> {
                        val errorBody = response.body<String>()
                        lastErrorMessage = "${response.status}: $errorBody"
                        logger.error("Tavily error: $lastErrorMessage")
                        return "Error performing web search: $lastErrorMessage"
                    }
                }
            } catch (e: Exception) {
                lastErrorMessage = e.message ?: "Unknown error"
                logger.error("Tavily search exception for key ${currentKey.take(8)}...", e)
                
                // Only mark as failed if it's a network/error, not just retry
                if (attempt == maxAttempts - 1) {
                    break
                }
            }
        }

        return "Error performing web search: All ${usedKeys.size} available keys failed. Last error: $lastErrorMessage"
    }

    /**
     * Perform MULTIPLE searches in PARALLEL with distributed API keys.
     * Each query gets its own API key for maximum throughput.
     */
    private suspend fun searchParallel(queries: List<String>): String = withContext(Dispatchers.IO) {
        if (queries.isEmpty()) {
            return@withContext "Error: No search queries provided."
        }
        
        if (apiKeys.isEmpty()) {
            return@withContext "Error: Web search is not configured (missing TAVILY_API_KEY)."
        }

        logger.info("Running ${queries.size} parallel searches with ${apiKeys.size} API key(s)")

        // Distribute keys across queries - each query gets its own key
        val results = kotlinx.coroutines.coroutineScope {
            queries.mapIndexed { index, query ->
                async {
                    // Assign key based on index to distribute evenly
                    val keyIndex = index % apiKeys.size
                    val apiKey = apiKeys.getOrNull(keyIndex) ?: apiKeys.first()
                    
                    val result = executeSearchWithKey(query, apiKey)
                    query to result
                }
            }.awaitAll()
        }

        // Aggregate results
        val allResults = mutableListOf<String>()
        val seenUrls = mutableSetOf<String>()
        var successCount = 0
        var errorCount = 0

        results.forEach { (query, resultText) ->
            if (resultText.startsWith("Error:")) {
                errorCount++
            } else {
                successCount++
            }
            
            allResults.add("## Query: $query\n")
            allResults.add(resultText)
            allResults.add("\n")
            
            // Extract URLs for deduplication
            val urlRegex = Regex("\\((https?://[^)]+)\\)")
            urlRegex.findAll(resultText).forEach { match ->
                seenUrls.add(match.groupValues[1])
            }
        }

        buildString {
            appendLine("### Parallel Search Results")
            appendLine("**Queries:** ${queries.size}")
            appendLine("**Successful:** $successCount")
            if (errorCount > 0) appendLine("**Failed:** $errorCount")
            appendLine("**Unique sources:** ${seenUrls.size}\n")
            append(allResults.joinToString("\n"))
        }
    }

    /**
     * Execute a single search with a specific API key (no retry logic, used for parallel).
     */
    private suspend fun executeSearchWithKey(query: String, apiKey: String): String {
        try {
            val response = client.post("https://api.tavily.com/search") {
                contentType(ContentType.Application.Json)
                setBody(TavilyRequest(
                    apiKey = apiKey,
                    query = query,
                    searchDepth = "advanced",
                    maxResults = 10,
                    includeRawContent = true
                ))
            }

            return try {
                val tavilyResponse: TavilyResponse = response.body()
                formatResults(tavilyResponse.results)
            } catch (e: Exception) {
                when (response.status) {
                    HttpStatusCode.TooManyRequests -> "Error: Rate limited on all keys"
                    HttpStatusCode.Unauthorized -> "Error: Invalid API key"
                    else -> "Error: ${response.status.description}"
                }
            }
        } catch (e: Exception) {
            logger.error("Parallel search failed for query: ${query.take(30)}...", e)
            return "Error: ${e.message ?: "Search failed"}"
        }
    }

    private fun formatResults(results: List<TavilyResult>): String {
        if (results.isEmpty()) return "No results found."

        // Sort by relevance score descending so the best sources come first
        val sorted = results.sortedByDescending { it.score }

        return buildString {
            appendLine("### Search Results (${sorted.size} sources)")
            sorted.forEachIndexed { i, result ->
                appendLine("#### ${i + 1}. [${result.title}](${result.url})  *(score: ${ "%.2f".format(result.score) })*")

                // Prefer raw full-page content for depth; fall back to snippet
                val body = if (!result.rawContent.isNullOrBlank()) {
                    result.rawContent.take(1500).let {
                        if (result.rawContent.length > 1500) "$it\n[...full article available]" else it
                    }
                } else {
                    result.content.take(800)
                }
                appendLine(body)
                appendLine()
            }
        }
    }

    fun close() {
        client.close()
    }
}

@Serializable
data class TavilyRequest(
    @SerialName("api_key") val apiKey: String,
    val query: String,
    @SerialName("search_depth") val searchDepth: String = "advanced",
    @SerialName("max_results") val maxResults: Int = 10,
    @SerialName("include_raw_content") val includeRawContent: Boolean = true
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
    val score: Double,
    @SerialName("raw_content") val rawContent: String? = null
)
