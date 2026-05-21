package com.example.smarty.features.runtime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarty.protocol.AgentEvent
import com.example.smarty.ui.components.timeline.TimelineNode
import com.example.smarty.ui.components.timeline.TimelineNodeAggregator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RuntimeUiState(
    val timelineNodes: List<TimelineNode> = emptyList(),
    val rawEvents: List<AgentEvent> = emptyList(),
    val isRunning: Boolean = false,
    val error: String? = null
)

class AgentRuntimeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(RuntimeUiState())
    val uiState: StateFlow<RuntimeUiState> = _uiState.asStateFlow()

    // Stateful aggregator — converts raw AgentEvents into stable TimelineNodes
    private val aggregator = TimelineNodeAggregator()

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
}
