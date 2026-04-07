package com.example.smarty.server.monitoring

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Security Monitoring Service.
 *
 * Single Responsibility: Only handles security event tracking and monitoring.
 * Security: Detects and logs suspicious activity, brute force attempts, and anomalies.
 *
 * Usage:
 * ```
 * SecurityMonitor.trackFailedAuth(userId, reason)
 * SecurityMonitor.trackSuspiciousActivity(ip, action)
 * SecurityMonitor.getSecurityMetrics()
 * ```
 */
object SecurityMonitor {
    private val logger = LoggerFactory.getLogger(SecurityMonitor::class.java)

    // Metrics counters
    private val failedAuthAttempts = AtomicLong(0)
    private val successfulAuthAttempts = AtomicLong(0)
    private val suspiciousActivities = AtomicLong(0)
    private val rateLimitHits = AtomicLong(0)
    private val blockedRequests = AtomicLong(0)

    // Track failed auth by IP/user
    private val failedAuthByIp = ConcurrentHashMap<String, AtomicLong>()
    private val failedAuthByUser = ConcurrentHashMap<String, AtomicLong>()

    // Track suspicious activity
    private val recentSuspiciousActivity = ConcurrentHashMap<String, MutableSet<String>>()

    // Thresholds
    private const val FAILED_AUTH_THRESHOLD = 10
    private const val SUSPICIOUS_ACTIVITY_THRESHOLD = 5
    private const val TIME_WINDOW_MS = 60 * 60 * 1000L // 1 hour

    // State flows for real-time monitoring
    private val _securityMetrics = MutableStateFlow(SecurityMetrics())
    val securityMetrics: StateFlow<SecurityMetrics> = _securityMetrics.asStateFlow()

    data class SecurityMetrics(
        val failedAuthAttempts: Long = 0,
        val successfulAuthAttempts: Long = 0,
        val suspiciousActivities: Long = 0,
        val rateLimitHits: Long = 0,
        val blockedRequests: Long = 0,
        val timestamp: Long = System.currentTimeMillis(),
    )

    /**
     * Track a failed authentication attempt.
     */
    fun trackFailedAuth(
        ip: String,
        userId: String? = null,
        reason: String = "Unknown",
    ) {
        failedAuthAttempts.incrementAndGet()

        failedAuthByIp.computeIfAbsent(ip) { AtomicLong(0) }.incrementAndGet()

        if (userId != null) {
            failedAuthByUser.computeIfAbsent(userId) { AtomicLong(0) }.incrementAndGet()
        }

        logger.warn(
            "SECURITY: Failed authentication - IP: {}, User: {}, Reason: {}",
            ip,
            userId ?: "anonymous",
            reason,
        )

        // Check for brute force
        val ipAttempts = failedAuthByIp[ip]?.get() ?: 0
        if (ipAttempts >= FAILED_AUTH_THRESHOLD) {
            logger.error(
                "SECURITY ALERT: Possible brute force attack from IP: {} ({} attempts)",
                ip,
                ipAttempts,
            )
            // Could trigger IP ban here
        }

        updateMetrics()
    }

    /**
     * Track a successful authentication.
     */
    fun trackSuccessfulAuth(
        ip: String,
        userId: String,
    ) {
        successfulAuthAttempts.incrementAndGet()

        // Clear failed attempts for this user
        failedAuthByUser[userId]?.set(0)

        logger.debug("Auth success - IP: {}, User: {}", ip, userId)
        updateMetrics()
    }

    /**
     * Track suspicious activity.
     */
    fun trackSuspiciousActivity(
        ip: String,
        action: String,
        details: String? = null,
    ) {
        suspiciousActivities.incrementAndGet()

        recentSuspiciousActivity.computeIfAbsent(ip) { mutableSetOf() }.add(
            "$action: ${details ?: ""}",
        )

        logger.warn(
            "SECURITY: Suspicious activity - IP: {}, Action: {}, Details: {}",
            ip,
            action,
            details ?: "N/A",
        )

        // Check for pattern
        val activityCount = recentSuspiciousActivity[ip]?.size ?: 0
        if (activityCount >= SUSPICIOUS_ACTIVITY_THRESHOLD) {
            logger.error(
                "SECURITY ALERT: Repeated suspicious activity from IP: {} ({} incidents)",
                ip,
                activityCount,
            )
        }

        updateMetrics()
    }

    /**
     * Track a rate limit hit.
     */
    fun trackRateLimit(
        ip: String,
        endpoint: String,
    ) {
        rateLimitHits.incrementAndGet()

        logger.debug("Rate limit hit - IP: {}, Endpoint: {}", ip, endpoint)
        updateMetrics()
    }

    /**
     * Track a blocked request.
     */
    fun trackBlockedRequest(
        ip: String,
        reason: String,
    ) {
        blockedRequests.incrementAndGet()

        logger.warn("SECURITY: Blocked request - IP: {}, Reason: {}", ip, reason)
        updateMetrics()
    }

    /**
     * Get security metrics.
     */
    fun getMetrics(): SecurityMetrics =
        SecurityMetrics(
            failedAuthAttempts = failedAuthAttempts.get(),
            successfulAuthAttempts = successfulAuthAttempts.get(),
            suspiciousActivities = suspiciousActivities.get(),
            rateLimitHits = rateLimitHits.get(),
            blockedRequests = blockedRequests.get(),
            timestamp = System.currentTimeMillis(),
        )

    /**
     * Get failed auth attempts by IP.
     */
    fun getFailedAuthByIp(): Map<String, Long> = failedAuthByIp.mapValues { it.value.get() }

    /**
     * Get failed auth attempts by user.
     */
    fun getFailedAuthByUser(): Map<String, Long> = failedAuthByUser.mapValues { it.value.get() }

    /**
     * Get recent suspicious activity for an IP.
     */
    fun getSuspiciousActivity(ip: String): Set<String> = recentSuspiciousActivity[ip]?.toSet() ?: emptySet()

    /**
     * Check if an IP should be temporarily blocked.
     */
    fun shouldBlockIp(ip: String): Boolean {
        val failedAuth = failedAuthByIp[ip]?.get() ?: 0
        val suspiciousCount = recentSuspiciousActivity[ip]?.size ?: 0

        return failedAuth >= FAILED_AUTH_THRESHOLD * 2 ||
            suspiciousCount >= SUSPICIOUS_ACTIVITY_THRESHOLD * 2
    }

    /**
     * Reset metrics (for testing or manual reset).
     */
    fun resetMetrics() {
        failedAuthAttempts.set(0)
        successfulAuthAttempts.set(0)
        suspiciousActivities.set(0)
        rateLimitHits.set(0)
        blockedRequests.set(0)
        failedAuthByIp.clear()
        failedAuthByUser.clear()
        recentSuspiciousActivity.clear()
        updateMetrics()
    }

    private fun updateMetrics() {
        _securityMetrics.value = getMetrics()
    }

    /**
     * Get security report for admin dashboard.
     */
    fun getSecurityReport(): Map<String, Any> {
        val failedAuthByIpTop =
            failedAuthByIp.entries.sortedByDescending { it.value.get() }.take(10)
                .associate { it.key to it.value.get() }
        val failedAuthByUserTop =
            failedAuthByUser.entries.sortedByDescending { it.value.get() }.take(10)
                .associate { it.key to it.value.get() }
        val highRiskIps = failedAuthByIp.filter { it.value.get() >= FAILED_AUTH_THRESHOLD }.keys.toList()

        return mapOf(
            "metrics" to getMetrics(),
            "failed_auth_by_ip" to failedAuthByIpTop,
            "failed_auth_by_user" to failedAuthByUserTop,
            "high_risk_ips" to highRiskIps,
            "recommendations" to
                buildList {
                    if (failedAuthAttempts.get() > 100) {
                        add("High number of failed auth attempts - consider enabling CAPTCHA")
                    }
                    if (suspiciousActivities.get() > 50) {
                        add("Elevated suspicious activity - review security logs")
                    }
                    if (rateLimitHits.get() > 1000) {
                        add("High rate limit hits - consider adjusting limits or investigating abuse")
                    }
                },
        )
    }
}
