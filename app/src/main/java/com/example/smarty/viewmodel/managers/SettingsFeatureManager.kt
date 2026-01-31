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
}
