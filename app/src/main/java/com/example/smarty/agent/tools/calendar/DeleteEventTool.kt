package com.example.smarty.agent.tools.calendar

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.util.Log
import com.example.smarty.data.repository.JarvisRepository
import kotlinx.serialization.Serializable

@Serializable
data class DeleteEventArgs(
    @property:LLMDescription("ID of the event to delete (if known)")
    val eventId: String? = null,
    @property:LLMDescription("Search query to find the event by title (if eventId not known)")
    val query: String? = null
)

/**
 * Tool for deleting calendar events.
 *
 * BUG FIX (AI-002): Added comprehensive logging for debugging.
 */
class DeleteEventTool(
    private val repository: JarvisRepository
) : Tool<DeleteEventArgs, CalendarOperationResult>(
    argsSerializer = DeleteEventArgs.serializer(),
    resultSerializer = CalendarOperationResult.serializer(),
    name = "delete_event",
    description = """
        Cancels/deletes an existing calendar event or meeting.
        Triggers: "Cancel my meeting", "Remove the appointment", "Delete the event".
        Requirement: Needs 'eventId' or a search 'query' to identify the event.
    """.trimIndent()
) {
    companion object {
        private const val TAG = "DeleteEventTool"
    }

    override suspend fun execute(args: DeleteEventArgs): CalendarOperationResult {
        Log.d(TAG, "Deleting event: eventId='${args.eventId}', query='${args.query}'")

        return try {
            if (args.eventId == null && args.query == null) {
                Log.w(TAG, "Delete failed: no identifier provided")
                return CalendarOperationResult(
                    success = false,
                    message = "Please provide either an event ID or a search query",
                    error = "No identifier provided"
                )
            }

            // SECURITY: Use AI-safe methods that filter out private events
            val event = if (args.eventId != null) {
                Log.d(TAG, "Looking up event by ID: ${args.eventId}")
                repository.getCalendarEventByIdForAi(args.eventId)
            } else {
                Log.d(TAG, "Searching for event with query: '${args.query}'")
                repository.searchCalendarEvents(args.query!!).firstOrNull()
            }

            if (event == null) {
                Log.w(TAG, "Event not found or is private")
                return CalendarOperationResult(
                    success = false,
                    message = "Event not found (note: private events cannot be accessed)",
                    error = "Not found"
                )
            }

            Log.d(TAG, "Found event: id=${event.id}, title='${event.title}'")
            repository.deleteCalendarEvent(event.id)
            Log.i(TAG, "Event deleted successfully: ${event.id}")

            CalendarOperationResult(
                success = true,
                eventId = event.id,
                eventTitle = event.title,
                message = "Event '${event.title}' has been deleted"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete event: ${e.message}", e)
            CalendarOperationResult(
                success = false,
                message = "Failed to delete event: ${e.message}",
                error = e.message
            )
        }
    }
}
