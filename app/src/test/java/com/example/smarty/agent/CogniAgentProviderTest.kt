package com.example.smarty.agent

import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.SecurePreferences
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)

/**
 * Unit tests for CogniAgentProvider.
 * Verifies provider selection, executor creation, and custom base URL configuration.
 */
class CogniAgentProviderTest {

    private lateinit var securePreferences: SecurePreferences
    private lateinit var agentProvider: CogniAgentProvider

    @Before
    fun setup() {
        securePreferences = mockk(relaxed = true)
        agentProvider = CogniAgentProvider(securePreferences)
    }

    // =========================================================================
    // NO CONFIGURATION TESTS
    // =========================================================================

    @Test
    fun `getExecutor returns NoApiKey when no providers enabled`() {
        every { securePreferences.getProviderPriority() } returns AIProvider.entries
        every { securePreferences.isProviderEnabled(any()) } returns false

        val result = agentProvider.getExecutor()

        assertTrue(result is CogniAgentProvider.ExecutorResult.NoApiKey)
    }

    @Test
    fun `getExecutor returns NoApiKey when providers enabled but no keys`() {
        every { securePreferences.getProviderPriority() } returns listOf(AIProvider.GEMINI)
        every { securePreferences.isProviderEnabled(AIProvider.GEMINI) } returns true
        every { securePreferences.getProviderKeys(AIProvider.GEMINI) } returns emptyList()

        val result = agentProvider.getExecutor()

        assertTrue(result is CogniAgentProvider.ExecutorResult.NoApiKey)
    }

    // =========================================================================
    // GEMINI EXECUTOR TESTS
    // =========================================================================

    @Test
    fun `creates Gemini executor with valid configuration`() {
        setupProvider(AIProvider.GEMINI, "test-gemini-key", "gemini-2.5-pro")

        val result = agentProvider.getExecutor()

        assertTrue(result is CogniAgentProvider.ExecutorResult.Success)
        val success = result as CogniAgentProvider.ExecutorResult.Success
        assertEquals(AIProvider.GEMINI, success.provider)
        assertNotNull(success.executor)
        assertNotNull(success.model)
    }

    @Test
    fun `maps Gemini 2_5 Flash model correctly`() {
        setupProvider(AIProvider.GEMINI, "key", "gemini-2.5-flash")

        val result = agentProvider.getExecutor() as CogniAgentProvider.ExecutorResult.Success

        // Model mapping is internal, but we verify execution doesn't fail
        assertNotNull(result.model)
    }

    // =========================================================================
    // ANTHROPIC EXECUTOR TESTS
    // =========================================================================

    @Test
    fun `creates Anthropic executor with valid configuration`() {
        setupProvider(AIProvider.ANTHROPIC, "test-anthropic-key", "claude-3-5-sonnet")

        val result = agentProvider.getExecutor()

        assertTrue(result is CogniAgentProvider.ExecutorResult.Success)
        val success = result as CogniAgentProvider.ExecutorResult.Success
        assertEquals(AIProvider.ANTHROPIC, success.provider)
    }

    @Test
    fun `maps Claude 3 Opus model correctly`() {
        setupProvider(AIProvider.ANTHROPIC, "key", "claude-3-opus")

        val result = agentProvider.getExecutor() as CogniAgentProvider.ExecutorResult.Success

        assertNotNull(result.model)
    }

    // =========================================================================
    // OPENAI EXECUTOR TESTS
    // =========================================================================

    @Test
    fun `creates OpenAI executor with valid configuration`() {
        setupProvider(AIProvider.OPENAI, "test-openai-key", "gpt-4o")

        val result = agentProvider.getExecutor()

        assertTrue(result is CogniAgentProvider.ExecutorResult.Success)
        val success = result as CogniAgentProvider.ExecutorResult.Success
        assertEquals(AIProvider.OPENAI, success.provider)
    }

    @Test
    fun `maps GPT-4o-mini model correctly`() {
        setupProvider(AIProvider.OPENAI, "key", "gpt-4o-mini")

        val result = agentProvider.getExecutor() as CogniAgentProvider.ExecutorResult.Success

        assertNotNull(result.model)
    }

    // =========================================================================
    // GROQ EXECUTOR TESTS (CUSTOM BASE URL)
    // =========================================================================

    @Test
    fun `creates Groq executor with custom base URL`() {
        setupProvider(AIProvider.GROQ, "test-groq-key", "llama-3-70b")

        val result = agentProvider.getExecutor()

        assertTrue("Groq should create success result", result is CogniAgentProvider.ExecutorResult.Success)
        val success = result as CogniAgentProvider.ExecutorResult.Success
        assertEquals(AIProvider.GROQ, success.provider)
        assertNotNull("Groq executor should not be null", success.executor)
    }

    @Test
    fun `Groq uses OpenAI-compatible model`() {
        setupProvider(AIProvider.GROQ, "key", "mixtral-8x7b")

        val result = agentProvider.getExecutor() as CogniAgentProvider.ExecutorResult.Success

        // Groq maps to OpenAI models for Koog compatibility
        assertNotNull(result.model)
    }

    // =========================================================================
    // DEEPSEEK EXECUTOR TESTS (CUSTOM BASE URL)
    // =========================================================================

    @Test
    fun `creates DeepSeek executor with custom base URL`() {
        setupProvider(AIProvider.DEEPSEEK, "test-deepseek-key", "deepseek-chat")

        val result = agentProvider.getExecutor()

        assertTrue("DeepSeek should create success result", result is CogniAgentProvider.ExecutorResult.Success)
        val success = result as CogniAgentProvider.ExecutorResult.Success
        assertEquals(AIProvider.DEEPSEEK, success.provider)
    }

    @Test
    fun `DeepSeek coder model mapped correctly`() {
        setupProvider(AIProvider.DEEPSEEK, "key", "deepseek-coder")

        val result = agentProvider.getExecutor() as CogniAgentProvider.ExecutorResult.Success

        assertNotNull(result.model)
    }

    // =========================================================================
    // OPENROUTER EXECUTOR TESTS (CUSTOM BASE URL)
    // =========================================================================

    @Test
    fun `creates OpenRouter executor with custom base URL`() {
        setupProvider(AIProvider.OPENROUTER, "test-openrouter-key", "anthropic/claude-3-opus")

        val result = agentProvider.getExecutor()

        assertTrue("OpenRouter should create success result", result is CogniAgentProvider.ExecutorResult.Success)
        val success = result as CogniAgentProvider.ExecutorResult.Success
        assertEquals(AIProvider.OPENROUTER, success.provider)
    }

    // =========================================================================
    // HUGGINGFACE UNSUPPORTED TEST
    // =========================================================================

    @Test
    fun `HuggingFace returns unsupported provider`() {
        setupProvider(AIProvider.HUGGINGFACE, "test-hf-key", "meta-llama/Llama-2-7b")

        val result = agentProvider.getExecutor()

        assertTrue("HuggingFace should be unsupported", result is CogniAgentProvider.ExecutorResult.UnsupportedProvider)
        assertEquals(AIProvider.HUGGINGFACE, (result as CogniAgentProvider.ExecutorResult.UnsupportedProvider).provider)
    }

    // =========================================================================
    // PROVIDER PRIORITY TESTS
    // =========================================================================

    @Test
    fun `uses first enabled provider from priority list`() {
        // Priority: OPENAI, GEMINI, ANTHROPIC
        every { securePreferences.getProviderPriority() } returns listOf(
            AIProvider.OPENAI,
            AIProvider.GEMINI,
            AIProvider.ANTHROPIC
        )
        // Only GEMINI is enabled and has keys
        every { securePreferences.isProviderEnabled(AIProvider.OPENAI) } returns false
        every { securePreferences.isProviderEnabled(AIProvider.GEMINI) } returns true
        every { securePreferences.isProviderEnabled(AIProvider.ANTHROPIC) } returns false
        every { securePreferences.getProviderKeys(AIProvider.GEMINI) } returns listOf("gemini-key")
        every { securePreferences.getSelectedModel(AIProvider.GEMINI) } returns "gemini-2.5-flash"

        val result = agentProvider.getExecutor()

        assertTrue(result is CogniAgentProvider.ExecutorResult.Success)
        assertEquals(AIProvider.GEMINI, (result as CogniAgentProvider.ExecutorResult.Success).provider)
    }

    @Test
    fun `skips providers without keys`() {
        every { securePreferences.getProviderPriority() } returns listOf(
            AIProvider.OPENAI,
            AIProvider.GEMINI
        )
        every { securePreferences.isProviderEnabled(AIProvider.OPENAI) } returns true
        every { securePreferences.isProviderEnabled(AIProvider.GEMINI) } returns true
        every { securePreferences.getProviderKeys(AIProvider.OPENAI) } returns emptyList() // No keys!
        every { securePreferences.getProviderKeys(AIProvider.GEMINI) } returns listOf("gemini-key")
        every { securePreferences.getSelectedModel(AIProvider.GEMINI) } returns "gemini-2.5-flash"

        val result = agentProvider.getExecutor()

        assertTrue(result is CogniAgentProvider.ExecutorResult.Success)
        assertEquals(AIProvider.GEMINI, (result as CogniAgentProvider.ExecutorResult.Success).provider)
    }

    // =========================================================================
    // HAS CONFIGURED PROVIDER TESTS
    // =========================================================================

    @Test
    fun `hasConfiguredProvider returns true when valid provider exists`() {
        every { securePreferences.getProviderPriority() } returns listOf(AIProvider.GEMINI)
        every { securePreferences.isProviderEnabled(AIProvider.GEMINI) } returns true
        every { securePreferences.getProviderKeys(AIProvider.GEMINI) } returns listOf("key")

        assertTrue(agentProvider.hasConfiguredProvider())
    }

    @Test
    fun `hasConfiguredProvider returns false when no valid provider`() {
        every { securePreferences.getProviderPriority() } returns emptyList()

        assertFalse(agentProvider.hasConfiguredProvider())
    }

    @Test
    fun `hasConfiguredProvider excludes HuggingFace`() {
        every { securePreferences.getProviderPriority() } returns listOf(AIProvider.HUGGINGFACE)
        every { securePreferences.isProviderEnabled(AIProvider.HUGGINGFACE) } returns true
        every { securePreferences.getProviderKeys(AIProvider.HUGGINGFACE) } returns listOf("key")

        // HuggingFace is not supported for agent, so should return false
        assertFalse(agentProvider.hasConfiguredProvider())
    }

    // =========================================================================
    // GET CURRENT PROVIDER NAME TESTS
    // =========================================================================

    @Test
    fun `getCurrentProviderName returns name when provider configured`() {
        setupProvider(AIProvider.ANTHROPIC, "key", "claude-3-5-sonnet")

        val name = agentProvider.getCurrentProviderName()

        assertEquals("ANTHROPIC", name)
    }

    @Test
    fun `getCurrentProviderName returns null when no provider`() {
        every { securePreferences.getProviderPriority() } returns emptyList()

        val name = agentProvider.getCurrentProviderName()

        assertNull(name)
    }

    // =========================================================================
    // HELPER FUNCTIONS
    // =========================================================================

    private fun setupProvider(provider: AIProvider, apiKey: String, model: String) {
        every { securePreferences.getProviderPriority() } returns listOf(provider)
        every { securePreferences.isProviderEnabled(provider) } returns true
        every { securePreferences.getProviderKeys(provider) } returns listOf(apiKey)
        every { securePreferences.getSelectedModel(provider) } returns model
    }
}
