package com.example.smarty.agent.tools.calendar

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.data.repository.CogniRepository
import kotlinx.serialization.KSerializer
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
 */
class DeleteEventTool(
    private val repository: CogniRepository
) : Tool<DeleteEventArgs, CalendarOperationResult>() {

    override val argsSerializer: KSerializer<DeleteEventArgs> = DeleteEventArgs.serializer()
    override val resultSerializer: KSerializer<CalendarOperationResult> = CalendarOperationResult.serializer()

    override val name = "delete_event"

    override val description = """
        Deletes a calendar event.
        Can delete by event ID or by searching for the event title.
        Use this when the user wants to cancel or remove a meeting/event.
    """.trimIndent()

    override suspend fun execute(args: DeleteEventArgs): CalendarOperationResult {
        return try {
            if (args.eventId == null && args.query == null) {
                return CalendarOperationResult(
                    success = false,
                    message = "Please provide either an event ID or a search query",
                    error = "No identifier provided"
                )
            }

            // SECURITY: Use AI-safe methods that filter out private events
            val event = if (args.eventId != null) {
                repository.getCalendarEventByIdForAi(args.eventId)
            } else {
                repository.searchCalendarEvents(args.query!!).firstOrNull()
            }

            if (event == null) {
                return CalendarOperationResult(
                    success = false,
                    message = "Event not found",
                    error = "Not found"
                )
            }

            repository.deleteCalendarEvent(event.id)

            CalendarOperationResult(
                success = true,
                eventId = event.id,
                eventTitle = event.title,
                message = "Event '${event.title}' has been deleted"
            )
        } catch (e: Exception) {
            CalendarOperationResult(
                success = false,
                message = "Failed to delete event",
                error = e.message
            )
        }
    }
}
