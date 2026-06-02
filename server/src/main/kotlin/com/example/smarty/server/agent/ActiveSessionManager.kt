package com.example.smarty.server.agent

import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks active agent sessions across the server.
 * Used to prevent digest scheduler from interrupting ongoing conversations.
 *
 * Sessions are tracked per user with timestamps for automatic cleanup.
 */
object ActiveSessionManager {
    private val logger = LoggerFactory.getLogger(ActiveSessionManager::class.java)

    private val activeSessions = ConcurrentHashMap<String, SessionInfo>()
    private val mutex = Mutex()

    /**
     * Reverse lookup: opencodeSessionId → (userId, chatSessionId)
     * Populated when the Ktor server calls `updateOpencodeSessionId` after the
     * OpenCode daemon assigns a session ID. Used by `TimelineBridgeRoutes`
     * to route live `message.part.delta` events (which carry the OpenCode
     * session ID) into the correct per-session flow so the Android app can
     * stream them as `FinalAnswerDelta` chunks.
     */
    private val opencodeIndex = ConcurrentHashMap<String, Pair<String, String>>()

    private const val SESSION_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes

    private var sweeperJob: kotlinx.coroutines.Job? = null

    fun startSweeper(scope: kotlinx.coroutines.CoroutineScope) {
        if (sweeperJob?.isActive == true) return
        sweeperJob =
            scope.launch {
                while (isActive) {
                    kotlinx.coroutines.delay(1 * 60 * 1000L) // 1 minute
                    try {
                        cleanupStaleSessions()
                    } catch (e: Exception) {
                        logger.error("Error during session cleanup", e)
                    }
                }
            }
        logger.info("ActiveSessionManager sweeper started")
    }

    @Serializable
    data class SessionInfo(
        val sessionId: String,
        val userId: String? = null, // Added to identify user in advanced health
        val startedAt: Long,
        val lastActivity: Long,
        val operation: String,
    )

    /**
     * Register an active session for a user.
     */
    suspend fun startSession(
        userId: String,
        sessionId: String,
        operation: String = "chat",
    ) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            activeSessions[sessionId] =
                SessionInfo(
                    sessionId = sessionId,
                    userId = userId,
                    startedAt = now,
                    lastActivity = now,
                    operation = operation,
                )
            logger.debug("Session started: userId=$userId, sessionId=$sessionId, operation=$operation")
        }
    }

    /**
     * Update last activity timestamp for a session.
     */
    suspend fun updateActivity(userId: String) {
        mutex.withLock {
            // Update all sessions for this user
            activeSessions.values.filter { it.userId == userId }.forEach { info ->
                activeSessions[info.sessionId] = info.copy(lastActivity = System.currentTimeMillis())
            }
        }
    }

    /**
     * End a session for a user.
     */
    suspend fun endSession(
        userId: String,
        sessionId: String,
    ) {
        mutex.withLock {
            val info = activeSessions[sessionId]
            if (info?.userId == userId) {
                activeSessions.remove(sessionId)
                // Drop any opencodeSessionId entries pointing at this chat session.
                opencodeIndex.entries.removeAll { it.value.second == sessionId }
                logger.debug("Session ended: userId=$userId, sessionId=$sessionId")
            }
        }
    }

    /**
     * Register an opencodeSessionId → (userId, chatSessionId) mapping so
     * that timeline events arriving from the OpenCode plugin (which only
     * know the opencodeSessionId) can be routed into the right per-session
     * event flow. Safe to call repeatedly with the same values; calling
     * with a different `userId`/`chatSessionId` for the same opencodeSessionId
     * overwrites (this shouldn't happen in practice — one opencode session
     * is bound to one chat session for its lifetime).
     */
    fun registerOpencodeSessionId(
        opencodeSessionId: String,
        userId: String,
        chatSessionId: String,
    ) {
        opencodeIndex[opencodeSessionId] = userId to chatSessionId
        logger.debug("[ActiveSessionManager] opencode session mapped: $opencodeSessionId -> user=$userId, chatSession=$chatSessionId")
    }

    /**
     * Resolve an opencodeSessionId to its (userId, chatSessionId) pair, or
     * null if no mapping has been registered yet (or it has been cleaned up).
     * Used by `TimelineBridgeRoutes` to route streaming deltas.
     */
    fun resolveOpencodeSessionId(opencodeSessionId: String): Pair<String, String>? = opencodeIndex[opencodeSessionId]

    /**
     * Check if a user has an active session.
     */
    suspend fun hasActiveSession(userId: String): Boolean {
        cleanupStaleSessions()
        return activeSessions.values.any { it.userId == userId }
    }

    /**
     * Check if any user has an active session.
     */
    suspend fun hasAnyActiveSession(): Boolean {
        cleanupStaleSessions()
        return activeSessions.isNotEmpty()
    }

    /**
     * Get all users with active sessions.
     */
    suspend fun getActiveUsers(): Set<String> {
        cleanupStaleSessions()
        return activeSessions.values.mapNotNull { it.userId }.toSet()
    }

    /**
     * Get all active sessions.
     */
    suspend fun getAllSessions(): List<SessionInfo> {
        cleanupStaleSessions()
        return activeSessions.values.toList()
    }

    /**
     * Get session info for a user.
     */
    fun getSessionInfo(userId: String): SessionInfo? = activeSessions.values.find { it.userId == userId }

    /**
     * Remove stale sessions that have timed out.
     */
    private suspend fun cleanupStaleSessions() {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val staleSessions =
                activeSessions.entries
                    .filter { now - it.value.lastActivity > SESSION_TIMEOUT_MS }
                    .map { it.key }

            staleSessions.forEach { sessionId ->
                val info = activeSessions.remove(sessionId)
                if (info?.userId != null && !activeSessions.values.any { it.userId == info.userId }) {
                    ActiveEventBridge.clear(info.userId)
                }
                logger.info("Removed stale session: userId=${info?.userId}, sessionId=$sessionId")
            }
        }
    }

    /**
     * Clear all sessions (for testing or shutdown).
     */
    suspend fun clearAll() {
        mutex.withLock {
            activeSessions.clear()
        }
    }
}
