package com.example.smarty.server.routes

import com.example.smarty.protocol.AgentCommand
import com.example.smarty.server.agent.ActiveSessionManager
import com.example.smarty.server.llm.OpencodeModelRegistry
import com.example.smarty.server.monitoring.ServerActivityMonitor
import com.example.smarty.server.serverStartTime
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.io.File
import io.ktor.http.*

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

            call.respond(
                HealthResponse(
                    status = "ok",
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
                    activeSessions = activeSessions.map { it.toString() },
                    recentActivities = recentActivities.map { it.toString() },
                    environment = EnvironmentInfo(
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
         * Protected by query param secret to prevent abuse.
         * Usage: GET /api/v1/opencode/daemon-log?lines=50&secret=debug123
         */
        get("/api/v1/opencode/daemon-log") {
            val secret = call.request.queryParameters["secret"]
            if (secret != "debug123") {
                call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                return@get
            }
            val lines = call.request.queryParameters["lines"]?.toIntOrNull()?.coerceIn(1, 500) ?: 50
            val logFile = File("/tmp/opencode-daemon.log")
            if (!logFile.exists()) {
                call.respondText("Daemon log not found at /tmp/opencode-daemon.log", status = HttpStatusCode.NotFound)
                return@get
            }
            val allLines = logFile.readLines()
            val lastLines = allLines.takeLast(lines)
            call.respondText(lastLines.joinToString("\n"), contentType = ContentType.Text.Plain)
        }
    }
}
