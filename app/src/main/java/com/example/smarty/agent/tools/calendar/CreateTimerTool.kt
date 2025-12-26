package com.example.smarty.agent.tools.calendar

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.util.Log
import com.example.smarty.data.model.CogniTimer
import com.example.smarty.service.AlarmScheduler
import com.google.gson.Gson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Serializable
data class CreateTimerArgs(
    @property:LLMDescription("Name or label for the timer/alarm")
    val name: String,
    @property:LLMDescription("When to trigger: 'in 5 minutes', 'at 3:00 PM', 'tomorrow 7:00 AM'")
    val triggerTime: String,
    @property:LLMDescription("For recurring alarms: list of days like ['monday', 'wednesday', 'friday']. Leave empty for one-time.")
    val repeatDays: List<String>? = null,
    @property:LLMDescription("Set to true for alarm (louder, vibrates), false for timer (simple notification)")
    val isAlarm: Boolean = false
)

/**
 * Tool for creating timers and alarms.
 * Timers play audio for 5 seconds when triggered.
 *
 * BUG FIX (AI-002): Added comprehensive logging for debugging.
 */
class CreateTimerTool(
    private val alarmScheduler: AlarmScheduler
) : Tool<CreateTimerArgs, TimerOperationResult>() {

    companion object {
        private const val TAG = "CreateTimerTool"
    }

    private val gson = Gson()

    override val argsSerializer: KSerializer<CreateTimerArgs> = CreateTimerArgs.serializer()
    override val resultSerializer: KSerializer<TimerOperationResult> = TimerOperationResult.serializer()

    override val name = "create_timer"

    override val description = """
        Creates a timer or alarm that will play audio for 5 seconds when triggered.
        Supports one-time timers ("in 5 minutes", "at 3 PM") and recurring alarms on specific days.
        Use this when the user wants to set a reminder, alarm, or timer.
    """.trimIndent()

    override suspend fun execute(args: CreateTimerArgs): TimerOperationResult {
        Log.d(TAG, "Creating timer: name='${args.name}', triggerTime='${args.triggerTime}', isAlarm=${args.isAlarm}")

        return try {
            if (args.name.isBlank()) {
                Log.w(TAG, "Timer creation failed: empty name")
                return TimerOperationResult(
                    success = false,
                    message = "Timer name cannot be empty",
                    error = "Empty name"
                )
            }

            val triggerMillis = parseTimeExpression(args.triggerTime)
            if (triggerMillis == null) {
                Log.w(TAG, "Timer creation failed: could not parse '${args.triggerTime}'")
                return TimerOperationResult(
                    success = false,
                    message = "Could not parse time: ${args.triggerTime}. Try formats like 'in 5 minutes', 'at 3 PM', or 'tomorrow 7:00 AM'",
                    error = "Invalid time expression"
                )
            }
            Log.d(TAG, "Parsed trigger time: $triggerMillis (${Date(triggerMillis)})")

            // Validate trigger time is in the future
            if (triggerMillis <= System.currentTimeMillis()) {
                Log.w(TAG, "Timer creation failed: time is in the past")
                return TimerOperationResult(
                    success = false,
                    message = "Timer must be set for a future time",
                    error = "Time is in the past"
                )
            }

            val repeatDaysJson = if (!args.repeatDays.isNullOrEmpty()) {
                gson.toJson(args.repeatDays.map { it.lowercase() })
            } else null

            val timer = CogniTimer(
                name = args.name,
                triggerTime = triggerMillis,
                repeatDays = repeatDaysJson,
                isAlarm = args.isAlarm,
                isActive = true
            )

            alarmScheduler.scheduleTimer(timer)
            Log.i(TAG, "Timer scheduled successfully: id=${timer.id}, name='${timer.name}'")

            val dateFormat = SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault())
            val formattedTime = dateFormat.format(Date(triggerMillis))

            val typeLabel = if (args.isAlarm) "Alarm" else "Timer"
            val recurringInfo = if (!args.repeatDays.isNullOrEmpty()) {
                " (repeating on ${args.repeatDays.joinToString(", ")})"
            } else ""

            TimerOperationResult(
                success = true,
                timerId = timer.id,
                timerName = args.name,
                scheduledFor = formattedTime,
                message = "$typeLabel '${args.name}' set for $formattedTime$recurringInfo"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Timer creation failed with exception: ${e.message}", e)
            TimerOperationResult(
                success = false,
                message = "Failed to create timer: ${e.message}",
                error = e.message
            )
        }
    }

    /**
     * Parse time expression to milliseconds.
     * Supports:
     * - "in X minutes/hours"
     * - "at HH:mm" or "at h:mm AM/PM"
     * - "tomorrow HH:mm"
     */
    private fun parseTimeExpression(input: String): Long? {
        val calendar = Calendar.getInstance()
        val inputLower = input.lowercase().trim()

        when {
            // "in X minutes/hours"
            inputLower.startsWith("in ") -> {
                val remaining = inputLower.removePrefix("in ").trim()
                val numberMatch = Regex("(\\d+)\\s*(second|sec|minute|min|hour|hr)s?").find(remaining)
                if (numberMatch != null) {
                    val amount = numberMatch.groupValues[1].toIntOrNull() ?: return null
                    val unit = numberMatch.groupValues[2]
                    when {
                        unit.startsWith("second") || unit.startsWith("sec") -> {
                            calendar.add(Calendar.SECOND, amount)
                        }
                        unit.startsWith("minute") || unit.startsWith("min") -> {
                            calendar.add(Calendar.MINUTE, amount)
                        }
                        unit.startsWith("hour") || unit.startsWith("hr") -> {
                            calendar.add(Calendar.HOUR, amount)
                        }
                    }
                    return calendar.timeInMillis
                }
            }

            // "at HH:mm" or "at h:mm AM/PM"
            inputLower.startsWith("at ") -> {
                val remaining = inputLower.removePrefix("at ").trim()
                val time = parseTimeOfDay(remaining) ?: return null
                calendar.set(Calendar.HOUR_OF_DAY, time.first)
                calendar.set(Calendar.MINUTE, time.second)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)

                // If time is in the past today, schedule for tomorrow
                if (calendar.timeInMillis <= System.currentTimeMillis()) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }
                return calendar.timeInMillis
            }

            // "tomorrow HH:mm"
            // BUG FIX (L-005): Use current time if no time specified instead of silent 9 AM default
            inputLower.startsWith("tomorrow") -> {
                val remaining = inputLower.removePrefix("tomorrow").trim()
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                val parsedTime = parseTimeOfDay(remaining)
                if (parsedTime != null) {
                    calendar.set(Calendar.HOUR_OF_DAY, parsedTime.first)
                    calendar.set(Calendar.MINUTE, parsedTime.second)
                } else {
                    // If no time specified, keep current time of day (same time tomorrow)
                    // This is more intuitive than defaulting to 9 AM
                }
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                return calendar.timeInMillis
            }

            // Just a time like "3:00 PM" or "15:00"
            else -> {
                val time = parseTimeOfDay(inputLower)
                if (time != null) {
                    calendar.set(Calendar.HOUR_OF_DAY, time.first)
                    calendar.set(Calendar.MINUTE, time.second)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)

                    // If time is in the past today, schedule for tomorrow
                    if (calendar.timeInMillis <= System.currentTimeMillis()) {
                        calendar.add(Calendar.DAY_OF_YEAR, 1)
                    }
                    return calendar.timeInMillis
                }
            }
        }

        return null
    }

    /**
     * Parse time of day from string like "3pm", "15:00", "3:30 PM"
     */
    private fun parseTimeOfDay(input: String): Pair<Int, Int>? {
        val cleanInput = input.removePrefix("at").trim()
        if (cleanInput.isEmpty()) return null

        // Match "3pm", "3:30pm", "15:00", "3:30 pm"
        val timeRegex = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE)
        val match = timeRegex.find(cleanInput) ?: return null

        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val period = match.groupValues[3].lowercase()

        // Convert to 24-hour if PM
        if (period == "pm" && hour != 12) {
            hour += 12
        } else if (period == "am" && hour == 12) {
            hour = 0
        }

        // Validate hour
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return null
        }

        return Pair(hour, minute)
    }
}
