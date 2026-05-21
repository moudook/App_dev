package com.example.smarty.features.runtime

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarty.core.common.util.HttpClientProvider
import com.example.smarty.core.common.util.buildJsonBody
import com.example.smarty.data.local.SecurePreferences
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.smarty.protocol.AgentEvent
import com.example.smarty.ui.components.timeline.TimelineNode
import com.example.smarty.ui.components.timeline.TimelineNodeAggregator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RuntimeUiState(
    val timelineNodes: List<TimelineNode> = emptyList(),
    val rawEvents: List<AgentEvent> = emptyList(),
    val isRunning: Boolean = false,
    val error: String? = null
)

class AgentRuntimeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(RuntimeUiState())
    val uiState: StateFlow<RuntimeUiState> = _uiState.asStateFlow()

    // Stateful aggregator — converts raw AgentEvents into stable TimelineNodes
    private val aggregator = TimelineNodeAggregator()

    // Cached dependencies
    private val okHttpClient = HttpClientProvider.default
    private val securePreferences = SecurePreferences.getInstance(application)

    fun startRun(query: String) {
        viewModelScope.launch {
            aggregator.reset()
            _uiState.update {
                it.copy(
                    isRunning = true,
                    error = null,
                    rawEvents = emptyList(),
                    timelineNodes = emptyList()
                )
            }
            // TODO: Wire up actual Server connection to /runtime/stream
        }
    }

    /**
     * Process a raw AgentEvent from the SSE stream. Updates the timeline in-place.
     */
    fun handleIncomingEvent(event: AgentEvent) {
        val updatedEvents = _uiState.value.rawEvents + event
        aggregator.process(event)
        _uiState.update { state ->
            state.copy(
                rawEvents = updatedEvents,
                timelineNodes = aggregator.nodes.toList()
            )
        }
    }

    /**
     * Send approval/deny decision back to the server so the suspended MCP tool can resume.
     */
    fun sendApproval(toolId: String, approved: Boolean, feedback: String? = null) {
        viewModelScope.launch {
            try {
                val serverUrl = securePreferences.getSmartyServerUrl()
                val token = withContext(Dispatchers.IO) {
                    runCatching {
                        FirebaseAuth.getInstance().currentUser
                            ?.getIdToken(false)
                            ?.await()
                            ?.token
                    }.getOrNull()
                }
                val url = "$serverUrl/api/v1/chat/events/approval"

                val jsonBody = buildJsonBody(
                    "toolId" to toolId,
                    "approved" to approved,
                    "feedback" to feedback,
                )

                withContext(Dispatchers.IO) {
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .post(jsonBody.toRequestBody(HttpClientProvider.JSON_MEDIA_TYPE))
                        .apply {
                            if (token != null) {
                                addHeader("Authorization", "Bearer $token")
                            }
                        }
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        Log.i(TAG, "Approval sent: $approved for $toolId -> ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send approval for $toolId", e)
            }
        }
    }

    companion object {
        private const val TAG = "AgentRuntimeVM"
    }
}
