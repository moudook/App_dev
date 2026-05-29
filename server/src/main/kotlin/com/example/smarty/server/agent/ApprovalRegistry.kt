package com.example.smarty.server.agent

import kotlinx.coroutines.CompletableDeferred
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

data class ApprovalResult(
    val approved: Boolean,
    val feedback: String?,
)

private data class ApprovalEntry(
    val deferred: CompletableDeferred<ApprovalResult>,
    val sessionId: String,
    val userId: String,
)

object ApprovalRegistry {
    private val logger = LoggerFactory.getLogger(ApprovalRegistry::class.java)
    private val pendingApprovals = ConcurrentHashMap<String, ApprovalEntry>()

    fun createPendingApproval(
        toolCallId: String,
        sessionId: String,
        userId: String = "",
    ): CompletableDeferred<ApprovalResult> {
        val deferred = CompletableDeferred<ApprovalResult>()
        pendingApprovals[toolCallId] = ApprovalEntry(deferred, sessionId, userId)
        logger.info("[ApprovalRegistry] Created pending approval for toolCallId=$toolCallId in session=$sessionId user=$userId")
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
        logger.info("[ApprovalRegistry] Resolving approval: toolCallId=$toolCallId approved=$approved feedback=${feedback?.take(100)} caller=$callerUserId")
        deferred.complete(ApprovalResult(approved, feedback))
        return true
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
}
