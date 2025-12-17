package com.example.smarty.data.local

import android.content.Context
import dev.spght.encryptedprefs.EncryptedSharedPreferences
import dev.spght.encryptedprefs.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AIProvider {
    GEMINI,
    DEEPSEEK,
    GROQ,
    OPENAI,
    OPENROUTER,
    HUGGINGFACE
}

/**
 * Available models for each provider with display names
 */
object AIModels {
    // Gemini models
    val GEMINI_MODELS = listOf(
        "gemini-2.5-flash" to "Gemini 2.5 Flash (Latest)",
        "gemini-2.0-flash" to "Gemini 2.0 Flash",
        "gemini-2.0-flash-lite" to "Gemini 2.0 Flash Lite (Fastest)",
        "gemini-2.5-pro" to "Gemini 2.5 Pro (Most Capable)"
    )
    const val GEMINI_DEFAULT = "gemini-2.5-flash"

    // DeepSeek models
    val DEEPSEEK_MODELS = listOf(
        "deepseek-chat" to "DeepSeek Chat (V3.2)",
        "deepseek-reasoner" to "DeepSeek Reasoner (Thinking Mode)"
    )
    const val DEEPSEEK_DEFAULT = "deepseek-chat"

    // Groq models (ultra-fast inference)
    val GROQ_MODELS = listOf(
        "llama-3.3-70b-versatile" to "Llama 3.3 70B (Best Quality)",
        "llama-3.1-8b-instant" to "Llama 3.1 8B (Fastest)",
        "gemma2-9b-it" to "Gemma 2 9B",
        "deepseek-r1-distill-llama-70b" to "DeepSeek R1 Distill 70B",
        "qwen-qwq-32b" to "Qwen QWQ 32B (Preview)"
    )
    const val GROQ_DEFAULT = "llama-3.3-70b-versatile"

    // OpenAI models
    val OPENAI_MODELS = listOf(
        "gpt-4o-mini" to "GPT-4o Mini (Cost Efficient)",
        "gpt-4o" to "GPT-4o (Most Capable)",
        "gpt-4.1" to "GPT-4.1 (Smartest)",
        "gpt-4.1-mini" to "GPT-4.1 Mini",
        "gpt-4.1-nano" to "GPT-4.1 Nano (Fastest)"
    )
    const val OPENAI_DEFAULT = "gpt-4o-mini"

    // OpenRouter models (includes free options)
    val OPENROUTER_MODELS = listOf(
        "allenai/olmo-3.1-32b-think:free" to "OLMo 3.1 32B Think (Free, Reasoning)",
        "meta-llama/llama-3.1-8b-instruct:free" to "Llama 3.1 8B (Free)",
        "google/gemma-2-9b-it:free" to "Gemma 2 9B (Free)",
        "mistralai/mistral-7b-instruct:free" to "Mistral 7B (Free)",
        "deepseek/deepseek-r1:free" to "DeepSeek R1 (Free, Reasoning)",
        "qwen/qwen3-32b:free" to "Qwen 3 32B (Free)",
        "meta-llama/llama-3.3-70b-instruct" to "Llama 3.3 70B (Paid)",
        "anthropic/claude-3.5-sonnet" to "Claude 3.5 Sonnet (Paid)"
    )
    const val OPENROUTER_DEFAULT = "allenai/olmo-3.1-32b-think:free"

    // HuggingFace models
    val HUGGINGFACE_MODELS = listOf(
        "mistralai/Mistral-7B-Instruct-v0.2" to "Mistral 7B Instruct v0.2",
        "meta-llama/Llama-2-7b-chat-hf" to "Llama 2 7B Chat",
        "HuggingFaceH4/zephyr-7b-beta" to "Zephyr 7B Beta"
    )
    const val HUGGINGFACE_DEFAULT = "mistralai/Mistral-7B-Instruct-v0.2"

    fun getModelsForProvider(provider: AIProvider): List<Pair<String, String>> {
        return when (provider) {
            AIProvider.GEMINI -> GEMINI_MODELS
            AIProvider.DEEPSEEK -> DEEPSEEK_MODELS
            AIProvider.GROQ -> GROQ_MODELS
            AIProvider.OPENAI -> OPENAI_MODELS
            AIProvider.OPENROUTER -> OPENROUTER_MODELS
            AIProvider.HUGGINGFACE -> HUGGINGFACE_MODELS
        }
    }

    fun getDefaultModel(provider: AIProvider): String {
        return when (provider) {
            AIProvider.GEMINI -> GEMINI_DEFAULT
            AIProvider.DEEPSEEK -> DEEPSEEK_DEFAULT
            AIProvider.GROQ -> GROQ_DEFAULT
            AIProvider.OPENAI -> OPENAI_DEFAULT
            AIProvider.OPENROUTER -> OPENROUTER_DEFAULT
            AIProvider.HUGGINGFACE -> HUGGINGFACE_DEFAULT
        }
    }
}

data class AIProviderConfig(
    val provider: AIProvider,
    val apiKeys: List<String> = emptyList(),
    val isEnabled: Boolean = true,
    val selectedModel: String = AIModels.getDefaultModel(provider)
)

class SecurePreferences(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "cogni_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val gson = Gson()

    private val _isPinSet = MutableStateFlow(isPinConfigured())
    val isPinSet: StateFlow<Boolean> = _isPinSet.asStateFlow()

    // Legacy single API key for backward compatibility
    private val _apiKey = MutableStateFlow(getApiKey())
    val apiKey: StateFlow<String?> = _apiKey.asStateFlow()

    // Multi-provider API key states
    private val _geminiKeys = MutableStateFlow(getProviderKeys(AIProvider.GEMINI))
    val geminiKeys: StateFlow<List<String>> = _geminiKeys.asStateFlow()

    private val _huggingFaceKeys = MutableStateFlow(getProviderKeys(AIProvider.HUGGINGFACE))
    val huggingFaceKeys: StateFlow<List<String>> = _huggingFaceKeys.asStateFlow()

    private val _providerConfigs = MutableStateFlow(getAllProviderConfigs())
    val providerConfigs: StateFlow<Map<AIProvider, AIProviderConfig>> = _providerConfigs.asStateFlow()

    // Theme preference
    private val _isDarkTheme = MutableStateFlow(getDarkThemePreference())
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_API_KEY = "ai_api_key"
        private const val KEY_FIRST_LAUNCH = "first_launch"
        // API Keys for each provider
        private const val KEY_GEMINI_KEYS = "gemini_api_keys"
        private const val KEY_DEEPSEEK_KEYS = "deepseek_api_keys"
        private const val KEY_GROQ_KEYS = "groq_api_keys"
        private const val KEY_OPENAI_KEYS = "openai_api_keys"
        private const val KEY_OPENROUTER_KEYS = "openrouter_api_keys"
        private const val KEY_HUGGINGFACE_KEYS = "huggingface_api_keys"
        private const val KEY_PROVIDER_ENABLED_PREFIX = "provider_enabled_"
        private const val KEY_PROVIDER_MODEL_PREFIX = "provider_model_"
        private const val KEY_DARK_THEME = "dark_theme"
        // Backup settings
        private const val KEY_GOOGLE_ACCOUNT_EMAIL = "google_account_email"
        private const val KEY_LAST_BACKUP_TIME = "last_backup_time"
        private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
        private const val KEY_AUTO_BACKUP_INTERVAL_DAYS = "auto_backup_interval_days"
        private const val DEFAULT_BACKUP_INTERVAL_DAYS = 100

        @Volatile
        private var INSTANCE: SecurePreferences? = null

        fun getInstance(context: Context): SecurePreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecurePreferences(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    // PIN Management
    fun isPinConfigured(): Boolean {
        return encryptedPrefs.contains(KEY_PIN_HASH)
    }

    fun isFirstLaunch(): Boolean {
        return !encryptedPrefs.contains(KEY_FIRST_LAUNCH)
    }

    fun setFirstLaunchComplete() {
        encryptedPrefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
    }

    fun setPin(pin: String) {
        val pinHash = hashPin(pin)
        encryptedPrefs.edit().putString(KEY_PIN_HASH, pinHash).apply()
        _isPinSet.value = true
    }

    fun clearPin() {
        encryptedPrefs.edit().remove(KEY_PIN_HASH).apply()
        _isPinSet.value = false
    }

    fun verifyPin(pin: String): Boolean {
        val storedHash = encryptedPrefs.getString(KEY_PIN_HASH, null) ?: return false
        return hashPin(pin) == storedHash
    }

    fun changePin(oldPin: String, newPin: String): Boolean {
        if (!verifyPin(oldPin)) return false
        setPin(newPin)
        return true
    }

    // Legacy single API key (for backward compatibility)
    fun setApiKey(apiKey: String?) {
        if (apiKey.isNullOrBlank()) {
            encryptedPrefs.edit().remove(KEY_API_KEY).apply()
            _apiKey.value = null
        } else {
            encryptedPrefs.edit().putString(KEY_API_KEY, apiKey).apply()
            _apiKey.value = apiKey
        }
    }

    fun getApiKey(): String? {
        return encryptedPrefs.getString(KEY_API_KEY, null)
    }

    fun hasApiKey(): Boolean {
        return !getApiKey().isNullOrBlank()
    }

    // Multi-provider API Key Management
    fun getProviderKeys(provider: AIProvider): List<String> {
        val key = when (provider) {
            AIProvider.GEMINI -> KEY_GEMINI_KEYS
            AIProvider.DEEPSEEK -> KEY_DEEPSEEK_KEYS
            AIProvider.GROQ -> KEY_GROQ_KEYS
            AIProvider.OPENAI -> KEY_OPENAI_KEYS
            AIProvider.OPENROUTER -> KEY_OPENROUTER_KEYS
            AIProvider.HUGGINGFACE -> KEY_HUGGINGFACE_KEYS
        }
        val json = encryptedPrefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setProviderKeys(provider: AIProvider, keys: List<String>) {
        val key = when (provider) {
            AIProvider.GEMINI -> KEY_GEMINI_KEYS
            AIProvider.DEEPSEEK -> KEY_DEEPSEEK_KEYS
            AIProvider.GROQ -> KEY_GROQ_KEYS
            AIProvider.OPENAI -> KEY_OPENAI_KEYS
            AIProvider.OPENROUTER -> KEY_OPENROUTER_KEYS
            AIProvider.HUGGINGFACE -> KEY_HUGGINGFACE_KEYS
        }
        val filteredKeys = keys.filter { it.isNotBlank() }
        if (filteredKeys.isEmpty()) {
            encryptedPrefs.edit().remove(key).apply()
        } else {
            val json = gson.toJson(filteredKeys)
            encryptedPrefs.edit().putString(key, json).apply()
        }

        // Update state flows
        when (provider) {
            AIProvider.GEMINI -> _geminiKeys.value = filteredKeys
            AIProvider.HUGGINGFACE -> _huggingFaceKeys.value = filteredKeys
            else -> {} // Other providers don't have dedicated state flows
        }
        _providerConfigs.value = getAllProviderConfigs()
    }

    fun addProviderKey(provider: AIProvider, newKey: String) {
        if (newKey.isBlank()) return
        val currentKeys = getProviderKeys(provider).toMutableList()
        if (!currentKeys.contains(newKey)) {
            currentKeys.add(newKey)
            setProviderKeys(provider, currentKeys)
        }
    }

    fun removeProviderKey(provider: AIProvider, keyToRemove: String) {
        val currentKeys = getProviderKeys(provider).toMutableList()
        currentKeys.remove(keyToRemove)
        setProviderKeys(provider, currentKeys)
    }

    fun updateProviderKey(provider: AIProvider, oldKey: String, newKey: String) {
        if (newKey.isBlank()) return
        val currentKeys = getProviderKeys(provider).toMutableList()
        val index = currentKeys.indexOf(oldKey)
        if (index != -1) {
            currentKeys[index] = newKey
            setProviderKeys(provider, currentKeys)
        }
    }

    fun hasProviderKeys(provider: AIProvider): Boolean {
        return getProviderKeys(provider).isNotEmpty()
    }

    fun hasAnyApiKeys(): Boolean {
        return AIProvider.entries.any { hasProviderKeys(it) }
    }

    // Provider enable/disable
    fun isProviderEnabled(provider: AIProvider): Boolean {
        return encryptedPrefs.getBoolean("${KEY_PROVIDER_ENABLED_PREFIX}${provider.name}", true)
    }

    fun setProviderEnabled(provider: AIProvider, enabled: Boolean) {
        encryptedPrefs.edit()
            .putBoolean("${KEY_PROVIDER_ENABLED_PREFIX}${provider.name}", enabled)
            .apply()
        _providerConfigs.value = getAllProviderConfigs()
    }

    // Model selection for each provider
    fun getSelectedModel(provider: AIProvider): String {
        return encryptedPrefs.getString(
            "${KEY_PROVIDER_MODEL_PREFIX}${provider.name}",
            AIModels.getDefaultModel(provider)
        ) ?: AIModels.getDefaultModel(provider)
    }

    fun setSelectedModel(provider: AIProvider, model: String) {
        encryptedPrefs.edit()
            .putString("${KEY_PROVIDER_MODEL_PREFIX}${provider.name}", model)
            .apply()
        _providerConfigs.value = getAllProviderConfigs()
    }

    fun getProviderConfig(provider: AIProvider): AIProviderConfig {
        return AIProviderConfig(
            provider = provider,
            apiKeys = getProviderKeys(provider),
            isEnabled = isProviderEnabled(provider),
            selectedModel = getSelectedModel(provider)
        )
    }

    fun getAllProviderConfigs(): Map<AIProvider, AIProviderConfig> {
        return AIProvider.entries.associateWith { getProviderConfig(it) }
    }

    // Get the first available API key from enabled providers (for fallback logic)
    fun getFirstAvailableKey(): Pair<AIProvider, String>? {
        for (provider in AIProvider.entries) {
            if (isProviderEnabled(provider)) {
                val keys = getProviderKeys(provider)
                if (keys.isNotEmpty()) {
                    return provider to keys.first()
                }
            }
        }
        return null
    }

    private fun hashPin(pin: String): String {
        // Simple hash for demo - in production use proper bcrypt/argon2
        val bytes = pin.toByteArray()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }

    // Theme Management
    fun getDarkThemePreference(): Boolean {
        return encryptedPrefs.getBoolean(KEY_DARK_THEME, true) // Default to dark theme
    }

    fun setDarkTheme(isDark: Boolean) {
        encryptedPrefs.edit().putBoolean(KEY_DARK_THEME, isDark).apply()
        _isDarkTheme.value = isDark
    }

    // Backup Management
    fun getGoogleAccountEmail(): String? {
        return encryptedPrefs.getString(KEY_GOOGLE_ACCOUNT_EMAIL, null)
    }

    fun setGoogleAccountEmail(email: String?) {
        if (email.isNullOrBlank()) {
            encryptedPrefs.edit().remove(KEY_GOOGLE_ACCOUNT_EMAIL).apply()
        } else {
            encryptedPrefs.edit().putString(KEY_GOOGLE_ACCOUNT_EMAIL, email).apply()
        }
    }

    fun getLastBackupTime(): Long {
        return encryptedPrefs.getLong(KEY_LAST_BACKUP_TIME, 0L)
    }

    fun setLastBackupTime(timestamp: Long) {
        encryptedPrefs.edit().putLong(KEY_LAST_BACKUP_TIME, timestamp).apply()
    }

    fun isAutoBackupEnabled(): Boolean {
        return encryptedPrefs.getBoolean(KEY_AUTO_BACKUP_ENABLED, false)
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        encryptedPrefs.edit().putBoolean(KEY_AUTO_BACKUP_ENABLED, enabled).apply()
    }

    fun getAutoBackupIntervalDays(): Int {
        return encryptedPrefs.getInt(KEY_AUTO_BACKUP_INTERVAL_DAYS, DEFAULT_BACKUP_INTERVAL_DAYS)
    }

    fun setAutoBackupIntervalDays(days: Int) {
        encryptedPrefs.edit().putInt(KEY_AUTO_BACKUP_INTERVAL_DAYS, days).apply()
    }

    fun isBackupDue(): Boolean {
        if (!isAutoBackupEnabled()) return false
        val lastBackup = getLastBackupTime()
        if (lastBackup == 0L) return true
        val intervalMs = getAutoBackupIntervalDays() * 24L * 60 * 60 * 1000
        return System.currentTimeMillis() - lastBackup >= intervalMs
    }

    fun getDaysSinceLastBackup(): Int {
        val lastBackup = getLastBackupTime()
        if (lastBackup == 0L) return -1
        val diff = System.currentTimeMillis() - lastBackup
        return (diff / (24L * 60 * 60 * 1000)).toInt()
    }
}
