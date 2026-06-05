package com.example.smarty.server.data

import com.example.smarty.agent.permissions.ToolPermissionDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * Effective decision for a (user, tool) pair, returned by
 * [PermissionRepository.resolveEffectiveDecision]. Combines the
 * per-user override in `tool_permissions` with the static
 * `SMARTY_DEFAULT` policy.
 */
data class EffectiveDecision(
    val userId: String,
    val toolName: String,
    val decision: ToolPermissionDecision,
    /** True iff the decision was sourced from a `tool_permissions` override. */
    val isOverridden: Boolean,
    /** Source of the override (if any): `default`, `user_request`, `admin`, `revoked`. */
    val overrideSource: String?,
    /** When the override was last updated (if any). */
    val overrideUpdatedAt: Instant?,
    /** When the override expires (if any). Soft expiry — if past, treated as INHERIT. */
    val overrideExpiresAt: Instant?,
)

/**
 * Repository for per-user tool permission overrides and the
 * append-only permission audit log. Backed by the Supabase
 * `tool_permissions` and `permission_audit_log` tables (see
 * `SUPABASE_SCHEMA.sql` v11.1.0).
 *
 * Two responsibilities:
 *  1. [resolveEffectiveDecision] — merge a user's `tool_permissions`
 *     override with the static `SMARTY_DEFAULT` policy. Cached in
 *     memory for [cacheTtlMs] per (user, tool) pair so a busy
 *     session doesn't re-query the DB on every tool invocation.
 *  2. [logDecision] — append a row to `permission_audit_log`.
 *     Best-effort: a failure here must NOT break the user's
 *     tool flow (we log and move on).
 *
 * Thread-safe: backed by a [DataSource] and an in-process cache
 * guarded by a [Mutex]. Safe to share as a singleton.
 */
class PermissionRepository(
    private val dataSource: DataSource?,
) {
    private val logger = LoggerFactory.getLogger(PermissionRepository::class.java)

    /** How long a (user, tool) decision is cached before re-fetching. */
    private val cacheTtlMs: Long = 30_000L

    private data class CacheEntry(
        val decision: EffectiveDecision,
        val cachedAt: Long,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val cacheMutex = Mutex()

    private fun cacheKey(
        userId: String,
        toolName: String,
    ) = "$userId::$toolName"

    /**
     * Returns the effective decision for (userId, toolName). The
     * precedence chain is:
     *  1. `tool_permissions` override with `decision` in (ALLOW, DENY)
     *     AND `expires_at` not in the past → that decision, marked
     *     `isOverridden = true`.
     *  2. `tool_permissions` row with `decision = INHERIT` OR an
     *     expired `expires_at` → fall through to the static policy.
     *  3. Static `SMARTY_DEFAULT` policy → ALLOW / DENY / DEFAULT.
     *  4. Unknown tool → DEFAULT (let the CLI surface the prompt).
     *
     * Results are cached in-process for [cacheTtlMs] per (user, tool).
     */
    suspend fun resolveEffectiveDecision(
        userId: String,
        toolName: String,
    ): EffectiveDecision =
        withContext(Dispatchers.IO) {
            val key = cacheKey(userId, toolName)
            val now = System.currentTimeMillis()

            cacheMutex.withLock {
                val cached = cache[key]
                if (cached != null && (now - cached.cachedAt) < cacheTtlMs) {
                    return@withContext cached.decision
                }
            }

            val fresh = loadFromDb(userId, toolName)
            cacheMutex.withLock { cache[key] = CacheEntry(fresh, now) }
            fresh
        }

    private fun loadFromDb(
        userId: String,
        toolName: String,
    ): EffectiveDecision {
        val staticDecision =
            com.example.smarty.agent.permissions.ToolPermissionPolicy
                .SMARTY_DEFAULT
                .decide(toolName)

        val ds = dataSource
        if (ds == null) {
            return EffectiveDecision(
                userId = userId,
                toolName = toolName,
                decision = staticDecision,
                isOverridden = false,
                overrideSource = null,
                overrideUpdatedAt = null,
                overrideExpiresAt = null,
            )
        }

        return try {
            ds.connection.use { conn ->
                val sql =
                    """
                    SELECT decision, source, updated_at, expires_at
                    FROM tool_permissions
                    WHERE user_id = ? AND tool_name = ?
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, parseUuidOrNull(userId) ?: return@use null)
                    stmt.setString(2, toolName)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) return@use null
                        val decisionStr = rs.getString("decision")
                        val source = rs.getString("source")
                        val updatedAt = rs.getTimestamp("updated_at")?.toInstant()
                        val expiresAt = rs.getTimestamp("expires_at")?.toInstant()
                        Quad(decisionStr, source, updatedAt, expiresAt)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("[PermissionRepository] DB read failed for user=$userId tool=$toolName: ${e.message}")
            null
        }.let { override ->
            if (override == null) {
                return@let EffectiveDecision(
                    userId = userId,
                    toolName = toolName,
                    decision = staticDecision,
                    isOverridden = false,
                    overrideSource = null,
                    overrideUpdatedAt = null,
                    overrideExpiresAt = null,
                )
            }
            val expiresAt = override.d
            val now = Instant.now()
            val expired = expiresAt != null && expiresAt < now
            when {
                override.a == "INHERIT" || expired ->
                    EffectiveDecision(
                        userId = userId,
                        toolName = toolName,
                        decision = staticDecision,
                        isOverridden = override.a == "INHERIT",
                        overrideSource = override.b,
                        overrideUpdatedAt = override.c,
                        overrideExpiresAt = override.d,
                    )
                else -> {
                    val decision =
                        when (override.a) {
                            "ALLOW" -> ToolPermissionDecision.ALLOW
                            "DENY" -> ToolPermissionDecision.DENY
                            else -> staticDecision
                        }
                    EffectiveDecision(
                        userId = userId,
                        toolName = toolName,
                        decision = decision,
                        isOverridden = true,
                        overrideSource = override.b,
                        overrideUpdatedAt = override.c,
                        overrideExpiresAt = override.d,
                    )
                }
            }
        }
    }

    /**
     * Upsert a `tool_permissions` row for (userId, toolName).
     * The row is set to the given [decision] (ALLOW/DENY/INHERIT)
     * with the given [source] and optional [reason]. Returns true
     * on success, false if the DB is unavailable OR if [userId] is
     * not a valid UUID (the FK requires a UUID, and we'd rather
     * fail loudly than write a row with the wrong shape).
     */
    suspend fun setUserPermission(
        userId: String,
        toolName: String,
        decision: String,
        source: String,
        reason: String? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val ds = dataSource ?: return@withContext false
            val uuid = parseUuidOrNull(userId)
            if (uuid == null) {
                logger.warn("[PermissionRepository] setUserPermission: userId='$userId' is not a valid UUID — skipping")
                return@withContext false
            }
            try {
                ds.connection.use { conn ->
                    val sql =
                        """
                        INSERT INTO tool_permissions (user_id, tool_name, decision, source, reason, updated_at)
                        VALUES (?, ?, ?, ?, ?, now())
                        ON CONFLICT (user_id, tool_name) DO UPDATE SET
                            decision = EXCLUDED.decision,
                            source = EXCLUDED.source,
                            reason = EXCLUDED.reason,
                            updated_at = now()
                        """.trimIndent()
                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setObject(1, uuid)
                        stmt.setString(2, toolName)
                        stmt.setString(3, decision)
                        stmt.setString(4, source)
                        stmt.setString(5, reason)
                        stmt.executeUpdate()
                    }
                }
                // Invalidate cache for this (user, tool)
                cacheMutex.withLock { cache.remove(cacheKey(userId, toolName)) }
                true
            } catch (e: Exception) {
                logger.warn("[PermissionRepository] setUserPermission failed for user=$userId tool=$toolName: ${e.message}")
                false
            }
        }

    /**
     * Fetch all `tool_permissions` rows for a user. Returns an
     * empty list if the DB is unavailable or the userId is not a
     * valid UUID.
     */
    suspend fun listUserPermissions(userId: String): List<EffectiveDecision> =
        withContext(Dispatchers.IO) {
            val ds = dataSource ?: return@withContext emptyList()
            val uuid = parseUuidOrNull(userId) ?: return@withContext emptyList()
            try {
                ds.connection.use { conn ->
                    val sql =
                        """
                        SELECT tool_name, decision, source, updated_at, expires_at
                        FROM tool_permissions
                        WHERE user_id = ?
                        """.trimIndent()
                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setObject(1, uuid)
                        stmt.executeQuery().use { rs ->
                            val results = mutableListOf<EffectiveDecision>()
                            while (rs.next()) {
                                val toolName = rs.getString("tool_name")
                                val decisionStr = rs.getString("decision")
                                val source = rs.getString("source")
                                val updatedAt = rs.getTimestamp("updated_at")?.toInstant()
                                val expiresAt = rs.getTimestamp("expires_at")?.toInstant()
                                val staticDecision =
                                    com.example.smarty.agent.permissions
                                        .ToolPermissionPolicy.SMARTY_DEFAULT
                                        .decide(toolName)
                                val effective =
                                    when (decisionStr) {
                                        "ALLOW" -> ToolPermissionDecision.ALLOW
                                        "DENY" -> ToolPermissionDecision.DENY
                                        "INHERIT" -> staticDecision
                                        else -> staticDecision
                                    }
                                results.add(
                                    EffectiveDecision(
                                        userId = userId,
                                        toolName = toolName,
                                        decision = effective,
                                        isOverridden = decisionStr != "INHERIT",
                                        overrideSource = source,
                                        overrideUpdatedAt = updatedAt,
                                        overrideExpiresAt = expiresAt,
                                    ),
                                )
                            }
                            results
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("[PermissionRepository] listUserPermissions failed for user=$userId: ${e.message}")
                emptyList()
            }
        }

    /**
     * Best-effort insert into `permission_audit_log`. A failure
     * here MUST NOT break the calling tool flow — we log and
     * return false.
     *
     * If [userId] is not a valid UUID (e.g. the OpenCode plugin's
     * `sessionID` passed as a placeholder when no user context is
     * available), the row is still written but with `user_id = NULL`
     * and the original string preserved in `metadata.user_id_raw`
     * for forensic correlation. The `session_id` column carries the
     * real session value in that case.
     */
    suspend fun logDecision(
        userId: String?,
        sessionId: String?,
        toolName: String,
        decision: String,
        actor: String,
        callId: String? = null,
        userFeedback: String? = null,
        argsPreview: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ): Boolean =
        withContext(Dispatchers.IO) {
            val ds = dataSource ?: return@withContext false
            try {
                ds.connection.use { conn ->
                    val sql =
                        """
                        INSERT INTO permission_audit_log (
                            user_id, session_id, tool_name, decision, actor,
                            call_id, user_feedback, args_preview, metadata, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
                        """.trimIndent()
                    val uuid = userId?.let { parseUuidOrNull(it) }
                    // Build a single metadata JSON that may include user_id_raw
                    // if the userId wasn't a valid UUID.
                    val metaBuilder = StringBuilder("{")
                    if (uuid == null && userId != null) {
                        metaBuilder.append("\"user_id_raw\":\"${escapeJson(userId)}\"")
                    }
                    for ((k, v) in metadata) {
                        if (metaBuilder.length > 1) metaBuilder.append(",")
                        metaBuilder.append("\"${escapeJson(k)}\":\"${escapeJson(v)}\"")
                    }
                    metaBuilder.append("}")
                    val metaJson = metaBuilder.toString()

                    conn.prepareStatement(sql).use { stmt ->
                        if (uuid == null) {
                            stmt.setNull(1, java.sql.Types.OTHER)
                        } else {
                            stmt.setObject(1, uuid)
                        }
                        stmt.setString(2, sessionId)
                        stmt.setString(3, toolName)
                        stmt.setString(4, decision)
                        stmt.setString(5, actor)
                        stmt.setString(6, callId)
                        stmt.setString(7, userFeedback?.take(2000))
                        if (argsPreview != null && argsPreview.length > 500) {
                            stmt.setString(8, argsPreview.substring(0, 500))
                        } else {
                            stmt.setString(8, argsPreview)
                        }
                        stmt.setString(9, metaJson)
                        stmt.executeUpdate()
                    }
                }
                true
            } catch (e: Exception) {
                logger.warn(
                    "[PermissionRepository] logDecision failed: user=$userId tool=$toolName decision=$decision: ${e.message}",
                )
                false
            }
        }

    /** Invalidate the in-process cache. Used by tests and admin endpoints. */
    fun invalidateCache() {
        cacheMutex.tryLock()
        try {
            cache.clear()
        } finally {
            if (cacheMutex.isLocked) cacheMutex.unlock()
        }
    }

    private fun parseUuidOrNull(s: String): UUID? = runCatching { UUID.fromString(s) }.getOrNull()

    private fun escapeJson(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}

private data class Quad(
    val a: String, // decision
    val b: String, // source
    val c: Instant?, // updated_at
    val d: Instant?, // expires_at
)
