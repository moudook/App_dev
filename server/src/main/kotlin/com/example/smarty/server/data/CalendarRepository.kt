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
 */
class CalendarRepository(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(CalendarRepository::class.java)

    /**
     * Create a new calendar event.
     * @return The UUID of the created event.
     */
    suspend fun create(
        userId: String,
        title: String,
        startTime: Long,
        endTime: Long,
        description: String? = null,
        reminderMinutes: Int = 15
    ): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
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
        }
        logger.info("Calendar event created: id={}, user={}, title={}", id, userId, title)
        id.toString()
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
}


