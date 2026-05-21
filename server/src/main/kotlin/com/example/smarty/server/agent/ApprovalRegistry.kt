package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentEvent
import kotlinx.coroutines.CompletableDeferred
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

data class ApprovalResult(val approved: Boolean, val feedback: String?)

object ApprovalRegistry {
    private val logger = LoggerFactory.getLogger(ApprovalRegistry::class.java)
    private val pendingApprovals = ConcurrentHashMap<String, CompletableDeferred<ApprovalResult>>()

    fun createPendingApproval(toolCallId: String): CompletableDeferred<ApprovalResult> {
        val deferred = CompletableDeferred<ApprovalResult>()
        pendingApprovals[toolCallId] = deferred
        logger.info("[ApprovalRegistry] Created pending approval for toolCallId=$toolCallId")
        return deferred
    }

    fun resolveApproval(toolCallId: String, approved: Boolean, feedback: String?): Boolean {
        val deferred = pendingApprovals.remove(toolCallId)
        if (deferred == null) {
            logger.warn("[ApprovalRegistry] No pending approval found for toolCallId=$toolCallId (may have expired or already resolved)")
            return false
        }
        logger.info("[ApprovalRegistry] Resolving approval: toolCallId=$toolCallId approved=$approved feedback=${feedback?.take(100)}")
        deferred.complete(ApprovalResult(approved, feedback))
        return true
    }
}
