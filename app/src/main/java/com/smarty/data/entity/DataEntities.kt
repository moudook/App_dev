
package com.smarty.data.entity

import androidx.room.*
import com.smarty.data.model.SyncState
import com.smarty.data.sync.EntityWithMetadata
import java.time.Instant

/**
 * Note entity - core content unit
 */
@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["supabase_id"], unique = true),
        Index(value = ["user_id"]),
        Index(value = ["sync_state", "last_modified"]),
        Index(value = ["created_at"]),
        Index(value = ["is_archived", "last_modified"])
    ]
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "supabase_id")
    val supabaseId: String,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "content_format")
    val contentFormat: String = "markdown", // markdown, html, plain

    @ColumnInfo(name = "category")
    val category: String? = null,

    @ColumnInfo(name = "priority")
    val priority: Int = 0,

    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean = false,

    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,

    @ColumnInfo(name = "archived_at")
    val archivedAt: Instant? = null,

    @ColumnInfo(name = "expires_at")
    val expiresAt: Instant? = null,

    @ColumnInfo(name = "sync_state")
    @SyncState.State
    val syncState: String = SyncState.PENDING,

    @ColumnInfo(name = "last_sync_timestamp")
    val lastSyncTimestamp: Instant? = null,

    @ColumnInfo(name = "last_modified")
    override val lastModified: Instant = Instant.now(),

    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),

    @ColumnInfo(name = "version")
    override val version: Long = 0,

    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Instant? = null,

    @ColumnInfo(name = "metadata")
    val metadata: String? = null
) : EntityWithMetadata

/**
 * Tag entity for hybrid tagging system
 */
@Entity(
    tableName = "tags",
    indices = [
        Index(value = ["supabase_id"], unique = true),
        Index(value = ["user_id"]),
        Index(value = ["type", "name"])
    ]
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "supabase_id")
    val supabaseId: String,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "type")
    val type: String, // CATEGORY, TAG, SYSTEM

    @ColumnInfo(name = "color")
    val color: String? = null,

    @ColumnInfo(name = "icon")
    val icon: String? = null,

    @ColumnInfo(name = "parent_tag_id")
    val parentTagId: Long? = null,

    @ColumnInfo(name = "is_system")
    val isSystem: Boolean = false,

    @ColumnInfo(name = "sync_state")
    @SyncState.State
    val syncState: String = SyncState.PENDING,

    @ColumnInfo(name = "last_modified")
    override val lastModified: Instant = Instant.now(),

    @ColumnInfo(name = "version")
    override val version: Long = 0,

    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false
) : EntityWithMetadata

/**
 * Junction table: note_tags - Hybrid tagging system
 * Enables both category-based and tag-based organization
 */
@Entity(
    tableName = "note_tags",
    primaryKeys = ["note_id", "tag_id", "tagging_type"],
    indices = [
        Index(value = ["note_id"]),
        Index(value = ["tag_id"]),
        Index(value = ["tagging_type"]),
        Index(value = ["note_id", "tagging_type"]),
        Index(value = ["tag_id", "tagging_type"])
    ]
)
data class NoteTagEntity(
    @ColumnInfo(name = "note_id")
    val noteId: Long,

    @ColumnInfo(name = "tag_id")
    val tagId: Long,

    @ColumnInfo(name = "tagging_type")
    val taggingType: String, // CATEGORY, TAG, AUTO, MANUAL, AI

    @ColumnInfo(name = "confidence_score")
    val confidenceScore: Float? = null, // For AI-assigned tags

    @ColumnInfo(name = "assigned_by")
    val assignedBy: String? = null, // user_id or agent_id

    @ColumnInfo(name = "assigned_at")
    val assignedAt: Instant = Instant.now(),

    @ColumnInfo(name = "metadata")
    val metadata: String? = null,

    @ColumnInfo(name = "sync_state")
    @SyncState.State
    val syncState: String = SyncState.PENDING,

    @ColumnInfo(name = "last_modified")
    val lastModified: Instant = Instant.now(),

    @ColumnInfo(name = "version")
    val version: Long = 0
)

/**
 * Chat entity
 */
@Entity(
    tableName = "chats",
    indices = [
        Index(value = ["supabase_id"], unique = true),
        Index(value = ["user_id"]),
        Index(value = ["sync_state"])
    ]
)
data class ChatEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "supabase_id")
    val supabaseId: String,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "title")
    val title: String? = null,

    @ColumnInfo(name = "chat_type")
    val chatType: String, // CONVERSATION, AI_SESSION, COLLABORATION

    @ColumnInfo(name = "context")
    val context: String? = null, // JSON serialized context

    @ColumnInfo(name = "sync_state")
    @SyncState.State
    val syncState: String = SyncState.PENDING,

    @ColumnInfo(name = "last_modified")
    val lastModified: Instant = Instant.now(),

    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),

    @ColumnInfo(name = "version")
    val version: Long = 0,

    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false
)

/**
 * Chat message entity
 */
@Entity(
    tableName = "chat_messages",
    indices = [
        Index(value = ["supabase_id"], unique = true),
        Index(value = ["chat_id"]),
        Index(value = ["user_id"]),
        Index(value = ["created_at"])
    ]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "supabase_id")
    val supabaseId: String,

    @ColumnInfo(name = "chat_id")
    val chatId: Long,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "message_type")
    val messageType: String, // TEXT, AI_RESPONSE, SYSTEM, ACTION

    @ColumnInfo(name = "sender_type")
    val senderType: String, // USER, AI, SYSTEM

    @ColumnInfo(name = "context_snapshot")
    val contextSnapshot: String? = null, // JSON of context at message time

    @ColumnInfo(name = "sync_state")
    @SyncState.State
    val syncState: String = SyncState.PENDING,

    @ColumnInfo(name = "last_modified")
    override val lastModified: Instant = Instant.now(),

    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),

    @ColumnInfo(name = "version")
    override val version: Long = 0,

    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false
) : EntityWithMetadata

/**
 * Junction table: chat_message_notes - Deep note-chat integration
 * Links notes to specific chat messages for context preservation
 */
@Entity(
    tableName = "chat_message_notes",
    primaryKeys = ["chat_message_id", "note_id", "link_type"],
    indices = [
        Index(value = ["chat_message_id"]),
        Index(value = ["note_id"]),
        Index(value = ["link_type"]),
        Index(value = ["chat_id", "note_id"])
    ]
)
data class ChatMessageNoteEntity(
    @ColumnInfo(name = "chat_message_id")
    val chatMessageId: Long,

    @ColumnInfo(name = "chat_id")
    val chatId: Long,

    @ColumnInfo(name = "note_id")
    val noteId: Long,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "link_type")
    val linkType: String, // REFERENCE, GENERATED_FROM, RELATED_TO, CONTEXT_FOR

    @ColumnInfo(name = "relevance_score")
    val relevanceScore: Float? = null,

    @ColumnInfo(name = "link_reason")
    val linkReason: String? = null, // Why this note is linked

    @ColumnInfo(name = "bidirectional")
    val bidirectional: Boolean = true,

    @ColumnInfo(name = "sync_state")
    @SyncState.State
    val syncState: String = SyncState.PENDING,

    @ColumnInfo(name = "last_modified")
    val lastModified: Instant = Instant.now(),

    @ColumnInfo(name = "version")
    val version: Long = 0
)

/**
 * Calendar event entity
 */
@Entity(
    tableName = "calendar_events",
    indices = [
        Index(value = ["supabase_id"], unique = true),
        Index(value = ["user_id"]),
        Index(value = ["start_time", "end_time"]),
        Index(value = ["sync_state"])
    ]
)
data class CalendarEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "supabase_id")
    val supabaseId: String,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "start_time")
    val startTime: Instant,

    @ColumnInfo(name = "end_time")
    val endTime: Instant,

    @ColumnInfo(name = "location")
    val location: String? = null,

    @ColumnInfo(name = "event_type")
    val eventType: String, // MEETING, REMINDER, TASK, PERSONAL

    @ColumnInfo(name = "sync_state")
    @SyncState.State
    val syncState: String = SyncState.PENDING,

    @ColumnInfo(name = "last_modified")
    val lastModified: Instant = Instant.now(),

    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),

    @ColumnInfo(name = "version")
    val version: Long = 0,

    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false
)

/**
 * Junction table: calendar_event_notes - Smart event-note linking
 * Enables bidirectional linking between calendar events and notes
 */
@Entity(
    tableName = "calendar_event_notes",
    primaryKeys = ["event_id", "note_id", "link_type"],
    indices = [
        Index(value = ["event_id"]),
        Index(value = ["note_id"]),
        Index(value = ["link_type"]),
        Index(value = ["event_id", "note_id"])
    ]
)
data class CalendarEventNoteEntity(
    @ColumnInfo(name = "event_id")
    val eventId: Long,

    @ColumnInfo(name = "note_id")
    val noteId: Long,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "link_type")
    val linkType: String, // AGENDA, MEETING_NOTES, FOLLOW_UP, CONTEXT, ACTION_ITEMS

    @ColumnInfo(name = "relevance_score")
    val relevanceScore: Float? = null,

    @ColumnInfo(name = "auto_linked")
    val autoLinked: Boolean = false,

    @ColumnInfo(name = "link_reason")
    val linkReason: String? = null,

    @ColumnInfo(name = "sync_state")
    @SyncState.State
    val syncState: String = SyncState.PENDING,

    @ColumnInfo(name = "last_modified")
    val lastModified: Instant = Instant.now(),

    @ColumnInfo(name = "version")
    val version: Long = 0
)

/**
 * Task entity
 */
@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["supabase_id"], unique = true),
        Index(value = ["user_id"]),
        Index(value = ["status", "priority"]),
        Index(value = ["due_date"]),
        Index(value = ["sync_state"])
    ]
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "supabase_id")
    val supabaseId: String,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "status")
    val status: String, // TODO, IN_PROGRESS, DONE, CANCELLED

    @ColumnInfo(name = "priority")
    val priority: Int,

    @ColumnInfo(name = "due_date")
    val dueDate: Instant? = null,

    @ColumnInfo(name = "completed_at")
    val completedAt: Instant? = null,

    @ColumnInfo(name = "sync_state")
    @SyncState.State
    val syncState: String = SyncState.PENDING,

    @ColumnInfo(name = "last_modified")
    val lastModified: Instant = Instant.now(),

    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),

    @ColumnInfo(name = "version")
    val version: Long = 0,

    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false
)

/**
 * Shared item entity for collaborative workflows
 */
@Entity(
    tableName = "shared_items",
    primaryKeys = ["item_id", "item_type", "shared_with_user_id"],
    indices = [
        Index(value = ["item_id", "item_type"]),
        Index(value = ["shared_with_user_id"]),
        Index(value = ["owner_user_id"]),
        Index(value = ["permission_level"])
    ]
)
data class SharedItemEntity(
    @ColumnInfo(name = "item_id")
    val itemId: Long,

    @ColumnInfo(name = "item_type")
    val itemType: String, // NOTE, TASK, CALENDAR_EVENT, CHAT

    @ColumnInfo(name = "owner_user_id")
    val ownerUserId: Long,

    @ColumnInfo(name = "shared_with_user_id")
    val sharedWithUserId: Long,

    @ColumnInfo(name = "permission_level")
    val permissionLevel: String, // VIEW, COMMENT, EDIT, ADMIN

    @ColumnInfo(name = "shared_at")
    val sharedAt: Instant = Instant.now(),

    @ColumnInfo(name = "expires_at")
    val expiresAt: Instant? = null,

    @ColumnInfo(name = "sync_state")
    @SyncState.State
    val syncState: String = SyncState.PENDING,

    @ColumnInfo(name = "last_modified")
    val lastModified: Instant = Instant.now(),

    @ColumnInfo(name = "version")
    val version: Long = 0
)

/**
 * Reasoning trace entity - AI decision provenance
 * Linked to all entities for AI-driven data flow
 */
@Entity(
    tableName = "reasoning_traces",
    indices = [
        Index(value = ["supabase_id"], unique = true),
        Index(value = ["user_id"]),
        Index(value = ["entity_type", "entity_id"]),
        Index(value = ["trace_type"]),
        Index(value = ["created_at"])
    ]
)
data class ReasoningTraceEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "supabase_id")
    val supabaseId: String,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "entity_type")
    val entityType: String, // NOTE, TASK, CALENDAR_EVENT, CHAT, USER

    @ColumnInfo(name = "entity_id")
    val entityId: Long,

    @ColumnInfo(name = "trace_type")
    val traceType: String, // DECISION, PREDICTION, RECOMMENDATION, VALIDATION

    @ColumnInfo(name = "agent_id")
    val agentId: String? = null,

    @ColumnInfo(name = "input_context")
    val inputContext: String, // JSON serialized input

    @ColumnInfo(name = "reasoning_steps")
    val reasoningSteps: String, // JSON array of reasoning steps

    @ColumnInfo(name = "output_decision")
    val outputDecision: String, // JSON serialized decision

    @ColumnInfo(name = "confidence_score")
    val confidenceScore: Float,

    @ColumnInfo(name = "alternative_options")
    val alternativeOptions: String? = null, // JSON array

    @ColumnInfo(name = "execution_result")
    val executionResult: String? = null, // JSON serialized result

    @ColumnInfo(name = "feedback_score")
    val feedbackScore: Float? = null,

    @ColumnInfo(name = "parent_trace_id")
    val parentTraceId: Long? = null,

    @ColumnInfo(name = "sync_state")
    @SyncState.State
    val syncState: String = SyncState.PENDING,

    @ColumnInfo(name = "last_modified")
    val lastModified: Instant = Instant.now(),

    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),

    @ColumnInfo(name = "version")
    val version: Long = 0,

    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false
)

/**
 * Agent checkpoint entity - for session continuity
 */
@Entity(
    tableName = "agent_checkpoints",
    indices = [
        Index(value = ["supabase_id"], unique = true),
        Index(value = ["user_id"]),
        Index(value = ["session_id"]),
        Index(value = ["checkpoint_type"]),
        Index(value = ["created_at"])
    ]
)
data class AgentCheckpointEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "supabase_id")
    val supabaseId: String,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "agent_id")
    val agentId: String,

    @ColumnInfo(name = "checkpoint_type")
    val checkpointType: String, // SESSION_START, SESSION_END, CONTEXT_SAVE, STATE_RESTORE

    @ColumnInfo(name = "context_state")
    val contextState: String, // JSON serialized context state

    @ColumnInfo(name = "active_entities")
    val activeEntities: String? = null, // JSON array of active entity references

    @ColumnInfo(name = "memory_state")
    val memoryState: String? = null, // JSON serialized memory

    @ColumnInfo(name = "prediction_cache")
    val predictionCache: String? = null, // JSON cached predictions

    @ColumnInfo(name = "sync_state")
    @SyncState.State
    val syncState: String = SyncState.PENDING,

    @ColumnInfo(name = "last_modified")
    val lastModified: Instant = Instant.now(),

    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),

    @ColumnInfo(name = "version")
    val version: Long = 0,

    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false
)

/**
 * Unified search index entity
 */
@Entity(
    tableName = "search_index",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["entity_type", "entity_id"]),
        Index(value = ["search_content"]),
        Index(value = ["last_indexed"])
    ]
)
data class SearchIndexEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "entity_type")
    val entityType: String,

    @ColumnInfo(name = "entity_id")
    val entityId: Long,

    @ColumnInfo(name = "search_content")
    val searchContent: String,

    @ColumnInfo(name = "search_tokens")
    val searchTokens: String, // JSON array of normalized tokens

    @ColumnInfo(name = "weight")
    val weight: Float = 1.0f,

    @ColumnInfo(name = "last_indexed")
    val lastIndexed: Instant = Instant.now(),

    @ColumnInfo(name = "sync_state")
    @SyncState.State
    val syncState: String = SyncState.PENDING,

    @ColumnInfo(name = "version")
    val version: Long = 0
)

/**
 * CRDT metadata for conflict-free replication
 */
@Entity(
    tableName = "crdt_metadata",
    primaryKeys = ["entity_type", "entity_id", "device_id"],
    indices = [
        Index(value = ["entity_type", "entity_id"]),
        Index(value = ["last_updated"])
    ]
)
data class CRDTMetadataEntity(
    @ColumnInfo(name = "entity_type")
    val entityType: String,

    @ColumnInfo(name = "entity_id")
    val entityId: Long,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "vector_clock")
    val vectorClock: String, // JSON serialized vector clock

    @ColumnInfo(name = "last_updated")
    val lastUpdated: Instant,

    @ColumnInfo(name = "update_count")
    val updateCount: Long,

    @ColumnInfo(name = "merge_strategy")
    val mergeStrategy: String, // LAST_WRITE_WINS, MANUAL, CUSTOM

    @ColumnInfo(name = "conflict_flags")
    val conflictFlags: String? = null, // JSON array of conflicts

    @ColumnInfo(name = "sync_state")
    @SyncState.State
    val syncState: String = SyncState.PENDING,

    @ColumnInfo(name = "version")
    val version: Long = 0
)
