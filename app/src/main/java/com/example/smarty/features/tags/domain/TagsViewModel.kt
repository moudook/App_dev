package com.example.smarty.features.tags.domain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarty.core.domain.model.Tag
import com.example.smarty.core.domain.model.TagCreateResponse
import com.example.smarty.core.domain.model.TagResponse
import com.example.smarty.core.domain.model.TagsResponse
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.remote.RemoteDataSource
import com.example.smarty.features.tags.data.TagRepository
import com.google.firebase.auth.FirebaseAuth
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json

data class TagsUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val selectedTagType: TagTypeFilter = TagTypeFilter.ALL,
)

enum class TagTypeFilter {
    ALL, MANUAL, AUTO, AI
}

class TagsViewModel(application: Application) : AndroidViewModel(application) {

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

    private val repository = TagRepository(remoteDataSource)

    private val _uiState = MutableStateFlow(TagsUiState())
    val uiState: StateFlow<TagsUiState> = _uiState.asStateFlow()

    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    val tags: StateFlow<List<Tag>> = _tags.asStateFlow()

    init {
        loadTags()
    }

    fun loadTags() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response: TagsResponse? = repository.getTags()
                if (response?.success == true) {
                    _tags.value = response.tags
                    _uiState.value = _uiState.value.copy(isLoading = false)
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = response?.message ?: "Failed to load tags")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun createTag(name: String, color: String, tagType: String = Tag.TYPE_MANUAL) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            try {
                val response: TagCreateResponse? = repository.createTag(name, color, tagType)
                if (response?.success == true) {
                    loadTags()
                    _uiState.value = _uiState.value.copy(isSaving = false)
                } else {
                    _uiState.value = _uiState.value.copy(isSaving = false, error = response?.message ?: "Failed to create tag")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message)
            }
        }
    }

    fun updateTag(tag: Tag) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            try {
                val response: TagResponse? = repository.updateTag(tag)
                if (response?.success == true) {
                    loadTags()
                    _uiState.value = _uiState.value.copy(isSaving = false)
                } else {
                    _uiState.value = _uiState.value.copy(isSaving = false, error = response?.message ?: "Failed to update tag")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message)
            }
        }
    }

    fun deleteTag(tagId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            try {
                val response: TagResponse? = repository.deleteTag(tagId)
                if (response?.success == true) {
                    loadTags()
                    _uiState.value = _uiState.value.copy(isSaving = false)
                } else {
                    _uiState.value = _uiState.value.copy(isSaving = false, error = response?.message ?: "Failed to delete tag")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message)
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun setTagTypeFilter(filter: TagTypeFilter) {
        _uiState.value = _uiState.value.copy(selectedTagType = filter)
    }

    fun getFilteredTags(): List<Tag> {
        val query = _uiState.value.searchQuery.lowercase()
        val typeFilter = _uiState.value.selectedTagType

        return _tags.value.filter { tag ->
            val matchesQuery = query.isEmpty() || tag.name.lowercase().contains(query)
            val matchesType = when (typeFilter) {
                TagTypeFilter.ALL -> true
                TagTypeFilter.MANUAL -> tag.tagType == Tag.TYPE_MANUAL
                TagTypeFilter.AUTO -> tag.tagType == Tag.TYPE_AUTO
                TagTypeFilter.AI -> tag.tagType == Tag.TYPE_AI
            }
            matchesQuery && matchesType
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
