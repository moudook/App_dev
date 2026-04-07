package com.example.smarty.server.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory

/**
 * Firewall and security configuration.
 * Provides request validation, size limits, and optional IP restrictions.
 */

private val logger = LoggerFactory.getLogger("Firewall")

// Environment-based configuration
private val ENABLE_IP_ALLOWLIST = System.getenv("ENABLE_IP_ALLOWLIST")?.toBoolean() ?: false
private val ALLOWED_IPS =
    System.getenv("ALLOWED_IPS")?.split(",")?.map { it.trim() }?.toSet()
        ?: setOf("127.0.0.1", "0:0:0:0:0:0:0:1", "::1") // Localhost by default

// Request size limits (in bytes)
private const val MAX_BODY_SIZE = 50 * 1024 * 1024L // 50MB for file uploads
private const val MAX_QUERY_LENGTH = 50000 // Characters for query strings

// Required client headers for handshake validation
private const val CLIENT_VERSION_HEADER = "X-Smarty-Version"
private const val DEVICE_ID_HEADER = "X-Smarty-Device-Id"

/**
 * Configure firewall rules for the application.
 * This should be called early in the plugin chain.
 */
fun Application.configureFirewall() {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()

        // Skip firewall for health endpoints
        if (path == "/health" || path == "/metrics") {
            return@intercept
        }

        // 1. IP Allowlist (if enabled)
        if (ENABLE_IP_ALLOWLIST) {
            val clientIp = call.request.local.remoteHost
            if (clientIp !in ALLOWED_IPS) {
                logger.warn("Blocked request from unauthorized IP: $clientIp to $path")
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
                finish()
                return@intercept
            }
        }

        // 2. Request Size Validation
        val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: 0
        if (contentLength > MAX_BODY_SIZE) {
            logger.warn("Request too large: $contentLength bytes from ${call.request.local.remoteHost}")
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                mapOf(
                    "error" to "Request body too large",
                    "maxSize" to MAX_BODY_SIZE,
                ),
            )
            finish()
            return@intercept
        }

        // 3. Query Parameter Length Validation
        val queryLength = call.request.queryString().length
        if (queryLength > MAX_QUERY_LENGTH) {
            logger.warn("Query too long: $queryLength chars from ${call.request.local.remoteHost}")
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to "Query string too long",
                    "maxLength" to MAX_QUERY_LENGTH,
                ),
            )
            finish()
            return@intercept
        }

        // 4. Client Version Header Check (for API routes only)
        if (path.startsWith("/chat") || path.startsWith("/process") || path.startsWith("/analyze")) {
            val clientVersion = call.request.header(CLIENT_VERSION_HEADER)
            if (clientVersion == null) {
                // Log but don't block - allows gradual migration
                logger.debug("Missing $CLIENT_VERSION_HEADER header for $path")
            }
        }
    }
}

/**
 * Extension to check if a client has valid handshake headers.
 * Can be used in routes that require additional security.
 */
fun ApplicationCall.hasValidClientHandshake(): Boolean {
    val version = request.header(CLIENT_VERSION_HEADER)
    val deviceId = request.header(DEVICE_ID_HEADER)
    return !version.isNullOrBlank() && !deviceId.isNullOrBlank()
}

/**
 * Get client device ID from headers.
 */
fun ApplicationCall.clientDeviceId(): String? {
    return request.header(DEVICE_ID_HEADER)
}

/**
 * Get client version from headers.
 */
fun ApplicationCall.clientVersion(): String? {
    return request.header(CLIENT_VERSION_HEADER)
}
