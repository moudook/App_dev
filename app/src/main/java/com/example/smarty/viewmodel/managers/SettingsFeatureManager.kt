package com.example.smarty.viewmodel.managers

import android.util.Log
import com.example.smarty.data.local.SecurePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    // --- Local Server Actions ---
    private val _isLocalPCEnabled = MutableStateFlow(securePreferences.isLocalPCEnabled())
    val isLocalPCEnabled: StateFlow<Boolean> = _isLocalPCEnabled.asStateFlow()

    private val _localServerIP = MutableStateFlow(securePreferences.getLocalPCIP())
    val localServerIP: StateFlow<String> = _localServerIP.asStateFlow()

    private val _localServerPort = MutableStateFlow(securePreferences.getLocalPCPort())
    val localServerPort: StateFlow<String> = _localServerPort.asStateFlow()

    private val _localServerUseHttps = MutableStateFlow(securePreferences.getLocalPCUseHttps())
    val localServerUseHttps: StateFlow<Boolean> = _localServerUseHttps.asStateFlow()

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
                    .addHeader("ngrok-skip-browser-warning", "true")
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
