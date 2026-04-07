package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID
import javax.sql.DataSource

/**
 * Repository for managing calendar event to note relationships (junction table).
 *
 * SINGLE RESPONSIBILITY: Only manages calendar_event_notes junction table.
 * DRY: Uses consistent pattern with ChatMessageNotesRepository.
 * GLOBAL STATE: Ensures referential integrity via foreign keys.
 */
class CalendarEventNotesRepository(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(CalendarEventNotesRepository::class.java)

    /**
     * Link a calendar event to a note.
     */
    suspend fun linkEventToNote(
        eventId: UUID,
        noteId: UUID,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    INSERT INTO calendar_event_notes (event_id, note_id)
                    VALUES (?, ?)
                    ON CONFLICT (event_id, note_id) DO NOTHING
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, eventId)
                    stmt.setObject(2, noteId)
                    stmt.executeUpdate()
                }
            }
            logger.debug("Linked event {} to note {}", eventId, noteId)
        }

    /**
     * Unlink a calendar event from a note.
     */
    suspend fun unlinkEventFromNote(
        eventId: UUID,
        noteId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    DELETE FROM calendar_event_notes
                    WHERE event_id = ? AND note_id = ?
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, eventId)
                    stmt.setObject(2, noteId)
                    stmt.executeUpdate() > 0
                }
            }
        }

    /**
     * Get all note IDs linked to a calendar event.
     */
    suspend fun getLinkedNotes(eventId: UUID): List<UUID> =
        withContext(Dispatchers.IO) {
            val noteIds = mutableListOf<UUID>()
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT note_id FROM calendar_event_notes
                    WHERE event_id = ?
                    ORDER BY note_id
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, eventId)
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
     * Get all event IDs linked to a note.
     */
    suspend fun getLinkedEvents(noteId: UUID): List<UUID> =
        withContext(Dispatchers.IO) {
            val eventIds = mutableListOf<UUID>()
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT event_id FROM calendar_event_notes
                    WHERE note_id = ?
                    ORDER BY event_id
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, noteId)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            eventIds.add(rs.getObject("event_id") as UUID)
                        }
                    }
                }
            }
            eventIds
        }

    /**
     * Delete all note links for an event (cascade on event delete).
     */
    suspend fun deleteAllForEvent(eventId: UUID): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    DELETE FROM calendar_event_notes
                    WHERE event_id = ?
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, eventId)
                    stmt.executeUpdate()
                }
            }
        }

    /**
     * Delete all event links for a note (cascade on note delete).
     */
    suspend fun deleteAllForNote(noteId: UUID): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    DELETE FROM calendar_event_notes
                    WHERE note_id = ?
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, noteId)
                    stmt.executeUpdate()
                }
            }
        }

    /**
     * Batch link multiple notes to an event.
     */
    suspend fun linkMultipleNotesToEvent(
        eventId: UUID,
        noteIds: List<UUID>,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    val sql =
                        """
                        INSERT INTO calendar_event_notes (event_id, note_id)
                        VALUES (?, ?)
                        ON CONFLICT (event_id, note_id) DO NOTHING
                        """.trimIndent()

                    conn.prepareStatement(sql).use { stmt ->
                        noteIds.forEach { noteId ->
                            stmt.setObject(1, eventId)
                            stmt.setObject(2, noteId)
                            stmt.addBatch()
                        }
                        stmt.executeBatch()
                    }
                    conn.commit()
                } catch (e: Exception) {
                    conn.rollback()
                    logger.error("Failed to batch link notes to event {}", eventId, e)
                    throw e
                } finally {
                    conn.autoCommit = true
                }
            }
        }

    /**
     * Check if an event is linked to a specific note.
     */
    suspend fun isLinked(
        eventId: UUID,
        noteId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT 1 FROM calendar_event_notes
                    WHERE event_id = ? AND note_id = ?
                    LIMIT 1
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, eventId)
                    stmt.setObject(2, noteId)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                    }
                }
            }
        }

    /**
     * Get relationship count for an event.
     */
    suspend fun getLinkCountForEvent(eventId: UUID): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT COUNT(*) as count FROM calendar_event_notes
                    WHERE event_id = ?
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, eventId)
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
                    SELECT COUNT(*) as count FROM calendar_event_notes
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
}
