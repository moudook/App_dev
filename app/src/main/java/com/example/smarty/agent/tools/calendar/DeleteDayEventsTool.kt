package com.example.smarty.agent.tools.calendar

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.util.Log
import com.example.smarty.data.repository.JarvisRepository
import com.example.smarty.util.toon.ToonManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Arguments for deleting all events of a specific day.
 */
@Serializable
data class DeleteDayEventsArgs(
    @property:LLMDescription("The date to delete events for. Supports: 'today', 'tomorrow', 'YYYY-MM-DD' format, or natural language like 'next monday'")
    val date: String,
    @property:LLMDescription("If true, also delete recurring event instances (default false)")
    val includeRecurring: Boolean = false
)

/**
 * Result of deleting all events for a day.
 */
@Serializable
data class DeleteDayEventsResult(
    val success: Boolean,
    val deletedCount: Int = 0,
    val date: String? = null,
    val deletedEventTitles: List<String> = emptyList(),
    val message: String? = null,
    val error: String? = null
) {
    override fun toString(): String {
        val json = Json { encodeDefaults = false }
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

/**
 * Tool for deleting all calendar events on a specific day.
 *
 * Useful for clearing a day's schedule or canceling all events for a particular date.
 * PRIVACY: Only deletes non-private events. Private events are protected.
 */
class DeleteDayEventsTool(
    private val repository: JarvisRepository
) : Tool<DeleteDayEventsArgs, DeleteDayEventsResult>(
    argsSerializer = DeleteDayEventsArgs.serializer(),
    resultSerializer = DeleteDayEventsResult.serializer(),
    name = "delete_day_events",
    description = """
        Deletes all calendar events for a specific day.
        Triggers: "Clear my schedule for today", "Cancel all meetings tomorrow", "Delete all events on Monday".
        Requirement: Needs a date parameter (today, tomorrow, or specific date).
        Note: Private events are protected and won't be deleted.
    """.trimIndent()
) {
    companion object {
        private const val TAG = "DeleteDayEventsTool"
    }

    override suspend fun execute(args: DeleteDayEventsArgs): DeleteDayEventsResult {
        Log.d(TAG, "Deleting events for date: '${args.date}'")

        return try {
            // Parse the date string to get start and end of day
            val (startOfDay, endOfDay) = parseDateRange(args.date)

            if (startOfDay == null || endOfDay == null) {
                return DeleteDayEventsResult(
                    success = false,
                    message = "Could not parse date: '${args.date}'",
                    error = "Invalid date format"
                )
            }

            val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
            val formattedDate = dateFormat.format(Date(startOfDay))

            Log.d(TAG, "Date range: ${Date(startOfDay)} to ${Date(endOfDay)}")

            // Get AI-visible events for the day (excludes private events)
            val events = repository.getAiVisibleEventsInRange(startOfDay, endOfDay)

            if (events.isEmpty()) {
                return DeleteDayEventsResult(
                    success = true,
                    deletedCount = 0,
                    date = formattedDate,
                    message = "No events found for $formattedDate"
                )
            }

            Log.d(TAG, "Found ${events.size} events to delete")

            // Collect titles before deletion
            val deletedTitles = events.map { it.title }

            // Delete each event
            var deletedCount = 0
            for (event in events) {
                try {
                    repository.deleteCalendarEvent(event.id)
                    deletedCount++
                    Log.d(TAG, "Deleted event: ${event.title}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete event ${event.id}: ${e.message}")
                }
            }

            val message = when (deletedCount) {
                0 -> "No events were deleted"
                1 -> "Deleted 1 event from $formattedDate"
                else -> "Deleted $deletedCount events from $formattedDate"
            }

            Log.i(TAG, message)

            DeleteDayEventsResult(
                success = true,
                deletedCount = deletedCount,
                date = formattedDate,
                deletedEventTitles = deletedTitles.take(5), // Limit to first 5 for brevity
                message = message
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete day events: ${e.message}", e)
            DeleteDayEventsResult(
                success = false,
                message = "Failed to delete events: ${e.message}",
                error = e.message
            )
        }
    }

    /**
     * Parse a date string and return the start and end timestamps for that day.
     */
    private fun parseDateRange(dateStr: String): Pair<Long?, Long?> {
        val calendar = Calendar.getInstance()

        // Reset to start of day
        fun resetToStartOfDay() {
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
        }

        when (dateStr.lowercase().trim()) {
            "today" -> {
                resetToStartOfDay()
                val start = calendar.timeInMillis
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                return Pair(start, calendar.timeInMillis)
            }
            "tomorrow" -> {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                resetToStartOfDay()
                val start = calendar.timeInMillis
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                return Pair(start, calendar.timeInMillis)
            }
            "yesterday" -> {
                calendar.add(Calendar.DAY_OF_YEAR, -1)
                resetToStartOfDay()
                val start = calendar.timeInMillis
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                return Pair(start, calendar.timeInMillis)
            }
        }

        // Try parsing day names (next monday, this friday, etc.)
        val dayNames = mapOf(
            "sunday" to Calendar.SUNDAY,
            "monday" to Calendar.MONDAY,
            "tuesday" to Calendar.TUESDAY,
            "wednesday" to Calendar.WEDNESDAY,
            "thursday" to Calendar.THURSDAY,
            "friday" to Calendar.FRIDAY,
            "saturday" to Calendar.SATURDAY
        )

        val lowerDate = dateStr.lowercase().trim()
        for ((dayName, dayValue) in dayNames) {
            if (lowerDate.contains(dayName)) {
                val currentDay = calendar.get(Calendar.DAY_OF_WEEK)
                var daysToAdd = dayValue - currentDay
                if (daysToAdd <= 0) daysToAdd += 7 // Next week
                if (lowerDate.contains("next")) daysToAdd += 7

                calendar.add(Calendar.DAY_OF_YEAR, daysToAdd)
                resetToStartOfDay()
                val start = calendar.timeInMillis
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                return Pair(start, calendar.timeInMillis)
            }
        }

        // Try parsing YYYY-MM-DD format
        try {
            val parts = dateStr.split("-")
            if (parts.size == 3) {
                calendar.set(Calendar.YEAR, parts[0].toInt())
                calendar.set(Calendar.MONTH, parts[1].toInt() - 1) // Month is 0-indexed
                calendar.set(Calendar.DAY_OF_MONTH, parts[2].toInt())
                resetToStartOfDay()
                val start = calendar.timeInMillis
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                return Pair(start, calendar.timeInMillis)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse date as YYYY-MM-DD: $dateStr")
        }

        // Try parsing MM/DD/YYYY or DD/MM/YYYY format
        try {
            val formats = listOf(
                SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()),
                SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()),
                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()),
                SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
            )

            for (format in formats) {
                try {
                    val date = format.parse(dateStr)
                    if (date != null) {
                        calendar.time = date
                        resetToStartOfDay()
                        val start = calendar.timeInMillis
                        calendar.add(Calendar.DAY_OF_YEAR, 1)
                        return Pair(start, calendar.timeInMillis)
                    }
                } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse date: $dateStr")
        }

        return Pair(null, null)
    }
}
