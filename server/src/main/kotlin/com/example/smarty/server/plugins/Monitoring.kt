package com.example.smarty.server.plugins

import com.example.smarty.server.data.DatabaseFactory
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.principal
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Enhanced server monitoring dashboard.
 * Tracks request counts, active connections, LLM usage, and health status.
 */

data class RequestLog(
    val timestamp: Long,
    val method: String,
    val path: String,
    val sourceIp: String,
    val statusCode: Int,
    val durationMs: Long,
    val userId: String? = null,
)

data class LlmRequestLog(
    val timestamp: Long,
    val model: String,
    val latencyMs: Long,
    val success: Boolean,
    val errorMsg: String? = null,
)

object ServerMonitor {
    // --- General Request Stats ---
    private val requestLogs = ConcurrentLinkedDeque<RequestLog>()
    private val totalRequests = AtomicLong(0)
    private val startTime = System.currentTimeMillis()
    private const val MAX_LOGS = 100

    // --- Active Connections ---
    private val activeSseConnections = AtomicInteger(0)

    // --- LLM Stats ---
    private val llmLogs = ConcurrentLinkedDeque<LlmRequestLog>()
    private val totalLlmRequests = AtomicLong(0)
    private val totalLlmErrors = AtomicLong(0)
    private val totalLlmLatency = AtomicLong(0)
    private const val MAX_LLM_LOGS = 50

    // --- Health Status (Volatile for thread visibility) ---
    @Volatile var isOpenCodeDaemonReachable: Boolean = false

    @Volatile var openCodeDaemonStatusMsg: String = "Checking..."

    @Volatile var isDbConnected: Boolean = false

    @Volatile var lastHealthCheck: Long = 0

    // --- Methods ---

    fun logRequest(log: RequestLog) {
        totalRequests.incrementAndGet()
        requestLogs.addFirst(log)
        while (requestLogs.size > MAX_LOGS) {
            requestLogs.removeLast()
        }
    }

    fun incrementSse() {
        activeSseConnections.incrementAndGet()
    }

    fun decrementSse() {
        activeSseConnections.decrementAndGet()
    }

    fun trackLlmRequest(
        model: String,
        latency: Long,
        success: Boolean,
        errorMsg: String? = null,
    ) {
        totalLlmRequests.incrementAndGet()
        if (!success) totalLlmErrors.incrementAndGet()
        totalLlmLatency.addAndGet(latency)

        llmLogs.addFirst(
            LlmRequestLog(
                timestamp = System.currentTimeMillis(),
                model = model,
                latencyMs = latency,
                success = success,
                errorMsg = errorMsg,
            ),
        )
        while (llmLogs.size > MAX_LLM_LOGS) {
            llmLogs.removeLast()
        }
    }

    fun getStats(): Map<String, Any> {
        val uptimeMs = System.currentTimeMillis() - startTime
        val uptimeHours = uptimeMs / 3600000.0

        // Memory
        val runtime = Runtime.getRuntime()
        val totalMem = runtime.totalMemory() / (1024 * 1024)
        val freeMem = runtime.freeMemory() / (1024 * 1024)
        val usedMem = totalMem - freeMem

        // LLM Avgs
        val llmCount = totalLlmRequests.get()
        val avgLlmLat = if (llmCount > 0) totalLlmLatency.get() / llmCount else 0

        return mapOf(
            // General
            "totalRequests" to totalRequests.get(),
            "uptimeHours" to "%.1f".format(uptimeHours),
            "requestsPerMinute" to if (uptimeMs > 60000) "%.1f".format(totalRequests.get() * 60000.0 / uptimeMs) else "N/A",
            "uniqueIps" to requestLogs.map { it.sourceIp }.toSet().size,
            // Infrastructure
            "isOpenCodeDaemonReachable" to isOpenCodeDaemonReachable,
            "openCodeDaemonStatusMsg" to openCodeDaemonStatusMsg,
            "isDbConnected" to isDbConnected,
            "activeSse" to activeSseConnections.get(),
            "usedMemoryMb" to usedMem,
            "totalMemoryMb" to totalMem,
            // LLM
            "llmTotal" to llmCount,
            "llmErrors" to totalLlmErrors.get(),
            "llmAvgLatency" to avgLlmLat,
        )
    }

    fun getRecentLogs(): List<RequestLog> = requestLogs.toList()

    fun getLlmLogs(): List<LlmRequestLog> = llmLogs.toList()
}

fun Application.configureMonitoring() {
    val monitorLogger = org.slf4j.LoggerFactory.getLogger("OpenCodeMonitor")
    monitorLogger.info("[OpenCodeMonitor] Monitoring subsystem started — checking daemon at 127.0.0.1:4096/global/health")

    // 1. Start Background Health Check
    val monitorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    monitorScope.launch {
        var consecutiveFailures = 0
        while (isActive) {
            runCatching {
                // Check OpenCode Daemon
                val daemonCheckStart = System.currentTimeMillis()
                try {
                    val url = URL("http://127.0.0.1:4096/global/health")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    conn.requestMethod = "GET"

                    if (conn.responseCode == 200) {
                        val wasDown = !ServerMonitor.isOpenCodeDaemonReachable
                        ServerMonitor.isOpenCodeDaemonReachable = true
                        ServerMonitor.openCodeDaemonStatusMsg = "Online (${conn.responseCode})"
                        if (wasDown) {
                            monitorLogger.info("[OpenCodeMonitor] Daemon RECOVERED — now healthy (check took ${System.currentTimeMillis() - daemonCheckStart}ms)")
                        }
                        consecutiveFailures = 0
                    } else {
                        ServerMonitor.isOpenCodeDaemonReachable = false
                        ServerMonitor.openCodeDaemonStatusMsg = "HTTP ${conn.responseCode}"
                        monitorLogger.warn("[OpenCodeMonitor] Daemon returned HTTP ${conn.responseCode}")
                        consecutiveFailures++
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    val wasUp = ServerMonitor.isOpenCodeDaemonReachable
                    ServerMonitor.isOpenCodeDaemonReachable = false
                    ServerMonitor.openCodeDaemonStatusMsg = "Unreachable (${e.message?.take(60)})"
                    if (wasUp) {
                        monitorLogger.error("[OpenCodeMonitor] Daemon WENT DOWN — ${e.message?.take(80)}")
                    }
                    consecutiveFailures++
                    if (consecutiveFailures > 0 && consecutiveFailures % 6 == 0) {
                        monitorLogger.warn("[OpenCodeMonitor] Daemon unreachable for ${consecutiveFailures * 5}s — ${e.message?.take(80)}")
                    }
                }

                // Check DB
                try {
                    val ds = DatabaseFactory.getDataSource()
                    if (ds != null) {
                        val conn = ds.connection
                        val isValid = conn.isValid(2)
                        conn.close()
                        ServerMonitor.isDbConnected = isValid
                    } else {
                        ServerMonitor.isDbConnected = false
                    }
                } catch (e: Exception) {
                    ServerMonitor.isDbConnected = false
                }

                ServerMonitor.lastHealthCheck = System.currentTimeMillis()
            }
            delay(5000) // Check every 5s
        }
    }

    // 2. Intercept Requests
    intercept(ApplicationCallPipeline.Monitoring) {
        val startTime = System.currentTimeMillis()
        val sourceIp = call.request.local.remoteHost
        val method = call.request.httpMethod.value
        val path = call.request.uri

        // Track active SSE connections
        val isSse = path.contains("/chat/stream")
        if (isSse) ServerMonitor.incrementSse()

        try {
            proceed()
        } finally {
            if (isSse) ServerMonitor.decrementSse()

            val duration = System.currentTimeMillis() - startTime
            val statusCode = call.response.status()?.value ?: 0
            val userId = call.principal<FirebaseUserPrincipal>()?.userId

            ServerMonitor.logRequest(
                RequestLog(
                    timestamp = System.currentTimeMillis(),
                    method = method,
                    path = path,
                    sourceIp = sourceIp,
                    statusCode = statusCode,
                    durationMs = duration,
                    userId = userId,
                ),
            )
        }
    }

    routing {
        // Dashboard HTML page
        get("/dashboard") {
            val stats = ServerMonitor.getStats()
            val logs = ServerMonitor.getRecentLogs()
            val llmLogs = ServerMonitor.getLlmLogs()
            val formatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

            // HTML Helpers
            fun statusColor(isGood: Boolean) = if (isGood) "#4CAF50" else "#f44336"

            val logsHtml =
                logs.joinToString("\n") { log ->
                    val time = formatter.format(Instant.ofEpochMilli(log.timestamp))
                    val statusC =
                        when {
                            log.statusCode in 200..299 -> "#4CAF50"
                            log.statusCode in 400..499 -> "#FF9800"
                            else -> "#f44336"
                        }
                    """<tr>
                    <td>$time</td>
                    <td><span class="badge" style="background:#7c4dff22;color:#7c4dff">${log.method}</span></td>
                    <td class="truncate" title="${log.path}">${log.path}</td>
                    <td><span style="color:$statusC;font-weight:bold">${log.statusCode}</span></td>
                    <td>${log.durationMs}ms</td>
                    <td>${log.userId ?: "-"}</td>
                </tr>"""
                }

            val llmHtml =
                llmLogs.joinToString("\n") { log ->
                    val time = formatter.format(Instant.ofEpochMilli(log.timestamp))
                    val statusC = if (log.success) "#4CAF50" else "#f44336"
                    """<tr>
                    <td>$time</td>
                    <td>${log.model}</td>
                    <td>${log.latencyMs}ms</td>
                    <td><span style="color:$statusC">${if (log.success) "OK" else "ERR"}</span></td>
                    <td class="error-msg">${log.errorMsg ?: "-"}</td>
                </tr>"""
                }

            val opencodeStatus = stats["isOpenCodeDaemonReachable"] as Boolean
            val opencodeMsg = stats["openCodeDaemonStatusMsg"] as String

            call.respondText(ContentType.Text.Html) {
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Smarty Server Monitor</title>
                    <meta http-equiv="refresh" content="5">
                    <style>
                        :root { --bg: #0f0f0f; --card: #1a1a2e; --border: #2a2a4a; --text: #e0e0e0; --accent: #7c4dff; }
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        body { font-family: 'Segoe UI', monospace; background: var(--bg); color: var(--text); padding: 20px; font-size: 14px; }

                        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 15px; margin-bottom: 25px; }
                        .card { background: var(--card); border: 1px solid var(--border); border-radius: 8px; padding: 15px; }
                        .card h3 { font-size: 12px; color: #888; text-transform: uppercase; margin-bottom: 5px; }
                        .card .val { font-size: 24px; font-weight: bold; }
                        .card .sub { font-size: 12px; opacity: 0.7; }

                        h2 { margin: 30px 0 15px 0; border-bottom: 1px solid var(--border); padding-bottom: 5px; font-size: 16px; color: #fff; }
                        table { width: 100%; border-collapse: collapse; background: var(--card); border-radius: 8px; overflow: hidden; font-size: 13px; }
                        th { background: #16213e; padding: 10px; text-align: left; color: #888; font-weight: normal; }
                        td { padding: 8px 10px; border-bottom: 1px solid var(--border); font-family: 'Cascadia Code', monospace; }
                        .badge { padding: 2px 6px; border-radius: 4px; font-weight: bold; font-size: 11px; }
                        .truncate { max-width: 250px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                        .error-msg { color: #f44336; max-width: 200px; overflow: hidden; }

                        .status-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 5px; }
                    </style>
                </head>
                <body>
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:20px;">
                        <h1>Smarty Server Monitor</h1>
                        <div style="font-size:12px; color:#888;">Auto-refresh: 5s</div>
                    </div>

                    <!-- Health & Resources -->
                    <div class="grid">
                        <div class="card">
                            <h3>OpenCode Daemon</h3>
                            <div class="val" style="color:${statusColor(opencodeStatus)}">
                                ${if (opencodeStatus) "HEALTHY" else "DOWN"}
                            </div>
                            <div class="sub">$opencodeMsg</div>
                        </div>
                        <div class="card">
                            <h3>Database</h3>
                            <div class="val" style="color:${statusColor(stats["isDbConnected"] as Boolean)}">
                                ${if (stats["isDbConnected"] as Boolean) "CONNECTED" else "DISCONNECTED"}
                            </div>
                            <div class="sub">PostgreSQL</div>
                        </div>
                        <div class="card">
                            <h3>JVM Memory</h3>
                            <div class="val">${stats["usedMemoryMb"]} MB</div>
                            <div class="sub">of ${stats["totalMemoryMb"]} MB</div>
                        </div>
                        <div class="card">
                            <h3>Uptime</h3>
                            <div class="val">${stats["uptimeHours"]}h</div>
                            <div class="sub">Since restart</div>
                        </div>
                    </div>

                    <!-- Activity Stats -->
                    <div class="grid">
                        <div class="card">
                            <h3>Active SSE</h3>
                            <div class="val" style="color:#2196F3">${stats["activeSse"]}</div>
                            <div class="sub">Streaming Connections</div>
                        </div>
                        <div class="card">
                            <h3>Requests/Min</h3>
                            <div class="val">${stats["requestsPerMinute"]}</div>
                            <div class="sub">Total: ${stats["totalRequests"]}</div>
                        </div>
                        <div class="card">
                            <h3>LLM Requests</h3>
                            <div class="val">${stats["llmTotal"]}</div>
                            <div class="sub">Latency: ${stats["llmAvgLatency"]}ms</div>
                        </div>
                         <div class="card">
                            <h3>LLM Errors</h3>
                            <div class="val" style="color:${if ((stats["llmErrors"] as Long) > 0) "#f44336" else "#4CAF50"}">
                                ${stats["llmErrors"]}
                            </div>
                        </div>
                    </div>

                    <h2>LLM Interaction History</h2>
                    <table>
                        <thead><tr><th>Time</th><th>Model</th><th>Latency</th><th>Status</th><th>Error</th></tr></thead>
                        <tbody>$llmHtml</tbody>
                    </table>

                    <h2>Recent HTTP Requests</h2>
                    <table>
                        <thead><tr><th>Time</th><th>Method</th><th>Path</th><th>Status</th><th>Duration</th><th>User</th></tr></thead>
                        <tbody>$logsHtml</tbody>
                    </table>
                </body>
                </html>
                """.trimIndent()
            }
        }

        // JSON API for programmatic access
        get("/dashboard/api") {
            call.respond(ServerMonitor.getStats())
        }
    }
}
