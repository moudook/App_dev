package com.example.smarty.server.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import javax.sql.DataSource

/**
 * Status lifecycle for an interactive ask_user session.
 */
enum class ToolSessionStatus {
    PENDING, // Awaiting user response
    ANSWERED, // User submitted answers; agent resumed
    TIMED_OUT, // TTL expired before user answered
    CANCELLED, // User explicitly cancelled (app backgrounded, session closed)
}

/**
 * Minimal delta state persisted to `tool_sessions` for a single ask_user gate.
 * Kept strictly < 8 KB to avoid Postgres TOAST I/O throttling on Supabase free tier (§2.2).
 */
@Serializable
data class ToolSessionPayload(
    /** The session ID of the chat (for reconnect). */
    val chatSessionId: String,
    /** The unique tool call ID produced by the agent loop. */
    val toolCallId: String,
    /** The userId owning this session. */
    val userId: String,
    /** Questions emitted in the AskUserRequest SSE event (compact representation). */
    val questionSummaries: List<String>,
    /** ISO-8601 expiry time (30 min from creation). */
    val expiresAt: String,
)

/**
 * DB record loaded when the webhook fires (/webhook/ask_user_response).
 */
data class ToolSessionRecord(
    val id: String,
    val chatSessionId: String,
    val toolCallId: String,
    val userId: String,
    val status: ToolSessionStatus,
    val answersJson: String?,
    val expiresAt: Instant,
    val createdAt: Instant,
)

/**
 * Persists and retrieves ask_user interactive sessions from the `tool_sessions` table.
 *
 * Schema assumed (must exist in Supabase):
 * ```sql
 * CREATE TABLE IF NOT EXISTS tool_sessions (
 *   id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 *   chat_session_id UUID NOT NULL,
 *   tool_call_id TEXT NOT NULL UNIQUE,
 *   user_id      UUID NOT NULL,
 *   status       TEXT NOT NULL DEFAULT 'PENDING',
 *   payload      JSONB NOT NULL,
 *   answers_json TEXT,
 *   expires_at   TIMESTAMPTZ NOT NULL,
 *   created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
 *   updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
 * );
 * CREATE INDEX IF NOT EXISTS idx_tool_sessions_tool_call_id ON tool_sessions(tool_call_id);
 * CREATE INDEX IF NOT EXISTS idx_tool_sessions_status_expires ON tool_sessions(status, expires_at);
 * ```
 */
class ToolSessionRepository(
    private val dataSource: DataSource,
) {
    private val logger = LoggerFactory.getLogger(ToolSessionRepository::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Persist a new PENDING ask_user session.
     * Returns the generated UUID, or null on failure.
     */
    fun createPendingSession(payload: ToolSessionPayload): String? =
        try {
            dataSource.connection.use { conn ->
                val payloadJson = json.encodeToString(ToolSessionPayload.serializer(), payload)
                val expiresAt = Instant.parse(payload.expiresAt)
                val id =
                    java.util.UUID
                        .randomUUID()
                        .toString()
                conn
                    .prepareStatement(
                        """
                        INSERT INTO tool_sessions
                            (id, chat_session_id, tool_call_id, user_id, status, payload, expires_at, created_at, updated_at)
                        VALUES
                            (?::uuid, ?::uuid, ?, ?::uuid, 'PENDING', ?::jsonb, ?, now(), now())
                        ON CONFLICT (tool_call_id) DO UPDATE SET
                            status = 'PENDING',
                            payload = EXCLUDED.payload,
                            expires_at = EXCLUDED.expires_at,
                            updated_at = now()
                        """.trimIndent(),
                    ).use { stmt ->
                        stmt.setString(1, id)
                        stmt.setString(2, payload.chatSessionId)
                        stmt.setString(3, payload.toolCallId)
                        stmt.setString(4, payload.userId)
                        stmt.setString(5, payloadJson)
                        stmt.setTimestamp(6, java.sql.Timestamp.from(expiresAt))
                        stmt.executeUpdate()
                    }
                id
            }
        } catch (e: Exception) {
            logger.error("[ToolSession] Failed to create pending session for toolCallId=${payload.toolCallId}: ${e.message}", e)
            null
        }

    /**
     * Load a PENDING session by toolCallId.
     * Returns null if not found or already answered/timed out.
     */
    fun loadPendingSession(toolCallId: String): ToolSessionRecord? {
        return try {
            dataSource.connection.use { conn ->
                conn
                    .prepareStatement(
                        """
                        SELECT id, chat_session_id, tool_call_id, user_id, status, answers_json, expires_at, created_at
                        FROM tool_sessions
                        WHERE tool_call_id = ? AND status = 'PENDING'
                        LIMIT 1
                        """.trimIndent(),
                    ).use { stmt ->
                        stmt.setString(1, toolCallId)
                        stmt.executeQuery().use { rs ->
                            if (!rs.next()) return null
                            ToolSessionRecord(
                                id = rs.getString("id"),
                                chatSessionId = rs.getString("chat_session_id"),
                                toolCallId = rs.getString("tool_call_id"),
                                userId = rs.getString("user_id"),
                                status = ToolSessionStatus.valueOf(rs.getString("status")),
                                answersJson = rs.getString("answers_json"),
                                expiresAt = rs.getTimestamp("expires_at").toInstant(),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                            )
                        }
                    }
            }
        } catch (e: Exception) {
            logger.error("[ToolSession] Failed to load session for toolCallId=$toolCallId: ${e.message}", e)
            null
        }
    }

    /**
     * Mark a session as ANSWERED and store the answers JSON.
     * Returns true if exactly one row was updated.
     */
    fun markAnswered(
        toolCallId: String,
        answersJson: String,
    ): Boolean =
        try {
            dataSource.connection.use { conn ->
                val rows =
                    conn
                        .prepareStatement(
                            """
                            UPDATE tool_sessions
                            SET status = 'ANSWERED', answers_json = ?, updated_at = now()
                            WHERE tool_call_id = ? AND status = 'PENDING' AND expires_at > now()
                            """.trimIndent(),
                        ).use { stmt ->
                            stmt.setString(1, answersJson)
                            stmt.setString(2, toolCallId)
                            stmt.executeUpdate()
                        }
                rows == 1
            }
        } catch (e: Exception) {
            logger.error("[ToolSession] Failed to mark ANSWERED for toolCallId=$toolCallId: ${e.message}", e)
            false
        }

    /**
     * Mark a session as CANCELLED (app went to background, user dismissed).
     */
    fun markCancelled(toolCallId: String): Boolean =
        try {
            dataSource.connection.use { conn ->
                conn
                    .prepareStatement(
                        """
                        UPDATE tool_sessions
                        SET status = 'CANCELLED', updated_at = now()
                        WHERE tool_call_id = ? AND status = 'PENDING'
                        """.trimIndent(),
                    ).use { stmt ->
                        stmt.setString(1, toolCallId)
                        stmt.executeUpdate() == 1
                    }
            }
        } catch (e: Exception) {
            logger.error("[ToolSession] Failed to mark CANCELLED for toolCallId=$toolCallId: ${e.message}", e)
            false
        }

    /**
     * Sweep expired PENDING sessions, marking them TIMED_OUT.
     * Called by RegistryReaper every 5 minutes.
     * Returns count of swept rows.
     */
    fun sweepExpired(): Int =
        try {
            dataSource.connection.use { conn ->
                conn
                    .prepareStatement(
                        """
                        UPDATE tool_sessions
                        SET status = 'TIMED_OUT', updated_at = now()
                        WHERE status = 'PENDING' AND expires_at <= now()
                        """.trimIndent(),
                    ).use { it.executeUpdate() }
            }
        } catch (e: Exception) {
            logger.warn("[ToolSession] Failed to sweep expired sessions: ${e.message}")
            0
        }

    /**
     * Poll for a session's answers (used after the SSE turn ends to wait for the webhook).
     * Returns the answers JSON, or null if not yet answered/timed out.
     */
    fun pollForAnswer(toolCallId: String): String? {
        return try {
            dataSource.connection.use { conn ->
                conn
                    .prepareStatement(
                        """
                        SELECT status, answers_json FROM tool_sessions
                        WHERE tool_call_id = ?
                        LIMIT 1
                        """.trimIndent(),
                    ).use { stmt ->
                        stmt.setString(1, toolCallId)
                        stmt.executeQuery().use { rs ->
                            if (!rs.next()) return null
                            when (rs.getString("status")) {
                                "ANSWERED" -> rs.getString("answers_json")
                                "TIMED_OUT", "CANCELLED" -> "" // Empty string signals terminal state
                                else -> null // Still PENDING
                            }
                        }
                    }
            }
        } catch (e: Exception) {
            logger.warn("[ToolSession] pollForAnswer failed for toolCallId=$toolCallId: ${e.message}")
            null
        }
    }
}
