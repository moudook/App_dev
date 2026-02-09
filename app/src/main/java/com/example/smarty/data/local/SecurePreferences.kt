package com.example.smarty.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.smarty.data.local.AIConnection

/**
 * Available models for the Local LLM connection.
 */
object AIModels {
    // Local PC models - Run AI locally on your computer
    val LOCAL_PC_MODELS = listOf(
        "chatglm3-6b-128k" to "ChatGLM3 6B 128K (Full)",
        "qwen2.5-3b-instruct" to "Qwen 2.5 3B Instruct (Default)",
        "qwen2.5-7b-instruct" to "Qwen 2.5 7B Instruct",
        "qwen2.5-14b-instruct" to "Qwen 2.5 14B Instruct",
        "llama-3.2-3b-instruct" to "Llama 3.2 3B Instruct",
        "phi-3-mini-4k-instruct" to "Phi-3 Mini 4K Instruct",
        "gemma-2-2b-it" to "Gemma 2 2B IT",
        "mistral-7b-instruct-v0.3" to "Mistral 7B Instruct v0.3"
    )
    const val LOCAL_PC_DEFAULT = "chatglm3-6b-128k"

    fun getModelsForConnection(connection: AIConnection): List<Pair<String, String>> {
        return when (connection) {
            AIConnection.LOCAL_PC -> LOCAL_PC_MODELS
        }
    }

    fun getDefaultModel(connection: AIConnection): String {
        return when (connection) {
            AIConnection.LOCAL_PC -> LOCAL_PC_DEFAULT
        }
    }
}

/**
 * Secure storage for application preferences and sensitive settings.
 * Thin Client Version: Only manages local settings and Local LLM connection configuration.
 */
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
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
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

    fun getConnectionPriority(): List<AIConnection> {
        return listOf(AIConnection.LOCAL_PC)
    }

    companion object {
        private const val KEY_FIRST_LAUNCH = "first_launch"
        private const val KEY_SHAKE_SENSITIVITY = "shake_sensitivity"
        private const val KEY_LOCAL_PC_MODEL = "local_pc_model"
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

        // Local PC USB/WiFi Tethering
        private const val KEY_LOCAL_PC_IP = "local_pc_ip"
        private const val KEY_LOCAL_PC_PORT = "local_pc_port"
        private const val KEY_LOCAL_PC_USE_HTTPS = "local_pc_use_https"
        private const val KEY_LOCAL_PC_ENABLED = "local_pc_enabled"

        private const val DEFAULT_LOCAL_PC_IP = "largest-camron-usuriously.ngrok-free.dev"
        private const val DEFAULT_LOCAL_PC_PORT = "443"
        private const val DEFAULT_LOCAL_PC_USE_HTTPS = true
        private const val DEFAULT_LOCAL_PC_ENABLED = true

        // Google Calendar Sync
        private const val KEY_SYNC_TO_GOOGLE_CALENDAR = "sync_to_google_calendar"
        private const val KEY_TARGET_GOOGLE_CALENDAR_ID = "target_google_calendar_id"

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
        return isLocalPCEnabled()
    }

    fun getSelectedModel(connection: AIConnection): String {
        return encryptedPrefs.getString(
            KEY_LOCAL_PC_MODEL,
            AIModels.getDefaultModel(connection)
        ) ?: AIModels.getDefaultModel(connection)
    }

    fun setSelectedModel(connection: AIConnection, model: String) {
        encryptedPrefs.edit().putString(KEY_LOCAL_PC_MODEL, model).apply()
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
        if (email.isNullOrBlank()) encryptedPrefs.edit().remove(KEY_GOOGLE_ACCOUNT_EMAIL).apply()
        else encryptedPrefs.edit().putString(KEY_GOOGLE_ACCOUNT_EMAIL, email).apply()
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

    // Local PC Settings
    fun getLocalPCIP(): String = encryptedPrefs.getString(KEY_LOCAL_PC_IP, DEFAULT_LOCAL_PC_IP) ?: DEFAULT_LOCAL_PC_IP
    fun setLocalPCIP(ip: String) = encryptedPrefs.edit().putString(KEY_LOCAL_PC_IP, ip.trim()).apply()
    fun getLocalPCPort(): String = encryptedPrefs.getString(KEY_LOCAL_PC_PORT, DEFAULT_LOCAL_PC_PORT) ?: DEFAULT_LOCAL_PC_PORT
    fun setLocalPCPort(port: String) {
        val portNum = port.trim().toIntOrNull()
        if (portNum != null && portNum in 1..65535) {
            encryptedPrefs.edit().putString(KEY_LOCAL_PC_PORT, port.trim()).apply()
        }
    }
    fun getLocalPCUseHttps(): Boolean = encryptedPrefs.getBoolean(KEY_LOCAL_PC_USE_HTTPS, DEFAULT_LOCAL_PC_USE_HTTPS)
    fun setLocalPCUseHttps(useHttps: Boolean) = encryptedPrefs.edit().putBoolean(KEY_LOCAL_PC_USE_HTTPS, useHttps).apply()
    fun isLocalPCEnabled(): Boolean = encryptedPrefs.getBoolean(KEY_LOCAL_PC_ENABLED, DEFAULT_LOCAL_PC_ENABLED)
    fun setLocalPCEnabled(enabled: Boolean) {
        encryptedPrefs.edit().putBoolean(KEY_LOCAL_PC_ENABLED, enabled).apply()
    }

    fun getLocalPCUrl(): String {
        val protocol = if (getLocalPCUseHttps()) "https" else "http"
        return "$protocol://${getLocalPCIP()}:${getLocalPCPort()}/v1/chat/completions"
    }

    fun getSmartyServerUrl(): String {
        val protocol = if (getLocalPCUseHttps()) "https" else "http"
        return "$protocol://${getLocalPCIP()}:${getLocalPCPort()}"
    }

    fun getAvailableModels(connection: AIConnection): List<Pair<String, String>> {
        return AIModels.getModelsForConnection(connection)
    }
}
