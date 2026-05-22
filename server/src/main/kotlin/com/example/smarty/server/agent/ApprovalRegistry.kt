package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentEvent
import kotlinx.coroutines.CompletableDeferred
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

data class ApprovalResult(val approved: Boolean, val feedback: String?)

object ApprovalRegistry {
    private val logger = LoggerFactory.getLogger(ApprovalRegistry::class.java)
    private val pendingApprovals = ConcurrentHashMap<String, Pair<CompletableDeferred<ApprovalResult>, String>>()

    fun createPendingApproval(toolCallId: String, sessionId: String): CompletableDeferred<ApprovalResult> {
        val deferred = CompletableDeferred<ApprovalResult>()
        pendingApprovals[toolCallId] = Pair(deferred, sessionId)
        logger.info("[ApprovalRegistry] Created pending approval for toolCallId=$toolCallId in session=$sessionId")
        return deferred
    }

    fun resolveApproval(toolCallId: String, approved: Boolean, feedback: String?): Boolean {
        val entry = pendingApprovals.remove(toolCallId)
        val deferred = entry?.first
        if (deferred == null) {
            logger.warn("[ApprovalRegistry] No pending approval found for toolCallId=$toolCallId (may have expired or already resolved)")
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
            if (entry.value.second == sessionId) {
                entry.value.first.complete(ApprovalResult(false, "Session disconnected"))
                iterator.remove()
                logger.info("[ApprovalRegistry] Cancelled pending approval ${entry.key} for disconnected session $sessionId")
            }
        }
    }
}
