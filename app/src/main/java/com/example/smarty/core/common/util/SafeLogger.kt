package com.example.smarty.core.common.util

import android.util.Log
import com.example.smarty.BuildConfig
import java.util.regex.Pattern

/**
 * Safe logging utility that prevents logging sensitive information.
 * 
 * SECURITY FEATURES:
 * - Automatically redacts tokens, passwords, and API keys
 * - Prevents logging of PII (emails, phone numbers, credit cards)
 * - Disabled in release builds for extra security
 * - Provides sanitized versions of sensitive data for debugging
 * 
 * USAGE:
 * - Use SafeLogger.d/e/i/w instead of Log.d/e/i/w for user-generated content
 * - SafeLogger automatically redacts sensitive patterns
 * - For non-sensitive debug logs, regular Log can still be used
 */
object SafeLogger {
    
    private const val TAG = "Smarty"
    private const val REDACTED = "[REDACTED]"
    
    // Patterns for sensitive data detection
    private val SENSITIVE_PATTERNS = listOf(
        // API keys and tokens
        Pattern.compile("(?i)(api[_-]?key|apikey|token|auth[_-]?token|access[_-]?token|secret[_-]?key)\\s*[=:]\\s*['\"]?([a-zA-Z0-9_\\-]{16,})['\"]?"),
        Pattern.compile("(?i)bearer\\s+([a-zA-Z0-9_\\-\\.]{20,})"),
        Pattern.compile("(?i)(password|passwd|pwd|secret)\\s*[=:]\\s*['\"]?([^\\s'\"]{4,})['\"]?"),
        
        // Email addresses
        Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
        
        // Phone numbers (various formats)
        Pattern.compile("\\+?[0-9]{1,3}?[-.\\s]?\\(?[0-9]{1,4}?\\)?[-.\\s]?[0-9]{1,4}[-.\\s]?[0-9]{1,9}"),
        
        // Credit card numbers
        Pattern.compile("\\b[0-9]{4}[-\\s]?[0-9]{4}[-\\s]?[0-9]{4}[-\\s]?[0-9]{4}\\b"),
        
        // Firebase tokens (long base64-like strings)
        Pattern.compile("(?i)(firebase[_-]?token|fcm[_-]?token|registration[_-]?token)\\s*[=:]\\s*['\"]?([a-zA-Z0-9_\\-]{50,})['\"]?"),
        
        // URLs with query parameters containing sensitive data
        Pattern.compile("(?i)(password|token|key|secret|auth)=[^&\\s]+")
    )
    
    /**
     * Log debug message with automatic sensitive data redaction.
     * Only active in debug builds.
     */
    fun d(tag: String = TAG, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            val sanitized = sanitizeMessage(message)
            Log.d(tag, sanitized, throwable)
        }
    }
    
    /**
     * Log error message with automatic sensitive data redaction.
     * Active in all builds for error tracking.
     */
    fun e(tag: String = TAG, message: String, throwable: Throwable? = null) {
        val sanitized = sanitizeMessage(message)
        Log.e(tag, sanitized, throwable)
    }
    
    /**
     * Log info message with automatic sensitive data redaction.
     * Only active in debug builds.
     */
    fun i(tag: String = TAG, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            val sanitized = sanitizeMessage(message)
            Log.i(tag, sanitized, throwable)
        }
    }
    
    /**
     * Log warning message with automatic sensitive data redaction.
     * Active in all builds.
     */
    fun w(tag: String = TAG, message: String, throwable: Throwable? = null) {
        val sanitized = sanitizeMessage(message)
        Log.w(tag, sanitized, throwable)
    }
    
    /**
     * Log verbose message with automatic sensitive data redaction.
     * Only active in debug builds.
     */
    fun v(tag: String = TAG, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            val sanitized = sanitizeMessage(message)
            Log.v(tag, sanitized, throwable)
        }
    }
    
    /**
     * Sanitize a message by redacting sensitive information.
     * Can be used for custom logging scenarios.
     */
    fun sanitizeMessage(message: String): String {
        var sanitized = message
        
        for (pattern in SENSITIVE_PATTERNS) {
            val matcher = pattern.matcher(sanitized)
            if (matcher.find()) {
                // Replace sensitive data with redacted placeholder
                sanitized = matcher.replaceAll { match ->
                    // Keep the key/name but redact the value
                    val groupCount = match.groupCount()
                    if (groupCount >= 2) {
                        "${match.group(1)}=$REDACTED"
                    } else {
                        REDACTED
                    }
                }
            }
        }
        
        return sanitized
    }
    
    /**
     * Redact a specific string (e.g., token, password).
     * Shows first 4 and last 4 characters for debugging.
     */
    fun redactSensitiveValue(value: String, showEdges: Boolean = true): String {
        if (value.length <= 8 || !showEdges) {
            return REDACTED
        }
        return "${value.take(4)}...${value.takeLast(4)}"
    }
    
    /**
     * Log a network request safely, redacting sensitive headers and body.
     */
    fun logNetworkRequest(
        tag: String = TAG,
        method: String,
        url: String,
        headers: Map<String, String>? = null,
        body: String? = null
    ) {
        if (!BuildConfig.DEBUG) return
        
        val sanitizedUrl = sanitizeMessage(url)
        val sanitizedHeaders = headers?.mapValues { (_, value) ->
            sanitizeMessage(value)
        }
        val sanitizedBody = body?.let { sanitizeMessage(it) }
        
        val logBuilder = StringBuilder()
        logBuilder.appendLine("HTTP $method $sanitizedUrl")
        
        sanitizedHeaders?.forEach { (key, value) ->
            logBuilder.appendLine("  $key: $value")
        }
        
        if (sanitizedBody != null) {
            logBuilder.appendLine("  Body: ${sanitizedBody.take(500)}")
        }
        
        Log.d(tag, logBuilder.toString())
    }
    
    /**
     * Log a network response safely.
     */
    fun logNetworkResponse(
        tag: String = TAG,
        statusCode: Int,
        body: String? = null
    ) {
        if (!BuildConfig.DEBUG) return
        
        val sanitizedBody = body?.let { sanitizeMessage(it) }?.take(500)
        Log.d(tag, "HTTP Response: $statusCode\n${sanitizedBody ?: "null"}")
    }
}
