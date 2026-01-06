package com.example.smarty.agent.tools.external

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.util.Log
import com.example.smarty.agent.tools.base.WebResult
import com.example.smarty.agent.tools.base.WebSearchResult
import com.example.smarty.data.remote.providers.TavilySearchProvider
import kotlinx.serialization.Serializable

@Serializable
data class WebSearchArgs(
    @property:LLMDescription("The search query to find information on the internet")
    val query: String,
    @property:LLMDescription("Brief explanation of why this search is needed")
    val reason: String,
    @property:LLMDescription("Topic category: 'general', 'news', or 'finance'")
    val topic: String = "general",
    @property:LLMDescription("Maximum number of results to return (1-10)")
    val maxResults: Int = 5
)

/**
 * Citation data from web search
 */
data class SearchCitation(
    val title: String,
    val url: String,
    val snippet: String
)

/**
 * Tool for searching the web via Tavily API.
 * Provides real-time information to the agent.
 */
class WebSearchTool(
    private val tavilySearchProvider: TavilySearchProvider,
    private val getApiKey: () -> String?,
    private val onCitationsFound: ((List<SearchCitation>) -> Unit)? = null
) : Tool<WebSearchArgs, WebSearchResult>(
    argsSerializer = WebSearchArgs.serializer(),
    resultSerializer = WebSearchResult.serializer(),
    name = "web_search",
    description = """ONLY use when user says "search for". Do NOT use for questions.""".trimIndent()
) {
    companion object {
        private const val TAG = "WebSearchTool"
        private const val TAVILY_RATE_KEY = "tavily_search"
        private const val TAVILY_DAILY_LIMIT = 1000  // Free tier limit

        // AGENT-010: Rate limit tracking (resets on app restart)
        // NOTE: Persistence requires Context injection which WebSearchTool doesn't support.
        // To add persistence, modify CogniAgent.buildToolRegistry() to inject SharedPreferences
        // or create a singleton RateLimitTracker that can be initialized with Application context.
        private var dailyCallCount = 0
        private var lastResetDay = 0L
    }

    private fun checkRateLimit(): Boolean {
        val today = System.currentTimeMillis() / (24 * 60 * 60 * 1000)
        if (today != lastResetDay) {
            dailyCallCount = 0
            lastResetDay = today
        }

        if (dailyCallCount >= TAVILY_DAILY_LIMIT) {
            Log.w(TAG, "Tavily daily rate limit reached")
            return false
        }

        dailyCallCount++
        return true
    }

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

        if (!checkRateLimit()) {
            return WebSearchResult(
                success = false,
                query = args.query,
                reason = args.reason,
                error = "Search rate limit reached. Please try again tomorrow."
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

                // Report citations to the callback
                if (results.isNotEmpty()) {
                    val citations = results.map { SearchCitation(it.title, it.url, it.snippet) }
                    onCitationsFound?.invoke(citations)
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
