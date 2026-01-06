package com.example.smarty.agent.tools.planning

import ai.koog.agents.core.tools.Tool
import kotlinx.serialization.Serializable

@Serializable
class CancelPlanArgs

@Serializable
data class CancelPlanResult(
    val success: Boolean,
    val message: String
)

class CancelPlanTool(
    private val planManager: ExecutionPlanManager
) : Tool<CancelPlanArgs, CancelPlanResult>(
    argsSerializer = CancelPlanArgs.serializer(),
    resultSerializer = CancelPlanResult.serializer(),
    name = "cancel_plan",
    description = "Cancels the current active plan. Use this if the user wants to stop, change course, or just chat instead of following the plan."
) {
    override suspend fun execute(args: CancelPlanArgs): CancelPlanResult {
        planManager.clearPlan()
        return CancelPlanResult(
            success = true,
            message = "Plan cancelled. You are now in normal chat mode."
        )
    }
}
