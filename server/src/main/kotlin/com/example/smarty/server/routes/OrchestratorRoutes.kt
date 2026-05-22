package com.example.smarty.server.routes

import com.example.smarty.server.plugins.FirebaseUserPrincipal
import com.example.smarty.server.services.OrchestratorService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Routes for Orchestrator Service (Request routing decision)
 */
fun Application.configureOrchestratorRoutes(orchestratorService: OrchestratorService) {
    val logger = LoggerFactory.getLogger("OrchestratorRoutes")

    routing {
        authenticate("firebase") {
            route("/api/v1/orchestrator") {
                // Decide how to route a request
                post("/decide") {
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@post call.respond(HttpStatusCode.Unauthorized)

                    try {
                        val request = call.receive<OrchestratorDecisionRequest>()

                        val action = orchestratorService.decideAction(request.query, request.hasAttachments)

                        call.respond(
                            OrchestratorDecisionResponse(
                                action = action.action.name,
                                reasoning = action.reasoning,
                            ),
                        )
                    } catch (e: Exception) {
                        logger.error("Orchestrator decision failed", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to make decision")
                    }
                }
            }
        }
    }
}

@Serializable
data class OrchestratorDecisionRequest(
    val query: String,
    val hasAttachments: Boolean = false,
)

@Serializable
data class OrchestratorDecisionResponse(
    val action: String,
    val reasoning: String,
)
