package com.example.smarty.features.searchhistory.domain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.features.searchhistory.ui.SearchHistoryItem
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
 * Search History ViewModel
 * Manages search history state and operations
 */
class SearchHistoryViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val client = HttpClient(OkHttp)
    private val serverUrl = SecurePreferences(application).getServerUrl()

    private val _uiState = MutableStateFlow(SearchHistoryUiState())
    val uiState: StateFlow<SearchHistoryUiState> = _uiState.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<SearchHistoryItem>>(emptyList())
    val searchHistory: StateFlow<List<SearchHistoryItem>> = _searchHistory.asStateFlow()

    init {
        loadSearchHistory()
    }

    fun loadSearchHistory(limit: Int = 20) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val token = getFirebaseToken()
                if (token == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Not authenticated")
                    return@launch
                }

                val response: HttpResponse =
                    client.get("$serverUrl/api/search/history?limit=$limit") {
                        header("Authorization", "Bearer $token")
                    }

                if (response.status.isSuccess()) {
                    val result: SearchHistoryResponse = response.body()
                    _searchHistory.value = result.history.map { it.toItem() }
                    _uiState.value = _uiState.value.copy(isLoading = false)
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Failed to load search history")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun addSearch(
        query: String,
        searchScope: String = "all",
        resultCount: Int = 0,
    ) {
        viewModelScope.launch {
            try {
                val token = getFirebaseToken()
                if (token == null) return@launch

                val request =
                    AddSearchRequest(
                        query = query,
                        searchScope = searchScope,
                        resultCount = resultCount,
                    )

                client.post("$serverUrl/api/search/history") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

                // Reload after adding
                loadSearchHistory()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val token = getFirebaseToken()
                if (token == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Not authenticated")
                    return@launch
                }

                val response: HttpResponse =
                    client.delete("$serverUrl/api/search/history/clear") {
                        header("Authorization", "Bearer $token")
                    }

                if (response.status.isSuccess()) {
                    _searchHistory.value = emptyList()
                    _uiState.value = _uiState.value.copy(isLoading = false)
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Failed to clear history")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            try {
                val token = getFirebaseToken()
                if (token == null) return@launch

                val response: HttpResponse =
                    client.delete("$serverUrl/api/search/history/$itemId") {
                        header("Authorization", "Bearer $token")
                    }

                if (response.status.isSuccess()) {
                    _searchHistory.value = _searchHistory.value.filter { it.id != itemId }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    private suspend fun getFirebaseToken(): String? =
        try {
            val user = FirebaseAuth.getInstance().currentUser
            user?.getIdToken(false)?.await()?.token
        } catch (e: Exception) {
            null
        }
}

data class SearchHistoryUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
)

@Serializable
data class SearchHistoryResponse(
    val success: Boolean,
    val history: List<SearchHistoryData> = emptyList(),
)

@Serializable
data class SearchHistoryData(
    val id: String,
    val userId: String,
    val query: String,
    val searchScope: String,
    val resultCount: Int,
    val createdAt: String,
)

@Serializable
data class AddSearchRequest(
    val query: String,
    val searchScope: String? = "all",
    val resultCount: Int? = 0,
)

fun SearchHistoryData.toItem(): SearchHistoryItem =
    SearchHistoryItem(
        id = id,
        query = query,
        scope = searchScope,
        resultCount = resultCount,
        timestamp =
            try {
                java.text
                    .SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                    .parse(createdAt)
                    ?.time ?: 0L
            } catch (e: Exception) {
                0L
            },
    )
