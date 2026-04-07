package com.example.smarty.protocol

import com.example.smarty.core.domain.model.Note
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Events sent from the Android Client (Body) to the Cloud Agent (Brain).
 * These events provide context, command results, or requested data.
 */
@Serializable
sealed class ClientEvent {
    abstract val timestamp: Long

    // ============================================================================================
    // PROACTIVE EVENTS
    // ============================================================================================

    @Serializable
    @SerialName("app_state")
    data class AppState(
        @SerialName("current_screen") val currentScreen: String,
        @SerialName("battery_level") val batteryLevel: Float, // 0.0 to 1.0
        @SerialName("is_wifi") val isWifi: Boolean,
        override val timestamp: Long = 0L,
    ) : ClientEvent()

    // ============================================================================================
    // COMMAND RESULTS
    // ============================================================================================

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("command_id") val commandId: String,
        val result: String,
        val isError: Boolean,
        override val timestamp: Long = 0L,
    ) : ClientEvent()

    // ============================================================================================
    // DATA RESPONSES
    // ============================================================================================

    @Serializable
    @SerialName("data_active_notes")
    data class ActiveNotesResponse(
        @SerialName("command_id") val commandId: String,
        val notes: List<Note>,
        override val timestamp: Long = 0L,
    ) : ClientEvent()

    @Serializable
    @SerialName("data_search_results")
    data class SearchResultsResponse(
        @SerialName("command_id") val commandId: String,
        val results: List<ProtocolSearchResult>,
        override val timestamp: Long = 0L,
    ) : ClientEvent()

    @Serializable
    @SerialName("data_system_status")
    data class SystemStatusResponse(
        @SerialName("command_id") val commandId: String,
        val status: Map<String, String>,
        override val timestamp: Long = 0L,
    ) : ClientEvent()

    @Serializable
    @SerialName("data_calendar_events")
    data class CalendarEventsResponse(
        @SerialName("command_id") val commandId: String,
        val events: List<com.example.smarty.core.domain.model.CalendarEvent>,
        override val timestamp: Long = 0L,
    ) : ClientEvent()

    @Serializable
    @SerialName("data_recall_results")
    data class RecallResultsResponse(
        @SerialName("command_id") val commandId: String,
        val results: List<com.example.smarty.core.domain.model.RecallResult>,
        override val timestamp: Long = 0L,
    ) : ClientEvent()

    @Serializable
    @SerialName("data_screen_context")
    data class ScreenContextResponse(
        @SerialName("command_id") val commandId: String,
        val context: com.example.smarty.features.chat.agent.models.ScreenContext?,
        override val timestamp: Long = 0L,
    ) : ClientEvent()
}

@Serializable
data class ProtocolSearchResult(
    val id: String,
    val title: String,
    val content: String,
    val score: Double,
)
