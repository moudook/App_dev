package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID
import javax.sql.DataSource
import com.example.smarty.protocol.NoteInfo

/**
 * Server-side repository for notes.
 * PostgreSQL is the source of truth; Android caches via StateSync events.
 *
 * DEDUPLICATION: Automatically detects and prevents duplicate notes.
 * - Checks for existing notes with identical content before creating
 * - Returns existing note ID if duplicate found
 * - Keeps oldest note, prevents newer duplicates
 * 
 * SINGLE RESPONSIBILITY: Only manages notes table.
 * Delegates relationship queries to junction repositories.
 * GLOBAL STATE: All tables reference users(firebase_uid) with cascade deletes.
 */
class NoteRepository(
    private val dataSource: DataSource,
    private val chatMessageNotesRepo: ChatMessageNotesRepository,
    private val calendarEventNotesRepo: CalendarEventNotesRepository
) {
    private val logger = LoggerFactory.getLogger(NoteRepository::class.java)
    private val deduplicationManager = NoteDeduplicationManager(dataSource)

    /**
     * Create a new note with automatic deduplication.
     * @return The UUID of the created note (or existing note if duplicate).
     * @return Existing note ID if duplicate content found
     */
    suspend fun create(
        userId: String,
        title: String,
        content: String,
        category: String? = null
    ): String = withContext(Dispatchers.IO) {
        // CHECK FOR DUPLICATES FIRST
        val existingNoteId = deduplicationManager.findDuplicateNote(userId, content, title)
        if (existingNoteId != null) {
            logger.info("Duplicate note detected: returning existing note id={} for user={}", existingNoteId, userId)
            return@withContext existingNoteId
        }
        
        // No duplicate found, create new note
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO notes (id, user_id, title, content, category)
                VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, id)
                stmt.setString(2, userId)
                stmt.setString(3, title)
                stmt.setString(4, content)
                stmt.setString(5, category)
                stmt.executeUpdate()
            }
        }
        logger.info("Note created: id={}, user={}, title={}", id, userId, title)
        id.toString()
    }

    /**
     * Search notes by keyword in title or content.
     */
    suspend fun search(userId: String, query: String, limit: Int = 10): List<NoteInfo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<NoteInfo>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT id, title, content, category, is_archived, created_at, updated_at
                FROM notes
                WHERE user_id = ? AND is_pinned = true
                ORDER BY updated_at DESC
                LIMIT ?
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, userId)
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        results.add(NoteInfo(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            content = rs.getString("content"),
                            category = rs.getString("category"),
                            isArchived = rs.getBoolean("is_archived"),
                            createdAt = rs.getTimestamp("created_at").time,
                            updatedAt = rs.getTimestamp("updated_at").time
                        ))
                    }
                }
            }
        }
        results
    }

    suspend fun listByUser(userId: String, limit: Int = 50): List<NoteInfo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<NoteInfo>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT id, title, content, category, is_archived, created_at, updated_at
                FROM notes
                WHERE user_id = ? AND NOT is_archived
                ORDER BY updated_at DESC
                LIMIT ?
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, userId)
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        results.add(NoteInfo(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            content = rs.getString("content"),
                            category = rs.getString("category"),
                            isArchived = rs.getBoolean("is_archived"),
                            createdAt = rs.getTimestamp("created_at").time,
                            updatedAt = rs.getTimestamp("updated_at").time
                        ))
                    }
                }
            }
        }
        results
    }

    /**
     * Gets a single note by ID.
     */
    suspend fun getById(userId: String, noteId: String): NoteInfo? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                SELECT id, title, content, category, is_archived, created_at, updated_at
                FROM notes
                WHERE id = ? AND user_id = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(noteId))
                stmt.setString(2, userId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        return@withContext NoteInfo(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            content = rs.getString("content"),
                            category = rs.getString("category"),
                            isArchived = rs.getBoolean("is_archived"),
                            createdAt = rs.getTimestamp("created_at").time,
                            updatedAt = rs.getTimestamp("updated_at").time
                        )
                    }
                }
            }
        }
        null
    }

    /**
     * Update an existing note.
     */
    suspend fun update(userId: String, noteId: String, title: String?, content: String?, category: String?): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val setClauses = mutableListOf<String>()
            if (title != null) setClauses.add("title = ?")
            if (content != null) setClauses.add("content = ?")
            if (category != null) setClauses.add("category = ?")
            setClauses.add("updated_at = NOW()")

            if (setClauses.isEmpty()) return@withContext false

            val sql = "UPDATE notes SET ${setClauses.joinToString(", ")} WHERE id = ? AND user_id = ?"
            conn.prepareStatement(sql).use { stmt ->
                var idx = 1
                if (title != null) stmt.setString(idx++, title)
                if (content != null) stmt.setString(idx++, content)
                if (category != null) stmt.setString(idx++, category)
                stmt.setObject(idx++, UUID.fromString(noteId))
                stmt.setString(idx, userId)
                stmt.executeUpdate() > 0
            }
        }
    }

    /**
     * Archive a note (soft delete).
     */
    suspend fun archive(userId: String, noteId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "UPDATE notes SET is_archived = TRUE, updated_at = NOW() WHERE id = ? AND user_id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(noteId))
                stmt.setString(2, userId)
                stmt.executeUpdate() > 0
            }
        }
    }

    /**
     * Permanently delete a note.
     */
    suspend fun delete(userId: String, noteId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "DELETE FROM notes WHERE id = ? AND user_id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(noteId))
                stmt.setString(2, userId)
                stmt.executeUpdate() > 0
            }
        }
    }

    // =============================================================================
    // RELATIONSHIP QUERY METHODS (Delegated to junction repositories)
    // =============================================================================

    /**
     * Get all chat messages linked to a note.
     */
    suspend fun getLinkedMessages(userId: String, noteId: String): List<String> = withContext(Dispatchers.IO) {
        // Verify note belongs to user
        val note = getNoteById(userId, noteId)
        if (note == null) {
            logger.warn("Note {} does not belong to user {}", noteId, userId)
            throw IllegalAccessException("Note does not belong to user")
        }
        // Delegate to junction repository
        chatMessageNotesRepo.getLinkedMessages(UUID.fromString(noteId))
            .map { it.toString() }
    }

    /**
     * Get all calendar events linked to a note.
     */
    suspend fun getLinkedEvents(userId: String, noteId: String): List<String> = withContext(Dispatchers.IO) {
        // Verify note belongs to user
        val note = getNoteById(userId, noteId)
        if (note == null) {
            logger.warn("Note {} does not belong to user {}", noteId, userId)
            throw IllegalAccessException("Note does not belong to user")
        }
        // Delegate to junction repository
        calendarEventNotesRepo.getLinkedEvents(UUID.fromString(noteId))
            .map { it.toString() }
    }

    /**
     * Get count of linked messages for a note.
     */
    suspend fun getLinkedMessageCount(noteId: String): Int = withContext(Dispatchers.IO) {
        chatMessageNotesRepo.getLinkCountForNote(UUID.fromString(noteId))
    }

    /**
     * Get count of linked events for a note.
     */
    suspend fun getLinkedEventCount(noteId: String): Int = withContext(Dispatchers.IO) {
        calendarEventNotesRepo.getLinkCountForNote(UUID.fromString(noteId))
    }

    /**
     * Helper method to get a note by ID (for verification).
     */
    private suspend fun getNoteById(userId: String, noteId: String): NoteInfo? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                SELECT id, title, content, category, is_archived, created_at, updated_at
                FROM notes
                WHERE id = ? AND user_id = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(noteId))
                stmt.setString(2, userId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        NoteInfo(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            content = rs.getString("content"),
                            category = rs.getString("category"),
                            isArchived = rs.getBoolean("is_archived"),
                            createdAt = rs.getTimestamp("created_at").time,
                            updatedAt = rs.getTimestamp("updated_at").time
                        )
                    } else null
                }
            }
        }
    }

    /**
     * Clean up existing duplicate notes in the database.
     * Keeps the oldest note, deletes newer duplicates.
     * @param userId Optional user ID to clean duplicates for (null = all users)
     * @return Number of duplicates removed
     */
    suspend fun cleanupDuplicates(userId: String? = null): Int {
        return deduplicationManager.cleanupExistingDuplicates(userId)
    }
}


