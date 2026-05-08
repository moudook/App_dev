package com.example.smarty.server.data

import com.example.smarty.protocol.NoteInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID
import javax.sql.DataSource
import java.sql.ResultSet

/**
 * Server-side repository for notes.
 * PostgreSQL is the source of truth; Android caches via StateSync events.
 */
class NoteRepository(
    private val dataSource: DataSource,
    private val chatMessageNotesRepo: ChatMessageNotesRepository,
    private val calendarEventNotesRepo: CalendarEventNotesRepository,
) {
    private val logger = LoggerFactory.getLogger(NoteRepository::class.java)
    private val deduplicationManager = NoteDeduplicationManager(dataSource)

    /**
     * Create a new note with all fields.
     */
    suspend fun create(
        userId: String,
        info: NoteInfo,
    ): String =
        withContext(Dispatchers.IO) {
            // CHECK FOR DUPLICATES FIRST
            val existingNoteId = deduplicationManager.findDuplicateNote(userId, info.content, info.title)
            if (existingNoteId != null) {
                logger.info("Duplicate note detected: returning existing note id={} for user={}", existingNoteId, userId)
                return@withContext existingNoteId
            }

            val id = if (info.id.isNotEmpty()) UUID.fromString(info.id) else UUID.randomUUID()
            dataSource.connection.use { conn ->
                val sql =
                    """
                    INSERT INTO notes (
                        id, user_id, category_id, stack_id, parent_note_id,
                        title, content, summary, source_url, image_uri, file_uri,
                        file_name, file_mime_type, file_size, type, category_name,
                        why_saved, processing_status, is_archived, is_pinned,
                        is_favorite, is_full_privacy, exclude_from_ai_chat,
                        is_ai_created, is_viewed, todo_content, attachments_json,
                        tags_json, chunk_analyses_json, reminder_text, reminder_expires_at,
                        metadata, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, now(), now())
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    var idx = 1
                    stmt.setObject(idx++, id)
                    stmt.setObject(idx++, UUID.fromString(userId))
                    stmt.setObject(idx++, info.categoryId?.let { UUID.fromString(it) })
                    stmt.setObject(idx++, info.stackId?.let { UUID.fromString(it) })
                    stmt.setObject(idx++, info.parentNoteId?.let { UUID.fromString(it) })
                    stmt.setString(idx++, info.title)
                    stmt.setString(idx++, info.content)
                    stmt.setString(idx++, info.summary)
                    stmt.setString(idx++, info.sourceUrl)
                    stmt.setString(idx++, info.imageUri)
                    stmt.setString(idx++, info.fileUri)
                    stmt.setString(idx++, info.fileName)
                    stmt.setString(idx++, info.fileMimeType)
                    stmt.setObject(idx++, info.fileSize)
                    stmt.setString(idx++, info.type)
                    stmt.setString(idx++, info.categoryName)
                    stmt.setString(idx++, info.whySaved)
                    stmt.setString(idx++, info.processingStatus)
                    stmt.setBoolean(idx++, info.isArchived)
                    stmt.setBoolean(idx++, info.isPinned)
                    stmt.setBoolean(idx++, info.isFavorite)
                    stmt.setBoolean(idx++, info.isFullPrivacy)
                    stmt.setBoolean(idx++, info.excludeFromAiChat)
                    stmt.setBoolean(idx++, info.isAiCreated)
                    stmt.setBoolean(idx++, info.isViewed)
                    stmt.setString(idx++, info.todoContent)
                    stmt.setString(idx++, info.attachmentsJson ?: "[]")
                    stmt.setString(idx++, info.tagsJson ?: "[]")
                    stmt.setString(idx++, info.chunkAnalysesJson ?: "[]")
                    stmt.setString(idx++, info.reminderText)
                    stmt.setTimestamp(idx++, info.reminderExpiresAt?.let { java.sql.Timestamp(it) })
                    stmt.setString(idx++, "{}")
                    stmt.executeUpdate()
                }
            }
            id.toString()
        }

    suspend fun listByUser(
        userId: String,
        limit: Int = 50,
    ): List<NoteInfo> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<NoteInfo>()
            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM notes WHERE user_id = ?::uuid AND deleted_at IS NULL ORDER BY updated_at DESC LIMIT ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.setInt(2, limit)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(mapRowToNoteInfo(rs))
                        }
                    }
                }
            }
            results
        }

    suspend fun listByUserUpdatedAfter(
        userId: String,
        timestamp: Long,
        limit: Int = 100,
    ): List<NoteInfo> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<NoteInfo>()
            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM notes WHERE user_id = ?::uuid AND deleted_at IS NULL AND updated_at > ? ORDER BY updated_at ASC LIMIT ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.setTimestamp(2, java.sql.Timestamp(timestamp))
                    stmt.setInt(3, limit)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(mapRowToNoteInfo(rs))
                        }
                    }
                }
            }
            results
        }

    suspend fun getById(
        userId: String,
        noteId: String,
    ): NoteInfo? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM notes WHERE id = ? AND user_id = ? AND deleted_at IS NULL"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(noteId))
                    stmt.setObject(2, UUID.fromString(userId))
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) mapRowToNoteInfo(rs) else null
                    }
                }
            }
        }

    suspend fun update(
        userId: String,
        info: NoteInfo,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    UPDATE notes SET
                        title = ?, content = ?, summary = ?, source_url = ?,
                        image_uri = ?, file_uri = ?, file_name = ?, file_mime_type = ?,
                        file_size = ?, type = ?, category_name = ?, why_saved = ?,
                        processing_status = ?, is_archived = ?, is_pinned = ?,
                        is_favorite = ?, is_full_privacy = ?, exclude_from_ai_chat = ?,
                        is_ai_created = ?, is_viewed = ?, todo_content = ?,
                        attachments_json = ?::jsonb, tags_json = ?::jsonb,
                        chunk_analyses_json = ?::jsonb, reminder_text = ?,
                        reminder_expires_at = ?, updated_at = now()
                    WHERE id = ? AND user_id = ? AND deleted_at IS NULL
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    var idx = 1
                    stmt.setString(idx++, info.title)
                    stmt.setString(idx++, info.content)
                    stmt.setString(idx++, info.summary)
                    stmt.setString(idx++, info.sourceUrl)
                    stmt.setString(idx++, info.imageUri)
                    stmt.setString(idx++, info.fileUri)
                    stmt.setString(idx++, info.fileName)
                    stmt.setString(idx++, info.fileMimeType)
                    stmt.setObject(idx++, info.fileSize)
                    stmt.setString(idx++, info.type)
                    stmt.setString(idx++, info.categoryName)
                    stmt.setString(idx++, info.whySaved)
                    stmt.setString(idx++, info.processingStatus)
                    stmt.setBoolean(idx++, info.isArchived)
                    stmt.setBoolean(idx++, info.isPinned)
                    stmt.setBoolean(idx++, info.isFavorite)
                    stmt.setBoolean(idx++, info.isFullPrivacy)
                    stmt.setBoolean(idx++, info.excludeFromAiChat)
                    stmt.setBoolean(idx++, info.isAiCreated)
                    stmt.setBoolean(idx++, info.isViewed)
                    stmt.setString(idx++, info.todoContent)
                    stmt.setString(idx++, info.attachmentsJson ?: "[]")
                    stmt.setString(idx++, info.tagsJson ?: "[]")
                    stmt.setString(idx++, info.chunkAnalysesJson ?: "[]")
                    stmt.setString(idx++, info.reminderText)
                    stmt.setTimestamp(idx++, info.reminderExpiresAt?.let { java.sql.Timestamp(it) })
                    stmt.setObject(idx++, UUID.fromString(info.id))
                    stmt.setObject(idx, UUID.fromString(userId))
                    stmt.executeUpdate() > 0
                }
            }
        }

    private fun mapRowToNoteInfo(rs: ResultSet): NoteInfo {
        return NoteInfo(
            id = rs.getString("id"),
            title = rs.getString("title"),
            content = rs.getString("content"),
            summary = rs.getString("summary"),
            sourceUrl = rs.getString("source_url"),
            imageUri = rs.getString("image_uri"),
            fileUri = rs.getString("file_uri"),
            fileName = rs.getString("file_name"),
            fileMimeType = rs.getString("file_mime_type"),
            fileSize = rs.getLong("file_size").takeIf { !rs.wasNull() },
            type = rs.getString("type") ?: "BRAIN_DUMP",
            categoryId = rs.getObject("category_id")?.toString(),
            categoryName = rs.getString("category_name"),
            stackId = rs.getObject("stack_id")?.toString(),
            parentNoteId = rs.getObject("parent_note_id")?.toString(),
            whySaved = rs.getString("why_saved"),
            processingStatus = rs.getString("processing_status") ?: "COMPLETED",
            wordCount = rs.getInt("word_count").takeIf { !rs.wasNull() },
            isArchived = rs.getBoolean("is_archived"),
            isPinned = rs.getBoolean("is_pinned"),
            isFavorite = rs.getBoolean("is_favorite"),
            isFullPrivacy = rs.getBoolean("is_full_privacy"),
            excludeFromAiChat = rs.getBoolean("exclude_from_ai_chat"),
            isAiCreated = rs.getBoolean("is_ai_created"),
            isViewed = rs.getBoolean("is_viewed"),
            todoContent = rs.getString("todo_content"),
            attachmentsJson = rs.getString("attachments_json"),
            tagsJson = rs.getString("tags_json"),
            chunkAnalysesJson = rs.getString("chunk_analyses_json"),
            reminderText = rs.getString("reminder_text"),
            reminderExpiresAt = rs.getTimestamp("reminder_expires_at")?.time,
            createdAt = rs.getTimestamp("created_at").time,
            updatedAt = rs.getTimestamp("updated_at").time,
        )
    }

    suspend fun delete(userId: String, noteId: String): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "UPDATE notes SET deleted_at = now(), updated_at = now() WHERE id = ? AND user_id = ? AND deleted_at IS NULL"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(noteId))
                    stmt.setObject(2, UUID.fromString(userId))
                    stmt.executeUpdate() > 0
                }
            }
        }

    suspend fun search(userId: String, query: String, limit: Int = 20): List<NoteInfo> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<NoteInfo>()
            dataSource.connection.use { conn ->
                val sql = """
                    SELECT * FROM notes 
                    WHERE user_id = ?::uuid 
                    AND deleted_at IS NULL 
                    AND (
                        title ILIKE ? 
                        OR content ILIKE ?
                        OR summary ILIKE ?
                        OR tags_json LIKE ?
                    )
                    ORDER BY updated_at DESC 
                    LIMIT ?
                """.trimIndent()
                val searchPattern = "%$query%"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.setString(2, searchPattern)
                    stmt.setString(3, searchPattern)
                    stmt.setString(4, searchPattern)
                    stmt.setString(5, searchPattern)
                    stmt.setInt(6, limit)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(mapRowToNoteInfo(rs))
                        }
                    }
                }
            }
            results
        }

    /**
     * Backward-compatible overload: create note with individual fields.
     */
    suspend fun create(
        userId: String,
        title: String,
        content: String,
        categoryId: String? = null,
    ): String = create(
        userId,
        NoteInfo(
            id = "",
            title = title,
            content = content,
            categoryId = categoryId,
            processingStatus = "COMPLETED",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
    )

    /**
     * Backward-compatible overload: update note with individual fields.
     * Fetches existing note, updates provided fields, then persists.
     */
    suspend fun update(
        userId: String,
        id: String,
        title: String?,
        content: String?,
        categoryId: String?,
    ): Boolean {
        val existing = getById(userId, id) ?: return false
        val updated = existing.copy(
            title = title ?: existing.title,
            content = content ?: existing.content,
            categoryId = categoryId ?: existing.categoryId,
            updatedAt = System.currentTimeMillis()
        )
        return update(userId, updated)
    }
}
