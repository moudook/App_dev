package com.example.smarty.server.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Task data model (v6.0.0)
 */
@Serializable
data class Task(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val sessionId: String? = null,
    val noteId: String? = null,
    val title: String,
    val description: String? = null,
    val status: TaskStatus = TaskStatus.TODO,
    val priority: Int = 2,  // 0-4 scale
    val dueDate: String? = null,  // ISO 8601 format
    val completedAt: String? = null,
    val sortOrder: Int = 0,
    val isRecurring: Boolean = false,
    val recurrenceRule: String? = null,
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String = Instant.now().toString(),
    val deletedAt: String? = null
)

@Serializable
enum class TaskStatus {
    TODO,
    IN_PROGRESS,
    DONE,
    CANCELLED
}

/**
 * Tag data model (v6.0.0)
 */
@Serializable
data class Tag(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val name: String,
    val color: String = "#6200EE",
    val usageCount: Int = 0,
    val createdAt: String = Instant.now().toString()
)

/**
 * Notification data model (v6.0.0)
 */
@Serializable
data class Notification(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val type: String,
    val title: String,
    val body: String? = null,
    val isRead: Boolean = false,
    val readAt: String? = null,
    val createdAt: String = Instant.now().toString()
)

/**
 * Chat Folder data model (v6.0.0)
 */
@Serializable
data class ChatFolder(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val name: String,
    val color: String = "#6200EE",
    val sortOrder: Int = 0,
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String = Instant.now().toString()
)

/**
 * Search History data model (v6.0.0)
 */
@Serializable
data class SearchHistory(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val query: String,
    val searchScope: SearchScope = SearchScope.ALL,
    val resultCount: Int = 0,
    val createdAt: String = Instant.now().toString()
)

@Serializable
enum class SearchScope {
    ALL,
    NOTES,
    CHAT,
    RESEARCH,
    TASKS
}
