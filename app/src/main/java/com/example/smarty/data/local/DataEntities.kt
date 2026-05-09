package com.example.smarty.data.local

import androidx.room.*
import com.example.smarty.core.domain.model.NoteType
import com.example.smarty.core.domain.model.ProcessingStatus
import kotlinx.serialization.Serializable

/**
 * Comprehensive data entities for tight database-application integration
 * All entities include user_id for proper scoping and multi-tenant support
 */

// ============================================================
// TAGS - Proper tag entity (replaces JSON-based tags in notes)
// ============================================================
@Entity(
    tableName = "tags",
    indices = [
        Index(value = ["user_id"], unique = false),
        Index(value = ["user_id", "name"], unique = true),
        Index(value = ["usage_count"]),
    ],
)
data class TagEntity(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "user_id")
    val userId: String,
    
    val name: String,
    
    val color: String = "#6200EE",
    
    @ColumnInfo(name = "usage_count")
    val usageCount: Int = 0,
    
    @ColumnInfo(name = "tag_type")
    val tagType: String = TagType.MANUAL.name,
    
    @ColumnInfo(name = "confidence_score")
    val confidenceScore: Double = 1.0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
) {
    enum class TagType {
        MANUAL, AUTO, AI
    }
}

// ============================================================
// STACKS - Note organization stacks
// ============================================================
@Entity(
    tableName = "stacks",
    indices = [
        Index(value = ["user_id"], unique = false),
        Index(value = ["name"], unique = false),
    ],
)
data class StackEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    val name: String,
    val description: String? = null,
    val color: String = "#03DAC6",
    val icon: String = "stack",

    @ColumnInfo(name = "parent_id")
    val parentId: String? = null,

    @ColumnInfo(name = "note_count")
    val noteCount: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "note_stacks",
    primaryKeys = ["note_id", "stack_id"],
    indices = [
        Index(value = ["note_id"], unique = false),
        Index(value = ["stack_id"], unique = false),
    ],
)
data class NoteStackEntity(
    @ColumnInfo(name = "note_id")
    val noteId: String,

    @ColumnInfo(name = "stack_id")
    val stackId: String,
)
@Entity(
    tableName = "note_tags",
    primaryKeys = ["note_id", "tag_id"],
    indices = [
        Index(value = ["note_id"], unique = false),
        Index(value = ["tag_id"], unique = false),
    ],
)
data class NoteTagEntity(
    @ColumnInfo(name = "note_id")
    val noteId: String,

    @ColumnInfo(name = "tag_id")
    val tagId: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "assigned_by")
    val assignedBy: String = "user",

    @ColumnInfo(name = "confidence_score")
    val confidenceScore: Double = 1.0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)

// ============================================================
// CHAT_FOLDERS - Organization for chat sessions
// ============================================================
@Entity(
    tableName = "chat_folders",
    indices = [
        Index(value = ["user_id"], unique = false),
        Index(value = ["user_id", "name"], unique = false),
    ],
)
data class ChatFolderEntity(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "user_id")
    val userId: String,
    
    val name: String,
    
    val color: String = "#6200EE",
    
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)

// ============================================================
// TASKS - Task management entity
// ============================================================
@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["user_id"], unique = false),
        Index(value = ["session_id"], unique = false),
        Index(value = ["note_id"], unique = false),
        Index(value = ["status", "due_date"], unique = false),
        Index(value = ["is_recurring"]),
    ],
)
data class TaskEntity(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "user_id")
    val userId: String,
    
    @ColumnInfo(name = "session_id")
    val sessionId: String? = null,
    
    @ColumnInfo(name = "note_id")
    val noteId: String? = null,
    
    val title: String,
    
    val description: String? = null,
    
    val status: String = TaskStatus.TODO.name,
    
    val priority: Int = 2,
    
    @ColumnInfo(name = "due_date")
    val dueDate: Long? = null,
    
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,
    
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    
    @ColumnInfo(name = "is_recurring")
    val isRecurring: Boolean = false,
    
    @ColumnInfo(name = "recurrence_rule")
    val recurrenceRule: String? = null,
    
    @ColumnInfo(name = "metadata")
    val metadata: String = "{}",
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
) {
    enum class TaskStatus {
        TODO, IN_PROGRESS, COMPLETED, CANCELLED, BLOCKED
    }
}

// ============================================================
// NOTE_TASKS - Junction table for notes and tasks
// ============================================================
@Entity(
    tableName = "note_tasks",
    primaryKeys = ["note_id", "task_id"],
    indices = [
        Index(value = ["note_id"]),
        Index(value = ["task_id"]),
        Index(value = ["user_id"]),
    ],
)
data class NoteTaskEntity(
    @ColumnInfo(name = "note_id")
    val noteId: String,
    
    @ColumnInfo(name = "task_id")
    val taskId: String,
    
    @ColumnInfo(name = "user_id")
    val userId: String,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)

// ============================================================
// REASONING_TRACES - AI reasoning step-by-step logs
// ============================================================
@Entity(
    tableName = "reasoning_traces",
    indices = [
        Index(value = ["user_id"], unique = false),
        Index(value = ["session_id"], unique = false),
        Index(value = ["message_id"], unique = false),
        Index(value = ["entity_type", "entity_id"], unique = false),
        Index(value = ["step_index"], unique = false),
        Index(value = ["created_at"], unique = false),
    ],
)
data class ReasoningTraceEntity(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    
    @ColumnInfo(name = "message_id")
    val messageId: String? = null,
    
    @ColumnInfo(name = "user_id")
    val userId: String,
    
    @ColumnInfo(name = "step_index")
    val stepIndex: Int,
    
    @ColumnInfo(name = "step_type")
    val stepType: String,
    
    @ColumnInfo(name = "title")
    val title: String,
    
    @ColumnInfo(name = "content")
    val content: String,
    
    @ColumnInfo(name = "entity_type")
    val entityType: String? = null,
    
    @ColumnInfo(name = "entity_id")
    val entityId: String? = null,
    
    @ColumnInfo(name = "input_data")
    val inputData: String? = null,
    
    @ColumnInfo(name = "output_data")
    val outputData: String? = null,
    
    @ColumnInfo(name = "confidence_score")
    val confidenceScore: Double = 0.5,
    
    @ColumnInfo(name = "importance_score")
    val importanceScore: Double = 0.5,
    
    @ColumnInfo(name = "is_final")
    val isFinal: Boolean = false,
    
    @ColumnInfo(name = "was_revised")
    val wasRevised: Boolean = false,
    
    @ColumnInfo(name = "revised_by_trace_id")
    val revisedByTraceId: String? = null,
    
    @ColumnInfo(name = "token_count")
    val tokenCount: Int = 0,
    
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0,
    
    @ColumnInfo(name = "metadata")
    val metadata: String = "{}",
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)

// ============================================================
// REASONING_SUMMARIES - AI reasoning session summaries
// ============================================================
@Entity(
    tableName = "reasoning_summaries",
    indices = [
        Index(value = ["user_id"], unique = false),
        Index(value = ["session_id"], unique = false),
        Index(value = ["message_id"], unique = false),
        Index(value = ["created_at"], unique = false),
    ],
)
data class ReasoningSummaryEntity(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    
    @ColumnInfo(name = "message_id")
    val messageId: String? = null,
    
    @ColumnInfo(name = "user_id")
    val userId: String,
    
    @ColumnInfo(name = "one_liner")
    val oneLiner: String,
    
    @ColumnInfo(name = "brief_summary")
    val briefSummary: String,
    
    @ColumnInfo(name = "detailed_summary")
    val detailedSummary: String,
    
    @ColumnInfo(name = "total_steps")
    val totalSteps: Int = 0,
    
    @ColumnInfo(name = "total_duration_ms")
    val totalDurationMs: Long = 0,
    
    @ColumnInfo(name = "total_tokens")
    val totalTokens: Int = 0,
    
    @ColumnInfo(name = "confidence_score")
    val confidenceScore: Double = 0.5,
    
    @ColumnInfo(name = "complexity_score")
    val complexityScore: Double = 0.5,
    
    @ColumnInfo(name = "reasoning_type")
    val reasoningType: String,
    
    @ColumnInfo(name = "tags")
    val tags: String = "[]",
    
    @ColumnInfo(name = "linked_entities")
    val linkedEntities: String = "[]",
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)

// ============================================================
// AGENT_CHECKPOINTS - AI agent state snapshots
// ============================================================
@Entity(
    tableName = "agent_checkpoints",
    indices = [
        Index(value = ["user_id"], unique = false),
        Index(value = ["session_id"], unique = false),
        Index(value = ["workflow_id"], unique = false),
        Index(value = ["created_at"], unique = false),
    ],
)
data class AgentCheckpointEntity(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    
    @ColumnInfo(name = "user_id")
    val userId: String,
    
    @ColumnInfo(name = "workflow_id")
    val workflowId: String? = null,
    
    @ColumnInfo(name = "state_json")
    val stateJson: String,
    
    @ColumnInfo(name = "context_json")
    val contextJson: String? = null,
    
    @ColumnInfo(name = "memory_json")
    val memoryJson: String? = null,
    
    @ColumnInfo(name = "version")
    val version: Int = 1,
    
    @ColumnInfo(name = "checkpoint_type")
    val checkpointType: String = CheckpointType.MANUAL.name,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
) {
    enum class CheckpointType {
        MANUAL, AUTO, SYSTEM, BEFORE_ACTION, AFTER_ACTION
    }
}

// ============================================================
// SEARCH_HISTORY - Persistent search query history
// ============================================================
@Entity(
    tableName = "search_history",
    indices = [
        Index(value = ["user_id"], unique = false),
        Index(value = ["query"], unique = false),
        Index(value = ["created_at"], unique = false),
        Index(value = ["search_scope"], unique = false),
    ],
)
data class SearchHistoryEntity(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "user_id")
    val userId: String,
    
    val query: String,
    
    @ColumnInfo(name = "search_scope")
    val searchScope: String = "all",
    
    @ColumnInfo(name = "result_count")
    val resultCount: Int = 0,
    
    @ColumnInfo(name = "entities_found")
    val entitiesFound: String = "[]",
    
    @ColumnInfo(name = "search_type")
    val searchType: String = SearchType.TEXT.name,
    
    @ColumnInfo(name = "ai_enhanced")
    val aiEnhanced: Boolean = false,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
) {
    enum class SearchType {
        TEXT, SEMANTIC, HYBRID, AI
    }
}

// ============================================================
// USER_FCM_TOKENS - FCM push notification tokens
// ============================================================
@Entity(
    tableName = "user_fcm_tokens",
    indices = [
        Index(value = ["user_id"], unique = false),
        Index(value = ["token"], unique = true),
        Index(value = ["device_id"], unique = false),
    ],
)
data class UserFcmTokenEntity(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "user_id")
    val userId: String,
    
    val token: String,
    
    @ColumnInfo(name = "device_name")
    val deviceName: String? = null,
    
    @ColumnInfo(name = "device_id")
    val deviceId: String? = null,
    
    @ColumnInfo(name = "platform")
    val platform: String = "android",
    
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)

// ============================================================
// DAILY_DIGESTS - Generated digest records
// ============================================================
@Entity(
    tableName = "daily_digests",
    indices = [
        Index(value = ["user_id"], unique = false),
        Index(value = ["digest_date"], unique = false),
        Index(value = ["digest_type"], unique = false),
    ],
)
data class DailyDigestEntity(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "user_id")
    val userId: String,
    
    @ColumnInfo(name = "digest_date")
    val digestDate: Long,
    
    @ColumnInfo(name = "digest_type")
    val digestType: String = DigestType.DAILY.name,
    
    @ColumnInfo(name = "content")
    val content: String,
    
    @ColumnInfo(name = "notification_sent")
    val notificationSent: Boolean = false,
    
    @ColumnInfo(name = "calendar_event_id")
    val calendarEventId: String? = null,
    
    @ColumnInfo(name = "linked_note_ids")
    val linkedNoteIds: String = "[]",
    
    @ColumnInfo(name = "generated_by_ai")
    val generatedByAi: Boolean = true,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
) {
    enum class DigestType {
        DAILY, WEEKLY, MONTHLY, CUSTOM
    }
}

// ============================================================
// SHARED_ITEMS - User-to-user sharing
// ============================================================
@Entity(
    tableName = "shared_items",
    indices = [
        Index(value = ["owner_id"], unique = false),
        Index(value = ["shared_with_id"], unique = false),
        Index(value = ["share_token"], unique = true),
        Index(value = ["item_type", "item_id"], unique = false),
    ],
)
data class SharedItemEntity(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "owner_id")
    val ownerId: String,
    
    @ColumnInfo(name = "shared_with_id")
    val sharedWithId: String? = null,
    
    @ColumnInfo(name = "item_type")
    val itemType: String,
    
    @ColumnInfo(name = "item_id")
    val itemId: String,
    
    @ColumnInfo(name = "permission")
    val permission: String = Permission.VIEW.name,
    
    @ColumnInfo(name = "share_token")
    val shareToken: String? = null,
    
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long? = null,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
) {
    enum class Permission {
        VIEW, COMMENT, EDIT, ADMIN
    }
}

// ============================================================
// NOTE_VERSIONS - Git-like note versioning
// ============================================================
@Entity(
    tableName = "note_versions",
    indices = [
        Index(value = ["note_id"], unique = false),
        Index(value = ["version_no"], unique = false),
        Index(value = ["created_at"], unique = false),
    ],
)
data class NoteVersionEntity(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "note_id")
    val noteId: String,
    
    val title: String,
    
    val content: String,
    
    val summary: String? = null,
    
    @ColumnInfo(name = "version_no")
    val versionNo: Int,
    
    @ColumnInfo(name = "change_description")
    val changeDescription: String? = null,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)