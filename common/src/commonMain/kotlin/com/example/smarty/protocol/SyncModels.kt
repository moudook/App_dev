package com.example.smarty.protocol

import kotlinx.serialization.Serializable

@Serializable
data class NoteInfo(
    val id: String,
    val title: String,
    val content: String,
    val summary: String? = null,
    val sourceUrl: String? = null,
    val imageUri: String? = null,
    val fileUri: String? = null,
    val fileName: String? = null,
    val fileMimeType: String? = null,
    val fileSize: Long? = null,
    val type: String = "BRAIN_DUMP",
    val categoryId: String? = null,
    val categoryName: String? = null,
    val stackId: String? = null,
    val parentNoteId: String? = null,
    val whySaved: String? = null,
    val processingStatus: String = "COMPLETED",
    val contentHash: String? = null,
    val processedContentHash: String? = null,
    val wordCount: Int? = null,
    val isArchived: Boolean = false,
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val isFullPrivacy: Boolean = false,
    val excludeFromAiChat: Boolean = false,
    val isAiCreated: Boolean = false,
    val isViewed: Boolean = false,
    val todoContent: String? = null,
    val attachmentsJson: String? = null,
    val tagsJson: String? = null,
    val chunkAnalysesJson: String? = null,
    val reminderText: String? = null,
    val reminderExpiresAt: Long? = null,
    val metadata: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class TimerInfo(
    val id: String,
    val name: String,
    val durationMs: Long,
    val triggerAt: Long,
    val isAlarm: Boolean,
    val isActive: Boolean,
    val createdAt: Long,
    val repeat: String? = null,
)

@Serializable
data class CalendarEventInfo(
    val id: String,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val description: String? = null,
    val location: String? = null,
    val reminderMinutes: Int = 15,
    val linkedNoteId: String? = null,
    val googleEventId: String? = null,
    val isEventPrivate: Boolean = false,
    val createdAt: Long,
)

// Sync API Models

@Serializable
data class SyncPullResponse(
    val notes: List<NoteInfo>,
    val sessions: List<SessionInfoData>,
    val events: List<CalendarEventInfo>,
    val generatedImages: List<GeneratedImageInfo> = emptyList(),
    val lastSyncAt: Long,
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
    val updatedAt: Long,
)

@Serializable
data class SessionInfoData(
    val id: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val lastMessagePreview: String? = null,
    val summary: String? = null,
    val summaryGeneratedAt: Long? = null,
    val messages: List<MessageInfoData>,
)

@Serializable
data class MessageInfoData(
    val id: String,
    val role: String,
    val content: String,
    val thinking: String? = null,
    val createdAt: Long,
    val linkedNoteIds: List<String> = emptyList(),
    val agentStepsJson: String? = null,
)

@Serializable
data class SyncPushRequest(
    val notes: List<NotePushItem>? = null,
    val sessions: List<SessionPushItem>? = null,
    val events: List<EventPushItem>? = null,
)

@Serializable
data class NotePushItem(
    val id: String? = null,
    val title: String,
    val content: String,
    val summary: String? = null,
    val sourceUrl: String? = null,
    val imageUri: String? = null,
    val fileUri: String? = null,
    val fileName: String? = null,
    val fileMimeType: String? = null,
    val fileSize: Long? = null,
    val type: String = "BRAIN_DUMP",
    val categoryId: String? = null,
    val categoryName: String? = null,
    val stackId: String? = null,
    val parentNoteId: String? = null,
    val whySaved: String? = null,
    val processingStatus: String = "COMPLETED",
    val contentHash: String? = null,
    val processedContentHash: String? = null,
    val isArchived: Boolean = false,
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val isFullPrivacy: Boolean = false,
    val excludeFromAiChat: Boolean = false,
    val isAiCreated: Boolean = false,
    val isViewed: Boolean = false,
    val todoContent: String? = null,
    val attachmentsJson: String? = null,
    val tagsJson: String? = null,
    val chunkAnalysesJson: String? = null,
    val reminderText: String? = null,
    val reminderExpiresAt: Long? = null,
    val updatedAt: Long,
)

@Serializable
data class SessionPushItem(
    val id: String,
    val title: String?,
    val createdAt: Long,
    val lastMessagePreview: String? = null,
    val summary: String? = null,
    val summaryGeneratedAt: Long? = null,
    val messages: List<MessagePushItem>? = null,
)

@Serializable
data class MessagePushItem(
    val id: String? = null,
    val role: String,
    val content: String,
    val thinking: String? = null,
    val createdAt: Long,
    val linkedNoteIds: List<String> = emptyList(),
    val agentStepsJson: String? = null,
)

@Serializable
data class EventPushItem(
    val id: String? = null,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val description: String? = null,
    val location: String? = null,
    val reminderMinutes: Int = 15,
    val linkedNoteId: String? = null,
    val googleEventId: String? = null,
    val isEventPrivate: Boolean = false,
)

@Serializable
data class SyncPushResponse(
    val success: Boolean,
    val createdNotes: List<String> = emptyList(),
    val createdSessions: List<String> = emptyList(),
    val createdEvents: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)

@Serializable
data class SyncStatusResponse(
    val lastSyncAt: Long?,
    val lastPullAt: Long?,
)
