package com.example.smarty.features.chat.domain

import android.util.Log
import com.example.smarty.protocol.AgentCommand

object ChatCommandValidator {
    private const val MAX_CONTENT_LENGTH = 10000
    private const val MAX_TITLE_LENGTH = 200
    private const val MAX_QUERY_LENGTH = 500
    private const val COMMAND_LOG_BUFFER_SIZE = 50
    private val ALLOWED_AUDIO_ACTIONS = setOf("play", "pause", "resume", "stop", "next", "previous")

    data class CommandLogEntry(
        val timestamp: Long,
        val commandType: String,
        val commandId: String,
        val summary: String,
        val rejected: Boolean = false,
        val rejectionReason: String? = null,
    ) {
        fun toLogString(): String {
            val prefix = if (rejected) "REJECTED " else ""
            val suffix = if (rejected && rejectionReason != null) " | reason=$rejectionReason" else ""
            return "$prefix[$commandType] id=${commandId.take(8)} | $summary$suffix"
        }
    }

    sealed class CommandValidationResult {
        object Valid : CommandValidationResult()
        data class Invalid(
            val reason: String,
            val field: String? = null,
        ) : CommandValidationResult() {
            fun toLogString(): String = if (field != null) "$field: $reason" else reason
        }
    }

    private val commandHistory = ArrayDeque<CommandLogEntry>(COMMAND_LOG_BUFFER_SIZE)
    private val commandHistoryLock = Any()

    fun validateCommand(command: AgentCommand): CommandValidationResult =
        when (command) {
            is AgentCommand.AddNote -> {
                when {
                    command.content.isBlank() -> CommandValidationResult.Invalid("content cannot be blank", "content")
                    command.content.length > MAX_CONTENT_LENGTH ->
                        CommandValidationResult.Invalid("content exceeds max length ($MAX_CONTENT_LENGTH)", "content")
                    else -> CommandValidationResult.Valid
                }
            }
            is AgentCommand.ListTimers -> CommandValidationResult.Valid
            is AgentCommand.CancelTimer -> {
                if (command.id.isBlank()) CommandValidationResult.Invalid("id cannot be blank", "id") else CommandValidationResult.Valid
            }
            is AgentCommand.UpdateNote -> {
                val contentVal = command.content
                val titleVal = command.title
                when {
                    command.noteId.isBlank() -> CommandValidationResult.Invalid("noteId cannot be blank", "noteId")
                    contentVal != null && contentVal.length > MAX_CONTENT_LENGTH ->
                        CommandValidationResult.Invalid("content exceeds max length", "content")
                    titleVal != null && titleVal.length > MAX_TITLE_LENGTH ->
                        CommandValidationResult.Invalid("title exceeds max length", "title")
                    else -> CommandValidationResult.Valid
                }
            }
            is AgentCommand.DeleteNote -> {
                if (command.noteId.isBlank()) CommandValidationResult.Invalid("noteId cannot be blank", "noteId") else CommandValidationResult.Valid
            }
            is AgentCommand.ArchiveNote -> {
                if (command.noteId.isBlank()) CommandValidationResult.Invalid("noteId cannot be blank", "noteId") else CommandValidationResult.Valid
            }
            is AgentCommand.SearchNotes -> {
                when {
                    command.query.length > MAX_QUERY_LENGTH -> CommandValidationResult.Invalid("query exceeds max length", "query")
                    command.limit <= 0 -> CommandValidationResult.Invalid("limit must be positive", "limit")
                    command.limit > 100 -> CommandValidationResult.Invalid("limit exceeds maximum (100)", "limit")
                    else -> CommandValidationResult.Valid
                }
            }
            is AgentCommand.GetActiveNotes -> CommandValidationResult.Valid
            is AgentCommand.StoreContext -> {
                when {
                    command.content.isBlank() -> CommandValidationResult.Invalid("content cannot be blank", "content")
                    command.type.isBlank() -> CommandValidationResult.Invalid("type cannot be blank", "type")
                    else -> CommandValidationResult.Valid
                }
            }
            is AgentCommand.UpdateContext -> {
                when {
                    command.id.isBlank() -> CommandValidationResult.Invalid("id cannot be blank", "id")
                    command.content.isBlank() -> CommandValidationResult.Invalid("content cannot be blank", "content")
                    command.type.isBlank() -> CommandValidationResult.Invalid("type cannot be blank", "type")
                    else -> CommandValidationResult.Valid
                }
            }
            is AgentCommand.DeleteContext -> {
                if (command.id.isBlank()) CommandValidationResult.Invalid("id cannot be blank", "id") else CommandValidationResult.Valid
            }
            is AgentCommand.LaunchApp -> {
                when {
                    command.packageName.isBlank() -> CommandValidationResult.Invalid("packageName cannot be blank", "packageName")
                    command.packageName.contains(" ") -> CommandValidationResult.Invalid("packageName cannot contain whitespace", "packageName")
                    else -> CommandValidationResult.Valid
                }
            }
            is AgentCommand.TakeScreenshot -> CommandValidationResult.Valid
            is AgentCommand.ToggleSetting -> {
                if (command.setting.isBlank()) CommandValidationResult.Invalid("setting cannot be blank", "setting") else CommandValidationResult.Valid
            }
            is AgentCommand.GetSystemStatus -> CommandValidationResult.Valid
            is AgentCommand.GetDeviceInfo -> CommandValidationResult.Valid
            is AgentCommand.GetScreenContext -> CommandValidationResult.Valid
            is AgentCommand.SetTimer -> {
                when {
                    command.name.isBlank() -> CommandValidationResult.Invalid("name cannot be blank", "name")
                    command.timeStr.isBlank() -> CommandValidationResult.Invalid("timeStr cannot be blank", "timeStr")
                    else -> CommandValidationResult.Valid
                }
            }
            is AgentCommand.PlayAudio -> {
                when {
                    command.query.isBlank() -> CommandValidationResult.Invalid("query cannot be blank", "query")
                    command.query.length > MAX_QUERY_LENGTH -> CommandValidationResult.Invalid("query exceeds max length", "query")
                    else -> CommandValidationResult.Valid
                }
            }
            is AgentCommand.ControlAudio -> {
                when {
                    command.action.isBlank() -> CommandValidationResult.Invalid("action cannot be blank", "action")
                    command.action.lowercase() !in ALLOWED_AUDIO_ACTIONS ->
                        CommandValidationResult.Invalid("action must be one of: $ALLOWED_AUDIO_ACTIONS", "action")
                    else -> CommandValidationResult.Valid
                }
            }
            is AgentCommand.SeekAudio -> {
                if (command.positionMs < 0) CommandValidationResult.Invalid("positionMs must be non-negative", "positionMs") else CommandValidationResult.Valid
            }
            is AgentCommand.ScheduleEvent -> {
                when {
                    command.title.isBlank() -> CommandValidationResult.Invalid("title cannot be blank", "title")
                    command.startTime <= 0 -> CommandValidationResult.Invalid("startTime must be positive", "startTime")
                    command.endTime < command.startTime -> CommandValidationResult.Invalid("endTime cannot be before startTime", "endTime")
                    else -> CommandValidationResult.Valid
                }
            }
            is AgentCommand.ListEvents -> {
                if (command.date <= 0) CommandValidationResult.Invalid("date must be positive", "date") else CommandValidationResult.Valid
            }
            is AgentCommand.DeleteEvent -> {
                if (command.eventId.isBlank()) CommandValidationResult.Invalid("eventId cannot be blank", "eventId") else CommandValidationResult.Valid
            }
            is AgentCommand.AddCalendarEvent -> {
                when {
                    command.title.isBlank() -> CommandValidationResult.Invalid("title cannot be blank", "title")
                    command.title.length > MAX_TITLE_LENGTH -> CommandValidationResult.Invalid("title exceeds max length", "title")
                    command.start.isBlank() -> CommandValidationResult.Invalid("start cannot be blank", "start")
                    else -> CommandValidationResult.Valid
                }
            }
            is AgentCommand.QueryCalendar -> {
                val q = command.query
                if (q != null && q.length > MAX_QUERY_LENGTH) {
                    CommandValidationResult.Invalid("query exceeds max length", "query")
                } else {
                    CommandValidationResult.Valid
                }
            }
            is AgentCommand.NotifyToolStarted -> {
                if (command.toolName.isBlank()) CommandValidationResult.Invalid("toolName cannot be blank", "toolName") else CommandValidationResult.Valid
            }
            is AgentCommand.NotifyToolCompleted -> {
                if (command.toolName.isBlank()) CommandValidationResult.Invalid("toolName cannot be blank", "toolName") else CommandValidationResult.Valid
            }
            is AgentCommand.NotifyStatus -> {
                if (command.status.isBlank()) CommandValidationResult.Invalid("status cannot be blank", "status") else CommandValidationResult.Valid
            }
            is AgentCommand.NotifyCitations -> CommandValidationResult.Valid
            is AgentCommand.Navigate -> {
                if (command.screen.isBlank()) CommandValidationResult.Invalid("screen cannot be blank", "screen") else CommandValidationResult.Valid
            }
            is AgentCommand.Share -> {
                if (command.content.isBlank()) CommandValidationResult.Invalid("content cannot be blank", "content") else CommandValidationResult.Valid
            }
            is AgentCommand.ShowBreathing -> CommandValidationResult.Valid
        }

    fun logCommand(
        command: AgentCommand,
        rejected: Boolean = false,
        rejectionReason: String? = null,
    ) {
        val entry = CommandLogEntry(
            timestamp = System.currentTimeMillis(),
            commandType = command::class.simpleName ?: "Unknown",
            commandId = command.commandId,
            summary = summarizeCommand(command),
            rejected = rejected,
            rejectionReason = rejectionReason,
        )

        synchronized(commandHistoryLock) {
            if (commandHistory.size >= COMMAND_LOG_BUFFER_SIZE) {
                commandHistory.removeFirst()
            }
            commandHistory.addLast(entry)
        }

        if (rejected) {
            Log.w("AgentCommand", entry.toLogString())
        } else {
            Log.d("AgentCommand", entry.toLogString())
        }
    }

    private fun summarizeCommand(command: AgentCommand): String =
        when (command) {
            is AgentCommand.AddNote -> "content.len=${command.content.length} | category=${command.category != null}"
            is AgentCommand.UpdateNote -> "noteId=${command.noteId} | hasTitle=${command.title != null} | hasContent=${command.content != null}"
            is AgentCommand.DeleteNote -> "noteId=${command.noteId}"
            is AgentCommand.ArchiveNote -> "noteId=${command.noteId}"
            is AgentCommand.SearchNotes -> "query.len=${command.query.length} | category=${command.category != null} | limit=${command.limit}"
            is AgentCommand.GetActiveNotes -> "(no params)"
            is AgentCommand.StoreContext -> "content.len=${command.content.length} | type=${command.type}"
            is AgentCommand.UpdateContext -> "id=${command.id} | content.len=${command.content.length} | type=${command.type}"
            is AgentCommand.DeleteContext -> "id=${command.id}"
            is AgentCommand.LaunchApp -> "packageName.len=${command.packageName.length}"
            is AgentCommand.TakeScreenshot -> "save=${command.save}"
            is AgentCommand.ToggleSetting -> "setting=${command.setting} | enable=${command.enable}"
            is AgentCommand.GetSystemStatus -> "(no params)"
            is AgentCommand.GetDeviceInfo -> "infoType=${command.infoType}"
            is AgentCommand.GetScreenContext -> "(no params)"
            is AgentCommand.SetTimer -> "name.len=${command.name.length} | timeStr.len=${command.timeStr.length} | isAlarm=${command.isAlarm}"
            is AgentCommand.ListTimers -> "(no params)"
            is AgentCommand.CancelTimer -> "id=${command.id}"
            is AgentCommand.PlayAudio -> "query.len=${command.query.length} | service=${command.service != null}"
            is AgentCommand.ControlAudio -> "action=${command.action}"
            is AgentCommand.SeekAudio -> "positionMs=${command.positionMs}"
            is AgentCommand.ScheduleEvent -> "title.len=${command.title.length} | duration=${command.endTime - command.startTime}"
            is AgentCommand.ListEvents -> "date=${command.date}"
            is AgentCommand.DeleteEvent -> "eventId=${command.eventId}"
            is AgentCommand.AddCalendarEvent -> "title.len=${command.title.length} | hasEnd=${command.end != null} | hasDesc=${command.description != null}"
            is AgentCommand.QueryCalendar -> "hasQuery=${command.query != null}"
            is AgentCommand.NotifyToolStarted -> "toolName"
            is AgentCommand.NotifyToolCompleted -> "toolName"
            is AgentCommand.NotifyStatus -> "status"
            is AgentCommand.NotifyCitations -> "count"
            is AgentCommand.Navigate -> "screen"
            is AgentCommand.Share -> "share"
            is AgentCommand.ShowBreathing -> "(no params)"
        }

    fun getRecentCommands(): List<CommandLogEntry> {
        synchronized(commandHistoryLock) {
            return commandHistory.toList()
        }
    }

    fun clearCommandHistory() {
        synchronized(commandHistoryLock) {
            commandHistory.clear()
        }
    }
}
