package com.example.smarty.viewmodel.managers

import android.util.Log
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.AIProviderConfig
import com.example.smarty.data.local.SecurePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import com.example.smarty.data.remote.AIService
import com.example.smarty.util.api.RateLimiter
import com.example.smarty.util.api.RateLimitStats

/**
 * Centralized manager for application settings and user preferences.
 * Hybridizes logic for:
 * - AI Provider configuration and key management
 * - Local LLM server connectivity (USB/WiFi)
 * - UI preferences (Theme, Shake sensitivity)
 * - App lifecycle flags (First launch)
 * - API rate limiting and key testing
 *
 * This manager ensures that UI settings screens and the AI Agent use the same preference source.
 */
class SettingsFeatureManager(
    private val securePreferences: SecurePreferences,
    private val aiService: AIService,
    private val rateLimiter: RateLimiter,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "SettingsFeatureManager"
    }

    // --- AI Provider State ---
    val geminiKeys: StateFlow<List<String>> = securePreferences.geminiKeys
    val huggingFaceKeys: StateFlow<List<String>> = securePreferences.huggingFaceKeys
    val providerConfigs: StateFlow<Map<AIProvider, AIProviderConfig>> = securePreferences.providerConfigs
    val providerPriorityOrder: StateFlow<List<AIProvider>> = securePreferences.providerPriorityOrder

    // --- Local Server State ---
    private val _isLocalPCEnabled = MutableStateFlow(securePreferences.isLocalPCEnabled())
    val isLocalPCEnabled: StateFlow<Boolean> = _isLocalPCEnabled.asStateFlow()

    private val _localServerIP = MutableStateFlow(securePreferences.getLocalPCIP())
    val localServerIP: StateFlow<String> = _localServerIP.asStateFlow()

    private val _localServerPort = MutableStateFlow(securePreferences.getLocalPCPort())
    val localServerPort: StateFlow<String> = _localServerPort.asStateFlow()

    private val _localServerUseHttps = MutableStateFlow(securePreferences.getLocalPCUseHttps())
    val localServerUseHttps: StateFlow<Boolean> = _localServerUseHttps.asStateFlow()

    // --- Tavily State ---
    private val _tavilyApiKey = MutableStateFlow(securePreferences.getTavilyApiKey())
    val tavilyApiKey: StateFlow<String?> = _tavilyApiKey.asStateFlow()

    private val _tavilyApiKeys = MutableStateFlow(securePreferences.getTavilyApiKeys())
    val tavilyApiKeys: StateFlow<List<String>> = _tavilyApiKeys.asStateFlow()

    private val _isTavilyEnabled = MutableStateFlow(securePreferences.isTavilyEnabled())
    val isTavilyEnabled: StateFlow<Boolean> = _isTavilyEnabled.asStateFlow()

    // --- UI/System Preferences ---
    val isDarkTheme: StateFlow<Boolean> = securePreferences.isDarkTheme

    // Map logic sensitivity (0.5..5.0) to UI range (0.0..1.0)
    // Logic 5.0 (Low) -> UI 0.0
    // Logic 0.5 (High) -> UI 1.0
    private fun mapLogicToUi(logic: Float): Float = ((5.0f - logic) / 4.5f).coerceIn(0f, 1f)

    // Map UI range (0.0..1.0) to logic sensitivity (5.0..0.5)
    private fun mapUiToLogic(ui: Float): Float = (5.0f - (ui * 4.5f)).coerceIn(0.5f, 5.0f)

    private val _shakeSensitivity = MutableStateFlow(mapLogicToUi(securePreferences.getShakeSensitivity()))
    val shakeSensitivity: StateFlow<Float> = _shakeSensitivity.asStateFlow()

    // --- AI Provider Actions ---
    fun setProviderPriority(priority: List<AIProvider>) {
        securePreferences.setProviderPriority(priority)
    }

    fun addProviderKey(provider: AIProvider, apiKey: String) {
        securePreferences.addProviderKey(provider, apiKey)
    }

    fun removeProviderKey(provider: AIProvider, apiKey: String) {
        securePreferences.removeProviderKey(provider, apiKey)
    }

    fun updateProviderKey(provider: AIProvider, oldKey: String, newKey: String) {
        securePreferences.updateProviderKey(provider, oldKey, newKey)
    }

    fun setProviderEnabled(provider: AIProvider, enabled: Boolean) {
        securePreferences.setProviderEnabled(provider, enabled)
    }

    fun setSelectedModel(provider: AIProvider, model: String) {
        securePreferences.setSelectedModel(provider, model)
    }

    fun getAvailableModels(provider: AIProvider): List<Pair<String, String>> {
        return securePreferences.getAvailableModels(provider)
    }

    fun isProviderEnabled(provider: AIProvider): Boolean {
        return securePreferences.isProviderEnabled(provider)
    }

    fun setDynamicModels(provider: AIProvider, models: List<Pair<String, String>>) {
        securePreferences.setDynamicModels(provider, models)
    }

    /**
     * Fetch available models from Groq API and update local preferences.
     */
    fun refreshGroqModels(onComplete: (Boolean) -> Unit = {}) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val apiKey = securePreferences.getProviderKeys(AIProvider.GROQ).firstOrNull()
            if (apiKey.isNullOrBlank()) {
                onComplete(false)
                return@launch
            }

            try {
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder()
                    .url("https://api.groq.com/openai/v1/models")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = org.json.JSONObject(body)
                        val data = json.getJSONArray("data")
                        val models = mutableListOf<Pair<String, String>>()

                        for (i in 0 until data.length()) {
                            val item = data.getJSONObject(i)
                            val id = item.getString("id")
                            val name = formatModelName(id)
                            models.add(id to name)
                        }

                        models.sortBy { it.second }

                        if (models.isNotEmpty()) {
                            securePreferences.setDynamicModels(AIProvider.GROQ, models)
                            // Force refresh
                            securePreferences.setProviderEnabled(AIProvider.GROQ, securePreferences.isProviderEnabled(AIProvider.GROQ))
                            onComplete(true)
                        } else onComplete(false)
                    } else {
                        Log.e(TAG, "Groq models fetch failed: ${response.code}")
                        onComplete(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching Groq models", e)
                onComplete(false)
            }
        }
    }

    private fun formatModelName(id: String): String {
        return when {
            id.contains("llama-4-scout") -> "Llama 4 Scout 17B (Dynamic)"
            id.contains("llama-4") -> "Llama 4 (Dynamic)"
            id.contains("llama-3.3") -> "Llama 3.3 (Dynamic)"
            id.contains("llama-3.1") -> "Llama 3.1 (Dynamic)"
            id.contains("mixtral") -> "Mixtral (Dynamic)"
            id.contains("gemma") -> "Gemma (Dynamic)"
            else -> id
        }
    }

    // --- Local Server Actions ---
    fun setLocalPCEnabled(enabled: Boolean) {
        securePreferences.setLocalPCEnabled(enabled)
        _isLocalPCEnabled.value = enabled
    }

    fun setLocalServerIP(ip: String) {
        securePreferences.setLocalPCIP(ip)
        _localServerIP.value = ip
    }

    fun setLocalServerPort(port: String) {
        securePreferences.setLocalPCPort(port)
        _localServerPort.value = port
    }

    fun setLocalServerUseHttps(useHttps: Boolean) {
        securePreferences.setLocalPCUseHttps(useHttps)
        _localServerUseHttps.value = useHttps
    }

    // --- Tavily Actions ---
    fun setTavilyApiKey(key: String?) {
        securePreferences.setTavilyApiKey(key)
        _tavilyApiKey.value = key
        _tavilyApiKeys.value = securePreferences.getTavilyApiKeys()
    }

    fun addTavilyApiKey(key: String) {
        securePreferences.addTavilyApiKey(key)
        _tavilyApiKeys.value = securePreferences.getTavilyApiKeys()
        _tavilyApiKey.value = securePreferences.getTavilyApiKey()
    }

    fun removeTavilyApiKey(key: String) {
        securePreferences.removeTavilyApiKey(key)
        _tavilyApiKeys.value = securePreferences.getTavilyApiKeys()
        _tavilyApiKey.value = securePreferences.getTavilyApiKey()
    }

    fun getTavilyApiKeySync(): String? = securePreferences.getTavilyApiKey()

    fun setTavilyEnabled(enabled: Boolean) {
        securePreferences.setTavilyEnabled(enabled)
        _isTavilyEnabled.value = enabled
    }

    // --- AI Service Actions ---
    /**
     * Test an API key for a specific provider.
     */
    suspend fun testApiKey(provider: AIProvider, apiKey: String): Boolean {
        return aiService.testApiKey(provider, apiKey)
    }

    /**
     * Get current API usage stats.
     */
    fun getRateLimitStats(): RateLimitStats = rateLimiter.getUsageStats()

    // --- UI/System Actions ---
    fun setDarkTheme(isDark: Boolean) {
        securePreferences.setDarkTheme(isDark)
    }

    /**
     * Set shake sensitivity.
     * @param uiSensitivity Value from UI (0.0 to 1.0)
     */
    fun setShakeSensitivity(uiSensitivity: Float) {
        val logicValue = mapUiToLogic(uiSensitivity)
        securePreferences.setShakeSensitivity(logicValue)
        _shakeSensitivity.value = uiSensitivity.coerceIn(0f, 1f)
    }

    fun getShakeThreshold(): Int = securePreferences.getShakeThreshold()

    fun isFirstLaunch(): Boolean = securePreferences.isFirstLaunch()

    fun setFirstLaunchComplete() {
        securePreferences.setFirstLaunchComplete()
    }

    fun isSoundEnabled(): Boolean = securePreferences.isSoundEnabled()

    fun hasAnyApiKeys(): Boolean = securePreferences.hasAnyApiKeys()

    fun getProviderKeys(provider: AIProvider): List<String> = securePreferences.getProviderKeys(provider)

    /**
     * Result of testing connection to local LLM server
     */
    sealed class LocalServerTestResult {
        data object Success : LocalServerTestResult()
        data class Failure(val message: String) : LocalServerTestResult()
    }

    /**
     * Test connection to local LLM server by pinging the health endpoint
     */
    suspend fun testLocalServer(ip: String, port: String, useHttps: Boolean): LocalServerTestResult {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val protocol = if (useHttps) "https" else "http"
                val testUrl = "$protocol://$ip:$port/v1/models"

                // Build appropriate OkHttp client
                val client = if (useHttps) {
                    // Create trust-all SSL configuration for self-signed certs
                    val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                        object : javax.net.ssl.X509TrustManager {
                            @Throws(java.security.cert.CertificateException::class)
                            override fun checkClientTrusted(
                                chain: Array<java.security.cert.X509Certificate>,
                                authType: String
                            ) {}

                            @Throws(java.security.cert.CertificateException::class)
                            override fun checkServerTrusted(
                                chain: Array<java.security.cert.X509Certificate>,
                                authType: String
                            ) {}

                            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                        }
                    )

                    // Use TLSv1.2 and TLSv1.3 for maximum compatibility
                    val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                    sslContext.init(null, trustAllCerts, java.security.SecureRandom())

                    okhttp3.OkHttpClient.Builder()
                        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                        .hostnameVerifier { _, _ -> true } // Accept any hostname
                        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                } else {
                    okhttp3.OkHttpClient.Builder()
                        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                }

                val request = okhttp3.Request.Builder()
                    .url(testUrl)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val code = response.code
                    // Any response means server is reachable
                    if (code in 200..299 || code in 400..499) {
                        LocalServerTestResult.Success
                    } else {
                        LocalServerTestResult.Failure("server_returned:_$code")
                    }
                }
            } catch (e: java.net.ConnectException) {
                LocalServerTestResult.Failure("connection_refused_-_is_server_running?")
            } catch (e: java.net.SocketTimeoutException) {
                LocalServerTestResult.Failure("timeout_-_check_ip_and_firewall")
            } catch (e: java.net.UnknownHostException) {
                LocalServerTestResult.Failure("invalid_ip_address")
            } catch (e: javax.net.ssl.SSLHandshakeException) {
                LocalServerTestResult.Failure("ssl_failed_-_ensure_caddy_is_running")
            } catch (e: javax.net.ssl.SSLException) {
                LocalServerTestResult.Failure("ssl_error_-_check_port_(8443_for_https)")
            } catch (e: Exception) {
                val msg = e.message?.lowercase() ?: ""
                when {
                    msg.contains("ssl") || msg.contains("tls") ->
                        LocalServerTestResult.Failure("ssl/tls_error_-_try_http_mode")
                    msg.contains("certificate") ->
                        LocalServerTestResult.Failure("cert_error_-_is_caddy_running?")
                    msg.contains("reset") || msg.contains("closed") ->
                        LocalServerTestResult.Failure("connection_reset_-_wrong_port?")
                    else ->
                        LocalServerTestResult.Failure("error:_${e.message?.take(50) ?: "unknown"}")
                }
            }
        }
    }
}
