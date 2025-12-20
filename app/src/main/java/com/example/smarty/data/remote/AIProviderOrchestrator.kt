package com.example.smarty.data.remote

import android.util.Log
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.AIProviderConfig
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.remote.providers.AIProviderContract
import com.example.smarty.data.remote.providers.AnthropicProvider
import com.example.smarty.data.remote.providers.GeminiProvider
import com.example.smarty.data.remote.providers.HuggingFaceProvider
import com.example.smarty.data.remote.providers.OpenAICompatibleProvider
import com.example.smarty.data.remote.providers.OpenRouterProvider
import com.example.smarty.util.api.ApiKeyRotator
import com.example.smarty.util.api.ProviderPriorityResolver
import com.example.smarty.util.retry.RetryExecutor
import com.google.gson.Gson
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Orchestrates AI provider selection, configuration, and fallback logic.
 *
 * Responsibilities:
 * - Manages provider instances
 * - Handles provider priority ordering
 * - Manages API key rotation
 * - Executes operations with retry logic
 *
 * @property securePreferences Secure storage for API keys and settings
 */
class AIProviderOrchestrator(private val securePreferences: SecurePreferences) {

    companion object {
        private const val TAG = "AIProviderOrchestrator"
        const val MAX_RETRIES = 3
        const val INITIAL_RETRY_DELAY_MS = 1000L
    }

    val gson = Gson()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ==================== Provider Instances ====================

    private val geminiProvider: AIProviderContract = GeminiProvider(client, gson)
    private val deepSeekProvider: AIProviderContract = OpenAICompatibleProvider.deepSeek(client, gson)
    private val groqProvider: AIProviderContract = OpenAICompatibleProvider.groq(client, gson)
    private val openAIProvider: AIProviderContract = OpenAICompatibleProvider.openAI(client, gson)
    private val openRouterProvider: AIProviderContract = OpenRouterProvider(client, gson)
    private val anthropicProvider: AIProviderContract = AnthropicProvider(client, gson)
    private val huggingFaceProvider: AIProviderContract = HuggingFaceProvider(client, gson)

    /**
     * Get the provider instance for an AIProvider enum value.
     */
    fun getProvider(provider: AIProvider): AIProviderContract {
        return when (provider) {
            AIProvider.GEMINI -> geminiProvider
            AIProvider.DEEPSEEK -> deepSeekProvider
            AIProvider.GROQ -> groqProvider
            AIProvider.OPENAI -> openAIProvider
            AIProvider.ANTHROPIC -> anthropicProvider
            AIProvider.OPENROUTER -> openRouterProvider
            AIProvider.HUGGINGFACE -> huggingFaceProvider
        }
    }

    /**
     * Get the selected model for a provider from SecurePreferences.
     */
    fun getModelForProvider(provider: AIProvider): String {
        return securePreferences.getSelectedModel(provider)
    }

    /**
     * Get all provider configurations.
     */
    fun getAllProviderConfigs(): Map<AIProvider, AIProviderConfig> {
        return securePreferences.getAllProviderConfigs()
    }

    /**
     * Get ordered list of providers to try based on user priority.
     */
    fun getOrderedProviders(): List<AIProvider> {
        return ProviderPriorityResolver.getOrderedProviders(
            securePreferences.getProviderPriority()
        )
    }

    /**
     * Execute an action with content analysis retry logic.
     * Tries keys in rotation order (2,3,4... then 1 as fallback).
     *
     * @param provider The AI provider to use
     * @param config Provider configuration with API keys
     * @param action The action to execute with each key
     * @return Successful AIResponse or null if all attempts failed
     */
    suspend fun executeWithContentAnalysisRetry(
        provider: AIProvider,
        config: AIProviderConfig,
        action: suspend (apiKey: String) -> AIResponse?
    ): AIResponse? {
        val keysToTry = ApiKeyRotator.getRotatedKeysWithAgentFallback(config.apiKeys)
        Log.i(TAG, "Attempting $provider with ${keysToTry.size} key(s)")

        for (apiKey in keysToTry) {
            val keyLabel = ApiKeyRotator.getKeyLabel(config.apiKeys, apiKey)
            Log.d(TAG, "Trying $provider $keyLabel")

            val result = RetryExecutor.withRetry(
                maxRetries = MAX_RETRIES,
                initialDelayMs = INITIAL_RETRY_DELAY_MS,
                successCheck = { it?.success == true }
            ) {
                action(apiKey)
            }

            if (result != null && result.success) {
                Log.i(TAG, "✓ $provider SUCCESS ($keyLabel): category=${result.category}")
                return result
            }
        }

        Log.w(TAG, "All keys failed for $provider")
        return null
    }

    /**
     * Execute an action with document analysis retry logic.
     *
     * @param provider The AI provider to use
     * @param config Provider configuration with API keys
     * @param action The action to execute with each key
     * @return Successful DocumentAnalysisResponse or null if all attempts failed
     */
    suspend fun executeWithDocumentAnalysisRetry(
        provider: AIProvider,
        config: AIProviderConfig,
        action: suspend (apiKey: String) -> DocumentAnalysisResponse?
    ): DocumentAnalysisResponse? {
        val keysToTry = ApiKeyRotator.getRotatedKeysWithAgentFallback(config.apiKeys)
        Log.i(TAG, "Attempting document analysis with $provider (${keysToTry.size} keys)")

        for (apiKey in keysToTry) {
            val keyLabel = ApiKeyRotator.getKeyLabel(config.apiKeys, apiKey)
            Log.d(TAG, "Trying $provider $keyLabel for document analysis")

            val result = RetryExecutor.withRetry(
                maxRetries = MAX_RETRIES,
                initialDelayMs = INITIAL_RETRY_DELAY_MS,
                successCheck = { it?.success == true }
            ) {
                action(apiKey)
            }

            if (result != null && result.success) {
                Log.i(TAG, "✓ Document analysis SUCCESS via $provider: ${result.title}")
                return result
            }
        }

        Log.w(TAG, "All $provider keys failed for document analysis")
        return null
    }

    /**
     * Execute a chat action using normal keys (excludes agent key 1).
     *
     * @param provider The AI provider to use
     * @param config Provider configuration with API keys
     * @param action The action to execute with each key
     * @return Successful response string or null if all attempts failed
     */
    suspend fun executeWithNormalKeys(
        provider: AIProvider,
        config: AIProviderConfig,
        action: suspend (apiKey: String) -> String?
    ): String? {
        val normalKeys = ApiKeyRotator.getNormalKeysOnly(config.apiKeys)
        if (ApiKeyRotator.isSharedKeyMode(config.apiKeys)) {
            Log.w(TAG, "$provider has only 1 key - must share with agent")
        }

        Log.i(TAG, "$provider: Using ${normalKeys.size} keys for normal chat")

        for (apiKey in normalKeys) {
            val keyLabel = ApiKeyRotator.getKeyLabel(config.apiKeys, apiKey)
            Log.i(TAG, "Attempting normal chat with $provider ($keyLabel)")

            val result = RetryExecutor.withStringRetry(
                maxRetries = MAX_RETRIES,
                initialDelayMs = INITIAL_RETRY_DELAY_MS
            ) {
                action(apiKey)
            }

            if (result != null) {
                Log.i(TAG, "✓ Normal chat SUCCESS via $provider ($keyLabel)")
                return result
            }

            Log.w(TAG, "$provider $keyLabel failed, trying next...")
        }

        Log.w(TAG, "All normal keys failed for $provider")
        return null
    }

    /**
     * Execute a chat action using the dedicated agent key (key 1).
     *
     * @param provider The AI provider to use
     * @param config Provider configuration with API keys
     * @param action The action to execute with agent key
     * @return Successful response string or null if failed
     */
    suspend fun executeWithAgentKey(
        provider: AIProvider,
        config: AIProviderConfig,
        action: suspend (apiKey: String) -> String?
    ): String? {
        val agentKey = ApiKeyRotator.getAgentKey(config.apiKeys) ?: return null
        Log.i(TAG, "$provider: Using KEY-1 (dedicated agent key)")

        val result = RetryExecutor.withStringRetry(
            maxRetries = MAX_RETRIES,
            initialDelayMs = INITIAL_RETRY_DELAY_MS
        ) {
            action(agentKey)
        }

        if (result != null) {
            Log.i(TAG, "✓ Agent operation SUCCESS via $provider")
            return result
        }

        Log.w(TAG, "$provider agent key failed")
        return null
    }

    /**
     * Check if a provider is available (enabled with keys).
     */
    fun isProviderAvailable(config: AIProviderConfig?): Boolean {
        return config != null && config.isEnabled && config.apiKeys.isNotEmpty()
    }
}
