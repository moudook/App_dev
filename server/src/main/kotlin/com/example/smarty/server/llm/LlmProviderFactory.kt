package com.example.smarty.server.llm

import io.ktor.client.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

object LlmProviderFactory {
    private val logger = LoggerFactory.getLogger(LlmProviderFactory::class.java)

    private fun parseApiKeys(envVar: String?): List<String> {
        if (envVar.isNullOrBlank()) return emptyList()
        return envVar.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun getEnvKeyName(provider: String): String {
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

        val envKeyName = getEnvKeyName(activeProvider)
        val rawEnvValue = System.getenv(envKeyName)
        val keys = parseApiKeys(apiKeyOverride ?: rawEnvValue)

        logger.info("Initializing LLM Provider: $activeProvider with ${keys.size} API key(s)")

        if (keys.isEmpty()) {
            require(false) { "ERROR: $envKeyName is missing." }
        }

        return when (activeProvider) {
            "GEMINI" -> {
                if (keys.size > 1) KeyRotatingGeminiProvider(client, keys)
                else createGemini(client, keys[0])
            }
            "OPENAI" -> {
                if (keys.size > 1) KeyRotatingOpenAiProvider(client, "OpenAI", finalBaseUrl ?: "https://api.openai.com/v1", keys, finalModelId ?: "gpt-4-turbo-preview")
                else createOpenAi(client, keys[0], finalBaseUrl, finalModelId)
            }
            "GROQ" -> {
                val url = finalBaseUrl ?: "https://api.groq.com/openai/v1"
                val model = finalModelId ?: "llama3-70b-8192"
                if (keys.size > 1) KeyRotatingOpenAiProvider(client, "Groq", url, keys, model)
                else createGroq(client, keys[0], finalBaseUrl, finalModelId)
            }
            "DEEPSEEK" -> {
                val url = finalBaseUrl ?: "https://api.deepseek.com"
                val model = finalModelId ?: "deepseek-chat"
                if (keys.size > 1) KeyRotatingOpenAiProvider(client, "DeepSeek", url, keys, model)
                else createDeepSeek(client, keys[0], finalBaseUrl, finalModelId)
            }
            "OPENROUTER" -> {
                val url = finalBaseUrl ?: "https://openrouter.ai/api/v1"
                val model = finalModelId ?: "openai/gpt-4o"
                if (keys.size > 1) KeyRotatingOpenAiProvider(client, "OpenRouter", url, keys, model)
                else createOpenRouter(client, keys[0], finalBaseUrl, finalModelId)
            }
            "CEREBRAS" -> {
                val url = finalBaseUrl ?: "https://api.cerebras.ai/v1"
                val model = finalModelId ?: "llama3.1-70b"
                if (keys.size > 1) KeyRotatingOpenAiProvider(client, "Cerebras", url, keys, model)
                else createCerebras(client, keys[0], finalBaseUrl, finalModelId)
            }
            "GITHUB" -> {
                val url = finalBaseUrl ?: "https://models.inference.ai.azure.com"
                val model = finalModelId ?: "gpt-4o"
                if (keys.size > 1) KeyRotatingOpenAiProvider(client, "GitHub Models", url, keys, model)
                else createGitHub(client, keys[0], finalBaseUrl, finalModelId)
            }
            "LOCAL", "LOCAL_PC" -> createLocal(client, finalBaseUrl, keys.firstOrNull(), finalModelId)
            "MOCK" -> createMock(client)
            else -> {
                logger.warn("Unknown provider: $activeProvider. Falling back to OpenAI.")
                if (keys.size > 1) KeyRotatingOpenAiProvider(client, "OpenAI", finalBaseUrl ?: "https://api.openai.com/v1", keys, finalModelId ?: "gpt-4-turbo-preview")
                else createOpenAi(client, keys[0], finalBaseUrl, finalModelId)
            }
        }
    }

    private fun createOpenAi(client: HttpClient, apiKey: String, baseUrlOverride: String?, modelIdOverride: String?) = OpenAiCompatibleProvider(
        client = client,
        providerName = "OpenAI",
        baseUrl = baseUrlOverride ?: "https://api.openai.com/v1",
        apiKey = apiKey,
        defaultModel = modelIdOverride ?: "gpt-4-turbo-preview"
    )

    private fun createGroq(client: HttpClient, apiKey: String?, baseUrlOverride: String?, modelIdOverride: String?) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Groq",
        baseUrl = baseUrlOverride ?: "https://api.groq.com/openai/v1",
        apiKey = apiKey ?: "",
        defaultModel = modelIdOverride ?: "llama3-70b-8192"
    )

    private fun createDeepSeek(client: HttpClient, apiKey: String?, baseUrlOverride: String?, modelIdOverride: String?) = OpenAiCompatibleProvider(
        client = client,
        providerName = "DeepSeek",
        baseUrl = baseUrlOverride ?: "https://api.deepseek.com",
        apiKey = apiKey ?: "",
        defaultModel = modelIdOverride ?: "deepseek-chat"
    )

    private fun createGemini(client: HttpClient, apiKey: String) = GeminiProvider(
        client = client,
        apiKey = apiKey
    )

    private fun createOpenRouter(client: HttpClient, apiKey: String?, baseUrlOverride: String?, modelIdOverride: String?) = OpenAiCompatibleProvider(
        client = client,
        providerName = "OpenRouter",
        baseUrl = baseUrlOverride ?: "https://openrouter.ai/api/v1",
        apiKey = apiKey ?: "",
        defaultModel = modelIdOverride ?: "openai/gpt-4o"
    )

    private fun createCerebras(client: HttpClient, apiKey: String?, baseUrlOverride: String?, modelIdOverride: String?) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Cerebras",
        baseUrl = baseUrlOverride ?: "https://api.cerebras.ai/v1",
        apiKey = apiKey ?: "",
        defaultModel = modelIdOverride ?: "llama3.1-70b"
    )

    private fun createGitHub(client: HttpClient, apiKey: String?, baseUrlOverride: String?, modelIdOverride: String?) = OpenAiCompatibleProvider(
        client = client,
        providerName = "GitHub Models",
        baseUrl = baseUrlOverride ?: "https://models.inference.ai.azure.com",
        apiKey = apiKey ?: "",
        defaultModel = modelIdOverride ?: "gpt-4o"
    )

    private fun createLocal(client: HttpClient, baseUrlOverride: String?, apiKey: String?, modelIdOverride: String?) = OpenAiCompatibleProvider(
        client = client,
        providerName = "Local LLM",
        baseUrl = baseUrlOverride ?: System.getenv("LOCAL_LLM_URL") ?: "http://localhost:8000/v1",
        apiKey = apiKey ?: System.getenv("LOCAL_LLM_KEY") ?: "not-needed",
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

class KeyRotatingOpenAiProvider(
    private val client: HttpClient,
    private val baseProviderName: String,
    private val baseUrl: String,
    private val apiKeys: List<String>,
    private val defaultModel: String
) : LlmProvider {

    private val logger = LoggerFactory.getLogger(KeyRotatingOpenAiProvider::class.java)
    private val currentIndex = AtomicInteger(0)

    override val providerName: String = "$baseProviderName (Rotating ${apiKeys.size} keys)"

    private fun isRetryableError(error: Throwable): Boolean {
        val msg = error.message?.lowercase() ?: ""
        return msg.contains("401") || msg.contains("403") || 
               msg.contains("429") || msg.contains("rate") ||
               msg.contains("500") || msg.contains("502") || 
               msg.contains("503") || msg.contains("timeout") ||
               msg.contains("reset") || msg.contains("connection") ||
               msg.contains("closed") || msg.contains("broken")
    }

    private fun getNextKeyIndex(): Int {
        return currentIndex.getAndIncrement() % apiKeys.size
    }

    override suspend fun generate(messages: List<LlmMessage>, tools: List<ToolDefinition>, model: String?): LlmResponse {
        var lastException: Exception? = null
        val triedKeys = mutableSetOf<Int>()

        while (triedKeys.size < apiKeys.size) {
            val keyIndex = getNextKeyIndex()
            if (keyIndex in triedKeys) continue
            triedKeys.add(keyIndex)

            val provider = OpenAiCompatibleProvider(
                client = client,
                providerName = baseProviderName,
                baseUrl = baseUrl,
                apiKey = apiKeys[keyIndex],
                defaultModel = defaultModel
            )

            try {
                logger.debug("Trying generate with key #$keyIndex for $baseProviderName")
                return provider.generate(messages, tools, model)
            } catch (e: Exception) {
                lastException = e
                if (isRetryableError(e)) {
                    logger.warn("Key #$keyIndex failed for $baseProviderName: ${e.message}, trying next key")
                } else {
                    throw e
                }
            }
        }

        throw lastException ?: IllegalStateException("All API keys failed for $baseProviderName")
    }

    override suspend fun stream(messages: List<LlmMessage>, tools: List<ToolDefinition>, model: String?): Flow<LlmChunk> = flow {
        var lastException: Exception? = null
        val triedKeys = mutableSetOf<Int>()

        while (triedKeys.size < apiKeys.size) {
            val keyIndex = getNextKeyIndex()
            if (keyIndex in triedKeys) continue
            triedKeys.add(keyIndex)

            val provider = OpenAiCompatibleProvider(
                client = client,
                providerName = baseProviderName,
                baseUrl = baseUrl,
                apiKey = apiKeys[keyIndex],
                defaultModel = defaultModel
            )

            try {
                logger.debug("Trying stream with key #$keyIndex for $baseProviderName")
                provider.stream(messages, tools, model).collect { chunk ->
                    emit(chunk)
                }
                return@flow
            } catch (e: Exception) {
                lastException = e
                if (isRetryableError(e)) {
                    logger.warn("Key #$keyIndex failed during stream for $baseProviderName: ${e.message}, trying next key")
                } else {
                    throw e
                }
            }
        }

        throw lastException ?: IllegalStateException("All API keys failed for $baseProviderName")
    }
}

class KeyRotatingGeminiProvider(
    private val client: HttpClient,
    private val apiKeys: List<String>
) : LlmProvider {

    private val logger = LoggerFactory.getLogger(KeyRotatingGeminiProvider::class.java)
    private val currentIndex = AtomicInteger(0)

    override val providerName: String = "Gemini (Rotating ${apiKeys.size} keys)"

    private fun isRetryableError(error: Throwable): Boolean {
        val msg = error.message?.lowercase() ?: ""
        return msg.contains("401") || msg.contains("403") || 
               msg.contains("429") || msg.contains("rate") ||
               msg.contains("500") || msg.contains("502") || 
               msg.contains("503") || msg.contains("timeout")
    }

    private fun getNextKeyIndex(): Int {
        return currentIndex.getAndIncrement() % apiKeys.size
    }

    override suspend fun generate(messages: List<LlmMessage>, tools: List<ToolDefinition>, model: String?): LlmResponse {
        var lastException: Exception? = null
        val triedKeys = mutableSetOf<Int>()

        while (triedKeys.size < apiKeys.size) {
            val keyIndex = getNextKeyIndex()
            if (keyIndex in triedKeys) continue
            triedKeys.add(keyIndex)

            val provider = GeminiProvider(client = client, apiKey = apiKeys[keyIndex])

            try {
                logger.debug("Trying generate with key #$keyIndex for Gemini")
                return provider.generate(messages, tools, model)
            } catch (e: Exception) {
                lastException = e
                if (isRetryableError(e)) {
                    logger.warn("Key #$keyIndex failed for Gemini: ${e.message}, trying next key")
                } else {
                    throw e
                }
            }
        }

        throw lastException ?: IllegalStateException("All API keys failed for Gemini")
    }

    override suspend fun stream(messages: List<LlmMessage>, tools: List<ToolDefinition>, model: String?): Flow<LlmChunk> = flow {
        var lastException: Exception? = null
        val triedKeys = mutableSetOf<Int>()

        while (triedKeys.size < apiKeys.size) {
            val keyIndex = getNextKeyIndex()
            if (keyIndex in triedKeys) continue
            triedKeys.add(keyIndex)

            val provider = GeminiProvider(client = client, apiKey = apiKeys[keyIndex])

            try {
                logger.debug("Trying stream with key #$keyIndex for Gemini")
                provider.stream(messages, tools, model).collect { chunk ->
                    emit(chunk)
                }
                return@flow
            } catch (e: Exception) {
                lastException = e
                if (isRetryableError(e)) {
                    logger.warn("Key #$keyIndex failed during stream for Gemini: ${e.message}, trying next key")
                } else {
                    throw e
                }
            }
        }

        throw lastException ?: IllegalStateException("All API keys failed for Gemini")
    }
}
