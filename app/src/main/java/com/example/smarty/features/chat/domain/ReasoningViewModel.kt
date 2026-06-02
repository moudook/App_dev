package com.example.smarty.features.chat.domain

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarty.data.model.ProgressiveDisclosureResponse
import com.example.smarty.data.model.ReasoningTimelineResponse
import com.example.smarty.data.model.ReasoningTrace
import com.example.smarty.data.remote.RemoteDataSource
import com.example.smarty.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for reasoning traces and thinking logs.
 *
 * Manages UI state for displaying AI reasoning process with progressive disclosure.
 */
class ReasoningViewModel(
    application: Application,
) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "ReasoningViewModel"
    }

    // Remote data source
    private val remoteDataSource: RemoteDataSource by lazy {
        ServiceLocator.provideRemoteDataSource(getApplication())
    }

    // UI State
    data class ReasoningUiState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val sessionId: String? = null,
        val timeline: ReasoningTimelineResponse? = null,
        val traces: List<ReasoningTrace> = emptyList(),
        val disclosure: ProgressiveDisclosureResponse? = null,
        val disclosureLevel: DisclosureLevel = DisclosureLevel.BRIEF,
    )

    enum class DisclosureLevel {
        ONE_LINER, // Just the conclusion
        BRIEF, // Key steps only (3-5)
        DETAILED, // All steps with full content
    }

    // State flow
    private val _uiState = MutableStateFlow(ReasoningUiState())
    val uiState: StateFlow<ReasoningUiState> = _uiState.asStateFlow()

    /**
     * Load reasoning timeline for a session
     */
    fun loadTimeline(sessionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, sessionId = sessionId) }

            try {
                val timeline = remoteDataSource.getReasoningTimeline(sessionId)
                if (timeline != null) {
                    _uiState.update { it.copy(timeline = timeline, isLoading = false) }
                    Log.d(TAG, "Loaded timeline: ${timeline.totalSteps} steps")
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "No reasoning traces found for this session",
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading timeline: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load reasoning: ${e.message}",
                    )
                }
            }
        }
    }

    /**
     * Load reasoning traces for a session (with optional message filter)
     */
    fun loadTraces(
        sessionId: String,
        messageId: String? = null,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, sessionId = sessionId) }

            try {
                val response = remoteDataSource.getReasoningTraces(sessionId, messageId)
                if (response != null) {
                    _uiState.update {
                        it.copy(
                            traces = response.traces,
                            isLoading = false,
                        )
                    }
                    Log.d(TAG, "Loaded traces: ${response.traces.size} traces")
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            traces = emptyList(),
                            error = "No reasoning traces found",
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading traces: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load traces: ${e.message}",
                    )
                }
            }
        }
    }

    /**
     * Load progressive disclosure levels
     */
    fun loadDisclosure(sessionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, sessionId = sessionId) }

            try {
                val disclosure = remoteDataSource.getProgressiveDisclosure(sessionId)
                if (disclosure != null) {
                    _uiState.update {
                        it.copy(
                            disclosure = disclosure,
                            isLoading = false,
                        )
                    }
                    Log.d(TAG, "Loaded disclosure: ${disclosure.statistics.totalSteps} total steps")
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "No disclosure data available",
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading disclosure: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load disclosure: ${e.message}",
                    )
                }
            }
        }
    }

    /**
     * Set disclosure level (for progressive disclosure UI)
     */
    fun setDisclosureLevel(level: DisclosureLevel) {
        _uiState.update { it.copy(disclosureLevel = level) }
    }

    /**
     * Toggle disclosure level (cycle through: ONE_LINER -> BRIEF -> DETAILED -> ONE_LINER)
     */
    fun toggleDisclosureLevel() {
        val current = _uiState.value.disclosureLevel
        val next =
            when (current) {
                DisclosureLevel.ONE_LINER -> DisclosureLevel.BRIEF
                DisclosureLevel.BRIEF -> DisclosureLevel.DETAILED
                DisclosureLevel.DETAILED -> DisclosureLevel.ONE_LINER
            }
        setDisclosureLevel(next)
    }

    /**
     * Clear error
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Clear all state
     */
    fun clear() {
        _uiState.update { ReasoningUiState() }
    }

    /**
     * Get display content based on disclosure level
     */
    fun getDisplayContent(): List<String> {
        val disclosure = _uiState.value.disclosure ?: return emptyList()
        val level = _uiState.value.disclosureLevel

        return when (level) {
            DisclosureLevel.ONE_LINER -> listOf(disclosure.oneLiner)
            DisclosureLevel.BRIEF -> disclosure.briefSteps
            DisclosureLevel.DETAILED -> disclosure.detailedSteps
        }
    }

    /**
     * Get step type icon/color based on type
     */
    fun getStepTypeDisplay(stepType: String): StepTypeDisplay =
        when (stepType.uppercase()) {
            "ANALYSIS" -> StepTypeDisplay.Analysis
            "PLANNING" -> StepTypeDisplay.Planning
            "HYPOTHESIS" -> StepTypeDisplay.Hypothesis
            "RESEARCH" -> StepTypeDisplay.Research
            "VERIFICATION" -> StepTypeDisplay.Verification
            "SYNTHESIS" -> StepTypeDisplay.Synthesis
            "REFLECTION" -> StepTypeDisplay.Reflection
            "CORRECTION" -> StepTypeDisplay.Correction
            else -> StepTypeDisplay.Unknown(stepType)
        }

    /**
     * Step type display information
     */
    sealed class StepTypeDisplay {
        object Analysis : StepTypeDisplay()

        object Planning : StepTypeDisplay()

        object Hypothesis : StepTypeDisplay()

        object Research : StepTypeDisplay()

        object Verification : StepTypeDisplay()

        object Synthesis : StepTypeDisplay()

        object Reflection : StepTypeDisplay()

        object Correction : StepTypeDisplay()

        data class Unknown(
            val type: String,
        ) : StepTypeDisplay()

        val icon: String
            get() =
                when (this) {
                    is Analysis -> "🔍"
                    is Planning -> "📋"
                    is Hypothesis -> "💡"
                    is Research -> "📚"
                    is Verification -> "✅"
                    is Synthesis -> "🧩"
                    is Reflection -> "🤔"
                    is Correction -> "🔄"
                    is Unknown -> "📝"
                }

        val color: Int
            get() =
                when (this) {
                    is Analysis -> 0xFF2196F3.toInt() // Blue
                    is Planning -> 0xFF9C27B0.toInt() // Purple
                    is Hypothesis -> 0xFFFFC107.toInt() // Amber
                    is Research -> 0xFF4CAF50.toInt() // Green
                    is Verification -> 0xFF00BCD4.toInt() // Cyan
                    is Synthesis -> 0xFFFF9800.toInt() // Orange
                    is Reflection -> 0xFF607D8B.toInt() // Blue Grey
                    is Correction -> 0xFFF44336.toInt() // Red
                    is Unknown -> 0xFF9E9E9E.toInt() // Grey
                }
    }
}
