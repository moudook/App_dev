package com.example.smarty.server.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import com.example.smarty.protocol.AgentCommand

/**
 * Health check response model.
 */
@Serializable
data class HealthResponse(
    val status: String,
    val module: String,
    val timestamp: Long,
    val protocolVersion: String
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

            call.respond(
                HealthResponse(
                    status = "ok",
                    module = "smarty-server",
                    timestamp = System.currentTimeMillis(),
                    protocolVersion = "AgentCommand.$protocolCheck"
                )
            )
        }
    }
}
