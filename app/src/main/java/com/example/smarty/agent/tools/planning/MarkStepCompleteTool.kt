package com.example.smarty.agent.tools.planning

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

@Serializable
data class MarkStepCompleteArgs(
    @property:LLMDescription("Summary of what was achieved in this step (e.g., 'Found 5 cleaning tips', 'Created note ID 123')")
    val resultSummary: String
)

@Serializable
data class MarkStepCompleteResult(
    val success: Boolean,
    val message: String,
    val nextStep: String? = null,
    val isPlanComplete: Boolean
)

class MarkStepCompleteTool(
    private val planManager: ExecutionPlanManager
) : Tool<MarkStepCompleteArgs, MarkStepCompleteResult>(
    argsSerializer = MarkStepCompleteArgs.serializer(),
    resultSerializer = MarkStepCompleteResult.serializer(),
    name = "mark_step_complete",
    description = """
        Marks the current plan step as COMPLETED.
        Call this tool IMMEDIATELY after successfully finishing a step in the plan.
        Returns the NEXT step to execute, or indicates the plan is finished.
    """.trimIndent()
) {
    override suspend fun execute(args: MarkStepCompleteArgs): MarkStepCompleteResult {
        if (!planManager.hasActivePlan()) {
            return MarkStepCompleteResult(
                success = false,
                message = "No active plan found. Use 'create_plan' first.",
                isPlanComplete = true
            )
        }

        val updatedPlan = planManager.markStepComplete(args.resultSummary)
        
        if (updatedPlan == null) {
             return MarkStepCompleteResult(
                success = false,
                message = "Failed to update plan.",
                isPlanComplete = true
            )
        }

        val nextStep = updatedPlan.getCurrentStep()
        
        return if (nextStep != null) {
            MarkStepCompleteResult(
                success = true,
                message = "Step completed. Moving to Step ${nextStep.index}.",
                nextStep = "STEP ${nextStep.index}: ${nextStep.description}",
                isPlanComplete = false
            )
        } else {
            // Plan is finished, clear from memory
            planManager.clearPlan()
             MarkStepCompleteResult(
                success = true,
                message = "ALL STEPS COMPLETED! Plan finished.",
                nextStep = null,
                isPlanComplete = true
            )
        }
    }
}
