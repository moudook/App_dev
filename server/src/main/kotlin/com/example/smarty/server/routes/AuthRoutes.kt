package com.example.smarty.server.routes

import com.example.smarty.server.plugins.isAdminEmail
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureAuthRoutes() {
    routing {
        post("/auth/verify") {
            // AUTH DISABLED — always returns 200 with a stub principal. See AGENTS.md "Auth State".
            val authHeader = call.request.header(HttpHeaders.Authorization)
            val token = authHeader?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrBlank()) {
                // AUTH DISABLED — accept even no-token requests for testing.
                // Original: call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing or invalid token"))
            }
            val deviceId = call.request.header("X-Smarty-Device-Id")

            // AUTH DISABLED — skip verifyFirebaseToken and ADMIN_EMAIL whitelist, return OK with stub.
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "userId" to "anonymous",
                    "email" to "forpblcusz@gmail.com",
                    "displayName" to "Auth Disabled",
                ),
            )

            // ---- ORIGINAL CODE (COMMENTED OUT) ----
            /*
            try {
                val user = verifyFirebaseToken(token, deviceId)
                if (user != null) {
                    if (!isAdminEmail(user.email)) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf("error" to "Access denied. This server is restricted to the owner's account."),
                        )
                        return@post
                    }
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "userId" to user.userId,
                            "email" to user.email,
                            "displayName" to user.displayName,
                        ),
                    )
                } else {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                }
            } catch (e: IllegalStateException) {
                if (e.message?.contains("There is already an existing user") == true) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf(
                            "error" to "There is already an existing user. Please use that Google account.",
                        ),
                    )
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Server error")))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication failed: ${e.message}"))
            }
            */
        }
    }
}
