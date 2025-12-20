package com.example.smarty.agent.tools.calendar

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.data.repository.CogniRepository
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Serializable
data class CreateEventArgs(
    @property:LLMDescription("Title of the event or meeting")
    val title: String,
    @property:LLMDescription("Optional description of the event")
    val description: String? = null,
    @property:LLMDescription("Start time in format 'YYYY-MM-DD HH:mm' or natural language like 'tomorrow 2pm', 'next Monday 10:00'")
    val startTime: String,
    @property:LLMDescription("End time in same format. If not provided, defaults to 1 hour after start")
    val endTime: String? = null,
    @property:LLMDescription("Set to true for all-day events")
    val isAllDay: Boolean = false,
    @property:LLMDescription("Optional location for the event")
    val location: String? = null,
    @property:LLMDescription("Minutes before event to send reminder (e.g., 15, 30, 60)")
    val reminderMinutes: Int? = null
)

/**
 * Tool for creating calendar events.
 */
class CreateEventTool(
    private val repository: CogniRepository
) : Tool<CreateEventArgs, CalendarOperationResult>() {

    override val argsSerializer: KSerializer<CreateEventArgs> = CreateEventArgs.serializer()
    override val resultSerializer: KSerializer<CalendarOperationResult> = CalendarOperationResult.serializer()

    override val name = "create_event"

    override val description = """
        Creates a new calendar event or meeting.
        Use this when the user wants to schedule a meeting, appointment, or event.
        Supports natural language time expressions like "tomorrow 2pm" or "next Monday at 10:00".
    """.trimIndent()

    override suspend fun execute(args: CreateEventArgs): CalendarOperationResult {
        return try {
            if (args.title.isBlank()) {
                return CalendarOperationResult(
                    success = false,
                    message = "Event title cannot be empty",
                    error = "Empty title"
                )
            }

            val startMillis = parseDateTime(args.startTime)
            if (startMillis == null) {
                return CalendarOperationResult(
                    success = false,
                    message = "Could not parse start time: ${args.startTime}",
                    error = "Invalid start time"
                )
            }

            val endMillis = if (args.endTime != null) {
                parseDateTime(args.endTime) ?: (startMillis + 3600000) // 1 hour default
            } else {
                startMillis + 3600000 // 1 hour default
            }

            val event = CalendarEvent(
                title = args.title,
                description = args.description,
                startTime = startMillis,
                endTime = endMillis,
                isAllDay = args.isAllDay,
                location = args.location,
                reminderMinutes = args.reminderMinutes
            )

            repository.insertCalendarEvent(event)

            val dateFormat = SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault())
            val formattedTime = dateFormat.format(Date(startMillis))

            CalendarOperationResult(
                success = true,
                eventId = event.id,
                eventTitle = args.title,
                message = "Event '${args.title}' created for $formattedTime"
            )
        } catch (e: Exception) {
            CalendarOperationResult(
                success = false,
                message = "Failed to create event",
                error = e.message
            )
        }
    }

    /**
     * Parse datetime from various formats.
     * Supports:
     * - ISO format: "2024-12-25 14:00"
     * - Natural language: "tomorrow 2pm", "next Monday 10:00", "in 2 hours"
     */
    private fun parseDateTime(input: String): Long? {
        val calendar = Calendar.getInstance()
        val inputLower = input.lowercase().trim()

        // Try natural language first
        when {
            inputLower.startsWith("tomorrow") -> {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                parseTimeFromString(inputLower.removePrefix("tomorrow").trim())?.let { (hour, minute) ->
                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                    calendar.set(Calendar.MINUTE, minute)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                }
                return calendar.timeInMillis
            }
            inputLower.startsWith("today") -> {
                parseTimeFromString(inputLower.removePrefix("today").trim())?.let { (hour, minute) ->
                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                    calendar.set(Calendar.MINUTE, minute)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                }
                return calendar.timeInMillis
            }
            inputLower.startsWith("next ") -> {
                val remaining = inputLower.removePrefix("next ")
                val dayNames = mapOf(
                    "monday" to Calendar.MONDAY,
                    "tuesday" to Calendar.TUESDAY,
                    "wednesday" to Calendar.WEDNESDAY,
                    "thursday" to Calendar.THURSDAY,
                    "friday" to Calendar.FRIDAY,
                    "saturday" to Calendar.SATURDAY,
                    "sunday" to Calendar.SUNDAY
                )
                for ((day, calendarDay) in dayNames) {
                    if (remaining.startsWith(day)) {
                        // Find next occurrence of this day
                        while (calendar.get(Calendar.DAY_OF_WEEK) != calendarDay) {
                            calendar.add(Calendar.DAY_OF_YEAR, 1)
                        }
                        calendar.add(Calendar.DAY_OF_YEAR, 7) // "next" means next week
                        parseTimeFromString(remaining.removePrefix(day).trim())?.let { (hour, minute) ->
                            calendar.set(Calendar.HOUR_OF_DAY, hour)
                            calendar.set(Calendar.MINUTE, minute)
                            calendar.set(Calendar.SECOND, 0)
                            calendar.set(Calendar.MILLISECOND, 0)
                        }
                        return calendar.timeInMillis
                    }
                }
            }
            inputLower.startsWith("in ") -> {
                val remaining = inputLower.removePrefix("in ")
                val numberMatch = Regex("(\\d+)\\s*(hour|minute|min|hr)").find(remaining)
                if (numberMatch != null) {
                    val amount = numberMatch.groupValues[1].toIntOrNull() ?: 0
                    val unit = numberMatch.groupValues[2]
                    when {
                        unit.startsWith("hour") || unit.startsWith("hr") -> {
                            calendar.add(Calendar.HOUR, amount)
                        }
                        unit.startsWith("min") -> {
                            calendar.add(Calendar.MINUTE, amount)
                        }
                    }
                    return calendar.timeInMillis
                }
            }
        }

        // Try ISO format: "2024-12-25 14:00" or "2024-12-25T14:00"
        try {
            val formats = listOf(
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault()),
                SimpleDateFormat("yyyy-MM-dd h:mm a", Locale.getDefault())
            )
            for (format in formats) {
                try {
                    return format.parse(input)?.time
                } catch (e: Exception) {
                    // Try next format
                }
            }
        } catch (e: Exception) {
            // Parsing failed
        }

        return null
    }

    /**
     * Parse time portion from string like "2pm", "14:00", "at 3:30pm"
     */
    private fun parseTimeFromString(input: String): Pair<Int, Int>? {
        val cleanInput = input.removePrefix("at").trim()

        // Match "2pm", "2:30pm", "14:00", "2:30 pm"
        val timeRegex = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE)
        val match = timeRegex.find(cleanInput) ?: return Pair(9, 0) // Default to 9:00 AM

        var hour = match.groupValues[1].toIntOrNull() ?: 9
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val period = match.groupValues[3].lowercase()

        // Convert to 24-hour if PM
        if (period == "pm" && hour != 12) {
            hour += 12
        } else if (period == "am" && hour == 12) {
            hour = 0
        }

        return Pair(hour, minute)
    }
}
