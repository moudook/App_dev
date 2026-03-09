package com.example.smarty.server.tools

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Server-side tool for Web Search using Tavily API.
 * Returns clean Markdown/JSON results with API key rotation support.
 */
class TavilySearchTool {
    private val logger = LoggerFactory.getLogger(TavilySearchTool::class.java)

    // Support multiple API keys separated by comma
    private val apiKeys: List<String> = System.getenv("TAVILY_API_KEY")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?: emptyList()

    private val currentIndex = AtomicInteger(0)

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    /**
     * Perform a search and return a formatted markdown string.
     * Rotates through available API keys on each call and retries on failure.
     */
    suspend fun search(query: String): String {
        if (apiKeys.isEmpty()) {
            return "Error: Web search is not configured (missing TAVILY_API_KEY)."
        }

        val maxAttempts = apiKeys.size
        var lastErrorMessage = ""

        for (attempt in 0 until maxAttempts) {
            val index = Math.abs(currentIndex.getAndIncrement() % apiKeys.size)
            val apiKey = apiKeys[index]

            logger.info("Performing Tavily search using key at index $index (attempt ${attempt + 1}/$maxAttempts)")

            try {
                val response = client.post("https://api.tavily.com/search") {
                    contentType(ContentType.Application.Json)
                    setBody(TavilyRequest(
                        apiKey = apiKey,
                        query = query,
                        searchDepth = "basic",
                        maxResults = 5
                    ))
                }

                if (response.status.isSuccess()) {
                    val tavilyResponse: TavilyResponse = response.body()
                    return formatResults(tavilyResponse.results)
                } else if (response.status == HttpStatusCode.TooManyRequests || response.status == HttpStatusCode.Unauthorized) {
                    lastErrorMessage = "Key at index $index failed with ${response.status}"
                    logger.warn("$lastErrorMessage. Trying next key...")
                    continue
                } else {
                    val errorBody = response.status.description
                    return "Error performing web search: $errorBody"
                }
            } catch (e: Exception) {
                logger.error("Tavily search failed for key at index $index", e)
                lastErrorMessage = e.message ?: "Unknown error"
                if (attempt == maxAttempts - 1) break
            }
        }

        return "Error performing web search: All configured keys failed. Last error: $lastErrorMessage"
    }

    /**
     * Perform MULTIPLE searches in PARALLEL and aggregate results.
     * All queries run simultaneously, results combined and deduplicated.
     * 
     * @param queries List of search queries to run in parallel
     * @return Combined search results from all queries
     */
    suspend fun searchParallel(queries: List<String>): String = withContext(Dispatchers.IO) {
        if (queries.isEmpty()) {
            return@withContext "Error: No search queries provided."
        }
        
        if (apiKeys.isEmpty()) {
            return@withContext "Error: Web search is not configured (missing TAVILY_API_KEY)."
        }

        logger.info("Running ${queries.size} parallel searches")

        // Run all searches concurrently using coroutineScope
        val results = kotlinx.coroutines.coroutineScope {
            queries.map { query ->
                async {
                    val result = search(query)
                    query to result
                }
            }.awaitAll()
        }

        // Aggregate and deduplicate results
        val allResults = mutableListOf<String>()
        val seenUrls = mutableSetOf<String>()

        results.forEach { (query, resultText) ->
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
            appendLine("### Combined Search Results")
            appendLine("Queries: ${queries.joinToString(", ")}")
            appendLine("Unique sources: ${seenUrls.size}\n")
            append(allResults.joinToString("\n"))
        }
    }

    private fun formatResults(results: List<TavilyResult>): String {
        if (results.isEmpty()) return "No results found."

        return buildString {
            appendLine("### Search Results")
            results.forEach { result ->
                appendLine("- **[${result.title}](${result.url})**")
                appendLine("  ${result.content}")
                appendLine()
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
