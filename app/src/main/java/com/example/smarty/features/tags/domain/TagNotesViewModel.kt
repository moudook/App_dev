package com.example.smarty.features.tags.domain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.smarty.core.domain.model.NoteForTag
import com.example.smarty.core.domain.model.TagNotesResponse
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.remote.RemoteDataSource
import com.example.smarty.features.tags.data.TagRepository
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class TagNotesUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val tagName: String = "",
    val tagColor: String = "#6200EE",
)

class TagNotesViewModel(
    application: Application,
    private val tagId: String,
    tagName: String = "",
    tagColor: String = "#6200EE",
) : AndroidViewModel(application) {
    private val serverUrl = SecurePreferences.getInstance(application).getServerUrl()

    private val client =
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
                )
            }
        }

    private val remoteDataSource =
        RemoteDataSource(
            client = client,
            serverUrlProvider = { serverUrl },
            deviceIdProvider = { "android-client" },
        )

    private val repository = TagRepository(remoteDataSource)

    private val _uiState = MutableStateFlow(TagNotesUiState(tagName = tagName, tagColor = tagColor))
    val uiState: StateFlow<TagNotesUiState> = _uiState.asStateFlow()

    private val _notes = MutableStateFlow<List<NoteForTag>>(emptyList())
    val notes: StateFlow<List<NoteForTag>> = _notes.asStateFlow()

    init {
        loadNotes()
    }

    fun loadNotes() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response: TagNotesResponse? = repository.getNotesForTag(tagId)
                if (response?.success == true) {
                    _notes.value = response.notes
                    _uiState.value = _uiState.value.copy(isLoading = false)
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = response?.message ?: "Failed to load notes")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
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

    class Factory(
        private val application: Application,
        private val tagId: String,
        private val tagName: String = "",
        private val tagColor: String = "#6200EE",
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TagNotesViewModel(application, tagId, tagName, tagColor) as T
    }
}
