package com.example.smarty.server.utils

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import com.example.smarty.server.plugins.FirebaseUserPrincipal
import com.example.smarty.server.plugins.firebaseUser
import org.slf4j.LoggerFactory

/**
 * Authentication helper for consistent auth checks across routes.
 * 
 * This utility eliminates duplication of authentication checks
 * by providing a standardized way to verify and extract user information.
 * 
 * Usage:
 * ```
 * val authResult = call.authenticateUser()
 * if (!authResult.success) return@post
 * val userId = authResult.userId
 * ```
 */
class AuthResult private constructor(
    val success: Boolean,
    val userId: String? = null,
    val user: FirebaseUserPrincipal? = null,
    val error: String? = null
) {
    companion object {
        fun success(userId: String, user: FirebaseUserPrincipal): AuthResult =
            AuthResult(success = true, userId = userId, user = user)
        
        fun failure(error: String): AuthResult =
            AuthResult(success = false, error = error)
    }

    val isAuthenticated: Boolean get() = success
}

/**
 * Authenticate the current call and return an AuthResult.
 * 
 * @param respondUnauthorized If true, automatically responds with 401 on failure
 * @return AuthResult with user information or error
 */
suspend fun ApplicationCall.authenticateUser(
    respondUnauthorized: Boolean = true
): AuthResult {
    val logger = LoggerFactory.getLogger("AuthHelper")
    
    try {
        val user = this.firebaseUser()
        
        if (user == null) {
            logger.warn("Authentication failed: No user principal found")
            
            if (respondUnauthorized) {
                this.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf(
                        "error" to "Authentication required",
                        "code" to "UNAUTHORIZED"
                    )
                )
            }
            
            return AuthResult.failure("Authentication required")
        }
        
        logger.debug("User authenticated: userId={}", user.userId)
        return AuthResult.success(user.userId, user)
        
    } catch (e: Exception) {
        logger.error("Authentication failed: {}", e.message)
        
        if (respondUnauthorized) {
            this.respond(
                HttpStatusCode.Unauthorized,
                mapOf(
                    "error" to "Invalid authentication token",
                    "code" to "INVALID_TOKEN"
                )
            )
        }
        
        return AuthResult.failure("Invalid authentication token")
    }
}

/**
 * Get the authenticated user or throw an exception.
 * Use this when authentication is required and you want to fail fast.
 *
 * @throws UnauthorizedException if not authenticated
 */
suspend fun ApplicationCall.requireAuthenticatedUser(): FirebaseUserPrincipal {
    val user = this.firebaseUser()

    if (user == null) {
        throw UnauthorizedException("Authentication required")
    }

    return user
}

/**
 * Error response helper for consistent error formatting.
 */
suspend fun ApplicationCall.respondError(
    statusCode: HttpStatusCode = HttpStatusCode.InternalServerError,
    message: String,
    code: String? = null,
    details: Map<String, Any?>? = null
) {
    val response = mutableMapOf<String, Any?>(
        "error" to message
    )
    
    code?.let { response["code"] = it }
    details?.let { response["details"] = it }
    
    this.respond(statusCode, response)
}

/**
 * Success response helper for consistent success formatting.
 */
suspend fun <T> ApplicationCall.respondSuccess(data: T) {
    this.respond(HttpStatusCode.OK, mapOf("success" to true, "data" to data))
}

/**
 * Standard error response format for API endpoints.
 */
@Serializable
data class ErrorResponse(
    val error: String,
    val code: String? = null,
    val details: Map<String, @Contextual Any?>? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun internalError(message: String): ErrorResponse =
            ErrorResponse(error = message, code = "INTERNAL_ERROR")
        
        fun badRequest(message: String): ErrorResponse =
            ErrorResponse(error = message, code = "BAD_REQUEST")
        
        fun unauthorized(message: String): ErrorResponse =
            ErrorResponse(error = message, code = "UNAUTHORIZED")
        
        fun notFound(message: String): ErrorResponse =
            ErrorResponse(error = message, code = "NOT_FOUND")
    }
}
