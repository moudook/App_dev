package com.example.smarty.server.llm

import io.ktor.client.*
import kotlinx.coroutines.flow.Flow
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Factory for creating LlmProvider instances based on configuration.
 * Supports dynamic switching via ACTIVE_PROVIDER environment variable.
 * Supports comma-separated API keys for automatic key rotation.
 */
object LlmProviderFactory {
    private val logger = LoggerFactory.getLogger(LlmProviderFactory::class.java)

    private fun parseApiKeys(envVar: String?): List<String> {
        if (envVar.isNullOrBlank()) return emptyList()
        return envVar.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun getEnvKey(provider: String): String {
        return when (provider.uppercase()) {
            "GEMINI" -> "GEMINI_API_KEY"
            "OPENAI" -> "OPENAI_API_KEY"
            "GROQ" -> "GROQ_API_KEY"
            "DEEPSEEK" -> "DEEPSEEK_API_KEY"
            "OPENROUTER" -> "OPENROUTER_API_KEY"
            "CEREBRAS" -> "CEREBRAS_API_KEY"
            "GITHUB" -> "GITHUB_TOKEN"
            "LOCAL", "LOCAL_PC" -> "LOCAL_LLM_KEY"
            else -> "${provider.uppercase()}_API_KEY"
        }
    }

    fun create(client: HttpClient, providerOverride: String? = null, baseUrlOverride: String? = null, apiKeyOverride: String? = null, modelIdOverride: String? = null): LlmProvider {
        val activeProvider = providerOverride?.uppercase()
            ?: System.getenv("ACTIVE_PROVIDER")?.uppercase()
            ?: "GEMINI"
        
        val envBaseUrl = System.getenv("LLM_BASE_URL")?.takeIf { it.isNotBlank() }
        val envModelId = System.getenv("LLM_MODEL_ID")?.takeIf { it.isNotBlank() }
        
        val finalBaseUrl = baseUrlOverride ?: envBaseUrl
        val finalModelId = modelIdOverride ?: envModelId

        val envKeyName = getEnvKey(activeProvider)
        val envApiKeys = parseApiKeys(System.getenv(envKeyName))
        val keys = when {
            apiKeyOverride != null -> parseApiKeys(apiKeyOverride)
            envApiKeys.isNotEmpty() -> envApiKeys
            else -> {
                val singleKey = System.getenv(envKeyName)
                if (singleKey.isNullOrBlank()) emptyList() else listOf(singleKey)
            }
        }

        logger.info("Initializing LLM Provider: $activeProvider with ${keys.size} API key(s)")

        val baseProvider = when (activeProvider) {
            "GEMINI" -> {
                require(keys.isNotEmpty() && keys[0].isNotBlank()) { "ERROR: $envKeyName is missing. Please add it to use the $activeProvider provider." }
                createGemini(client, keys[0])
            }
            "OPENAI" -> {
                require(keys.isNotEmpty() && keys[0].isNotBlank()) { "ERROR: $envKeyName is missing." }
                createOpenAi(client, keys[0], finalBaseUrl, finalModelId)
            }
            "GROQ" -> createGroq(client, keys, finalBaseUrl, finalModelId)
            "DEEPSEEK" -> createDeepSeek(client, keys, finalBaseUrl, finalModelId)
            "OPENROUTER" -> createOpenRouter(client, keys, finalBaseUrl, finalModelId)
            "CEREBRAS" -> createCerebras(client, keys, finalBaseUrl, finalModelId)
            "GITHUB" -> createGitHub(client, keys, finalBaseUrl, finalModelId)
            "LOCAL", "LOCAL_PC" -> createLocal(client, keys, finalBaseUrl, finalModelId)
            "MOCK" -> createMock(client)
            else -> {
                logger.warn("Unknown provider: $activeProvider. Falling back to OpenAI.")
                require(keys.isNotEmpty() && keys[0].isNotBlank()) { "ERROR: $envKeyName is missing." }
                createOpenAi(client, keys[0], finalBaseUrl, finalModelId)
            }
        }

        return if (keys.size > 1) {
            KeyRotatingProvider(baseProvider, keys, activeProvider)
        } else {
            baseProvider
        }
    }

    private fun createOpenAi(client: HttpClient, apiKey: String, baseUrlOverride: String?, modelIdOverride: String?) = OpenAiCompatibleProvider(
        client = client,
        providerName = "OpenAI",
        baseUrl = baseUrlOverride ?: "https://api.openai.com/v1",
        apiKey = apiKey,
        defaultModel = modelIdOverride ?: "gpt-4-turbo-preview"
    )

    private fun createOpenAi(client: HttpClient, keys: List<String>, baseUrlOverride: String?, modelIdOverride: String?) = OpenAiCompatibleProvider(
        client = client,
        providerName = "OpenAI",
        baseUrl = baseUrlOverride ?: "https://api.openai.com/v1",
        apiKey = keys.firstOrNull() ?: "",
        defaultModel = modelIdOverride ?: "gpt-4-turbo-preview"
    )

    private fun createGroq(client: HttpClient, keys: List<String>, baseUrlOverride: String?, modelIdOverride: String?) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Groq",
        baseUrl = baseUrlOverride ?: "https://api.groq.com/openai/v1",
        apiKey = keys.firstOrNull() ?: System.getenv("GROQ_API_KEY") ?: "",
        defaultModel = modelIdOverride ?: "llama3-70b-8192"
    )

    private fun createDeepSeek(client: HttpClient, keys: List<String>, baseUrlOverride: String?, modelIdOverride: String?) = OpenAiCompatibleProvider(
        client = client,
        providerName = "DeepSeek",
        baseUrl = baseUrlOverride ?: "https://api.deepseek.com",
        apiKey = keys.firstOrNull() ?: System.getenv("DEEPSEEK_API_KEY") ?: "",
        defaultModel = modelIdOverride ?: "deepseek-chat"
    )

    private fun createGemini(client: HttpClient, apiKey: String) = GeminiProvider(
        client = client,
        apiKey = apiKey
    )

    private fun createOpenRouter(client: HttpClient, keys: List<String>, baseUrlOverride: String?, modelIdOverride: String?) = OpenAiCompatibleProvider(
        client = client,
        providerName = "OpenRouter",
        baseUrl = baseUrlOverride ?: "https://openrouter.ai/api/v1",
        apiKey = keys.firstOrNull() ?: System.getenv("OPENROUTER_API_KEY") ?: "",
        defaultModel = modelIdOverride ?: "openai/gpt-4o"
    )

    private fun createCerebras(client: HttpClient, keys: List<String>, baseUrlOverride: String?, modelIdOverride: String?) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Cerebras",
        baseUrl = baseUrlOverride ?: "https://api.cerebras.ai/v1",
        apiKey = keys.firstOrNull() ?: System.getenv("CEREBRAS_API_KEY") ?: "",
        defaultModel = modelIdOverride ?: "llama3.1-70b"
    )

    private fun createGitHub(client: HttpClient, keys: List<String>, baseUrlOverride: String?, modelIdOverride: String?) = OpenAiCompatibleProvider(
        client = client,
        providerName = "GitHub Models",
        baseUrl = baseUrlOverride ?: "https://models.inference.ai.azure.com",
        apiKey = keys.firstOrNull() ?: System.getenv("GITHUB_TOKEN") ?: "",
        defaultModel = modelIdOverride ?: "gpt-4o"
    )

    private fun createLocal(client: HttpClient, keys: List<String>, baseUrlOverride: String?, modelIdOverride: String?) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Local LLM",
        baseUrl = baseUrlOverride ?: System.getenv("LOCAL_LLM_URL") ?: "http://localhost:8000/v1",
        apiKey = keys.firstOrNull() ?: System.getenv("LOCAL_LLM_KEY") ?: "not-needed",
        defaultModel = modelIdOverride ?: System.getenv("LOCAL_LLM_MODEL") ?: "chatglm3-6b"
    )

    private fun createMock(client: HttpClient) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Mock",
        baseUrl = "http://localhost:7860/mock",
        apiKey = "mock-key",
        defaultModel = "mock-model"
    )
}

/**
 * Wrapper provider that rotates through multiple API keys.
 * Uses round-robin strategy and skips failed keys.
 */
class KeyRotatingProvider(
    private val baseProvider: LlmProvider,
    private val apiKeys: List<String>,
    private val providerName: String
) : LlmProvider {

    private val logger = LoggerFactory.getLogger(KeyRotatingProvider::class.java)
    private val currentIndex = AtomicInteger(0)
    private val failedKeys = mutableSetOf<Int>()

    @Synchronized
    private fun getNextKeyIndex(): Int {
        if (failedKeys.size >= apiKeys.size) {
            logger.warn("All API keys for $providerName have failed. Resetting failed keys.")
            failedKeys.clear()
        }

        var startIndex = currentIndex.get()
        var attempts = 0
        while (attempts < apiKeys.size) {
            if (!failedKeys.contains(startIndex)) {
                currentIndex.set((startIndex + 1) % apiKeys.size)
                return startIndex
            }
            startIndex = (startIndex + 1) % apiKeys.size
            attempts++
        }
        return 0
    }

    private fun getCurrentKey(): String = apiKeys[getNextKeyIndex()]

    private fun markKeyFailed(index: Int) {
        synchronized(failedKeys) {
            failedKeys.add(index)
            logger.warn("Marked API key #$index as failed for $providerName. Failed keys: ${failedKeys.size}/${apiKeys.size}")
        }
    }

    private fun updateProviderKey(key: String) {
        when (val provider = baseProvider) {
            is OpenAiCompatibleProvider -> provider.updateApiKey(key)
            is GeminiProvider -> provider.updateApiKey(key)
        }
    }

    override val providerName: String = "$providerName (Rotating ${apiKeys.size} keys)"

    override suspend fun generate(messages: List<LlmMessage>, tools: List<ToolDefinition>, model: String?): LlmResponse {
        var lastException: Exception? = null

        for (attempt in 0 until apiKeys.size) {
            val keyIndex = getNextKeyIndex()
            val key = apiKeys[keyIndex]

            try {
                updateProviderKey(key)
                logger.debug("Attempting generate with key #$keyIndex for $providerName")
                return baseProvider.generate(messages, tools, model)
            } catch (e: Exception) {
                lastException = e
                val errorMsg = e.message ?: "Unknown error"
                val isRetryable = errorMsg.contains("429") || 
                                  errorMsg.contains("rate") || 
                                  errorMsg.contains("500") || 
                                  errorMsg.contains("502") || 
                                  errorMsg.contains("503") ||
                                  errorMsg.contains("timeout", ignoreCase = true)

                if (isRetryable || errorMsg.contains("401") || errorMsg.contains("403")) {
                    logger.warn("Key #$keyIndex failed for $providerName: ${e.message}")
                    markKeyFailed(keyIndex)
                } else {
                    logger.error("Non-retryable error with key #$keyIndex for $providerName", e)
                    throw e
                }
            }
        }

        throw lastException ?: IllegalStateException("All API keys failed for $providerName")
    }

    override suspend fun stream(messages: List<LlmMessage>, tools: List<ToolDefinition>, model: String?): Flow<LlmChunk> {
        var lastException: Exception? = null

        for (attempt in 0 until apiKeys.size) {
            val keyIndex = getNextKeyIndex()
            val key = apiKeys[keyIndex]

            try {
                updateProviderKey(key)
                logger.debug("Attempting stream with key #$keyIndex for $providerName")
                return baseProvider.stream(messages, tools, model)
            } catch (e: Exception) {
                lastException = e
                val errorMsg = e.message ?: "Unknown error"
                val isRetryable = errorMsg.contains("429") || 
                                  errorMsg.contains("rate") || 
                                  errorMsg.contains("500") || 
                                  errorMsg.contains("502") || 
                                  errorMsg.contains("503") ||
                                  errorMsg.contains("timeout", ignoreCase = true)

                if (isRetryable || errorMsg.contains("401") || errorMsg.contains("403")) {
                    logger.warn("Key #$keyIndex failed for $providerName: ${e.message}")
                    markKeyFailed(keyIndex)
                } else {
                    logger.error("Non-retryable error with key #$keyIndex for $providerName", e)
                    throw e
                }
            }
        }

        throw lastException ?: IllegalStateException("All API keys failed for $providerName")
    }
}
