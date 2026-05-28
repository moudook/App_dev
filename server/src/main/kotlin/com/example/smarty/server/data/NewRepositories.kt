package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

/**
 * Tasks Repository (v6.0.0 schema)
 * Handles: tasks table
 */
class TaskRepository(
    private val dataSource: DataSource,
) {
    private val logger = LoggerFactory.getLogger(TaskRepository::class.java)

    suspend fun createTask(task: Task): String =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
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

    suspend fun getTasksForUser(
        userId: String,
        status: String? = null,
        limit: Int = 100,
    ): List<Task> =
        withContext(Dispatchers.IO) {
            val tasks = mutableListOf<Task>()
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT * FROM tasks
                    WHERE user_id = ? AND deleted_at IS NULL
                    ${if (status != null) "AND status = ?" else ""}
                    ORDER BY sort_order, due_date ASC NULLS LAST, created_at DESC
                    LIMIT ?
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    if (status != null) {
                        stmt.setString(2, status)
                        stmt.setInt(3, limit)
                    } else {
                        stmt.setInt(2, limit)
                    }
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) tasks.add(rs.toTask())
                    }
                }
            }
            tasks
        }

    suspend fun getTaskById(taskId: String): Task? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM tasks WHERE id = ? AND deleted_at IS NULL"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(taskId))
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toTask() else null }
                }
            }
        }

    suspend fun updateTaskStatus(
        taskId: String,
        status: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
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

    suspend fun updateTask(task: Task): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
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

    suspend fun deleteTask(taskId: String): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "UPDATE tasks SET deleted_at = now(), updated_at = now() WHERE id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(taskId))
                    stmt.executeUpdate() > 0
                }
            }
        }

    suspend fun getTasksForSession(sessionId: String, limit: Int = 100): List<Task> =
        withContext(Dispatchers.IO) {
            val tasks = mutableListOf<Task>()
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT * FROM tasks
                    WHERE session_id = ? AND deleted_at IS NULL
                    ORDER BY sort_order, due_date ASC NULLS LAST
                    LIMIT ?
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(sessionId))
                    stmt.setInt(2, limit)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) tasks.add(rs.toTask())
                    }
                }
            }
            tasks
        }

    private fun ResultSet.toTask(): Task =
        Task(
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
            deletedAt = getTimestamp("deleted_at")?.toString(),
        )
}

/**
 * Tags Repository (v6.0.0 schema)
 * Handles: tags table, note_tags join table
 */
class TagRepository(
    private val dataSource: DataSource,
) {
    private val logger = LoggerFactory.getLogger(TagRepository::class.java)

    suspend fun createTag(tag: Tag): String =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    INSERT INTO tags (id, user_id, name, color, tag_type, confidence_score, usage_count, created_at, updated_at)
                    VALUES (?, ?, lower(?), ?, ?, ?, ?, now(), now())
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
                    stmt.setString(5, tag.tagType)
                    stmt.setDouble(6, tag.confidenceScore)
                    stmt.setInt(7, tag.usageCount)
                    val result = stmt.executeQuery()
                    if (result.next()) result.getObject("id").toString() else tag.id
                }
            }
        }

    suspend fun getTagsForUser(userId: String, limit: Int = 100): List<Tag> =
        withContext(Dispatchers.IO) {
            val tags = mutableListOf<Tag>()
            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM tags WHERE user_id = ? ORDER BY usage_count DESC, name LIMIT ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.setInt(2, limit)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) tags.add(rs.toTag())
                    }
                }
            }
            tags
        }

    suspend fun getTagById(tagId: String): Tag? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM tags WHERE id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(tagId))
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toTag() else null }
                }
            }
        }

    suspend fun updateTag(tag: Tag): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    UPDATE tags SET
                        name = lower(?), color = ?, tag_type = ?, confidence_score = ?, updated_at = now()
                    WHERE id = ?
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, tag.name)
                    stmt.setString(2, tag.color)
                    stmt.setString(3, tag.tagType)
                    stmt.setDouble(4, tag.confidenceScore)
                    stmt.setObject(5, UUID.fromString(tag.id))
                    stmt.executeUpdate() > 0
                }
            }
        }

    suspend fun getNotesForTag(tagId: String, limit: Int = 100): List<NoteForTag> =
        withContext(Dispatchers.IO) {
            val notes = mutableListOf<NoteForTag>()
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT n.id, n.title, n.content, n.summary, n.type, n.category_id,
                           n.created_at, n.updated_at, n.pinned, n.archived, n.tags_json
                    FROM notes n
                    JOIN note_tags nt ON n.id = nt.note_id
                    WHERE nt.tag_id = ? AND n.deleted_at IS NULL
                    ORDER BY n.created_at DESC
                    LIMIT ?
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(tagId))
                    stmt.setInt(2, limit)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            notes.add(
                                NoteForTag(
                                    id = rs.getObject("id").toString(),
                                    title = rs.getString("title"),
                                    content = rs.getString("content"),
                                    summary = rs.getString("summary"),
                                    type = rs.getString("type"),
                                    categoryId = rs.getObject("category_id")?.toString(),
                                    createdAt = rs.getTimestamp("created_at")?.toString(),
                                    updatedAt = rs.getTimestamp("updated_at")?.toString(),
                                    pinned = rs.getBoolean("pinned"),
                                    archived = rs.getBoolean("archived"),
                                    tagsJson = rs.getString("tags_json"),
                                ),
                            )
                        }
                    }
                }
            }
            notes
        }

    suspend fun addTagToNote(
        noteId: String,
        tagId: String,
        userId: String,
        assignedBy: String = "user",
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    INSERT INTO note_tags (note_id, tag_id, user_id, assigned_by, confidence_score, created_at)
                    VALUES (?, ?, ?, ?, ?, now())
                    ON CONFLICT (note_id, tag_id) DO NOTHING
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(noteId))
                    stmt.setObject(2, UUID.fromString(tagId))
                    stmt.setObject(3, UUID.fromString(userId))
                    stmt.setString(4, assignedBy)
                    stmt.setDouble(5, 1.0)
                    stmt.executeUpdate() >= 0
                }
            }
        }

    suspend fun removeTagFromNote(
        noteId: String,
        tagId: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "DELETE FROM note_tags WHERE note_id = ? AND tag_id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(noteId))
                    stmt.setObject(2, UUID.fromString(tagId))
                    stmt.executeUpdate() > 0
                }
            }
        }

    suspend fun getTagsForNote(noteId: String): List<Tag> =
        withContext(Dispatchers.IO) {
            val tags = mutableListOf<Tag>()
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT t.* FROM tags t
                    JOIN note_tags nt ON t.id = nt.tag_id
                    WHERE nt.note_id = ?
                    ORDER BY t.usage_count DESC, t.name
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(noteId))
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) tags.add(rs.toTag())
                    }
                }
            }
            tags
        }

    suspend fun deleteTag(tagId: String): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement("DELETE FROM note_tags WHERE tag_id = ?").use { stmt ->
                        stmt.setObject(1, UUID.fromString(tagId))
                        stmt.executeUpdate()
                    }
                    conn.prepareStatement("DELETE FROM tags WHERE id = ?").use { stmt ->
                        stmt.setObject(1, UUID.fromString(tagId))
                        val result = stmt.executeUpdate() > 0
                        conn.commit()
                        result
                    }
                } catch (e: Exception) {
                    conn.rollback()
                    logger.error("Failed to delete tag $tagId: ${e.message}")
                    false
                } finally {
                    conn.autoCommit = true
                }
            }
        }

    private fun ResultSet.toTag(): Tag =
        Tag(
            id = getObject("id").toString(),
            userId = getObject("user_id").toString(),
            name = getString("name"),
            color = getString("color"),
            tagType = getString("tag_type"),
            confidenceScore = getDouble("confidence_score"),
            usageCount = getInt("usage_count"),
            createdAt = getTimestamp("created_at")?.toString(),
            updatedAt = getTimestamp("updated_at")?.toString(),
        )
}

/**
 * Notifications Repository (v6.0.0 schema)
 * Handles: notifications table
 */
class NotificationRepository(
    private val dataSource: DataSource,
) {
    private val logger = LoggerFactory.getLogger(NotificationRepository::class.java)

    suspend fun createNotification(notification: Notification): String =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
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

    suspend fun getUnreadNotifications(
        userId: String,
        limit: Int = 50,
    ): List<Notification> =
        withContext(Dispatchers.IO) {
            val notifications = mutableListOf<Notification>()
            dataSource.connection.use { conn ->
                val sql =
                    """
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

    suspend fun getNotificationsForUser(
        userId: String,
        limit: Int = 100,
    ): List<Notification> =
        withContext(Dispatchers.IO) {
            val notifications = mutableListOf<Notification>()
            dataSource.connection.use { conn ->
                val sql =
                    """
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

    suspend fun markAsRead(notificationId: String): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "UPDATE notifications SET is_read = true, read_at = now() WHERE id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(notificationId))
                    stmt.executeUpdate() > 0
                }
            }
        }

    suspend fun markAllAsRead(userId: String): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "UPDATE notifications SET is_read = true, read_at = now() WHERE user_id = ? AND is_read = false"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.executeUpdate()
                }
            }
        }

    suspend fun deleteNotification(notificationId: String): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "DELETE FROM notifications WHERE id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(notificationId))
                    stmt.executeUpdate() > 0
                }
            }
        }

    private fun ResultSet.toNotification(): Notification =
        Notification(
            id = getObject("id").toString(),
            userId = getObject("user_id").toString(),
            type = getString("type"),
            title = getString("title"),
            body = getString("body"),
            data = getString("data") ?: "{}",
            isRead = getBoolean("is_read"),
            readAt = getTimestamp("read_at")?.toString(),
            createdAt = getTimestamp("created_at")?.toString(),
        )
}

/**
 * Chat Folders Repository (v6.0.0 schema)
 * Handles: chat_folders table
 */
class ChatFolderRepository(
    private val dataSource: DataSource,
) {
    private val logger = LoggerFactory.getLogger(ChatFolderRepository::class.java)

    suspend fun createFolder(folder: ChatFolder): String =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
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

    suspend fun getFoldersForUser(userId: String, limit: Int = 50): List<ChatFolder> =
        withContext(Dispatchers.IO) {
            val folders = mutableListOf<ChatFolder>()
            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM chat_folders WHERE user_id = ? ORDER BY sort_order, name LIMIT ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.setInt(2, limit)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            folders.add(
                                ChatFolder(
                                    id = rs.getObject("id").toString(),
                                    userId = rs.getObject("user_id").toString(),
                                    name = rs.getString("name"),
                                    color = rs.getString("color"),
                                    sortOrder = rs.getInt("sort_order"),
                                    createdAt = rs.getTimestamp("created_at")?.toString(),
                                    updatedAt = rs.getTimestamp("updated_at")?.toString(),
                                ),
                            )
                        }
                    }
                }
            }
            folders
        }

    suspend fun updateFolder(folder: ChatFolder): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
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

    suspend fun deleteFolder(folderId: String): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "DELETE FROM chat_folders WHERE id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(folderId))
                    stmt.executeUpdate() > 0
                }
            }
        }
}

/**
 * Search History Repository (v6.0.0 schema)
 * Handles: search_history table
 */
class SearchHistoryRepository(
    private val dataSource: DataSource,
) {
    private val logger = LoggerFactory.getLogger(SearchHistoryRepository::class.java)

    suspend fun addSearch(search: SearchHistory): String =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    INSERT INTO search_history (id, user_id, query, search_scope, result_count, created_at)
                    VALUES (?, ?, ?, ?, ?, now())
                    ON CONFLICT DO NOTHING
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(search.id))
                    stmt.setObject(2, UUID.fromString(search.userId))
                    stmt.setString(3, search.query)
                    stmt.setString(4, search.searchScope)
                    stmt.setInt(5, search.resultCount)
                    stmt.executeUpdate()
                }
            }
            search.id
        }

    suspend fun getSearchHistory(
        userId: String,
        limit: Int = 20,
    ): List<SearchHistory> =
        withContext(Dispatchers.IO) {
            val history = mutableListOf<SearchHistory>()
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT * FROM search_history
                    WHERE user_id = ?
                    ORDER BY created_at DESC
                    LIMIT ?
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.setInt(2, limit)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            history.add(
                                SearchHistory(
                                    id = rs.getObject("id").toString(),
                                    userId = rs.getObject("user_id").toString(),
                                    query = rs.getString("query"),
                                    searchScope = rs.getString("search_scope"),
                                    resultCount = rs.getInt("result_count"),
                                    createdAt = rs.getTimestamp("created_at")?.toString(),
                                ),
                            )
                        }
                    }
                }
            }
            history
        }

    suspend fun deleteSearch(searchId: String): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "DELETE FROM search_history WHERE id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(searchId))
                    stmt.executeUpdate() > 0
                }
            }
        }

    suspend fun clearUserSearchHistory(userId: String): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "DELETE FROM search_history WHERE user_id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.executeUpdate()
                }
            }
        }
}

/**
 * User Device Repository (v6.0.0 schema)
 * Handles: user_devices table
 */
class UserDeviceRepository(
    private val dataSource: DataSource,
) {
    private val logger = LoggerFactory.getLogger(UserDeviceRepository::class.java)

    suspend fun registerDevice(device: UserDevice): String =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    INSERT INTO user_devices (id, user_id, device_name, device_type, push_token, last_active_at, app_version, metadata, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, now(), ?, ?::jsonb, now(), now())
                    ON CONFLICT (user_id, device_name) DO UPDATE SET
                        push_token = EXCLUDED.push_token,
                        last_active_at = now(),
                        app_version = EXCLUDED.app_version,
                        metadata = EXCLUDED.metadata,
                        updated_at = now()
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(device.id))
                    stmt.setObject(2, UUID.fromString(device.userId))
                    stmt.setString(3, device.deviceName)
                    stmt.setString(4, device.deviceType)
                    stmt.setString(5, device.pushToken)
                    stmt.setString(6, device.appVersion)
                    stmt.setString(7, device.metadata)
                    stmt.executeUpdate()
                }
            }
            device.id
        }

    suspend fun getDevicesForUser(userId: String, limit: Int = 20): List<UserDevice> =
        withContext(Dispatchers.IO) {
            val devices = mutableListOf<UserDevice>()
            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM user_devices WHERE user_id = ? ORDER BY last_active_at DESC LIMIT ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.setInt(2, limit)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            devices.add(
                                UserDevice(
                                    id = rs.getObject("id").toString(),
                                    userId = rs.getObject("user_id").toString(),
                                    deviceName = rs.getString("device_name"),
                                    deviceType = rs.getString("device_type"),
                                    pushToken = rs.getString("push_token"),
                                    lastActiveAt = rs.getTimestamp("last_active_at")?.toString(),
                                    appVersion = rs.getString("app_version"),
                                    metadata = rs.getString("metadata") ?: "{}",
                                    createdAt = rs.getTimestamp("created_at")?.toString(),
                                    updatedAt = rs.getTimestamp("updated_at")?.toString(),
                                ),
                            )
                        }
                    }
                }
            }
            devices
        }

    suspend fun updatePushToken(
        deviceId: String,
        pushToken: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "UPDATE user_devices SET push_token = ?, updated_at = now() WHERE id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, pushToken)
                    stmt.setObject(2, UUID.fromString(deviceId))
                    stmt.executeUpdate() > 0
                }
            }
        }

    suspend fun updateLastActive(deviceId: String): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "UPDATE user_devices SET last_active_at = now() WHERE id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(deviceId))
                    stmt.executeUpdate() > 0
                }
            }
        }

    suspend fun deleteDevice(deviceId: String): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "DELETE FROM user_devices WHERE id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(deviceId))
                    stmt.executeUpdate() > 0
                }
            }
        }
}

/**
 * Note Version Repository (v6.0.0 schema)
 * Handles: note_versions table for note version history
 */
class NoteVersionRepository(
    private val dataSource: DataSource,
) {
    private val logger = LoggerFactory.getLogger(NoteVersionRepository::class.java)

    suspend fun createVersion(
        version: NoteVersion,
        connection: java.sql.Connection? = null,
    ): String =
        withContext(Dispatchers.IO) {
            val closeConn = connection == null
            val conn = connection ?: dataSource.connection
            try {
                val sql =
                    """
                    INSERT INTO note_versions (id, note_id, title, content, version_no, created_at)
                    VALUES (?, ?, ?, ?, ?, now())
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(version.id))
                    stmt.setObject(2, UUID.fromString(version.noteId))
                    stmt.setString(3, version.title)
                    stmt.setString(4, version.content)
                    stmt.setInt(5, version.versionNo)
                    stmt.executeUpdate()
                }
            } finally {
                if (closeConn) conn.close()
            }
            version.id
        }

    suspend fun getVersionsForNote(
        noteId: String,
        limit: Int = 50,
        connection: java.sql.Connection? = null,
    ): List<NoteVersion> =
        withContext(Dispatchers.IO) {
            val versions = mutableListOf<NoteVersion>()
            val closeConn = connection == null
            val conn = connection ?: dataSource.connection
            try {
                val sql =
                    """
                    SELECT * FROM note_versions
                    WHERE note_id = ?
                    ORDER BY version_no DESC
                    LIMIT ?
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(noteId))
                    stmt.setInt(2, limit)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            versions.add(
                                NoteVersion(
                                    id = rs.getObject("id").toString(),
                                    noteId = rs.getObject("note_id").toString(),
                                    title = rs.getString("title"),
                                    content = rs.getString("content"),
                                    versionNo = rs.getInt("version_no"),
                                    createdAt = rs.getTimestamp("created_at")?.toString(),
                                ),
                            )
                        }
                    }
                }
            } finally {
                if (closeConn) conn.close()
            }
            versions
        }

    suspend fun getVersionById(versionId: String): NoteVersion? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM note_versions WHERE id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(versionId))
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            NoteVersion(
                                id = rs.getObject("id").toString(),
                                noteId = rs.getObject("note_id").toString(),
                                title = rs.getString("title"),
                                content = rs.getString("content"),
                                versionNo = rs.getInt("version_no"),
                                createdAt = rs.getTimestamp("created_at")?.toString(),
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }

    suspend fun deleteOldVersions(
        noteId: String,
        keepCount: Int = 10,
        connection: java.sql.Connection? = null,
    ): Int =
        withContext(Dispatchers.IO) {
            val closeConn = connection == null
            val conn = connection ?: dataSource.connection
            try {
                val sql =
                    """
                    DELETE FROM note_versions
                    WHERE note_id = ? AND id NOT IN (
                        SELECT id FROM note_versions WHERE note_id = ?
                        ORDER BY version_no DESC LIMIT ?
                    )
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(noteId))
                    stmt.setObject(2, UUID.fromString(noteId))
                    stmt.setInt(3, keepCount)
                    stmt.executeUpdate()
                }
            } finally {
                if (closeConn) conn.close()
            }
        }
}

/**
 * Shared Items Repository (v6.0.0 schema)
 * Handles: shared_items table
 */
class SharedItemRepository(
    private val dataSource: DataSource,
) {
    private val logger = LoggerFactory.getLogger(SharedItemRepository::class.java)

    suspend fun createSharedItem(item: SharedItem): String =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    INSERT INTO shared_items (id, owner_id, shared_with_id, item_type, item_id, permission, share_token, expires_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(item.id))
                    stmt.setObject(2, UUID.fromString(item.ownerId))
                    stmt.setObject(3, item.sharedWithId?.let { UUID.fromString(it) })
                    stmt.setString(4, item.itemType)
                    stmt.setObject(5, UUID.fromString(item.itemId))
                    stmt.setString(6, item.permission)
                    stmt.setString(7, item.shareToken)
                    stmt.setTimestamp(8, item.expiresAt?.let { java.sql.Timestamp.valueOf(it.replace("Z", "")) })
                    stmt.executeUpdate()
                }
            }
            item.id
        }

    suspend fun getSharedItemsForUser(userId: String, limit: Int = 50): List<SharedItem> =
        withContext(Dispatchers.IO) {
            val items = mutableListOf<SharedItem>()
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT * FROM shared_items
                    WHERE owner_id = ? OR shared_with_id = ?
                    ORDER BY created_at DESC
                    LIMIT ?
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.setObject(2, UUID.fromString(userId))
                    stmt.setInt(3, limit)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            items.add(
                                SharedItem(
                                    id = rs.getObject("id").toString(),
                                    ownerId = rs.getObject("owner_id").toString(),
                                    sharedWithId = rs.getObject("shared_with_id")?.toString(),
                                    itemType = rs.getString("item_type"),
                                    itemId = rs.getObject("item_id").toString(),
                                    permission = rs.getString("permission"),
                                    shareToken = rs.getString("share_token"),
                                    expiresAt = rs.getTimestamp("expires_at")?.toString(),
                                    createdAt = rs.getTimestamp("created_at")?.toString(),
                                ),
                            )
                        }
                    }
                }
            }
            items
        }

    suspend fun getItemByShareToken(token: String): SharedItem? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM shared_items WHERE share_token = ? AND (expires_at IS NULL OR expires_at > now())"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, token)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            SharedItem(
                                id = rs.getObject("id").toString(),
                                ownerId = rs.getObject("owner_id").toString(),
                                sharedWithId = rs.getObject("shared_with_id")?.toString(),
                                itemType = rs.getString("item_type"),
                                itemId = rs.getObject("item_id").toString(),
                                permission = rs.getString("permission"),
                                shareToken = rs.getString("share_token"),
                                expiresAt = rs.getTimestamp("expires_at")?.toString(),
                                createdAt = rs.getTimestamp("created_at")?.toString(),
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }

    suspend fun deleteSharedItem(itemId: String): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "DELETE FROM shared_items WHERE id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(itemId))
                    stmt.executeUpdate() > 0
                }
            }
        }
}
