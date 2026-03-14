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
                AND start_time >= to_timestamp(? / 1000) - INTERVAL '1 minute'
                AND start_time <= to_timestamp(? / 1000) + INTERVAL '1 minute'
                AND end_time >= to_timestamp(? / 1000) - INTERVAL '1 minute'
                AND end_time <= to_timestamp(? / 1000) + INTERVAL '1 minute'
                LIMIT 1
            """.trimIndent()
            conn.prepareStatement(checkSql).use { stmt ->
                stmt.setObject(1, UUID.fromString(userId))
                stmt.setString(2, title)
                stmt.setLong(3, startTime)
                stmt.setLong(4, startTime)
                stmt.setLong(5, endTime)
                stmt.setLong(6, endTime)
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
                INSERT INTO calendar_events (id, user_id, title, start_time, end_time, description, is_all_day, status, visibility, reminders, attendees, metadata, created_at, updated_at)
                VALUES (?, ?, ?, to_timestamp(? / 1000), to_timestamp(? / 1000), ?, false, 'confirmed', 'private', '[]', '[]', '{}', now(), now())
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, id)
                stmt.setObject(2, UUID.fromString(userId))
                stmt.setString(3, title)
                stmt.setLong(4, startTime)
                stmt.setLong(5, endTime)
                stmt.setString(6, description)
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
                SELECT id, title, start_time, end_time, description, created_at
                FROM calendar_events
                WHERE user_id = ? AND status <> 'cancelled' AND start_time >= now()
                ORDER BY start_time ASC
                LIMIT ?
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(userId))
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        results.add(CalendarEventInfo(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            startTime = rs.getTimestamp("start_time").time,
                            endTime = rs.getTimestamp("end_time").time,
                            description = rs.getString("description"),
                            reminderMinutes = 15, // Default since not in schema
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
                SELECT id, title, start_time, end_time, description, created_at
                FROM calendar_events
                WHERE user_id = ?
                ORDER BY start_time DESC
                LIMIT ?
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(userId))
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        results.add(CalendarEventInfo(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            startTime = rs.getTimestamp("start_time").time,
                            endTime = rs.getTimestamp("end_time").time,
                            description = rs.getString("description"),
                            reminderMinutes = 15, // Default
                            createdAt = rs.getTimestamp("created_at").time
                        ))
                    }
                }
            }
        }
        results
    }

    /**
     * DELTA SYNC: List events updated after a specific timestamp.
     * Uses index on (user_id, updated_at) for fast queries.
     */
    suspend fun listEventsUpdatedAfter(userId: String, timestamp: Long, limit: Int = 200): List<CalendarEventInfo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<CalendarEventInfo>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT id, title, start_time, end_time, description, created_at, updated_at
                FROM calendar_events
                WHERE user_id = ? AND updated_at > to_timestamp(? / 1000.0)
                ORDER BY updated_at DESC
                LIMIT ?
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(userId))
                stmt.setLong(2, timestamp)
                stmt.setInt(3, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        results.add(CalendarEventInfo(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            startTime = rs.getTimestamp("start_time").time,
                            endTime = rs.getTimestamp("end_time").time,
                            description = rs.getString("description"),
                            reminderMinutes = 15, // Default
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
        description: String? = null
    ): String = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO calendar_events (id, user_id, title, start_time, end_time, description, is_all_day, status, visibility, reminders, attendees, metadata, created_at, updated_at)
                VALUES (?, ?, ?, to_timestamp(? / 1000), to_timestamp(? / 1000), ?, false, 'confirmed', 'private', '[]', '[]', '{}', now(), now())
                ON CONFLICT (id) DO NOTHING
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(eventId))
                stmt.setObject(2, UUID.fromString(userId))
                stmt.setString(3, title)
                stmt.setLong(4, startTime)
                stmt.setLong(5, endTime)
                stmt.setString(6, description)
                val rows = stmt.executeUpdate()
                if (rows > 0) eventId else create(userId, title, startTime, endTime, description, 15)
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
                stmt.setObject(2, UUID.fromString(userId))
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
                stmt.setObject(2, UUID.fromString(userId))  // UUID cast — v6 schema
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
                stmt.setObject(2, UUID.fromString(userId))  // UUID cast — v6 schema
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
                stmt.setObject(2, UUID.fromString(userId))  // UUID cast — v6 schema
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


