package com.example.smarty.server.routes

import com.example.smarty.protocol.AgentCommand
import com.example.smarty.server.agent.ActiveSessionManager
import com.example.smarty.server.monitoring.ServerActivityMonitor
import com.example.smarty.server.serverStartTime
import io.ktor.server.application.Application
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

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

            val overallStatus = "ok"

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
            call.respondText("""{"models":[],"default":"openrouter/auto","updatedAt":0}""", io.ktor.http.ContentType.Application.Json)
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
            val modelList = listOf(
                mapOf("id" to "openrouter/auto", "object" to "model", "created" to 0, "owned_by" to "openrouter"),
                mapOf("id" to "google/gemini-2.5-pro-exp-03-25:free", "object" to "model", "created" to 0, "owned_by" to "google"),
                mapOf("id" to "deepseek/deepseek-chat-v3-0324:free", "object" to "model", "created" to 0, "owned_by" to "deepseek"),
                mapOf("id" to "meta-llama/llama-4-scout:free", "object" to "model", "created" to 0, "owned_by" to "meta"),
            )
            call.respond(mapOf("object" to "list", "data" to modelList))
        }
    }
}
