package com.example.smarty.server.services

import com.example.smarty.server.agent.ActiveSessionManager
import com.example.smarty.server.data.ChatRepository
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.llm.LlmProvider
import io.ktor.server.application.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * Scheduler for daily and weekly digest generation.
 * 
 * Runs as a background coroutine within the Ktor server:
 * - Daily digest: Triggers at configured time (default 7 AM) for each user
 * - Weekly digest: Triggers on configured day (default Sunday) at configured time
 * 
 * The scheduler:
 * 1. Queries all users with digest preferences
 * 2. Checks if digest is due based on user's timezone
 * 3. Checks if user has active agent session (skips if so)
 * 4. Generates digest via DigestService
 * 5. Sends push notification via FCM
 * 6. Creates calendar event if enabled
 */
class DigestScheduler(
    private val application: Application,
    private val dataSource: DataSource,
    private val digestService: DigestService,
    private val fcmService: FcmNotificationService?
) {
    private val logger = LoggerFactory.getLogger(DigestScheduler::class.java)
    private val schedulerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Track users currently being processed to avoid duplicates
    private val processingUsers = ConcurrentHashMap<String, Boolean>()
    
    // Check interval (5 minutes)
    private val checkIntervalMs = 5 * 60 * 1000L

    /**
     * Start the scheduler. Called on server startup.
     */
    fun start() {
        logger.info("Starting Digest Scheduler")
        
        // Main scheduler loop
        schedulerScope.launch {
            while (isActive) {
                try {
                    checkAndGenerateDigests()
                } catch (e: Exception) {
                    logger.error("Error in digest scheduler: ${e.message}", e)
                }
                delay(checkIntervalMs)
            }
        }
    }

    /**
     * Stop the scheduler. Called on server shutdown.
     */
    fun stop() {
        logger.info("Stopping Digest Scheduler")
        schedulerScope.cancel()
    }

    /**
     * Main check loop - runs every 5 minutes.
     * Checks all users and generates digests if due.
     */
    private suspend fun checkAndGenerateDigests() {
        val now = ZonedDateTime.now(ZoneId.of("UTC"))
        logger.debug("Checking digests at $now")

        // Skip if any user has an active agent session
        if (ActiveSessionManager.hasAnyActiveSession()) {
            logger.debug("Skipping digest check - active agent session detected")
            return
        }

        // Get all users with digest preferences
        val usersWithPrefs = getUsersWithDigestPreferences()

        for (userPref in usersWithPrefs) {
            // Skip if user has active session
            if (ActiveSessionManager.hasActiveSession(userPref.userId)) {
                logger.debug("Skipping digest for user ${userPref.userId} - active session")
                continue
            }
            
            // Skip if already processing this user
            if (processingUsers.containsKey(userPref.userId)) {
                continue
            }

            // Check if daily digest is due
            if (userPref.dailyEnabled && isDigestDue(now, userPref.dailyTime, userPref.timezone, "daily", userPref.userId)) {
                processingUsers[userPref.userId] = true
                schedulerScope.launch {
                    try {
                        generateDailyDigestForUser(userPref)
                    } finally {
                        processingUsers.remove(userPref.userId)
                    }
                }
            }

            // Check if weekly digest is due
            if (userPref.weeklyEnabled && isWeeklyDigestDue(now, userPref)) {
                processingUsers[userPref.userId] = true
                schedulerScope.launch {
                    try {
                        generateWeeklyDigestForUser(userPref)
                    } finally {
                        processingUsers.remove(userPref.userId)
                    }
                }
            }
        }
    }

    /**
     * Check if a digest is due based on current time and user preferences.
     */
    private suspend fun isDigestDue(
        now: ZonedDateTime,
        scheduledTime: LocalTime,
        userTimezone: String,
        digestType: String,
        userId: String
    ): Boolean {
        // Skip if user has active session
        if (ActiveSessionManager.hasActiveSession(userId)) {
            return false
        }
        
        val userNow = now.withZoneSameInstant(ZoneId.of(userTimezone))
        val userScheduledToday = userNow.with(scheduledTime)
        
        // Check if we're within 5 minutes of the scheduled time
        val diff = Duration.between(userScheduledToday, userNow).abs()
        if (diff.toMinutes() > 5) return false

        // Check if we already generated today's digest
        val today = userNow.toLocalDate()
        return !digestExistsForDate(userId, today, digestType)
    }

/**
     * Check if weekly digest is due.
     */
    private suspend fun isWeeklyDigestDue(now: ZonedDateTime, userPref: UserDigestPreferences): Boolean {
        // Skip if user has active session
        if (ActiveSessionManager.hasActiveSession(userPref.userId)) {
            return false
        }
        
        val userNow = now.withZoneSameInstant(ZoneId.of(userPref.timezone))
        
        // Check if today is the configured day (0=Sunday, 1=Monday, etc.)
        if (userNow.dayOfWeek.value % 7 != userPref.weeklyDay) return false

        return isDigestDue(now, userPref.weeklyTime, userPref.timezone, "weekly", userPref.userId)
    }

    /**
     * Generate daily digest for a specific user.
     */
    private suspend fun generateDailyDigestForUser(userPref: UserDigestPreferences) {
        logger.info("Generating daily digest for user ${userPref.userId}")
        
        val yesterday = LocalDate.now(ZoneId.of(userPref.timezone)).minusDays(1)
        
        val result = digestService.generateDailyDigest(
            userId = userPref.userId,
            targetDate = yesterday,
            userTimezone = userPref.timezone
        )

        if (result != null) {
            // Send push notification if enabled
            if (userPref.pushNotification && fcmService != null) {
                sendPushNotification(userPref.userId, result, "daily")
            }

            // Create calendar event if enabled
            if (userPref.calendarLogging) {
                createCalendarEvent(userPref.userId, result)
            }
        }
    }

    /**
     * Generate weekly digest for a specific user.
     */
    private suspend fun generateWeeklyDigestForUser(userPref: UserDigestPreferences) {
        logger.info("Generating weekly digest for user ${userPref.userId}")
        
        val weekEnd = LocalDate.now(ZoneId.of(userPref.timezone)).minusDays(1)
        
        val result = digestService.generateWeeklyDigest(
            userId = userPref.userId,
            weekEndDate = weekEnd,
            userTimezone = userPref.timezone
        )

        if (result != null) {
            // Send push notification if enabled
            if (userPref.pushNotification && fcmService != null) {
                sendPushNotification(userPref.userId, result, "weekly")
            }

            // Create calendar event if enabled
            if (userPref.calendarLogging) {
                createCalendarEvent(userPref.userId, result)
            }
        }
    }

    /**
     * Send push notification via FCM.
     */
    private suspend fun sendPushNotification(userId: String, digest: DigestService.DigestResult, type: String) {
        try {
            val title = if (type == "weekly") {
                "📊 Your Weekly Summary"
            } else {
                "☀️ Good Morning! Here's your daily summary"
            }

            val body = if (digest.criticalInfo != null) {
                "${digest.summary.take(100)}... ⚠️ Critical info included!"
            } else {
                digest.summary.take(150)
            }

            fcmService?.sendNotification(
                userId = userId,
                title = title,
                body = body,
                data = mapOf(
                    "type" to "digest",
                    "digestId" to digest.id,
                    "digestType" to type,
                    "clickAction" to "OPEN_DIGEST"
                )
            )

            digestService.markNotificationSent(digest.id)
            logger.info("Sent $type digest notification to user $userId")
        } catch (e: Exception) {
            logger.error("Failed to send digest notification: ${e.message}", e)
        }
    }

    /**
     * Create calendar event for the digest.
     */
    private suspend fun createCalendarEvent(userId: String, digest: DigestService.DigestResult) {
        try {
            dataSource.connection.use { conn ->
                val eventTitle = "Smarty ${digest.digestType.replaceFirstChar { it.uppercase() }} Digest"
                val eventDescription = digest.summary.take(500)

                val now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                val eventStart = now.toLocalDate().atTime(22, 0)
                    .atOffset(java.time.ZoneOffset.UTC)
                val eventEnd = eventStart.plusHours(1)

                val insertSql = """
                    INSERT INTO calendar_events
                    (user_id, title, description, start_time, end_time, is_all_day, status, visibility,
                     reminders, attendees, metadata, created_at, updated_at)
                    VALUES (?::uuid, ?, ?, ?, ?, false, 'confirmed', 'private', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, now(), now())
                """.trimIndent()

                conn.prepareStatement(insertSql).use { stmt ->
                    stmt.setObject(1, java.util.UUID.fromString(userId))
                    stmt.setString(2, eventTitle)
                    stmt.setString(3, eventDescription)
                    stmt.setObject(4, eventStart.toInstant().let { java.sql.Timestamp.from(it) })
                    stmt.setObject(5, eventEnd.toInstant().let { java.sql.Timestamp.from(it) })
                    stmt.executeUpdate()
                }

                logger.info("Created calendar event for digest ${digest.id} for user $userId")
            }
        } catch (e: Exception) {
            logger.error("Failed to create calendar event for digest ${digest.id}: ${e.message}", e)
        }
    }

    // ============================================================================
    // DATABASE QUERIES
    // ============================================================================

    data class UserDigestPreferences(
        val userId: String,
        val dailyEnabled: Boolean,
        val dailyTime: LocalTime,
        val weeklyEnabled: Boolean,
        val weeklyDay: Int,
        val weeklyTime: LocalTime,
        val pushNotification: Boolean,
        val calendarLogging: Boolean,
        val timezone: String
    )

    private suspend fun getUsersWithDigestPreferences(): List<UserDigestPreferences> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            try {
                conn.createStatement().use { stmt ->
                    val sql = """
                        SELECT user_id, enabled, frequency,
                               delivery_hour, delivery_minute, timezone
                        FROM digest_preferences
                        WHERE enabled = TRUE
                    """.trimIndent()
                    stmt.executeQuery(sql).use { rs ->
                        val prefs = mutableListOf<UserDigestPreferences>()
                        while (rs.next()) {
                            val frequency = rs.getString("frequency") ?: "daily"
                            val hour = rs.getInt("delivery_hour").takeIf { !rs.wasNull() } ?: 8
                            val minute = rs.getInt("delivery_minute").takeIf { !rs.wasNull() } ?: 0
                            val deliveryTime = LocalTime.of(hour, minute)
                            prefs.add(UserDigestPreferences(
                                userId = rs.getString("user_id"),
                                dailyEnabled = frequency == "daily",
                                dailyTime = deliveryTime,
                                weeklyEnabled = frequency == "weekly",
                                weeklyDay = 1, // Default to Monday
                                weeklyTime = deliveryTime,
                                pushNotification = false, // Not in v6 schema
                                calendarLogging = false,  // Not in v6 schema
                                timezone = rs.getString("timezone") ?: "UTC"
                            ))
                        }
                        prefs
                    }
                }
            } catch (e: Exception) {
                logger.warn("digest_preferences table not available: ${e.message}")
                emptyList()
            }
        }
    }

    private suspend fun digestExistsForDate(userId: String, date: LocalDate, type: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "SELECT 1 FROM daily_digests WHERE user_id = ? AND digest_date = ? AND digest_type = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, userId)
                stmt.setDate(2, java.sql.Date.valueOf(date))
                stmt.setString(3, type)
                stmt.executeQuery().next()
            }
        }
    }

    // ============================================================================
    // MANUAL TRIGGER (for testing)
    // ============================================================================

    /**
     * Manually trigger digest generation for a user.
     * Useful for testing or on-demand generation.
     */
    suspend fun triggerDigestForUser(userId: String, type: String = "daily"): DigestService.DigestResult? {
        val userPref = getUserPreferences(userId) ?: return null
        
        return if (type == "weekly") {
            val weekEnd = LocalDate.now(ZoneId.of(userPref.timezone)).minusDays(1)
            digestService.generateWeeklyDigest(userId, weekEnd, userPref.timezone)
        } else {
            val yesterday = LocalDate.now(ZoneId.of(userPref.timezone)).minusDays(1)
            digestService.generateDailyDigest(userId, yesterday, userPref.timezone)
        }
    }

    private suspend fun getUserPreferences(userId: String): UserDigestPreferences? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                SELECT user_id, enabled, frequency, delivery_hour, delivery_minute, timezone
                FROM digest_preferences
                WHERE user_id = ?
            """
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, java.util.UUID.fromString(userId))
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val hour = rs.getInt("delivery_hour").takeIf { !rs.wasNull() } ?: 8
                        val minute = rs.getInt("delivery_minute").takeIf { !rs.wasNull() } ?: 0
                        val deliveryTime = LocalTime.of(hour, minute)
                        val frequency = rs.getString("frequency") ?: "daily"
                        UserDigestPreferences(
                            userId = rs.getString("user_id"),
                            dailyEnabled = frequency == "daily",
                            dailyTime = deliveryTime,
                            weeklyEnabled = frequency == "weekly",
                            weeklyDay = 1, // Default to Monday
                            weeklyTime = deliveryTime,
                            pushNotification = false, // Not in v6 schema
                            calendarLogging = false,  // Not in v6 schema
                            timezone = rs.getString("timezone") ?: "UTC"
                        )
                    } else null
                }
            }
        }
    }
}
