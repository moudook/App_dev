package com.example.smarty.server.routes

import com.example.smarty.server.data.DigestPreferencesRepository
import com.example.smarty.server.services.DigestService
import com.example.smarty.server.services.DigestScheduler
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Routes for digest management.
 *
 * Endpoints:
 * - GET /digests - Get all digests for user
 * - GET /digests/{id} - Get specific digest
 * - POST /digests/trigger - Manually trigger digest generation
 * - GET /digests/preferences - Get user preferences
 * - PUT /digests/preferences - Update user preferences
 */
fun Route.configureDigestRoutes(
    digestService: DigestService,
    digestScheduler: DigestScheduler,
    dataSource: javax.sql.DataSource
) {
    val logger = LoggerFactory.getLogger("DigestRoutes")
    val preferencesRepository = DigestPreferencesRepository(dataSource)

    route("/digests") {
        authenticate("firebase-auth") {
            // Get all digests for user
            get {
                val userId = call.principal<UserIdPrincipal>()?.name
                    ?: return@get call.respond(mapOf("error" to "Unauthorized"))

                try {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 30
                    val digests = digestService.getDigestsForUser(userId, limit)
                    call.respond(mapOf("digests" to digests))
                } catch (e: Exception) {
                    logger.error("Failed to get digests: ${e.message}", e)
                    call.respond(mapOf("error" to "Failed to get digests"))
                }
            }

            // Get specific digest
            get("/{id}") {
                val userId = call.principal<UserIdPrincipal>()?.name
                    ?: return@get call.respond(mapOf("error" to "Unauthorized"))

                val digestId = call.parameters["id"]
                    ?: return@get call.respond(mapOf("error" to "Missing digest ID"))

                try {
                    val digest = digestService.getDigestById(userId, digestId)
                    if (digest != null) {
                        call.respond(digest)
                    } else {
                        call.respond(mapOf("error" to "Digest not found"))
                    }
                } catch (e: Exception) {
                    logger.error("Failed to get digest: ${e.message}", e)
                    call.respond(mapOf("error" to "Failed to get digest"))
                }
            }

            // Manually trigger digest generation
            post("/trigger") {
                val userId = call.principal<UserIdPrincipal>()?.name
                    ?: return@post call.respond(mapOf("error" to "Unauthorized"))

                try {
                    val request = call.receive<TriggerDigestRequest>()
                    val type = request.type ?: "daily"

                    val result = digestScheduler.triggerDigestForUser(userId, type)
                    if (result != null) {
                        call.respond(mapOf(
                            "success" to true,
                            "digest" to result
                        ))
                    } else {
                        call.respond(mapOf(
                            "success" to false,
                            "message" to "No activity found to generate digest"
                        ))
                    }
                } catch (e: Exception) {
                    logger.error("Failed to trigger digest: ${e.message}", e)
                    call.respond(mapOf("error" to "Failed to trigger digest"))
                }
            }

            // Get user preferences
            get("/preferences") {
                val userId = call.principal<UserIdPrincipal>()?.name
                    ?: return@get call.respond(mapOf("error" to "Unauthorized"))

                try {
                    val prefs = preferencesRepository.getPreferences(userId)
                        ?: DigestPreferencesRepository.DigestPreferences(
                            userId = userId,
                            dailyEnabled = true,
                            dailyTime = "07:00",
                            weeklyEnabled = true,
                            weeklyDay = 0,
                            weeklyTime = "08:00",
                            pushNotification = true,
                            calendarLogging = true
                        )
                    call.respond(DigestPreferencesResponse(
                        dailyEnabled = prefs.dailyEnabled,
                        dailyTime = prefs.dailyTime,
                        weeklyEnabled = prefs.weeklyEnabled,
                        weeklyDay = prefs.weeklyDay,
                        weeklyTime = prefs.weeklyTime,
                        pushNotification = prefs.pushNotification,
                        calendarLogging = prefs.calendarLogging
                    ))
                } catch (e: Exception) {
                    logger.error("Failed to get preferences: ${e.message}", e)
                    call.respond(mapOf("error" to "Failed to get preferences"))
                }
            }

            // Update user preferences
            put("/preferences") {
                val userId = call.principal<UserIdPrincipal>()?.name
                    ?: return@put call.respond(mapOf("error" to "Unauthorized"))

                try {
                    val request = call.receive<UpdatePreferencesRequest>()
                    preferencesRepository.updatePreferences(
                        userId = userId,
                        dailyEnabled = request.dailyEnabled,
                        dailyTime = request.dailyTime,
                        weeklyEnabled = request.weeklyEnabled,
                        weeklyDay = request.weeklyDay,
                        weeklyTime = request.weeklyTime,
                        pushNotification = request.pushNotification,
                        calendarLogging = request.calendarLogging
                    )
                    call.respond(mapOf("success" to true))
                } catch (e: Exception) {
                    logger.error("Failed to update preferences: ${e.message}", e)
                    call.respond(mapOf("error" to "Failed to update preferences"))
                }
            }
        }
    }
}

@Serializable
data class TriggerDigestRequest(
    val type: String? = null // "daily" or "weekly"
)

@Serializable
data class UpdatePreferencesRequest(
    val dailyEnabled: Boolean? = null,
    val dailyTime: String? = null,      // "HH:mm" format
    val weeklyEnabled: Boolean? = null,
    val weeklyDay: Int? = null,          // 0=Sunday, 1=Monday, etc.
    val weeklyTime: String? = null,
    val pushNotification: Boolean? = null,
    val calendarLogging: Boolean? = null
)

@Serializable
data class DigestPreferencesResponse(
    val dailyEnabled: Boolean,
    val dailyTime: String,
    val weeklyEnabled: Boolean,
    val weeklyDay: Int,
    val weeklyTime: String,
    val pushNotification: Boolean,
    val calendarLogging: Boolean
)
