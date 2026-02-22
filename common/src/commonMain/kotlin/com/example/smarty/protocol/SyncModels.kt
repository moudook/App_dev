package com.example.smarty.protocol

import kotlinx.serialization.Serializable

@Serializable
data class NoteInfo(
    val id: String,
    val title: String,
    val content: String,
    val category: String?,
    val isArchived: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class TimerInfo(
    val id: String,
    val name: String,
    val durationMs: Long,
    val triggerAt: Long,
    val isAlarm: Boolean,
    val isActive: Boolean,
    val createdAt: Long
)

@Serializable
data class CalendarEventInfo(
    val id: String,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val description: String?,
    val reminderMinutes: Int,
    val createdAt: Long
)

// Sync API Models

@Serializable
data class SyncPullResponse(
    val notes: List<NoteInfo>,
    val sessions: List<SessionInfoData>,
    val events: List<CalendarEventInfo>,
    val lastSyncAt: Long
)

@Serializable
data class SessionInfoData(
    val id: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val lastMessagePreview: String,
    val messages: List<MessageInfoData>
)

@Serializable
data class MessageInfoData(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long
)

@Serializable
data class SyncPushRequest(
    val notes: List<NotePushItem>? = null,
    val sessions: List<SessionPushItem>? = null,
    val events: List<EventPushItem>? = null
)

@Serializable
data class NotePushItem(
    val id: String? = null,
    val title: String,
    val content: String,
    val category: String? = null,
    val updatedAt: Long
)

@Serializable
data class SessionPushItem(
    val id: String,
    val title: String?,
    val createdAt: Long,
    val messages: List<MessagePushItem>? = null
)

@Serializable
data class MessagePushItem(
    val id: String? = null,
    val role: String,
    val content: String,
    val createdAt: Long
)

@Serializable
data class EventPushItem(
    val id: String? = null,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val description: String? = null,
    val reminderMinutes: Int = 15
)

@Serializable
data class SyncPushResponse(
    val success: Boolean,
    val createdNotes: List<String> = emptyList(),
    val createdSessions: List<String> = emptyList(),
    val createdEvents: List<String> = emptyList(),
    val errors: List<String> = emptyList()
)

@Serializable
data class SyncStatusResponse(
    val lastSyncAt: Long?,
    val lastPullAt: Long?
)
