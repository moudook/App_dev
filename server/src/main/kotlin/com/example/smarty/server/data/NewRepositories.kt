package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

/**
 * Tasks Repository (v6.0.0 schema)
 * Handles: tasks table
 */
class TaskRepository(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(TaskRepository::class.java)

    suspend fun createTask(task: Task): String = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO tasks (
                    id, user_id, session_id, note_id, title, description,
                    status, priority, due_date, completed_at, sort_order,
                    is_recurring, recurrence_rule, metadata, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now(), now())
                RETURNING id
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(task.id))
                stmt.setObject(2, UUID.fromString(task.userId))
                stmt.setObject(3, task.sessionId?.let { UUID.fromString(it) })
                stmt.setObject(4, task.noteId?.let { UUID.fromString(it) })
                stmt.setString(5, task.title)
                stmt.setString(6, task.description)
                stmt.setString(7, task.status)
                stmt.setInt(8, task.priority)
                stmt.setTimestamp(9, task.dueDate?.let { java.sql.Timestamp.valueOf(it.replace("Z", "")) })
                stmt.setTimestamp(10, task.completedAt?.let { java.sql.Timestamp.valueOf(it.replace("Z", "")) })
                stmt.setInt(11, task.sortOrder)
                stmt.setBoolean(12, task.isRecurring)
                stmt.setString(13, task.recurrenceRule)
                stmt.setString(14, task.metadata)
                val rs = stmt.executeQuery()
                if (rs.next()) rs.getObject("id").toString() else task.id
            }
        }
    }

    suspend fun getTasksForUser(userId: String, status: String? = null): List<Task> = withContext(Dispatchers.IO) {
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
                if (status != null) stmt.setString(2, status)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) tasks.add(rs.toTask())
                }
            }
        }
        tasks
    }

    suspend fun getTaskById(taskId: String): Task? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "SELECT * FROM tasks WHERE id = ? AND deleted_at IS NULL"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(taskId))
                stmt.executeQuery().use { rs -> if (rs.next()) rs.toTask() else null }
            }
        }
    }

    suspend fun updateTaskStatus(taskId: String, status: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                UPDATE tasks SET
                    status = ?,
                    completed_at = CASE WHEN ? = 'done' THEN now() ELSE completed_at END,
                    updated_at = now()
                WHERE id = ?
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, status)
                stmt.setString(2, status)
                stmt.setObject(3, UUID.fromString(taskId))
                stmt.executeUpdate() > 0
            }
        }
    }

    suspend fun updateTask(task: Task): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                UPDATE tasks SET
                    title = ?, description = ?, status = ?, priority = ?,
                    due_date = ?, sort_order = ?, is_recurring = ?,
                    recurrence_rule = ?, metadata = ?::jsonb, updated_at = now()
                WHERE id = ?
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, task.title)
                stmt.setString(2, task.description)
                stmt.setString(3, task.status)
                stmt.setInt(4, task.priority)
                stmt.setTimestamp(5, task.dueDate?.let { java.sql.Timestamp.valueOf(it.replace("Z", "")) })
                stmt.setInt(6, task.sortOrder)
                stmt.setBoolean(7, task.isRecurring)
                stmt.setString(8, task.recurrenceRule)
                stmt.setString(9, task.metadata)
                stmt.setObject(10, UUID.fromString(task.id))
                stmt.executeUpdate() > 0
            }
        }
    }

    suspend fun deleteTask(taskId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "UPDATE tasks SET deleted_at = now(), updated_at = now() WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(taskId))
                stmt.executeUpdate() > 0
            }
        }
    }

    suspend fun getTasksForSession(sessionId: String): List<Task> = withContext(Dispatchers.IO) {
        val tasks = mutableListOf<Task>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT * FROM tasks
                WHERE session_id = ? AND deleted_at IS NULL
                ORDER BY sort_order, due_date ASC NULLS LAST
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                stmt.executeQuery().use { rs ->
                    while (rs.next()) tasks.add(rs.toTask())
                }
            }
        }
        tasks
    }

    private fun ResultSet.toTask(): Task {
        return Task(
            id = getObject("id").toString(),
            userId = getObject("user_id").toString(),
            sessionId = getObject("session_id")?.toString(),
            noteId = getObject("note_id")?.toString(),
            title = getString("title"),
            description = getString("description"),
            status = getString("status"),
            priority = getInt("priority"),
            dueDate = getTimestamp("due_date")?.toString(),
            completedAt = getTimestamp("completed_at")?.toString(),
            sortOrder = getInt("sort_order"),
            isRecurring = getBoolean("is_recurring"),
            recurrenceRule = getString("recurrence_rule"),
            metadata = getString("metadata") ?: "{}",
            createdAt = getTimestamp("created_at")?.toString(),
            updatedAt = getTimestamp("updated_at")?.toString(),
            deletedAt = getTimestamp("deleted_at")?.toString()
        )
    }
}

/**
 * Tags Repository (v6.0.0 schema)
 * Handles: tags table, note_tags join table
 */
class TagRepository(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(TagRepository::class.java)

    suspend fun createTag(tag: Tag): String = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO tags (id, user_id, name, color, usage_count, created_at)
                VALUES (?, ?, lower(?), ?, ?, now())
                ON CONFLICT (user_id, lower(name)) DO UPDATE SET
                    color = EXCLUDED.color,
                    usage_count = tags.usage_count + 1,
                    updated_at = now()
                RETURNING id
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(tag.id))
                stmt.setObject(2, UUID.fromString(tag.userId))
                stmt.setString(3, tag.name)
                stmt.setString(4, tag.color)
                stmt.setInt(5, tag.usageCount)
                val result = stmt.executeQuery()
                if (result.next()) result.getObject("id").toString() else tag.id
            }
        }
    }

    suspend fun getTagsForUser(userId: String): List<Tag> = withContext(Dispatchers.IO) {
        val tags = mutableListOf<Tag>()
        dataSource.connection.use { conn ->
            val sql = "SELECT * FROM tags WHERE user_id = ? ORDER BY name"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(userId))
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        tags.add(Tag(
                            id = rs.getObject("id").toString(),
                            userId = rs.getObject("user_id").toString(),
                            name = rs.getString("name"),
                            color = rs.getString("color"),
                            usageCount = rs.getInt("usage_count"),
                            createdAt = rs.getTimestamp("created_at")?.toString()
                        ))
                    }
                }
            }
        }
        tags
    }

    suspend fun getTagById(tagId: String): Tag? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "SELECT * FROM tags WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(tagId))
                stmt.executeQuery().use { rs ->
                    if (rs.next()) Tag(
                        id = rs.getObject("id").toString(),
                        userId = rs.getObject("user_id").toString(),
                        name = rs.getString("name"),
                        color = rs.getString("color"),
                        usageCount = rs.getInt("usage_count"),
                        createdAt = rs.getTimestamp("created_at")?.toString()
                    ) else null
                }
            }
        }
    }

    suspend fun deleteTag(tagId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "DELETE FROM tags WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(tagId))
                stmt.executeUpdate() > 0
            }
        }
    }

    suspend fun addTagToNote(noteId: String, tagId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "INSERT INTO note_tags (note_id, tag_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(noteId))
                stmt.setObject(2, UUID.fromString(tagId))
                stmt.executeUpdate() >= 0
            }
        }
    }

    suspend fun removeTagFromNote(noteId: String, tagId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "DELETE FROM note_tags WHERE note_id = ? AND tag_id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(noteId))
                stmt.setObject(2, UUID.fromString(tagId))
                stmt.executeUpdate() > 0
            }
        }
    }

    suspend fun getTagsForNote(noteId: String): List<Tag> = withContext(Dispatchers.IO) {
        val tags = mutableListOf<Tag>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT t.* FROM tags t
                JOIN note_tags nt ON t.id = nt.tag_id
                WHERE nt.note_id = ?
                ORDER BY t.name
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(noteId))
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        tags.add(Tag(
                            id = rs.getObject("id").toString(),
                            userId = rs.getObject("user_id").toString(),
                            name = rs.getString("name"),
                            color = rs.getString("color"),
                            usageCount = rs.getInt("usage_count"),
                            createdAt = rs.getTimestamp("created_at")?.toString()
                        ))
                    }
                }
            }
        }
        tags
    }
}

/**
 * Notifications Repository (v6.0.0 schema)
 * Handles: notifications table
 */
class NotificationRepository(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(NotificationRepository::class.java)

    suspend fun createNotification(notification: Notification): String = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO notifications (
                    id, user_id, type, title, body, data, is_read, created_at
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, now())
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(notification.id))
                stmt.setObject(2, UUID.fromString(notification.userId))
                stmt.setString(3, notification.type)
                stmt.setString(4, notification.title)
                stmt.setString(5, notification.body)
                stmt.setString(6, notification.data)
                stmt.setBoolean(7, notification.isRead)
                stmt.executeUpdate()
            }
        }
        notification.id
    }

    suspend fun getUnreadNotifications(userId: String, limit: Int = 50): List<Notification> = withContext(Dispatchers.IO) {
        val notifications = mutableListOf<Notification>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT * FROM notifications
                WHERE user_id = ? AND is_read = false
                ORDER BY created_at DESC
                LIMIT ?
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(userId))
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) notifications.add(rs.toNotification())
                }
            }
        }
        notifications
    }

    suspend fun getNotificationsForUser(userId: String, limit: Int = 100): List<Notification> = withContext(Dispatchers.IO) {
        val notifications = mutableListOf<Notification>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT * FROM notifications
                WHERE user_id = ?
                ORDER BY created_at DESC
                LIMIT ?
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(userId))
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) notifications.add(rs.toNotification())
                }
            }
        }
        notifications
    }

    suspend fun markAsRead(notificationId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "UPDATE notifications SET is_read = true, read_at = now() WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(notificationId))
                stmt.executeUpdate() > 0
            }
        }
    }

    suspend fun markAllAsRead(userId: String): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "UPDATE notifications SET is_read = true, read_at = now() WHERE user_id = ? AND is_read = false"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(userId))
                stmt.executeUpdate()
            }
        }
    }

    suspend fun deleteNotification(notificationId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "DELETE FROM notifications WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(notificationId))
                stmt.executeUpdate() > 0
            }
        }
    }

    private fun ResultSet.toNotification(): Notification {
        return Notification(
            id = getObject("id").toString(),
            userId = getObject("user_id").toString(),
            type = getString("type"),
            title = getString("title"),
            body = getString("body"),
            data = getString("data") ?: "{}",
            isRead = getBoolean("is_read"),
            readAt = getTimestamp("read_at")?.toString(),
            createdAt = getTimestamp("created_at")?.toString()
        )
    }
}

/**
 * Chat Folders Repository (v6.0.0 schema)
 * Handles: chat_folders table
 */
class ChatFolderRepository(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(ChatFolderRepository::class.java)

    suspend fun createFolder(folder: ChatFolder): String = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO chat_folders (id, user_id, name, color, sort_order, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, now(), now())
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(folder.id))
                stmt.setObject(2, UUID.fromString(folder.userId))
                stmt.setString(3, folder.name)
                stmt.setString(4, folder.color)
                stmt.setInt(5, folder.sortOrder)
                stmt.executeUpdate()
            }
        }
        folder.id
    }

    suspend fun getFoldersForUser(userId: String): List<ChatFolder> = withContext(Dispatchers.IO) {
        val folders = mutableListOf<ChatFolder>()
        dataSource.connection.use { conn ->
            val sql = "SELECT * FROM chat_folders WHERE user_id = ? ORDER BY sort_order, name"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(userId))
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        folders.add(ChatFolder(
                            id = rs.getObject("id").toString(),
                            userId = rs.getObject("user_id").toString(),
                            name = rs.getString("name"),
                            color = rs.getString("color"),
                            sortOrder = rs.getInt("sort_order"),
                            createdAt = rs.getTimestamp("created_at")?.toString(),
                            updatedAt = rs.getTimestamp("updated_at")?.toString()
                        ))
                    }
                }
            }
        }
        folders
    }

    suspend fun updateFolder(folder: ChatFolder): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                UPDATE chat_folders SET
                    name = ?, color = ?, sort_order = ?, updated_at = now()
                WHERE id = ?
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, folder.name)
                stmt.setString(2, folder.color)
                stmt.setInt(3, folder.sortOrder)
                stmt.setObject(4, UUID.fromString(folder.id))
                stmt.executeUpdate() > 0
            }
        }
    }

    suspend fun deleteFolder(folderId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "DELETE FROM chat_folders WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(folderId))
                stmt.executeUpdate() > 0
            }
        }
    }
}
