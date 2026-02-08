package com.example.smarty.server.tools

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Server-side tool for Web Search using Tavily API.
 * Returns clean Markdown/JSON results.
 */
class TavilySearchTool {
    private val logger = LoggerFactory.getLogger(TavilySearchTool::class.java)
    private val apiKey = System.getenv("TAVILY_API_KEY")

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
     */
    suspend fun search(query: String): String {
        if (apiKey.isNullOrBlank()) {
            return "Error: Web search is not configured (missing TAVILY_API_KEY)."
        }

        return try {
            val response: TavilyResponse = client.post("https://api.tavily.com/search") {
                contentType(ContentType.Application.Json)
                setBody(TavilyRequest(
                    apiKey = apiKey,
                    query = query,
                    searchDepth = "basic",
                    maxResults = 5
                ))
            }.body()

            formatResults(response.results)
        } catch (e: Exception) {
            logger.error("Tavily search failed", e)
            "Error performing web search: ${e.message}"
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
