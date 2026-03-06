package com.example.smarty.server.tools

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Web Search Tool - Performs web searches using Serper API.
 * Returns search results with URLs, titles, and snippets.
 */
class WebSearchTool(private val apiKey: String) {
    companion object {
        private val logger = LoggerFactory.getLogger(WebSearchTool::class.java)
        private const val SERPER_URL = "https://google.serper.dev/search"
    }
    
    @Serializable
    data class SearchResult(
        val title: String,
        val link: String,
        val snippet: String,
        val position: Int
    )
    
    @Serializable
    data class SerperResponse(
        val organic: List<SerperResult>?
    )
    
    @Serializable
    data class SerperResult(
        val title: String,
        val link: String,
        val snippet: String,
        val position: Int
    )
    
    /**
     * Search the web and return results
     */
    suspend fun search(query: String, numResults: Int = 10): List<SearchResult> {
        return try {
            val client = HttpClient()
            
            val response = client.post(SERPER_URL) {
                headers {
                    append("X-API-KEY", apiKey)
                    append("Content-Type", "application/json")
                }
                setBody(mapOf("q" to query, "num" to numResults))
            }
            
            val serperResponse: SerperResponse = response.body()
            
            client.close()
            
            serperResponse.organic?.map { result ->
                SearchResult(
                    title = result.title,
                    link = result.link,
                    snippet = result.snippet,
                    position = result.position
                )
            } ?: emptyList()
            
        } catch (e: Exception) {
            logger.error("Web search failed: ${e.message}")
            emptyList()
        }
    }
}
