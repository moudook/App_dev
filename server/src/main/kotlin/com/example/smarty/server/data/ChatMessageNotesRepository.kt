package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Repository for managing chat message to note relationships (junction table).
 *
 * SINGLE RESPONSIBILITY: Only manages chat_message_notes junction table.
 * DRY: Uses consistent pattern with CalendarEventNotesRepository.
 * GLOBAL STATE: Ensures referential integrity via foreign keys.
 */
class ChatMessageNotesRepository(
    private val dataSource: DataSource,
) {
    private val logger = LoggerFactory.getLogger(ChatMessageNotesRepository::class.java)

    /**
     * Link a chat message to a note.
     */
    suspend fun linkMessageToNote(
        messageId: UUID,
        noteId: UUID,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                insertLink(conn, messageId, noteId)
            }
            logger.debug("Linked message {} to note {}", messageId, noteId)
        }

    /**
     * Unlink a chat message from a note.
     */
    suspend fun unlinkMessageFromNote(
        messageId: UUID,
        noteId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    DELETE FROM chat_message_notes
                    WHERE message_id = ? AND note_id = ?
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, messageId)
                    stmt.setObject(2, noteId)
                    stmt.executeUpdate() > 0
                }
            }
        }

    /**
     * Get all note IDs linked to a chat message.
     */
    suspend fun getLinkedNotes(messageId: UUID): List<UUID> =
        withContext(Dispatchers.IO) {
            val noteIds = mutableListOf<UUID>()
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT note_id FROM chat_message_notes
                    WHERE message_id = ?
                    ORDER BY note_id
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, messageId)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            noteIds.add(rs.getObject("note_id") as UUID)
                        }
                    }
                }
            }
            noteIds
        }

    /**
     * Get all message IDs linked to a note.
     */
    suspend fun getLinkedMessages(noteId: UUID): List<UUID> =
        withContext(Dispatchers.IO) {
            val messageIds = mutableListOf<UUID>()
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT message_id FROM chat_message_notes
                    WHERE note_id = ?
                    ORDER BY message_id
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, noteId)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            messageIds.add(rs.getObject("message_id") as UUID)
                        }
                    }
                }
            }
            messageIds
        }

    /**
     * Delete all note links for a message (cascade on message delete).
     */
    suspend fun deleteAllForMessage(messageId: UUID): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    DELETE FROM chat_message_notes
                    WHERE message_id = ?
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, messageId)
                    stmt.executeUpdate()
                }
            }
        }

    /**
     * Delete all message links for a note (cascade on note delete).
     */
    suspend fun deleteAllForNote(noteId: UUID): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    DELETE FROM chat_message_notes
                    WHERE note_id = ?
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, noteId)
                    stmt.executeUpdate()
                }
            }
        }

    /**
     * Batch link multiple notes to a message.
     */
    suspend fun linkMultipleNotesToMessage(
        messageId: UUID,
        noteIds: List<UUID>,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    noteIds.forEach { noteId ->
                        insertLink(conn, messageId, noteId)
                    }
                    conn.commit()
                } catch (e: Exception) {
                    conn.rollback()
                    logger.error("Failed to batch link notes to message {}", messageId, e)
                    throw e
                } finally {
                    conn.autoCommit = true
                }
            }
        }

    /**
     * Check if a message is linked to a specific note.
     */
    suspend fun isLinked(
        messageId: UUID,
        noteId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT 1 FROM chat_message_notes
                    WHERE message_id = ? AND note_id = ?
                    LIMIT 1
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, messageId)
                    stmt.setObject(2, noteId)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                    }
                }
            }
        }

    /**
     * Get relationship count for a message.
     */
    suspend fun getLinkCountForMessage(messageId: UUID): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT COUNT(*) as count FROM chat_message_notes
                    WHERE message_id = ?
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, messageId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getInt("count") else 0
                    }
                }
            }
        }

    /**
     * Get relationship count for a note.
     */
    suspend fun getLinkCountForNote(noteId: UUID): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT COUNT(*) as count FROM chat_message_notes
                    WHERE note_id = ?
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, noteId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getInt("count") else 0
                    }
                }
            }
        }

    private fun insertLink(
        conn: Connection,
        messageId: UUID,
        noteId: UUID,
    ) {
        val sql = insertLinkSql(conn)
        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, messageId)
            stmt.setObject(2, noteId)
            if (!isPostgres(conn)) {
                stmt.setObject(3, messageId)
                stmt.setObject(4, noteId)
            }
            stmt.executeUpdate()
        }
    }

    private fun insertLinkSql(conn: Connection): String =
        if (isPostgres(conn)) {
            """
            INSERT INTO chat_message_notes (message_id, note_id)
            VALUES (?, ?)
            ON CONFLICT (message_id, note_id) DO NOTHING
            """.trimIndent()
        } else {
            """
            INSERT INTO chat_message_notes (message_id, note_id)
            SELECT ?, ?
            WHERE NOT EXISTS (
                SELECT 1 FROM chat_message_notes
                WHERE message_id = ? AND note_id = ?
            )
            """.trimIndent()
        }

    private fun isPostgres(conn: Connection): Boolean = conn.metaData.databaseProductName.contains("PostgreSQL", ignoreCase = true)
}
