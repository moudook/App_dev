package com.example.smarty.server.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Task data model (v6.0.0 schema)
 * Matches: tasks table
 */
@Serializable
data class Task(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val sessionId: String? = null,
    val noteId: String? = null,
    val title: String,
    val description: String? = null,
    val status: String = "todo", // todo, in_progress, done, cancelled
    val priority: Int = 2, // 0-4 scale
    val dueDate: String? = null, // TIMESTAMPTZ
    val completedAt: String? = null, // TIMESTAMPTZ
    val sortOrder: Int = 0,
    val isRecurring: Boolean = false,
    val recurrenceRule: String? = null,
    val metadata: String = "{}", // JSONB
    val createdAt: String? = null, // TIMESTAMPTZ
    val updatedAt: String? = null, // TIMESTAMPTZ
    val deletedAt: String? = null, // TIMESTAMPTZ (soft delete)
)

/**
 * Tag data model (v6.0.0 schema)
 * Matches: tags table
 */
@Serializable
data class Tag(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val name: String,
    val color: String = "#6200EE",
    val usageCount: Int = 0,
    val createdAt: String? = null, // TIMESTAMPTZ
)

/**
 * Notification data model (v6.0.0 schema)
 * Matches: notifications table
 */
@Serializable
data class Notification(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val type: String,
    val title: String,
    val body: String? = null,
    val data: String = "{}", // JSONB
    val isRead: Boolean = false,
    val readAt: String? = null, // TIMESTAMPTZ
    val createdAt: String? = null, // TIMESTAMPTZ
)

/**
 * Chat Folder data model (v6.0.0 schema)
 * Matches: chat_folders table
 */
@Serializable
data class ChatFolder(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val name: String,
    val color: String = "#6200EE",
    val sortOrder: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/**
 * User Device data model (v6.0.0 schema)
 * Matches: user_devices table
 */
@Serializable
data class UserDevice(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val deviceName: String? = null,
    val deviceType: String? = null, // ios, android, web, desktop, other
    val pushToken: String? = null,
    val lastActiveAt: String? = null,
    val appVersion: String? = null,
    val metadata: String = "{}",
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/**
 * Search History data model (v6.0.0 schema)
 * Matches: search_history table
 */
@Serializable
data class SearchHistory(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val query: String,
    val searchScope: String = "all", // all, notes, chat, research, tasks
    val resultCount: Int = 0,
    val createdAt: String? = null,
)

/**
 * Shared Item data model (v6.0.0 schema)
 * Matches: shared_items table
 */
@Serializable
data class SharedItem(
    val id: String = UUID.randomUUID().toString(),
    val ownerId: String,
    val sharedWithId: String? = null,
    val itemType: String, // note, chat_session, research_session, task
    val itemId: String,
    val permission: String = "view", // view, comment, edit
    val shareToken: String? = null,
    val expiresAt: String? = null,
    val createdAt: String? = null,
)

/**
 * Note Tag join model (v6.0.0 schema)
 * Matches: note_tags table
 */
@Serializable
data class NoteTag(
    val noteId: String,
    val tagId: String,
)

/**
 * Note Version data model (v6.0.0 schema)
 * Matches: note_versions table
 */
@Serializable
data class NoteVersion(
    val id: String = UUID.randomUUID().toString(),
    val noteId: String,
    val title: String,
    val content: String,
    val versionNo: Int,
    val createdAt: String? = null,
)

/**
 * Chat Attachment data model (v6.0.0 schema)
 * Matches: chat_attachments table
 */
@Serializable
data class ChatAttachment(
    val id: String = UUID.randomUUID().toString(),
    val messageId: String,
    val fileId: String,
    val userId: String,
    val createdAt: String? = null,
)

/**
 * Note Attachment data model (v6.0.0 schema)
 * Matches: note_attachments table
 */
@Serializable
data class NoteAttachment(
    val noteId: String,
    val fileId: String,
)

/**
 * Stack data model (v6.0.0 schema)
 * Matches: stacks table
 */
@Serializable
data class Stack(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val name: String,
    val description: String? = null,
    val color: String = "#03DAC6",
    val icon: String = "stack",
    val parentId: String? = null,
    val noteCount: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/**
 * Note-Stack join model (v6.0.0 schema)
 * Matches: note_stacks table
 */
@Serializable
data class NoteStack(
    val noteId: String,
    val stackId: String,
)
