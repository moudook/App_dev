package com.example.smarty.server.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import kotlinx.serialization.Serializable
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
    
    private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes
    
    @Serializable
    data class SessionInfo(
        val sessionId: String,
        val userId: String? = null, // Added to identify user in advanced health
        val startedAt: Long,
        val lastActivity: Long,
        val operation: String
    )
    
    /**
     * Register an active session for a user.
     */
    suspend fun startSession(userId: String, sessionId: String, operation: String = "chat") {
        mutex.withLock {
            val now = System.currentTimeMillis()
            activeSessions[userId] = SessionInfo(
                sessionId = sessionId,
                userId = userId,
                startedAt = now,
                lastActivity = now,
                operation = operation
            )
            logger.debug("Session started: userId=$userId, sessionId=$sessionId, operation=$operation")
        }
    }
    
    /**
     * Update last activity timestamp for a session.
     */
    suspend fun updateActivity(userId: String) {
        mutex.withLock {
            activeSessions[userId]?.let { info ->
                activeSessions[userId] = info.copy(lastActivity = System.currentTimeMillis())
            }
        }
    }
    
    /**
     * End a session for a user.
     */
    suspend fun endSession(userId: String, sessionId: String) {
        mutex.withLock {
            val info = activeSessions[userId]
            if (info?.sessionId == sessionId) {
                activeSessions.remove(userId)
                logger.debug("Session ended: userId=$userId, sessionId=$sessionId")
            }
        }
    }
    
    /**
     * Check if a user has an active session.
     */
    suspend fun hasActiveSession(userId: String): Boolean {
        cleanupStaleSessions()
        return activeSessions.containsKey(userId)
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
        return activeSessions.keys.toSet()
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
    fun getSessionInfo(userId: String): SessionInfo? = activeSessions[userId]
    
    /**
     * Remove stale sessions that have timed out.
     */
    private suspend fun cleanupStaleSessions() {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val staleUsers = activeSessions.entries
                .filter { now - it.value.lastActivity > SESSION_TIMEOUT_MS }
                .map { it.key }
            
            staleUsers.forEach { userId ->
                val info = activeSessions.remove(userId)
                logger.info("Removed stale session: userId=$userId, sessionId=${info?.sessionId}")
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
