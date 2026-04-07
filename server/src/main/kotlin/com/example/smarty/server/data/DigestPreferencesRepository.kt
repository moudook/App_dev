package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

/**
 * Repository for managing user digest preferences.
 *
 * v6 schema uses: enabled (BOOLEAN), frequency (TEXT: 'daily'|'weekly'),
 * delivery_hour (SMALLINT), delivery_minute (SMALLINT), timezone (TEXT).
 *
 * The API layer (DigestRoutes) still exposes the old field names
 * (dailyEnabled, dailyTime, etc.) for backward compatibility with the
 * Android client. This repository bridges the two.
 */
class DigestPreferencesRepository(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(DigestPreferencesRepository::class.java)

    /**
     * Get digest preferences for a user.
     * Returns null only if a genuine DB error occurs (not a missing row).
     */
    suspend fun getPreferences(userId: String): DigestPreferences? =
        withContext(Dispatchers.IO) {
            try {
                dataSource.connection.use { conn ->
                    val sql =
                        """
                        SELECT enabled, frequency, delivery_hour, delivery_minute, timezone
                        FROM digest_preferences
                        WHERE user_id = ?
                        """.trimIndent()

                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setObject(1, UUID.fromString(userId))
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) {
                                mapRowToPreferences(rs, userId)
                            } else {
                                getDefaultPreferences(userId)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to get digest preferences for user $userId: ${e.message}")
                getDefaultPreferences(userId)
            }
        }

    /**
     * Update (or insert) digest preferences for a user.
     *
     * The API passes the old-style fields; we convert them to v6 columns:
     *  - dailyEnabled / weeklyEnabled  ->  enabled + frequency
     *  - dailyTime / weeklyTime (HH:mm) -> delivery_hour + delivery_minute
     */
    suspend fun updatePreferences(
        userId: String,
        dailyEnabled: Boolean? = null,
        dailyTime: String? = null,
        weeklyEnabled: Boolean? = null,
        weeklyDay: Int? = null,
        weeklyTime: String? = null,
        pushNotification: Boolean? = null, // not in v6 schema, ignored silently
        calendarLogging: Boolean? = null, // not in v6 schema, ignored silently
    ) = withContext(Dispatchers.IO) {
        try {
            // Derive v6 columns from old-style API input
            val enabled = dailyEnabled ?: weeklyEnabled ?: true
            val frequency =
                when {
                    weeklyEnabled == true -> "weekly"
                    dailyEnabled == true -> "daily"
                    else -> "daily"
                }
            // Pick whichever time was supplied (daily wins over weekly)
            val timeStr = dailyTime ?: weeklyTime
            val (hour, minute) = parseHHmm(timeStr ?: "07:00")

            dataSource.connection.use { conn ->
                val upsertSql =
                    """
                    INSERT INTO digest_preferences (user_id, enabled, frequency, delivery_hour, delivery_minute)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (user_id) DO UPDATE SET
                        enabled         = EXCLUDED.enabled,
                        frequency       = EXCLUDED.frequency,
                        delivery_hour   = EXCLUDED.delivery_hour,
                        delivery_minute = EXCLUDED.delivery_minute,
                        updated_at      = now()
                    """.trimIndent()

                conn.prepareStatement(upsertSql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.setBoolean(2, enabled)
                    stmt.setString(3, frequency)
                    stmt.setInt(4, hour)
                    stmt.setInt(5, minute)
                    stmt.executeUpdate()
                }
            }
            logger.info("Updated digest preferences for user $userId: frequency=$frequency, time=$hour:$minute")
        } catch (e: Exception) {
            logger.error("Failed to update digest preferences for user $userId: ${e.message}")
            throw e
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun getDefaultPreferences(userId: String): DigestPreferences =
        DigestPreferences(
            userId = userId,
            dailyEnabled = true,
            dailyTime = "07:00",
            weeklyEnabled = false,
            weeklyDay = 1, // Monday
            weeklyTime = "08:00",
            pushNotification = true,
            calendarLogging = false,
        )

    private fun mapRowToPreferences(
        rs: ResultSet,
        userId: String,
    ): DigestPreferences {
        val frequency = rs.getString("frequency") ?: "daily"
        val hour = rs.getInt("delivery_hour").takeIf { !rs.wasNull() } ?: 7
        val minute = rs.getInt("delivery_minute").takeIf { !rs.wasNull() } ?: 0
        val timeStr = "%02d:%02d".format(hour, minute)
        return DigestPreferences(
            userId = userId,
            dailyEnabled = frequency == "daily" && rs.getBoolean("enabled"),
            dailyTime = timeStr,
            weeklyEnabled = frequency == "weekly" && rs.getBoolean("enabled"),
            weeklyDay = 1, // v6 doesn't store preferred day; default Monday
            weeklyTime = timeStr,
            pushNotification = false, // not in v6 schema
            calendarLogging = false, // not in v6 schema
        )
    }

    /** Parse "HH:mm" string → Pair(hour, minute). Falls back to (7, 0) on failure. */
    private fun parseHHmm(time: String): Pair<Int, Int> {
        return try {
            val parts = time.split(":")
            Pair(parts[0].trim().toInt().coerceIn(0, 23), parts[1].trim().toInt().coerceIn(0, 59))
        } catch (e: Exception) {
            Pair(7, 0)
        }
    }
}

/**
 * Data class representing user digest preferences.
 * Exposes the old-style field names for backward compatibility with the API layer.
 */
data class DigestPreferences(
    val userId: String,
    val dailyEnabled: Boolean,
    val dailyTime: String, // "HH:mm" format
    val weeklyEnabled: Boolean,
    val weeklyDay: Int, // 0=Sunday, 1=Monday, … (default only; not persisted in v6)
    val weeklyTime: String, // "HH:mm" format
    val pushNotification: Boolean,
    val calendarLogging: Boolean,
)
