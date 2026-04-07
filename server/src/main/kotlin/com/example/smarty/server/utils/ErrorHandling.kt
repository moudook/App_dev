package com.example.smarty.server.utils

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Exception thrown when authentication fails.
 */
class UnauthorizedException(message: String) : Exception(message)

/**
 * Error handling wrapper for consistent error responses across routes.
 *
 * This utility eliminates duplication of try-catch blocks by providing
 * a standardized way to handle errors with consistent logging and responses.
 *
 * Usage:
 * ```
 * handleRouteErrors(call, "Operation failed") {
 *     // Business logic here
 *     performOperation()
 * }
 * ```
 */

/**
 * Handle errors in a route handler with consistent logging and response.
 *
 * @param call The application call
 * @param errorMessage Base error message for logging
 * @param statusCode HTTP status code for errors (default: 500)
 * @param includeStackTrace If true, include stack trace in response (development only)
 * @param block The business logic to execute
 */
suspend fun <T> handleRouteErrors(
    call: ApplicationCall,
    errorMessage: String,
    statusCode: HttpStatusCode = HttpStatusCode.InternalServerError,
    includeStackTrace: Boolean = false,
    block: suspend () -> T,
): T? {
    val logger = LoggerFactory.getLogger("ErrorHandler")
    val requestId = call.request.headers["X-Request-ID"] ?: java.util.UUID.randomUUID().toString()

    try {
        logger.info(
            "Request started: {} (ID: {})",
            call.request.uri,
            requestId,
        )

        val result = block()

        logger.info(
            "Request completed: {} (ID: {}, Status: OK)",
            call.request.uri,
            requestId,
        )

        return result
    } catch (e: UnauthorizedException) {
        logger.warn("Authentication failed: {} (ID: {})", e.message, requestId)

        call.respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse.unauthorized(e.message ?: "Authentication required"),
        )
        return null
    } catch (e: IllegalArgumentException) {
        logger.warn("Bad request: {} (ID: {})", e.message, requestId)

        call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse.badRequest(e.message ?: "Invalid request"),
        )
        return null
    } catch (e: IllegalAccessException) {
        logger.warn("Access denied: {} (ID: {})", e.message, requestId)

        call.respond(
            HttpStatusCode.Forbidden,
            ErrorResponse(error = e.message ?: "Access denied", code = "FORBIDDEN"),
        )
        return null
    } catch (e: ResourceNotFoundException) {
        logger.debug("Resource not found: {} (ID: {})", e.message, requestId)

        call.respond(
            HttpStatusCode.NotFound,
            ErrorResponse.notFound(e.message ?: "Resource not found"),
        )
        return null
    } catch (e: Exception) {
        logger.error(
            "{}: {} (ID: {})",
            errorMessage,
            e.message,
            requestId,
            e,
        )

        val errorResponse =
            if (includeStackTrace) {
                ErrorResponse(
                    error = errorMessage,
                    code = "INTERNAL_ERROR",
                    details =
                        mapOf(
                            "stackTrace" to e.stackTraceToString(),
                            "requestId" to requestId,
                        ),
                )
            } else {
                ErrorResponse(
                    error = "An internal error occurred",
                    code = "INTERNAL_ERROR",
                    details = mapOf("requestId" to requestId),
                )
            }

        call.respond(statusCode, errorResponse)
        return null
    }
}

/**
 * Handle errors and return a nullable result.
 * Similar to handleRouteErrors but designed for use inside route blocks.
 */
suspend fun <T> safeExecute(
    logger: org.slf4j.Logger,
    errorMessage: String,
    block: suspend () -> T,
): T? {
    return try {
        block()
    } catch (e: UnauthorizedException) {
        logger.warn("Authentication failed: {}", e.message)
        throw e
    } catch (e: IllegalArgumentException) {
        logger.warn("Bad request: {}", e.message)
        throw e
    } catch (e: IllegalAccessException) {
        logger.warn("Access denied: {}", e.message)
        throw e
    } catch (e: ResourceNotFoundException) {
        logger.debug("Resource not found: {}", e.message)
        throw e
    } catch (e: Exception) {
        logger.error(errorMessage, e)
        throw e
    }
}

/**
 * Exception for resource not found errors.
 */
class ResourceNotFoundException(message: String) : Exception(message)

/**
 * Validate a condition and throw IllegalArgumentException if false.
 */
fun requireValid(
    condition: Boolean,
    message: String,
) {
    if (!condition) {
        throw IllegalArgumentException(message)
    }
}

/**
 * Validate a condition and throw ResourceNotFoundException if false.
 */
fun requireResource(
    condition: Boolean,
    message: String,
) {
    if (!condition) {
        throw ResourceNotFoundException(message)
    }
}

/**
 * Validate a condition and throw UnauthorizedException if false.
 */
fun requireAuth(
    condition: Boolean,
    message: String,
) {
    if (!condition) {
        throw UnauthorizedException(message)
    }
}

/**
 * Standard API response wrapper.
 */
@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ErrorResponse? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    companion object {
        fun <T> ok(data: T): ApiResponse<T> = ApiResponse(success = true, data = data)

        fun <T> error(error: ErrorResponse): ApiResponse<T> = ApiResponse(success = false, error = error)
    }
}
