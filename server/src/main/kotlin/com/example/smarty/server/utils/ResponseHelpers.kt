package com.example.smarty.server.utils

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

/**
 * Response Helpers for standardized API responses.
 *
 * Single Responsibility: Only handles response formatting.
 * DRY: Replaces repeated response patterns across all route files.
 *
 * Usage:
 * ```
 * // Success response
 * call.respondSuccess(data)
 *
 * // Error response
 * call.respondError(HttpStatusCode.BadRequest, "Invalid input")
 *
 * // Specific error types
 * call.respondBadRequest("Missing required field")
 * call.respondNotFound("Resource not found")
 * call.respondInternalServerError("Database error")
 * ```
 */

/**
 * Respond with a success message and optional data.
 */
suspend fun ApplicationCall.respondSuccess(data: Any? = null) {
    if (data != null) {
        respond(HttpStatusCode.OK, data)
    } else {
        respond(HttpStatusCode.OK, mapOf("success" to true))
    }
}

/**
 * Respond with an error message and status code.
 */
suspend fun ApplicationCall.respondError(
    statusCode: HttpStatusCode = HttpStatusCode.InternalServerError,
    message: String,
    details: Map<String, Any?>? = null,
) {
    val response =
        buildMap {
            put("error", message)
            if (details != null) {
                put("details", details)
            }
        }
    respond(statusCode, response)
}

/**
 * Respond with a bad request error (400).
 */
suspend fun ApplicationCall.respondBadRequest(message: String) {
    respondError(HttpStatusCode.BadRequest, message)
}

/**
 * Respond with a not found error (404).
 */
suspend fun ApplicationCall.respondNotFound(message: String = "Not found") {
    respondError(HttpStatusCode.NotFound, message)
}

/**
 * Respond with a conflict error (409).
 */
suspend fun ApplicationCall.respondConflict(message: String) {
    respondError(HttpStatusCode.Conflict, message)
}

/**
 * Respond with an internal server error (500).
 */
suspend fun ApplicationCall.respondInternalServerError(message: String = "Internal server error") {
    respondError(HttpStatusCode.InternalServerError, message)
}

/**
 * Respond with a service unavailable error (503).
 */
suspend fun ApplicationCall.respondServiceUnavailable(message: String = "Service unavailable") {
    respondError(HttpStatusCode.ServiceUnavailable, message)
}

/**
 * Respond with validation errors.
 */
suspend fun ApplicationCall.respondValidationErrors(errors: Map<String, String>) {
    respondError(HttpStatusCode.BadRequest, "Validation failed", mapOf("validation_errors" to errors))
}

/**
 * Respond with a created resource (201).
 */
suspend fun ApplicationCall.respondCreated(
    resource: Any,
    location: String? = null,
) {
    if (location != null) {
        response.headers.append(HttpHeaders.Location, location)
    }
    respond(HttpStatusCode.Created, resource)
}

/**
 * Respond with accepted for async operations (202).
 */
suspend fun ApplicationCall.respondAccepted(
    message: String = "Request accepted",
    jobId: String? = null,
) {
    val response =
        buildMap {
            put("status", "accepted")
            put("message", message)
            if (jobId != null) {
                put("job_id", jobId)
            }
        }
    respond(HttpStatusCode.Accepted, response)
}
