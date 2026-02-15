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
 * Thin Client Version: Connects to remote Smarty Server. Cloud connections are managed by the server.
 *
 * Responsibilities:
 * - Manages server connection instance
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

    // Remote server connection - connects to Smarty Server for AI processing
    private var _serverConnection: CompatibleAIConnection? = null
    private var _serverUrl: String? = null
    private val serverConnection: AIConnectionContract
        get() {
            val currentUrl = securePreferences.getServerUrl()
            if (_serverConnection == null || _serverUrl != currentUrl) {
                _serverUrl = currentUrl
                _serverConnection = CompatibleAIConnection.localPC(HttpClientProvider.default, gson, currentUrl)
            }
            return _serverConnection!!
        }

    /**
     * Get the connection instance for an AIConnection enum value.
     */
    fun getConnection(connection: AIConnection): AIConnectionContract {
        return when (connection) {
            AIConnection.LOCAL_PC -> serverConnection
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
     * Thin Client: Only returns server connection if enabled.
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
     * Thin Client: Uses server connection.
     */
    suspend fun executeWithContentAnalysisRetry(
        context: android.content.Context,
        action: suspend (connectionToken: String) -> AIResponse?
    ): AIResponse? {
        val connection = AIConnection.LOCAL_PC

        // Skip if connection circuit is open
        if (!failoverManager.isConnectionAvailable(connection)) {
            Log.d(TAG, "Skipping server connection - circuit is open")
            return null
        }

        // Server connection doesn't need API keys on client side
        val connectionToken = "server_no_token_needed"

        try {
            val result = RetryExecutor.withRetry(
                maxRetries = MAX_RETRIES,
                initialDelayMs = INITIAL_RETRY_DELAY_MS,
                successCheck = { it?.success == true }
            ) {
                action(connectionToken)
            }

            if (result != null && result.success) {
                Log.i(TAG, " Server connection SUCCESS")
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
            Log.w(TAG, "Server connection failed: ${e.message} [$category]")
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
            Log.d(TAG, "Skipping server for document analysis - circuit is open")
            return null
        }

        val connectionToken = "server_no_token_needed"

        try {
            val result = RetryExecutor.withRetry(
                maxRetries = MAX_RETRIES,
                initialDelayMs = INITIAL_RETRY_DELAY_MS,
                successCheck = { it?.success == true }
            ) {
                action(connectionToken)
            }

            if (result != null && result.success) {
                Log.i(TAG, " Document analysis SUCCESS via server")
                failoverManager.recordSuccess(connection)
                return result
            }

            if (result?.error != null) {
                failoverManager.recordFailure(connection, ApiErrorCategory.SERVER_ERROR)
            }
        } catch (e: Exception) {
            val category = failoverManager.categorizeError(e)
            Log.w(TAG, "Server document analysis failed: ${e.message} [$category]")
            failoverManager.recordFailure(connection, category)
        }

        return null
    }

    /**
     * Execute a chat action.
     * Thin Client: All chat operations use the server connection.
     */
    suspend fun executeChat(
        context: android.content.Context,
        action: suspend (connectionToken: String) -> String?
    ): String? {
        val connection = AIConnection.LOCAL_PC

        // Skip if connection circuit is open
        if (!failoverManager.isConnectionAvailable(connection)) {
            Log.d(TAG, "Skipping server for chat - circuit is open")
            return null
        }

        val connectionToken = "server_no_token_needed"

        try {
            val result = RetryExecutor.withStringRetry(
                maxRetries = MAX_RETRIES,
                initialDelayMs = INITIAL_RETRY_DELAY_MS
            ) {
                action(connectionToken)
            }

            if (result != null) {
                Log.i(TAG, " Chat SUCCESS via server")
                failoverManager.recordSuccess(connection)
                return result
            }
        } catch (e: Exception) {
            val category = failoverManager.categorizeError(e)
            Log.w(TAG, "Server chat failed: ${e.message} [$category]")
            failoverManager.recordFailure(connection, category)
        }

        return null
    }
}
