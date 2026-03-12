package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID
import javax.sql.DataSource
import com.example.smarty.protocol.CalendarEventInfo

/**
 * Server-side repository for calendar events.
 * PostgreSQL is the source of truth; Android caches via StateSync events.
 * 
 * SINGLE RESPONSIBILITY: Only manages calendar_events table.
 * Delegates note relationship management to CalendarEventNotesRepository.
 * GLOBAL STATE: All tables reference users(firebase_uid) with cascade deletes.
 */
class CalendarRepository(
    private val dataSource: DataSource,
    private val calendarEventNotesRepo: CalendarEventNotesRepository
) {
    private val logger = LoggerFactory.getLogger(CalendarRepository::class.java)

    /**
     * Create a new calendar event.
     * Checks for duplicates before creating (same title, startTime, endTime).
     * @return The UUID of the created or existing event.
     */
    suspend fun create(
        userId: String,
        title: String,
        startTime: Long,
        endTime: Long,
        description: String? = null,
        reminderMinutes: Int = 15
    ): String = withContext(Dispatchers.IO) {
        // Check for duplicate event (same title, startTime, endTime within 1 minute tolerance)
        dataSource.connection.use { conn ->
            val checkSql = """
                SELECT id FROM calendar_events 
                WHERE user_id = ? AND title = ? 
                AND ABS(start_time - ?) < 60000 
                AND ABS(end_time - ?) < 60000
                LIMIT 1
            """.trimIndent()
            conn.prepareStatement(checkSql).use { stmt ->
                stmt.setString(1, userId)
                stmt.setString(2, title)
                stmt.setLong(3, startTime)
                stmt.setLong(4, endTime)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val existingId = rs.getString("id")
                        logger.info("Duplicate event prevented: using existing id={}, title={}", existingId, title)
                        return@withContext existingId
                    }
                }
            }
            
            // No duplicate found, create new event
            val id = UUID.randomUUID()
            val sql = """
                INSERT INTO calendar_events (id, user_id, title, start_time, end_time, description, reminder_minutes)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, id)
                stmt.setString(2, userId)
                stmt.setString(3, title)
                stmt.setLong(4, startTime)
                stmt.setLong(5, endTime)
                stmt.setString(6, description)
                stmt.setInt(7, reminderMinutes)
                stmt.executeUpdate()
            }
            logger.info("Calendar event created: id={}, user={}, title={}", id, userId, title)
            id.toString()
        }
    }

    /**
     * List upcoming events for a user.
     */
    suspend fun listUpcoming(userId: String, limit: Int = 20): List<CalendarEventInfo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<CalendarEventInfo>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT id, title, start_time, end_time, description, reminder_minutes, created_at
                FROM calendar_events
                WHERE user_id = ? AND end_time >= ?
                ORDER BY start_time ASC
                LIMIT ?
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, userId)
                stmt.setLong(2, System.currentTimeMillis())
                stmt.setInt(3, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        results.add(CalendarEventInfo(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            startTime = rs.getLong("start_time"),
                            endTime = rs.getLong("end_time"),
                            description = rs.getString("description"),
                            reminderMinutes = rs.getInt("reminder_minutes"),
                            createdAt = rs.getTimestamp("created_at").time
                        ))
                    }
                }
            }
        }
        results
    }

    /**
     * List ALL events for a user (for sync).
     */
    suspend fun listAllEvents(userId: String, limit: Int = 500): List<CalendarEventInfo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<CalendarEventInfo>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT id, title, start_time, end_time, description, reminder_minutes, created_at
                FROM calendar_events
                WHERE user_id = ?
                ORDER BY start_time DESC
                LIMIT ?
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, userId)
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        results.add(CalendarEventInfo(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            startTime = rs.getLong("start_time"),
                            endTime = rs.getLong("end_time"),
                            description = rs.getString("description"),
                            reminderMinutes = rs.getInt("reminder_minutes"),
                            createdAt = rs.getTimestamp("created_at").time
                        ))
                    }
                }
            }
        }
        results
    }

    /**
     * Create an event with a specific ID (for sync).
     */
    suspend fun createWithId(
        userId: String,
        eventId: String,
        title: String,
        startTime: Long,
        endTime: Long,
        description: String? = null,
        reminderMinutes: Int = 15
    ): String = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO calendar_events (id, user_id, title, start_time, end_time, description, reminder_minutes)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(eventId))
                stmt.setString(2, userId)
                stmt.setString(3, title)
                stmt.setLong(4, startTime)
                stmt.setLong(5, endTime)
                stmt.setString(6, description)
                stmt.setInt(7, reminderMinutes)
                val rows = stmt.executeUpdate()
                if (rows > 0) eventId else create(userId, title, startTime, endTime, description, reminderMinutes)
            }
        }
    }

    /**
     * Delete a calendar event.
     */
    suspend fun delete(userId: String, eventId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "DELETE FROM calendar_events WHERE id = ? AND user_id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(eventId))
                stmt.setString(2, userId)
                stmt.executeUpdate() > 0
            }
        }
    }

    // =============================================================================
    // NOTE RELATIONSHIP METHODS (Delegated to CalendarEventNotesRepository)
    // =============================================================================

    /**
     * Link a note to a calendar event.
     * Validates that the event belongs to the user before linking.
     */
    suspend fun linkNoteToEvent(userId: String, eventId: String, noteId: String): Unit = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            // Verify event belongs to user
            val verifySql = """
                SELECT 1 FROM calendar_events
                WHERE id = ? AND user_id = ?
            """.trimIndent()
            conn.prepareStatement(verifySql).use { stmt ->
                stmt.setObject(1, UUID.fromString(eventId))
                stmt.setString(2, userId)
                val exists = stmt.executeQuery().next()
                if (!exists) {
                    logger.warn("Event {} does not belong to user {}", eventId, userId)
                    throw IllegalAccessException("Event does not belong to user")
                }
            }
        }
        // Delegate to junction repository
        calendarEventNotesRepo.linkEventToNote(UUID.fromString(eventId), UUID.fromString(noteId))
        logger.info("Linked note {} to event {} for user {}", noteId, eventId, userId)
    }

    /**
     * Unlink a note from a calendar event.
     */
    suspend fun unlinkNoteFromEvent(userId: String, eventId: String, noteId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            // Verify event belongs to user
            val verifySql = """
                SELECT 1 FROM calendar_events
                WHERE id = ? AND user_id = ?
            """.trimIndent()
            conn.prepareStatement(verifySql).use { stmt ->
                stmt.setObject(1, UUID.fromString(eventId))
                stmt.setString(2, userId)
                val exists = stmt.executeQuery().next()
                if (!exists) {
                    logger.warn("Event {} does not belong to user {}", eventId, userId)
                    throw IllegalAccessException("Event does not belong to user")
                }
            }
        }
        // Delegate to junction repository
        calendarEventNotesRepo.unlinkEventFromNote(UUID.fromString(eventId), UUID.fromString(noteId))
    }

    /**
     * Get all notes linked to a calendar event.
     */
    suspend fun getLinkedNotes(userId: String, eventId: String): List<String> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            // Verify event belongs to user
            val verifySql = """
                SELECT 1 FROM calendar_events
                WHERE id = ? AND user_id = ?
            """.trimIndent()
            conn.prepareStatement(verifySql).use { stmt ->
                stmt.setObject(1, UUID.fromString(eventId))
                stmt.setString(2, userId)
                val exists = stmt.executeQuery().next()
                if (!exists) {
                    logger.warn("Event {} does not belong to user {}", eventId, userId)
                    throw IllegalAccessException("Event does not belong to user")
                }
            }
        }
        // Delegate to junction repository
        calendarEventNotesRepo.getLinkedNotes(UUID.fromString(eventId))
            .map { it.toString() }
    }
}


