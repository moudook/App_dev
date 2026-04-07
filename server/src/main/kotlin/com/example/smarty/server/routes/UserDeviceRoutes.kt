package com.example.smarty.server.routes

import com.example.smarty.server.data.UserDevice
import com.example.smarty.server.data.UserDeviceRepository
import com.example.smarty.server.plugins.FirebaseUserPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * User Device Routes
 * API endpoints for managing user devices and push tokens
 */
fun Application.configureUserDeviceRoutes(userDeviceRepository: UserDeviceRepository) {
    routing {
        authenticate("firebase") {
            route("/api/devices") {
                /**
                 * Register/update a device
                 * POST /api/devices/register
                 * Body: { "deviceName": "Pixel 8", "deviceType": "android", "pushToken": "...", "appVersion": "6.0.0" }
                 */
                post("/register") {
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@post call.respond(HttpStatusCode.Unauthorized, "User not authenticated")

                    try {
                        val request = call.receive<RegisterDeviceRequest>()

                        if (request.deviceName.isNullOrBlank()) {
                            return@post call.respond(HttpStatusCode.BadRequest, "Device name is required")
                        }

                        val device =
                            UserDevice(
                                id = UUID.randomUUID().toString(),
                                userId = user.userId,
                                deviceName = request.deviceName,
                                deviceType = request.deviceType ?: "android",
                                pushToken = request.pushToken,
                                appVersion = request.appVersion,
                            )

                        val id = userDeviceRepository.registerDevice(device)

                        call.respond(
                            RegisterDeviceResponse(
                                success = true,
                                deviceId = id,
                                message = "Device registered successfully",
                            ),
                        )
                    } catch (e: Exception) {
                        call.application.log.error("Failed to register device", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to register device: ${e.message}")
                    }
                }

                /**
                 * Update device push token
                 * POST /api/devices/{deviceId}/token
                 * Body: { "pushToken": "..." }
                 */
                post("/{deviceId}/token") {
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@post call.respond(HttpStatusCode.Unauthorized, "User not authenticated")

                    val deviceId =
                        call.parameters["deviceId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, "Device ID required")

                    try {
                        val request = call.receive<UpdateTokenRequest>()

                        if (request.pushToken.isNullOrBlank()) {
                            return@post call.respond(HttpStatusCode.BadRequest, "Push token is required")
                        }

                        // Re-register with updated token
                        val device =
                            UserDevice(
                                id = deviceId,
                                userId = user.userId,
                                deviceName = "", // Will use existing
                                deviceType = "",
                                pushToken = request.pushToken,
                                appVersion = request.appVersion,
                            )

                        userDeviceRepository.registerDevice(device)

                        call.respond(
                            UpdateTokenResponse(
                                success = true,
                                message = "Push token updated",
                            ),
                        )
                    } catch (e: Exception) {
                        call.application.log.error("Failed to update push token", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to update token: ${e.message}")
                    }
                }

                /**
                 * Get user's registered devices
                 * GET /api/devices
                 */
                get {
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@get call.respond(HttpStatusCode.Unauthorized, "User not authenticated")

                    try {
                        val devices = userDeviceRepository.getDevicesForUser(user.userId)

                        call.respond(
                            GetDevicesResponse(
                                success = true,
                                devices = devices,
                                message = "Retrieved ${devices.size} devices",
                            ),
                        )
                    } catch (e: Exception) {
                        call.application.log.error("Failed to get devices", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to get devices: ${e.message}")
                    }
                }

                /**
                 * Unregister a device
                 * DELETE /api/devices/{deviceId}
                 */
                delete("/{deviceId}") {
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@delete call.respond(HttpStatusCode.Unauthorized, "User not authenticated")

                    val deviceId =
                        call.parameters["deviceId"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, "Device ID required")

                    try {
                        val deleted = userDeviceRepository.deleteDevice(deviceId)

                        if (deleted) {
                            call.respond(
                                DeleteDeviceResponse(
                                    success = true,
                                    message = "Device unregistered",
                                ),
                            )
                        } else {
                            call.respond(HttpStatusCode.NotFound, "Device not found")
                        }
                    } catch (e: Exception) {
                        call.application.log.error("Failed to delete device", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to delete device: ${e.message}")
                    }
                }
            }
        }
    }
}

// ==================== REQUEST/RESPONSE DATA CLASSES ====================

@Serializable
data class RegisterDeviceRequest(
    val deviceName: String,
    val deviceType: String? = null, // ios, android, web, desktop, other
    val pushToken: String? = null,
    val appVersion: String? = null,
)

@Serializable
data class RegisterDeviceResponse(
    val success: Boolean,
    val deviceId: String,
    val message: String,
)

@Serializable
data class UpdateTokenRequest(
    val pushToken: String,
    val appVersion: String? = null,
)

@Serializable
data class UpdateTokenResponse(
    val success: Boolean,
    val message: String,
)

@Serializable
data class GetDevicesResponse(
    val success: Boolean,
    val devices: List<UserDevice>,
    val message: String,
)

@Serializable
data class DeleteDeviceResponse(
    val success: Boolean,
    val message: String,
)
