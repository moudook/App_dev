package com.example.smarty.features.notifications.domain

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
 * Notifications ViewModel
 * Manages notifications state and operations
 */
class NotificationsViewModel(application: Application) : AndroidViewModel(application) {
    
    private val client = HttpClient(OkHttp)
    private val serverUrl = SecurePreferences(application).getServerUrl()
    
    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()
    
    private val _notifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val notifications: StateFlow<List<NotificationItem>> = _notifications.asStateFlow()
    
    init {
        loadNotifications()
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
                
                val response: HttpResponse = client.get("$serverUrl/api/notifications") {
                    header("Authorization", "Bearer $token")
                }
                
                if (response.status.isSuccess()) {
                    val result: NotificationsResponse = response.body()
                    _notifications.value = result.notifications.map { it.toItem() }
                    _uiState.value = _uiState.value.copy(isLoading = false)
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
                
                val response: HttpResponse = client.get("$serverUrl/api/notifications/unread") {
                    header("Authorization", "Bearer $token")
                }
                
                if (response.status.isSuccess()) {
                    val result: NotificationsResponse = response.body()
                    _notifications.value = result.notifications.map { it.toItem() }
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
                
                val response: HttpResponse = client.post("$serverUrl/api/notifications/$notificationId/read") {
                    header("Authorization", "Bearer $token")
                }
                
                if (response.status.isSuccess()) {
                    loadNotifications()
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
                
                val response: HttpResponse = client.post("$serverUrl/api/notifications/read-all") {
                    header("Authorization", "Bearer $token")
                }
                
                if (response.status.isSuccess()) {
                    loadNotifications()
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
                
                val response: HttpResponse = client.delete("$serverUrl/api/notifications/$notificationId") {
                    header("Authorization", "Bearer $token")
                }
                
                if (response.status.isSuccess()) {
                    loadNotifications()
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

data class NotificationsUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val unreadCount: Int = 0
)

@Serializable
data class NotificationsResponse(
    val success: Boolean,
    val notifications: List<NotificationItem> = emptyList()
)

@Serializable
data class NotificationItem(
    val id: String,
    val userId: String,
    val type: String,
    val title: String,
    val body: String?,
    val isRead: Boolean,
    val createdAt: String?
)

fun NotificationItem.toItem(): NotificationItem = this
