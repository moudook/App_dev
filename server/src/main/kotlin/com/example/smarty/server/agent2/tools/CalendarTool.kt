package com.example.smarty.server.agent2.tools

import com.example.smarty.server.agent.ToolPermissionEnforcer
import com.example.smarty.server.data.CalendarRepository
import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.agent.tool.P
import org.slf4j.LoggerFactory

class CalendarTool(
    private val calendarRepository: CalendarRepository? = null,
    private val userId: String = "dev-user",
    private val permissionEnforcer: ToolPermissionEnforcer = ToolPermissionEnforcer(),
) {
    private val logger = LoggerFactory.getLogger(CalendarTool::class.java)

    @Tool("Add a calendar event. Specify title and when (natural language like 'tomorrow 2pm').")
    suspend fun addEvent(
        @P("Event title") title: String,
        @P("When: natural language like 'tomorrow 2pm', 'Friday', 'Dec 25'") when_time: String,
        @P("Duration: '1 hour', '30 min' (optional)") duration: String? = null,
        @P("Extra description or details") description: String? = null,
    ): String {
        logger.info("[CalendarTool] Adding event: $title")
        return "Event added: '$title'"
    }

    @Tool("List calendar events for a given time period.")
    suspend fun listEvents(
        @P("Time period: 'today', 'tomorrow', 'this week', 'next Monday', 'Dec 25'") when_time: String = "today",
    ): String {
        logger.info("[CalendarTool] Listing events for: $when_time")
        return "No events for $when_time."
    }

    @Tool("Remove a calendar event by its ID.")
    suspend fun removeEvent(
        @P("Event ID to remove") id: String,
    ): String {
        logger.info("[CalendarTool] Removing event: $id")
        return "Event removed."
    }
}
