package com.example.smarty.features.reasoning.domain

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
 * Reasoning ViewModel
 * Manages reasoning traces state and operations
 */
class ReasoningViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val client = HttpClient(OkHttp)
    private val serverUrl = SecurePreferences(application).getServerUrl()

    private val _uiState = MutableStateFlow(ReasoningUiState())
    val uiState: StateFlow<ReasoningUiState> = _uiState.asStateFlow()

    private val _reasoningTraces = MutableStateFlow<List<ReasoningTraceItem>>(emptyList())
    val reasoningTraces: StateFlow<List<ReasoningTraceItem>> = _reasoningTraces.asStateFlow()

    fun loadReasoningTraces(sessionId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val token = getFirebaseToken()
                if (token == null) {
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            error = "Not authenticated",
                        )
                    return@launch
                }

                val response: HttpResponse =
                    client.get("$serverUrl/api/reasoning/session/$sessionId/traces") {
                        header("Authorization", "Bearer $token")
                    }

                if (response.status.isSuccess()) {
                    val result: ReasoningTracesResponse = response.body()
                    _reasoningTraces.value = result.traces.map { it.toItem() }
                    _uiState.value = _uiState.value.copy(isLoading = false)
                } else {
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            error = "Failed to load reasoning traces",
                        )
                }
            } catch (e: Exception) {
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        error = e.message,
                    )
            }
        }
    }

    fun loadReasoningSummary(
        sessionId: String,
        messageId: String? = null,
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val token = getFirebaseToken()
                if (token == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Not authenticated")
                    return@launch
                }

                val url =
                    if (messageId != null) {
                        "$serverUrl/api/reasoning/session/$sessionId/summary?messageId=$messageId"
                    } else {
                        "$serverUrl/api/reasoning/session/$sessionId/summary"
                    }

                val response: HttpResponse =
                    client.get(url) {
                        header("Authorization", "Bearer $token")
                    }

                if (response.status.isSuccess()) {
                    val summary: ReasoningSummaryItem = response.body()
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            summary = summary,
                        )
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            } catch (e: Exception) {
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        error = e.message,
                    )
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

data class ReasoningUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val summary: ReasoningSummaryItem? = null,
)

@Serializable
data class ReasoningTracesResponse(
    val sessionId: String,
    val messageId: String?,
    val traces: List<ReasoningTraceResponse>,
    val totalSteps: Int = 0,
)

@Serializable
data class ReasoningTraceResponse(
    val traceId: String,
    val sessionId: String,
    val messageId: String?,
    val stepIndex: Int,
    val stepType: String,
    val title: String,
    val content: String,
    val confidenceScore: Double,
    val importanceScore: Double,
    val isFinal: Boolean,
    val wasRevised: Boolean,
    val durationMs: Long,
    val createdAt: String,
)

@Serializable
data class ReasoningSummaryItem(
    val summaryId: String,
    val sessionId: String,
    val messageId: String?,
    val oneLiner: String,
    val briefSummary: String,
    val detailedSummary: String,
    val totalSteps: Int,
    val totalDurationMs: Long,
    val totalTokens: Int,
    val confidenceScore: Double,
    val complexityScore: Double,
    val reasoningType: String,
    val tags: List<String>,
)

data class ReasoningTraceItem(
    val traceId: String,
    val sessionId: String,
    val stepIndex: Int,
    val stepType: String,
    val title: String,
    val content: String,
    val isFinal: Boolean,
    val durationMs: Long,
)

fun ReasoningTraceResponse.toItem(): ReasoningTraceItem =
    ReasoningTraceItem(
        traceId = traceId,
        sessionId = sessionId,
        stepIndex = stepIndex,
        stepType = stepType,
        title = title,
        content = content,
        isFinal = isFinal,
        durationMs = durationMs,
    )
