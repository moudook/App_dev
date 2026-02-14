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
 */
class NoteRepository(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(NoteRepository::class.java)

    /**
     * Create a new note.
     * @return The UUID of the created note.
     */
    suspend fun create(
        userId: String,
        title: String,
        content: String,
        category: String? = null
    ): String = withContext(Dispatchers.IO) {
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
                WHERE user_id = ? AND NOT is_archived
                  AND (title ILIKE ? OR content ILIKE ?)
                ORDER BY updated_at DESC
                LIMIT ?
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, userId)
                stmt.setString(2, "%$query%")
                stmt.setString(3, "%$query%")
                stmt.setInt(4, limit)
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
     * List all active notes for a user.
     */
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
}


