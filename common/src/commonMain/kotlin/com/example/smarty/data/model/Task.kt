package com.example.smarty.core.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Task(
    val id: String = "",
    val userId: String = "",
    val sessionId: String? = null,
    val noteId: String? = null,
    val title: String = "",
    val description: String? = null,
    val status: String = "todo",
    val priority: Int = 2,
    val dueDate: String? = null,
    val completedAt: String? = null,
    val sortOrder: Int = 0,
    val isRecurring: Boolean = false,
    val recurrenceRule: String? = null,
    val metadata: String = "{}",
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val deletedAt: String? = null,
) {
    companion object {
        const val STATUS_TODO = "todo"
        const val STATUS_IN_PROGRESS = "in_progress"
        const val STATUS_DONE = "done"
        const val STATUS_CANCELLED = "cancelled"
    }

    val isOverdue: Boolean
        get() = dueDate != null && status != STATUS_DONE && status != STATUS_CANCELLED
}

@Serializable
data class TasksResponse(
    val success: Boolean,
    val tasks: List<Task> = emptyList(),
    val message: String? = null,
)

@Serializable
data class TaskResponse(
    val success: Boolean,
    val task: Task? = null,
    val message: String? = null,
)

@Serializable
data class TaskCreateResponse(
    val success: Boolean,
    val id: String,
    val message: String? = null,
)
