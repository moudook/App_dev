package com.example.smarty.data.remote

import android.util.Log
import com.example.smarty.data.local.AIConnection
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.remote.providers.AIConnectionContract
import com.example.smarty.data.remote.providers.CompatibleAIConnection
import com.example.smarty.core.common.util.api.ApiErrorCategory
import com.example.smarty.core.common.util.api.ApiMetrics
import com.example.smarty.core.common.util.api.ConnectionFailoverManager
import com.example.smarty.core.common.util.HttpClientProvider
import com.example.smarty.core.common.util.retry.RetryExecutor
import com.google.gson.Gson

/**
 * Orchestrates AI connection selection, configuration, and fallback logic.
 * Thin Client Version: Only handles LOCAL_PC. Cloud connections are managed by the server.
 *
 * Responsibilities:
 * - Manages LOCAL_PC connection instance
 * - Executes operations with retry logic
 *
 * @property securePreferences Secure storage for settings
 */
class AIConnectionOrchestrator(private val securePreferences: SecurePreferences) {

    companion object {
        private const val TAG = "AIConnectionOrchestrator"
        const val MAX_RETRIES = 3
        const val INITIAL_RETRY_DELAY_MS = 1000L
    }

    val gson = Gson()

    // Use shared singleton to prevent resource leaks
    val client = HttpClientProvider.default

    // Failover manager for health tracking
    private val failoverManager = ConnectionFailoverManager.getInstance()

    // ==================== Connection Instances ====================

    // Local LLM connection - connects to your PC via USB/WiFi for privacy and offline use
    private var _localPCConnection: CompatibleAIConnection? = null
    private var _localPCUrl: String? = null
    private val localPCConnection: AIConnectionContract
        get() {
            val currentUrl = securePreferences.getLocalPCUrl()
            if (_localPCConnection == null || _localPCUrl != currentUrl) {
                _localPCUrl = currentUrl
                // Use localServer client that trusts self-signed certificates for HTTPS
                _localPCConnection = CompatibleAIConnection.localPC(HttpClientProvider.localServer, gson, currentUrl)
            }
            return _localPCConnection!!
        }

    /**
     * Get the connection instance for an AIConnection enum value.
     */
    fun getConnection(connection: AIConnection): AIConnectionContract {
        return when (connection) {
            AIConnection.LOCAL_PC -> localPCConnection
            else -> throw IllegalArgumentException("Connection $connection is not supported on the client (Thin Client)")
        }
    }

    /**
     * Get the selected model for a connection from SecurePreferences.
     */
    fun getModelForConnection(connection: AIConnection): String {
        return securePreferences.getSelectedModel(connection)
    }

    /**
     * Get ordered list of connections to try based on user priority.
     * Thin Client: Only returns LOCAL_PC if enabled.
     */
    fun getOrderedConnections(): List<AIConnection> {
        if (securePreferences.isLocalPCEnabled() && failoverManager.isConnectionAvailable(AIConnection.LOCAL_PC)) {
            return listOf(AIConnection.LOCAL_PC)
        }
        return emptyList()
    }

    /**
     * Get ALL connections in priority order.
     */
    fun getAllConnectionsInOrder(): List<AIConnection> {
        return listOf(AIConnection.LOCAL_PC)
    }

    /**
     * Record a successful API call for a connection.
     */
    fun recordSuccess(connection: AIConnection) {
        failoverManager.recordSuccess(connection)
    }

    /**
     * Record a failed API call for a connection.
     */
    fun recordFailure(connection: AIConnection, exception: Exception) {
        failoverManager.recordFailure(connection, exception)
    }

    /**
     * Record a failed API call with known error category.
     */
    fun recordFailure(connection: AIConnection, category: ApiErrorCategory) {
        failoverManager.recordFailure(connection, category)
    }

    /**
     * Check if a connection is currently healthy and available.
     */
    fun isConnectionHealthy(connection: AIConnection): Boolean {
        return failoverManager.isConnectionAvailable(connection)
    }

    /**
     * Reset health state for a connection.
     */
    fun resetConnectionHealth(connection: AIConnection) {
        failoverManager.resetConnection(connection)
    }

    /**
     * Execute an action with content analysis retry logic.
     * Thin Client: Only supports LOCAL_PC.
     */
    suspend fun executeWithContentAnalysisRetry(
        context: android.content.Context,
        action: suspend (connectionToken: String) -> AIResponse?
    ): AIResponse? {
        val connection = AIConnection.LOCAL_PC

        // Skip if connection circuit is open
        if (!failoverManager.isConnectionAvailable(connection)) {
            Log.d(TAG, "Skipping LOCAL_PC - circuit is open")
            return null
        }

        // LOCAL_PC doesn't need API keys, using connectionToken terminology
        val connectionToken = "local_pc_no_token_needed"

        try {
            val result = RetryExecutor.withRetry(
                maxRetries = MAX_RETRIES,
                initialDelayMs = INITIAL_RETRY_DELAY_MS,
                successCheck = { it?.success == true }
            ) {
                action(connectionToken)
            }

            if (result != null && result.success) {
                Log.i(TAG, " LOCAL_PC SUCCESS")
                ApiMetrics.recordApiCall(true)
                failoverManager.recordSuccess(connection)
                return result
            }

            ApiMetrics.recordApiCall(false)
            if (result?.error != null) {
                failoverManager.recordFailure(connection, ApiErrorCategory.SERVER_ERROR)
            }
        } catch (e: Exception) {
            ApiMetrics.recordApiCall(false)
            val category = failoverManager.categorizeError(e)
            Log.w(TAG, "LOCAL_PC failed: ${e.message} [$category]")
            failoverManager.recordFailure(connection, category)
        }

        return null
    }

    /**
     * Execute an action with document analysis retry logic.
     */
    suspend fun executeWithDocumentAnalysisRetry(
        context: android.content.Context,
        action: suspend (connectionToken: String) -> DocumentAnalysisResponse?
    ): DocumentAnalysisResponse? {
        val connection = AIConnection.LOCAL_PC

        // Skip if connection circuit is open
        if (!failoverManager.isConnectionAvailable(connection)) {
            Log.d(TAG, "Skipping LOCAL_PC for document analysis - circuit is open")
            return null
        }

        val connectionToken = "local_pc_no_token_needed"

        try {
            val result = RetryExecutor.withRetry(
                maxRetries = MAX_RETRIES,
                initialDelayMs = INITIAL_RETRY_DELAY_MS,
                successCheck = { it?.success == true }
            ) {
                action(connectionToken)
            }

            if (result != null && result.success) {
                Log.i(TAG, " Document analysis SUCCESS via LOCAL_PC")
                failoverManager.recordSuccess(connection)
                return result
            }

            if (result?.error != null) {
                failoverManager.recordFailure(connection, ApiErrorCategory.SERVER_ERROR)
            }
        } catch (e: Exception) {
            val category = failoverManager.categorizeError(e)
            Log.w(TAG, "LOCAL_PC document analysis failed: ${e.message} [$category]")
            failoverManager.recordFailure(connection, category)
        }

        return null
    }

    /**
     * Execute a chat action.
     * Thin Client: All chat operations use the LOCAL_PC connection.
     */
    suspend fun executeChat(
        context: android.content.Context,
        action: suspend (connectionToken: String) -> String?
    ): String? {
        val connection = AIConnection.LOCAL_PC

        // Skip if connection circuit is open
        if (!failoverManager.isConnectionAvailable(connection)) {
            Log.d(TAG, "Skipping LOCAL_PC for chat - circuit is open")
            return null
        }

        val connectionToken = "local_pc_no_token_needed"

        try {
            val result = RetryExecutor.withStringRetry(
                maxRetries = MAX_RETRIES,
                initialDelayMs = INITIAL_RETRY_DELAY_MS
            ) {
                action(connectionToken)
            }

            if (result != null) {
                Log.i(TAG, " Chat SUCCESS via LOCAL_PC")
                failoverManager.recordSuccess(connection)
                return result
            }
        } catch (e: Exception) {
            val category = failoverManager.categorizeError(e)
            Log.w(TAG, "LOCAL_PC chat failed: ${e.message} [$category]")
            failoverManager.recordFailure(connection, category)
        }

        return null
    }
}
