package com.example.smarty.server.services

import com.example.smarty.server.data.ChatRepository
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.LlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Service for generating daily and weekly digests.
 * 
 * Daily Digest: Runs at 7 AM (configurable) to analyze previous day's activity
 * - Gathers notes, chats, memories from previous 24 hours
 * - AI generates summary with insights, goal progress, priorities
 * - Identifies critical information
 * - Sends push notification
 * - Creates calendar event
 * 
 * Weekly Digest: Runs on Sunday to analyze past 7 days
 * - Aggregates all daily activity
 * - Identifies patterns and trends
 * - Provides weekly summary
 */
class DigestService(
    private val dataSource: DataSource,
    private val chatRepository: ChatRepository,
    private val vectorStore: PostgresVectorStore,
    private val llmProvider: LlmProvider
) {
    private val logger = LoggerFactory.getLogger(DigestService::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ============================================================================
    // DATA MODELS
    // ============================================================================

    @Serializable
    data class DigestContent(
        val summary: String,
        val keyInsights: List<String> = emptyList(),
        val goalsProgress: List<GoalProgress> = emptyList(),
        val priorities: List<String> = emptyList(),
        val criticalInfo: String? = null
    )

    @Serializable
    data class GoalProgress(
        val goal: String,
        val status: String,
        val updates: List<String> = emptyList()
    )

    @Serializable
    data class DigestResult(
        val id: String,
        val userId: String,
        val digestDate: String,
        val digestType: String,
        val summary: String,
        val keyInsights: List<String>,
        val goalsProgress: List<GoalProgress>,
        val priorities: List<String>,
        val criticalInfo: String?,
        val notesAnalyzed: Int,
        val chatsAnalyzed: Int,
        val memoriesAnalyzed: Int
    )

    // ============================================================================
    // DIGEST GENERATION
    // ============================================================================

    /**
     * Generate a daily digest for a specific user.
     * Called by the scheduler at the configured time (default 7 AM).
     */
    suspend fun generateDailyDigest(
        userId: String,
        targetDate: LocalDate = LocalDate.now().minusDays(1),
        userTimezone: String = "UTC"
    ): DigestResult? = withContext(Dispatchers.IO) {
        logger.info("Generating daily digest for user $userId, date: $targetDate")

        // Check if digest already exists
        if (digestExists(userId, targetDate, "daily")) {
            logger.info("Daily digest already exists for user $userId on $targetDate")
            return@withContext getDigestByDate(userId, targetDate, "daily")
        }

        // Gather data from previous day
        val startTime = targetDate.atStartOfDay(ZoneId.of(userTimezone))
        val endTime = targetDate.plusDays(1).atStartOfDay(ZoneId.of(userTimezone))

        val notes = getNotesInTimeRange(userId, startTime.toInstant().toEpochMilli(), endTime.toInstant().toEpochMilli())
        val chats = getChatsInTimeRange(userId, startTime.toInstant().toEpochMilli(), endTime.toInstant().toEpochMilli())
        val memories = getMemoriesInTimeRange(userId, startTime.toInstant().toEpochMilli(), endTime.toInstant().toEpochMilli())

        if (notes.isEmpty() && chats.isEmpty() && memories.isEmpty()) {
            logger.info("No activity found for user $userId on $targetDate, skipping digest")
            return@withContext null
        }

        // Generate AI digest
        val digestContent = generateDigestWithAI(
            userId = userId,
            digestType = "daily",
            notes = notes,
            chats = chats,
            memories = memories,
            targetDate = targetDate
        )

        // Save to database
        val digestId = saveDigest(
            userId = userId,
            digestDate = targetDate,
            digestType = "daily",
            content = digestContent,
            notesAnalyzed = notes.size,
            chatsAnalyzed = chats.size,
            memoriesAnalyzed = memories.size
        )

        logger.info("Daily digest generated for user $userId: $digestId")

        DigestResult(
            id = digestId,
            userId = userId,
            digestDate = targetDate.toString(),
            digestType = "daily",
            summary = digestContent.summary,
            keyInsights = digestContent.keyInsights,
            goalsProgress = digestContent.goalsProgress,
            priorities = digestContent.priorities,
            criticalInfo = digestContent.criticalInfo,
            notesAnalyzed = notes.size,
            chatsAnalyzed = chats.size,
            memoriesAnalyzed = memories.size
        )
    }

    /**
     * Generate a weekly digest for a specific user.
     * Called by the scheduler on the configured day (default Sunday).
     */
    suspend fun generateWeeklyDigest(
        userId: String,
        weekEndDate: LocalDate = LocalDate.now().minusDays(1),
        userTimezone: String = "UTC"
    ): DigestResult? = withContext(Dispatchers.IO) {
        logger.info("Generating weekly digest for user $userId, week ending: $weekEndDate")

        val weekStartDate = weekEndDate.minusDays(6) // 7 days including end date

        // Check if digest already exists
        if (digestExists(userId, weekEndDate, "weekly")) {
            logger.info("Weekly digest already exists for user $userId for week ending $weekEndDate")
            return@withContext getDigestByDate(userId, weekEndDate, "weekly")
        }

        // Gather data from the week
        val startTime = weekStartDate.atStartOfDay(ZoneId.of(userTimezone))
        val endTime = weekEndDate.plusDays(1).atStartOfDay(ZoneId.of(userTimezone))

        val notes = getNotesInTimeRange(userId, startTime.toInstant().toEpochMilli(), endTime.toInstant().toEpochMilli())
        val chats = getChatsInTimeRange(userId, startTime.toInstant().toEpochMilli(), endTime.toInstant().toEpochMilli())
        val memories = getMemoriesInTimeRange(userId, startTime.toInstant().toEpochMilli(), endTime.toInstant().toEpochMilli())

        if (notes.isEmpty() && chats.isEmpty() && memories.isEmpty()) {
            logger.info("No activity found for user $userId for week ending $weekEndDate, skipping digest")
            return@withContext null
        }

        // Generate AI digest
        val digestContent = generateDigestWithAI(
            userId = userId,
            digestType = "weekly",
            notes = notes,
            chats = chats,
            memories = memories,
            targetDate = weekEndDate,
            startDate = weekStartDate
        )

        // Save to database
        val digestId = saveDigest(
            userId = userId,
            digestDate = weekEndDate,
            digestType = "weekly",
            content = digestContent,
            notesAnalyzed = notes.size,
            chatsAnalyzed = chats.size,
            memoriesAnalyzed = memories.size
        )

        logger.info("Weekly digest generated for user $userId: $digestId")

        DigestResult(
            id = digestId,
            userId = userId,
            digestDate = weekEndDate.toString(),
            digestType = "weekly",
            summary = digestContent.summary,
            keyInsights = digestContent.keyInsights,
            goalsProgress = digestContent.goalsProgress,
            priorities = digestContent.priorities,
            criticalInfo = digestContent.criticalInfo,
            notesAnalyzed = notes.size,
            chatsAnalyzed = chats.size,
            memoriesAnalyzed = memories.size
        )
    }

    // ============================================================================
    // AI DIGEST GENERATION
    // ============================================================================

    private suspend fun generateDigestWithAI(
        userId: String,
        digestType: String,
        notes: List<NoteData>,
        chats: List<ChatData>,
        memories: List<MemoryData>,
        targetDate: LocalDate,
        startDate: LocalDate? = null
    ): DigestContent = withContext(Dispatchers.IO) {
        
        val dateRange = if (startDate != null) {
            "$startDate to $targetDate"
        } else {
            targetDate.toString()
        }

        val systemPrompt = buildString {
            append("You are Friday, an AI assistant that helps users understand their daily activity.\n")
            append("Analyze the following data and generate a ${digestType} digest.\n\n")
            append("## Your Task:\n")
            append("1. Summarize the key activities and insights from the provided data\n")
            append("2. Identify any goals mentioned and track their progress\n")
            append("3. Highlight priorities that emerged\n")
            append("4. Flag any CRITICAL information that needs immediate attention\n\n")
            append("## Response Format (JSON):\n")
            append("```json\n")
            append("{\n")
            append("  \"summary\": \"A 2-3 sentence overview of the day/week\",\n")
            append("  \"keyInsights\": [\"insight 1\", \"insight 2\", ...],\n")
            append("  \"goalsProgress\": [\n")
            append("    {\"goal\": \"goal name\", \"status\": \"on-track|at-risk|completed\", \"updates\": [\"update 1\"]}\n")
            append("  ],\n")
            append("  \"priorities\": [\"priority 1\", \"priority 2\", ...],\n")
            append("  \"criticalInfo\": \"Any urgent information, or null if none\"\n")
            append("}\n")
            append("```\n\n")
            append("## Important:\n")
            append("- Be concise but insightful\n")
            append("- Focus on what matters most to the user\n")
            append("- If critical info exists, explain clearly why it's critical\n")
            append("- Return ONLY valid JSON, no markdown or explanation\n")
        }

        val userPrompt = buildString {
            append("## Data for $dateRange\n\n")
            
            append("### Notes (${notes.size}):\n")
            notes.take(20).forEach { note ->
                append("- [${note.category ?: "uncategorized"}] ${note.title}: ${note.content.take(200)}\n")
            }
            if (notes.size > 20) append("... and ${notes.size - 20} more notes\n")
            
            append("\n### Chat Sessions (${chats.size}):\n")
            chats.take(10).forEach { chat ->
                append("- ${chat.title ?: "Untitled"}: ${chat.preview?.take(150) ?: "No preview"}\n")
            }
            if (chats.size > 10) append("... and ${chats.size - 10} more chats\n")
            
            append("\n### Memories (${memories.size}):\n")
            memories.take(15).forEach { memory ->
                append("- [${memory.type}] ${memory.content.take(150)}\n")
            }
            if (memories.size > 15) append("... and ${memories.size - 15} more memories\n")
            
            append("\nGenerate the digest now.")
        }

        val messages = listOf(
            LlmMessage(role = LlmMessage.Role.SYSTEM, content = systemPrompt),
            LlmMessage(role = LlmMessage.Role.USER, content = userPrompt)
        )

        try {
            val response = llmProvider.generate(messages).content ?: ""
            parseDigestResponse(response)
        } catch (e: Exception) {
            logger.error("Failed to generate digest with AI: ${e.message}", e)
            // Return a basic digest on failure
            DigestContent(
                summary = "Analyzed ${notes.size} notes, ${chats.size} chats, and ${memories.size} memories for $dateRange.",
                keyInsights = emptyList(),
                goalsProgress = emptyList(),
                priorities = emptyList(),
                criticalInfo = null
            )
        }
    }

    private fun parseDigestResponse(response: String): DigestContent {
        return try {
            // Extract JSON from response (handle markdown code blocks)
            val jsonStr = response
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val parsed = json.parseToJsonElement(jsonStr).jsonObject

            DigestContent(
                summary = parsed["summary"]?.jsonPrimitive?.content ?: "",
                keyInsights = parsed["keyInsights"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                goalsProgress = parsed["goalsProgress"]?.jsonArray?.map { goalJson ->
                    val goalObj = goalJson.jsonObject
                    GoalProgress(
                        goal = goalObj["goal"]?.jsonPrimitive?.content ?: "",
                        status = goalObj["status"]?.jsonPrimitive?.content ?: "unknown",
                        updates = goalObj["updates"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                    )
                } ?: emptyList(),
                priorities = parsed["priorities"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                criticalInfo = parsed["criticalInfo"]?.jsonPrimitive?.content?.takeIf { it != "null" }
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse digest response: ${e.message}")
            DigestContent(
                summary = response.take(500),
                keyInsights = emptyList(),
                goalsProgress = emptyList(),
                priorities = emptyList(),
                criticalInfo = null
            )
        }
    }

    // ============================================================================
    // DATA GATHERING
    // ============================================================================

    data class NoteData(
        val id: String,
        val title: String,
        val content: String,
        val category: String?,
        val createdAt: Long
    )

    data class ChatData(
        val id: String,
        val title: String?,
        val preview: String?,
        val createdAt: Long
    )

    data class MemoryData(
        val id: String,
        val type: String,
        val content: String,
        val createdAt: Long
    )

    private suspend fun getNotesInTimeRange(userId: String, startTime: Long, endTime: Long): List<NoteData> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                SELECT id, title, content, category, created_at
                FROM notes
                WHERE user_id = ?
                AND created_at >= to_timestamp(? / 1000.0)
                AND created_at < to_timestamp(? / 1000.0)
                AND is_archived = FALSE
                ORDER BY created_at DESC
            """
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, userId)
                stmt.setLong(2, startTime)
                stmt.setLong(3, endTime)
                stmt.executeQuery().use { rs ->
                    val notes = mutableListOf<NoteData>()
                    while (rs.next()) {
                        notes.add(NoteData(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            content = rs.getString("content"),
                            category = rs.getString("category"),
                            createdAt = rs.getTimestamp("created_at").time
                        ))
                    }
                    notes
                }
            }
        }
    }

    private suspend fun getChatsInTimeRange(userId: String, startTime: Long, endTime: Long): List<ChatData> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                SELECT s.id, s.title, m.content as preview, s.created_at
                FROM chat_sessions s
                LEFT JOIN chat_messages m ON s.id = m.session_id AND m.role = 'USER'
                WHERE s.user_id = ? 
                AND s.created_at >= to_timestamp(? / 1000.0)
                AND s.created_at < to_timestamp(? / 1000.0)
                ORDER BY s.created_at DESC
            """
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, userId)
                stmt.setLong(2, startTime)
                stmt.setLong(3, endTime)
                stmt.executeQuery().use { rs ->
                    val chats = mutableListOf<ChatData>()
                    while (rs.next()) {
                        chats.add(ChatData(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            preview = rs.getString("preview"),
                            createdAt = rs.getTimestamp("created_at").time
                        ))
                    }
                    chats
                }
            }
        }
    }

    private suspend fun getMemoriesInTimeRange(userId: String, startTime: Long, endTime: Long): List<MemoryData> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                SELECT id, content, metadata->>'type' as type, created_at
                FROM agent_context
                WHERE user_id = ?
                AND created_at >= to_timestamp(? / 1000.0)
                AND created_at < to_timestamp(? / 1000.0)
                ORDER BY created_at DESC
            """
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, userId)
                stmt.setLong(2, startTime)
                stmt.setLong(3, endTime)
                stmt.executeQuery().use { rs ->
                    val memories = mutableListOf<MemoryData>()
                    while (rs.next()) {
                        memories.add(MemoryData(
                            id = rs.getString("id"),
                            type = rs.getString("type") ?: "memory",
                            content = rs.getString("content"),
                            createdAt = rs.getTimestamp("created_at").time
                        ))
                    }
                    memories
                }
            }
        }
    }

    // ============================================================================
    // DATABASE OPERATIONS
    // ============================================================================

    private suspend fun digestExists(userId: String, date: LocalDate, type: String): Boolean = withContext(Dispatchers.IO) {
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

    private suspend fun getDigestByDate(userId: String, date: LocalDate, type: String): DigestResult? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                SELECT id, user_id, digest_date, digest_type, summary, key_insights, goals_progress,
                       priorities, critical_info, notes_analyzed, chats_analyzed, memories_analyzed
                FROM daily_digests
                WHERE user_id = ? AND digest_date = ? AND digest_type = ?
            """
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, userId)
                stmt.setDate(2, java.sql.Date.valueOf(date))
                stmt.setString(3, type)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        parseDigestResult(rs)
                    } else null
                }
            }
        }
    }

    private suspend fun saveDigest(
        userId: String,
        digestDate: LocalDate,
        digestType: String,
        content: DigestContent,
        notesAnalyzed: Int,
        chatsAnalyzed: Int,
        memoriesAnalyzed: Int
    ): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        
        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO daily_digests 
                (id, user_id, digest_date, digest_type, summary, key_insights, goals_progress,
                 priorities, critical_info, notes_analyzed, chats_analyzed, memories_analyzed)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
            """
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(id))
                stmt.setString(2, userId)
                stmt.setDate(3, java.sql.Date.valueOf(digestDate))
                stmt.setString(4, digestType)
                stmt.setString(5, content.summary)
                stmt.setString(6, json.encodeToString(content.keyInsights))
                stmt.setString(7, json.encodeToString(content.goalsProgress))
                stmt.setString(8, json.encodeToString(content.priorities))
                stmt.setString(9, content.criticalInfo)
                stmt.setInt(10, notesAnalyzed)
                stmt.setInt(11, chatsAnalyzed)
                stmt.setInt(12, memoriesAnalyzed)
                stmt.executeUpdate()
            }
        }
        
        id
    }

    private fun parseDigestResult(rs: ResultSet): DigestResult {
        return DigestResult(
            id = rs.getString("id"),
            userId = rs.getString("user_id"),
            digestDate = rs.getDate("digest_date").toString(),
            digestType = rs.getString("digest_type"),
            summary = rs.getString("summary"),
            keyInsights = try { json.decodeFromString(rs.getString("key_insights") ?: "[]") } catch (e: Exception) { emptyList() },
            goalsProgress = try { json.decodeFromString(rs.getString("goals_progress") ?: "[]") } catch (e: Exception) { emptyList() },
            priorities = try { json.decodeFromString(rs.getString("priorities") ?: "[]") } catch (e: Exception) { emptyList() },
            criticalInfo = rs.getString("critical_info"),
            notesAnalyzed = rs.getInt("notes_analyzed"),
            chatsAnalyzed = rs.getInt("chats_analyzed"),
            memoriesAnalyzed = rs.getInt("memories_analyzed")
        )
    }

    // ============================================================================
    // PUBLIC API
    // ============================================================================

    /**
     * Get all digests for a user, ordered by date descending.
     */
    suspend fun getDigestsForUser(userId: String, limit: Int = 30): List<DigestResult> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                SELECT id, user_id, digest_date, digest_type, summary, key_insights, goals_progress,
                       priorities, critical_info, notes_analyzed, chats_analyzed, memories_analyzed
                FROM daily_digests
                WHERE user_id = ?
                ORDER BY digest_date DESC
                LIMIT ?
            """
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, userId)
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    val digests = mutableListOf<DigestResult>()
                    while (rs.next()) {
                        digests.add(parseDigestResult(rs))
                    }
                    digests
                }
            }
        }
    }

    /**
     * Get a specific digest by ID.
     */
    suspend fun getDigestById(userId: String, digestId: String): DigestResult? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                SELECT id, user_id, digest_date, digest_type, summary, key_insights, goals_progress,
                       priorities, critical_info, notes_analyzed, chats_analyzed, memories_analyzed
                FROM daily_digests
                WHERE id = ? AND user_id = ?
            """
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(digestId))
                stmt.setString(2, userId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) parseDigestResult(rs) else null
                }
            }
        }
    }

    /**
     * Mark digest notification as sent.
     */
    suspend fun markNotificationSent(digestId: String) = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "UPDATE daily_digests SET notification_sent = TRUE WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(digestId))
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Update calendar event ID for a digest.
     */
    suspend fun setCalendarEventId(digestId: String, eventId: String) = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "UPDATE daily_digests SET calendar_event_id = ? WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, eventId)
                stmt.setObject(2, UUID.fromString(digestId))
                stmt.executeUpdate()
            }
        }
    }
}
