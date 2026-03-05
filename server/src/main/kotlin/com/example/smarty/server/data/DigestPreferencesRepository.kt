package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.sql.Time
import java.util.UUID
import javax.sql.DataSource

/**
 * Repository for managing user digest preferences.
 */
class DigestPreferencesRepository(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(DigestPreferencesRepository::class.java)

    /**
     * Get digest preferences for a user.
     */
    suspend fun getPreferences(userId: String): DigestPreferences? = withContext(Dispatchers.IO) {
        try {
            dataSource.connection.use { conn ->
                val sql = """
                    SELECT daily_enabled, daily_time, weekly_enabled, weekly_day, 
                           weekly_time, push_notification, calendar_logging
                    FROM digest_preferences
                    WHERE user_id = ?
                """.trimIndent()
                
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, userId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            mapRowToPreferences(rs, userId)
                        } else {
                            // Return default preferences if none exist
                            getDefaultPreferences(userId)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to get digest preferences for user $userId", e)
            getDefaultPreferences(userId)
        }
    }

    /**
     * Update digest preferences for a user.
     */
    suspend fun updatePreferences(
        userId: String,
        dailyEnabled: Boolean? = null,
        dailyTime: String? = null,
        weeklyEnabled: Boolean? = null,
        weeklyDay: Int? = null,
        weeklyTime: String? = null,
        pushNotification: Boolean? = null,
        calendarLogging: Boolean? = null
    ) = withContext(Dispatchers.IO) {
        try {
            dataSource.connection.use { conn ->
                // First check if preferences exist
                val checkSql = "SELECT 1 FROM digest_preferences WHERE user_id = ?"
                val exists = conn.prepareStatement(checkSql).use { stmt ->
                    stmt.setString(1, userId)
                    stmt.executeQuery().next()
                }

                if (exists) {
                    // Update existing preferences
                    val updates = mutableListOf<String>()
                    val params = mutableListOf<Any>()

                    dailyEnabled?.let { 
                        updates.add("daily_enabled = ?")
                        params.add(it)
                    }
                    dailyTime?.let { 
                        updates.add("daily_time = ?::time")
                        params.add(it)
                    }
                    weeklyEnabled?.let { 
                        updates.add("weekly_enabled = ?")
                        params.add(it)
                    }
                    weeklyDay?.let { 
                        updates.add("weekly_day = ?")
                        params.add(it)
                    }
                    weeklyTime?.let { 
                        updates.add("weekly_time = ?::time")
                        params.add(it)
                    }
                    pushNotification?.let { 
                        updates.add("push_notification = ?")
                        params.add(it)
                    }
                    calendarLogging?.let { 
                        updates.add("calendar_logging = ?")
                        params.add(it)
                    }

                    if (updates.isNotEmpty()) {
                        val updateSql = """
                            UPDATE digest_preferences 
                            SET ${updates.joinToString(", ")}
                            WHERE user_id = ?
                        """.trimIndent()
                        
                        conn.prepareStatement(updateSql).use { stmt ->
                            params.forEachIndexed { index, param ->
                                when (param) {
                                    is Boolean -> stmt.setBoolean(index + 1, param)
                                    is Int -> stmt.setInt(index + 1, param)
                                    is String -> stmt.setString(index + 1, param)
                                }
                            }
                            stmt.setString(params.size + 1, userId)
                            stmt.executeUpdate()
                        }
                    }
                } else {
                    // Insert new preferences with defaults
                    val prefs = getDefaultPreferences(userId).copy(
                        dailyEnabled = dailyEnabled ?: true,
                        dailyTime = dailyTime ?: "07:00",
                        weeklyEnabled = weeklyEnabled ?: true,
                        weeklyDay = weeklyDay ?: 0,
                        weeklyTime = weeklyTime ?: "08:00",
                        pushNotification = pushNotification ?: true,
                        calendarLogging = calendarLogging ?: true
                    )
                    
                    val insertSql = """
                        INSERT INTO digest_preferences 
                        (user_id, daily_enabled, daily_time, weekly_enabled, weekly_day, 
                         weekly_time, push_notification, calendar_logging)
                        VALUES (?, ?, ?::time, ?, ?, ?::time, ?, ?)
                    """.trimIndent()
                    
                    conn.prepareStatement(insertSql).use { stmt ->
                        stmt.setString(1, userId)
                        stmt.setBoolean(2, prefs.dailyEnabled)
                        stmt.setString(3, prefs.dailyTime)
                        stmt.setBoolean(4, prefs.weeklyEnabled)
                        stmt.setInt(5, prefs.weeklyDay)
                        stmt.setString(6, prefs.weeklyTime)
                        stmt.setBoolean(7, prefs.pushNotification)
                        stmt.setBoolean(8, prefs.calendarLogging)
                        stmt.executeUpdate()
                    }
                }
            }
            logger.info("Updated digest preferences for user $userId")
        } catch (e: Exception) {
            logger.error("Failed to update digest preferences for user $userId", e)
            throw e
        }
    }

    /**
     * Get default preferences for a user.
     */
    private fun getDefaultPreferences(userId: String): DigestPreferences {
        return DigestPreferences(
            userId = userId,
            dailyEnabled = true,
            dailyTime = "07:00",
            weeklyEnabled = true,
            weeklyDay = 0,
            weeklyTime = "08:00",
            pushNotification = true,
            calendarLogging = true
        )
    }

    /**
     * Map ResultSet row to DigestPreferences.
     */
    private fun mapRowToPreferences(rs: ResultSet, userId: String): DigestPreferences {
        return DigestPreferences(
            userId = userId,
            dailyEnabled = rs.getBoolean("daily_enabled"),
            dailyTime = rs.getTime("daily_time")?.toString()?.take(5) ?: "07:00",
            weeklyEnabled = rs.getBoolean("weekly_enabled"),
            weeklyDay = rs.getInt("weekly_day"),
            weeklyTime = rs.getTime("weekly_time")?.toString()?.take(5) ?: "08:00",
            pushNotification = rs.getBoolean("push_notification"),
            calendarLogging = rs.getBoolean("calendar_logging")
        )
    }
}

/**
 * Data class representing user digest preferences.
 */
data class DigestPreferences(
    val userId: String,
    val dailyEnabled: Boolean,
    val dailyTime: String,      // "HH:mm" format
    val weeklyEnabled: Boolean,
    val weeklyDay: Int,          // 0=Sunday, 1=Monday, etc.
    val weeklyTime: String,      // "HH:mm" format
    val pushNotification: Boolean,
    val calendarLogging: Boolean
)
