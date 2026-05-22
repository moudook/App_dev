package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentEvent
import kotlinx.coroutines.CompletableDeferred
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

data class ApprovalResult(val approved: Boolean, val feedback: String?)

private data class ApprovalEntry(
    val deferred: CompletableDeferred<ApprovalResult>,
    val sessionId: String,
    val userId: String,
)

object ApprovalRegistry {
    private val logger = LoggerFactory.getLogger(ApprovalRegistry::class.java)
    private val pendingApprovals = ConcurrentHashMap<String, ApprovalEntry>()

    fun createPendingApproval(toolCallId: String, sessionId: String, userId: String = ""): CompletableDeferred<ApprovalResult> {
        val deferred = CompletableDeferred<ApprovalResult>()
        pendingApprovals[toolCallId] = ApprovalEntry(deferred, sessionId, userId)
        logger.info("[ApprovalRegistry] Created pending approval for toolCallId=$toolCallId in session=$sessionId user=$userId")
        return deferred
    }

    fun resolveApproval(toolCallId: String, approved: Boolean, feedback: String?, callerUserId: String? = null): Boolean {
        val entry = pendingApprovals.remove(toolCallId)
        val deferred = entry?.deferred
        if (deferred == null) {
            logger.warn("[ApprovalRegistry] No pending approval found for toolCallId=$toolCallId (may have expired or already resolved)")
            return false
        }
        // Cross-user check: only the user who owns the approval can resolve it
        if (callerUserId != null && entry.userId.isNotBlank() && callerUserId != entry.userId) {
            logger.warn("[ApprovalRegistry] CROSS-USER BLOCKED: user=$callerUserId tried to resolve toolCallId=$toolCallId owned by user=${entry.userId}")
            pendingApprovals[toolCallId] = entry
            return false
        }
        logger.info("[ApprovalRegistry] Resolving approval: toolCallId=$toolCallId approved=$approved feedback=${feedback?.take(100)}")
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
