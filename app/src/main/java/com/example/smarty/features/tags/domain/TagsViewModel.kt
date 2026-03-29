package com.example.smarty.features.tags.domain

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
 * Tags ViewModel
 * Manages tags state and CRUD operations
 */
class TagsViewModel(application: Application) : AndroidViewModel(application) {
    
    private val client = HttpClient(OkHttp)
    private val serverUrl = SecurePreferences(application).getServerUrl()
    
    private val _uiState = MutableStateFlow(TagsUiState())
    val uiState: StateFlow<TagsUiState> = _uiState.asStateFlow()
    
    private val _tags = MutableStateFlow<List<TagItem>>(emptyList())
    val tags: StateFlow<List<TagItem>> = _tags.asStateFlow()
    
    init {
        loadTags()
    }
    
    fun loadTags() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val token = getFirebaseToken()
                if (token == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Not authenticated")
                    return@launch
                }
                
                val response: HttpResponse = client.get("$serverUrl/api/tags") {
                    header("Authorization", "Bearer $token")
                }
                
                if (response.status.isSuccess()) {
                    val result: TagsResponse = response.body()
                    _tags.value = result.tags.map { it.toItem() }
                    _uiState.value = _uiState.value.copy(isLoading = false)
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Failed to load tags")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }
    
    fun createTag(name: String, color: String = "#6200EE") {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                val token = getFirebaseToken()
                if (token == null) {
                    _uiState.value = _uiState.value.copy(isSaving = false, error = "Not authenticated")
                    return@launch
                }
                
                val tag = TagCreateRequest(
                    name = name,
                    color = color
                )
                
                val response: HttpResponse = client.post("$serverUrl/api/tags") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(tag)
                }
                
                if (response.status.isSuccess()) {
                    loadTags()
                    _uiState.value = _uiState.value.copy(isSaving = false)
                } else {
                    _uiState.value = _uiState.value.copy(isSaving = false, error = "Failed to create tag")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message)
            }
        }
    }
    
    fun deleteTag(tagId: String) {
        viewModelScope.launch {
            try {
                val token = getFirebaseToken()
                if (token == null) return@launch
                
                val response: HttpResponse = client.delete("$serverUrl/api/tags/$tagId") {
                    header("Authorization", "Bearer $token")
                }
                
                if (response.status.isSuccess()) {
                    loadTags()
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

data class TagsUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null
)

@Serializable
data class TagsResponse(
    val success: Boolean,
    val tags: List<TagItem> = emptyList()
)

@Serializable
data class TagItem(
    val id: String,
    val userId: String,
    val name: String,
    val color: String,
    val usageCount: Int,
    val createdAt: String?
)

@Serializable
data class TagCreateRequest(
    val name: String,
    val color: String = "#6200EE"
)

fun TagItem.toItem(): TagItem = this
