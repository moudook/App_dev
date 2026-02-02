package com.example.smarty.server.llm

import io.ktor.client.*
import org.slf4j.LoggerFactory

/**
 * Factory for creating LlmProvider instances based on configuration.
 * Supports dynamic switching via ACTIVE_PROVIDER environment variable.
 */
object LlmProviderFactory {
    private val logger = LoggerFactory.getLogger(LlmProviderFactory::class.java)

    fun create(client: HttpClient): LlmProvider {
        val activeProvider = System.getenv("ACTIVE_PROVIDER")?.uppercase() ?: "OPENAI"
        logger.info("Initializing LLM Provider: $activeProvider")

        return when (activeProvider) {
            "OPENAI" -> createOpenAi(client)
            "GROQ" -> createGroq(client)
            "DEEPSEEK" -> createDeepSeek(client)
            "GEMINI" -> createGemini(client)
            "CLAUDE" -> createClaude(client)
            "OPENROUTER" -> createOpenRouter(client)
            "CEREBRAS" -> createCerebras(client)
            "GITHUB" -> createGitHub(client)
            "MOCK" -> createMock(client)
            else -> {
                logger.warn("Unknown provider: $activeProvider. Fallback to OpenAI.")
                createOpenAi(client)
            }
        }
    }

    private fun createOpenAi(client: HttpClient) = OpenAiCompatibleProvider(
        client = client,
        providerName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        apiKey = System.getenv("OPENAI_API_KEY") ?: "",
        defaultModel = "gpt-4-turbo-preview"
    )

    private fun createGroq(client: HttpClient) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        apiKey = System.getenv("GROQ_API_KEY") ?: "",
        defaultModel = "llama3-70b-8192"
    )

    private fun createDeepSeek(client: HttpClient) = OpenAiCompatibleProvider(
        client = client,
        providerName = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        apiKey = System.getenv("DEEPSEEK_API_KEY") ?: "",
        defaultModel = "deepseek-chat"
    )

    private fun createGemini(client: HttpClient) = GeminiProvider(
        client = client,
        apiKey = System.getenv("GEMINI_API_KEY") ?: ""
    )

    private fun createClaude(client: HttpClient) = AnthropicProvider(
        client = client,
        apiKey = System.getenv("ANTHROPIC_API_KEY") ?: ""
    )

    private fun createOpenRouter(client: HttpClient) = OpenAiCompatibleProvider(
        client = client,
        providerName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        apiKey = System.getenv("OPENROUTER_API_KEY") ?: "",
        defaultModel = "openai/gpt-4o" // OpenRouter requires 'provider/model' format
    )

    private fun createCerebras(client: HttpClient) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Cerebras",
        baseUrl = "https://api.cerebras.ai/v1",
        apiKey = System.getenv("CEREBRAS_API_KEY") ?: "",
        defaultModel = "llama3.1-70b"
    )

    private fun createGitHub(client: HttpClient) = OpenAiCompatibleProvider(
        client = client,
        providerName = "GitHub Models",
        baseUrl = "https://models.inference.ai.azure.com",
        apiKey = System.getenv("GITHUB_TOKEN") ?: "",
        defaultModel = "gpt-4o"
    )

    private fun createMock(client: HttpClient) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Mock",
        baseUrl = "http://localhost:7860/mock", // Points to local mock if needed
        apiKey = "mock-key",
        defaultModel = "mock-model"
    )
}
