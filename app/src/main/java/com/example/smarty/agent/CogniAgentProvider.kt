package com.example.smarty.agent

import android.util.Log
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.util.api.ProviderFailoverManager
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider

/**
 * Provides configured prompt executor based on user's API key settings.
 * Supports provider priority fallback with circuit breaker pattern.
 */
class CogniAgentProvider(
    private val securePreferences: SecurePreferences
) {
    companion object {
        private const val TAG = "CogniAgentProvider"
    }

    // Failover manager for circuit breaker and health tracking
    private val failoverManager = ProviderFailoverManager.getInstance()

    /**
     * Result of attempting to get an executor.
     */
    sealed class ExecutorResult {
        data class Success(
            val executor: PromptExecutor,
            val model: LLModel,
            val provider: AIProvider,
            val apiKey: String = "",     // The API key used (for failure tracking)
            val keyIndex: Int = 1        // 1-indexed key number for logging
        ) : ExecutorResult()

        data class NoApiKey(val message: String) : ExecutorResult()
        data class UnsupportedProvider(val provider: AIProvider) : ExecutorResult()
    }

    /**
     * Get configured executor based on provider priority.
     * Returns the first available executor from enabled providers.
     */
    fun getExecutor(): ExecutorResult {
        val executors = getAllAvailableExecutors()
        return executors.firstOrNull()
            ?: ExecutorResult.NoApiKey("No AI provider configured with API keys")
    }

    /**
     * Get available executors for the AI Agent in priority order.
     *
     * KEY SEPARATION ARCHITECTURE:
     * - Agent uses Keys 1 and 2 (dedicated agent keys with failover)
     * - Keys 3, 4, 5... are reserved for AIService (background operations)
     * - If both agent keys fail, falls back to next provider
     *
     * This ensures:
     * - Agent has 2 keys for reliability (Key 1 → Key 2 → next provider)
     * - Background operations use Keys 3-5 without affecting agent
     * - No rate limit conflicts between agent and background tasks
     */
    fun getAllAvailableExecutors(): List<ExecutorResult.Success> {
        val priority = securePreferences.getProviderPriority()
        val executors = mutableListOf<ExecutorResult.Success>()

        // Get healthy providers in priority order
        val healthyProviders = failoverManager.getOrderedHealthyProviders(priority)

        for (provider in healthyProviders) {
            if (!securePreferences.isProviderEnabled(provider)) continue

            val allKeys = securePreferences.getProviderKeys(provider)
            if (allKeys.isEmpty()) continue

            val selectedModel = securePreferences.getSelectedModel(provider)

            // Agent uses same keys as notecard processing (Keys 3, 4, 5...)
            // Skip Keys 1-2, use Keys 3+ for both agent and AIService
            val agentKeys = allKeys.drop(2) // Skip first 2 keys, use Keys 3, 4, 5...
            val healthyAgentKeys = agentKeys.filter { !failoverManager.isKeyFailed(it) }

            if (healthyAgentKeys.isEmpty()) {
                Log.d(TAG, "Skipping $provider - all agent keys (3+) temporarily failed, trying next provider")
                continue
            }

            Log.d(TAG, "Agent using $provider with ${healthyAgentKeys.size} healthy key(s) [KEY-3+], model: $selectedModel")

            // Create executor entries for agent keys (Keys 3, 4, 5...)
            for (apiKey in healthyAgentKeys) {
                val keyIndex = allKeys.indexOf(apiKey) + 1 // 1-indexed for logging

                val result = when (provider) {
                    AIProvider.GEMINI -> createGeminiExecutor(apiKey, selectedModel, keyIndex)
                    AIProvider.ANTHROPIC -> createAnthropicExecutor(apiKey, selectedModel, keyIndex)
                    AIProvider.OPENAI -> createOpenAIExecutor(apiKey, selectedModel, keyIndex)
                    // OpenAI-compatible providers via OpenAI executor with custom base URL
                    AIProvider.GROQ -> createGroqExecutor(apiKey, selectedModel, keyIndex)
                    AIProvider.CEREBRAS -> createCerebrasExecutor(apiKey, selectedModel, keyIndex)
                    AIProvider.COHERE -> createCohereExecutor(apiKey, selectedModel, keyIndex)
                    AIProvider.DEEPSEEK -> createDeepSeekExecutor(apiKey, selectedModel, keyIndex)
                    AIProvider.OPENROUTER -> createOpenRouterExecutor(apiKey, selectedModel, keyIndex)
                    AIProvider.HUGGINGFACE -> ExecutorResult.UnsupportedProvider(provider)
                }

                if (result is ExecutorResult.Success) {
                    executors.add(result)
                }
            }
        }

        Log.i(TAG, "Agent executors available: ${executors.size} (using KEY-3+ per provider)")
        return executors
    }

    /**
     * Record a successful API call for a provider.
     */
    fun recordSuccess(provider: AIProvider) {
        failoverManager.recordSuccess(provider)
    }

    /**
     * Record a failed API call for a provider.
     */
    fun recordFailure(provider: AIProvider, exception: Exception) {
        failoverManager.recordFailure(provider, exception)
    }

    /**
     * Record a failed API call for a specific key with provider-specific cooldown.
     * Parses retry-after from error message if available.
     */
    fun recordKeyFailure(apiKey: String, provider: AIProvider, exception: Exception) {
        val errorMessage = exception.message ?: ""
        val category = failoverManager.categorizeError(exception)
        failoverManager.recordKeyFailureForProvider(apiKey, provider, category, errorMessage)
    }

    /**
     * Reset health state for a provider.
     */
    fun resetProviderHealth(provider: AIProvider) {
        failoverManager.resetProvider(provider)
    }

    private fun createGeminiExecutor(apiKey: String, modelId: String, keyIndex: Int = 1): ExecutorResult {
        val model = mapGeminiModel(modelId)
        return ExecutorResult.Success(
            executor = simpleGoogleAIExecutor(apiKey),
            model = model,
            provider = AIProvider.GEMINI,
            apiKey = apiKey,
            keyIndex = keyIndex
        )
    }

    private fun createAnthropicExecutor(apiKey: String, modelId: String, keyIndex: Int = 1): ExecutorResult {
        val model = mapAnthropicModel(modelId)
        return ExecutorResult.Success(
            executor = simpleAnthropicExecutor(apiKey),
            model = model,
            provider = AIProvider.ANTHROPIC,
            apiKey = apiKey,
            keyIndex = keyIndex
        )
    }

    private fun createOpenAIExecutor(apiKey: String, modelId: String, keyIndex: Int = 1): ExecutorResult {
        val model = mapOpenAIModel(modelId)
        return ExecutorResult.Success(
            executor = simpleOpenAIExecutor(apiKey),
            model = model,
            provider = AIProvider.OPENAI,
            apiKey = apiKey,
            keyIndex = keyIndex
        )
    }

    /**
     * Create a custom OpenAI-compatible executor with a specific base URL.
     */
    private fun createCustomOpenAIExecutor(
        apiKey: String,
        baseUrl: String
    ): PromptExecutor {
        val settings = OpenAIClientSettings(
            baseUrl = baseUrl
        )
        val client = OpenAILLMClient(
            apiKey = apiKey,
            settings = settings
        )
        return SingleLLMPromptExecutor(client)
    }

    private fun createGroqExecutor(apiKey: String, modelId: String, keyIndex: Int = 1): ExecutorResult {
        val model = mapGroqModel(modelId)
        return ExecutorResult.Success(
            executor = createCustomOpenAIExecutor(
                apiKey = apiKey,
                baseUrl = "https://api.groq.com/openai/v1"
            ),
            model = model,
            provider = AIProvider.GROQ,
            apiKey = apiKey,
            keyIndex = keyIndex
        )
    }

    private fun createCerebrasExecutor(apiKey: String, modelId: String, keyIndex: Int = 1): ExecutorResult {
        val model = mapCerebrasModel(modelId)
        return ExecutorResult.Success(
            executor = createCustomOpenAIExecutor(
                apiKey = apiKey,
                baseUrl = "https://api.cerebras.ai/v1"
            ),
            model = model,
            provider = AIProvider.CEREBRAS,
            apiKey = apiKey,
            keyIndex = keyIndex
        )
    }

    private fun createCohereExecutor(apiKey: String, modelId: String, keyIndex: Int = 1): ExecutorResult {
        val model = mapCohereModel(modelId)
        return ExecutorResult.Success(
            executor = createCustomOpenAIExecutor(
                apiKey = apiKey,
                baseUrl = "https://api.cohere.ai/compatibility/v1"
            ),
            model = model,
            provider = AIProvider.COHERE,
            apiKey = apiKey,
            keyIndex = keyIndex
        )
    }

    private fun createDeepSeekExecutor(apiKey: String, modelId: String, keyIndex: Int = 1): ExecutorResult {
        val model = mapDeepSeekModel(modelId)
        return ExecutorResult.Success(
            executor = createCustomOpenAIExecutor(
                apiKey = apiKey,
                baseUrl = "https://api.deepseek.com/v1"
            ),
            model = model,
            provider = AIProvider.DEEPSEEK,
            apiKey = apiKey,
            keyIndex = keyIndex
        )
    }

    private fun createOpenRouterExecutor(apiKey: String, modelId: String, keyIndex: Int = 1): ExecutorResult {
        val model = mapOpenRouterModel(modelId)
        return ExecutorResult.Success(
            executor = createCustomOpenAIExecutor(
                apiKey = apiKey,
                baseUrl = "https://openrouter.ai/api/v1"
            ),
            model = model,
            provider = AIProvider.OPENROUTER,
            apiKey = apiKey,
            keyIndex = keyIndex
        )
    }

    /**
     * Create a custom LLModel with a specific model ID for OpenAI-compatible providers.
     * This allows using actual model IDs (e.g., "llama-3.3-70b-versatile" for GROQ)
     * instead of mapping to OpenAI model names which would fail.
     */
    private fun createOpenAICompatibleModel(modelId: String): LLModel {
        return LLModel(
            provider = LLMProvider.OpenAI,  // OpenAI-compatible API
            id = modelId,                    // Actual model ID sent to API
            capabilities = listOf(
                LLMCapability.Temperature,
                LLMCapability.Tools,
                LLMCapability.Completion
            ),
            contextLength = 128_000  // Conservative default
        )
    }

    // Custom model mapping for Groq - use actual model ID
    private fun mapGroqModel(modelId: String): LLModel {
        // Use the actual model ID so GROQ receives the correct model name
        // e.g., "llama-3.3-70b-versatile", "llama-3.1-8b-instant", etc.
        return createOpenAICompatibleModel(modelId)
    }

    // Custom model mapping for Cerebras - use actual model ID
    private fun mapCerebrasModel(modelId: String): LLModel {
        // Use the actual model ID so Cerebras receives the correct model name
        // e.g., "llama-3.3-70b", "llama-4-scout-17b-16e-instruct", etc.
        return createOpenAICompatibleModel(modelId)
    }

    // Custom model mapping for Cohere - use actual model ID
    private fun mapCohereModel(modelId: String): LLModel {
        // Use the actual model ID so Cohere receives the correct model name
        // e.g., "command-a-03-2025", "command-r-plus", etc.
        return createOpenAICompatibleModel(modelId)
    }

    // Custom model mapping for DeepSeek - use actual model ID
    private fun mapDeepSeekModel(modelId: String): LLModel {
        // Use the actual model ID so DeepSeek receives the correct model name
        // e.g., "deepseek-chat", "deepseek-reasoner", etc.
        return createOpenAICompatibleModel(modelId)
    }

    // Custom model mapping for OpenRouter - use actual model ID
    private fun mapOpenRouterModel(modelId: String): LLModel {
        // Use the actual model ID so OpenRouter receives the correct model name
        return createOpenAICompatibleModel(modelId)
    }

    // Model mapping functions
    private fun mapGeminiModel(modelId: String): LLModel {
        return when {
            modelId.contains("2.5-pro") -> GoogleModels.Gemini2_5Pro
            modelId.contains("2.5-flash-lite") -> GoogleModels.Gemini2_5FlashLite
            modelId.contains("2.5-flash") -> GoogleModels.Gemini2_5Flash
            modelId.contains("2.0-flash-lite") -> GoogleModels.Gemini2_0FlashLite
            modelId.contains("2.0-flash") -> GoogleModels.Gemini2_0Flash
            else -> GoogleModels.Gemini2_5Flash // Default
        }
    }

    private fun mapAnthropicModel(modelId: String): LLModel {
        return when {
            modelId.contains("claude-4-opus") -> AnthropicModels.Opus_4
            modelId.contains("claude-4.1-opus") -> AnthropicModels.Opus_4_1
            modelId.contains("claude-3-opus") -> AnthropicModels.Opus_3
            modelId.contains("claude-4-sonnet") -> AnthropicModels.Sonnet_4
            modelId.contains("claude-3-7-sonnet") -> AnthropicModels.Sonnet_3_7
            modelId.contains("claude-3-5-sonnet") -> AnthropicModels.Sonnet_3_5
            modelId.contains("claude-3-5-haiku") -> AnthropicModels.Haiku_3_5
            modelId.contains("claude-3-haiku") -> AnthropicModels.Haiku_3
            else -> AnthropicModels.Sonnet_3_5 // Default
        }
    }

    private fun mapOpenAIModel(modelId: String): LLModel {
        return when {
            modelId.contains("gpt-4o-mini") -> OpenAIModels.CostOptimized.GPT4oMini
            modelId.contains("gpt-4o") -> OpenAIModels.Chat.GPT4o
            modelId.contains("gpt-4.1-nano") -> OpenAIModels.CostOptimized.GPT4_1Nano
            modelId.contains("gpt-4.1-mini") -> OpenAIModels.CostOptimized.GPT4_1Mini
            modelId.contains("gpt-4.1") -> OpenAIModels.Chat.GPT4_1
            modelId.contains("gpt-5-mini") -> OpenAIModels.Chat.GPT5Mini
            modelId.contains("gpt-5-nano") -> OpenAIModels.Chat.GPT5Nano
            modelId.contains("gpt-5") -> OpenAIModels.Chat.GPT5
            else -> OpenAIModels.CostOptimized.GPT4oMini // Default
        }
    }

    /**
     * Check if any valid provider is configured.
     * HuggingFace is the only unsupported provider.
     */
    fun hasConfiguredProvider(): Boolean {
        val priority = securePreferences.getProviderPriority()
        return priority.any { provider ->
            securePreferences.isProviderEnabled(provider) &&
            securePreferences.getProviderKeys(provider).isNotEmpty() &&
            provider != AIProvider.HUGGINGFACE // HuggingFace not supported for agent
        }
    }

    /**
     * Get the currently configured provider name for display.
     */
    fun getCurrentProviderName(): String? {
        val result = getExecutor()
        return when (result) {
            is ExecutorResult.Success -> result.provider.name
            else -> null
        }
    }
}
