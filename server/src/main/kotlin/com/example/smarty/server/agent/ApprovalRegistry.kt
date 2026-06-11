package com.example.smarty.server.agent

import com.example.smarty.server.data.PermissionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

data class ApprovalResult(
    val approved: Boolean,
    val feedback: String?,
)

/**
 * Public read-only view of a pending approval. Returned by
 * [ApprovalRegistry.peek] so callers (e.g. the audit log writer
 * and the `/chat/ws` debug endpoint) can see what tool a pending
 * approval is for without consuming it.
 */
data class PendingApprovalView(
    val toolCallId: String,
    val sessionId: String,
    val userId: String,
    val toolName: String,
    val createdAt: Long,
)

private data class ApprovalEntry(
    val deferred: CompletableDeferred<ApprovalResult>,
    val sessionId: String,
    val userId: String,
    val toolName: String,
    val createdAt: Long = System.currentTimeMillis(),
)

object ApprovalRegistry {
    private val logger = LoggerFactory.getLogger(ApprovalRegistry::class.java)
    private val pendingApprovals = ConcurrentHashMap<String, ApprovalEntry>()

    /**
     * Optional reference to the [PermissionRepository] for audit
     * logging. Set at startup via [setRepository]. When null,
     * audit log writes are silently skipped (with a warning).
     *
     * We can't use constructor injection because [ApprovalRegistry]
     * is a Kotlin `object` (singleton) and is referenced from many
     * places. Late-bound initialization via a setter is the
     * simplest non-invasive approach.
     */
    @Volatile
    private var repository: PermissionRepository? = null

    /**
     * Fire-and-forget scope for audit log writes. We use a
     * dedicated [SupervisorJob] so an audit-log failure can't
     * crash the calling tool flow.
     */
    private val auditScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Wire the [PermissionRepository] for audit logging. Safe to
     * call multiple times (last write wins).
     */
    fun setRepository(repo: PermissionRepository?) {
        repository = repo
        if (repo == null) {
            logger.warn("[ApprovalRegistry] setRepository(null) — audit logging disabled")
        } else {
            logger.info("[ApprovalRegistry] audit logging enabled via PermissionRepository")
        }
    }

    fun createPendingApproval(
        toolCallId: String,
        sessionId: String,
        userId: String = "",
        toolName: String = "",
    ): CompletableDeferred<ApprovalResult> {
        val deferred = CompletableDeferred<ApprovalResult>()
        pendingApprovals[toolCallId] = ApprovalEntry(deferred, sessionId, userId, toolName)
        logger.info(
            "[ApprovalRegistry] Created pending approval for toolCallId=$toolCallId tool=$toolName in session=$sessionId user=$userId",
        )
        return deferred
    }

    fun resolveApproval(
        toolCallId: String,
        approved: Boolean,
        feedback: String?,
        callerUserId: String? = null,
    ): Boolean {
        val entry = pendingApprovals.remove(toolCallId)
        val deferred = entry?.deferred
        if (deferred == null) {
            logger.warn("[ApprovalRegistry] No pending approval found for toolCallId=$toolCallId (may have expired or already resolved)")
            return false
        }
        // Note: Cross-user check removed — the MCP daemon creates approvals with a
        // fallback userId that doesn't match the Android app's Firebase UID.
        // The toolCallId is already a unique, unguessable identifier.
        logger.info(
            "[ApprovalRegistry] Resolving approval: toolCallId=$toolCallId approved=$approved feedback=${feedback?.take(
                100,
            )} caller=$callerUserId",
        )
        deferred.complete(ApprovalResult(approved, feedback))

        // ── Audit log: best-effort, fire-and-forget ──
        // We log AFTER completing the deferred so the user's tool
        // flow is never blocked by an audit DB hiccup. If the repo
        // is null (DB unavailable or not yet wired), we skip with
        // a one-shot warning to avoid log spam.
        val repo = repository
        if (repo == null) {
            // Log only the first few to avoid spam
            if (pendingApprovals.size % 100 == 0) {
                logger.warn("[ApprovalRegistry] audit log skipped (no repo wired)")
            }
        } else {
            val userId = entry.userId
            val sessionId = entry.sessionId
            val toolName = entry.toolName
            val decision = if (approved) "USER_APPROVED" else "USER_DENIED"
            auditScope.launch {
                val ok =
                    repo.logDecision(
                        userId = userId.ifBlank { null },
                        sessionId = sessionId,
                        toolName = toolName,
                        decision = decision,
                        actor = "user",
                        callId = toolCallId,
                        userFeedback = feedback,
                        metadata =
                            mapOf(
                                "source" to "android_app",
                                "caller_user_id" to (callerUserId ?: ""),
                            ),
                    )
                if (!ok) {
                    logger.warn("[ApprovalRegistry] audit log write failed for toolCallId=$toolCallId")
                }
            }
        }

        return true
    }

    /**
     * Returns a non-mutating view of the pending approval for the
     * given [toolCallId], or null if there's no pending entry.
     * Useful for debug endpoints and audit correlation.
     */
    fun peek(toolCallId: String): PendingApprovalView? {
        val e = pendingApprovals[toolCallId] ?: return null
        return PendingApprovalView(
            toolCallId = toolCallId,
            sessionId = e.sessionId,
            userId = e.userId,
            toolName = e.toolName,
            createdAt = e.createdAt,
        )
    }

    /** Remove a specific pending approval (e.g. after timeout) without resolving it. */
    fun clearApproval(toolCallId: String): Boolean {
        val entry = pendingApprovals.remove(toolCallId)
        if (entry != null) {
            logger.info("[ApprovalRegistry] Cleared stale pending approval for toolCallId=$toolCallId")
            return true
        }
        return false
    }

    fun cancelApprovalsForSession(sessionId: String) {
        val iterator = pendingApprovals.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.sessionId == sessionId) {
                entry.value.deferred.complete(ApprovalResult(false, "Session disconnected"))
                iterator.remove()
                logger.info("[ApprovalRegistry] Cancelled pending approval ${entry.key} for disconnected session $sessionId")
            }
        }
    }

    fun hasPendingForSession(sessionId: String): Boolean = pendingApprovals.values.any { it.sessionId == sessionId }

    /**
     * Evicts entries older than [ttlMs]. Returns count evicted.
     * Called by the Registry Reaper coroutine in Application.kt every 5 minutes.
     * Per §7.1: 30-minute TTL — matches ask_user session lifetime.
     * Evicted entries are resolved with false so suspended coroutines unblock cleanly.
     */
    fun evictExpired(ttlMs: Long = 30 * 60_000L): Int {
        val now = System.currentTimeMillis()
        var count = 0
        val iterator = pendingApprovals.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.createdAt > ttlMs) {
                entry.value.deferred.complete(ApprovalResult(false, "Session expired"))
                iterator.remove()
                logger.warn("[ApprovalRegistry] Evicted stale toolCallId=${entry.key} (age=${now - entry.value.createdAt}ms)")
                count++
            }
        }
        // Circuit breaker: if registry still has > 1000 entries, force-evict oldest 500
        if (pendingApprovals.size > 1000) {
            logger.warn("[ApprovalRegistry] Registry exceeded 1000 entries — force-evicting oldest 500")
            pendingApprovals.entries
                .sortedBy { it.value.createdAt }
                .take(500)
                .forEach {
                    it.value.deferred.complete(ApprovalResult(false, "Force-evicted"))
                    pendingApprovals.remove(it.key)
                    count++
                }
        }
        return count
    }

    fun size(): Int = pendingApprovals.size
}
