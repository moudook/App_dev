package com.example.smarty.server.data

import com.example.smarty.protocol.CalendarEventInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

class CalendarRepository(
    private val dataSource: DataSource,
    private val calendarEventNotesRepo: CalendarEventNotesRepository,
) {
    private val logger = LoggerFactory.getLogger(CalendarRepository::class.java)

    /**
     * Create a new calendar event with full CalendarEventInfo.
     */
    suspend fun create(
        userId: String,
        info: CalendarEventInfo,
    ): String =
        withContext(Dispatchers.IO) {
            val id = if (info.id.isNotEmpty()) UUID.fromString(info.id) else UUID.randomUUID()
            dataSource.connection.use { conn ->
                val sql =
                    """
                    INSERT INTO calendar_events (
                        id, user_id, linked_note_id, google_event_id, title,
                        description, start_time, end_time, is_all_day,
                        is_event_private, status, visibility, reminders,
                        attendees, location, metadata, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, to_timestamp(? / 1000), to_timestamp(? / 1000), ?, ?, 'confirmed', 'private', '[]', '[]', ?, '{}', now(), now())
                    ON CONFLICT (id) DO UPDATE SET
                        linked_note_id = EXCLUDED.linked_note_id,
                        google_event_id = EXCLUDED.google_event_id,
                        title = EXCLUDED.title,
                        description = EXCLUDED.description,
                        start_time = EXCLUDED.start_time,
                        end_time = EXCLUDED.end_time,
                        is_event_private = EXCLUDED.is_event_private,
                        location = EXCLUDED.location,
                        updated_at = now()
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    var idx = 1
                    stmt.setObject(idx++, id)
                    stmt.setObject(idx++, UUID.fromString(userId))
                    stmt.setObject(idx++, info.linkedNoteId?.let { UUID.fromString(it) })
                    stmt.setString(idx++, info.googleEventId)
                    stmt.setString(idx++, info.title)
                    stmt.setString(idx++, info.description)
                    stmt.setLong(idx++, info.startTime)
                    stmt.setLong(idx++, info.endTime)
                    stmt.setBoolean(idx++, false)
                    stmt.setBoolean(idx++, info.isEventPrivate)
                    stmt.setString(idx++, info.location)
                    stmt.executeUpdate()
                }
                id.toString()
            }
        }

    /**
     * Create event with individual parameters (backward compatible overload).
     */
    suspend fun create(
        userId: String,
        title: String,
        startTime: Long,
        endTime: Long,
        description: String? = null,
        reminderMinutes: Int = 15,
    ): String =
        create(
            userId,
            CalendarEventInfo(
                id = "",
                title = title,
                startTime = startTime,
                endTime = endTime,
                description = description,
                reminderMinutes = reminderMinutes,
                linkedNoteId = null,
                googleEventId = null,
                isEventPrivate = false,
                createdAt = System.currentTimeMillis(),
            ),
        )

    suspend fun listAllEvents(
        userId: String,
        limit: Int = 500,
    ): List<CalendarEventInfo> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<CalendarEventInfo>()
            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM calendar_events WHERE user_id = ?::uuid ORDER BY start_time DESC LIMIT ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.setInt(2, limit)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(mapRowToEventInfo(rs))
                        }
                    }
                }
            }
            results
        }

    /**
     * List events within a time range. Use this instead of listAllEvents + in-memory filtering.
     */
    suspend fun listEventsInRange(
        userId: String,
        startMs: Long,
        endMs: Long,
        limit: Int = 500,
    ): List<CalendarEventInfo> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<CalendarEventInfo>()
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT * FROM calendar_events 
                    WHERE user_id = ?::uuid 
                    AND start_time >= to_timestamp(? / 1000.0)
                    AND start_time < to_timestamp(? / 1000.0)
                    ORDER BY start_time ASC 
                    LIMIT ?
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.setLong(2, startMs)
                    stmt.setLong(3, endMs)
                    stmt.setInt(4, limit)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(mapRowToEventInfo(rs))
                        }
                    }
                }
            }
            results
        }

    suspend fun listEventsUpdatedAfter(
        userId: String,
        timestamp: Long,
        limit: Int = 200,
    ): List<CalendarEventInfo> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<CalendarEventInfo>()
            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM calendar_events WHERE user_id = ?::uuid AND updated_at > ? ORDER BY updated_at ASC LIMIT ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.setTimestamp(2, java.sql.Timestamp(timestamp))
                    stmt.setInt(3, limit)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(mapRowToEventInfo(rs))
                        }
                    }
                }
            }
            results
        }

    private fun mapRowToEventInfo(rs: ResultSet): CalendarEventInfo =
        CalendarEventInfo(
            id = rs.getString("id"),
            title = rs.getString("title"),
            startTime = rs.getTimestamp("start_time").time,
            endTime = rs.getTimestamp("end_time")?.time ?: rs.getTimestamp("start_time").time,
            description = rs.getString("description"),
            location = rs.getString("location"),
            reminderMinutes = 15, // TODO: parse from reminders JSONB
            linkedNoteId = rs.getObject("linked_note_id")?.toString(),
            googleEventId = rs.getString("google_event_id"),
            isEventPrivate = rs.getBoolean("is_event_private"),
            createdAt = rs.getTimestamp("created_at").time,
        )

    suspend fun delete(
        userId: String,
        eventId: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "DELETE FROM calendar_events WHERE id = ? AND user_id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(eventId))
                    stmt.setObject(2, UUID.fromString(userId))
                    stmt.executeUpdate() > 0
                }
            }
        }

    /**
     * Create event with specific ID (for sync).
     */
    suspend fun createWithId(
        userId: String,
        eventId: String,
        title: String,
        startTime: Long,
        endTime: Long,
        description: String? = null,
        location: String? = null,
        reminderMinutes: Int = 15,
        linkedNoteId: String? = null,
        googleEventId: String? = null,
        isEventPrivate: Boolean = false,
    ): String =
        create(
            userId,
            CalendarEventInfo(
                id = eventId,
                title = title,
                startTime = startTime,
                endTime = endTime,
                description = description,
                location = location,
                reminderMinutes = reminderMinutes,
                linkedNoteId = linkedNoteId,
                googleEventId = googleEventId,
                isEventPrivate = isEventPrivate,
                createdAt = System.currentTimeMillis(),
            ),
        )

    /**
     * List upcoming events from now.
     */
    suspend fun listUpcoming(
        userId: String,
        limit: Int = 100,
    ): List<CalendarEventInfo> = listAllEvents(userId, limit)

    /**
     * Link a note to a calendar event.
     */
    suspend fun linkNoteToEvent(
        userId: String,
        eventId: String,
        noteId: String,
    ) {
        val eventUuid = UUID.fromString(eventId)
        val noteUuid = UUID.fromString(noteId)
        // Verify ownership
        dataSource.connection.use { conn ->
            val checkSql = "SELECT 1 FROM calendar_events WHERE id = ? AND user_id = ?"
            conn.prepareStatement(checkSql).use { stmt ->
                stmt.setObject(1, eventUuid)
                stmt.setObject(2, UUID.fromString(userId))
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) throw IllegalAccessException("Event not found or not owned by user")
                }
            }
        }
        calendarEventNotesRepo.linkEventToNote(eventUuid, noteUuid)
    }

    /**
     * Unlink a note from a calendar event.
     */
    suspend fun unlinkNoteFromEvent(
        userId: String,
        eventId: String,
        noteId: String,
    ): Boolean {
        val eventUuid = UUID.fromString(eventId)
        val noteUuid = UUID.fromString(noteId)
        // Verify ownership
        dataSource.connection.use { conn ->
            val checkSql = "SELECT 1 FROM calendar_events WHERE id = ? AND user_id = ?"
            conn.prepareStatement(checkSql).use { stmt ->
                stmt.setObject(1, eventUuid)
                stmt.setObject(2, UUID.fromString(userId))
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) throw IllegalAccessException("Event not found or not owned by user")
                }
            }
        }
        return calendarEventNotesRepo.unlinkEventFromNote(eventUuid, noteUuid)
    }

    /**
     * Get all note IDs linked to a calendar event.
     */
    suspend fun getLinkedNotes(
        userId: String,
        eventId: String,
    ): List<String> =
        withContext(Dispatchers.IO) {
            // Verify ownership
            dataSource.connection.use { conn ->
                val checkSql = "SELECT 1 FROM calendar_events WHERE id = ? AND user_id = ?"
                conn.prepareStatement(checkSql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(eventId))
                    stmt.setObject(2, UUID.fromString(userId))
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) return@withContext emptyList()
                    }
                }
            }
            calendarEventNotesRepo
                .getLinkedNotes(UUID.fromString(eventId))
                .map { it.toString() }
        }
}
