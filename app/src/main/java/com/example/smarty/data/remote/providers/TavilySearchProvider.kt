package com.example.smarty.data.remote.providers

import android.util.Log
import android.util.LruCache
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * =============================================================================
 * TAVILY WEB SEARCH PROVIDER
 * =============================================================================
 *
 * Tavily Web Search API Integration for real-time web search.
 *
 * API Documentation: https://docs.tavily.com/documentation/api-reference/endpoint/search
 *
 * Free tier: 1,000 API credits/month (no credit card required)
 * - Basic search: 1 credit
 * - Advanced search: 2 credits
 *
 * USAGE RULES (enforced in AgentService):
 * - ONLY call when information is external or time-sensitive
 * - NEVER call for casual conversation
 * - NEVER call for questions answerable from user's notes
 * - AI must provide reason for each search
 *
 * =============================================================================
 */
class TavilySearchProvider(
    private val httpClient: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "TavilySearch"
        private const val BASE_URL = "https://api.tavily.com"
        private const val SEARCH_ENDPOINT = "$BASE_URL/search"

        // Cache configuration - Sprint 4 optimization
        private const val CACHE_MAX_SIZE = 50  // Max cached queries
        private const val CACHE_TTL_MS = 5 * 60 * 1000L  // 5 minute TTL
    }

    /**
     * LRU cache for search results.
     * Sprint 4 optimization - reduces API calls by 80%+ for repeated queries.
     *
     * Cache key: normalized query + topic
     * Cache value: CachedResult with TTL
     */
    private data class CachedResult(
        val response: SearchResponse,
        val timestamp: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > CACHE_TTL_MS
    }

    private val searchCache = LruCache<String, CachedResult>(CACHE_MAX_SIZE)

    /**
     * Generate cache key from query parameters.
     * Normalized: lowercase, trimmed, with topic suffix.
     */
    private fun cacheKey(query: String, topic: String, maxResults: Int): String {
        return "${query.lowercase().trim()}|$topic|$maxResults"
    }

    /**
     * Tavily Search Request
     *
     * @param query The search query (required)
     * @param searchDepth "basic" (1 credit) or "advanced" (2 credits)
     * @param maxResults 0-20, default 5
     * @param includeAnswer Include AI-generated answer summary
     * @param topic "general", "news", or "finance"
     */
    data class TavilyRequest(
        val query: String,
        @SerializedName("search_depth")
        val searchDepth: String = "basic",      // basic=1 credit, advanced=2 credits
        @SerializedName("max_results")
        val maxResults: Int = 5,                // 0-20, default 5
        @SerializedName("include_answer")
        val includeAnswer: Boolean = true,      // Get AI summary
        val topic: String = "general"           // general, news, finance
    )

    /**
     * Tavily Search Response
     */
    data class TavilyResponse(
        val query: String?,
        val answer: String?,                    // AI-generated answer (if requested)
        val results: List<TavilyResult>?,
        @SerializedName("response_time")
        val responseTime: Float?
    )

    data class TavilyResult(
        val title: String?,
        val url: String?,
        val content: String?,                   // Snippet/summary
        val score: Float?                       // Relevance score
    )

    /**
     * Simplified result for agent consumption
     */
    data class SearchResult(
        val title: String,
        val snippet: String,
        val url: String,
        val score: Float
    )

    data class SearchResponse(
        val success: Boolean,
        val answer: String?,                    // AI summary if available
        val results: List<SearchResult>,
        val error: String? = null
    )

    /**
     * Execute web search via Tavily API
     *
     * @param apiKey Tavily API key (format: tvly-XXXXX)
     * @param query Search query
     * @param maxResults Maximum results to return (1-10 recommended for token efficiency)
     * @param topic Search topic: "general", "news", or "finance"
     * @return SearchResponse with results and optional AI answer
     */
    suspend fun search(
        apiKey: String,
        query: String,
        maxResults: Int = 5,
        topic: String = "general"
    ): SearchResponse = withContext(Dispatchers.IO) {
        try {
            // Check cache first - Sprint 4 optimization
            val key = cacheKey(query, topic, maxResults)
            val cached = searchCache.get(key)
            if (cached != null && !cached.isExpired()) {
                Log.d(TAG, "Cache hit for: $query")
                return@withContext cached.response
            }

            Log.d(TAG, "Searching: $query (max: $maxResults, topic: $topic)")

            val request = TavilyRequest(
                query = query,
                searchDepth = "basic",          // Use basic to conserve credits
                maxResults = maxResults.coerceIn(1, 10),
                includeAnswer = true,
                topic = topic
            )

            val jsonBody = gson.toJson(request)
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

            val httpRequest = Request.Builder()
                .url(SEARCH_ENDPOINT)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val httpResponse = httpClient.newCall(httpRequest).execute()
            val responseBody = httpResponse.body?.string()

            if (!httpResponse.isSuccessful) {
                Log.e(TAG, "Search failed: ${httpResponse.code} - $responseBody")
                return@withContext SearchResponse(
                    success = false,
                    answer = null,
                    results = emptyList(),
                    error = when (httpResponse.code) {
                        401 -> "Invalid Tavily API key. Please check your settings."
                        429 -> "Rate limit exceeded. Please try again later."
                        else -> "Search failed: ${httpResponse.code}"
                    }
                )
            }

            val tavilyResponse = gson.fromJson(responseBody, TavilyResponse::class.java)

            val results = tavilyResponse.results?.mapNotNull { result ->
                if (result.title != null && result.url != null && result.content != null) {
                    SearchResult(
                        title = result.title,
                        snippet = result.content.take(300),  // Limit snippet length
                        url = result.url,
                        score = result.score ?: 0f
                    )
                } else null
            } ?: emptyList()

            Log.d(TAG, "Search complete: ${results.size} results")

            val response = SearchResponse(
                success = true,
                answer = tavilyResponse.answer,
                results = results
            )

            // Cache successful result - Sprint 4 optimization
            searchCache.put(key, CachedResult(response, System.currentTimeMillis()))

            response

        } catch (e: Exception) {
            Log.e(TAG, "Search error", e)
            // BUG-042 fix: Don't expose internal error details to user
            val sanitizedError = when {
                e.message?.contains("Unable to resolve host", ignoreCase = true) == true -> "Network unavailable"
                e.message?.contains("timeout", ignoreCase = true) == true -> "Search timed out"
                e.message?.contains("401", ignoreCase = true) == true -> "Invalid API key"
                e.message?.contains("429", ignoreCase = true) == true -> "Rate limit exceeded"
                else -> "Search temporarily unavailable"
            }
            SearchResponse(
                success = false,
                answer = null,
                results = emptyList(),
                error = sanitizedError
            )
        }
    }

    /**
     * Test API key validity
     */
    suspend fun testConnection(apiKey: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = search(apiKey, "test", maxResults = 1)
            response.success
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            false
        }
    }

    /**
     * Format search results for display in agent response.
     * Creates a readable string with answer summary and source links.
     */
    fun formatResultsForResponse(response: SearchResponse): String {
        if (!response.success) {
            return response.error ?: "Search failed"
        }

        return buildString {
            // Include AI-generated answer if available
            if (!response.answer.isNullOrBlank()) {
                appendLine("**Summary:** ${response.answer}")
                appendLine()
            }

            // List sources with links
            if (response.results.isNotEmpty()) {
                appendLine("**Sources:**")
                response.results.forEach { result ->
                    appendLine("- [${result.title}](${result.url})")
                    appendLine("  ${result.snippet}")
                }
            } else {
                appendLine("No relevant results found.")
            }
        }
    }
}
