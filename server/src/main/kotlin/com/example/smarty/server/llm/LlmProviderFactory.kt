package com.example.smarty.server.llm

import io.ktor.client.*
import org.slf4j.LoggerFactory

/**
 * Factory for creating LlmProvider instances based on configuration.
 * Supports dynamic switching via ACTIVE_PROVIDER environment variable.
 */
object LlmProviderFactory {
    private val logger = LoggerFactory.getLogger(LlmProviderFactory::class.java)

    fun create(client: HttpClient, providerOverride: String? = null, baseUrlOverride: String? = null, apiKeyOverride: String? = null, modelIdOverride: String? = null): LlmProvider {
        val activeProvider = providerOverride?.uppercase()
            ?: System.getenv("ACTIVE_PROVIDER")?.uppercase()
            ?: "GEMINI"
        
        // Universal overrides from environment
        val envBaseUrl = System.getenv("LLM_BASE_URL")?.takeIf { it.isNotBlank() }
        val envModelId = System.getenv("LLM_MODEL_ID")?.takeIf { it.isNotBlank() }
        
        // Effective values (Function args > Env Vars > Default)
        val finalBaseUrl = baseUrlOverride ?: envBaseUrl
        val finalModelId = modelIdOverride ?: envModelId
        logger.info("Initializing LLM Provider: $activeProvider")

        return when (activeProvider) {
            "GEMINI" -> {
                val key = apiKeyOverride ?: System.getenv("GEMINI_API_KEY")
                require(!key.isNullOrBlank()) { "ERROR: GEMINI_API_KEY is missing in your .env file. Please add it to use the GEMINI provider." }
                createGemini(client, key)
            }
            "OPENAI" -> {
                val key = apiKeyOverride ?: System.getenv("OPENAI_API_KEY")
                require(!key.isNullOrBlank()) { "ERROR: OPENAI_API_KEY is missing in your .env file." }
                createOpenAi(client, key, finalBaseUrl, finalModelId)
            }
            "CLAUDE" -> {
                val key = apiKeyOverride ?: System.getenv("ANTHROPIC_API_KEY")
                require(!key.isNullOrBlank()) { "ERROR: ANTHROPIC_API_KEY is missing in your .env file." }
                createClaude(client, finalBaseUrl, key)
            }
            "GROQ" -> createGroq(client, apiKeyOverride, finalBaseUrl, finalModelId)
            "DEEPSEEK" -> createDeepSeek(client, apiKeyOverride, finalBaseUrl, finalModelId)
            "OPENROUTER" -> createOpenRouter(client, apiKeyOverride, finalBaseUrl, finalModelId)
            "Cerebras" -> createCerebras(client, apiKeyOverride, finalBaseUrl, finalModelId)
            "GITHUB" -> createGitHub(client, apiKeyOverride, finalBaseUrl, finalModelId)
            "LOCAL", "LOCAL_PC" -> createLocal(client, finalBaseUrl, apiKeyOverride, finalModelId)
            "MOCK" -> createMock(client)
            else -> {
                logger.warn("Unknown provider: $activeProvider. Falling back to Gemini.")
                val key = apiKeyOverride ?: System.getenv("GEMINI_API_KEY")
                require(!key.isNullOrBlank()) { "ERROR: GEMINI_API_KEY is missing in your .env file." }
                createGemini(client, key)
            }
        }
    }

    private fun createOpenAi(client: HttpClient, apiKeyOverride: String? = null, baseUrlOverride: String? = null, modelIdOverride: String? = null) = OpenAiCompatibleProvider(
        client = client,
        providerName = "OpenAI",
        baseUrl = baseUrlOverride ?: "https://api.openai.com/v1",
        apiKey = apiKeyOverride ?: System.getenv("OPENAI_API_KEY") ?: "",
        defaultModel = modelIdOverride ?: "gpt-4-turbo-preview"
    )

    private fun createGroq(client: HttpClient, apiKeyOverride: String? = null, baseUrlOverride: String? = null, modelIdOverride: String? = null) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Groq",
        baseUrl = baseUrlOverride ?: "https://api.groq.com/openai/v1",
        apiKey = apiKeyOverride ?: System.getenv("GROQ_API_KEY") ?: "",
        defaultModel = modelIdOverride ?: "llama3-70b-8192"
    )

    private fun createDeepSeek(client: HttpClient, apiKeyOverride: String? = null, baseUrlOverride: String? = null, modelIdOverride: String? = null) = OpenAiCompatibleProvider(
        client = client,
        providerName = "DeepSeek",
        baseUrl = baseUrlOverride ?: "https://api.deepseek.com",
        apiKey = apiKeyOverride ?: System.getenv("DEEPSEEK_API_KEY") ?: "",
        defaultModel = modelIdOverride ?: "deepseek-chat"
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

    private fun createOpenRouter(client: HttpClient, apiKeyOverride: String? = null, baseUrlOverride: String? = null, modelIdOverride: String? = null) = OpenAiCompatibleProvider(
        client = client,
        providerName = "OpenRouter",
        baseUrl = baseUrlOverride ?: "https://openrouter.ai/api/v1",
        apiKey = apiKeyOverride ?: System.getenv("OPENROUTER_API_KEY") ?: "",
        defaultModel = modelIdOverride ?: "openai/gpt-4o" // OpenRouter requires 'provider/model' format
    )

    private fun createCerebras(client: HttpClient, apiKeyOverride: String? = null, baseUrlOverride: String? = null, modelIdOverride: String? = null) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Cerebras",
        baseUrl = baseUrlOverride ?: "https://api.cerebras.ai/v1",
        apiKey = apiKeyOverride ?: System.getenv("CEREBRAS_API_KEY") ?: "",
        defaultModel = modelIdOverride ?: "llama3.1-70b"
    )

    private fun createGitHub(client: HttpClient, apiKeyOverride: String? = null, baseUrlOverride: String? = null, modelIdOverride: String? = null) = OpenAiCompatibleProvider(
        client = client,
        providerName = "GitHub Models",
        baseUrl = baseUrlOverride ?: "https://models.inference.ai.azure.com",
        apiKey = apiKeyOverride ?: System.getenv("GITHUB_TOKEN") ?: "",
        defaultModel = modelIdOverride ?: "gpt-4o"
    )

    private fun createLocal(client: HttpClient, baseUrlOverride: String?, apiKeyOverride: String? = null, modelIdOverride: String? = null) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Local LLM",
        baseUrl = baseUrlOverride ?: System.getenv("LOCAL_LLM_URL") ?: "http://localhost:8000/v1",
        apiKey = apiKeyOverride ?: System.getenv("LOCAL_LLM_KEY") ?: "not-needed",
        defaultModel = modelIdOverride ?: System.getenv("LOCAL_LLM_MODEL") ?: "chatglm3-6b"
    )

    private fun createMock(client: HttpClient) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Mock",
        baseUrl = "http://localhost:7860/mock", // Points to local mock if needed
        apiKey = "mock-key",
        defaultModel = "mock-model"
    )
}
