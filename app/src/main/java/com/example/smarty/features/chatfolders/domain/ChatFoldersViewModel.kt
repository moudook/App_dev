package com.example.smarty.features.chatfolders.domain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarty.core.domain.model.ChatFolder
import com.example.smarty.core.domain.model.ChatFolderCreateResponse
import com.example.smarty.core.domain.model.ChatFoldersResponse
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.remote.RemoteDataSource
import com.example.smarty.features.chatfolders.data.ChatFoldersRepository
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class ChatFoldersUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
)

class ChatFoldersViewModel(application: Application) : AndroidViewModel(application) {

    private val serverUrl = SecurePreferences.getInstance(application).getServerUrl()

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    private val remoteDataSource = RemoteDataSource(
        client = client,
        serverUrlProvider = { serverUrl },
        deviceIdProvider = { "android-client" },
    )

    private val repository = ChatFoldersRepository(remoteDataSource)

    private val _uiState = MutableStateFlow(ChatFoldersUiState())
    val uiState: StateFlow<ChatFoldersUiState> = _uiState.asStateFlow()

    private val _folders = MutableStateFlow<List<ChatFolder>>(emptyList())
    val folders: StateFlow<List<ChatFolder>> = _folders.asStateFlow()

    init {
        loadFolders()
    }

    fun loadFolders() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response: ChatFoldersResponse? = repository.getFolders()
                if (response?.success == true) {
                    _folders.value = response.folders
                    _uiState.value = _uiState.value.copy(isLoading = false)
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = response?.message ?: "Failed to load folders")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun createFolder(name: String, color: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            try {
                val response: ChatFolderCreateResponse? = repository.createFolder(name, color, _folders.value.size)
                if (response?.success == true) {
                    loadFolders()
                    _uiState.value = _uiState.value.copy(isSaving = false)
                } else {
                    _uiState.value = _uiState.value.copy(isSaving = false, error = response?.message ?: "Failed to create folder")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message)
            }
        }
    }

    fun updateFolder(folder: ChatFolder) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            try {
                val response = repository.updateFolder(folder)
                if (response?.success == true) {
                    loadFolders()
                    _uiState.value = _uiState.value.copy(isSaving = false)
                } else {
                    _uiState.value = _uiState.value.copy(isSaving = false, error = response?.message ?: "Failed to update folder")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message)
            }
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            try {
                val response = repository.deleteFolder(folderId)
                if (response?.success == true) {
                    loadFolders()
                    _uiState.value = _uiState.value.copy(isSaving = false)
                } else {
                    _uiState.value = _uiState.value.copy(isSaving = false, error = response?.message ?: "Failed to delete folder")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message)
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun getFilteredFolders(): List<ChatFolder> {
        val query = _uiState.value.searchQuery.lowercase()
        if (query.isEmpty()) return _folders.value
        return _folders.value.filter { it.name.lowercase().contains(query) }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        client.close()
    }
}
