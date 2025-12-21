package com.example.smarty.viewmodel.managers

import android.util.Log
import com.example.smarty.ui.components.DynamicIslandState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages the AI Agent's state for the Dynamic Island UI component.
 *
 * Responsibilities:
 * - Track agent execution state (thinking, executing, waiting, completed)
 * - Manage elapsed time timer during tool execution
 * - Track tools used in current agent run
 * - Handle state transitions and timeouts
 *
 * @property scope Coroutine scope for timer operations
 */
class AgentStateManager(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "AgentStateManager"
        private const val TOOL_TIMEOUT_SECONDS = 45
        private const val COMPLETION_DISPLAY_MS = 2000L
    }

    private val _agentState = MutableStateFlow<DynamicIslandState>(DynamicIslandState.Contracted)
    val agentState: StateFlow<DynamicIslandState> = _agentState.asStateFlow()

    // Timer job for elapsed time updates
    private var timerJob: Job? = null

    // Track current run stats
    private var toolsUsedInRun = mutableListOf<String>()
    private var runStartTime = 0L

    /**
     * Start a new agent run.
     */
    fun startAgentRun() {
        toolsUsedInRun.clear()
        runStartTime = System.currentTimeMillis()
    }

    /**
     * Record a tool being used in the current run.
     */
    fun recordToolUsed(toolName: String) {
        if (!toolsUsedInRun.contains(toolName)) {
            toolsUsedInRun.add(toolName)
        }
    }

    /**
     * Get count of tools used in current run.
     */
    fun getToolsUsedCount(): Int = toolsUsedInRun.size

    /**
     * Set agent state and manage timer accordingly.
     */
    fun setState(state: DynamicIslandState) {
        _agentState.value = state

        when (state) {
            is DynamicIslandState.AgentExecutingTool,
            is DynamicIslandState.AgentWaitingForResult -> {
                startTimer(state)
            }
            is DynamicIslandState.Contracted,
            is DynamicIslandState.AgentCompleted,
            is DynamicIslandState.AgentError -> {
                stopTimer()
            }
            else -> {
                // Keep timer running for other states
            }
        }
    }

    /**
     * Show agent is thinking/processing.
     */
    fun showThinking(message: String = "Thinking...") {
        setState(DynamicIslandState.AgentThinking(message = message))
    }

    /**
     * Show agent is executing a tool.
     */
    fun showExecutingTool(toolName: String, toolDisplayName: String) {
        recordToolUsed(toolName)
        setState(DynamicIslandState.AgentExecutingTool(
            toolName = toolName,
            toolDisplayName = toolDisplayName,
            elapsedSeconds = 0
        ))
    }

    /**
     * Show agent is waiting for tool result.
     */
    fun showWaitingForResult(toolDisplayName: String) {
        setState(DynamicIslandState.AgentWaitingForResult(
            toolDisplayName = toolDisplayName,
            elapsedSeconds = 0
        ))
    }

    /**
     * Show agent completed successfully.
     */
    fun showCompleted() {
        val totalTime = if (runStartTime > 0) {
            ((System.currentTimeMillis() - runStartTime) / 1000).toInt()
        } else 0

        setState(DynamicIslandState.AgentCompleted(
            toolsUsed = toolsUsedInRun.size,
            totalSeconds = totalTime
        ))

        // Auto-contract after display time
        scope.launch {
            delay(COMPLETION_DISPLAY_MS)
            if (_agentState.value is DynamicIslandState.AgentCompleted) {
                setState(DynamicIslandState.Contracted)
            }
        }
    }

    /**
     * Show agent error state.
     */
    fun showError(message: String) {
        setState(DynamicIslandState.AgentError(message))

        // Auto-contract after display time
        scope.launch {
            delay(COMPLETION_DISPLAY_MS)
            if (_agentState.value is DynamicIslandState.AgentError) {
                setState(DynamicIslandState.Contracted)
            }
        }
    }

    /**
     * Contract the island (hide agent state).
     */
    fun contract() {
        setState(DynamicIslandState.Contracted)
    }

    /**
     * Reset all state.
     */
    fun reset() {
        stopTimer()
        toolsUsedInRun.clear()
        runStartTime = 0L
        _agentState.value = DynamicIslandState.Contracted
    }

    /**
     * Start the elapsed time timer.
     * Has maximum iteration guard to prevent infinite loops.
     */
    private fun startTimer(initialState: DynamicIslandState) {
        timerJob?.cancel()
        val startTime = System.currentTimeMillis()
        // Safety guard: max iterations = timeout + 10 seconds buffer
        val maxIterations = TOOL_TIMEOUT_SECONDS + 10

        timerJob = scope.launch {
            var iterations = 0
            while (iterations < maxIterations) {
                delay(1000)
                iterations++
                val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()

                when (val current = _agentState.value) {
                    is DynamicIslandState.AgentExecutingTool -> {
                        _agentState.value = current.copy(elapsedSeconds = elapsed)
                    }
                    is DynamicIslandState.AgentWaitingForResult -> {
                        _agentState.value = current.copy(elapsedSeconds = elapsed)
                    }
                    else -> break
                }

                // Check timeout
                if (elapsed > TOOL_TIMEOUT_SECONDS) {
                    Log.w(TAG, "Tool execution timeout after ${elapsed}s")
                    showError("Tool timeout")
                    break
                }
            }

            // Safety: log if max iterations reached (should never happen with proper timeout)
            if (iterations >= maxIterations) {
                Log.e(TAG, "Timer exceeded max iterations ($maxIterations), forcing stop")
                showError("Operation timed out")
            }
        }
    }

    /**
     * Stop the elapsed time timer.
     */
    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }
}
