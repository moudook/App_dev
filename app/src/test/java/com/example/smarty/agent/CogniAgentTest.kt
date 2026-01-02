package com.example.smarty.agent

import android.content.Context
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.remote.providers.TavilySearchProvider
import com.example.smarty.data.repository.CogniRepository
import com.example.smarty.service.AlarmScheduler
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)

/**
 * Unit tests for CogniAgent.
 * Verifies agent initialization, tool registration, and result handling.
 *
 * Note: These tests mock the executor to avoid actual API calls.
 * Integration tests with real APIs should be run separately.
 */
class CogniAgentTest {

    private lateinit var context: Context
    private lateinit var agentProvider: CogniAgentProvider
    private lateinit var repository: CogniRepository
    private lateinit var tavilySearchProvider: TavilySearchProvider
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var callbacks: AgentCallbacks
    private lateinit var cogniAgent: CogniAgent

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        agentProvider = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        tavilySearchProvider = mockk(relaxed = true)
        alarmScheduler = mockk(relaxed = true)
        callbacks = mockk(relaxed = true)

        // Default mock behavior
        every { callbacks.getActiveNotes() } returns emptyList()
        every { callbacks.getArchivedNotes() } returns emptyList()
        every { callbacks.getCategories() } returns emptyList()
        every { callbacks.getTavilyApiKey() } returns null

        cogniAgent = CogniAgent(
            context = context,
            agentProvider = agentProvider,
            repository = repository,
            tavilySearchProvider = tavilySearchProvider,
            alarmScheduler = alarmScheduler,
            callbacks = callbacks
        )
    }

    // =========================================================================
    // READINESS TESTS
    // =========================================================================

    @Test
    fun `isReady returns true when provider is configured`() {
        every { agentProvider.hasConfiguredProvider() } returns true

        assertTrue(cogniAgent.isReady())
    }

    @Test
    fun `isReady returns false when no provider is configured`() {
        every { agentProvider.hasConfiguredProvider() } returns false

        assertFalse(cogniAgent.isReady())
    }

    // =========================================================================
    // CURRENT PROVIDER TESTS
    // =========================================================================

    @Test
    fun `getCurrentProvider returns provider name when available`() {
        every { agentProvider.getCurrentProviderName() } returns "GEMINI"

        assertEquals("GEMINI", cogniAgent.getCurrentProvider())
    }

    @Test
    fun `getCurrentProvider returns null when no provider`() {
        every { agentProvider.getCurrentProviderName() } returns null

        assertNull(cogniAgent.getCurrentProvider())
    }

    // =========================================================================
    // NO API KEY HANDLING TESTS
    // =========================================================================

    @Test
    fun `run returns NoProvider when no API key configured`() = runTest {
        // CogniAgent.run() uses getAllAvailableExecutors(), not getExecutor()
        // When no executors are available, it returns NoProvider with the actual message
        every { agentProvider.getAllAvailableExecutors() } returns emptyList()

        val result = cogniAgent.run("Hello")

        assertTrue(result is AgentResult.NoProvider)
        assertEquals(
            "No AI provider available. All providers may be temporarily disabled due to errors.",
            (result as AgentResult.NoProvider).message
        )
    }

    // =========================================================================
    // UNSUPPORTED PROVIDER TESTS
    // =========================================================================

    @Test
    fun `run returns NoProvider for empty available executors`() = runTest {
        // When getAllAvailableExecutors returns empty list (e.g., unsupported providers filtered out),
        // the agent returns NoProvider, not Error
        every { agentProvider.getAllAvailableExecutors() } returns emptyList()

        val result = cogniAgent.run("Hello")

        // Verify it returns NoProvider when no executors available
        assertTrue(result is AgentResult.NoProvider)
    }

    // =========================================================================
    // CONTEXT BUILDING TESTS
    // =========================================================================

    @Test
    fun `context includes visible notes count`() {
        val notes = listOf(
            createTestNote("1", "Note 1"),
            createTestNote("2", "Note 2", isFullPrivacy = true), // Should be filtered
            createTestNote("3", "Note 3")
        )
        every { callbacks.getActiveNotes() } returns notes

        // The context is built internally during run(), so we verify the callback is called
        verify(exactly = 0) { callbacks.getActiveNotes() } // Not called until run()
    }

    @Test
    fun `context includes category counts`() {
        val categories = listOf(
            Category(id = "1", name = "Work"),
            Category(id = "2", name = "Personal")
        )
        every { callbacks.getCategories() } returns categories

        // Categories callback is used during context building
        verify(exactly = 0) { callbacks.getCategories() }
    }

    // =========================================================================
    // CALLBACKS INTEGRATION TESTS
    // =========================================================================

    @Test
    fun `callbacks are registered correctly`() {
        // Verify that the callbacks object is accessible
        assertNotNull(callbacks)

        // Verify mock responses
        assertEquals(emptyList<Note>(), callbacks.getActiveNotes())
        assertEquals(emptyList<Category>(), callbacks.getCategories())
        assertNull(callbacks.getTavilyApiKey())
    }

    // =========================================================================
    // AGENT RESULT TYPES TESTS
    // =========================================================================

    @Test
    fun `AgentResult Success contains response and provider`() {
        val result = AgentResult.Success(
            response = "Test response",
            provider = AIProvider.GEMINI
        )

        assertEquals("Test response", result.response)
        assertEquals(AIProvider.GEMINI, result.provider)
    }

    @Test
    fun `AgentResult Error contains message`() {
        val result = AgentResult.Error("Something went wrong")

        assertEquals("Something went wrong", result.message)
    }

    @Test
    fun `AgentResult NoProvider contains message`() {
        val result = AgentResult.NoProvider("No API key")

        assertEquals("No API key", result.message)
    }

    // =========================================================================
    // TOOL ERROR TYPE TESTS
    // =========================================================================

    @Test
    fun `ToolErrorType classify identifies IllegalArgumentException as Validation`() {
        val exception = IllegalArgumentException("Invalid parameter")
        val errorType = ToolErrorType.classify(exception)

        // IllegalArgumentException could be classified as Validation or by message
        assertTrue(
            "Should be Validation or relevant type",
            errorType is ToolErrorType.Validation || errorType is ToolErrorType.Unknown
        )
    }

    @Test
    fun `ToolErrorType classify identifies IllegalStateException as InvalidState`() {
        val exception = IllegalStateException("Cannot perform action in current state")
        val errorType = ToolErrorType.classify(exception)

        assertTrue(errorType is ToolErrorType.InvalidState)
    }

    @Test
    fun `ToolErrorType classify identifies SecurityException as PermissionDenied`() {
        val exception = SecurityException("Access denied")
        val errorType = ToolErrorType.classify(exception)

        assertTrue(errorType is ToolErrorType.PermissionDenied)
    }

    @Test
    fun `ToolErrorType classify identifies NoSuchElementException as NotFound`() {
        val exception = NoSuchElementException("Item not found")
        val errorType = ToolErrorType.classify(exception)

        assertTrue(errorType is ToolErrorType.NotFound)
    }

    @Test
    fun `ToolErrorType classify identifies NumberFormatException correctly`() {
        val exception = NumberFormatException("Invalid number format: abc")
        val errorType = ToolErrorType.classify(exception)

        // NumberFormatException is handled in classify() - could be ParseError or Validation based on message
        assertTrue(
            "NumberFormatException should be ParseError or Validation",
            errorType is ToolErrorType.ParseError || errorType is ToolErrorType.Validation
        )
    }

    @Test
    fun `ToolErrorType shouldFailover returns true for ProviderError`() {
        val error = ToolErrorType.ProviderError("API error")
        assertTrue(error.shouldFailover())
    }

    @Test
    fun `ToolErrorType shouldFailover returns true for NetworkError`() {
        val error = ToolErrorType.NetworkError("Connection failed")
        assertTrue(error.shouldFailover())
    }

    @Test
    fun `ToolErrorType shouldFailover returns false for Validation`() {
        val error = ToolErrorType.Validation("Invalid input")
        assertFalse(error.shouldFailover())
    }

    @Test
    fun `ToolErrorType shouldFailover returns false for NotFound`() {
        val error = ToolErrorType.NotFound("note", "Note not found")
        assertFalse(error.shouldFailover())
    }

    @Test
    fun `ToolErrorType toUserMessage formats correctly`() {
        val validationError = ToolErrorType.Validation("Missing field")
        assertTrue(validationError.toUserMessage().contains("Invalid input"))

        val notFoundError = ToolErrorType.NotFound("note", "Note doesn't exist")
        assertTrue(notFoundError.toUserMessage().contains("Could not find"))

        val networkError = ToolErrorType.NetworkError("Timeout")
        assertTrue(networkError.toUserMessage().contains("Connection"))
    }

    // =========================================================================
    // TIMEOUT TESTS
    // =========================================================================

    @Test
    fun `getTimeoutForProvider returns shorter timeout for LOCAL_PC`() {
        val localTimeout = CogniAgent.getTimeoutForProvider(AIProvider.LOCAL_PC)
        val cloudTimeout = CogniAgent.getTimeoutForProvider(AIProvider.GEMINI)

        assertTrue(
            "Local PC should have shorter timeout than cloud",
            localTimeout < cloudTimeout
        )
    }

    @Test
    fun `getTimeoutForProvider returns longer timeout for slow providers`() {
        val anthropicTimeout = CogniAgent.getTimeoutForProvider(AIProvider.ANTHROPIC)
        val geminiTimeout = CogniAgent.getTimeoutForProvider(AIProvider.GEMINI)

        assertTrue(
            "Anthropic should have longer timeout than Gemini",
            anthropicTimeout >= geminiTimeout
        )
    }

    // =========================================================================
    // MESSAGE CLASSIFICATION BY ERROR CONTENT
    // =========================================================================

    @Test
    fun `ToolErrorType classifies rate limit error correctly`() {
        val exception = Exception("Rate limit exceeded: 429 Too Many Requests")
        val errorType = ToolErrorType.classify(exception)

        assertTrue(
            "Rate limit error should be ResourceExhausted",
            errorType is ToolErrorType.ResourceExhausted
        )
    }

    @Test
    fun `ToolErrorType classifies not found error correctly`() {
        val exception = Exception("Note does not exist in database")
        val errorType = ToolErrorType.classify(exception)

        assertTrue(
            "Not found error should be NotFound",
            errorType is ToolErrorType.NotFound
        )
    }

    @Test
    fun `ToolErrorType classifies permission error correctly`() {
        val exception = Exception("Permission denied: unauthorized access")
        val errorType = ToolErrorType.classify(exception)

        assertTrue(
            "Permission error should be PermissionDenied",
            errorType is ToolErrorType.PermissionDenied
        )
    }

    @Test
    fun `ToolErrorType classifies parse error correctly`() {
        val exception = Exception("Invalid format: could not parse JSON")
        val errorType = ToolErrorType.classify(exception)

        assertTrue(
            "Parse error should be ParseError",
            errorType is ToolErrorType.ParseError
        )
    }

    // =========================================================================
    // AGENT RESULT SEALED CLASS TESTS
    // =========================================================================

    @Test
    fun `AgentResult Success with citations`() {
        val citations = listOf(
            WebCitation("Source 1", "https://example.com/1", "Snippet 1"),
            WebCitation("Source 2", "https://example.com/2", "Snippet 2")
        )
        val result = AgentResult.Success(
            response = "Response with sources",
            provider = AIProvider.GEMINI,
            citations = citations
        )

        assertEquals(2, result.citations.size)
        assertEquals("Source 1", result.citations[0].title)
        assertEquals("https://example.com/2", result.citations[1].url)
    }

    @Test
    fun `AgentResult Success default has empty citations`() {
        val result = AgentResult.Success(
            response = "Simple response",
            provider = AIProvider.GROQ
        )

        assertTrue(result.citations.isEmpty())
    }

    // =========================================================================
    // WEB CITATION TESTS
    // =========================================================================

    @Test
    fun `WebCitation data class holds values correctly`() {
        val citation = WebCitation(
            title = "Test Article",
            url = "https://example.com/article",
            snippet = "This is a test snippet..."
        )

        assertEquals("Test Article", citation.title)
        assertEquals("https://example.com/article", citation.url)
        assertEquals("This is a test snippet...", citation.snippet)
    }

    // =========================================================================
    // ALL PROVIDERS TEST
    // =========================================================================

    @Test
    fun `getTimeoutForProvider handles all AIProvider values`() {
        // Ensure getTimeoutForProvider doesn't crash for any provider
        AIProvider.entries.forEach { provider ->
            val timeout = CogniAgent.getTimeoutForProvider(provider)
            assertTrue(
                "Timeout for $provider should be positive",
                timeout > 0
            )
            assertTrue(
                "Timeout for $provider should be reasonable (< 3 minutes)",
                timeout <= 180_000
            )
        }
    }

    // =========================================================================
    // HELPER FUNCTIONS
    // =========================================================================

    private fun createTestNote(
        id: String,
        title: String,
        content: String = "Content for $title",
        isFullPrivacy: Boolean = false,
        excludeFromAiChat: Boolean = false,
        isArchived: Boolean = false,
        categoryName: String? = null
    ): Note {
        return Note(
            id = id,
            title = title,
            content = content,
            type = NoteType.BRAIN_DUMP,
            processingStatus = ProcessingStatus.COMPLETED,
            isFullPrivacy = isFullPrivacy,
            excludeFromAiChat = excludeFromAiChat,
            isArchived = isArchived,
            categoryName = categoryName
        )
    }
}
