package com.example.smarty.features.chat.domain

import com.example.smarty.protocol.AgentCommand

/**
 * Validates AgentCommand objects before execution.
 * All validation is pure — no side effects, no state access.
 */
object CommandValidator {
    // Validation constants
    private const val MAX_CONTENT_LENGTH = 100_000 // 100KB max for note content
    private const val MAX_TITLE_LENGTH = 500
    private const val MAX_QUERY_LENGTH = 1_000
    private val ALLOWED_AUDIO_ACTIONS = setOf("pause", "resume", "stop", "next", "prev", "toggle")

    /**
     * Result of command validation.
     * Commands are either Valid or Invalid with a reason.
     */
    sealed class Result {
        object Valid : Result()

        data class Invalid(val reason: String, val field: String? = null) : Result() {
            fun toLogString(): String = if (field != null) "$field: $reason" else reason
        }
    }

    /**
     * Validate an AgentCommand before execution.
     *
     * TOTAL FUNCTION: Every AgentCommand subtype is explicitly handled.
     * No default else branch — unknown commands cause compile error.
     *
     * @param command The command to validate
     * @return Valid if command passes all checks, Invalid with reason otherwise
     */
    fun validate(command: AgentCommand): Result =
        when (command) {
            // === NOTE OPERATIONS ===
            is AgentCommand.AddNote -> {
                when {
                    command.content.isBlank() -> Result.Invalid("content cannot be blank", "content")
                    command.content.length > MAX_CONTENT_LENGTH ->
                        Result.Invalid(
                            "content exceeds max length ($MAX_CONTENT_LENGTH)",
                            "content",
                        )
                    else -> Result.Valid
                }
            }

            is AgentCommand.UpdateNote -> {
                val contentVal = command.content
                val titleVal = command.title
                when {
                    command.noteId.isBlank() -> Result.Invalid("noteId cannot be blank", "noteId")
                    contentVal != null && contentVal.length > MAX_CONTENT_LENGTH -> Result.Invalid("content exceeds max length", "content")
                    titleVal != null && titleVal.length > MAX_TITLE_LENGTH -> Result.Invalid("title exceeds max length", "title")
                    else -> Result.Valid
                }
            }

            is AgentCommand.DeleteNote -> {
                when {
                    command.noteId.isBlank() -> Result.Invalid("noteId cannot be blank", "noteId")
                    else -> Result.Valid
                }
            }

            is AgentCommand.ArchiveNote -> {
                when {
                    command.noteId.isBlank() -> Result.Invalid("noteId cannot be blank", "noteId")
                    else -> Result.Valid
                }
            }

            is AgentCommand.SearchNotes -> {
                when {
                    command.query.length > MAX_QUERY_LENGTH -> Result.Invalid("query exceeds max length", "query")
                    command.limit <= 0 -> Result.Invalid("limit must be positive", "limit")
                    command.limit > 100 -> Result.Invalid("limit exceeds maximum (100)", "limit")
                    else -> Result.Valid
                }
            }

            is AgentCommand.GetActiveNotes -> {
                Result.Valid // No params to validate
            }

            // === CONTEXT / PERSONALIZATION ===
            is AgentCommand.StoreContext -> {
                when {
                    command.content.isBlank() -> Result.Invalid("content cannot be blank", "content")
                    command.type.isBlank() -> Result.Invalid("type cannot be blank", "type")
                    else -> Result.Valid
                }
            }

            is AgentCommand.UpdateContext -> {
                when {
                    command.id.isBlank() -> Result.Invalid("id cannot be blank", "id")
                    command.content.isBlank() -> Result.Invalid("content cannot be blank", "content")
                    command.type.isBlank() -> Result.Invalid("type cannot be blank", "type")
                    else -> Result.Valid
                }
            }

            is AgentCommand.DeleteContext -> {
                when {
                    command.id.isBlank() -> Result.Invalid("id cannot be blank", "id")
                    else -> Result.Valid
                }
            }

            // === SYSTEM & APP CONTROL ===
            is AgentCommand.LaunchApp -> {
                when {
                    command.packageName.isBlank() -> Result.Invalid("packageName cannot be blank", "packageName")
                    command.packageName.contains(" ") -> Result.Invalid("packageName cannot contain whitespace", "packageName")
                    else -> Result.Valid
                }
            }

            is AgentCommand.TakeScreenshot -> {
                Result.Valid
            }

            is AgentCommand.ToggleSetting -> {
                when {
                    command.setting.isBlank() -> Result.Invalid("setting cannot be blank", "setting")
                    else -> Result.Valid
                }
            }

            is AgentCommand.GetSystemStatus -> {
                Result.Valid // No params to validate
            }

            is AgentCommand.GetDeviceInfo -> {
                Result.Valid // No params to validate
            }

            is AgentCommand.GetScreenContext -> {
                Result.Valid // No params to validate
            }

            is AgentCommand.SetTimer -> {
                when {
                    command.name.isBlank() -> Result.Invalid("name cannot be blank", "name")
                    command.timeStr.isBlank() -> Result.Invalid("timeStr cannot be blank", "timeStr")
                    else -> Result.Valid
                }
            }

            is AgentCommand.ListTimers -> {
                Result.Valid
            }

            is AgentCommand.CancelTimer -> {
                when {
                    command.id.isBlank() -> Result.Invalid("id cannot be blank", "id")
                    else -> Result.Valid
                }
            }

            // === AUDIO CONTROL ===
            is AgentCommand.PlayAudio -> {
                when {
                    command.query.isBlank() -> Result.Invalid("query cannot be blank", "query")
                    command.query.length > MAX_QUERY_LENGTH -> Result.Invalid("query exceeds max length", "query")
                    else -> Result.Valid
                }
            }

            is AgentCommand.ControlAudio -> {
                when {
                    command.action.isBlank() -> Result.Invalid("action cannot be blank", "action")
                    command.action.lowercase() !in ALLOWED_AUDIO_ACTIONS ->
                        Result.Invalid(
                            "action must be one of: $ALLOWED_AUDIO_ACTIONS",
                            "action",
                        )
                    else -> Result.Valid
                }
            }

            is AgentCommand.SeekAudio -> {
                when {
                    command.positionMs < 0 -> Result.Invalid("positionMs must be non-negative", "positionMs")
                    else -> Result.Valid
                }
            }

            // === CALENDAR ===
            is AgentCommand.ScheduleEvent -> {
                when {
                    command.title.isBlank() -> Result.Invalid("title cannot be blank", "title")
                    command.startTime <= 0 -> Result.Invalid("startTime must be positive", "startTime")
                    command.endTime < command.startTime -> Result.Invalid("endTime cannot be before startTime", "endTime")
                    else -> Result.Valid
                }
            }

            is AgentCommand.ListEvents -> {
                when {
                    command.date <= 0 -> Result.Invalid("date must be positive", "date")
                    else -> Result.Valid
                }
            }

            is AgentCommand.DeleteEvent -> {
                when {
                    command.eventId.isBlank() -> Result.Invalid("eventId cannot be blank", "eventId")
                    else -> Result.Valid
                }
            }

            is AgentCommand.AddCalendarEvent -> {
                when {
                    command.title.isBlank() -> Result.Invalid("title cannot be blank", "title")
                    command.title.length > MAX_TITLE_LENGTH -> Result.Invalid("title exceeds max length", "title")
                    command.start.isBlank() -> Result.Invalid("start cannot be blank", "start")
                    else -> Result.Valid
                }
            }

            is AgentCommand.QueryCalendar -> {
                val queryVal = command.query
                when {
                    queryVal != null && queryVal.length > MAX_QUERY_LENGTH -> Result.Invalid("query exceeds max length", "query")
                    else -> Result.Valid
                }
            }

            // === UI NOTIFICATIONS ===
            is AgentCommand.NotifyToolStarted -> {
                when {
                    command.toolName.isBlank() -> Result.Invalid("toolName cannot be blank", "toolName")
                    else -> Result.Valid
                }
            }

            is AgentCommand.NotifyToolCompleted -> {
                when {
                    command.toolName.isBlank() -> Result.Invalid("toolName cannot be blank", "toolName")
                    else -> Result.Valid
                }
            }

            is AgentCommand.NotifyStatus -> {
                when {
                    command.status.isBlank() -> Result.Invalid("status cannot be blank", "status")
                    else -> Result.Valid
                }
            }

            is AgentCommand.NotifyCitations -> {
                Result.Valid // Empty list is valid
            }

            is AgentCommand.Navigate -> {
                when {
                    command.screen.isBlank() -> Result.Invalid("screen cannot be blank", "screen")
                    else -> Result.Valid
                }
            }

            is AgentCommand.Share -> {
                when {
                    command.content.isBlank() -> Result.Invalid("content cannot be blank", "content")
                    else -> Result.Valid
                }
            }

            // NO else BRANCH - Kotlin exhaustive when ensures all subtypes handled
        }

    /**
     * Generate safe summary for a command (no user-generated content).
     * Only includes: lengths, IDs, enums, booleans, counts.
     */
    fun summarize(command: AgentCommand): String =
        when (command) {
            // Note operations - content lengths only
            is AgentCommand.AddNote -> "content.len=${command.content.length} | category=${command.category != null}"
            is AgentCommand.UpdateNote -> "noteId=${command.noteId} | hasTitle=${command.title != null} | hasContent=${command.content != null}"
            is AgentCommand.DeleteNote -> "noteId=${command.noteId}"
            is AgentCommand.ArchiveNote -> "noteId=${command.noteId}"
            is AgentCommand.SearchNotes -> "query.len=${command.query.length} | category=${command.category != null} | limit=${command.limit}"
            is AgentCommand.GetActiveNotes -> "(no params)"

            // Context / Personalization
            is AgentCommand.StoreContext -> "content.len=${command.content.length} | type=${command.type}"
            is AgentCommand.UpdateContext -> "id=${command.id} | content.len=${command.content.length} | type=${command.type}"
            is AgentCommand.DeleteContext -> "id=${command.id}"

            // System & app control
            is AgentCommand.LaunchApp -> "packageName.len=${command.packageName.length}"
            is AgentCommand.TakeScreenshot -> "save=${command.save}"
            is AgentCommand.ToggleSetting -> "setting=${command.setting} | enable=${command.enable}"
            is AgentCommand.GetSystemStatus -> "(no params)"
            is AgentCommand.GetDeviceInfo -> "infoType=${command.infoType}"
            is AgentCommand.GetScreenContext -> "(no params)"
            is AgentCommand.SetTimer -> "name.len=${command.name.length} | timeStr.len=${command.timeStr.length} | isAlarm=${command.isAlarm}"
            is AgentCommand.ListTimers -> "(no params)"
            is AgentCommand.CancelTimer -> "id=${command.id}"

            // Audio control
            is AgentCommand.PlayAudio -> "query.len=${command.query.length} | service=${command.service != null}"
            is AgentCommand.ControlAudio -> "action=${command.action}"
            is AgentCommand.SeekAudio -> "positionMs=${command.positionMs}"

            // Calendar
            is AgentCommand.ScheduleEvent -> "title.len=${command.title.length} | duration=${command.endTime - command.startTime}"
            is AgentCommand.ListEvents -> "date=${command.date}"
            is AgentCommand.DeleteEvent -> "eventId=${command.eventId}"
            is AgentCommand.AddCalendarEvent -> "title.len=${command.title.length} | hasEnd=${command.end != null} | hasDesc=${command.description != null}"
            is AgentCommand.QueryCalendar -> "hasQuery=${command.query != null}"

            // UI notifications
            is AgentCommand.NotifyToolStarted -> "toolName.len=${command.toolName.length}"
            is AgentCommand.NotifyToolCompleted -> "toolName.len=${command.toolName.length}"
            is AgentCommand.NotifyStatus -> "status.len=${command.status.length}"
            is AgentCommand.NotifyCitations -> "count=${command.citations.size}"

            // New commands
            is AgentCommand.Navigate -> "screen=${command.screen}"
            is AgentCommand.Share -> "content.len=${command.content.length} | hasTitle=${command.title != null}"
        }
}
