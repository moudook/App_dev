package com.example.smarty.features.devices.domain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarty.data.local.SecurePreferences
import com.google.firebase.auth.FirebaseAuth
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable

/**
 * Device Management ViewModel
 * Manages registered devices state and operations
 */
class DeviceViewModel(application: Application) : AndroidViewModel(application) {
    private val client = HttpClient(OkHttp)
    private val serverUrl = SecurePreferences(application).getServerUrl()

    private val _uiState = MutableStateFlow(DeviceUiState())
    val uiState: StateFlow<DeviceUiState> = _uiState.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceItem>>(emptyList())
    val devices: StateFlow<List<DeviceItem>> = _devices.asStateFlow()

    init {
        loadDevices()
    }

    fun loadDevices() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val token = getFirebaseToken()
                if (token == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Not authenticated")
                    return@launch
                }

                val response: HttpResponse =
                    client.get("$serverUrl/api/devices") {
                        header("Authorization", "Bearer $token")
                    }

                if (response.status.isSuccess()) {
                    val result: DevicesResponse = response.body()
                    _devices.value = result.devices.map { it.toItem() }
                    _uiState.value = _uiState.value.copy(isLoading = false)
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Failed to load devices")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun registerDevice(
        deviceName: String,
        deviceType: String = "android",
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                val token = getFirebaseToken()
                if (token == null) {
                    _uiState.value = _uiState.value.copy(isSaving = false, error = "Not authenticated")
                    return@launch
                }

                val request =
                    RegisterDeviceRequest(
                        deviceName = deviceName,
                        deviceType = deviceType,
                    )

                val response: HttpResponse =
                    client.post("$serverUrl/api/devices/register") {
                        header("Authorization", "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(request)
                    }

                if (response.status.isSuccess()) {
                    loadDevices()
                    _uiState.value = _uiState.value.copy(isSaving = false)
                } else {
                    _uiState.value = _uiState.value.copy(isSaving = false, error = "Failed to register device")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message)
            }
        }
    }

    fun removeDevice(deviceId: String) {
        viewModelScope.launch {
            try {
                val token = getFirebaseToken()
                if (token == null) return@launch

                val response: HttpResponse =
                    client.delete("$serverUrl/api/devices/$deviceId") {
                        header("Authorization", "Bearer $token")
                    }

                if (response.status.isSuccess()) {
                    loadDevices()
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    private suspend fun getFirebaseToken(): String? {
        return try {
            val user = FirebaseAuth.getInstance().currentUser
            user?.getIdToken(false)?.await()?.token
        } catch (e: Exception) {
            null
        }
    }
}

data class DeviceUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
)

@Serializable
data class DevicesResponse(
    val success: Boolean,
    val devices: List<DeviceItem> = emptyList(),
)

@Serializable
data class DeviceItem(
    val id: String,
    val userId: String,
    val deviceName: String?,
    val deviceType: String?,
    val pushToken: String?,
    val lastActiveAt: String?,
    val appVersion: String?,
)

@Serializable
data class RegisterDeviceRequest(
    val deviceName: String,
    val deviceType: String = "android",
)

fun DeviceItem.toItem(): DeviceItem = this

// Conversion to UI model
fun DeviceItem.toUiModel(): com.example.smarty.features.devices.ui.DeviceItem {
    return com.example.smarty.features.devices.ui.DeviceItem(
        id = id,
        name = deviceName ?: "Unknown Device",
        type = deviceType ?: "unknown",
        lastActive = lastActiveAt ?: "Unknown",
    )
}
