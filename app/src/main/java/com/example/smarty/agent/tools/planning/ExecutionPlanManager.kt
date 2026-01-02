package com.example.smarty.agent.tools.planning

/**
 * Manager class to handle the creation, retrieval, and updating of ExecutionPlans.
 * Manually instantiated in CogniAgent to ensure session-scoped persistence.
 */
class ExecutionPlanManager(
    private val onPlanStatusChange: ((String?) -> Unit)? = null
) {
    private var activePlan: ExecutionPlan? = null

    private fun notifyStatusChange() {
        val plan = activePlan
        if (plan == null) {
            onPlanStatusChange?.invoke(null)
        } else {
            val currentStep = plan.getCurrentStep()
            val status = if (currentStep != null) {
                "Step ${currentStep.index}/${plan.steps.size}: ${currentStep.description}"
            } else {
                "Plan '${plan.goal}' Complete"
            }
            onPlanStatusChange?.invoke(status)
        }
    }


    fun createPlan(goal: String, steps: List<String>): ExecutionPlan {
        val planSteps = steps.mapIndexed { index, desc ->
            PlanStep(index = index + 1, description = desc)
        }
        val plan = ExecutionPlan(goal = goal, steps = planSteps)
        activePlan = plan
        notifyStatusChange()
        return plan
    }

    fun getActivePlan(): ExecutionPlan? = activePlan

    fun clearPlan() {
        activePlan = null
        notifyStatusChange()
    }

    fun markStepComplete(result: String): ExecutionPlan? {
        activePlan?.let {
            it.markCurrentStepComplete(result)
            notifyStatusChange()
            return it
        }
        return null
    }
    
    fun hasActivePlan(): Boolean = activePlan != null && activePlan?.status == PlanStatus.IN_PROGRESS
}
