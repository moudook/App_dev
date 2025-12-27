package com.example.smarty.agent

import android.util.Log
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.util.api.ApiKeyRotator
import com.example.smarty.util.api.ProviderFailoverManager
import com.example.smarty.util.api.GroqKeyManager
import com.example.smarty.util.api.GroqKeyConfig
import com.example.smarty.util.api.KeyPurpose
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
 * Integrates with GroqKeyManager for per-key rate limit tracking.
 */
class CogniAgentProvider(
    private val securePreferences: SecurePreferences,
    private val groqKeyManager: GroqKeyManager? = null
) {
    companion object {
        private const val TAG = "CogniAgentProvider"
    }

    // Failover manager for circuit breaker and health tracking
    private val failoverManager = ProviderFailoverManager.getInstance()

    // 2-key rotation: alternate between 2 keys per message
    @Volatile
    private var keyRotationIndex = 0

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
     * Get available executors for the AI Agent.
     *
     * KEY SEPARATION ARCHITECTURE:
     * - AI Agent uses ONLY keys 1 & 2 (agent keys)
     * - Note analysis uses keys 3, 4, 5... (background keys)
     * - This prevents agent operations from exhausting note processing quota
     *
     * Uses USER PRIORITY ORDER - iterates through all enabled providers
     * in the order set by the user in Settings.
     */
    fun getAllAvailableExecutors(): List<ExecutorResult.Success> {
        val executors = mutableListOf<ExecutorResult.Success>()

        // Get providers in user's priority order
        val priorityOrder = securePreferences.getProviderPriority()
        // Include any providers not in priority list (newly added)
        val allProviders = (priorityOrder + AIProvider.entries).distinct()

        Log.d(TAG, "Provider priority order: ${allProviders.map { it.name }}")

        for (provider in allProviders) {
            // Skip disabled providers
            if (!securePreferences.isProviderEnabled(provider)) {
                continue
            }

            // LOCAL_PC doesn't need API keys - handle specially
            if (provider == AIProvider.LOCAL_PC) {
                val selectedModel = securePreferences.getSelectedModel(provider)
                val result = createExecutorForProvider(provider, "local_pc_no_key", selectedModel, 1)
                if (result is ExecutorResult.Success) {
                    executors.add(result)
                    Log.d(TAG, "${provider.name}: Using local server, model: $selectedModel")
                }
                continue
            }

            val allKeys = securePreferences.getProviderKeys(provider)
            if (allKeys.isEmpty()) {
                continue
            }

            val selectedModel = securePreferences.getSelectedModel(provider)

            // Use ONLY agent keys (keys 1 & 2) - never touch background keys
            val agentKeys = ApiKeyRotator.getAgentKeys(allKeys)

            if (agentKeys.size > 1) {
                // Rotate between agent keys (1 and 2)
                val tryFirstFirst = (keyRotationIndex % 2) == 0
                keyRotationIndex++

                val keysToUse = if (tryFirstFirst) agentKeys else agentKeys.reversed()

                for ((idx, apiKey) in keysToUse.withIndex()) {
                    val keyIndex = ApiKeyRotator.getKeyIndex(allKeys, apiKey)
                    val result = createExecutorForProvider(provider, apiKey, selectedModel, keyIndex)
                    if (result is ExecutorResult.Success) {
                        executors.add(result)
                    }
                }

                Log.d(TAG, "${provider.name}: Using agent keys 1 & 2, model: $selectedModel")
            } else if (agentKeys.isNotEmpty()) {
                // Single key - use it (shared mode)
                val apiKey = agentKeys.first()
                val result = createExecutorForProvider(provider, apiKey, selectedModel, 1)
                if (result is ExecutorResult.Success) {
                    executors.add(result)
                }

                Log.d(TAG, "${provider.name}: Using single key (shared mode), model: $selectedModel")
            }
        }

        Log.i(TAG, "Agent executors ready: ${executors.size} across ${executors.map { it.provider }.distinct().size} providers")
        return executors
    }

    /**
     * Create executor for a specific provider.
     */
    private fun createExecutorForProvider(
        provider: AIProvider,
        apiKey: String,
        modelId: String,
        keyIndex: Int
    ): ExecutorResult {
        return when (provider) {
            AIProvider.GROQ -> createGroqExecutor(apiKey, modelId, keyIndex)
            AIProvider.GEMINI -> createGeminiExecutor(apiKey, modelId, keyIndex)
            AIProvider.ANTHROPIC -> createAnthropicExecutor(apiKey, modelId, keyIndex)
            AIProvider.OPENAI -> createOpenAIExecutor(apiKey, modelId, keyIndex)
            AIProvider.DEEPSEEK -> createDeepSeekExecutor(apiKey, modelId, keyIndex)
            AIProvider.CEREBRAS -> createCerebrasExecutor(apiKey, modelId, keyIndex)
            AIProvider.COHERE -> createCohereExecutor(apiKey, modelId, keyIndex)
            AIProvider.OPENROUTER -> createOpenRouterExecutor(apiKey, modelId, keyIndex)
            AIProvider.HUGGINGFACE -> createHuggingFaceExecutor(apiKey, modelId, keyIndex)
            AIProvider.GITHUB -> createGitHubExecutor(apiKey, modelId, keyIndex)
            AIProvider.LOCAL_PC -> createLocalPCExecutor(apiKey, modelId, keyIndex)
        }
    }

    /**
     * Create HuggingFace executor (OpenAI-compatible inference API).
     */
    private fun createHuggingFaceExecutor(apiKey: String, modelId: String, keyIndex: Int): ExecutorResult {
        val model = createOpenAICompatibleModel(modelId)
        return ExecutorResult.Success(
            executor = createCustomOpenAIExecutor(
                apiKey = apiKey,
                baseUrl = "https://api-inference.huggingface.co/v1"
            ),
            model = model,
            provider = AIProvider.HUGGINGFACE,
            apiKey = apiKey,
            keyIndex = keyIndex
        )
    }

    /**
     * Create GitHub Models executor (OpenAI-compatible inference API).
     * Free with GitHub account (requires PAT with models scope).
     */
    private fun createGitHubExecutor(apiKey: String, modelId: String, keyIndex: Int): ExecutorResult {
        val model = createOpenAICompatibleModel(modelId)
        return ExecutorResult.Success(
            executor = createCustomOpenAIExecutor(
                apiKey = apiKey,
                baseUrl = "https://models.github.ai/inference"
            ),
            model = model,
            provider = AIProvider.GITHUB,
            apiKey = apiKey,
            keyIndex = keyIndex
        )
    }

    /**
     * Create Local PC executor (OpenAI-compatible inference API via USB tethering).
     * FOR TESTING ONLY - Remove before publishing!
     */
    private fun createLocalPCExecutor(apiKey: String, modelId: String, keyIndex: Int): ExecutorResult {
        val model = createOpenAICompatibleModel(modelId)
        return ExecutorResult.Success(
            executor = createCustomOpenAIExecutor(
                apiKey = "",  // No API key needed for local server
                baseUrl = "http://10.224.189.60:8000"
            ),
            model = model,
            provider = AIProvider.LOCAL_PC,
            apiKey = apiKey,
            keyIndex = keyIndex
        )
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

    // ==================== GroqKeyManager Integration ====================

    /**
     * Record a successful API call for a specific GROQ key.
     * Updates both failover manager and GroqKeyManager.
     */
    suspend fun recordKeySuccess(apiKey: String) {
        groqKeyManager?.recordCall(apiKey)
    }

    /**
     * Record a rate limit for a specific GROQ key.
     */
    suspend fun recordKeyRateLimit(apiKey: String, retryAfterMs: Long? = null) {
        groqKeyManager?.recordRateLimit(apiKey, retryAfterMs)
    }

    /**
     * Record an error for a specific GROQ key.
     */
    suspend fun recordKeyError(apiKey: String, isAuthError: Boolean = false) {
        groqKeyManager?.recordError(apiKey, isAuthError)
    }

    /**
     * Sync GROQ keys from SecurePreferences to GroqKeyManager.
     * Call this when keys are added/removed in settings.
     */
    suspend fun syncGroqKeys() {
        groqKeyManager ?: return

        val keys = securePreferences.getProviderKeys(AIProvider.GROQ)
        if (keys.isEmpty()) return

        // Create configs for each key with default settings
        // Keys are assigned purposes based on their index:
        // - First 3 keys: ORCHESTRATOR (main agent)
        // - Next 2 keys: NOTE_PROCESSING (background)
        // - Remaining: GENERAL (fallback)
        val configs = keys.mapIndexed { index, key ->
            val purpose = when {
                index < 3 -> KeyPurpose.ORCHESTRATOR
                index < 5 -> KeyPurpose.NOTE_PROCESSING
                index < 7 -> KeyPurpose.RESEARCH
                else -> KeyPurpose.GENERAL
            }
            val label = when (purpose) {
                KeyPurpose.ORCHESTRATOR -> "Agent #${index + 1}"
                KeyPurpose.NOTE_PROCESSING -> "Notes #${index - 2}"
                KeyPurpose.RESEARCH -> "Research #${index - 4}"
                KeyPurpose.GENERAL -> "Backup #${index - 6}"
            }
            GroqKeyConfig(
                key = key,
                label = label,
                rateLimit = 30,      // Default GROQ rate limit
                dailyLimit = 14400,  // Default GROQ daily limit
                purpose = purpose
            )
        }

        groqKeyManager.configureKeys(configs)
        Log.i(TAG, "Synced ${configs.size} GROQ keys with GroqKeyManager")
    }

    /**
     * Get the GroqKeyManager instance for UI access.
     */
    fun getGroqKeyManager(): GroqKeyManager? = groqKeyManager

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
     * Create a custom LLModel for OpenAI-compatible providers (GROQ, Cerebras, etc.).
     *
     * CRITICAL: Must include LLMCapability.OpenAIEndpoint.Completions so the
     * OpenAILLMClient knows which API endpoint to use. Without this capability,
     * the client throws "Cannot determine proper LLM params for OpenAI model".
     *
     * The executor uses OpenAIClientSettings with custom baseUrl to call GROQ/etc.
     */
    private fun createOpenAICompatibleModel(modelId: String): LLModel {
        return LLModel(
            provider = LLMProvider.OpenAI,    // Use OpenAI provider for OpenAI-compatible APIs
            id = modelId,                      // Actual model ID sent to API
            capabilities = listOf(
                LLMCapability.Temperature,
                LLMCapability.Tools,
                LLMCapability.Completion,
                LLMCapability.OpenAIEndpoint.Completions  // Required for OpenAILLMClient
            ),
            contextLength = 128_000  // GROQ/Llama models support large context
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
     * Check if any AI provider is configured with API keys.
     */
    fun hasConfiguredProvider(): Boolean {
        return AIProvider.entries.any { provider ->
            securePreferences.isProviderEnabled(provider) &&
                    securePreferences.getProviderKeys(provider).isNotEmpty()
        }
    }

    /**
     * Get the total number of API keys configured across all providers.
     */
    fun getConfiguredKeyCount(): Int {
        return AIProvider.entries.sumOf { provider ->
            securePreferences.getProviderKeys(provider).size
        }
    }

    /**
     * Get the first configured provider name for display (based on priority).
     */
    fun getCurrentProviderName(): String? {
        val priorityOrder = securePreferences.getProviderPriority()
        val allProviders = (priorityOrder + AIProvider.entries).distinct()

        return allProviders.firstOrNull { provider ->
            securePreferences.isProviderEnabled(provider) &&
                    securePreferences.getProviderKeys(provider).isNotEmpty()
        }?.name
    }

    /**
     * Get configuration status for display.
     */
    fun getProviderStatus(): String {
        val configuredProviders = AIProvider.entries.filter { provider ->
            securePreferences.isProviderEnabled(provider) &&
                    securePreferences.getProviderKeys(provider).isNotEmpty()
        }
        val keyCount = getConfiguredKeyCount()

        return when {
            configuredProviders.isEmpty() -> "No providers configured"
            configuredProviders.size == 1 -> "${configuredProviders.first().name} ($keyCount key${if (keyCount > 1) "s" else ""})"
            else -> "${configuredProviders.size} providers ($keyCount keys total)"
        }
    }

    /**
     * Get GROQ configuration status for display (legacy).
     */
    fun getGroqStatus(): String {
        val keyCount = securePreferences.getProviderKeys(AIProvider.GROQ).size
        return when {
            keyCount == 0 -> "Not configured"
            keyCount == 1 -> "1 API key"
            else -> "$keyCount API keys (high throughput)"
        }
    }
}
