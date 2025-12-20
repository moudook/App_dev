package com.example.smarty.agent.tools.external

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.util.Log
import com.example.smarty.agent.tools.base.WebResult
import com.example.smarty.agent.tools.base.WebSearchResult
import com.example.smarty.data.remote.providers.TavilySearchProvider
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable
data class WebSearchArgs(
    @property:LLMDescription("The search query to find information on the internet")
    val query: String,
    @property:LLMDescription("Brief explanation of why this search is needed")
    val reason: String,
    @property:LLMDescription("Topic category: 'general', 'news', 'research', or 'code'")
    val topic: String = "general",
    @property:LLMDescription("Maximum number of results to return (1-10)")
    val maxResults: Int = 5
)

/**
 * Tool for searching the web via Tavily API.
 * Provides real-time information to the agent.
 */
class WebSearchTool(
    private val tavilySearchProvider: TavilySearchProvider,
    private val getApiKey: () -> String?
) : Tool<WebSearchArgs, WebSearchResult>() {

    override val argsSerializer: KSerializer<WebSearchArgs> = WebSearchArgs.serializer()
    override val resultSerializer: KSerializer<WebSearchResult> = WebSearchResult.serializer()

    companion object {
        private const val TAG = "WebSearchTool"
    }

    override val name = "web_search"

    override val description = """
        Searches the internet for real-time information.
        Use for queries about current events, facts, weather, news,
        or any information not in your knowledge base.
        Provide a reason explaining why search is needed.
    """.trimIndent()

    override suspend fun execute(args: WebSearchArgs): WebSearchResult {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "No Tavily API key configured")
            return WebSearchResult(
                success = false,
                query = args.query,
                reason = args.reason,
                error = "Web search not configured"
            )
        }

        return try {
            Log.d(TAG, "Searching: '${args.query}' (${args.reason})")

            val searchResult = tavilySearchProvider.search(
                apiKey = apiKey,
                query = args.query,
                maxResults = args.maxResults,
                topic = args.topic
            )

            if (searchResult.success) {
                val results = searchResult.results.map { result ->
                    WebResult(
                        title = result.title,
                        url = result.url,
                        snippet = result.snippet
                    )
                }

                WebSearchResult(
                    success = true,
                    query = args.query,
                    reason = args.reason,
                    aiSummary = searchResult.answer,
                    results = results,
                    totalResults = results.size
                )
            } else {
                WebSearchResult(
                    success = false,
                    query = args.query,
                    reason = args.reason,
                    error = searchResult.error ?: "Search failed"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Web search error: ${e.message}", e)
            WebSearchResult(
                success = false,
                query = args.query,
                reason = args.reason,
                error = "Search error: ${e.message}"
            )
        }
    }
}
