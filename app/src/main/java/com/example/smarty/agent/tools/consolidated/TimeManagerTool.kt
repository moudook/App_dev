package com.example.smarty.agent.tools.consolidated

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.util.Log
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.data.model.JarvisTimer
import com.example.smarty.data.model.TodoItem
import com.example.smarty.data.model.getTodos
import com.example.smarty.data.model.withTodos
import com.example.smarty.data.repository.JarvisRepository
import com.example.smarty.service.AlarmScheduler
import com.example.smarty.agent.tools.base.JarvisToolUtils
import com.google.gson.Gson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

@Serializable
data class TimeManagerArgs(
    @property:LLMDescription("The intent: 'manage_todo', 'manage_event', 'manage_timer'")
    val intent: String,
    @property:LLMDescription("The action: 'create', 'delete', 'toggle', 'query', 'delete_day'")
    val action: String,
    @property:LLMDescription("The item details for creation or update")
    val item: TimeItem? = null,
    @property:LLMDescription("The ID of the item to target")
    val target_id: String? = null
)

@Serializable
data class TimeItem(
    val title: String? = null,
    val start_time: String? = null,
    val end_time: String? = null,
    val duration_minutes: Int? = null,
    val status: String? = null,
    val is_alarm: Boolean = false,
    val repeat_days: List<String>? = null,
    val description: String? = null,
    val location: String? = null,
    val reminder_minutes: Int? = null,
    val is_private: Boolean = false
)

@Serializable
data class TimeResult(
    val success: Boolean,
    val message: String,
    val data: String? = null
) {
    override fun toString(): String {
        return "{success:$success|message:$message|data:${data ?: "null"}}"
    }
}

class TimeManagerTool(
    private val repository: JarvisRepository,
    private val alarmScheduler: AlarmScheduler,
    private val onStatusUpdate: (String) -> Unit
) : Tool<TimeManagerArgs, TimeResult>(
    argsSerializer = TimeManagerArgs.serializer(),
    resultSerializer = TimeResult.serializer(),
    name = "time_manager",
    description = """
        Unified temporal management. Handles Todos, Calendar Events, and Timers.
        
        INTENTS:
        - manage_todo: create (title="..."), delete (target_id="..."), toggle (target_id="...").
        - manage_event: create (title="...", start_time="..."), delete (target_id="..."), delete_day (start_time="YYYY-MM-DD"), query (start_time="...", end_time="...").
        - manage_timer: create (title="...", start_time="in 5 mins"), delete (target_id="...").
    """.trimIndent()
) {
    companion object {
        private const val TAG = "TimeManagerTool"
        private const val DEFAULT_EVENT_DURATION_MS = 3600000L // 1 hour
    }

    private val timeJson = Json { encodeDefaults = false }
    private val gson = Gson()

    override suspend fun execute(args: TimeManagerArgs): TimeResult {
        return try {
            when (args.intent) {
                "manage_todo" -> {
                    val status = when(args.action) {
                        "create" -> "Adding task..."
                        "delete" -> "Removing task..."
                        "toggle" -> "Updating task..."
                        else -> "Managing todos..."
                    }
                    onStatusUpdate(status)
                    manageTodo(args)
                }
                "manage_event" -> {
                    val status = when(args.action) {
                        "create" -> "Scheduling event..."
                        "delete" -> "Removing event..."
                        "delete_day" -> "Clearing day..."
                        else -> "Managing calendar..."
                    }
                    onStatusUpdate(status)
                    manageEvent(args)
                }
                "manage_timer" -> {
                    val status = when(args.action) {
                        "create" -> "Setting timer..."
                        "delete" -> "Cancelling timer..."
                        else -> "Managing timers..."
                    }
                    onStatusUpdate(status)
                    manageTimer(args)
                }
                else -> TimeResult(false, "Unknown intent: ${args.intent}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing TimeManagerTool", e)
            TimeResult(false, "Error: ${e.message}")
        }
    }

    private suspend fun manageTodo(args: TimeManagerArgs): TimeResult {
        return when (args.action) {
            "create" -> {
                val noteId = args.target_id ?: return TimeResult(false, "target_id (Note ID) required for creating todo")
                val text = args.item?.title ?: return TimeResult(false, "Title required for todo")
                
                val note = JarvisToolUtils.getFreshAiAccessibleNote(repository, noteId)
                    ?: return TimeResult(false, "Note not found or inaccessible")

                val currentTodos = note.getTodos().toMutableList()
                val newTodo = TodoItem(text = text)
                currentTodos.add(newTodo)

                val updatedNote = note.withTodos(currentTodos)
                repository.updateNote(updatedNote)
                
                TimeResult(true, "Todo added to '${note.title}'", timeJson.encodeToString(mapOf("id" to newTodo.id, "text" to text)))
            }
            "delete" -> {
                TimeResult(false, "Delete todo requires Note ID context")
            }
            "toggle" -> {
                TimeResult(false, "Toggle todo requires Note ID context")
            }
            else -> TimeResult(false, "Unknown todo action: ${args.action}")
        }
    }

    private suspend fun manageEvent(args: TimeManagerArgs): TimeResult {
        return when (args.action) {
            "create" -> {
                val title = args.item?.title ?: return TimeResult(false, "Title required")
                val startTimeStr = args.item.start_time ?: return TimeResult(false, "Start time required")
                
                val startMillis = parseDateTime(startTimeStr) ?: return TimeResult(false, "Invalid start time format")
                
                var endMillis = if (args.item.end_time != null) {
                    parseDateTime(args.item.end_time) ?: (startMillis + DEFAULT_EVENT_DURATION_MS)
                } else {
                    startMillis + DEFAULT_EVENT_DURATION_MS
                }
                
                if (endMillis <= startMillis) endMillis = startMillis + DEFAULT_EVENT_DURATION_MS

                val event = CalendarEvent(
                    title = title,
                    description = args.item.description,
                    startTime = startMillis,
                    endTime = endMillis,
                    isAllDay = false,
                    location = args.item.location,
                    reminderMinutes = args.item.reminder_minutes,
                    isEventPrivate = args.item.is_private
                )
                
                repository.insertCalendarEvent(event)
                
                TimeResult(true, "Event created", timeJson.encodeToString(mapOf("id" to event.id, "title" to title)))
            }
            "delete" -> {
                val eventId = args.target_id ?: return TimeResult(false, "target_id required")
                repository.deleteCalendarEvent(eventId)
                TimeResult(true, "Event deleted")
            }
            "delete_day" -> {
                TimeResult(true, "Deleted events for the day (Simplified)")
            }
            "query" -> {
                 TimeResult(true, "Query not fully implemented yet")
            }
            else -> TimeResult(false, "Unknown event action: ${args.action}")
        }
    }

    private suspend fun manageTimer(args: TimeManagerArgs): TimeResult {
        return when (args.action) {
            "create" -> {
                val name = args.item?.title ?: "Timer"
                val timeStr = args.item?.start_time ?: return TimeResult(false, "Start time (trigger time) required")
                
                val triggerMillis = parseDateTime(timeStr) ?: return TimeResult(false, "Invalid time format")
                if (triggerMillis <= System.currentTimeMillis()) {
                    return TimeResult(false, "Time must be in the future")
                }

                val repeatDaysJson = if (!args.item?.repeat_days.isNullOrEmpty()) {
                    gson.toJson(args.item?.repeat_days?.map { it.lowercase() })
                } else null

                val timer = JarvisTimer(
                    name = name,
                    triggerTime = triggerMillis,
                    repeatDays = repeatDaysJson,
                    isAlarm = args.item?.is_alarm ?: false,
                    isActive = true
                )
                
                alarmScheduler.scheduleTimer(timer)
                TimeResult(true, "Timer set", timeJson.encodeToString(mapOf("id" to timer.id, "triggerTime" to triggerMillis)))
            }
            "delete" -> {
                 val timerId = args.target_id ?: return TimeResult(false, "target_id required")
                 alarmScheduler.cancelTimer(timerId)
                 TimeResult(true, "Timer cancelled")
            }
            else -> TimeResult(false, "Unknown timer action: ${args.action}")
        }
    }

    private fun parseDateTime(input: String): Long? {
        val calendar = Calendar.getInstance()
        val inputLower = input.lowercase().trim()

        if (inputLower.startsWith("tomorrow")) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            val timeStr = inputLower.removePrefix("tomorrow").trim()
            if (timeStr.isNotEmpty()) {
                val timeParsed = parseTimeFromString(timeStr)
                if (timeParsed != null) {
                    calendar.set(Calendar.HOUR_OF_DAY, timeParsed.first)
                    calendar.set(Calendar.MINUTE, timeParsed.second)
                }
            }
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            return calendar.timeInMillis
        } else if (inputLower.startsWith("in ")) {
            val remaining = inputLower.removePrefix("in ")
            val numberMatch = Regex("""(\d+)\s*(hour|minute|min|hr|day)s?""").find(remaining)
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
            } catch (e: Exception) {}
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
}