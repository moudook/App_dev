package com.example.smarty.data.local

import androidx.room.*
import com.example.smarty.core.domain.model.Note

/**
 * Complex relationship entities with Room @Relation for automatic joins
 * These enable creative cross-feature data weaving
 */

// ============================================================
// NOTE_WITH_TAGS - Note with its tags (hybrid category+tag system)
// ============================================================
data class NoteWithTags(
    @Embedded
    val note: Note,

    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(NoteTagEntity::class, parentColumn = "note_id", entityColumn = "tag_id")
    )
    val tags: List<TagEntity> = emptyList(),
)

// ============================================================
// TAG_WITH_NOTES - Tag with all its notes
// ============================================================
data class TagWithNotes(
    @Embedded
    val tag: TagEntity,

    @Relation(
        entity = Note::class,
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(NoteTagEntity::class, parentColumn = "tag_id", entityColumn = "note_id")
    )
    val notes: List<Note> = emptyList(),
)

// ============================================================
// CHAT_SESSION_WITH_MESSAGES - Chat session with all messages
// ============================================================
data class ChatSessionWithMessages(
    @Embedded
    val session: com.example.smarty.core.domain.model.ChatSession,

    @Relation(
        parentColumn = "id",
        entityColumn = "session_id"
    )
    val messages: List<com.example.smarty.core.domain.model.ChatMessageEntity> = emptyList(),
)

// ============================================================
// CHAT_MESSAGE_WITH_NOTES - Deep chat-note integration
// ============================================================
data class ChatMessageWithNotes(
    @Embedded
    val message: com.example.smarty.core.domain.model.ChatMessageEntity,

    @Relation(
        entity = Note::class,
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(ChatMessageNote::class, parentColumn = "chat_message_id", entityColumn = "note_id")
    )
    val linkedNotes: List<Note> = emptyList(),
)

// ============================================================
// CALENDAR_EVENT_WITH_NOTES - Smart event-note linking
// ============================================================
data class CalendarEventWithNotes(
    @Embedded
    val event: com.example.smarty.core.domain.model.CalendarEvent,

    @Relation(
        entity = Note::class,
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(CalendarEventNote::class, parentColumn = "event_id", entityColumn = "note_id")
    )
    val linkedNotes: List<Note> = emptyList(),
)

// ============================================================
// NOTE_WITH_LINKED_EVENTS - Note with linked calendar events
// ============================================================
data class NoteWithLinkedEvents(
    @Embedded
    val note: Note,

    @Relation(
        parentColumn = "id",
        entityColumn = "linked_note_id"
    )
    val linkedEvents: List<com.example.smarty.core.domain.model.CalendarEvent> = emptyList(),
)

// ============================================================
// TASK_WITH_NOTES - Task with related notes
// ============================================================
data class TaskWithNotes(
    @Embedded
    val task: TaskEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "task_id"
    )
    val noteLinks: List<NoteTaskEntity> = emptyList(),
)

// ============================================================
// NOTE_WITH_TASKS - Note with related tasks
// ============================================================
data class NoteWithTasks(
    @Embedded
    val note: Note,

    @Relation(
        parentColumn = "id",
        entityColumn = "note_id"
    )
    val taskLinks: List<NoteTaskEntity> = emptyList(),
)

// ============================================================
// REASONING_TRACE_WITH_ENTITY - Reasoning with linked entity
// ============================================================
data class ReasoningTraceWithEntity(
    @Embedded
    val trace: ReasoningTraceEntity,

    // The linked entity is determined by entityType and entityId
    // This would be resolved in the repository layer
    val linkedNote: Note? = null,
    val linkedTask: TaskEntity? = null,
    val linkedEvent: com.example.smarty.core.domain.model.CalendarEvent? = null,
    val linkedChatMessage: com.example.smarty.core.domain.model.ChatMessageEntity? = null,
)

// ============================================================
// AGENT_CHECKPOINT_WITH_CONTEXT - Checkpoint with full context
// ============================================================
data class AgentCheckpointWithContext(
    @Embedded
    val checkpoint: AgentCheckpointEntity,

    @Relation(
        parentColumn = "session_id",
        entityColumn = "session_id"
    )
    val recentTraces: List<ReasoningTraceEntity> = emptyList(),
)

// ============================================================
// SEARCH_HISTORY_WITH_RESULTS - Search with found entities
// ============================================================
data class SearchHistoryWithResults(
    @Embedded
    val search: SearchHistoryEntity,

    // Results are stored as JSON in entitiesFound field
    // This would be parsed in the repository layer
)



// ============================================================
// USER_WITH_SYNC_STATE - User with sync information
// ============================================================
data class UserWithSyncState(
    @Embedded
    val user: UserEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "user_id"
    )
    val syncState: SyncStateEntity? = null,
)

// ============================================================
// SHARED_ITEM_WITH_DETAILS - Shared item with permission info
// ============================================================
data class SharedItemWithDetails(
    @Embedded
    val sharedItem: SharedItemEntity,

    @Relation(
        parentColumn = "owner_id",
        entityColumn = "id"
    )
    val owner: UserEntity? = null,

    @Relation(
        parentColumn = "shared_with_id",
        entityColumn = "id"
    )
    val sharedWith: UserEntity? = null,
)
