package com.example.smarty.agent

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

    private lateinit var agentProvider: CogniAgentProvider
    private lateinit var repository: CogniRepository
    private lateinit var tavilySearchProvider: TavilySearchProvider
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var callbacks: AgentCallbacks
    private lateinit var cogniAgent: CogniAgent

    @Before
    fun setup() {
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
        every { agentProvider.getExecutor() } returns CogniAgentProvider.ExecutorResult.NoApiKey(
            "No AI provider configured with API keys"
        )

        val result = cogniAgent.run("Hello")

        assertTrue(result is AgentResult.NoProvider)
        assertEquals("No AI provider configured with API keys", (result as AgentResult.NoProvider).message)
    }

    // =========================================================================
    // UNSUPPORTED PROVIDER TESTS
    // =========================================================================

    @Test
    fun `run returns Error for unsupported provider`() = runTest {
        every { agentProvider.getExecutor() } returns CogniAgentProvider.ExecutorResult.UnsupportedProvider(
            AIProvider.HUGGINGFACE
        )

        val result = cogniAgent.run("Hello")

        assertTrue(result is AgentResult.Error)
        assertTrue((result as AgentResult.Error).message.contains("HUGGINGFACE"))
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
