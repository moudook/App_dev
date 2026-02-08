package com.example.smarty.viewmodel.managers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manager class to handle the creation, retrieval, and updating of ExecutionPlans.
 * Centralized for hybridization: Shared between UI and AI Agent.
 */
class ExecutionPlanManager {
    private val _activePlan = MutableStateFlow<ExecutionPlan?>(null)
    val activePlan: StateFlow<ExecutionPlan?> = _activePlan.asStateFlow()

    fun createPlan(goal: String, steps: List<String>): ExecutionPlan {
        val planSteps = steps.mapIndexed { index, desc ->
            PlanStep(index = index + 1, description = desc)
        }
        val plan = ExecutionPlan(goal = goal, steps = planSteps)
        _activePlan.value = plan
        return plan
    }

    fun getActivePlan(): ExecutionPlan? = _activePlan.value

    fun clearPlan() {
        _activePlan.value = null
    }

    fun markStepComplete(result: String): ExecutionPlan? {
        val current = _activePlan.value ?: return null
        current.markCurrentStepComplete(result)
        // Trigger update in StateFlow
        _activePlan.value = current.copy()
        return _activePlan.value
    }

    fun hasActivePlan(): Boolean = _activePlan.value?.let {
        it.status == PlanStatus.IN_PROGRESS
    } ?: false
}
