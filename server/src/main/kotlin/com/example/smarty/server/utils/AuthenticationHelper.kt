package com.example.smarty.server.utils

import com.example.smarty.server.services.FirebaseUserPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

/**
 * Authentication Helper for Routes.
 * 
 * Single Responsibility: Only handles authentication logic.
 * DRY: Replaces repeated authentication checks in 8+ route files.
 * 
 * Usage:
 * ```
 * // In a route handler
 * val userId = AuthenticationHelper.requireUserId(call)
 * 
 * // Or with block
 * AuthenticationHelper.withAuthenticatedUser(call) { user ->
 *     // User is guaranteed to be non-null
 *     val userId = user.userId
 * }
 * ```
 */
object AuthenticationHelper {
    
    /**
     * Get the authenticated user from the call.
     * Throws UnauthorizedException if not authenticated.
     */
    suspend fun requireAuthenticatedUser(call: ApplicationCall): FirebaseUserPrincipal {
        val user = call.firebaseUser()
            ?: throw UnauthorizedException("Authentication required")
        return user
    }
    
    /**
     * Get the user ID from the authenticated call.
     * Throws UnauthorizedException if not authenticated.
     */
    suspend fun requireUserId(call: ApplicationCall): String {
        val user = requireAuthenticatedUser(call)
        return user.userId
    }
    
    /**
     * Execute a block with the authenticated user.
     * Throws UnauthorizedException if not authenticated.
     */
    suspend inline fun <T> withAuthenticatedUser(
        call: ApplicationCall,
        block: (FirebaseUserPrincipal) -> T
    ): T {
        val user = requireAuthenticatedUser(call)
        return block(user)
    }
    
    /**
     * Check if the call is authenticated.
     * Returns true if authenticated, false otherwise.
     */
    suspend fun isAuthenticated(call: ApplicationCall): Boolean {
        return call.firebaseUser() != null
    }
    
    /**
     * Get the user ID if authenticated, null otherwise.
     */
    suspend fun getUserIdOrNull(call: ApplicationCall): String? {
        return call.firebaseUser()?.userId
    }
}

/**
 * Exception thrown when authentication is required but not provided.
 */
class UnauthorizedException(message: String) : Exception(message)

/**
 * Extension function for responding with unauthorized error.
 */
suspend fun ApplicationCall.respondUnauthorized(message: String = "Unauthorized") {
    respond(HttpStatusCode.Unauthorized, mapOf("error" to message))
}

/**
 * Extension function for responding with forbidden error.
 */
suspend fun ApplicationCall.respondForbidden(message: String = "Forbidden") {
    respond(HttpStatusCode.Forbidden, mapOf("error" to message))
}
