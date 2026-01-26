package com.example.smarty.agent.tools.consolidated

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TimeManagerArgs(
    @property:LLMDescription("The intent: 'manage_todo', 'manage_event', 'manage_timer'")
    val intent: String,
    @property:LLMDescription("The action: 'create', 'delete', 'toggle', 'query'")
    val action: String,
    @property:LLMDescription("The item details for creation or update")
    val item: TimeItem? = null,
    @property:LLMDescription("The ID of the item to target")
    val target_id: String? = null
)

@Serializable
data class TimeItem(
    val title: String? = null,
    val start_time: String? = null, // Natural language: "tomorrow at 10am", "in 5 mins"
    val end_time: String? = null,
    val description: String? = null,
    val location: String? = null,
    val is_alarm: Boolean = false,
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

/**
 * Hybridized Time Manager Tool.
 * 100% logic-free. Delegates to CalendarManager and AlarmScheduler.
 */
class TimeManagerTool(
    private val onAddTodo: (String, String) -> Unit,
    private val onAddEvent: (String, String, String?, String?, String?, Boolean) -> Unit,
    private val onDeleteEvent: (String) -> Unit,
    private val onBulkDeleteEvents: (List<String>) -> Unit,
    private val onQueryEvents: suspend (String?) -> List<com.example.smarty.data.model.CalendarEvent>,
    private val onSetTimer: (String, String, Boolean) -> Unit,
    private val onCancelTimer: (String) -> Unit,
    private val onStatusUpdate: (String) -> Unit
) : Tool<TimeManagerArgs, TimeResult>(
    argsSerializer = TimeManagerArgs.serializer(),
    resultSerializer = TimeResult.serializer(),
    name = "time_manager",
    description = """
        Unified temporal management. Handles Todos, Calendar Events, and Timers.

        INTENTS:
        - manage_todo: create (title="...", target_id="NoteID").
        - manage_event: create (title="...", start_time="tomorrow at 9am"), delete (target_id="..."), query (item={title="query"}).
        - manage_timer: create (title="...", start_time="in 5 mins"), delete (target_id="...").
    """.trimIndent()
) {
    private val timeJson = Json { encodeDefaults = false }

    override suspend fun execute(args: TimeManagerArgs): TimeResult {
        return try {
            when (args.intent) {
                "manage_todo" -> {
                    if (args.action == "create") {
                        val noteId = args.target_id ?: return TimeResult(false, "target_id required")
                        val text = args.item?.title ?: return TimeResult(false, "Title required")
                        onAddTodo(noteId, text)
                        TimeResult(true, "Todo added to note")
                    } else TimeResult(false, "Unknown todo action")
                }
                "manage_event" -> {
                    when (args.action) {
                        "create" -> {
                            val title = args.item?.title ?: return TimeResult(false, "Title required")
                            val start = args.item.start_time ?: return TimeResult(false, "Start time required")
                            onStatusUpdate("Scheduling event...")
                            onAddEvent(title, start, args.item.end_time, args.item.description, args.item.location, args.item.is_private)
                            TimeResult(true, "Event scheduling initiated")
                        }
                        "delete" -> {
                            val id = args.target_id ?: return TimeResult(false, "ID required")
                            onDeleteEvent(id)
                            TimeResult(true, "Event deleted")
                        }
                        "bulk_delete" -> {
                            val ids = args.target_id?.split(",")?.map { it.trim() } ?: return TimeResult(false, "Comma-separated IDs required in target_id")
                            onBulkDeleteEvents(ids)
                            TimeResult(true, "Bulk delete initiated for ${ids.size} events")
                        }
                        "query" -> {
                            onStatusUpdate("Searching events...")
                            val query = args.item?.title
                            val events = onQueryEvents(query)
                            TimeResult(true, "Found ${events.size} events", timeJson.encodeToString(events))
                        }
                        else -> TimeResult(false, "Unknown event action")
                    }
                }
                "manage_timer" -> {
                    when (args.action) {
                        "create" -> {
                            val title = args.item?.title ?: "Timer"
                            val start = args.item?.start_time ?: return TimeResult(false, "Start time required")
                            onStatusUpdate("Setting timer...")
                            onSetTimer(title, start, args.item.is_alarm)
                            TimeResult(true, "Timer/Alarm initiated")
                        }
                        "delete" -> {
                            val id = args.target_id ?: return TimeResult(false, "ID required")
                            onCancelTimer(id)
                            TimeResult(true, "Timer cancelled")
                        }
                        else -> TimeResult(false, "Unknown timer action")
                    }
                }
                else -> TimeResult(false, "Unknown intent: ${args.intent}")
            }
        } catch (e: Exception) {
            TimeResult(false, "Error: ${e.message}")
        }
    }
}
