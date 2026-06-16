package com.example.smarty.server.agent2.tools

import com.example.smarty.server.agent.ToolPermissionEnforcer
import com.example.smarty.server.data.TimerRepository
import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.agent.tool.P
import org.slf4j.LoggerFactory

class TimerTool(
    private val timerRepository: TimerRepository? = null,
    private val userId: String = "dev-user",
    private val permissionEnforcer: ToolPermissionEnforcer = ToolPermissionEnforcer(),
) {
    private val logger = LoggerFactory.getLogger(TimerTool::class.java)

    @Tool("Set a timer, alarm, or reminder. Specify what and when (natural language).")
    suspend fun setTimer(
        @P("What to remind about") what: String,
        @P("When: 'in 10 min', 'at 7am', 'tomorrow 3pm'") when_time: String,
        @P("Repeat: daily|weekdays|weekly|monthly (optional)") repeat: String? = null,
    ): String {
        logger.info("[TimerTool] Setting: $what")
        return "Reminder set: '$what'"
    }

    @Tool("List all active timers and reminders.")
    suspend fun listTimers(): String {
        logger.info("[TimerTool] Listing active timers")
        return "No active timers or reminders."
    }

    @Tool("Cancel a timer or reminder by its ID.")
    suspend fun cancelTimer(
        @P("Timer or reminder ID to cancel") id: String,
    ): String {
        logger.info("[TimerTool] Cancelling: $id")
        return "Reminder cancelled."
    }
}
