package com.example.smarty.features.calendar.domain

import android.util.Log
import com.example.smarty.data.repository.SmartyRepository
import com.example.smarty.core.domain.model.CalendarEvent
import com.example.smarty.core.domain.model.SmartyTimer
import com.example.smarty.service.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Manages calendar event operations.
 *
 * Responsibilities:
 * - Calendar event CRUD operations
 * - Event queries (by date range, upcoming, etc.)
 * - Alarm scheduling for event reminders
 * - Privacy filtering for AI-visible events
 *
 * @property repository Database access for calendar events via Repository
 * @property alarmScheduler Optional alarm scheduler for reminders
 * @property scope Coroutine scope for async operations
 */
class CalendarManager(
    private val repository: SmartyRepository,
    private val alarmScheduler: AlarmScheduler?,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "CalendarManager"
    }

    // ==================== Observable State ====================

    /**
     * All calendar events, ordered by start time.
     * Use for full calendar view.
     */
    val calendarEvents: StateFlow<List<CalendarEvent>> = repository.getAllEvents()
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Upcoming events (from now onwards).
     * Use for dashboard/overview displays.
     */
    val upcomingEvents: Flow<List<CalendarEvent>> = repository.getUpcomingEvents()

    /**
     * AI-visible events (excludes private events).
     * Use when building context for AI/Agent services.
     */
    val aiVisibleEvents: Flow<List<CalendarEvent>> = repository.getAiVisibleEvents()

    /**
     * Observable stream of active timers.
     */
    val activeTimers: StateFlow<List<SmartyTimer>> = alarmScheduler?.activeTimers
        ?.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
        ?: MutableStateFlow(emptyList<SmartyTimer>())

    // ==================== CRUD Operations ====================

    /**
     * Add a new calendar event.
     *
     * @param title Event title
     * @param description Optional event description
     * @param startTime Event start time (Unix timestamp in milliseconds)
     * @param endTime Event end time (Unix timestamp in milliseconds)
     * @param isAllDay Whether this is an all-day event
     * @param location Optional event location
     * @param color Optional color for visual distinction
     * @param reminderMinutes Minutes before event to remind (null = no reminder)
     * @param isPrivate Whether event is private (hidden from AI)
     * @param linkedNoteId Optional link to a note
     */
    fun addCalendarEvent(
        title: String,
        description: String? = null,
        startTime: Long,
        endTime: Long,
        isAllDay: Boolean = false,
        location: String? = null,
        color: Int? = null,
        reminderMinutes: Int? = null,
        isPrivate: Boolean = false,
        linkedNoteId: String? = null,
        googleEventId: String? = null
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val event = CalendarEvent(
                    title = title,
                    description = description,
                    startTime = startTime,
                    endTime = endTime,
                    isAllDay = isAllDay,
                    location = location,
                    color = color,
                    reminderMinutes = reminderMinutes,
                    isEventPrivate = isPrivate,
                    linkedNoteId = linkedNoteId,
                    googleEventId = googleEventId
                )

                repository.insertEvent(event)
                Log.d(TAG, "Added calendar event: $title")

                // Schedule alarm if reminder is set
                reminderMinutes?.let { minutes ->
                    scheduleReminder(event, minutes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding calendar event: ${e.message}", e)
            }
        }
    }

    /**
     * Add a calendar event from an existing CalendarEvent object.
     */
    fun addCalendarEvent(event: CalendarEvent) {
        scope.launch(Dispatchers.IO) {
            try {
                repository.insertEvent(event)
                Log.d(TAG, "Added calendar event: ${event.title}")

                // Schedule alarm if reminder is set
                event.reminderMinutes?.let { minutes ->
                    scheduleReminder(event, minutes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding calendar event: ${e.message}", e)
            }
        }
    }

    /**
     * Update an existing calendar event.
     */
    fun updateCalendarEvent(event: CalendarEvent) {
        scope.launch(Dispatchers.IO) {
            try {
                val updatedEvent = event.copy(updatedAt = System.currentTimeMillis())
                repository.updateEvent(updatedEvent)
                Log.d(TAG, "Updated calendar event: ${event.title}")

                // Reschedule alarm if reminder changed
                event.reminderMinutes?.let { minutes ->
                    cancelReminder(event.id)
                    scheduleReminder(updatedEvent, minutes)
                } ?: cancelReminder(event.id)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating calendar event: ${e.message}", e)
            }
        }
    }

    /**
     * Delete a calendar event by ID.
     */
    fun deleteCalendarEvent(eventId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                cancelReminder(eventId)
                repository.deleteEventById(eventId)
                Log.d(TAG, "Deleted calendar event: $eventId")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting calendar event: ${e.message}", e)
            }
        }
    }

    /**
     * Delete a calendar event.
     */
    fun deleteCalendarEvent(event: CalendarEvent) {
        deleteCalendarEvent(event.id)
    }

    // ==================== Query Operations ====================

    /**
     * Get a single event by ID.
     */
    suspend fun getEventById(eventId: String): CalendarEvent? {
        return try {
            repository.getEventById(eventId)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting event by ID: ${e.message}", e)
            null
        }
    }

    /**
     * Get events within a date range.
     *
     * @param startMillis Start of range (inclusive)
     * @param endMillis End of range (exclusive)
     */
    fun getEventsInRange(startMillis: Long, endMillis: Long): Flow<List<CalendarEvent>> {
        return repository.getEventsInRange(startMillis, endMillis)
    }

    /**
     * Get events for a specific day.
     *
     * @param dayMillis Any timestamp within the desired day
     */
    suspend fun getEventsForDay(dayMillis: Long): List<CalendarEvent> {
        val (dayStart, dayEnd) = getDayBounds(dayMillis)
        return try {
            repository.getEventsForDay(dayStart, dayEnd)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting events for day: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get today's events.
     */
    suspend fun getTodayEvents(): List<CalendarEvent> {
        val (dayStart, dayEnd) = getDayBounds(System.currentTimeMillis())
        return try {
            repository.getTodayEvents(dayStart, dayEnd)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting today's events: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get events linked to a specific note.
     */
    fun getEventsForNote(noteId: String): Flow<List<CalendarEvent>> {
        return repository.getEventsForNote(noteId)
    }

    /**
     * Get AI-visible upcoming events (for agent context).
     */
    suspend fun getAiVisibleUpcomingEvents(limit: Int = 10): List<CalendarEvent> {
        return try {
            repository.getAiVisibleUpcomingEvents(System.currentTimeMillis(), limit)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting AI-visible upcoming events: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Check if there are events on a specific day.
     */
    suspend fun hasEventsOnDay(dayMillis: Long): Boolean {
        val (dayStart, dayEnd) = getDayBounds(dayMillis)
        return try {
            repository.hasEventsOnDay(dayStart, dayEnd)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking events on day: ${e.message}", e)
            false
        }
    }

    /**
     * Get total event count.
     */
    suspend fun getEventCount(): Int {
        return try {
            repository.getEventCount()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting event count: ${e.message}", e)
            0
        }
    }

    // ==================== Note Link Operations ====================

    /**
     * Clear note link when a note is deleted.
     * Call this from NoteOperationsManager when deleting a note.
     */
    suspend fun clearNoteLinkForNote(noteId: String) {
        try {
            repository.clearNoteLinkForNote(noteId)
            Log.d(TAG, "Cleared note link for note: $noteId")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing note link: ${e.message}", e)
        }
    }

    /**
     * Parse natural language date/time strings into Unix timestamps.
     * Supports: "tomorrow at 10am", "in 5 minutes", "2026-01-27 14:00", etc.
     */
    fun parseDateTime(input: String): Long? {
        val calendar = Calendar.getInstance()
        val inputLower = input.lowercase().trim()

        // Handle numeric timestamp (milliseconds)
        val numericValue = inputLower.toLongOrNull()
        if (numericValue != null) {
            return numericValue
        }

        // Handle duration strings (e.g. "5m", "1h") with or without "in " prefix
        val durationInput = if (inputLower.startsWith("in ")) inputLower.removePrefix("in ") else inputLower
        val numberMatch = Regex("""(\d+)\s*(hour|minute|min|hr|day)s?""").find(durationInput)
        if (numberMatch != null) {
            val amount = numberMatch.groupValues[1].toIntOrNull() ?: 0
            val unit = numberMatch.groupValues[2]
            when {
                unit.startsWith("hour") || unit.startsWith("hr") -> calendar.add(Calendar.HOUR, amount)
                unit.startsWith("min") -> calendar.add(Calendar.MINUTE, amount)
                unit.startsWith("day") -> calendar.add(Calendar.DAY_OF_YEAR, amount)
            }
            return calendar.timeInMillis
        }

        if (inputLower.startsWith("tomorrow")) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            val timePart = inputLower.removePrefix("tomorrow").trim()
            if (timePart.isNotBlank()) {
                parseTimeFromString(timePart)?.let { (h, m) ->
                    calendar.set(Calendar.HOUR_OF_DAY, h)
                    calendar.set(Calendar.MINUTE, m)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                }
            } else {
                calendar.set(Calendar.HOUR_OF_DAY, 9) // Default to 9 AM tomorrow
                calendar.set(Calendar.MINUTE, 0)
            }
            return calendar.timeInMillis
        }

        if (inputLower.startsWith("today")) {
            val timePart = inputLower.removePrefix("today").trim()
            if (timePart.isNotBlank()) {
                parseTimeFromString(timePart)?.let { (h, m) ->
                    calendar.set(Calendar.HOUR_OF_DAY, h)
                    calendar.set(Calendar.MINUTE, m)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                }
            } else {
                calendar.set(Calendar.HOUR_OF_DAY, 9)
            }
            return calendar.timeInMillis
        }

        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault()),
            SimpleDateFormat("h:mm a", Locale.getDefault()),
            SimpleDateFormat("HH:mm", Locale.getDefault())
        )

        for (format in formats) {
            try {
                val date = format.parse(input)
                if (date != null) {
                    if (input.contains(":")) {
                        val tempCal = Calendar.getInstance()
                        tempCal.time = date
                        calendar.set(Calendar.HOUR_OF_DAY, tempCal.get(Calendar.HOUR_OF_DAY))
                        calendar.set(Calendar.MINUTE, tempCal.get(Calendar.MINUTE))
                        calendar.set(Calendar.SECOND, 0)
                        if (calendar.timeInMillis <= System.currentTimeMillis()) {
                            calendar.add(Calendar.DAY_OF_YEAR, 1)
                        }
                        return calendar.timeInMillis
                    }
                    return date.time
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse date/time: ${e.message}")
            }
        }

        return null
    }

    private fun parseTimeFromString(input: String): Pair<Int, Int>? {
        val cleanInput = input.removePrefix("at").trim()
        val timeRegex = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""", RegexOption.IGNORE_CASE)
        val match = timeRegex.find(cleanInput) ?: return Pair(9, 0)

        var hour = match.groupValues[1].toIntOrNull() ?: 9
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val period = match.groupValues[3].lowercase()

        if (period == "pm" && hour != 12) hour += 12
        else if (period == "am" && hour == 12) hour = 0

        return Pair(hour, minute)
    }

    // ==================== Alarm/Reminder Operations ====================

    /**
     * Schedule a timer or alarm.
     */
    fun setTimer(name: String, triggerTime: Long, isAlarm: Boolean) {
        alarmScheduler?.let { scheduler ->
            val timer = SmartyTimer(
                name = name,
                triggerTime = triggerTime,
                isAlarm = isAlarm,
                isActive = true
            )
            scheduler.scheduleTimer(timer)
            Log.d(TAG, "Scheduled ${if (isAlarm) "alarm" else "timer"}: $name at $triggerTime")
        }
    }

    /**
     * Cancel an active timer or alarm.
     */
    fun cancelTimer(timerId: String) {
        alarmScheduler?.cancelTimer(timerId)
        Log.d(TAG, "Cancelled timer: $timerId")
    }

    /**
     * Cancel all active timers and alarms.
     */
    fun cancelAllTimers() {
        scope.launch(Dispatchers.IO) {
            try {
                val active = repository.getActiveTimersOnce()
                active.forEach { timer ->
                    alarmScheduler?.cancelTimer(timer.id)
                }
                Log.i(TAG, "Cancelled ${active.size} active timers")
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling all timers: ${e.message}")
            }
        }
    }

    /**
     * Schedule a reminder alarm for an event using SmartyTimer.
     * Creates a one-time timer that fires at the reminder time.
     */
    private fun scheduleReminder(event: CalendarEvent, minutesBefore: Int) {
        alarmScheduler?.let { scheduler ->
            val reminderTime = event.startTime - (minutesBefore * 60 * 1000L)
            if (reminderTime > System.currentTimeMillis()) {
                // Create a SmartyTimer for the event reminder
                val timer = SmartyTimer(
                    id = "event_reminder_${event.id}",
                    name = "Reminder: ${event.title}",
                    triggerTime = reminderTime,
                    isAlarm = false // Use notification, not loud alarm
                )
                scheduler.scheduleTimer(timer)
                Log.d(TAG, "Scheduled reminder for event ${event.id} at $reminderTime")
            }
        }
    }

    /**
     * Cancel a reminder alarm for an event.
     */
    private fun cancelReminder(eventId: String) {
        alarmScheduler?.cancelTimer("event_reminder_$eventId")
    }

    /**
     * Search for events by query text.
     */
    suspend fun searchEvents(query: String): List<CalendarEvent> {
        return try {
            val allEvents = repository.getAllEventsOnce()
            allEvents.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.description?.contains(query, ignoreCase = true) == true ||
                it.location?.contains(query, ignoreCase = true) == true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching events: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Bulk delete calendar events by IDs.
     */
    fun bulkDeleteEvents(eventIds: List<String>) {
        scope.launch(Dispatchers.IO) {
            try {
                eventIds.forEach { eventId ->
                    cancelReminder(eventId)
                    repository.deleteEventById(eventId)
                }
                Log.d(TAG, "Bulk deleted ${eventIds.size} calendar events")
            } catch (e: Exception) {
                Log.e(TAG, "Error bulk deleting events: ${e.message}", e)
            }
        }
    }

    // ==================== Utility Functions ====================

    /**
     * Get the start and end timestamps for a day in local timezone.
     */
    private fun getDayBounds(millis: Long): Pair<Long, Long> {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dayStart = calendar.timeInMillis

        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val dayEnd = calendar.timeInMillis

        return Pair(dayStart, dayEnd)
    }

    /**
     * Get all events as a one-shot query (for backup).
     */
    suspend fun getAllEventsOnce(): List<CalendarEvent> {
        return try {
            repository.getAllEventsOnce()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all events: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Delete all events.
     * Use with caution - typically for testing or user-initiated data wipe.
     */
    suspend fun deleteAllEvents() {
        try {
            repository.deleteAllEvents()
            Log.w(TAG, "Deleted all calendar events")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting all events: ${e.message}", e)
        }
    }
}

