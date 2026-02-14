package com.example.smarty.features.settings.domain

import android.util.Log
import com.example.smarty.data.local.SecurePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Centralized manager for application settings and user preferences.
 * Handles logic for:
 * - Connection to the Smarty Server
 * - Local LLM server connectivity (USB/WiFi)
 * - UI preferences (Theme, Shake sensitivity)
 * - App lifecycle flags (First launch)
 *
 * This manager ensures that UI settings screens and the AI Agent use the same preference source.
 */
class SettingsFeatureManager(
    private val securePreferences: SecurePreferences,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "SettingsFeatureManager"
    }

    // --- Cache State ---
    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    private val _isClearingCache = MutableStateFlow(false)
    val isClearingCache: StateFlow<Boolean> = _isClearingCache.asStateFlow()

    init {
        updateCacheSize()
    }

    // --- Cache Actions ---
    fun updateCacheSize() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val size = calculateCacheSize()
            _cacheSizeBytes.value = size
        }
    }

    fun clearCache() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isClearingCache.value = true
            try {
                val cacheDir = securePreferences.getContext().cacheDir
                cacheDir.deleteRecursively()
                cacheDir.mkdirs()
                updateCacheSize()
            } finally {
                _isClearingCache.value = false
            }
        }
    }

    private fun calculateCacheSize(): Long {
        return try {
            val cacheDir = securePreferences.getContext().cacheDir
            getFolderSize(cacheDir)
        } catch (e: Exception) {
            0L
        }
    }

    private fun getFolderSize(file: java.io.File): Long {
        var size: Long = 0
        if (file.exists()) {
            val files = file.listFiles()
            if (files != null) {
                for (f in files) {
                    size += if (f.isDirectory) getFolderSize(f) else f.length()
                }
            }
        }
        return size
    }

    // --- Server Settings (Remote Only) ---
    private val _serverUrl = MutableStateFlow(securePreferences.getServerUrl())
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    fun setServerUrl(url: String) {
        securePreferences.setServerUrl(url)
        _serverUrl.value = url
    }

    // --- AI Strategy ---
    private val _providerStrategy = MutableStateFlow(securePreferences.getProviderStrategy())
    val providerStrategy: StateFlow<String> = _providerStrategy.asStateFlow()

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

    // --- Server Settings Actions ---
    // Deprecated IP/Port actions removed


    // --- AI Strategy Actions ---
    fun setProviderStrategy(strategy: String) {
        securePreferences.setProviderStrategy(strategy)
        _providerStrategy.value = strategy
    }

    fun getSmartyServerUrl(): String {
        return securePreferences.getSmartyServerUrl()
    }

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

    /**
     * Result of testing connection to local LLM server
     */
    sealed class LocalServerTestResult {
        data object Success : LocalServerTestResult()
        data class Failure(val message: String) : LocalServerTestResult()
    }

    /**
     * Test connection to Smarty Server
     */
    suspend fun testServerConnection(url: String): LocalServerTestResult {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Ensure URL has protocol
                var testUrl = url.trim()
                if (!testUrl.startsWith("http")) {
                    testUrl = "http://$testUrl" // Default to http for local IPs
                }

                // Remove existing endpoints if user pasted full URL
                val baseUrl = testUrl
                    .removeSuffix("/")
                    .removeSuffix("/v1/models")
                    .removeSuffix("/health")

                val fullUrl = "$baseUrl/health"
                Log.d(TAG, "Testing connection to: $fullUrl")

                val useHttps = testUrl.startsWith("https")

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
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS) // Increased to 10s
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                } else {
                    okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS) // Increased to 10s
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                }

                val request = okhttp3.Request.Builder()
                    .url(fullUrl)
                    .addHeader("ngrok-skip-browser-warning", "true")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        // Check body for status: ok
                        val body = response.body?.string() ?: ""
                        if (body.contains("ok", ignoreCase = true)) {
                            Log.d(TAG, "Connection successful: $body")
                            LocalServerTestResult.Success
                        } else {
                            // If 200 OK but unexpected body, we consider it a success but maybe with a warning?
                            // For now, treat 200 OK as success as the server is reachable
                            Log.d(TAG, "Connection successful (unexpected body): $body")
                            LocalServerTestResult.Success
                        }
                    } else {
                        Log.e(TAG, "Connection failed with code: ${response.code}")
                        LocalServerTestResult.Failure("server_returned:_${response.code}")
                    }
                }
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "Connection refused", e)
                LocalServerTestResult.Failure("Connection refused - is server running?")
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "Connection timeout", e)
                LocalServerTestResult.Failure("Timeout - check IP and firewall")
            } catch (e: java.net.UnknownHostException) {
                Log.e(TAG, "Unknown host", e)
                LocalServerTestResult.Failure("Invalid URL - check hostname")
            } catch (e: javax.net.ssl.SSLHandshakeException) {
                Log.e(TAG, "SSL Handshake failed", e)
                LocalServerTestResult.Failure("SSL failed - check certificates")
            } catch (e: javax.net.ssl.SSLException) {
                Log.e(TAG, "SSL Exception", e)
                LocalServerTestResult.Failure("SSL error - check port (usually 443 for HTTPS)")
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                val msg = e.message?.lowercase() ?: ""
                when {
                    msg.contains("ssl") || msg.contains("tls") ->
                        LocalServerTestResult.Failure("SSL/TLS error - try HTTP mode")
                    msg.contains("certificate") ->
                        LocalServerTestResult.Failure("Certificate error")
                    msg.contains("reset") || msg.contains("closed") ->
                        LocalServerTestResult.Failure("Connection reset - wrong port?")
                    else ->
                        LocalServerTestResult.Failure("Error: ${e.message?.take(50) ?: "unknown"}")
                }
            }
        }
    }
}


