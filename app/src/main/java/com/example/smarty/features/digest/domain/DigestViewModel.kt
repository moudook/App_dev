package com.example.smarty.features.digest.domain

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
 * Digest Preferences ViewModel
 * Manages digest settings and operations
 */
class DigestViewModel(application: Application) : AndroidViewModel(application) {
    
    private val client = HttpClient(OkHttp)
    private val serverUrl = SecurePreferences(application).getServerUrl()
    
    private val _uiState = MutableStateFlow(DigestUiState())
    val uiState: StateFlow<DigestUiState> = _uiState.asStateFlow()
    
    private val _preferences = MutableStateFlow<DigestPreferences?>(null)
    val preferences: StateFlow<DigestPreferences?> = _preferences.asStateFlow()
    
    init {
        loadPreferences()
    }
    
    fun loadPreferences() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val token = getFirebaseToken()
                if (token == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Not authenticated")
                    return@launch
                }
                
                // For now, use default preferences since backend endpoint may not exist
                _preferences.value = DigestPreferences()
                _uiState.value = _uiState.value.copy(isLoading = false)
            } catch (e: Exception) {
                _preferences.value = DigestPreferences()
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }
    
    fun savePreferences(preferences: DigestPreferences) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                val token = getFirebaseToken()
                if (token == null) {
                    _uiState.value = _uiState.value.copy(isSaving = false, error = "Not authenticated")
                    return@launch
                }
                
                // Store locally for now
                _preferences.value = preferences
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    lastSavedAt = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message)
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

data class DigestUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val lastSavedAt: Long? = null,
    val error: String? = null
)
