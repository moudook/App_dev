package com.example.smarty.agent.tools.calendar

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.util.Log
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.data.repository.CogniRepository
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
    val reminderMinutes: Int? = null,
    @property:LLMDescription("Set to true to mark event as PRIVATE (hidden from AI in future queries). Use for sensitive/personal events.")
    val isPrivate: Boolean = false
)

/**
 * Tool for creating calendar events.
 *
 * BUG FIX (AI-002): Added comprehensive logging for debugging.
 * BUG FIX (NEW-012/17): Added end time validation to prevent invalid events.
 */
class CreateEventTool(
    private val repository: CogniRepository
) : Tool<CreateEventArgs, CalendarOperationResult>(
    argsSerializer = CreateEventArgs.serializer(),
    resultSerializer = CalendarOperationResult.serializer(),
    name = "create_event",
    description = """ONLY use when user says "schedule" or "remind me at". Do NOT use otherwise.""".trimIndent()
) {
    companion object {
        private const val TAG = "CreateEventTool"
        private const val DEFAULT_EVENT_DURATION_MS = 3600000L // 1 hour
    }

    override suspend fun execute(args: CreateEventArgs): CalendarOperationResult {
        Log.d(TAG, "Creating event: title='${args.title}', startTime='${args.startTime}', endTime='${args.endTime}'")

        return try {
            if (args.title.isBlank()) {
                Log.w(TAG, "Event creation failed: empty title")
                return CalendarOperationResult(
                    success = false,
                    message = "Event title cannot be empty",
                    error = "Empty title"
                )
            }

            val startMillis = parseDateTime(args.startTime)
            if (startMillis == null) {
                Log.w(TAG, "Event creation failed: could not parse startTime '${args.startTime}'")
                return CalendarOperationResult(
                    success = false,
                    message = "Could not parse start time: ${args.startTime}. Try formats like '2024-12-25 14:00' or 'tomorrow 2pm'",
                    error = "Invalid start time format"
                )
            }
            Log.d(TAG, "Parsed startTime: $startMillis (${Date(startMillis)})")

            var endMillis = if (args.endTime != null) {
                val parsed = parseDateTime(args.endTime)
                if (parsed == null) {
                    Log.w(TAG, "Could not parse endTime '${args.endTime}', using default duration")
                }
                parsed ?: (startMillis + DEFAULT_EVENT_DURATION_MS)
            } else {
                startMillis + DEFAULT_EVENT_DURATION_MS
            }

            // BUG FIX (NEW-012/17): Validate end time is after start time
            if (endMillis <= startMillis) {
                Log.w(TAG, "End time ($endMillis) is not after start time ($startMillis), adjusting")
                // If end time is before or equal to start, set it to 1 hour after start
                endMillis = startMillis + DEFAULT_EVENT_DURATION_MS
            }

            // Handle midnight edge case: if end time is exactly midnight, it might be meant for the next day
            val endCalendar = Calendar.getInstance().apply { timeInMillis = endMillis }
            if (endCalendar.get(Calendar.HOUR_OF_DAY) == 0 &&
                endCalendar.get(Calendar.MINUTE) == 0 &&
                endMillis <= startMillis) {
                // Likely meant next day midnight
                endCalendar.add(Calendar.DAY_OF_YEAR, 1)
                endMillis = endCalendar.timeInMillis
                Log.d(TAG, "Adjusted midnight end time to next day: $endMillis")
            }

            Log.d(TAG, "Final endTime: $endMillis (${Date(endMillis)})")

            val event = CalendarEvent(
                title = args.title,
                description = args.description,
                startTime = startMillis,
                endTime = endMillis,
                isAllDay = args.isAllDay,
                location = args.location,
                reminderMinutes = args.reminderMinutes,
                // PRIVACY FIX (CRIT-001): Respect user's privacy preference
                isEventPrivate = args.isPrivate
            )

            repository.insertCalendarEvent(event)
            Log.i(TAG, "Event created successfully: id=${event.id}, title='${event.title}'")

            val dateFormat = SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault())
            val formattedTime = dateFormat.format(Date(startMillis))

            CalendarOperationResult(
                success = true,
                eventId = event.id,
                eventTitle = args.title,
                message = "Event '${args.title}' created for $formattedTime"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Event creation failed with exception: ${e.message}", e)
            CalendarOperationResult(
                success = false,
                message = "Failed to create event: ${e.message}",
                error = e.message
            )
        }
    }

    /**
     * Parse datetime from various formats.
     * Supports:
     * - ISO format: "2024-12-25 14:00"
     * - Natural language: "tomorrow 2pm", "next Monday 10:00", "in 2 hours"
     * - Month names: "January 4th at 12:00 am", "March 15 3pm", "Dec 25 noon"
     *
     * BUG FIX (AI-003): Added logging and additional format support for better debugging.
     * BUG FIX (DATE-001): Added support for month names and ordinal dates.
     */
    private fun parseDateTime(input: String): Long? {
        val calendar = Calendar.getInstance()
        val inputLower = input.lowercase().trim()
        Log.d(TAG, "Parsing datetime: '$input'")

        // Try natural language first
        when {
            inputLower.startsWith("tomorrow") -> {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                val timeStr = inputLower.removePrefix("tomorrow").trim()
                val timeParsed = parseTimeFromString(timeStr)
                if (timeParsed != null) {
                    calendar.set(Calendar.HOUR_OF_DAY, timeParsed.first)
                    calendar.set(Calendar.MINUTE, timeParsed.second)
                }
                // Always reset seconds/millis for clean times
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                Log.d(TAG, "Parsed 'tomorrow' expression: ${calendar.time}")
                return calendar.timeInMillis
            }
            inputLower.startsWith("today") -> {
                val timeStr = inputLower.removePrefix("today").trim()
                val timeParsed = parseTimeFromString(timeStr)
                if (timeParsed != null) {
                    calendar.set(Calendar.HOUR_OF_DAY, timeParsed.first)
                    calendar.set(Calendar.MINUTE, timeParsed.second)
                }
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                Log.d(TAG, "Parsed 'today' expression: ${calendar.time}")
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
                        val timeStr = remaining.removePrefix(day).trim()
                        val timeParsed = parseTimeFromString(timeStr)
                        if (timeParsed != null) {
                            calendar.set(Calendar.HOUR_OF_DAY, timeParsed.first)
                            calendar.set(Calendar.MINUTE, timeParsed.second)
                        }
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)
                        Log.d(TAG, "Parsed 'next $day' expression: ${calendar.time}")
                        return calendar.timeInMillis
                    }
                }
            }
            inputLower.startsWith("this ") -> {
                // BUG FIX (AI-003): Support "this Monday", "this Friday" etc.
                val remaining = inputLower.removePrefix("this ")
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
                        // Find this week's occurrence
                        while (calendar.get(Calendar.DAY_OF_WEEK) != calendarDay) {
                            calendar.add(Calendar.DAY_OF_YEAR, 1)
                        }
                        val timeStr = remaining.removePrefix(day).trim()
                        val timeParsed = parseTimeFromString(timeStr)
                        if (timeParsed != null) {
                            calendar.set(Calendar.HOUR_OF_DAY, timeParsed.first)
                            calendar.set(Calendar.MINUTE, timeParsed.second)
                        }
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)
                        Log.d(TAG, "Parsed 'this $day' expression: ${calendar.time}")
                        return calendar.timeInMillis
                    }
                }
            }
            inputLower.startsWith("in ") -> {
                val remaining = inputLower.removePrefix("in ")
                val numberMatch = Regex("(\\d+)\\s*(hour|minute|min|hr|day)s?").find(remaining)
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
                        unit.startsWith("day") -> {
                            calendar.add(Calendar.DAY_OF_YEAR, amount)
                        }
                    }
                    Log.d(TAG, "Parsed 'in $amount $unit' expression: ${calendar.time}")
                    return calendar.timeInMillis
                }
            }
        }

        // Try month name formats: "January 4th at 12:00 am", "March 15 3pm", "Dec 25"
        val monthDateResult = parseMonthDateFormat(inputLower)
        if (monthDateResult != null) {
            Log.d(TAG, "Parsed month-date format: ${Date(monthDateResult)}")
            return monthDateResult
        }

        // Try ISO format: "2024-12-25 14:00" or "2024-12-25T14:00"
        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault()),
            SimpleDateFormat("yyyy-MM-dd h:mm a", Locale.getDefault()),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()),
            SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault()),
            SimpleDateFormat("MM/dd/yyyy h:mm a", Locale.getDefault()),
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        )
        for (format in formats) {
            try {
                val parsed = format.parse(input)?.time
                if (parsed != null) {
                    Log.d(TAG, "Parsed ISO format with '${format.toPattern()}': ${Date(parsed)}")
                    return parsed
                }
            } catch (e: Exception) {
                // Try next format
            }
        }

        // FALLBACK: If no format matched, default to today at 2 PM
        // This provides a better UX than returning an error when the LLM provides an unclear time
        Log.w(TAG, "Could not parse datetime '$input', defaulting to today at 2:00 PM")
        val defaultCalendar = Calendar.getInstance()
        defaultCalendar.set(Calendar.HOUR_OF_DAY, 14)  // 2 PM default
        defaultCalendar.set(Calendar.MINUTE, 0)
        defaultCalendar.set(Calendar.SECOND, 0)
        defaultCalendar.set(Calendar.MILLISECOND, 0)
        
        // If it's already past 2 PM today, default to tomorrow at 2 PM
        if (defaultCalendar.timeInMillis < System.currentTimeMillis()) {
            defaultCalendar.add(Calendar.DAY_OF_YEAR, 1)
            Log.d(TAG, "2 PM already passed today, defaulting to tomorrow at 2:00 PM")
        }
        
        return defaultCalendar.timeInMillis
    }

    /**
     * Parse date formats with month names like:
     * - "January 4th at 12:00 am"
     * - "March 15 3pm"
     * - "Dec 25 noon"
     * - "February 14th"
     * - "Jan 1st midnight"
     */
    private fun parseMonthDateFormat(input: String): Long? {
        val months = mapOf(
            "january" to Calendar.JANUARY, "jan" to Calendar.JANUARY,
            "february" to Calendar.FEBRUARY, "feb" to Calendar.FEBRUARY,
            "march" to Calendar.MARCH, "mar" to Calendar.MARCH,
            "april" to Calendar.APRIL, "apr" to Calendar.APRIL,
            "may" to Calendar.MAY,
            "june" to Calendar.JUNE, "jun" to Calendar.JUNE,
            "july" to Calendar.JULY, "jul" to Calendar.JULY,
            "august" to Calendar.AUGUST, "aug" to Calendar.AUGUST,
            "september" to Calendar.SEPTEMBER, "sep" to Calendar.SEPTEMBER, "sept" to Calendar.SEPTEMBER,
            "october" to Calendar.OCTOBER, "oct" to Calendar.OCTOBER,
            "november" to Calendar.NOVEMBER, "nov" to Calendar.NOVEMBER,
            "december" to Calendar.DECEMBER, "dec" to Calendar.DECEMBER
        )

        // Find month in input
        var foundMonth: Int? = null
        var remainingAfterMonth = input
        for ((monthName, monthValue) in months) {
            if (input.startsWith(monthName)) {
                foundMonth = monthValue
                remainingAfterMonth = input.removePrefix(monthName).trim()
                break
            }
        }

        if (foundMonth == null) return null

        // Extract day number (with optional ordinal suffix)
        val dayMatch = Regex("^(\\d{1,2})(st|nd|rd|th)?").find(remainingAfterMonth)
        if (dayMatch == null) return null

        val day = dayMatch.groupValues[1].toIntOrNull() ?: return null
        if (day < 1 || day > 31) return null

        val remainingAfterDay = remainingAfterMonth.substring(dayMatch.range.last + 1).trim()

        // Parse time portion
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.MONTH, foundMonth)
        calendar.set(Calendar.DAY_OF_MONTH, day)

        // Handle special time words
        val cleanTimeStr = remainingAfterDay
            .removePrefix("at").trim()
            .removePrefix(",").trim()

        when {
            cleanTimeStr.startsWith("midnight") || cleanTimeStr == "12:00 a.m." || cleanTimeStr == "12 a.m." -> {
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
            }
            cleanTimeStr.startsWith("noon") || cleanTimeStr == "12:00 p.m." || cleanTimeStr == "12 p.m." -> {
                calendar.set(Calendar.HOUR_OF_DAY, 12)
                calendar.set(Calendar.MINUTE, 0)
            }
            cleanTimeStr.isNotEmpty() -> {
                val timeParsed = parseTimeFromString(cleanTimeStr)
                if (timeParsed != null) {
                    calendar.set(Calendar.HOUR_OF_DAY, timeParsed.first)
                    calendar.set(Calendar.MINUTE, timeParsed.second)
                } else {
                    // Default to 9 AM if no time specified
                    calendar.set(Calendar.HOUR_OF_DAY, 9)
                    calendar.set(Calendar.MINUTE, 0)
                }
            }
            else -> {
                // Default to 9 AM if no time specified
                calendar.set(Calendar.HOUR_OF_DAY, 9)
                calendar.set(Calendar.MINUTE, 0)
            }
        }

        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        // If date is in the past, assume next year
        if (calendar.timeInMillis < System.currentTimeMillis()) {
            calendar.add(Calendar.YEAR, 1)
        }

        return calendar.timeInMillis
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
