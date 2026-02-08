package com.example.smarty.viewmodel.managers

import java.util.UUID

enum class PlanStatus { IN_PROGRESS, COMPLETED, FAILED }
enum class StepStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED, SKIPPED }

data class PlanStep(
    val index: Int,
    val description: String,
    var status: StepStatus = StepStatus.PENDING,
    var resultSummary: String? = null
)

data class ExecutionPlan(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val steps: List<PlanStep>,
    var currentStepIndex: Int = 0,
    var status: PlanStatus = PlanStatus.IN_PROGRESS
) {
    fun getCurrentStep(): PlanStep? {
        if (currentStepIndex >= steps.size) return null
        return steps[currentStepIndex]
    }

    fun markCurrentStepComplete(result: String) {
        if (currentStepIndex < steps.size) {
            steps[currentStepIndex].status = StepStatus.COMPLETED
            steps[currentStepIndex].resultSummary = result
            currentStepIndex++
            
            if (currentStepIndex >= steps.size) {
                status = PlanStatus.COMPLETED
            }
        }
    }
}
