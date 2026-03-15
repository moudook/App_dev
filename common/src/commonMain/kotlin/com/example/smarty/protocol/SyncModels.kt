package com.example.smarty.protocol

import kotlinx.serialization.Serializable

@Serializable
data class NoteInfo(
    val id: String,
    val title: String,
    val content: String,
    val categoryId: String?,  // UUID reference to note_categories
    val stackId: String?,     // UUID reference to note_stacks
    val parentNoteId: String?, // UUID reference to notes
    val wordCount: Int?,      // generated column
    val isArchived: Boolean,
    val isPinned: Boolean,
    val isFavorite: Boolean,
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
    val generatedImages: List<GeneratedImageInfo> = emptyList(),
    val lastSyncAt: Long
)

@Serializable
data class GeneratedImageInfo(
    val id: String,
    val userId: String,
    val sessionId: String?,
    val prompt: String,
    val kreaJobId: String,
    val status: String,
    val imageUrl: String?,
    val supabaseUrl: String?,
    val createdAt: Long,
    val updatedAt: Long
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
    val thinking: String? = null,
    val createdAt: Long,
    val linkedNoteIds: List<String> = emptyList()
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
    val categoryId: String? = null,
    val stackId: String? = null,
    val parentNoteId: String? = null,
    val isArchived: Boolean = false,
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
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
    val thinking: String? = null,
    val createdAt: Long,
    val linkedNoteIds: List<String> = emptyList()
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
