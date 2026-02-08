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
import com.example.smarty.util.api.ApiErrorCategory
import com.example.smarty.util.api.ApiKeyRotator
import com.example.smarty.util.api.ApiMetrics
import com.example.smarty.util.api.ProviderFailoverManager
import com.example.smarty.util.api.ProviderPriorityResolver
import com.example.smarty.util.HttpClientProvider
import com.example.smarty.util.retry.RetryExecutor
import com.google.gson.Gson

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

    // Use shared singleton to prevent resource leaks (connection pool exhaustion, thread leaks)
    // Uses standardized timeouts: connect=30s, read=90s, write=60s
    val client = HttpClientProvider.default

    // Failover manager for circuit breaker and health tracking
    private val failoverManager = ProviderFailoverManager.getInstance()

    // ==================== Provider Instances ====================

    private val geminiProvider: AIProviderContract = GeminiProvider(client, gson)
    private val deepSeekProvider: AIProviderContract = OpenAICompatibleProvider.deepSeek(client, gson)
    private val groqProvider: AIProviderContract = OpenAICompatibleProvider.groq(client, gson)
    private val cerebrasProvider: AIProviderContract = OpenAICompatibleProvider.cerebras(client, gson)
    private val cohereProvider: AIProviderContract = OpenAICompatibleProvider.cohere(client, gson)
    private val openAIProvider: AIProviderContract = OpenAICompatibleProvider.openAI(client, gson)
    private val openRouterProvider: AIProviderContract = OpenRouterProvider(client, gson)
    private val anthropicProvider: AIProviderContract = AnthropicProvider(client, gson)
    private val huggingFaceProvider: AIProviderContract = HuggingFaceProvider(client, gson)
    private val githubProvider: AIProviderContract = OpenAICompatibleProvider.github(client, gson)
    // Local LLM provider - connects to your PC via USB/WiFi for privacy and offline use
    // Cached instance - recreated only when URL changes
    // Uses localServer client that trusts self-signed certificates for HTTPS
    private var _localPCProvider: OpenAICompatibleProvider? = null
    private var _localPCUrl: String? = null
    private val localPCProvider: AIProviderContract
        get() {
            val currentUrl = securePreferences.getLocalPCUrl()
            if (_localPCProvider == null || _localPCUrl != currentUrl) {
                _localPCUrl = currentUrl
                // Use localServer client that trusts self-signed certificates for HTTPS
                _localPCProvider = OpenAICompatibleProvider.localPC(HttpClientProvider.localServer, gson, currentUrl)
            }
            return _localPCProvider!!
        }

    /**
     * Get the provider instance for an AIProvider enum value.
     */
    fun getProvider(provider: AIProvider): AIProviderContract {
        return when (provider) {
            AIProvider.GEMINI -> geminiProvider
            AIProvider.DEEPSEEK -> deepSeekProvider
            AIProvider.GROQ -> groqProvider
            AIProvider.CEREBRAS -> cerebrasProvider
            AIProvider.COHERE -> cohereProvider
            AIProvider.OPENAI -> openAIProvider
            AIProvider.ANTHROPIC -> anthropicProvider
            AIProvider.OPENROUTER -> openRouterProvider
            AIProvider.HUGGINGFACE -> huggingFaceProvider
            AIProvider.GITHUB -> githubProvider
            AIProvider.LOCAL_PC -> localPCProvider
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
     * Filters out providers with open circuits (temporarily failed).
     */
    fun getOrderedProviders(): List<AIProvider> {
        val userPriority = ProviderPriorityResolver.getOrderedProviders(
            securePreferences.getProviderPriority()
        )
        // 1. Filter and reorder based on health status (circuit breaker)
        val healthy = failoverManager.getOrderedHealthyProviders(userPriority)
        
        // 2. Filter by user's "Enabled" toggle and mandatory key presence
        val configs = getAllProviderConfigs()
        return healthy.filter { isProviderAvailable(configs[it]) }
    }

    /**
     * Get ALL providers in priority order (including circuit-open ones).
     * Use this for displaying status, not for making requests.
     */
    fun getAllProvidersInOrder(): List<AIProvider> {
        return ProviderPriorityResolver.getOrderedProviders(
            securePreferences.getProviderPriority()
        )
    }

    /**
     * Record a successful API call for a provider.
     * Updates health status and potentially closes circuit.
     */
    fun recordSuccess(provider: AIProvider) {
        failoverManager.recordSuccess(provider)
    }

    /**
     * Record a failed API call for a provider.
     * Updates health status and potentially opens circuit.
     */
    fun recordFailure(provider: AIProvider, exception: Exception) {
        failoverManager.recordFailure(provider, exception)
    }

    /**
     * Record a failed API call with known error category.
     */
    fun recordFailure(provider: AIProvider, category: ApiErrorCategory) {
        failoverManager.recordFailure(provider, category)
    }

    /**
     * Check if a provider is currently healthy and available.
     */
    fun isProviderHealthy(provider: AIProvider): Boolean {
        return failoverManager.isProviderAvailable(provider)
    }

    /**
     * Reset health state for a provider (call when user updates API key).
     */
    fun resetProviderHealth(provider: AIProvider) {
        failoverManager.resetProvider(provider)
    }

    /**
     * Execute an action with content analysis retry logic and failover tracking.
     * Tries keys in rotation order (2,3,4... then 1 as fallback).
     * Records success/failure with ProviderFailoverManager.
     *
     * @param provider The AI provider to use
     * @param config Provider configuration with API keys
     * @param action The action to execute with each key
     * @return Successful AIResponse or null if all attempts failed
     */
    suspend fun executeWithContentAnalysisRetry(
        context: android.content.Context,
        provider: AIProvider,
        config: AIProviderConfig,
        action: suspend (apiKey: String) -> AIResponse?
    ): AIResponse? {
        // Skip if provider circuit is open
        if (!failoverManager.isProviderAvailable(provider)) {
            Log.d(TAG, "Skipping $provider - circuit is open")
            return null
        }

        // LOCAL_PC doesn't need API keys - use dummy key
        val keysToTry = if (provider == AIProvider.LOCAL_PC) {
            listOf("local_pc_no_key_needed")
        } else {
            ApiKeyRotator.getRotatedKeysWithBusyAwareness(
                ApiKeyRotator.getRotatedKeysWithAgentFallback(config.apiKeys)
            ).filter { !failoverManager.isKeyFailed(it) } // Skip known bad keys
        }

        if (keysToTry.isEmpty()) {
            Log.w(TAG, "$provider has no healthy keys available")
            return null
        }

        Log.i(TAG, "Attempting $provider with ${keysToTry.size} key(s)")

        var lastException: Exception? = null

        for (apiKey in keysToTry) {
            val keyLabel = ApiKeyRotator.getKeyLabel(config.apiKeys, apiKey)
            Log.d(TAG, "Trying $provider $keyLabel")

            try {
                // Mark key as busy before use
                ApiKeyRotator.markKeyBusy(apiKey)

                val result = RetryExecutor.withRetry(
                    maxRetries = MAX_RETRIES,
                    initialDelayMs = INITIAL_RETRY_DELAY_MS,
                    successCheck = { it?.success == true }
                ) {
                    action(apiKey)
                }

                if (result != null && result.success) {
                    Log.i(TAG, " $provider SUCCESS ($keyLabel): category=${result.category}")
                    ApiKeyRotator.markKeyAvailable(apiKey)
                    ApiMetrics.recordApiCall(true)
                    failoverManager.recordSuccess(provider)
                    return result
                }

                // Result was null or not successful
                ApiKeyRotator.markKeyAvailable(apiKey)
                ApiMetrics.recordApiCall(false)
                if (result?.error != null) {
                    val category = categorizeErrorMessage(result.error)
                    if (failoverManager.shouldTryDifferentKey(category)) {
                        failoverManager.recordKeyFailure(apiKey, category)
                    }
                }
            } catch (e: Exception) {
                lastException = e
                ApiKeyRotator.markKeyAvailable(apiKey)
                ApiMetrics.recordApiCall(false)
                val category = failoverManager.categorizeError(e)
                Log.w(TAG, "$provider $keyLabel failed: ${e.message} [$category]")

                if (failoverManager.shouldTryDifferentKey(category)) {
                    failoverManager.recordKeyFailure(apiKey, category)
                }
            }
        }

        // All keys failed for this provider
        Log.w(TAG, "All keys failed for $provider")
        lastException?.let { failoverManager.recordFailure(provider, it) }
            ?: failoverManager.recordFailure(provider, ApiErrorCategory.UNKNOWN)

        return null
    }

    /**
     * Categorize error from error message string.
     */
    private fun categorizeErrorMessage(errorMessage: String?): ApiErrorCategory {
        if (errorMessage == null) return ApiErrorCategory.UNKNOWN
        val msg = errorMessage.lowercase()
        return when {
            msg.contains("401") || msg.contains("403") || msg.contains("auth") -> ApiErrorCategory.AUTH_ERROR
            msg.contains("429") || msg.contains("rate") || msg.contains("quota") -> ApiErrorCategory.RATE_LIMIT
            msg.contains("500") || msg.contains("502") || msg.contains("503") -> ApiErrorCategory.SERVER_ERROR
            msg.contains("timeout") || msg.contains("network") -> ApiErrorCategory.NETWORK_ERROR
            msg.contains("model") || msg.contains("not found") -> ApiErrorCategory.MODEL_ERROR
            msg.contains("context") || msg.contains("token") -> ApiErrorCategory.CONTEXT_OVERFLOW
            else -> ApiErrorCategory.UNKNOWN
        }
    }

    /**
     * Execute an action with document analysis retry logic and failover tracking.
     *
     * @param context Android context for localization
     * @param provider The AI provider to use
     * @param config Provider configuration with API keys
     * @param action The action to execute with each key
     * @return Successful DocumentAnalysisResponse or null if all attempts failed
     */
    suspend fun executeWithDocumentAnalysisRetry(
        context: android.content.Context,
        provider: AIProvider,
        config: AIProviderConfig,
        action: suspend (apiKey: String) -> DocumentAnalysisResponse?
    ): DocumentAnalysisResponse? {
        // Skip if provider circuit is open
        if (!failoverManager.isProviderAvailable(provider)) {
            Log.d(TAG, "Skipping $provider for document analysis - circuit is open")
            return null
        }

        // LOCAL_PC doesn't need API keys - use dummy key
        val keysToTry = if (provider == AIProvider.LOCAL_PC) {
            listOf("local_pc_no_key_needed")
        } else {
            ApiKeyRotator.getRotatedKeysWithAgentFallback(config.apiKeys)
                .filter { !failoverManager.isKeyFailed(it) }
        }

        if (keysToTry.isEmpty()) {
            Log.w(TAG, "$provider has no healthy keys for document analysis")
            return null
        }

        Log.i(TAG, "Attempting document analysis with $provider (${keysToTry.size} keys)")

        var lastException: Exception? = null

        for (apiKey in keysToTry) {
            val keyLabel = ApiKeyRotator.getKeyLabel(config.apiKeys, apiKey)
            Log.d(TAG, "Trying $provider $keyLabel for document analysis")

            try {
                val result = RetryExecutor.withRetry(
                    maxRetries = MAX_RETRIES,
                    initialDelayMs = INITIAL_RETRY_DELAY_MS,
                    successCheck = { it?.success == true }
                ) {
                    action(apiKey)
                }

                if (result != null && result.success) {
                    Log.i(TAG, " Document analysis SUCCESS via $provider: ${result.title}")
                    failoverManager.recordSuccess(provider)
                    return result
                }

                if (result?.error != null) {
                    val category = categorizeErrorMessage(result.error)
                    if (failoverManager.shouldTryDifferentKey(category)) {
                        failoverManager.recordKeyFailure(apiKey, category)
                    }
                }
            } catch (e: Exception) {
                lastException = e
                val category = failoverManager.categorizeError(e)
                Log.w(TAG, "$provider $keyLabel document analysis failed: ${e.message} [$category]")

                if (failoverManager.shouldTryDifferentKey(category)) {
                    failoverManager.recordKeyFailure(apiKey, category)
                }
            }
        }

        Log.w(TAG, "All $provider keys failed for document analysis")
        lastException?.let { failoverManager.recordFailure(provider, it) }
            ?: failoverManager.recordFailure(provider, ApiErrorCategory.UNKNOWN)

        return null
    }

    /**
     * Execute a chat action using normal keys (excludes agent key 1).
     * Includes failover tracking.
     *
     * @param context Android context for localization
     * @param provider The AI provider to use
     * @param config Provider configuration with API keys
     * @param action The action to execute with each key
     * @return Successful response string or null if all attempts failed
     */
    suspend fun executeWithNormalKeys(
        context: android.content.Context,
        provider: AIProvider,
        config: AIProviderConfig,
        action: suspend (apiKey: String) -> String?
    ): String? {
        // Skip if provider circuit is open
        if (!failoverManager.isProviderAvailable(provider)) {
            Log.d(TAG, "Skipping $provider for normal chat - circuit is open")
            return null
        }

        // LOCAL_PC doesn't need API keys - use dummy key
        val normalKeys = if (provider == AIProvider.LOCAL_PC) {
            listOf("local_pc_no_key_needed")
        } else {
            ApiKeyRotator.getNormalKeysOnly(config.apiKeys)
                .filter { !failoverManager.isKeyFailed(it) }
        }

        if (normalKeys.isEmpty()) {
            Log.w(TAG, "$provider has no healthy normal keys available")
            return null
        }

        if (provider != AIProvider.LOCAL_PC && ApiKeyRotator.isSharedKeyMode(config.apiKeys)) {
            Log.w(TAG, "$provider has only 1 key - must share with agent")
        }

        Log.i(TAG, "$provider: Using ${normalKeys.size} keys for normal chat")

        var lastException: Exception? = null

        for (apiKey in normalKeys) {
            val keyLabel = ApiKeyRotator.getKeyLabel(config.apiKeys, apiKey)
            Log.i(TAG, "Attempting normal chat with $provider ($keyLabel)")

            try {
                val result = RetryExecutor.withStringRetry(
                    maxRetries = MAX_RETRIES,
                    initialDelayMs = INITIAL_RETRY_DELAY_MS
                ) {
                    action(apiKey)
                }

                if (result != null) {
                    Log.i(TAG, " Normal chat SUCCESS via $provider ($keyLabel)")
                    failoverManager.recordSuccess(provider)
                    return result
                }
            } catch (e: Exception) {
                lastException = e
                val category = failoverManager.categorizeError(e)
                Log.w(TAG, "$provider $keyLabel chat failed: ${e.message} [$category]")

                if (failoverManager.shouldTryDifferentKey(category)) {
                    failoverManager.recordKeyFailure(apiKey, category)
                }
            }

            Log.w(TAG, "$provider $keyLabel failed, trying next...")
        }

        Log.w(TAG, "All normal keys failed for $provider")
        lastException?.let { failoverManager.recordFailure(provider, it) }
            ?: failoverManager.recordFailure(provider, ApiErrorCategory.UNKNOWN)

        return null
    }

    /**
     * Execute a chat action using the dedicated agent key (key 1).
     * Includes failover tracking.
     *
     * @param context Android context for localization
     * @param provider The AI provider to use
     * @param config Provider configuration with API keys
     * @param action The action to execute with agent key
     * @return Successful response string or null if failed
     */
    suspend fun executeWithAgentKey(
        context: android.content.Context,
        provider: AIProvider,
        config: AIProviderConfig,
        action: suspend (apiKey: String) -> String?
    ): String? {
        // Skip if provider circuit is open
        if (!failoverManager.isProviderAvailable(provider)) {
            Log.d(TAG, "Skipping $provider for agent key - circuit is open")
            return null
        }

        // LOCAL_PC doesn't need API keys - use dummy key
        val agentKey = if (provider == AIProvider.LOCAL_PC) {
            "local_pc_no_key_needed"
        } else {
            ApiKeyRotator.getAgentKey(config.apiKeys) ?: return null
        }

        // Skip if this specific key is marked failed (not applicable for LOCAL_PC)
        if (provider != AIProvider.LOCAL_PC && failoverManager.isKeyFailed(agentKey)) {
            Log.d(TAG, "$provider agent key is temporarily failed")
            return null
        }

        Log.i(TAG, "$provider: Using ${if (provider == AIProvider.LOCAL_PC) "local server" else "KEY-1 (dedicated agent key)"}")

        try {
            val result = RetryExecutor.withStringRetry(
                maxRetries = MAX_RETRIES,
                initialDelayMs = INITIAL_RETRY_DELAY_MS
            ) {
                action(agentKey)
            }

            if (result != null) {
                Log.i(TAG, " Agent operation SUCCESS via $provider")
                failoverManager.recordSuccess(provider)
                return result
            }
        } catch (e: Exception) {
            val category = failoverManager.categorizeError(e)
            Log.w(TAG, "$provider agent key failed: ${e.message} [$category]")

            if (failoverManager.shouldTryDifferentKey(category)) {
                failoverManager.recordKeyFailure(agentKey, category)
            }
            failoverManager.recordFailure(provider, e)
        }

        Log.w(TAG, "$provider agent key failed")
        return null
    }

    /**
     * Check if a provider is available (enabled with keys).
     * LOCAL_PC doesn't require API keys (uses local server).
     */
    fun isProviderAvailable(config: AIProviderConfig?): Boolean {
        if (config == null || !config.isEnabled) return false
        // LOCAL_PC doesn't need API keys - it uses local server
        if (config.provider == AIProvider.LOCAL_PC) return true
        return config.apiKeys.isNotEmpty()
    }
}
