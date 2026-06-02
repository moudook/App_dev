package com.example.smarty.features.digest.domain

import android.app.Application
import android.util.Log
import com.example.smarty.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json

/**
 * Feature manager for the Digest system.
 * Handles API calls to the server and manages digest state.
 */
class DigestFeatureManager(
    private val application: Application,
    private val serverUrlProvider: () -> String,
    private val deviceIdProvider: () -> String,
) {
    companion object {
        private const val TAG = "DigestFeatureManager"
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

    private val httpClient =
        HttpClient {
            install(ContentNegotiation) {
                this.json(json)
            }
        }

    // State
    private val _digests = MutableStateFlow<List<DigestResult>>(emptyList())
    val digests: StateFlow<List<DigestResult>> = _digests.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _preferences = MutableStateFlow(DigestPreferences())
    val preferences: StateFlow<DigestPreferences> = _preferences.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private suspend fun getFirebaseToken(): String? =
        try {
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null) {
                val tokenResult = user.getIdToken(false).await()
                tokenResult.token
            } else {
                Log.w(TAG, "No Firebase user signed in")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Firebase token: ${e.message}")
            null
        }

    private fun getDeviceId(): String =
        try {
            deviceIdProvider()
        } catch (e: Exception) {
            "smarty-unknown"
        }

    /**
     * Fetch all digests for the current user.
     */
    suspend fun fetchDigests(limit: Int = 30) {
        _isLoading.value = true
        _error.value = null

        try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            if (token == null) {
                _error.value = "Not authenticated"
                _isLoading.value = false
                return
            }

            val response =
                httpClient.get("$baseUrl/digests?limit=$limit") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("X-Smarty-Version", BuildConfig.VERSION_NAME)
                    header("X-Smarty-Device-Id", getDeviceId())
                }

            if (response.status.isSuccess()) {
                val responseBody: String = response.body()
                val digestList = json.decodeFromString<DigestListResponse>(responseBody)
                _digests.value = digestList.digests
                Log.d(TAG, "Fetched ${digestList.digests.size} digests")
            } else {
                _error.value = "Failed to fetch digests: ${response.status}"
                Log.e(TAG, "Failed to fetch digests: ${response.status}")
            }
        } catch (e: Exception) {
            _error.value = "Error fetching digests: ${e.message}"
            Log.e(TAG, "Error fetching digests", e)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Fetch a specific digest by ID.
     */
    suspend fun fetchDigestById(digestId: String): DigestResult? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response =
                httpClient.get("$baseUrl/digests/$digestId") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("X-Smarty-Version", BuildConfig.VERSION_NAME)
                    header("X-Smarty-Device-Id", getDeviceId())
                }

            if (response.status.isSuccess()) {
                val responseBody: String = response.body()
                json.decodeFromString<DigestResult>(responseBody)
            } else {
                Log.e(TAG, "Failed to fetch digest $digestId: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching digest $digestId", e)
            null
        }
    }

    /**
     * Manually trigger digest generation.
     */
    suspend fun triggerDigest(type: String = "daily"): TriggerDigestResponse? {
        _isLoading.value = true

        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            if (token == null) {
                _error.value = "Not authenticated"
                _isLoading.value = false
                return null
            }

            val response =
                httpClient.post("$baseUrl/digests/trigger") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("X-Smarty-Version", BuildConfig.VERSION_NAME)
                    header("X-Smarty-Device-Id", getDeviceId())
                    contentType(ContentType.Application.Json)
                    setBody(TriggerDigestRequest(type = type))
                }

            if (response.status.isSuccess()) {
                val responseBody: String = response.body()
                val result = json.decodeFromString<TriggerDigestResponse>(responseBody)

                // Refresh digests if successful
                if (result.success && result.digest != null) {
                    fetchDigests()
                }

                result
            } else {
                _error.value = "Failed to trigger digest: ${response.status}"
                null
            }
        } catch (e: Exception) {
            _error.value = "Error triggering digest: ${e.message}"
            Log.e(TAG, "Error triggering digest", e)
            null
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Fetch user preferences.
     */
    suspend fun fetchPreferences() {
        try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return

            val response =
                httpClient.get("$baseUrl/digests/preferences") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("X-Smarty-Version", BuildConfig.VERSION_NAME)
                    header("X-Smarty-Device-Id", getDeviceId())
                }

            if (response.status.isSuccess()) {
                val responseBody: String = response.body()
                _preferences.value = json.decodeFromString<DigestPreferences>(responseBody)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching preferences", e)
        }
    }

    /**
     * Update user preferences.
     */
    suspend fun updatePreferences(request: UpdatePreferencesRequest): Boolean {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return false

            val response =
                httpClient.put("$baseUrl/digests/preferences") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("X-Smarty-Version", BuildConfig.VERSION_NAME)
                    header("X-Smarty-Device-Id", getDeviceId())
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            if (response.status.isSuccess()) {
                // Refresh preferences
                fetchPreferences()
                true
            } else {
                _error.value = "Failed to update preferences: ${response.status}"
                false
            }
        } catch (e: Exception) {
            _error.value = "Error updating preferences: ${e.message}"
            Log.e(TAG, "Error updating preferences", e)
            false
        }
    }

    /**
     * Get the most recent digest.
     */
    fun getMostRecentDigest(): DigestResult? = _digests.value.firstOrNull()

    /**
     * Get digests by type.
     */
    fun getDigestsByType(type: String): List<DigestResult> = _digests.value.filter { it.digestType == type }

    /**
     * Clear error.
     */
    fun clearError() {
        _error.value = null
    }
}
