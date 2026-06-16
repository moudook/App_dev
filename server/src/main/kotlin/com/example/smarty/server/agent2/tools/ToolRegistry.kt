package com.example.smarty.server.agent2.tools

import com.example.smarty.server.data.CalendarRepository
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.data.TimerRepository
import com.example.smarty.server.services.NoteService

data class ToolDependencies(
    val userId: String = "dev-user",
    val noteService: NoteService? = null,
    val noteRepository: NoteRepository? = null,
    val calendarRepository: CalendarRepository? = null,
    val timerRepository: TimerRepository? = null,
    val vectorStore: PostgresVectorStore? = null,
    val section: String? = null,
)

class ToolRegistry(
    private val deps: ToolDependencies = ToolDependencies(),
) {
    fun getAllTools(): List<Any> = listOf(
        NoteTool(userId = deps.userId, section = deps.section),
        CalendarTool(userId = deps.userId),
        TimerTool(userId = deps.userId),
        ProfileTool(userId = deps.userId),
        WebSearchTool(),
        CodeInterpreterTool(),
        ScratchpadTool(),
        ImageTool(userId = deps.userId),
        LaunchTool(),
        ShareTool(),
        AskUserTool(userId = deps.userId),
    )
}
