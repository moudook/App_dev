package com.example.smarty.server.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages health monitoring and crash recovery for the OpenCode CLI daemon.
 * Polls the daemon's /global/health endpoint periodically and exposes status
 * for the server's health check endpoints.
 */
object OpencodeDaemonManager {
    private val logger = LoggerFactory.getLogger(OpencodeDaemonManager::class.java)

    var daemonPort: Int = 4096
    var daemonUsername: String = ""
    var daemonPassword: String = ""
    val healthUrl get() = "http://127.0.0.1:$daemonPort/global/health"

    private const val HEALTH_CHECK_INTERVAL_MS = 15_000L // 15 seconds
    private const val HEALTH_CHECK_TIMEOUT_MS = 5_000    // 5 seconds
    private const val MAX_CONSECUTIVE_FAILURES = 3       // Mark unhealthy after 3 failures
    private const val MAX_RESTART_ATTEMPTS = 5
    private const val BASE_RESTART_DELAY_MS = 5_000L     // 5s base for exponential backoff
    private const val MAX_RESTART_DELAY_MS = 120_000L    // 2 min max backoff

    // Health state
    @Volatile var isHealthy: Boolean = false
        private set
    @Volatile var lastHealthCheckMs: Long = 0L
        private set
    @Volatile var lastHealthyMs: Long = 0L
        private set
    private val consecutiveFailures = AtomicInteger(0)
    private val restartAttempts = AtomicInteger(0)
    private val lastRestartMs = AtomicLong(0L)
    private val monitoring = AtomicBoolean(false)

    private var monitorJob: Job? = null

    fun startMonitoring() {
        if (monitoring.getAndSet(true)) return // Already monitoring

        logger.info("Starting OpenCode daemon health monitoring (interval: ${HEALTH_CHECK_INTERVAL_MS}ms)")
        monitorJob = CoroutineScope(Dispatchers.IO).launch {
            while (monitoring.get()) {
                try {
                    checkHealth()
                } catch (e: Exception) {
                    logger.warn("Health check error: ${e.message}")
                }
                delay(HEALTH_CHECK_INTERVAL_MS)
            }
        }
    }

    fun stopMonitoring() {
        if (!monitoring.getAndSet(false)) return
        logger.info("Stopping OpenCode daemon health monitoring")
        monitorJob?.cancel()
        monitorJob = null
    }

    /**
     * Performs a single health check against the daemon.
     */
    private suspend fun checkHealth() {
        lastHealthCheckMs = System.currentTimeMillis()

        try {
            val url = URL(healthUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = HEALTH_CHECK_TIMEOUT_MS
            conn.readTimeout = HEALTH_CHECK_TIMEOUT_MS
            conn.requestMethod = "GET"

            val statusCode = conn.responseCode
            conn.disconnect()

            if (statusCode == 200) {
                if (!isHealthy) {
                    logger.info("OpenCode daemon is healthy again")
                }
                isHealthy = true
                lastHealthyMs = System.currentTimeMillis()
                consecutiveFailures.set(0)
                restartAttempts.set(0)
            } else {
                handleFailure("Unexpected status code: $statusCode")
            }
        } catch (e: Exception) {
            handleFailure("Connection failed: ${e.message}")
        }
    }

    /**
     * Handle a health check failure. If threshold is exceeded, attempts restart.
     */
    private suspend fun handleFailure(reason: String) {
        val failures = consecutiveFailures.incrementAndGet()
        isHealthy = false

        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            logger.error("OpenCode daemon unhealthy for $failures consecutive checks: $reason")
            attemptRestart()
        } else {
            logger.warn("OpenCode daemon health check failed ($failures/$MAX_CONSECUTIVE_FAILURES): $reason")
        }
    }

    /**
     * Attempts to restart the daemon with exponential backoff.
     * Since the daemon is managed by the entrypoint script, we signal it's down
     * and the actual restart depends on the container orchestrator.
     */
    private suspend fun attemptRestart() {
        val attempts = restartAttempts.get()
        if (attempts >= MAX_RESTART_ATTEMPTS) {
            val cooldownMs = MAX_RESTART_DELAY_MS * 2
            val timeSinceLastRestart = System.currentTimeMillis() - lastRestartMs.get()
            if (timeSinceLastRestart < cooldownMs) {
                return // In cooldown
            }
            // Reset after cooldown
            restartAttempts.set(0)
        }

        val delayMs = (BASE_RESTART_DELAY_MS * (1L shl attempts.coerceAtMost(4))).coerceAtMost(MAX_RESTART_DELAY_MS)
        val now = System.currentTimeMillis()
        val timeSinceLast = now - lastRestartMs.get()
        if (timeSinceLast < delayMs) {
            delay(delayMs - timeSinceLast)
        }

        val attempt = restartAttempts.incrementAndGet()
        lastRestartMs.set(System.currentTimeMillis())
        logger.warn("OpenCode daemon restart signal (attempt $attempt/$MAX_RESTART_ATTEMPTS)")

        // The daemon is managed by the entrypoint script. We can't directly restart it,
        // but we clear any cached sessions so the next request creates a fresh connection.
        // The container orchestrator (Docker) will restart if the process dies.
        try {
            // Try to kill any stuck daemon process and let the entrypoint script restart it
            val pid = findDaemonPid()
            if (pid != null) {
                logger.info("Found daemon process PID: $pid - sending SIGTERM")
                ProcessBuilder("kill", "-TERM", pid.toString()).start()
            }
        } catch (e: Exception) {
            logger.warn("Could not signal daemon process: ${e.message}")
        }
    }

    private fun findDaemonPid(): Int? {
        return try {
            val proc = ProcessBuilder("pgrep", "-f", "opencode serve").start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (output.isNotBlank()) output.lines().first().toIntOrNull() else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get health status for inclusion in server health endpoints.
     */
    fun getHealthStatus(): Map<String, Any?> = mapOf(
        "daemon_healthy" to isHealthy,
        "last_health_check_ms" to lastHealthCheckMs,
        "last_healthy_ms" to lastHealthyMs,
        "consecutive_failures" to consecutiveFailures.get(),
        "restart_attempts" to restartAttempts.get(),
        "monitoring_active" to monitoring.get(),
        "daemon_port" to daemonPort,
    )
}
