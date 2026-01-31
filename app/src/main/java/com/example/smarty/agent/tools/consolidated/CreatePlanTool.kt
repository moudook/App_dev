package com.example.smarty.agent.tools.consolidated

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.viewmodel.managers.ExecutionPlanManager
import kotlinx.serialization.Serializable

@Serializable
data class CreatePlanArgs(
    @property:LLMDescription("The overall goal of this plan (e.g., 'Clean room workflow')")
    val goal: String,
    @property:LLMDescription("List of distinct, actionable steps. Example: ['Search for cleaning tips', 'Create note with tips', 'Create calendar event', 'Play music']")
    val steps: List<String>
)

@Serializable
data class CreatePlanResult(
    val success: Boolean,
    val message: String,
    val firstStep: String? = null
)

class CreatePlanTool(
    private val planManager: ExecutionPlanManager
) : Tool<CreatePlanArgs, CreatePlanResult>(
    argsSerializer = CreatePlanArgs.serializer(),
    resultSerializer = CreatePlanResult.serializer(),
    name = "create_plan",
    description = """
        Use specific tools (create_note, web_search) directly for most tasks.
        ONLY use this tool if the user EXPLICITLY asks to "create a plan" or "make a plan".
        NEVER use for "find X and save it" or "search and create note" - just do the actions.
        DO NOT use this tool for general queries.
    """.trimIndent()
) {
    override suspend fun execute(args: CreatePlanArgs): CreatePlanResult {
        val context = com.example.smarty.SmartyApplication.appInstance
        if (args.steps.isEmpty()) {
             return CreatePlanResult(
                success = false,
                message = context.getString(com.example.smarty.R.string.error_plan_empty)
            )
        }

        val plan = planManager.createPlan(args.goal, args.steps)
        val firstStep = plan.getCurrentStep()

        return CreatePlanResult(
            success = true,
            message = context.getString(com.example.smarty.R.string.plan_created, args.goal, plan.steps.size),
            firstStep = context.getString(com.example.smarty.R.string.immediate_action, firstStep?.description ?: "")
        )
    }
}
