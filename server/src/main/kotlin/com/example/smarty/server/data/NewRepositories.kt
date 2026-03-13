package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Tasks Repository (v6.0.0)
 */
class TaskRepository(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(TaskRepository::class.java)

    /**
     * Create a new task
     */
    suspend fun createTask(task: Task): String = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO tasks (
                    id, user_id, session_id, note_id, title, description,
                    status, priority, due_date, completed_at, sort_order,
                    is_recurring, recurrence_rule, metadata, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, task.id)
                stmt.setObject(2, UUID.fromString(task.userId))
                stmt.setObject(3, task.sessionId?.let { UUID.fromString(it) })
                stmt.setObject(4, task.noteId?.let { UUID.fromString(it) })
                stmt.setString(5, task.title)
                stmt.setString(6, task.description)
                stmt.setString(7, task.status.name)
                stmt.setInt(8, task.priority)
                stmt.setTimestamp(9, task.dueDate?.let { java.sql.Timestamp.from(it) })
                stmt.setTimestamp(10, task.completedAt?.let { java.sql.Timestamp.from(it) })
                stmt.setInt(11, task.sortOrder)
                stmt.setBoolean(12, task.isRecurring)
                stmt.setString(13, task.recurrenceRule)
                stmt.setString(14, kotlinx.serialization.json.Json.encodeToString(task.metadata))
                stmt.setTimestamp(15, java.sql.Timestamp.from(task.createdAt))
                stmt.setTimestamp(16, java.sql.Timestamp.from(task.updatedAt))
                stmt.executeUpdate()
            }
        }
        task.id.toString()
    }

    /**
     * Get tasks for a user
     */
    suspend fun getTasksForUser(userId: String, status: TaskStatus? = null): List<Task> = withContext(Dispatchers.IO) {
        val tasks = mutableListOf<Task>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT * FROM tasks
                WHERE user_id = ? AND deleted_at IS NULL
                ${if (status != null) "AND status = ?" else ""}
                ORDER BY sort_order, due_date ASC NULLS LAST, created_at DESC
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(userId))
                if (status != null) {
                    stmt.setString(2, status.name)
                }
                
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        tasks.add(rs.toTask())
                    }
                }
            }
        }
        tasks
    }

    /**
     * Update task status
     */
    suspend fun updateTaskStatus(taskId: String, status: TaskStatus): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                UPDATE tasks SET
                    status = ?,
                    completed_at = ?,
                    updated_at = ?
                WHERE id = ?
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, status.name)
                stmt.setTimestamp(2, if (status == TaskStatus.DONE) java.sql.Timestamp.from(Instant.now()) else null)
                stmt.setTimestamp(3, java.sql.Timestamp.from(Instant.now()))
                stmt.setObject(4, UUID.fromString(taskId))
                stmt.executeUpdate() > 0
            }
        }
    }

    /**
     * Delete task (soft delete)
     */
    suspend fun deleteTask(taskId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "UPDATE tasks SET deleted_at = ?, updated_at = ? WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setTimestamp(1, java.sql.Timestamp.from(Instant.now()))
                stmt.setTimestamp(2, java.sql.Timestamp.from(Instant.now()))
                stmt.setObject(3, UUID.fromString(taskId))
                stmt.executeUpdate() > 0
            }
        }
    }

    private fun ResultSet.toTask(): Task {
        return Task(
            id = getObject("id") as UUID,
            userId = getObject("user_id").toString(),
            sessionId = getObject("session_id")?.toString(),
            noteId = getObject("note_id")?.toString(),
            title = getString("title"),
            description = getString("description"),
            status = TaskStatus.valueOf(getString("status")),
            priority = getInt("priority"),
            dueDate = getTimestamp("due_date")?.toInstant()?.toString(),
            completedAt = getTimestamp("completed_at")?.toInstant()?.toString(),
            sortOrder = getInt("sort_order"),
            isRecurring = getBoolean("is_recurring"),
            recurrenceRule = getString("recurrence_rule"),
            createdAt = getTimestamp("created_at").toInstant().toString(),
            updatedAt = getTimestamp("updated_at").toInstant().toString(),
            deletedAt = getTimestamp("deleted_at")?.toInstant()?.toString()
        )
    }
}

/**
 * Tags Repository (v6.0.0)
 */
class TagRepository(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(TagRepository::class.java)

    /**
     * Create a tag
     */
    suspend fun createTag(tag: Tag): String = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO tags (id, user_id, name, color, usage_count, created_at)
                VALUES (?, ?, lower(?), ?, ?, ?)
                ON CONFLICT (user_id, lower(name)) DO UPDATE SET
                    color = EXCLUDED.color,
                    updated_at = now()
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, tag.id)
                stmt.setObject(2, UUID.fromString(tag.userId))
                stmt.setString(3, tag.name)
                stmt.setString(4, tag.color)
                stmt.setInt(5, tag.usageCount)
                stmt.setTimestamp(6, java.sql.Timestamp.from(tag.createdAt))
                stmt.executeUpdate()
            }
        }
        tag.id.toString()
    }

    /**
     * Get tags for a user
     */
    suspend fun getTagsForUser(userId: String): List<Tag> = withContext(Dispatchers.IO) {
        val tags = mutableListOf<Tag>()
        dataSource.connection.use { conn ->
            val sql = "SELECT * FROM tags WHERE user_id = ? ORDER BY name"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(userId))
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        tags.add(
                            Tag(
                                id = rs.getObject("id") as UUID,
                                userId = rs.getObject("user_id").toString(),
                                name = rs.getString("name"),
                                color = rs.getString("color"),
                                usageCount = rs.getInt("usage_count"),
                                createdAt = rs.getTimestamp("created_at").toInstant()
                            )
                        )
                    }
                }
            }
        }
        tags
    }

    /**
     * Delete a tag
     */
    suspend fun deleteTag(tagId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "DELETE FROM tags WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(tagId))
                stmt.executeUpdate() > 0
            }
        }
    }
}

/**
 * Notifications Repository (v6.0.0)
 */
class NotificationRepository(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(NotificationRepository::class.java)

    /**
     * Create a notification
     */
    suspend fun createNotification(notification: Notification): String = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO notifications (
                    id, user_id, type, title, body, data, is_read, created_at
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, notification.id)
                stmt.setObject(2, UUID.fromString(notification.userId))
                stmt.setString(3, notification.type)
                stmt.setString(4, notification.title)
                stmt.setString(5, notification.body)
                stmt.setString(6, kotlinx.serialization.json.Json.encodeToString(notification.data))
                stmt.setBoolean(7, notification.isRead)
                stmt.setTimestamp(8, java.sql.Timestamp.from(notification.createdAt))
                stmt.executeUpdate()
            }
        }
        notification.id.toString()
    }

    /**
     * Get unread notifications for a user
     */
    suspend fun getUnreadNotifications(userId: String): List<Notification> = withContext(Dispatchers.IO) {
        val notifications = mutableListOf<Notification>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT * FROM notifications
                WHERE user_id = ? AND is_read = false
                ORDER BY created_at DESC
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(userId))
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        notifications.add(rs.toNotification())
                    }
                }
            }
        }
        notifications
    }

    /**
     * Mark notification as read
     */
    suspend fun markAsRead(notificationId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "UPDATE notifications SET is_read = true, read_at = ? WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setTimestamp(1, java.sql.Timestamp.from(Instant.now()))
                stmt.setObject(2, UUID.fromString(notificationId))
                stmt.executeUpdate() > 0
            }
        }
    }

    private fun Connection.ResultSet.toNotification(): Notification {
        return Notification(
            id = getObject("id") as UUID,
            userId = getObject("user_id").toString(),
            type = getString("type"),
            title = getString("title"),
            body = getString("body"),
            isRead = getBoolean("is_read"),
            readAt = getTimestamp("read_at")?.toInstant(),
            createdAt = getTimestamp("created_at").toInstant()
        )
    }
}
