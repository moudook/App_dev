package com.example.smarty.server.data

import com.example.smarty.protocol.NoteInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

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
    suspend fun createWithDuplicateStatus(
        userId: String,
        info: NoteInfo,
        connection: Connection? = null,
    ): Pair<String, Boolean> =
        withContext(Dispatchers.IO) {
            val existingNoteId = deduplicationManager.findDuplicateNote(userId, info.content, info.title)
            if (existingNoteId != null) {
                logger.info("Duplicate note detected: returning existing note id={} for user={}", existingNoteId, userId)
                return@withContext Pair(existingNoteId, true)
            }

            val id = if (info.id.isNotEmpty()) UUID.fromString(info.id) else UUID.randomUUID()
            val closeConn = connection == null
            val conn = connection ?: dataSource.connection
            try {
                val sql =
                    """
                    INSERT INTO notes (
                        id, user_id, category_id, stack_id, parent_note_id,
                        title, content, summary, source_url, image_uri, file_uri,
                        file_name, file_mime_type, file_size, type, category_name,
                        why_saved, processing_status, content_hash, processed_content_hash,
                        is_archived, is_pinned, is_favorite, is_full_privacy, exclude_from_ai_chat,
                        is_ai_created, is_viewed, todo_content, attachments_json,
                        tags_json, chunk_analyses_json, reminder_text, reminder_expires_at,
                        metadata, word_count, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, ?, now(), now())
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
                    stmt.setString(idx++, info.contentHash)
                    stmt.setString(idx++, info.processedContentHash)
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
                    stmt.setString(idx++, info.metadata ?: "{}")
                    stmt.setObject(idx++, info.wordCount)
                    stmt.executeUpdate()
                }
            } finally {
                if (closeConn) conn.close()
            }
            Pair(id.toString(), false)
        }

    suspend fun create(
        userId: String,
        info: NoteInfo,
        connection: Connection? = null,
    ): String = createWithDuplicateStatus(userId, info, connection).first

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
                getByIdWithConn(conn, userId, noteId)
            }
        }

    private suspend fun getByIdWithConn(
        conn: Connection,
        userId: String,
        noteId: String,
    ): NoteInfo? {
        val sql = "SELECT * FROM notes WHERE id = ? AND user_id = ? AND deleted_at IS NULL"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, UUID.fromString(noteId))
            stmt.setObject(2, UUID.fromString(userId))
            stmt.executeQuery().use { rs ->
                if (rs.next()) mapRowToNoteInfo(rs) else null
            }
        }
    }

    private val noteVersionRepo = NoteVersionRepository(dataSource)

    suspend fun update(
        userId: String,
        info: NoteInfo,
        connection: Connection? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val closeConn = connection == null
            val conn = connection ?: dataSource.connection
            try {
                val existingNote =
                    if (connection != null) {
                        getByIdWithConn(connection, userId, info.id)
                    } else {
                        getById(userId, info.id)
                    }
                val contentChanged = existingNote != null && existingNote.content != info.content

                if (contentChanged) {
                    val latestVersionNo = noteVersionRepo.getVersionsForNote(info.id, 1, connection).firstOrNull()?.versionNo ?: 0
                    noteVersionRepo.createVersion(
                        version =
                            com.example.smarty.server.data.NoteVersion(
                                id = java.util.UUID.randomUUID().toString(),
                                noteId = info.id,
                                title = existingNote!!.title,
                                content = existingNote.content,
                                versionNo = latestVersionNo + 1,
                                createdAt = null,
                            ),
                        connection = connection,
                    )
                    noteVersionRepo.deleteOldVersions(info.id, keepCount = 10, connection = connection)
                    logger.info("Created version snapshot for note ${info.id} (version ${latestVersionNo + 1})")
                }

                val sql =
                    """
                    UPDATE notes SET
                        title = ?, content = ?, summary = ?, source_url = ?,
                        image_uri = ?, file_uri = ?, file_name = ?, file_mime_type = ?,
                        file_size = ?, type = ?, category_id = ?, category_name = ?,
                        stack_id = ?, parent_note_id = ?, why_saved = ?,
                        processing_status = ?, content_hash = ?, processed_content_hash = ?,
                        is_archived = ?, is_pinned = ?, is_favorite = ?,
                        is_full_privacy = ?, exclude_from_ai_chat = ?,
                        is_ai_created = ?, is_viewed = ?, todo_content = ?,
                        attachments_json = ?::jsonb, tags_json = ?::jsonb,
                        chunk_analyses_json = ?::jsonb, reminder_text = ?,
                        reminder_expires_at = ?, metadata = ?::jsonb, word_count = ?,
                        updated_at = now()
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
                    stmt.setObject(idx++, info.categoryId?.let { UUID.fromString(it) })
                    stmt.setString(idx++, info.categoryName)
                    stmt.setObject(idx++, info.stackId?.let { UUID.fromString(it) })
                    stmt.setObject(idx++, info.parentNoteId?.let { UUID.fromString(it) })
                    stmt.setString(idx++, info.whySaved)
                    stmt.setString(idx++, info.processingStatus)
                    stmt.setString(idx++, info.contentHash)
                    stmt.setString(idx++, info.processedContentHash)
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
                    stmt.setString(idx++, info.metadata ?: "{}")
                    stmt.setObject(idx++, info.wordCount)
                    stmt.setObject(idx++, UUID.fromString(info.id))
                    stmt.setObject(idx, UUID.fromString(userId))
                    stmt.executeUpdate() > 0
                }
            } finally {
                if (closeConn) conn.close()
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
            contentHash = rs.getString("content_hash"),
            processedContentHash = rs.getString("processed_content_hash"),
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
            metadata = rs.getString("metadata"),
            createdAt = rs.getTimestamp("created_at").time,
            updatedAt = rs.getTimestamp("updated_at").time,
        )
    }

    suspend fun delete(
        userId: String,
        noteId: String,
    ): Boolean =
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

    suspend fun search(
        userId: String,
        query: String,
        limit: Int = 20,
    ): List<NoteInfo> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<NoteInfo>()
            dataSource.connection.use { conn ->
                val sql =
                    """
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
    ): String =
        create(
            userId,
            NoteInfo(
                id = "",
                title = title,
                content = content,
                categoryId = categoryId,
                processingStatus = "COMPLETED",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
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
        val updated =
            existing.copy(
                title = title ?: existing.title,
                content = content ?: existing.content,
                categoryId = categoryId ?: existing.categoryId,
                updatedAt = System.currentTimeMillis(),
            )
        return update(userId, updated)
    }

    // ==================== PHASE 7: Batch Operations ====================

    /**
     * Batch upsert notes: INSERT OR UPDATE by ID.
     * Used for sync operations - idempotent.
     * @return List of note IDs that were created or updated
     */
    suspend fun batchUpsert(
        userId: String,
        notes: List<NoteInfo>,
    ): List<String> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<String>()
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    val sql =
                        """
                        INSERT INTO notes (
                            id, user_id, category_id, stack_id, parent_note_id,
                            title, content, summary, source_url, image_uri, file_uri,
                            file_name, file_mime_type, file_size, type, category_name,
                            why_saved, processing_status, content_hash, processed_content_hash,
                            is_archived, is_pinned, is_favorite, is_full_privacy, exclude_from_ai_chat,
                            is_ai_created, is_viewed, todo_content, attachments_json,
                            tags_json, chunk_analyses_json, reminder_text, reminder_expires_at,
                            metadata, word_count, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, ?, now(), now())
                        ON CONFLICT (id) DO UPDATE SET
                            title = EXCLUDED.title, content = EXCLUDED.content, summary = EXCLUDED.summary,
                            source_url = EXCLUDED.source_url, image_uri = EXCLUDED.image_uri,
                            file_uri = EXCLUDED.file_uri, file_name = EXCLUDED.file_name,
                            file_mime_type = EXCLUDED.file_mime_type, file_size = EXCLUDED.file_size,
                            type = EXCLUDED.type, category_name = EXCLUDED.category_name,
                            why_saved = EXCLUDED.why_saved, processing_status = EXCLUDED.processing_status,
                            content_hash = EXCLUDED.content_hash, processed_content_hash = EXCLUDED.processed_content_hash,
                            is_archived = EXCLUDED.is_archived, is_pinned = EXCLUDED.is_pinned,
                            is_favorite = EXCLUDED.is_favorite, is_full_privacy = EXCLUDED.is_full_privacy,
                            exclude_from_ai_chat = EXCLUDED.exclude_from_ai_chat,
                            is_ai_created = EXCLUDED.is_ai_created, is_viewed = EXCLUDED.is_viewed,
                            todo_content = EXCLUDED.todo_content, attachments_json = EXCLUDED.attachments_json,
                            tags_json = EXCLUDED.tags_json, chunk_analyses_json = EXCLUDED.chunk_analyses_json,
                            reminder_text = EXCLUDED.reminder_text, reminder_expires_at = EXCLUDED.reminder_expires_at,
                            metadata = EXCLUDED.metadata, word_count = EXCLUDED.word_count,
                            updated_at = now()
                        WHERE notes.user_id = EXCLUDED.user_id
                        """.trimIndent()

                    conn.prepareStatement(sql).use { stmt ->
                        notes.forEach { info ->
                            var idx = 1
                            stmt.setObject(idx++, UUID.fromString(info.id))
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
                            stmt.setString(idx++, info.contentHash)
                            stmt.setString(idx++, info.processedContentHash)
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
                            stmt.setString(idx++, info.metadata ?: "{}")
                            stmt.setObject(idx++, info.wordCount)
                            stmt.addBatch()
                            results.add(info.id)
                        }
                        stmt.executeBatch()
                    }
                    conn.commit()
                } catch (e: Exception) {
                    conn.rollback()
                    logger.error("Batch upsert failed for user $userId", e)
                    throw e
                } finally {
                    conn.autoCommit = true
                }
            }
            results
        }

    /**
     * Batch delete notes by ID (soft delete).
     * @return Number of notes deleted
     */
    suspend fun batchDelete(
        userId: String,
        noteIds: List<String>,
    ): Int =
        withContext(Dispatchers.IO) {
            if (noteIds.isEmpty()) return@withContext 0
            dataSource.connection.use { conn ->
                val placeholders = noteIds.joinToString(",") { "?" }
                val sql = "UPDATE notes SET deleted_at = now(), updated_at = now() WHERE id IN ($placeholders) AND user_id = ? AND deleted_at IS NULL"
                conn.prepareStatement(sql).use { stmt ->
                    noteIds.forEachIndexed { index, id ->
                        stmt.setObject(index + 1, UUID.fromString(id))
                    }
                    stmt.setObject(noteIds.size + 1, UUID.fromString(userId))
                    stmt.executeUpdate()
                }
            }
        }
}
