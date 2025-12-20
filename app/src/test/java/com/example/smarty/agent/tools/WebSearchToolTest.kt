package com.example.smarty.agent.tools

import com.example.smarty.agent.tools.external.WebSearchArgs
import com.example.smarty.agent.tools.external.WebSearchTool
import com.example.smarty.data.remote.providers.TavilySearchProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)

/**
 * Unit tests for WebSearchTool.
 * Verifies API key validation, search execution, and error handling.
 */
class WebSearchToolTest {

    private lateinit var tavilyProvider: TavilySearchProvider
    private lateinit var webSearchTool: WebSearchTool
    private var apiKey: String? = null

    @Before
    fun setup() {
        tavilyProvider = mockk(relaxed = true)
        webSearchTool = WebSearchTool(
            tavilySearchProvider = tavilyProvider,
            getApiKey = { apiKey }
        )
    }

    // =========================================================================
    // ARGS SERIALIZATION TESTS
    // =========================================================================

    @Test
    fun `Args can be deserialized from JSON`() {
        val json = """{"query":"kotlin coroutines","reason":"Need to learn about coroutines","topic":"code","maxResults":3}"""
        val args = Json.decodeFromString(WebSearchArgs.serializer(), json)

        assertEquals("kotlin coroutines", args.query)
        assertEquals("Need to learn about coroutines", args.reason)
        assertEquals("code", args.topic)
        assertEquals(3, args.maxResults)
    }

    @Test
    fun `Args with defaults deserializes correctly`() {
        val json = """{"query":"test search","reason":"testing"}"""
        val args = Json.decodeFromString(WebSearchArgs.serializer(), json)

        assertEquals("test search", args.query)
        assertEquals("testing", args.reason)
        assertEquals("general", args.topic) // default
        assertEquals(5, args.maxResults) // default
    }

    @Test
    fun `Args serializes correctly`() {
        val args = WebSearchArgs(
            query = "AI news",
            reason = "Stay updated",
            topic = "news",
            maxResults = 10
        )

        val jsonString = Json.encodeToString(WebSearchArgs.serializer(), args)

        assertTrue(jsonString.contains("\"query\":\"AI news\""))
        assertTrue(jsonString.contains("\"reason\":\"Stay updated\""))
        assertTrue(jsonString.contains("\"topic\":\"news\""))
    }

    // =========================================================================
    // API KEY VALIDATION TESTS
    // =========================================================================

    @Test
    fun `missing API key returns failure`() = runTest {
        apiKey = null

        val args = WebSearchArgs(query = "test", reason = "testing")
        val result = webSearchTool.execute(args)

        assertFalse("Should fail without API key", result.success)
        assertEquals("Web search not configured", result.error)
        coVerify(exactly = 0) { tavilyProvider.search(any(), any(), any(), any()) }
    }

    @Test
    fun `empty API key returns failure`() = runTest {
        apiKey = ""

        val args = WebSearchArgs(query = "test", reason = "testing")
        val result = webSearchTool.execute(args)

        assertFalse("Should fail with empty API key", result.success)
        assertEquals("Web search not configured", result.error)
    }

    @Test
    fun `blank API key returns failure`() = runTest {
        apiKey = "   "

        val args = WebSearchArgs(query = "test", reason = "testing")
        val result = webSearchTool.execute(args)

        assertFalse("Should fail with blank API key", result.success)
    }

    // =========================================================================
    // SUCCESSFUL SEARCH TESTS
    // =========================================================================

    @Test
    fun `valid search returns results`() = runTest {
        apiKey = "valid-api-key"

        val mockSearchResponse = TavilySearchProvider.SearchResponse(
            success = true,
            answer = "AI summary of search results",
            results = listOf(
                TavilySearchProvider.SearchResult(
                    title = "Result 1",
                    url = "https://example.com/1",
                    snippet = "First search result snippet",
                    score = 0.9f
                ),
                TavilySearchProvider.SearchResult(
                    title = "Result 2",
                    url = "https://example.com/2",
                    snippet = "Second search result snippet",
                    score = 0.8f
                )
            )
        )

        coEvery {
            tavilyProvider.search(
                apiKey = "valid-api-key",
                query = "kotlin tutorial",
                maxResults = 5,
                topic = "general"
            )
        } returns mockSearchResponse

        val args = WebSearchArgs(query = "kotlin tutorial", reason = "Learning Kotlin")
        val result = webSearchTool.execute(args)

        assertTrue("Search should succeed", result.success)
        assertEquals("kotlin tutorial", result.query)
        assertEquals("Learning Kotlin", result.reason)
        assertEquals("AI summary of search results", result.aiSummary)
        assertEquals(2, result.results.size)
        assertEquals(2, result.totalResults)
    }

    @Test
    fun `search passes parameters correctly`() = runTest {
        apiKey = "test-key"

        coEvery {
            tavilyProvider.search(any(), any(), any(), any())
        } returns TavilySearchProvider.SearchResponse(
            success = true,
            answer = null,
            results = emptyList()
        )

        val args = WebSearchArgs(
            query = "custom query",
            reason = "custom reason",
            topic = "code",
            maxResults = 3
        )
        webSearchTool.execute(args)

        coVerify {
            tavilyProvider.search(
                apiKey = "test-key",
                query = "custom query",
                maxResults = 3,
                topic = "code"
            )
        }
    }

    // =========================================================================
    // SEARCH FAILURE TESTS
    // =========================================================================

    @Test
    fun `search provider failure returns failure result`() = runTest {
        apiKey = "valid-key"

        coEvery {
            tavilyProvider.search(any(), any(), any(), any())
        } returns TavilySearchProvider.SearchResponse(
            success = false,
            answer = null,
            results = emptyList(),
            error = "Rate limit exceeded"
        )

        val args = WebSearchArgs(query = "test", reason = "testing")
        val result = webSearchTool.execute(args)

        assertFalse("Should fail when provider fails", result.success)
        assertEquals("Rate limit exceeded", result.error)
    }

    @Test
    fun `search provider exception returns failure result`() = runTest {
        apiKey = "valid-key"

        coEvery {
            tavilyProvider.search(any(), any(), any(), any())
        } throws RuntimeException("Network error")

        val args = WebSearchArgs(query = "test", reason = "testing")
        val result = webSearchTool.execute(args)

        assertFalse("Should fail on exception", result.success)
        assertTrue("Error should contain exception message", result.error?.contains("Network error") == true)
    }

    // =========================================================================
    // TOOL METADATA TESTS
    // =========================================================================

    @Test
    fun `tool name is correct`() {
        assertEquals("web_search", webSearchTool.name)
    }

    @Test
    fun `tool has description`() {
        assertTrue(webSearchTool.description.isNotBlank())
        assertTrue(webSearchTool.description.contains("Searches the internet"))
    }

    // =========================================================================
    // RESULT TOSTRING TESTS
    // =========================================================================

    @Test
    fun `success result toString is LLM-friendly`() = runTest {
        apiKey = "valid-key"

        coEvery {
            tavilyProvider.search(any(), any(), any(), any())
        } returns TavilySearchProvider.SearchResponse(
            success = true,
            answer = "Summary",
            results = listOf(
                TavilySearchProvider.SearchResult(
                    title = "Test Result",
                    url = "https://test.com",
                    snippet = "Test snippet",
                    score = 0.9f
                )
            )
        )

        val args = WebSearchArgs(query = "test query", reason = "testing")
        val result = webSearchTool.execute(args)

        val stringOutput = result.toString()
        assertTrue("Should contain query", stringOutput.contains("test query"))
        assertTrue("Should contain results", stringOutput.contains("Results"))
    }

    @Test
    fun `failure result toString is LLM-friendly`() = runTest {
        apiKey = null

        val args = WebSearchArgs(query = "test", reason = "testing")
        val result = webSearchTool.execute(args)

        val stringOutput = result.toString()
        assertTrue("Should indicate failure", stringOutput.contains("failed"))
    }

    // =========================================================================
    // WEB RESULT STRUCTURE TESTS
    // =========================================================================

    @Test
    fun `WebResult contains expected fields`() = runTest {
        apiKey = "valid-key"

        coEvery {
            tavilyProvider.search(any(), any(), any(), any())
        } returns TavilySearchProvider.SearchResponse(
            success = true,
            answer = null,
            results = listOf(
                TavilySearchProvider.SearchResult(
                    title = "Test Title",
                    url = "https://example.com/page",
                    snippet = "This is the snippet text",
                    score = 0.95f
                )
            )
        )

        val args = WebSearchArgs(query = "test", reason = "testing")
        val result = webSearchTool.execute(args)

        assertEquals(1, result.results.size)
        val webResult = result.results[0]
        assertEquals("Test Title", webResult.title)
        assertEquals("https://example.com/page", webResult.url)
        assertEquals("This is the snippet text", webResult.snippet)
    }
}
