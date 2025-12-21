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
    CEREBRAS,
    COHERE,
    OPENAI,
    OPENROUTER,
    ANTHROPIC,
    HUGGINGFACE
}

/**
 * GROQ Model configuration with TPM (Tokens Per Minute) limits.
 * Each model has specific rate limits that affect how the agent operates.
 */
data class GroqModelConfig(
    val modelId: String,
    val displayName: String,
    val tpm: Int,                    // Tokens per minute limit
    val rpm: Int = 30,               // Requests per minute
    val dailyTokens: Int = 0,        // Daily token limit (0 = unlimited)
    val contextWindow: Int = 8192    // Max context size
)

/**
 * Available models for each provider with display names
 */
object AIModels {
    // GROQ models with TPM limits (free tier) - Primary focus
    val GROQ_MODELS_CONFIG = listOf(
        // Production Models
        GroqModelConfig("llama-3.1-8b-instant", "Llama 3.1 8B Instant (Free Tier Default)", tpm = 6_000, rpm = 30, dailyTokens = 500_000, contextWindow = 128_000),
        GroqModelConfig("llama-3.3-70b-versatile", "Llama 3.3 70B Versatile (Best Quality)", tpm = 6_000, rpm = 30, dailyTokens = 100_000, contextWindow = 128_000),
        GroqModelConfig("openai/gpt-oss-120b", "GPT-OSS 120B (High Capability)", tpm = 6_000, rpm = 30, dailyTokens = 50_000, contextWindow = 128_000),
        GroqModelConfig("openai/gpt-oss-20b", "GPT-OSS 20B (Fast)", tpm = 6_000, rpm = 30, dailyTokens = 200_000, contextWindow = 128_000),
        // Compound Systems
        GroqModelConfig("groq/compound", "Groq Compound (Multi-Model)", tpm = 6_000, rpm = 30, contextWindow = 128_000),
        GroqModelConfig("groq/compound-mini", "Groq Compound Mini (Multi-Model Fast)", tpm = 6_000, rpm = 30, contextWindow = 128_000),
        // Preview Models - Llama 4
        GroqModelConfig("meta-llama/llama-4-maverick-17b-128e-instruct", "Llama 4 Maverick 17B (Preview)", tpm = 6_000, rpm = 30, dailyTokens = 100_000, contextWindow = 128_000),
        GroqModelConfig("meta-llama/llama-4-scout-17b-16e-instruct", "Llama 4 Scout 17B (Preview)", tpm = 6_000, rpm = 30, dailyTokens = 100_000, contextWindow = 128_000),
        // Other Preview Models
        GroqModelConfig("qwen/qwen3-32b", "Qwen 3 32B (Preview)", tpm = 6_000, rpm = 30, dailyTokens = 100_000, contextWindow = 128_000),
        GroqModelConfig("moonshotai/kimi-k2-instruct-0905", "Kimi K2 Instruct (262K Context)", tpm = 6_000, rpm = 30, dailyTokens = 100_000, contextWindow = 262_000)
    )

    /**
     * Get GROQ TPM config for a specific model.
     * Returns null if model not found.
     */
    fun getGroqModelConfig(modelId: String): GroqModelConfig? {
        return GROQ_MODELS_CONFIG.find { it.modelId == modelId }
    }

    /**
     * Get TPM limit for a GROQ model.
     * Returns default 6000 if model not found.
     */
    fun getGroqTPM(modelId: String): Int {
        return getGroqModelConfig(modelId)?.tpm ?: 6_000
    }

    /**
     * Get context window for a GROQ model.
     * Returns default 8192 if model not found.
     */
    fun getGroqContextWindow(modelId: String): Int {
        return getGroqModelConfig(modelId)?.contextWindow ?: 8192
    }

    /**
     * Get daily token limit for a GROQ model.
     * Returns 0 (unlimited) if model not found.
     */
    fun getGroqDailyLimit(modelId: String): Int {
        return getGroqModelConfig(modelId)?.dailyTokens ?: 0
    }

    // Legacy: Simple model lists for backward compatibility
    // Gemini models
    val GEMINI_MODELS = listOf(
        "gemini-1.5-flash" to "Gemini 1.5 Flash (Default)",
        "gemini-2.5-flash" to "Gemini 2.5 Flash (Latest)",
        "gemini-2.0-flash" to "Gemini 2.0 Flash",
        "gemini-2.0-flash-lite" to "Gemini 2.0 Flash Lite (Fastest)",
        "gemini-2.5-pro" to "Gemini 2.5 Pro (Most Capable)",
        "gemini-1.5-pro" to "Gemini 1.5 Pro"
    )
    const val GEMINI_DEFAULT = "gemini-1.5-flash"

    // DeepSeek models
    val DEEPSEEK_MODELS = listOf(
        "deepseek-chat" to "DeepSeek Chat (V3.2)",
        "deepseek-reasoner" to "DeepSeek Reasoner (Thinking Mode)"
    )
    const val DEEPSEEK_DEFAULT = "deepseek-chat"

    // Groq models (ultra-fast inference) - All free with API key
    // API: https://api.groq.com/openai/v1/chat/completions
    // Free tier: 14,400 requests/day, rate limited per minute
    val GROQ_MODELS = listOf(
        // Production Models - Best for free tier
        "llama-3.1-8b-instant" to "Llama 3.1 8B Instant (Free Tier Default)",
        "llama-3.3-70b-versatile" to "Llama 3.3 70B Versatile (Best Quality)",
        "openai/gpt-oss-120b" to "GPT-OSS 120B (High Capability)",
        "openai/gpt-oss-20b" to "GPT-OSS 20B (Fast)",
        // Production Systems (Compound AI)
        "groq/compound" to "Groq Compound (Multi-Model)",
        "groq/compound-mini" to "Groq Compound Mini (Multi-Model Fast)",
        // Preview Models
        "meta-llama/llama-4-maverick-17b-128e-instruct" to "Llama 4 Maverick 17B (Preview)",
        "meta-llama/llama-4-scout-17b-16e-instruct" to "Llama 4 Scout 17B (Preview)",
        "qwen/qwen3-32b" to "Qwen 3 32B (Preview)",
        "moonshotai/kimi-k2-instruct-0905" to "Kimi K2 Instruct (262K Context)"
    )
    const val GROQ_DEFAULT = "llama-3.1-8b-instant"  // Best for free tier - fastest, highest rate limits

    // Cerebras models (ultra-fast inference, 2000+ tokens/sec) - Free tier available
    // API: https://api.cerebras.ai/v1/chat/completions
    // Free tier: 1M tokens/day, 8K context limit (except Qwen 235B: 64K)
    val CEREBRAS_MODELS = listOf(
        // Best for free tier - faster, uses less tokens
        "llama3.1-8b" to "Llama 3.1 8B (Free Tier Default, 1800 T/s)",
        "llama-3.3-70b" to "Llama 3.3 70B (450 T/s, Best Quality)",
        "qwen-3-32b" to "Qwen 3 32B (40K Context)",
        "gpt-oss-120b" to "GPT-OSS 120B (Reasoning)",
        "qwen-3-235b-a22b-instruct-2507" to "Qwen 3 235B (1400 T/s, 64K Context)",
        "zai-glm-4.6" to "ZAI GLM 4.6 (Preview)"
    )
    const val CEREBRAS_DEFAULT = "llama3.1-8b"  // Best for free tier - fastest, conserves daily token limit

    // Cohere models - Free/Trial tier available (1000 API calls/month)
    // API: https://api.cohere.ai/compatibility/v1 (OpenAI-compatible)
    // Trial key: 20 requests/min for chat, all models accessible
    val COHERE_MODELS = listOf(
        // Best for free tier - fastest, conserves monthly API calls
        "command-r7b-12-2024" to "Command R7B (Free Tier Default, Fast)",
        "command-r-08-2024" to "Command R (128K Context)",
        "command-r-plus-08-2024" to "Command R+ (128K Context)",
        "command-a-03-2025" to "Command A (256K Context, Best)",
        "command" to "Command (Legacy)"
    )
    const val COHERE_DEFAULT = "command-r7b-12-2024"  // Best for trial tier - fast, conserves monthly API limit

    // OpenAI models
    val OPENAI_MODELS = listOf(
        "gpt-4o-mini" to "GPT-4o Mini (Cost Efficient)",
        "gpt-4o" to "GPT-4o (Most Capable)",
        "gpt-4.1" to "GPT-4.1 (Smartest)",
        "gpt-4.1-mini" to "GPT-4.1 Mini",
        "gpt-4.1-nano" to "GPT-4.1 Nano (Fastest)"
    )
    const val OPENAI_DEFAULT = "gpt-4o-mini"

    // Anthropic models
    val ANTHROPIC_MODELS = listOf(
        "claude-3-5-sonnet-20240620" to "Claude 3.5 Sonnet",
        "claude-3-opus-20240229" to "Claude 3 Opus",
        "claude-3-haiku-20240307" to "Claude 3 Haiku",
        "claude-3-5-haiku-20241022" to "Claude 3.5 Haiku"
    )
    const val ANTHROPIC_DEFAULT = "claude-3-5-sonnet-20240620"

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
            AIProvider.CEREBRAS -> CEREBRAS_MODELS
            AIProvider.COHERE -> COHERE_MODELS
            AIProvider.OPENAI -> OPENAI_MODELS
            AIProvider.ANTHROPIC -> ANTHROPIC_MODELS
            AIProvider.OPENROUTER -> OPENROUTER_MODELS
            AIProvider.HUGGINGFACE -> HUGGINGFACE_MODELS
        }
    }

    fun getDefaultModel(provider: AIProvider): String {
        return when (provider) {
            AIProvider.GEMINI -> GEMINI_DEFAULT
            AIProvider.DEEPSEEK -> DEEPSEEK_DEFAULT
            AIProvider.GROQ -> GROQ_DEFAULT
            AIProvider.CEREBRAS -> CEREBRAS_DEFAULT
            AIProvider.COHERE -> COHERE_DEFAULT
            AIProvider.OPENAI -> OPENAI_DEFAULT
            AIProvider.ANTHROPIC -> ANTHROPIC_DEFAULT
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

    // Lock for compound read-modify-write operations (BUG-023 fix)
    private val keyOperationLock = Any()

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

    // Provider priority order - separate StateFlow for UI reactivity
    private val _providerPriorityOrder = MutableStateFlow(getProviderPriority())
    val providerPriorityOrder: StateFlow<List<AIProvider>> = _providerPriorityOrder.asStateFlow()

    // Theme preference
    private val _isDarkTheme = MutableStateFlow(getDarkThemePreference())
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    // Provider Priority Management
    // Default order: GROQ first, then others
    private val defaultProviderPriority = listOf(
        AIProvider.GROQ,      // Top priority
        AIProvider.GEMINI,
        AIProvider.DEEPSEEK,
        AIProvider.CEREBRAS,
        AIProvider.COHERE,
        AIProvider.OPENAI,
        AIProvider.OPENROUTER,
        AIProvider.ANTHROPIC,
        AIProvider.HUGGINGFACE
    )

    fun getProviderPriority(): List<AIProvider> {
        val json = encryptedPrefs.getString(KEY_PROVIDER_PRIORITY, null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<AIProvider>>() {}.type
                val result: List<AIProvider> = gson.fromJson(json, type)
                // Ensure all providers are included (for newly added ones)
                return (result + AIProvider.entries).distinct()
            } catch (e: Exception) {
                // Return default order if parsing fails
            }
        }
        return defaultProviderPriority
    }

    fun setProviderPriority(priority: List<AIProvider>) {
        val json = gson.toJson(priority)
        encryptedPrefs.edit().putString(KEY_PROVIDER_PRIORITY, json).apply()
        // Update both StateFlows
        _providerPriorityOrder.value = priority
        _providerConfigs.value = getAllProviderConfigs()
    }

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_API_KEY = "ai_api_key"
        private const val KEY_FIRST_LAUNCH = "first_launch"
        // API Keys for each provider
        private const val KEY_GEMINI_KEYS = "gemini_api_keys"
        private const val KEY_DEEPSEEK_KEYS = "deepseek_api_keys"
        private const val KEY_GROQ_KEYS = "groq_api_keys"
        private const val KEY_CEREBRAS_KEYS = "cerebras_api_keys"
        private const val KEY_COHERE_KEYS = "cohere_api_keys"
        private const val KEY_OPENAI_KEYS = "openai_api_keys"
        private const val KEY_SHAKE_SENSITIVITY = "shake_sensitivity"
        private const val KEY_OPENROUTER_KEYS = "openrouter_api_keys"
        private const val KEY_ANTHROPIC_KEYS = "anthropic_api_keys"
        private const val KEY_HUGGINGFACE_KEYS = "huggingface_api_keys"
        private const val KEY_PROVIDER_ENABLED_PREFIX = "provider_enabled_"
        private const val KEY_PROVIDER_MODEL_PREFIX = "provider_model_"
        private const val KEY_PROVIDER_PRIORITY = "provider_priority"
        private const val KEY_GROQ_DYNAMIC_MODELS = "groq_dynamic_models"
        private const val KEY_DARK_THEME = "dark_theme"
        // Backup settings
        private const val KEY_GOOGLE_ACCOUNT_EMAIL = "google_account_email"
        private const val KEY_LAST_BACKUP_TIME = "last_backup_time"
        private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
        private const val KEY_AUTO_BACKUP_INTERVAL_DAYS = "auto_backup_interval_days"
        private const val DEFAULT_BACKUP_INTERVAL_DAYS = 100
        // Tavily Web Search API
        private const val KEY_TAVILY_API_KEY = "tavily_api_key"

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

    // Shake sensitivity (0.0 to 1.0, default 0.63 - baseline recommended)
    fun getShakeSensitivity(): Float {
        return encryptedPrefs.getFloat(KEY_SHAKE_SENSITIVITY, 0.63f)
    }

    fun setShakeSensitivity(value: Float) {
        encryptedPrefs.edit().putFloat(KEY_SHAKE_SENSITIVITY, value.coerceIn(0f, 1f)).apply()
    }

    /**
     * Convert sensitivity (0-1) to shake threshold.
     * Higher sensitivity = lower threshold (easier to trigger)
     * sensitivity 0 -> threshold 1600 (hard to trigger)
     * sensitivity 1 -> threshold 400 (easy to trigger)
     */
    fun getShakeThreshold(): Int {
        val sensitivity = getShakeSensitivity()
        return (1600 - sensitivity * 1200).toInt()
    }

    // Multi-provider API Key Management
    fun getProviderKeys(provider: AIProvider): List<String> {
        val key = when (provider) {
            AIProvider.GEMINI -> KEY_GEMINI_KEYS
            AIProvider.DEEPSEEK -> KEY_DEEPSEEK_KEYS
            AIProvider.GROQ -> KEY_GROQ_KEYS
            AIProvider.CEREBRAS -> KEY_CEREBRAS_KEYS
            AIProvider.COHERE -> KEY_COHERE_KEYS
            AIProvider.OPENAI -> KEY_OPENAI_KEYS
            AIProvider.OPENROUTER -> KEY_OPENROUTER_KEYS
            AIProvider.ANTHROPIC -> KEY_ANTHROPIC_KEYS
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
            AIProvider.CEREBRAS -> KEY_CEREBRAS_KEYS
            AIProvider.COHERE -> KEY_COHERE_KEYS
            AIProvider.OPENAI -> KEY_OPENAI_KEYS
            AIProvider.OPENROUTER -> KEY_OPENROUTER_KEYS
            AIProvider.ANTHROPIC -> KEY_ANTHROPIC_KEYS
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
        synchronized(keyOperationLock) {
            val currentKeys = getProviderKeys(provider).toMutableList()
            if (!currentKeys.contains(newKey)) {
                currentKeys.add(newKey)
                setProviderKeys(provider, currentKeys)
            }
        }
    }

    fun removeProviderKey(provider: AIProvider, keyToRemove: String) {
        synchronized(keyOperationLock) {
            val currentKeys = getProviderKeys(provider).toMutableList()
            currentKeys.remove(keyToRemove)
            setProviderKeys(provider, currentKeys)
        }
    }

    fun updateProviderKey(provider: AIProvider, oldKey: String, newKey: String) {
        if (newKey.isBlank()) return
        synchronized(keyOperationLock) {
            val currentKeys = getProviderKeys(provider).toMutableList()
            val index = currentKeys.indexOf(oldKey)
            if (index != -1) {
                currentKeys[index] = newKey
                setProviderKeys(provider, currentKeys)
            }
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
        val priority = getProviderPriority()
        // Ensure all providers are present, even if not in the stored priority list (e.g. newly added ones)
        val allProviders = (priority + AIProvider.entries).distinct()
        return allProviders.associateWith { getProviderConfig(it) }
    }

    // Get the first available API key from enabled providers (using priority order)
    fun getFirstAvailableKey(): Pair<AIProvider, String>? {
        val priority = getProviderPriority()
        val allProviders = (priority + AIProvider.entries).distinct()
        
        for (provider in allProviders) {
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

    // ==================== Tavily Web Search API ====================

    /**
     * Get Tavily API key for web search functionality.
     * Free tier: 1,000 API credits/month
     * Key format: tvly-XXXXX
     */
    fun getTavilyApiKey(): String? {
        return encryptedPrefs.getString(KEY_TAVILY_API_KEY, null)
    }

    /**
     * Set Tavily API key.
     * @param key The API key (format: tvly-XXXXX), or null to remove
     */
    fun setTavilyApiKey(key: String?) {
        if (key.isNullOrBlank()) {
            encryptedPrefs.edit().remove(KEY_TAVILY_API_KEY).apply()
        } else {
            encryptedPrefs.edit().putString(KEY_TAVILY_API_KEY, key.trim()).apply()
        }
    }

    /**
     * Check if Tavily API key is configured.
     */
    fun hasTavilyApiKey(): Boolean {
        return !getTavilyApiKey().isNullOrBlank()
    }

    // ==================== Dynamic Model Management ====================

    /**
     * Get available models for a provider, prioritizing dynamic models if present.
     */
    fun getAvailableModels(provider: AIProvider): List<Pair<String, String>> {
        val dynamicModels = getDynamicModels(provider)
        if (dynamicModels.isNotEmpty()) {
            return dynamicModels
        }
        return AIModels.getModelsForProvider(provider)
    }

    /**
     * Get dynamic models from secure storage.
     */
    fun getDynamicModels(provider: AIProvider): List<Pair<String, String>> {
        // Currently only supporting Groq logic
        if (provider != AIProvider.GROQ) return emptyList()

        val json = encryptedPrefs.getString(KEY_GROQ_DYNAMIC_MODELS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Pair<String, String>>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Set dynamic models for a provider.
     */
    fun setDynamicModels(provider: AIProvider, models: List<Pair<String, String>>) {
        if (provider != AIProvider.GROQ) return

        if (models.isEmpty()) {
            encryptedPrefs.edit().remove(KEY_GROQ_DYNAMIC_MODELS).apply()
        } else {
            val json = gson.toJson(models)
            encryptedPrefs.edit().putString(KEY_GROQ_DYNAMIC_MODELS, json).apply()
        }
    }
}
