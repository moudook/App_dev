package com.example.smarty.server.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import com.example.smarty.protocol.AgentCommand
import com.example.smarty.server.serverStartTime
import java.lang.management.ManagementFactory

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
    val provider: String = "Unknown"
)

/**
 * Configure health check routes.
 *
 * Endpoints:
 * - GET /health - Returns server health status
 */
fun Application.configureHealthRoutes() {
    routing {
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
                    protocolVersion = "AgentCommand.$protocolCheck"
                )
            )
        }
    }
}
