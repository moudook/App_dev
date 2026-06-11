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
    val createdAt: Long = System.currentTimeMillis(),
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

    /**
     * Evicts entries older than [ttlMs]. Returns count evicted.
     * Called by the Registry Reaper coroutine in Application.kt every 5 minutes.
     */
    fun evictExpired(ttlMs: Long = 60_000L): Int {
        val now = System.currentTimeMillis()
        var count = 0
        val iterator = pendingRequests.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.createdAt > ttlMs) {
                entry.value.deferred.complete(DeviceStatusResult(emptyMap()))
                iterator.remove()
                logger.warn("[DeviceResponseRegistry] Evicted stale commandId=${entry.key} (age=${now - entry.value.createdAt}ms)")
                count++
            }
        }
        // Circuit breaker: if registry is still huge, force-evict oldest 500
        if (pendingRequests.size > 1000) {
            logger.warn("[DeviceResponseRegistry] Registry exceeded 1000 entries — force-evicting oldest 500")
            pendingRequests.entries
                .sortedBy { it.value.createdAt }
                .take(500)
                .forEach {
                    it.value.deferred.complete(DeviceStatusResult(emptyMap()))
                    pendingRequests.remove(it.key)
                    count++
                }
        }
        return count
    }

    fun size(): Int = pendingRequests.size
}
