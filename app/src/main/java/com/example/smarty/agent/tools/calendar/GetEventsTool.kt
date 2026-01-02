package com.example.smarty.agent.tools.calendar

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.util.Log
import com.example.smarty.data.cache.ToolResultCache
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.data.repository.CogniRepository
import com.example.smarty.util.toon.ToonManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Arguments for getting calendar events.
 */
@Serializable
data class GetEventsArgs(
    @property:LLMDescription("Time range filter: 'today', 'tomorrow', 'this_week', 'next_week', 'upcoming' (default)")
    val timeRange: String = "upcoming",
    @property:LLMDescription("Optional search query to filter events by title/description")
    val query: String? = null,
    @property:LLMDescription("Maximum number of events to return (default 10)")
    val limit: Int = 10
)

/**
 * Result containing list of events.
 */
@Serializable
data class GetEventsResult(
    val success: Boolean,
    val events: List<EventSummary> = emptyList(),
    val count: Int = 0,
    val timeRange: String? = null,
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
 * Compact event summary for AI context.
 */
@Serializable
data class EventSummary(
    val id: String,
    val title: String,
    val startTime: String,  // Human-readable
    val endTime: String,    // Human-readable
    val location: String? = null,
    val description: String? = null,
    val isAllDay: Boolean = false
)

/**
 * Tool for retrieving calendar events.
 *
 * BUG FIX (NEW-015): AI needs a way to query upcoming events to answer questions like
 * "What's on my calendar today?" or "Do I have any meetings this week?"
 *
 * PRIVACY: Only returns non-private events. Private events are never exposed to AI.
 */
class GetEventsTool(
    private val repository: CogniRepository
) : Tool<GetEventsArgs, GetEventsResult>(
    argsSerializer = GetEventsArgs.serializer(),
    resultSerializer = GetEventsResult.serializer(),
    name = "get_events",
    description = """
        Retrieves calendar events for the user.
        Use this to check what's on the user's calendar or find specific events.
        Time ranges: 'today', 'tomorrow', 'this_week', 'next_week', 'upcoming'
        Can also search by title/description with the query parameter.
        Note: Private events marked by user are never returned.
    """.trimIndent()
) {
    companion object {
        private const val TAG = "GetEventsTool"
    }

    override suspend fun execute(args: GetEventsArgs): GetEventsResult {
        Log.d(TAG, "Getting events: timeRange='${args.timeRange}', query='${args.query}', limit=${args.limit}")

        // Check cache first to avoid duplicate DB queries
        val cacheKey = ToolResultCache.generateKey(name, "${args.timeRange}|${args.query}|${args.limit}")
        val cached = ToolResultCache.get(cacheKey)
        if (cached != null) {
            Log.d(TAG, "Returning cached result for timeRange: '${args.timeRange}'")
            return Json.decodeFromString(GetEventsResult.serializer(), cached)
        }

        return try {
            val (startMillis, endMillis) = getTimeRange(args.timeRange)
            Log.d(TAG, "Time range: ${Date(startMillis)} to ${Date(endMillis)}")

            // Get AI-visible events only (excludes private events)
            val events = if (args.query != null) {
                repository.searchCalendarEvents(args.query)
                    .filter { it.startTime >= startMillis && it.startTime < endMillis }
                    .take(args.limit)
            } else {
                repository.getAiVisibleEventsInRange(startMillis, endMillis)
                    .take(args.limit)
            }

            Log.d(TAG, "Found ${events.size} events")

            val dateFormat = SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault())
            val summaries = events.map { event ->
                EventSummary(
                    id = event.id,
                    title = event.title,
                    startTime = dateFormat.format(Date(event.startTime)),
                    endTime = dateFormat.format(Date(event.endTime)),
                    location = event.location,
                    description = event.description?.take(100), // Truncate long descriptions
                    isAllDay = event.isAllDay
                )
            }

            val message = when {
                summaries.isEmpty() -> "No events found for ${args.timeRange}"
                summaries.size == 1 -> "Found 1 event"
                else -> "Found ${summaries.size} events"
            }

            val result = GetEventsResult(
                success = true,
                events = summaries,
                count = summaries.size,
                timeRange = args.timeRange,
                message = message
            )

            // Cache the successful result
            ToolResultCache.put(cacheKey, Json.encodeToString(GetEventsResult.serializer(), result))

            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get events: ${e.message}", e)
            GetEventsResult(
                success = false,
                error = e.message,
                message = "Failed to retrieve events"
            )
        }
    }

    /**
     * Calculate start and end timestamps for the given time range.
     */
    private fun getTimeRange(range: String): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        // Reset to start of day
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        return when (range.lowercase()) {
            "today" -> {
                val start = calendar.timeInMillis
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                Pair(start, calendar.timeInMillis)
            }
            "tomorrow" -> {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                val start = calendar.timeInMillis
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                Pair(start, calendar.timeInMillis)
            }
            "this_week" -> {
                // Go to start of week (Sunday or Monday depending on locale)
                calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                val start = calendar.timeInMillis
                calendar.add(Calendar.WEEK_OF_YEAR, 1)
                Pair(start, calendar.timeInMillis)
            }
            "next_week" -> {
                calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                calendar.add(Calendar.WEEK_OF_YEAR, 1)
                val start = calendar.timeInMillis
                calendar.add(Calendar.WEEK_OF_YEAR, 1)
                Pair(start, calendar.timeInMillis)
            }
            "upcoming", "" -> {
                // From now to 30 days in future
                val start = System.currentTimeMillis()
                calendar.timeInMillis = start
                calendar.add(Calendar.DAY_OF_YEAR, 30)
                Pair(start, calendar.timeInMillis)
            }
            else -> {
                Log.w(TAG, "Unknown time range '$range', using 'upcoming'")
                val start = System.currentTimeMillis()
                calendar.timeInMillis = start
                calendar.add(Calendar.DAY_OF_YEAR, 30)
                Pair(start, calendar.timeInMillis)
            }
        }
    }
}
