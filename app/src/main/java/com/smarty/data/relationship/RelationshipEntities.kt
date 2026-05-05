
package com.smarty.data.relationship

import androidx.room.*
import com.smarty.data.entity.*
import java.time.Instant

/**
 * Comprehensive relationship definitions for cross-feature weaving
 */

/**
 * Note with all related entities
 */
data class NoteWithRelations(
    @Embedded
    val note: NoteEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(NoteTagEntity::class, parentColumn = "note_id", entityColumn = "tag_id")
    )
    val tags: List<TagEntity> = emptyList(),

    @Relation(
        parentColumn = "id",
        entityColumn = "note_id"
    )
    val chatMessageLinks: List<ChatMessageNoteEntity> = emptyList(),

    @Relation(
        parentColumn = "id",
        entityColumn = "note_id"
    )
    val calendarEventLinks: List<CalendarEventNoteEntity> = emptyList(),

    @Relation(
        parentColumn = "id",
        entityColumn = "entity_id"
    )
    val reasoningTraces: List<ReasoningTraceEntity> = emptyList(),

    @Relation(
        parentColumn = "id",
        entityColumn = "item_id"
    )
    val sharedItems: List<SharedItemEntity> = emptyList()
)

data class TagWithNoteTag(
    @Embedded
    val tag: TagEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "tag_id"
    )
    val noteTag: NoteTagEntity
)

/**
 * Chat with messages and linked notes
 */
data class ChatWithMessages(
    @Embedded
    val chat: ChatEntity,

    @Relation(
        entity = ChatMessageEntity::class,
        parentColumn = "id",
        entityColumn = "chat_id"
    )
    val messages: List<ChatMessageWithNotes> = emptyList(),

    @Relation(
        parentColumn = "id",
        entityColumn = "entity_id"
    )
    val reasoningTraces: List<ReasoningTraceEntity> = emptyList()
)

data class ChatMessageWithNotes(
    @Embedded
    val message: ChatMessageEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(ChatMessageNoteEntity::class, parentColumn = "chat_message_id", entityColumn = "note_id")
    )
    val linkedNotes: List<NoteEntity> = emptyList(),

    @Relation(
        parentColumn = "id",
        entityColumn = "entity_id"
    )
    val reasoningTraces: List<ReasoningTraceEntity> = emptyList()
)

/**
 * Calendar event with linked notes
 */
data class CalendarEventWithNotes(
    @Embedded
    val event: CalendarEventEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(CalendarEventNoteEntity::class, parentColumn = "event_id", entityColumn = "note_id")
    )
    val linkedNotes: List<NoteEntity> = emptyList(),

    @Relation(
        parentColumn = "id",
        entityColumn = "event_id"
    )
    val noteLinks: List<CalendarEventNoteEntity> = emptyList(),

    @Relation(
        parentColumn = "id",
        entityColumn = "entity_id"
    )
    val reasoningTraces: List<ReasoningTraceEntity> = emptyList()
)

/**
 * Task with related entities
 */
data class TaskWithRelations(
    @Embedded
    val task: TaskEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "entity_id"
    )
    val reasoningTraces: List<ReasoningTraceEntity> = emptyList(),

    @Relation(
        parentColumn = "id",
        entityColumn = "item_id"
    )
    val sharedItems: List<SharedItemEntity> = emptyList()
)

/**
 * User with complete data graph
 */
data class UserWithCompleteGraph(
    @Embedded
    val user: UserEntity,

    @Relation(
        entity = NoteEntity::class,
        parentColumn = "id",
        entityColumn = "user_id"
    )
    val notes: List<NoteWithRelations> = emptyList(),

    @Relation(
        entity = ChatEntity::class,
        parentColumn = "id",
        entityColumn = "user_id"
    )
    val chats: List<ChatWithMessages> = emptyList(),

    @Relation(
        entity = CalendarEventEntity::class,
        parentColumn = "id",
        entityColumn = "user_id"
    )
    val calendarEvents: List<CalendarEventWithNotes> = emptyList(),

    @Relation(
        entity = TaskEntity::class,
        parentColumn = "id",
        entityColumn = "user_id"
    )
    val tasks: List<TaskWithRelations> = emptyList(),

    @Relation(
        parentColumn = "id",
        entityColumn = "user_id"
    )
    val reasoningTraces: List<ReasoningTraceEntity> = emptyList(),

    @Relation(
        parentColumn = "id",
        entityColumn = "user_id"
    )
    val agentCheckpoints: List<AgentCheckpointEntity> = emptyList()
)

/**
 * Smart context propagation container
 */
data class SmartContextBundle(
    val userId: Long,
    val currentNoteId: Long? = null,
    val currentChatId: Long? = null,
    val currentEventId: Long? = null,
    val currentTaskId: Long? = null,
    val activeTags: List<TagEntity> = emptyList(),
    val recentReasoningTraces: List<ReasoningTraceEntity> = emptyList(),
    val agentCheckpoint: AgentCheckpointEntity? = null,
    val deviceContext: DeviceContext? = null,
    val timestamp: Instant = Instant.now()
)

/**
 * Unified search result
 */
data class UnifiedSearchResult(
    val entityType: String,
    val entityId: Long,
    val title: String,
    val snippet: String,
    val relevanceScore: Float,
    val lastModified: Instant,
    val tags: List<String> = emptyList(),
    val metadata: Map<String, Any> = emptyMap()
)
