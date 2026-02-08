package com.example.smarty.data.remote

import android.util.Log
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.AIProviderConfig
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.remote.providers.AIProviderContract
import com.example.smarty.data.remote.providers.OpenAICompatibleProvider
import com.example.smarty.util.api.ApiErrorCategory
import com.example.smarty.util.api.ApiMetrics
import com.example.smarty.util.api.ProviderFailoverManager
import com.example.smarty.util.HttpClientProvider
import com.example.smarty.util.retry.RetryExecutor
import com.google.gson.Gson

/**
 * Orchestrates AI provider selection, configuration, and fallback logic.
 * Thin Client Version: Only handles LOCAL_PC. Cloud providers are managed by the server.
 *
 * Responsibilities:
 * - Manages LOCAL_PC provider instance
 * - Executes operations with retry logic
 *
 * @property securePreferences Secure storage for settings
 */
class AIProviderOrchestrator(private val securePreferences: SecurePreferences) {

    companion object {
        private const val TAG = "AIProviderOrchestrator"
        const val MAX_RETRIES = 3
        const val INITIAL_RETRY_DELAY_MS = 1000L
    }

    val gson = Gson()

    // Use shared singleton to prevent resource leaks
    val client = HttpClientProvider.default

    // Failover manager for health tracking
    private val failoverManager = ProviderFailoverManager.getInstance()

    // ==================== Provider Instances ====================

    // Local LLM provider - connects to your PC via USB/WiFi for privacy and offline use
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
            AIProvider.LOCAL_PC -> localPCProvider
            else -> throw IllegalArgumentException("Provider $provider is not supported on the client (Thin Client)")
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
     * Thin Client: Only returns LOCAL_PC if enabled.
     */
    fun getOrderedProviders(): List<AIProvider> {
        if (securePreferences.isLocalPCEnabled() && failoverManager.isProviderAvailable(AIProvider.LOCAL_PC)) {
            return listOf(AIProvider.LOCAL_PC)
        }
        return emptyList()
    }

    /**
     * Get ALL providers in priority order.
     */
    fun getAllProvidersInOrder(): List<AIProvider> {
        return listOf(AIProvider.LOCAL_PC)
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
     * Reset health state for a provider.
     */
    fun resetProviderHealth(provider: AIProvider) {
        failoverManager.resetProvider(provider)
    }

    /**
     * Execute an action with content analysis retry logic.
     *
     * @param provider The AI provider to use
     * @param config Provider configuration
     * @param action The action to execute
     * @return Successful AIResponse or null if all attempts failed
     */
    suspend fun executeWithContentAnalysisRetry(
        context: android.content.Context,
        provider: AIProvider,
        config: AIProviderConfig,
        action: suspend (apiKey: String) -> AIResponse?
    ): AIResponse? {
        if (provider != AIProvider.LOCAL_PC) return null

        // Skip if provider circuit is open
        if (!failoverManager.isProviderAvailable(provider)) {
            Log.d(TAG, "Skipping $provider - circuit is open")
            return null
        }

        // LOCAL_PC doesn't need API keys
        val apiKey = "local_pc_no_key_needed"

        try {
            val result = RetryExecutor.withRetry(
                maxRetries = MAX_RETRIES,
                initialDelayMs = INITIAL_RETRY_DELAY_MS,
                successCheck = { it?.success == true }
            ) {
                action(apiKey)
            }

            if (result != null && result.success) {
                Log.i(TAG, " $provider SUCCESS")
                ApiMetrics.recordApiCall(true)
                failoverManager.recordSuccess(provider)
                return result
            }

            ApiMetrics.recordApiCall(false)
            if (result?.error != null) {
                val category = categorizeErrorMessage(result.error)
                failoverManager.recordFailure(provider, category)
            }
        } catch (e: Exception) {
            ApiMetrics.recordApiCall(false)
            val category = failoverManager.categorizeError(e)
            Log.w(TAG, "$provider failed: ${e.message} [$category]")
            failoverManager.recordFailure(provider, category)
        }

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
     * Execute an action with document analysis retry logic.
     */
    suspend fun executeWithDocumentAnalysisRetry(
        context: android.content.Context,
        provider: AIProvider,
        config: AIProviderConfig,
        action: suspend (apiKey: String) -> DocumentAnalysisResponse?
    ): DocumentAnalysisResponse? {
        if (provider != AIProvider.LOCAL_PC) return null

        // Skip if provider circuit is open
        if (!failoverManager.isProviderAvailable(provider)) {
            Log.d(TAG, "Skipping $provider for document analysis - circuit is open")
            return null
        }

        val apiKey = "local_pc_no_key_needed"

        try {
            val result = RetryExecutor.withRetry(
                maxRetries = MAX_RETRIES,
                initialDelayMs = INITIAL_RETRY_DELAY_MS,
                successCheck = { it?.success == true }
            ) {
                action(apiKey)
            }

            if (result != null && result.success) {
                Log.i(TAG, " Document analysis SUCCESS via $provider")
                failoverManager.recordSuccess(provider)
                return result
            }

            if (result?.error != null) {
                val category = categorizeErrorMessage(result.error)
                failoverManager.recordFailure(provider, category)
            }
        } catch (e: Exception) {
            val category = failoverManager.categorizeError(e)
            Log.w(TAG, "$provider document analysis failed: ${e.message} [$category]")
            failoverManager.recordFailure(provider, category)
        }

        return null
    }

    /**
     * Execute a chat action using normal keys.
     */
    suspend fun executeWithNormalKeys(
        context: android.content.Context,
        provider: AIProvider,
        config: AIProviderConfig,
        action: suspend (apiKey: String) -> String?
    ): String? {
        if (provider != AIProvider.LOCAL_PC) return null

        // Skip if provider circuit is open
        if (!failoverManager.isProviderAvailable(provider)) {
            Log.d(TAG, "Skipping $provider for normal chat - circuit is open")
            return null
        }

        val apiKey = "local_pc_no_key_needed"

        try {
            val result = RetryExecutor.withStringRetry(
                maxRetries = MAX_RETRIES,
                initialDelayMs = INITIAL_RETRY_DELAY_MS
            ) {
                action(apiKey)
            }

            if (result != null) {
                Log.i(TAG, " Normal chat SUCCESS via $provider")
                failoverManager.recordSuccess(provider)
                return result
            }
        } catch (e: Exception) {
            val category = failoverManager.categorizeError(e)
            Log.w(TAG, "$provider chat failed: ${e.message} [$category]")
            failoverManager.recordFailure(provider, category)
        }

        return null
    }

    /**
     * Execute a chat action using the dedicated agent key.
     */
    suspend fun executeWithAgentKey(
        context: android.content.Context,
        provider: AIProvider,
        config: AIProviderConfig,
        action: suspend (apiKey: String) -> String?
    ): String? {
        // Local PC is the same for agent and normal
        return executeWithNormalKeys(context, provider, config, action)
    }

    /**
     * Check if a provider is available.
     */
    fun isProviderAvailable(config: AIProviderConfig?): Boolean {
        if (config == null || !config.isEnabled) return false
        return config.provider == AIProvider.LOCAL_PC
    }
}
