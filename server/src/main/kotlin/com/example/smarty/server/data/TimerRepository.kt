package com.example.smarty.server.data

import com.example.smarty.protocol.TimerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource

/**
 * Server-side repository for timers and alarms.
 * PostgreSQL is the source of truth; Android caches via StateSync events
 * and schedules local alarms for offline support.
 */
class TimerRepository(
    private val dataSource: DataSource,
) {
    private val logger = LoggerFactory.getLogger(TimerRepository::class.java)

    /**
     * Create a new timer or alarm.
     * @param durationMs Duration in milliseconds (for timers)
     * @param triggerAt Absolute trigger time (for alarms, epoch millis)
     * @param isAlarm True if this is an alarm (absolute time), false for timer (relative duration)
     * @return The UUID of the created timer.
     */
    suspend fun create(
        userId: String,
        name: String,
        durationMs: Long = 0,
        triggerAt: Long? = null,
        isAlarm: Boolean = false,
        repeat: String? = null,
    ): String =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID()
            val actualTriggerAt = triggerAt ?: (System.currentTimeMillis() + durationMs)

            dataSource.connection.use { conn ->
                val sql =
                    """
                    INSERT INTO timers (id, user_id, name, duration_ms, trigger_at, is_alarm, repeat)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, id)
                    stmt.setObject(2, UUID.fromString(userId)) // UUID cast — v6 schema user_id is UUID
                    stmt.setString(3, name)
                    stmt.setLong(4, durationMs)
                    stmt.setTimestamp(5, Timestamp(actualTriggerAt))
                    stmt.setBoolean(6, isAlarm)
                    stmt.setString(7, repeat)
                    stmt.executeUpdate()
                }
            }
            logger.info("Timer created: id={}, user={}, name={}, triggerAt={}, repeat={}", id, userId, name, actualTriggerAt, repeat)
            id.toString()
        }

    /**
     * List active timers for a user.
     */
    suspend fun listActive(userId: String): List<TimerInfo> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<TimerInfo>()
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT id, name, duration_ms, trigger_at, is_alarm, is_active, created_at, repeat
                    FROM timers
                    WHERE user_id = ? AND is_active = TRUE
                    ORDER BY trigger_at ASC
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId)) // UUID cast — v6 schema
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(
                                TimerInfo(
                                    id = rs.getString("id"),
                                    name = rs.getString("name"),
                                    durationMs = rs.getLong("duration_ms"),
                                    triggerAt = rs.getTimestamp("trigger_at").time,
                                    isAlarm = rs.getBoolean("is_alarm"),
                                    isActive = rs.getBoolean("is_active"),
                                    createdAt = rs.getTimestamp("created_at").time,
                                    repeat = rs.getString("repeat"),
                                ),
                            )
                        }
                    }
                }
            }
            results
        }

    /**
     * Deactivate a timer (mark as completed/cancelled).
     */
    suspend fun deactivate(
        userId: String,
        timerId: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "UPDATE timers SET is_active = FALSE WHERE id = ? AND user_id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(timerId))
                    stmt.setObject(2, UUID.fromString(userId)) // UUID cast — v6 schema
                    stmt.executeUpdate() > 0
                }
            }
        }

    /**
     * Delete a timer permanently.
     */
    suspend fun delete(
        userId: String,
        timerId: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "DELETE FROM timers WHERE id = ? AND user_id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(timerId))
                    stmt.setObject(2, UUID.fromString(userId)) // UUID cast — v6 schema
                    stmt.executeUpdate() > 0
                }
            }
        }
}
