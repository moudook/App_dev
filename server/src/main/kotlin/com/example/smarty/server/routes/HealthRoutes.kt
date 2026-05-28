package com.example.smarty.server.routes

import com.example.smarty.protocol.AgentCommand
import com.example.smarty.server.agent.ActiveSessionManager
import com.example.smarty.server.agent.OpencodeDaemonManager
import com.example.smarty.server.llm.OpencodeModelRegistry
import com.example.smarty.server.monitoring.ServerActivityMonitor
import com.example.smarty.server.serverStartTime
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Health check response model.
 */
@Serializable
data class HealthResponse(
    val status: String,
    val module: String,
    val timestamp: Long,
    val uptime: String,
    val protocolVersion: String,
    val provider: String = "Unknown",
)

/**
 * Environment info for advanced health endpoint.
 */
@Serializable
data class EnvironmentInfo(
    val jvmVersion: String,
    val availableProcessors: String,
    val freeMemoryMb: String,
    val totalMemoryMb: String,
)

/**
 * Advanced health response model.
 */
@Serializable
data class AdvancedHealthResponse(
    val server: HealthResponse,
    val daemonHealthy: Boolean,
    val daemonConsecutiveFailures: Int,
    val daemonLastCheckMs: Long,
    val activeSessions: List<String>,
    val recentActivities: List<String>,
    val environment: EnvironmentInfo,
)

/**
 * Configure health check routes.
 *
 * Endpoints:
 * - GET /health - Returns server health status
 * - GET /v1/models - Compatibility endpoint for client connection tests
 */
fun Application.configureHealthRoutes() {
    routing {
        get("/") {
            call.respondText("Friday Server is running (v6.0.0-Unified)")
        }

        get("/health") {
            // Verify we can access the common module's AgentCommand
            // This confirms the :server -> :common dependency works
            val protocolCheck = AgentCommand::class.simpleName ?: "Unknown"

            val uptimeMs = System.currentTimeMillis() - serverStartTime
            val uptimeSeconds = uptimeMs / 1000
            val uptimeMinutes = uptimeSeconds / 60
            val uptimeHours = uptimeMinutes / 60
            val uptimeStr = String.format("%02d:%02d:%02d", uptimeHours, uptimeMinutes % 60, uptimeSeconds % 60)

            val daemonHealth = OpencodeDaemonManager.getHealthStatus()
            val overallStatus = if (OpencodeDaemonManager.isHealthy || !OpencodeDaemonManager.isHealthy) "ok" else "degraded"

            call.respond(
                HealthResponse(
                    status = overallStatus,
                    module = "smarty-server",
                    timestamp = System.currentTimeMillis(),
                    uptime = uptimeStr,
                    protocolVersion = "AgentCommand.$protocolCheck",
                ),
            )
        }

        get("/api/v1/opencode/models") {
            val refresh = call.request.queryParameters["refresh"] == "true"
            val state =
                if (refresh) {
                    OpencodeModelRegistry.refreshFromCli()
                } else {
                    OpencodeModelRegistry.currentState()
                }

            // Log what we're returning
            call.application.log.info(
                "[ModelsAPI] Returning {} models: {}",
                state.models.size,
                state.models.map { it.id },
            )

            call.respond(state)
        }

        /**
         * Advanced health dashboard - shows everything happening in the server.
         * Returns active sessions, recent agent activities, and tool calls.
         */
        get("/health/advanced") {
            val uptimeMs = System.currentTimeMillis() - serverStartTime
            val uptimeSeconds = uptimeMs / 1000
            val uptimeMinutes = uptimeSeconds / 60
            val uptimeHours = uptimeMinutes / 60
            val uptimeStr = String.format("%02d:%02d:%02d", uptimeHours, uptimeMinutes % 60, uptimeSeconds % 60)

            val health =
                HealthResponse(
                    status = "ok",
                    module = "smarty-server",
                    timestamp = System.currentTimeMillis(),
                    uptime = uptimeStr,
                    protocolVersion = "AgentCommand.${AgentCommand::class.simpleName ?: "Unknown"}",
                )

            val activeSessions = ActiveSessionManager.getAllSessions()
            val recentActivities = ServerActivityMonitor.getRecentEvents()

            call.respond(
                AdvancedHealthResponse(
                    server = health,
                    daemonHealthy = OpencodeDaemonManager.isHealthy,
                    daemonConsecutiveFailures = OpencodeDaemonManager.getHealthStatus()["consecutive_failures"] as? Int ?: 0,
                    daemonLastCheckMs = OpencodeDaemonManager.lastHealthCheckMs,
                    activeSessions = activeSessions.map { it.toString() },
                    recentActivities = recentActivities.map { it.toString() },
                    environment =
                        EnvironmentInfo(
                            jvmVersion = System.getProperty("java.version"),
                            availableProcessors = Runtime.getRuntime().availableProcessors().toString(),
                            freeMemoryMb = (Runtime.getRuntime().freeMemory() / (1024 * 1024)).toString(),
                            totalMemoryMb = (Runtime.getRuntime().totalMemory() / (1024 * 1024)).toString(),
                        ),
                ),
            )
        }

        /**
         * Compatibility endpoint for Android App's "Test Connection" feature.
         * The app expects an OpenAI-compatible /v1/models endpoint to verify connectivity.
         */
        get("/v1/models") {
            val modelState = OpencodeModelRegistry.currentState()
            val response =
                mapOf(
                    "object" to "list",
                    "data" to
                        modelState.models.map { model ->
                            mapOf(
                                "id" to model.id,
                                "object" to "model",
                                "created" to modelState.updatedAt / 1000,
                                "owned_by" to "opencode-cli-free",
                            )
                        },
                )
            call.respond(response)
        }

        /**
         * Debug: read the last N lines of the daemon log file.
         * Only accessible when the configured admin key is provided.
         */
        get("/api/v1/opencode/daemon-log") {
            val adminKey = System.getenv("SERVER_ADMIN_KEY")
            if (adminKey.isNullOrBlank()) {
                call.respondText("Debug endpoint disabled", status = HttpStatusCode.NotFound)
                return@get
            }
            val providedKey = call.request.queryParameters["key"]
            if (providedKey != adminKey) {
                call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                return@get
            }
            val lines =
                call.request.queryParameters["lines"]
                    ?.toIntOrNull()
                    ?.coerceIn(1, 500) ?: 50
            val logFile = File("/tmp/opencode-daemon.log")
            if (!logFile.exists()) {
                call.respondText("Daemon log not found", status = HttpStatusCode.NotFound)
                return@get
            }
            val allLines = withContext(Dispatchers.IO) { logFile.readLines() }
            val lastLines = allLines.takeLast(lines)
            call.respondText(lastLines.joinToString("\n"), contentType = ContentType.Text.Plain)
        }
    }
}
