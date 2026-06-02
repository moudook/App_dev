package com.example.smarty.features.notifications.domain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarty.core.domain.model.Notification
import com.example.smarty.core.domain.model.NotificationsResponse
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

data class NotificationsUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val unreadCount: Int = 0,
)

class NotificationsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val client = HttpClient(OkHttp)
    private val serverUrl = SecurePreferences(application).getServerUrl()

    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

    private val _notifications = MutableStateFlow<List<Notification>>(emptyList())
    val notifications: StateFlow<List<Notification>> = _notifications.asStateFlow()

    init {
        loadNotifications()
    }

    private suspend fun getFirebaseToken(): String? =
        try {
            val user = FirebaseAuth.getInstance().currentUser
            user?.getIdToken(false)?.await()?.token
        } catch (e: Exception) {
            null
        }

    fun loadNotifications() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val token = getFirebaseToken()
                if (token == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Not authenticated")
                    return@launch
                }

                val response: HttpResponse =
                    client.get("$serverUrl/api/notifications") {
                        header("Authorization", "Bearer $token")
                    }

                if (response.status.isSuccess()) {
                    val result: NotificationsResponse = response.body()
                    _notifications.value = result.notifications
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            unreadCount = result.notifications.count { !it.isRead },
                        )
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Failed to load notifications")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun loadUnreadNotifications() {
        viewModelScope.launch {
            try {
                val token = getFirebaseToken()
                if (token == null) return@launch

                val response: HttpResponse =
                    client.get("$serverUrl/api/notifications/unread") {
                        header("Authorization", "Bearer $token")
                    }

                if (response.status.isSuccess()) {
                    val result: NotificationsResponse = response.body()
                    _notifications.value = result.notifications
                    _uiState.value =
                        _uiState.value.copy(
                            unreadCount = result.notifications.count { !it.isRead },
                        )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun markAsRead(notificationId: String) {
        viewModelScope.launch {
            try {
                val token = getFirebaseToken()
                if (token == null) return@launch

                val response: HttpResponse =
                    client.post("$serverUrl/api/notifications/$notificationId/read") {
                        header("Authorization", "Bearer $token")
                    }

                if (response.status.isSuccess()) {
                    _notifications.value =
                        _notifications.value.map {
                            if (it.id == notificationId) it.copy(isRead = true) else it
                        }
                    _uiState.value =
                        _uiState.value.copy(
                            unreadCount = _notifications.value.count { n -> !n.isRead },
                        )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            try {
                val token = getFirebaseToken()
                if (token == null) return@launch

                val response: HttpResponse =
                    client.post("$serverUrl/api/notifications/read-all") {
                        header("Authorization", "Bearer $token")
                    }

                if (response.status.isSuccess()) {
                    _notifications.value = _notifications.value.map { it.copy(isRead = true) }
                    _uiState.value = _uiState.value.copy(unreadCount = 0)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun deleteNotification(notificationId: String) {
        viewModelScope.launch {
            try {
                val token = getFirebaseToken()
                if (token == null) return@launch

                val response: HttpResponse =
                    client.delete("$serverUrl/api/notifications/$notificationId") {
                        header("Authorization", "Bearer $token")
                    }

                if (response.status.isSuccess()) {
                    _notifications.value = _notifications.value.filter { it.id != notificationId }
                    _uiState.value =
                        _uiState.value.copy(
                            unreadCount = _notifications.value.count { n -> !n.isRead },
                        )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        client.close()
    }
}
