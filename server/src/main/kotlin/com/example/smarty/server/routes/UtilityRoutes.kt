package com.example.smarty.server.routes

import com.example.smarty.server.plugins.FirebaseUserPrincipal
import com.example.smarty.server.services.UtilityService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/**
 * Utility Routes
 * API endpoints for utility services like date parsing, categorization, etc.
 */
fun Application.configureUtilityRoutes(utilityService: UtilityService) {
    routing {
        authenticate("firebase") {
            route("/api/utility") {

                /**
                 * Extract date/time from natural language query.
                 * POST /api/utility/extract-datetime
                 * Body: { "query": "remind me in 2 hours", "timezone": "America/New_York" }
                 */
                post("/extract-datetime") {
                    val user = call.principal<FirebaseUserPrincipal>()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, "User not authenticated")

                    try {
                        val request = call.receive<ExtractDateTimeRequest>()
                        val result = utilityService.extractDateTime(
                            query = request.query,
                            userTimezone = request.timezone ?: "UTC"
                        )

                        if (result != null) {
                            call.respond(ExtractDateTimeResponse(
                                success = true,
                                dateTime = result
                            ))
                        } else {
                            call.respond(HttpStatusCode.NotFound, "No date/time found in query")
                        }
                    } catch (e: Exception) {
                        call.application.log.error("Date/time extraction failed", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to extract date/time: ${e.message}")
                    }
                }

                /**
                 * Categorize content (note, task, etc.)
                 * POST /api/utility/categorize
                 * Body: { "content": "Buy milk and eggs" }
                 */
                post("/categorize") {
                    val user = call.principal<FirebaseUserPrincipal>()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, "User not authenticated")

                    try {
                        val request = call.receive<CategorizeRequest>()
                        val category = utilityService.categorize(request.content)

                        call.respond(CategorizeResponse(
                            success = true,
                            category = category
                        ))
                    } catch (e: Exception) {
                        call.application.log.error("Categorization failed", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to categorize: ${e.message}")
                    }
                }
            }
        }
    }
}

// ==================== REQUEST/RESPONSE DATA CLASSES ====================

@Serializable
data class ExtractDateTimeRequest(
    val query: String,
    val timezone: String? = null
)

@Serializable
data class ExtractDateTimeResponse(
    val success: Boolean,
    val dateTime: String
)

@Serializable
data class CategorizeRequest(
    val content: String
)

@Serializable
data class CategorizeResponse(
    val success: Boolean,
    val category: String
)
