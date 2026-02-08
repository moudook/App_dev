package com.example.smarty.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Commands sent from the Cloud Agent (Brain) to the Android Client (Body).
 * These commands instruct the device to perform actions or update the UI.
 *
 * Covers surface area of:
 * 1. ClientCommandExecutor (Actions)
 * 2. AgentEventSink (UI Notifications)
 */
@Serializable
sealed class AgentCommand {
    abstract val commandId: String

    // ============================================================================================
    // KNOWLEDGE & NOTE OPERATIONS
    // ============================================================================================

    @Serializable
    @SerialName("capture_knowledge")
    data class CaptureKnowledge(
        override val commandId: String,
        val title: String,
        val content: String,
        val source: String,
        val category: String? = null
    ) : AgentCommand()

    @Serializable
    @SerialName("search_knowledge")
    data class SearchKnowledge(
        override val commandId: String,
        val query: String,
        val filter: String? = null
    ) : AgentCommand()

    @Serializable
    @SerialName("add_note")
    data class AddNote(
        override val commandId: String,
        val content: String,
        val category: String? = null
    ) : AgentCommand()

    @Serializable
    @SerialName("update_note")
    data class UpdateNote(
        override val commandId: String,
        @SerialName("note_id") val noteId: String,
        val title: String? = null,
        val content: String? = null,
        val append: Boolean = false // Legacy support, usually false for full update
    ) : AgentCommand()

    @Serializable
    @SerialName("delete_note")
    data class DeleteNote(
        override val commandId: String,
        @SerialName("note_id") val noteId: String
    ) : AgentCommand()

    @Serializable
    @SerialName("archive_note")
    data class ArchiveNote(
        override val commandId: String,
        @SerialName("note_id") val noteId: String
    ) : AgentCommand()

    @Serializable
    @SerialName("search_notes")
    data class SearchNotes(
        override val commandId: String,
        val query: String,
        val category: String? = null,
        @SerialName("time_range") val timeRange: String = "all",
        val limit: Int = 10
    ) : AgentCommand()

    @Serializable
    @SerialName("get_active_notes")
    data class GetActiveNotes(
        override val commandId: String
    ) : AgentCommand()

    // ============================================================================================
    // MEMORY OPERATIONS
    // ============================================================================================

    @Serializable
    @SerialName("store_memory")
    data class StoreMemory(
        override val commandId: String,
        val content: String,
        val scope: String? = null
    ) : AgentCommand()

    @Serializable
    @SerialName("update_memory")
    data class UpdateMemory(
        override val commandId: String,
        val id: String,
        val content: String,
        val type: String? = null
    ) : AgentCommand()

    @Serializable
    @SerialName("delete_memory")
    data class DeleteMemory(
        override val commandId: String,
        val id: String
    ) : AgentCommand()

    @Serializable
    @SerialName("retrieve_memories")
    data class RetrieveMemories(
        override val commandId: String,
        val query: String? = null,
        val limit: Int = 10
    ) : AgentCommand()

    // ============================================================================================
    // SYSTEM & APP CONTROL
    // ============================================================================================

    @Serializable
    @SerialName("launch_app")
    data class LaunchApp(
        override val commandId: String,
        @SerialName("package_name") val packageName: String
    ) : AgentCommand()

    @Serializable
    @SerialName("take_screenshot")
    data class TakeScreenshot(
        override val commandId: String,
        val save: Boolean = true
    ) : AgentCommand()

    @Serializable
    @SerialName("toggle_setting")
    data class ToggleSetting(
        override val commandId: String,
        val setting: String,
        val enable: Boolean
    ) : AgentCommand()

    @Serializable
    @SerialName("get_system_status")
    data class GetSystemStatus(
        override val commandId: String
    ) : AgentCommand()

    @Serializable
    @SerialName("get_screen_context")
    data class GetScreenContext(
        override val commandId: String
    ) : AgentCommand()

    @Serializable
    @SerialName("set_timer")
    data class SetTimer(
        override val commandId: String,
        val name: String,
        @SerialName("time_str") val timeStr: String,
        @SerialName("is_alarm") val isAlarm: Boolean
    ) : AgentCommand()

    @Serializable
    @SerialName("navigate")
    data class Navigate(
        override val commandId: String,
        val screen: String // "home", "calendar", "stacks", "archive", "settings"
    ) : AgentCommand()

    @Serializable
    @SerialName("share")
    data class Share(
        override val commandId: String,
        val content: String,
        val title: String? = null
    ) : AgentCommand()

    // ============================================================================================
    // AUDIO CONTROL
    // ============================================================================================

    @Serializable
    @SerialName("play_audio")
    data class PlayAudio(
        override val commandId: String,
        val query: String,
        val service: String? = null
    ) : AgentCommand()

    @Serializable
    @SerialName("control_audio")
    data class ControlAudio(
        override val commandId: String,
        val action: String // "play", "pause", "resume", "stop", "next", "previous"
    ) : AgentCommand()

    @Serializable
    @SerialName("seek_audio")
    data class SeekAudio(
        override val commandId: String,
        @SerialName("position_ms") val positionMs: Long
    ) : AgentCommand()

    // ============================================================================================
    // CALENDAR
    // ============================================================================================

    @Serializable
    @SerialName("schedule_event")
    data class ScheduleEvent(
        override val commandId: String,
        val title: String,
        @SerialName("start_time") val startTime: Long,
        @SerialName("end_time") val endTime: Long,
        val description: String? = null
    ) : AgentCommand()

    @Serializable
    @SerialName("list_events")
    data class ListEvents(
        override val commandId: String,
        val date: Long
    ) : AgentCommand()

    @Serializable
    @SerialName("delete_event")
    data class DeleteEvent(
        override val commandId: String,
        @SerialName("event_id") val eventId: String
    ) : AgentCommand()

    @Serializable
    @SerialName("add_calendar_event")
    data class AddCalendarEvent(
        override val commandId: String,
        val title: String,
        val start: String,
        val end: String?,
        val description: String?,
        val location: String?
    ) : AgentCommand()

    @Serializable
    @SerialName("query_calendar")
    data class QueryCalendar(
        override val commandId: String,
        val query: String?
    ) : AgentCommand()

    // ============================================================================================
    // UI NOTIFICATIONS (AgentEventSink)
    // ============================================================================================

    @Serializable
    @SerialName("notify_tool_started")
    data class NotifyToolStarted(
        override val commandId: String,
        @SerialName("tool_name") val toolName: String,
        @SerialName("display_name") val displayName: String
    ) : AgentCommand()

    @Serializable
    @SerialName("notify_tool_completed")
    data class NotifyToolCompleted(
        override val commandId: String,
        @SerialName("tool_name") val toolName: String
    ) : AgentCommand()

    @Serializable
    @SerialName("notify_status")
    data class NotifyStatus(
        override val commandId: String,
        val status: String
    ) : AgentCommand()

    @Serializable
    @SerialName("notify_citations")
    data class NotifyCitations(
        override val commandId: String,
        val citations: List<ProtocolWebCitation>
    ) : AgentCommand()

    @Serializable
    @SerialName("plan_step")
    data class PlanStep(
        override val commandId: String,
        @SerialName("step_id") val stepId: String,
        val description: String
    ) : AgentCommand()
}

@Serializable
data class ProtocolWebCitation(
    val title: String,
    val url: String,
    val snippet: String
)
