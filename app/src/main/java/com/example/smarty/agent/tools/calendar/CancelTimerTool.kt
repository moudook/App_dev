package com.example.smarty.agent.tools.calendar

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.service.AlarmScheduler
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable
data class CancelTimerArgs(
    @property:LLMDescription("ID of the timer to cancel (if known)")
    val timerId: String? = null,
    @property:LLMDescription("Name/label of the timer to cancel (if timerId not known)")
    val name: String? = null
)

/**
 * Tool for cancelling scheduled timers and alarms.
 */
class CancelTimerTool(
    private val alarmScheduler: AlarmScheduler
) : Tool<CancelTimerArgs, TimerOperationResult>() {

    override val argsSerializer: KSerializer<CancelTimerArgs> = CancelTimerArgs.serializer()
    override val resultSerializer: KSerializer<TimerOperationResult> = TimerOperationResult.serializer()

    override val name = "cancel_timer"

    override val description = """
        Cancels a scheduled timer or alarm.
        Can cancel by timer ID or by name.
        Use this when the user wants to cancel, stop, or remove a timer/alarm.
    """.trimIndent()

    override suspend fun execute(args: CancelTimerArgs): TimerOperationResult {
        return try {
            if (args.timerId == null && args.name == null) {
                return TimerOperationResult(
                    success = false,
                    message = "Please provide either a timer ID or name",
                    error = "No identifier provided"
                )
            }

            // Cancel by ID if provided
            val cancelId = args.timerId ?: args.name?.hashCode().toString()

            alarmScheduler.cancelTimer(cancelId)

            TimerOperationResult(
                success = true,
                timerId = cancelId,
                timerName = args.name,
                message = "Timer${args.name?.let { " '$it'" } ?: ""} has been cancelled"
            )
        } catch (e: Exception) {
            TimerOperationResult(
                success = false,
                message = "Failed to cancel timer",
                error = e.message
            )
        }
    }
}
