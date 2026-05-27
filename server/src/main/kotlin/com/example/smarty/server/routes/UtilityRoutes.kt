package com.example.smarty.server.routes

import com.example.smarty.server.plugins.FirebaseUserPrincipal
import com.example.smarty.server.services.UtilityService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
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
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@post call.respond(HttpStatusCode.Unauthorized, "User not authenticated")

                    try {
                        val request = call.receive<ExtractDateTimeRequest>()

                        // Input validation
                        com.example.smarty.server.utils.InputValidation
                            .validateQuery(request.query)
                        request.timezone?.let {
                            if (it.length > 50) throw IllegalArgumentException("Timezone too long")
                        }

                        val result =
                            utilityService.extractDateTime(
                                query = request.query,
                                userTimezone = request.timezone ?: "UTC",
                            )

                        if (result != null) {
                            call.respond(
                                ExtractDateTimeResponse(
                                    success = true,
                                    dateTime = result,
                                ),
                            )
                        } else {
                            call.respond(HttpStatusCode.NotFound, "No date/time found in query")
                        }
                    } catch (e: Exception) {
                        call.application.log.error("Date/time extraction failed", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to extract date/time")
                    }
                }

                /**
                 * Categorize content (note, task, etc.)
                 * POST /api/utility/categorize
                 * Body: { "content": "Buy milk and eggs" }
                 */
                post("/categorize") {
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@post call.respond(HttpStatusCode.Unauthorized, "User not authenticated")

                    try {
                        val request = call.receive<CategorizeRequest>()

                        // Input validation
                        com.example.smarty.server.utils.InputValidation
                            .validateContent(request.content)

                        val category = utilityService.categorize(request.content)

                        call.respond(
                            CategorizeResponse(
                                success = true,
                                category = category,
                            ),
                        )
                    } catch (e: Exception) {
                        call.application.log.error("Categorization failed", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to categorize")
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
    val timezone: String? = null,
)

@Serializable
data class ExtractDateTimeResponse(
    val success: Boolean,
    val dateTime: String,
)

@Serializable
data class CategorizeRequest(
    val content: String,
)

@Serializable
data class CategorizeResponse(
    val success: Boolean,
    val category: String,
)
