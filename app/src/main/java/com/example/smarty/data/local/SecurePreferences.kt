@file:Suppress("DEPRECATION")

package com.example.smarty.data.local

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Available models for the server connection.
 * These are FALLBACK defaults — the app fetches the real list from the server
 * at startup via GET /api/v1/opencode/models (which runs `opencode models`).
 * The server discovers free models at runtime, so this list may be stale.
 */
object AIModels {
    val SERVER_MODELS =
        listOf(
            "opencode/deepseek-v4-flash-free" to "DeepSeek V4 Flash",
            "opencode/nemotron-3-super-free" to "Nemotron 3 Super",
            "opencode/qwen3.6-plus-free" to "Qwen 3.6 Plus",
        )
    const val SERVER_DEFAULT = "opencode/deepseek-v4-flash-free"

    fun getModelsForConnection(connection: AIConnection): List<Pair<String, String>> {
        return when (connection) {
            AIConnection.LOCAL_PC -> SERVER_MODELS
        }
    }

    fun getDefaultModel(connection: AIConnection): String {
        return when (connection) {
            AIConnection.LOCAL_PC -> SERVER_DEFAULT
        }
    }
}

/**
 * Secure storage for application preferences and sensitive settings.
 * Thin Client Version: Only manages local settings and server connection configuration.
 */
@Suppress("DEPRECATION")
class SecurePreferences(private val context: Context) {
    fun getContext(): Context = context

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs: android.content.SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "Smarty_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val gson = Gson()

    // Theme preference
    private val _isDarkTheme: MutableStateFlow<Boolean> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        MutableStateFlow(getDarkThemePreference())
    }
    val isDarkTheme: StateFlow<Boolean> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        _isDarkTheme.asStateFlow()
    }

    private val _selectedModel: MutableStateFlow<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        MutableStateFlow(getSelectedModel(AIConnection.LOCAL_PC))
    }
    val selectedModelFlow: StateFlow<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        _selectedModel.asStateFlow()
    }

    private val _availableModels: MutableStateFlow<List<Pair<String, String>>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        MutableStateFlow(getAvailableModels(AIConnection.LOCAL_PC))
    }
    val availableModelsFlow: StateFlow<List<Pair<String, String>>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        _availableModels.asStateFlow()
    }

    fun getConnectionPriority(): List<AIConnection> {
        return listOf(AIConnection.LOCAL_PC)
    }

    companion object {
        private const val KEY_FIRST_LAUNCH = "first_launch"
        private const val KEY_IS_ONBOARDED = "is_onboarded"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_GOALS = "user_goals"
        private const val KEY_USER_PREFERENCES = "user_preferences"
        private const val KEY_SHAKE_SENSITIVITY = "shake_sensitivity"
        private const val KEY_LOCAL_PC_MODEL = "local_pc_model"
        private const val KEY_CACHED_MODELS = "cached_models"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_HAPTIC_ENABLED = "haptic_enabled"

        // Backup settings
        private const val KEY_GOOGLE_ACCOUNT_EMAIL = "google_account_email"
        private const val KEY_LAST_BACKUP_TIME = "last_backup_time"
        private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
        private const val KEY_AUTO_BACKUP_INTERVAL_DAYS = "auto_backup_interval_days"
        private const val DEFAULT_BACKUP_INTERVAL_DAYS = 100

        // FTS Maintenance
        private const val KEY_LAST_FTS_MAINTENANCE = "last_fts_maintenance"

        // Remote Server Configuration - Hardcoded to Hugging Face Spaces
        private const val KEY_SERVER_URL = "server_url"
        private const val DEFAULT_SERVER_URL = "https://k1tt3n-friday-server.hf.space"

        // Google Calendar Sync
        private const val KEY_SYNC_TO_GOOGLE_CALENDAR = "sync_to_google_calendar"
        private const val KEY_TARGET_GOOGLE_CALENDAR_ID = "target_google_calendar_id"

        // AI Provider Strategy
        private const val KEY_PROVIDER_STRATEGY = "provider_strategy"
        private const val DEFAULT_PROVIDER_STRATEGY = "AUTO"

        // AI Personality
        private const val KEY_PERSONALITY = "ai_personality"
        private const val DEFAULT_PERSONALITY = "DEFAULT"

        // Security
        private const val KEY_DEVICE_ID = "device_id"

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

    fun isFirstLaunch(): Boolean = !encryptedPrefs.contains(KEY_FIRST_LAUNCH)

    fun setFirstLaunchComplete() {
        encryptedPrefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
    }

    fun isOnboarded(): Boolean = encryptedPrefs.getBoolean(KEY_IS_ONBOARDED, false)

    fun setOnboarded(onboarded: Boolean) {
        encryptedPrefs.edit().putBoolean(KEY_IS_ONBOARDED, onboarded).apply()
    }

    // User Profile
    fun getUserName(): String = encryptedPrefs.getString(KEY_USER_NAME, "") ?: ""
    fun setUserName(name: String) = encryptedPrefs.edit().putString(KEY_USER_NAME, name).apply()

    fun getUserGoals(): String = encryptedPrefs.getString(KEY_USER_GOALS, "") ?: ""
    fun setUserGoals(goals: String) = encryptedPrefs.edit().putString(KEY_USER_GOALS, goals).apply()

    fun getUserPreferences(): String = encryptedPrefs.getString(KEY_USER_PREFERENCES, "") ?: ""
    fun setUserPreferences(prefs: String) = encryptedPrefs.edit().putString(KEY_USER_PREFERENCES, prefs).apply()

    // Shake sensitivity
    fun getShakeSensitivity(): Float = encryptedPrefs.getFloat(KEY_SHAKE_SENSITIVITY, 1.5f)

    fun setShakeSensitivity(value: Float) {
        encryptedPrefs.edit().putFloat(KEY_SHAKE_SENSITIVITY, value.coerceIn(0.5f, 5.0f)).apply()
    }

    fun getShakeThreshold(): Int {
        val logicValue = getShakeSensitivity()
        return (400 + (logicValue - 0.5f) * (1200f / 4.5f)).toInt()
    }

    // Sound/Haptic
    fun isSoundEnabled(): Boolean = encryptedPrefs.getBoolean(KEY_SOUND_ENABLED, true)

    fun setSoundEnabled(enabled: Boolean) = encryptedPrefs.edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()

    fun isHapticEnabled(): Boolean = encryptedPrefs.getBoolean(KEY_HAPTIC_ENABLED, true)

    fun setHapticEnabled(enabled: Boolean) = encryptedPrefs.edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply()

    // Connection Management
    fun isConnectionEnabled(connection: AIConnection): Boolean {
        return isServerEnabled()
    }

    fun getSelectedModel(connection: AIConnection): String {
        val saved = encryptedPrefs.getString(KEY_LOCAL_PC_MODEL, null)
        if (saved == null || !saved.startsWith("opencode/")) {
            return AIModels.getDefaultModel(connection)
        }
        return saved
    }

    fun setSelectedModel(
        connection: AIConnection,
        model: String,
    ) {
        encryptedPrefs.edit().putString(KEY_LOCAL_PC_MODEL, model).apply()
        _selectedModel.value = model
    }

    // Theme Management
    fun getDarkThemePreference(): Boolean = encryptedPrefs.getBoolean(KEY_DARK_THEME, false)

    fun setDarkTheme(isDark: Boolean) {
        encryptedPrefs.edit().putBoolean(KEY_DARK_THEME, isDark).apply()
        _isDarkTheme.value = isDark
    }

    // Google Calendar Sync
    fun isSyncToGoogleCalendarEnabled(): Boolean = encryptedPrefs.getBoolean(KEY_SYNC_TO_GOOGLE_CALENDAR, false)

    fun setSyncToGoogleCalendarEnabled(enabled: Boolean) = encryptedPrefs.edit().putBoolean(KEY_SYNC_TO_GOOGLE_CALENDAR, enabled).apply()

    fun getTargetGoogleCalendarId(): Long = encryptedPrefs.getLong(KEY_TARGET_GOOGLE_CALENDAR_ID, -1L)

    fun setTargetGoogleCalendarId(calendarId: Long) = encryptedPrefs.edit().putLong(KEY_TARGET_GOOGLE_CALENDAR_ID, calendarId).apply()

    // Backup Management
    fun getGoogleAccountEmail(): String? = encryptedPrefs.getString(KEY_GOOGLE_ACCOUNT_EMAIL, null)

    fun setGoogleAccountEmail(email: String?) {
        if (email.isNullOrBlank()) {
            encryptedPrefs.edit().remove(KEY_GOOGLE_ACCOUNT_EMAIL).apply()
        } else {
            encryptedPrefs.edit().putString(KEY_GOOGLE_ACCOUNT_EMAIL, email).apply()
        }
    }

    fun getLastBackupTime(): Long = encryptedPrefs.getLong(KEY_LAST_BACKUP_TIME, 0L)

    fun setLastBackupTime(timestamp: Long) = encryptedPrefs.edit().putLong(KEY_LAST_BACKUP_TIME, timestamp).apply()

    fun isAutoBackupEnabled(): Boolean = encryptedPrefs.getBoolean(KEY_AUTO_BACKUP_ENABLED, false)

    fun setAutoBackupEnabled(enabled: Boolean) = encryptedPrefs.edit().putBoolean(KEY_AUTO_BACKUP_ENABLED, enabled).apply()

    fun getAutoBackupIntervalDays(): Int = encryptedPrefs.getInt(KEY_AUTO_BACKUP_INTERVAL_DAYS, DEFAULT_BACKUP_INTERVAL_DAYS)

    fun setAutoBackupIntervalDays(days: Int) = encryptedPrefs.edit().putInt(KEY_AUTO_BACKUP_INTERVAL_DAYS, days).apply()

    fun isBackupDue(): Boolean {
        if (!isAutoBackupEnabled()) return false
        val lastBackup = getLastBackupTime()
        val intervalMs = getAutoBackupIntervalDays().toLong() * 24 * 60 * 60 * 1000
        return System.currentTimeMillis() - lastBackup > intervalMs
    }

    // FTS Maintenance
    fun getLastFtsMaintenance(): Long = encryptedPrefs.getLong(KEY_LAST_FTS_MAINTENANCE, 0L)

    fun setLastFtsMaintenance(timestamp: Long) = encryptedPrefs.edit().putLong(KEY_LAST_FTS_MAINTENANCE, timestamp).apply()

    // Server Settings (Remote Only) - Hardcoded for security
    // Server URL is fixed and cannot be changed by users
    fun getServerUrl(): String = DEFAULT_SERVER_URL

    fun setServerUrl(url: String) {
        // No-op: Server URL is hardcoded and cannot be changed
        // This prevents users from accidentally breaking the app or leaking the server URL
    }

    fun getSmartyServerUrl(): String = DEFAULT_SERVER_URL

    // Compatibility methods for AIConnectionOrchestrator
    fun getLocalPCUrl(): String = getServerUrl()

    fun isLocalPCEnabled(): Boolean = false

    fun isServerEnabled(): Boolean = true // Always enabled in Remote-Only mode

    fun getCachedModels(): List<Pair<String, String>> {
        val jsonStr = encryptedPrefs.getString(KEY_CACHED_MODELS, null) ?: return AIModels.SERVER_MODELS
        return try {
            val listType = object : com.google.gson.reflect.TypeToken<List<Pair<String, String>>>() {}.type
            gson.fromJson(jsonStr, listType) ?: AIModels.SERVER_MODELS
        } catch (e: Exception) {
            AIModels.SERVER_MODELS
        }
    }

    fun setCachedModels(models: List<Pair<String, String>>) {
        try {
            val jsonStr = gson.toJson(models)
            encryptedPrefs.edit().putString(KEY_CACHED_MODELS, jsonStr).apply()
            _availableModels.value = models
        } catch (e: Exception) {
            Log.e("SecurePreferences", "Failed to cache models: ${e.message}")
        }
    }

    fun getAvailableModels(connection: AIConnection): List<Pair<String, String>> {
        return getCachedModels()
    }

    // AI Provider Strategy
    fun getProviderStrategy(): String = DEFAULT_PROVIDER_STRATEGY

    fun setProviderStrategy(strategy: String) = encryptedPrefs.edit().putString(KEY_PROVIDER_STRATEGY, DEFAULT_PROVIDER_STRATEGY).apply()

    // AI Personality
    fun getPersonality(): String = encryptedPrefs.getString(KEY_PERSONALITY, DEFAULT_PERSONALITY) ?: DEFAULT_PERSONALITY

    fun setPersonality(personality: String) = encryptedPrefs.edit().putString(KEY_PERSONALITY, personality).apply()

    /**
     * Get or create a unique, persistent Device ID.
     * Uses a random UUID that persists across app launches but is cleared on uninstall.
     * This is safer than using hardware identifiers or user ID hashes.
     */
    fun getDeviceId(): String {
        var deviceId = encryptedPrefs.getString(KEY_DEVICE_ID, null)
        if (deviceId == null) {
            deviceId = "smarty-" + UUID.randomUUID().toString()
            encryptedPrefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    /**
     * Clear all user-specific preferences.
     * Called on sign-out to prevent data leakage.
     * Note: Does not clear app-level settings like theme preference.
     */
    fun clearAll() {
        encryptedPrefs.edit()
            .remove(KEY_GOOGLE_ACCOUNT_EMAIL)
            // Add other user-specific keys here if needed
            .apply()
    }
}
