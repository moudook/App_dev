package com.example.smarty.server.llm

import io.ktor.client.*
import org.slf4j.LoggerFactory

/**
 * Factory for creating LlmProvider instances based on configuration.
 * Supports dynamic switching via ACTIVE_PROVIDER environment variable.
 */
object LlmProviderFactory {
    private val logger = LoggerFactory.getLogger(LlmProviderFactory::class.java)

    fun create(client: HttpClient, providerOverride: String? = null, baseUrlOverride: String? = null, apiKeyOverride: String? = null): LlmProvider {
        val activeProvider = providerOverride?.uppercase()
            ?: System.getenv("ACTIVE_PROVIDER")?.uppercase()
            ?: "GEMINI"
        logger.info("Initializing LLM Provider: $activeProvider (URL: $baseUrlOverride)")

        return when (activeProvider) {
            "OPENAI" -> createOpenAi(client, apiKeyOverride)
            "GROQ" -> createGroq(client, apiKeyOverride)
            "DEEPSEEK" -> createDeepSeek(client, apiKeyOverride)
            "GEMINI" -> createGemini(client, apiKeyOverride)
            "CLAUDE", "ANTIGRAVITY" -> createClaude(client, baseUrlOverride, apiKeyOverride)
            "OPENROUTER" -> createOpenRouter(client, apiKeyOverride)
            "Cerebras" -> createCerebras(client, apiKeyOverride)
            "GITHUB" -> createGitHub(client, apiKeyOverride)
            "LOCAL" -> createLocal(client, baseUrlOverride, apiKeyOverride)
            "LOCAL_PC" -> createLocal(client, baseUrlOverride, apiKeyOverride)
            "MOCK" -> createMock(client)
            else -> {
                logger.warn("Unknown provider: $activeProvider. Fallback to OpenAI.")
                createOpenAi(client, apiKeyOverride)
            }
        }
    }

    private fun createOpenAi(client: HttpClient, apiKeyOverride: String? = null) = OpenAiCompatibleProvider(
        client = client,
        providerName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        apiKey = apiKeyOverride ?: System.getenv("OPENAI_API_KEY") ?: "",
        defaultModel = "gpt-4-turbo-preview"
    )

    private fun createGroq(client: HttpClient, apiKeyOverride: String? = null) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        apiKey = apiKeyOverride ?: System.getenv("GROQ_API_KEY") ?: "",
        defaultModel = "llama3-70b-8192"
    )

    private fun createDeepSeek(client: HttpClient, apiKeyOverride: String? = null) = OpenAiCompatibleProvider(
        client = client,
        providerName = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        apiKey = apiKeyOverride ?: System.getenv("DEEPSEEK_API_KEY") ?: "",
        defaultModel = "deepseek-chat"
    )

    private fun createGemini(client: HttpClient, apiKeyOverride: String? = null) = GeminiProvider(
        client = client,
        apiKey = apiKeyOverride ?: System.getenv("GEMINI_API_KEY") ?: ""
    )

    private fun createClaude(client: HttpClient, baseUrlOverride: String? = null, apiKeyOverride: String? = null) = AnthropicProvider(
        client = client,
        apiKey = apiKeyOverride ?: System.getenv("ANTHROPIC_API_KEY") ?: "",
        baseUrl = baseUrlOverride ?: System.getenv("ANTHROPIC_BASE_URL") ?: "https://api.anthropic.com/v1"
    )

    private fun createOpenRouter(client: HttpClient, apiKeyOverride: String? = null) = OpenAiCompatibleProvider(
        client = client,
        providerName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        apiKey = apiKeyOverride ?: System.getenv("OPENROUTER_API_KEY") ?: "",
        defaultModel = "openai/gpt-4o" // OpenRouter requires 'provider/model' format
    )

    private fun createCerebras(client: HttpClient, apiKeyOverride: String? = null) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Cerebras",
        baseUrl = "https://api.cerebras.ai/v1",
        apiKey = apiKeyOverride ?: System.getenv("CEREBRAS_API_KEY") ?: "",
        defaultModel = "llama3.1-70b"
    )

    private fun createGitHub(client: HttpClient, apiKeyOverride: String? = null) = OpenAiCompatibleProvider(
        client = client,
        providerName = "GitHub Models",
        baseUrl = "https://models.inference.ai.azure.com",
        apiKey = apiKeyOverride ?: System.getenv("GITHUB_TOKEN") ?: "",
        defaultModel = "gpt-4o"
    )

    private fun createLocal(client: HttpClient, baseUrlOverride: String? = null, apiKeyOverride: String? = null) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Local LLM",
        baseUrl = baseUrlOverride ?: System.getenv("LOCAL_LLM_URL") ?: "http://localhost:8000/v1",
        apiKey = apiKeyOverride ?: System.getenv("LOCAL_LLM_KEY") ?: "not-needed",
        defaultModel = System.getenv("LOCAL_LLM_MODEL") ?: "chatglm3-6b"
    )

    private fun createMock(client: HttpClient) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Mock",
        baseUrl = "http://localhost:7860/mock", // Points to local mock if needed
        apiKey = "mock-key",
        defaultModel = "mock-model"
    )
}
