package com.example.smarty.server.agent

import kotlinx.coroutines.CompletableDeferred
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

data class DeviceStatusResult(
    val status: Map<String, String>,
)

private data class DeviceStatusEntry(
    val deferred: CompletableDeferred<DeviceStatusResult>,
    val sessionId: String,
)

object DeviceResponseRegistry {
    private val logger = LoggerFactory.getLogger(DeviceResponseRegistry::class.java)
    private val pendingRequests = ConcurrentHashMap<String, DeviceStatusEntry>()

    fun createPendingRequest(
        commandId: String,
        sessionId: String,
    ): CompletableDeferred<DeviceStatusResult> {
        val deferred = CompletableDeferred<DeviceStatusResult>()
        pendingRequests[commandId] = DeviceStatusEntry(deferred, sessionId)
        logger.info("[DeviceResponseRegistry] Created pending request for commandId=$commandId in session=$sessionId")
        return deferred
    }

    fun resolveRequest(
        commandId: String,
        status: Map<String, String>,
    ): Boolean {
        val entry = pendingRequests.remove(commandId)
        val deferred = entry?.deferred
        if (deferred == null) {
            logger.warn("[DeviceResponseRegistry] No pending request found for commandId=$commandId (may have expired or already resolved)")
            return false
        }
        logger.info("[DeviceResponseRegistry] Resolving request: commandId=$commandId status=$status")
        deferred.complete(DeviceStatusResult(status))
        return true
    }

    fun cancelRequestsForSession(sessionId: String) {
        val iterator = pendingRequests.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.sessionId == sessionId) {
                entry.value.deferred.complete(DeviceStatusResult(emptyMap()))
                iterator.remove()
                logger.info("[DeviceResponseRegistry] Cancelled pending request ${entry.key} for disconnected session $sessionId")
            }
        }
    }
}
