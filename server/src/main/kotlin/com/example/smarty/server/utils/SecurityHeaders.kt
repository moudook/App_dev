package com.example.smarty.server.utils

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

/**
 * Security Headers Utility.
 *
 * Single Responsibility: Only handles security header management.
 * Security: Prevents XSS, clickjacking, MIME sniffing, and other attacks.
 *
 * Usage:
 * ```
 * call.applySecurityHeaders()
 * SecurityHeaders.addCommonHeaders(call)
 * ```
 */
object SecurityHeaders {
    /**
     * Apply all security headers to a response.
     */
    suspend fun applySecurityHeaders(call: ApplicationCall) {
        // Prevent MIME type sniffing
        call.response.headers.append("X-Content-Type-Options", "nosniff")

        // Prevent clickjacking
        call.response.headers.append("X-Frame-Options", "DENY")

        // Enable XSS filter
        call.response.headers.append("X-XSS-Protection", "1; mode=block")

        // Enforce HTTPS
        call.response.headers.append(
            "Strict-Transport-Security",
            "max-age=31536000; includeSubDomains; preload",
        )

        // Control referrer information
        call.response.headers.append("Referrer-Policy", "strict-origin-when-cross-origin")

        // Content Security Policy
        call.response.headers.append(
            "Content-Security-Policy",
            "default-src 'self'; " +
                "script-src 'self'; " +
                "style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: https:; " +
                "font-src 'self'; " +
                "connect-src 'self' https:; " +
                "frame-ancestors 'none'",
        )

        // Permissions Policy (formerly Feature Policy)
        call.response.headers.append(
            "Permissions-Policy",
            "accelerometer=(), camera=(), geolocation=(), gyroscope=(), " +
                "magnetometer=(), microphone=(), payment=(), usb=()",
        )

        // Cache control for sensitive data
        call.response.headers.append("Cache-Control", "no-store, no-cache, must-revalidate")
        call.response.headers.append("Pragma", "no-cache")
        call.response.headers.append("Expires", "0")
    }

    /**
     * Apply CORS headers.
     */
    suspend fun applyCorsHeaders(
        call: ApplicationCall,
        allowedOrigins: List<String> = emptyList(),
    ) {
        val origin = call.request.headers["Origin"]

        if (origin != null && (allowedOrigins.isEmpty() || allowedOrigins.contains(origin))) {
            call.response.headers.append("Access-Control-Allow-Origin", origin)
            call.response.headers.append("Access-Control-Allow-Credentials", "true")
        }

        call.response.headers.append(
            "Access-Control-Allow-Headers",
            "Authorization, Content-Type, X-Smarty-Device-Id",
        )

        call.response.headers.append(
            "Access-Control-Allow-Methods",
            "GET, POST, PUT, DELETE, OPTIONS",
        )

        call.response.headers.append("Access-Control-Max-Age", "86400")
    }

    /**
     * Add common security headers (minimal set for API responses).
     */
    suspend fun addCommonHeaders(call: ApplicationCall) {
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("X-Frame-Options", "DENY")
        call.response.headers.append("X-XSS-Protection", "1; mode=block")
        call.response.headers.append("Strict-Transport-Security", "max-age=31536000")
    }

    /**
     * Add headers for file downloads.
     */
    suspend fun addDownloadHeaders(
        call: ApplicationCall,
        filename: String,
    ) {
        call.response.headers.append("Content-Disposition", "attachment; filename=\"$filename\"")
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("X-Frame-Options", "DENY")
    }

    /**
     * Add headers for JSON API responses.
     */
    suspend fun addJsonHeaders(call: ApplicationCall) {
        call.response.headers.append("Content-Type", "application/json")
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        addCommonHeaders(call)
    }
}

/**
 * Extension function to apply security headers easily.
 */
suspend fun ApplicationCall.applySecurityHeaders() {
    SecurityHeaders.applySecurityHeaders(this)
}

/**
 * Extension function to apply JSON headers.
 */
suspend fun ApplicationCall.applyJsonHeaders() {
    SecurityHeaders.addJsonHeaders(this)
}
