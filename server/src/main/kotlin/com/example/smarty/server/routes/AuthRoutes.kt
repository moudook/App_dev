package com.example.smarty.server.routes

import com.example.smarty.server.plugins.verifyFirebaseToken
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureAuthRoutes() {
    routing {
        post("/auth/verify") {
            // Get the Bearer token from the Authorization header
            val authHeader = call.request.authorization()
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing or invalid Authorization header"))
                return@post
            }

            val token = authHeader.removePrefix("Bearer ")
            val deviceId = call.request.header("X-Smarty-Device-Id")

            try {
                // Verify the token and get the user
                val user = verifyFirebaseToken(token, deviceId)
                if (user != null) {
                    call.respond(HttpStatusCode.OK, mapOf(
                        "userId" to user.userId,
                        "email" to user.email,
                        "displayName" to user.displayName
                    ))
                } else {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                }
            } catch (e: IllegalStateException) {
                if (e.message?.contains("There is already an existing user") == true) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "There is already an existing user. Please use that Google account."))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Server error")))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication failed: ${e.message}"))
            }
        }
    }
}
